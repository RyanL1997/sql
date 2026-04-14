/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.plugin.rest;

import static org.opensearch.rest.RestRequest.Method.POST;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import lombok.extern.log4j.Log4j2;
import org.opensearch.common.annotation.ExperimentalApi;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.BytesRestResponse;
import org.opensearch.rest.RestChannel;
import org.opensearch.rest.RestRequest;
import org.opensearch.sql.ast.statement.Query;
import org.opensearch.sql.ast.statement.Statement;
import org.opensearch.sql.ast.tree.UnresolvedPlan;
import org.opensearch.sql.lint.Diagnostic;
import org.opensearch.sql.lint.QueryLinter;
import org.opensearch.sql.ppl.antlr.PPLSyntaxParser;
import org.opensearch.sql.ppl.parser.AstBuilder;
import org.opensearch.sql.ppl.parser.AstStatementBuilder;
import org.opensearch.transport.client.node.NodeClient;

/**
 * REST handler for {@code POST /_plugins/_ppl/_lint}. Parses a PPL query and returns lint
 * diagnostics without executing it.
 *
 * @opensearch.experimental
 */
@ExperimentalApi
@Log4j2
public class RestPPLLintAction extends BaseRestHandler {

  private static final String ENDPOINT_PATH = "/_plugins/_ppl/_lint";

  private final QueryLinter linter = new QueryLinter();

  @Override
  public String getName() {
    return "ppl_lint_action";
  }

  @Override
  public List<Route> routes() {
    return ImmutableList.of(new Route(POST, ENDPOINT_PATH));
  }

  @Override
  protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client)
      throws IOException {

    String queryStr = request.content().utf8ToString();
    org.json.JSONObject json = new org.json.JSONObject(queryStr);
    String pplQuery = json.getString("query");

    return channel -> {
      try {
        List<Diagnostic> diagnostics = runLint(pplQuery);
        sendLintResponse(channel, diagnostics);
      } catch (Exception e) {
        log.error("Error linting PPL query", e);
        sendErrorResponse(channel, e);
      }
    };
  }

  private List<Diagnostic> runLint(String pplQuery) {
    // Explore sends only the query body without "source = index".
    // If parsing fails, prepend a dummy source so lint rules can still run.
    String fullQuery = pplQuery;
    PPLSyntaxParser parser = new PPLSyntaxParser();
    try {
      parser.parse(fullQuery);
    } catch (Exception e) {
      fullQuery = "source = _dummy | " + pplQuery;
    }
    var cst = parser.parse(fullQuery);
    Statement statement =
        cst.accept(
            new AstStatementBuilder(
                new AstBuilder(fullQuery),
                AstStatementBuilder.StatementBuilderContext.builder()
                    .isExplain(false)
                    .build()));
    UnresolvedPlan plan = ((Query) statement).getPlan();
    return linter.lint(plan);
  }

  private void sendLintResponse(RestChannel channel, List<Diagnostic> diagnostics)
      throws IOException {
    XContentBuilder builder = channel.newBuilder();
    builder.startObject();
    builder.startArray("diagnostics");
    for (Diagnostic d : diagnostics) {
      builder.startObject();
      builder.field("severity", d.getSeverity().name().toLowerCase());
      builder.field("message", d.getMessage());
      builder.field("command", d.getCommand());
      if (d.getSuggestion() != null) {
        builder.field("suggestion", d.getSuggestion());
      }
      builder.endObject();
    }
    builder.endArray();
    builder.endObject();
    channel.sendResponse(new BytesRestResponse(RestStatus.OK, builder));
  }

  private void sendErrorResponse(RestChannel channel, Exception e) {
    try {
      channel.sendResponse(new BytesRestResponse(channel, e));
    } catch (IOException ioException) {
      log.error("Failed to send PPL lint error response", ioException);
    }
  }
}

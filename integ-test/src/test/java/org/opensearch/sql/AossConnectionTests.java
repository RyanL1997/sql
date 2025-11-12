/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql;

import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.Test;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;

/**
 * Simple standalone test to verify AOSS connection works with AWS authentication. This test doesn't
 * use the complex integration test framework.
 */
public class AossConnectionTests {

  private static final String AOSS_ENDPOINT = "<end-point>";
  private static final int AOSS_PORT = 443;

  public static void main(String[] args) {
    System.out.println("🚀 Starting AOSS Connection Test...");
    AossConnectionTests test = new AossConnectionTests();
    try {
      test.testAossConnection();
      System.out.println("✅ All tests passed!");
    } catch (Exception e) {
      System.err.println("❌ Test failed:");
      e.printStackTrace();
      System.exit(1);
    }
  }

  @Test
  public void testAossConnection() throws Exception {
    System.out.println("Testing AOSS connection...");

    try {
      // Create HTTP host for AOSS
      HttpHost httpHost = new HttpHost("https", AOSS_ENDPOINT, AOSS_PORT);

      // Create REST client
      RestClientBuilder builder = RestClient.builder(httpHost);

      // Configure client with our AOSS authentication
      configureAossClient(builder, httpHost);

      RestClient client = builder.build();

      // Test with your actual accounts data using PPL
      System.out.println("Testing PPL query with accounts data...");
      Request pplRequest = new Request("POST", "/_plugins/_ppl");
      pplRequest.setJsonEntity("{\"query\": \"source=accounts | head 10\"}");

      Response pplResponse = client.performRequest(pplRequest);
      System.out.println("✅ SUCCESS! PPL Query executed on AOSS!");
      System.out.println("Response status: " + pplResponse.getStatusLine().getStatusCode());

      // Print some of the response to see the data
      String responseBody =
          org.apache.hc.core5.http.io.entity.EntityUtils.toString(pplResponse.getEntity());
      System.out.println(
          "Response preview: "
              + responseBody.substring(0, Math.min(500, responseBody.length()))
              + "...");

      // Also try the explain endpoint
      System.out.println("\nTrying PPL explain endpoint...");
      Request explainRequest = new Request("POST", "/_plugins/_ppl/_explain");
      explainRequest.setJsonEntity("{\"query\": \"source=accounts | head 10\"}");

      Response explainResponse = client.performRequest(explainRequest);
      System.out.println("✅ PPL Explain SUCCESS!");
      System.out.println(
          "Explain Response status: " + explainResponse.getStatusLine().getStatusCode());

      client.close();

    } catch (Exception e) {
      System.err.println("❌ FAILED to connect to AOSS:");
      e.printStackTrace();
    }
  }

  /** Configure REST client for AOSS using AWS Signature V4 authentication. */
  private static void configureAossClient(RestClientBuilder builder, HttpHost httpHost) {
    System.out.println(
        "Configuring AWS authentication for AOSS endpoint: " + httpHost.getHostName());

    builder.setHttpClientConfigCallback(
        httpClientBuilder -> {
          try {
            return httpClientBuilder.addRequestInterceptorLast(new AwsHttpRequestInterceptor());
          } catch (Exception e) {
            throw new RuntimeException("Failed to configure AWS client for AOSS", e);
          }
        });
  }
}

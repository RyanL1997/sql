/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.opensearch.sql.ast.dsl.AstDSL.field;
import static org.opensearch.sql.ast.dsl.AstDSL.function;
import static org.opensearch.sql.ast.dsl.AstDSL.intLiteral;
import static org.opensearch.sql.ast.dsl.AstDSL.let;

import java.sql.Connection;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.tools.FrameworkConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.sql.ast.expression.Cast;
import org.opensearch.sql.ast.expression.UnresolvedExpression;
import org.opensearch.sql.calcite.CalcitePlanContext;
import org.opensearch.sql.calcite.SysLimit;
import org.opensearch.sql.calcite.utils.CalciteToolsHelper.OpenSearchRelBuilder;
import org.opensearch.sql.common.error.ErrorCode;
import org.opensearch.sql.common.error.ErrorReport;
import org.opensearch.sql.executor.QueryType;

/** The uses a {@code flat_object} field allows, and the refusal for everything else. */
@ExtendWith(MockitoExtension.class)
class FlatObjectScopeTest {

  @Mock private OpenSearchRelBuilder relBuilder;
  @Mock private MockedStatic<CalciteToolsHelper> mockedToolsHelper;

  private CalcitePlanContext context;

  @BeforeEach
  void setUp() {
    mockedToolsHelper
        .when(() -> CalciteToolsHelper.connect(any(), any()))
        .thenReturn(mock(Connection.class));
    mockedToolsHelper
        .when(() -> CalciteToolsHelper.create(any(), any(), any()))
        .thenReturn(relBuilder);
    when(relBuilder.getRexBuilder()).thenReturn(new RexBuilder(OpenSearchTypeFactory.TYPE_FACTORY));
    context =
        CalcitePlanContext.create(mock(FrameworkConfig.class), SysLimit.DEFAULT, QueryType.PPL);
    context.getFlatObjectFields().add("attributes");
  }

  /** What a command does when it resolves a name, with no read permission open. */
  private ErrorReport refused(String name) {
    return assertThrows(ErrorReport.class, () -> FlatObjectScope.check(name, context));
  }

  // ---- what is allowed

  @Test
  void aFieldOrALeafMayBeRead() {
    FlatObjectScope.allowRead(
        field("attributes.duration_ms"),
        "attributes.duration_ms",
        context,
        () -> {
          FlatObjectScope.check("attributes.duration_ms", context);
          return null;
        });
    FlatObjectScope.allowRead(
        field("attributes"),
        "attributes",
        context,
        () -> {
          FlatObjectScope.check("attributes", context);
          return null;
        });
  }

  /** A read may wear a cast: that is how a query asks for a leaf as a number. */
  @Test
  void aReadMayBeCast() {
    UnresolvedExpression asDouble =
        let(
            field("d"),
            new Cast(
                field("attributes.duration_ms"),
                org.opensearch.sql.ast.dsl.AstDSL.stringLiteral("double")));
    assertDoesNotThrow(
        () ->
            FlatObjectScope.allowRead(
                asDouble,
                "d",
                context,
                () -> {
                  FlatObjectScope.check("attributes.duration_ms", context);
                  return null;
                }));
  }

  /** typeof asks what a leaf is, not what it holds, so it reads nothing from the record. */
  @Test
  void typeofIsAllowed() {
    UnresolvedExpression typeOfLeaf =
        let(field("t"), function("typeof", field("attributes.duration_ms")));
    assertDoesNotThrow(
        () ->
            FlatObjectScope.allowRead(
                typeOfLeaf,
                "t",
                context,
                () -> {
                  FlatObjectScope.check("attributes.duration_ms", context);
                  return null;
                }));
  }

  @Test
  void aNameOutsideTheFieldIsNotTouched() {
    assertDoesNotThrow(() -> FlatObjectScope.check("service", context));
    assertDoesNotThrow(() -> FlatObjectScope.check("attributesX", context));
  }

  // ---- what is refused

  @Test
  void aValueComputedFromALeafIsRefused() {
    UnresolvedExpression plusOne =
        let(field("x"), function("+", field("attributes.duration_ms"), intLiteral(1)));
    ErrorReport error =
        assertThrows(
            ErrorReport.class,
            () ->
                FlatObjectScope.allowRead(
                    plusOne,
                    "x",
                    context,
                    () -> {
                      FlatObjectScope.check("attributes.duration_ms", context);
                      return null;
                    }));
    assertTrue(
        error.getMessage().contains("Cannot compute a value from attributes.duration_ms"),
        error.getMessage());
  }

  @Test
  void theCommandNamesWhatItWasDoing() {
    FlatObjectScope.runAs(
        "sort by",
        context,
        () ->
            assertTrue(
                refused("attributes.duration_ms")
                    .getMessage()
                    .contains("Cannot sort by attributes.duration_ms")));
  }

  /**
   * A command that reads a leaf into a column of its own does not escape the rule: pushdown fuses
   * the projection back into whatever reads that column.
   */
  @Test
  void aColumnReadFromALeafIsRefusedToo() {
    FlatObjectScope.allowRead(
        let(field("d"), field("attributes.duration_ms")), "d", context, () -> null);
    FlatObjectScope.runAs(
        "sort by",
        context,
        () ->
            assertTrue(
                refused("d").getMessage().contains("Cannot sort by attributes.duration_ms"),
                "the refusal names the leaf, not the column the query gave it"));
  }

  @Test
  void aRenameCarriesTheColumnForward() {
    FlatObjectScope.allowRead(
        let(field("d"), field("attributes.duration_ms")), "d", context, () -> null);
    FlatObjectScope.trackRename("d", "e", context);
    FlatObjectScope.runAs(
        "stats by",
        context,
        () ->
            assertTrue(
                refused("e").getMessage().contains("Cannot stats by attributes.duration_ms"),
                "a renamed column is still named by the leaf it was read from"));
  }

  @Test
  void theRefusalCarriesTheRuleAndTheDocumentation() {
    ErrorReport error = refused("attributes.duration_ms");
    assertEquals(ErrorCode.UNSUPPORTED_OPERATION, error.getCode());
    assertTrue(error.getSuggestion().contains("can only be read"), error.getSuggestion());
    assertTrue(
        error.getSuggestion().contains("supported-field-types/flat-object/"),
        error.getSuggestion());
  }
}

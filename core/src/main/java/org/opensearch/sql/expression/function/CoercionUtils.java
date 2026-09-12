/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.function;

import static org.opensearch.sql.data.type.ExprCoreType.UNKNOWN;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.commons.lang3.tuple.Pair;
import org.opensearch.sql.calcite.utils.OpenSearchTypeFactory;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.data.type.ExprType;
import org.opensearch.sql.exception.ExpressionEvaluationException;

public final class CoercionUtils {
  /**
   * Casts the arguments to the types specified in the typeChecker. Returns null if no combination
   * of parameter types matches the arguments or if casting fails.
   *
   * @param builder RexBuilder to create casts
   * @param typeChecker PPLTypeChecker that provides the parameter types
   * @param arguments List of RexNode arguments to be cast
   * @return List of cast RexNode arguments or null if casting fails
   */
  public static @Nullable List<RexNode> castArguments(
      RexBuilder builder, PPLTypeChecker typeChecker, List<RexNode> arguments) {
    List<List<ExprType>> paramTypeCombinations =
        typeChecker.getParameterTypes().stream()
            .map(
                types ->
                    types.stream()
                        .map(OpenSearchTypeFactory::convertRelDataTypeToExprType)
                        .toList())
            .toList();

    if (hasVariant(arguments)) {
      return castVariantArguments(builder, paramTypeCombinations, arguments);
    }

    List<ExprType> sourceTypes =
        arguments.stream()
            .map(node -> OpenSearchTypeFactory.convertRelDataTypeToExprType(node.getType()))
            .collect(Collectors.toList());
    // Candidate parameter signatures ordered by decreasing widening distance
    PriorityQueue<Pair<List<ExprType>, Integer>> rankedSignatures =
        new PriorityQueue<>((left, right) -> Integer.compare(right.getValue(), left.getValue()));
    for (List<ExprType> paramTypes : paramTypeCombinations) {
      int distance = distance(sourceTypes, paramTypes);
      if (distance == TYPE_EQUAL) {
        return castArguments(builder, paramTypes, arguments);
      }
      Optional.of(distance)
          .filter(value -> value != IMPOSSIBLE_WIDENING)
          .ifPresent(value -> rankedSignatures.add(Pair.of(paramTypes, value)));
    }
    return Optional.ofNullable(rankedSignatures.peek())
        .map(Pair::getKey)
        .map(paramTypes -> castArguments(builder, paramTypes, arguments))
        .orElse(null);
  }

  /**
   * Cast arguments when at least one is a VARIANT (a flat_object leaf). A variant carries its own
   * runtime type and Calcite can cast it to any scalar type -- a numeric variant converts, a text
   * one becomes null -- so the widening distance that ranks ordinary arguments does not apply. At
   * each variant position the widest type any candidate signature accepts is chosen (DOUBLE among
   * the numeric types), so that no precision is lost; the remaining positions are cast as usual.
   * Returns null if no candidate signature fits the non-variant arguments.
   */
  private static @Nullable List<RexNode> castVariantArguments(
      RexBuilder builder, List<List<ExprType>> paramTypeCombinations, List<RexNode> arguments) {
    List<Integer> variantPositions = new ArrayList<>();
    for (int i = 0; i < arguments.size(); i++) {
      if (arguments.get(i).getType().getSqlTypeName() == SqlTypeName.VARIANT) {
        variantPositions.add(i);
      }
    }
    List<ExprType> chosen = null;
    for (List<ExprType> paramTypes : paramTypeCombinations) {
      if (paramTypes.size() != arguments.size() || !fitsOutsideVariants(paramTypes, arguments)) {
        continue;
      }
      if (chosen == null) {
        chosen = new ArrayList<>(paramTypes);
        continue;
      }
      for (int position : variantPositions) {
        chosen.set(position, widest(chosen.get(position), paramTypes.get(position)));
      }
    }
    return chosen == null ? null : castArguments(builder, chosen, arguments);
  }

  /**
   * Convert a VARIANT argument (a flat_object leaf) to the PPL type a function parameter declares.
   *
   * <p>A scalar target is a plain CAST, not the SAFE_CAST every other coercion uses: a variant's
   * cast never throws -- Calcite's {@code VariantValue.cast} returns null for a value that is not
   * of the target type -- so SAFE_CAST would add nothing, and it would break lambdas, because
   * Calcite implements SAFE_CAST as a try/catch inside a generated inner class, which cannot
   * capture a lambda parameter ("Cannot access non-final local variable from inner class").
   *
   * <p>An ARRAY target is {@code VARIANT_ARRAY}: PPL's array functions are written for a list of
   * plain values, which is what it yields; see {@link #castVariantToArrayOfVariants} for the typed
   * form.
   */
  public static RexNode castVariant(RexBuilder builder, RexNode variant, ExprType targetType) {
    if (targetType == ExprCoreType.ARRAY) {
      return builder.makeCall(PPLBuiltinOperators.VARIANT_ARRAY, variant);
    }
    return builder.makeCast(
        OpenSearchTypeFactory.convertExprTypeToRelDataType(targetType), variant, true, false);
  }

  /**
   * Cast a VARIANT argument to {@code ARRAY<VARIANT>}: each element stays the variant it is, so
   * that a lambda parameter or an expanded row is typed VARIANT and keeps the element's own type.
   * The element type is VARIANT rather than PPL's usual ANY because Calcite's runtime type
   * information can describe VARIANT but not ANY.
   */
  public static RexNode castVariantToArrayOfVariants(RexBuilder builder, RexNode variant) {
    RelDataType arrayOfVariant =
        OpenSearchTypeFactory.TYPE_FACTORY.createArrayType(
            OpenSearchTypeFactory.TYPE_FACTORY.createSqlType(SqlTypeName.VARIANT, true), -1);
    return builder.makeCast(arrayOfVariant, variant, true, false);
  }

  /** Whether every non-variant argument can be cast to the candidate signature's type. */
  private static boolean fitsOutsideVariants(List<ExprType> paramTypes, List<RexNode> arguments) {
    for (int i = 0; i < arguments.size(); i++) {
      if (arguments.get(i).getType().getSqlTypeName() == SqlTypeName.VARIANT) {
        continue;
      }
      ExprType source =
          OpenSearchTypeFactory.convertRelDataTypeToExprType(arguments.get(i).getType());
      if (distance(source, paramTypes.get(i)) == IMPOSSIBLE_WIDENING) {
        return false;
      }
    }
    return true;
  }

  /** The wider of two candidate types; the first one if neither widens to the other. */
  private static ExprType widest(ExprType current, ExprType candidate) {
    return distance(current, candidate) != IMPOSSIBLE_WIDENING ? candidate : current;
  }

  /**
   * Widen the arguments to the widest type found among them. If no widest type can be determined,
   * returns null.
   *
   * @param builder RexBuilder to create casts
   * @param arguments List of RexNode arguments to be widened
   * @return List of widened RexNode arguments or null if no widest type can be determined
   */
  public static @Nullable List<RexNode> widenArguments(
      RexBuilder builder, List<RexNode> arguments) {
    // TODO: Add test on e.g. IP
    ExprType widestType = findWidestType(arguments);
    if (widestType == null) {
      return null; // No widest type found, return null
    }
    return arguments.stream().map(arg -> cast(builder, widestType, arg)).toList();
  }

  /**
   * Casts the arguments to the types specified in paramTypes. Returns null if the number of
   * parameters does not match or if casting fails.
   */
  private static @Nullable List<RexNode> castArguments(
      RexBuilder builder, List<ExprType> paramTypes, List<RexNode> arguments) {
    if (paramTypes.size() != arguments.size()) {
      return null; // Skip if the number of parameters does not match
    }

    List<RexNode> castedArguments = new ArrayList<>();
    for (int i = 0; i < paramTypes.size(); i++) {
      ExprType toType = paramTypes.get(i);
      RexNode arg = arguments.get(i);

      RexNode castedArg = cast(builder, toType, arg);

      if (castedArg == null) {
        return null;
      }
      castedArguments.add(castedArg);
    }
    return castedArguments;
  }

  private static @Nullable RexNode cast(RexBuilder builder, ExprType targetType, RexNode arg) {
    if (arg.getType().getSqlTypeName() == SqlTypeName.VARIANT) {
      return castVariant(builder, arg, targetType);
    }
    ExprType argType = OpenSearchTypeFactory.convertRelDataTypeToExprType(arg.getType());
    if (!argType.shouldCast(targetType)) {
      return arg;
    }
    if (distance(argType, targetType) != IMPOSSIBLE_WIDENING) {
      return builder.makeCast(
          OpenSearchTypeFactory.convertExprTypeToRelDataType(targetType), arg, true, true);
    }
    return resolveCommonType(argType, targetType)
        .map(
            exprType ->
                builder.makeCast(
                    OpenSearchTypeFactory.convertExprTypeToRelDataType(exprType), arg, true, true))
        .orElse(null);
  }

  /**
   * Finds the widest type among the given arguments. The widest type is determined by applying the
   * widening type rule to each pair of types in the arguments.
   *
   * @param arguments List of RexNode arguments to find the widest type from
   * @return the widest ExprType if found, otherwise null
   */
  private static @Nullable ExprType findWidestType(List<RexNode> arguments) {
    if (arguments.isEmpty()) {
      return null; // No arguments to process
    }
    ExprType widestType =
        OpenSearchTypeFactory.convertRelDataTypeToExprType(arguments.getFirst().getType());
    if (arguments.size() == 1) {
      return widestType;
    }

    // Iterate pairwise through the arguments and find the widest type
    for (int i = 1; i < arguments.size(); i++) {
      var type = OpenSearchTypeFactory.convertRelDataTypeToExprType(arguments.get(i).getType());
      try {
        final ExprType tempType = widestType;
        widestType = resolveCommonType(widestType, type).orElseGet(() -> max(tempType, type));
      } catch (ExpressionEvaluationException e) {
        // the two types are not compatible, return null
        return null;
      }
    }
    return widestType;
  }

  private static boolean areDateAndTime(ExprType type1, ExprType type2) {
    return (type1 == ExprCoreType.DATE && type2 == ExprCoreType.TIME)
        || (type1 == ExprCoreType.TIME && type2 == ExprCoreType.DATE);
  }

  @VisibleForTesting
  public static Optional<ExprType> resolveCommonType(ExprType left, ExprType right) {
    return COMMON_COERCION_RULES.stream()
        .map(rule -> rule.apply(left, right))
        .flatMap(Optional::stream)
        .findFirst();
  }

  public static boolean hasString(List<RexNode> rexNodeList) {
    return rexNodeList.stream()
        .map(RexNode::getType)
        .map(OpenSearchTypeFactory::convertRelDataTypeToExprType)
        .anyMatch(t -> t == ExprCoreType.STRING);
  }

  /**
   * Whether any argument is a VARIANT (a flat_object leaf). A variant carries its own runtime type
   * and can be cast to any scalar type -- a numeric variant converts, a text one becomes null -- so
   * like a string argument it is eligible for coercion to the type a function expects.
   */
  public static boolean hasVariant(List<RexNode> rexNodeList) {
    return rexNodeList.stream()
        .map(RexNode::getType)
        .anyMatch(t -> t.getSqlTypeName() == SqlTypeName.VARIANT);
  }

  private static final Set<ExprType> NUMBER_TYPES = ExprCoreType.numberTypes();

  private static final List<CoercionRule> COMMON_COERCION_RULES =
      List.of(
          CoercionRule.of(
              (left, right) -> areDateAndTime(left, right),
              (left, right) -> ExprCoreType.TIMESTAMP),
          CoercionRule.of(
              (left, right) -> hasString(left, right) && hasNumber(left, right),
              (left, right) -> ExprCoreType.DOUBLE),
          // (BINARY, STRING) → BINARY: ip/binary columns compared with string literals.
          // Triggers ExtendedRexBuilder.makeCast which wraps the literal with BINARY.
          CoercionRule.of(
              (left, right) -> hasString(left, right) && hasBinary(left, right),
              (left, right) -> ExprCoreType.BINARY));

  private static boolean hasString(ExprType left, ExprType right) {
    return left == ExprCoreType.STRING || right == ExprCoreType.STRING;
  }

  private static boolean hasNumber(ExprType left, ExprType right) {
    return NUMBER_TYPES.contains(left) || NUMBER_TYPES.contains(right);
  }

  private static boolean hasBoolean(ExprType left, ExprType right) {
    return left == ExprCoreType.BOOLEAN || right == ExprCoreType.BOOLEAN;
  }

  private static boolean hasBinary(ExprType left, ExprType right) {
    return left == ExprCoreType.BINARY || right == ExprCoreType.BINARY;
  }

  private record CoercionRule(
      BiPredicate<ExprType, ExprType> predicate, BinaryOperator<ExprType> resolver) {

    Optional<ExprType> apply(ExprType left, ExprType right) {
      return predicate.test(left, right)
          ? Optional.of(resolver.apply(left, right))
          : Optional.empty();
    }

    static CoercionRule of(
        BiPredicate<ExprType, ExprType> predicate, BinaryOperator<ExprType> resolver) {
      return new CoercionRule(predicate, resolver);
    }
  }

  private static final int IMPOSSIBLE_WIDENING = Integer.MAX_VALUE;
  private static final int TYPE_EQUAL = 0;

  private static int distance(ExprType type1, ExprType type2) {
    return distance(type1, type2, TYPE_EQUAL);
  }

  private static int distance(ExprType type1, ExprType type2, int distance) {
    if (type1 == type2) {
      return distance;
    } else if (type1 == UNKNOWN) {
      return IMPOSSIBLE_WIDENING;
    } else if (type1 == ExprCoreType.STRING && type2 == ExprCoreType.DOUBLE) {
      return 1;
    } else {
      return type1.getParent().stream()
          .map(parentOfType1 -> distance(parentOfType1, type2, distance + 1))
          .reduce(Math::min)
          .get();
    }
  }

  /**
   * The max type among two types. The max is defined as follow if type1 could widen to type2, then
   * max is type2, vice versa if type1 couldn't widen to type2 and type2 could't widen to type1,
   * then throw {@link ExpressionEvaluationException}.
   *
   * @param type1 type1
   * @param type2 type2
   * @return the max type among two types.
   */
  public static ExprType max(ExprType type1, ExprType type2) {
    int type1To2 = distance(type1, type2);
    int type2To1 = distance(type2, type1);

    if (type1To2 == Integer.MAX_VALUE && type2To1 == Integer.MAX_VALUE) {
      throw new ExpressionEvaluationException(
          String.format("no max type of %s and %s ", type1, type2));
    } else {
      return type1To2 == Integer.MAX_VALUE ? type1 : type2;
    }
  }

  public static int distance(List<ExprType> sourceTypes, List<ExprType> targetTypes) {
    if (sourceTypes.size() != targetTypes.size()) {
      return IMPOSSIBLE_WIDENING;
    }

    int totalDistance = 0;
    for (int i = 0; i < sourceTypes.size(); i++) {
      ExprType source = sourceTypes.get(i);
      ExprType target = targetTypes.get(i);
      int distance = distance(source, target);
      if (distance == IMPOSSIBLE_WIDENING) {
        return IMPOSSIBLE_WIDENING;
      } else {
        totalDistance += distance;
      }
    }
    return totalDistance;
  }
}

# PPL Regex Command Implementation Design Document

## Overview

This document outlines the implementation of a new PPL `regex` command for OpenSearch SQL that provides **zero delta** compatibility with Splunk's SPL regex command, featuring full PCRE2 support for both legacy and Calcite execution engines.

## Requirements

### Functional Requirements
- **FR1**: Implement PPL `regex` command with identical syntax to Splunk SPL
- **FR2**: Full PCRE2 support including advanced features (recursion, variable-length lookbehind, conditionals)
- **FR3**: Dual-engine support (legacy and Calcite) with identical behavior
- **FR4**: Script query pushdown execution for optimal performance
- **FR5**: Zero impact on existing REGEXP function

### Non-Functional Requirements
- **NFR1**: Optimal performance for regex pattern matching operations
- **NFR2**: Consistent behavior across all execution modes  
- **NFR3**: Maintainable and extensible codebase

## Architecture

### High-Level Design

```
PPL Query: source=index | regex field="pattern"
    ↓
AST Parser (existing)
    ↓
Engine Selection
    ├── Legacy Engine → RegexMatch Expression → Script Query
    └── Calcite Engine → REGEX_MATCH UDF → Script Query / In-memory
```

### Key Components

1. **AST Layer**: Existing `Regex` node handles parsing
2. **Expression Layer**: `RegexMatch` expression with PCRE2 implementation
3. **Calcite Integration**: `RegexMatchFunctionImpl` UDF for Calcite engine
4. **Script Engine**: OpenSearch script queries for pushdown execution

## Implementation Details

### 1. Core Expression Implementation

**File**: `core/src/main/java/org/opensearch/sql/expression/operator/predicate/RegexMatch.java`

```java
public class RegexMatch implements Expression {
    // Uses pcre4j library for full PCRE2 support
    // Implements pattern caching for performance
    // Supports find() semantics matching SPL behavior
}
```

**Key Features**:
- PCRE4J library integration with JNA backend
- Pattern compilation caching (LRU-style, max 1000 patterns)
- Exception handling for invalid patterns
- Support for negation

### 2. Calcite Engine Integration

**Problem**: Calcite's `RexToLixTranslator` cannot translate custom operators without proper `RexCallImplementor`.

**Solution**: Implemented UDF-based approach following existing patterns.

**File**: `core/src/main/java/org/opensearch/sql/expression/function/udf/RegexMatchFunctionImpl.java`

```java
public class RegexMatchFunctionImpl extends ImplementorUDF {
    // Provides NotNullImplementor for Calcite code generation
    // Static eval() method for in-memory execution
    // Uses same PCRE2 logic as RegexMatch expression
}
```

**Execution Modes**:
1. **Pushdown Enabled**: Script query execution via FilterQueryBuilder
2. **Pushdown Disabled**: In-memory enumerable execution via UDF

### 3. Function Registration

**File**: `core/src/main/java/org/opensearch/sql/expression/function/PPLBuiltinOperators.java`

```java
public static final SqlOperator REGEX_MATCH = 
    new RegexMatchFunctionImpl().toUDF("REGEX_MATCH");
```

**File**: `core/src/main/java/org/opensearch/sql/expression/function/BuiltinFunctionName.java`

```java
REGEX_MATCH(FunctionName.of("REGEX_MATCH")),
```

### 4. Legacy Engine Support

**File**: `core/src/main/java/org/opensearch/sql/expression/function/PPLFuncImpTable.java`

```java
// Maps regex AST node to RegexMatch expression
registerOperator(REGEX_MATCH, /* RegexMatch expression factory */);
```

### 5. Calcite Planning

**File**: `core/src/main/java/org/opensearch/sql/calcite/CalciteRelNodeVisitor.java`

```java
@Override
public RelNode visitRegex(Regex node, CalcitePlanContext context) {
    // Creates RexNode using REGEX_MATCH UDF
    // Ensures both execution paths use same implementation
    RexNode regexCondition = context.rexBuilder.makeCall(
        PPLBuiltinOperators.REGEX_MATCH, fieldRex, patternRex);
}
```

### 6. Script Query Generation

**File**: `opensearch/src/main/java/org/opensearch/sql/opensearch/storage/script/filter/FilterQueryBuilder.java`

```java
case "REGEX_MATCH":
    return buildScriptQueryForRegex(createRegexMatchFromFunction(func));
```

Converts REGEX_MATCH function calls to RegexMatch expressions for script serialization.

## Technical Challenges and Solutions

### Challenge 1: Dual Engine Support
**Problem**: Need identical PCRE2 behavior across legacy and Calcite engines.

**Solution**: 
- Single PCRE2 implementation in `RegexMatch` class
- Calcite UDF delegates to same PCRE2 logic
- Script queries ensure pushdown uses same implementation

### Challenge 2: Calcite Translation
**Problem**: Raw `SqlOperator` cannot be translated by `RexToLixTranslator`.

**Root Cause**: Missing `RexCallImplementor` for code generation.

**Solution**: 
- Implemented proper UDF extending `ImplementorUDF`
- Provides `NotNullImplementor` for enumerable execution
- Static `eval()` method for in-memory evaluation

### Challenge 3: Execution Mode Compatibility
**Problem**: Different behavior between pushdown enabled/disabled modes.

**Solution**:
- UDF approach works for both execution modes
- FilterQueryBuilder handles pushdown conversion
- Enumerable execution uses UDF directly

## Library Dependencies

### PCRE4J Integration
- **Library**: `org.pcre4j:pcre4j` (already included)
- **Backend**: JNA-based PCRE2 implementation
- **Features**: Full PCRE2 support including recursion, conditionals, variable-length lookbehind

### Initialization
```java
static {
    Pcre4j.setup(new Pcre2());  // Initialize JNA backend
}
```

## File Changes Summary

### New Files
1. `core/src/main/java/org/opensearch/sql/expression/function/udf/RegexMatchFunctionImpl.java`
   - Calcite UDF implementation with enumerable support

### Modified Files
1. `core/src/main/java/org/opensearch/sql/expression/operator/predicate/RegexMatch.java`
   - Enhanced with PCRE2 support and pattern caching

2. `core/src/main/java/org/opensearch/sql/expression/function/PPLBuiltinOperators.java`
   - Added REGEX_MATCH operator registration

3. `core/src/main/java/org/opensearch/sql/expression/function/BuiltinFunctionName.java`
   - Added REGEX_MATCH function name

4. `core/src/main/java/org/opensearch/sql/expression/function/PPLFuncImpTable.java`
   - Registered REGEX_MATCH for legacy engine

5. `core/src/main/java/org/opensearch/sql/calcite/CalciteRelNodeVisitor.java`
   - Updated to use UDF for both execution modes

6. `opensearch/src/main/java/org/opensearch/sql/opensearch/storage/script/filter/FilterQueryBuilder.java`
   - Added REGEX_MATCH function handling for script queries

## Testing Strategy

### Unit Testing
- PCRE2 pattern compilation and matching
- UDF enumerable execution
- Script query generation
- Error handling for invalid patterns

### Integration Testing
- Legacy engine execution
- Calcite engine with pushdown enabled/disabled
- Cross-engine behavior consistency
- Performance benchmarking

### Test Cases

#### Basic Pattern Matching
```bash
# Test 1: Simple character matching
curl -s -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\"^[A-Z][a-z]+$\" | head 5"}'

# Expected: Match standard capitalized names (Duke, Bond, Bates) 
# but NOT "Mcpherson" (capital in middle)
```

#### PCRE2-Specific Features
```bash
# Test 2: Case-insensitive with inline flag
curl -s -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\"(?i)^duke$\""}'

# Expected: Match exactly 1 record (Amber Duke)

# Test 3: Lookahead assertion
curl -s -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\"^(?=.*[aeiou])[A-Z][a-z]+$\" | head 5"}'

# Expected: Match names containing vowels (Duke, Bates, Adams) 
# but NOT "Key" (no vowels)

# Test 4: Named groups (PCRE2 feature)
curl -s -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\"^(?<first>[A-Z])(?<rest>[a-z]+)$\" | head 3"}'

# Expected: Match simple names (Duke, Bond, Holt) but NOT "Mcpherson"

# Test 5: Negative lookbehind (PCRE2 feature)
curl -s -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\"(?<!Mc)[A-Z][a-z]+\""}'

# Expected: Match most names but NOT "Mcpherson" (preceded by "Mc")
```

#### Edge Cases
```bash
# Test 6: Match all pattern
curl -s -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\".*\" | head 3"}'

# Expected: Match all records (universal pattern)

# Test 7: Length constraints
curl -s -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\"^[A-Z][a-z]{1,3}$\""}'

# Expected: Match short names (Key, Holt) but NOT longer names
```

#### Execution Mode Testing
- Test each pattern with Calcite + pushdown enabled
- Test each pattern with Calcite + pushdown disabled  
- Verify identical results across execution modes

## Performance Considerations

### Pattern Caching
- LRU-style cache with 1000 pattern limit
- Thread-safe `ConcurrentHashMap` implementation
- Automatic cache clearing when size limit reached

### Script Query Optimization
- Pushdown execution reduces data transfer
- Native OpenSearch script execution
- Compiled PCRE2 patterns for optimal performance

## Security Considerations

### Pattern Validation
- Invalid patterns return `false` rather than throwing exceptions
- Resource limits via pattern cache size
- No code injection risks (patterns are data, not code)

### Error Handling
```java
try {
    Pattern compiledPattern = Pattern.compile(pattern);
    // ... matching logic
} catch (Exception e) {
    return ExprValueUtils.booleanValue(false);
}
```

## Future Enhancements

### Potential Improvements
1. **Advanced Caching**: LRU with TTL for pattern cache
2. **Metrics**: Pattern compilation and execution metrics
3. **Configuration**: Configurable cache sizes and limits
4. **Optimization**: Pattern analysis for optimization hints

### Extensibility
- Clean separation of concerns allows easy enhancement
- UDF pattern enables additional PCRE2 functions
- Modular design supports new regex features

## Conclusion

This implementation provides a robust, performant PPL regex command with full PCRE2 support and dual-engine compatibility. The UDF-based approach ensures proper Calcite integration while maintaining the flexibility for script query pushdown optimization.

The design prioritizes:
- **Compatibility**: Zero delta from Splunk SPL behavior
- **Performance**: Script query pushdown with pattern caching
- **Maintainability**: Clean architecture following existing patterns
- **Extensibility**: Foundation for future regex enhancements
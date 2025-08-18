# PPL Regex Command Implementation Design Document

## Overview

This document outlines the implementation of a new PPL `regex` command for OpenSearch SQL that provides regex pattern matching using Java's built-in regex engine for both legacy and Calcite execution engines.

## Requirements

### Functional Requirements
- **FR1**: Implement PPL `regex` command with regex pattern matching capabilities
- **FR2**: Java regex support including named groups, backreferences, lookahead/lookbehind (fixed-width)
- **FR3**: Dual-engine support (legacy and Calcite) with identical behavior
- **FR4**: Script query pushdown execution for optimal performance
- **FR5**: Zero impact on existing REGEXP function
- **FR6**: Support for `_source` field to search across all document fields (SPL compatibility)
- **FR7**: Automatic type coercion for non-string fields (SPL behavior)

### Non-Functional Requirements
- **NFR1**: Optimal performance for regex pattern matching operations
- **NFR2**: Consistent behavior across all execution modes  
- **NFR3**: Maintainable and extensible codebase

## Relationship to Existing REGEXP Function

### Existing `REGEXP` Function vs New `regex` Command

OpenSearch SQL already has a `REGEXP` binary predicate function. Our new `regex` command serves a complementary but different purpose:

| Aspect | REGEXP Function (existing) | regex Command (new) |
|--------|---------------------------|-------------------|
| **Type** | Binary predicate function | PPL filter command |
| **Usage** | `field REGEXP 'pattern'` | `regex field="pattern"` or `regex "pattern"` |
| **Context** | `eval`, `where` conditions | Standalone filter command |
| **Return Type** | INTEGER (1/0) or BOOLEAN | Filters records (boolean evaluation) |
| **Match Semantics** | Full match (`matches()`) | Partial match (`find()`) - SPL-like |
| **Error Handling** | Throws exceptions | Throws IllegalArgumentException |
| **Calcite Support** | Built-in predicate | Custom UDF implementation |
| **SQL/PPL Support** | Both SQL and PPL | PPL only |
| **All-field Search** | Not supported | Supported via `_source` or no field |

### Usage Examples

**REGEXP Function (existing):**
```sql
-- SQL style
SELECT * FROM accounts WHERE lastname REGEXP '^[A-Z][a-z]+$'

-- PPL style  
source=accounts | eval matches = lastname regexp '^[A-Z][a-z]+$' | where matches=1
```

**regex Command (new):**
```sql
-- PPL with specific field
source=accounts | regex lastname="^[A-Z][a-z]+$"

-- PPL with negation
source=accounts | regex lastname!=".*son$"

-- PPL with all-field search (no field specified)
source=accounts | regex "gibsonpotts@zensus\.com"

-- PPL with explicit _source field
source=accounts | regex _source="error|warning|critical"

-- PPL with numeric field (automatic type coercion)
source=accounts | regex age="34$"
```

### Why Both Are Needed

1. **Different Semantics**: 
   - `REGEXP`: Full string match for conditional logic
   - `regex`: Partial match for record filtering (SPL compatibility)

2. **Different Use Cases**:
   - `REGEXP`: Binary comparison in expressions
   - `regex`: Dataset filtering command with all-field search capability

3. **SPL Compatibility**: 
   - Our `regex` command matches Splunk's SPL `regex` behavior
   - `REGEXP` follows SQL standard semantics

4. **Implementation Details**:
   - `REGEXP`: Uses `OperatorUtils.matchesRegexp()` with `matcher.matches()`
   - `regex`: Uses `RegexMatch` expression with `matcher.find()`

## Architecture

### High-Level Design

```
PPL Query: source=index | regex field="pattern" | regex "pattern" | regex _source="pattern"
    ↓
AST Parser (AstBuilder)
    ↓
Engine Selection
    ├── Legacy Engine → RegexMatch Expression → Script Query
    └── Calcite Engine → CalciteRelNodeVisitor → REGEX_MATCH UDF
                         ├── With field → CAST + REGEX_MATCH → Script Query / In-memory
                         └── No field/_source → OR(REGEX_MATCH(field1), ...) → Script Query / In-memory
```

### Key Components

1. **AST Layer**: `Regex` node handles parsing
2. **Expression Layer**: `RegexMatch` expression with Java regex implementation
3. **Calcite Integration**: 
   - `CalciteRelNodeVisitor` handles field logic and type coercion
   - `RegexMatchFunctionImpl` UDF for Calcite engine execution
4. **Script Engine**: OpenSearch script queries for pushdown execution

## Implementation Details

### 1. Core Expression Implementation

**File**: `core/src/main/java/org/opensearch/sql/expression/operator/predicate/RegexMatch.java`

```java
public class RegexMatch implements Expression {
    // Uses Java's built-in regex engine
    // Implements pattern caching for performance
    // Supports find() semantics matching SPL behavior
}
```

**Key Features**:
- Java regex engine (java.util.regex.Pattern)
- Pattern compilation caching (LRU-style, max 1000 patterns)
- Exception handling for invalid patterns
- Support for negation

### 2. All-Field Search Implementation (_source support)

**File**: `core/src/main/java/org/opensearch/sql/calcite/CalciteRelNodeVisitor.java`

```java
@Override
public RelNode visitRegex(Regex node, CalcitePlanContext context) {
    if (node.getField() == null || isSourceField(node.getField())) {
        // No field or _source specified - search across all fields
        List<RexNode> fieldConditions = new ArrayList<>();
        
        for (String fieldName : fieldNames) {
            if (!fieldName.startsWith("_")) { // Skip system fields
                // Get field reference
                RexNode fieldRef = ...;
                
                // Apply CAST to VARCHAR for non-string fields (SPL behavior)
                if (field.getType().getSqlTypeName() != SqlTypeName.VARCHAR &&
                    field.getType().getSqlTypeName() != SqlTypeName.CHAR) {
                    fieldRef = context.rexBuilder.makeCast(
                        context.relBuilder.getTypeFactory().createSqlType(SqlTypeName.VARCHAR),
                        fieldRef);
                }
                
                // Create REGEX_MATCH for this field
                RexNode fieldCondition = context.rexBuilder.makeCall(
                    PPLBuiltinOperators.REGEX_MATCH, fieldRef, patternRex);
                fieldConditions.add(fieldCondition);
            }
        }
        
        // Create OR condition across all fields
        regexCondition = createOrCondition(fieldConditions);
    } else {
        // Regular field specified - also apply CAST if needed
        RexNode fieldRex = rexVisitor.analyze(node.getField(), context);
        
        // Type coercion for non-string fields
        if (fieldRex.getType().getSqlTypeName() != SqlTypeName.VARCHAR &&
            fieldRex.getType().getSqlTypeName() != SqlTypeName.CHAR) {
            fieldRex = context.rexBuilder.makeCast(...);
        }
        
        regexCondition = context.rexBuilder.makeCall(
            PPLBuiltinOperators.REGEX_MATCH, fieldRex, patternRex);
    }
}
```

**Key Features**:
- Detects null field or `_source` field
- Creates OR conditions across all non-system fields
- Applies CAST to VARCHAR for non-string fields (matching SPL's type coercion)
- Works with both pushdown and non-pushdown execution

### 3. Type Coercion (SPL Compatibility)

SPL regex coerces all field types to strings before pattern matching. Our implementation mirrors this:

- **Numeric fields** (BIGINT, INTEGER, etc.) → CAST to VARCHAR
- **Boolean fields** → CAST to VARCHAR
- **String fields** (VARCHAR, CHAR) → Used directly
- **Other types** → CAST to VARCHAR

This ensures queries like `regex age="34"` work correctly even though `age` is a numeric field.

### 4. Calcite UDF Implementation

**File**: `core/src/main/java/org/opensearch/sql/expression/function/udf/RegexMatchFunctionImpl.java`

```java
public class RegexMatchFunctionImpl extends ImplementorUDF {
    public static Boolean eval(String field, String pattern) {
        if (field == null || pattern == null) {
            return null;
        }
        
        Pattern compiledPattern = Pattern.compile(pattern);
        Matcher matcher = compiledPattern.matcher(field);
        return matcher.find(); // Partial match like SPL
    }
}
```

**Execution Modes**:
1. **Pushdown Enabled**: Script query execution via FilterQueryBuilder
2. **Pushdown Disabled**: In-memory enumerable execution via UDF

### 5. AST and Grammar Support

**File**: `ppl/src/main/antlr/OpenSearchPPLParser.g4`

```antlr
regexCommand
    : REGEX regexExpr
    ;

regexExpr
    : pattern=stringLiteral                          // No field - search all fields
    | field=qualifiedName EQUAL pattern=stringLiteral    // Specific field
    | field=qualifiedName NOT_EQUAL pattern=stringLiteral // Negated match
    ;
```

**File**: `ppl/src/main/java/org/opensearch/sql/ppl/parser/AstBuilder.java`

```java
@Override
public UnresolvedPlan visitRegexCommand(RegexCommandContext ctx) {
    UnresolvedExpression field = null; // null means search all fields
    String operator = null;
    Literal pattern = (Literal) internalVisitExpression(ctx.regexExpr().pattern);
    
    if (ctx.regexExpr().field != null) {
        field = internalVisitExpression(ctx.regexExpr().field);
    }
    // ... handle operator
    
    return new Regex(field, operator, pattern);
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

### Challenge 1: All-Field Search Support
**Problem**: Need to support searching across all document fields when no field is specified or `_source` is used.

**Solution**: 
- Detect null field or `_source` field in CalciteRelNodeVisitor
- Create OR conditions across all non-system fields
- Apply same logic for both pushdown and non-pushdown modes

### Challenge 2: Type Coercion for Non-String Fields
**Problem**: SPL regex works with any field type by coercing to string, but Java regex expects String parameters.

**Solution**: 
- Apply CAST to VARCHAR for non-string fields in CalciteRelNodeVisitor
- Works for both specific field and all-field search cases
- Ensures numeric, boolean, and other types work correctly

### Challenge 3: Dual Engine Support
**Problem**: Need identical Java regex behavior across legacy and Calcite engines.

**Solution**: 
- Single Java regex implementation in `RegexMatch` class
- Calcite UDF delegates to same Java regex logic
- Script queries ensure pushdown uses same implementation

### Challenge 4: Calcite Translation
**Problem**: Raw `SqlOperator` cannot be translated by `RexToLixTranslator`.

**Solution**: 
- Implemented proper UDF extending `ImplementorUDF`
- Provides `NotNullImplementor` for enumerable execution
- Static `eval()` method for in-memory evaluation

## Library Dependencies

### Java Regex Integration
- **Library**: Built-in `java.util.regex` package
- **Backend**: Native Java regex engine
- **Features**: Java regex support including named groups, backreferences, lookahead/lookbehind (fixed-width)
- **License**: Compatible with Apache 2.0 (no external dependencies)

### Java Regex Features and Limitations

**Supported Java Regex Features:**
- Named capture groups: `(?<name>pattern)`
- Lookahead assertions: `(?=...)` and `(?!...)`
- Fixed-width lookbehind: `(?<=...)` and `(?<!...)`
- Backreferences: `\1`, `\2`, etc.
- Inline flags: `(?i)`, `(?m)`, `(?s)`, `(?x)`
- Atomic groups: `(?>...)`
- Possessive quantifiers: `*+`, `++`, `?+`

**NOT Supported (PCRE-only features):**
- Recursion: `(?R)`, `(?1)`, etc.
- Conditionals: `(?(condition)yes|no)`
- Variable-length lookbehind
- PCRE-specific escape sequences
- Subroutine calls: `(?&name)`

## File Changes Summary

### New Files
1. `core/src/main/java/org/opensearch/sql/ast/tree/Regex.java`
   - AST node for regex command
   
2. `core/src/main/java/org/opensearch/sql/expression/function/udf/RegexMatchFunctionImpl.java`
   - Calcite UDF implementation with enumerable support
   
3. `core/src/main/java/org/opensearch/sql/expression/operator/predicate/RegexMatch.java`
   - Core regex expression with Java regex support and pattern caching

### Modified Files
1. `core/src/main/java/org/opensearch/sql/calcite/CalciteRelNodeVisitor.java`
   - Added visitRegex with all-field search and type coercion support

2. `core/src/main/java/org/opensearch/sql/expression/function/PPLBuiltinOperators.java`
   - Added REGEX_MATCH operator registration

3. `core/src/main/java/org/opensearch/sql/expression/function/BuiltinFunctionName.java`
   - Added REGEX_MATCH function name

4. `ppl/src/main/antlr/OpenSearchPPLParser.g4`
   - Added regex command grammar

5. `ppl/src/main/java/org/opensearch/sql/ppl/parser/AstBuilder.java`
   - Added visitRegexCommand implementation

6. `opensearch/src/main/java/org/opensearch/sql/opensearch/storage/script/filter/FilterQueryBuilder.java`
   - Added REGEX_MATCH function handling for script queries

## Testing Strategy

### Unit Testing
- Java regex pattern compilation and matching
- UDF enumerable execution
- Script query generation
- Error handling for invalid patterns
- Type coercion for numeric fields

### Integration Testing
- Legacy engine execution
- Calcite engine with pushdown enabled/disabled
- Cross-engine behavior consistency
- All-field search functionality
- Type coercion verification

### Test Cases

#### Basic Pattern Matching
```bash
# Test 1: Simple character matching with specific field
curl -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\"^[A-Z][a-z]+$\" | head 5"}'

# Expected: Match standard capitalized names (Duke, Bond, Bates) 
# but NOT "Mcpherson" (capital in middle)
```

#### All-Field Search (_source support)
```bash
# Test 2: Search across all fields (no field specified)
curl -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex \"gibsonpotts@zensus\\.com\""}'

# Expected: Match record containing this email in any field

# Test 3: Explicit _source field
curl -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex _source=\"Duke|Bond|Bates\""}'

# Expected: Match records containing any of these values in any field

# Test 4: Multiple regex filters with all-field search
curl -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex \"on$\" | regex \"gibsonpotts@zensus\\.com\""}'

# Expected: Match records ending with "on" AND containing the email
```

#### Type Coercion Testing
```bash
# Test 5: Numeric field (automatic type coercion)
curl -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex age=\"34$\""}'

# Expected: Match records where age ends with "34" (e.g., 34)

# Test 6: Balance field (numeric to string)
curl -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex balance=\"^4[0-9]{4}$\""}'

# Expected: Match balances starting with 4 and having 5 digits total
```

#### Negation Testing
```bash
# Test 7: Negated match
curl -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname!=\".*son$\" | head 5"}'

# Expected: Match names NOT ending with "son"
```

#### Java Regex Features
```bash
# Test 8: Case-insensitive with inline flag
curl -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\"(?i)^duke$\""}'

# Expected: Match exactly 1 record (Amber Duke)

# Test 9: Lookahead assertion
curl -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\"^(?=.*[aeiou])[A-Z][a-z]+$\" | head 5"}'

# Expected: Match names containing vowels
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

### All-Field Search Optimization
- OR conditions are generated at plan time, not runtime
- Type coercion is applied only to non-string fields
- System fields (starting with "_") are automatically excluded

### Script Query Optimization
- Pushdown execution reduces data transfer
- Native OpenSearch script execution
- Compiled Java regex patterns for optimal performance

## Security Considerations

### Pattern Validation
- Invalid patterns throw `IllegalArgumentException` with clear error messages
- Resource limits via pattern cache size
- No code injection risks (patterns are data, not code)

### Error Handling
```java
try {
    Pattern compiledPattern = Pattern.compile(pattern);
    // ... matching logic
} catch (PatternSyntaxException e) {
    throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage());
}
```

## Future Enhancements

### Potential Improvements
1. **Field Selection**: Allow specifying which fields to search in all-field mode
2. **Advanced Caching**: LRU with TTL for pattern cache
3. **Metrics**: Pattern compilation and execution metrics
4. **Configuration**: Configurable cache sizes and limits
5. **Optimization**: Pattern analysis for optimization hints

### Extensibility
- Clean separation of concerns allows easy enhancement
- UDF pattern enables additional Java regex functions
- Modular design supports new regex features

## Conclusion

This implementation provides a robust, performant PPL regex command with Java regex support, all-field search capability, and dual-engine compatibility. The implementation includes SPL-compatible features like `_source` field support and automatic type coercion for non-string fields.

The design prioritizes:
- **SPL Compatibility**: Matches Splunk's regex behavior including all-field search and type coercion
- **Performance**: Script query pushdown with pattern caching
- **Flexibility**: Support for specific field, no field, and `_source` field patterns
- **Maintainability**: Clean architecture following existing patterns
- **Extensibility**: Foundation for future regex enhancements
- **License Compliance**: Pure Apache 2.0 with no external dependencies
debug server
```angular2html
./gradlew opensearch-sql:run -DdebugJVM
```

sample data
```angular2html
curl -X POST "localhost:9200/accounts/_bulk" \
-H "Content-Type: application/json" \
--data-binary @/Users/jiallian/Desktop/opensearch/sql-team/cve-fix/sql/integ-test/src/test/resources/accounts.json | jq
```

calcite enable
```angular2html
curl -X PUT "localhost:9200/_cluster/settings" \
  -H "Content-Type: application/json" \
  -d '{
    "transient": {
      "plugins.calcite.enabled": true
    }
  }'
```

pushdown disable
```angular2html
curl -X PUT "localhost:9200/_cluster/settings" \
  -H "Content-Type: application/json" \
  -d '{
    "transient": {
      "plugins.calcite.pushdown.enabled": false
    }
  }'
```

last name ending with "on"
```angular2html
curl -X POST 'http://localhost:9200/_plugins/_ppl' \
    -H 'Content-Type: application/json' \
    -d '{"query":"source=accounts | regex lastname=\".*on$\" | head 20" }' | jq
```

johnson: "Find 'son' at the end, but only if it's preceded by 'John'
- (?<=John): This is a positive lookbehind assertion. It checks that the text immediately before the next part of the pattern is exactly the string "John", but it does not include "John" in the actual match. (Lookbehinds are zero-width assertions—they verify context without consuming characters.)
- son: This matches the literal characters "s", "o", "n" in sequence.
- $: This is an anchor that asserts the end of the string (or end of the line in multiline mode, but here it's likely treating the lastname field as a single-line string).
```angular2html
curl -s -X POST 'http://localhost:9200/_plugins/_ppl' \
  -H 'Content-Type: application/json' \
  -d '{"query":"source=accounts | regex lastname=\"(?<=John)son$\""}' | jq
```

combined - 
1. Regex Filter: Uses regex pattern ^[0-9]{3,5} .* to find addresses that start with a street number (3-5 digits followed by a space)
   - Example matches: "123 Main Street", "4567 Oak Avenue", "89012 First Road"
   - Example non-matches: "Apartment 5B", "PO Box 123", "12 Short St" (only 2 digits)
2. Where Clause: Further filters to only include accounts with balance greater than $30,000
3. Stats Aggregation: Calculates two metrics:
   - high_balance_accounts: Total count of matching accounts
   - avg_account_balance: Average balance of these filtered accounts

```angular2html
curl -X POST "localhost:9200/_plugins/_ppl" \
    -H "Content-Type: application/json" \
    -d '{
      "query": "source=accounts | regex lastname=\".*on$\" | where balance > 30000 | stats count() as high_balance_accounts, avg(balance) as avg_account_balance"
    }'
```

```angular2html
curl -X POST "localhost:9200/_plugins/_ppl" \
    -H "Content-Type: application/json" \
    -d '{
      "query": "source=accounts | where balance > 30000 | stats count() as high_balance_accounts, avg(balance) as avg_account_balance"
    }'
```

Pattern: \((?:[^()]|(?R))*\)
- Matches balanced parentheses like: (hello), (foo(bar)), (a(b(c)d)e)
- The (?R) here recursively calls the entire pattern when it encounters a nested (

```angular2html
  curl -s -X POST 'http://localhost:9200/_plugins/_ppl' \
      -H 'Content-Type: application/json' \
      -d '{"query":"source=accounts | regex address=\"\\\\((?:[^()]|(?R))*\\\\)\""}'
```
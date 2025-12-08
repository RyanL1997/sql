#!/bin/bash

# Performance Testing Script for Filter Merge Optimization
# This script runs clickbench integration tests and collects timing data

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_FILE="${SCRIPT_DIR}/performance_results.log"
SUMMARY_FILE="${SCRIPT_DIR}/performance_summary.csv"

echo "============================================"
echo "Filter Merge Performance Testing"
echo "============================================"
echo ""
echo "This will run clickbench integration tests with performance instrumentation."
echo "Results will be saved to:"
echo "  - Full log: ${LOG_FILE}"
echo "  - Summary:  ${SUMMARY_FILE}"
echo ""

# Cleanup previous results
rm -f "${LOG_FILE}" "${SUMMARY_FILE}"

# Create CSV header
echo "Query,AnalyzeTime(μs),FilterMergeTime(μs),OptimizeTime(μs),ConvertTime(μs),TotalPlanningTime(μs),FilterMerge%,Status" > "${SUMMARY_FILE}"

echo "Running clickbench integration tests..."
echo "This may take 5-10 minutes..."
echo ""

# Run the tests and capture output
./gradlew ':integ-test:integTest' \
  --tests "org.opensearch.sql.calcite.clickbench.CalcitePPLClickBenchIT" \
  --info 2>&1 | tee "${LOG_FILE}"

echo ""
echo "============================================"
echo "Extracting Performance Data..."
echo "============================================"

# Extract PERF lines from the log and parse them
grep "PERF \[Planning\]" "${LOG_FILE}" | while read -r line; do
  # Extract timing values using regex
  analyze=$(echo "$line" | sed -n 's/.*analyze=\([0-9]*\)μs.*/\1/p')
  merge=$(echo "$line" | sed -n 's/.*filterMerge=\([0-9]*\)μs.*/\1/p')
  optimize=$(echo "$line" | sed -n 's/.*optimize=\([0-9]*\)μs.*/\1/p')
  convert=$(echo "$line" | sed -n 's/.*convert=\([0-9]*\)μs.*/\1/p')
  total=$(echo "$line" | sed -n 's/.*total=\([0-9]*\)μs.*/\1/p')

  # Calculate filter merge percentage
  if [ "$total" -gt 0 ]; then
    merge_pct=$(awk "BEGIN {printf \"%.2f\", ($merge/$total)*100}")
  else
    merge_pct="0.00"
  fi

  # Append to CSV (query name will be extracted separately)
  echo "unknown,$analyze,$merge,$optimize,$convert,$total,$merge_pct,PASS" >> "${SUMMARY_FILE}"
done

echo ""
echo "============================================"
echo "Quick Summary"
echo "============================================"

# Calculate statistics
total_queries=$(grep -c "PERF \[Planning\]" "${LOG_FILE}" || echo "0")
echo "Total queries analyzed: ${total_queries}"

if [ "$total_queries" -gt 0 ]; then
  # Calculate average filter merge time
  avg_merge=$(grep "PERF \[Planning\]" "${LOG_FILE}" | \
    sed -n 's/.*filterMerge=\([0-9]*\)μs.*/\1/p' | \
    awk '{s+=$1; c++} END {if(c>0) printf "%.0f", s/c; else print "0"}')

  # Calculate average total planning time
  avg_total=$(grep "PERF \[Planning\]" "${LOG_FILE}" | \
    sed -n 's/.*total=\([0-9]*\)μs.*/\1/p' | \
    awk '{s+=$1; c++} END {if(c>0) printf "%.0f", s/c; else print "0"}')

  # Calculate max filter merge time
  max_merge=$(grep "PERF \[Planning\]" "${LOG_FILE}" | \
    sed -n 's/.*filterMerge=\([0-9]*\)μs.*/\1/p' | \
    sort -n | tail -1)

  echo ""
  echo "Filter Merge Timing:"
  echo "  - Average: ${avg_merge}μs ($(awk "BEGIN {printf \"%.2f\", $avg_merge/1000}")ms)"
  echo "  - Maximum: ${max_merge}μs ($(awk "BEGIN {printf \"%.2f\", $max_merge/1000}")ms)"

  if [ "$avg_total" -gt 0 ]; then
    avg_merge_pct=$(awk "BEGIN {printf \"%.1f\", ($avg_merge/$avg_total)*100}")
    echo "  - % of planning: ${avg_merge_pct}%"
  fi

  echo ""
  echo "Total Planning Time:"
  echo "  - Average: ${avg_total}μs ($(awk "BEGIN {printf \"%.2f\", $avg_total/1000}")ms)"

  echo ""
  echo "Performance Assessment:"
  if [ "$avg_merge" -lt 1000 ]; then
    echo "  ✅ EXCELLENT - Filter merge overhead < 1ms"
  elif [ "$avg_merge" -lt 5000 ]; then
    echo "  ✅ GOOD - Filter merge overhead < 5ms"
  elif [ "$avg_merge" -lt 10000 ]; then
    echo "  ⚠️  MODERATE - Consider optimization"
  else
    echo "  ❌ HIGH - Optimization required"
  fi
else
  echo "⚠️  No performance data found in logs"
  echo "Make sure log level is set to INFO"
fi

echo ""
echo "============================================"
echo "Detailed Results"
echo "============================================"
echo "Full log saved to: ${LOG_FILE}"
echo "CSV summary saved to: ${SUMMARY_FILE}"
echo ""
echo "To analyze further:"
echo "  cat ${SUMMARY_FILE}"
echo "  grep PERF ${LOG_FILE}"
echo ""
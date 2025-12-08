#!/usr/bin/env python3
"""
Performance Analysis Script for Filter Merge Optimization

This script analyzes the performance log and generates detailed reports.
"""

import re
import sys
from pathlib import Path
from collections import defaultdict
import statistics

def parse_perf_log(log_file, test_log_file=None):
    """Extract performance metrics from log file."""
    perf_pattern = re.compile(
        r'PERF \[Planning\] analyze=(\d+)μs, filterMerge=(\d+)μs, optimize=(\d+)μs, convert=(\d+)μs, total=(\d+)μs'
    )

    # Build ordered list of query names from test log
    query_names = []
    if test_log_file and test_log_file.exists():
        running_query_pattern = re.compile(r'Running (Query\d+)')
        with open(test_log_file, 'r') as f:
            for line in f:
                query_match = running_query_pattern.search(line)
                if query_match:
                    query_names.append(query_match.group(1))

    # Parse performance data from server log
    # Each query runs twice (explain + execute), so we track pairs
    results = []
    query_index = 0
    perf_count_for_query = 0

    with open(log_file, 'r') as f:
        for line in f:
            # Extract performance data
            perf_match = perf_pattern.search(line)
            if perf_match:
                analyze, merge, optimize, convert, total = map(int, perf_match.groups())

                # Determine query name (each query has 2 PERF lines: explain + execute)
                if query_index < len(query_names):
                    current_test = query_names[query_index]
                else:
                    current_test = f"Query{query_index + 1}"

                results.append({
                    'test': current_test,
                    'analyze': analyze,
                    'merge': merge,
                    'optimize': optimize,
                    'convert': convert,
                    'total': total,
                    'merge_pct': (merge / total * 100) if total > 0 else 0
                })

                # Each query generates 2 PERF logs (explain yaml + actual query)
                perf_count_for_query += 1
                if perf_count_for_query == 2:
                    query_index += 1
                    perf_count_for_query = 0

    return results

def generate_report(results):
    """Generate detailed performance report."""
    if not results:
        print("❌ No performance data found!")
        print("Make sure:")
        print("  1. The log level is set to INFO")
        print("  2. The instrumentation code is active")
        print("  3. Tests actually ran")
        return

    print("=" * 80)
    print("FILTER MERGE PERFORMANCE ANALYSIS")
    print("=" * 80)
    print()

    # Overall statistics
    merge_times = [r['merge'] for r in results]
    total_times = [r['total'] for r in results]
    merge_pcts = [r['merge_pct'] for r in results]

    print(f"📊 OVERALL STATISTICS ({len(results)} queries)")
    print("-" * 80)
    print(f"Filter Merge Time:")
    print(f"  Mean:     {statistics.mean(merge_times):>8.0f} μs  ({statistics.mean(merge_times)/1000:>6.2f} ms)")
    print(f"  Median:   {statistics.median(merge_times):>8.0f} μs  ({statistics.median(merge_times)/1000:>6.2f} ms)")
    print(f"  Std Dev:  {statistics.stdev(merge_times) if len(merge_times) > 1 else 0:>8.0f} μs")
    print(f"  Min:      {min(merge_times):>8.0f} μs  ({min(merge_times)/1000:>6.2f} ms)")
    print(f"  Max:      {max(merge_times):>8.0f} μs  ({max(merge_times)/1000:>6.2f} ms)")
    print()

    print(f"Total Planning Time:")
    print(f"  Mean:     {statistics.mean(total_times):>8.0f} μs  ({statistics.mean(total_times)/1000:>6.2f} ms)")
    print(f"  Median:   {statistics.median(total_times):>8.0f} μs  ({statistics.median(total_times)/1000:>6.2f} ms)")
    print()

    print(f"Filter Merge as % of Planning:")
    print(f"  Mean:     {statistics.mean(merge_pcts):>6.2f}%")
    print(f"  Median:   {statistics.median(merge_pcts):>6.2f}%")
    print(f"  Max:      {max(merge_pcts):>6.2f}%")
    print()

    # Performance assessment
    print("=" * 80)
    print("📈 PERFORMANCE ASSESSMENT")
    print("-" * 80)

    avg_merge_ms = statistics.mean(merge_times) / 1000
    avg_merge_pct = statistics.mean(merge_pcts)

    if avg_merge_ms < 1:
        rating = "✅ EXCELLENT"
        recommendation = "No optimization needed. Merge immediately."
    elif avg_merge_ms < 5:
        rating = "✅ GOOD"
        recommendation = "Acceptable overhead. Safe to merge."
    elif avg_merge_ms < 10:
        rating = "⚠️  MODERATE"
        recommendation = "Consider adding conditional execution (pre-check)."
    else:
        rating = "❌ HIGH OVERHEAD"
        recommendation = "Optimization required before merge."

    print(f"Rating: {rating}")
    print(f"Average overhead: {avg_merge_ms:.2f}ms ({avg_merge_pct:.1f}% of planning)")
    print(f"Recommendation: {recommendation}")
    print()

    # Percentile analysis
    merge_times_sorted = sorted(merge_times)
    n = len(merge_times_sorted)
    p50 = merge_times_sorted[n//2]
    p95 = merge_times_sorted[int(n*0.95)]
    p99 = merge_times_sorted[int(n*0.99)] if n > 100 else merge_times_sorted[-1]

    print("=" * 80)
    print("📊 PERCENTILE ANALYSIS")
    print("-" * 80)
    print(f"Filter Merge Time Percentiles:")
    print(f"  p50: {p50:>6.0f} μs  ({p50/1000:>6.2f} ms)")
    print(f"  p95: {p95:>6.0f} μs  ({p95/1000:>6.2f} ms)")
    print(f"  p99: {p99:>6.0f} μs  ({p99/1000:>6.2f} ms)")
    print()

    # Breakdown by phase
    print("=" * 80)
    print("⏱️  PLANNING PHASE BREAKDOWN")
    print("-" * 80)

    avg_analyze = statistics.mean([r['analyze'] for r in results])
    avg_merge = statistics.mean([r['merge'] for r in results])
    avg_optimize = statistics.mean([r['optimize'] for r in results])
    avg_convert = statistics.mean([r['convert'] for r in results])
    avg_total = statistics.mean([r['total'] for r in results])

    print(f"Phase Averages:")
    print(f"  Analyze:      {avg_analyze:>8.0f} μs  ({avg_analyze/avg_total*100:>5.1f}%)")
    print(f"  Filter Merge: {avg_merge:>8.0f} μs  ({avg_merge/avg_total*100:>5.1f}%)  ← THIS IS WHAT WE ADDED")
    print(f"  Optimize:     {avg_optimize:>8.0f} μs  ({avg_optimize/avg_total*100:>5.1f}%)")
    print(f"  Convert:      {avg_convert:>8.0f} μs  ({avg_convert/avg_total*100:>5.1f}%)")
    print(f"  TOTAL:        {avg_total:>8.0f} μs  (100.0%)")
    print()

    # Top slowest queries (aggregate by query name to remove duplicates)
    print("=" * 80)
    print("🐢 TOP 10 SLOWEST FILTER MERGE TIMES")
    print("-" * 80)

    # Group by test name and average the metrics
    query_aggregates = {}
    for r in results:
        if r['test'] not in query_aggregates:
            query_aggregates[r['test']] = {
                'merge_times': [],
                'total_times': [],
                'merge_pcts': []
            }
        query_aggregates[r['test']]['merge_times'].append(r['merge'])
        query_aggregates[r['test']]['total_times'].append(r['total'])
        query_aggregates[r['test']]['merge_pcts'].append(r['merge_pct'])

    # Calculate averages and sort
    query_stats = []
    for test_name, data in query_aggregates.items():
        query_stats.append({
            'test': test_name,
            'avg_merge': statistics.mean(data['merge_times']),
            'max_merge': max(data['merge_times']),
            'avg_merge_pct': statistics.mean(data['merge_pcts']),
            'count': len(data['merge_times'])
        })

    sorted_queries = sorted(query_stats, key=lambda q: q['avg_merge'], reverse=True)[:10]
    print(f"{'Rank':<6} {'Query':<20} {'Avg Merge Time':<20} {'Max Merge Time':<20} {'% of Planning':<15}")
    print("-" * 80)
    for i, q in enumerate(sorted_queries, 1):
        print(f"{i:<6} {q['test']:<20} {q['avg_merge']:>8.0f} μs ({q['avg_merge']/1000:>5.2f}ms)   {q['max_merge']:>8.0f} μs ({q['max_merge']/1000:>5.2f}ms)   {q['avg_merge_pct']:>5.1f}%")
    print()

    # Distribution analysis
    print("=" * 80)
    print("📈 DISTRIBUTION ANALYSIS")
    print("-" * 80)

    buckets = {
        '<100μs': 0,
        '100-500μs': 0,
        '500-1000μs (1ms)': 0,
        '1-5ms': 0,
        '5-10ms': 0,
        '>10ms': 0
    }

    for time in merge_times:
        if time < 100:
            buckets['<100μs'] += 1
        elif time < 500:
            buckets['100-500μs'] += 1
        elif time < 1000:
            buckets['500-1000μs (1ms)'] += 1
        elif time < 5000:
            buckets['1-5ms'] += 1
        elif time < 10000:
            buckets['5-10ms'] += 1
        else:
            buckets['>10ms'] += 1

    print("Filter Merge Time Distribution:")
    for bucket, count in buckets.items():
        pct = count / len(merge_times) * 100
        bar = '█' * int(pct / 2)
        print(f"  {bucket:<20} {count:>4} ({pct:>5.1f}%) {bar}")
    print()

    # Export CSV for further analysis
    csv_file = Path(__file__).parent / 'performance_analysis.csv'
    with open(csv_file, 'w') as f:
        f.write('Test,Analyze(μs),FilterMerge(μs),Optimize(μs),Convert(μs),Total(μs),FilterMerge%\n')
        for r in results:
            f.write(f"{r['test']},{r['analyze']},{r['merge']},{r['optimize']},{r['convert']},{r['total']},{r['merge_pct']:.2f}\n")

    print("=" * 80)
    print(f"📄 Detailed CSV exported to: {csv_file}")
    print("=" * 80)
    print()

def main():
    # Try server log first (where the actual perf data is), fallback to test log
    server_log = Path(__file__).parent / 'integ-test/build/testclusters/integTest-0/logs/integTest.log'
    test_log = Path(__file__).parent / 'performance_results.log'

    log_file = server_log if server_log.exists() else test_log

    if not log_file.exists():
        print(f"❌ Log file not found: {log_file}")
        print()
        print("Please run the performance test first:")
        print("  ./run_performance_test.sh")
        print()
        print("Looked for logs at:")
        print(f"  - {server_log}")
        print(f"  - {test_log}")
        sys.exit(1)

    print(f"Analyzing log file: {log_file}")
    if test_log.exists():
        print(f"Using test log for query names: {test_log}")
    print()

    results = parse_perf_log(log_file, test_log if test_log.exists() else None)
    generate_report(results)

if __name__ == '__main__':
    main()
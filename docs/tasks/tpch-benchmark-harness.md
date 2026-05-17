# TPC-H Benchmark Harness

Issue: #35

## Status

Initial harness added.

## Goal

Measure the current 22-query TPC-H compatibility suite in a repeatable way before changing optimizer behavior.

The first harness intentionally reuses the existing compatibility test methods. That keeps correctness and benchmark coverage aligned: if a query stops matching its expected DuckDB-derived result, the benchmark run fails instead of reporting a misleading timing.

## Command

Compile test classes first:

```bash
mvn test-compile
```

Run the full suite once:

```bash
mvn exec:exec \
  -Dexec.classpathScope=test \
  -Dexec.executable=java \
  -Dexec.args="-classpath %classpath dev.trentdb.tpch.TpchBenchmarkRunner"
```

Run selected queries with warmup and repeated measurements:

```bash
mvn exec:exec \
  -Dexec.classpathScope=test \
  -Dexec.executable=java \
  -Dexec.args="-classpath %classpath dev.trentdb.tpch.TpchBenchmarkRunner --warmup 1 --iterations 3 --query Q01,Q06,Q20"
```

## Output

The runner prints CSV with one row per measured query execution:

```text
query,iteration,status,total_ms,csv_read_ms,csv_schema_ms,csv_materialize_ms,csv_cache_hits,csv_cache_misses,optimizer_ms,physical_plan_ms,pipeline_ms,result_ms,query_executor_ms,error
```

Key fields:

- `total_ms`: full compatibility test method time, including parse, bind, planning, execution, and assertions.
- `csv_read_ms`, `csv_schema_ms`, `csv_materialize_ms`: CSV replacement scan work observed through profiler events.
- `optimizer_ms`, `physical_plan_ms`, `pipeline_ms`, `result_ms`: top-level `QueryExecutor` phase timings.
- `query_executor_ms`: total top-level time inside `QueryExecutor`, excluding parse, bind, and logical planning.

## Limitations

- This is a correctness-preserving smoke benchmark, not a formal benchmark.
- CSV cache state is process-local, so query order can affect CSV load timings.
- Parse, bind, and logical planning are currently included in `total_ms`; they are not yet reported as separate fields.
- Nested subquery execution is included in the parent top-level `pipeline_ms` instead of being summed again.
- Typed-table benchmarking is tracked separately in #36 and should be added before making optimizer claims from execution-only measurements.

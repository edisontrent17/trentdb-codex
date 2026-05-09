# TPC-H Q4 Correlated EXISTS

## Status

- Implemented `EXISTS` parsing, AST, binding, logical planning, physical planning, and execution.
- Added generated SF 0.01 CSV fixtures for Q4 `orders` and `lineitem`.
- Added Q4 to the TPC-H regression suite.

## DuckDB Reference

DuckDB represents this as `SubqueryType::EXISTS` and decorrelates the correlated predicate into a delimiter/mark join path:

- `src/include/duckdb/parser/expression/subquery_expression.hpp`
- `src/include/duckdb/planner/expression/bound_subquery_expression.hpp`
- `src/planner/binder/expression/bind_subquery_expression.cpp`
- `src/planner/binder/query_node/plan_subquery.cpp`

DuckDB's Q4 plan uses `RIGHT_DELIM_JOIN` / `RIGHT_SEMI`, with a filtered `lineitem` side and a delimiter scan over the outer `orders` keys. TrentDB now uses a DuckDB-shaped logical dependent join and physical mark join for the supported correlated `EXISTS` equality shape. It does not yet implement DuckDB's full duplicate-eliminated delim join machinery.

## Correctness

Command:

```bash
/bin/bash -lc 'javac -cp target/classes:/home/manoj/.m2/repository/org/antlr/antlr4-runtime/4.13.2/antlr4-runtime-4.13.2.jar /tmp/RunTrentSqlCsv.java && java -cp /tmp:target/classes:/home/manoj/.m2/repository/org/antlr/antlr4-runtime/4.13.2/antlr4-runtime-4.13.2.jar RunTrentSqlCsv /tmp/tpch_q4.sql > /tmp/trent_q4.csv && duckdb -csv -c "PRAGMA threads=1; $(cat /tmp/tpch_q4.sql)" > /tmp/duck_q4.csv && python3 /tmp/compare_tpch_csv.py /tmp/trent_q4.csv /tmp/duck_q4.csv'
```

Result:

```text
ok rows=5
```

Expected Q4 output:

```text
1-URGENT,93
2-HIGH,103
3-MEDIUM,109
4-NOT SPECIFIED,102
5-LOW,128
```

## Benchmark

Five samples, generated SF 0.01 CSV, DuckDB with `PRAGMA threads=1`:

```text
query,engine,avg_s,median_s,min_s,max_s
q4,trentdb,0.752955,0.757753,0.679145,0.874167
q4,duckdb_threads1,0.338656,0.336116,0.329273,0.351563
```

## Limitations

- Correlated `EXISTS` currently supports a single-table subquery with one correlated equality predicate and inner-only residual filters.
- Unsupported correlated shapes fail explicitly.
- `NOT EXISTS` is still pending because general unary `NOT` binding is not implemented yet.

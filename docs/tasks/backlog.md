# Backlog

## Current Direction

- Build a production-quality in-memory analytical engine first.
- Mirror DuckDB's parser -> binder -> logical operator -> physical operator -> vectorized execution shape.
- Use `/home/manoj/Projects/duckdb` as the local architecture reference.
- Defer WAL, crash recovery, persistence, and full multi-client MVCC.

## Next

- implement inner joins through explicit logical and physical join operators
- add ambiguity handling for unqualified column references once joins are bound

## Done

- implement `ORDER BY` through parser AST, binder, logical planner, physical planner, and execution
- add sort semantics tests for ascending, descending, null ordering, aliases, expression order keys, and select-list ordinals
- implement aggregate binding and execution for `count`, `sum`, `min`, `max`, and `avg`
- add `GROUP BY` planning and hash aggregate execution
- run canonical TPC-H Q6 from generated SF 0.01 `lineitem` CSV data

## DuckDB Reference Areas

- `src/planner/operator/logical_order.cpp`
- `src/planner/operator/logical_aggregate.cpp`
- `src/planner/operator/logical_join.cpp`
- `src/execution/operator/order`
- `src/execution/operator/aggregate`
- `src/execution/operator/join`
- `src/execution/operator/csv_scanner`

## Soon

- connect `CREATE TABLE` AST to in-memory catalog registration through an execution-facing statement path
- connect `INSERT ... VALUES` to in-memory table storage through an execution-facing statement path
- add end-to-end tests for `CREATE TABLE`, `INSERT`, and subsequent `SELECT`
- add casts and type coercion for the supported SQL subset
- add `IS NULL` and `IS NOT NULL`
- add physical `EXPLAIN` output in addition to logical output
- add operator-level memory/accounting hooks

## Later

- optimizer passes: constant folding, filter pushdown, projection pruning
- richer DuckDB-compatibility tests
- primitive-specialized vectors for fixed-width types
- columnar append storage with segments and scan state
- order/top-N physical operators
- broader scalar function coverage
- benchmark suite for scan, filter, projection, aggregate, join, and CSV scans
- replace the current minimal CSV replacement scan typing with DuckDB-shaped CSV sniffing:
  `CsvReaderOptions`, dialect detection, header detection, type detection, type refinement, and user type overrides

## Deferred

- persistence
- WAL and crash recovery
- full MVCC and multi-client isolation
- indexes
- concurrency
- distributed execution

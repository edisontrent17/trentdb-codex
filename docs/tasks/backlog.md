# Backlog

## Current Direction

- Build a production-quality in-memory analytical engine first.
- Mirror DuckDB's parser -> binder -> logical operator -> physical operator -> vectorized execution shape.
- Use `/home/manoj/Projects/duckdb` as the local architecture reference.
- Keep write paths behind WAL-capable catalog, storage, and transaction boundaries before claiming durable DDL or DML.

## Next

- extend TPC-H coverage to the next query shapes while deferring correlated subquery execution until later
- add correlated subqueries and `EXISTS`/`NOT EXISTS` support
- introduce optimizer scaffolding once the unoptimized behavior is covered by compatibility tests
- design the durable write path for `CREATE TABLE` and `INSERT` around WAL and recovery boundaries

## Done

- implement `ORDER BY` through parser AST, binder, logical planner, physical planner, and execution
- add sort semantics tests for ascending, descending, null ordering, aliases, expression order keys, and select-list ordinals
- implement aggregate binding and execution for `count`, `sum`, `min`, `max`, and `avg`
- add `GROUP BY` planning and hash aggregate execution
- run canonical TPC-H Q6 from generated SF 0.01 `lineitem` CSV data
- implement single and multiple explicit `INNER JOIN` queries with DuckDB-shaped logical joins
- execute joins through physical hash join and nested loop join operators in the operator pipeline
- implement DuckDB-shaped `HAVING` binding and planning as a filter above `LogicalAggregate`
- run generated CSV TPC-H compatibility tests for Q1, Q3, Q5, Q6, Q10, Q11, Q12, Q14, and Q19
- add ambiguity handling for unqualified column references in join binding
- add non-correlated scalar subqueries and `IN`/`NOT IN` subqueries

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
- evaluate CSV parser replacement using real data before choosing a dependency:
  compare the current parser against candidate libraries on generated TPC-H CSV fixtures,
  quoted/comma-heavy Q10-style data, and malformed/edge-case CSV files; record throughput,
  allocation profile, correctness coverage, streaming behavior, dependency size, license,
  and maintenance activity before selecting an implementation

## Later

- optimizer passes: constant folding, filter pushdown, projection pruning, top-N rewrite
- rewrite eligible subqueries into semi joins, mark joins, or scalar subquery operators
- richer DuckDB-compatibility tests
- primitive-specialized vectors for fixed-width types
- columnar append storage with segments and scan state
- top-N physical operator
- broader scalar function coverage
- benchmark suite for scan, filter, projection, aggregate, join, and CSV scans
- replace the current minimal CSV replacement scan typing with DuckDB-shaped CSV sniffing:
  `CsvReaderOptions`, dialect detection, header detection, type detection, type refinement, and user type overrides

## Deferred

- persistence
- full MVCC and multi-client isolation
- indexes
- concurrency
- distributed execution

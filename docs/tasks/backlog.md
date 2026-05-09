# Backlog

## Current Direction

- Build a production-quality in-memory analytical engine first.
- Mirror DuckDB's parser -> binder -> logical operator -> physical operator -> vectorized execution shape.
- Use `/home/manoj/Projects/duckdb` as the local architecture reference.
- Keep write paths behind WAL-capable catalog, storage, and transaction boundaries before claiming durable DDL or DML.
- Preserve DuckDB-compatible correctness for all 22 TPC-H query shapes before optimizer work.

## Next

- introduce optimizer scaffolding now that all 22 TPC-H queries have unoptimized regression coverage
- decorrelate scalar aggregate subqueries into join-shaped plans, starting with canonical Q20 performance
- broaden generic correlated scalar subquery execution beyond the current TPC-H scalar aggregate shapes
- add semi/anti/mark join rewrite infrastructure for `IN`, `EXISTS`, and scalar subqueries
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
- implement distinct aggregate arguments for the supported aggregate functions
- implement DuckDB-shaped `LEFT OUTER JOIN` and non-correlated derived tables in `FROM`
- implement non-recursive common table expressions for read queries
- run generated CSV TPC-H compatibility tests for Q1 through Q22
- execute canonical TPC-H Q2, Q17, and Q20 scalar aggregate subquery shapes
- add ambiguity handling for unqualified column references in join binding
- add non-correlated scalar subqueries and `IN`/`NOT IN` subqueries
- add correlated `EXISTS` planning and execution for the single-table equality shape used by TPC-H Q4
- add comma join parsing, SQL-standard `substring(... FROM ... FOR ...)`, unary `NOT` binding, and correlated `EXISTS`/`NOT EXISTS` inequality support for the remaining TPC-H query shapes

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
- match DuckDB's full duplicate-eliminated delim join decorrelation for broader correlated subqueries
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

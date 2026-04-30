# SELECT Slice

## Goal

Add the first read-only `SELECT` path in a DuckDB-shaped way: parser AST -> binder -> logical plan skeleton.

This slice does not execute queries yet. It establishes name binding and logical operator shape while reading catalog metadata through a transaction-aware API.

## Scope

- support `SELECT * FROM table`
- support `SELECT column FROM table`
- support `SELECT column_a, column_b FROM table`
- bind table names through `Catalog` using a `Transaction`
- bind column names against `TableCatalogEntry`
- produce a minimal logical plan tree

## Out of Scope

- physical execution
- storage scans
- joins
- aliases
- expressions beyond simple column references and star
- `WHERE`
- `GROUP BY`
- aggregates
- `ORDER BY`
- `LIMIT`
- WAL
- real MVCC visibility rules beyond the transaction/snapshot API shape

## Tasks

- [x] add `dev.trentdb.planner.Binder`
- [x] add `BoundStatement`
- [x] add `BoundSelectStatement`
- [x] add bound table reference representation
- [x] add bound select item or bound expression representation for star and column references
- [x] bind `SELECT * FROM table`
- [x] bind explicit column references
- [x] reject missing tables through catalog lookup
- [x] reject missing columns explicitly
- [x] add `dev.trentdb.planner.logical.LogicalOperator`
- [x] add `LogicalGet`
- [x] add `LogicalProjection`
- [x] convert bound select statements to a logical plan skeleton
- [x] add binder/logical planning tests for supported `SELECT` forms
- [x] run `mvn test`

## Notes

- Keep catalog access transaction-aware.
- Keep the read path WAL-free.
- Preserve room for MVCC by avoiding direct global catalog reads.
- Mirror DuckDB naming and flow where it fits Java cleanly.

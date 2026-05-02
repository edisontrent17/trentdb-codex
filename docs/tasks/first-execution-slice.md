# Vectorized Execution Slice

## Goal

Execute simple read-only logical plans through a DuckDB-shaped vectorized pipeline.

DuckDB is not a tuple-at-a-time Volcano iterator engine. The first execution path should move `DataChunk`s through a source/operator/sink pipeline. This slice keeps storage in-memory and non-durable, but execution is chunk-oriented from the start.

## Scope

- add vector and data chunk substrate
- add in-memory table storage that scans as `DataChunk`s
- add push-style physical pipeline pieces
- execute `LogicalGet`
- execute `LogicalFilter`
- execute `LogicalProjection`
- execute `LogicalExplain`
- evaluate bound column references vector-wise
- evaluate literals vector-wise
- evaluate comparison and boolean predicates vector-wise
- evaluate `lower(text)` vector-wise
- evaluate arithmetic expressions vector-wise
- collect final chunks into a test-friendly `QueryResult`

## Supported Queries

- `SELECT * FROM people`
- `SELECT id, name FROM people`
- `SELECT id + 1 AS next_id FROM people`
- `SELECT id FROM people WHERE id = 1`
- `SELECT name FROM people WHERE id + 1 = 2`
- `SELECT lower(name) FROM people`
- `SELECT * FROM people LIMIT 1`
- `EXPLAIN SELECT id FROM people WHERE id = 1`

## Out of Scope

- durable storage
- WAL
- real MVCC visibility
- optimized physical planning
- joins
- aggregates
- `ORDER BY`
- specialized primitive vectors
- selection vectors as a standalone abstraction

## Tasks

- [x] add vector package
- [x] add `Vector`
- [x] add `DataChunk`
- [x] add `VectorType`
- [x] add `SelectionVector`
- [x] add constant vector support for literals
- [x] add dictionary vector support for slices
- [x] add `StorageManager`
- [x] add `InMemoryTableStorage`
- [x] scan in-memory data as chunks
- [x] add `QueryResult`
- [x] add chunk push interfaces
- [x] add `QueryExecutor`
- [x] add table scan source
- [x] add filter operator
- [x] add projection operator
- [x] add result sink
- [x] add vector expression evaluator
- [x] execute `LogicalGet`
- [x] execute `LogicalFilter`
- [x] execute `LogicalProjection`
- [x] execute `LogicalExplain`
- [x] add end-to-end execution tests
- [x] run `mvn test`

## Follow-Up Refactor

- [x] replace boolean filter arrays with `SelectionVector`
- [x] make filter forward unchanged chunks when all rows pass
- [x] make filter slice chunks through dictionary vectors when some rows pass
- [x] rerun `mvn test`

## Null Semantics Refactor

- [x] add `ValidityMask`
- [x] add vector null checks and null mutation
- [x] make constant vectors carry null validity
- [x] make dictionary vectors resolve child nulls through selection
- [x] make comparisons with `NULL` produce `NULL`
- [x] make filter pass only exact `TRUE`
- [x] add SQL three-valued logic for `AND` and `OR`
- [x] rerun `mvn test`

## Read SQL Follow-Up

- [x] add streaming `PhysicalLimit`
- [x] add projection aliases
- [x] bind arithmetic expressions in `SELECT` and `WHERE`
- [x] execute arithmetic expressions in vectorized projections and filters
- [x] add tests for limit, aliases, arithmetic, and null propagation

## Notes

- Keep this read-only and WAL-free.
- Keep storage in-memory and test-controlled.
- This is vectorized structurally, but still uses boxed Java values until primitive vectors are worth adding.
- Filtering now mirrors DuckDB's selection/dictionary shape more closely, but null masks and primitive flat vectors are still future work.

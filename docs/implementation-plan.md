# Trent Db Implementation Plan

## Goal

Build a production-oriented analytical SQL database in Java that follows DuckDB's overall architecture while preserving Postgres-like SQL behavior for the supported subset.

This project is not a line-by-line port. The target is:

- DuckDB-shaped architecture
- Java-native implementation choices
- Postgres-compatible semantics for supported SQL
- deliberate scope control without throwaway shortcuts
- production-grade correctness, durability, recovery, and maintainability as design goals

## Reference Systems

DuckDB is the architectural reference. The checked-out source at `/home/ubuntu/duckdb` is the implementation guide for subsystem boundaries, data flow, and operator design.

Postgres is the behavior reference. When behavior is visible to users, the default rule is:

- match Postgres if the feature is supported
- reject unsupported behavior explicitly
- avoid "almost works" semantics

## Architectural Throughline

The intended pipeline is:

`SQL -> AST -> bound tree -> logical plan -> optimizer -> physical plan -> vectorized execution`

That maps cleanly to the DuckDB source layout:

- `src/parser` -> parser and SQL transformation
- `src/catalog` -> schemas, tables, and functions
- `src/planner` -> binder plus logical operators
- `src/optimizer` -> rule-based rewrites
- `src/execution` -> physical operators and execution engine
- `src/common/vector` and `src/common/types` -> vector model and type system
- `src/storage` -> table storage and persistence concerns
- `src/transaction` -> transaction state, commit protocol, and visibility rules
- `src/wal` or `src/storage/wal` -> write-ahead log records, flushing, and recovery

## Project Principles

- Prefer a smaller correct subset over shallow broad coverage.
- Mirror DuckDB concepts when they provide the right production shape.
- Favor Java records, sealed interfaces, and explicit immutable state in planner layers.
- Keep execution hot paths pragmatic, measurable, and open to lower-level optimization.
- Every supported feature must have both planner-level and behavior-level tests.
- Every durable write feature must have recovery tests.
- Unsupported features should fail explicitly and predictably.

## Read and Write Iteration Strategy

The project should advance incrementally by separating read-facing and write-facing work.

Read-only paths do not require WAL. Parser, binder, planner, optimizer, scans, expression evaluation, `SELECT`, `EXPLAIN`, and read-only catalog lookup can move forward against immutable or snapshot-like state.

Write paths do require a durability design. `CREATE TABLE`, `INSERT`, `DELETE`, `UPDATE`, `DROP TABLE`, and future schema changes must go through write-aware boundaries even before the first durable implementation exists.

Read paths also need a visibility design. WAL is not involved in reads, but production reads must run against an MVCC snapshot so concurrent writes and DDL do not change what a query can see while it is binding, planning, or executing.

Initial write implementations may be in-memory, but the API shape should leave room for:

- transaction begin/commit/rollback
- read snapshots and visibility checks
- WAL record generation before durable mutation
- commit record flushing
- recovery by replaying committed records
- catalog and table storage updates under the same commit protocol

The practical iteration model is:

1. Build read-only structures and query execution against stable interfaces.
2. Introduce transaction and snapshot objects early, even if the first implementation uses a trivial single-version snapshot.
3. Add write APIs as explicit storage/catalog operations, not direct mutations from parser or planner code.
4. Start with in-memory write behavior behind those APIs.
5. Add WAL and recovery before claiming writes are durable.
6. Add MVCC version chains, catalog versioning, and isolation rules before claiming production multi-client behavior.

## Near-Term Scope

The first complete vertical slice should support:

- `CREATE TABLE`
- `INSERT ... VALUES`
- `SELECT ... FROM ...`
- `WHERE`
- simple arithmetic and boolean expressions
- `ORDER BY`
- `LIMIT`
- `INNER JOIN`
- `GROUP BY`
- `sum`, `count`, `avg`, `min`, `max`
- `EXPLAIN`

The first vertical slice is successful when a query can move from SQL text to a materialized result through all engine layers without shortcuts.

## Module Plan

### 1. Parser and AST

Status: implemented for the current subset; ongoing hardening remains

Purpose:

- own the Postgres-flavored subset grammar
- decouple ANTLR parse trees from internal engine structures

Deliverables:

- stable AST model for statements, expressions, table refs, and types
- parser facade returning internal AST nodes only
- parse errors with line and column information
- negative tests for unsupported syntax

Constraints:

- no semantic checks here beyond syntax shape
- avoid leaking ANTLR types outside the parser package

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/parser`
- `/home/ubuntu/duckdb/src/parser/statement`
- `/home/ubuntu/duckdb/src/parser/expression`

### 2. Catalog and Type System

Status: initial implementation complete

Purpose:

- represent schemas, tables, columns, and built-in functions
- define the engine's logical type system
- support snapshot-aware catalog lookup once transactions exist

Deliverables:

- `Catalog`
- `SchemaCatalogEntry`
- `TableCatalogEntry`
- `ColumnDefinition`
- logical types: `BOOLEAN`, `INTEGER`, `BIGINT`, `DOUBLE`, `TEXT`, `NULL`
- duplicate-object and missing-object error model
- API shape for lookup under a transaction snapshot

Postgres parity rules:

- identifier lookup should respect unquoted lowercase folding
- quoted identifiers must preserve case
- basic type names should match Postgres naming where supported

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/catalog`
- `/home/ubuntu/duckdb/src/common/types`
- `/home/ubuntu/duckdb/src/function`

### 3. Execution Data Model

Status: initial vectorized substrate implemented

Purpose:

- establish the data structures every operator uses

Deliverables:

- `Vector`
- flat and constant vector behavior
- `SelectionVector`
- `DataChunk`
- null mask representation
- logical-to-physical Java storage mapping

Implementation direction:

- current vectors use boxed Java values with explicit validity tracking
- dictionary-style slicing is available for filtered chunks
- primitive-specialized vectors are a future performance hardening step

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/common/vector`
- `/home/ubuntu/duckdb/src/common/vector_operations`
- `/home/ubuntu/duckdb/src/execution/expression_executor`

### 4. In-Memory Storage

Status: initial in-memory storage manager and table storage implemented

Purpose:

- store base tables behind a storage boundary compatible with vectorized scans and future durability

Deliverables:

- append-only table storage
- per-column segment or chunk storage
- table scan state
- insert path from bound values into storage
- write API shape that can be guarded by transactions and WAL

Non-goals for the first pass:

- MVCC
- disk persistence
- indexes

Important constraint:

- in-memory writes are allowed only as an incremental implementation detail; durable write semantics require WAL and recovery before being advertised as persistent

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/storage/table`
- `/home/ubuntu/duckdb/src/main/chunk_scan_state`

### 4a. Write-Ahead Log and Recovery

Purpose:

- prevent data loss and catalog/table corruption for committed DDL and DML

Deliverables:

- WAL record model for DDL and DML
- binary or structured record framing with length and checksum
- commit records and flush boundary
- recovery that replays complete committed records only
- checkpoint interface for bounding recovery time

Initial supported record types:

- `CREATE_TABLE`
- `INSERT_CHUNK` or `INSERT_VALUES`
- `COMMIT`

Design rule:

- no durable catalog or table mutation should bypass the WAL-capable write path

### 4b. Transactions and MVCC

Purpose:

- provide consistent read snapshots and atomic write visibility

Deliverables:

- `Transaction`
- `TransactionManager`
- read snapshot model
- commit timestamp or transaction id visibility rules
- catalog entry versioning for DDL visibility
- row versioning or append visibility metadata for table scans

Initial implementation direction:

- start with a single-threaded transaction manager and a trivial snapshot
- require read APIs to accept transaction or snapshot context before full MVCC exists
- add real row/catalog version visibility when concurrent writes are introduced

Design rule:

- reads do not write WAL, but they must not read uncommitted, partially committed, or future catalog/table state

### 5. Binder

Status: implemented for single-table reads, replacement scans, projection, filters, aliases, scalar `lower`, arithmetic, `LIMIT`, and `EXPLAIN`

Purpose:

- resolve names and types and convert AST to a bound tree

Deliverables:

- table and column name resolution
- alias handling
- star expansion
- aggregate legality checks
- scalar vs aggregate expression separation
- implicit cast insertion for a small supported set

Postgres parity rules:

- column ambiguity should fail explicitly
- invalid aggregate usage should fail explicitly
- `GROUP BY` semantics should match Postgres for supported cases

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/planner/binder`
- `/home/ubuntu/duckdb/src/planner/expression_binder`

### 6. Logical Planning

Status: implemented for scan, filter, projection, limit, and explain

Purpose:

- convert bound queries into a logical operator tree

Deliverables:

- logical scan
- filter
- projection
- aggregate
- join
- order
- limit
- explain

Design rule:

- logical operators are immutable and easy to print for tests

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/planner/operator`

### 7. Optimizer

Purpose:

- introduce a small, understandable rule-based optimizer

Deliverables:

- constant folding
- trivial predicate simplification
- filter pushdown through projections and joins where safe
- projection pruning

Non-goals for the first pass:

- cost model
- join reordering
- statistics-driven optimization

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/optimizer`
- `/home/ubuntu/duckdb/src/optimizer/pushdown`
- `/home/ubuntu/duckdb/src/optimizer/rule`

### 8. Physical Planning and Operators

Status: implemented for table/replacement scan, filter, projection, limit, explain, and result collection

Purpose:

- lower logical operators into executable physical operators

Deliverables:

- physical table scan
- physical filter
- physical projection
- physical hash aggregate
- physical hash join
- physical order or top-N path
- physical limit
- materializing result collector

Execution rule:

- every operator should consume and produce `DataChunk`

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/execution/operator`
- `/home/ubuntu/duckdb/src/execution/physical_plan`
- `/home/ubuntu/duckdb/src/execution/nested_loop_join`

### 9. Function and Expression Execution

Status: implemented for column refs, literals, comparisons, SQL boolean logic, `lower(text)`, and arithmetic expressions

Purpose:

- evaluate scalar and aggregate expressions over vectors

Deliverables:

- arithmetic evaluators
- comparison evaluators
- boolean evaluators with SQL null semantics
- aggregate state implementations
- built-in scalar and aggregate function registry

Postgres parity rules:

- three-valued boolean logic must be preserved
- null propagation must match SQL semantics

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/execution/expression_executor`
- `/home/ubuntu/duckdb/src/function/scalar`
- `/home/ubuntu/duckdb/src/function/aggregate`

### 10. Explainability and Testing

Purpose:

- keep the system debuggable and production-maintainable

Deliverables:

- `EXPLAIN` output for logical and physical plans
- plan-shape assertions in tests
- behavior tests for supported SQL
- error-message tests for unsupported or invalid SQL

Test layers:

- parser tests
- binder tests
- planner tests
- execution tests
- end-to-end SQL tests

## Delivery Phases

### Phase A: Vertical Slice

Ship a minimal end-to-end path for:

- single-table `SELECT`
- `WHERE`
- projection
- `LIMIT`

Exit criteria:

- SQL text reaches execution without bypassing binder or planner
- `EXPLAIN` can print the logical and physical tree

Status: complete for the current read path. Implemented single-table catalog scans, replacement scans, projection, aliases, `WHERE`, scalar `lower`, arithmetic, `LIMIT`, logical `EXPLAIN`, and vectorized physical execution.

### Phase B: DML and Base Storage

Ship:

- `CREATE TABLE`
- `INSERT ... VALUES`
- table scan over stored data

Exit criteria:

- inserted rows persist in process memory and can be scanned repeatedly

### Phase C: Aggregation

Ship:

- `GROUP BY`
- aggregate functions
- aggregate validation in binder

Exit criteria:

- grouped queries return correct results and reject invalid select lists

### Phase D: Join Processing

Ship:

- `INNER JOIN`
- join condition binding
- hash join physical operator

Exit criteria:

- multi-table queries use a real join operator rather than nested ad hoc logic

### Phase E: Optimizer Passes

Ship:

- constant folding
- filter pushdown
- projection pruning

Exit criteria:

- `EXPLAIN` shows optimization effects clearly

### Phase F: Postgres Compatibility Hardening

Ship:

- semantics-focused regression tests
- identifier and null-semantics coverage
- unsupported-feature errors made consistent

Exit criteria:

- supported subset is stable enough for regression comparison against Postgres behavior

## Suggested Java Package Layout

Keep the package layout close to the engine pipeline:

- `dev.trentdb.parser`
- `dev.trentdb.ast`
- `dev.trentdb.catalog`
- `dev.trentdb.types`
- `dev.trentdb.storage`
- `dev.trentdb.binder`
- `dev.trentdb.logical`
- `dev.trentdb.optimizer`
- `dev.trentdb.physical`
- `dev.trentdb.execution`
- `dev.trentdb.function`
- `dev.trentdb.testing`

## Immediate Next Steps

The next implementation sequence should be:

1. Add production-facing write interfaces for catalog and storage mutations before implementing durable DDL/DML.
2. Design the WAL record model for `CREATE_TABLE`, `INSERT_VALUES` or `INSERT_CHUNK`, and `COMMIT`.
3. Add recovery tests that define committed, uncommitted, and partially written WAL behavior.
4. Harden CSV replacement scans behind the generic replacement scan abstraction with quote-aware parsing and type inference.
5. Add more SQL execution surface: `ORDER BY`, aggregates, and joins, each through bound/logical/physical layers.
6. Introduce optimizer scaffolding after unoptimized execution behavior is covered by tests.

## Explicit Non-Goals For Now

- transactions
- persistence
- indexes
- concurrency
- distributed execution
- full SQL coverage
- exact internal parity with DuckDB's C++ implementation details

These can be revisited later, but they should not dilute the production storage, transaction, and execution boundaries.

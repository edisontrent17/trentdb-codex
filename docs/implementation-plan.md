# DuckDB-in-Java Implementation Plan

## Goal

Build an educational analytical database engine in Java that follows DuckDB's overall architecture while preserving Postgres-like SQL behavior for the supported subset.

This project is not a line-by-line port. The target is:

- DuckDB-shaped architecture
- Java-native implementation choices
- Postgres-compatible semantics for supported SQL
- deliberate scope control so the system remains understandable

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

## Project Principles

- Keep the code readable enough for a student to follow end to end.
- Prefer a smaller correct subset over shallow broad coverage.
- Mirror DuckDB concepts when they teach the right shape.
- Favor Java records, sealed interfaces, and explicit immutable state in planner layers.
- Keep execution hot paths pragmatic, with room for lower-level optimization later.
- Every supported feature must have both planner-level and behavior-level tests.

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

Status: started

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

Purpose:

- represent schemas, tables, columns, and built-in functions
- define the engine's logical type system

Deliverables:

- `Catalog`
- `SchemaCatalogEntry`
- `TableCatalogEntry`
- `ColumnDefinition`
- logical types: `BOOLEAN`, `INTEGER`, `BIGINT`, `DOUBLE`, `TEXT`, `NULL`
- duplicate-object and missing-object error model

Postgres parity rules:

- identifier lookup should respect unquoted lowercase folding
- quoted identifiers must preserve case
- basic type names should match Postgres naming where supported

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/catalog`
- `/home/ubuntu/duckdb/src/common/types`
- `/home/ubuntu/duckdb/src/function`

### 3. Execution Data Model

Purpose:

- establish the data structures every operator uses

Deliverables:

- `Vector`
- `FlatVector`
- `ConstantVector`
- `SelectionVector`
- `DataChunk`
- null mask representation
- logical-to-physical Java storage mapping

Implementation direction:

- fixed-width vectors backed by primitive arrays
- text values backed initially by `String[]`
- chunks sized for readability first, then tunable later

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/common/vector`
- `/home/ubuntu/duckdb/src/common/vector_operations`
- `/home/ubuntu/duckdb/src/execution/expression_executor`

### 4. In-Memory Storage

Purpose:

- store base tables in a columnar format compatible with vectorized scans

Deliverables:

- append-only table storage
- per-column segment or chunk storage
- table scan state
- insert path from bound values into storage

Non-goals for the first pass:

- MVCC
- WAL
- disk persistence
- indexes

DuckDB reference areas:

- `/home/ubuntu/duckdb/src/storage/table`
- `/home/ubuntu/duckdb/src/main/chunk_scan_state`

### 5. Binder

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

- keep the system debuggable and educational

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

- supported subset is stable enough for educational comparison against Postgres

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

1. Finish parser hardening with negative tests and a few missing expression cases.
2. Add the logical type system and catalog skeleton.
3. Build the vector and `DataChunk` substrate before deep planner work.
4. Add a binder for single-table queries.
5. Implement a minimal logical planner and physical table scan path.
6. Add end-to-end tests for `CREATE TABLE`, `INSERT`, and single-table `SELECT`.

## Explicit Non-Goals For Now

- transactions
- persistence
- indexes
- concurrency
- extensions
- distributed execution
- full SQL coverage
- exact internal parity with DuckDB's C++ implementation details

These can be revisited later, but they should not dilute the first educational engine slice.

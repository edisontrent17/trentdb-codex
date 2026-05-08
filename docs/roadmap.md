# Roadmap

## Principle

Build incrementally, but keep production boundaries from the beginning.

For supported features, target DuckDB-compatible behavior. For architecture, follow DuckDB's end-to-end shape:

`SQL -> AST -> bound tree -> logical plan -> optimized plan -> physical plan -> vectorized execution`

The current product target is an in-memory analytical engine first. The priority is a correct DuckDB-shaped SQL pipeline, vectorized execution model, catalog, type system, storage abstractions, optimizer, and compatibility test suite. Durable writes are not implemented yet, but DDL and DML must remain behind write-aware boundaries that can enforce WAL, commit records, flush boundaries, and crash recovery before persistent visibility is advertised.

Read-only operations remain WAL-free and should operate against a consistent catalog and storage snapshot. Full multi-client MVCC is a later milestone, but APIs should not assume direct reads from unversioned global mutable state.

DuckDB source reference: `/home/manoj/Projects/duckdb`.

Near-term DuckDB areas to mirror:

- `src/planner/operator/logical_order.cpp`
- `src/planner/operator/logical_aggregate.cpp`
- `src/planner/operator/logical_join.cpp`
- `src/planner/operator/logical_insert.cpp`
- `src/planner/operator/logical_create_table.cpp`
- `src/execution/operator/order`
- `src/execution/operator/aggregate`
- `src/execution/operator/join`
- `src/execution/operator/schema`
- `src/execution/operator/persistent`
- `src/execution/operator/csv_scanner`

## Milestone 1: Parser and AST

Deliverables:

- Maven build with ANTLR integration
- DuckDB-compatible grammar for the initial subset
- Internal AST classes
- Parser facade and parser tests

Status:

- implemented for the current subset

## Milestone 2: Catalog and Types

Deliverables:

- schema and table metadata
- column definitions and logical types
- simple error model for duplicate/missing objects
- API boundaries for read-only lookup versus write catalog mutation
- lookup APIs that can accept transaction/snapshot context

Status:

- initial implementation complete

## Milestone 3: Storage and Write Boundaries

Deliverables:

- storage manager abstraction
- table storage abstraction
- explicit in-memory DDL/DML write APIs
- in-memory implementation for early iteration
- no parser, binder, planner, or execution code directly mutates table internals
- transaction/snapshot types, initially trivial

Status:

- initial storage manager, in-memory table storage, transaction object, and snapshot placeholder are implemented
- higher-level `CREATE TABLE` and `INSERT ... VALUES` execution paths are pending

## Milestone 4: MVCC-Aware Read Shape

Deliverables:

- read snapshot object
- catalog visibility hooks for DDL
- table scan visibility hooks for row versions
- tests proving a query reads through a stable snapshot API, even before concurrent MVCC is complete

Status:

- transaction/snapshot API shape exists
- real catalog and row-version visibility rules are pending

## Milestone 5: In-Memory Columnar Storage

Deliverables:

- append-only in-memory tables
- chunked columnar layout
- primitive-backed storage for fixed-width types
- basic variable-width storage strategy for text
- initial row visibility metadata or a placeholder that preserves the API shape

Status:

- initial appendable in-memory table storage and chunk scans are implemented
- primitive-specialized storage and real row visibility metadata are pending

## Milestone 6: Binder

Deliverables:

- name resolution
- type resolution
- aggregate legality checks
- alias resolution rules
- catalog resolution through transaction/snapshot context

Status:

- implemented for table reads, replacement scans, star expansion, projection aliases, `WHERE`, `HAVING`, scalar functions including DuckDB-style `EXTRACT(... FROM date)` via `date_part`, arithmetic, `IN`, `LIKE`, `CASE`, casts, dates, intervals, `LIMIT`, and `EXPLAIN`
- grouped and ungrouped aggregate binding includes distinct aggregate arguments for the supported aggregate functions
- `ORDER BY` binding is implemented for expressions, aliases, and select-list positions
- grouped and ungrouped aggregate binding is implemented
- explicit `INNER JOIN` and `LEFT OUTER JOIN` binding is implemented for left-deep multi-join trees with ambiguity handling
- derived table binding is implemented for non-correlated subqueries in `FROM`, including explicit output column aliases
- non-recursive common table expression binding is implemented for read queries
- non-correlated scalar subqueries and `IN`/`NOT IN` subqueries are implemented

## Milestone 7: Logical Planning

Deliverables:

- logical scan
- filter
- projection
- aggregate
- join
- limit
- explain

Status:

- implemented for scan, filter, projection, aggregate, `HAVING` as a post-aggregate filter, inner and left joins, derived tables, non-recursive common table expressions, order, limit, and explain
- optimizer-facing rewrites are pending

## Milestone 8: Execution Substrate

Deliverables:

- `Vector`
- `SelectionVector`
- `DataChunk`
- expression evaluator

This is the first deep execution milestone.

Status:

- implemented with `Vector`, `ValidityMask`, `SelectionVector`, dictionary slicing, `DataChunk`, and expression evaluation
- primitive-specialized vectors remain a future performance step

## Milestone 9: Physical Operators

Deliverables:

- table scan
- filter
- projection
- result collector
- limit
- hash aggregate
- hash join

Status:

- implemented for table/replacement scan, filter, projection, hash aggregate, hash join, nested loop join, order, limit, explain, and result collection
- top-N is pending

## Milestone 10: WAL and Recovery

Deliverables:

- WAL record model for committed DDL and DML
- flush and commit protocol
- startup recovery
- crash/recovery tests
- checkpoint interface

Status:

- not implemented yet
- required before persistent DDL or DML can be advertised as durable
- current catalog, storage, and transaction APIs should stay compatible with WAL-backed writes

## Milestone 11: Full MVCC and Isolation

Deliverables:

- transaction ids or timestamps
- row version visibility
- catalog version visibility
- atomic DDL/DML commit visibility
- cleanup strategy for obsolete versions

Status:

- deferred; keep snapshot-shaped APIs, but do not implement full multi-client isolation yet

## Milestone 12: Optimizer

Deliverables:

- constant folding
- filter pushdown
- projection pruning
- eligible subquery rewrites

Status:

- pending

## Milestone 13: Compatibility and Reliability Tests

Deliverables:

- DuckDB-style SQL behavior tests for the supported subset
- plan-shape assertions for `EXPLAIN`
- recovery tests for committed writes
- corruption/partial-WAL handling tests
- concurrency tests once multi-client execution exists

Status:

- parser, binder, planner, and read execution tests are in place for the implemented subset
- generated CSV compatibility coverage exists for TPC-H Q1, Q3, Q5, Q6, Q7, Q8, Q9, Q10, Q11, Q12, Q13, Q14, Q15, Q16, Q18, and Q19
- broader DuckDB-style behavior tests, plan-shape tests, and execution edge cases remain near-term priorities
- recovery, corruption, and concurrency tests depend on the WAL, persistence, and MVCC milestones

# Roadmap

## Principle

Build incrementally, but keep production boundaries from the beginning.

For supported features, target Postgres-like behavior. For architecture, follow DuckDB's end-to-end shape:

`SQL -> AST -> bound tree -> logical plan -> optimized plan -> physical plan -> vectorized execution`

Read-only features can advance without WAL. They should still be shaped around transaction snapshots so MVCC can provide consistent catalog and table visibility. Write features must go through explicit catalog/storage/transaction boundaries so WAL, recovery, and MVCC visibility can be added without redesigning call sites.

## Milestone 1: Parser and AST

Deliverables:

- Maven build with ANTLR integration
- Postgres-flavored grammar for the initial subset
- Internal AST classes
- Parser facade and parser tests

Status:

- scaffolded

## Milestone 2: Catalog and Types

Deliverables:

- schema and table metadata
- column definitions and logical types
- simple error model for duplicate/missing objects
- API boundaries for read-only lookup versus write catalog mutation
- lookup APIs that can accept transaction/snapshot context

## Milestone 3: Storage and Write Boundaries

Deliverables:

- storage manager abstraction
- table storage abstraction
- explicit DDL/DML write APIs
- in-memory implementation for early iteration
- no direct durable mutation outside storage/catalog write boundaries
- transaction/snapshot types, initially trivial

## Milestone 4: MVCC-Aware Read Shape

Deliverables:

- read snapshot object
- catalog visibility hooks for DDL
- table scan visibility hooks for row versions
- tests proving a query reads through a stable snapshot API, even before concurrent MVCC is complete

## Milestone 5: In-Memory Columnar Storage

Deliverables:

- append-only in-memory tables
- chunked columnar layout
- primitive-backed storage for fixed-width types
- basic variable-width storage strategy for text
- initial row visibility metadata or a placeholder that preserves the API shape

## Milestone 6: Binder

Deliverables:

- name resolution
- type resolution
- aggregate legality checks
- alias resolution rules
- catalog resolution through transaction/snapshot context

## Milestone 7: Logical Planning

Deliverables:

- logical scan
- filter
- projection
- aggregate
- join
- limit
- explain

## Milestone 8: Execution Substrate

Deliverables:

- `Vector`
- `SelectionVector`
- `DataChunk`
- expression evaluator

This is the first deep execution milestone.

## Milestone 9: Physical Operators

Deliverables:

- table scan
- filter
- projection
- result collector
- limit
- hash aggregate
- hash join

## Milestone 10: WAL and Recovery

Deliverables:

- WAL record model for committed DDL and DML
- flush and commit protocol
- startup recovery
- crash/recovery tests
- checkpoint interface

## Milestone 11: Full MVCC and Isolation

Deliverables:

- transaction ids or timestamps
- row version visibility
- catalog version visibility
- atomic DDL/DML commit visibility
- cleanup strategy for obsolete versions

## Milestone 12: Optimizer

Deliverables:

- constant folding
- filter pushdown
- projection pruning

## Milestone 13: Compatibility and Reliability Tests

Deliverables:

- Postgres-style SQL behavior tests for the supported subset
- plan-shape assertions for `EXPLAIN`
- recovery tests for committed writes
- corruption/partial-WAL handling tests
- concurrency tests once multi-client execution exists

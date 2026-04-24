# Roadmap

## Principle

Keep broad engine coverage, but spend the deepest implementation effort on execution.

For supported features, target Postgres-like behavior. For architecture, follow DuckDB's end-to-end shape:

`SQL -> AST -> bound tree -> logical plan -> optimized plan -> physical plan -> vectorized execution`

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

## Milestone 3: In-Memory Columnar Storage

Deliverables:

- append-only in-memory tables
- chunked columnar layout
- primitive-backed storage for fixed-width types
- basic variable-width storage strategy for text

## Milestone 4: Binder

Deliverables:

- name resolution
- type resolution
- aggregate legality checks
- alias resolution rules

## Milestone 5: Logical Planning

Deliverables:

- logical scan
- filter
- projection
- aggregate
- join
- limit
- explain

## Milestone 6: Execution Substrate

Deliverables:

- `Vector`
- `SelectionVector`
- `DataChunk`
- expression evaluator

This is the first deep execution milestone.

## Milestone 7: Physical Operators

Deliverables:

- table scan
- filter
- projection
- result collector
- limit
- hash aggregate
- hash join

## Milestone 8: Optimizer

Deliverables:

- constant folding
- filter pushdown
- projection pruning

## Milestone 9: Compatibility Tests

Deliverables:

- Postgres-style SQL behavior tests for the supported subset
- plan-shape assertions for `EXPLAIN`

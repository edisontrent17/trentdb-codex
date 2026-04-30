# EXPLAIN Slice

## Goal

Add first logical `EXPLAIN` semantics.

This slice binds and plans `EXPLAIN` around already-supported statements. It does not execute `EXPLAIN` into result rows yet.

## Scope

- bind `EXPLAIN SELECT ...`
- recursively bind the inner statement
- add `LogicalExplain`
- recursively plan the inner statement
- add a stable logical plan printer for tests

## Tasks

- [x] add `BoundExplainStatement`
- [x] bind `ExplainStatement`
- [x] add `LogicalExplain`
- [x] plan `EXPLAIN` by wrapping the child logical plan
- [x] add logical plan printer
- [x] add EXPLAIN tests
- [x] run `mvn test`

## Notes

- Keep this read-only and WAL-free.
- Do not add physical execution in this slice.
- The output format is internal and test-stable, not a final user-facing explain format.

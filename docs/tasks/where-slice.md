# WHERE Slice

## Goal

Add the first read-only `WHERE` path in the binder and logical plan.

This slice does not execute predicates. It binds supported predicates and produces a DuckDB-shaped logical plan: projection over filter over get.

## Scope

- support simple column references in predicates
- support literal values in predicates
- support comparison predicates such as `id = 1`
- support boolean `AND` and `OR`
- reject arithmetic and unsupported predicate expressions explicitly
- add `LogicalFilter`

## Tasks

- [x] add bound literal expression
- [x] add bound binary expression
- [x] add optional bound `WHERE` expression to `BoundSelectStatement`
- [x] bind column references in `WHERE`
- [x] bind literals in `WHERE`
- [x] bind comparison predicates in `WHERE`
- [x] bind `AND` and `OR` predicates in `WHERE`
- [x] reject unsupported `WHERE` expressions explicitly
- [x] add `LogicalFilter`
- [x] plan `Projection(Filter(Get))` when `WHERE` is present
- [x] add binder/logical planning tests
- [x] run `mvn test`

## Notes

- Keep this read-only and WAL-free.
- Keep catalog access transaction-aware.
- Do not add physical execution in this slice.

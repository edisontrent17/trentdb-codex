# Read SQL Slice

## Goal

Deepen read-only SQL support over in-memory catalog tables and CSV replacement scans while staying close to DuckDB's architecture.

## Priorities

1. Improve CLI usability with multiline SQL terminated by semicolon.
2. Add `LIMIT` as a streaming physical operator.
3. Add projection aliases for output column names.
4. Add arithmetic expressions after the simple read path is stable.
5. Harden CSV parsing after SQL flow is easier to exercise manually.

## DuckDB Alignment

- Keep parser -> binder -> logical plan -> physical plan -> execution.
- Add SQL features through bound nodes, logical operators, and physical operators rather than special-casing in the CLI.
- Keep read paths WAL-free.
- Keep replacement scans generic and avoid CSV-specific logical/physical operators.

## Current Supported Read Behavior

- `SELECT *` and explicit projection from catalog tables
- file path replacement scans such as `SELECT * FROM 'people.csv'`
- projection aliases
- `WHERE` comparisons and SQL three-valued boolean logic
- scalar `lower(text)`
- arithmetic expressions in projections and predicates
- streaming `LIMIT`
- logical `EXPLAIN`

## Remaining Read Gaps

- quote-aware and type-aware CSV scanning
- `ORDER BY`
- aggregates and `GROUP BY`
- joins
- optimizer passes such as constant folding, filter pushdown, and projection pruning

## Tasks

- [x] implement multiline CLI statement input ending in semicolon
- [x] add CLI smoke verification
- [x] bind `LIMIT`
- [x] add `LogicalLimit`
- [x] add `PhysicalLimit`
- [x] add `LIMIT` tests
- [x] add projection aliases
- [x] add alias tests
- [x] bind arithmetic expressions in `SELECT` and `WHERE`
- [x] execute arithmetic expressions in vectorized projections and filters
- [x] add arithmetic expression tests
- [x] run `mvn test`

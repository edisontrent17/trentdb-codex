# Trent Db

Production-oriented analytical SQL database in Java, using DuckDB as both the architectural reference and the semantic reference for the supported SQL subset.

## Current scope

The current implementation includes:

- ANTLR-based SQL parser
- DuckDB-compatible subset grammar
- Internal AST layer decoupled from ANTLR
- catalog metadata for schemas, tables, columns, and logical types
- transaction and snapshot API placeholders for future MVCC visibility
- in-memory table storage behind a storage manager boundary
- DuckDB-shaped binder -> logical plan -> physical plan -> vectorized execution flow
- DuckDB-shaped physical operator model with source, operator, and sink behavior
- replacement scan registry for native-feeling file path scans such as `SELECT * FROM 'people.csv'`
- CLI entry point for exercising simple read queries
- TPC-H compatibility coverage for the supported query subset

Supported parser coverage currently includes:

- `CREATE TABLE`
- `INSERT ... VALUES`
- `SELECT ... FROM ...`
- `WHERE`
- `GROUP BY`
- `ORDER BY`
- `LIMIT`
- `INNER JOIN`
- `EXPLAIN`

Supported execution coverage currently includes:

- single-table catalog scans
- CSV path replacement scans through the generic replacement scan interface
- projection and aliases
- `WHERE` filters
- SQL three-valued boolean logic for `AND`, `OR`, and comparisons with `NULL`
- scalar `lower(text)`
- arithmetic expressions in `SELECT` and `WHERE`
- `IN`, `NOT IN`, `LIKE`, `NOT LIKE`, `CASE`, date literals, interval literals, and casts
- grouped and ungrouped aggregates: `count`, `sum`, `min`, `max`, `avg`
- single and multiple explicit `INNER JOIN` queries through physical join operators
- non-correlated scalar subqueries and `IN`/`NOT IN` subqueries
- `ORDER BY` with DuckDB default null ordering
- streaming `LIMIT`
- logical and physical `EXPLAIN`

Current TPC-H coverage from generated scale 0.01 CSV fixtures:

- Q1
- Q3
- Q5
- Q6
- Q10
- Q12
- Q14
- Q19

Writes are intentionally not advertised as durable yet. DDL and DML must move through write-aware catalog, storage, transaction, WAL, and recovery boundaries before this project claims persistent write safety.

## Build

Run the test suite with:

```bash
mvn test
```

Run the CLI with:

```bash
mvn exec:java
```

## Design rule

For the supported subset, syntax and semantics should follow DuckDB as closely as practical. Architecture should stay recognizable to someone familiar with DuckDB's parser, binder, logical planning, physical planning, and vectorized execution pipeline.

Unsupported features should fail explicitly during parsing, binding, or planning rather than degrade silently.

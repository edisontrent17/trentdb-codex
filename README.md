# Trent Db

Learning-oriented analytical database engine in Java, architecturally inspired by DuckDB and constrained to Postgres-like behavior for the supported subset.

## Current scope

The project is starting with:

- ANTLR-based SQL parser
- Postgres-flavored subset grammar
- Internal AST layer decoupled from ANTLR
- Test scaffold for parser coverage

The parser currently targets:

- `CREATE TABLE`
- `INSERT ... VALUES`
- `SELECT ... FROM ...`
- `WHERE`
- `GROUP BY`
- `ORDER BY`
- `LIMIT`
- `INNER JOIN`
- `EXPLAIN`

## Build

This repository is scaffolded for Maven and ANTLR.

Expected commands once a JDK and Maven are installed:

```bash
mvn test
```

## Design rule

For the supported subset, syntax and semantics should follow Postgres as closely as practical.

Unsupported features should fail explicitly during parsing, binding, or planning rather than degrade silently.

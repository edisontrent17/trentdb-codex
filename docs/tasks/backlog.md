# Backlog

## Next

- finish catalog and type system
- separate catalog read lookup from catalog write mutation APIs
- introduce transaction and snapshot objects, initially with trivial single-version visibility
- introduce storage manager and table storage boundaries
- define WAL-facing write operation interfaces before persistent writes
- add in-memory table metadata and storage skeleton
- add binder for table and column resolution
- add logical operator tree for scan, filter, projection, and explain

## Soon

- connect `CREATE TABLE` AST to catalog registration
- connect `INSERT` target resolution to catalog lookup
- route `CREATE TABLE` and `INSERT` through explicit write APIs, even while backed by memory
- make binder/catalog lookup accept a transaction or read snapshot context
- add vector, selection vector, and data chunk substrate
- add end-to-end tests for parse -> catalog -> lookup flow
- draft WAL record model for `CREATE_TABLE`, `INSERT_VALUES`, and `COMMIT`
- draft MVCC visibility model for catalog entries and append-only table rows

## Later

- implement WAL append, flush, and recovery
- add checkpoint manager
- add full transaction manager and MVCC visibility rules
- add catalog versioning for DDL
- add row version cleanup/vacuum strategy
- logical planning for aggregates and joins
- physical operators for scan, filter, projection, limit
- hash aggregate
- hash join
- optimizer passes: constant folding, filter pushdown, projection pruning
- richer Postgres-compatibility tests

## Deferred

- persistence
- indexes
- concurrency
- extensions
- distributed execution

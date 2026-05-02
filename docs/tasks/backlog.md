# Backlog

## Next

- define WAL-facing write operation interfaces before persistent writes
- separate catalog write mutation APIs from direct in-memory registration
- route `CREATE TABLE` through a write-aware catalog operation
- route `INSERT ... VALUES` through a write-aware storage operation
- draft WAL record model for `CREATE_TABLE`, `INSERT_VALUES` or `INSERT_CHUNK`, and `COMMIT`
- define recovery behavior for complete committed records, incomplete transactions, and partial records
- harden CSV replacement scans with quote-aware parsing and type inference

## Soon

- route `CREATE TABLE` and `INSERT` through explicit write APIs, even while backed by memory
- draft MVCC visibility model for catalog entries and append-only table rows
- add catalog version placeholders for DDL visibility
- add row visibility placeholders to table scan state
- implement `ORDER BY` through bound/logical/physical layers
- implement aggregate binding and execution for `count`, `sum`, `min`, `max`, and `avg`
- implement inner joins through explicit logical and physical join operators

## Later

- implement WAL append, flush, and recovery
- add checkpoint manager
- add full transaction manager and MVCC visibility rules
- add catalog versioning for DDL
- add row version cleanup/vacuum strategy
- hash aggregate
- hash join
- order/top-N physical operators
- optimizer passes: constant folding, filter pushdown, projection pruning
- richer Postgres-compatibility tests

## Deferred

- persistence
- indexes
- concurrency
- distributed execution

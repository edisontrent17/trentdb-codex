# Decisions

## 2026-04-24

### DuckDB Structure

Mirror DuckDB's structure as closely as practical while expressing the design cleanly in Java.

Implications:

- preserve recognizable subsystem boundaries
- keep explicit metadata concepts such as `CatalogEntry`
- keep the parser -> binder -> logical plan -> optimizer -> physical plan -> execution pipeline intact
- prefer Java-native implementations over literal C++ transliteration
- keep simplifications behind stable interfaces that can be hardened later

### SQL Semantics

For supported SQL, prefer DuckDB-compatible behavior.

Implications:

- unsupported features should fail explicitly
- identifier, ordering, type coercion, function, and error behavior should follow DuckDB rules where supported
- semantic behavior matters more than copying internal implementation details exactly

## 2026-04-27

### Production Goal

The project is production-oriented.

Implications:

- correctness, durability, recovery, concurrency, observability, and maintainability are first-class design goals
- implementation remains incremental, but throwaway shortcuts should not leak into public subsystem contracts
- read-only features may advance independently from WAL
- durable writes must be designed around WAL and recovery before they are advertised as persistent

### Read and Write Split

Read operations and write operations should be designed as separate paths.

Implications:

- `SELECT`, `EXPLAIN`, binding, planning, optimization, and scans can operate without WAL
- DDL and DML are write operations and must eventually use WAL
- early in-memory write implementations are acceptable only behind write-aware catalog/storage APIs
- catalog changes and table data changes should share a commit protocol once transactions exist

### MVCC for Reads

Reads do not append WAL, but production reads must be snapshot-aware.

Implications:

- every query should eventually bind, plan, and execute against a transaction snapshot
- catalog lookup must become visibility-aware so DDL changes do not appear mid-query
- table scans must become visibility-aware so readers do not observe uncommitted or future row versions
- early single-threaded snapshots are acceptable as an API placeholder
- WAL handles durability; MVCC handles visibility and isolation

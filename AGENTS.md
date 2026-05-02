# AGENTS

## Project Intent

This repository is a production-oriented analytical SQL database in Java, with DuckDB as both the architectural reference and the semantic reference for the supported SQL subset.

The implementation can advance incrementally, but subsystem boundaries should be designed for production requirements: correctness, durability, crash recovery, concurrency, observability, and stable long-term maintenance.

## Architecture Rule

Mirror DuckDB's structure as closely as practical while expressing the design cleanly in Java.

That means:

- prefer DuckDB-shaped subsystem boundaries
- prefer DuckDB-style concepts and naming where they fit Java cleanly
- keep the end-to-end pipeline aligned with DuckDB: parser -> binder -> logical plan -> optimizer -> physical plan -> execution
- preserve explicit metadata layers such as catalog entries instead of collapsing everything into ad hoc objects
- simplify implementation details only behind stable interfaces that can be hardened without changing the surrounding architecture
- keep read paths and write paths explicitly separated where possible
- route DDL and DML through write-aware boundaries that can provide WAL, recovery, and transactional semantics

This is not a line-by-line port, but it should remain structurally recognizable to someone familiar with DuckDB's source tree.

## Behavior Rule

For supported SQL, prefer DuckDB-compatible behavior. If a feature is unsupported, fail explicitly rather than approximating it.

## Durability Rule

Read-only operations do not require WAL. They should operate against a consistent catalog and storage snapshot.

All write operations do require a durable write path. Before DDL or DML becomes persistent or visible as committed state, the design must account for write-ahead logging and crash recovery. Early implementations may keep storage in memory, but they should still use APIs that can later enforce:

- WAL record creation before durable mutation
- commit records and flush boundaries for write transactions
- recovery by replaying committed WAL records
- explicit handling for incomplete or failed writes

DDL is a write operation. Catalog changes such as `CREATE TABLE`, `DROP TABLE`, and future schema changes must follow the same durability discipline as row changes.

## MVCC Rule

Reads do not append WAL, but production reads must eventually be snapshot-aware.

The read path should be designed so `SELECT`, `EXPLAIN`, binding, planning, catalog lookup, and table scans can operate against a transaction snapshot. That snapshot determines which catalog entries and row versions are visible.

Write operations must coordinate durability and visibility:

- WAL protects committed writes from data loss
- MVCC protects readers from observing partial or future writes
- commits should make catalog and table changes visible atomically
- DDL must be versioned like data changes, so existing readers do not observe catalog mutation mid-query

Early single-threaded implementations may use a trivial snapshot, but APIs should not assume global mutable catalog or table state is directly readable without visibility checks.

## Coding Conventions

- prefer DuckDB naming and conceptual decomposition unless Java idioms clearly improve readability without obscuring the original structure
- prefer Java-native expression of DuckDB concepts over literal C++ transliteration
- keep subsystem boundaries explicit, even when a simpler shortcut would work for an early milestone
- avoid direct catalog or table mutation from parser, binder, planner, or execution code; use catalog/storage/transaction boundaries instead

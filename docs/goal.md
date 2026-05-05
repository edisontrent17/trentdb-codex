# Project Goal

Trent Db is a production-quality analytical SQL database in Java.

The goal is to reimplement DuckDB-shaped database architecture in Java while making Java-native engineering choices where they improve clarity, safety, or maintainability. The system should remain recognizable to someone familiar with DuckDB's source tree: parser, binder, logical planner, optimizer, physical planner, vectorized execution, catalog, type system, storage, and transaction-shaped APIs should stay explicit.

The near-term target is an in-memory analytical SQL engine with write APIs shaped for durability. Persistent DDL and DML must not be advertised as durable until WAL, commit records, flush boundaries, and crash recovery exist. Full multi-client MVCC can arrive later, but read and write APIs should keep snapshot and visibility boundaries explicit now.

This is not a tutorial project or throwaway prototype. Development may proceed incrementally, but subsystem boundaries must be suitable for production hardening: correctness, durability, crash recovery, concurrency, observability, and stable long-term maintenance.

For supported SQL, Trent Db should prefer DuckDB-compatible user-visible behavior. Unsupported features must fail explicitly rather than approximate semantics.

# Today - 2026-04-24

## Goals

- pivot project intent from educational to production-oriented
- preserve incremental delivery by separating read paths from write paths
- document that WAL is required for durable DDL and DML, not read-only operations
- document that reads still need MVCC snapshots for production consistency
- keep the build green throughout

## Tasks

- [ ] verify the rewritten Git history and current author identity
- [x] add `CatalogEntry` base abstraction
- [x] add `CatalogEntryType`
- [x] add `SchemaCatalogEntry`
- [x] add `TableCatalogEntry`
- [x] add `ColumnCatalogEntry`
- [x] add initial `Catalog` root object
- [x] add `LogicalType` and `LogicalTypeId`
- [x] map AST `TypeName` to engine logical types
- [x] add tests for catalog registration and lookup
- [x] add tests for duplicate table and missing table errors
- [x] update project intent in `AGENTS.md`
- [x] update implementation plan for production durability and read/write iteration
- [x] update roadmap milestones for storage boundaries, WAL, and recovery
- [x] update backlog for WAL-aware write APIs
- [x] update docs for MVCC-aware read snapshots
- [x] update the daily task list as work progresses

## Blockers

- none yet

## Notes

- mirror DuckDB's metadata structure closely while designing for production hardening
- prefer Postgres-compatible behavior for supported SQL
- avoid shortcuts that collapse catalog layers too early
- read-only operations can proceed without WAL
- DDL and DML must go through write-aware boundaries so WAL and recovery can protect committed writes
- production reads must use a transaction snapshot once MVCC is introduced
- DDL visibility is part of MVCC, not only table row visibility

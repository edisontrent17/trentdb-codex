# CSV Replacement Scan Slice

## Goal

Support ad hoc CSV scans with native-feeling SQL:

```sql
SELECT * FROM 'people.csv';
```

This should be modeled as a DuckDB-style replacement scan, not as a catalog table and not as a `read_csv` function.

## Scope

- parse string literal relation references in `FROM`
- add replacement scan registry
- add CSV replacement scan
- bind CSV replacement scans as table-like sources
- keep logical and physical scan operators generic
- execute CSV scan through existing vectorized pipeline

## Initial CSV Rules

- local file paths only
- header row required
- all columns are `TEXT`
- comma delimiter only
- basic quoted fields are supported only if Java CSV parsing is added later; first pass may be simple comma split

## Out of Scope

- catalog registration
- WAL
- DDL
- type inference
- compressed files
- remote files
- full RFC 4180 CSV handling
- projection pushdown
- filter pushdown

## Tasks

- [x] update parser grammar for string relation refs
- [x] update AST table reference shape
- [x] add replacement scan package
- [x] add replacement scan registry
- [x] add CSV replacement scan
- [x] keep logical scan generic through `LogicalGet`
- [x] update binder to resolve path refs through replacement scan registry
- [x] keep physical scan generic through `PhysicalTableScan`
- [x] update physical planner
- [x] add execution tests for `SELECT * FROM 'people.csv'`
- [x] run `mvn test`

## Notes

- Keep catalog table lookup unchanged for normal names.
- Replacement scans should become a future extension point.
- This is read-only and WAL-free.
- CSV-specific code is limited to the replacement scan provider, not the logical or physical operator tree.

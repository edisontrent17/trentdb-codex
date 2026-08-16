# DuckDB Compatibility Oracle

DuckDB's pinned upstream source is a test oracle, not a TrentDB runtime dependency. The
`third_party/duckdb` Git submodule is fixed at the revision in
`config/compatibility/duckdb-oracle.properties`. TrentDB neither compiles nor links DuckDB
as part of its normal build, and production execution has no native DuckDB dependency.

## Reproducible C0 inventory

Initialize the pinned source exactly as recorded by Git, then generate the complete
tracked-test inventory:

```bash
git submodule update --init --recursive
mvn -Pduckdb-compatibility validate
```

The profile verifies the checked-out commit before it reads the corpus. It writes the
deterministic manifest to `target/compatibility/duckdb-sqllogic-manifest.json`; it never
writes build products into `third_party/duckdb`.

The manifest contains every tracked SQLLogic input (`.test`, `.test_slow`, or `.test_coverage`) below DuckDB's `test/` tree and every
other tracked file in that tree as a fixture candidate. Direct fixture resolution is a C1
harness responsibility because directives such as `include`, `load`, and `unzip` have
runtime semantics. Keeping this C0 inventory broad prevents a fixture from being silently
lost before that resolver exists.

## Compatibility contract

Compatibility runs consume upstream SQLLogic files unchanged. Each completed file must be
classified as exactly one of `PASS`, `ENGINE_FAILURE`, `RUNNER_UNSUPPORTED`,
`ENVIRONMENT_BLOCKED`, or `HARNESS_ERROR`. `ENVIRONMENT_BLOCKED` is reserved for an
upstream-declared unavailable condition and must remain visible in totals. The initial C0
manifest uses `NOT_RUN`: it is an inventory, not a compatibility claim.

Run the cumulative compatibility gate with:

```bash
mvn -Pduckdb-compatibility test
```

The regular `mvn test` gate remains independent of the submodule and does not generate a
compatibility manifest. The compatibility profile supplies the
`duckdb.compatibility.enabled=true` test-system property for the SQLLogic harness.

## CI

The `Compatibility Oracle` workflow is manually dispatched so the broad corpus remains
separate from the fast pull-request health gate. It checks out submodules recursively,
caches Maven dependencies, generates the manifest, and runs the opt-in profile. Reports
remain in the job's `target/compatibility/` working directory and are retained locally for
reproducible investigation.

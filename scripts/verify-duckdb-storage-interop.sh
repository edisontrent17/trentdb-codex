#!/usr/bin/env bash
set -euo pipefail

# Produces a checkpointed V2.0/format-69 database using the pinned native DuckDB CLI,
# then exercises the Java header reader against that exact file. DuckDB remains test-only.
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
duckdb_cli=${DUCKDB_CLI:-${1:-}}

if [[ -z "$duckdb_cli" ]]; then
    printf '%s\n' "Set DUCKDB_CLI or pass the path to a DuckDB CLI built from third_party/duckdb." >&2
    exit 2
fi
if [[ ! -x "$duckdb_cli" ]]; then
    printf 'DuckDB CLI is not executable: %s\n' "$duckdb_cli" >&2
    exit 2
fi

fixture_directory=$(mktemp -d "${TMPDIR:-/tmp}/trentdb-duckdb-storage-interop.XXXXXX")
fixture="$fixture_directory/native-format69.duckdb"
cleanup() {
    rm -rf -- "$fixture_directory"
}
trap cleanup EXIT

"$duckdb_cli" "$fixture" -c 'CREATE TABLE interop_fixture(i INTEGER); INSERT INTO interop_fixture VALUES (42); CHECKPOINT;'

cd -- "$project_dir"
mvn -Dduckdb.interop.fixture="$fixture" -Dtest=DuckDbProducedFormat69InteropTest test

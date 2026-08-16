#!/usr/bin/env bash
# Generates a deterministic C0 inventory of the pinned DuckDB SQLLogic corpus.
# It reads the submodule only and writes every generated file beneath target/.
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
config_file=${1:-"$project_dir/config/compatibility/duckdb-oracle.properties"}

if [[ ! -f "$config_file" ]]; then
    echo "DuckDB oracle config not found: $config_file" >&2
    exit 2
fi

config_value() {
    local key=$1
    local value
    value=$(sed -n "s/^${key}=//p" "$config_file" | tail -n 1)
    if [[ -z "$value" ]]; then
        echo "Missing ${key} in $config_file" >&2
        exit 2
    fi
    printf '%s' "$value"
}

expected_commit=$(config_value duckdb.commit)
source_path=$(config_value duckdb.path)
test_root=$(config_value duckdb.test.root)
manifest_path=$(config_value manifest.path)

if [[ "$source_path" != /* ]]; then
    source_path="$project_dir/$source_path"
fi
if [[ "$manifest_path" != /* ]]; then
    manifest_path="$project_dir/$manifest_path"
fi

if [[ ! -d "$source_path/.git" && ! -f "$source_path/.git" ]]; then
    echo "DuckDB submodule is not initialized at $source_path. Run: git submodule update --init --recursive" >&2
    exit 2
fi

actual_commit=$(git -C "$source_path" rev-parse HEAD)
if [[ "$actual_commit" != "$expected_commit" ]]; then
    echo "DuckDB oracle revision mismatch: expected $expected_commit, found $actual_commit" >&2
    exit 2
fi

mkdir -p "$(dirname -- "$manifest_path")"
inventory_file=$(mktemp "$(dirname -- "$manifest_path")/.duckdb-sqllogic-inventory.XXXXXX")
trap 'rm -f "$inventory_file"' EXIT

# Use Git's tracked-file view so generated files and local builds in the
# submodule never change this inventory.
git -C "$source_path" ls-files -- "$test_root" | LC_ALL=C sort > "$inventory_file"

json_string() {
    local value=$1
    value=${value//\\/\\\\}
    value=${value//\"/\\\"}
    value=${value//$'\n'/\\n}
    value=${value//$'\r'/\\r}
    value=${value//$'\t'/\\t}
    printf '"%s"' "$value"
}

test_count=0
fixture_count=0
while IFS= read -r path; do
    if [[ "$path" == *.test || "$path" == *.test_slow || "$path" == *.test_coverage ]]; then
        ((test_count += 1))
    else
        ((fixture_count += 1))
    fi
done < "$inventory_file"

{
    printf '{\n'
    printf '  "schemaVersion": 1,\n'
    printf '  "oracle": {"name": "DuckDB", "commit": '
    json_string "$actual_commit"
    printf ', "submodulePath": '
    json_string "${source_path#"$project_dir"/}"
    printf '},\n'
    printf '  "scope": {"testRoot": '
    json_string "$test_root"
    printf ', "trackedTestFiles": %d, "trackedFixtureCandidates": %d},\n' "$test_count" "$fixture_count"
    printf '  "initialResult": {"state": "NOT_RUN", "totals": {"PASS": 0, "ENGINE_FAILURE": 0, "RUNNER_UNSUPPORTED": 0, "ENVIRONMENT_BLOCKED": 0, "HARNESS_ERROR": 0}},\n'
    printf '  "classificationContract": {\n'
    printf '    "PASS": "The unchanged upstream test completed with its expected result.",\n'
    printf '    "ENGINE_FAILURE": "The harness executed the test, exposing missing or incorrect TrentDB behavior.",\n'
    printf '    "RUNNER_UNSUPPORTED": "The harness encountered an upstream SQLLogic directive or mode it does not implement.",\n'
    printf '    "ENVIRONMENT_BLOCKED": "An upstream-declared dependency or predicate is unavailable; it is reported, never silently skipped.",\n'
    printf '    "HARNESS_ERROR": "The test or runner state is malformed, or compatibility infrastructure failed.",\n'
    printf '    "NOT_RUN": "C0 inventory state; no semantic compatibility claim has been made."\n'
    printf '  },\n'
    printf '  "tests": [\n'
    first=true
    while IFS= read -r path; do
        [[ "$path" == *.test || "$path" == *.test_slow || "$path" == *.test_coverage ]] || continue
        if [[ "$first" == true ]]; then first=false; else printf ',\n'; fi
        printf '    {"path": '
        json_string "$path"
        printf ', "initialState": "NOT_RUN"}'
    done < "$inventory_file"
    printf '\n  ],\n'
    printf '  "fixtureCandidates": [\n'
    first=true
    while IFS= read -r path; do
        [[ "$path" == *.test || "$path" == *.test_slow || "$path" == *.test_coverage ]] && continue
        if [[ "$first" == true ]]; then first=false; else printf ',\n'; fi
        printf '    '
        json_string "$path"
    done < "$inventory_file"
    printf '\n  ]\n}\n'
} > "$manifest_path"

printf 'DuckDB SQLLogic C0 manifest: %s (%d tests, %d fixture candidates, commit %s)\n' \
    "$manifest_path" "$test_count" "$fixture_count" "$actual_commit"

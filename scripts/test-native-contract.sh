#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/cellidtracker-native-tests.XXXXXX")"
trap 'rm -rf "$build_dir"' EXIT

cmake -S "$repo_root/wifi-calling/tests" -B "$build_dir"
cmake --build "$build_dir"
ctest --test-dir "$build_dir" --output-on-failure

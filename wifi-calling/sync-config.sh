#!/usr/bin/env bash
set -euo pipefail
SRC="$(cd "$(dirname "$0")/../app/src/main/assets/config" && pwd)"
DST="$(cd "$(dirname "$0")" && pwd)/config"
rm -rf "$DST"
mkdir -p "$DST"
cp -a "$SRC"/* "$DST"/
echo "Copied assets config from $SRC to $DST"

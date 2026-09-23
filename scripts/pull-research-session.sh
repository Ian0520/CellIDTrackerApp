#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
    echo "Usage: $0 <session-id> [destination] [--raw]" >&2
    exit 2
fi

session_id="$1"
destination="${2:-captures/$session_id}"
raw_option="${3:-}"

if [[ ! "$session_id" =~ ^[0-9]{8}_[0-9]{6}_[0-9]{3}$ ]]; then
    echo "Invalid session ID: $session_id" >&2
    exit 2
fi
if [[ -n "$raw_option" && "$raw_option" != "--raw" ]]; then
    echo "Third argument must be --raw" >&2
    exit 2
fi

adb_command=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb_command+=(-s "$ANDROID_SERIAL")
fi

external_root="/sdcard/Android/data/com.example.cellidtracker/files/experiment_sessions"
mkdir -p "$destination/sanitized"
"${adb_command[@]}" pull \
    "$external_root/$session_id.json" \
    "$destination/sanitized/"
"${adb_command[@]}" pull \
    "$external_root/${session_id}_research" \
    "$destination/sanitized/"

if [[ "$raw_option" == "--raw" ]]; then
    mkdir -p "$destination/raw"
    archive="$destination/raw/session.tar"
    "${adb_command[@]}" exec-out run-as com.example.cellidtracker \
        tar -C files/research_captures -cf - "$session_id" > "$archive"
    tar -xf "$archive" -C "$destination/raw"
    rm -f "$archive"
fi

echo "Pulled session $session_id to $destination"

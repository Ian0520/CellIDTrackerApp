#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

google_api_key="${GOOGLE_API_KEY:-phase0-verification-placeholder}"

./gradlew \
    :app:testDebugUnitTest \
    :app:lintDebug \
    :app:assembleDebug \
    :app:assembleDebugAndroidTest \
    "-PGOOGLE_API_KEY=$google_api_key"

./scripts/verify-native-artifact.sh

if [[ "${VERIFY_NATIVE_BUILD:-0}" == "1" ]]; then
    ./scripts/build-native-probe.sh "${NATIVE_BUILD_DIR:-$repo_root/wifi-calling/build}"
fi

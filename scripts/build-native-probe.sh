#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${1:-$repo_root/wifi-calling/build}"
ndk_root="${NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"

if [[ -z "$ndk_root" ]]; then
    echo "Set NDK, ANDROID_NDK_HOME, or ANDROID_NDK_ROOT to an Android NDK directory." >&2
    exit 2
fi

cmake -S "$repo_root/wifi-calling/all" -B "$build_dir" \
    -D "CMAKE_TOOLCHAIN_FILE=$ndk_root/build/cmake/android.toolchain.cmake" \
    -D ANDROID_PLATFORM=android-29 \
    -D ANDROID_ABI=armeabi-v7a \
    -D CMAKE_BUILD_TYPE=Release
cmake --build "$build_dir" --config Release --parallel 2

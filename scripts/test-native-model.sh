#!/usr/bin/env bash
# Run finite PCM through the actual JNI bridge and a host build of the pinned engine.
set -euo pipefail
if [[ $# -ne 3 ]]; then
    echo "Usage: $0 /path/to/transcribe.cpp model.gguf sample.wav" >&2
    exit 2
fi
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
engine_dir="$(cd -- "$1" && pwd)"
expected_commit=63a44d9239d610b3908e8a66b384924cd4a77217
if [[ "$(git -C "$engine_dir" rev-parse HEAD)" != "$expected_commit" ]]; then
    echo "Host engine must use pinned commit $expected_commit" >&2
    exit 1
fi
host_build="${TRANSCRIBE_HOST_BUILD_DIR:-$engine_dir/build-phone-validation}"
"$repo_dir/scripts/build-native-jni.sh" --test
native_build="${NEMOTRON_NATIVE_BUILD_DIR:-$repo_dir/build/native-jni}"
java_sdk="${JAVA_HOME:-}"
if [[ -z "$java_sdk" ]]; then
    java_sdk="$(dirname -- "$(dirname -- "$(readlink -f "$(command -v javac)")")")"
fi
"${CXX:-c++}" -std=c++17 -O2 -Wall -Wextra -Werror \
    -I"$java_sdk/include" -I"$java_sdk/include/linux" \
    -I"$native_build/include" -I"$repo_dir/app/src/main/cpp/tests/include" \
    "$repo_dir/app/src/main/cpp/tests/speech_model_smoke_test.cpp" \
    -L"$host_build/src" -ltranscribe -Wl,-rpath,"$host_build/src" \
    -o "$native_build/speech_model_smoke_test"
"$native_build/speech_model_smoke_test" "$2" "$3"

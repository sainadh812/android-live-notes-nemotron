#!/usr/bin/env bash
# Rebuild the JNI bridge against the exact ABI used by the checked-in engine.
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${NEMOTRON_NATIVE_BUILD_DIR:-$repo_dir/build/native-jni}"
include_dir="$build_dir/include"
library_dir="$repo_dir/app/src/main/jniLibs/arm64-v8a"
transcribe_commit=63a44d9239d610b3908e8a66b384924cd4a77217
transcribe_sha256=d3a74372c6e0447890d7c83a59809dbbbefff7bfd8eacce6d5474b2bfa4bd1f9
ndk_version=27.2.12479018

if [[ $# -gt 1 || (${1:-} != "" && ${1:-} != --test) ]]; then
    echo "Usage: $0 [--test]" >&2
    echo "--test runs host JNI contract tests without an Android SDK or model." >&2
    exit 2
fi

mkdir -p "$include_dir/transcribe"
fetch_header() {
    local relative_path="$1" expected_sha256="$2" target="$include_dir/$1"
    if [[ -f "$target" ]] && [[ "$(sha256sum "$target" | cut -d ' ' -f 1)" == "$expected_sha256" ]]; then
        return
    fi
    curl --fail --location --silent --show-error --retry 3 \
        "https://raw.githubusercontent.com/handy-computer/transcribe.cpp/$transcribe_commit/include/$relative_path" \
        --output "$target.part"
    if [[ "$(sha256sum "$target.part" | cut -d ' ' -f 1)" != "$expected_sha256" ]]; then
        echo "Unexpected content for pinned header $relative_path" >&2
        exit 1
    fi
    mv "$target.part" "$target"
}

fetch_header transcribe.h 07fff3489a3c282ab7ee8835b010f1f9f9abd5b61426f8c0e80424dabaaf9a6b
fetch_header transcribe/parakeet.h 969a8d124ae837cdff3038f22a1a6037f5a68d5f906a4cf731154cb756b3b132
fetch_header transcribe/moonshine_streaming.h fe67db1635f2c15059d88f54095f6c14c6ae315e09f9a1d447d0f075f5de5e20

java_sdk="${JAVA_HOME:-}"
if [[ -z "$java_sdk" ]]; then
    java_sdk="$(dirname -- "$(dirname -- "$(readlink -f "$(command -v javac)")")")"
fi
"${CXX:-c++}" -std=c++17 -Wall -Wextra -Werror \
    -I"$java_sdk/include" -I"$java_sdk/include/linux" \
    -I"$include_dir" -I"$repo_dir/app/src/main/cpp/tests/include" \
    "$repo_dir/app/src/main/cpp/tests/nemotron_jni_contract_test.cpp" \
    -o "$build_dir/nemotron_jni_contract_test"
"$build_dir/nemotron_jni_contract_test"

if [[ ${1:-} == --test ]]; then exit 0; fi

ndk_dir="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/ndk/$ndk_version}"
toolchain_dir="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64"
compiler="$toolchain_dir/bin/aarch64-linux-android26-clang++"
if [[ ! -x "$compiler" ]]; then
    echo "Set ANDROID_NDK_HOME to Android NDK $ndk_version, or ANDROID_SDK_ROOT to its SDK." >&2
    exit 1
fi
if ! rg -q "Pkg.Revision[[:space:]]*=[[:space:]]*$ndk_version" "$ndk_dir/source.properties"; then
    echo "This build is pinned to Android NDK $ndk_version." >&2
    exit 1
fi
if [[ "$(sha256sum "$library_dir/libtranscribe.so" | cut -d ' ' -f 1)" != "$transcribe_sha256" ]]; then
    echo "libtranscribe.so differs from the engine associated with $transcribe_commit; update the ABI pin deliberately." >&2
    exit 1
fi

output="$build_dir/libnemotron_jni.so"
"$compiler" -std=c++17 -O2 -g -fPIC -shared -static-libstdc++ \
    -fvisibility=hidden -ffile-prefix-map="$repo_dir"=. \
    -I"$include_dir" "$repo_dir/app/src/main/cpp/nemotron_jni.cpp" \
    -L"$library_dir" -ltranscribe -llog \
    -Wl,-rpath-link,"$library_dir" -Wl,--no-undefined \
    -Wl,-soname,libnemotron_jni.so -Wl,--build-id=sha1 \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
    -o "$output"

"$toolchain_dir/bin/llvm-readelf" --program-headers --wide "$output" > "$build_dir/program-headers.txt"
python3 - "$build_dir/program-headers.txt" <<'PY'
import pathlib, sys
loads = [line.split() for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line.lstrip().startswith('LOAD ')]
if not loads or any(int(fields[-1], 16) < 16384 for fields in loads):
    raise SystemExit('Native bridge does not preserve 16 KB LOAD alignment')
print('Native bridge: all LOAD segments support 16 KB pages')
PY
"$toolchain_dir/bin/llvm-nm" --dynamic "$output" > "$build_dir/dynamic-symbols.txt"
if ! rg -q ' U transcribe_stream_update_init$' "$build_dir/dynamic-symbols.txt"; then
    echo "Rebuilt bridge is missing the stream update initializer import." >&2
    exit 1
fi
cp "$output" "$library_dir/libnemotron_jni.so"
cat > "$repo_dir/app/src/main/jniLibs/nemotron-jni-build.properties" <<EOF
sourcePath=app/src/main/cpp/nemotron_jni.cpp
sourceSha256=$(sha256sum "$repo_dir/app/src/main/cpp/nemotron_jni.cpp" | cut -d ' ' -f 1)
binarySha256=$(sha256sum "$library_dir/libnemotron_jni.so" | cut -d ' ' -f 1)
upstreamCommit=$transcribe_commit
ndkVersion=$ndk_version
EOF
sha256sum "$library_dir/libnemotron_jni.so"
echo "Rebuilt JNI bridge using transcribe.cpp $transcribe_commit and NDK $ndk_version."

#!/usr/bin/env bash
# Stage the pinned engine's 16 KB repair; install only with an explicit flag.
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${NEMOTRON_ENGINE_BUILD_DIR:-$repo_dir/build/native-engine}"
source_dir="${NEMOTRON_ENGINE_SOURCE_DIR:-$build_dir/source}"
stage_dir="$build_dir/staged"
library_dir="$repo_dir/app/src/main/jniLibs/arm64-v8a"
transcribe_commit=63a44d9239d610b3908e8a66b384924cd4a77217
ndk_version=27.2.12479018

if [[ $# -gt 1 || (${1:-} != "" && ${1:-} != --install) ]]; then
    echo "Usage: $0 [--install]" >&2
    exit 2
fi

ndk_dir="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/ndk/$ndk_version}"
toolchain_dir="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -x "$toolchain_dir/bin/aarch64-linux-android26-clang++" ]] ||
   ! rg -q "Pkg.Revision[[:space:]]*=[[:space:]]*$ndk_version" "$ndk_dir/source.properties"; then
    echo "Set ANDROID_NDK_HOME to Android NDK $ndk_version, or ANDROID_SDK_ROOT to its SDK." >&2
    exit 1
fi

if [[ ! -d "$source_dir/.git" ]]; then
    if [[ -n ${NEMOTRON_ENGINE_SOURCE_DIR:-} ]]; then
        echo "NEMOTRON_ENGINE_SOURCE_DIR must be a clean checkout of $transcribe_commit." >&2
        exit 1
    fi
    mkdir -p "$source_dir"
    git -C "$source_dir" init --quiet
    git -C "$source_dir" fetch --depth 1 https://github.com/handy-computer/transcribe.cpp.git "$transcribe_commit"
    git -C "$source_dir" checkout --quiet --detach FETCH_HEAD
fi
if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$transcribe_commit" ]] ||
   [[ -n "$(git -C "$source_dir" status --porcelain --untracked-files=no)" ]]; then
    echo "Engine source must be an unmodified checkout of $transcribe_commit." >&2
    exit 1
fi

cmake -S "$source_dir" -B "$build_dir/cmake" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DTRANSCRIBE_BUILD_SHARED=ON -DTRANSCRIBE_BUILD_TESTS=OFF \
    -DTRANSCRIBE_BUILD_EXAMPLES=OFF -DTRANSCRIBE_BUILD_TOOLS=OFF \
    -DTRANSCRIBE_USE_SYSTEM_BLAS=OFF -DTRANSCRIBE_USE_OPENMP=OFF \
    -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8-a \
    '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
cmake --build "$build_dir/cmake" --target transcribe --parallel 2

mkdir -p "$stage_dir"
cp "$build_dir/cmake/src/libtranscribe.so" "$stage_dir/libtranscribe.so"
cp "$build_dir/cmake/ggml/src/libggml.so" "$stage_dir/libggml.so"

# Inspect the exact proposed package: two rebuilt libraries and three retained ones.
python3 "$repo_dir/scripts/check-native-page-alignment.py" \
    "$stage_dir/libggml.so" "$stage_dir/libtranscribe.so" \
    "$library_dir/libggml-base.so" "$library_dir/libggml-cpu.so" "$library_dir/libnemotron_jni.so"

# Compare the replacement ABI against the shipped libraries.
python3 - "$library_dir" "$stage_dir" "$toolchain_dir/bin" <<'PY'
import hashlib, pathlib, re, subprocess, sys
original, staged, tools = map(pathlib.Path, sys.argv[1:])
replacements = ('libggml.so', 'libtranscribe.so')

def run(tool, *args):
    return subprocess.check_output([str(tools / tool), *map(str, args)], text=True)

def exports(path):
    return {line.split()[0]: line.split()[1] for line in
            run('llvm-nm', '--dynamic', '--defined-only', '--extern-only', '--format=posix', path).splitlines()
            if len(line.split()) >= 2}

def dependencies(path):
    return sorted(re.findall(r'\((NEEDED|SONAME)\).*?\[([^\]]+)\]',
                             run('llvm-readelf', '--dynamic', '--wide', path)))

for name in replacements:
    old, new = original / name, staged / name
    old_exports, new_exports = exports(old), exports(new)
    missing = sorted(name for name, kind in old_exports.items() if new_exports.get(name) != kind)
    if missing:
        raise SystemExit(f'{name}: removed or changed exported symbols: {missing}')
    if dependencies(old) != dependencies(new):
        raise SystemExit(f'{name}: SONAME or dependencies changed')
    print(f'{name}: all {len(old_exports)} existing exports and SONAME/dependencies preserved')

proposed = [staged / old.name if old.name in replacements else old
            for old in sorted(original.glob('*.so'))]
provided = set().union(*(exports(path) for path in proposed))
for path in proposed:
    imported = {line.split()[0] for line in
                run('llvm-nm', '--dynamic', '--undefined-only', '--format=posix', path).splitlines()
                if line.split() and line.split()[0].startswith(('ggml_', 'transcribe_'))}
    if imported - provided:
        raise SystemExit(f'{path.name}: unresolved engine imports: {sorted(imported - provided)}')
print('Proposed package: all ggml/transcribe imports resolve to packaged exports')

checksums = ''.join(f'{hashlib.sha256((staged / name).read_bytes()).hexdigest()}  {name}\n'
                    for name in replacements)
(staged / 'SHA256SUMS').write_text(checksums)
print(checksums, end='')
PY

cat > "$stage_dir/build.properties" <<EOF
upstreamCommit=$transcribe_commit
ndkVersion=$ndk_version
abi=arm64-v8a
androidApi=26
linkerFlags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
EOF

if [[ ${1:-} == --install ]]; then
    cp "$stage_dir/libggml.so" "$library_dir/libggml.so"
    cp "$stage_dir/libtranscribe.so" "$library_dir/libtranscribe.so"
    python3 - "$repo_dir/scripts/build-native-jni.sh" "$stage_dir/libtranscribe.so" <<'PY'
import hashlib, pathlib, re, sys
script, library = map(pathlib.Path, sys.argv[1:])
digest = hashlib.sha256(library.read_bytes()).hexdigest()
updated, count = re.subn(r'^transcribe_sha256=[0-9a-f]{64}$',
                         'transcribe_sha256=' + digest, script.read_text(), flags=re.MULTILINE)
if count != 1:
    raise SystemExit('Could not update the JNI script engine hash pin')
script.write_text(updated)
PY
    echo "Installed validated engine libraries and updated the JNI engine hash pin."
else
    echo "Validated replacement libraries staged in $stage_dir; repository binaries are unchanged."
    echo "Run this script with --install only after other Android builds have finished."
fi

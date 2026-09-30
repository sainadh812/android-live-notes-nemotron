#!/usr/bin/env bash
# Build the complete pinned engine and JNI bridge for every Android release ABI.
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${NEMOTRON_ANDROID_BUILD_DIR:-$repo_dir/build/native-android}"
source_dir="${NEMOTRON_ENGINE_SOURCE_DIR:-$build_dir/source}"
upstream_commit=63a44d9239d610b3908e8a66b384924cd4a77217
ndk_version=27.2.12479018
ndk_dir="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/ndk/$ndk_version}"
tools_dir="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
abis=("$@")
if [[ ${#abis[@]} -eq 0 ]]; then abis=(arm64-v8a armeabi-v7a x86 x86_64); fi
for abi in "${abis[@]}"; do
    case "$abi" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo "Unsupported Android ABI: $abi" >&2; exit 2 ;; esac
done
if [[ ! -x "$tools_dir/clang++" ]] ||
   ! rg -q "Pkg.Revision[[:space:]]*=[[:space:]]*$ndk_version" "$ndk_dir/source.properties"; then
    echo "Set ANDROID_NDK_HOME to Android NDK $ndk_version." >&2
    exit 1
fi
mkdir -p "$build_dir"
if [[ ! -d "$source_dir/.git" ]]; then
    if [[ -n ${NEMOTRON_ENGINE_SOURCE_DIR:-} ]]; then
        echo "NEMOTRON_ENGINE_SOURCE_DIR must be a clean checkout of $upstream_commit." >&2
        exit 1
    fi
    mkdir -p "$source_dir"
    git -C "$source_dir" init --quiet
    git -C "$source_dir" fetch --depth 1 https://github.com/handy-computer/transcribe.cpp.git "$upstream_commit"
    git -C "$source_dir" checkout --quiet --detach FETCH_HEAD
fi
if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$upstream_commit" ]] ||
   [[ -n "$(git -C "$source_dir" status --porcelain --untracked-files=no)" ]]; then
    echo "Engine source must be an unmodified checkout of $upstream_commit." >&2
    exit 1
fi

for abi in "${abis[@]}"; do
    abi_dir="$build_dir/$abi"
    abi_source_dir="$source_dir"
    source_patch_sha256=""
    output_dir="$build_dir/jniLibs/$abi"
    arch_flags=()
    case "$abi" in
        arm64-v8a) compiler=aarch64-linux-android26-clang++; arch_flags=(-DGGML_CPU_ARM_ARCH=armv8-a) ;;
        armeabi-v7a)
            compiler=armv7a-linux-androideabi26-clang++
            arch_flags=(-DGGML_CPU_ARM_ARCH=armv7-a -DGGML_LLAMAFILE=OFF)
            # The upstream optional llamafile kernel assumes ARM FP16 vector
            # intrinsics unavailable on ARMv7. Keep the complete generic CPU
            # backend and isolate this one CMake patch from every other ABI.
            abi_source_dir="$build_dir/source-armeabi-v7a"
            abi_dir="$build_dir/armeabi-v7a-generic"
            patch_file="$repo_dir/scripts/native-patches/armv7-optional-llamafile.patch"
            if [[ ! -e "$abi_source_dir/.git" ]]; then
                git -C "$source_dir" worktree add --detach "$abi_source_dir" "$upstream_commit"
            fi
            if [[ "$(git -C "$abi_source_dir" rev-parse HEAD)" != "$upstream_commit" ]]; then
                echo "ARMv7 source must be pinned to $upstream_commit." >&2
                exit 1
            fi
            if [[ -z "$(git -C "$abi_source_dir" status --porcelain --untracked-files=no)" ]]; then
                git -C "$abi_source_dir" apply --check "$patch_file"
                git -C "$abi_source_dir" apply "$patch_file"
            fi
            source_patch_sha256="$(python3 - "$abi_source_dir" "$patch_file" <<'PY'
import hashlib, pathlib, subprocess, sys
source, patch = sys.argv[1], pathlib.Path(sys.argv[2])
expected = patch.read_bytes()
actual = subprocess.check_output(['git', '-C', source, '-c', 'core.autocrlf=false',
                                 'diff', '--no-ext-diff', '--full-index', '--binary', 'HEAD', '--'])
if actual != expected:
    raise SystemExit('ARMv7 source contains changes beyond the approved llamafile CMake patch')
print(hashlib.sha256(expected).hexdigest())
PY
            )"
            ;;
        x86) compiler=i686-linux-android26-clang++; arch_flags=(-DTRANSCRIBE_X86_CONSERVATIVE=ON) ;;
        x86_64) compiler=x86_64-linux-android26-clang++; arch_flags=(-DTRANSCRIBE_X86_CONSERVATIVE=ON) ;;
    esac
    cmake -S "$abi_source_dir" -B "$abi_dir" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DTRANSCRIBE_BUILD_SHARED=ON -DTRANSCRIBE_BUILD_TESTS=OFF \
        -DTRANSCRIBE_BUILD_EXAMPLES=OFF -DTRANSCRIBE_BUILD_TOOLS=OFF \
        -DTRANSCRIBE_USE_SYSTEM_BLAS=OFF -DTRANSCRIBE_USE_OPENMP=OFF \
        -DGGML_NATIVE=OFF "${arch_flags[@]}" \
        "-DCMAKE_C_FLAGS=-ffile-prefix-map=$abi_source_dir=transcribe.cpp" \
        "-DCMAKE_CXX_FLAGS=-ffile-prefix-map=$abi_source_dir=transcribe.cpp" \
        '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
    cmake --build "$abi_dir" --target transcribe --parallel "${NEMOTRON_NATIVE_JOBS:-2}"
    mkdir -p "$output_dir"
    python3 - "$abi_dir" "$output_dir" <<'PY'
import pathlib, shutil, sys
build, output = map(pathlib.Path, sys.argv[1:])
for name in ('libggml.so', 'libggml-base.so', 'libggml-cpu.so', 'libtranscribe.so'):
    matches = list(build.rglob(name))
    if len(matches) != 1:
        raise SystemExit(f'Expected exactly one freshly built {name}: {matches}')
    shutil.copyfile(matches[0], output / name)
PY
    "$tools_dir/$compiler" -std=c++17 -O2 -g -fPIC -shared -static-libstdc++ \
        -fvisibility=hidden -ffile-prefix-map="$repo_dir"=. \
        -I"$abi_source_dir/include" "$repo_dir/app/src/main/cpp/nemotron_jni.cpp" \
        -L"$output_dir" -ltranscribe -llog -Wl,-rpath-link,"$output_dir" -Wl,--no-undefined \
        -Wl,-soname,libnemotron_jni.so -Wl,--build-id=sha1 \
        -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
        -o "$output_dir/libnemotron_jni.so"
    python3 "$repo_dir/scripts/check-native-page-alignment.py" --abi "$abi" "$output_dir"
    python3 - "$output_dir" "$tools_dir" "$repo_dir/app/src/main/cpp/nemotron_jni.cpp" "$upstream_commit" "$ndk_version" "$source_patch_sha256" <<'PY'
import hashlib, json, pathlib, re, subprocess, sys
directory, tools, source = map(pathlib.Path, sys.argv[1:4])
def run(tool, *args):
    return subprocess.check_output([str(tools / tool), *map(str, args)], text=True)
libraries = sorted(directory.glob('*.so'))
exports = set()
for library in libraries:
    exports.update(line.split()[0] for line in run('llvm-nm', '--dynamic', '--defined-only', '--extern-only', '--format=posix', library).splitlines() if line.split())
system = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so'}
for library in libraries:
    imports = {line.split()[0] for line in run('llvm-nm', '--dynamic', '--undefined-only', '--format=posix', library).splitlines() if line.split() and line.split()[0].startswith(('ggml_', 'transcribe_'))}
    if imports - exports:
        raise SystemExit(f'{library.name}: unresolved native engine imports: {sorted(imports - exports)}')
    needed = set(re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', run('llvm-readelf', '--dynamic', library)))
    if needed - system - {p.name for p in libraries}:
        raise SystemExit(f'{library.name}: unpackaged dependencies: {needed}')
    if '.debug_info' not in run('llvm-readelf', '--sections', library):
        raise SystemExit(f'{library.name}: full native debug information is missing')
required = set(re.findall(r'Java_com_sainadh_livenotes_stt_NemotronTranscriber_\w+', source.read_text()))
if not required <= exports:
    raise SystemExit('Native bridge is missing JNI entry points')
record = {'abi': directory.name, 'upstreamCommit': sys.argv[4], 'ndkVersion': sys.argv[5], 'androidApi': 26,
          'jniSourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
          'libraries': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in libraries}}
if sys.argv[6]:
    record['sourcePatchSha256'] = sys.argv[6]
(directory.parent.parent / (directory.name + '-build.json')).write_text(json.dumps(record, indent=2) + '\n')
print(f'{directory.name}: complete engine, JNI symbols, dependencies and debug information verified')
PY
done

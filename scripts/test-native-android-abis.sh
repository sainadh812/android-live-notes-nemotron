#!/usr/bin/env bash
# Build bounded Android native probes, then run only ABIs advertised by a connected device.
# No Gradle, model download, application installation, or microphone access is involved.
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="${NEMOTRON_ANDROID_BUILD_DIR:-$repo_dir/build/native-android}"
source_dir="${NEMOTRON_ENGINE_SOURCE_DIR:-$build_dir/source}"
smoke_dir="$build_dir/smoke"
mode=both
case "${1:-}" in
    --build-only) mode=build; shift ;;
    --run-only) mode=run; shift ;;
    --help|-h)
        echo "Usage: $0 [--build-only|--run-only] [x86 x86_64]"
        echo "Run scripts/build-native-android.sh first. ANDROID_SERIAL selects the test device."
        exit 0 ;;
esac
abis=("$@")
if [[ ${#abis[@]} -eq 0 ]]; then abis=(x86 x86_64); fi
for abi in "${abis[@]}"; do
    case "$abi" in x86|x86_64) ;; *) echo "Unsupported smoke ABI: $abi" >&2; exit 2 ;; esac
done
mkdir -p "$smoke_dir"
libraries=(libggml-base.so libggml-cpu.so libggml.so libtranscribe.so libnemotron_jni.so)

if [[ "$mode" != run ]]; then
    upstream_commit=63a44d9239d610b3908e8a66b384924cd4a77217
    ndk_version=27.2.12479018
    ndk_dir="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/ndk/$ndk_version}"
    tools_dir="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
    if [[ ! -x "$tools_dir/clang++" ]] ||
       ! rg -q "Pkg.Revision[[:space:]]*=[[:space:]]*$ndk_version" "$ndk_dir/source.properties"; then
        echo "Set ANDROID_NDK_HOME to Android NDK $ndk_version." >&2
        exit 1
    fi
    if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$upstream_commit" ]] ||
       [[ -n "$(git -C "$source_dir" status --porcelain --untracked-files=no)" ]]; then
        echo "Smoke headers must come from the clean pinned engine checkout." >&2
        exit 1
    fi
    for abi in "${abis[@]}"; do
        case "$abi" in x86) compiler=i686-linux-android26-clang++ ;; x86_64) compiler=x86_64-linux-android26-clang++ ;; esac
        mkdir -p "$smoke_dir/$abi"
        # Resolve engine APIs with dlopen/dlsym so each loader failure is identified explicitly.
        "$tools_dir/$compiler" -std=c++17 -O2 -g -Wall -Wextra -Werror \
            -fPIE -pie -static-libstdc++ -fvisibility=hidden \
            -I"$source_dir/include" -I"$source_dir/ggml/include" \
            "$repo_dir/app/src/main/cpp/tests/android_native_abi_smoke.cpp" \
            -ldl -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
            -o "$smoke_dir/$abi/native-abi-smoke"
        {
            printf 'abi=%s\nandroidApi=26\nndk=%s\nupstreamCommit=%s\n' "$abi" "$ndk_version" "$upstream_commit"
            "$tools_dir/$compiler" --version
            sha256sum "$smoke_dir/$abi/native-abi-smoke"
        } > "$smoke_dir/$abi/build.txt"
        echo "Built Android API 26 smoke executable: $abi"
    done
fi
if [[ "$mode" == build ]]; then exit 0; fi

adb_bin="${ADB:-adb}"
timeout 60s "$adb_bin" wait-for-device
device_abis="$(timeout 15s "$adb_bin" shell getprop ro.product.cpu.abilist | tr -d '\r\n')"
device_sdk="$(timeout 15s "$adb_bin" shell getprop ro.build.version.sdk | tr -d '\r\n')"
if [[ ! "$device_sdk" =~ ^[0-9]+$ ]] || [[ "$device_sdk" -lt 26 ]]; then
    echo "The smoke test requires an Android API 26+ device." >&2
    exit 1
fi
printf 'Device API=%s; advertised ABIs=%s\n' "$device_sdk" "$device_abis" | tee "$smoke_dir/device.txt"
printf 'Native baseline only: loader, JNI exports, transcribe API, GGML CPU; no model inference.\n' > "$smoke_dir/results.txt"
ran=0
failed=0
for abi in "${abis[@]}"; do
    if [[ ",$device_abis," != *",$abi,"* ]]; then
        echo "SKIP $abi: device does not advertise this ABI" | tee -a "$smoke_dir/results.txt"
        continue
    fi
    ran=$((ran + 1))
    lib_dir="$build_dir/jniLibs/$abi"
    test -x "$smoke_dir/$abi/native-abi-smoke"
    python3 "$repo_dir/scripts/check-native-page-alignment.py" --abi "$abi" "$lib_dir" \
        | tee "$smoke_dir/$abi/alignment.txt"
    # A private, fresh directory avoids touching any installed app or another test's files.
    remote_dir="/data/local/tmp/live-notes-native-smoke-$$-$abi"
    timeout 15s "$adb_bin" shell mkdir -p "$remote_dir"
    for library in "${libraries[@]}"; do
        timeout 60s "$adb_bin" push "$lib_dir/$library" "$remote_dir/$library"
    done
    timeout 30s "$adb_bin" push "$smoke_dir/$abi/native-abi-smoke" "$remote_dir/native-abi-smoke"
    timeout 15s "$adb_bin" shell chmod 700 "$remote_dir/native-abi-smoke"
    # Device timeout also terminates the probe if the host-side adb call is interrupted.
    if timeout 60s "$adb_bin" shell \
        "LD_LIBRARY_PATH=$remote_dir timeout 30 $remote_dir/native-abi-smoke $remote_dir" \
        2>&1 | tee "$smoke_dir/$abi/runtime.txt"; then
        echo "PASS $abi: native Android loader and CPU baseline" | tee -a "$smoke_dir/results.txt"
    else
        echo "FAIL $abi: inspect $abi/runtime.txt" | tee -a "$smoke_dir/results.txt"
        failed=1
    fi
    timeout 15s "$adb_bin" shell rm -rf "$remote_dir" || true
done
if [[ "$ran" -eq 0 ]]; then
    echo "FAIL no requested ABI was supported by this device" | tee -a "$smoke_dir/results.txt"
    exit 1
fi
exit "$failed"

# Rebuilding the on-device speech JNI bridge

The app packages `app/src/main/jniLibs/arm64-v8a/libnemotron_jni.so`.
Gradle does not compile `nemotron_jni.cpp`; rebuild the library after changing
that file. `nemotron-jni-build.properties` records the source and binary hashes
so the Android build can detect a stale binary.

On Linux x86_64, install JDK 17, a C++17 host compiler, Python 3, curl, ripgrep,
and Android NDK **27.2.12479018 (r27c)**. From the repository root:

```sh
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/27.2.12479018
scripts/build-native-jni.sh
```

Alternatively, set `ANDROID_SDK_ROOT` to an SDK containing that NDK version.
The script downloads three small public headers, verifies their SHA-256 hashes,
runs host contract tests, builds the arm64 Android API 26 JNI library, checks
16 KB ELF segment alignment, and updates the binary and build metadata.
It does not download a speech model. Intermediate files stay in the ignored
`build/native-jni/` directory; set `NEMOTRON_NATIVE_BUILD_DIR` to override it.

The headers are pinned to transcribe.cpp commit
`63a44d9239d610b3908e8a66b384924cd4a77217`, matching the checked-in
`libtranscribe.so`. Its SHA-256 is pinned too. Replacing that engine requires
deliberately updating these pins and checking its ABI; this script rebuilds
only the JNI bridge, using the existing engine and ggml libraries.

## Contract regression tests

```sh
scripts/build-native-jni.sh --test
```

This command needs the host prerequisites above, but no NDK, Android SDK,
device, or model. It compiles the actual JNI source with the pinned public
headers, a minimal JVM interface, and an engine double that enforces the
stream update size contract. It checks audio feed, finalization, transcript
assembly, family extension selection, declared-locale mapping, output-limit
reporting, error propagation, and resource cleanup after initialization errors.
Finalization reads the stream snapshot. Moonshine uses ON_FINALIZE commitment
so early word/punctuation revisions cannot cause the engine to retain only an
old prefix and discard the final tail. Its live hypothesis stays replaceable;
Nemotron continues emitting stable committed segments. Engine UTF-8 is converted
to Java UTF-16, including supplementary characters; malformed bytes are replaced
with U+FFFD. Engine output never goes directly to NewStringUTF.
Removing either `transcribe_stream_update_init` call makes the tests fail.

These checks do not establish microphone routing, recognition quality, or
real-time performance; those require an Android device and model.

All session operations must run serially on the Kotlin worker that owns the
native handle. Native API failures throw `IllegalStateException` containing
the operation, status description, and numeric status. The worker must catch
the exception, report it to the UI, and destroy the native session.


## Supported streaming families

The bundled engine supports both cache-aware Parakeet/Nemotron streaming and
Moonshine Streaming. The bridge probes each model's accepted extension kind:
Nemotron receives its right-context setting; Moonshine receives a 500 ms
minimum partial-decode interval matching the microphone chunks. Sessions use
four CPU inference threads to bound inference concurrency. Requested
locale tags use an exact metadata match first, then an explicitly supported
language subtag (for example, `en-US` becomes `en` for English-only models).
Unsupported model families or languages fail clearly.

Moonshine Streaming has a finite output window (4,096 tokens in these models).
The bridge exposes `transcribe_was_truncated`; Kotlin retains the current text
and stops with an explicit output-limit message when that flag is set. This
avoids treating a truncated recording as complete. Short-note performance and
longer recordings still need measurement on the actual phone.

## Finite real-model inference on Linux

To exercise the actual bridge against a real model, first build the pinned
engine as a host shared library:

```sh
git clone https://github.com/handy-computer/transcribe.cpp /tmp/transcribe-phone-test
git -C /tmp/transcribe-phone-test checkout --detach 63a44d9239d610b3908e8a66b384924cd4a77217
cmake -S /tmp/transcribe-phone-test -B /tmp/transcribe-phone-test/build-phone-validation \
  -DTRANSCRIBE_BUILD_TESTS=OFF -DTRANSCRIBE_BUILD_SHARED=ON \
  -DTRANSCRIBE_USE_SYSTEM_BLAS=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/transcribe-phone-test/build-phone-validation --target transcribe-cli -j 3
scripts/test-native-model.sh /tmp/transcribe-phone-test /path/to/model.gguf \
  /tmp/transcribe-phone-test/samples/jfk.wav
```

The smoke test runs half-second PCM feeds, verifies append-only committed text,
checks truncation, checks the final snapshot, and restarts the same session.
For the JFK sample it requires both complete reference clauses, so a nonempty
but severely truncated transcript cannot pass.
Its small JVM stand-in supplies strings and PCM arrays; recognition uses the
real engine and model, not an engine double. This verifies host integration,
not Android microphone hardware, arm64 numerical output, or phone speed.
`TRANSCRIBE_HOST_BUILD_DIR` can select another host shared-library build dir.

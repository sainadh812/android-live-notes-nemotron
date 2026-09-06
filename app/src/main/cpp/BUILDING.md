# Rebuilding the Nemotron JNI bridge

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
The script downloads two small public headers, verifies their SHA-256 hashes,
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
assembly, error propagation, and resource cleanup after initialization errors.
Removing either `transcribe_stream_update_init` call makes the tests fail.

These checks do not establish microphone routing, recognition quality, or
real-time performance; those require an Android device and model.

All session operations must run serially on the Kotlin worker that owns the
native handle. Native API failures throw `IllegalStateException` containing
the operation, status description, and numeric status. The worker must catch
the exception, report it to the UI, and destroy the native session.

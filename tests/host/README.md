# Nemotron lifecycle regression tests

Run `./scripts/test-transcriber-lifecycle.sh` on Linux with JDK 17, GCC, and a
Gradle distribution downloaded by `./gradlew --version`. Alternatively set
`KOTLIN_COMPILER_LIB` to a Kotlin compiler library directory. No Android SDK,
model download, emulator, or network connection is needed once Gradle exists.

The harness compiles the actual `NemotronTranscriber.kt` with small Android
and JNI stubs. Gated native calls deterministically exercise cancellation
during model initialization and inference, duplicate starts/stops, model reuse,
native restart/init/feed/finalize failures, microphone errors, callback order,
and suppression of queued callbacks after destruction. A feed is held beyond
the previous two-second join timeout to guard against freeing an active session.
JNI calls assert serialization and the PCM input conversion is checked.

These tests validate Kotlin lifecycle ordering, cleanup, and error handling.
They do not exercise Android microphone hardware, the actual native engine,
ABI compatibility, model accuracy, or performance on a device.

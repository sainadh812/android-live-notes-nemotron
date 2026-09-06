# Listening service lifecycle checks

Run `scripts/test-listening-service.sh` with a downloaded Gradle distribution
(`./gradlew --version`), or set `KOTLIN_COMPILER_LIB` to its `lib` directory.

This compiles the production `ForegroundListeningService.kt` and
`SpeechTranscriber.kt` against Android stand-ins and the real coroutines runtime.
The tests use a controllable native-transcriber double and persistence gate to
verify final transcript writes finish before destruction, capture resources are
released after failures, stale callbacks cannot recreate notifications, queued
restarts invalidate the previous session, and OS final results survive shutdown.

These are lifecycle regression tests, not an Android emulator: device permission
prompts, Android's foreground-service enforcement, Bluetooth routing and actual
notification delivery still require device testing.

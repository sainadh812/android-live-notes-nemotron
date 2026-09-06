# OS speech recognizer lifecycle checks

Run `scripts/test-speech-lifecycle.sh` after `./gradlew --version` has downloaded
Gradle, or point `KOTLIN_COMPILER_LIB` at a Gradle `lib` directory containing its
Kotlin compiler. This compiles the production `SpeechTranscriber.kt` against
small, controllable Android stand-ins and runs without a device.

The scenarios cover unavailable recognition, final-result ordering on stop,
a provider that never completes stop, cancelling delayed restarts, rejecting
callbacks from replaced/destroyed recognizers, and bounded failure retries.
These validate wrapper lifecycle behavior; actual OS speech services, microphone
routing, Android service/notification behavior, and runtime permissions still
need device testing.

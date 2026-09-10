# Nemotron capture and lifecycle regression tests

Run `./scripts/test-transcriber-lifecycle.sh` on Linux with JDK 17, GCC, and a
Gradle distribution downloaded by `./gradlew --version`. Alternatively set
`KOTLIN_COMPILER_LIB` to a Kotlin compiler library directory. No Android SDK,
model download, emulator, or network connection is needed once Gradle exists.

The harness compiles the actual `NemotronTranscriber.kt`, native transcript
segment assembly, and shared update contract with small Android and JNI stubs.
Its 29 deterministic lifecycle scenarios and standalone WAV/sample-clock checks cover:

- Cancellation during model initialization and inference, duplicate starts/stops,
  model reuse, native restart/init/feed/finalize failures, and microphone errors.
- Continued microphone reads while a native feed is blocked; microphone release
  before that inference returns; ordered draining of queued chunks and incomplete
  audio still in the driver when stop is requested.
- Explicit bounded-queue overflow and a driver that will not finish draining,
  preserving accepted audio and reporting the loss before the stopped callback.
- Preserving an incomplete capture chunk after a read error; marking the latest
  hypothesis interrupted on native failure; discarding queued work on destroy.
- Deferred destroy completions during held init/feed calls and repeated destroy,
  enabling model download/delete leases to outlive every native read.
- Explicit output-window truncation after feed or finalize, retaining returned
  text before stopping instead of silently declaring the recording complete.
- Stable complete-word segments, one replaceable tentative tail, exact whitespace
  and punctuation, empty-tail clearing, and rejection of committed-prefix changes.
- Finalized WAV files containing every actual PCM read, including incomplete tails,
  audio beyond an overflowing inference queue, and cancellation during inference.
  Failed microphone starts never publish an empty recording.
- Correct little-endian WAV headers and signed samples, storage checkpoints,
  idempotent finalization, and refusal to overwrite an existing recording.
  Process-death recovery repairs stale headers, trims incomplete samples, and
  rejects malformed files without publishing them.
- Monotonic captured duration, normalized RMS, and estimated transcript bounds
  measured from consumed samples instead of delayed inference wall time.

A native feed is held beyond the former two-second join timeout to guard against
freeing an active session. The stubs assert single-owner AudioRecord access,
serialized JNI calls, exact PCM conversion, consumed sample counts, main-thread
callbacks, final/error/stopped ordering, and suppression after destruction.

These tests validate Kotlin capture coordination, lifecycle ordering, segment
assembly, cleanup, and error handling. They do not exercise Android microphone
hardware, the actual native engine, ABI compatibility, model accuracy, or
performance on a device. Real-phone capture and sustained inference still need
measurement; the bounded queue reports excessive inference lag, not every
possible driver-level audio loss.

Repair completed locally on 2026-09-06, starting from commit `fd3aa35713066483b0f7ff167740c1194e1f2f58`. App version: **1.0.1**, version code **2**.

The Nemotron JNI bridge previously passed zero-sized update structures to feed and finalize. The bundled native engine rejected those calls with error 14. Both structures now use the required initializer, and the rebuilt arm64 JNI library contains those calls. Native errors reach the app as descriptive exceptions. A pinned build script, contract regression tests, and a Gradle source/binary hash check keep the source and shipped library consistent.

Transcription now uses one worker to own native and microphone resources. Stopping during model loading cannot start capture afterward; stopping during inference cannot free the session before the native call returns. The service retains final text through database persistence, releases its resources on terminal errors, rejects stale callbacks, and prevents stopped callbacks from recreating notifications. The OS recognizer also preserves final results through normal stop, retry backoff, and a bounded provider timeout.

Phone-microphone recording no longer depends on optional notification or Bluetooth permissions, and stopping always bypasses permission prompts. Android recognition-service visibility is declared in the manifest. Capture errors remain visible separately from summary failures.

Summary requests run outside the microphone service's persistence path. Partials are throttled and coalesced, final results promote pending work, and only one request runs at a time. The summary query preserves unsummarized final chunks and timestamp ties instead of taking only the last twelve rows. Failed summaries have an explicit retry action. The committed corporate proxy was removed, and build/setup documentation now describes this app and its native build.

Validation passed:

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain`: **BUILD SUCCESSFUL**. Seven unit tests passed. Lint found zero errors and 23 warnings, covering dependency versions, arm64-only support, wake-lock guidance, an obsolete SDK check, and unused resources.
- `./scripts/build-native-jni.sh --test`: native contract regression passed; the same test rejected the original JNI implementation.
- `./scripts/test-transcriber-lifecycle.sh`: 13 native-transcriber lifecycle/failure scenarios passed against the actual Kotlin source with controlled Android/JNI stubs.
- `./scripts/test-speech-lifecycle.sh`: nine OS-recognizer scenarios passed.
- `./scripts/test-listening-service.sh`: five service scenarios passed, including final persistence, terminal cleanup, stale notification suppression, and restart ordering.
- `python3 scripts/test-transcript-window.py`: production SQLite query passed backlog, partial replacement, timestamp tie, and day-isolation checks.
- APK signature and ZIP alignment checks passed. All five packaged native libraries have 16 KB ELF segment alignment and 16 KB ZIP data offsets. The packaged JNI matches Gradle's stripped output and imports the required update initializer.
- `git diff --check` passed.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (9,901,782 bytes).

SHA-256: `f3e1b123628978c9f2018960b2e0052ce6b0c158f9fa978ca03f68116994ed96`.

No Android device was connected. These checks establish compilation, packaging, native API use, lifecycle ordering, and tested failure handling. A physical phone and real model are still needed to validate microphone routing, transcription quality, real-time performance, and background operation. No paid AI requests were used in verification.

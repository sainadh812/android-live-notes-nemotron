# LiveMeetingNotes

Android app in Kotlin for live speech transcription, daily notes, and AI summaries with action items. Supports Android 8+ on arm64 devices.

[Download Preview 1.1.0 APK](https://github.com/sainadh812/android-live-notes-nemotron/releases/download/v1.1.0-recorder-playback/android-live-notes-nemotron-v1.1.0-preview.apk) · [Model comparison and validation](SPEECH_MODELS.md)

- Choose Moonshine Tiny Streaming for English short notes, Nemotron English for longer English sessions, or Nemotron 3.5 for 32 supported language locales. All downloaded models run on the CPU on your phone. See [model choices and evidence](SPEECH_MODELS.md).
- Android speech is an explicit alternative and the initial choice on a new installation. Its provider may use network recognition; availability depends on the device. Downloads do not change your selected engine.
- A foreground service supports phone and Bluetooth microphones, including capture with the screen off.
- Room stores transcript chunks and daily notes locally. OpenAI, DeepSeek, or Qwen can summarize text using the user's API key.
- Partial transcripts schedule a summary at most once per 30-second interval. Final results request a prompt refresh; updates arriving during a request are coalesced. Failed summaries expose a Retry summary action.
- Microphone capture runs independently of native inference, with a bounded 10-second queue. Stop drains accepted audio before finalizing. Overload stops capture with a visible error.
- New recordings store stable transcript segments and update one tentative segment. Interrupted Android recognizer results are preserved with an explicit uncertain status before retries. Existing notes survive the database upgrade.

- Record, Notes, and Settings tabs keep capture controls separate from downloads and AI setup. Model files use immutable URLs and SHA-256 verification, with validated partial-download recovery.

## Recorder and playback

Version 1.1.0 adds a live microphone level display, recording timer, auto-following transcript, an audio library, and a dedicated playback screen. Copy/share/export actions are also available for daily summaries and action items. Recordings stay in app-private storage; sharing grants temporary read access to the selected WAV only. WAV audio uses about 1.9 MB per minute and is not included in cloud backup. The app pauses playback when starting capture, leaving the app, losing audio focus, or disconnecting headphones.

Audio is checkpointed while capturing and finalized before it appears as playable. After a process restart, valid interrupted WAV files are recovered. Audio-only captures are retained if recognition fails. A Room v3 migration preserves old transcripts, partial revisions, and summaries. Models and saved recordings are separate: removing a speech model does not remove recordings.

See [recorder implementation and validation](RECORDER_PLAYBACK.md) for supported behavior and test coverage.

## Build and test

Install JDK 17 and the Android SDK with platform 35, build tools 34.0.0, and NDK 27.2.12479018. Set `ANDROID_HOME` to your SDK directory, or put `sdk.dir=/path/to/android-sdk` in the ignored `local.properties` file.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The current app version is 1.1.0 (version code 5). Windows users can invoke the same tasks with `gradlew.bat`.

For a phone trial alongside an older installation, build with `-PpreviewBuild=true`:

```sh
./gradlew :app:assembleDebug -PpreviewBuild=true
```

This produces **LiveMeetingNotes Preview** with package `com.sainadh.livenotes.preview`, separate notes/settings/models, and version 1.1.0-preview. The Preview APK uses this workspace’s existing debug signing key and is intended to update the previous Preview installation, preserving its notes and models. Android requires the installed package to have the same signing certificate. The original non-Preview app remains a separate installation.

Network-specific Gradle proxy settings belong in your personal `~/.gradle/gradle.properties`; the repository does not force a corporate proxy.

The app packages arm64 native libraries. The JNI source and binary hashes are checked before each Android build. After changing C++, rebuild the JNI library using the pinned script described in [native build instructions](app/src/main/cpp/BUILDING.md):

```sh
ANDROID_NDK_HOME=/path/to/android-sdk/ndk/27.2.12479018 ./scripts/build-native-jni.sh
```

Focused regression checks on Linux:

```sh
./scripts/build-native-jni.sh --test
./scripts/test-transcriber-lifecycle.sh
./scripts/test-speech-lifecycle.sh
./scripts/test-listening-service.sh
python3 scripts/test-transcript-window.py
```

The native and Kotlin host checks use controlled test doubles and validate the actual source; they do not replace a phone test. See [lifecycle test details](tests/host/README.md).

## Use on a phone

1. Install the debug APK on an arm64 Android device and grant microphone access. Notification and Bluetooth permissions are optional for phone-microphone capture.
2. Start listening to check the OS recognizer. No AI key is needed for the live transcript.
3. In Settings, download a speech model, then tap **Use this model**. Start a new recording to apply your choice. Moonshine Tiny is a small English model to try first. Multilingual Nemotron provides a language selector, including auto-detect.
4. Select an AI provider, save your own API key, and test the connection for summaries. Transcription errors and summary errors are shown separately.
5. Stop recording when finished. The UI distinguishes preparing, recording, and saving. Shutdown preserves the final transcript before closing the service. A slow native call may take time to finish.
6. Open **Notes** to reopen saved recordings. Copy text to paste into any app, share text or WAV audio, or save a `.txt` document. Play, pause, scrub, skip ten seconds, and change playback speed. Tap a timed word or timestamp to play from that position; follow mode scrolls the transcript as the highlighted word changes.
7. **Saved audio requires a downloaded speech model.** Native recordings save the same 16 kHz mono PCM used for transcription; Android speech remains text-only because generic recognition providers cannot reliably supply their microphone stream. Existing archives remain text-only.
8. **Nemotron playback uses decoder word timing** when its timing matches the saved text. **Moonshine and unavailable timing use estimates**, clearly labeled in the player. Decoder frame resolution, recognition errors, and estimated timing can affect alignment; live timestamps are approximate until final timing is available.
9. Your AI key is entered in Settings; distributed APKs never read a key from the build machine. No key is needed for recording or local transcription.

## Validation limits

The JNI update-initialization defect and asynchronous shutdown races have regression coverage. Native library segments support 16 KB pages. Real microphone routing, recognition quality, performance, and background behavior still need validation on a physical phone with a valid model. Models are downloaded separately and are not bundled in the APK. This build targets arm64; an x86 emulator cannot execute the bundled native engine.

See [transcription reliability changes](TRANSCRIPTION_RELIABILITY.md) for capture buffering, retry recovery, storage migration, and remaining limits.

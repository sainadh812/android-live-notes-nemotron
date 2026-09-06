# LiveMeetingNotes

Android app in Kotlin for live speech transcription, daily notes, and AI summaries with action items. Supports Android 8+ on arm64 devices.

- Nemotron 3.5 performs on-device transcription after a GGUF model is downloaded in Settings. The bundled engine runs on the CPU.
- Before a model is available, the app uses Android SpeechRecognizer. That provider may use network recognition; availability depends on the device.
- A foreground service supports phone and Bluetooth microphones, including capture with the screen off.
- Room stores transcript chunks and daily notes locally. OpenAI, DeepSeek, or Qwen can summarize text using the user's API key.
- Partial transcripts schedule a summary at most once per 30-second interval. Final results request a prompt refresh; updates arriving during a request are coalesced. Failed summaries expose a Retry summary action.

## Build and test

Install JDK 17 and the Android SDK with platform 35, build tools 34.0.0, and NDK 27.2.12479018. Set `ANDROID_HOME` to your SDK directory, or put `sdk.dir=/path/to/android-sdk` in the ignored `local.properties` file.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The current app version is 1.0.1 (version code 2). Windows users can invoke the same tasks with `gradlew.bat`.

For a phone trial alongside an older installation, build with `-PpreviewBuild=true`:

```sh
./gradlew :app:assembleDebug -PpreviewBuild=true
```

This produces **LiveMeetingNotes Preview** with package `com.sainadh.livenotes.preview`, separate notes/settings/models, and version 1.0.1-preview. It avoids signing-key conflicts with older APKs. Download a model again inside the preview app to test Nemotron; the original app and its data are preserved.

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
3. In Settings, download a Nemotron model. Stop and restart listening to switch to it. With multiple downloaded models, the app currently prefers Q8_0.
4. Select an AI provider, save your own API key, and test the connection for summaries. Transcription errors and summary errors are shown separately.
5. Stop listening when finished. Shutdown preserves the final transcript before closing the service. A slow native call may take time to finish, but it cannot be freed while in use.

## Validation limits

The JNI update-initialization defect and asynchronous shutdown races have regression coverage. Native library segments support 16 KB pages. Real microphone routing, recognition quality, performance, and background behavior still need validation on a physical phone with a valid model. Models are downloaded separately and are not bundled in the APK. This build targets arm64; an x86 emulator cannot execute the bundled native engine.

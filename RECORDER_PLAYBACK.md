# Recorder playback (1.1.0)

New downloaded-model recordings keep actual microphone audio and transcript segments. The microphone owner writes 16 kHz, mono, 16-bit PCM to an app-private WAV before enqueuing the same samples for transcription. Inference and capture keep their existing independent workers. No second microphone recorder is started.

The recorder screen shows sample-based elapsed time, measured microphone level, and incoming text with auto-follow. Notes includes audio-only recordings and earlier text-only archives. A recording opens a dedicated player with play/pause, seek slider, ten-second skips, playback speeds, estimated word highlighting, and tap-word seeking. Copy works with the system clipboard; text and audio use Android's share sheet; Save .txt uses the system document picker. Daily summaries/actions also have text controls.

## Timing and engine support

Nemotron recordings use decoder token timestamps joined into words after finalization. The pinned engine exposes token rows but leaves its word table empty in streaming mode, so the JNI bridge groups decoded pieces at whitespace boundaries while retaining their audio-relative times. The timing sidecar is accepted only when it covers the saved transcript exactly and fits the saved audio duration. A real Nemotron English model test against the bundled 11-second JFK sample produced 22 timed words matching the complete transcript. Decoder alignment still has finite frame resolution and is not a guarantee of perfect acoustic boundaries.

For Moonshine or unavailable/mismatching native timing, word intervals are estimates from consumed PCM and streaming text boundaries, distributed by visible character count. These can include decoder delay or silence and are labeled approximate. Live timestamps use these estimates until final decoder timing is available. The UI does not invent timestamps for historical text.

Generic Android SpeechRecognizer remains text-only. Android's external audio source extra is optional and providers may ignore it and open the microphone themselves, so using it alongside a recorder could silently corrupt capture. Choose a downloaded model in Settings for saved audio and playback. Provider behavior is documented in [RecognizerIntent.EXTRA_AUDIO_SOURCE](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE).

## Storage and lifecycle

Room v3 adds recording metadata and nullable segment bounds. Migrations preserve v1 and v2 rows without guessing old audio or recording boundaries. Begin is persisted before microphone start; final metadata waits for transcript writes and WAV completion. Native files use a UUID basename under files/recordings. FileProvider exposes only that directory, with temporary read grants for selected audio.

WAV headers are checkpointed every two seconds. Process startup validates and recovers interrupted .part files using actual PCM length, drops an incomplete sample byte, and repairs headers. Valid finalized WAVs are also recovered when process death occurred between file rename and database completion. Malformed audio is never exposed as playable. Normal stop retains final and interrupted words. Abrupt service destruction can leave metadata pending until the next app-process startup.

Playback uses one MediaPlayer owned by the ViewModel, generation-safe preparation callbacks, audio focus, and headphone-disconnect handling. It pauses on backgrounding and new capture. Recording remains in its microphone foreground service. Audio is not included in Android backup rules; export/share recordings you want to keep outside the app.

## Verification

Automated coverage includes WAV sample integrity and recovery, stop/loading/inference races, queue overflow, Room upgrades, segment revisions, estimated word offsets, actual MediaPlayer playback/seek, and FileProvider isolation. Full Android build, unit tests, lint, and instrumented test results are recorded in the GitHub release notes. The Android recorder checks workflow uses an accelerated x86 emulator for framework/UI tests. Its `-PemulatorTests=true` package is separately named `com.sainadh.livenotes.emulatortest` and excludes ARM libraries; it is never distributed as a phone APK. Actual native speech/timing is covered separately by the pinned JNI host harness and real-model sample test.

Real microphone quality, Bluetooth routing, speech accuracy, and performance need a physical arm64 phone. The x86 emulator cannot execute the bundled native speech libraries. The APK downloads models separately and does not bundle an AI key. Preview APKs use the workspace's existing debug signing certificate; they are installable test builds, not Play Store releases.

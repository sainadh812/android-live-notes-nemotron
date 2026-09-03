# Project Goal

Build an Android app (LiveMeetingNotes / android-live-notes-nemotron) that
does fully on-device live speech-to-text using NVIDIA Nemotron 3.5
streaming ASR, replacing the cloud-API-based transcription in the original
app. Runs on Samsung Galaxy S25 Ultra (Android 15).

Repo: https://github.com/sainadh812/android-live-notes-nemotron
Local checkout: /tmp/android-live-notes

## What's built
- transcribe.cpp cross-compiled for arm64-v8a (NDK r27c, CPU backend)
- JNI bridge (nemotron_jni.cpp) wrapping transcribe.cpp's streaming C API
- NemotronTranscriber.kt - Kotlin wrapper, drop-in for old SpeechTranscriber
- In-app GGUF model downloader (4 quants: Q8_0/Q6_K/Q5_K_M/Q4_K_M)
- ForegroundListeningService auto-picks Nemotron vs OS SpeechRecognizer
  based on whether a model file exists on device
- Debug APK builds clean, pushed to GitHub Releases each time (git push
  itself is broken through the TI proxy - use Git Data API instead)

## Known bug history
- v0.3.0: fixed committed/tentative transcript overwrite bug (was
  silently dropping transcript text on every audio chunk)
- v0.4.0: caught native-load crash risk, fixed stale transcriber
  selection, added dedicated live-transcript UI card
- v0.5.0: fixed repo integrity - GitHub repo was missing ~60 baseline
  files (manifest, SpeechTranscriber, resources, gradle config), never
  buildable from a fresh clone until this release
- v0.6.0: fixed handle/audioRecord/captureThread missing @Volatile
  (found by an independent review session) - could leak the native
  session if stop()/destroy() raced a still-running init thread
- v0.7.0: rebuilt all 5 native .so libs (ggml-base, ggml, ggml-cpu,
  transcribe, nemotron_jni) with -Wl,-z,max-page-size=16384. Verified
  0x4000 alignment end-to-end: fresh rebuild -> copied into jniLibs ->
  packaged into APK -> unzipped and re-checked inside the final APK.
  This was the leading suspect for total silent transcription failure
  on 16KB-page devices (S25 Ultra is this class) - now resolved at the
  binary level. Still needs on-device confirmation that this was the
  actual root cause (see Current status below).
- v0.8.0: a THIRD independent review re-verified v0.7.0's 16KB fix
  directly (own readelf/nm run, not commit-message trust) - confirmed
  true. Found one new potential crash risk: BluetoothAudioRouter's
  audio-routing calls (setMode/setCommunicationDevice/
  startBluetoothSco) need MODIFY_AUDIO_SETTINGS, which was missing
  from AndroidManifest.xml, called unconditionally on every
  startListening() with no try/catch anywhere in the call chain - if
  it throws, it would crash the app before the transcriber even
  starts, looking identical to "still totally silent" even though the
  16KB fix worked. Added the permission + defensive try/catch in
  BluetoothAudioRouter as belt-and-suspenders. Also fixed: OkHttp
  client had no explicit timeouts (10s default, too short for a real
  LLM chat-completion call with a full transcript window) - now 60s
  read/write.

## Resolved findings from independent review
- 16KB page-size alignment: FIXED in v0.7.0, RE-CONFIRMED independently
  in v0.8.0's review via direct readelf/nm inspection (not just trusting
  the commit message).
- Missing MODIFY_AUDIO_SETTINGS permission + unhandled SecurityException
  risk in BluetoothAudioRouter: FIXED in v0.8.0.
- OkHttp default 10s timeouts too short for real LLM calls: FIXED in
  v0.8.0.

## Still open (not fixed - needs user input before touching)
- LlmProvider.OPENAI.defaultModel = "gpt-5.4" is not a real OpenAI
  model ID - would 404 if a user picks OpenAI without overriding the
  model field. Blocks summarization (goal #2), not transcription
  (goal #1). Needs user input on the correct model name.
- app/build.gradle.kts loadEmbeddedEnvValue() reads
  rootProject.file("../../.env") - resolves two directories ABOVE the
  repo root from a normal clone (verified: resolves to /.env from
  /tmp/android-live-notes), so the "embedded default API key" feature
  advertised in the Settings UI copy is effectively always blank/false
  on a normal checkout. Needs to know the intended file layout before
  fixing (was this designed assuming the repo is nested inside some
  specific workspace structure?).
- gradle.properties hardcodes the TI corporate proxy
  (webproxy.ext.ti.com:80) - breaks a truly clean build off that
  specific network. Low priority, environment-portability only.
- app/src/main/cpp/nemotron_jni.cpp has no CMakeLists.txt/
  externalNativeBuild wiring in this repo - the checked-in .cpp source
  is never compiled by Gradle; the shipped .so is a pure prebuilt
  binary with no build-time proof the source matches the binary. Not
  a runtime bug, just a build-integrity gap (the actual JNI bridge
  build lives in a separate location - see the
  transcribe-cpp-android-jni skill for the full build pipeline).

## Current status (as of this file)
Three independent code review passes have now checked the STT
pipeline. All known bugs, including the 16KB page-size alignment
issue and a newly-found MODIFY_AUDIO_SETTINGS permission gap, are
fixed as of v0.8.0. v0.8.0 is the build to test next on the actual
device.

If v0.8.0 still doesn't transcribe, static analysis has been
exhausted on every suspect found across three review passes - the
next step is an actual adb logcat capture from the S25 Ultra (grep
for NemotronJNI/UnsatisfiedLinkError/SecurityException/
AndroidRuntime while toggling listening), since no further leads
remain that are checkable from source alone.

## Standing workflow
After every build/fix: build -> verify APK -> push source+APK to GitHub
as a release (Git Data API) -> give user the release download link.
Don't wait to be asked each time.

##end Goal
So I am expecting them expecting the app to do the transcription first. Okay, so let's make this completely perfectly working. Then we will move on to the other features. In an overall goal, what we're trying to do is we I will speak to the mobile phone, it will capture the audio and convert text and then there is an AI model and the back end which will read the text and then pick up the important points action items from it and log it into the app so this is what I'm trying to build so yeah

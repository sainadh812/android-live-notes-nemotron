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

## Resolved findings from independent review
- 16KB page-size alignment: FIXED in v0.7.0 (see above).
- LlmProvider.OPENAI.defaultModel = "gpt-5.4" is not a real OpenAI
  model ID - would 404 if a user picks OpenAI without overriding the
  model field. Blocks summarization (goal #2), not transcription
  (goal #1). Still open - needs user input on the correct model name.

## Current status (as of this file)
All known STT-pipeline bugs found across two independent code review
passes are now fixed, including the 16KB page-size alignment issue
that was the leading unconfirmed suspect for total silent
transcription failure on the S25 Ultra. v0.7.0 is the build to test
next on the actual device.

If v0.7.0 still doesn't transcribe, the remaining unknowns are: (a)
something device-specific not yet identified by static review, or
(b) a genuinely different failure mode entirely - at that point an
actual adb logcat capture becomes necessary (grep for NemotronJNI/
UnsatisfiedLinkError/AndroidRuntime while toggling listening) since
static analysis has now been exhausted on the known suspects.

## Standing workflow
After every build/fix: build -> verify APK -> push source+APK to GitHub
as a release (Git Data API) -> give user the release download link.
Don't wait to be asked each time.

##end Goal
So I am expecting them expecting the app to do the transcription first. Okay, so let's make this completely perfectly working. Then we will move on to the other features. In an overall goal, what we're trying to do is we I will speak to the mobile phone, it will capture the audio and convert text and then there is an AI model and the back end which will read the text and then pick up the important points action items from it and log it into the app so this is what I'm trying to build so yeah

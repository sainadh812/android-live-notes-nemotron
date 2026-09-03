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

## Open findings from independent review (not yet resolved)
- 16KB page-size alignment: all 5 native .so libs are 4KB-aligned
  (0x1000), not 16KB (0x4000). Leading unconfirmed suspect for total
  silent transcription failure on 16KB-page devices (S25 Ultra is this
  class). Code DOES catch UnsatisfiedLinkError gracefully, but a
  page-size mismatch can manifest as a native SIGBUS/SIGSEGV during
  dlopen that no Kotlin try/catch can intercept. Needs either an
  on-device logcat check or a transcribe.cpp rebuild with
  -Wl,-z,max-page-size=16384.
- LlmProvider.OPENAI.defaultModel = "gpt-5.4" is not a real OpenAI
  model ID - would 404 if a user picks OpenAI without overriding the
  model field. Blocks summarization (goal #2), not transcription
  (goal #1). Needs user input on the correct model name before fixing.

## Current status (as of this file)
User reports on real device: no live transcription happening, no
visible "landing window" for transcripts. Two independent review
passes have checked the STT pipeline end-to-end and confirmed all
previously-known bugs are fixed. The 16KB alignment issue above is
the most likely remaining root cause but is unconfirmed without
device logcat access.

Next step: get an adb logcat capture from the actual S25 Ultra while
toggling listening (grep for NemotronJNI/UnsatisfiedLinkError/
AndroidRuntime) to confirm or rule out the 16KB alignment theory.

## Standing workflow
After every build/fix: build -> verify APK -> push source+APK to GitHub
as a release (Git Data API) -> give user the release download link.
Don't wait to be asked each time.

##end Goal
So I am expecting them expecting the app to do the transcription first. Okay, so let's make this completely perfectly working. Then we will move on to the other features. In an overall goal, what we're trying to do is we I will speak to the mobile phone, it will capture the audio and convert text and then there is an AI model and the back end which will read the text and then pick up the important points action items from it and log it into the app so this is what I'm trying to build so yeah

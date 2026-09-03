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

## Current status (as of this file)
User reports on real device: no live transcription happening, no
visible "landing window" for transcripts. Under investigation:
1. Native lib load failure (System.loadLibrary crash, uncaught -
   would kill whole app process) - not yet confirmed via logcat
2. ForegroundListeningService only picks Nemotron-vs-SpeechRecognizer
   ONCE in onCreate() - if model downloaded after first listen-toggle,
   stays on old path silently
3. Transcript IS shown in HeaderCard (top of screen) but it's a single
   replaceable text line, not a dedicated scrolling transcript view -
   easy to miss

Next step: check adb logcat for UnsatisfiedLinkError/crash while
toggling listening, confirm which of the above is the real cause.

## Standing workflow
After every build/fix: build -> verify APK -> push source+APK to GitHub
as a release (Git Data API) -> give user the release download link.
Don't wait to be asked each time.

##end Goal
So I am expecting them expecting the app to do the transcription first. Okay, so let's make this completely perfectly working. Then we will move on to the other features. In an overall goal, what we're trying to do is we I will speak to the mobile phone, it will capture the audio and convert text and then there is an AI model and the back end which will read the text and then pick up the important points action items from it and log it into the app so this is what I'm trying to build so yeah

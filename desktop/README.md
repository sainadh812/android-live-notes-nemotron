# LiveMeetingNotes for Windows

A Windows desktop recorder with local speech recognition, saved audio, timed
transcripts, editable speaker labels, and optional AI meeting summaries.
The Android app and Windows app are maintained in the same repository. Their
recording libraries are separate.

## Install and record

Download [Windows 1.0.1 Preview](https://github.com/sainadh812/android-live-notes-nemotron/releases/tag/windows-v1.0.1).
Use the Windows x64 EXE installer, or extract the portable ZIP and run
`LiveMeetingNotes.exe` inside its folder. The package includes its Java runtime,
native speech engine, and speaker worker; Java and Python installations are not
required. Keep the files in a portable distribution together.

1. Open **Settings**, choose a speech model, and click **Download from GitHub**.
   When it is ready, click **Use model** unless the card already says
   **Selected**. **Nemotron English** is the initial selection for English
   meetings (approximately 476 MB). Nemotron 3.5 supports the same multilingual
   choices as Android; its Q8 variant is approximately 752 MB. Model files are
   separate from the installer. Browser downloads and manual imports are also
   supported, as described below.
2. Choose the input microphone and supported language. Windows exposes the
   available built-in, USB, and Bluetooth input devices. This version captures
   the selected microphone input; it does not capture Teams/Zoom system output.
3. Start recording. The timer and microphone level reflect captured samples.
   Incoming words appear in the live transcript. **Stop** drains accepted speech
   input and saves the WAV and transcript before the meeting enters the library.
4. Open the meeting in **Library**. Play, pause, seek, skip ten seconds, or tap a
   timestamp/word to play from there. Follow mode scrolls with the current word.
   Playback speeds range from 0.5× to 2×; changing speed also changes pitch.
5. Copy a transcript or summary to paste/share elsewhere, save a `.txt` export,
   or export the WAV to a location outside the app's data folder.

This version uses four CPU inference threads. Intel GPU acceleration has
not been enabled or measured in this build. CPU speed varies by i7 generation,
power settings, model, and other applications running during the meeting.

## Speech model downloads and imports

Windows 1.0.1 uses GitHub for speech-model downloads. The
[models-v1 release](https://github.com/sainadh812/android-live-notes-nemotron/releases/tag/models-v1)
mirrors all six compatible model files, with the same bytes and pinned hashes
as the app's model catalog:

| Settings model card | Matching release asset | Approximate size |
| --- | --- | --- |
| Moonshine Tiny | `moonshine-streaming-tiny-Q8_0.gguf` | 51 MB |
| Nemotron English | `nemotron-speech-streaming-en-0.6b-Q4_K_M.gguf` | 476 MB |
| Nemotron 3.5 · Compact | `nemotron-3.5-asr-streaming-0.6b-Q4_K_M.gguf` | 496 MB |
| Nemotron 3.5 · Full | `nemotron-3.5-asr-streaming-0.6b-Q8_0.gguf` | 752 MB |
| Nemotron 3.5 · Q6 | `nemotron-3.5-asr-streaming-0.6b-Q6_K.gguf` | 622 MB |
| Nemotron 3.5 · Q5 | `nemotron-3.5-asr-streaming-0.6b-Q5_K_M.gguf` | 560 MB |

If an in-app download fails on your network:

1. In **Settings → Local transcription**, click **Open model downloads on GitHub**.
2. Download the matching `.gguf` asset from the release in your browser.
3. Return to **Settings**, find the matching model card, and click **Import .gguf**.
   Select the file you downloaded.
4. Wait for verification, then click **Use model** unless the card already says
   **Selected**. Your selection applies to the next recording.

Import checks the exact file size, GGUF header, and pinned SHA-256 hash before
installing a copy in the app's model folder. It keeps your source file. Only the
six catalog models above are accepted; an arbitrary GGUF model is not supported.
Downloads use the same verification, and a canceled download can resume. Finish
recording or speaker analysis before starting a model download or import.

The Windows package includes model attribution, license copies, and the pinned
manifest under `model-notices` in its resources. The sources are
[Notice.txt](model-mirror/Notice.txt), [manifest.json](model-mirror/manifest.json),
and [license sources and copies](model-mirror/licenses/SOURCES.md). The GitHub
model release also includes `model-attribution.zip` with complete original and
conversion model cards. Model files remain under their upstream licenses.

## Speaker labels and names

Download **Speaker models** in Settings once (approximately 47 MB). Analysis
runs locally in a separate process after recording has stopped and the speech
model has been released. **Automatically analyze speakers** controls whether
that step starts after saving; it needs the optional models to be installed.
You can also start analysis from a saved meeting, follow its progress, cancel,
and retry. Canceling analysis preserves the recording and previous assignments.

The result starts with anonymous labels such as **Speaker 1**. Rename a label
to a person's name to update that meeting's transcript turns and text exports.
Names are entered by the user; the app does not infer real identities or keep a
cross-meeting voice-identification database.

Automatic speaker counting is an estimate. It can split one person into several
labels or combine similar voices. **Merge** combines duplicate labels; the
speaker menu on a transcript turn corrects an individual assignment. If the
participant count is known, analysis can be rerun with a count from 1 to 20.
Rerunning replaces prior speaker names and corrections after confirmation.
Untimed or ambiguous words stay visible as Unknown or Overlapping speakers.

The speaker pipeline uses sherpa-onnx on the CPU with an openly distributed
pyannote 3.0 ONNX segmentation model and a TitaNet-S speaker embedding model.
Model URLs, exact digests, and license sources are recorded in
[speaker-worker/models.json](speaker-worker/models.json) and
[speaker-worker/THIRD_PARTY.md](speaker-worker/THIRD_PARTY.md). Model files and
audio remain on the computer. No gated Hugging Face weights are downloaded.

## Summaries and API keys

Optional summaries use OpenAI, DeepSeek, or Qwen with the user's own API key.
Saving a key does not enable automatic summaries: the **automatic summaries**
setting is an explicit opt-in. When enabled, finalized transcript additions are
summarized at roughly 30-second intervals, with one request sequence at a time.
Revisable text is included in the saved-meeting summary with its uncertainty
status. A final/manual summary processes a complete transcript snapshot in
bounded requests. Failed requests preserve the previous saved summary.

Only transcript text is sent to the selected AI provider for summaries; local
recording, transcription, playback, and speaker analysis do not need an AI key.
Keys are encrypted with Windows DPAPI for the current Windows account. No keys
are embedded in the app or installer.

## Storage and long meetings

The library is under `%LOCALAPPDATA%\LiveMeetingNotesData`, separate from the
installation directory. SQLite holds transcript
segments, speaker names/turns, summaries, and recording metadata. WAV audio and
decoder timing files are stored under `recordings`. Speech models live under
`models`; speaker models live under `speaker-models`.

Audio is 16 kHz mono 16-bit PCM (about 1.9 MB per minute). The recorder writes it
progressively and checkpoints the WAV header every two seconds. A bounded
ten-second queue separates capture from inference. If decoding cannot keep up,
capture stops with an error while already recorded audio is preserved. Missing
or uncertain transcript content is not reported as a complete recording.
Interrupted recording files are recovered on the next launch when valid.

The live preview is capped at 4,000 characters and 120 segments. The complete
transcript remains saved and available through Copy, Save, and View full
transcript. The full view is a snapshot with a refresh action. The library does
not rebuild on each incoming word, and playback uses prepared transcript blocks
and binary search for the current word.

Nemotron uses decoder word timing when it exactly matches the saved text.
Moonshine and unmatched native timing use estimates, labeled in the player.
The pinned Moonshine Tiny runtime has a 4,096-token stream output limit, making
it a short-note option. Hitting an output limit saves the audio and reports an
incomplete transcript. Native engine history costs still require measurement
on actual long recordings.

## Build and verification

The desktop folder is an independent Gradle project using Kotlin and Compose
Desktop. It compiles selected platform-independent source files directly from
the Android app through a generated-source task: WAV recovery, transcript
revision handling, bounded previews, model metadata, timing, and the AI client.
Building desktop does not require the Android SDK.

For a Windows build, install JDK 17, Visual Studio C++ Build Tools, CMake, and
Python 3.11 x64. From the repository root:

```powershell
./gradlew.bat -p desktop test
./desktop/native/build-windows.ps1
./desktop/speaker-worker/build.ps1
./gradlew.bat -p desktop run
./gradlew.bat -p desktop packageExe packageMsi createDistributable
```

Native speech uses the same pinned transcribe.cpp commit as Android. Details are
in [native/README.md](native/README.md). The packaged speaker worker is built with
PyInstaller; [speaker-worker/README.md](speaker-worker/README.md) describes its
protocol, cancellation, and model verification.

[Windows CI](../.github/workflows/windows-desktop.yml) compiles and tests the
desktop app, exercises real speech and speaker models, packages both installers
and the portable application, installs the MSI, and launches the installed app
to verify startup, database initialization, bundled speech DLL loading, the
bundled speaker worker, rendering, and shutdown. Reports,
screenshots, and native-model results are retained as workflow artifacts.

Tests cover an hour's synthetic transcript update count, persistence/recovery,
speaker correction/merging, download/import integrity, playback requests, microphone
conversion, stop races, subprocess cancellation, and UI actions. Real-model
checks use the 11-second JFK speech sample and public two/four-speaker fixtures.
They establish integration on those fixtures, not perfect recognition, reliable
20-person separation, or measured responsiveness on the user's laptop. Physical
microphone/Bluetooth routing and a real hour-long meeting still need laptop
validation.

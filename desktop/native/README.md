# Desktop speech native bridge

The desktop library includes the existing Android JNI implementation unchanged,
renaming its Java binding to `desktop.stt.NativeSpeech`. Its desktop-only initializer
converts Java UTF-16 paths to standard UTF-8, including supplementary characters
in Windows usernames and model filenames. The small logging shim writes to stderr.
Streaming text deltas, output Unicode conversion, model-family selection,
truncation checks, and decoder word timestamps remain shared; initialization
retains the same four inference threads and cleanup behavior.

The first Windows build uses the **CPU backend**. It does not claim GPU or NPU
acceleration. It uses a conservative x64 instruction baseline so the installer does
not silently require the build machine's AVX features. The transcription model is
downloaded separately by the app.

## Windows build

Install JDK 17, Git, CMake, and Visual Studio 2022 C++ build tools. From PowerShell:

```powershell
./desktop/native/build-windows.ps1
```

`JAVA_HOME` must point at the JDK used to build the app. The script checks out
`handy-computer/transcribe.cpp` at
`63a44d9239d610b3908e8a66b384924cd4a77217`, builds the engine and bridge with the
static MSVC runtime, and stages the five required DLLs and licenses in
`desktop/resources/windows-x64/native`. An existing clean engine checkout may be
provided using `-EngineSource`. No model weights are bundled in these DLLs.

The runtime looks in `compose.application.resources.dir/native`, the development
resources folder, or the directory explicitly set by `-Dlivenotes.native.dir=...`.

## Host / CI validation without a microphone

The CMake bridge can also link a host build of the same pinned engine:

```sh
JAVA_HOME=/path/to/jdk cmake -S desktop/native -B desktop/native/build-host \
  -DTRANSCRIBE_SOURCE_DIR=/path/to/transcribe.cpp \
  -DTRANSCRIBE_LIBRARY=/path/to/libtranscribe.so
cmake --build desktop/native/build-host
```

The synchronous Kotlin entrypoint is
`FileTranscriber.transcribe(model, audio, language, onUpdate)`. It streams 16 kHz
mono PCM16 WAV in half-second blocks. The CLI class
`com.sainadh.livenotes.desktop.stt.SpeechFileCli` takes `model.gguf sample.wav
[language]`; use `-Dlivenotes.native.dir=...` on its JVM. It prints duration, decoder
word count, and text, and fails if a model truncates or the fixture produces no text.
No microphone, audio output device, cloud key, or UI is required.

Recorder tests exercise queue overflow, inference/persistence delivery failure,
audio preservation, stopping during model load or blocked inference/microphone
reads, and native teardown before completion.
Player and conversion tests cover signed PCM, sample-rate conversion, seek/speed,
and sparse one-hour WAV access with bounded memory. Windows microphone/device
behavior and listening quality still require real hardware validation.

JavaSound exposes operating-system microphones, including available Bluetooth
inputs; this layer does not capture system playback audio. Alternate input formats
(48/44.1 kHz mono/stereo) are converted to 16 kHz mono. Playback speed uses linear
resampling and therefore changes pitch.

# Handy comparison for Windows 1.0.2

Reviewed Handy's source at commit
[`2bdf9ac05724fd6fa22f28dabb664c54a43fae3c`](https://github.com/cjpais/Handy/tree/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c).
The changes below adapt relevant behavior to this app's existing implementation.
They do not replace the speech model or recording library.

| Area | Handy | LiveMeetingNotes 1.0.2 |
| --- | --- | --- |
| Model files and hosts | Handy's catalog includes all six of our speech GGUF files with the same sizes and SHA-256 hashes. It tries Hugging Face and configured Handy blob mirrors; not every quantization is guaranteed to exist on a mirror. | Keeps those same six files as verified GitHub release assets, plus browser/file import introduced in 1.0.1. No model replacement is needed. |
| Windows networking | Reqwest's enabled defaults use system proxies and native TLS; native-tls uses Windows SChannel. | Enables Java's system proxy selector at startup and validates model-download certificates against Windows trusted roots as well as standard JVM roots. Certificate and hostname checks stay enabled. |
| Capture and transcription | Streaming-capable models receive audio on a separate worker while recording. Other models transcribe after Stop; an empty streaming result can also fall back to batch transcription. | Records audio independently of inference and feeds the live speech model ordered, bounded chunks. Pending audio stays on disk when inference falls behind; Stop ends capture and lets transcription catch up before finalizing the meeting. |
| Microphone setup | CPAL enumerates inputs, chooses device-supported configuration, waits for samples, and records asynchronous stream errors. | Retains JavaSound capture, adds an explicit microphone test without inference/storage, and detects loss of audio frames or a stopped device. Captured WAV data is preserved on failure. |
| Output selection | Handy's output picker controls its start/stop/test feedback sounds through rodio. Its history audio player uses an HTML audio element. | Adds a speaker/headphone picker for meeting playback and a short test tone. Negotiates compatible PCM16 mono/stereo rates and resamples playback while preserving the transcript time scale. Pausing releases the device. |
| Windows microphone permissions | Reads microphone consent settings, including the desktop-app NonPackaged scope, and opens Windows microphone settings. | Adds the corresponding advisory status and Settings links. A desktop-app allowance takes priority over a UWP-only denial. The app never changes permission registry values or treats the advisory check as proof of working capture. |
| Background work | Device enumeration and stream work run away from the UI thread. | Enumeration, capture, playback, tests, and downloads also run away from the UI thread. This was already largely the same. |

The Full model stopping a recording after roughly 30–34 seconds in 1.0.1 was
caused by its ten-second inference queue filling when decoding ran slower than
capture. That duration depends on processing speed; it was not a fixed recording
limit. Version 1.0.2 replaces that queue limit with pending audio on disk. The
recording timer follows captured audio, while transcription can visibly lag.
After **Stop**, the microphone closes and the app processes the remaining audio
before saving the completed transcript. A slower model can therefore leave a
longer catch-up period. The selected model is retained; the user can choose
**Nemotron English** or **Nemotron 3.5 · Compact** before the next meeting to
reduce compute requirements.

Handy is not exclusively a record-then-transcribe app at the reviewed commit:
its [start/stop actions](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src-tauri/src/actions.rs#L503)
start streaming for capable models and finalize it on Stop, with a
[batch fallback](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src-tauri/src/actions.rs#L715)
when there is no usable streaming result. Its
[stream router](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src-tauri/src/managers/transcription.rs#L138)
uses an unbounded in-memory channel, and its
[recorder](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src-tauri/src/audio_toolkit/audio/recorder.rs#L631)
also retains processed samples. LiveMeetingNotes uses disk storage for its
pending audio so that a slow decoder does not require an ever-growing audio
queue in RAM. Disk space and model processing time still limit what either
workflow can complete.

Relevant Handy sources: [download implementation](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src-tauri/src/managers/model/download.rs),
[model catalog](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src-tauri/src/catalog/catalog.json),
[dependencies](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src-tauri/Cargo.toml),
[recorder](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src-tauri/src/audio_toolkit/audio/recorder.rs),
[device and permission commands](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src-tauri/src/commands/audio.rs),
[feedback playback](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src-tauri/src/audio_feedback.rs),
and [history audio player](https://github.com/cjpais/Handy/blob/2bdf9ac05724fd6fa22f28dabb664c54a43fae3c/src/components/ui/AudioPlayer.tsx).
Platform behavior: [Reqwest 0.12.28 defaults](https://docs.rs/reqwest/0.12.28/reqwest/#optional-features),
[native-tls Windows implementation](https://docs.rs/native-tls/0.2.18/native_tls/#how-is-this-implemented),
and [Java networking properties](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/doc-files/net-properties.html).

The proxy/certificate difference is a plausible cause of office-network download
failures, not a diagnosis of this particular network. A proxy requiring sign-in,
an unapproved download host, or a policy-controlled microphone can still need
action in the browser or Windows Settings.

The audio backends remain different: Handy uses CPAL/rodio, while this app uses
JavaSound. Automated checks cover format conversion, playback position,
cancellation, device failures, certificate trust, UI controls, real speech, and
packaged startup. They cannot certify a physical USB/Bluetooth driver or an
office proxy that is unavailable to the build runner. The app still records a
microphone input; selecting speakers does not add Teams/Zoom system-audio capture.

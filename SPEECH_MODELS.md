# Speech model choices — September 6, 2026

There is no phone-tested universal winner. This release makes model choice explicit, adds smaller English alternatives, and preserves existing Nemotron models and notes. The speech engine is separate from the optional cloud model used for summaries.

## Included in the app

| Choice | Download (decimal MB) | Use and limits |
| --- | ---: | --- |
| Moonshine Tiny Streaming Q8 | 50.5 | First lightweight English trial. The pinned runtime caps one stream at 4,096 output tokens, roughly 17 minutes of typical speech; this is an output limit, not a guaranteed duration. |
| Nemotron English 0.6B Q4 | 475.4 | NVIDIA’s English-specific streaming model, for longer English sessions. CPU speed depends on the phone. |
| Nemotron 3.5 Compact Q4 | 495.8 | Multilingual streaming: 32 supported locales plus automatic language detection. Includes Hindi; Telugu is not supported by this checkpoint. |
| Nemotron 3.5 Q5 / Q6 / Q8 | 559.6 / 621.4 / 751.1 | Existing less-compressed variants remain available under More model variants. Larger weights are not a claim of measured phone accuracy. |
| Android speech | Provider dependent | No app model download. Availability, recognition quality and network use depend on the installed Android speech service. |

All app downloads are transcribe.cpp-compatible GGUF conversions from handy-computer. Their revisions, exact sizes and publisher SHA-256 digests are pinned in [SpeechModel.kt](app/src/main/java/com/sainadh/livenotes/stt/SpeechModel.kt). These sizes differ from Moonshine’s official ORT downloads and NVIDIA’s newer NeMo-Speech.cpp conversions. A shared file extension does not establish runtime compatibility.

Downloaded files must pass exact length, GGUF container and SHA-256 checks before publication. HTTP partial responses must match the requested offset and pinned total. Existing installed files get a cheap size/container check at startup; invoking download on an existing file performs full hashing. Already-installed legacy files are not claimed to have been cryptographically verified at each startup.

New installations explicitly start with Android speech. Upgrades preserve the previous installed Nemotron preference once. Downloading another model does not select it: choose **Use this model** and start a new recording. A missing selected model or unavailable native engine produces an error rather than silently switching providers.

## Why these choices

The [official Moonshine model catalog](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/) distinguishes native streaming Tiny/Small models from the older non-streaming Tiny/Base family. Its streaming models are licensed under MIT. The app uses the streaming GGUF port already supported by its pinned engine; it does not integrate the official Android ORT runtime in this release. Moonshine Small was evaluated but is not offered in the APK: its initial real-audio run through this runtime lost a substantial final portion of the reference speech. A commitment-policy fix was identified, but Small needs a fresh quality and performance evaluation before inclusion.

Moonshine’s [published mobile comparison](https://moonshine-voice.readthedocs.io/en/latest/moonshine-vs-whisper/) includes Pixel 10a results, but its [benchmark method](https://moonshine-voice.readthedocs.io/en/latest/using/benchmarks/) measures final transcript computation after endpoint detection on a supplied WAV, not end-to-end microphone latency or sustained thermal behavior. Those numbers do not establish performance for this app’s GGUF engine or the user’s phone.

NVIDIA’s [Nemotron 3.5 model card](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) explicitly recommends its [English-specific model](https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b) for English-only use. The multilingual card lists 32 locales usable out of the box and eight requiring adaptation; only the former appear in the app. Its headline throughput figures use an H100 GPU. The current 3.5 license is OpenMDW 1.1; the English model uses NVIDIA Open Model License.

[Whisper Base via whisper.cpp](https://github.com/ggml-org/whisper.cpp/tree/master/models) remains a useful next comparison for wider language coverage. Its live examples repeatedly process audio windows rather than sharing the native cached streaming architecture used here. Integrating it needs a separate capture/segmentation and runtime validation pass. The app does not advertise Whisper support in this release.

## Runtime behavior and testing

JNI now selects the model’s declared streaming family, maps `en-US` to `en` only when the model advertises that language, and uses the public stream snapshot through finalization. Moonshine keeps its live hypothesis replaceable until finalization: its decoder can revise earlier words and punctuation, and committing them early in this runtime can discard later speech. Nemotron retains stable incremental commits. Inference is limited to four CPU threads to leave scheduling room for capture and the UI. The microphone keeps its bounded queue and stop/drain behavior from 1.0.2.

If a model reaches its output limit, the app saves the last returned text, stops capture and tells the user to begin a new recording. It does not silently truncate a running session. Audio already waiting in the queue cannot be decoded after the model limit; raw PCM is not persisted for replay. Speech recognition can still omit or mishear words before any runtime limit is reached.

A finite sample through the real pinned host engine and the JNI bridge checks integration, not general recognition accuracy. A physical arm64 phone test is still required for microphone routing, screen-off operation, multilingual accuracy, and sustained real-time performance. Compare the same speech with Tiny and the relevant Nemotron model; watch caption lag and test Stop after a longer session. Do not infer a ranking from desktop or vendor GPU benchmarks.

### Final finite reference check

After the commitment-policy and UTF-16 fixes, the actual JNI bridge and pinned host engine transcribed all 176,000 samples (11 seconds) from the engine’s `samples/jfk.wav`. Both shipped alternatives returned both complete reference clauses, finalized without a truncation flag, and restarted the same native session:

| Model | Total host wall time, including model load | Load time |
| --- | ---: | ---: |
| Moonshine Tiny Q8 | 5.691 s | 0.259 s |
| Nemotron English Q4 | 8.040 s | 1.199 s |

These are single finite runs on a shared Linux x86_64 host with four inference threads and 500 ms feeds, not Android measurements or an accuracy benchmark. They validate this integration on that fixture. Contention caused much slower earlier runs; no phone performance guarantee follows. The reproducible [real-model smoke script](scripts/test-native-model.sh) rejects an incomplete JFK result instead of treating any nonempty text as success.

# Gemma Summary Prototype

An isolated Android experiment for fully local summaries of long meeting transcripts. It installs as `com.sainadh.livenotes.gemmaprototype` alongside Live Meeting Notes. It does not open or modify that app's recordings or notes database.

## Try it on the phone

1. Install the prototype APK. Keep at least 5 GB of storage free.
2. Choose **Download / resume model** (3.66 GB, ideally on Wi-Fi), or import the exact pinned `.litertlm` file linked below. Its size and SHA-256 are verified before use.
3. Export a recording as plain text from Live Meeting Notes and import it here, or choose the clearly labelled synthetic 125-minute test.
4. Choose **Summarize / resume**. GPU is requested by default; initialization failures are reported and can fall back to CPU. Start with reasoning disabled, then enable final-summary reasoning and run again to reuse completed evidence extraction.
5. Review the cited source evidence. Export the summary or JSON test report using the Android file picker.

The model download needs internet. Summarization does not use any server or API key. Source text, checkpoints and results stay in this test app's private storage unless explicitly exported. Uninstalling the prototype removes that storage. The report includes transcript excerpts.

## What this tests

- Full transcript coverage through bounded chunks with overlap; no head/tail truncation.
- Evidence extraction, recursive merging, final summary, and original source IDs/timestamps.
- Later corrections, unresolved decisions and explicit action owners rather than inferred assignments.
- Per-stage checkpoints keyed to input, model, prompts, runtime/backend and budgets; cancellation and rerunning after process death.
- Optional **final-stage** reasoning capped at 256 tokens inside a total 2,048-token output budget. Extraction and merging do not use reasoning.
- Actual backend, initialization/generation timings, available native token/throughput metrics, sampled peak process PSS and Android thermal status in exported reports.

The 8,192-token context is intentionally bounded. The Android API does not expose standalone tokenization, so preflight uses a conservative UTF-8 byte bound plus template/output reserves. Reported native token counts are separate. Context length and model download size do not determine peak RAM. Real S25 Ultra memory, speed, heat and quality still require running the APK on that phone.

Malformed JSON, missing/unknown citations, output limits or failed merges produce an explicit error and preserve completed checkpoints. Citation validation checks that source IDs exist, not that a claim is true. Important summaries still require review. If a model repeatedly fails a cached reduction, saved checkpoints are retained for diagnosis; this is a prototype rather than a production recovery workflow.

## Pinned dependencies

- [Gemma 4 E4B IT LiteRT model](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm), revision `2eee7ac325f20eb8c9ac1d0e972f7c84663062da`.
- [Exact model download](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm), 3,659,530,240 bytes; SHA-256 `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0`.
- `com.google.ai.edge.litertlm:litertlm-android:0.17.1`. Text-only engine, four CPU threads where CPU is selected, no speech model loaded.
- [Gemma terms](https://ai.google.dev/gemma/terms).

## Build and verification

Use **JDK 21**: the published LiteRT AAR contains Java 21 class files. Our source/target is Java 17. Android SDK 36 is required; the prototype targets API 36 with min API 26 and packages the supported ARM64 and x86_64 runtime libraries.

```sh
./gradlew :gemma-prototype:testDebugUnitTest :gemma-prototype:lintDebug :gemma-prototype:assembleDebug
```

This builds only the separate prototype; no preview flag or Play upload key is used. It produces a debug-signed test APK, not a Play release. The production app's package/version/signing configuration is unchanged.

The pipeline's deterministic JVM tests are not model-quality tests. Android instrumentation checks UI/runtime loading without downloading weights. `scripts/gemma-prototype/` contains the separate real-model host benchmark and explicitly synthetic fixture. Host CPU measurements and emulator tests must not be presented as S25 Ultra measurements.

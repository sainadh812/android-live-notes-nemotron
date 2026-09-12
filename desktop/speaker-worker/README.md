# Packaged local speaker analysis

Run `build.ps1` on Windows x64 with CPython 3.11 x64. It installs pinned
dependencies, tests the worker, builds a PyInstaller directory, verifies its
native import and copies it to `desktop/resources/windows-x64/speaker-worker`.
Ship the whole directory; `_internal` contains the Python runtime and native DLLs.
The Windows app does not need a user-installed Python runtime.

```
speaker-worker.exe --self-test
speaker-worker.exe --install-models --models C:\path\to\speaker-models
speaker-worker.exe --analyze C:\path\to\meeting.wav --models C:\path\to\speaker-models --output C:\path\to\result.json
```

`--num-speakers 3` supplies a known count (1–20). Omit it for automatic count
estimation. The pipeline clusters embeddings across the complete recording,
so global speaker IDs are not reset at chunk boundaries. Inference runs after
capture, in a separate process with at most four CPU threads. It currently
requires mono 16 kHz PCM16 WAV and supports up to 12 hours. The complete decoded
audio and engine clustering state are loaded in the worker; this is an offline
analysis step, separate from the bounded streaming transcription path.

Stdout emits newline-delimited JSON:

```json
{"event":"progress","stage":"analyzing","fraction":0.5,"message":"Finding speaker turns"}
```

An error emits `{"event":"error","message":"..."}` and exits nonzero. Progress
is model-window progress, not a wall-clock completion estimate. The result is
atomically written with `schemaVersion`, `durationMs`, `speakerCount` and `spans`
containing `speakerId`, `startMs`, `endMs`. Numbering follows first appearance.
Silence and unresolved text remain Unknown in the app; actual intersecting
speaker intervals are marked Overlapping. Analysis may mislabel similar voices,
short contributions, distant microphones or simultaneous speech; users can
correct turns and names. No benchmark claim is made for a 20-person meeting.

Only model installation uses the network. Exact model sizes and SHA-256 values
are checked on installation and before every analysis. `ready.json` is written
only after both models verify. The Kotlin client serializes jobs, captures
bounded logs, kills the native process on cancellation, removes the unique
scratch directory and leaves the saved WAV and transcript intact.

Tests without model dependencies:

```
python -m unittest discover -s desktop/speaker-worker/tests -v
```

Optional real-model smoke test downloads the two public sherpa demonstration
recordings into an external temporary directory and runs the packaged worker:

```
python desktop/speaker-worker/smoke_test.py --worker path/to/speaker-worker.exe --work path/to/test-data
```

The smoke test checks two English speakers and four Chinese speakers with known
counts, and also verifies that automatic counting produces valid speech spans.
These short examples test integration, not long-meeting accuracy. Their source
URLs and checksums are pinned in the script. Audio fixtures are not redistributed
in the app. Model and runtime notices are in `THIRD_PARTY.md` and `licenses/`.

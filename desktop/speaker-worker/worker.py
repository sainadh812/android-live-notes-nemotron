"""Local CPU speaker analysis. Audio is never sent over the network.

Only --install-models performs network requests; --analyze verifies local models.
The newline-delimited JSON stdout protocol is deliberately separate from stderr.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import wave

RESOURCE_DIR = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
MANIFEST = json.loads((RESOURCE_DIR / "models.json").read_text(encoding="utf-8"))
MAX_SECONDS = 12 * 60 * 60


def emit(event: str, **values) -> None:
    print(json.dumps({"event": event, **values}, ensure_ascii=True), flush=True)


def progress(stage: str, fraction: float | None, message: str) -> None:
    emit("progress", stage=stage, fraction=fraction, message=message)


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def valid_model(path: Path, entry: dict) -> bool:
    return (path.is_file() and not path.is_symlink()
            and path.stat().st_size == entry["size"]
            and digest(path) == entry["sha256"])


def verify_models(models: Path) -> None:
    for entry in MANIFEST["files"]:
        if not valid_model(models / entry["name"], entry):
            raise ValueError("Speaker models are missing or damaged. Download speaker models again.")


def atomic_json(target: Path, value: dict) -> None:
    # Callers own the job directory. A failed write cannot replace a saved result.
    partial = target.with_suffix(target.suffix + ".partial")
    try:
        with partial.open("w", encoding="utf-8", newline="\n") as sink:
            json.dump(value, sink, ensure_ascii=True, allow_nan=False)
            sink.flush()
            os.fsync(sink.fileno())
        os.replace(partial, target)
    finally:
        partial.unlink(missing_ok=True)


def install_models(models: Path, scratch: Path) -> None:
    models.mkdir(parents=True, exist_ok=True)
    (models / "ready.json").unlink(missing_ok=True)
    total = sum(entry["sourceSize"] for entry in MANIFEST["files"])
    completed = 0
    # Public release downloads avoid unauthenticated GitHub API rate limits.
    # Exact lengths and SHA-256 pin the accepted bytes if a release asset changes.
    for entry in MANIFEST["files"]:
        destination = models / entry["name"]
        if valid_model(destination, entry):
            completed += entry["sourceSize"]
            progress("download", completed / total, "Speaker model already verified")
            continue
        archive = scratch / (entry["name"] + ".download")
        request = urllib.request.Request(entry["sourceUrl"], headers={
            "Accept": "application/octet-stream",
            "User-Agent": "Live-Meeting-Notes-Desktop/1.0",
        })
        progress("download", completed / total, "Downloading speaker models")
        with urllib.request.urlopen(request, timeout=30) as response, archive.open("wb") as sink:
            downloaded = 0
            last_percent = -1
            while True:
                block = response.read(256 * 1024)
                if not block:
                    break
                downloaded += len(block)
                if downloaded > entry["sourceSize"]:
                    raise ValueError("Speaker model download has an unexpected size.")
                sink.write(block)
                fraction = (completed + downloaded) / total
                if int(fraction * 100) != last_percent:
                    progress("download", fraction, "Downloading speaker models")
                    last_percent = int(fraction * 100)
        if downloaded != entry["sourceSize"] or digest(archive) != entry["sourceSha256"]:
            raise ValueError("Speaker model checksum did not match. Please retry the download.")
        staged = scratch / entry["name"]
        if "archiveMember" in entry:
            # Never extract paths from an archive. Copy exactly the one pinned member.
            with tarfile.open(archive, "r:bz2") as bundle:
                member = bundle.getmember(entry["archiveMember"])
                if not member.isfile() or member.size != entry["size"]:
                    raise ValueError("Speaker model archive has an invalid member.")
                with bundle.extractfile(member) as source, staged.open("wb") as sink:
                    shutil.copyfileobj(source, sink)
        else:
            os.replace(archive, staged)
        if not valid_model(staged, entry):
            raise ValueError("Extracted speaker model checksum did not match.")
        # Scratch is created under the same app data root as models by the client.
        # shutil.move also works for a developer supplying a scratch folder elsewhere.
        local_stage = models / (entry["name"] + ".installing")
        shutil.copyfile(staged, local_stage)
        os.replace(local_stage, destination)
        completed += entry["sourceSize"]
        archive.unlink(missing_ok=True)
    verify_models(models)
    atomic_json(models / "ready.json", {"schemaVersion": 1, "modelSet": MANIFEST["id"]})
    progress("ready", 1.0, "Speaker models ready")


def load_audio(path: Path):
    import numpy as np
    with wave.open(str(path), "rb") as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) != (1, 2, 16000, "NONE"):
            raise ValueError("Speaker analysis requires a 16 kHz mono PCM16 WAV recording.")
        frames = source.getnframes()
        if frames > MAX_SECONDS * 16000:
            raise ValueError("Speaker analysis currently supports recordings up to 12 hours.")
        if frames * 2 > path.stat().st_size:
            raise ValueError("Recording is truncated.")
        samples = np.empty(frames, dtype=np.float32)
        offset = 0
        while offset < frames:
            raw = source.readframes(min(16000 * 30, frames - offset))
            if not raw or len(raw) % 2:
                raise ValueError("Recording is truncated or contains an invalid audio frame.")
            pcm = np.frombuffer(raw, dtype="<i2")
            samples[offset:offset + len(pcm)] = pcm / 32768.0
            offset += len(pcm)
        return samples, round(frames / 16)


def result_from_segments(segments, duration_ms: int) -> dict:
    result = []
    labels = {}
    for segment in sorted(segments, key=lambda item: (item.start, item.end, item.speaker)):
        if not (math.isfinite(segment.start) and math.isfinite(segment.end)) or segment.speaker < 0:
            raise ValueError("Speaker engine returned invalid timing or speaker data.")
        start = max(0, min(duration_ms, round(segment.start * 1000)))
        end = max(start, min(duration_ms, round(segment.end * 1000)))
        if end == start:
            continue
        # Stable user-facing numbering follows each speaker's first appearance.
        speaker = labels.setdefault(segment.speaker, f"speaker_{len(labels) + 1}")
        result.append({"speakerId": speaker, "startMs": start, "endMs": end})
    return {"schemaVersion": 1, "durationMs": duration_ms, "spans": result,
            "speakerCount": len(labels)}


def create_engine(models: Path, num_speakers: int | None, threshold: float):
    import sherpa_onnx
    # sherpa 1.12.26 reads models using narrow std::ifstream on Windows. Python
    # changes directory through the wide Windows API; the native model filenames
    # stay ASCII even when the user's profile path contains non-ASCII characters.
    original_directory = Path.cwd()
    try:
        os.chdir(models)
        threads = min(4, os.cpu_count() or 1)
        config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
            segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
                pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                    model="segmentation.onnx"), num_threads=threads, provider="cpu"),
            embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                model="embedding.onnx", num_threads=threads, provider="cpu"),
            clustering=sherpa_onnx.FastClusteringConfig(num_clusters=num_speakers or -1, threshold=threshold),
            min_duration_on=0.3, min_duration_off=0.5,
        )
        if not config.validate():
            raise ValueError("Speaker model configuration is invalid.")
        engine = sherpa_onnx.OfflineSpeakerDiarization(config)
        if engine.sample_rate != 16000:
            raise ValueError("Speaker models have an unsupported sample rate.")
        return engine
    finally:
        os.chdir(original_directory)


def analyze(wav: Path, models: Path, output: Path, num_speakers: int | None, threshold: float) -> dict:
    progress("verify", None, "Checking speaker models")
    verify_models(models)
    progress("loading", None, "Loading the recording and speaker models")
    audio, duration_ms = load_audio(wav)
    if len(audio) == 0:
        result = result_from_segments([], 0)
    else:
        engine = create_engine(models, num_speakers, threshold)
        last_percent = -1

        def callback(done: int, total: int) -> int:
            nonlocal last_percent
            fraction = min(0.99, done / max(total, 1))
            if int(fraction * 100) != last_percent:
                progress("analyzing", fraction, "Finding speaker turns")
                last_percent = int(fraction * 100)
            return 0

        progress("analyzing", 0.0, "Finding speaker turns")
        result = result_from_segments(engine.process(audio, callback=callback).sort_by_start_time(), duration_ms)
    atomic_json(output, result)
    progress("complete", 1.0, f"Found {result['speakerCount']} speakers; review labels before relying on them")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--self-test", action="store_true")
    operation.add_argument("--install-models", action="store_true")
    operation.add_argument("--analyze", type=Path)
    parser.add_argument("--models", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--scratch", type=Path)
    parser.add_argument("--num-speakers", type=int, choices=range(1, 21))
    parser.add_argument("--threshold", type=float, default=0.9)
    args = parser.parse_args()
    try:
        if args.self_test:
            import numpy as np
            import sherpa_onnx
            assert hasattr(sherpa_onnx, "OfflineSpeakerDiarization")
            assert np.frombuffer(b"\x00\x00", dtype="<i2").tolist() == [0]
            emit("ready", protocolVersion=1, engine="sherpa-onnx", engineVersion=sherpa_onnx.__version__)
            return 0
        if args.models is None:
            parser.error("--models is required")
        if not math.isfinite(args.threshold) or not 0.01 <= args.threshold <= 1.99:
            parser.error("--threshold must be between 0.01 and 1.99")
        if args.install_models:
            if args.scratch:
                args.scratch.mkdir(parents=True, exist_ok=True)
                install_models(args.models, args.scratch)
            else:
                with tempfile.TemporaryDirectory(prefix="live-notes-speakers-") as directory:
                    install_models(args.models, Path(directory))
        else:
            if args.output is None:
                parser.error("--output is required")
            analyze(args.analyze, args.models, args.output, args.num_speakers, args.threshold)
        return 0
    except (Exception, KeyboardInterrupt) as error:
        # Keep stdout machine-readable and avoid unbounded native tracebacks in the UI.
        message = str(error)[:1000] or type(error).__name__
        emit("error", message=message)
        print(message, file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

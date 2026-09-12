import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("worker", Path(__file__).parents[1] / "worker.py")
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class WorkerTests(unittest.TestCase):
    def test_unknown_count_does_not_cap_twenty_speakers(self):
        segments = [SimpleNamespace(start=i, end=i + 0.8, speaker=50 - i) for i in range(23)]
        result = worker.result_from_segments(reversed(segments), 23000)
        self.assertEqual(23, result["speakerCount"])
        self.assertEqual("speaker_1", result["spans"][0]["speakerId"])
        self.assertEqual("speaker_23", result["spans"][-1]["speakerId"])

    def test_repeated_speaker_and_overlap_are_preserved(self):
        segments = [SimpleNamespace(start=0, end=2, speaker=9),
                    SimpleNamespace(start=1, end=3, speaker=7),
                    SimpleNamespace(start=4, end=5, speaker=9)]
        result = worker.result_from_segments(segments, 5000)
        self.assertEqual(["speaker_1", "speaker_2", "speaker_1"], [s["speakerId"] for s in result["spans"]])
        self.assertGreater(result["spans"][0]["endMs"], result["spans"][1]["startMs"])

    def test_silence_and_boundary_rounding(self):
        self.assertEqual(0, worker.result_from_segments([], 1000)["speakerCount"])
        result = worker.result_from_segments([SimpleNamespace(start=-0.01, end=1.001, speaker=0)], 1000)
        self.assertEqual({"speakerId": "speaker_1", "startMs": 0, "endMs": 1000}, result["spans"][0])

    def test_non_finite_timestamps_rejected(self):
        with self.assertRaises(ValueError):
            worker.result_from_segments([SimpleNamespace(start=float("nan"), end=1, speaker=0)], 1000)

    def test_install_verifies_before_ready_and_can_reuse_valid_files(self):
        payload = b"example-model"
        entry = {"name": "test.onnx", "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest(),
                 "sourceUrl": "https://example.invalid/model", "sourceSize": len(payload),
                 "sourceSha256": hashlib.sha256(payload).hexdigest()}
        manifest = {"id": "test-model-set", "files": [entry]}
        with tempfile.TemporaryDirectory() as directory, patch.object(worker, "MANIFEST", manifest), patch.object(worker, "progress"):
            root = Path(directory)
            scratch = root / "scratch"
            scratch.mkdir()
            with patch.object(worker.urllib.request, "urlopen", return_value=io.BytesIO(payload)) as download:
                worker.install_models(root / "models", scratch)
                self.assertEqual(1, download.call_count)
                worker.install_models(root / "models", scratch)
                self.assertEqual(1, download.call_count)
            self.assertEqual("test-model-set", json.loads((root / "models/ready.json").read_text())["modelSet"])

    def test_checksum_mismatch_never_marks_ready(self):
        entry = {"name": "test.onnx", "size": 4, "sha256": "0" * 64,
                 "sourceUrl": "https://example.invalid/model", "sourceSize": 4, "sourceSha256": "0" * 64}
        with tempfile.TemporaryDirectory() as directory, patch.object(worker, "MANIFEST", {"id": "test", "files": [entry]}), \
                patch.object(worker, "progress"), patch.object(worker.urllib.request, "urlopen", return_value=io.BytesIO(b"bad!")):
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, "checksum"):
                worker.install_models(root / "models", root)
            self.assertFalse((root / "models/ready.json").exists())
            self.assertFalse((root / "models/test.onnx").exists())

    def test_archive_copies_only_exact_regular_member(self):
        payload = b"model"
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w:bz2") as bundle:
            good = tarfile.TarInfo("bundle/model.onnx")
            good.size = len(payload)
            bundle.addfile(good, io.BytesIO(payload))
            evil = tarfile.TarInfo("../../outside.txt")
            evil.size = 3
            bundle.addfile(evil, io.BytesIO(b"bad"))
        raw = archive.getvalue()
        entry = {"name": "test.onnx", "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest(),
                 "sourceUrl": "https://example.invalid/model", "sourceSize": len(raw),
                 "sourceSha256": hashlib.sha256(raw).hexdigest(), "archiveMember": "bundle/model.onnx"}
        with tempfile.TemporaryDirectory() as directory, patch.object(worker, "MANIFEST", {"id": "test", "files": [entry]}), \
                patch.object(worker, "progress"), patch.object(worker.urllib.request, "urlopen", return_value=io.BytesIO(raw)):
            root = Path(directory)
            worker.install_models(root / "models", root)
            self.assertEqual(payload, (root / "models/test.onnx").read_bytes())
            self.assertEqual(["test.onnx", "models"], sorted([p.name for p in root.iterdir()], reverse=True))

    def test_json_replacement_never_leaves_partial_result(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "result.json"
            path.write_text("old")
            worker.atomic_json(path, {"schemaVersion": 1, "spans": []})
            self.assertEqual([], json.loads(path.read_text())["spans"])
            self.assertFalse(path.with_suffix(".json.partial").exists())


if __name__ == "__main__":
    unittest.main()

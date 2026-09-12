import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("model_mirror_prepare", ROOT / "prepare.py")
mirror = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mirror)


class FakeOpener:
    def __init__(self, responses):
        self.responses = responses

    def open(self, request, timeout):
        return io.BytesIO(self.responses[request.full_url])


class PrepareTest(unittest.TestCase):
    def test_manifest_matches_every_shipped_model_and_pins_notices(self):
        manifest = json.loads((ROOT / "manifest.json").read_bytes())
        mirror.validate_manifest(manifest)
        catalog = (ROOT.parents[1] / "app/src/main/java/com/sainadh/livenotes/stt/SpeechModel.kt").read_text()
        catalog = catalog.split("enum class SpeechModel(", 1)[1]
        actual = {}
        for block in re.findall(r"\b[A-Z][A-Z0-9_]+\((.*?)\n    \)", catalog, re.DOTALL):
            strings = re.findall(r'"([^"\n]*)"', block)
            repositories = [value for value in strings if value.endswith("-gguf")]
            if not repositories:
                continue
            repository = repositories[0]
            offset = strings.index(repository)
            revision, filename, digest = strings[offset + 1:offset + 4]
            size = int(re.search(r"\n\s*(\d+)L,", block).group(1))
            actual[strings[0]] = (filename, revision, size, digest)
        self.assertEqual(actual, {m["id"]: (m["fileName"], m["revision"], m["bytes"], m["sha256"])
                                  for m in manifest["models"]})

    def test_bad_download_never_replaces_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "model.gguf"
            for downloaded, expected, limit in [(b"bad", 3, 3), (b"ok", 3, 3), (b"oversized", 3, 3)]:
                target.write_bytes(b"previous")
                with patch.object(mirror.urllib.request, "build_opener", return_value=FakeOpener({"https://example.test/model": downloaded})), \
                        patch.object(mirror.time, "sleep"):
                    with self.assertRaises(ValueError):
                        mirror.download("https://example.test/model", target, limit,
                                        expected_bytes=expected, sha256=hashlib.sha256(b"yes").hexdigest())
                self.assertEqual(target.read_bytes(), b"previous")
                self.assertFalse(target.with_name("model.gguf.partial").exists())

    def test_complete_preparation_is_reproducible_and_contains_all_licenses(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, source, cache, responses = self.fixture(root)
            first, second = root / "first", root / "second"
            with patch.object(mirror.urllib.request, "build_opener", return_value=FakeOpener(responses)), \
                    contextlib.redirect_stdout(io.StringIO()):
                mirror.prepare(source, first, [cache])
                mirror.prepare(source, second, [cache])
            self.assertEqual(len(list(first.iterdir())), 9)
            self.assertEqual((first / "manifest.json").read_bytes(), source.read_bytes())
            for path in first.iterdir():
                self.assertEqual(path.read_bytes(), (second / path.name).read_bytes())
            checksums = (first / "SHA256SUMS").read_text().splitlines()
            self.assertEqual(len(checksums), 8)
            for checksum in checksums:
                expected, name = checksum.split("  ")
                self.assertEqual(mirror.digest(first / name), expected)
            with zipfile.ZipFile(first / "model-attribution.zip") as archive:
                self.assertEqual(archive.namelist(), sorted(archive.namelist()))
                for model in manifest["models"]:
                    self.assertTrue(set(model["attributionFiles"]) <= set(archive.namelist()))
                for info in archive.infolist():
                    self.assertEqual(info.date_time, (2000, 1, 1, 0, 0, 0))
                    self.assertEqual(info.external_attr >> 16, 0o100644)
                self.assertEqual(archive.read("Notice.txt"), (ROOT / "Notice.txt").read_bytes())
                self.assertEqual(len(json.loads(archive.read("document-checksums.json"))), 6)

    def test_missing_model_card_license_fails_before_weights_are_prepared(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest, source, cache, responses = self.fixture(root)
            responses[manifest["documents"][0]["url"]] = b"<html>upstream error</html>"
            output = root / "output"
            with patch.object(mirror.urllib.request, "build_opener", return_value=FakeOpener(responses)):
                with self.assertRaisesRegex(ValueError, "missing the expected license"):
                    mirror.prepare(source, output, [cache])
            self.assertEqual(list(output.iterdir()), [])

    @staticmethod
    def fixture(root):
        manifest = json.loads((ROOT / "manifest.json").read_bytes())
        cache = root / "cache"
        cache.mkdir()
        for index, model in enumerate(manifest["models"]):
            payload = b"GGUF test fixture " + bytes([index])
            (cache / model["fileName"]).write_bytes(payload)
            model["bytes"] = len(payload)
            model["sha256"] = hashlib.sha256(payload).hexdigest()
        source = root / "manifest.json"
        source.write_bytes((json.dumps(manifest, indent=2) + "\n").encode())
        responses = {doc["url"]: f'---\nlicense_name: {doc["expectedLicense"]}\n---\nOriginal model card.\n'.encode()
                     for doc in manifest["documents"]}
        return manifest, source, cache, responses


if __name__ == "__main__":
    unittest.main()

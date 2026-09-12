"""Prepare verified release assets. No credentials or publishing operations are used."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BLOCK = 1024 * 1024


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for data in iter(lambda: source.read(BLOCK), b""):
            value.update(data)
    return value.hexdigest()


def valid_file(path: Path, expected_bytes: int, sha256: str) -> bool:
    return (path.is_file() and not path.is_symlink() and path.stat().st_size == expected_bytes
            and digest(path) == sha256)


class HttpsRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme != "https":
            raise ValueError("Refusing a model download redirect away from HTTPS")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url: str, target: Path, maximum_bytes: int, *, expected_bytes: int | None = None,
             sha256: str | None = None) -> None:
    """Publish a local file atomically only after its length and digest are valid."""
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + ".partial")
    opener = urllib.request.build_opener(HttpsRedirectHandler())
    try:
        for attempt in range(3):
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "LiveMeetingNotes-model-mirror/1"})
                value = hashlib.sha256()
                count = 0
                with opener.open(request, timeout=60) as source, partial.open("wb") as sink:
                    for data in iter(lambda: source.read(BLOCK), b""):
                        count += len(data)
                        if count > maximum_bytes:
                            raise ValueError(f"Download exceeded the pinned size: {target.name}")
                        sink.write(data)
                        value.update(data)
                if expected_bytes is not None and count != expected_bytes:
                    raise ValueError(f"Download size mismatch: {target.name}: {count} != {expected_bytes}")
                if sha256 is not None and value.hexdigest() != sha256:
                    raise ValueError(f"SHA-256 mismatch: {target.name}")
                partial.replace(target)
                return
            except (OSError, ValueError):
                partial.unlink(missing_ok=True)
                if attempt == 2:
                    raise
                time.sleep(2 ** attempt)
    finally:
        partial.unlink(missing_ok=True)


def validate_manifest(manifest: dict) -> None:
    if manifest.get("schemaVersion") != 1 or manifest.get("releaseTag") != "models-v1":
        raise ValueError("Unsupported mirror manifest")
    models = manifest["models"]
    if len(models) != 6 or len({m["id"] for m in models}) != 6 or len({m["fileName"] for m in models}) != 6:
        raise ValueError("Expected six distinct speech models")
    for model in models:
        if not re.fullmatch(r"[a-zA-Z0-9_.-]+\.gguf", model["fileName"]):
            raise ValueError("Invalid model filename")
        if not re.fullmatch(r"[0-9a-f]{64}", model["sha256"]) or not 0 < model["bytes"] < 2_000_000_000:
            raise ValueError("Invalid pinned model digest or size")
    for entry in models + manifest["documents"]:
        if not re.fullmatch(r"[0-9a-f]{40}", entry["revision"]):
            raise ValueError("Model sources must use immutable revision IDs")
        if not re.fullmatch(r"[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+", entry["repository"]):
            raise ValueError("Invalid source repository")
        filename = entry["fileName"] if entry in models else "README.md"
        if entry["url"] != f'https://huggingface.co/{entry["repository"]}/resolve/{entry["revision"]}/{filename}':
            raise ValueError("Source URL differs from its pinned repository and revision")
    for entry in manifest["documents"]:
        if not re.fullmatch(r"model-cards/[a-zA-Z0-9_.-]+\.md", entry["fileName"]):
            raise ValueError("Invalid model card filename")
    for entry in manifest["localAttribution"]:
        name = entry["fileName"]
        if name != "Notice.txt" and not re.fullmatch(r"licenses/[a-zA-Z0-9_.-]+", name):
            raise ValueError("Invalid local attribution filename")
        if not valid_file(ROOT / name, entry["bytes"], entry["sha256"]):
            raise ValueError(f"Local attribution is missing or changed: {name}")
    names = {entry["fileName"] for entry in manifest["documents"] + manifest["localAttribution"]}
    if not all(set(model["attributionFiles"]) <= names for model in models):
        raise ValueError("A required model attribution file is missing from the manifest")
    notice = (ROOT / "Notice.txt").read_text(encoding="utf-8")
    if "Licensed by NVIDIA Corporation under the NVIDIA Open Model License" not in notice:
        raise ValueError("The required NVIDIA attribution is missing")


def deterministic_zip(target: Path, entries: dict[str, bytes]) -> None:
    partial = target.with_name(target.name + ".partial")
    try:
        with zipfile.ZipFile(partial, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in sorted(entries):
                info = zipfile.ZipInfo(name, date_time=(2000, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, entries[name], compresslevel=9)
        partial.replace(target)
    finally:
        partial.unlink(missing_ok=True)


def prepare(manifest_path: Path, output: Path, caches: list[Path]) -> None:
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    validate_manifest(manifest)
    output.mkdir(parents=True, exist_ok=True)
    entries = {entry["fileName"]: (ROOT / entry["fileName"]).read_bytes()
               for entry in manifest["localAttribution"]}
    # Fail before publishing weights if complete upstream cards cannot be retrieved.
    with tempfile.TemporaryDirectory(prefix="model-mirror-cards-") as directory:
        documents = []
        for document in manifest["documents"]:
            path = Path(directory) / document["fileName"]
            download(document["url"], path, 2 * BLOCK)
            content = path.read_bytes()
            card = content.decode("utf-8")
            pattern = r"^license(?:_name)?:\s*['\"]?" + re.escape(document["expectedLicense"]) + r"\b"
            if not re.search(pattern, card, flags=re.MULTILINE | re.IGNORECASE):
                raise ValueError(f'Model card is missing the expected license: {document["fileName"]}')
            entries[document["fileName"]] = content
            documents.append({**document, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()})
            print(f'Verified original card: {document["fileName"]}', flush=True)
    entries["document-checksums.json"] = (json.dumps(documents, indent=2, sort_keys=True) + "\n").encode()
    entries["manifest.json"] = manifest_bytes
    for model in manifest["models"]:
        target = output / model["fileName"]
        if not valid_file(target, model["bytes"], model["sha256"]):
            cached = next((directory / model["fileName"] for directory in caches
                           if valid_file(directory / model["fileName"], model["bytes"], model["sha256"])), None)
            if cached:
                partial = target.with_name(target.name + ".partial")
                try:
                    shutil.copyfile(cached, partial)
                    if not valid_file(partial, model["bytes"], model["sha256"]):
                        raise ValueError(f'Cached model changed while copying: {model["fileName"]}')
                    partial.replace(target)
                finally:
                    partial.unlink(missing_ok=True)
            else:
                print(f'Downloading {model["fileName"]}: {model["bytes"]} bytes', flush=True)
                download(model["url"], target, model["bytes"], expected_bytes=model["bytes"], sha256=model["sha256"])
        print(f'Verified model: {model["fileName"]} {model["sha256"]}', flush=True)
    (output / "manifest.json").write_bytes(manifest_bytes)
    deterministic_zip(output / "model-attribution.zip", entries)
    asset_names = sorted([model["fileName"] for model in manifest["models"]] + ["manifest.json", "model-attribution.zip"])
    checksums = "".join(f'{digest(output / name)}  {name}\n' for name in asset_names)
    (output / "SHA256SUMS").write_bytes(checksums.encode("ascii"))
    print(f'Prepared {len(asset_names) + 1} verified assets in {output}', flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=ROOT / "manifest.json")
    parser.add_argument("--cache", type=Path, action="append", default=[])
    args = parser.parse_args()
    prepare(args.manifest, args.output, args.cache)


if __name__ == "__main__":
    main()

"""Publish only verified model assets; never replace a differing release asset."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent


def gh(*arguments):
    result = subprocess.run(["gh", *arguments], check=True, text=True,
                            stdout=subprocess.PIPE, timeout=1800)
    return result.stdout


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def find_release(repository, tag):
    releases = json.loads(gh("api", f"repos/{repository}/releases?per_page=100"))
    return next((release for release in releases if release["tag_name"] == tag), None)


def verify_uploaded(asset, path, expected):
    if (asset["state"] != "uploaded" or asset["size"] != path.stat().st_size
            or asset.get("digest") != "sha256:" + expected):
        raise RuntimeError(f"Existing asset differs: {path.name}. Use a new release tag; do not overwrite it.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()
    repository = os.environ["GITHUB_REPOSITORY"]
    if repository != "sainadh812/android-live-notes-nemotron":
        raise RuntimeError("Model mirror publisher is restricted to the intended repository")
    manifest = json.loads((HERE / "manifest.json").read_text(encoding="utf-8"))
    if manifest["schemaVersion"] != 1:
        raise RuntimeError("Unsupported model mirror manifest")
    tag = manifest["releaseTag"]
    models = manifest["models"]
    names = [model["fileName"] for model in models] + ["manifest.json", "model-attribution.zip", "SHA256SUMS"]
    if len(names) != len(set(names)) or any(Path(name).name != name for name in names):
        raise RuntimeError("Invalid or duplicate release filenames")
    expected = {name: digest(args.assets / name) for name in names}
    for model in models:
        if ((args.assets / model["fileName"]).stat().st_size != model["bytes"]
                or expected[model["fileName"]] != model["sha256"]):
            raise RuntimeError(f"Unverified model: {model['fileName']}")
    if expected["manifest.json"] != digest(HERE / "manifest.json"):
        raise RuntimeError("Prepared manifest differs from the reviewed source")
    checksums = dict((name.strip(), value) for value, name in
                     (line.split(None, 1) for line in (args.assets / "SHA256SUMS").read_text().splitlines()))
    if checksums != {name: value for name, value in expected.items() if name != "SHA256SUMS"}:
        raise RuntimeError("Prepared checksums are incomplete or incorrect")

    release = find_release(repository, tag)
    if release is None:
        gh("release", "create", tag, "--repo", repository, "--draft", "--prerelease",
           "--target", os.environ["GITHUB_SHA"], "--title", "LiveMeetingNotes speech models (models-v1)",
           "--notes-file", str(HERE / "RELEASE_NOTES.md"))
        release = find_release(repository, tag)
    if release is None:
        raise RuntimeError("The draft model release could not be found")
    existing = {asset["name"]: asset for asset in release["assets"]}
    if set(existing) - set(names):
        raise RuntimeError("Unexpected assets exist in the model release")
    for name in names:
        path = args.assets / name
        if name in existing:
            verify_uploaded(existing[name], path, expected[name])
        else:
            if not release["draft"]:
                raise RuntimeError("Published model release is incomplete; refusing to mutate it")
            gh("release", "upload", tag, str(path), "--repo", repository)
        print(f"Verified or uploaded: {name}", flush=True)

    release = find_release(repository, tag)
    if release is None or {asset["name"] for asset in release["assets"]} != set(names):
        raise RuntimeError("Model release does not contain the complete expected asset set")
    for asset in release["assets"]:
        verify_uploaded(asset, args.assets / asset["name"], expected[asset["name"]])
    if release["draft"]:
        gh("release", "edit", tag, "--repo", repository, "--draft=false", "--prerelease")
    print(f"Published verified model mirror: https://github.com/{repository}/releases/tag/{tag}")


if __name__ == "__main__":
    main()

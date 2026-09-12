"""Publish only verified model assets; never replace a differing release asset."""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import subprocess
import urllib.parse

HERE = Path(__file__).resolve().parent


def gh(*arguments):
    result = subprocess.run(["gh", *arguments], check=True, text=True,
                            stdout=subprocess.PIPE, timeout=1800)
    return result.stdout


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def find_release(repository, tag):
    releases = json.loads(gh("api", f"repos/{repository}/releases?per_page=100", "-H", "Cache-Control: no-cache"))
    matches = [release for release in releases if release["tag_name"] == tag or (
        release["draft"] and release["tag_name"].startswith("untagged-")
        and release["name"] == "LiveMeetingNotes speech models (models-v1)")]
    if len(matches) > 1:
        raise RuntimeError("Multiple model mirror drafts exist; refusing to choose one")
    return next(iter(matches), None)


def upload(repository, release_id, path):
    # Address the release by ID. Draft tags can appear as GitHub's temporary
    # untagged-* name, which makes gh release upload's tag lookup unreliable.
    connection = http.client.HTTPSConnection("uploads.github.com", timeout=1800)
    endpoint = f"/repos/{repository}/releases/{release_id}/assets?" + urllib.parse.urlencode({"name": path.name})
    try:
        with path.open("rb") as stream:
            connection.request("POST", endpoint, body=stream, headers={
                "Authorization": "Bearer " + os.environ["GH_TOKEN"],
                "User-Agent": "LiveMeetingNotes-model-mirror/1",
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/octet-stream",
                "Content-Length": str(path.stat().st_size),
            })
            response = connection.getresponse()
            body = response.read()
            if response.status != 201:
                raise RuntimeError(f"GitHub upload failed for {path.name}: HTTP {response.status}")
            return json.loads(body)
    finally:
        connection.close()


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
        # Use the creation response directly: GitHub's release list can briefly
        # omit a newly created draft, even after the create request succeeds.
        release = json.loads(gh("api", f"repos/{repository}/releases", "--method", "POST",
            "-f", f"tag_name={tag}", "-f", f"target_commitish={os.environ['GITHUB_SHA']}",
            "-f", "name=LiveMeetingNotes speech models (models-v1)",
            "-f", "body=" + (HERE / "RELEASE_NOTES.md").read_text(encoding="utf-8"),
            "-F", "draft=true", "-F", "prerelease=true"))
    elif release["draft"] and not release["assets"]:
        release = json.loads(gh("api", f"repos/{repository}/releases/{release['id']}", "--method", "PATCH",
            "-f", f"tag_name={tag}", "-F", "draft=true", "-F", "prerelease=true",
            "-f", f"target_commitish={os.environ['GITHUB_SHA']}",
            "-f", "body=" + (HERE / "RELEASE_NOTES.md").read_text(encoding="utf-8")))
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
            asset = upload(repository, release["id"], path)
            verify_uploaded(asset, path, expected[name])
        print(f"Verified or uploaded: {name}", flush=True)

    release = json.loads(gh("api", f"repos/{repository}/releases/{release['id']}", "-H", "Cache-Control: no-cache"))
    if {asset["name"] for asset in release["assets"]} != set(names):
        raise RuntimeError("Model release does not contain the complete expected asset set")
    for asset in release["assets"]:
        verify_uploaded(asset, args.assets / asset["name"], expected[asset["name"]])
    if release["draft"]:
        gh("api", f"repos/{repository}/releases/{release['id']}", "--method", "PATCH",
           "-f", f"tag_name={tag}", "-F", "draft=false", "-F", "prerelease=true")
    print(f"Published verified model mirror: https://github.com/{repository}/releases/tag/{tag}")


if __name__ == "__main__":
    main()

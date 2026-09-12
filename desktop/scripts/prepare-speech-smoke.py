"""Fetch immutable real-model fixtures for Windows JNI integration tests only."""
import hashlib
from pathlib import Path
import sys
import urllib.request

root = Path(sys.argv[1]).resolve()
root.mkdir(parents=True, exist_ok=True)
fixtures = [
    ("Nemotron 英语 😀.gguf",
     "https://github.com/sainadh812/android-live-notes-nemotron/releases/download/models-v1/nemotron-speech-streaming-en-0.6b-Q4_K_M.gguf",
     "dc959ca31499b114e395c44eb4f0778968f20e5cfb03305a08a39925b2da8e1e"),
    ("jfk.wav", "https://raw.githubusercontent.com/handy-computer/transcribe.cpp/63a44d9239d610b3908e8a66b384924cd4a77217/samples/jfk.wav", None),
]
for name, url, expected in fixtures:
    if "--audio-only" in sys.argv and name.endswith(".gguf"):
        continue
    target = root / name
    if not target.is_file():
        urllib.request.urlretrieve(url, target)
    with target.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if expected and digest != expected:
        raise RuntimeError(f"Checksum mismatch for {name}")
    print(f"Verified fixture: {target.name.encode('ascii', errors='backslashreplace').decode('ascii')} sha256={digest}")

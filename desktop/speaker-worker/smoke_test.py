"""Integration check using public sherpa demonstration audio, downloaded only for tests."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

FIXTURES = [
    ("1-two-speakers-en.wav", "f1c877dc01595e28be7147bf2fe38e5268147a868bf3fdb5c37b97f5940e21f3", 2),
    ("0-four-speakers-zh.wav", "bedf036caed208386c67b4ef4b11f83d74dd0d420b102163a1c33cd09cde7010", 4),
]
BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=True)
    worker = str(args.worker.resolve())
    # Non-ASCII model paths expose Windows native narrow-file API regressions.
    models = args.work / "speaker models 会议"
    subprocess.run([worker, "--self-test"], check=True, timeout=60)
    subprocess.run([worker, "--install-models", "--models", str(models)], check=True, timeout=600)
    evidence = []
    for name, expected, count in FIXTURES:
        wav = args.work / name
        if not wav.is_file() or hashlib.sha256(wav.read_bytes()).hexdigest() != expected:
            urllib.request.urlretrieve(BASE + name, wav)
        assert hashlib.sha256(wav.read_bytes()).hexdigest() == expected, "Fixture checksum mismatch"
        for requested in (count, None):
            result = args.work / (name + (".known.json" if requested else ".auto.json"))
            command = [worker, "--analyze", str(wav), "--models", str(models), "--output", str(result)]
            if requested:
                command += ["--num-speakers", str(requested)]
            subprocess.run(command, check=True, timeout=600)
            payload = json.loads(result.read_text(encoding="utf-8"))
            assert payload["schemaVersion"] == 1
            assert payload["durationMs"] > 10000
            assert payload["spans"], "Expected speech in the fixture"
            assert payload["speakerCount"] == len({s["speakerId"] for s in payload["spans"]})
            if requested:
                assert payload["speakerCount"] == count, payload
            else:
                assert 1 <= payload["speakerCount"] <= 20, payload
            for span in payload["spans"]:
                assert 0 <= span["startMs"] < span["endMs"] <= payload["durationMs"]
            evidence.append({"fixture": name, "requestedCount": requested,
                             "detectedCount": payload["speakerCount"], "spans": len(payload["spans"])})
    (args.work / "smoke-results.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()

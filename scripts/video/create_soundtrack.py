#!/usr/bin/env python3
"""Render the original Live Meeting Notes product-demo bed.

Only generated oscillators are used: no samples, loops, speech, or recordings.
Requires numpy; writes stereo 48 kHz PCM16 WAV plus verification metadata.
"""

from pathlib import Path
import json
import math
import wave

import numpy as np


RATE = 48_000
DURATION = 42.0
HERE = Path(__file__).resolve().parent
OUT = HERE / "live-meeting-notes-original-ambient.wav"


def hz(midi: float) -> float:
    return 440.0 * 2.0 ** ((midi - 69.0) / 12.0)


def smoothstep(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def place(mix, signal, start, pan=0.0):
    first = max(0, round(start * RATE))
    count = min(len(signal), len(mix) - first)
    if count <= 0:
        return
    angle = (pan + 1.0) * math.pi / 4.0
    mix[first : first + count, 0] += signal[:count] * math.cos(angle)
    mix[first : first + count, 1] += signal[:count] * math.sin(angle)


def pad(midi, seconds, phase=0.0):
    t = np.arange(round(seconds * RATE), dtype=np.float64) / RATE
    frequency = hz(midi)
    slow_drift = 0.025 * np.sin(2.0 * math.pi * 0.13 * t + phase)
    oscillator = 2.0 * math.pi * frequency * t + slow_drift
    # Near-sine tone with just enough harmonics for warmth on phone speakers.
    tone = (
        np.sin(oscillator + phase)
        + 0.17 * np.sin(2.0 * oscillator + 0.31)
        + 0.045 * np.sin(3.0 * oscillator + 0.17)
    )
    envelope = smoothstep(t / 1.6) * smoothstep((seconds - t) / 2.1)
    breath = 0.94 + 0.06 * np.sin(2.0 * math.pi * 0.095 * t + phase)
    return tone * envelope * breath * 0.020


def pluck(midi, seconds=3.4):
    t = np.arange(round(seconds * RATE), dtype=np.float64) / RATE
    oscillator = 2.0 * math.pi * hz(midi) * t
    tone = (
        np.sin(oscillator)
        + 0.10 * np.sin(2.0 * oscillator) * np.exp(-3.0 * t)
        + 0.025 * np.sin(3.0 * oscillator) * np.exp(-4.8 * t)
    )
    envelope = smoothstep(t / 0.035) * np.exp(-1.75 * t)
    envelope *= smoothstep((seconds - t) / 0.4)
    return tone * envelope * 0.020


def main():
    count = round(RATE * DURATION)
    mix = np.zeros((count, 2), dtype=np.float64)
    # An original seven-phrase arrangement: Dadd9, Aadd9, Bm7, Gadd9,
    # Dadd9, Gmaj7, Dadd9. Each phrase lasts six seconds.
    chords = [
        (50, 57, 61 + 5, 64),
        (45, 52, 61, 59),
        (47, 54, 62, 57),
        (43, 50, 59, 57),
        (50, 57, 66, 64),
        (43, 50, 59, 66),
        (50, 57, 62, 64),
    ]
    for phrase, chord in enumerate(chords):
        start = phrase * 6.0
        for voice, note in enumerate(chord):
            signal = pad(note, 7.6, phase=voice * 0.71 + phrase * 0.19)
            place(mix, signal, start, pan=(-0.45, 0.38, -0.16, 0.21)[voice])

        # Sparse, rounded upper notes give movement without a drumbeat.
        notes = (chord[1] + 12, chord[3] + 12, chord[2] + 12, chord[1] + 12)
        for step, (offset, note) in enumerate(zip((1.15, 2.65, 4.15, 5.15), notes)):
            if start + offset > 39.0:
                continue
            signal = pluck(note)
            pan = -0.23 if step % 2 == 0 else 0.23
            place(mix, signal, start + offset, pan=pan)
            # Quiet oscillator-derived echoes; no external reverb samples.
            place(mix, signal * 0.22, start + offset + 0.31, pan=-pan)
            place(mix, signal * 0.085, start + offset + 0.64, pan=pan)

    timeline = np.arange(count, dtype=np.float64) / RATE
    fade = smoothstep(timeline / 2.4) * smoothstep((DURATION - timeline) / 3.6)
    mix *= fade[:, None]
    mix -= mix.mean(axis=0, keepdims=True)
    # Apply fade once more at the endpoints after DC removal.
    mix *= smoothstep(timeline / 0.025)[:, None]
    mix *= smoothstep((DURATION - timeline) / 0.05)[:, None]
    mix *= 0.27 / max(float(np.max(np.abs(mix))), 1e-12)
    peak = float(np.max(np.abs(mix)))
    assert np.isfinite(mix).all()
    assert peak <= 0.32
    pcm = np.rint(mix * 32767.0).astype("<i2")
    assert not np.any(np.abs(pcm.astype(np.int32)) >= 32767)
    with wave.open(str(OUT), "wb") as wav:
        wav.setnchannels(2)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(pcm.tobytes())

    with wave.open(str(OUT), "rb") as check:
        actual = np.frombuffer(check.readframes(check.getnframes()), dtype="<i2")
        meta = {
            "file": OUT.name,
            "duration_seconds": check.getnframes() / check.getframerate(),
            "sample_rate_hz": check.getframerate(),
            "channels": check.getnchannels(),
            "sample_width_bytes": check.getsampwidth(),
            "peak_linear": float(np.max(np.abs(actual.astype(np.int32)))) / 32768.0,
            "rms_linear": float(np.sqrt(np.mean((actual.astype(np.float64) / 32768.0) ** 2))),
            "clipped_samples": int(np.count_nonzero(np.abs(actual.astype(np.int32)) >= 32767)),
            "original_synthesis": True,
        }
    assert meta["duration_seconds"] == DURATION
    assert meta["channels"] == 2
    assert meta["peak_linear"] <= 0.32
    assert meta["clipped_samples"] == 0
    (HERE / "soundtrack-verification.json").write_text(json.dumps(meta, indent=2) + "\n")
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Synthesises the app's three UI sounds into app/src/main/res/raw: move, open and back.

Each is a dry blip under 40 ms (no reverb, no music): a sine with a fast attack and an exponential decay, quiet
(peak about -14 dBFS) so it sits under whatever else plays. Run it from android/; it overwrites the files.
Standard library only, so the sounds are ours, with no third-party audio.
"""
import math
import struct
import wave
from pathlib import Path

RATE = 22050
PEAK = 0.2  # about -14 dBFS


def blip(ms, f0, f1, decay, harmonic=0.0):
    n = int(RATE * ms / 1000)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / n
        f = f0 + (f1 - f0) * t
        phase += 2 * math.pi * f / RATE
        attack = min(1.0, i / (RATE * 0.0015))  # 1.5 ms, so it doesn't click
        env = attack * math.exp(-decay * t)
        tail = min(1.0, (n - i) / (RATE * 0.002))  # 2 ms fade to silence
        s = math.sin(phase) + harmonic * math.sin(2 * phase)
        out.append(PEAK * env * tail * s / (1 + harmonic))
    return out


def write(path, samples):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))


def main():
    raw = Path(__file__).resolve().parent.parent / "app/src/main/res/raw"
    raw.mkdir(parents=True, exist_ok=True)
    write(raw / "ui_move.wav", blip(14, 2400, 2200, 9.0, harmonic=0.15))   # a tick
    write(raw / "ui_open.wav", blip(34, 1300, 2000, 5.0, harmonic=0.2))    # a short rise
    write(raw / "ui_back.wav", blip(30, 1700, 1050, 5.5, harmonic=0.2))    # a short fall


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Synthesises the dial click sounds into app/src/main/res/raw/*.wav.

click_fwd_N  - plain single click (clockwise)
click_back_N - ratchet click with a short "spin-through" rattle (counter-clockwise)

Pure stdlib, deterministic (seeded). Re-run to regenerate:  python3 tools/gen_sounds.py
"""
import math
import os
import random
import struct
import wave

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def hit(rng, length, pitch=1.0, gain=1.0):
    """One mechanical 'tick': filtered noise transient + ringing metal + low thump."""
    n = int(SR * length)
    buf = [0.0] * n
    partials = [(1700, 1.0, 0.0060), (2950, 0.65, 0.0042), (5100, 0.35, 0.0026), (7600, 0.15, 0.0015)]
    prev = 0.0
    for i in range(n):
        t = i / SR
        noise = rng.uniform(-1, 1)
        hp = noise - prev  # first difference = crude high-pass, keeps the transient crisp
        prev = noise
        v = hp * math.exp(-t / 0.0006) * 0.9
        for f, a, tau in partials:
            v += a * math.sin(2 * math.pi * f * pitch * t) * math.exp(-t / tau) * 0.45
        v += 0.5 * math.sin(2 * math.pi * 190 * pitch * t) * math.exp(-t / 0.009)
        attack = min(1.0, t / 0.0002)
        buf[i] = v * attack * gain
    return buf


def mix(dst, src, offset):
    for i, s in enumerate(src):
        j = offset + i
        if j < len(dst):
            dst[j] += s


def finish(buf, peak=0.85):
    m = max(abs(x) for x in buf) or 1.0
    fade = int(SR * 0.006)
    for i in range(fade):
        buf[len(buf) - 1 - i] *= i / fade
    return [x / m * peak for x in buf]


def save(name, buf):
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, x)) * 32767)) for x in buf))
    print("wrote", os.path.relpath(path), f"{len(buf) / SR * 1000:.0f} ms")


def forward(seed):
    rng = random.Random(seed)
    pitch = 1.0 + (seed % 3 - 1) * 0.04
    buf = [0.0] * int(SR * 0.075)
    mix(buf, hit(rng, 0.07, pitch), 0)
    return finish(buf)


def backward(seed):
    """Pawl riding over the tooth: dull main click, then a quick tk-tk-tk rattle."""
    rng = random.Random(100 + seed)
    pitch = 0.82 + (seed % 3 - 1) * 0.03
    buf = [0.0] * int(SR * 0.11)
    mix(buf, hit(rng, 0.06, pitch, 1.0), 0)
    mix(buf, hit(rng, 0.04, pitch * 1.25, 0.5), int(SR * 0.0075))
    mix(buf, hit(rng, 0.04, pitch * 1.45, 0.3), int(SR * 0.0140))
    mix(buf, hit(rng, 0.04, pitch * 1.6, 0.16), int(SR * 0.0205))
    return finish(buf)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for i in range(3):
        save(f"click_fwd_{i}", forward(i))
        save(f"click_back_{i}", backward(i))

#!/usr/bin/env python3
"""Synthesises the dial click sounds into app/src/main/res/raw/*.wav.

Both are a dry metallic ratchet "tr-click": a few tiny pawl-chatter ticks that run into one
main click, with a small bounce after it.

click_fwd_N  - clockwise: crisp, ringing click
click_back_N - counter-clockwise: same character, but ~38% quieter, lower and duller (low-passed)

Pure stdlib, deterministic (seeded). Re-run to regenerate:  python3 tools/gen_sounds.py
"""
import math
import os
import random
import struct
import wave

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")

PEAK_FWD = 0.88
BACK_LEVEL = 0.62  # counter-clockwise peak relative to clockwise (-38%)

MAIN_PARTIALS = [(2100, 1.00, 0.0050), (3600, 0.70, 0.0036), (5900, 0.40, 0.0022), (8200, 0.20, 0.0014)]
CHATTER_PARTIALS = [(3200, 1.0, 0.0014), (4800, 0.7, 0.0010), (7000, 0.4, 0.0007)]


def hit(rng, length, partials, pitch=1.0, gain=1.0, noise=0.9, thump=0.0):
    """One mechanical tick: high-passed noise transient + ringing metal (+ optional dry thump)."""
    n = int(SR * length)
    buf = [0.0] * n
    prev = 0.0
    for i in range(n):
        t = i / SR
        x = rng.uniform(-1, 1)
        hp = x - prev  # first difference = crude high-pass, keeps the transient crisp
        prev = x
        v = hp * math.exp(-t / 0.0005) * noise
        for f, a, tau in partials:
            v += a * math.sin(2 * math.pi * f * pitch * t) * math.exp(-t / tau) * 0.45
        if thump:
            v += thump * math.sin(2 * math.pi * 260 * pitch * t) * math.exp(-t / 0.005)
        buf[i] = v * min(1.0, t / 0.00015) * gain
    return buf


def mix(dst, src, at):
    off = int(SR * at)
    for i, s in enumerate(src):
        if off + i < len(dst):
            dst[off + i] += s


def lowpass(buf, fc, poles=2):
    a = 1 - math.exp(-2 * math.pi * fc / SR)
    for _ in range(poles):
        y = 0.0
        for i, x in enumerate(buf):
            y += a * (x - y)
            buf[i] = y
    return buf


def ratchet(rng, pitch):
    buf = [0.0] * int(SR * 0.095)
    # "tr": pawl chatter
    for at, g, p in ((0.0, 0.30, 1.00), (0.0030, 0.45, 1.12), (0.0061, 0.60, 0.95)):
        mix(buf, hit(rng, 0.010, CHATTER_PARTIALS, pitch * p, g, noise=0.7), at)
    # "klick": main hit, then a small bounce of the pawl
    mix(buf, hit(rng, 0.070, MAIN_PARTIALS, pitch, 1.0, thump=0.25), 0.0098)
    mix(buf, hit(rng, 0.020, MAIN_PARTIALS, pitch * 1.2, 0.22, noise=0.5), 0.0098 + 0.013)
    return buf


def finish(buf, peak):
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
    print("wrote", os.path.relpath(path), f"{len(buf) / SR * 1000:.0f} ms, peak {max(abs(x) for x in buf):.2f}")


def forward(seed):
    rng = random.Random(seed)
    return finish(ratchet(rng, 1.0 + (seed - 1) * 0.03), PEAK_FWD)


def backward(seed):
    rng = random.Random(100 + seed)
    buf = ratchet(rng, 0.78 + (seed - 1) * 0.025)  # lower
    buf = lowpass(buf, 2600)  # duller: cut the highs
    return finish(buf, PEAK_FWD * BACK_LEVEL)  # quieter


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for i in range(3):
        save(f"click_fwd_{i}", forward(i))
        save(f"click_back_{i}", backward(i))

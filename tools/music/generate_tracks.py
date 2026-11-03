#!/usr/bin/env python3
"""
GILTUNE procedural scene tracks. 100% synthesized from math (sine/saw/noise oscillators);
NO samples, NO third-party audio, pure math synthesis. Deterministic: same seed -> same audio.
This script + its output hashes (TRACKS_SHA256.txt) are the provenance record.
Usage: python3 generate_tracks.py OUT_DIR   (needs numpy, ffmpeg with libmp3lame)
"""
import sys, os, subprocess, hashlib, numpy as np

SR = 44100
NOTE = lambda m: 440.0 * 2 ** ((m - 69) / 12)

def osc(kind, f, t):
    ph = 2 * np.pi * np.cumsum(np.broadcast_to(f, t.shape)) / SR
    if kind == "sine": return np.sin(ph)
    if kind == "saw":  # band-limited-ish saw: 6 harmonics
        return sum(np.sin(k * ph) / k for k in range(1, 7)) * 0.6
    if kind == "tri": return (2 / np.pi) * np.arcsin(np.sin(ph))
    raise ValueError(kind)

def env(n, a=0.01, r=0.2):
    e = np.ones(n); na, nr = int(a * SR), int(r * SR)
    if na: e[:na] = np.linspace(0, 1, na)
    if nr and nr < n: e[-nr:] *= np.linspace(1, 0, nr)
    return e

def lowpass(x, alpha):
    y = np.empty_like(x); acc = 0.0
    for i in range(len(x)):
        acc += alpha * (x[i] - acc); y[i] = acc
    return y

def track(spec, seed):
    rng = np.random.default_rng(seed)
    bpm, bars, root, prog, scale = spec["bpm"], spec["bars"], spec["root"], spec["prog"], spec["scale"]
    beat = 60 / bpm; bar = 4 * beat
    n = int(bars * bar * SR) + SR
    L = np.zeros(n); R = np.zeros(n)
    def add(sig, start, pan=0.0, gain=1.0):
        s = int(start * SR); e = min(n, s + len(sig)); sig = sig[: e - s] * gain
        L[s:e] += sig * (1 - pan) / 2 * 2 ** 0.5 / 1.0; R[s:e] += sig * (1 + pan) / 2 * 2 ** 0.5
    for b in range(bars):
        deg = prog[b % len(prog)]
        chord = [root + scale[(deg + k) % len(scale)] + 12 * ((deg + k) // len(scale)) for k in (0, 2, 4)]
        t = np.arange(int(bar * SR)) / SR
        pad = sum(osc(spec["pad"], NOTE(m), t) for m in chord) / 3
        pad = pad * env(len(t), 0.3, 0.4)
        add(pad, b * bar, rng.uniform(-.3, .3), spec["pad_gain"])
        for q in range(4):                                     # bass on beats
            tb = np.arange(int(beat * 0.9 * SR)) / SR
            add(osc("tri", NOTE(chord[0] - 24), tb) * env(len(tb), .005, .15), b * bar + q * beat, 0, .35)
        if spec["drums"]:
            for q in range(4):
                tk = np.arange(int(0.25 * SR)) / SR             # kick: pitch-swept sine
                kick = np.sin(2 * np.pi * (50 + 90 * np.exp(-tk * 30)) * tk) * np.exp(-tk * 12)
                add(kick, b * bar + q * beat, 0, .55)
                for h in (0, .5):                                # hat: filtered noise
                    nh = int(.05 * SR); hat = rng.standard_normal(nh) * np.exp(-np.arange(nh) / SR * 70)
                    add(hat - lowpass(hat, .3), b * bar + (q + h) * beat, .2, .10)
                if q in (1, 3):                                  # snare: noise + tone
                    ns = int(.18 * SR); ts = np.arange(ns) / SR
                    sn = (rng.standard_normal(ns) * .6 + np.sin(2 * np.pi * 190 * ts)) * np.exp(-ts * 18)
                    add(sn, b * bar + q * beat, -.1, .22)
        steps = spec["mel_steps"]                                # melody: random walk on scale
        idx = 7
        for s in range(steps):
            if rng.random() < spec["mel_rest"]: continue
            idx = int(np.clip(idx + rng.integers(-2, 3), 0, 13))
            m = root + 12 + scale[idx % len(scale)] + 12 * (idx // len(scale))
            dur = bar / steps
            tm = np.arange(int(dur * .95 * SR)) / SR
            add(osc(spec["lead"], NOTE(m), tm) * env(len(tm), .01, dur * .5), b * bar + s * dur, rng.uniform(-.5, .5), spec["lead_gain"])
    if spec.get("noise_bed"):                                    # wind / tunnel rumble
        bed = lowpass(rng.standard_normal(n), spec["noise_bed"]) ; L += bed * .25; R += np.roll(bed, 400) * .25
    mix = np.stack([L, R], 1)
    fade = int(3 * SR); mix[:fade] *= np.linspace(0, 1, fade)[:, None]; mix[-fade:] *= np.linspace(1, 0, fade)[:, None]
    mix /= np.max(np.abs(mix)) * 1.12
    return (mix * 32767).astype(np.int16)

MAJ = [0, 2, 4, 5, 7, 9, 11]; MIN = [0, 2, 3, 5, 7, 8, 10]; PENT = [0, 2, 4, 7, 9]
SPECS = {
 "highway":      dict(bpm=124, bars=40, root=50, prog=[0, 4, 5, 3], scale=MAJ, pad="saw", pad_gain=.22, drums=True,  mel_steps=8, mel_rest=.2, lead="saw",  lead_gain=.16),
 "city_express": dict(bpm=110, bars=36, root=53, prog=[5, 3, 0, 4], scale=MIN, pad="saw", pad_gain=.20, drums=True,  mel_steps=8, mel_rest=.35, lead="tri", lead_gain=.22),
 "alley":        dict(bpm=78,  bars=24, root=57, prog=[1, 4, 0, 5], scale=MAJ, pad="tri", pad_gain=.30, drums=True,  mel_steps=4, mel_rest=.3, lead="sine", lead_gain=.25),
 "tunnel":       dict(bpm=96,  bars=28, root=45, prog=[0, 0, 5, 6], scale=MIN, pad="saw", pad_gain=.25, drums=True,  mel_steps=4, mel_rest=.5, lead="sine", lead_gain=.18, noise_bed=.01),
 "coastal":      dict(bpm=100, bars=30, root=55, prog=[0, 3, 1, 4], scale=PENT, pad="sine", pad_gain=.35, drums=False, mel_steps=8, mel_rest=.25, lead="tri", lead_gain=.22, noise_bed=.02),
}

if __name__ == "__main__":
    out = sys.argv[1]; os.makedirs(out, exist_ok=True); lines = []
    for i, (name, spec) in enumerate(SPECS.items()):
        pcm = track(spec, seed=20261029 + i)
        wav = os.path.join(out, name + ".raw"); pcm.tofile(wav)
        mp3 = os.path.join(out, name + ".mp3")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(SR), "-ac", "2", "-i", wav,
                        "-codec:a", "libmp3lame", "-b:a", "128k", "-metadata", f"title=GILTUNE {name}",
                        "-metadata", "artist=GILTUNE (procedural, generate_tracks.py)", mp3], check=True)
        os.remove(wav)
        lines.append(f"{hashlib.sha256(open(mp3,'rb').read()).hexdigest()}  {name}.mp3  seed={20261029+i}")
        print(lines[-1])
    open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "TRACKS_SHA256.txt"), "w").write("\n".join(lines) + "\n")

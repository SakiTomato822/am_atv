"""
Deterministic offline composer and renderer for AM Folio welcome-page BGM (Gemini version).

Blends original style traits from:
  - MementoMori Concert OP (opening orchestral chimes, felt piano, chamber strings swell)
  - PS4 home-menu ambience (warm slowly breathing pads, soft harp washes, unobtrusive seamlessness)
  - PS5 home-menu ambience & UI shimmer (spatial depth, soft marimba/droplet micro-accents)

Output files in the script directory (bgm-gemini/):
  - welcome_bgm_gemini.wav         (48 kHz stereo PCM master, 30.000 s)
  - welcome_bgm_gemini.ogg         (Ogg Vorbis for Android raw res, ~150-260 KB)
  - welcome_bgm_gemini_preview.wav (2x concatenated loop for seam listening)
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import warnings

sys.path.insert(0, r"D:\Data for Apps and etc\Codex\audio_analysis_deps")

import numpy as np
from scipy import signal
from scipy.io import wavfile

# ---------------------------------------------------------------------------
# Configuration & Constants
# ---------------------------------------------------------------------------

SEED = 20261127
SR = 48000
BPM = 64.0
BEATS_PER_BAR = 4
NUM_BARS = 8
BEAT_SEC = 60.0 / BPM          # 0.9375 s
BAR_SEC = BEAT_SEC * BEATS_PER_BAR  # 3.75 s
LOOP_SEC = BAR_SEC * NUM_BARS  # 30.000 s
LOOP_SAMPLES = int(round(LOOP_SEC * SR))  # 1,440,000 samples

ASSETS_ROOT = r"D:\Data for Apps and etc\Codex\audio_assets\vsco2ce"
FFMPEG_BIN = r"D:\Useful Tools\ffmpeg-N-124557-g9e71ea2d60-win64-gpl-shared\bin\ffmpeg.exe"
OUT_DIR = os.path.dirname(os.path.abspath(__file__))

OUT_WAV = os.path.join(OUT_DIR, "welcome_bgm_gemini.wav")
OUT_OGG = os.path.join(OUT_DIR, "welcome_bgm_gemini.ogg")
OUT_PREVIEW = os.path.join(OUT_DIR, "welcome_bgm_gemini_preview.wav")

TARGET_LUFS_APPROX = -18.5
TRUE_PEAK_CEIL_DB = -2.8

NOTE_SEMITONES = {
    "C": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3,
    "E": 4, "F": 5, "F#": 6, "Gb": 6, "G": 7, "G#": 8,
    "Ab": 8, "A": 9, "A#": 10, "Bb": 10, "B": 11,
}


def note_name_to_midi(name: str, octave_shift: int = 0) -> int:
    m = re.match(r"^([A-G][#b]?)(-?\d+)$", name.strip())
    if not m:
        raise ValueError(f"Invalid note name: {name!r}")
    pc = NOTE_SEMITONES[m.group(1)]
    octave = int(m.group(2))
    return (octave + 1) * 12 + pc + octave_shift


def midi_to_hz(midi: float) -> float:
    return 440.0 * (2.0 ** ((midi - 69.0) / 12.0))


# ---------------------------------------------------------------------------
# Sample Library Loader (VSCO 2 Community Edition, CC0 1.0)
# ---------------------------------------------------------------------------

def load_wav_stereo_float(path: str, max_sec: float = 7.0) -> tuple[int, np.ndarray]:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        sr, data = wavfile.read(path)
    max_n = int(round(max_sec * sr))
    if len(data) > max_n:
        data = data[:max_n]
    if data.dtype == np.int32:
        audio = (data.astype(np.float32) / 2147483648.0)
    elif data.dtype == np.int16:
        audio = (data.astype(np.float32) / 32768.0)
    elif data.dtype == np.uint8:
        audio = ((data.astype(np.float32) - 128.0) / 128.0)
    else:
        audio = data.astype(np.float32)
    if audio.ndim == 1:
        audio = np.stack([audio, audio], axis=1)
    elif audio.shape[1] > 2:
        audio = audio[:, :2]
    return sr, audio


def cubic_interp_stereo(audio: np.ndarray, t_q: np.ndarray) -> np.ndarray:
    """Fast Catmull-Rom cubic interpolation for (N, 2) float32/float64 audio."""
    n = len(audio)
    idx = np.floor(t_q).astype(np.int64)
    frac = (t_q - idx)[:, None].astype(np.float32)
    i0 = np.clip(idx - 1, 0, n - 1)
    i1 = np.clip(idx, 0, n - 1)
    i2 = np.clip(idx + 1, 0, n - 1)
    i3 = np.clip(idx + 2, 0, n - 1)
    p0, p1, p2, p3 = audio[i0], audio[i1], audio[i2], audio[i3]
    f2 = frac * frac
    f3 = f2 * frac
    return 0.5 * (
        (2.0 * p1)
        + (-p0 + p2) * frac
        + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f2
        + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * f3
    )


def trim_onset_silence(audio: np.ndarray, sr: int, threshold_frac: float, lead_ms: float, fade_in_ms: float) -> np.ndarray:
    env = np.max(np.abs(audio), axis=1)
    peak = float(np.max(env))
    if peak <= 0.0:
        return audio
    hits = np.flatnonzero(env >= threshold_frac * peak)
    if len(hits) == 0:
        return audio
    lead_samples = int(sr * lead_ms / 1000.0)
    start = max(0, int(hits[0]) - lead_samples)
    trimmed = audio[start:].copy()
    fade_len = min(len(trimmed), int(sr * fade_in_ms / 1000.0))
    if fade_len > 0:
        ramp = 0.5 - 0.5 * np.cos(np.pi * np.arange(fade_len) / fade_len)
        trimmed[:fade_len] *= ramp[:, None]
    return trimmed


class SampleBank:
    def __init__(self, root: str) -> None:
        self.root = root
        self.instruments: dict[str, dict[int, list[str]]] = {}
        self.raw_cache: dict[str, tuple[int, np.ndarray]] = {}
        self.shifted_cache: dict[tuple[str, int], np.ndarray] = {}
        self._scan()

    def _add(self, inst: str, midi: int, rel_path: str) -> None:
        full = os.path.join(self.root, rel_path)
        if os.path.isfile(full):
            self.instruments.setdefault(inst, {}).setdefault(midi, []).append(full)

    def _scan(self) -> None:
        # 1. Upright Piano (concert C4 = 60 via MappingChart.txt)
        piano_dir = os.path.join(self.root, "Keys", "Upright Piano")
        chart_path = os.path.join(piano_dir, "MappingChart.txt")
        index_to_midi: dict[str, int] = {}
        with open(chart_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if "=" in line and line[0].isdigit():
                    idx_str, midi_str = line.split("=", 1)
                    index_to_midi[idx_str.strip()] = int(midi_str.strip())

        for dyn in ("dyn1", "dyn2"):
            inst_name = f"piano_{dyn}"
            for fname in sorted(os.listdir(piano_dir)):
                m = re.match(rf"^Player_{dyn}_rr1_(\d+)\.wav$", fname)
                if m and m.group(1) in index_to_midi:
                    self._add(inst_name, index_to_midi[m.group(1)], os.path.join("Keys", "Upright Piano", fname))

        # 2. Harp (concert C4 = 60)
        harp_dir = os.path.join(self.root, "Strings", "Harp")
        for fname in sorted(os.listdir(harp_dir)):
            m = re.match(r"^KSHarp_([A-G][#b]?\d+)_", fname)
            if m:
                self._add("harp", note_name_to_midi(m.group(1), 0), os.path.join("Strings", "Harp", fname))

        # 3. Glockenspiel (named in C3=60 convention -> +12 semitones to concert MIDI)
        glock_dir = os.path.join(self.root, "Percussion", "Glock")
        for fname in sorted(os.listdir(glock_dir)):
            m = re.match(r"^glock_medium_([A-G][#b]?\d+)\.wav$", fname)
            if m:
                self._add("glock", note_name_to_midi(m.group(1), 12), os.path.join("Percussion", "Glock", fname))

        # 4. Marimba (named in C3=60 convention -> +12 semitones to concert MIDI)
        mar_dir = os.path.join(self.root, "Percussion", "Marimba")
        for fname in sorted(os.listdir(mar_dir)):
            m = re.match(r"^Marimba_hit_Outrigger_([A-G][#b]?\d+)_", fname)
            if m:
                self._add("marimba", note_name_to_midi(m.group(1), 12), os.path.join("Percussion", "Marimba", fname))

        # 5. Strings (C3=60 convention -> +12 semitones to concert MIDI; soft v1 layers)
        cello_dir = os.path.join(self.root, "Strings", "Cello Section", "susvib")
        for fname in sorted(os.listdir(cello_dir)):
            m = re.match(r"^susvib_([A-G][#b]?\d+)_v1_\d+\.wav$", fname)
            if m:
                self._add("cello", note_name_to_midi(m.group(1), 12), os.path.join("Strings", "Cello Section", "susvib", fname))

        viola_dir = os.path.join(self.root, "Strings", "Viola Section", "susvib")
        for fname in sorted(os.listdir(viola_dir)):
            m = re.match(r"^ViolaEns_susvib_([A-G][#b]?\d+)_v1_\d+\.wav$", fname)
            if m:
                self._add("viola", note_name_to_midi(m.group(1), 12), os.path.join("Strings", "Viola Section", "susvib", fname))

        violin_dir = os.path.join(self.root, "Strings", "Violin Section", "susVib")
        for fname in sorted(os.listdir(violin_dir)):
            m = re.match(r"^VlnEns_susVib_([A-G][#b]?\d+)_v1\.wav$", fname)
            if m:
                self._add("violin", note_name_to_midi(m.group(1), 12), os.path.join("Strings", "Violin Section", "susVib", fname))

    def _get_raw(self, inst: str, path: str) -> tuple[int, np.ndarray]:
        if path in self.raw_cache:
            return self.raw_cache[path]
        sr, audio = load_wav_stereo_float(path)
        # Normalize relative sample level per instrument category for consistent velocity staging
        if inst in ("piano_dyn1", "piano_dyn2"):
            audio = trim_onset_silence(audio, sr, 0.008, lead_ms=1.5, fade_in_ms=5.0)
            # Bring dyn1/dyn2 to consistent scale (dyn1 is naturally ~-34 dBFS, dyn2 ~-22 dBFS)
            pk = max(float(np.max(np.abs(audio))), 1e-6)
            target_pk = 0.28 if inst == "piano_dyn1" else 0.45
            audio = audio * (target_pk / pk)
        elif inst == "harp":
            audio = trim_onset_silence(audio, sr, 0.015, lead_ms=2.0, fade_in_ms=4.0)
            pk = max(float(np.max(np.abs(audio))), 1e-6)
            audio = audio * (0.35 / pk)
        elif inst == "glock":
            audio = trim_onset_silence(audio, sr, 0.010, lead_ms=1.0, fade_in_ms=6.0)
            pk = max(float(np.max(np.abs(audio))), 1e-6)
            audio = audio * (0.32 / pk)
        elif inst == "marimba":
            audio = trim_onset_silence(audio, sr, 0.015, lead_ms=1.0, fade_in_ms=3.0)
            pk = max(float(np.max(np.abs(audio))), 1e-6)
            audio = audio * (0.38 / pk)
        else:
            # Strings: keep natural slow bow onset intact, trim only dead noise floor
            audio = trim_onset_silence(audio, sr, 0.002, lead_ms=5.0, fade_in_ms=12.0)
            rms = max(float(np.sqrt(np.mean(audio[:min(len(audio), sr * 3)] ** 2))), 1e-6)
            audio = audio * (0.065 / rms)
        self.raw_cache[path] = (sr, audio)
        return sr, audio

    def get_note(self, inst: str, target_midi: int) -> np.ndarray:
        key = (inst, target_midi)
        if key in self.shifted_cache:
            return self.shifted_cache[key]

        available = self.instruments[inst]
        src_midi = min(available.keys(), key=lambda m: (abs(m - target_midi), m))
        path = available[src_midi][0]
        sr, audio = self._get_raw(inst, path)

        semitones = target_midi - src_midi
        # 2x oversample before Catmull-Rom cubic interpolation for clean anti-aliased resampling
        os_audio = signal.resample_poly(audio, 2, 1, axis=0).astype(np.float32)
        os_sr = sr * 2.0
        os_ratio = (os_sr / float(SR)) * (2.0 ** (semitones / 12.0))
        out_len = min(int(round(6.0 * SR)), int(np.floor((len(os_audio) - 4) / os_ratio)))
        t_q = np.arange(out_len, dtype=np.float64) * os_ratio
        out = cubic_interp_stereo(os_audio, t_q)

        # Gentle 20 ms fade-out at sample tail
        tail = min(out_len, int(0.02 * SR))
        if tail > 0:
            out[-tail:] *= np.linspace(1.0, 0.0, tail, dtype=np.float32)[:, None]

        self.shifted_cache[key] = out
        return out


# ---------------------------------------------------------------------------
# Circular Placement & Spatial Utilities
# ---------------------------------------------------------------------------

def stereo_pan_gains(pan: float) -> tuple[float, float]:
    """Constant-power pan in [-1.0 (L), +1.0 (R)]."""
    angle = (np.clip(pan, -1.0, 1.0) + 1.0) * (np.pi / 4.0)
    return float(np.cos(angle)), float(np.sin(angle))


def apply_stereo_width_and_pan(sig: np.ndarray, pan: float, width: float = 0.85) -> np.ndarray:
    mid = 0.5 * (sig[:, 0] + sig[:, 1])
    side = 0.5 * (sig[:, 0] - sig[:, 1]) * width
    l = mid + side
    r = mid - side
    gl, gr = stereo_pan_gains(pan)
    return np.stack([l * gl * 1.414, r * gr * 1.414], axis=1)


def add_circular(buf: np.ndarray, start_sec: float, sig: np.ndarray) -> None:
    """Wrap-add a stereo event into a single-cycle circular buffer."""
    n = len(buf)
    idx0 = int(round(start_sec * SR)) % n
    m = len(sig)
    if m >= n:
        # Fold multi-cycle long events cleanly if needed
        pos = idx0
        rem = m
        off = 0
        while rem > 0:
            chunk = min(rem, n - pos)
            buf[pos:pos + chunk] += sig[off:off + chunk]
            pos = (pos + chunk) % n
            off += chunk
            rem -= chunk
        return
    end = idx0 + m
    if end <= n:
        buf[idx0:end] += sig
    else:
        first = n - idx0
        buf[idx0:] += sig[:first]
        buf[:m - first] += sig[first:]


def envelope_note(sig: np.ndarray, hold_sec: float, attack_ms: float = 8.0, release_sec: float = 0.65) -> np.ndarray:
    n = len(sig)
    target_len = min(n, int(round((hold_sec + release_sec) * SR)))
    if target_len <= 0:
        return np.zeros((0, 2), dtype=np.float64)
    out = sig[:target_len].copy()

    att_n = min(target_len, max(1, int(round(attack_ms / 1000.0 * SR))))
    if att_n > 1:
        att = 0.5 - 0.5 * np.cos(np.pi * np.arange(att_n) / att_n)
        out[:att_n] *= att[:, None]

    hold_n = min(target_len, int(round(hold_sec * SR)))
    rel_n = target_len - hold_n
    if rel_n > 1:
        # Smooth natural exponential + cosine release to zero
        t_rel = np.linspace(0.0, 1.0, rel_n)
        rel = (0.5 + 0.5 * np.cos(np.pi * t_rel)) ** 1.35
        out[hold_n:] *= rel[:, None]
    return out


def lowpass_audio(sig: np.ndarray, cutoff_hz: float, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, cutoff_hz / (0.5 * SR), btype="low", output="sos")
    return signal.sosfilt(sos, sig, axis=0)


def bandpass_audio(sig: np.ndarray, low_hz: float, high_hz: float, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, [low_hz / (0.5 * SR), high_hz / (0.5 * SR)], btype="band", output="sos")
    return signal.sosfilt(sos, sig, axis=0)


# ---------------------------------------------------------------------------
# Synthesized Ambient Layers (PS4 Warm Pads + PS5 Shimmer & Droplets)
# ---------------------------------------------------------------------------

def render_pad_bed(chords: list[list[int]]) -> tuple[np.ndarray, np.ndarray]:
    """
    Render continuous seamless 30.000 s warm pad bed + deep bass cushion.
    Uses periodic phases (exact integer cycles over 30.000 s or bar-crossfaded phases)
    and processes 3 concatenated cycles before slicing the middle cycle so filter state
    and chord crossfades are 100% seamless.
    """
    n_cyc = LOOP_SAMPLES
    n_bar = int(round(BAR_SEC * SR))
    cf_n = int(round(1.15 * SR))  # 1.15 s gentle overlap crossfade per bar

    t_long = np.arange(n_cyc * 3, dtype=np.float64) / SR
    pad_buf = np.zeros((n_cyc, 2), dtype=np.float64)
    sub_buf = np.zeros((n_cyc, 2), dtype=np.float64)

    # Bar-by-bar smooth crossfaded chord pad
    for bar_idx, chord in enumerate(chords):
        # Pad uses upper voices (exclude lowest bass note, keep warm inner/upper extensions)
        pad_voices = chord[1:]
        seg_len = n_bar + cf_n
        t_seg = np.arange(seg_len, dtype=np.float64) / SR
        voice_sum = np.zeros((seg_len, 2), dtype=np.float64)

        for v_idx, midi in enumerate(pad_voices):
            f0 = midi_to_hz(midi)
            # Dual gently detuned stereo voices with warm odd/even harmonic rolloff
            det_cents = (-5.5, 5.5)
            pan = -0.35 + 0.70 * (v_idx / max(1, len(pad_voices) - 1))
            gl, gr = stereo_pan_gains(pan)
            for d_i, cents in enumerate(det_cents):
                f = f0 * (2.0 ** (cents / 1200.0))
                phase0 = (bar_idx * 0.71 + v_idx * 1.13 + d_i * 2.09) % (2.0 * np.pi)
                # Soft analog-style spectrum: fundamental + gentle 2nd/3rd/4th harmonics
                sig = (
                    1.00 * np.sin(2.0 * np.pi * f * t_seg + phase0)
                    + 0.34 * np.sin(2.0 * np.pi * 2.0 * f * t_seg + phase0 * 1.3)
                    + 0.14 * np.sin(2.0 * np.pi * 3.0 * f * t_seg + phase0 * 0.7)
                    + 0.05 * np.sin(2.0 * np.pi * 4.0 * f * t_seg + phase0 * 1.7)
                )
                # Micro stereo width per detune pair
                if d_i == 0:
                    voice_sum[:, 0] += sig * gl * 0.85
                    voice_sum[:, 1] += sig * gr * 0.45
                else:
                    voice_sum[:, 0] += sig * gl * 0.45
                    voice_sum[:, 1] += sig * gr * 0.85

        voice_sum /= (len(pad_voices) * 1.4)
        # Raised-cosine fade-in & fade-out for smooth PS4-style harmonic drift
        fade_in = 0.5 - 0.5 * np.cos(np.pi * np.arange(cf_n) / cf_n)
        fade_out = 0.5 + 0.5 * np.cos(np.pi * np.arange(cf_n) / cf_n)
        env = np.ones(seg_len, dtype=np.float64)
        env[:cf_n] *= fade_in
        env[-cf_n:] *= fade_out
        voice_sum *= env[:, None]

        start_sec = bar_idx * BAR_SEC - (cf_n * 0.5 / SR)
        add_circular(pad_buf, start_sec, voice_sum)

        # Warm bass cushion (root fundamental + soft 2nd harmonic)
        bass_midi = chord[0]
        fb = midi_to_hz(bass_midi)
        b_phase = (bar_idx * 1.07) % (2.0 * np.pi)
        b_wave = 0.85 * np.sin(2.0 * np.pi * fb * t_seg + b_phase) + 0.25 * np.sin(2.0 * np.pi * 2.0 * fb * t_seg + b_phase)
        # Subtle 64 BPM gentle swell envelope on beats 1 and 3 inside the bass pad
        b_pulse = 0.84 + 0.16 * np.cos(2.0 * np.pi * (BPM / 60.0) * 0.5 * (t_seg - (cf_n * 0.5 / SR)))
        b_stereo = np.stack([b_wave * env * b_pulse, b_wave * env * b_pulse], axis=1)
        add_circular(sub_buf, start_sec, b_stereo)

    # Filter across 3 concatenated cycles with slow breathing LFO (period = 15.0 s & 30.0 s)
    pad_3x = np.tile(pad_buf, (3, 1))
    # High-pass pad at 115 Hz so it leaves room for bass & cello, then modulate warmth 620-1250 Hz
    sos_pad_hp = signal.butter(2, 115.0 / (0.5 * SR), btype="high", output="sos")
    pad_3x = signal.sosfilt(sos_pad_hp, pad_3x, axis=0)
    sos_dark = signal.butter(2, 620.0 / (0.5 * SR), btype="low", output="sos")
    sos_bright = signal.butter(2, 1250.0 / (0.5 * SR), btype="low", output="sos")
    p_dark = signal.sosfilt(sos_dark, pad_3x, axis=0)
    p_bright = signal.sosfilt(sos_bright, pad_3x, axis=0)
    lfo = 0.5 - 0.5 * np.cos(2.0 * np.pi * t_long / 15.0)  # 2 exact cycles per 30 s loop
    pad_filtered = p_dark * (1.0 - 0.55 * lfo[:, None]) + p_bright * (0.55 * lfo[:, None])

    sub_3x = np.tile(sub_buf, (3, 1))
    sos_sub = signal.butter(2, 195.0 / (0.5 * SR), btype="low", output="sos")
    sub_filtered = signal.sosfilt(sos_sub, sub_3x, axis=0)

    return pad_filtered[n_cyc:2 * n_cyc], sub_filtered[n_cyc:2 * n_cyc]


def render_ps5_shimmer_bed(chords: list[list[int]]) -> np.ndarray:
    """
    Ethereal high-register PS5-inspired harmonic air/shimmer bed.
    Sits very quietly (-22 dB) behind the acoustic instruments for spatial depth.
    """
    n_cyc = LOOP_SAMPLES
    n_bar = int(round(BAR_SEC * SR))
    cf_n = int(round(1.3 * SR))
    buf = np.zeros((n_cyc, 2), dtype=np.float64)

    for bar_idx, chord in enumerate(chords):
        seg_len = n_bar + cf_n
        t_seg = np.arange(seg_len, dtype=np.float64) / SR
        # Use chord 5th, 9th, and upper root +12/+24 semitones in ~1.2 - 3.6 kHz range
        shimmer_midis = [chord[2] + 12, chord[3] + 12, chord[-1] + 12]
        layer = np.zeros((seg_len, 2), dtype=np.float64)
        for idx, midi in enumerate(shimmer_midis):
            f = midi_to_hz(midi)
            # Slow stereo shimmer beat via slight L/R detune
            ph = (bar_idx * 0.91 + idx * 1.47) % (2.0 * np.pi)
            wl = np.sin(2.0 * np.pi * (f - 0.35) * t_seg + ph)
            wr = np.sin(2.0 * np.pi * (f + 0.35) * t_seg + ph + 1.1)
            layer[:, 0] += wl
            layer[:, 1] += wr
        layer /= len(shimmer_midis)
        fade_in = 0.5 - 0.5 * np.cos(np.pi * np.arange(cf_n) / cf_n)
        fade_out = 0.5 + 0.5 * np.cos(np.pi * np.arange(cf_n) / cf_n)
        env = np.ones(seg_len, dtype=np.float64)
        env[:cf_n] *= fade_in
        env[-cf_n:] *= fade_out
        layer *= env[:, None]
        add_circular(buf, bar_idx * BAR_SEC - (cf_n * 0.5 / SR), layer)

    # Gentle shimmer drift LFO (30 s period) across 3 cycles
    buf_3x = np.tile(buf, (3, 1))
    t_long = np.arange(n_cyc * 3, dtype=np.float64) / SR
    drift = 0.65 + 0.35 * np.sin(2.0 * np.pi * t_long / 30.0 + 0.4)
    bp = bandpass_audio(buf_3x, 900.0, 4600.0, order=2) * drift[:, None]
    return bp[n_cyc:2 * n_cyc]


def make_bubbly_droplet(midi: int, rng: np.random.Generator, pan: float) -> np.ndarray:
    """
    Soft, water-clear PS5-inspired micro-droplet harmonic pluck.
    Very short (~110 ms) with subtle upward micro-glide so it stays musical and non-alert-like.
    """
    dur = 0.13
    n = int(round(dur * SR))
    t = np.arange(n, dtype=np.float64) / SR
    f0 = midi_to_hz(midi)
    # Slight liquid pitch sweep (+2.8% over first 28 ms)
    freq = f0 * (1.0 - 0.028 * np.exp(-t / 0.012))
    phase = 2.0 * np.pi * np.cumsum(freq) / SR
    wave = 0.85 * np.sin(phase) + 0.22 * np.sin(2.0 * phase + 0.5)
    att = 1.0 - np.exp(-t / 0.004)
    dec = np.exp(-t / 0.032)
    tail = np.linspace(1.0, 0.0, min(n, int(0.02 * SR)))
    env = att * dec
    env[-len(tail):] *= tail
    mono = wave * env
    gl, gr = stereo_pan_gains(pan)
    return np.stack([mono * gl, mono * gr], axis=1)


# ---------------------------------------------------------------------------
# Stereo Concert-Hall Reverb (O(N log N) fftconvolve over 3 cycles)
# ---------------------------------------------------------------------------

def build_hall_ir(rng: np.random.Generator, rt60_sec: float = 2.6) -> np.ndarray:
    ir_len = int(round(3.1 * SR))
    t = np.arange(ir_len, dtype=np.float64) / SR
    ir = np.zeros((ir_len, 2), dtype=np.float64)

    # Early reflections (concert hall lateral & ceiling reflections)
    early_taps = [
        (0.011, 0.42, -0.55), (0.017, 0.36, 0.50),
        (0.024, 0.30, -0.30), (0.031, 0.28, 0.35),
        (0.041, 0.22, -0.65), (0.052, 0.20, 0.60),
    ]
    for d_s, amp, pan in early_taps:
        idx = int(round(d_s * SR))
        gl, gr = stereo_pan_gains(pan)
        ir[idx, 0] += amp * gl
        ir[idx, 1] += amp * gr

    # Diffuse late tail (different noise seeds L/R, mild cross-decorrelation)
    raw_l = rng.standard_normal(ir_len)
    raw_r = rng.standard_normal(ir_len)
    # Pre-delay 22 ms before diffuse tail builds smoothly
    pre_n = int(0.022 * SR)
    tail_env = np.zeros(ir_len, dtype=np.float64)
    tail_t = np.maximum(0.0, t[pre_n:] - 0.022)
    rise = 1.0 - np.exp(-tail_t / 0.035)
    decay = np.exp(-6.9078 * tail_t / rt60_sec)
    tail_env[pre_n:] = rise * decay

    # Low-frequency body + softer high-frequency decay (air absorption)
    sos_low = signal.butter(2, 1600.0 / (0.5 * SR), btype="low", output="sos")
    sos_hi = signal.butter(2, [1600.0 / (0.5 * SR), 5800.0 / (0.5 * SR)], btype="band", output="sos")
    hi_decay = np.zeros(ir_len, dtype=np.float64)
    hi_decay[pre_n:] = rise * np.exp(-6.9078 * tail_t / (rt60_sec * 0.52))

    for ch, raw in enumerate((raw_l, raw_r)):
        low_part = signal.sosfilt(sos_low, raw) * tail_env * 0.095
        hi_part = signal.sosfilt(sos_hi, raw) * hi_decay * 0.045
        ir[:, ch] += low_part + hi_part

    # High-pass IR above 95 Hz so reverb doesn't muddy car cabin bass
    sos_hp = signal.butter(2, 95.0 / (0.5 * SR), btype="high", output="sos")
    ir = signal.sosfilt(sos_hp, ir, axis=0)
    # Normalize IR energy cleanly
    ir /= max(float(np.sqrt(np.sum(ir[:, 0] ** 2) + np.sum(ir[:, 1] ** 2)) * 0.5), 1e-6)
    return ir * 0.85


def apply_circular_reverb(dry_1x: np.ndarray, ir: np.ndarray) -> np.ndarray:
    """Convolve 3 concatenated cycles and return the middle cycle for zero-seam tails."""
    n = len(dry_1x)
    dry_3x = np.tile(dry_1x, (3, 1))
    wet_l = signal.fftconvolve(dry_3x[:, 0], ir[:, 0], mode="full")[:len(dry_3x)]
    wet_r = signal.fftconvolve(dry_3x[:, 1], ir[:, 1], mode="full")[:len(dry_3x)]
    wet_3x = np.stack([wet_l, wet_r], axis=1)
    return wet_3x[n:2 * n]


# ---------------------------------------------------------------------------
# Car Mastering Chain (EQ, Gentle Bus Compression, True-Peak Ceiling)
# ---------------------------------------------------------------------------

def biquad_peaking(fc: float, gain_db: float, q: float, fs: float) -> np.ndarray:
    a = 10.0 ** (gain_db / 40.0)
    w0 = 2.0 * np.pi * fc / fs
    alpha = np.sin(w0) / (2.0 * q)
    b0 = 1.0 + alpha * a
    b1 = -2.0 * np.cos(w0)
    b2 = 1.0 - alpha * a
    a0 = 1.0 + alpha / a
    a1 = -2.0 * np.cos(w0)
    a2 = 1.0 - alpha / a
    return np.array([[b0 / a0, b1 / a0, b2 / a0, 1.0, a1 / a0, a2 / a0]], dtype=np.float64)


def biquad_highshelf(fc: float, gain_db: float, slope: float, fs: float) -> np.ndarray:
    a = 10.0 ** (gain_db / 40.0)
    w0 = 2.0 * np.pi * fc / fs
    cs = np.cos(w0)
    sn = np.sin(w0)
    alpha = sn / 2.0 * np.sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
    sq = 2.0 * np.sqrt(a) * alpha
    b0 = a * ((a + 1.0) + (a - 1.0) * cs + sq)
    b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cs)
    b2 = a * ((a + 1.0) + (a - 1.0) * cs - sq)
    a0 = (a + 1.0) - (a - 1.0) * cs + sq
    a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cs)
    a2 = (a + 1.0) - (a - 1.0) * cs - sq
    return np.array([[b0 / a0, b1 / a0, b2 / a0, 1.0, a1 / a0, a2 / a0]], dtype=np.float64)


def car_master_3x(mix_1x: np.ndarray) -> np.ndarray:
    """Apply car EQ, subtle stereo mono-bass alignment, and soft peak control across 3 cycles."""
    n = len(mix_1x)
    x = np.tile(mix_1x, (3, 1))

    # 1. Remove sub-55 Hz cabin rumble & tighten bass below 140 Hz toward center
    sos_hp = signal.butter(2, 55.0 / (0.5 * SR), btype="high", output="sos")
    x = signal.sosfilt(sos_hp, x, axis=0)

    sos_low = signal.butter(2, 145.0 / (0.5 * SR), btype="low", output="sos")
    low_band = signal.sosfilt(sos_low, x, axis=0)
    high_band = x - low_band
    low_mono = 0.5 * (low_band[:, 0] + low_band[:, 1])
    low_aligned = np.stack([
        0.85 * low_mono + 0.15 * low_band[:, 0],
        0.85 * low_mono + 0.15 * low_band[:, 1],
    ], axis=1)
    x = low_aligned + high_band

    # 2. Car cabin EQ: light low-mid warmth (+0.8 dB @ 220 Hz), gentle speech pocket (-2.0 dB @ 1.8 kHz),
    #    presence air (+1.0 dB @ 4.5 kHz for piano/chime sheen) and smooth roll-off (-2.0 dB shelf > 10 kHz)
    eq_sos = np.vstack([
        biquad_peaking(220.0, +0.8, 0.85, SR),
        biquad_peaking(1800.0, -2.0, 0.90, SR),
        biquad_peaking(4500.0, +1.0, 0.80, SR),
        biquad_highshelf(10000.0, -2.0, 0.70, SR),
    ])
    x = signal.sosfilt(eq_sos, x, axis=0)

    # 3. Very gentle leveling (0.18 ratio) to preserve natural Section A -> A' LRA (~2.3-3.2 LU)
    inst_pow = np.mean(x ** 2, axis=1)
    win = int(round(1.0 * SR))
    kernel = np.hanning(win)
    kernel /= np.sum(kernel)
    smooth_rms = np.sqrt(np.maximum(signal.fftconvolve(inst_pow, kernel, mode="same"), 1e-12))
    target_rms = 10.0 ** (-20.0 / 20.0)
    gain_curve = (target_rms / smooth_rms) ** 0.18
    gain_curve = np.clip(gain_curve, 0.78, 1.28)
    x = x * gain_curve[:, None]

    mid = x[n:2 * n].copy()

    # 4. Calibrate level toward -18.5 LUFS (empirically aligned via ffmpeg ebur128)
    cur_rms = float(np.sqrt(np.mean(mid ** 2)))
    est_target_rms = 10.0 ** (-20.9 / 20.0)
    mid *= est_target_rms / max(cur_rms, 1e-9)

    # Soft ceiling protection strictly below TRUE_PEAK_CEIL_DB (-2.8 dBFS)
    ceil_lin = 10.0 ** (TRUE_PEAK_CEIL_DB / 20.0)
    peak = float(np.max(np.abs(mid)))
    if peak > ceil_lin:
        # Smooth tanh soft limiter above 85% of ceiling
        knee = ceil_lin * 0.85
        over = np.maximum(0.0, np.abs(mid) - knee)
        scale = np.where(
            np.abs(mid) > knee,
            (knee + (ceil_lin - knee) * np.tanh(over / (ceil_lin - knee))) / np.maximum(np.abs(mid), 1e-12),
            1.0,
        )
        mid *= scale

    return mid


# ---------------------------------------------------------------------------
# Composition Score & Arrangement (E minor, 64.0 BPM, 8 Bars = 30.000 s)
# ---------------------------------------------------------------------------

def compose_and_render() -> None:
    rng = np.random.default_rng(SEED)
    bank = SampleBank(ASSETS_ROOT)

    # Original 8-bar progression in E minor (Aeolian / Dorian colour)
    # Each entry: [bass, inner1, inner2, inner3, upper1, upper2]
    chords = [
        [40, 52, 59, 64, 66, 74],  # Bar 1: Em9      (E2, E3, B3, E4, F#4, D5)
        [38, 50, 57, 64, 66, 71],  # Bar 2: D6/9     (D2, D3, A3, E4, F#4, B4)
        [36, 48, 55, 59, 62, 66],  # Bar 3: Cmaj7#11 (C2, C3, G3, B3, D4, F#4)
        [45, 52, 57, 64, 67, 72],  # Bar 4: Am11     (A2, E3, A3, E4, G4, C5)
        [40, 52, 59, 66, 67, 74],  # Bar 5: Em9      (E2, E3, B3, F#4, G4, D5)
        [47, 55, 59, 62, 67, 69],  # Bar 6: Gadd9/B  (B2, G3, B3, D4, G4, A4)
        [45, 52, 59, 64, 67, 76],  # Bar 7: Am9      (A2, E3, B3, E4, G4, E5)
        [47, 54, 59, 64, 66, 74],  # Bar 8: Bm7sus4  (B2, F#3, B3, E4, F#4, D5)
    ]

    # Separate dry & reverb-send buses (each 1x cycle = 30.000 s = 1,440,000 samples)
    bus_pads = np.zeros((LOOP_SAMPLES, 2), dtype=np.float64)
    bus_dry = np.zeros((LOOP_SAMPLES, 2), dtype=np.float64)
    bus_send = np.zeros((LOOP_SAMPLES, 2), dtype=np.float64)

    # 1. Pad Bed + Sub Cushion + PS5 Shimmer (direct + gentle send)
    pad_bed, sub_bed = render_pad_bed(chords)
    shimmer_bed = render_ps5_shimmer_bed(chords)
    bus_pads += pad_bed * 0.14 + sub_bed * 0.065 + shimmer_bed * 0.095
    bus_send += pad_bed * 0.045 + shimmer_bed * 0.070

    # 2. Upright Piano (felt-softened Concert OP intimacy + 64 BPM pulse anchor)
    #    (bar_idx, beat_in_bar, midi, hold_beats, vel, pan, use_dyn2)
    piano_events = [
        # Bar 1: Em9 — no hard transient on beat 1 (t=0); gentle entrance on beat 1.5
        (0, 0.5, 59, 2.5, 0.52, -0.18, False),  # B3
        (0, 1.0, 66, 2.0, 0.65,  0.14, False),  # F#4 (top voice)
        (0, 1.5, 64, 1.5, 0.48, -0.08, False),  # E4
        (0, 2.0, 67, 1.8, 0.70,  0.18, False),  # G4
        (0, 2.5, 52, 1.5, 0.45, -0.24, False),  # E3 (soft 64 BPM beat-3 anchor)
        (0, 3.0, 71, 1.5, 0.66,  0.22, False),  # B4
        (0, 3.5, 66, 1.2, 0.44,  0.05, False),  # F#4

        # Bar 2: D6/9
        (1, 0.0, 50, 3.5, 0.56, -0.25, False),  # D3
        (1, 0.0, 69, 2.6, 0.72,  0.18, True),   # A4 (lyrical melody)
        (1, 0.5, 57, 2.0, 0.46, -0.12, False),  # A3
        (1, 1.5, 64, 1.8, 0.44,  0.04, False),  # E4
        (1, 2.0, 50, 1.8, 0.46, -0.22, False),  # D3
        (1, 2.5, 66, 1.6, 0.60,  0.16, False),  # F#4
        (1, 3.0, 71, 1.4, 0.50,  0.22, False),  # B4

        # Bar 3: Cmaj7#11
        (2, 0.0, 48, 3.4, 0.58, -0.26, False),  # C3
        (2, 0.0, 67, 1.6, 0.68,  0.16, False),  # G4
        (2, 0.5, 55, 2.2, 0.46, -0.14, False),  # G3
        (2, 1.0, 71, 1.4, 0.72,  0.20, True),   # B4
        (2, 1.5, 59, 1.8, 0.46, -0.05, False),  # B3
        (2, 2.0, 74, 1.6, 0.76,  0.24, True),   # D5
        (2, 2.5, 62, 1.4, 0.44, -0.10, False),  # D4
        (2, 3.0, 78, 1.5, 0.74,  0.26, True),   # F#5 (#11 poignant peak)

        # Bar 4: Am11
        (3, 0.0, 45, 3.6, 0.58, -0.26, False),  # A2
        (3, 0.0, 76, 2.8, 0.78,  0.22, True),   # E5
        (3, 0.5, 52, 2.2, 0.45, -0.16, False),  # E3
        (3, 1.5, 64, 1.8, 0.46,  0.02, False),  # E4
        (3, 2.0, 57, 1.8, 0.48, -0.14, False),  # A3
        (3, 2.5, 67, 1.5, 0.46,  0.10, False),  # G4
        (3, 3.0, 74, 1.5, 0.66,  0.20, False),  # D5

        # Bar 5: Em9
        (4, 0.0, 40, 3.6, 0.54, -0.28, False),  # E2
        (4, 0.0, 71, 1.4, 0.72,  0.18, True),   # B4
        (4, 0.5, 59, 2.4, 0.48, -0.12, False),  # B3
        (4, 1.0, 78, 1.4, 0.76,  0.24, True),   # F#5
        (4, 1.5, 64, 1.8, 0.48, -0.02, False),  # E4
        (4, 2.0, 76, 1.5, 0.74,  0.22, True),   # E5
        (4, 2.5, 52, 1.5, 0.46, -0.22, False),  # E3
        (4, 3.0, 74, 1.6, 0.68,  0.20, False),  # D5

        # Bar 6: Gadd9/B
        (5, 0.0, 47, 3.4, 0.56, -0.24, False),  # B2
        (5, 0.0, 69, 1.4, 0.68,  0.16, False),  # A4
        (5, 0.5, 55, 2.2, 0.46, -0.14, False),  # G3
        (5, 1.0, 71, 1.4, 0.72,  0.20, True),   # B4
        (5, 1.5, 62, 1.8, 0.46, -0.04, False),  # D4
        (5, 2.0, 74, 1.4, 0.74,  0.22, True),   # D5
        (5, 2.5, 59, 1.5, 0.44, -0.12, False),  # B3
        (5, 3.0, 79, 1.6, 0.76,  0.25, True),   # G5

        # Bar 7: Am9
        (6, 0.0, 45, 3.6, 0.58, -0.26, False),  # A2
        (6, 0.0, 78, 1.4, 0.76,  0.24, True),   # F#5
        (6, 0.5, 52, 2.2, 0.46, -0.15, False),  # E3
        (6, 1.0, 76, 1.5, 0.72,  0.22, True),   # E5
        (6, 1.5, 64, 1.8, 0.46,  0.00, False),  # E4
        (6, 2.0, 74, 1.5, 0.68,  0.20, False),  # D5
        (6, 2.5, 59, 1.5, 0.44, -0.12, False),  # B3
        (6, 3.0, 71, 1.5, 0.64,  0.18, False),  # B4

        # Bar 8: Bm7sus4 — soft poised turnaround, fading peacefully back to Bar 1
        (7, 0.0, 47, 3.5, 0.52, -0.24, False),  # B2
        (7, 0.0, 76, 2.4, 0.68,  0.20, False),  # E5
        (7, 0.5, 54, 2.2, 0.44, -0.12, False),  # F#3
        (7, 1.5, 64, 1.8, 0.44,  0.02, False),  # E4
        (7, 2.0, 74, 2.1, 0.62,  0.18, False),  # D5 (resolves smoothly into Em9)
        (7, 3.0, 66, 1.2, 0.42,  0.10, False),  # F#4
    ]

    for bar_i, beat_i, midi, hold_b, vel, pan, use_dyn2 in piano_events:
        inst = "piano_dyn2" if use_dyn2 else "piano_dyn1"
        raw_note = bank.get_note(inst, midi)
        # Felt-piano warmth filter + softened hammer attack
        lp_hz = 4800.0 if use_dyn2 else 3600.0
        warm = lowpass_audio(raw_note, lp_hz, order=2)
        env = envelope_note(warm, hold_sec=hold_b * BEAT_SEC, attack_ms=11.0, release_sec=0.95)
        jitter = rng.uniform(-0.004, 0.004)
        placed = apply_stereo_width_and_pan(env * vel, pan=pan, width=0.72)
        t_start = bar_i * BAR_SEC + beat_i * BEAT_SEC + jitter
        add_circular(bus_dry, t_start, placed * 0.62)
        add_circular(bus_send, t_start, placed * 0.45)

    # 3. Orchestral Chimes / Glockenspiel (MementoMori Concert OP opening signature)
    #    Layer each glock bell with a softer octave-below piano/bell undertone for Concert Chime warmth
    chime_events = [
        (0, 1.0, 83, 3.2, 0.68, -0.22),  # Bar 1 beat 2: B5 (enters smoothly during 1.5s fade-in)
        (0, 3.0, 90, 2.8, 0.44,  0.28),  # Bar 1 beat 4: F#6 airy answer
        (4, 0.0, 88, 3.4, 0.66,  0.22),  # Bar 5 beat 1: E6 section A' bloom
        (4, 1.0, 83, 3.0, 0.48, -0.24),  # Bar 5 beat 2: B5 resonant fifth
        (6, 2.0, 88, 2.8, 0.42,  0.26),  # Bar 7 beat 3: E6 soft return
    ]
    for bar_i, beat_i, midi, hold_b, vel, pan in chime_events:
        glock_sig = lowpass_audio(bank.get_note("glock", midi), 7400.0, order=2)
        glock_env = envelope_note(glock_sig, hold_sec=hold_b * BEAT_SEC, attack_ms=7.0, release_sec=1.5)
        placed = apply_stereo_width_and_pan(glock_env * vel, pan=pan, width=0.80)
        t_start = bar_i * BAR_SEC + beat_i * BEAT_SEC
        add_circular(bus_dry, t_start, placed * 0.28)
        add_circular(bus_send, t_start, placed * 0.58)

        # Warm tubular-chime undertone (-12 semitones soft piano body)
        body_sig = lowpass_audio(bank.get_note("piano_dyn1", midi - 12), 2600.0, order=2)
        body_env = envelope_note(body_sig, hold_sec=hold_b * BEAT_SEC, attack_ms=14.0, release_sec=1.2)
        body_placed = apply_stereo_width_and_pan(body_env * (vel * 0.48), pan=pan * 0.7, width=0.55)
        add_circular(bus_dry, t_start, body_placed * 0.20)
        add_circular(bus_send, t_start, body_placed * 0.30)

    # 4. Harp Ripples (PS4/MementoMori graceful bridge arpeggios)
    harp_phrases = [
        (1, 2.5, [64, 69, 71, 78], 0.11, 0.52,  0.30),   # Bar 2 -> Bar 3
        (3, 2.5, [64, 67, 72, 74], 0.11, 0.54, -0.28),   # Bar 4 -> Bar 5
        (5, 2.5, [62, 67, 69, 74], 0.11, 0.56,  0.32),   # Bar 6 -> Bar 7
        (7, 1.5, [64, 66, 71, 74], 0.13, 0.46, -0.25),   # Bar 8 gentle dissolve
    ]
    for bar_i, beat_i, notes, step_s, vel, pan in harp_phrases:
        for k, midi in enumerate(notes):
            h_sig = lowpass_audio(bank.get_note("harp", midi), 5200.0, order=2)
            h_env = envelope_note(h_sig, hold_sec=2.0, attack_ms=6.0, release_sec=1.2)
            p_k = pan * (0.85 if k % 2 == 0 else 1.0)
            placed = apply_stereo_width_and_pan(h_env * vel, pan=p_k, width=0.65)
            t_start = bar_i * BAR_SEC + beat_i * BEAT_SEC + k * step_s
            add_circular(bus_dry, t_start, placed * 0.28)
            add_circular(bus_send, t_start, placed * 0.38)

    # 5. Chamber Strings (Cello, Viola, Violin susvib v1 — pre-rolled for bow swell!)
    cello_lines = [
        (0, 0.0, 52, 3.8, 0.30, -0.25),  # Bar 1: E3 (quiet opening)
        (1, 0.0, 50, 3.8, 0.36, -0.25),  # Bar 2: D3
        (2, 0.0, 48, 3.8, 0.48, -0.25),  # Bar 3: C3
        (3, 0.0, 45, 3.8, 0.50, -0.25),  # Bar 4: A2
        (4, 0.0, 52, 3.8, 0.62, -0.25),  # Bar 5: E3 (section A' bloom)
        (5, 0.0, 47, 3.8, 0.65, -0.25),  # Bar 6: B2
        (6, 0.0, 45, 3.8, 0.60, -0.25),  # Bar 7: A2
        (7, 0.0, 47, 3.8, 0.42, -0.25),  # Bar 8: B2
    ]
    for bar_i, beat_i, midi, hold_b, vel, pan in cello_lines:
        c_sig = lowpass_audio(bank.get_note("cello", midi), 2400.0, order=2)
        c_env = envelope_note(c_sig, hold_sec=hold_b * BEAT_SEC, attack_ms=360.0, release_sec=0.85)
        placed = apply_stereo_width_and_pan(c_env * vel, pan=pan, width=0.70)
        t_start = bar_i * BAR_SEC + beat_i * BEAT_SEC - 0.14
        add_circular(bus_dry, t_start, placed * 0.28)
        add_circular(bus_send, t_start, placed * 0.28)

    viola_lines = [
        (1, 0.0, 57, 3.8, 0.34,  0.18),  # Bar 2: A3
        (2, 0.0, 59, 3.8, 0.48,  0.18),  # Bar 3: B3
        (3, 0.0, 60, 3.8, 0.52,  0.18),  # Bar 4: C4
        (4, 0.0, 59, 3.8, 0.68,  0.18),  # Bar 5: B3
        (5, 0.0, 62, 3.8, 0.72,  0.18),  # Bar 6: D4
        (6, 0.0, 64, 3.8, 0.66,  0.18),  # Bar 7: E4
        (7, 0.0, 62, 3.7, 0.42,  0.18),  # Bar 8: D4
    ]
    for bar_i, beat_i, midi, hold_b, vel, pan in viola_lines:
        va_sig = lowpass_audio(bank.get_note("viola", midi), 3600.0, order=2)
        va_env = envelope_note(va_sig, hold_sec=hold_b * BEAT_SEC, attack_ms=420.0, release_sec=0.90)
        placed = apply_stereo_width_and_pan(va_env * vel, pan=pan, width=0.75)
        t_start = bar_i * BAR_SEC + beat_i * BEAT_SEC - 0.18
        add_circular(bus_dry, t_start, placed * 0.36)
        add_circular(bus_send, t_start, placed * 0.38)

    violin_lines = [
        (2, 0.0, 66, 3.8, 0.42, -0.15),  # Bar 3: F#4
        (3, 0.0, 67, 3.8, 0.48, -0.15),  # Bar 4: G4
        (4, 0.0, 74, 3.8, 0.72, -0.15),  # Bar 5: D5
        (5, 0.0, 74, 3.8, 0.78, -0.15),  # Bar 6: D5
        (6, 0.0, 76, 3.8, 0.70, -0.15),  # Bar 7: E5
        (7, 0.0, 74, 3.6, 0.40, -0.15),  # Bar 8: D5 fading back
    ]
    for bar_i, beat_i, midi, hold_b, vel, pan in violin_lines:
        vn_sig = lowpass_audio(bank.get_note("violin", midi), 5200.0, order=2)
        vn_env = envelope_note(vn_sig, hold_sec=hold_b * BEAT_SEC, attack_ms=520.0, release_sec=1.00)
        placed = apply_stereo_width_and_pan(vn_env * vel, pan=pan, width=0.80)
        t_start = bar_i * BAR_SEC + beat_i * BEAT_SEC - 0.30
        add_circular(bus_dry, t_start, placed * 0.36)
        add_circular(bus_send, t_start, placed * 0.45)

    # 6. PS5 Marimba & Bubbly Droplet Micro-Accents (off-beats, quiet tactile polish)
    marimba_events = [
        (1, 2.5, 66, 0.55, 0.42, -0.35),  # F#4
        (1, 3.5, 69, 0.55, 0.36,  0.35),  # A4
        (3, 2.5, 67, 0.55, 0.44,  0.34),  # G4
        (3, 3.5, 74, 0.55, 0.38, -0.34),  # D5
        (5, 2.5, 71, 0.55, 0.44, -0.36),  # B4
        (5, 3.5, 74, 0.55, 0.38,  0.36),  # D5
        (7, 2.5, 64, 0.55, 0.38,  0.32),  # E4
        (7, 3.5, 69, 0.55, 0.32, -0.32),  # A4
    ]
    for bar_i, beat_i, midi, hold_s, vel, pan in marimba_events:
        m_sig = bandpass_audio(bank.get_note("marimba", midi), 280.0, 2800.0, order=2)
        m_env = envelope_note(m_sig, hold_sec=hold_s, attack_ms=5.0, release_sec=0.35)
        placed = apply_stereo_width_and_pan(m_env * vel, pan=pan, width=0.55)
        t_start = bar_i * BAR_SEC + beat_i * BEAT_SEC
        add_circular(bus_dry, t_start, placed * 0.24)
        add_circular(bus_send, t_start, placed * 0.32)

    droplet_events = [
        (1, 3.25, 86,  0.45),  # D6 subtle water-drop harmonic
        (3, 3.25, 88, -0.45),  # E6
        (5, 3.25, 91,  0.48),  # G6
        (7, 3.25, 86, -0.42),  # D6
    ]
    for bar_i, beat_i, midi, pan in droplet_events:
        drop = make_bubbly_droplet(midi, rng, pan)
        t_start = bar_i * BAR_SEC + beat_i * BEAT_SEC
        add_circular(bus_dry, t_start, drop * 0.042)
        add_circular(bus_send, t_start, drop * 0.095)

    # 7. Circular Stereo Concert-Hall Reverb + Car Mastering
    hall_ir = build_hall_ir(rng, rt60_sec=2.65)
    wet_hall = apply_circular_reverb(bus_send, hall_ir)

    raw_mix = bus_pads + bus_dry + wet_hall * 0.88
    master = car_master_3x(raw_mix)

    # Write 48 kHz 24-bit (or 16-bit/float32 -> 24-bit via int32/ffmpeg or 16-bit PCM WAV)
    # Using standard 16-bit PCM WAV via scipy (universal playback compatibility) with TPDF dither
    dither = (rng.uniform(-1.0, 1.0, master.shape) + rng.uniform(-1.0, 1.0, master.shape)) * (0.5 / 32768.0)
    pcm16 = np.clip(np.round((master + dither) * 32767.0), -32768, 32767).astype(np.int16)
    wavfile.write(OUT_WAV, SR, pcm16)

    preview16 = np.concatenate([pcm16, pcm16], axis=0)
    wavfile.write(OUT_PREVIEW, SR, preview16)

    # Encode Ogg Vorbis target ~150-260 KB (30 s @ ~52-60 kbps -> ~195-225 KB)
    subprocess.run(
        [
            FFMPEG_BIN, "-y", "-v", "error",
            "-i", OUT_WAV,
            "-c:a", "libvorbis", "-q:a", "0.2",
            OUT_OGG,
        ],
        check=True,
    )

    # Print diagnostic summary
    diff_seam = np.max(np.abs(master[-1] - master[0]))
    ogg_bytes = os.path.getsize(OUT_OGG)
    wav_bytes = os.path.getsize(OUT_WAV)
    prev_bytes = os.path.getsize(OUT_PREVIEW)
    print(f"Rendered {OUT_WAV} ({wav_bytes} B), {OUT_OGG} ({ogg_bytes} B), {OUT_PREVIEW} ({prev_bytes} B)")
    print(f"Master peak: {20*np.log10(max(np.max(np.abs(master)), 1e-12)):.2f} dBFS, seam diff={diff_seam:.8f}")


if __name__ == "__main__":
    compose_and_render()

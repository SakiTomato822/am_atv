# Welcome BGM Composition Notes (Gemini Version)

Original welcome-page BGM rendered offline by [render.py](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/bgm-gemini/render.py) using CC0 1.0 samples from VSCO 2 Community Edition (`D:\Data for Apps and etc\Codex\audio_assets\vsco2ce\`), plus custom synthesized ambient pad, shimmer, and droplet layers.

---

## 1. Key, Chord Progression, Form, and Layers

- **Tempo & Time Signature**: `64.0 BPM` (`0.9375 s/beat`, `3.75 s/bar`), `4/4` time, matching the hard-coded `64 BPM` cover beat pulse in `FolioWelcomeStage.kt`.
- **Length & Form**: **8 bars = `30.000 s`** (`1,440,000` stereo samples at `48,000 Hz`), shaped as a two-part **A – A'** arc (`4 + 4` bars).
- **Key**: **E minor (Aeolian / Dorian colour)** — distinct from *Anemone* (`Dm/F`) and the placeholder BGM (`F–Am–Dm–G`).
- **Chord Progression (1 chord per bar)**:
  - **Section A (Bars 1–4 — Opening Stillness & Delicate Entry)**
    - Bar 1: `Em9` (`E2 – E3 – B3 – E4 – F#4 – D5`)
    - Bar 2: `D6/9` (`D2 – D3 – A3 – E4 – F#4 – B4`)
    - Bar 3: `Cmaj7#11` (`C2 – C3 – G3 – B3 – D4 – F#4`)
    - Bar 4: `Am11` (`A2 – E3 – A3 – E4 – G4 – C5`)
  - **Section A' (Bars 5–8 — Chamber Bloom & Suspended Turnaround)**
    - Bar 5: `Em9` (`E2 – E3 – B3 – F#4 – G4 – D5`)
    - Bar 6: `Gadd9/B` (`B2 – G3 – B3 – D4 – G4 – A4`)
    - Bar 7: `Am9` (`A2 – E3 – B3 – E4 – G4 – E5`)
    - Bar 8: `Bm7sus4` (`B2 – F#3 – B3 – E4 – F#4 – D5`) → glides unresolved back into `Em9`.
- **Instrumentation & Layers**:
  1. **Warm Pad Bed & Bass Cushion (Synthesized)**: Dual-detuned (`±5.5` cents) soft-harmonic stereo pad high-passed at `115 Hz` with a `15.0 s` periodic breathing low-pass sweep (`620–1250 Hz`), supported by a subtle warm low-octave cushion pulsing gently at half-note (`64 BPM` beats 1 & 3).
  2. **Felt-Softened Upright Piano (`Keys/Upright Piano`, `dyn1`/`dyn2`)**: Softened attack (`11 ms`) and gentle low-pass warmth (`3.6–4.8 kHz`); plays broken inner arpeggios and a sparse, singing top melody while omitting any sharp hit on Bar 1 beat 1 (`t = 0.0 s`).
  3. **Concert Opening Chimes (`Percussion/Glock` + soft piano octave undertone)**: Placed on Bar 1 beat 2 (`0.9375 s`, blooming inside the `1.5 s` app fade-in) and beat 4, Bar 5 beat 1 & 2, and Bar 7 beat 3.
  4. **Harp Ripples (`Strings/Harp`)**: Gentle 4-note upward phrases bridging Bars 2→3, 4→5, 6→7, and 8→1.
  5. **Chamber Strings (`Cello`, `Viola`, `Violin` `susvib` `v1`)**: Pre-rolled (`-140` to `-300 ms`) so natural bow swells arrive cleanly on the downbeat; cello and viola provide mid warmth from Bars 1–2, while violins bloom softly across Bars 3–7 before receding in Bar 8.
  6. **Marimba & Tactile Droplets (`Percussion/Marimba` + synthesized droplets + PS5 shimmer)**: Quiet off-beat wooden marimba echoes (`280–2800 Hz`, Bars 2, 4, 6, 8), brief liquid harmonic droplets (`110–130 ms`), and a `-22 dB` stereo air shimmer bed (`900–4600 Hz`).

---

## 2. What Was Taken From Each Reference Style & Why

- **MementoMori Concert OP**:
  - *Taken*: Solitary orchestral bell chimes cutting softly into warm felt piano and chamber string swells, minor-mode bittersweet yearning, and concert-hall reverb depth (`~2.65 s` RT60 tail).
  - *Left out / adapted for car*: No solo lead vocal (avoids colliding with driver speech or voice commands in `1–3 kHz`) and no massive dynamic jumps (`LRA` kept to `2.9 LU` so quiet passages stay audible above road noise).
- **PS4 Home Menu**:
  - *Taken*: Warm, continuously breathing low-mid synth pads, harp-like harmonic ripples, and seamless bar crossfades (`1.15 s` raised-cosine overlaps) with zero drum kit so the track feels poised and non-fatiguing across long user-paced dwell times.
  - *Left out*: Avoided totally beatless stasis by anchoring gentle `64.0 BPM` piano/bass harmonic motion to align with the existing `64 BPM` cover beat pulse.
- **PS5 Home Menu**:
  - *Taken*: Spacious stereo shimmer bed, suspended extended chord voicings (`m9`, `maj7#11`, `7sus4`), and delicate off-beat wooden marimba taps paired with soft water-clear harmonic droplets.
  - *Left out / adapted for car*: Kept droplets quiet and rounded (`-22 dB` behind piano) so they never sound like vehicle warning or navigation chimes, and tightened low-frequency reverb (`95 Hz` HPF on IR) to avoid cabin muddiness.

---

## 3. Measured Loudness, True Peak, and LRA (`ffmpeg ebur128=peak=true`)

Measured using `D:\Useful Tools\ffmpeg-N-124557-g9e71ea2d60-win64-gpl-shared\bin\ffmpeg.exe -hide_banner -nostats -i <file> -af "ebur128=peak=true" -f null -`:

| File | Integrated Loudness | True Peak | Loudness Range (LRA) | Target Assessment |
|---|---|---|---|---|
| `welcome_bgm_gemini.wav` | **`-18.6 LUFS`** | **`-7.0 dBFS`** | **`2.9 LU`** | Right on `-18.5 LUFS` target; ample headroom for `0.6f` playback & `0.15f` ducking. |
| `welcome_bgm_gemini.ogg` | **`-18.7 LUFS`** | **`-6.9 dBFS`** | **`2.9 LU`** | Matches master cleanly after Vorbis encoding with zero clipping risk. |

Car EQ applied in mastering (over 3 concatenated cycles before slicing the middle cycle):
- `55 Hz` 2nd-order high-pass filter (removes sub-bass road/door rumble) + bass mono-centering below `145 Hz`
- `+0.8 dB` gentle warmth at `220 Hz` (`Q = 0.85`)
- `-2.0 dB` driver-speech clarity pocket at `1,800 Hz` (`Q = 0.90`)
- `+1.0 dB` air sheen at `4,500 Hz` (`Q = 0.80`) and `-2.0 dB` high shelf above `10,000 Hz`

---

## 4. Loop-Seam Check

All pads, LFOs (`15.0 s` / `30.0 s` exact periods), `2.65 s` stereo hall convolution reverb, car EQ biquads, and RMS leveling were processed across **3 concatenated `30.000 s` cycles** (`90.000 s`), extracting the exact middle cycle (`[1,440,000 : 2,880,000]`).

Measured directly on `welcome_bgm_gemini.wav` (`48,000 Hz`, 16-bit stereo):
- **Last 3 samples (`[-3:]`)**:
  - `L`: `[-0.018890, -0.028198, -0.038116]`
  - `R`: `[+0.038818, +0.042450, +0.046295]`
- **First 3 samples (`[:3]`)**:
  - `L`: `[-0.048340, -0.058807, -0.069366]`
  - `R`: `[+0.050446, +0.054779, +0.059265]`
- **End-to-start sample difference (`|end[-1] - start[0]|`)**:
  - `L`: **`0.010223`** (`-39.8 dBFS`, matching the natural single-sample slope `-0.009918` before and `-0.010468` after the seam)
  - `R`: **`0.004150`** (`-47.6 dBFS`, matching `+0.003845` before and `+0.004333` after the seam)
- **Second-derivative slope jump across seam**: **`0.000031` (L)** / **`0.000061` (R)** (within 1–2 quantization LSBs of 16-bit PCM). There is no click, step, or reverb truncation at the loop boundary (`welcome_bgm_gemini_preview.wav` plays two loops consecutively with zero audible seam).

---

## 5. File Sizes & Stream Format

| Deliverable | Size (Bytes) | Human Size | Format Details |
|---|---|---|---|
| [render.py](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/bgm-gemini/render.py) | `42,933` | `~41.9 KiB` | Deterministic Python script (`SEED = 20261127`) |
| [welcome_bgm_gemini.wav](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/bgm-gemini/welcome_bgm_gemini.wav) | `5,760,044` | `~5.49 MiB` | RIFF WAV, 48,000 Hz, 16-bit PCM, stereo, `30.000 s` |
| [welcome_bgm_gemini.ogg](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/bgm-gemini/welcome_bgm_gemini.ogg) | `191,187` | `~186.7 KiB` | Ogg Vorbis (`Lavc62.34.100 libvorbis`, `-q:a 0.2`), 48,000 Hz stereo, `30.000 s`, ~51.0 kbps avg |
| [welcome_bgm_gemini_preview.wav](file:///D:/Data%20for%20Apps%20and%20etc/Codex/am_atv_a0/bgm-gemini/welcome_bgm_gemini_preview.wav) | `11,520,044` | `~10.99 MiB` | RIFF WAV, 48,000 Hz, 16-bit PCM, stereo, `60.000 s` (2x loop) |

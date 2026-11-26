# Welcome BGM generator

`render.py` renders `app/src/main/res/raw/folio_welcome_bgm.ogg`. It is an original 30 s seamless loop at 64 BPM in E minor, composed by Gemini 4 and chosen by ear over a second version on 2026-11-27. `NOTES.md` describes the score, the mix and the measurements:
- −18.6 LUFS integrated;
- −7.0 dBFS true peak;
- LRA 2.9 LU.

Samples come from VSCO 2 Community Edition, https://github.com/sgossner/VSCO-2-CE (CC0 1.0, no attribution required):
- `Keys/Upright Piano`;
- the `susvib` folders for the violin, viola and cello sections;
- `Strings/Harp`;
- `Percussion/Glock`;
- `Percussion/Marimba`.

VSCO names the strings, glockenspiel and marimba one octave below their sounding pitch (C3 = MIDI 60). The script corrects for this.

To re-render:
1. Download those folders.
2. Point `ASSETS_ROOT` in `render.py` at them.
3. Run it with Python 3.12, NumPy and SciPy.
4. Encode the master with ffmpeg: `-c:a libvorbis -q:a 0.2`.

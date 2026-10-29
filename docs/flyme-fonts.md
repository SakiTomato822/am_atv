# Flyme Auto fonts

Inter is the Latin/numeric family; Noto Sans SC is its Android custom fallback for Chinese. Android 10+ uses a real two-family Typeface fallback chain with 400/500/600/700/800/900 weights; Android 9 uses Inter with system CJK fallback. Other scripts retain Android system fallback. The font files are unmodified and keep their original family names.

Sources (downloaded 2026-10-28):

- https://github.com/google/fonts/tree/main/ofl/inter
- https://github.com/google/fonts/tree/main/ofl/notosanssc

Both fonts use SIL OFL 1.1. Full notices ship in `assets/licenses/Inter-OFL.txt` and `assets/licenses/NotoSansSC-OFL.txt`, with attribution in NOTICE.txt.

File hashes:

- `inter_variable.ttf`: SHA-256 `29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031`
- `noto_sans_sc_variable.ttf`: SHA-256 `a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da`

The app theme applies this family across the app's Compose pages, dialogs, lyric words and local Gecko status text. Apple's remote sign-in page retains its own styling.

Lyrics have a separate Standard/Large/Extra large setting (125% / 155% / 185% of the TV lyric sizes); the car default remains Large, so an existing Large preference also receives the larger sizes. Main lyrics use real weight 800, and translated/pronunciation lines keep their lighter weights and scale with them. Background vocals use about 60% of the main text size and weight 600 in every timing mode. Font changes remeasure the lyric viewport while preserving the current track and playback position. In the Cider layout the right lyric viewport is separate from the lower-left playback-control card.

The initial `FontFamily(Typeface)` wrapper did not honor Compose weight requests: AndroidX's `AndroidTypefaceWrapper.getNativeTypeface()` returns the same native typeface for every requested weight. The replacement uses a font list of blocking `AndroidFont` descriptors, one for each actual native weight. The regression test resolves the Compose family at 400 and 800 and verifies that Chinese 800 glyphs have measurably more rasterized ink, with font synthesis disabled.

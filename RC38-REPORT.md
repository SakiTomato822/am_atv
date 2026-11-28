# RC38 report

Branch `rc38` starts from RC2.9 at `00c13f6`.

## Now Playing

`FolioNowPlayingSurface` renders the backdrop, glass, track-switch and loading effects, header, tier-specific bodies, lyrics, progress, and transport controls. The live player and welcome guide call that composable with the same tier spec. The live layer retains the application store reads, lyric requests, queue and control state, input actions, pull-to-close, Back handling, and entrance and exit. The settings sheet and its Back handler are provided by the live caller and rendered in the player's glass and motion environment.

| Inputs | Parameters |
| --- | --- |
| Track and timeline | `track`, `displayedLines`, `positionState`, `durationMs`, `lyricOffsetMs`, `lyricsState` |
| Playback | `active`, `isPlaying`, `playWhenReady`, `isLoading`, `isLiveStream`, `canSeek`, `canSkipPrevious`, `canSkipNext` |
| Layout and appearance | `tierSpec`, `statusTop`, `navBottom`, `displayState`, `underlayDisplayState`, `effectiveStyle`, `lyricTextSize`, `isLight`, `lowPerformance`, `reducedMotion`, `controlsExpanded`, `alwaysOpen` |
| Lyric enhancements and queue | `translationEnabled`, `pronunciationEnabled`, `queueSnapshot`, `queueOpen` |
| Header and notices | `isFavorite`, `isInLibrary`, `favoriteInProgress`, `libraryInProgress`, `notice`, `showStyleToggleHint`, `onDismissStyleHint` |
| Interaction and composition | `callbacks`, `pullHandle`, `modifier`, `overlay` |

The welcome guide measures the player at the real window's pixel dimensions with the same density and insets. It applies one uniform graphics-layer scale inside the rounded preview window. The portrait stage is smaller and higher on the page to leave space for the onboarding controls. The preview consumes pointer input and supplies no-op actions. The home backdrop uses its static rendering path while welcome is open.

The welcome guide supplies showcase tracks, timed showcase lyrics when available, and a looping position source. It uses timed lines from the app's existing demo catalog for signed-out viewers, missing timed lyrics, and subsequent Gapless tracks. This gives both styles and lyric-size changes a timed example as soon as showcase artwork is available. The guide's badge, Gapless progress indicator, and loudness bars remain overlays. Track changes run through the player's track-switch effects. The cover beat pulse and the cover-wall fly-in were removed. The fly-in previously targeted a fixed tile in the hand-drawn player, while the shared player's artwork placement depends on its tier and style.

## Build and tests

`:app:testFlymeGeckoUnitTest` passed all 687 tests. The focused welcome tests exercise uniform scaling with landscape and portrait dimensions and verify that showcase lyrics are used only for their matching track.

`:app:assembleFlymeGecko` passed in a separate invocation. The exact baseline commit also assembled with the same arm64-v8a UI-verification configuration. Gradle ran with one worker, in-process Kotlin compilation, and the emulator stopped.

## Emulator screenshots

The screenshots use the Wide tier at 1920 × 1080 on `emulator-5582` (Android 15, host GPU, Vulkan disabled). A temporary instrumentation harness supplied local artwork and demo tracks to the ordinary app entry point. The first two pages have no player preview. The comparisons place the Styles preview next to the live Now Playing screen scaled to the same width. I also verified the live lyric-size change in Preferences. The emulator was stopped after capture.

| Appearance and style | Welcome | Sign-in | Lyrics | Styles | Gapless | Preferences | Now Playing | Side by side |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Dark Flow | [PNG](rc38-shots/welcome-dark-flow.png) | [PNG](rc38-shots/signin-dark-flow.png) | [PNG](rc38-shots/lyrics-dark-flow.png) | [PNG](rc38-shots/styles-dark-flow.png) | [PNG](rc38-shots/gapless-dark-flow.png) | [PNG](rc38-shots/preferences-dark-flow.png) | [PNG](rc38-shots/now-playing-dark-flow.png) | [PNG](rc38-shots/compare-dark-flow.png) |
| Dark Poster | [PNG](rc38-shots/welcome-dark-poster.png) | [PNG](rc38-shots/signin-dark-poster.png) | [PNG](rc38-shots/lyrics-dark-poster.png) | [PNG](rc38-shots/styles-dark-poster.png) | [PNG](rc38-shots/gapless-dark-poster.png) | [PNG](rc38-shots/preferences-dark-poster.png) | [PNG](rc38-shots/now-playing-dark-poster.png) | [PNG](rc38-shots/compare-dark-poster.png) |
| Light Flow | [PNG](rc38-shots/welcome-light-flow.png) | [PNG](rc38-shots/signin-light-flow.png) | [PNG](rc38-shots/lyrics-light-flow.png) | [PNG](rc38-shots/styles-light-flow.png) | [PNG](rc38-shots/gapless-light-flow.png) | [PNG](rc38-shots/preferences-light-flow.png) | [PNG](rc38-shots/now-playing-light-flow.png) | [PNG](rc38-shots/compare-light-flow.png) |
| Light Poster | [PNG](rc38-shots/welcome-light-poster.png) | [PNG](rc38-shots/signin-light-poster.png) | [PNG](rc38-shots/lyrics-light-poster.png) | [PNG](rc38-shots/styles-light-poster.png) | [PNG](rc38-shots/gapless-light-poster.png) | [PNG](rc38-shots/preferences-light-poster.png) | [PNG](rc38-shots/now-playing-light-poster.png) | [PNG](rc38-shots/compare-light-poster.png) |

## Frame timing

Each sample resets `dumpsys gfxinfo dev.amatv.flyme.preview` after the page and style settle, then collects approximately eight seconds of frames. The runs use dark appearance, animations on, Low Performance off, and the same signed-out fixture with playback idle behind welcome. The baseline Lyrics page in this scenario shows track metadata. RC38 runs the live karaoke renderer with demo lines. The samples cover the emulator's animated path. The Android 11 car's frame timing and the active-track home backdrop are outside this measurement.

| Page and style | Build | Frames | Janky frames | p50 | p90 | p95 | p99 | Missed vsync |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Lyrics Flow | RC2.9 | 360 | 115 (31.94%) | 17 ms | 18 ms | 23 ms | 32 ms | 0 |
| Lyrics Flow | RC38 | 437 | 49 (11.21%) | 32 ms | 48 ms | 61 ms | 85 ms | 18 |
| Styles Flow | RC2.9 | 354 | 122 (34.46%) | 17 ms | 24 ms | 27 ms | 40 ms | 0 |
| Styles Flow | RC38 | 405 | 80 (19.75%) | 42 ms | 61 ms | 65 ms | 85 ms | 29 |
| Styles Poster | RC2.9 | 365 | 120 (32.88%) | 17 ms | 18 ms | 18 ms | 20 ms | 0 |
| Styles Poster | RC38 | 469 | 17 (3.62%) | 29 ms | 40 ms | 46 ms | 61 ms | 6 |

RC38 had longer frame durations across the three samples even though Android classified a smaller share of frames as janky. The shared player draws the live backdrop and lyrics. Its existing static fallbacks still apply in Low Performance mode.

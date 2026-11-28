# RC39 report

RC39 adds the handwritten AM Folio opening to the Welcome page, including replay from Settings. The supplied binary contains 11 strokes and 723 pen samples. The timeline plays recorded positions and pressure at 1.6×, including the 140 ms inter-stroke intervals before scaling. Writing takes 4.563 s, followed by a 0.5 s hold and a 1.2 s eased reveal.

The opening pauses both marquee directions at their starting positions. It records the actual cover wall, scales the capture to 1/16 on each axis, and applies a 5×5 blur once. The acrylic adds a static grain and a 35% white or 45% dark tint according to the app theme. The writing spans 56% of the window. The base stroke width is 1.6% of the writing width and pressure modulates each rounded stroke. Cached paths carve through the offscreen layer with `DstOut` and a 1.35 dp feather. After the reveal, the same wall resumes moving and the page's existing gradient, copy, and controls fade in. A tap reveals the finished Welcome page immediately. Low Performance and reduced motion open directly on the regular page.

The capture waits up to 500 ms for the Browse cover rows. On a cold run where Browse took longer, the frost captured the dark base and some artwork arrived while the strokes were being drawn. The recordings below use Settings replay after the covers had loaded.

## Build and tests

`:app:testFlymeGeckoUnitTest` passed all 690 tests, including three new tests for little-endian parsing, the timing and pressure interpolation, and skipping from writing, hold, and expansion. `:app:assembleFlymeGecko` passed in a separate invocation with UI verification enabled and the requested `arm64-v8a` ABI. An additional `x86_64` preview build supports the Android 11 AVD. Gradle ran with the emulator stopped.

## Recordings and stills

Both videos are `adb shell screenrecord` captures of Settings replay on `emulator-5582` at 1920 × 1080. The AVD ran Android 11 (API 30) with the host GPU and Vulkan disabled. The stills use video frames at the requested writing and expansion checkpoints. I checked the feathered edge, the wall through the carved strokes, and the transition into the Welcome controls in both themes. I also verified an early tap on the acrylic, Low Performance mode, and reduced motion with the system animator scale set to zero. The emulator was stopped with `adb -s emulator-5582 emu kill`.

| Theme | Recording | 25% writing | 60% writing | 100% writing | Mid-expansion |
| --- | --- | --- | --- | --- | --- |
| Dark | [MP4](rc39-shots/intro-dark.mp4) | [PNG](rc39-shots/dark-25.png) | [PNG](rc39-shots/dark-60.png) | [PNG](rc39-shots/dark-100.png) | [PNG](rc39-shots/dark-mid-expansion.png) |
| Light | [MP4](rc39-shots/intro-light.mp4) | [PNG](rc39-shots/light-25.png) | [PNG](rc39-shots/light-60.png) | [PNG](rc39-shots/light-100.png) | [PNG](rc39-shots/light-mid-expansion.png) |

## Frame timing

I reset `dumpsys gfxinfo dev.amatv.flyme.preview` before each Settings replay and sampled about 7.3 s through the intro and handoff. The debuggable preview ran with Low Performance off and screen recording stopped.

| Theme | Frames | Janky | p50 | p90 | p95 | p99 | Missed vsync | Missed deadline |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Dark | 387 | 365 (94.32%) | 20 ms | 28 ms | 42 ms | 69 ms | 9 | 21 |
| Light | 389 | 353 (90.75%) | 20 ms | 28 ms | 40 ms | 125 ms | 7 | 19 |

Android reported high input latency on 359 Dark frames and 348 Light frames. GPU p95 was 19 ms in both runs. These timings describe the host-GPU emulator. [Dark gfxinfo](rc39-shots/gfxinfo-dark.txt), [Light gfxinfo](rc39-shots/gfxinfo-light.txt)

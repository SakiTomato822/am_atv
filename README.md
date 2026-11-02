# AM for Flyme Auto

**Apple Music in the car: an unofficial client adapted for Flyme Auto (Lynk & Co 06 and others)**

The `flyme-auto` branch of [AM for TV](https://github.com/Sam20060720/am_atv), bringing its Apple TV-style Apple Music experience to the in-car touchscreen.

English · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md)

> Unofficial project, not affiliated with Apple, Geely, Lynk & Co, ECARX or Meizu. Requires an Apple Music subscription. In-car behaviour is verified by owners, not guaranteed.

## Download

Get the latest **Flyme Auto test build** from [Releases](https://github.com/SakiTomato822/am_atv/releases): `AMATV-FlymeAuto-Gecko-arm64.apk`.

- ARM64, Android 9+; package `dev.amatv.flyme`.
- Every test build uses the same signing key, so it installs over the previous one and keeps your sign-in and settings.
- Sign in with your Apple ID on first launch (built-in GeckoView web sign-in).

## Features

**Playback**
- Apple TV / Cider-style player: artwork and controls on the left, word-by-word lyrics on the right; artwork shrinks when paused.
- Word-by-word lyrics (backing vocals, long notes, interludes, timing offset); the lyrics on/off choice is remembered.
- Queue reads left to right: Now Playing, then Up Next; swipe left for history.
- Radio: personal stations, song stations and live stations such as Apple Music 1, played by MusicKit JS inside a built-in GeckoView; live radio has its own page.

**Browsing**
- Centered top navigation with a persistent mini player; the home background takes its colors from the playing artwork.
- Library on one page: playlists, artists, albums and songs, as a grid or a list.
- Albums and songs sort by date added (newest first) by default; tap the selected sort again to reverse it.
- Artist pages show the albums in your library plus Apple Music's top songs, albums, singles and EPs.
- Saved albums can show the complete album.

**In the car**
- Scaled from a 1920×968 design size with system bars kept visible; touch first, key navigation still works.
- Publishes a standard Android MediaSession (steering-wheel keys, system media card).
- Tries to register as a Flyme MediaCenter online-music source (verified versions only; not yet confirmed to appear in the car).
- Sends the current lyrics (main vocals) to EVCC.

## Permissions

| Permission | Why |
| --- | --- |
| Record audio (`RECORD_AUDIO`) | Asked on first playback. Used only to analyze **this app's own playback** for the spectrum bars; the microphone is not used. Declining does not affect playback; the queue bars pulse decoratively instead. |

## Known limitations

- Radio plays in GeckoView, which exposes no audio for analysis, so its bars always pulse decoratively.
- The Apple Music API has no library sort parameter: any non-default sort loads every item in the background, which takes a moment for large libraries.
- The native MediaCenter source is enabled only for verified versions; others use the standard MediaSession alone.

## Build

Requires JDK 17, Android SDK 36 and the official [MusicKit Android SDK](https://developer.apple.com/musickit/android/), downloaded from Apple yourself (it is not redistributable and not in this repository). See [MusicKit setup](docs/musickit-setup.md).

```sh
./gradlew :app:assembleFlymeGecko
```

### Releasing with GitHub Actions

The [`flyme-auto.yml`](.github/workflows/flyme-auto.yml) workflow is started manually. It builds, tests and verifies the signature, then publishes a prerelease titled with the Flyme version from `app/build.gradle.kts`. Repository secrets:

| Secret | Contents |
| --- | --- |
| `MUSICKIT_SDK_TOKEN` | Fine-grained token with read-only Contents access to the private `am_atv-sdk` repository, whose release holds the SDK ZIP |
| `AM_DEVELOPER_TOKEN` | Apple Music developer token |
| `FLYME_SIGNING_KEYSTORE_BASE64` | The fixed signing keystore, Base64-encoded |

## Documentation

- [Flyme Auto adaptation](docs/flyme-auto.md): screen, interaction and media-source protocol
- [Progress](docs/flyme-auto-progress.md) · [Roadmap](docs/flyme-roadmap.md)
- [Radio playback status](docs/flyme-radio-playback-status.md) · [EVCC lyrics](docs/evcc-lyrics.md) · [Fonts](docs/flyme-fonts.md)

## License

[MIT](LICENSE) · [Third-party licenses](app/src/main/assets/licenses/NOTICE.txt). Upstream: [AM for TV](https://github.com/Sam20060720/am_atv).

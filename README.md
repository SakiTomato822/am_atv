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
- Now Playing grows out of the mini player and shrinks back into it; swipe up on the artwork to close it.
- Long titles, artists and albums scroll as a marquee; explicit songs carry an (E) badge.
- Tap the artist or album name to open its page.
- Star a song to add it to Favorite Songs. Apple lets only its own apps remove favorites, so starring a favorite again shows a note to remove it in Apple Music.
- Gapless playback: the next song is queued natively ahead of time.
- If a song fails because of Apple ID content restrictions (explicit content), the player says so instead of a generic error.

**Lyrics**
- Word-by-word lyrics (backing vocals, long notes, interludes, timing offset); the lyrics on/off choice is remembered.
- Drag the lyrics to look around; they return to the current line when you let go. Tap a timed line to jump to it.
- With Chinese as the translation language, Chinese lyrics get no extra Simplified/Traditional "translation" line; Japanese, Korean and English are translated as usual.

**Queue and radio**
- Queue reads left to right: earlier songs, Now Playing, then Up Next.
- Radio: personal stations, song stations and live stations such as Apple Music 1, played by MusicKit JS inside a built-in GeckoView; live radio has its own page.
- Station history: the last 50 station songs are kept (also across restarts) and shown in the queue; tap one to play it.
- Stations recover from decode errors at track changes and detect when Apple falls back to 30-second previews.

**Browsing**
- Centered top navigation with a mini player capsule that stays on every page, including album and artist pages; the home background takes its colors from the playing artwork.
- Home shows Top Picks for You and your Apple Music recommendations.
- Search: results grouped by songs, artists, albums and playlists; search history instead of fixed suggestions; recently played as a vertical, paged list; the layout stays clear of the on-screen keyboard in landscape.
- Library on one page: playlists, artists, albums and songs, as a grid or a list. Albums and songs sort by date added (newest first) by default; tap the selected sort again to reverse it.
- Artist pages follow Apple Music on the web: top songs (up to 30), latest release, albums, singles and EPs, artist playlists, live albums, compilations, appears on, and similar artists.
- Saved albums can show the complete album, and album pages link to the artist.

**In the car**
- Scaled from a 1920×968 design size with system bars kept visible; touch first, key navigation still works. One back-button style across pages.
- Publishes a standard Android MediaSession (steering-wheel keys, system media card).
- Tries to register as a Flyme MediaCenter online-music source (verified versions only; not yet confirmed to appear in the car).
- Sends the current lyrics (main vocals) to EVCC.

## Permissions

No special permissions: the app needs network access only. Earlier builds asked for the microphone for a spectrum display; that display and the permission were removed in Flyme.9.

## Known limitations

- Favorites can be added but not removed from this app (the public Apple Music API refuses removal for third-party apps).
- Your Apple Music profile photo cannot be shown; the public API does not provide it.
- The Apple Music API has no library sort parameter: any non-default sort loads every item in the background, which takes a moment for large libraries.
- The native MediaCenter source is enabled only for verified versions; others use the standard MediaSession alone.
- Portrait layouts are not adapted yet.

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
- [Gapless playback](docs/flyme-gapless.md) · [Radio playback status](docs/flyme-radio-playback-status.md) · [EVCC lyrics](docs/evcc-lyrics.md) · [Fonts](docs/flyme-fonts.md)

## License

[MIT](LICENSE) · [Third-party licenses](app/src/main/assets/licenses/NOTICE.txt). Upstream: [AM for TV](https://github.com/Sam20060720/am_atv).

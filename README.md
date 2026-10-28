# AM for TV

> This fork's `flyme-auto` branch adds an experimental **AM for Flyme Auto** variant: a 1920×968 design reference, visible system bars, Gecko sign-in, touch controls and a native media-source interoperability client. See [Flyme Auto adaptation and validation limits](docs/flyme-auto.md).

**The smoothest Apple Music client for Android TV**

Offers an experience close to the native Apple Music app on Apple TV.

English · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md)

## Features

- Library, search, playlists, playback queue, and listening history
- Word-by-word lyrics (backing vocals, long notes, interludes, timing offset)
- Artwork colors, animated backgrounds, and audio spectrum bars
- Autoplay recommendations (Apple Music / YouTube Music)
- Artwork and lyric caches; English, Traditional Chinese, and Simplified Chinese

## Install

Android TV 9+ and an Apple Music subscription required. Install the APK and sign in to Apple Music.

## Screenshots

| Home | Player |
| --- | --- |
| ![Home screen](docs/assets/home.png) | ![Player with lyrics](docs/assets/player-lyrics.png) |

| Playlist | Queue |
| --- | --- |
| ![Playlist tracks](docs/assets/playlist.png) | ![Playback queue](docs/assets/queue.png) |

![Playback demo](docs/assets/player-demo.webp)

## Microphone permission

`RECORD_AUDIO` is used to analyze playback audio for the visualizer. The physical microphone is not used.

## Build

Requires JDK 17, Android SDK 36, and the official [MusicKit Android SDK](https://developer.apple.com/musickit/android/). See [MusicKit setup](docs/musickit-setup.md) for credentials.

```sh
./gradlew :app:assembleDebug
tools/build-standalone.sh
# Alternate APK with GeckoView for Apple Music sign-in:
tools/build-standalone-gecko.sh
```

The standalone APK is written to `dist/`. See [APK and source distribution](docs/standalone-distribution.md) for signing and packaging.

## License

[MIT](LICENSE) · [Third-party licenses](app/src/main/assets/licenses/NOTICE.txt)

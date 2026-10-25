# AM for TV

An independent Apple Music client for Android TV, licensed under [MIT](LICENSE).

- Native MusicKit playback and remote navigation.
- Albums, playlists, search, playback history and a selectable queue.
- Synchronized lyrics and an artwork-colored background that responds to the music.
- Apple Music / YouTube Music recommendations.
- Configurable cover and lyrics caching.

## Install

Install the APK on Android TV 9 or later and sign in with your own Apple Music
account. Internet access and an Apple Music subscription are required. The APK
runs independently; no workstation or local server is needed.

## Build

Requires JDK 17 and Android SDK 36. For live playback, download the official
MusicKit SDK and configure your own developer credential: [setup](docs/musickit-setup.md).

```sh
./gradlew :app:assembleDebug
tools/build-standalone.sh
```

The shareable APK is written to `dist/`. An unconfigured debug build uses demo
data. Keep the signing key for future updates.

See [distribution](docs/standalone-distribution.md) for APK and source packaging.

The app caches covers and lyrics, not protected audio. Station and music-video
playback are not supported. Lyrics and optional YouTube Music recommendations
use undocumented upstream interfaces and may change.

Apple SDK binaries, credentials, account data and generated artifacts are not
included in source releases. Use `tools/export-source.sh` to create a source-only
archive. Third-party components retain their own
[licenses](app/src/main/assets/licenses/NOTICE.txt). This project is not affiliated with Apple.

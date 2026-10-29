# EVCC lyric output

Flyme.3 publishes the resolved original **main vocal** lyrics in the Android `MediaMetadata` String key `lyrics`. It uses the existing framework MediaSession and MusicKit playback engine. It does not bind EVCC's consumer-side music API. Harmony, translation and pronunciation stay inside the app; they are excluded from the external document, cues and content version.

## Receiver evidence

The interoperability rule was checked statically against the official beta EVCC 3.4.5 APK (`com.kooo.evcc`, versionCode 320), downloaded from the [official download center](https://download.coauto.cc/). SHA-256: `9b5b3dcac6430f63ba871c47bde6bdc6e97a5a7e2aa8e69e55f1b7e13b944004`. ZIP integrity and APK v2 signature verification passed. The APK was not installed, modified or redistributed.

`classes.dex`, `kr3.ll1()`, enumerates `MediaMetadata.keySet()` and reads String values whose keys contain `lyric` or `lrc`, ignoring case (instruction offsets 0x0280–0x02ae). The same rule is applied to `PlaybackState.extras` (0x02b6–0x02ec). Therefore the custom key `lyrics` matches an observed receiver rule; it is not an Android-standard lyrics constant. Android allows [custom MediaMetadata String keys](https://developer.android.google.cn/reference/android/media/MediaMetadata.Builder#putString(java.lang.String,%20java.lang.String)).

`kr3.lIl()` passes the text into its lyric classifier. Standard timestamped LRC is recognized as a complete document; plain multiline text is recognized as full text and a single untimed line as a current sentence. Keys containing `current`, `sentence`, or `full` have additional semantics, so this app uses the neutral `lyrics` key. The receiver conditionally URL-decodes percent-escaped values; literal percent escapes and plus signs are protected when needed.

## Producer behavior

- The account lyric request finishes independently of SDK playback snapshots. `AtvApp` attaches resolved lyrics only when the catalog identity matches, then republishes the current generation immediately when the document arrives or is cleared.
- A cached `PlaybackLyricExport` builds original-text LRC and a cue index once per resolved track/list. Clock updates do not serialize the whole song repeatedly.
- Timed text uses `[mm:ss.SSS]`. Static or incomplete timing is sent as plain text; timestamps are never fabricated. Oversized payloads are rejected as a whole, including after transport encoding.
- The media metadata update key includes the outgoing lyric payload, so late lyrics are visible even when title, artist and playback position have not changed.
- A new track with no resolved lyrics publishes an empty lyric field; clearing the playback session clears its metadata. EVCC may still refill an empty field from its own same-title/artist lyric cache. An empty field is not a verified EVCC cache-deletion command.

The app's own lyric page keeps its harmony rendering and font-size hierarchy. Independent vehicle controls without EVCC are a deferred follow-up, separate from this output adapter.

The ARM64 release keeps package `dev.amatv.flyme`. The first two CI releases did not actually use the restored fixed keystore; their certificates differ (`186f22…c7f5c` and `d2d730…d7f6`). The hosted-runner private keys were not retained in repository caches or artifacts. Starting with Flyme.3, Gradle explicitly uses the keystore file supplied by Actions and the release job pins certificate SHA-256 `91f7b9d6496414a0fab0a87c8fcc60f929df0dd9fa4d4a9a52215869178bab8d`. A mismatch fails before publishing. Normal upgrades from the first two releases require uninstall/reinstall and one new login; subsequent releases use this fixed identity. Local UI previews use a separate package and do not replace the vehicle package.

## Verification boundary

JVM tests cover song identity, asynchronous completion, main-only filtering, timestamp ordering, static lyrics, payload limits and transport escaping. Android instrumentation reads the actual framework MediaController metadata using EVCC's observed key scan, including late lyric arrival, harmony exclusion, track change and session clear. EVCC/vehicle end-to-end behavior still requires installation and acceptance testing; static receiver inspection and framework readback do not establish that result.

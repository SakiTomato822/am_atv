# Flyme Auto iteration plan

## Current vehicle feedback

Installation, Apple Music playback and EVCC controls work. EVCC displays lyrics for some songs; AM's own native MediaCenter source has not appeared. The bridge status needs to distinguish these routes. The first vehicle photo also shows that lyrics need larger sizes and genuinely heavier glyphs.

## Work streams

1. Typography: replace the weight-insensitive native Typeface wrapper with real Compose font descriptors for 400–900. Use 800 for primary car lyrics, with Standard/Large/Extra large at 125%/155%/185% of TV sizes. Preserve the selected preference and system bars. Verify the resolved native weight and rasterized Chinese glyph ink, then inspect both car content heights.
2. Library: show the user's saved albums and playlists through user-library endpoints. Preserve library IDs, resolve catalog IDs only when needed for playback, follow all pagination, and retain read-only/shared playlist records. Empty/error/loading states must remain distinct. Do not replace missing saved content with catalog recommendations.
3. EVCC lyrics: consume the already loaded Apple Music lyrics, scoped to the current track, and publish them only through a confirmed player-output protocol. EVCC's open `music.read`/`music.listen` APIs are consumer interfaces. No guessed broadcast actions or metadata keys. Clear stale lyrics on track/account changes and reject late results. Keep native MediaCenter diagnostics available separately.
4. Now Playing: follow the user's supplied Cider reference: full-height artwork on the left, track information and transport in a translucent lower-left card, and dominant full-height lyrics on the right. Current lyrics stay sharp; inactive rows progressively soften with distance. API 28–30 uses bounded cached raster blur; API 31+ uses RenderEffect. Reuse MusicKit, queue, seek and lyric timing. Build against available window constraints, not physical display bounds; keep a usable narrow-window layout. Background vocals should be about 60% of primary lyrics and use a lighter weight, without shrinking other duet singers' primary lines.

## Integration and release

The primary agent owns integration in AtvApp/PlayerScreen, reviews each work stream, runs relevant unit and Android tests, and inspects 1920×968, 1920×896 and a reduced-width viewport. Contributors work on separate files and return explicit integration instructions. Changes are published through the existing Actions workflow using the existing signing identity. Real-account library contents, OEM registration and EVCC interoperability remain vehicle acceptance checks; passing emulator tests does not establish those outcomes.

EVCC 3.4.5's actual lyric receiver was verified against its official signed APK. The current release uses the MediaMetadata `lyrics` String key and exports main lyrics only; see [receiver evidence and verification scope](evcc-lyrics.md).

## Independent in-car controls follow-up

Deferred at the user's request. Do not include new standalone bridge work in the current EVCC lyric release.

The user requested a later follow-up that uses EVCC's bridge behavior as an interoperability reference, so this app can provide vehicle controls without an EVCC installation. Keep MusicKit as the playback engine and the app's existing MediaSession and MediaCenter client as the control routes. Investigate source registration, supported MediaCenter versions, connection recovery, command callbacks and background/restart behavior using the app's own identity. Verify with EVCC music forwarding disabled: media-source discovery, title/artwork, steering-wheel previous/next/play/pause, seeking where supported, reconnect after MediaCenter restart, and navigation/phone audio-focus interactions. Vehicle acceptance and any source-registration requirements must be recorded separately from successful Android MediaSession control tests. Finish the current EVCC lyric output release before expanding this follow-up.

# Flyme radio playback status

Local diagnostic record, 2026-10-30. This records the current runtime boundary; it does not claim radio playback is fixed or change the public Flyme.4 release.

## Runtime comparison

- A regular catalog song played on the ARM64 preview emulator: MusicKit reached playing state (`rate=1.0`) and advanced playback position. It was then paused.
- A user-personal station (`ra.u` ID omitted) failed in the playback queue layer.
- Apple Music 1, opened from the app's official Apple Music Radio browse shelf, also failed in the playback queue layer.
- Both station failures reported MusicKit `TYPE_PLAYBACK_QUEUE`; the SDK's code and component values were `ERROR_UNKNOWN`, and the cause chain ended in `java.io.IOException`. The safe diagnostic formatter found no allowlisted message or HTTP status hint. The app displayed its existing retryable playback error. No exception text, request URL, query, or account token was recorded.
- The app remained signed in. No account data was cleared or changed.

## Callback fix

Before the fix, an error callback was dropped when a new station queue was preparing but the native controller still exposed the previous song. That left the app to report a local readiness timeout instead of the SDK's queue error.

The callback now accepts an error when its generation is still the active, issued request for the current controller and that request is preparing. It retains item/container matching after preparation and rejects stale generations. JVM regression tests cover the retained-old-song case, stale generation rejection, unrelated old items after preparation, and diagnostic redaction.

## Pinned SDK limitation

Static inspection of the pinned `mediaplayback-release-1.1.1.aar` shows a station queue limitation beyond the missing named constant:

- `CatalogPlaybackQueueItemProvider.Builder.containers(type, ids)` accepts type `3`, but the provider's URL builder only maps container types `1` and `2` to `albums` and `playlists`. Type `3` takes the default path and does not append a `stations` segment.
- The provider's response mapper recognizes `albums`, `playlists`, `songs`, and `music-videos`, but not `stations`. A station resource is mapped to an unplayable generic item; the AAR contains no station-specific queue model/parser.

Therefore a successful builder construction or a URL-only `/stations` patch would not establish a playable native station queue. The observed `IOException` does not reveal an HTTP status, so the runtime evidence does not identify the exact network response. The static AAR limitation is a separate, directly inspected finding. No private endpoint, DRM bypass, or speculative provider patch is used.

## Verification and release wording

The local `testFlymeGeckoUnitTest` run completed with 409 tests, 0 failures, 0 errors, and 0 skipped; `assembleFlymeGecko` succeeded. The updated ARM64 preview package was installed over the existing package without clearing its data. No new APK was published.

Suggested correction for review (not applied to the published release):

> Flyme.4 exposes the radio entry point, but station playback is not currently working with the pinned MusicKit Playback 1.1.1 library. Song playback was verified in the local ARM64 preview environment; both a personal station and Apple Music 1 failed in the native station-queue path. Do not treat provider construction as proof of station playback. Radio support needs an Apple-supported SDK implementation that handles station fetching and queue parsing.

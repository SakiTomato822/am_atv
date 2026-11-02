# Gapless playback feasibility (Flyme.7)

## Today

Native songs are handed to MusicKit **one at a time**. `MusicKitBridge.preparePlayback` builds a
`CatalogPlaybackQueueItemProvider` for the single requested catalog ID and calls
`prepare(provider, false)`. When the SDK reports `onItemEnded`, the app's own queue
(`PlaybackQueueController`: up next, autoplay, shuffle, repeat, YouTube Music recommendations) picks the
next song and prepares it from scratch. Every transition therefore waits for a new prepare: stream
lookup, licence, buffering. Continuous albums (live records, DJ mixes, concept albums) audibly break.

Stations are unaffected: they play in GeckoView through MusicKit JS, which manages its own queue.

## What the SDK offers (mediaplayback 1.1.1, from the compiled API)

`MediaPlayerController`:

- `prepare(provider, startIndex, playWhenReady)`; the builder accepts several IDs:
  `items(type, String...)`, `containers(type, String...)`, `startItemIndex(int)`.
- `addQueueItems(provider, insertionType)` with `PlaybackQueueInsertionType`
  `AT_END`, `AFTER_CURRENT_ITEM`, `BEFORE_CURRENT_ITEM`, `REPLACE`, `CLEAR_AND_REPLACE`, `KEEP_AND_REPLACE`.
- `removeQueueItemWithId`, `moveQueueItemWithId`, `skipToQueueItemWithId`, `getQueueItems`.
- `canAppendToPlaybackQueue`, `canEditPlaybackQueue`.

`Listener`: `onCurrentItemChanged(previous, current)`, `onItemEnded(item, position)`,
`onPlaybackQueueChanged`, `onPlaybackQueueItemsAdded`.

So the SDK can hold more than one item and tell us when it moves to the next one on its own.

## Proposed approach: one-item lookahead

Keep the app queue as the single source of truth and mirror only **the next song** into the native
queue:

1. After a song starts playing (and again whenever the app's "next" changes), append the app's next
   song with `addQueueItems(provider(nextId), AT_END)`, after first removing any previously appended
   lookahead item.
2. When `onCurrentItemChanged` reports the lookahead item, advance the app queue to it **without**
   calling `prepare`. Lyrics, MediaSession, EVCC and the UI follow the app queue as today.
3. User actions that change "next" (skip, select from queue, shuffle/repeat, clear, autoplay source,
   inserting into Up Next) remove the stale lookahead and append the new one. Explicit skips keep using
   the current prepare path.
4. No lookahead for repeat-one, stations, live streams, or when `canAppendToPlaybackQueue()` is false.
5. Library songs append their resolved catalog IDs, as prepare already does.

This keeps every existing queue feature and limits the change to `MusicKitBridge` plus the
"advance on ended" path in `AtvApp`.

## Risks to verify on a device

- **Whether the SDK actually stitches the two items.** Holding the next item lets it preload; the API
  does not promise sample-accurate gapless output. Measure with a known continuous album: log the
  `onItemEnded` → `onCurrentItemChanged` timing and listen for the gap.
- `playbackIntent` and `endedCatalogId` assume one native item per prepare; both must accept an
  SDK-initiated item change.
- `getPlaybackQueueIndex` / `canSkipToNextItem` will change meaning once the native queue has two items;
  the app must keep using its own queue for skip availability.
- Restore-on-launch must not restore the lookahead item as the current song.
- The x86 ARM-bridge emulator has already shown a native crash in `SVAudioDecoderJNI::run`; judge
  stability on real ARM hardware.

## Implemented (Flyme.7)

- `PlaybackQueueController.peekAutoNext()` names the entry an automatic advance would play.
- `AtvApp` resolves it 1.5 s after the current song is ready and calls `MusicServices.setLookahead`.
- `MusicKitBridge.syncLookahead` appends it with `addQueueItems(provider, INSERTION_TYPE_AT_END)`.
  In 1.1.1 `getQueueItems()` returns an empty list, so the bridge relies on
  `getPlaybackQueueIndex` / `getPlaybackQueueItemCount` and remembers what it queued; a stale item it
  cannot remove disables the lookahead so the normal prepare path is used.
- `onItemEnded` is not reported while the native queue continues; when `getCurrentItem` becomes the
  queued song the bridge adopts it (`NativePlaybackIntent.adoptContinuation`, no resume seek) and the
  snapshot carries `gaplessAdvanceCatalogId`. `AtvApp` then advances its queue without preparing.
- Emulator check: the next song was playing at 125 ms, 0.18 s after the handover, with no prepare.

## Verdict

Feasible with the public SDK API, as a contained change (one-item lookahead). Whether the result is truly
gapless, rather than a much shorter gap, can only be confirmed by listening on the car or a phone.

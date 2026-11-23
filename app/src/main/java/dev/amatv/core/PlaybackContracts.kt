package dev.amatv.core

import dev.amatv.integration.GaplessTransition
import dev.amatv.integration.PlaybackMediaSession
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.PlaybackSnapshot
import dev.amatv.integration.RecommendationProvider
import dev.amatv.model.Destination
import dev.amatv.model.Track

interface PlayerOperations : RecommendationProvider {
    val isCatalogConfigured: Boolean
    val widevineReady: Boolean
    suspend fun resolvePlayableTrack(track: Track): Track
    suspend fun preparePlayback(
        track: Track,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
        transition: GaplessTransition? = null,
    ): Long
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun skipToNext()
    fun skipToPrevious()
    suspend fun playQueuedSong(
        track: Track,
        transition: GaplessTransition? = null,
        confirmedLookaheadOnly: Boolean = false,
    ): Long?
    fun setLookahead(next: Track?, transition: GaplessTransition? = null)
    fun prefetchLoudness(track: Track)
    fun setFadeEnabled(enabled: Boolean)
    suspend fun stationSongs(stationId: String): List<Track>
    suspend fun prefetchLibraryDetails()
    fun setPlaybackListener(listener: ((PlaybackSnapshot) -> Unit)?)
    fun setSystemPlaybackCallbacks(callbacks: PlaybackMediaSession.Callbacks?)
    fun publishSystemPlayback(snapshot: PlaybackSnapshot, canSkipNext: Boolean, canSkipPrevious: Boolean)
}

interface PlaybackSessionStorage {
    val activeTab: Destination
    fun restoreCheckpointSession(): PlaybackSession?
    fun restoreSession(): PlaybackSession?
    fun saveSession(session: PlaybackSession, synchronous: Boolean = false)
    fun saveActiveTab(tab: Destination, synchronous: Boolean = false)
    fun loadStationHistory(): List<Track>
    fun saveStationHistory(songs: List<Track>)
}

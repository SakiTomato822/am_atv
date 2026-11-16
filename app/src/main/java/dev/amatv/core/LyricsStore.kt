package dev.amatv.core

import dev.amatv.integration.CatalogFailureReason
import dev.amatv.integration.LyricsLocalizationRequest
import dev.amatv.integration.LyricsRequestResult
import dev.amatv.integration.PlaybackFailureKind
import dev.amatv.integration.catalogFailureReason
import dev.amatv.integration.classifyPlaybackFailure
import dev.amatv.integration.requestLyricsWithRetry
import dev.amatv.integration.retryCatalogRequest
import dev.amatv.model.CatalogKind
import dev.amatv.model.LyricLine
import dev.amatv.model.Track
import dev.amatv.model.forDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LyricsLoadPhase {
    Idle,
    Loading,
    Ready,
    Unavailable,
    Failed,
}

data class LyricsState(
    val trackIdentity: String = "",
    val lines: List<LyricLine> = emptyList(),
    val resolvedVariant: String? = null,
    val phase: LyricsLoadPhase = LyricsLoadPhase.Idle,
) {
    val isLoading: Boolean get() = phase == LyricsLoadPhase.Loading
    val isLookupFailed: Boolean get() = phase == LyricsLoadPhase.Unavailable
    val isTransientFailure: Boolean get() = phase == LyricsLoadPhase.Failed
    val isReady: Boolean get() = phase == LyricsLoadPhase.Ready
}

interface LyricsSource {
    suspend fun resolvePlayableTrack(track: Track): Track
    suspend fun lyricsFor(track: Track, localization: LyricsLocalizationRequest): List<LyricLine>
    fun canWriteCache(): Boolean
    val cacheGeneration: Long
}

fun lyricsCacheKey(trackIdentity: String, variant: String): String = "$trackIdentity|$variant"

fun LyricsState.resolvedCacheEntries(): Map<String, List<LyricLine>> =
    resolvedVariant?.takeIf { phase == LyricsLoadPhase.Ready && lines.isNotEmpty() }
        ?.let { mapOf(lyricsCacheKey(trackIdentity, it) to lines) }.orEmpty()

internal fun lyricsLocalization(
    settings: SettingsState,
    appLanguageTag: String,
): LyricsLocalizationRequest {
    val effectiveLyricsLanguageTag = settings.lyricTranslationLanguageTag.ifBlank { appLanguageTag }
    return LyricsLocalizationRequest(
        languageTag = effectiveLyricsLanguageTag,
        pronunciationLanguageTag = settings.lyricPronunciationLanguageTag,
        includeTranslations = settings.lyricTranslationEnabled,
        includePronunciations = settings.lyricPronunciationEnabled,
    )
}

internal fun lyricsVariant(loc: LyricsLocalizationRequest): String =
    "${loc.languageTag}|trans=${loc.includeTranslations}" +
        "|pron=${loc.includePronunciations}" +
        "|pronLang=${loc.pronunciationLanguageTag}"

class LyricsStore(
    private val source: LyricsSource,
    private val settingsStore: SettingsStore,
    private val coroutineScope: CoroutineScope,
    private val appLanguageTagProvider: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = mutableState.asStateFlow()

    private var lyricsByTrackId = mutableMapOf<String, List<LyricLine>>()
    private val lyricsLookupFailures = mutableSetOf<String>()

    private var currentTrack: Track? = null
    private var isSignedIn: Boolean = false
    private var currentFetchJob: Job? = null

    private fun cacheLyrics(identity: String, variant: String, lines: List<LyricLine>) {
        val key = lyricsCacheKey(identity, variant)
        val recent = (lyricsByTrackId - key) + (key to lines)
        lyricsByTrackId = recent.entries.toList().takeLast(24).associate { it.toPair() }.toMutableMap()
    }

    fun setCurrentTrack(track: Track?, isSignedIn: Boolean, variant: String? = null) {
        this.currentTrack = track
        this.isSignedIn = isSignedIn

        if (track == null) {
            currentFetchJob?.cancel()
            currentFetchJob = null
            mutableState.value = LyricsState()
            return
        }

        val lookupIdentity = track.lyricsIdentity()
        val loc = lyricsLocalization(settingsStore.state.value, appLanguageTagProvider())
        val effectiveVariant = variant ?: lyricsVariant(loc)

        // Idempotency: if already ready for this track and variant, do nothing
        if (mutableState.value.trackIdentity == lookupIdentity &&
            mutableState.value.resolvedVariant == effectiveVariant &&
            mutableState.value.phase == LyricsLoadPhase.Ready
        ) {
            return
        }

        if (track.catalogId == null || track.catalogKind != CatalogKind.Song || !isSignedIn) {
            currentFetchJob?.cancel()
            currentFetchJob = null
            return
        }

        currentFetchJob?.cancel()
        currentFetchJob = coroutineScope.launch {
            val originalTrack = track
            if (mutableState.value.trackIdentity != lookupIdentity || mutableState.value.lines.isEmpty()) {
                mutableState.value = LyricsState(
                    trackIdentity = lookupIdentity,
                    lines = originalTrack.lyrics,
                    phase = LyricsLoadPhase.Loading,
                )
            }

            val resolvedTrack = runCatching {
                withContext(ioDispatcher) {
                    retryCatalogRequest({ source.resolvePlayableTrack(originalTrack) })
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                val phase = if (classifyPlaybackFailure(null, null, null, error) == PlaybackFailureKind.TrackUnavailable) {
                    LyricsLoadPhase.Unavailable
                } else {
                    LyricsLoadPhase.Failed
                }
                mutableState.value = mutableState.value.copy(phase = phase)
                logWarning("AMATV-Lyrics", "Library catalog resolution failed (${error.javaClass.simpleName})")
                return@launch
            }

            if (resolvedTrack.catalogId != originalTrack.catalogId) {
                onTrackIdentityResolved(originalTrack.lyricsIdentity(), resolvedTrack.lyricsIdentity())
                return@launch
            }

            val currentLyricsKey = lyricsCacheKey(lookupIdentity, effectiveVariant)
            val cached = lyricsByTrackId[currentLyricsKey]
            if (cached != null) {
                mutableState.value = LyricsState(
                    trackIdentity = lookupIdentity,
                    lines = cached,
                    resolvedVariant = effectiveVariant,
                    phase = LyricsLoadPhase.Ready,
                )
                return@launch
            }

            val baseline = (if (mutableState.value.trackIdentity == lookupIdentity) {
                mutableState.value.lines
            } else {
                track.lyrics
            }).map { it.forDisplay(showTranslation = false, showPronunciation = false) }

            if (!loc.enabled && baseline.isNotEmpty()) {
                cacheLyrics(lookupIdentity, effectiveVariant, baseline)
                mutableState.value = LyricsState(
                    trackIdentity = lookupIdentity,
                    lines = baseline,
                    resolvedVariant = effectiveVariant,
                    phase = LyricsLoadPhase.Ready,
                )
                return@launch
            }

            if (lookupIdentity in lyricsLookupFailures && baseline.isEmpty()) {
                mutableState.value = LyricsState(
                    trackIdentity = lookupIdentity,
                    lines = emptyList(),
                    resolvedVariant = effectiveVariant,
                    phase = LyricsLoadPhase.Unavailable,
                )
                return@launch
            }

            val lookupTrack = track
            mutableState.value = LyricsState(
                trackIdentity = lookupIdentity,
                lines = baseline,
                resolvedVariant = null,
                phase = LyricsLoadPhase.Loading,
            )

            logInfo("AMATV-Lyrics", "Loading current lyrics: $lookupIdentity source=${lookupTrack.resourceType}:${lookupTrack.resourceId}")

            val result = runCatching {
                withContext(ioDispatcher) {
                    requestLyricsWithRetry({ source.lyricsFor(lookupTrack, loc) })
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                LyricsRequestResult.RetryableFailure(dev.amatv.integration.LyricsFailureReason.Transient)
            }

            if (currentTrack?.lyricsIdentity() != lookupIdentity) return@launch

            when (result) {
                is LyricsRequestResult.Available -> {
                    cacheLyrics(lookupIdentity, effectiveVariant, result.lines)
                    lyricsLookupFailures.remove(lookupIdentity)
                    mutableState.value = LyricsState(
                        trackIdentity = lookupIdentity,
                        lines = result.lines,
                        resolvedVariant = effectiveVariant,
                        phase = LyricsLoadPhase.Ready,
                    )
                }
                LyricsRequestResult.Unavailable -> {
                    if (baseline.isEmpty()) {
                        lyricsLookupFailures.add(lookupIdentity)
                        mutableState.value = LyricsState(
                            trackIdentity = lookupIdentity,
                            lines = emptyList(),
                            resolvedVariant = effectiveVariant,
                            phase = LyricsLoadPhase.Unavailable,
                        )
                    } else {
                        cacheLyrics(lookupIdentity, effectiveVariant, baseline)
                        mutableState.value = LyricsState(
                            trackIdentity = lookupIdentity,
                            lines = baseline,
                            resolvedVariant = effectiveVariant,
                            phase = LyricsLoadPhase.Ready,
                        )
                    }
                }
                is LyricsRequestResult.RetryableFailure -> {
                    mutableState.value = LyricsState(
                        trackIdentity = lookupIdentity,
                        lines = baseline,
                        resolvedVariant = null,
                        phase = LyricsLoadPhase.Failed,
                    )
                    logWarning("AMATV-Lyrics", "Lyrics temporarily unavailable (${result.reason})")
                }
            }
        }
    }

    fun setPrefetchTargets(targets: List<Track>, isPlaying: Boolean) {
        val settings = settingsStore.state.value
        if (!isSignedIn || !isPlaying || !settings.cacheDiskEnabled || !settings.cachePrefetchNextTwo) {
            return
        }
        if (!source.canWriteCache()) return
        val cacheGen = source.cacheGeneration
        val loc = lyricsLocalization(settings, appLanguageTagProvider())
        val variant = lyricsVariant(loc)
        val currentIdentity = state.value.trackIdentity
        val currentLines = state.value.lines

        coroutineScope.launch {
            for (candidate in targets) {
                if (candidate.catalogKind != CatalogKind.Song) continue
                val candidateIdentity = candidate.lyricsIdentity()
                val candidateLyricsKey = lyricsCacheKey(candidateIdentity, variant)
                if ((currentLines.isNotEmpty() || currentIdentity in lyricsLookupFailures) &&
                    candidate.catalogId != null &&
                    (candidate.lyrics.isEmpty() || loc.enabled) &&
                    candidateLyricsKey !in lyricsByTrackId && candidateIdentity !in lyricsLookupFailures
                ) {
                    when (val result = runCatching {
                        withContext(ioDispatcher) {
                            requestLyricsWithRetry({ source.lyricsFor(candidate, loc) })
                        }
                    }.getOrNull()) {
                        is LyricsRequestResult.Available -> {
                            if (cacheGen == source.cacheGeneration) {
                                cacheLyrics(candidateIdentity, variant, result.lines)
                            }
                        }
                        LyricsRequestResult.Unavailable -> {
                            lyricsLookupFailures.add(candidateIdentity)
                        }
                        is LyricsRequestResult.RetryableFailure, null -> Unit
                    }
                }
            }
        }
    }

    fun retryCurrentLyrics() {
        val identity = currentTrack?.lyricsIdentity() ?: return
        lyricsLookupFailures.remove(identity)
        if (state.value.trackIdentity == identity) {
            mutableState.value = state.value.copy(phase = LyricsLoadPhase.Idle)
        }
        setCurrentTrack(currentTrack, isSignedIn)
    }

    fun withCachedLyrics(candidate: Track): Track {
        val identity = candidate.lyricsIdentity()
        val loc = lyricsLocalization(settingsStore.state.value, appLanguageTagProvider())
        val variant = lyricsVariant(loc)
        val cached = lyricsByTrackId[lyricsCacheKey(identity, variant)].orEmpty()
        val active = state.value.lines.takeIf {
            state.value.trackIdentity == identity && it.isNotEmpty()
        }.orEmpty()
        val lines = cached.ifEmpty { active }.ifEmpty { candidate.lyrics }
        return if (lines == candidate.lyrics) candidate else candidate.copy(lyrics = lines)
    }

    fun onTrackIdentityResolved(oldIdentity: String, newIdentity: String) {
        val currentLines = state.value.lines.takeIf { state.value.trackIdentity == oldIdentity }
        lyricsByTrackId = lyricsByTrackId.entries.associate { (key, lines) ->
            val migrated = if (key.startsWith("$oldIdentity|")) newIdentity + key.removePrefix(oldIdentity) else key
            migrated to lines
        }.toMutableMap()
        lyricsLookupFailures.remove(oldIdentity)
        if (state.value.trackIdentity == oldIdentity) {
            mutableState.value = state.value.copy(
                trackIdentity = newIdentity,
                lines = currentLines ?: state.value.lines,
            )
        }
    }

    fun onCacheCleared() {
        lyricsByTrackId = state.value.resolvedCacheEntries().toMutableMap()
        lyricsLookupFailures.clear()
        if (state.value.phase == LyricsLoadPhase.Failed) {
            mutableState.value = state.value.copy(phase = LyricsLoadPhase.Idle)
        }
    }
}

private fun logInfo(tag: String, msg: String) {
    try {
        android.util.Log.i(tag, msg)
    } catch (_: Throwable) {
        println("$tag: $msg")
    }
}

private fun logWarning(tag: String, msg: String) {
    try {
        android.util.Log.w(tag, msg)
    } catch (_: Throwable) {
        System.err.println("$tag: $msg")
    }
}

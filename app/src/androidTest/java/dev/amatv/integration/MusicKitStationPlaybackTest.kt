package dev.amatv.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicKitStationPlaybackTest {
    private val stationId = "ra.1498157166"

    @Test fun stationUsesRadioContainerTypeAndPreservesItsCatalogId() {
        val station = stationTrack()
        val provider = createCatalogPlaybackQueueProvider(station, CapturingQueueBuilder::class.java)
            as CapturedQueueProvider

        assertEquals("containers", provider.requestMethod)
        assertEquals(MUSIC_KIT_RADIO_STATION_CONTAINER_TYPE, provider.resourceType)
        assertEquals(listOf(stationId), provider.resourceIds)
    }

    @Test fun bundledOfficialAarBuildsItsCatalogProviderForStationContainer() {
        val builderClass = Class.forName(
            "com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider\$Builder",
        )
        val provider = createCatalogPlaybackQueueProvider(stationTrack(), builderClass)

        assertEquals(
            "com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider",
            provider.javaClass.name,
        )
        assertTrue(radioStationContainerType(
            Class.forName("com.apple.android.music.playback.model.MediaContainerType"),
        ) == MUSIC_KIT_RADIO_STATION_CONTAINER_TYPE)
    }

    private fun stationTrack() = Track(
        id = "apple:$stationId",
        title = "Apple Music Country",
        artist = "Apple Music",
        album = "Radio",
        durationMs = 48_000L,
        palette = emptyList(),
        lyrics = emptyList(),
        catalogId = stationId,
        catalogKind = CatalogKind.Station,
    )
}

class CapturingQueueBuilder {
    private var requestMethod: String = ""
    private var resourceType: Int = -1
    private var resourceIds: Array<String> = emptyArray()

    fun items(type: Int, ids: Array<String>): CapturingQueueBuilder = apply {
        requestMethod = "items"
        resourceType = type
        resourceIds = ids
    }

    fun containers(type: Int, ids: Array<String>): CapturingQueueBuilder = apply {
        requestMethod = "containers"
        resourceType = type
        resourceIds = ids
    }

    fun build() = CapturedQueueProvider(requestMethod, resourceType, resourceIds.toList())
}

data class CapturedQueueProvider(
    val requestMethod: String,
    val resourceType: Int,
    val resourceIds: List<String>,
)

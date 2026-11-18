package dev.amatv.flyme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.amatv.data.MockCatalog
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioDetailTest {
    @Test
    fun detailLayoutMatchesAllFiveTiers() {
        val tiers = listOf(FolioTier.Narrow, FolioTier.Short, FolioTier.Medium, FolioTier.Wide, FolioTier.Ultrawide)
        val layouts = tiers.map(::folioDetailLayout)

        assertEquals(listOf(116, 104, 188, 220, 170), layouts.map { it.coverSize })
        assertEquals(listOf(32, 32, 46, 60, 52), layouts.map { it.titleSize })
        assertEquals(listOf(1, 1, 1, 2, 3), layouts.map { it.collectionColumns })
        assertEquals(listOf(true, false, false, false, false), layouts.map { it.buttonsBelowCover })
        assertEquals(
            listOf(FolioArtistHeader.Top, FolioArtistHeader.Left, FolioArtistHeader.Top,
                FolioArtistHeader.Top, FolioArtistHeader.LeftWithSimilarRail),
            layouts.map { it.artistHeader },
        )
        assertEquals(listOf(116, 104, 188, 220, 200), layouts.map { it.artistCoverSize })
        assertEquals(listOf(32, 32, 46, 60, 38), layouts.map { it.artistTitleSize })
        assertEquals(listOf(1, 1, 2, 2, 2), layouts.map { it.topSongColumns })
    }

    @Test
    fun shortArtistHeaderFitsLandscapeAboveCapsule() {
        val windowHeight = 411.dp
        val tier = tierOf(914.dp, windowHeight)
        assertEquals(FolioTier.Short, tier)
        val layout = folioDetailLayout(tier)
        val spec = specForTier(tier)
        val contentHeight = with(Density(420f / 160f)) {
            layout.artistCoverSize.dp + layout.artistTitleLineHeight.toDp() +
                folioShortArtistMetaLineHeight.toDp() + folioDetailButtonHeight + folioShortArtistItemGap * 3
        }
        val statusTop = 24.dp
        val navBottom = 16.dp
        val bodyTop = statusTop + folioDetailTopMargin + spec.ib + folioShortArtistBodyGap
        val availableHeight = windowHeight - bodyTop - folioShortArtistBottomPadding(spec, navBottom)

        assertEquals(215.6f, contentHeight.value, 0.01f)
        assertEquals(227.dp, availableHeight)
        assertTrue("Artist header needs $contentHeight, available $availableHeight", contentHeight <= availableHeight)
        assertTrue(contentHeight <= windowHeight - bodyTop - folioShortArtistBottomPadding(spec, 24.dp))
    }

    @Test
    fun onlyUltrawideMovesSimilarArtistsOutOfTheMusicColumn() {
        for (tier in FolioTier.entries) {
            val layout = folioDetailLayout(tier)
            assertEquals(tier == FolioTier.Ultrawide, layout.placesArtistShelfInRail("artist-123-similar-artists"))
            assertFalse(layout.placesArtistShelfInRail("artist-123-full-albums"))
        }
    }

    @Test
    fun songColumnsKeepOriginalQueueIndexes() {
        assertEquals(listOf(0..5), folioDetailColumnRanges(6, 1))
        assertEquals(listOf(0..2, 3..4), folioDetailColumnRanges(5, 2))
        assertEquals(listOf(0..3, 4..7, 8..9), folioDetailColumnRanges(10, 3))
        assertEquals(listOf(0), folioDetailColumnRanges(1, 3).flatten())
        assertTrue(folioDetailColumnRanges(0, 3).flatten().isEmpty())
    }

    @Test
    fun detailStackReturnsThroughRepeatedEntriesToRoot() {
        val entry = MockCatalog.tracks.first()
        val album = entry.copy(id = "album", catalogKind = CatalogKind.Album)
        val artist = entry.copy(id = "artist", catalogKind = CatalogKind.Artist)
        val root = emptyList<Track>()
        val stack = root + album + artist + album

        assertEquals(album, stack.last())
        assertEquals(artist, popFolioDetail(stack).last())
        assertEquals(listOf(album), popFolioDetail(popFolioDetail(stack)))
        assertEquals(root, popFolioDetail(listOf(album)))
        assertEquals(root, popFolioDetail(root))
    }

    @Test
    fun publishesMemoryBeforeRefreshAndSkipsDiskForCompleteMemory() = runBlocking {
        val events = mutableListOf<String>()
        loadFolioDetail(
            cached = "memory",
            saved = { error("Complete memory should skip disk") },
            fetch = { events += "fetch"; "fresh" },
            complete = { true },
            publish = { events += "${it.value}:${it.loading}" },
        )
        assertEquals(listOf("memory:true", "fetch", "fresh:false"), events)
    }

    @Test
    fun diskSnapshotIsPublishedBeforeNetwork() = runBlocking {
        val events = mutableListOf<String>()
        loadFolioDetail(
            cached = null,
            saved = { events += "disk"; "saved" },
            fetch = { events += "fetch"; "fresh" },
            complete = { true },
            publish = { events += "${it.value}:${it.loading}" },
        )
        assertEquals(listOf("null:true", "disk", "saved:true", "fetch", "fresh:false"), events)
    }

    @Test
    fun emptyFetchedListIsACompletedPage() = runBlocking {
        val states = mutableListOf<FolioDetailData<List<String>>>()
        loadFolioDetail(null, { null }, { emptyList() }, { true }, states::add)
        assertEquals(
            listOf(FolioDetailData(), FolioDetailData(emptyList<String>(), loading = false)),
            states,
        )
    }

    @Test
    fun keepsCompleteDiskSnapshotWhenArtistRefreshIsPartial() = runBlocking {
        val states = mutableListOf<FolioDetailData<String>>()
        loadFolioDetail("partial", { "saved" }, { "partial" }, { it != "partial" }, states::add)
        assertEquals(
            listOf(FolioDetailData("partial"), FolioDetailData("saved"), FolioDetailData("saved", loading = false)),
            states,
        )
    }

    @Test
    fun partialArtistWithoutSnapshotRemainsVisibleAndRetryable() = runBlocking {
        val states = mutableListOf<FolioDetailData<String>>()
        loadFolioDetail(null, { null }, { "partial" }, { false }, states::add)
        assertEquals(FolioDetailData("partial", loading = false, failed = true), states.last())
    }

    @Test
    fun refreshFailureLeavesSavedDataAndReportsRetryableError() = runBlocking {
        val states = mutableListOf<FolioDetailData<String>>()
        loadFolioDetail(null, { "saved" }, { error("offline") }, { true }, states::add)
        assertEquals(FolioDetailData("saved", loading = false, failed = true), states.last())
    }

    @Test
    fun initialRequestFailureCanBeRetried() = runBlocking {
        val states = mutableListOf<FolioDetailData<String>>()
        loadFolioDetail(null, { null }, { error("offline") }, { true }, states::add)
        assertTrue(states.last().failed)
        assertNull(states.last().value)

        loadFolioDetail(null, { null }, { "retried" }, { true }, states::add)
        assertFalse(states.last().failed)
        assertEquals("retried", states.last().value)
    }

    @Test
    fun cancellationDoesNotPublishAnError() = runBlocking {
        val states = mutableListOf<FolioDetailData<String>>()
        try {
            loadFolioDetail("memory", { null }, { throw CancellationException("closed") }, { true }, states::add)
            throw AssertionError("Cancellation should propagate")
        } catch (_: CancellationException) {
            assertEquals(listOf(FolioDetailData("memory")), states)
        }
    }
}

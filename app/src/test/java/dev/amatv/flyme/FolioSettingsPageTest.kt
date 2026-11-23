package dev.amatv.flyme

import dev.amatv.R
import org.junit.Assert.assertEquals
import org.junit.Test

class FolioSettingsPageTest {
    @Test
    fun sidebarLabelsResolveToTheirSettingsGroupsInListOrder() {
        val expected = listOf(
            R.string.app_account to "grp-account",
            R.string.appearance_title to "grp-look",
            R.string.nav_now_playing to "grp-play",
            R.string.player_lyrics to "grp-lyr",
            R.string.flyme_media_status to "grp-car",
            R.string.browse_cache_title to "grp-store",
            R.string.flyme_app_name to "grp-about",
            R.string.diagnostics_gapless_history to "grp-gapless",
        )

        val sections = FolioSettingsSection.entries
        assertEquals(expected, sections.map { it.labelRes to it.itemKey })
        assertEquals(sections.indices.toList(), sections.map { settingsSectionIndex(it.itemKey) })
    }
}

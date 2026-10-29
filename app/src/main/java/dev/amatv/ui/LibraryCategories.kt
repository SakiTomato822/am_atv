package dev.amatv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R

internal enum class LibraryCategory(val shelfId: String, val title: Int) {
    Playlists("lib-playlists", R.string.app_library_playlists),
    Artists("lib-artists", R.string.app_library_artists),
    Albums("lib-albums", R.string.app_library_albums),
    Songs("lib-songs", R.string.app_library_songs),
}

@Composable
internal fun LibraryCategories(
    selected: LibraryCategory,
    onSelect: (LibraryCategory) -> Unit,
    firstFocus: FocusRequester?,
    contentFocus: FocusRequester,
    navigationFocus: FocusRequester?,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LibraryCategory.entries.forEach { category ->
            FocusSurface(
                onClick = { onSelect(category) },
                modifier = Modifier.weight(1f).height(56.dp)
                    .automationId("library-category-${category.name.lowercase()}")
                    .then(if (category == selected && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                    .focusProperties {
                        down = contentFocus
                        navigationFocus?.let { up = it }
                    },
                cornerRadius = 14.dp,
                padding = PaddingValues(horizontal = 16.dp),
                background = Color.White.copy(alpha = if (category == selected) 0.18f else 0.06f),
                focusedBackground = Color.White.copy(alpha = 0.24f),
            ) {
                Text(stringResource(category.title), fontSize = 19.sp,
                    fontWeight = if (category == selected) FontWeight.Bold else FontWeight.Medium,
                    color = Color.White)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}

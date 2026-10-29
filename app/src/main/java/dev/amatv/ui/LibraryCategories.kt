package dev.amatv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
            val isSelected = category == selected
            val background by animateColorAsState(
                targetValue = Color.White.copy(alpha = if (isSelected) 0.18f else 0.06f),
                animationSpec = tween(
                    MotionTokens.BrowseCategoryChangeMillis,
                    easing = MotionTokens.BrowseContentEasing,
                ),
                label = "library-category-background",
            )
            val focusedBackground by animateColorAsState(
                targetValue = Color.White.copy(alpha = if (isSelected) 0.24f else 0.14f),
                animationSpec = tween(
                    MotionTokens.BrowseCategoryChangeMillis,
                    easing = MotionTokens.BrowseContentEasing,
                ),
                label = "library-category-focused-background",
            )
            val textColor by animateColorAsState(
                targetValue = Color.White.copy(alpha = if (isSelected) 1f else 0.78f),
                animationSpec = tween(
                    MotionTokens.BrowseCategoryChangeMillis,
                    easing = MotionTokens.BrowseContentEasing,
                ),
                label = "library-category-label",
            )
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
                background = background,
                focusedBackground = focusedBackground,
            ) {
                Text(stringResource(category.title), fontSize = 19.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}

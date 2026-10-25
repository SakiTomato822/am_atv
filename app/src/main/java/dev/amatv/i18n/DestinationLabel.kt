package dev.amatv.i18n

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.amatv.R
import dev.amatv.model.Destination

@Composable
fun destinationLabel(destination: Destination): String = stringResource(when (destination) {
    Destination.Home -> R.string.nav_home
    Destination.Browse -> R.string.nav_browse
    Destination.Sing -> R.string.nav_sing
    Destination.Videos -> R.string.nav_videos
    Destination.Radio -> R.string.nav_radio
    Destination.Library -> R.string.nav_library
    Destination.Account -> R.string.nav_account
    Destination.NowPlaying -> R.string.nav_now_playing
    Destination.Search -> R.string.nav_search
})

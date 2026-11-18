package dev.amatv.flyme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.BuildConfig
import dev.amatv.R
import dev.amatv.integration.MusicConnectionState

@Composable
internal fun FolioAccountOverlay(
    visible: Boolean,
    connectionState: MusicConnectionState,
    setupMessage: String?,
    statusTop: Dp,
    navBottom: Dp,
    onClose: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRefresh: () -> Unit,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = tween(FolioMotion.SLOW_MS, easing = FolioMotion.PageEasing),
            initialOffsetX = { it },
        ) + fadeIn(tween(FolioMotion.FAST_MS)),
        exit = slideOutHorizontally(
            animationSpec = tween(FolioMotion.SLOW_MS, easing = FolioMotion.PageEasing),
            targetOffsetX = { it },
        ) + fadeOut(tween(FolioMotion.FAST_MS)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.bg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = (tierSpec.px - 8.dp).coerceAtLeast(0.dp),
                        end = tierSpec.pr,
                        top = statusTop + 10.dp,
                        bottom = 4.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FolioCircleButton(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.browse_back),
                    onClick = onClose,
                    outlined = false,
                )
                Text(
                    text = stringResource(R.string.app_account),
                    color = palette.fg,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Light,
                    fontSize = tierSpec.pvf,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        start = tierSpec.px,
                        end = tierSpec.pr,
                        top = 8.dp,
                        bottom = navBottom + 16.dp,
                    )
                    .widthIn(max = 760.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.padding(top = 14.dp, bottom = 18.dp, start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFFF5C72), Color(0xFF7A5CFF)),
                                ),
                            ),
                    )
                    Column {
                        Text(
                            text = "Apple Music",
                            color = palette.fg,
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                        val statusText = when (connectionState) {
                            MusicConnectionState.SignedIn -> stringResource(R.string.browse_apple_connected)
                            MusicConnectionState.ReadyToSignIn -> stringResource(R.string.browse_apple_sign_in)
                            MusicConnectionState.Demo -> setupMessage ?: stringResource(R.string.browse_apple_sign_in)
                        }
                        Text(
                            text = statusText,
                            color = palette.fg2,
                            fontFamily = fontFamily,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.app_account),
                    color = palette.accent,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.browse_refresh_apple),
                        color = palette.fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                    )
                    FolioChip(
                        label = stringResource(R.string.browse_reload),
                        selected = false,
                        onClick = {
                            onRefresh()
                            onClose()
                        },
                    )
                }

                Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rb))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Storefront",
                        color = palette.fg,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = BuildConfig.APPLE_MUSIC_STOREFRONT.uppercase(),
                        color = palette.fg2,
                        fontFamily = fontFamily,
                        fontSize = 14.sp,
                    )
                }

                Spacer(Modifier.height(14.dp))
                when (connectionState) {
                    MusicConnectionState.SignedIn -> {
                        Text(
                            text = stringResource(R.string.browse_sign_out_tv),
                            color = palette.danger,
                            fontFamily = fontFamily,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .clickable {
                                    onSignOut()
                                    onClose()
                                }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                        )
                    }
                    MusicConnectionState.ReadyToSignIn -> {
                        FolioChip(
                            label = stringResource(R.string.browse_sign_in_tv),
                            selected = true,
                            onClick = {
                                onClose()
                                onSignIn()
                            },
                        )
                    }
                    MusicConnectionState.Demo -> Unit
                }
            }
        }
    }
}

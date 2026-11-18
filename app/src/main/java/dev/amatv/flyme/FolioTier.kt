package dev.amatv.flyme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Five responsive layout tiers matching H5 prototype `flyme15-app.html`. */
enum class FolioTier(val code: Char) {
    Ultrawide('u'),
    Narrow('c'),
    Short('m'),
    Medium('t'),
    Wide('e'),
}

const val MIN_FOLIO_DENSITY: Float = 1.5f

/**
 * Low-density car head units (e.g. 1920x1080 @ 160 dpi, density 1.0) render at 1.5 density so
 * typography and spacing match the 1280x720 H5 design. High-density phones/tablets keep their own.
 */
fun folioRenderDensity(systemDensity: Float): Float =
    if (systemDensity < MIN_FOLIO_DENSITY) MIN_FOLIO_DENSITY else systemDensity

fun folioLayoutDp(
    windowWidthPx: Float,
    windowHeightPx: Float,
    systemDensity: Float,
): Pair<Float, Float> {
    val targetDensity = folioRenderDensity(systemDensity)
    return (windowWidthPx / targetDensity) to (windowHeightPx / targetDensity)
}

/**
 * Pure window-size classifier matching H5 `tierOf(w, h)`:
 * `w / h >= 2.6 -> u; w < 600 -> c; h < 480 -> m; w < 840 -> t; else -> e`.
 */
fun tierOf(widthDp: Float, heightDp: Float): FolioTier = when {
    heightDp > 0f && widthDp / heightDp >= 2.6f -> FolioTier.Ultrawide
    widthDp < 600f -> FolioTier.Narrow
    heightDp < 480f -> FolioTier.Short
    widthDp < 840f -> FolioTier.Medium
    else -> FolioTier.Wide
}

fun tierOf(width: Dp, height: Dp): FolioTier = tierOf(width.value, height.value)

@Immutable
data class FolioTierSpec(
    val tier: FolioTier,
    val px: Dp,
    val pr: Dp,
    val ib: Dp,
    val capw: Dp,
    val pvh: Dp,
    val pvf: TextUnit,
    val pvg: Dp,
    val secf: TextUnit,
    val cw: Dp,
    val gw: Dp,
    val rowh: Dp,
    val liw: Dp,
    val lic: Dp,
    val lif: TextUnit,
    val lstGap: Dp,
    val capsp: Dp,
) {
    /** Wide and Ultrawide render the circular cover dot in the top bar instead of the bottom capsule. */
    val usesBottomCapsule: Boolean get() = capsp > 0.dp

    /** Settings category navigation sidebar is shown only on Wide and Ultrawide tiers. */
    val showsSettingsSidebar: Boolean get() = tier == FolioTier.Wide || tier == FolioTier.Ultrawide
}

fun specForTier(tier: FolioTier): FolioTierSpec = when (tier) {
    FolioTier.Narrow -> FolioTierSpec(
        tier = tier,
        px = 16.dp,
        pr = 14.dp,
        ib = 44.dp,
        capw = 250.dp,
        pvh = 56.dp,
        pvf = 38.sp,
        pvg = 24.dp,
        secf = 22.sp,
        cw = 128.dp,
        gw = 100.dp,
        rowh = 56.dp,
        liw = 150.dp,
        lic = 48.dp,
        lif = 18.sp,
        lstGap = 14.dp,
        capsp = 84.dp,
    )
    FolioTier.Short -> FolioTierSpec(
        tier = tier,
        px = 28.dp,
        pr = 24.dp,
        ib = 44.dp,
        capw = 290.dp,
        pvh = 52.dp,
        pvf = 34.sp,
        pvg = 30.dp,
        secf = 22.sp,
        cw = 120.dp,
        gw = 118.dp,
        rowh = 52.dp,
        liw = 300.dp,
        lic = 52.dp,
        lif = 20.sp,
        lstGap = 32.dp,
        capsp = 84.dp,
    )
    FolioTier.Medium -> FolioTierSpec(
        tier = tier,
        px = 32.dp,
        pr = 28.dp,
        ib = 50.dp,
        capw = 300.dp,
        pvh = 64.dp,
        pvf = 44.sp,
        pvg = 34.dp,
        secf = 26.sp,
        cw = 150.dp,
        gw = 140.dp,
        rowh = 58.dp,
        liw = 320.dp,
        lic = 64.dp,
        lif = 24.sp,
        lstGap = 32.dp,
        capsp = 84.dp,
    )
    FolioTier.Wide -> FolioTierSpec(
        tier = tier,
        px = 48.dp,
        pr = 40.dp,
        ib = 56.dp,
        capw = 340.dp,
        pvh = 74.dp,
        pvf = 48.sp,
        pvg = 40.dp,
        secf = 28.sp,
        cw = 168.dp,
        gw = 150.dp,
        rowh = 62.dp,
        liw = 340.dp,
        lic = 72.dp,
        lif = 26.sp,
        lstGap = 32.dp,
        capsp = 0.dp,
    )
    FolioTier.Ultrawide -> FolioTierSpec(
        tier = tier,
        px = 56.dp,
        pr = 48.dp,
        ib = 56.dp,
        capw = 360.dp,
        pvh = 70.dp,
        pvf = 46.sp,
        pvg = 44.dp,
        secf = 26.sp,
        cw = 160.dp,
        gw = 150.dp,
        rowh = 60.dp,
        liw = 360.dp,
        lic = 64.dp,
        lif = 24.sp,
        lstGap = 32.dp,
        capsp = 0.dp,
    )
}

internal val LocalFolioTierSpec = staticCompositionLocalOf { specForTier(FolioTier.Wide) }

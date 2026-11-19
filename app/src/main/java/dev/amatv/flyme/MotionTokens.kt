package dev.amatv.flyme

import androidx.compose.animation.core.CubicBezierEasing

/** Shared player and lyric motion timings, geometry and easing curves. */
internal object MotionTokens {
    // A.java (Syllable/Karaoke line scale & deactivation sweep)
    const val KaraokeLineActivateDelayMillis = 0
    const val KaraokeLineActivateMillis = 800
    const val KaraokeLineDeactivateDelayMillis = 150
    const val KaraokeLineDeactivateMillis = 800
    const val KaraokeHighlightDeactivateDelayMillis = 0
    const val KaraokeHighlightDeactivateMillis = 150 // BR.isUsedInSwipingLists = 150
    const val KaraokeHighlightActivateMillis = 150

    // U0.java (Line-synced line scale & color fade)
    const val LineSyncActivateDelayMillis = 150
    const val LineSyncColorActivateMillis = 150
    const val LineSyncScaleActivateMillis = 500
    const val LineSyncDeactivateDelayMillis = 150
    const val LineSyncColorDeactivateMillis = 350
    const val LineSyncScaleDeactivateMillis = 600

    // A.java (Word/syllable lift, gradient, and long-emphasis character wave)
    const val KaraokeRushGradientMillis = 500

    const val KaraokeLineNormalScale = 0.98f // lyrics_line_scale_normal
    const val KaraokeLineHighlightScale = 1f // lyrics_line_scale_highlight
    const val KaraokePrimaryAlpha = 0.94f // player_vibrant_primary (white_alpha_94)
    const val KaraokeSecondaryAlpha = 0.45f // player_vibrant_secondary (white_alpha_45)
    const val KaraokeBgVocalLitAlpha = 0.35f // white_alpha_35 (bg vocals, pronunciation, translation)
    const val KaraokeTertiaryAlpha = 0.18f // player_vibrant_tertiary (white_alpha_18)
    const val KaraokeDuetLineWidthPercent = 0.85f // lyrics_karaoke_duet_line_width_percent
    const val KaraokeWordLiftDp = -2f // lyrics_karaoke_syllable_lift
    const val KaraokeGradientFeatherDp = 30f // lyrics_karaoke_gradient_feather_width
    const val KaraokeMainFontSizeSp = 34f
    const val KaraokeEmphasisShadowDp = 5f // lyrics_karaoke_emphasis_shadow_radius
    const val KaraokeEmphasisShadowAlpha = 0.5f // round(127.5f * fraction) / 255f
    const val KaraokeSpringDampingRatio = 0.93f
    const val KaraokeSpringStiffness = 25f

    // A.java & U0.java (Instrumental interlude 3-dot animation)
    const val InstrumentalEnterMillis = 750
    const val InstrumentalEnterStaggerMillis = 50
    const val InstrumentalPulsePeriodMillis = 4_000
    const val InstrumentalPulseMaxScale = 1.2f
    const val InstrumentalExitExpandMillis = 750
    const val InstrumentalExitShrinkMillis = 250
    const val InstrumentalExitMinScale = 0.5f

    // Interpolators from com.apple.android.music.utils.E and A.java
    /** `A.f38940i0 = new PathInterpolator(0.2f, 0.0f, 0.23f, 1.3f)` (overshooting emphasis curve). */
    val KaraokeEmphasisEasing = CubicBezierEasing(0.2f, 0.0f, 0.23f, 1.3f)
    /** `E.f43134h = new PathInterpolator(0.2f, 0.06f, 0.0f, 1.0f)` (line scale curve). */
    val LineScaleEasing = CubicBezierEasing(0.2f, 0.06f, 0.0f, 1.0f)
    /** `E.f43137k = new PathInterpolator(0.39f, 0.575f, 0.565f, 1.0f)` (`U0` color fade curve). */
    val KaraokeGradientExitEasing = CubicBezierEasing(0.39f, 0.575f, 0.565f, 1f)
    /** `A.java:782` / `U0.java:564`: `new PathInterpolator(0.0f, 0.25f, 1.0f, 0.58f)`. */
    val InstrumentalDotTintEasing = CubicBezierEasing(0.0f, 0.25f, 1.0f, 0.58f)
    /** `A.java:863` / `U0.java:601`: `new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)`. */
    val InstrumentalExitExpandEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)
    /** `A.java:887` / `U0.java:623`: `new PathInterpolator(0.25f, 0.0f, 1.0f, 0.2f)`. */
    val InstrumentalExitShrinkEasing = CubicBezierEasing(0.25f, 0.0f, 1.0f, 0.2f)
}

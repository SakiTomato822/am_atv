package dev.amatv.flyme

internal data class BackgroundVocalTypography(
    val fontSizeSp: Float,
    val lineHeightSp: Float,
    val pronunciationSizeSp: Float,
    val pronunciationLineHeightSp: Float,
    val translationSizeSp: Float,
    val translationLineHeightSp: Float,
    val localizationGapDp: Float,
)

/** Background-vocal sizes for timed and static lyrics. */
internal fun backgroundVocalTypography(
    isStaticMode: Boolean,
    compactMainLocalization: Boolean,
    compactBackgroundLocalization: Boolean,
): BackgroundVocalTypography {
    val compactSubtext = compactMainLocalization || compactBackgroundLocalization
    if (isStaticMode) {
        return BackgroundVocalTypography(
            fontSizeSp = if (compactMainLocalization) 13.5f else 14.5f,
            lineHeightSp = if (compactMainLocalization) 17f else 19f,
            pronunciationSizeSp = if (compactSubtext) 10.5f else 11.5f,
            pronunciationLineHeightSp = if (compactSubtext) 13f else 15f,
            translationSizeSp = if (compactSubtext) 11.5f else 12.5f,
            translationLineHeightSp = if (compactSubtext) 15f else 16f,
            localizationGapDp = if (compactSubtext) 2f else 4f,
        )
    }
    return BackgroundVocalTypography(
        fontSizeSp = if (compactMainLocalization) 18.5f else 20f,
        lineHeightSp = if (compactMainLocalization) 23f else 25f,
        pronunciationSizeSp = if (compactSubtext) 12f else 13f,
        pronunciationLineHeightSp = if (compactSubtext) 15f else 17f,
        translationSizeSp = if (compactSubtext) 14f else 15f,
        translationLineHeightSp = if (compactSubtext) 18f else 19f,
        localizationGapDp = if (compactSubtext) 2f else 4f,
    )
}

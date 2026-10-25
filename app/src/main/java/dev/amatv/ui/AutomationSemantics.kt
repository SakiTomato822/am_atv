package dev.amatv.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/** Stable, invisible accessibility IDs for real-device regression and frame benchmarks. */
internal fun Modifier.automationId(id: String): Modifier =
    semantics { testTagsAsResourceId = true }.testTag(id)

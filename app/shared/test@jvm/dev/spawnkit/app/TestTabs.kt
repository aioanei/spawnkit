package dev.spawnkit.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/**
 * Opens the Add tab on phones, where the assistant's tab opens first (C13b). Wide screens always show the palette, so
 * nothing happens there.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.showPalette() {
    if (onAllNodesWithTag("tab:Add").fetchSemanticsNodes().isNotEmpty()) onNodeWithTag("tab:Add").performClick()
}

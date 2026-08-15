package com.thrive.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import org.junit.Test

/**
 * Major-flow coverage for the Pantry tab: add an item through the search
 * sheet and verify it lands in the pantry list. Works at any font scale.
 */
class PantryFlowTest : ThriveUiTest() {

    @Test
    fun addItemThroughSearchSheetLandsInPantry() {
        // Navigate to the Pantry tab from the bottom navigation.
        rule.onNodeWithText("Pantry").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Add items").fetchSemanticsNodes().isNotEmpty()
        }

        // Open the add-item sheet. The FAB is overlaid outside the list, so
        // it is always composed — no scroll needed, even at 2.0x font scale.
        rule.onNodeWithText("Add items").performClick()
        rule.onNodeWithText("Add to your pantry").assertIsDisplayed()

        // Search for a catalog item and select the exact match.
        rule.onNodeWithText("Search items…").performTextInput("milk")
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Milk").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Milk").performClick()

        // Confirm the add — the item must appear in the pantry.
        rule.onNodeWithText("Add to your pantry").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Milk").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Milk").assertExists()
    }
}

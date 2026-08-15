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
 * Major-flow coverage for the Savings tab: the feed renders with the honest
 * availability header, search works, and a query with no matches shows the
 * explicit empty state rather than fabricated results. Interactions scroll
 * lazily-composed items into view first, so the flow is verified at any font
 * scale (1.0x through 2.0x).
 */
class SavingsFlowTest : ThriveUiTest() {

    @Test
    fun feedRendersWithVerifiedDealsAndSearchEmptyState() {
        // Header copy is unique to the Savings tab's start screen.
        rule.onNodeWithText("Good morning! Here's what's on sale today.")
            .assertIsDisplayed()

        // Scroll the search field into view (LazyColumn may not compose it at
        // large font scales), then type a nonsense query.
        rule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("Search products, stores, or brands"))
        rule.onNodeWithText("Search products, stores, or brands").performTextInput("zzzznothing")
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("No deals match").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("No deals match").assertExists()
    }

    @Test
    fun modeTabsAndCategoryChipsAreReachable() {
        rule.onNodeWithText("Good morning! Here's what's on sale today.")
            .assertIsDisplayed()
        // Category chips (canonical set) — the "All" chip is always present.
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("All"))
        rule.onNodeWithText("All").assertExists()
        // Stores mode is switchable from the mode tabs; the intro copy mentions
        // "nearest" in both the located and unlocated variants.
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Stores"))
        rule.onNodeWithText("Stores").performClick()
        // The intro moves above the current scroll position after the mode
        // switch, so scroll the list back to find it (LazyColumn scrolls until
        // the node matches, composing items as it goes).
        rule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("nearest", substring = true, ignoreCase = true))
    }
}

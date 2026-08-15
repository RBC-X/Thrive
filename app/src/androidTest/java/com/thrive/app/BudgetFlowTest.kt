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
 * Major-flow coverage for the Budget tab: answer the two onboarding questions
 * and confirm the shopping list appears with the entered budget. Scrolls to
 * the CTA so the flow is verified at any font scale.
 */
class BudgetFlowTest : ThriveUiTest() {

    @Test
    fun onboardingAnswersBudgetAndShowsList() {
        rule.onNodeWithText("Budget").performClick()
        rule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText("How much can you spend?"))
        rule.onNodeWithText("How much can you spend?").assertIsDisplayed()

        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("e.g. 75"))
        rule.onNodeWithText("e.g. 75").performTextInput("75")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Build my shopping list"))
        rule.onNodeWithText("Build my shopping list").performClick()

        // The shopping-list view shows the entered budget for the people count.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Shopping for 1 · $75.00 budget")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Shopping for 1 · $75.00 budget").assertIsDisplayed()
    }
}

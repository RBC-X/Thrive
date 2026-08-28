package com.thrive.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thrive.app.data.SyncFetcher
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.model.BudgetCadence
import com.thrive.app.data.model.HouseholdProfile
import com.thrive.app.data.remote.HttpResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HouseholdDataTest {
    private lateinit var repo: ThriveRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("household_${System.nanoTime()}", Context.MODE_PRIVATE)
        repo = ThriveRepository(
            context,
            SettingsStore(prefs),
            SyncFetcher { _, _ -> HttpResult(503, "", null) },
        )
    }

    @Test
    fun householdProfileSerializesAndNormalizes() {
        val input = HouseholdProfile(
            budgetAmount = 125.50,
            budgetCadence = BudgetCadence.MONTHLY,
            householdSize = 3,
            appliances = setOf("Oven", "Air fryer", "Unknown machine"),
        )
        val json = Json.encodeToString(HouseholdProfile.serializer(), input)
        val decoded = Json.decodeFromString(HouseholdProfile.serializer(), json).normalized()

        assertEquals(125.50, decoded.budgetAmount, 0.001)
        assertEquals(BudgetCadence.MONTHLY, decoded.budgetCadence)
        assertEquals(3, decoded.householdSize)
        assertEquals(setOf("Oven", "Air fryer"), decoded.appliances)
    }

    @Test
    fun completingOnboardingPersistsACompleteProfile() {
        assertFalse(repo.isOnboardingComplete())
        repo.completeOnboarding(
            HouseholdProfile(
                budgetAmount = 80.0,
                householdSize = 2,
                appliances = setOf("Microwave"),
            ),
        )

        val saved = repo.loadHouseholdProfile()
        assertTrue(repo.isOnboardingComplete())
        assertEquals(ThriveRepository.CURRENT_ONBOARDING_VERSION, saved.onboardingVersion)
        assertTrue((saved.onboardingCompletedAt ?: 0L) > 0L)
    }

    @Test
    fun firstCatalogBecomesBaselineAndOnlyLaterIdsAreNew() {
        assertEquals(emptySet<String>(), repo.unseenDealIds(listOf("a", "b"), "feed-1"))
        assertEquals(setOf("c"), repo.unseenDealIds(listOf("a", "b", "c"), "feed-2"))
        assertEquals(setOf("c"), repo.unseenDealIds(listOf("a", "b", "c"), "feed-2"))

        repo.markDealsSeen(listOf("a", "b", "c"), "feed-2")
        assertEquals(emptySet<String>(), repo.unseenDealIds(listOf("a", "b", "c"), "feed-2"))
        assertEquals("feed-2", repo.dealFeedRevision())
    }

    @Test
    fun restoredReadStateIsSanitizedAndBounded() {
        val ids = (0 until ThriveRepository.MAX_SEEN_DEAL_IDS + 100).map { "deal-$it" } + ""
        repo.restoreDealReadState(ids, "r1")
        assertEquals(ThriveRepository.MAX_SEEN_DEAL_IDS, repo.seenDealIds().size)
        assertFalse("" in repo.seenDealIds())
    }
}

package com.thrive.app

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File

/**
 * Shared setup for the major-flow Compose tests: every test starts from a
 * cleared app state (settings + sync cache) so a prior run can't leak pantry
 * items, budgets, or a persisted feed into the next one, then launches the
 * real [MainActivity].
 */
@RunWith(AndroidJUnit4::class)
abstract class ThriveUiTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    @Before
    fun clearDataAndLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("thrive_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, "sync_payload.json").delete()
        ActivityScenario.launch(MainActivity::class.java)
        rule.waitForIdle()
    }
}

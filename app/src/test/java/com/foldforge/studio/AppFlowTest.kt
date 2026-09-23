package com.foldforge.studio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.After
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end UI flows on Robolectric (JVM Android framework). Covers app launch, onboarding with the
 * demo project, workspace (compact/folded layout), preview pane and Settings.
 * Project creation + editing is covered at ViewModel level in [WorkspaceViewModelTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class AppFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch(onboardingDone: Boolean) {
        runBlocking { app.container.settings.update { it.copy(onboardingDone = onboardingDone) } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() { scenario?.close() }

    private val app get() = ApplicationProvider.getApplicationContext<FoldForgeApp>()

    private fun waitFor(tag: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun launch_onboarding_createsDemo_andOpensWorkspace() {
        launch(onboardingDone = false)
        waitForText("Next")
        compose.onNodeWithText("FOLD FORGE").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        waitFor("start_building")
        compose.onNodeWithTag("start_building").performClick()
        waitFor("project_title", 30_000)
        compose.onNodeWithTag("project_title").assertIsDisplayed()
        waitForText("Forge Runner")
        assertTrue(File(app.container.workspaceDir, "forge-runner/runner.js").isFile)
        // Compact (folded) layout shows the bottom navigation
        compose.onNodeWithTag("nav_Editor").assertIsDisplayed()
        compose.onNodeWithTag("nav_Preview").performClick()
        waitForText("Preview stopped")
    }

    @Test
    fun settings_screen_shows_sections() {
        launch(onboardingDone = true)
        waitFor("open_settings")
        compose.onNodeWithTag("open_settings").performClick()
        waitFor("settings")
        compose.onNodeWithText("APPEARANCE").assertIsDisplayed()
        compose.onNodeWithText("Forge Dark").assertIsDisplayed()
    }
}

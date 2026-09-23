package com.foldforge.studio

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foldforge.studio.core.templates.TemplateCatalog
import com.foldforge.studio.feature.home.NewProjectDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
/** Component-level tests for dialogs (full-app dialog windows cannot reach idle under Robolectric; see docs/QA.md). */
class DialogComponentTest {
    @get:Rule val compose = createComposeRule()
    @Test fun plainAlertDialogWithTextField() {
        compose.setContent { AlertDialog({}, confirmButton = { TextButton({}) { Text("ok") } }, text = { OutlinedTextField("", {}, modifier = Modifier.testTag("f")) }) }
        compose.onNodeWithTag("f").assertExists()
    }
    @Test fun newProjectDialog() {
        compose.setContent { NewProjectDialog(TemplateCatalog().templates, null, {}) { _, _ -> } }
        compose.onNodeWithTag("new_project_name").assertExists()
    }
}

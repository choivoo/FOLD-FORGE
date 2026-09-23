package com.foldforge.studio

import android.os.Looper
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foldforge.studio.feature.workspace.Pane
import com.foldforge.studio.feature.workspace.SaveState
import com.foldforge.studio.feature.workspace.WorkspaceLoad
import com.foldforge.studio.feature.workspace.WorkspaceViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** WorkspaceViewModel with a real project on disk: open, edit (IDE behaviours), autosave, undo, safe delete, layout. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WorkspaceViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<FoldForgeApp>()

    private fun pump(ms: Long = 200, until: () -> Boolean) {
        repeat(150) {
            shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
            if (until()) return
        }
        throw AssertionError("condition not met")
    }

    @Test fun create_open_edit_autosave_undo_delete() {
        val project = runBlocking { app.container.projects.create("VM Test", "blank-web") }
        val vm = WorkspaceViewModel(app)
        vm.open(project.id)
        pump { vm.load is WorkspaceLoad.Ready && vm.activeDoc != null }
        assertEquals("index.html", vm.activePath)
        vm.openFile("main.js")
        pump { vm.activePath == "main.js" }
        val doc = vm.activeDoc!!
        val start = doc.value.text
        vm.select(start.length, start.length) // caret at end of file

        // Soft keyboard types "{" at the end → auto-closed pair, caret inside
        val t1 = start + "{"
        vm.onEditorChange(doc, TextFieldValue(t1, TextRange(t1.length)))
        assertEquals(start + "{}", doc.value.text)
        assertEquals(start.length + 1, doc.value.selection.start)
        // Enter between braces → indented block
        val t2 = doc.value.text.substring(0, doc.value.selection.start) + "\n" + doc.value.text.substring(doc.value.selection.start)
        vm.onEditorChange(doc, TextFieldValue(t2, TextRange(doc.value.selection.start + 1)))
        assertTrue(doc.value.text.endsWith("{\n  \n}"))
        assertEquals(SaveState.MODIFIED, doc.saveState)

        // Debounced autosave writes to disk
        val file = File(project.root, "main.js")
        pump { file.readText().endsWith("{\n  \n}") }
        pump { doc.saveState == SaveState.SAVED }

        // Undo restores the previous text
        vm.undo()
        assertFalse(doc.value.text.endsWith("{\n  \n}"))

        // Safe delete: snapshot first, file gone, snapshot restorable
        vm.delete(listOf("style.css"))
        pump { !File(project.root, "style.css").exists() }
        val snaps = project.snapshots.list()
        assertTrue(snaps.any { it.reason == "delete" })
        project.snapshots.restore(snaps.first { it.reason == "delete" }.id)
        assertTrue(File(project.root, "style.css").isFile)

        // Layout presets
        vm.applyPreset("F")
        assertEquals(Pane.GIT, vm.leftTab)
        assertEquals(Pane.CONSOLE, vm.rightTab)
        vm.applyPreset("E")
        assertTrue(vm.fullscreenPreview)
    }

    @Test fun missing_project_fails_gracefully() {
        val vm = WorkspaceViewModel(app)
        vm.open("does-not-exist")
        pump { vm.load is WorkspaceLoad.Failed }
    }
}

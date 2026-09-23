package com.foldforge.studio.feature.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.foldforge.studio.core.editor.EditHistory
import com.foldforge.studio.core.editor.EditorLanguage
import com.foldforge.studio.core.editor.TextState

enum class Pane(val label: String) {
    EXPLORER("Files"), EDITOR("Editor"), PREVIEW("Preview"), AI("AI"), CONSOLE("Console"), QA("QA"),
    GIT("Git"), BUILD("Build"), ASSETS("Assets"), SEARCH("Search"), HISTORY("History"), INSPECTOR("Touch"),
}

data class LayoutPreset(val id: String, val label: String, val left: Pane?, val center: Pane, val right: Pane?, val split: Boolean = false)

object LayoutPresets {
    val ALL = listOf(
        LayoutPreset("A", "Files / Editor / AI", Pane.EXPLORER, Pane.EDITOR, Pane.AI),
        LayoutPreset("B", "Files / Editor / Preview", Pane.EXPLORER, Pane.EDITOR, Pane.PREVIEW),
        LayoutPreset("C", "Editor / Preview", null, Pane.EDITOR, Pane.PREVIEW),
        LayoutPreset("D", "Editor / QA", null, Pane.EDITOR, Pane.QA),
        LayoutPreset("E", "Preview fullscreen", null, Pane.PREVIEW, null),
        LayoutPreset("F", "Git / Editor / Console", Pane.GIT, Pane.EDITOR, Pane.CONSOLE),
    )
    fun byId(id: String) = ALL.firstOrNull { it.id == id } ?: ALL.first()
    val LEFT_TABS = listOf(Pane.EXPLORER, Pane.SEARCH, Pane.GIT, Pane.ASSETS, Pane.HISTORY)
    val CENTER_TABS = listOf(Pane.EDITOR, Pane.PREVIEW)
    val RIGHT_TABS = listOf(Pane.AI, Pane.PREVIEW, Pane.CONSOLE, Pane.QA, Pane.GIT, Pane.BUILD, Pane.ASSETS, Pane.INSPECTOR, Pane.HISTORY, Pane.SEARCH)
}

enum class SaveState(val label: String) { SAVED("Saved"), MODIFIED("Modified"), SAVING("Saving…"), ERROR("Save failed") }

/** An open editor document. Compose state so the editor recomposes on change. */
class EditorDoc(val path: String, initialText: String, val readOnly: Boolean = false) {
    val language: EditorLanguage = EditorLanguage.forFile(path)
    var value by mutableStateOf(TextFieldValue(initialText, TextRange(0)))
    var savedText by mutableStateOf(initialText)
    var saveState by mutableStateOf(SaveState.SAVED)
    var scrollY by mutableIntStateOf(0)
    var scrollX by mutableIntStateOf(0)
    val history = EditHistory()
    val dirty: Boolean get() = value.text != savedText

    fun textState(): TextState = TextState(value.text, value.selection.start, value.selection.end)
    fun apply(s: TextState) {
        val c = s.coerce()
        value = TextFieldValue(c.text, TextRange(c.selStart, c.selEnd))
    }
}

data class Notice(val message: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

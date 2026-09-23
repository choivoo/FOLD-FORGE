package com.foldforge.studio.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldforge.studio.core.editor.EditorOps
import com.foldforge.studio.core.model.FileKind
import com.foldforge.studio.core.ui.components.EmptyState
import com.foldforge.studio.core.ui.components.FileBadge
import com.foldforge.studio.core.ui.components.ToolIcon
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.feature.workspace.SaveState
import com.foldforge.studio.feature.workspace.WorkspaceViewModel

class FindState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var regex by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var index by mutableIntStateOf(0)
}

@Composable
fun EditorPanel(vm: WorkspaceViewModel, find: FindState, fontSize: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        EditorTabs(vm)
        val doc = vm.activeDoc
        if (doc == null) {
            EmptyState(Icons.Filled.Code, "No file open", "Open a file from the explorer, or press Ctrl+P to quick-open.")
            return@Column
        }
        EditorToolbar(vm, find, doc.saveState)
        val matches = remember(doc.value.text, find.open, find.query, find.caseSensitive, find.regex, find.wholeWord) {
            if (find.open && find.query.isNotEmpty()) EditorOps.find(doc.value.text, find.query, find.caseSensitive, find.regex, find.wholeWord, 5000) else emptyList()
        }
        AnimatedVisibility(find.open) { FindBar(vm, find, matches.size) { idx ->
            matches.getOrNull(idx)?.let { vm.select(it.start, it.end) }
        } }
        Box(Modifier.weight(1f)) {
            CodeEditor(
                doc = doc,
                fontSize = fontSize,
                matches = matches,
                activeMatch = find.index.coerceAtMost((matches.size - 1).coerceAtLeast(0)),
                pendingLine = vm.pendingLine,
                onLineConsumed = { vm.pendingLine = null },
                onChange = { vm.onEditorChange(doc, it) },
                onIndent = vm::indent,
                onOutdent = vm::outdent,
            )
        }
        CodingBar(vm)
    }
}

@Composable
private fun EditorTabs(vm: WorkspaceViewModel) {
    val errorFiles = vm.errorFiles
    LazyRow(Modifier.fillMaxWidth().height(38.dp).background(Forge.colors.panelAlt)) {
        items(vm.tabs.toList(), key = { it }) { path ->
            val active = path == vm.activePath
            val doc = vm.docs[path]
            Row(
                Modifier
                    .fillMaxSize()
                    .background(if (active) Forge.colors.panel else Forge.colors.panelAlt)
                    .clickable { vm.selectTab(path) }
                    .padding(start = 10.dp)
                    .widthIn(max = 220.dp)
                    .testTag("tab_$path"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (path in errorFiles) Box(Modifier.size(7.dp).clip(CircleShape).background(Forge.colors.error))
                else if (vm.aiEdited.containsKey(path)) Text("✦", color = Forge.colors.accent2, fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    path.substringAfterLast('/'),
                    fontSize = 13.sp, color = if (active) Forge.colors.text else Forge.colors.muted,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (doc?.dirty == true) Text(" ●", color = Forge.colors.accent, fontSize = 11.sp)
                Box(Modifier.size(34.dp).clickable { vm.closeTab(path) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Close ${path.substringAfterLast('/')}", tint = Forge.colors.muted, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun EditorToolbar(vm: WorkspaceViewModel, find: FindState, save: SaveState) {
    val doc = vm.activeDoc ?: return
    Row(Modifier.fillMaxWidth().height(40.dp).background(Forge.colors.panel).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FileBadge(FileKind.of(doc.path.substringAfterLast('/')))
        Spacer(Modifier.width(6.dp))
        Text(doc.path, fontSize = 12.sp, color = Forge.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            save.label, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp).testTag("save_state"),
            color = when (save) { SaveState.SAVED -> Forge.colors.success; SaveState.ERROR -> Forge.colors.error; else -> Forge.colors.warning },
        )
        ToolIcon(Icons.AutoMirrored.Filled.Undo, "Undo", vm::undo, enabled = doc.history.canUndo)
        ToolIcon(Icons.AutoMirrored.Filled.Redo, "Redo", vm::redo, enabled = doc.history.canRedo)
        ToolIcon(Icons.Filled.Search, "Find and replace", { find.open = !find.open })
        ToolIcon(Icons.Filled.AutoFixHigh, "Format code", vm::format)
        ToolIcon(Icons.Filled.Save, "Save", vm::saveActive)
        ToolIcon(Icons.Filled.PlayArrow, "Run", vm::run, tint = Forge.colors.success)
    }
}

@Composable
private fun FindField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier, tag: String) {
    Box(modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(6.dp)).background(Forge.colors.background).padding(horizontal = 8.dp, vertical = 8.dp)) {
        if (value.isEmpty()) Text(placeholder, color = Forge.colors.muted, fontSize = 13.sp)
        BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Forge.colors.text, fontSize = 13.sp, fontFamily = CodeFont), cursorBrush = SolidColor(Forge.colors.accent), modifier = Modifier.fillMaxWidth().testTag(tag))
    }
}

@Composable
private fun FindBar(vm: WorkspaceViewModel, find: FindState, count: Int, goTo: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Forge.colors.panelAlt).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FindField(find.query, { find.query = it; find.index = 0 }, "Find", Modifier.weight(1f), "find_query")
            Text(if (count == 0) "0/0" else "${find.index + 1}/$count", fontSize = 12.sp, color = Forge.colors.muted, modifier = Modifier.padding(horizontal = 6.dp))
            ToolIcon(Icons.Filled.KeyboardArrowUp, "Previous match", { if (count > 0) { find.index = (find.index - 1 + count) % count; goTo(find.index) } })
            ToolIcon(Icons.Filled.KeyboardArrowDown, "Next match", { if (count > 0) { find.index = (find.index + 1) % count; goTo(find.index) } })
            ToolIcon(Icons.Filled.Close, "Close find", { find.open = false })
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FindField(find.replacement, { find.replacement = it }, "Replace", Modifier.weight(1f), "replace_query")
            Spacer(Modifier.width(4.dp))
            Text("Replace all", color = Forge.colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                    val n = vm.replaceAllInActive(find.query, find.replacement, find.caseSensitive, find.regex, find.wholeWord)
                    vm.notify("Replaced $n occurrence(s)")
                }.padding(10.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(find.caseSensitive, { find.caseSensitive = !find.caseSensitive }, label = { Text("Aa") })
            Spacer(Modifier.width(6.dp))
            FilterChip(find.wholeWord, { find.wholeWord = !find.wholeWord }, label = { Text("Word") })
            Spacer(Modifier.width(6.dp))
            FilterChip(find.regex, { find.regex = !find.regex }, label = { Text(".*") })
            Spacer(Modifier.width(6.dp))
            FilterChip(false, { vm.wordAtCursor()?.let { vm.findReferences(it) } }, label = { Text("Find references") })
            Spacer(Modifier.width(6.dp))
            FilterChip(false, { vm.toggleComment() }, label = { Text("Toggle comment") })
        }
    }
}

/** Mobile coding bar shown above the soft keyboard. */
@Composable
fun CodingBar(vm: WorkspaceViewModel) {
    val keys = listOf("TAB", "{}", "()", "[]", "<>", ";", ":", "=", "\"", "'", "/", "Undo", "Redo")
    Row(
        Modifier.fillMaxWidth().imePadding().background(Forge.colors.panelAlt).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag("coding_bar"),
    ) {
        keys.forEach { k ->
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(width = if (k.length > 2) 56.dp else 44.dp, height = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Forge.colors.panel)
                    .clickable {
                        when (k) {
                            "TAB" -> vm.indent()
                            "Undo" -> vm.undo()
                            "Redo" -> vm.redo()
                            "\"" -> vm.insertSnippet("\"\"")
                            "'" -> vm.insertSnippet("''")
                            else -> vm.insertSnippet(k)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(k, fontFamily = CodeFont, fontSize = 14.sp, color = Forge.colors.text, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}


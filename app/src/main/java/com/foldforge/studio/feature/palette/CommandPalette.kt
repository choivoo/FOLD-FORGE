package com.foldforge.studio.feature.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge

data class PaletteCommand(val title: String, val shortcut: String = "", val action: () -> Unit)

/** Ctrl+K command palette. With [files], it doubles as Ctrl+P quick-open. */
@Composable
fun CommandPalette(commands: List<PaletteCommand>, files: List<String>, quickOpen: Boolean, onOpenFile: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val q = query.trim().lowercase()
    val matchingCommands = if (quickOpen) emptyList() else commands.filter { q.isEmpty() || fuzzy(it.title.lowercase(), q) }
    val matchingFiles = files.filter { q.isEmpty() && quickOpen || q.isNotEmpty() && fuzzy(it.lowercase(), q) }.take(60)
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Forge.colors.panel).padding(10.dp)) {
            OutlinedTextField(
                query, { query = it }, singleLine = true,
                placeholder = { Text(if (quickOpen) "Go to file…" else "Type a command or file name…") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("palette_input"),
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(matchingCommands, key = { "c:" + it.title }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onDismiss(); c.action() }.padding(horizontal = 8.dp, vertical = 11.dp)) {
                        Text(c.title, color = Forge.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (c.shortcut.isNotEmpty()) Text(c.shortcut, color = Forge.colors.muted, fontFamily = CodeFont, fontSize = 12.sp)
                    }
                }
                items(matchingFiles, key = { "f:$it" }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { onDismiss(); onOpenFile(f) }.padding(horizontal = 8.dp, vertical = 10.dp)) {
                        Text(f, color = Forge.colors.accent2, fontFamily = CodeFont, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** Subsequence match: "mjs" matches "src/main.js". */
fun fuzzy(text: String, query: String): Boolean {
    var i = 0
    for (c in text) if (i < query.length && c == query[i]) i++
    return i == query.length
}

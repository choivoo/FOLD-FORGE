package com.foldforge.studio.feature.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldforge.studio.core.ui.components.EmptyState
import com.foldforge.studio.core.ui.components.PanelHeader
import com.foldforge.studio.core.ui.components.ToolIcon
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.core.web.ConsoleLevel
import com.foldforge.studio.feature.workspace.WorkspaceViewModel

/** Captured console output: log/info/warn/error, runtime errors with stack traces; tap to jump to the line. */
@Composable
fun ConsolePanel(vm: WorkspaceViewModel, modifier: Modifier = Modifier) {
    var levels by remember { mutableStateOf(setOf(ConsoleLevel.LOG, ConsoleLevel.INFO, ConsoleLevel.WARN, ConsoleLevel.ERROR)) }
    val entries = vm.console.filter { it.level in levels }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.console.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1) }
    val errors = vm.console.count { it.level == ConsoleLevel.ERROR }
    val warns = vm.console.count { it.level == ConsoleLevel.WARN }
    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        PanelHeader("Console", subtitle = "$errors errors · $warns warnings") {
            ToolIcon(Icons.Filled.DeleteSweep, "Clear console", vm::clearConsole)
        }
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            listOf(ConsoleLevel.LOG, ConsoleLevel.INFO, ConsoleLevel.WARN, ConsoleLevel.ERROR, ConsoleLevel.DEBUG).forEach { l ->
                FilterChip(l in levels, { levels = if (l in levels) levels - l else levels + l }, label = { Text(l.name.lowercase(), fontSize = 11.sp) })
                Spacer(Modifier.width(4.dp))
            }
        }
        if (entries.isEmpty()) {
            EmptyState(Icons.Filled.Terminal, "Console is empty", "console.log, warnings, errors and uncaught exceptions from the preview appear here.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().testTag("console"), state = listState) {
            items(entries, key = { it.id }) { e ->
                val color = when (e.level) {
                    ConsoleLevel.ERROR -> Forge.colors.error
                    ConsoleLevel.WARN -> Forge.colors.warning
                    ConsoleLevel.INFO -> Forge.colors.accent2
                    ConsoleLevel.DEBUG -> Forge.colors.muted
                    ConsoleLevel.LOG -> Forge.colors.text
                }
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (e.level == ConsoleLevel.ERROR) Forge.colors.error.copy(alpha = 0.07f) else Forge.colors.panel)
                        .clickable(enabled = e.path != null) { e.path?.let { vm.openFile(it, e.line.coerceAtLeast(1)) } }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(e.message, fontFamily = CodeFont, fontSize = 12.sp, color = color)
                    if (e.path != null) Text("${e.path}:${e.line}", fontFamily = CodeFont, fontSize = 11.sp, color = Forge.colors.accent2)
                    e.stack?.let { Text(it.take(1500), fontFamily = CodeFont, fontSize = 10.sp, color = Forge.colors.muted) }
                }
            }
        }
    }
}

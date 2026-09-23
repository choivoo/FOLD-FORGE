package com.foldforge.studio.feature.history

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldforge.studio.core.model.HistoryEntry
import com.foldforge.studio.core.storage.Snapshot
import com.foldforge.studio.core.ui.components.PanelHeader
import com.foldforge.studio.core.ui.components.ToolIcon
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.feature.workspace.SnapshotDiff
import com.foldforge.studio.feature.workspace.WorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Version history: change log (user/AI/system) and snapshots with Restore / Compare / Undo AI change. */
@Composable
fun HistoryPanel(vm: WorkspaceViewModel, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var snapshots by remember { mutableStateOf<List<Snapshot>>(emptyList()) }
    var compare by remember { mutableStateOf<List<SnapshotDiff>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm.historyVersion, vm.projectOrNull) {
        val p = vm.projectOrNull ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            history = p.history().entries
            snapshots = p.snapshots.list()
        }
    }
    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        PanelHeader("History") { ToolIcon(Icons.Filled.AddAPhoto, "Create snapshot", vm::createManualSnapshot) }
        TabRow(tab, containerColor = Forge.colors.panelAlt) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Changes") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Snapshots (${snapshots.size})") })
        }
        if (tab == 0) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(history, key = { it.id }) { h ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(DateFormat.format("MM-dd HH:mm", h.timestamp).toString(), fontFamily = CodeFont, fontSize = 11.sp, color = Forge.colors.muted, modifier = Modifier.width(78.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (h.author == "ai") "AI " else if (h.author == "user") "User " else "") + h.message,
                                fontSize = 13.sp, color = if (h.author == "ai") Forge.colors.accent2 else Forge.colors.text,
                            )
                            if (h.files.isNotEmpty()) Text(h.files.take(4).joinToString(), fontSize = 11.sp, color = Forge.colors.muted, maxLines = 1)
                        }
                        if (h.snapshotId != null) {
                            TextButton(onClick = { vm.restoreSnapshot(h.snapshotId!!) }) { Text(if (h.author == "ai") "Undo AI change" else "Restore before", fontSize = 12.sp) }
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(snapshots, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(s.label, fontSize = 13.sp, color = Forge.colors.text)
                        Text("${DateFormat.format("yyyy-MM-dd HH:mm", s.timestamp)} · ${s.reason} · ${s.files.size} file(s)${if (s.partial) " · partial" else ""}", fontSize = 11.sp, color = Forge.colors.muted)
                        Row {
                            TextButton(onClick = { vm.restoreSnapshot(s.id) }) { Text("Restore") }
                            TextButton(onClick = { scope.launch { compare = vm.compareSnapshot(s.id) } }) { Text("Compare") }
                        }
                    }
                }
            }
        }
    }
    compare?.let { diffs ->
        AlertDialog(
            onDismissRequest = { compare = null },
            title = { Text("Snapshot vs current (${diffs.size} changed)") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    if (diffs.isEmpty()) Text("No differences.")
                    diffs.forEach { d ->
                        Text("${d.path}  +${d.added} −${d.removed}", fontSize = 12.sp, color = Forge.colors.accent2)
                        DiffText(d.unified)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { compare = null }) { Text("Close") } },
        )
    }
}

@Composable
fun DiffText(unified: String, maxLines: Int = 400) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        unified.lines().take(maxLines).forEach { line ->
            val color = when {
                line.startsWith("+++") || line.startsWith("---") -> Forge.colors.muted
                line.startsWith("+") -> Forge.colors.success
                line.startsWith("-") -> Forge.colors.error
                line.startsWith("@@") -> Forge.colors.accent2
                else -> Forge.colors.text
            }
            Text(line, fontFamily = CodeFont, fontSize = 11.sp, color = color, softWrap = false,
                modifier = Modifier.background(when { line.startsWith("+") && !line.startsWith("+++") -> Forge.colors.success.copy(alpha = 0.08f); line.startsWith("-") && !line.startsWith("---") -> Forge.colors.error.copy(alpha = 0.08f); else -> Forge.colors.panel }))
        }
    }
}

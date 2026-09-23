package com.foldforge.studio.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldforge.studio.core.ai.AgentStage
import com.foldforge.studio.core.ai.ContextScope
import com.foldforge.studio.core.ui.components.EmptyState
import com.foldforge.studio.core.ui.components.PanelHeader
import com.foldforge.studio.core.ui.components.StatusChip
import com.foldforge.studio.core.ui.components.ToolIcon
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.feature.history.DiffText
import com.foldforge.studio.feature.workspace.WorkspaceViewModel

@Composable
fun AiPanel(vm: WorkspaceViewModel, openSettings: () -> Unit, modifier: Modifier = Modifier) {
    val ai = vm.ai
    val settings by vm.settings.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(ai.messages.size) { if (ai.messages.isNotEmpty()) listState.animateScrollToItem(ai.messages.size - 1) }

    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        PanelHeader("AI", subtitle = if (ai.isConfigured) settings.aiModel else null) {
            ai.stage?.let { StatusChip(it.label, stageColor(it)) }
            ToolIcon(Icons.Filled.DeleteSweep, "Clear AI history", ai::clearHistory)
        }
        if (!ai.isConfigured) {
            EmptyState(
                Icons.Filled.AutoAwesome, "Connect AI Provider",
                "Editor, preview, QA basics, Git and ZIP work offline. Connect Claude (Anthropic) or an OpenAI-compatible endpoint to use the coding agent.",
                actionLabel = "Connect AI Provider", onAction = openSettings,
            )
            return@Column
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AiMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(ai.mode == m, { ai.mode = m }, SegmentedButtonDefaults.itemShape(i, AiMode.entries.size)) {
                        Text(if (m == AiMode.AGENT) "Agent (edits files)" else "Chat", fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(ContextScope.CURRENT_FILE to "Current File", ContextScope.RELATED_FILES to "Related Files", ContextScope.WHOLE_PROJECT to "Whole Project").forEach { (s, label) ->
                    FilterChip(ai.scope == s, { ai.scope = s }, label = { Text(label, fontSize = 11.sp) })
                    Spacer(Modifier.width(4.dp))
                }
                if (ai.mode == AiMode.AGENT) {
                    Switch(ai.runQaAfter, { ai.runQaAfter = it })
                    Text(" Run QA", fontSize = 11.sp, color = Forge.colors.muted)
                }
            }
        }
        if (ai.tasks.isNotEmpty()) TaskPanel(ai)
        if (ai.busy && ai.events.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 110.dp).verticalScroll(rememberScrollState()).background(Forge.colors.background).padding(8.dp)) {
                ai.events.takeLast(8).forEach { e -> Text("${e.stage.label}: ${e.message}", fontFamily = CodeFont, fontSize = 11.sp, color = stageColor(e.stage)) }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("ai_messages"), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ai.messages) { m -> MessageBubble(m, ai) }
        }
        ai.lastError?.let { err ->
            Row(Modifier.fillMaxWidth().background(Forge.colors.error.copy(alpha = 0.1f)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(err, color = Forge.colors.error, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(4.dp), maxLines = 3)
                TextButton(onClick = ai::retry) { Text("Retry") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(err)) }) { Text("Copy Error") }
                TextButton(onClick = openSettings) { Text("Settings") }
            }
        }
        Row(Modifier.fillMaxWidth().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, modifier = Modifier.weight(1f).testTag("ai_input"), maxLines = 5,
                placeholder = { Text(if (ai.mode == AiMode.AGENT) "\"Add a boss and run QA\", \"Fix touch controls\"…" else "Ask about this project…", fontSize = 13.sp) },
            )
            Spacer(Modifier.width(6.dp))
            if (ai.busy) ToolIcon(Icons.Filled.Stop, "Cancel", ai::cancel, tint = Forge.colors.error)
            else ToolIcon(Icons.AutoMirrored.Filled.Send, "Send", { ai.send(input); input = "" }, enabled = input.isNotBlank(), tint = Forge.colors.accent)
        }
    }

    ai.pendingReview?.let { ReviewDialog(it, ai) }
    ai.largeRequestWarning?.let { (chars, proceed) ->
        AlertDialog(
            onDismissRequest = { ai.largeRequestWarning = null },
            title = { Text("Send the whole project?") },
            text = { Text("About ${chars / 1000}k characters of project files would be sent to ${settings.aiProvider.label}. Sensitive files (.env, keystores, local.properties) are always excluded and detected secrets are redacted.") },
            confirmButton = { Button(onClick = { ai.largeRequestWarning = null; proceed() }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { ai.largeRequestWarning = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun stageColor(s: AgentStage) = when (s) {
    AgentStage.COMPLETE -> Forge.colors.success
    AgentStage.FAILED, AgentStage.REJECTED -> Forge.colors.error
    AgentStage.FIXING, AgentStage.REVIEW -> Forge.colors.warning
    else -> Forge.colors.accent2
}

@Composable
private fun TaskPanel(ai: AiController) {
    Column(Modifier.fillMaxWidth().background(Forge.colors.panelAlt).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("TASKS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Forge.colors.muted)
        ai.tasks.forEach { t ->
            val mark = when (t.state) { TaskState.DONE -> "[✓]"; TaskState.ACTIVE -> "[•]"; TaskState.PENDING -> "[ ]" }
            Text("$mark ${t.title}", fontFamily = CodeFont, fontSize = 12.sp, color = if (t.state == TaskState.ACTIVE) Forge.colors.accent else Forge.colors.text)
        }
    }
}

@Composable
private fun MessageBubble(m: ChatItem, ai: AiController) {
    val user = m.role == "user"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(when { m.isError -> Forge.colors.error.copy(alpha = 0.12f); user -> Forge.colors.accent.copy(alpha = 0.16f); else -> Forge.colors.panelAlt })
                .padding(10.dp),
        ) {
            Text(m.text, fontSize = 13.sp, color = if (m.isError) Forge.colors.error else Forge.colors.text)
            m.result?.takeIf { it.snapshotIds.isNotEmpty() }?.let { r ->
                TextButton(onClick = { ai.undoAgentResult(r) }) { Text("Undo AI Change", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ReviewDialog(review: PendingReview, ai: AiController) {
    val files = review.prepared.files
    val selected = remember(review) { mutableStateMapOf<String, Boolean>().apply { files.filter { it.ok }.forEach { put(it.path, true) } } }
    var expanded by remember(review) { mutableStateOf(files.firstOrNull { it.ok }?.path) }
    AlertDialog(
        onDismissRequest = {},
        title = {
            Column {
                Text("Review AI changes")
                Text("Files Changed: ${review.prepared.applicable.size}   +${review.prepared.added}  −${review.prepared.removed}", fontSize = 13.sp, color = Forge.colors.muted)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(review.prepared.summary, fontSize = 13.sp, color = Forge.colors.text)
                Spacer(Modifier.height(8.dp))
                files.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == f.path) null else f.path }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(selected[f.path] == true, { selected[f.path] = it }, enabled = f.ok)
                        Column(Modifier.weight(1f)) {
                            Text("${f.change.kind.name.lowercase()}  ${f.path}", fontFamily = CodeFont, fontSize = 12.sp, color = if (f.ok) Forge.colors.text else Forge.colors.error)
                            if (f.ok) Text("+${f.added} −${f.removed}", fontSize = 11.sp, color = Forge.colors.muted)
                            f.error?.let { Text(it, fontSize = 11.sp, color = Forge.colors.error) }
                            f.warnings.forEach { Text("⚠ $it", fontSize = 11.sp, color = Forge.colors.warning) }
                        }
                    }
                    if (expanded == f.path && f.ok) Box(Modifier.fillMaxWidth().background(Forge.colors.background).padding(4.dp)) { DiffText(f.unifiedDiff, 300) }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { ai.decide(files.filter { it.ok && selected[it.path] == true }) }, modifier = Modifier.testTag("apply_selected")) { Text("Apply Selected") }
                Button(onClick = { ai.decide(review.prepared.applicable) }, modifier = Modifier.testTag("apply_all")) { Text("Apply All") }
            }
        },
        dismissButton = { TextButton(onClick = { ai.decide(null) }) { Text("Reject", color = Forge.colors.error) } },
    )
}


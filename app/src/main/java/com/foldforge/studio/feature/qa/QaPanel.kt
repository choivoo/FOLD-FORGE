package com.foldforge.studio.feature.qa

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldforge.studio.core.qa.QaStatus
import com.foldforge.studio.core.ui.components.EmptyState
import com.foldforge.studio.core.ui.components.PanelHeader
import com.foldforge.studio.core.ui.components.SmallButton
import com.foldforge.studio.core.ui.components.StatusChip
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.feature.workspace.WorkspaceViewModel

@Composable
fun QaPanel(vm: WorkspaceViewModel, modifier: Modifier = Modifier) {
    val qa = vm.qa
    val settings by vm.settings.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.projectOrNull) { qa.loadState() }

    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        PanelHeader("Gameplay QA Agent") {
            StatusChip(qa.phase.label, when (qa.phase) {
                QaPhase.COMPLETE -> Forge.colors.success; QaPhase.FAILURE_FOUND, QaPhase.BLOCKED -> Forge.colors.error
                QaPhase.IDLE -> Forge.colors.muted; else -> Forge.colors.warning
            })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp)) {
            if (qa.running) SmallButton("Stop", qa::cancel)
            else {
                SmallButton("Start QA", qa::runFull, primary = true, modifier = Modifier.testTag("start_qa"))
                Spacer(Modifier.width(6.dp))
                SmallButton("Test ${settings.qaSoakMinutes} min", { qa.runTimed() })
                Spacer(Modifier.width(6.dp))
                SmallButton("AI Fix (max ${settings.qaMaxFixIterations})", qa::autoFix, enabled = (qa.report?.checkFail ?: 0) > 0)
                Spacer(Modifier.width(6.dp))
                SmallButton("Replay failures", qa::replayFailures, enabled = qa.replay != null)
                Spacer(Modifier.width(6.dp))
                SmallButton("AI scenarios", qa::generateAiScenarios)
            }
        }
        if (qa.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (qa.progress.isNotBlank()) Text(qa.progress, fontSize = 12.sp, color = Forge.colors.muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        if (qa.aiScenarios.isNotEmpty()) Text("${qa.aiScenarios.size} AI-generated scenario(s) included", fontSize = 11.sp, color = Forge.colors.accent2, modifier = Modifier.padding(horizontal = 12.dp))
        val report = qa.report
        if (report == null) {
            EmptyState(
                Icons.Filled.BugReport, "No QA run yet",
                "Start QA to launch the game in the preview sandbox and drive it with scenario-driven input: smoke, movement, combat, UI, restart, death, touch, orientation and performance tests.",
            )
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Stat("PASS", report.checkPass, Forge.colors.success)
            Stat("FAIL", report.checkFail, Forge.colors.error)
            Stat("WARN", report.checkWarn, Forge.colors.warning)
            Column(Modifier.weight(1f)) {
                Text("Mode: ${report.mode}", fontSize = 11.sp, color = Forge.colors.muted)
                report.performance.avgFps?.let { Text("FPS $it · worst frame ${report.performance.worstFrameMs ?: "-"}ms", fontSize = 11.sp, color = Forge.colors.muted) }
                report.performance.loadTimeMs?.let { Text("Load ${it}ms", fontSize = 11.sp, color = Forge.colors.muted) }
            }
        }
        report.performance.alerts.forEach { Text("⚠ $it", fontSize = 12.sp, color = Forge.colors.warning, modifier = Modifier.padding(horizontal = 12.dp)) }
        report.fixAttempts.forEach { a -> Text("Fix #${a.iteration}: ${a.outcome} (${a.changedFiles.joinToString()})", fontSize = 11.sp, color = Forge.colors.accent2, modifier = Modifier.padding(horizontal = 12.dp)) }
        LazyColumn(Modifier.fillMaxSize().testTag("qa_results")) {
            if (report.failures.isNotEmpty()) {
                item { Text("Failures", fontWeight = FontWeight.Bold, color = Forge.colors.error, fontSize = 13.sp, modifier = Modifier.padding(12.dp, 6.dp)) }
                items(report.failures) { Text("• $it", fontSize = 12.sp, color = Forge.colors.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
            }
            items(report.results, key = { it.id }) { r ->
                Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == r.id) null else r.id }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(r.status.name, statusColor(r.status))
                        Spacer(Modifier.width(8.dp))
                        Text(r.name, fontSize = 13.sp, color = Forge.colors.text, modifier = Modifier.weight(1f))
                        Text("${r.durationMs}ms", fontSize = 11.sp, color = Forge.colors.muted)
                    }
                    if (expanded == r.id) {
                        r.checks.forEach { c ->
                            Text("${c.status.name.padEnd(4)} ${c.name}: ${c.message}", fontFamily = CodeFont, fontSize = 11.sp, color = statusColor(c.status), modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                        }
                        r.errors.forEach { e -> Text("error: ${e.message} ${e.source}:${e.line}", fontFamily = CodeFont, fontSize = 11.sp, color = Forge.colors.error, modifier = Modifier.padding(start = 8.dp)) }
                        r.screenshot?.let { Text("screenshot: $it", fontSize = 11.sp, color = Forge.colors.accent2, modifier = Modifier.padding(start = 8.dp)) }
                        r.viewport?.let { Text("viewport: $it", fontSize = 11.sp, color = Forge.colors.muted, modifier = Modifier.padding(start = 8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(s: QaStatus) = when (s) {
    QaStatus.PASS -> Forge.colors.success; QaStatus.FAIL -> Forge.colors.error; QaStatus.WARN -> Forge.colors.warning; QaStatus.BLOCKED -> Forge.colors.muted
}

@Composable
private fun Stat(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.padding(end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", fontSize = 22.sp, fontWeight = FontWeight.Black, color = color)
        Text(label, fontSize = 10.sp, color = Forge.colors.muted)
    }
}

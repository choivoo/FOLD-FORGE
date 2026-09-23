package com.foldforge.studio.feature.home

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldforge.studio.LocalWindowInfo
import com.foldforge.studio.core.storage.ProjectSummary
import com.foldforge.studio.core.templates.TemplateInfo
import com.foldforge.studio.core.ui.components.StatusChip
import com.foldforge.studio.core.ui.theme.Forge

@Composable
fun HomeScreen(openProject: (String) -> Unit, openSettings: () -> Unit, incomingZip: Uri?, onZipConsumed: () -> Unit) {
    val vm: HomeViewModel = viewModel()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val git by vm.gitCache.collectAsStateWithLifecycle()
    val aiConfigured by vm.aiConfigured.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var initialTemplate by remember { mutableStateOf<String?>(null) }
    val window = LocalWindowInfo.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importZip(uri, openProject)
    }
    LaunchedEffect(incomingZip) {
        if (incomingZip != null) { vm.importZip(incomingZip, openProject); onZipConsumed() }
    }
    LaunchedEffect(Unit) { vm.refresh() }

    val columns = if (window.expanded) 3 else if (window.medium) 2 else 1
    Box(Modifier.fillMaxSize().background(Forge.colors.background).safeDrawingPadding()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().testTag("home"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("FOLD FORGE", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Forge.colors.accent, letterSpacing = 3.sp)
                        Text("AI MOBILE DEVELOPMENT WORKSTATION", fontSize = 11.sp, color = Forge.colors.muted, letterSpacing = 2.sp)
                    }
                    IconButton(onClick = openSettings, modifier = Modifier.testTag("open_settings")) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Forge.colors.text)
                    }
                }
            }
            vm.restorable?.let { s ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    RestoreBanner(s.projectId.orEmpty(), crashed = !s.cleanExit, onRestore = { vm.dismissRestore(); openProject(s.projectId!!) }, onDismiss = vm::dismissRestore)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                QuickActions(
                    onNew = { initialTemplate = null; dialog = "new" },
                    onAi = { dialog = "ai" },
                    onImport = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    onClone = { dialog = "clone" },
                    onRecent = { projects.firstOrNull()?.let { openProject(it.id) } },
                    onTemplates = { dialog = "templates" },
                    recentEnabled = projects.isNotEmpty(),
                )
            }
            vm.busy?.let { label ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("$label…", color = Forge.colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            vm.error?.let { err ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(err, color = Forge.colors.error, fontSize = 13.sp, modifier = Modifier.clickable { vm.error = null })
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("PROJECTS", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = Forge.colors.muted, modifier = Modifier.padding(top = 8.dp))
            }
            if (!loading && projects.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("No projects yet. Create one from a template or let the AI build it.", color = Forge.colors.muted, fontSize = 14.sp)
                }
            }
            items(projects, key = { it.id }) { p ->
                ProjectCard(p, git[p.id], onOpen = { openProject(p.id) }, onDelete = { dialog = "delete:${p.id}" })
            }
        }
    }

    when (val d = dialog) {
        "new" -> NewProjectDialog(vm.templates, initialTemplate, onDismiss = { dialog = null }) { name, template -> dialog = null; vm.create(name, template, openProject) }
        "templates" -> TemplatesDialog(vm.templates, onDismiss = { dialog = null }) { initialTemplate = it; dialog = "new" }
        "clone" -> CloneDialog(onDismiss = { dialog = null }) { url -> dialog = null; vm.clone(url, openProject) }
        "ai" -> AiBuildDialog(vm, aiConfigured, openSettings, onDismiss = { dialog = null }, onCreated = { dialog = null; openProject(it) })
        null -> Unit
        else -> if (d.startsWith("delete:")) {
            val id = d.removePrefix("delete:")
            val name = projects.firstOrNull { it.id == id }?.name ?: id
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Delete \"$name\"?") },
                text = { Text("The project folder and all its files, snapshots and history are permanently deleted from this device. Export a ZIP first if you want a copy.") },
                confirmButton = { TextButton(onClick = { dialog = null; vm.delete(id) }) { Text("Delete", color = Forge.colors.error) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun RestoreBanner(projectId: String, crashed: Boolean, onRestore: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Forge.colors.accent.copy(alpha = 0.12f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Restore, null, tint = Forge.colors.accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (crashed) "Restore session" else "Continue where you left off", fontWeight = FontWeight.Bold, color = Forge.colors.text)
            Text(if (crashed) "FOLD FORGE closed unexpectedly. Open tabs and unsaved edits of '$projectId' can be restored." else "Reopen '$projectId' with your tabs.", fontSize = 12.sp, color = Forge.colors.muted)
        }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
        Button(onClick = onRestore, modifier = Modifier.testTag("restore_session")) { Text("Restore Session") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActions(onNew: () -> Unit, onAi: () -> Unit, onImport: () -> Unit, onClone: () -> Unit, onRecent: () -> Unit, onTemplates: () -> Unit, recentEnabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionTile("+ New Project", Icons.Filled.Add, onNew, primary = true, tag = "action_new")
        ActionTile("AI Build", Icons.Filled.AutoAwesome, onAi, tag = "action_ai_build")
        ActionTile("Import Project", Icons.Filled.FileOpen, onImport)
        ActionTile("Clone GitHub", Icons.Filled.CloudDownload, onClone)
        ActionTile("Open Recent", Icons.Filled.History, onRecent, enabled = recentEnabled)
        ActionTile("Templates", Icons.Filled.ViewModule, onTemplates)
    }
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true, tag: String? = null) {
    val bg = if (primary) Forge.colors.accent else Forge.colors.panel
    val fg = if (primary) androidx.compose.ui.graphics.Color(0xFF1A0E05) else Forge.colors.text
    Row(
        Modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .border(1.dp, if (primary) bg else Forge.colors.border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ProjectCard(p: ProjectSummary, git: com.foldforge.studio.data.database.GitCacheEntity?, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Forge.colors.panel)
            .border(1.dp, Forge.colors.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(14.dp)
            .semantics { contentDescription = "Project ${p.name}" }
            .testTag("project_${p.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Forge.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.type.label, fontSize = 12.sp, color = Forge.colors.accent2)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Project actions", tint = Forge.colors.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Delete…") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Modified ${DateUtils.getRelativeTimeSpanString(p.lastModified)} · ${p.fileCount} files",
            fontSize = 12.sp, color = Forge.colors.muted,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(p.buildStatus, if (p.buildStatus.endsWith("success")) Forge.colors.success else if (p.buildStatus.endsWith("failed")) Forge.colors.error else Forge.colors.muted)
            StatusChip(
                if (!p.hasGit) "No Git" else git?.let { "${it.branch ?: "git"} · ${it.state.lowercase()}" } ?: "Git",
                if (!p.hasGit) Forge.colors.muted else if (git?.state == "CLEAN") Forge.colors.success else Forge.colors.warning,
            )
            StatusChip(if (p.previewReady) "Preview ready" else "No entry", if (p.previewReady) Forge.colors.accent2 else Forge.colors.error)
        }
    }
}

@Composable
private fun TemplateGrid(templates: List<TemplateInfo>, selected: String?, onSelect: (String) -> Unit) {
    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
        templates.forEach { t ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (t.id == selected) Forge.colors.accent.copy(alpha = 0.16f) else Forge.colors.panelAlt)
                    .clickable { onSelect(t.id) }
                    .padding(10.dp)
                    .testTag("template_${t.id}"),
            ) {
                Column {
                    Text(t.name, fontWeight = FontWeight.Bold, color = Forge.colors.text, fontSize = 14.sp)
                    Text(t.description, color = Forge.colors.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun NewProjectDialog(templates: List<TemplateInfo>, initialTemplate: String?, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(initialTemplate ?: templates.firstOrNull { it.id == "canvas-game" }?.id ?: templates.first().id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) }, label = { Text("Project name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_project_name"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Spacer(Modifier.height(10.dp))
                TemplateGrid(templates, template) { template = it }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name.ifBlank { templates.first { it.id == template }.name }, template) }, modifier = Modifier.testTag("create_project")) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TemplatesDialog(templates: List<TemplateInfo>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Templates") },
        text = { TemplateGrid(templates, null, onPick) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun CloneDialog(onDismiss: () -> Unit, onClone: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone GitHub repository") },
        text = {
            Column {
                OutlinedTextField(url, { url = it.trim() }, label = { Text("https://github.com/owner/repo or owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("Private repositories use the GitHub token from Settings → Git. The project is detected and FOLD FORGE metadata is created.", fontSize = 12.sp, color = Forge.colors.muted)
            }
        },
        confirmButton = { Button(onClick = { onClone(url) }, enabled = url.isNotBlank()) { Text("Clone") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AiBuildDialog(vm: HomeViewModel, configured: Boolean, openSettings: () -> Unit, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var prompt by remember { mutableStateOf("") }
    val running = vm.busy == "AI Build"
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("AI Build") },
        text = {
            Column {
                if (!configured) {
                    Text("No AI provider is connected. AI Build sends your prompt to the provider you configure.", color = Forge.colors.muted, fontSize = 13.sp)
                } else {
                    OutlinedTextField(
                        prompt, { prompt = it }, label = { Text("Describe your game or app") }, minLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("ai_build_prompt"), enabled = !running,
                        placeholder = { Text("An original monster-collecting 3D RPG with touch controls") },
                    )
                    vm.aiStep?.let { step ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(step.label, color = if (step == AiBuildStep.FAILED) Forge.colors.error else Forge.colors.text, fontSize = 13.sp)
                        }
                        vm.aiPlan.forEach { Text("✓ $it", fontSize = 12.sp, color = Forge.colors.muted) }
                    }
                    vm.error?.let { Text(it, color = Forge.colors.error, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            if (!configured) Button(onClick = { onDismiss(); openSettings() }) { Text("Connect AI Provider") }
            else Button(onClick = { vm.aiBuild(prompt, onCreated) }, enabled = prompt.isNotBlank() && !running) { Text("Build") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Cancel") } },
    )
}

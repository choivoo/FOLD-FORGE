package com.foldforge.studio.feature.explorer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldforge.studio.core.model.FileKind
import com.foldforge.studio.core.model.FileNode
import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.storage.SafePaths
import com.foldforge.studio.core.ui.components.FileBadge
import com.foldforge.studio.core.ui.components.PanelHeader
import com.foldforge.studio.core.ui.components.ToolIcon
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.feature.workspace.WorkspaceViewModel

private sealed class ExplorerDialog {
    data class NewFile(val parent: String) : ExplorerDialog()
    data class NewFolder(val parent: String) : ExplorerDialog()
    data class Rename(val path: String) : ExplorerDialog()
    data class Delete(val paths: List<String>) : ExplorerDialog()
    data class Move(val paths: List<String>) : ExplorerDialog()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerPanel(vm: WorkspaceViewModel, modifier: Modifier = Modifier) {
    var dialog by remember { mutableStateOf<ExplorerDialog?>(null) }
    var importTarget by remember { mutableStateOf("") }
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris, importTarget)
    }
    var exportPath by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = exportPath ?: return@rememberLauncherForActivityResult
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out -> vm.project.fs.file(path).inputStream().use { it.copyTo(out) } }
            vm.notify("Exported ${path.substringAfterLast('/')}")
        }.onFailure { vm.notify("Export failed: ${it.message}") }
    }
    val selection = vm.selected.filterValues { it }.keys.toList()
    LaunchedEffect(Unit) { if (vm.children.isEmpty()) vm.refreshTree() }

    val rows = remember(vm.children.toMap(), vm.expanded.toMap()) {
        val out = ArrayList<FileNode>()
        fun add(dir: String) {
            vm.children[dir].orEmpty().forEach { n ->
                out += n
                if (n.isDirectory && vm.expanded[n.path] == true) add(n.path)
            }
        }
        add("")
        out
    }

    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        PanelHeader("Explorer", subtitle = vm.projectOrNull?.meta?.name) {
            ToolIcon(Icons.Filled.NoteAdd, "New file", { dialog = ExplorerDialog.NewFile(selectedDir(vm, selection)) })
            ToolIcon(Icons.Filled.CreateNewFolder, "New folder", { dialog = ExplorerDialog.NewFolder(selectedDir(vm, selection)) })
            ToolIcon(Icons.Filled.Download, "Import files", { importTarget = selectedDir(vm, selection); importLauncher.launch(arrayOf("*/*")) })
            ToolIcon(Icons.Filled.Refresh, "Refresh", vm::refreshTree)
        }
        if (selection.isNotEmpty() || vm.clipboard != null) {
            Row(Modifier.fillMaxWidth().background(Forge.colors.panelAlt).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (selection.isNotEmpty()) "${selection.size} selected" else "Clipboard: ${vm.clipboard?.first?.size} item(s)", fontSize = 12.sp, color = Forge.colors.muted, modifier = Modifier.padding(start = 8.dp).weight(1f))
                if (selection.isNotEmpty()) {
                    ToolIcon(Icons.Filled.ContentCopy, "Copy", { vm.copyToClipboard(selection, cut = false) })
                    ToolIcon(Icons.Filled.ContentCut, "Cut", { vm.copyToClipboard(selection, cut = true) })
                    ToolIcon(Icons.Filled.FolderOpen, "Move to…", { dialog = ExplorerDialog.Move(selection) })
                    ToolIcon(Icons.Filled.Delete, "Delete", { dialog = ExplorerDialog.Delete(selection) }, tint = Forge.colors.error)
                }
                if (vm.clipboard != null) ToolIcon(Icons.Filled.ContentPaste, "Paste", { vm.paste(selectedDir(vm, selection)) })
                ToolIcon(Icons.Filled.SelectAll, "Clear selection", { vm.selected.clear() })
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("explorer")) {
            items(rows, key = { it.path }) { node ->
                var menu by remember { mutableStateOf(false) }
                val isSelected = vm.selected[node.path] == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .background(if (isSelected) Forge.colors.accent.copy(alpha = 0.16f) else if (node.path == vm.activePath) Forge.colors.panelAlt else Forge.colors.panel)
                        .combinedClickable(
                            onClick = {
                                if (selection.isNotEmpty()) vm.selected[node.path] = !isSelected
                                else if (node.isDirectory) vm.toggleDir(node.path) else vm.openFile(node.path)
                            },
                            onLongClick = { vm.selected[node.path] = !isSelected },
                        )
                        .padding(start = (8 + node.depth * 14).dp)
                        .testTag("node_${node.path}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (node.isDirectory) {
                        Icon(if (vm.expanded[node.path] == true) Icons.Filled.FolderOpen else Icons.Filled.Folder, null, tint = Forge.colors.accent, modifier = Modifier.size(18.dp))
                    } else {
                        FileBadge(FileKind.of(node.name))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        node.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        color = if (node.path in vm.errorFiles) Forge.colors.error else Forge.colors.text,
                    )
                    if (vm.aiEdited.containsKey(node.path)) Text("AI", fontSize = 9.sp, color = Forge.colors.accent2, modifier = Modifier.padding(end = 4.dp))
                    Box {
                        ToolIcon(Icons.Filled.MoreVert, "Actions for ${node.name}", { menu = true }, tint = Forge.colors.muted)
                        DropdownMenu(menu, { menu = false }) {
                            if (node.isDirectory) {
                                DropdownMenuItem(text = { Text("New file") }, onClick = { menu = false; dialog = ExplorerDialog.NewFile(node.path) })
                                DropdownMenuItem(text = { Text("New folder") }, onClick = { menu = false; dialog = ExplorerDialog.NewFolder(node.path) })
                                DropdownMenuItem(text = { Text("Import here…") }, onClick = { menu = false; importTarget = node.path; importLauncher.launch(arrayOf("*/*")) })
                                if (vm.clipboard != null) DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; vm.paste(node.path) })
                            } else {
                                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; vm.openFile(node.path) })
                                DropdownMenuItem(text = { Text("Export…") }, onClick = { menu = false; exportPath = node.path; exportLauncher.launch(node.name) })
                            }
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = ExplorerDialog.Rename(node.path) })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicate(node.path) })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; vm.copyToClipboard(listOf(node.path), false) })
                            DropdownMenuItem(text = { Text("Cut") }, onClick = { menu = false; vm.copyToClipboard(listOf(node.path), true) })
                            DropdownMenuItem(text = { Text("Move to…") }, onClick = { menu = false; dialog = ExplorerDialog.Move(listOf(node.path)) })
                            DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; vm.selected[node.path] = true })
                            DropdownMenuItem(text = { Text("Delete…", color = Forge.colors.error) }, onClick = { menu = false; dialog = ExplorerDialog.Delete(listOf(node.path)) })
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is ExplorerDialog.NewFile -> NameDialog("New file in /${d.parent}", "", "Create", { dialog = null }) { vm.createFile(d.parent, it); dialog = null }
        is ExplorerDialog.NewFolder -> NameDialog("New folder in /${d.parent}", "", "Create", { dialog = null }) { vm.createFolder(d.parent, it); dialog = null }
        is ExplorerDialog.Rename -> NameDialog("Rename", d.path.substringAfterLast('/'), "Rename", { dialog = null }) { vm.rename(d.path, it); dialog = null }
        is ExplorerDialog.Move -> NameDialog("Move ${d.paths.size} item(s) to folder (project-relative, empty = root)", "", "Move", { dialog = null }, allowEmpty = true, validate = { runCatching { SafePaths.normalize(it); null }.getOrElse { e -> e.message } }) { vm.move(d.paths, it); dialog = null }
        is ExplorerDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete ${d.paths.size} item(s)?") },
            text = { Text(d.paths.joinToString("\n", limit = 8) + "\n\nA snapshot is saved first, so you can restore from History.") },
            confirmButton = { TextButton(onClick = { vm.delete(d.paths); dialog = null }, modifier = Modifier.testTag("confirm_delete")) { Text("Delete", color = Forge.colors.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

private fun selectedDir(vm: WorkspaceViewModel, selection: List<String>): String {
    val first = selection.firstOrNull() ?: vm.activePath?.let { ProjectFileSystem.parentOf(it) } ?: return ""
    return if (runCatching { vm.project.fs.isDirectory(first) }.getOrDefault(false)) first else ProjectFileSystem.parentOf(first)
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    allowEmpty: Boolean = false,
    validate: (String) -> String? = { if (it.isEmpty()) null else SafePaths.validateName(it) },
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val error = validate(name.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, isError = error != null, modifier = Modifier.fillMaxWidth().testTag("name_input"))
                error?.let { Text(it, color = Forge.colors.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(name.trim()) }, enabled = error == null && (allowEmpty || name.isNotBlank()), modifier = Modifier.testTag("name_confirm")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

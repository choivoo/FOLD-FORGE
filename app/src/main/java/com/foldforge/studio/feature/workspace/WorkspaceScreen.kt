package com.foldforge.studio.feature.workspace

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foldforge.studio.BuildConfig
import com.foldforge.studio.LocalPreviewHost
import com.foldforge.studio.LocalWindowInfo
import com.foldforge.studio.Posture
import com.foldforge.studio.core.ui.components.EmptyState
import com.foldforge.studio.core.ui.components.StatusChip
import com.foldforge.studio.core.ui.components.ToolIcon
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.core.web.ConsoleLevel
import com.foldforge.studio.data.settings.ExternalNavPolicy
import com.foldforge.studio.feature.ai.AiPanel
import com.foldforge.studio.feature.assets.AssetsPanel
import com.foldforge.studio.feature.build.BuildPanel
import com.foldforge.studio.feature.build.ExportKind
import com.foldforge.studio.feature.console.ConsolePanel
import com.foldforge.studio.feature.editor.EditorPanel
import com.foldforge.studio.feature.editor.FindState
import com.foldforge.studio.feature.explorer.ExplorerPanel
import com.foldforge.studio.feature.git.GitPanel
import com.foldforge.studio.feature.history.HistoryPanel
import com.foldforge.studio.feature.palette.CommandPalette
import com.foldforge.studio.feature.palette.PaletteCommand
import com.foldforge.studio.feature.preview.PreviewPanel
import com.foldforge.studio.feature.preview.TouchInspectorPanel
import com.foldforge.studio.feature.qa.QaPanel
import com.foldforge.studio.feature.search.SearchPanel
import kotlinx.coroutines.launch

@Composable
fun WorkspaceScreen(projectId: String, onHome: () -> Unit, openSettings: () -> Unit) {
    val vm: WorkspaceViewModel = viewModel()
    val host = LocalPreviewHost.current
    val window = LocalWindowInfo.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val find = remember { FindState() }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(projectId) { vm.open(projectId) }
    DisposableEffect(host) {
        host?.let { vm.attachPreview(it) }
        onDispose { host?.let { vm.detachPreview(it) } }
    }
    LaunchedEffect(Unit) {
        vm.notices.collect { n ->
            val r = snackbar.showSnackbar(n.message, n.actionLabel, withDismissAction = n.actionLabel == null)
            if (r == SnackbarResult.ActionPerformed) n.action?.invoke()
        }
    }
    LaunchedEffect(vm.load) { runCatching { focus.requestFocus() } }

    fun leave() {
        scope.launch { vm.saveAll(); vm.markCleanExit(); host?.stop(); onHome() }
    }
    BackHandler {
        when {
            vm.fullscreenPreview -> vm.fullscreenPreview = false
            window.compact && vm.compactTab != Pane.EDITOR -> vm.compactTab = Pane.EDITOR
            else -> leave()
        }
    }

    val commands = remember(vm) {
        buildList {
            add(PaletteCommand("Open File", "Ctrl+P") { vm.quickOpen = true; vm.paletteOpen = true })
            add(PaletteCommand("New File") { vm.showPane(Pane.EXPLORER) })
            add(PaletteCommand("Run", "Ctrl+Enter") { vm.run() })
            add(PaletteCommand("Build APK") { vm.showPane(Pane.BUILD) })
            add(PaletteCommand("Ask AI") { vm.showPane(Pane.AI) })
            add(PaletteCommand("Search", "Ctrl+Shift+F") { vm.showPane(Pane.SEARCH) })
            add(PaletteCommand("Git Commit") { vm.showPane(Pane.GIT) })
            add(PaletteCommand("Git Push") { vm.git.push() })
            add(PaletteCommand("Start QA") { vm.showPane(Pane.QA); vm.qa.runFull() })
            add(PaletteCommand("Export ZIP") { vm.build.export(ExportKind.PROJECT_ZIP); vm.showPane(Pane.BUILD) })
            add(PaletteCommand("Export Android Project") { vm.build.export(ExportKind.ANDROID_PROJECT); vm.showPane(Pane.BUILD) })
            add(PaletteCommand("Format Document") { vm.format() })
            add(PaletteCommand("Toggle Comment") { vm.toggleComment() })
            add(PaletteCommand("Find References") { vm.wordAtCursor()?.let(vm::findReferences) })
            add(PaletteCommand("Console", "Ctrl+`") { vm.showPane(Pane.CONSOLE) })
            add(PaletteCommand("History & Snapshots") { vm.showPane(Pane.HISTORY) })
            add(PaletteCommand("Assets") { vm.showPane(Pane.ASSETS) })
            add(PaletteCommand("Touch Inspector") { vm.showPane(Pane.INSPECTOR) })
            add(PaletteCommand("Settings") { openSettings() })
            LayoutPresets.ALL.forEach { p -> add(PaletteCommand("Layout ${p.id}: ${p.label}") { vm.applyPreset(p.id) }) }
            if (BuildConfig.DEBUG) {
                add(PaletteCommand("QA Tools: Simulate JS Error") { vm.simulateJsError() })
                add(PaletteCommand("QA Tools: Simulate Build Error") { vm.build.simulateBuildError(); vm.showPane(Pane.BUILD) })
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Forge.colors.background)
            .safeDrawingPadding()
            .focusRequester(focus)
            .focusTarget()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.S -> { vm.saveActive(); true }
                    Key.P -> { vm.quickOpen = true; vm.paletteOpen = true; true }
                    Key.K -> { vm.quickOpen = false; vm.paletteOpen = true; true }
                    Key.F -> { if (e.isShiftPressed) vm.showPane(Pane.SEARCH) else find.open = true; true }
                    Key.Enter -> { vm.run(); true }
                    Key.Grave -> { vm.showPane(Pane.CONSOLE); true }
                    Key.Z -> { if (e.isShiftPressed) vm.redo() else vm.undo(); true }
                    Key.Y -> { vm.redo(); true }
                    else -> false
                }
            },
    ) {
        when (val state = vm.load) {
            WorkspaceLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Opening project…", color = Forge.colors.muted) }
            is WorkspaceLoad.Failed -> EmptyState(Icons.Filled.FolderOpen, "Could not open project", state.message, actionLabel = "Back to Home", onAction = onHome)
            is WorkspaceLoad.Ready -> Column(Modifier.fillMaxSize()) {
                StatusBar(vm, ::leave)
                Box(Modifier.weight(1f)) {
                    when {
                        vm.fullscreenPreview -> PreviewPanel(vm)
                        window.posture == Posture.TABLETOP -> TabletopLayout(vm, find, settings.editorFontSize, openSettings)
                        window.expanded || window.medium -> SplitLayout(vm, find, settings.editorFontSize, openSettings, showLeftDefault = window.expanded)
                        else -> PaneContent(vm.compactTab, vm, find, settings.editorFontSize, openSettings)
                    }
                }
                if (window.compact && !vm.fullscreenPreview && window.posture != Posture.TABLETOP) CompactNav(vm, ::leave)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }

    if (vm.paletteOpen) {
        val files = remember(vm.children.toMap()) { runCatching { vm.project.fs.walkFiles().map { it.path }.take(5000).toList() }.getOrDefault(emptyList()) }
        CommandPalette(commands, files, vm.quickOpen, onOpenFile = { vm.openFile(it) }, onDismiss = { vm.paletteOpen = false; vm.quickOpen = false })
    }
    vm.externalUrlPrompt?.let { url ->
        val open = { vm.externalUrlPrompt = null; runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        if (settings.externalNav == ExternalNavPolicy.OPEN_BROWSER) { open() } else AlertDialog(
            onDismissRequest = { vm.externalUrlPrompt = null },
            title = { Text("Leave the preview sandbox?") },
            text = { Text("The game tried to navigate to:\n$url\n\nIt was blocked inside the preview. Open it in your browser instead?") },
            confirmButton = { Button(onClick = { open() }) { Text("Open in browser") } },
            dismissButton = { TextButton(onClick = { vm.externalUrlPrompt = null }) { Text("Stay") } },
        )
    }
}

@Composable
private fun StatusBar(vm: WorkspaceViewModel, onHome: () -> Unit) {
    val window = LocalWindowInfo.current
    var more by remember { mutableStateOf(false) }
    var layoutMenu by remember { mutableStateOf(false) }
    val errors = vm.console.count { it.level == ConsoleLevel.ERROR }
    val save = vm.overallSaveState
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(Forge.colors.panel).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIcon(Icons.AutoMirrored.Filled.ArrowBack, "Home", onHome)
        if (window.expanded) Text("FOLD FORGE", fontWeight = FontWeight.Black, fontSize = 13.sp, color = Forge.colors.accent, letterSpacing = 1.5.sp, modifier = Modifier.padding(end = 10.dp))
        Text(vm.projectOrNull?.meta?.name ?: "", fontWeight = FontWeight.Bold, color = Forge.colors.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("project_title"))
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            vm.git.status?.let { StatusChip("⎇ ${it.branch ?: "-"}${if (it.changes.isNotEmpty()) " · ${it.changes.size}" else ""}", if (it.changes.isEmpty()) Forge.colors.success else Forge.colors.warning); Spacer(Modifier.width(4.dp)) }
            StatusChip(save.label, when (save) { SaveState.SAVED -> Forge.colors.success; SaveState.ERROR -> Forge.colors.error; else -> Forge.colors.warning })
            if (errors > 0) { Spacer(Modifier.width(4.dp)); StatusChip("$errors error${if (errors > 1) "s" else ""}", Forge.colors.error) }
            if (vm.qa.running) { Spacer(Modifier.width(4.dp)); StatusChip("QA ${vm.qa.phase.label}", Forge.colors.warning) }
            if (vm.ai.busy) { Spacer(Modifier.width(4.dp)); StatusChip("AI ${vm.ai.stage?.label ?: "…"}", Forge.colors.accent2) }
            if (vm.build.phase.name in setOf("PREPARING", "BUILDING", "TESTING")) { Spacer(Modifier.width(4.dp)); StatusChip("Build ${vm.build.phase.label}", Forge.colors.warning) }
        }
        ToolIcon(Icons.Filled.PlayArrow, "Run", vm::run, tint = Forge.colors.success)
        if (!window.compact) {
            ToolIcon(Icons.Filled.BugReport, "QA", { vm.showPane(Pane.QA) })
            ToolIcon(Icons.Filled.Build, "Build", { vm.showPane(Pane.BUILD) })
            ToolIcon(Icons.Filled.AutoAwesome, "AI", { vm.showPane(Pane.AI) })
            ToolIcon(Icons.Filled.VerticalSplit, "Split editor and preview", { vm.splitPreview = !vm.splitPreview }, tint = if (vm.splitPreview) Forge.colors.accent else Forge.colors.text)
            Box {
                ToolIcon(Icons.Filled.Dashboard, "Layout presets", { layoutMenu = true })
                DropdownMenu(layoutMenu, { layoutMenu = false }) {
                    LayoutPresets.ALL.forEach { p -> DropdownMenuItem(text = { Text("${p.id}  ${p.label}") }, onClick = { layoutMenu = false; vm.applyPreset(p.id) }) }
                }
            }
        }
        Box {
            ToolIcon(Icons.Filled.MoreVert, "More", { more = true })
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem(text = { Text("Command palette (Ctrl+K)") }, onClick = { more = false; vm.paletteOpen = true })
                listOf(Pane.CONSOLE, Pane.QA, Pane.GIT, Pane.BUILD, Pane.ASSETS, Pane.SEARCH, Pane.HISTORY, Pane.INSPECTOR).forEach { p ->
                    DropdownMenuItem(text = { Text(p.label) }, onClick = { more = false; vm.showPane(p) })
                }
            }
        }
    }
}

@Composable
private fun CompactNav(vm: WorkspaceViewModel, onHome: () -> Unit) {
    val items = listOf(
        Triple("Home", Icons.Filled.Home, null as Pane?), Triple("Projects", Icons.Filled.FolderOpen, Pane.EXPLORER),
        Triple("Editor", Icons.Filled.Code, Pane.EDITOR), Triple("AI", Icons.Filled.AutoAwesome, Pane.AI), Triple("Preview", Icons.Filled.PlayArrow, Pane.PREVIEW),
    )
    NavigationBar(containerColor = Forge.colors.panel, modifier = Modifier.height(64.dp)) {
        items.forEach { (label, icon, pane) ->
            NavigationBarItem(
                selected = pane != null && vm.compactTab == pane,
                onClick = { if (pane == null) onHome() else vm.compactTab = pane },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Forge.colors.accent, indicatorColor = Forge.colors.accent.copy(alpha = 0.15f)),
                modifier = Modifier.testTag("nav_$label"),
            )
        }
    }
}

@Composable
fun PaneContent(pane: Pane, vm: WorkspaceViewModel, find: FindState, fontSize: Int, openSettings: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedContent(pane, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pane", modifier = modifier) { p ->
        when (p) {
            Pane.EXPLORER -> ExplorerPanel(vm)
            Pane.EDITOR -> EditorPanel(vm, find, fontSize)
            Pane.PREVIEW -> PreviewPanel(vm)
            Pane.AI -> AiPanel(vm, openSettings)
            Pane.CONSOLE -> ConsolePanel(vm)
            Pane.QA -> QaPanel(vm)
            Pane.GIT -> GitPanel(vm)
            Pane.BUILD -> BuildPanel(vm)
            Pane.ASSETS -> AssetsPanel(vm)
            Pane.SEARCH -> SearchPanel(vm)
            Pane.HISTORY -> HistoryPanel(vm)
            Pane.INSPECTOR -> TouchInspectorPanel(vm)
        }
    }
}

@Composable
private fun PaneTabs(tabs: List<Pane>, selected: Pane, onSelect: (Pane) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp).background(Forge.colors.background).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEach { t ->
            val active = t == selected
            Text(
                t.label, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) Forge.colors.accent else Forge.colors.muted,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (active) Forge.colors.panelAlt else Forge.colors.background)
                    .clickable { onSelect(t) }.padding(horizontal = 10.dp, vertical = 8.dp).testTag("panetab_${t.name}"),
            )
        }
    }
}

@Composable
private fun Divider(onDrag: (Float) -> Unit) {
    Box(
        Modifier.width(8.dp).fillMaxHeight().background(Forge.colors.background)
            .draggable(rememberDraggableState(onDrag), Orientation.Horizontal)
            .testTag("pane_divider"),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.width(2.dp).height(36.dp).clip(RoundedCornerShape(1.dp)).background(Forge.colors.border)) }
}

/** Unfolded (and medium) layout: LEFT 25% · CENTER 45% · RIGHT 30% with draggable dividers. */
@Composable
private fun SplitLayout(vm: WorkspaceViewModel, find: FindState, fontSize: Int, openSettings: () -> Unit, showLeftDefault: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val total = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val showLeft = vm.showLeft && showLeftDefault
        val leftW: Dp = maxWidth * (if (showLeft) vm.leftFraction else 0f)
        val rightW: Dp = maxWidth * (if (vm.showRight) vm.rightFraction else 0f)
        Row(Modifier.fillMaxSize()) {
            if (showLeft) {
                Column(Modifier.width(leftW).fillMaxHeight()) {
                    PaneTabs(LayoutPresets.LEFT_TABS, vm.leftTab) { vm.leftTab = it }
                    PaneContent(vm.leftTab, vm, find, fontSize, openSettings, Modifier.weight(1f))
                }
                Divider { d -> vm.leftFraction = (vm.leftFraction + d / total).coerceIn(0.15f, 0.40f) }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showLeftDefault) ToolIcon(Icons.Filled.ViewSidebar, "Toggle side panel", { vm.showLeft = !vm.showLeft })
                    Box(Modifier.weight(1f)) { PaneTabs(LayoutPresets.CENTER_TABS, vm.centerTab) { vm.centerTab = it } }
                    ToolIcon(Icons.Filled.ViewSidebar, "Toggle right panel", { vm.showRight = !vm.showRight })
                }
                if (vm.splitPreview && vm.centerTab == Pane.EDITOR && vm.rightTab != Pane.PREVIEW) {
                    Column(Modifier.weight(1f)) {
                        EditorPanel(vm, find, fontSize, Modifier.weight(0.55f))
                        Box(Modifier.fillMaxWidth().height(6.dp).background(Forge.colors.background))
                        PreviewPanel(vm, Modifier.weight(0.45f))
                    }
                } else {
                    PaneContent(vm.centerTab, vm, find, fontSize, openSettings, Modifier.weight(1f))
                }
            }
            if (vm.showRight) {
                Divider { d -> vm.rightFraction = (vm.rightFraction - d / total).coerceIn(0.20f, 0.50f) }
                Column(Modifier.width(rightW).fillMaxHeight()) {
                    val rightTabs = LayoutPresets.RIGHT_TABS.filterNot { it == Pane.PREVIEW && vm.centerTab == Pane.PREVIEW }
                    PaneTabs(rightTabs, vm.rightTab) { vm.rightTab = it }
                    val tab = if (vm.rightTab == Pane.PREVIEW && vm.centerTab == Pane.PREVIEW) Pane.CONSOLE else vm.rightTab
                    PaneContent(tab, vm, find, fontSize, openSettings, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Tabletop posture: play on the top half, tools on the bottom half. */
@Composable
private fun TabletopLayout(vm: WorkspaceViewModel, find: FindState, fontSize: Int, openSettings: () -> Unit) {
    var bottom by remember { mutableStateOf(Pane.CONSOLE) }
    Column(Modifier.fillMaxSize()) {
        PreviewPanel(vm, Modifier.weight(1f), controllerInitially = true)
        Box(Modifier.fillMaxWidth().height(4.dp).background(Forge.colors.accent.copy(alpha = 0.3f)))
        Column(Modifier.weight(1f)) {
            PaneTabs(listOf(Pane.CONSOLE, Pane.EDITOR, Pane.QA, Pane.AI, Pane.INSPECTOR), bottom) { bottom = it }
            PaneContent(bottom, vm, find, fontSize, openSettings, Modifier.weight(1f))
        }
    }
}


package com.foldforge.studio.feature.preview

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldforge.studio.LocalPreviewHost
import com.foldforge.studio.core.qa.ViewportPreset
import com.foldforge.studio.core.qa.ViewportPresets
import com.foldforge.studio.core.ui.components.EmptyState
import com.foldforge.studio.core.ui.components.PanelHeader
import com.foldforge.studio.core.ui.components.StatusChip
import com.foldforge.studio.core.ui.components.ToolIcon
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.core.web.PreviewHost
import com.foldforge.studio.core.web.PreviewStatus
import com.foldforge.studio.feature.workspace.Pane
import com.foldforge.studio.feature.workspace.WorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun PreviewPanel(vm: WorkspaceViewModel, modifier: Modifier = Modifier, controllerInitially: Boolean = false) {
    val host = LocalPreviewHost.current ?: return
    val status by host.status.collectAsStateWithLifecycle()
    val viewport by host.viewport.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var presetMenu by remember { mutableStateOf(false) }
    var showController by remember { mutableStateOf(controllerInitially) }
    var customize by remember { mutableStateOf(false) }
    var fps by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settings.fpsOverlay, status) {
        fps = null
        while (settings.fpsOverlay && status == PreviewStatus.RUNNING) {
            val raw = host.evaluate("window.__ff ? JSON.stringify(window.__ff.snapshotMetrics()) : null")
            fps = raw?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }?.let { m ->
                val f = (m["fps"] as? JsonPrimitive)?.intOrNull ?: 0
                val ft = (m["avgFrameMs"] as? JsonPrimitive)?.content ?: "-"
                "$f FPS · ${ft}ms"
            }
            delay(1000)
        }
    }

    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        PanelHeader("Preview") {
            StatusChip(status.name.lowercase().replaceFirstChar { it.uppercase() }, when (status) {
                PreviewStatus.RUNNING -> Forge.colors.success; PreviewStatus.LOADING -> Forge.colors.warning
                PreviewStatus.ERROR -> Forge.colors.error; PreviewStatus.STOPPED -> Forge.colors.muted
            })
        }
        Row(Modifier.fillMaxWidth().background(Forge.colors.panelAlt).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Icons.Filled.PlayArrow, "Run", vm::run, tint = Forge.colors.success)
            ToolIcon(Icons.Filled.Stop, "Stop", { vm.stopPreview() }, enabled = status != PreviewStatus.STOPPED)
            ToolIcon(Icons.Filled.Refresh, "Reload", { vm.reloadPreview() }, enabled = status != PreviewStatus.STOPPED)
            ToolIcon(if (vm.fullscreenPreview) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Fullscreen", { vm.fullscreenPreview = !vm.fullscreenPreview })
            ToolIcon(Icons.Filled.ScreenRotation, "Rotate device", { host.rotate() }, enabled = viewport != null)
            Box {
                ToolIcon(Icons.Filled.PhoneAndroid, "Device preset", { presetMenu = true })
                DropdownMenu(presetMenu, { presetMenu = false }) {
                    DropdownMenuItem(text = { Text("Responsive (fill panel)") }, onClick = { presetMenu = false; host.setViewport(null) })
                    ViewportPresets.ALL.forEach { p ->
                        DropdownMenuItem(text = { Text("${p.label}  ${p.width}×${p.height}") }, onClick = { presetMenu = false; host.setViewport(p) })
                    }
                    DropdownMenuItem(text = { Text("Custom 412×915") }, onClick = { presetMenu = false; host.setViewport(ViewportPreset("custom", "Custom", 412, 915)) })
                }
            }
            ToolIcon(Icons.Filled.Speed, "FPS overlay", { scope.launch { vm.container.settings.update { it.copy(fpsOverlay = !it.fpsOverlay) } } }, tint = if (settings.fpsOverlay) Forge.colors.accent else Forge.colors.text)
            ToolIcon(Icons.Filled.CameraAlt, "Screenshot", {
                scope.launch {
                    val bmp = host.screenshot()
                    if (bmp == null) { vm.notify("Nothing to capture"); return@launch }
                    val rel = ".foldforge/screenshots/shot-${System.currentTimeMillis()}.png"
                    withContext(Dispatchers.IO) { vm.project.fs.writeBytes(rel, PreviewHost.pngBytes(bmp)) }
                    vm.notify("Screenshot saved: $rel")
                }
            }, enabled = status == PreviewStatus.RUNNING)
            ToolIcon(Icons.Filled.Gamepad, "Game controller", { showController = !showController }, tint = if (showController) Forge.colors.accent else Forge.colors.text)
            if (showController) ToolIcon(Icons.Filled.Tune, "Customize controller", { customize = !customize }, tint = if (customize) Forge.colors.accent else Forge.colors.text)
            ToolIcon(Icons.Filled.Terminal, "Console", { vm.showPane(Pane.CONSOLE) })
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Forge.colors.background), contentAlignment = Alignment.Center) {
            if (status == PreviewStatus.STOPPED) {
                EmptyState(Icons.Filled.PlayArrow, "Preview stopped", "Run the project (Ctrl+Enter) to start the live preview sandbox.", actionLabel = "Run", onAction = vm::run)
            }
            val vp = viewport
            val webModifier = if (vp == null) Modifier.fillMaxSize() else {
                val scale = minOf(maxWidth.value / vp.width, maxHeight.value / vp.height, 1f)
                Modifier.requiredSize(vp.width.dp, vp.height.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .border(1.dp, Forge.colors.border)
            }
            // The WebView instance is owned by PreviewHost and survives pane moves / fold changes.
            AndroidView(
                factory = { host.detachFromParent(); host.view().apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } },
                modifier = webModifier.alpha(if (status == PreviewStatus.STOPPED) 0f else 1f).testTag("preview_webview"),
            )
            DisposableEffect(Unit) { onDispose { host.detachFromParent() } }
            fps?.let {
                Text(it, fontFamily = CodeFont, fontSize = 11.sp, color = Forge.colors.success, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Forge.colors.background.copy(alpha = 0.7f)).padding(4.dp))
            }
            vp?.let { Text("${it.label} ${it.width}×${it.height}", fontSize = 10.sp, color = Forge.colors.muted, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)) }
            if (showController && status == PreviewStatus.RUNNING) ControllerOverlay(vm, host, customize)
        }
    }
}

@Serializable
data class ControlPos(val id: String, val x: Float, val y: Float, val size: Float = 64f)

@Serializable
data class ControllerLayout(val controls: List<ControlPos>, val opacity: Float = 0.75f)

private val DEFAULT_LAYOUT = ControllerLayout(
    listOf(
        ControlPos("joystick", 0.14f, 0.78f, 120f), ControlPos("a", 0.88f, 0.80f), ControlPos("b", 0.78f, 0.88f),
        ControlPos("x", 0.78f, 0.70f), ControlPos("y", 0.68f, 0.80f), ControlPos("jump", 0.90f, 0.62f, 56f),
        ControlPos("attack", 0.62f, 0.62f, 56f), ControlPos("dash", 0.50f, 0.88f, 56f),
    ),
)

/** Virtual game controller that feeds input into the preview sandbox via the FOLD FORGE runtime. */
@Composable
fun ControllerOverlay(vm: WorkspaceViewModel, host: PreviewHost, customize: Boolean) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val json = remember { Json { ignoreUnknownKeys = true } }
    val saved = remember(settings.controllerLayout) { runCatching { json.decodeFromString(ControllerLayout.serializer(), settings.controllerLayout) }.getOrNull() ?: DEFAULT_LAYOUT }
    val controls = remember(saved) { mutableStateListOf(*saved.controls.toTypedArray()) }
    var opacity by remember(saved) { mutableFloatStateOf(saved.opacity) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    fun send(js: String) { scope.launch { host.evaluate("window.__ff && window.__ff.input && ($js); 0") } }
    fun persist() {
        val text = json.encodeToString(ControllerLayout.serializer(), ControllerLayout(controls.toList(), opacity))
        scope.launch { vm.container.settings.update { it.copy(controllerLayout = text) } }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        controls.forEachIndexed { index, c ->
            val sizePx = with(density) { c.size.dp.toPx() }
            val base = Modifier
                .offset { IntOffset((c.x * w - sizePx / 2).roundToInt(), (c.y * h - sizePx / 2).roundToInt()) }
                .size(c.size.dp)
                .alpha(opacity)
            val dragModifier = if (customize) Modifier.pointerInput(c.id) {
                detectDragGestures(onDragEnd = { persist() }) { change, drag ->
                    change.consume()
                    val cur = controls[index]
                    controls[index] = cur.copy(x = (cur.x + drag.x / w).coerceIn(0.05f, 0.95f), y = (cur.y + drag.y / h).coerceIn(0.05f, 0.95f))
                }
            } else Modifier
            if (c.id == "joystick") {
                Joystick(base.then(dragModifier), customize) { x, y -> send("window.__ff.input.joystick(${"%.2f".format(x)}, ${"%.2f".format(y)})") }
            } else {
                val label = c.id.uppercase()
                Box(
                    base.then(dragModifier)
                        .clip(CircleShape)
                        .background(Forge.colors.panelAlt.copy(alpha = 0.85f))
                        .border(2.dp, Forge.colors.accent.copy(alpha = 0.6f), CircleShape)
                        .semantics { contentDescription = "Controller button $label" }
                        .then(if (!customize) Modifier.pointerInput(c.id) {
                            awaitPointerEventScopeLoop(
                                onDown = { send("window.__ff.input.pressButton('${c.id}', true)") },
                                onUp = { send("window.__ff.input.pressButton('${c.id}', false)") },
                            )
                        } else Modifier),
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontWeight = FontWeight.Black, fontSize = 12.sp, color = Forge.colors.text) }
            }
        }
        if (customize) {
            Column(Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Forge.colors.panel.copy(alpha = 0.95f)).padding(10.dp).width(260.dp)) {
                Text("Drag controls to move them", fontSize = 12.sp, color = Forge.colors.text)
                Text("Opacity", fontSize = 11.sp, color = Forge.colors.muted)
                Slider(opacity, { opacity = it }, valueRange = 0.2f..1f, onValueChangeFinished = { persist() })
                Text("Button size", fontSize = 11.sp, color = Forge.colors.muted)
                val avg = controls.filter { it.id != "joystick" }.map { it.size }.average().toFloat()
                Slider(avg, { s -> controls.indices.forEach { i -> if (controls[i].id != "joystick") controls[i] = controls[i].copy(size = s) } }, valueRange = 44f..96f, onValueChangeFinished = { persist() })
                Row {
                    TextButton(onClick = { controls.clear(); controls.addAll(DEFAULT_LAYOUT.controls); opacity = DEFAULT_LAYOUT.opacity; persist() }) { Text("Reset") }
                }
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitPointerEventScopeLoop(onDown: () -> Unit, onUp: () -> Unit) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent()
            if (down.changes.any { it.pressed }) {
                onDown()
                down.changes.forEach { it.consume() }
                while (true) {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { it.consume() }
                    if (ev.changes.none { it.pressed }) { onUp(); break }
                }
            }
        }
    }
}

@Composable
private fun Joystick(modifier: Modifier, customize: Boolean, onMove: (Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .clip(CircleShape)
            .background(Forge.colors.panelAlt.copy(alpha = 0.6f))
            .border(2.dp, Forge.colors.border, CircleShape)
            .semantics { contentDescription = "Virtual joystick" }
            .then(if (!customize) Modifier.pointerInput(Unit) {
                val radius = size.width / 2f
                detectDragGestures(
                    onDragEnd = { knob = Offset.Zero; onMove(0f, 0f) },
                    onDragCancel = { knob = Offset.Zero; onMove(0f, 0f) },
                ) { change, drag ->
                    change.consume()
                    var n = knob + drag
                    val len = sqrt(n.x * n.x + n.y * n.y)
                    if (len > radius) n = n * (radius / len)
                    knob = n
                    onMove(n.x / radius, n.y / radius)
                }
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }.size(44.dp).clip(CircleShape).background(Forge.colors.accent.copy(alpha = 0.7f)))
    }
}

data class TouchEvent(val type: String, val x: Int, val y: Int, val id: Int, val t: Int, val synthetic: Boolean)

/** Touch Event Inspector: shows touch/pointer events received by the running page. */
@Composable
fun TouchInspectorPanel(vm: WorkspaceViewModel, modifier: Modifier = Modifier) {
    val host = LocalPreviewHost.current
    val events = remember { mutableStateListOf<TouchEvent>() }
    var paused by remember { mutableStateOf(false) }
    val json = remember { Json { ignoreUnknownKeys = true } }
    LaunchedEffect(host, paused) {
        while (host != null && !paused) {
            val raw = host.evaluate("window.__ff ? window.__ff.drainTouches() : '[]'")
            runCatching {
                (json.parseToJsonElement(raw ?: "[]") as kotlinx.serialization.json.JsonArray).forEach { el ->
                    val o = el as JsonObject
                    fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
                    events.add(0, TouchEvent((o["type"] as JsonPrimitive).content, i("x"), i("y"), i("id"), i("t"), (o["synthetic"] as? JsonPrimitive)?.content == "true"))
                }
                while (events.size > 300) events.removeAt(events.size - 1)
            }
            delay(400)
        }
    }
    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        PanelHeader("Touch Inspector", subtitle = "${events.size} events") {
            TextButton(onClick = { paused = !paused }) { Text(if (paused) "Resume" else "Pause") }
            TextButton(onClick = { events.clear() }) { Text("Clear") }
        }
        if (events.isEmpty()) EmptyState(Icons.Filled.Gamepad, "No touch events yet", "Run the preview and touch it: touchstart/move/end and pointer events appear here with coordinates.")
        LazyColumn(Modifier.fillMaxSize()) {
            items(events) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(e.type, fontFamily = CodeFont, fontSize = 12.sp, color = if (e.type.startsWith("touch")) Forge.colors.accent2 else Forge.colors.accent, modifier = Modifier.width(110.dp))
                    Text("(${e.x}, ${e.y})", fontFamily = CodeFont, fontSize = 12.sp, color = Forge.colors.text, modifier = Modifier.width(100.dp))
                    Text("id ${e.id} · ${e.t}ms${if (e.synthetic) " · QA" else ""}", fontFamily = CodeFont, fontSize = 11.sp, color = Forge.colors.muted)
                }
            }
        }
    }
}


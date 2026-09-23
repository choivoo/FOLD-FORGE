package com.foldforge.studio.feature.assets

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.foldforge.studio.core.assets.AssetEntry
import com.foldforge.studio.core.assets.ProceduralAssets
import com.foldforge.studio.core.model.FileKind
import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.ui.components.FileBadge
import com.foldforge.studio.core.ui.components.KeyValue
import com.foldforge.studio.core.ui.components.PanelHeader
import com.foldforge.studio.core.ui.components.SmallButton
import com.foldforge.studio.core.ui.components.StatusChip
import com.foldforge.studio.core.ui.components.ToolIcon
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.core.web.PreviewHost
import com.foldforge.studio.feature.workspace.WorkspaceViewModel
import java.io.ByteArrayInputStream

@Composable
fun AssetsPanel(vm: WorkspaceViewModel, modifier: Modifier = Modifier) {
    val assets = vm.assets
    var dialog by remember { mutableStateOf<String?>(null) }
    var showUnusedOnly by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris, "assets")
    }
    LaunchedEffect(vm.projectOrNull) { assets.scan() }
    val selected = assets.entries.firstOrNull { it.path == assets.selectedPath }

    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        PanelHeader("Assets", subtitle = "${assets.entries.size} files · ${assets.entries.count { !it.referenced }} unused") {
            ToolIcon(Icons.Filled.Download, "Import assets (GLB, GLTF, OBJ, PNG, JPG, WEBP, SVG, audio, JSON)", { importLauncher.launch(arrayOf("*/*")) })
            ToolIcon(Icons.Filled.Refresh, "Rescan assets", assets::scan)
        }
        if (assets.scanning || assets.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton("Procedural 3D", { dialog = "procedural" })
            Spacer(Modifier.width(6.dp))
            SmallButton("AI image", { dialog = "ai" }, enabled = assets.imageProviderConfigured)
            Spacer(Modifier.width(6.dp))
            FilterChip(showUnusedOnly, { showUnusedOnly = !showUnusedOnly }, label = { Text("Unused only") })
        }
        if (selected != null) AssetDetail(vm, selected)
        LazyColumn(Modifier.fillMaxSize()) {
            items(assets.entries.filter { !showUnusedOnly || !it.referenced }, key = { it.path }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { assets.selectedPath = a.path }
                        .background(if (a.path == assets.selectedPath) Forge.colors.panelAlt else Forge.colors.panel)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileBadge(a.kind)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.path, fontSize = 13.sp, color = Forge.colors.text)
                        Text(
                            buildString {
                                append(android.text.format.Formatter.formatShortFileSize(vm.container.app, a.size))
                                a.image?.let { append(" · ${it.format} ${it.width}×${it.height}") }
                                a.model?.let { append(" · ${it.format} ${it.triangles} tris") }
                            },
                            fontSize = 11.sp, color = Forge.colors.muted,
                        )
                    }
                    if (!a.referenced) StatusChip("unused", Forge.colors.warning)
                }
            }
        }
    }

    when (dialog) {
        "procedural" -> ProceduralDialog({ dialog = null }) { kinds -> assets.generateProcedural(kinds); dialog = null }
        "ai" -> AiImageDialog({ dialog = null }) { prompt, texture -> assets.generateImage(prompt, texture); dialog = null }
    }
}

@Composable
private fun AssetDetail(vm: WorkspaceViewModel, a: AssetEntry) {
    Column(Modifier.fillMaxWidth().background(Forge.colors.panelAlt).padding(10.dp)) {
        KeyValue("File", a.path)
        KeyValue("Size", "${a.size} bytes")
        KeyValue("Referenced", if (a.referenced) "yes" else "no (not deleted automatically)")
        a.image?.let { KeyValue("Image", "${it.format} ${it.width}×${it.height}") }
        a.model?.let { m ->
            KeyValue("Model", "${m.format} · ${if (m.valid) "valid" else "INVALID: ${m.error}"}")
            if (m.valid) {
                KeyValue("Geometry", "${m.meshes} meshes · ${m.primitives} primitives · ${m.vertices} vertices · ${m.triangles} triangles")
                KeyValue("Materials", m.materials.joinToString().ifEmpty { "-" })
                KeyValue("Textures", "${m.textures} textures · ${m.images} images")
                KeyValue("Animations", m.animations.joinToString().ifEmpty { "-" })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
            if (a.kind == FileKind.IMAGE && !a.path.endsWith(".svg")) {
                SmallButton("Convert to WebP", { vm.assets.convertToWebp(a.path) })
                Spacer(Modifier.width(6.dp))
                SmallButton("Resize ≤1024", { vm.assets.resize(a.path, 1024) })
            }
        }
        if (a.kind == FileKind.MODEL3D && a.model?.valid == true) {
            Spacer(Modifier.height(6.dp))
            ModelViewer(vm.project.fs, a.path, Modifier.fillMaxWidth().height(260.dp))
        }
    }
}

/**
 * 3D viewer (GLB/GLTF/OBJ) using the bundled Three.js viewer page (app assets) in an isolated WebView:
 * rotate / pan / zoom (OrbitControls), reset, wireframe toggle and animation playback.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ModelViewer(fs: ProjectFileSystem, path: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url
                        if (url.host != PreviewHost.HOST) return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", mapOf(), ByteArrayInputStream(ByteArray(0)))
                        val p = url.path.orEmpty()
                        return runCatching {
                            when {
                                p.startsWith("/viewer/") -> {
                                    val name = p.removePrefix("/viewer/")
                                    if (name.contains("..")) throw SecurityException()
                                    WebResourceResponse(PreviewHost.mimeFor(name), "utf-8", ctx.assets.open("viewer/$name"))
                                }
                                p.startsWith("/model/") -> {
                                    val rel = java.net.URLDecoder.decode(p.removePrefix("/model/"), "UTF-8")
                                    WebResourceResponse(PreviewHost.mimeFor(rel), null, fs.file(rel).inputStream())
                                }
                                else -> null
                            }
                        }.getOrNull() ?: WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf(), ByteArrayInputStream(ByteArray(0)))
                    }
                }
            }
        },
        update = { wv ->
            val target = "https://${PreviewHost.HOST}/viewer/viewer.html?model=" + java.net.URLEncoder.encode(path, "UTF-8")
            if (wv.url != target) wv.loadUrl(target)
        },
    )
}

@Composable
private fun ProceduralDialog(onDismiss: () -> Unit, onGenerate: (List<String>) -> Unit) {
    val chosen = remember { mutableStateListOf("crate", "tree", "rock", "coin") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Procedural 3D assets") },
        text = {
            Column {
                Text("Generates a Three.js module with factory functions — no AI or downloads needed.", fontSize = 12.sp, color = Forge.colors.muted)
                ProceduralAssets.KINDS.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(k in chosen, { if (it) chosen += k else chosen -= k })
                        Text(k)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onGenerate(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Generate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AiImageDialog(onDismiss: () -> Unit, onGenerate: (String, Boolean) -> Unit) {
    var prompt by remember { mutableStateOf("") }
    var texture by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate image asset") },
        text = {
            Column {
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt") }, minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(texture, { texture = it }); Text("Seamless texture") }
                Text("Uses the image provider configured in Settings → AI. The result is saved to assets/generated/.", fontSize = 11.sp, color = Forge.colors.muted)
            }
        },
        confirmButton = { Button(onClick = { onGenerate(prompt, texture) }, enabled = prompt.isNotBlank()) { Text("Generate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

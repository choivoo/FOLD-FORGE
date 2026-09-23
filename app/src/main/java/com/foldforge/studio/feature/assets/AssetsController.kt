package com.foldforge.studio.feature.assets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.foldforge.studio.core.assets.AssetEntry
import com.foldforge.studio.core.assets.AssetScanner
import com.foldforge.studio.core.assets.HttpImageGenerationProvider
import com.foldforge.studio.core.assets.ProceduralAssets
import com.foldforge.studio.core.security.SecureStore
import com.foldforge.studio.feature.workspace.WorkspaceEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class AssetsController(private val env: WorkspaceEnv) {
    var entries by mutableStateOf<List<AssetEntry>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var selectedPath by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
        private set

    val imageProviderConfigured: Boolean
        get() = env.settings.value.imageEndpoint.isNotBlank() && env.settings.value.imageModel.isNotBlank() &&
            env.container.secureStore.has(SecureStore.IMAGE_API_KEY)

    fun scan() {
        val p = runCatching { env.project }.getOrNull() ?: return
        env.scope.launch {
            scanning = true
            entries = withContext(Dispatchers.IO) { runCatching { AssetScanner.scan(p.fs) }.getOrDefault(emptyList()) }
            scanning = false
        }
    }

    fun select(path: String) {
        selectedPath = path
        if (entries.none { it.path == path }) scan()
    }

    private fun op(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        env.scope.launch {
            try {
                env.notify(withContext(Dispatchers.IO) { block() })
            } catch (e: Exception) {
                env.notify("$label failed: ${e.message}")
            } finally {
                busy = null
                scan()
            }
        }
    }

    fun generateProcedural(kinds: List<String>) = op("Generate procedural assets") {
        val p = env.project
        val path = p.fs.uniquePath("assets/procedural/assets.js")
        p.fs.writeText(path, ProceduralAssets.module(kinds))
        p.addHistory("user", "Generated procedural 3D assets: ${kinds.joinToString()}", listOf(path))
        env.onFilesChanged(listOf(path), false)
        env.openFile(path)
        "Created $path — import it with: import { createCrate } from './$path'"
    }

    /** Re-encodes an image as WebP (lossy 85) next to the original. Never deletes the original. */
    fun convertToWebp(path: String) = op("Convert to WebP") {
        val fs = env.project.fs
        val bmp = BitmapFactory.decodeFile(fs.file(path).absolutePath) ?: throw IllegalArgumentException("Not a decodable image")
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bmp.compress(format, 85, out)
        val dest = fs.uniquePath(path.substringBeforeLast('.') + ".webp")
        fs.writeBytes(dest, out.toByteArray())
        "Saved $dest (${out.size() / 1024} KB, was ${fs.file(path).length() / 1024} KB)"
    }

    fun resize(path: String, maxSide: Int) = op("Resize image") {
        val fs = env.project.fs
        val src = BitmapFactory.decodeFile(fs.file(path).absolutePath) ?: throw IllegalArgumentException("Not a decodable image")
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        if (scale >= 1f) return@op "Already ${src.width}×${src.height} (≤ $maxSide)"
        val dst = Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
        val out = ByteArrayOutputStream()
        val png = path.lowercase().endsWith(".png")
        dst.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
        val snap = env.project.snapshots.create("Before resizing $path", "manual", listOf(path))
        env.recordSnapshot(snap)
        fs.writeBytes(path, out.toByteArray())
        "Resized to ${dst.width}×${dst.height} (snapshot saved)"
    }

    fun generateImage(prompt: String, texture: Boolean) = op("AI image") {
        val s = env.settings.value
        val key = env.container.secureStore.get(SecureStore.IMAGE_API_KEY) ?: throw IllegalStateException("Image provider API key missing")
        val provider = HttpImageGenerationProvider(s.imageEndpoint, key, s.imageModel)
        val asset = if (texture) provider.generateTexture(prompt) else provider.generateImage(prompt)
        val dest = env.project.fs.uniquePath("assets/generated/${asset.fileName}")
        env.project.fs.writeBytes(dest, asset.bytes)
        env.project.addHistory("ai", "Generated image asset: $prompt", listOf(dest))
        "Saved $dest"
    }
}

package com.foldforge.studio.core.web

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.drawToBitmap
import com.foldforge.studio.core.qa.ViewportPreset
import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.storage.SafePaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

enum class ConsoleLevel { LOG, INFO, WARN, ERROR, DEBUG }

data class ConsoleEntry(
    val id: Long,
    val level: ConsoleLevel,
    val message: String,
    val path: String?,
    val line: Int,
    val time: Long = System.currentTimeMillis(),
    val stack: String? = null,
)

enum class PreviewStatus { STOPPED, LOADING, RUNNING, ERROR }

data class PreviewConfig(
    val fpsLimit: Int = 0,
    val networkAllowed: Boolean = true,
)

/**
 * Owns the single preview WebView (kept across fold/unfold and pane moves) and implements the
 * sandbox: project files are served from `https://appassets.androidplatform.net/project/…` with the
 * FOLD FORGE runtime injected; file:// and content:// access are disabled; no JavaScript interface
 * is exposed; top-level navigation away from the sandbox is intercepted.
 */
class PreviewHost(private val activity: Activity, private val runtimeScript: () -> String) {
    private var webView: WebView? = null
    private var fs: ProjectFileSystem? = null
    private var entry: String = "index.html"
    private var config = PreviewConfig()
    private var pageReady: CompletableDeferred<Unit>? = null

    private val _status = MutableStateFlow(PreviewStatus.STOPPED)
    val status: StateFlow<PreviewStatus> = _status.asStateFlow()
    private val _viewport = MutableStateFlow<ViewportPreset?>(null)
    val viewport: StateFlow<ViewportPreset?> = _viewport.asStateFlow()
    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    var onConsole: (ConsoleEntry) -> Unit = {}
    var onExternalNavigation: (String) -> Unit = {}
    private var nextId = 1L

    @SuppressLint("SetJavaScriptEnabled")
    fun view(): WebView {
        webView?.let { return it }
        val wv = WebView(activity)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.webViewClient = client
        wv.webChromeClient = chrome
        webView = wv
        return wv
    }

    fun detachFromParent() {
        (webView?.parent as? ViewGroup)?.removeView(webView)
    }

    val isRunning get() = _status.value == PreviewStatus.RUNNING || _status.value == PreviewStatus.LOADING

    fun run(projectFs: ProjectFileSystem, entryPath: String, cfg: PreviewConfig) {
        fs = projectFs
        entry = SafePaths.normalize(entryPath)
        config = cfg
        pageReady = CompletableDeferred()
        _status.value = PreviewStatus.LOADING
        val target = "https://$HOST/project/$entry"
        _url.value = target
        view().loadUrl(target)
    }

    fun reload() {
        if (fs == null) return
        pageReady = CompletableDeferred()
        _status.value = PreviewStatus.LOADING
        webView?.reload()
    }

    fun stop() {
        webView?.stopLoading()
        webView?.loadUrl("about:blank")
        _status.value = PreviewStatus.STOPPED
    }

    fun setViewport(preset: ViewportPreset?) { _viewport.value = preset }

    fun rotate() {
        val v = _viewport.value ?: return
        _viewport.value = v.copy(id = v.id + "-r", label = v.label + " (rotated)", width = v.height, height = v.width)
    }

    fun pause() = webView?.onPause()
    fun resume() = webView?.onResume()

    fun destroy() {
        detachFromParent()
        webView?.destroy()
        webView = null
        _status.value = PreviewStatus.STOPPED
    }

    /** Waits until the current page load finishes (or timeout). */
    suspend fun awaitReady(timeoutMs: Long = 15_000): Boolean = withTimeoutOrNull(timeoutMs) { pageReady?.await(); true } ?: false

    /** Evaluates JS in the page, returning a decoded string (null for null/undefined). */
    suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext null
        val raw = suspendCancellableCoroutine { cont -> wv.evaluateJavascript(script) { cont.resume(it) } }
        decodeJsResult(raw)
    }

    /** Captures the preview (including WebGL content) as PNG via PixelCopy; falls back to drawing the view. */
    suspend fun screenshot(): Bitmap? = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext null
        if (!wv.isAttachedToWindow || wv.width == 0 || wv.height == 0) return@withContext null
        val loc = IntArray(2)
        wv.getLocationInWindow(loc)
        val rect = Rect(loc[0], loc[1], loc[0] + (wv.width * wv.scaleX).toInt(), loc[1] + (wv.height * wv.scaleY).toInt())
        val bmp = Bitmap.createBitmap(rect.width().coerceAtLeast(1), rect.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(activity.window, rect, bmp, { result -> cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                cont.resume(false)
            }
        }
        if (ok) bmp else runCatching { wv.drawToBitmap() }.getOrNull()
    }

    private val client = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host == HOST) return serve(url.path ?: "/")
            if (!config.networkAllowed && (url.scheme == "http" || url.scheme == "https")) {
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", mapOf(), ByteArrayInputStream("Network access disabled in preview settings".toByteArray()))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == HOST) return false
            if (request.isForMainFrame) {
                emit(ConsoleLevel.WARN, "Navigation to external URL intercepted: $url", null, 0)
                onExternalNavigation(url.toString())
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (url.contains(HOST)) {
                _status.value = PreviewStatus.RUNNING
                pageReady?.complete(Unit)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                _status.value = PreviewStatus.ERROR
                emit(ConsoleLevel.ERROR, "Failed to load ${request.url}: ${error.description}", null, 0)
                pageReady?.complete(Unit)
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.url.host == HOST) {
                emit(ConsoleLevel.ERROR, "HTTP ${errorResponse.statusCode}: ${previewPath(request.url.toString()) ?: request.url}", previewPath(request.url.toString()), 0)
            }
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val text = message.message() ?: return true
            if (text.startsWith(STACK_PREFIX)) {
                onConsole(ConsoleEntry(-1, ConsoleLevel.DEBUG, text, null, 0, stack = text.removePrefix(STACK_PREFIX)))
                return true
            }
            val level = when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.ERROR
                ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.WARN
                ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.DEBUG
                ConsoleMessage.MessageLevel.TIP -> ConsoleLevel.INFO
                else -> ConsoleLevel.LOG
            }
            emit(level, text, previewPath(message.sourceId().orEmpty()), message.lineNumber())
            return true
        }
    }

    private fun emit(level: ConsoleLevel, message: String, path: String?, line: Int) {
        onConsole(ConsoleEntry(nextId++, level, message, path, line))
    }

    private fun serve(path: String): WebResourceResponse {
        if (path == "/__ff/runtime.js") return ok("text/javascript", runtimeScript().toByteArray())
        if (!path.startsWith("/project/")) return notFound()
        val projectFs = fs ?: return notFound()
        return try {
            var rel = SafePaths.normalize(java.net.URLDecoder.decode(path.removePrefix("/project/"), "UTF-8"))
            val file = projectFs.file(rel)
            if (file.isDirectory) rel = if (rel.isEmpty()) "index.html" else "$rel/index.html"
            if (rel.startsWith(ProjectFileSystem.META_DIR) || rel.startsWith(".git/")) return notFound()
            val f = projectFs.file(rel)
            if (!f.isFile) return notFound()
            val mime = mimeFor(rel)
            val bytes = if (mime == "text/html") injectRuntime(f.readText()).toByteArray() else f.readBytes()
            ok(mime, bytes)
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun injectRuntime(html: String): String {
        val cfgJson = buildJsonObject { put("fpsLimit", config.fpsLimit); put("host", "foldforge") }
        val tag = "<script>window.__ffConfig=$cfgJson;</script><script src=\"/__ff/runtime.js\"></script>"
        val headIdx = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (headIdx != null) html.substring(0, headIdx.range.last + 1) + tag + html.substring(headIdx.range.last + 1)
        else tag + html
    }

    private fun ok(mime: String, bytes: ByteArray) = WebResourceResponse(
        mime, if (mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript")) "utf-8" else null,
        200, "OK", mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "https://$HOST"), ByteArrayInputStream(bytes),
    )

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf(), ByteArrayInputStream("Not found".toByteArray()))

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val STACK_PREFIX = "__ffstack:"
        private val json = Json { ignoreUnknownKeys = true }

        fun decodeJsResult(raw: String?): String? {
            if (raw == null || raw == "null" || raw == "undefined") return null
            return if (raw.startsWith("\"")) runCatching { json.decodeFromString(JsonPrimitive.serializer(), raw).content }.getOrNull() else raw
        }

        fun previewPath(url: String): String? = com.foldforge.studio.core.ai.CodingAgent.previewPathOf(url)

        fun mimeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "html", "htm" -> "text/html"
            "js", "mjs", "cjs" -> "text/javascript"
            "css" -> "text/css"
            "json", "gltf", "map" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "glb" -> "model/gltf-binary"
            "obj" -> "text/plain"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "wasm" -> "application/wasm"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt", "md", "glsl", "vert", "frag" -> "text/plain"
            else -> "application/octet-stream"
        }

        fun pngBytes(bmp: Bitmap): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        /** Mean/variance of luminance from a coarse grid sample (black/frozen screen detection). */
        fun lumaStats(bmp: Bitmap): Pair<Double, Double> {
            val samples = ArrayList<Double>()
            val stepX = (bmp.width / 24).coerceAtLeast(1)
            val stepY = (bmp.height / 24).coerceAtLeast(1)
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    val c = bmp.getPixel(x, y)
                    samples += 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
                    x += stepX
                }
                y += stepY
            }
            val mean = samples.average()
            val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
            return mean to variance
        }
    }
}

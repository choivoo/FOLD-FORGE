package com.foldforge.studio.core.templates

import com.foldforge.studio.core.model.FoldForgeJson
import com.foldforge.studio.core.model.ProjectType
import kotlinx.serialization.Serializable
import java.io.IOException

@Serializable
data class TemplateInfo(
    val id: String,
    val name: String,
    val description: String,
    val type: ProjectType,
    val entry: String,
    val tags: List<String> = emptyList(),
    val order: Int = 100,
    /** destination path -> path under the templates root (shared vendor files). */
    val shared: Map<String, String> = emptyMap(),
    val generator: String? = null,
)

data class TemplateFile(val path: String, val bytes: ByteArray) {
    val isText: Boolean get() = path.substringAfterLast('.') in TEXT_EXT && !path.endsWith(".min.js")

    companion object {
        private val TEXT_EXT = setOf("html", "css", "js", "json", "md", "txt", "kt", "kts", "xml", "glsl", "ts")
    }
}

/**
 * Loads the bundled project templates from classpath resources (`foldforge/templates/`).
 * Works both on the JVM and on Android (Java resources are packaged into the APK).
 */
class TemplateCatalog(private val loader: ClassLoader = TemplateCatalog::class.java.classLoader) {

    private val index: List<String> by lazy {
        open("index.txt").bufferedReader().use { r -> r.readLines().filter { it.isNotBlank() } }
    }

    val templates: List<TemplateInfo> by lazy {
        index.filter { it.endsWith("/template.json") && !it.startsWith("_") }
            .map { FoldForgeJson.decodeFromString(TemplateInfo.serializer(), String(read(it), Charsets.UTF_8)) }
            .sortedBy { it.order }
    }

    fun get(id: String): TemplateInfo = templates.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown template '$id'")

    /** All files of a template (excluding template.json), with shared files mapped to their destinations. */
    fun files(id: String): List<TemplateFile> {
        val info = get(id)
        val prefix = "$id/"
        val own = index.filter { it.startsWith(prefix) && it != "$id/template.json" }
            .map { TemplateFile(it.removePrefix(prefix), read(it)) }
        val shared = info.shared.map { (dest, src) -> TemplateFile(dest, read(src)) }
        return own + shared
    }

    private fun open(path: String) = loader.getResourceAsStream("$ROOT/$path")
        ?: throw IOException("Template resource missing: $path")

    private fun read(path: String): ByteArray = open(path).use { it.readBytes() }

    /** The runtime script injected into previews. */
    fun runtimeScript(): String =
        (loader.getResourceAsStream("foldforge/runtime/foldforge-runtime.js")
            ?: throw IOException("Runtime script missing")).use { String(it.readBytes(), Charsets.UTF_8) }

    companion object {
        const val ROOT = "foldforge/templates"
        const val NAME_TOKEN = "{{PROJECT_NAME}}"
    }
}

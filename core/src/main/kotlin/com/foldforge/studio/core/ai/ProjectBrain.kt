package com.foldforge.studio.core.ai

import com.foldforge.studio.core.model.FileKind
import com.foldforge.studio.core.search.SymbolExtractor
import com.foldforge.studio.core.security.SecretScanner
import com.foldforge.studio.core.storage.Project
import com.foldforge.studio.core.storage.ProjectFileSystem
import kotlinx.serialization.Serializable

@Serializable
data class BrainFile(val path: String, val kind: String, val size: Long, val lines: Int = 0)

@Serializable
data class BrainSymbol(val name: String, val kind: String, val path: String, val line: Int)

@Serializable
data class BrainDependency(val from: String, val to: String)

@Serializable
data class BrainIndex(
    val builtAt: Long = 0,
    val files: List<BrainFile> = emptyList(),
    val symbols: List<BrainSymbol> = emptyList(),
    val dependencies: List<BrainDependency> = emptyList(),
    val systems: Map<String, List<String>> = emptyMap(),
    val assets: List<String> = emptyList(),
    val recentEdits: List<String> = emptyList(),
    val knownBugs: List<String> = emptyList(),
)

/**
 * Project Brain: a compact structural index of a project (files, symbols, dependencies, detected
 * gameplay systems, assets, recent edits, known bugs). Persisted to `.foldforge/ai-context.json`
 * and summarised into AI prompts instead of sending the whole project.
 */
object ProjectBrain {
    private val SYSTEM_KEYWORDS = mapOf(
        "player" to listOf("player"), "enemy-ai" to listOf("enemy", "monster", "drone", "boss"),
        "combat" to listOf("attack", "damage", "hp", "hitbox"), "physics" to listOf("gravity", "velocity", "collision", "vy"),
        "input" to listOf("keydown", "pointerdown", "touchstart", "joystick"), "hud" to listOf("hud", "score", "health bar"),
        "camera" to listOf("camera"), "save" to listOf("localstorage", "save"), "audio" to listOf("audio", "sound"),
        "rendering" to listOf("renderer", "getcontext(", "webgl", "requestanimationframe"), "level" to listOf("level", "xp"),
    )

    private val IMPORT_RE = listOf(
        Regex("import\\s+(?:[^'\"]*?from\\s+)?['\"]([^'\"]+)['\"]"),
        Regex("<script[^>]+src=[\"']([^\"']+)[\"']"),
        Regex("<link[^>]+href=[\"']([^\"']+)[\"']"),
        Regex("(?:fetch|load|loadAsync)\\(\\s*['\"]([^'\"]+)['\"]"),
        Regex("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)"),
    )

    fun resolveReference(from: String, ref: String): String? {
        if (ref.startsWith("http:") || ref.startsWith("https:") || ref.startsWith("data:") || ref.startsWith("//")) return null
        if (!ref.startsWith(".") && !ref.startsWith("/") && !ref.contains('/') && !ref.contains('.')) return null // bare module
        val base = from.substringBeforeLast('/', "")
        val raw = if (ref.startsWith("/")) ref.drop(1) else if (base.isEmpty()) ref else "$base/$ref"
        val parts = ArrayList<String>()
        for (p in raw.substringBefore('?').substringBefore('#').split('/')) {
            when (p) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) else return null; else -> parts += p }
        }
        return parts.joinToString("/")
    }

    fun dependenciesOf(path: String, text: String, importMap: Map<String, String> = emptyMap()): List<String> {
        val out = LinkedHashSet<String>()
        for (re in IMPORT_RE) re.findAll(text).forEach { m ->
            val ref = importMap[m.groupValues[1]] ?: m.groupValues[1]
            resolveReference(path, ref)?.let { out += it }
        }
        return out.toList()
    }

    fun build(project: Project, knownBugs: List<String> = emptyList()): BrainIndex {
        val fs = project.fs
        val files = ArrayList<BrainFile>()
        val symbols = ArrayList<BrainSymbol>()
        val deps = ArrayList<BrainDependency>()
        val systems = HashMap<String, MutableList<String>>()
        val assets = ArrayList<String>()
        val importMap = HashMap<String, String>()
        for (node in fs.walkFiles().take(5000)) {
            val kind = FileKind.of(node.name)
            if (!kind.isText || node.name.endsWith(".min.js") || node.size > 512_000) {
                if (kind == FileKind.IMAGE || kind == FileKind.MODEL3D || kind == FileKind.AUDIO) assets += node.path
                files += BrainFile(node.path, kind.badge, node.size)
                continue
            }
            val text = runCatching { fs.readText(node.path) }.getOrDefault("")
            files += BrainFile(node.path, kind.badge, node.size, text.count { it == '\n' } + 1)
            if (kind == FileKind.HTML) {
                Regex("\"imports\"\\s*:\\s*\\{([^}]*)\\}").find(text)?.groupValues?.get(1)?.let { body ->
                    Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(body).forEach { importMap[it.groupValues[1]] = it.groupValues[2] }
                }
            }
            SymbolExtractor.extract(node.path, text).forEach { symbols += BrainSymbol(it.name, it.kind, it.path, it.line) }
            dependenciesOf(node.path, text, importMap).forEach { deps += BrainDependency(node.path, it) }
            val lower = text.lowercase()
            for ((system, keys) in SYSTEM_KEYWORDS) if (keys.any { lower.contains(it) }) systems.getOrPut(system) { mutableListOf() } += node.path
        }
        val recent = project.history().entries.take(15).map { "${it.author}: ${it.message}" }
        return BrainIndex(System.currentTimeMillis(), files, symbols.take(2000), deps, systems, assets, recent, knownBugs)
    }

    fun save(project: Project, index: BrainIndex) = project.writeJson("ai-context.json", BrainIndex.serializer(), index)
    fun load(project: Project): BrainIndex? = project.readJson("ai-context.json", BrainIndex.serializer())?.takeIf { it.builtAt > 0 }

    /** Compact textual summary for prompts. */
    fun summarize(index: BrainIndex, maxChars: Int = 6000): String {
        val sb = StringBuilder()
        sb.append("FILES (${index.files.size}):\n")
        index.files.take(150).forEach { sb.append("- ${it.path} [${it.kind}${if (it.lines > 0) ", ${it.lines} lines" else ""}]\n") }
        if (index.systems.isNotEmpty()) {
            sb.append("GAMEPLAY SYSTEMS:\n")
            index.systems.forEach { (k, v) -> sb.append("- $k: ${v.take(4).joinToString()}\n") }
        }
        if (index.symbols.isNotEmpty()) {
            sb.append("SYMBOLS:\n")
            index.symbols.groupBy { it.path }.forEach { (p, s) -> sb.append("- $p: ${s.take(25).joinToString { it.name }}\n") }
        }
        if (index.dependencies.isNotEmpty()) {
            sb.append("DEPENDENCIES:\n")
            index.dependencies.take(80).forEach { sb.append("- ${it.from} -> ${it.to}\n") }
        }
        if (index.recentEdits.isNotEmpty()) sb.append("RECENT EDITS:\n").append(index.recentEdits.joinToString("\n") { "- $it" }).append('\n')
        if (index.knownBugs.isNotEmpty()) sb.append("KNOWN BUGS:\n").append(index.knownBugs.joinToString("\n") { "- $it" }).append('\n')
        return if (sb.length > maxChars) sb.substring(0, maxChars) + "\n…(truncated)" else sb.toString()
    }
}

enum class ContextScope { CURRENT_FILE, RELATED_FILES, WHOLE_PROJECT }

data class ContextFile(val path: String, val content: String, val reason: String, val redacted: Boolean)

data class SelectedContext(val files: List<ContextFile>, val excludedSensitive: List<String>, val truncated: Boolean) {
    val totalChars get() = files.sumOf { it.content.length }
}

/**
 * Chooses which files go to the AI: active file, referenced/dependent files, error files and recent
 * edits first, within a character budget. Sensitive files (.env, keystores…) are never included and
 * secrets inside included files are redacted.
 */
class ContextSelector(private val fs: ProjectFileSystem) {

    fun select(
        scope: ContextScope,
        activeFile: String?,
        index: BrainIndex?,
        errorFiles: List<String> = emptyList(),
        recentFiles: List<String> = emptyList(),
        budgetChars: Int = 60_000,
    ): SelectedContext {
        val ordered = LinkedHashMap<String, String>()
        activeFile?.let { ordered[it] = "active file" }
        if (scope != ContextScope.CURRENT_FILE) {
            val deps = index?.dependencies.orEmpty()
            activeFile?.let { a ->
                deps.filter { it.from == a }.forEach { ordered.putIfAbsent(it.to, "referenced by active file") }
                deps.filter { it.to == a }.forEach { ordered.putIfAbsent(it.from, "references active file") }
            }
            errorFiles.forEach { ordered.putIfAbsent(it, "has runtime errors") }
            recentFiles.forEach { ordered.putIfAbsent(it, "recently changed") }
            index?.files?.firstOrNull { it.path.endsWith("index.html") }?.let { ordered.putIfAbsent(it.path, "entry point") }
            if (scope == ContextScope.WHOLE_PROJECT) {
                fs.walkFiles().forEach { ordered.putIfAbsent(it.path, "project file") }
            }
        }
        val files = ArrayList<ContextFile>()
        val excluded = ArrayList<String>()
        var used = 0
        var truncated = false
        for ((path, reason) in ordered) {
            if (SecretScanner.isSensitiveFile(path)) { excluded += path; continue }
            if (!runCatching { fs.exists(path) && !fs.isDirectory(path) }.getOrDefault(false)) continue
            if (!FileKind.of(path.substringAfterLast('/')).isText || path.endsWith(".min.js")) continue
            val raw = runCatching { fs.readText(path) }.getOrNull() ?: continue
            val redactedText = SecretScanner.redact(raw)
            if (used + redactedText.length > budgetChars) { truncated = true; continue }
            used += redactedText.length
            files += ContextFile(path, redactedText, reason, redactedText != raw)
        }
        return SelectedContext(files, excluded, truncated)
    }
}

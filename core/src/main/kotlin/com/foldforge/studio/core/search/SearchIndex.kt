package com.foldforge.studio.core.search

import com.foldforge.studio.core.editor.EditorOps
import com.foldforge.studio.core.model.FileKind
import com.foldforge.studio.core.storage.ProjectFileSystem

data class SearchResult(val path: String, val line: Int, val column: Int, val lineText: String, val matchLength: Int)

data class Symbol(val name: String, val kind: String, val path: String, val line: Int)

data class Reference(val path: String, val line: Int, val column: Int, val lineText: String, val isDefinition: Boolean)

/**
 * Incremental in-memory text index. [refresh] only re-reads files whose size or modification time
 * changed, so repeated global searches on large projects stay cheap. Binary/huge files are skipped.
 */
class SearchIndex(private val fs: ProjectFileSystem, private val maxFileBytes: Long = 1_000_000) {
    private data class Entry(val modified: Long, val size: Long, val text: String, val symbols: List<Symbol>)

    private val entries = HashMap<String, Entry>()

    val fileCount get() = synchronized(entries) { entries.size }

    /** Returns number of (re)indexed files. */
    fun refresh(): Int = synchronized(entries) {
        var changed = 0
        val seen = HashSet<String>()
        for (node in fs.walkFiles()) {
            if (!FileKind.of(node.name).isText || node.size > maxFileBytes || node.name.endsWith(".min.js")) continue
            seen += node.path
            val existing = entries[node.path]
            if (existing != null && existing.modified == node.lastModified && existing.size == node.size) continue
            val text = runCatching { fs.readText(node.path) }.getOrNull() ?: continue
            entries[node.path] = Entry(node.lastModified, node.size, text, SymbolExtractor.extract(node.path, text))
            changed++
        }
        val removed = entries.keys - seen
        removed.forEach { entries.remove(it) }
        changed + removed.size
    }

    /** Updates a single file immediately (e.g. after save) without a full walk. */
    fun update(path: String, text: String) {
        synchronized(entries) {
            entries[path] = Entry(System.currentTimeMillis(), text.length.toLong(), text, SymbolExtractor.extract(path, text))
        }
    }

    fun remove(path: String) {
        synchronized(entries) { entries.remove(path) }
    }

    fun search(query: String, caseSensitive: Boolean = false, regex: Boolean = false, wholeWord: Boolean = false, limit: Int = 2000): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        val snapshot = synchronized(entries) { entries.toSortedMap() }
        for ((path, e) in snapshot) {
            for (m in EditorOps.find(e.text, query, caseSensitive, regex, wholeWord, limit - out.size)) {
                val ls = EditorOps.lineStart(e.text, m.start)
                val le = EditorOps.lineEnd(e.text, m.start)
                out += SearchResult(path, m.line + 1, m.start - ls + 1, e.text.substring(ls, le).take(400), m.end - m.start)
                if (out.size >= limit) return out
            }
        }
        return out
    }

    fun symbols(): List<Symbol> = synchronized(entries) { entries.values.flatMap { it.symbols } }.sortedBy { it.name.lowercase() }

    fun findReferences(symbol: String): List<Reference> {
        if (!Regex("^[A-Za-z_$][A-Za-z0-9_$]*$").matches(symbol)) return emptyList()
        val defs = symbols().filter { it.name == symbol }.map { it.path to it.line }.toSet()
        return search(symbol, caseSensitive = true, wholeWord = true).map {
            Reference(it.path, it.line, it.column, it.lineText, (it.path to it.line) in defs)
        }
    }

    fun text(path: String): String? = synchronized(entries) { entries[path]?.text }
}

object SymbolExtractor {
    private val JS_PATTERNS = listOf(
        "function" to Regex("\\bfunction\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*\\("),
        "class" to Regex("\\bclass\\s+([A-Za-z_$][\\w$]*)"),
        "const" to Regex("\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>"),
        "method" to Regex("^\\s+(?:async\\s+)?([A-Za-z_$][\\w$]*)\\s*\\([^)]*\\)\\s*\\{"),
    )
    private val KOTLIN_PATTERNS = listOf(
        "function" to Regex("\\bfun\\s+(?:<[^>]+>\\s*)?(?:[\\w.]+\\.)?([A-Za-z_][\\w]*)\\s*\\("),
        "class" to Regex("\\b(?:class|object|interface)\\s+([A-Za-z_][\\w]*)"),
    )
    private val GLSL_PATTERNS = listOf("function" to Regex("^\\s*(?:void|float|vec[234]|mat[234]|int|bool)\\s+([A-Za-z_]\\w*)\\s*\\("))
    private val CSS_PATTERNS = listOf("selector" to Regex("^([.#][A-Za-z_-][\\w-]*)[^{]*\\{"))
    private val HTML_PATTERNS = listOf("id" to Regex("\\bid=\"([A-Za-z_][\\w-]*)\""))
    private val RESERVED = setOf("if", "for", "while", "switch", "catch", "function", "return")

    fun extract(path: String, text: String): List<Symbol> {
        val patterns = when (FileKind.of(path.substringAfterLast('/'))) {
            FileKind.JS, FileKind.TS -> JS_PATTERNS
            FileKind.KOTLIN, FileKind.GRADLE -> KOTLIN_PATTERNS
            FileKind.GLSL -> GLSL_PATTERNS
            FileKind.CSS -> CSS_PATTERNS
            FileKind.HTML -> HTML_PATTERNS
            else -> return emptyList()
        }
        val out = ArrayList<Symbol>()
        text.split('\n').forEachIndexed { i, line ->
            if (line.length > 1000) return@forEachIndexed
            for ((kind, re) in patterns) {
                re.findAll(line).forEach { m ->
                    val name = m.groupValues[1]
                    if (name !in RESERVED) out += Symbol(name, kind, path, i + 1)
                }
            }
        }
        return out.distinctBy { Triple(it.name, it.path, it.line) }
    }
}

package com.foldforge.studio.core.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Immutable editor text + selection. `selStart <= selEnd` is not required (selection direction preserved). */
data class TextState(val text: String, val selStart: Int, val selEnd: Int = selStart) {
    val min get() = minOf(selStart, selEnd)
    val max get() = maxOf(selStart, selEnd)
    val hasSelection get() = selStart != selEnd
    fun coerce(): TextState {
        val s = selStart.coerceIn(0, text.length)
        val e = selEnd.coerceIn(0, text.length)
        return if (s == selStart && e == selEnd) this else copy(selStart = s, selEnd = e)
    }
}

data class SearchMatch(val start: Int, val end: Int, val line: Int)

/**
 * Undo/redo history. Consecutive single-character typing within [coalesceMs] is merged into one
 * undo step so undo behaves like a desktop editor.
 */
class EditHistory(private val limit: Int = 500, private val coalesceMs: Long = 800) {
    private val undo = ArrayDeque<TextState>()
    private val redo = ArrayDeque<TextState>()
    private var lastPushTime = 0L
    private var lastWasTyping = false

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /** Record [before] as the state to return to, prior to applying an edit. */
    fun record(before: TextState, typing: Boolean, now: Long = System.currentTimeMillis()) {
        val merge = typing && lastWasTyping && now - lastPushTime < coalesceMs && undo.isNotEmpty()
        if (!merge) {
            undo.addLast(before)
            if (undo.size > limit) undo.removeFirst()
        }
        redo.clear()
        lastPushTime = now
        lastWasTyping = typing
    }

    fun undo(current: TextState): TextState? {
        val prev = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        lastWasTyping = false
        return prev
    }

    fun redo(current: TextState): TextState? {
        val next = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        lastWasTyping = false
        return next
    }

    fun clear() { undo.clear(); redo.clear() }
}

object EditorOps {
    private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')
    private val CLOSERS = setOf(')', ']', '}')
    const val INDENT = "  "

    fun lineStart(text: String, offset: Int): Int = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(-1)).let { if (offset == 0) 0 else it + 1 }
    fun lineEnd(text: String, offset: Int): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }

    fun lineOf(text: String, offset: Int): Int {
        var line = 0
        val end = offset.coerceAtMost(text.length)
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }

    fun offsetOfLine(text: String, line: Int): Int {
        if (line <= 0) return 0
        var count = 0
        for (i in text.indices) if (text[i] == '\n') { count++; if (count == line) return i + 1 }
        return text.length
    }

    fun lineCount(text: String) = text.count { it == '\n' } + 1

    fun replaceSelection(s: TextState, insert: String): TextState {
        val t = s.text.substring(0, s.min) + insert + s.text.substring(s.max)
        val caret = s.min + insert.length
        return TextState(t, caret)
    }

    /** Newline with auto-indentation; splits `{|}` into an indented block. */
    fun newline(s: TextState): TextState {
        val text = s.text
        val ls = lineStart(text, s.min)
        val indent = text.substring(ls, s.min).takeWhile { it == ' ' || it == '\t' }
        val before = text.substring(ls, s.min).trimEnd().lastOrNull()
        val after = text.getOrNull(s.max)
        val opens = before == '{' || before == '[' || before == '(' || (before == '>' && isOpeningTagBefore(text, s.min))
        return if (opens && after != null && (after == '}' || after == ']' || after == ')' || (after == '<' && text.getOrNull(s.max + 1) == '/'))) {
            val insert = "\n$indent$INDENT\n$indent"
            val t = text.substring(0, s.min) + insert + text.substring(s.max)
            TextState(t, s.min + 1 + indent.length + INDENT.length)
        } else {
            replaceSelection(s, "\n" + indent + if (opens) INDENT else "")
        }
    }

    private fun isOpeningTagBefore(text: String, offset: Int): Boolean {
        val lt = text.lastIndexOf('<', offset - 1)
        if (lt < 0) return false
        val tag = text.substring(lt, offset)
        return !tag.startsWith("</") && !tag.endsWith("/>") && !tag.startsWith("<!") &&
            tag.drop(1).takeWhile { it.isLetterOrDigit() }.lowercase() !in VOID_TAGS
    }

    private val VOID_TAGS = setOf("br", "img", "input", "meta", "link", "hr", "source", "area", "base", "col", "embed", "track", "wbr")

    /** Handles typing of a character, applying auto-closing pairs and skip-over of closers. */
    fun typeChar(s: TextState, c: Char): TextState {
        val text = s.text
        if (!s.hasSelection && (c in CLOSERS || c == '"' || c == '\'' || c == '`') && text.getOrNull(s.min) == c) {
            return TextState(text, s.min + 1)
        }
        val close = PAIRS[c]
        if (close != null) {
            if (s.hasSelection) {
                val inner = text.substring(s.min, s.max)
                val t = text.substring(0, s.min) + c + inner + close + text.substring(s.max)
                return TextState(t, s.min + 1, s.min + 1 + inner.length)
            }
            val prev = text.getOrNull(s.min - 1)
            val next = text.getOrNull(s.min)
            val isQuote = c == '"' || c == '\'' || c == '`'
            val nextOk = next == null || next.isWhitespace() || next in ")]};,>"
            val quoteAfterWord = isQuote && prev != null && (prev.isLetterOrDigit() || prev == '\\')
            val allow = nextOk && !quoteAfterWord
            if (allow) {
                val t = text.substring(0, s.min) + c + close + text.substring(s.min)
                return TextState(t, s.min + 1)
            }
        }
        if (c in CLOSERS && !s.hasSelection) {
            // Outdent a line that only contains whitespace before a closer.
            val ls = lineStart(text, s.min)
            val prefix = text.substring(ls, s.min)
            if (prefix.isNotEmpty() && prefix.isBlank() && prefix.length >= INDENT.length) {
                val t = text.substring(0, s.min - INDENT.length) + c + text.substring(s.min)
                return TextState(t, s.min - INDENT.length + 1)
            }
        }
        return replaceSelection(s, c.toString())
    }

    /** Backspace that also deletes an empty auto-inserted pair `(|)`. */
    fun backspace(s: TextState): TextState {
        if (s.hasSelection) return replaceSelection(s, "")
        if (s.min == 0) return s
        val text = s.text
        val prev = text[s.min - 1]
        val next = text.getOrNull(s.min)
        if (PAIRS[prev] != null && PAIRS[prev] == next) {
            return TextState(text.removeRange(s.min - 1, s.min + 1), s.min - 1)
        }
        return TextState(text.removeRange(s.min - 1, s.min), s.min - 1)
    }

    /** Tab: inserts indent, or indents all selected lines. */
    fun indent(s: TextState): TextState {
        if (!s.hasSelection) return replaceSelection(s, INDENT)
        return transformLines(s) { INDENT + it }
    }

    fun outdent(s: TextState): TextState = transformLines(s) { line ->
        when {
            line.startsWith(INDENT) -> line.removePrefix(INDENT)
            line.startsWith("\t") -> line.removePrefix("\t")
            line.startsWith(" ") -> line.removePrefix(" ")
            else -> line
        }
    }

    private fun transformLines(s: TextState, f: (String) -> String): TextState {
        val text = s.text
        val start = lineStart(text, s.min)
        val endOffset = if (s.max > s.min && text.getOrNull(s.max - 1) == '\n') s.max - 1 else s.max
        val end = lineEnd(text, endOffset)
        val block = text.substring(start, end)
        val transformed = block.split('\n').joinToString("\n") { f(it) }
        val t = text.substring(0, start) + transformed + text.substring(end)
        return TextState(t, start, start + transformed.length)
    }

    fun toggleComment(s: TextState, language: EditorLanguage): TextState {
        val (open, close) = when (language) {
            EditorLanguage.HTML, EditorLanguage.XML, EditorLanguage.MARKDOWN -> "<!-- " to " -->"
            EditorLanguage.CSS -> "/* " to " */"
            EditorLanguage.JSON, EditorLanguage.PLAIN -> return s
            else -> "// " to ""
        }
        val text = s.text
        val start = lineStart(text, s.min)
        val end = lineEnd(text, s.max)
        val lines = text.substring(start, end).split('\n')
        val allCommented = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith(open.trim()) }
        val out = lines.joinToString("\n") { line ->
            if (line.isBlank()) line
            else if (allCommented) {
                val idx = line.indexOf(open.trim())
                var rest = line.substring(idx + open.trim().length).removePrefix(" ")
                if (close.isNotEmpty()) rest = rest.removeSuffix(close.trim()).removeSuffix(" ")
                line.substring(0, idx) + rest
            } else {
                val ind = line.takeWhile { it == ' ' || it == '\t' }
                ind + open + line.substring(ind.length) + close
            }
        }
        val t = text.substring(0, start) + out + text.substring(end)
        return TextState(t, start, start + out.length)
    }

    /** Returns the offset of the bracket matching the one at or just before [offset], or null. */
    fun matchBracket(text: String, offset: Int): Pair<Int, Int>? {
        val candidates = listOf(offset, offset - 1).filter { it in text.indices }
        for (pos in candidates) {
            val c = text[pos]
            val (open, close, forward) = when (c) {
                '(' -> Triple('(', ')', true); ')' -> Triple('(', ')', false)
                '[' -> Triple('[', ']', true); ']' -> Triple('[', ']', false)
                '{' -> Triple('{', '}', true); '}' -> Triple('{', '}', false)
                else -> continue
            }
            var depth = 0
            var i = pos
            while (i in text.indices) {
                val ch = text[i]
                if (ch == open) depth += if (forward) 1 else -1
                else if (ch == close) depth += if (forward) -1 else 1
                if (depth == 0) return pos to i
                i += if (forward) 1 else -1
            }
            return null
        }
        return null
    }

    fun find(text: String, query: String, caseSensitive: Boolean = false, regex: Boolean = false, wholeWord: Boolean = false, limit: Int = 10_000): List<SearchMatch> {
        if (query.isEmpty()) return emptyList()
        val pattern = try {
            val base = if (regex) query else Regex.escape(query)
            val wrapped = if (wholeWord) "\\b(?:$base)\\b" else base
            Regex(wrapped, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        val out = ArrayList<SearchMatch>()
        var line = 0
        var scanned = 0
        for (m in pattern.findAll(text)) {
            if (m.range.isEmpty()) continue
            for (i in scanned until m.range.first) if (text[i] == '\n') line++
            scanned = m.range.first
            out += SearchMatch(m.range.first, m.range.last + 1, line)
            if (out.size >= limit) break
        }
        return out
    }

    fun replaceAll(text: String, query: String, replacement: String, caseSensitive: Boolean = false, regex: Boolean = false, wholeWord: Boolean = false): Pair<String, Int> {
        val matches = find(text, query, caseSensitive, regex, wholeWord)
        if (matches.isEmpty()) return text to 0
        val sb = StringBuilder()
        var last = 0
        for (m in matches) {
            sb.append(text, last, m.start).append(replacement)
            last = m.end
        }
        sb.append(text, last, text.length)
        return sb.toString() to matches.size
    }

    // ------------------------------------------------------------------ formatting

    private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** Formats code. Returns null if the language is unsupported or the input is invalid (e.g. bad JSON). */
    fun format(text: String, language: EditorLanguage): String? = when (language) {
        EditorLanguage.JSON -> try {
            prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) + "\n"
        } catch (e: Exception) {
            null
        }
        EditorLanguage.JAVASCRIPT, EditorLanguage.TYPESCRIPT, EditorLanguage.CSS, EditorLanguage.GLSL,
        EditorLanguage.KOTLIN, EditorLanguage.GRADLE -> reindentBraces(text, language)
        EditorLanguage.HTML, EditorLanguage.XML -> reindentMarkup(text)
        else -> null
    }

    private fun reindentBraces(text: String, language: EditorLanguage): String {
        val tokens = Highlighter.highlight(text, language).filter { it.type == TokenType.STRING || it.type == TokenType.COMMENT }
        // For each offset inside a string/comment, remember where that token started, so braces
        // inside them are ignored and lines continuing a multi-line comment/string stay verbatim.
        val tokenStart = IntArray(text.length) { -1 }
        for (t in tokens) for (i in t.start until t.end.coerceAtMost(text.length)) tokenStart[i] = t.start
        val out = StringBuilder()
        var depth = 0
        var offset = 0
        val lines = text.split('\n')
        for ((idx, raw) in lines.withIndex()) {
            val trimmed = raw.trim()
            val firstChar = offset + (raw.length - raw.trimStart().length)
            val continuesToken = trimmed.isNotEmpty() && firstChar < text.length && tokenStart[firstChar] in 0 until offset
            var leadingClosers = 0
            for (ch in trimmed) if (ch == '}' || ch == ']' || ch == ')') leadingClosers++ else break
            val lineDepth = (depth - leadingClosers).coerceAtLeast(0)
            val line = when {
                trimmed.isEmpty() -> ""
                continuesToken -> raw.trimEnd()
                else -> INDENT.repeat(lineDepth) + trimmed
            }
            out.append(line)
            if (idx < lines.size - 1) out.append('\n')
            for (i in raw.indices) {
                val o = offset + i
                if (o < tokenStart.size && tokenStart[o] >= 0) continue
                when (raw[i]) { '{', '[', '(' -> depth++; '}', ']', ')' -> depth = (depth - 1).coerceAtLeast(0) }
            }
            offset += raw.length + 1
        }
        return out.toString()
    }

    private fun reindentMarkup(text: String): String {
        val out = StringBuilder()
        var depth = 0
        var inRaw: String? = null
        val lines = text.split('\n')
        for ((idx, raw) in lines.withIndex()) {
            val t = raw.trim()
            if (inRaw != null) {
                if (t.startsWith("</$inRaw", ignoreCase = true)) {
                    inRaw = null
                    depth = (depth - 1).coerceAtLeast(0)
                    out.append(INDENT.repeat(depth)).append(t)
                } else {
                    out.append(raw.trimEnd()) // script/style bodies are kept verbatim
                }
            } else {
                val closesFirst = t.startsWith("</")
                if (closesFirst) depth = (depth - 1).coerceAtLeast(0)
                out.append(if (t.isEmpty()) "" else INDENT.repeat(depth) + t)
                val opens = Regex("<([A-Za-z][A-Za-z0-9-]*)[^>]*?(/?)>").findAll(t).count { m ->
                    m.groupValues[2] != "/" && m.groupValues[1].lowercase() !in VOID_TAGS
                }
                val closes = Regex("</[A-Za-z][A-Za-z0-9-]*\\s*>").findAll(t).count()
                depth = (depth + opens - closes + (if (closesFirst) 1 else 0)).coerceAtLeast(0)
                val rawOpen = Regex("<(script|style)(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(t)
                if (rawOpen != null && !t.contains("</${rawOpen.groupValues[1]}", ignoreCase = true)) inRaw = rawOpen.groupValues[1].lowercase()
            }
            if (idx < lines.size - 1) out.append('\n')
        }
        return out.toString()
    }
}

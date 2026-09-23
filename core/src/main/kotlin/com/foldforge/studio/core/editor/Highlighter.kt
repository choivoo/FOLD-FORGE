package com.foldforge.studio.core.editor

enum class TokenType { KEYWORD, TYPE, STRING, NUMBER, COMMENT, TAG, ATTRIBUTE, PUNCTUATION, FUNCTION, PROPERTY, OPERATOR, HEADING, EMPHASIS, CODE, LINK, CONSTANT }

data class Token(val start: Int, val end: Int, val type: TokenType)

enum class EditorLanguage(val displayName: String) {
    HTML("HTML"), CSS("CSS"), JAVASCRIPT("JavaScript"), TYPESCRIPT("TypeScript"), JSON("JSON"),
    MARKDOWN("Markdown"), GLSL("GLSL"), KOTLIN("Kotlin"), XML("XML"), GRADLE("Gradle"), PLAIN("Plain text");

    companion object {
        fun forFile(name: String): EditorLanguage {
            val lower = name.lowercase()
            if (lower.endsWith(".gradle.kts") || lower.endsWith(".gradle")) return GRADLE
            return when (lower.substringAfterLast('.', "")) {
                "html", "htm" -> HTML
                "css" -> CSS
                "js", "mjs", "cjs", "jsx" -> JAVASCRIPT
                "ts", "tsx" -> TYPESCRIPT
                "json", "gltf" -> JSON
                "md", "markdown" -> MARKDOWN
                "glsl", "vert", "frag", "vs", "fs" -> GLSL
                "kt", "kts" -> KOTLIN
                "xml", "svg" -> XML
                else -> PLAIN
            }
        }
    }
}

/**
 * Fast single-pass syntax highlighters. They never throw and always return tokens sorted by start
 * offset and non-overlapping, so a UI layer can map them directly to span styles.
 */
object Highlighter {
    private val JS_KEYWORDS = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else",
        "export", "extends", "finally", "for", "function", "if", "import", "in", "instanceof", "let", "new",
        "return", "super", "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with", "yield",
        "async", "await", "of", "static", "get", "set", "from", "as",
    )
    private val JS_CONSTANTS = setOf("true", "false", "null", "undefined", "NaN", "Infinity")
    private val TS_EXTRA = setOf(
        "interface", "type", "enum", "implements", "private", "public", "protected", "readonly", "abstract",
        "declare", "namespace", "keyof", "never", "unknown", "any", "number", "string", "boolean", "is",
    )
    private val GLSL_KEYWORDS = setOf(
        "attribute", "const", "uniform", "varying", "in", "out", "inout", "layout", "centroid", "flat", "smooth",
        "break", "continue", "do", "for", "while", "if", "else", "discard", "return", "struct", "precision",
        "highp", "mediump", "lowp", "void", "true", "false",
    )
    private val GLSL_TYPES = setOf(
        "float", "int", "uint", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "bvec2", "bvec3", "bvec4",
        "mat2", "mat3", "mat4", "sampler2D", "samplerCube", "sampler3D",
    )
    private val KOTLIN_KEYWORDS = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for",
        "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw", "is", "in", "as",
        "private", "public", "internal", "protected", "override", "open", "abstract", "data", "sealed", "enum",
        "companion", "suspend", "inline", "lateinit", "const", "init", "constructor", "this", "super", "by",
        "typealias", "null", "true", "false", "operator", "infix", "reified", "vararg",
    )

    fun highlight(text: String, language: EditorLanguage): List<Token> = try {
        when (language) {
            EditorLanguage.JAVASCRIPT -> cLike(text, JS_KEYWORDS, JS_CONSTANTS, emptySet(), templates = true, regex = true)
            EditorLanguage.TYPESCRIPT -> cLike(text, JS_KEYWORDS + TS_EXTRA, JS_CONSTANTS, emptySet(), templates = true, regex = true)
            EditorLanguage.GLSL -> cLike(text, GLSL_KEYWORDS, emptySet(), GLSL_TYPES, preprocessor = true)
            EditorLanguage.KOTLIN, EditorLanguage.GRADLE -> cLike(text, KOTLIN_KEYWORDS, emptySet(), emptySet(), templates = false, tripleQuotes = true, annotations = true)
            EditorLanguage.JSON -> json(text)
            EditorLanguage.CSS -> css(text, 0, text.length)
            EditorLanguage.HTML, EditorLanguage.XML -> markup(text, embedded = language == EditorLanguage.HTML)
            EditorLanguage.MARKDOWN -> markdown(text)
            EditorLanguage.PLAIN -> emptyList()
        }
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    fun cLike(
        text: String,
        keywords: Set<String>,
        constants: Set<String>,
        types: Set<String>,
        start: Int = 0,
        end: Int = text.length,
        templates: Boolean = false,
        regex: Boolean = false,
        preprocessor: Boolean = false,
        tripleQuotes: Boolean = false,
        annotations: Boolean = false,
    ): List<Token> {
        val out = ArrayList<Token>()
        var i = start
        var lastSignificant: Char = '\u0000'
        while (i < end) {
            val c = text[i]
            when {
                c == '/' && i + 1 < end && text[i + 1] == '/' -> {
                    val e = text.indexOf('\n', i).let { if (it < 0 || it > end) end else it }
                    out += Token(i, e, TokenType.COMMENT); i = e
                }
                c == '/' && i + 1 < end && text[i + 1] == '*' -> {
                    val e = text.indexOf("*/", i + 2).let { if (it < 0 || it + 2 > end) end else it + 2 }
                    out += Token(i, e, TokenType.COMMENT); i = e
                }
                preprocessor && c == '#' -> {
                    val e = text.indexOf('\n', i).let { if (it < 0 || it > end) end else it }
                    out += Token(i, e, TokenType.KEYWORD); i = e
                }
                annotations && c == '@' && i + 1 < end && isIdentStart(text[i + 1]) -> {
                    var e = i + 1
                    while (e < end && isIdentPart(text[e])) e++
                    out += Token(i, e, TokenType.TYPE); i = e
                }
                tripleQuotes && text.startsWith("\"\"\"", i) -> {
                    val e = text.indexOf("\"\"\"", i + 3).let { if (it < 0 || it + 3 > end) end else it + 3 }
                    out += Token(i, e, TokenType.STRING); i = e; lastSignificant = '"'
                }
                c == '"' || c == '\'' || (templates && c == '`') -> {
                    var e = i + 1
                    while (e < end && text[e] != c) {
                        if (text[e] == '\\') e++
                        else if (text[e] == '\n' && c != '`') break
                        e++
                    }
                    e = minOf(end, e + 1)
                    out += Token(i, e, TokenType.STRING); i = e; lastSignificant = '"'
                }
                regex && c == '/' && (lastSignificant == '\u0000' || lastSignificant in "(,=:[!&|?{};+-*%<>~^")
                    && i + 1 < end && text[i + 1] != ' ' -> {
                    var e = i + 1
                    var inClass = false
                    while (e < end && text[e] != '\n') {
                        val ch = text[e]
                        if (ch == '\\') { e += 2; continue }
                        if (ch == '[') inClass = true else if (ch == ']') inClass = false
                        else if (ch == '/' && !inClass) break
                        e++
                    }
                    if (e < end && text[e] == '/') {
                        e++
                        while (e < end && text[e].isLetter()) e++
                        out += Token(i, e, TokenType.STRING); i = e; lastSignificant = '"'
                    } else {
                        out += Token(i, i + 1, TokenType.OPERATOR); i++; lastSignificant = '/'
                    }
                }
                c.isDigit() || (c == '.' && i + 1 < end && text[i + 1].isDigit()) -> {
                    var e = i + 1
                    while (e < end && (text[e].isLetterOrDigit() || text[e] == '.' || text[e] == '_')) e++
                    out += Token(i, e, TokenType.NUMBER); i = e; lastSignificant = '0'
                }
                isIdentStart(c) -> {
                    var e = i + 1
                    while (e < end && isIdentPart(text[e])) e++
                    val word = text.substring(i, e)
                    var next = e
                    while (next < end && text[next] == ' ') next++
                    val type = when {
                        word in keywords -> TokenType.KEYWORD
                        word in constants -> TokenType.CONSTANT
                        word in types -> TokenType.TYPE
                        next < end && text[next] == '(' -> TokenType.FUNCTION
                        i > start && text[i - 1] == '.' -> TokenType.PROPERTY
                        word[0].isUpperCase() -> TokenType.TYPE
                        else -> null
                    }
                    if (type != null) out += Token(i, e, type)
                    lastSignificant = if (word in keywords && word != "this" && word != "super") '(' else 'a'
                    i = e
                }
                c in "{}()[];,." -> { out += Token(i, i + 1, TokenType.PUNCTUATION); lastSignificant = c; i++ }
                c in "=+-*%<>!&|^~?:" -> { out += Token(i, i + 1, TokenType.OPERATOR); lastSignificant = c; i++ }
                else -> i++
            }
        }
        return out
    }

    private fun json(text: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    var e = i + 1
                    while (e < text.length && text[e] != '"' && text[e] != '\n') { if (text[e] == '\\') e++; e++ }
                    e = minOf(text.length, e + 1)
                    var n = e
                    while (n < text.length && text[n].isWhitespace()) n++
                    out += Token(i, e, if (n < text.length && text[n] == ':') TokenType.PROPERTY else TokenType.STRING)
                    i = e
                }
                c == '-' || c.isDigit() -> {
                    var e = i + 1
                    while (e < text.length && (text[e].isDigit() || text[e] in ".eE+-")) e++
                    out += Token(i, e, TokenType.NUMBER); i = e
                }
                text.startsWith("true", i) || text.startsWith("null", i) -> { out += Token(i, i + 4, TokenType.CONSTANT); i += 4 }
                text.startsWith("false", i) -> { out += Token(i, i + 5, TokenType.CONSTANT); i += 5 }
                c in "{}[]:," -> { out += Token(i, i + 1, TokenType.PUNCTUATION); i++ }
                else -> i++
            }
        }
        return out
    }

    fun css(text: String, start: Int, end: Int): List<Token> {
        val out = ArrayList<Token>()
        var i = start
        var depth = 0
        while (i < end) {
            val c = text[i]
            when {
                c == '/' && i + 1 < end && text[i + 1] == '*' -> {
                    val e = text.indexOf("*/", i + 2).let { if (it < 0 || it + 2 > end) end else it + 2 }
                    out += Token(i, e, TokenType.COMMENT); i = e
                }
                c == '"' || c == '\'' -> {
                    var e = i + 1
                    while (e < end && text[e] != c && text[e] != '\n') e++
                    e = minOf(end, e + 1)
                    out += Token(i, e, TokenType.STRING); i = e
                }
                c == '{' -> { depth++; out += Token(i, i + 1, TokenType.PUNCTUATION); i++ }
                c == '}' -> { depth = maxOf(0, depth - 1); out += Token(i, i + 1, TokenType.PUNCTUATION); i++ }
                c == '@' -> {
                    var e = i + 1
                    while (e < end && (text[e].isLetterOrDigit() || text[e] == '-')) e++
                    out += Token(i, e, TokenType.KEYWORD); i = e
                }
                depth > 0 && (c.isLetter() || c == '-') -> {
                    var e = i + 1
                    while (e < end && (text[e].isLetterOrDigit() || text[e] == '-')) e++
                    var n = e
                    while (n < end && text[n] == ' ') n++
                    out += Token(i, e, if (n < end && text[n] == ':') TokenType.PROPERTY else TokenType.CONSTANT)
                    i = e
                }
                depth > 0 && (c.isDigit() || c == '#') -> {
                    var e = i + 1
                    while (e < end && (text[e].isLetterOrDigit() || text[e] == '.' || text[e] == '%')) e++
                    out += Token(i, e, TokenType.NUMBER); i = e
                }
                depth == 0 && (c.isLetter() || c in ".#:*[") -> {
                    var e = i + 1
                    while (e < end && text[e] != '{' && text[e] != ',' && text[e] != '\n') e++
                    out += Token(i, e, TokenType.TAG); i = e
                }
                else -> i++
            }
        }
        return out
    }

    private fun markup(text: String, embedded: Boolean): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (text.startsWith("<!--", i)) {
                val e = text.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                out += Token(i, e, TokenType.COMMENT); i = e; continue
            }
            if (text[i] == '<' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '/' || text[i + 1] == '!' || text[i + 1] == '?')) {
                var e = i + 1
                if (text[e] == '/' || text[e] == '!' || text[e] == '?') e++
                while (e < n && (text[e].isLetterOrDigit() || text[e] in "-:_")) e++
                out += Token(i, e, TokenType.TAG)
                val tagName = text.substring(i + 1, e).trimStart('/', '!', '?').lowercase()
                val closing = i + 1 < n && text[i + 1] == '/'
                i = e
                while (i < n && text[i] != '>') {
                    val c = text[i]
                    when {
                        c == '"' || c == '\'' -> {
                            val q = text.indexOf(c, i + 1).let { if (it < 0) n else it + 1 }
                            out += Token(i, q, TokenType.STRING); i = q
                        }
                        c.isLetter() -> {
                            var a = i + 1
                            while (a < n && (text[a].isLetterOrDigit() || text[a] in "-:_")) a++
                            out += Token(i, a, TokenType.ATTRIBUTE); i = a
                        }
                        c == '/' -> { out += Token(i, i + 1, TokenType.TAG); i++ }
                        else -> i++
                    }
                }
                if (i < n) { out += Token(i, i + 1, TokenType.TAG); i++ }
                if (embedded && !closing && (tagName == "script" || tagName == "style")) {
                    val close = text.indexOf("</$tagName", i, ignoreCase = true).let { if (it < 0) n else it }
                    val inner = if (tagName == "script") {
                        cLike(text, JS_KEYWORDS, JS_CONSTANTS, emptySet(), start = i, end = close, templates = true, regex = true)
                    } else css(text, i, close)
                    out += inner
                    i = close
                }
                continue
            }
            if (text[i] == '&') {
                val semi = text.indexOf(';', i)
                if (semi in (i + 2)..(i + 10)) { out += Token(i, semi + 1, TokenType.CONSTANT); i = semi + 1; continue }
            }
            i++
        }
        return out
    }

    private fun markdown(text: String): List<Token> {
        val out = ArrayList<Token>()
        var lineStart = 0
        var inFence = false
        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)
            when {
                line.trimStart().startsWith("```") -> { out += Token(lineStart, lineEnd, TokenType.CODE); inFence = !inFence }
                inFence -> if (lineEnd > lineStart) out += Token(lineStart, lineEnd, TokenType.CODE)
                line.startsWith("#") -> out += Token(lineStart, lineEnd, TokenType.HEADING)
                else -> {
                    if (line.trimStart().startsWith(">")) out += Token(lineStart, lineEnd, TokenType.COMMENT)
                    else inlineMarkdown(text, lineStart, lineEnd, out)
                }
            }
            lineStart = lineEnd + 1
        }
        return out
    }

    private fun inlineMarkdown(text: String, start: Int, end: Int, out: MutableList<Token>) {
        var i = start
        while (i < end) {
            val c = text[i]
            if (c == '`') {
                val e = text.indexOf('`', i + 1).let { if (it < 0 || it >= end) -1 else it + 1 }
                if (e > 0) { out += Token(i, e, TokenType.CODE); i = e; continue }
            }
            if (c == '*' || c == '_') {
                val double = i + 1 < end && text[i + 1] == c
                val marker = if (double) "$c$c" else "$c"
                val e = text.indexOf(marker, i + marker.length).let { if (it < 0 || it >= end) -1 else it + marker.length }
                if (e > i + marker.length) { out += Token(i, e, TokenType.EMPHASIS); i = e; continue }
            }
            if (c == '[') {
                val close = text.indexOf("](", i).let { if (it < 0 || it >= end) -1 else it }
                val paren = if (close > 0) text.indexOf(')', close).let { if (it < 0 || it >= end) -1 else it + 1 } else -1
                if (paren > 0) { out += Token(i, paren, TokenType.LINK); i = paren; continue }
            }
            i++
        }
    }
}

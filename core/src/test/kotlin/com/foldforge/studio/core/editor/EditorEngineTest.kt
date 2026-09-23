package com.foldforge.studio.core.editor

import com.foldforge.studio.core.templates.TemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEngineTest {

    @Test fun `newline keeps indent and expands braces`() {
        val s = EditorOps.newline(TextState("  if (x) {}", 11 - 1))
        assertEquals("  if (x) {\n    \n  }", s.text)
        assertEquals(15, s.selStart)
        val plain = EditorOps.newline(TextState("    foo();", 10))
        assertEquals("    foo();\n    ", plain.text)
    }

    @Test fun `auto pairs, skip-over and pair deletion`() {
        var s = EditorOps.typeChar(TextState("", 0), '(')
        assertEquals("()", s.text); assertEquals(1, s.selStart)
        s = EditorOps.typeChar(s, ')')
        assertEquals("()", s.text); assertEquals(2, s.selStart)
        val wrapped = EditorOps.typeChar(TextState("abc", 0, 3), '[')
        assertEquals("[abc]", wrapped.text)
        val deleted = EditorOps.backspace(TextState("{}", 1))
        assertEquals("", deleted.text)
        val noQuoteAfterWord = EditorOps.typeChar(TextState("don", 3), '\'')
        assertEquals("don'", noQuoteAfterWord.text)
    }

    @Test fun `indent and outdent selected lines`() {
        val s = EditorOps.indent(TextState("a\nb", 0, 3))
        assertEquals("  a\n  b", s.text)
        assertEquals("a\nb", EditorOps.outdent(TextState(s.text, 0, s.text.length)).text)
    }

    @Test fun `toggle comment`() {
        val on = EditorOps.toggleComment(TextState("let a;\nlet b;", 0, 13), EditorLanguage.JAVASCRIPT)
        assertEquals("// let a;\n// let b;", on.text)
        assertEquals("let a;\nlet b;", EditorOps.toggleComment(TextState(on.text, 0, on.text.length), EditorLanguage.JAVASCRIPT).text)
    }

    @Test fun `bracket matching`() {
        val text = "f(a[1], {b: 2})"
        assertEquals(1 to 14, EditorOps.matchBracket(text, 1))
        assertEquals(8 to 13, EditorOps.matchBracket(text, 8))
        assertEquals(14 to 1, EditorOps.matchBracket(text, 15)) // caret just after ')'
        assertNull(EditorOps.matchBracket("(()", 0))
    }

    @Test fun `find and replace with options`() {
        val t = "Enemy enemy ENEMY enemyCount"
        assertEquals(4, EditorOps.find(t, "enemy").size)
        assertEquals(1, EditorOps.find(t, "enemy", caseSensitive = true, wholeWord = true).size)
        assertEquals(3, EditorOps.find(t, "enemy", wholeWord = true).size)
        assertEquals(1, EditorOps.find(t, "e[nN]emy\\b", regex = true, caseSensitive = true).size)
        assertTrue(EditorOps.find(t, "(", regex = true).isEmpty()) // invalid regex is safe
        val (out, n) = EditorOps.replaceAll(t, "enemy", "foe", wholeWord = true)
        assertEquals(3, n)
        assertEquals("foe foe foe enemyCount", out)
    }

    @Test fun `format json and braces`() {
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    1,\n    2\n  ]\n}\n", EditorOps.format("{\"a\":1,\"b\":[1,2]}", EditorLanguage.JSON))
        assertNull(EditorOps.format("{bad", EditorLanguage.JSON))
        val js = "function a() {\nif (x) {\nreturn '{';\n}\n}"
        assertEquals("function a() {\n  if (x) {\n    return '{';\n  }\n}", EditorOps.format(js, EditorLanguage.JAVASCRIPT))
    }

    @Test fun `undo history coalesces typing`() {
        val h = EditHistory()
        var s = TextState("", 0)
        for ((i, c) in "abc".withIndex()) {
            h.record(s, typing = true, now = 1000L + i * 100)
            s = EditorOps.typeChar(s, c)
        }
        h.record(s, typing = false, now = 5000)
        val afterPaste = EditorOps.replaceSelection(s, " pasted")
        val back = h.undo(afterPaste)!!
        assertEquals("abc", back.text)
        assertEquals("", h.undo(back)!!.text)
        assertFalse(h.canUndo)
        assertEquals("abc", h.redo(TextState("", 0))!!.text)
    }

    @Test fun `highlighter tokens are sorted and in range for all template sources`() {
        val catalog = TemplateCatalog()
        for (t in catalog.templates) for (f in catalog.files(t.id).filter { it.isText }) {
            val text = String(f.bytes, Charsets.UTF_8)
            val lang = EditorLanguage.forFile(f.path)
            val tokens = Highlighter.highlight(text, lang)
            var last = 0
            for (tok in tokens) {
                assertTrue("${f.path}: token $tok out of order", tok.start >= last)
                assertTrue("${f.path}: token $tok out of range", tok.end <= text.length && tok.end > tok.start)
                last = tok.end
            }
            if (lang != EditorLanguage.PLAIN && text.length > 50) assertTrue("${t.id}/${f.path} produced no tokens", tokens.isNotEmpty())
        }
    }

    @Test fun `javascript highlighting classifies basics`() {
        val text = "const x = 'hi'; // note\nfunction go() { return 42; }"
        val types = Highlighter.highlight(text, EditorLanguage.JAVASCRIPT).associate { text.substring(it.start, it.end) to it.type }
        assertEquals(TokenType.KEYWORD, types["const"])
        assertEquals(TokenType.STRING, types["'hi'"])
        assertEquals(TokenType.COMMENT, types["// note"])
        assertEquals(TokenType.FUNCTION, types["go"])
        assertEquals(TokenType.NUMBER, types["42"])
    }
}

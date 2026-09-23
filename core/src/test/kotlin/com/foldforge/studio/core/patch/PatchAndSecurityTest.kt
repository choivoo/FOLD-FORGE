package com.foldforge.studio.core.patch

import com.foldforge.studio.core.ai.BrainIndex
import com.foldforge.studio.core.ai.ContextScope
import com.foldforge.studio.core.ai.ContextSelector
import com.foldforge.studio.core.ai.ProjectBrain
import com.foldforge.studio.core.search.SearchIndex
import com.foldforge.studio.core.security.GradleCommand
import com.foldforge.studio.core.security.SecretScanner
import com.foldforge.studio.core.security.UrlValidator
import com.foldforge.studio.core.storage.Project
import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.storage.ProjectStore
import com.foldforge.studio.core.tempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchAndSecurityTest {

    @Test fun `diff counts and unified format`() {
        val before = "a\nb\nc\nd\n"
        val after = "a\nB\nc\nd\ne\n"
        assertEquals(2 to 1, Diff.stats(before, after))
        val u = Diff.unified("f.txt", before, after)
        assertTrue(u.contains("--- a/f.txt"))
        assertTrue(u.contains("-b\n+B"))
        assertTrue(u.contains("+e"))
        assertEquals(3 to 0, Diff.stats(null, "1\n2\n3\n"))
    }

    @Test fun `myers diff reconstructs both sides`() {
        val a = "the quick brown fox jumps over the lazy dog".split(' ')
        val b = "the slow brown cat jumps high over the dog".split(' ')
        val d = Diff.diffLines(a, b)
        assertEquals(a, d.filter { it.op != DiffOp.INSERT }.map { it.text })
        assertEquals(b, d.filter { it.op != DiffOp.DELETE }.map { it.text })
    }

    @Test fun `search replace edits apply exactly once`() {
        val src = "let speed = 10;\nlet hp = 5;\n"
        assertEquals("let speed = 11.5;\nlet hp = 5;\n", PatchEngine.applyEdits(src, listOf(SearchReplace("speed = 10", "speed = 11.5"))).getOrThrow())
        assertTrue(PatchEngine.applyEdits("x\nx\n", listOf(SearchReplace("x", "y"))).isFailure)
        assertTrue(PatchEngine.applyEdits(src, listOf(SearchReplace("missing", "y"))).isFailure)
        // whitespace-tolerant fallback
        val fuzzy = PatchEngine.applyEdits("  if (a) {\n    go();\n  }\n", listOf(SearchReplace("if (a) {\n  go();\n}", "if (b) {\n    go();\n  }"))).getOrThrow()
        assertEquals("if (b) {\n    go();\n  }\n", fuzzy)
    }

    @Test fun `patch engine prepares reviews and applies selected changes safely`() {
        val fs = ProjectFileSystem(tempDir())
        fs.writeText("src/game.js", "const SPEED = 10;\n")
        fs.writeText("old.js", "x")
        val engine = PatchEngine(fs)
        val prepared = engine.prepare(
            ChangeSet(
                "tune",
                listOf(
                    FileChange("src/game.js", ChangeKind.MODIFY, edits = listOf(SearchReplace("SPEED = 10", "SPEED = 11.5"))),
                    FileChange("src/boss.js", ChangeKind.CREATE, content = "export class Boss {}\n"),
                    FileChange("old.js", ChangeKind.DELETE),
                    FileChange("../escape.js", ChangeKind.CREATE, content = "x"),
                    FileChange(".foldforge/project.json", ChangeKind.MODIFY, content = "{}"),
                    FileChange("src/game.js", ChangeKind.MODIFY, edits = listOf(SearchReplace("nope", "x"))),
                ),
            ),
        )
        assertEquals(3, prepared.applicable.size)
        assertEquals(3, prepared.files.count { !it.ok })
        assertEquals(2, prepared.added)
        val selected = prepared.applicable.filter { it.path != "old.js" }
        val touched = engine.apply(selected)
        assertEquals(listOf("src/game.js", "src/boss.js"), touched)
        assertEquals("const SPEED = 11.5;\n", fs.readText("src/game.js"))
        assertTrue(fs.exists("old.js"))
        // stale apply guard
        fs.writeText("old.js", "changed meanwhile")
        assertThrows(IllegalStateException::class.java) { engine.apply(prepared.applicable.filter { it.path == "old.js" }) }
    }

    @Test fun `secret scanner detects and redacts without leaking`() {
        val text = "const key = 'sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123';\nconst gh = 'ghp_abcdefghijklmnopqrstuvwxyz0123456789';\n"
        val findings = SecretScanner.scanText("a.js", text)
        assertEquals(2, findings.size)
        assertFalse(findings.any { it.preview.contains("abcdefghijklmnop") })
        val redacted = SecretScanner.redact(text)
        assertFalse(redacted.contains("sk-ant-api03"))
        assertTrue(redacted.contains("[REDACTED:"))
        listOf(".env", "config/.env.local", "release.keystore", "app/key.jks", "local.properties").forEach { assertTrue(it, SecretScanner.isSensitiveFile(it)) }
        assertFalse(SecretScanner.isSensitiveFile(".env.example"))
        assertFalse(SecretScanner.isSensitiveFile("src/env.js"))
    }

    @Test fun `git url validation`() {
        assertNull(UrlValidator.validateGitUrl("https://github.com/user/repo.git"))
        assertNull(UrlValidator.validateGitUrl("git@github.com:user/repo.git"))
        assertNotNull(UrlValidator.validateGitUrl("file:///etc"))
        assertNotNull(UrlValidator.validateGitUrl("https://user:pass@github.com/u/r.git"))
        assertNotNull(UrlValidator.validateGitUrl("--upload-pack=touch /tmp/x"))
        assertNotNull(UrlValidator.validateGitUrl("http://github.com/u/r"))
        assertNotNull(UrlValidator.validateGitUrl("https://github.com/"))
        assertEquals("https://github.com/owner/repo.git", UrlValidator.normalizeGitHub("owner/repo"))
        assertEquals("owner" to "repo", UrlValidator.githubOwnerRepo("https://github.com/owner/repo.git"))
    }

    @Test fun `gradle command allow-list blocks injection`() {
        assertEquals(listOf("./gradlew", "assembleDebug"), GradleCommand.build(listOf("assembleDebug")))
        assertThrows(IllegalArgumentException::class.java) { GradleCommand.build(listOf("assembleDebug; rm -rf /")) }
        assertThrows(IllegalArgumentException::class.java) { GradleCommand.build(listOf("test"), listOf("-Dorg.gradle.jvmargs=x")) }
    }

    @Test fun `search index is incremental and finds references`() {
        val fs = ProjectFileSystem(tempDir())
        fs.writeText("src/a.js", "function spawnEnemy() {}\nspawnEnemy();\n")
        fs.writeText("src/b.js", "import './a.js';\nspawnEnemy();\n")
        fs.writeText("logo.png", "binary")
        val idx = SearchIndex(fs)
        assertEquals(2, idx.refresh())
        assertEquals(0, idx.refresh())
        val refs = idx.findReferences("spawnEnemy")
        assertEquals(3, refs.size)
        assertEquals(1, refs.count { it.isDefinition })
        fs.delete("src/b.js")
        assertEquals(1, idx.refresh())
        assertEquals(2, idx.search("spawnenemy").size)
        assertTrue(idx.symbols().any { it.name == "spawnEnemy" && it.kind == "function" })
    }

    @Test fun `project brain and context selection`() {
        val store = ProjectStore(tempDir())
        val p: Project = store.createFromTemplate("RPG", "rpg3d")
        p.fs.writeText(".env", "API_KEY=sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123")
        val brain: BrainIndex = ProjectBrain.build(p, listOf("combat: hitbox"))
        assertTrue(brain.dependencies.any { it.from == "src/main.js" && it.to == "src/world.js" })
        assertTrue(brain.dependencies.any { it.from == "index.html" && it.to == "src/main.js" })
        assertTrue("combat" in brain.systems)
        assertTrue(ProjectBrain.summarize(brain).contains("KNOWN BUGS"))
        val ctx = ContextSelector(p.fs).select(ContextScope.RELATED_FILES, "src/main.js", brain, budgetChars = 200_000)
        assertEquals("src/main.js", ctx.files.first().path)
        assertTrue(ctx.files.any { it.path == "src/entities.js" })
        assertFalse(ctx.files.any { it.path.endsWith(".min.js") })
        val whole = ContextSelector(p.fs).select(ContextScope.WHOLE_PROJECT, null, brain, budgetChars = 500_000)
        assertTrue(".env" in whole.excludedSensitive)
        assertFalse(whole.files.any { it.path == ".env" })
        val single = ContextSelector(p.fs).select(ContextScope.CURRENT_FILE, "src/hud.js", brain)
        assertEquals(listOf("src/hud.js"), single.files.map { it.path })
        assertEquals("src/x.js", ProjectBrain.resolveReference("src/main.js", "./x.js"))
        assertEquals("vendor/three.module.min.js", ProjectBrain.resolveReference("index.html", "./vendor/three.module.min.js"))
        assertNull(ProjectBrain.resolveReference("a.js", "https://cdn/x.js"))
    }
}

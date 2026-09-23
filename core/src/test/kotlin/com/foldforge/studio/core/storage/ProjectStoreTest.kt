package com.foldforge.studio.core.storage

import com.foldforge.studio.core.export.WebExporter
import com.foldforge.studio.core.model.ProjectType
import com.foldforge.studio.core.templates.TemplateCatalog
import com.foldforge.studio.core.tempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ProjectStoreTest {
    private val catalog = TemplateCatalog()

    @Test fun `catalog exposes all required templates`() {
        val ids = catalog.templates.map { it.id }
        listOf(
            "blank-web", "html-app", "canvas-game", "threejs-game", "rpg3d", "platformer",
            "puzzle", "interactive-story", "mobile-ui", "android-webview-wrapper", "forge-runner",
        ).forEach { assertTrue("missing template $it", it in ids) }
        assertTrue(catalog.runtimeScript().contains("FoldForgeTestInput"))
    }

    @Test fun `every template creates a valid project with its entry file`() {
        val store = ProjectStore(tempDir(), catalog)
        for (t in catalog.templates) {
            val p = store.createFromTemplate("Test ${t.name}", t.id)
            assertTrue("${t.id}: entry ${p.meta.entry} missing", p.fs.exists(p.meta.entry))
            assertEquals(t.type, p.meta.type)
            val entry = p.fs.readText(p.meta.entry)
            assertFalse("${t.id}: placeholder not replaced", entry.contains(TemplateCatalog.NAME_TOKEN))
            assertTrue(File(p.root, ".foldforge/project.json").isFile)
            listOf("history.json", "ai-context.json", "qa.json", "build.json").forEach { assertTrue("${t.id} $it", File(p.root, ".foldforge/$it").isFile) }
            assertTrue(p.history().entries.isNotEmpty())
        }
        val three = store.list().first { it.type == ProjectType.THREEJS }
        assertTrue(File(three.path, "vendor/three.module.min.js").length() > 500_000)
        val wrapper = store.list().first { it.type == ProjectType.ANDROID }
        assertTrue(File(wrapper.path, "app/build.gradle.kts").isFile)
        assertTrue(File(wrapper.path, "gradlew").canExecute())
    }

    @Test fun `project listing summarises metadata`() {
        val store = ProjectStore(tempDir(), catalog)
        store.createFromTemplate("Monster RPG", "rpg3d")
        val s = store.list().single()
        assertEquals("Monster RPG", s.name)
        assertEquals("monster-rpg", s.id)
        assertTrue(s.fileCount >= 8)
        assertTrue(s.previewReady)
        assertFalse(s.hasGit)
        assertEquals("Not built", s.buildStatus)
    }

    @Test fun `duplicate names get unique folders`() {
        val store = ProjectStore(tempDir(), catalog)
        val a = store.createFromTemplate("Game", "blank-web")
        val b = store.createFromTemplate("Game", "blank-web")
        assertEquals("game", a.id)
        assertEquals("game-2", b.id)
    }

    @Test fun `invalid project json is rejected`() {
        val dir = tempDir()
        File(dir, ".foldforge").mkdirs()
        File(dir, ".foldforge/project.json").writeText("{ not json")
        assertThrows(InvalidProjectException::class.java) { Project.readMeta(dir) }
        File(dir, ".foldforge/project.json").writeText("""{"name":"x","entry":"../../etc/passwd"}""")
        assertThrows(UnsafePathException::class.java) { Project.readMeta(dir) }
        File(dir, ".foldforge/project.json").writeText("""{"name":"   "}""")
        assertThrows(InvalidProjectException::class.java) { Project.readMeta(dir) }
        File(dir, ".foldforge/project.json").writeText("""{"name":"Monster RPG","type":"threejs","version":"1.0.0","entry":"index.html"}""")
        assertEquals(ProjectType.THREEJS, Project.readMeta(dir).type)
    }

    @Test fun `zip export import round trip keeps files and excludes snapshots`() {
        val store = ProjectStore(tempDir(), catalog)
        val p = store.createFromTemplate("Runner", "forge-runner")
        p.snapshots.create("s", "manual")
        val bos = ByteArrayOutputStream()
        WebExporter.exportZip(p.root, bos)
        val imported = store.importZip(ByteArrayInputStream(bos.toByteArray()), "Runner Copy")
        assertEquals("Runner", imported.meta.name) // metadata travels with the ZIP
        assertTrue(imported.fs.exists("runner.js"))
        assertFalse(File(imported.root, ".foldforge/snapshots").listFiles()?.isNotEmpty() == true)
    }

    @Test fun `plain web zip import is detected`() {
        val src = tempDir()
        File(src, "index.html").writeText("<canvas></canvas>")
        val bos = ByteArrayOutputStream()
        ZipTools.zipDirectory(src, bos)
        val store = ProjectStore(tempDir(), catalog)
        val p = store.importZip(ByteArrayInputStream(bos.toByteArray()), "Imported")
        assertEquals(ProjectType.CANVAS, p.meta.type)
        assertEquals("index.html", p.meta.entry)
    }

    @Test fun `file system operations`() {
        val fs = ProjectFileSystem(tempDir())
        fs.createFolder("", "src")
        val path = fs.createFile("src", "a.js", "1")
        assertEquals("src/a.js", path)
        assertThrows(java.io.IOException::class.java) { fs.createFile("src", "a.js") }
        assertEquals("src/b.js", fs.rename(path, "b.js"))
        assertEquals("src/b copy.js", fs.duplicate("src/b.js"))
        fs.createFolder("", "lib")
        assertEquals("lib/b.js", fs.move("src/b.js", "lib"))
        assertEquals("lib/b copy.js", fs.copy("src/b copy.js", "lib"))
        assertThrows(java.io.IOException::class.java) { fs.move("lib", "lib") }
        assertThrows(UnsafePathException::class.java) { fs.delete("") }
        assertThrows(UnsafePathException::class.java) { fs.createFile("", "../x.js") }
        fs.delete("lib")
        assertFalse(fs.exists("lib/b.js"))
        assertEquals(listOf("src"), fs.listChildren().map { it.name })
    }

    @Test fun `partial snapshot restore undoes an AI change including created files`() {
        val fs = ProjectFileSystem(tempDir())
        fs.writeText("a.js", "original")
        val store = SnapshotStore(fs)
        val snap = store.create("before ai", "ai-edit", listOf("a.js", "new.js"))
        fs.writeText("a.js", "changed by ai")
        fs.writeText("new.js", "created by ai")
        val safety = store.restore(snap.id)
        assertEquals("original", fs.readText("a.js"))
        assertFalse(fs.exists("new.js"))
        store.restore(safety.id) // restore is undoable
        assertEquals("changed by ai", fs.readText("a.js"))
        assertNotNull(store.fileContent(snap.id, "a.js"))
    }

    @Test fun `full snapshot restores whole tree`() {
        val fs = ProjectFileSystem(tempDir())
        fs.writeText("a.txt", "1")
        val store = SnapshotStore(fs)
        val snap = store.create("full", "manual")
        fs.writeText("a.txt", "2")
        fs.writeText("b.txt", "extra")
        store.restore(snap.id)
        assertEquals("1", fs.readText("a.txt"))
        assertFalse(fs.exists("b.txt"))
    }
}

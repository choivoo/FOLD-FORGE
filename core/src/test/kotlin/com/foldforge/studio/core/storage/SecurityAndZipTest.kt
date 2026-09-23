package com.foldforge.studio.core.storage

import com.foldforge.studio.core.tempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SecurityAndZipTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test fun `normalize rejects traversal absolute and drive paths`() {
        listOf("../x", "a/../../b", "/etc/passwd", "C:\\Windows", "a/\u0000b", "..\\..\\x").forEach {
            assertThrows(UnsafePathException::class.java) { SafePaths.normalize(it) }
        }
        assertEquals("a/b/c.js", SafePaths.normalize("a/./b//c.js"))
        assertEquals("a/b", SafePaths.normalize("a\\b"))
    }

    @Test fun `unsafe file names are rejected`() {
        listOf("", "..", "a/b", "con", "nul.txt", "a<b", "tab\tname", " lead").forEach {
            assertNotNull("expected '$it' to be rejected", SafePaths.validateName(it))
        }
        assertNull(SafePaths.validateName("main.js"))
        assertNull(SafePaths.validateName("몬스터.png"))
    }

    @Test fun `resolve cannot escape root even through symlinks`() {
        val root = tempDir()
        val outside = tempDir()
        File(outside, "secret.txt").writeText("s")
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertThrows(UnsafePathException::class.java) { SafePaths.resolve(root, "link/secret.txt") }
    }

    @Test fun `zip slip entry is rejected and nothing is written outside`() {
        val base = tempDir()
        val target = File(base, "proj")
        val bytes = zipOf("ok.txt" to "a".toByteArray(), "../../evil.txt" to "x".toByteArray())
        assertThrows(ZipSecurityException::class.java) { ZipTools.extract(ByteArrayInputStream(bytes), target) }
        assertFalse(File(base, "evil.txt").exists())
        assertFalse(File(base.parentFile, "evil.txt").exists())
        assertTrue("partial extraction must be cleaned", target.listFiles().isNullOrEmpty())
    }

    @Test fun `absolute and backslash traversal entries are rejected`() {
        for (name in listOf("/abs/file.txt", "..\\win.txt", "C:/x.txt", "a/../../b.txt")) {
            val bytes = zipOf(name to "x".toByteArray())
            assertThrows(name, ZipSecurityException::class.java) { ZipTools.extract(ByteArrayInputStream(bytes), File(tempDir(), "t")) }
        }
    }

    @Test fun `malformed and empty zip rejected`() {
        assertThrows(ZipSecurityException::class.java) { ZipTools.extract(ByteArrayInputStream("not a zip at all".toByteArray()), File(tempDir(), "t")) }
        assertThrows(ZipSecurityException::class.java) { ZipTools.extract(ByteArrayInputStream(ByteArray(0)), File(tempDir(), "t")) }
    }

    @Test fun `oversized and bomb archives rejected`() {
        val big = ByteArray(2_000_000) // highly compressible zeros
        val bytes = zipOf("zeros.bin" to big)
        assertThrows(ZipSecurityException::class.java) {
            ZipTools.extract(ByteArrayInputStream(bytes), File(tempDir(), "t"), ZipLimits(maxEntryBytes = 1_000_000))
        }
        assertThrows(ZipSecurityException::class.java) {
            ZipTools.extract(ByteArrayInputStream(bytes), File(tempDir(), "t"), ZipLimits(maxCompressionRatio = 10, ratioThresholdBytes = 1000))
        }
        val many = zipOf(*Array(20) { "f$it.txt" to "x".toByteArray() })
        assertThrows(ZipSecurityException::class.java) { ZipTools.extract(ByteArrayInputStream(many), File(tempDir(), "t"), ZipLimits(maxEntries = 10)) }
    }

    @Test fun `zip round trip strips single root folder`() {
        val src = tempDir()
        File(src, "game/src").mkdirs()
        File(src, "game/index.html").writeText("<h1>x</h1>")
        File(src, "game/src/main.js").writeText("console.log(1)")
        val bos = ByteArrayOutputStream()
        ZipTools.zipDirectory(src, bos)
        val target = File(tempDir(), "out")
        val r = ZipTools.extract(ByteArrayInputStream(bos.toByteArray()), target)
        assertEquals("game", r.strippedPrefix)
        assertEquals(2, r.files)
        assertEquals("console.log(1)", File(target, "src/main.js").readText())
    }
}

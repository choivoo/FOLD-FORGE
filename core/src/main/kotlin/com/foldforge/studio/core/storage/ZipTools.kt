package com.foldforge.studio.core.storage

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ZipSecurityException(message: String) : IOException(message)

data class ZipLimits(
    val maxEntries: Int = 20_000,
    val maxEntryBytes: Long = 256L * 1024 * 1024,
    val maxTotalBytes: Long = 1024L * 1024 * 1024,
    /** Max uncompressed/compressed ratio for entries larger than [ratioThresholdBytes] (zip bomb guard). */
    val maxCompressionRatio: Int = 250,
    val ratioThresholdBytes: Long = 4L * 1024 * 1024,
)

data class ZipImportResult(val files: Int, val bytes: Long, val strippedPrefix: String?)

object ZipTools {

    /**
     * Zips [sourceDir] into [out]. [exclude] receives project-relative paths ('/'-separated, folders
     * end with '/') and returns true to skip them.
     */
    fun zipDirectory(sourceDir: File, out: OutputStream, exclude: (String) -> Boolean = { false }): Int {
        var count = 0
        ZipOutputStream(out).use { zos ->
            sourceDir.walkTopDown()
                .onEnter { dir -> dir == sourceDir || !exclude(SafePaths.relativize(sourceDir, dir) + "/") }
                .filter { it != sourceDir }
                .forEach { f ->
                    val rel = SafePaths.relativize(sourceDir, f)
                    if (f.isDirectory) {
                        zos.putNextEntry(ZipEntry("$rel/").apply { time = f.lastModified() })
                        zos.closeEntry()
                    } else if (!exclude(rel)) {
                        zos.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        count++
                    }
                }
        }
        return count
    }

    /**
     * Validates an entry name: rejects absolute paths, traversal, drive letters, NUL/control chars.
     * Returns the normalised relative path (possibly empty for the root directory entry).
     */
    fun sanitizeEntryName(raw: String): String {
        if (raw.isEmpty()) throw ZipSecurityException("Malformed entry with empty name")
        if (raw.any { it.code == 0 }) throw ZipSecurityException("Malformed entry name (NUL byte)")
        val unified = raw.replace('\\', '/')
        if (unified.startsWith("/")) throw ZipSecurityException("Absolute entry path rejected: $raw")
        if (Regex("^[A-Za-z]:").containsMatchIn(unified)) throw ZipSecurityException("Drive entry path rejected: $raw")
        if (unified.split('/').any { it == ".." }) throw ZipSecurityException("Path traversal entry rejected: $raw")
        return try {
            SafePaths.normalize(unified)
        } catch (e: UnsafePathException) {
            throw ZipSecurityException("Unsafe entry name '$raw': ${e.message}")
        }
    }

    /**
     * Safely extracts a ZIP stream into [targetDir]. Protects against Zip Slip, absolute paths,
     * malformed entries, oversized files, too many entries and zip bombs (actual bytes are counted,
     * headers are never trusted). If [stripSingleRoot] and every entry shares one top folder, it is removed.
     * On any failure the partially extracted content is removed.
     */
    fun extract(
        input: InputStream,
        targetDir: File,
        limits: ZipLimits = ZipLimits(),
        stripSingleRoot: Boolean = true,
    ): ZipImportResult {
        targetDir.mkdirs()
        val staging = File(targetDir.parentFile, ".${targetDir.name}.unzip-${System.nanoTime()}")
        staging.mkdirs()
        try {
            var entries = 0
            var files = 0
            var total = 0L
            val topLevel = mutableSetOf<String>()
            var hasRootFile = false
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = try {
                        zis.nextEntry
                    } catch (e: ZipException) {
                        throw ZipSecurityException("Malformed ZIP: ${e.message}")
                    } catch (e: IllegalArgumentException) {
                        throw ZipSecurityException("Malformed ZIP entry name encoding")
                    } ?: break
                    entries++
                    if (entries > limits.maxEntries) throw ZipSecurityException("Too many entries (> ${limits.maxEntries})")
                    val name = sanitizeEntryName(entry.name)
                    if (name.isEmpty()) continue
                    topLevel += name.substringBefore('/')
                    if (!name.contains('/') && !entry.isDirectory) hasRootFile = true
                    val dest = SafePaths.resolve(staging, name)
                    if (entry.isDirectory) {
                        dest.mkdirs()
                        continue
                    }
                    dest.parentFile?.mkdirs()
                    var written = 0L
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            written += n
                            total += n
                            if (written > limits.maxEntryBytes) throw ZipSecurityException("Entry '$name' exceeds size limit")
                            if (total > limits.maxTotalBytes) throw ZipSecurityException("Archive exceeds total extraction limit")
                            out.write(buf, 0, n)
                        }
                    }
                    val compressed = entry.compressedSize
                    if (compressed > 0 && written > limits.ratioThresholdBytes &&
                        written / compressed > limits.maxCompressionRatio
                    ) {
                        throw ZipSecurityException("Suspicious compression ratio for '$name' (possible zip bomb)")
                    }
                    files++
                }
            }
            if (entries == 0) throw ZipSecurityException("ZIP archive is empty or not a ZIP file")
            val strip = if (stripSingleRoot && topLevel.size == 1 && !hasRootFile) topLevel.first() else null
            val source = if (strip != null) File(staging, strip) else staging
            (source.listFiles() ?: emptyArray()).forEach { child ->
                val dest = File(targetDir, child.name)
                if (dest.exists()) dest.deleteRecursively()
                if (!child.renameTo(dest)) {
                    child.copyRecursively(dest, overwrite = true)
                }
            }
            return ZipImportResult(files, total, strip)
        } finally {
            staging.deleteRecursively()
        }
    }
}

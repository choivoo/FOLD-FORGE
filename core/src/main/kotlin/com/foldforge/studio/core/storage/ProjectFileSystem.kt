package com.foldforge.studio.core.storage

import com.foldforge.studio.core.model.FileNode
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * All file operations for a single project root. Paths are project-relative and always validated
 * through [SafePaths]. Writes are atomic (temp file + rename) so a crash never leaves a half-written file.
 */
class ProjectFileSystem(val root: File) {

    init {
        require(root.isDirectory || root.mkdirs()) { "Cannot create project root ${root.path}" }
    }

    fun file(path: String): File = SafePaths.resolve(root, path)

    fun exists(path: String): Boolean = file(path).exists()

    fun isDirectory(path: String): Boolean = file(path).isDirectory

    /** Lists direct children of [dir] (lazy, one level). Directories first, then alphabetical. */
    fun listChildren(dir: String = "", includeHidden: Boolean = false): List<FileNode> {
        val parent = file(dir)
        if (!parent.isDirectory) return emptyList()
        val depth = if (dir.isEmpty()) 0 else SafePaths.normalize(dir).count { it == '/' } + 1
        return (parent.listFiles() ?: emptyArray())
            .filter { includeHidden || !isHiddenInternal(it.name) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { toNode(it, depth) }
    }

    /** Walks the full tree (skipping internal folders) lazily as a sequence. */
    fun walkFiles(includeInternal: Boolean = false): Sequence<FileNode> =
        root.walkTopDown()
            .onEnter { dir -> dir == root || includeInternal || !isHiddenInternal(dir.name) }
            .filter { it.isFile }
            .map { f ->
                val rel = SafePaths.relativize(root, f)
                toNode(f, rel.count { it == '/' })
            }

    fun readText(path: String): String = file(path).readText(Charsets.UTF_8)

    fun readBytes(path: String): ByteArray = file(path).readBytes()

    fun writeText(path: String, content: String) = writeBytes(path, content.toByteArray(Charsets.UTF_8))

    fun writeBytes(path: String, bytes: ByteArray) {
        val target = file(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.ffwrite")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            // renameTo can fail across some file systems when target exists: fall back to replace.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun writeStream(path: String, input: InputStream, maxBytes: Long = Long.MAX_VALUE) {
        val target = file(path)
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IOException("File exceeds size limit of $maxBytes bytes")
                out.write(buf, 0, n)
            }
        }
    }

    fun createFile(parentDir: String, name: String, content: String = ""): String {
        SafePaths.validateName(name)?.let { throw UnsafePathException(it) }
        val path = join(parentDir, name)
        val f = file(path)
        if (f.exists()) throw IOException("'$path' already exists")
        writeText(path, content)
        return path
    }

    fun createFolder(parentDir: String, name: String): String {
        SafePaths.validateName(name)?.let { throw UnsafePathException(it) }
        val path = join(parentDir, name)
        val f = file(path)
        if (f.exists()) throw IOException("'$path' already exists")
        if (!f.mkdirs()) throw IOException("Could not create folder '$path'")
        return path
    }

    fun rename(path: String, newName: String): String {
        SafePaths.validateName(newName)?.let { throw UnsafePathException(it) }
        val src = file(path)
        if (!src.exists()) throw IOException("'$path' does not exist")
        val newPath = join(parentOf(path), newName)
        val dst = file(newPath)
        if (dst.exists()) throw IOException("'$newPath' already exists")
        if (!src.renameTo(dst)) throw IOException("Rename failed for '$path'")
        return newPath
    }

    fun delete(path: String) {
        val normalized = SafePaths.normalize(path)
        if (normalized.isEmpty()) throw UnsafePathException("Refusing to delete project root")
        val f = file(normalized)
        if (!f.exists()) return
        if (!f.deleteRecursively()) throw IOException("Delete failed for '$path'")
    }

    fun copy(path: String, targetDir: String, newName: String? = null): String {
        val src = file(path)
        if (!src.exists()) throw IOException("'$path' does not exist")
        val name = newName ?: src.name
        val destPath = uniquePath(join(targetDir, name))
        val dst = file(destPath)
        if (src.isDirectory && SafePaths.isWithin(src, dst)) throw IOException("Cannot copy a folder into itself")
        if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
        return destPath
    }

    fun duplicate(path: String): String {
        val src = file(path)
        val base = src.nameWithoutExtension
        val ext = if (src.isDirectory || src.extension.isEmpty()) "" else ".${src.extension}"
        return copy(path, parentOf(path), "$base copy$ext")
    }

    fun move(path: String, targetDir: String): String {
        val src = file(path)
        if (!src.exists()) throw IOException("'$path' does not exist")
        val destPath = join(targetDir, src.name)
        val dst = file(destPath)
        if (dst.exists()) throw IOException("'$destPath' already exists")
        if (src.isDirectory && SafePaths.isWithin(src, dst)) throw IOException("Cannot move a folder into itself")
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
            src.deleteRecursively()
        }
        return destPath
    }

    fun uniquePath(path: String): String {
        if (!file(path).exists()) return SafePaths.normalize(path)
        val parent = parentOf(path)
        val name = path.substringAfterLast('/')
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.') && !name.startsWith('.')) "." + name.substringAfterLast('.') else ""
        var i = 2
        while (true) {
            val candidate = join(parent, "$base $i$ext")
            if (!file(candidate).exists()) return candidate
            i++
        }
    }

    fun fileCount(): Int = walkFiles().count()

    private fun toNode(f: File, depth: Int) = FileNode(
        path = SafePaths.relativize(root, f),
        name = f.name,
        isDirectory = f.isDirectory,
        size = if (f.isFile) f.length() else 0L,
        lastModified = f.lastModified(),
        depth = depth,
    )

    companion object {
        const val META_DIR = ".foldforge"
        private val INTERNAL = setOf(META_DIR, ".git")

        fun isHiddenInternal(name: String) = name in INTERNAL

        fun join(parent: String, name: String): String {
            val p = SafePaths.normalize(parent)
            return SafePaths.normalize(if (p.isEmpty()) name else "$p/$name")
        }

        fun parentOf(path: String): String {
            val n = SafePaths.normalize(path)
            return if (n.contains('/')) n.substringBeforeLast('/') else ""
        }
    }
}

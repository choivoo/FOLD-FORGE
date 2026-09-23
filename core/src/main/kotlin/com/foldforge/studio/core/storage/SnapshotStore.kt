package com.foldforge.studio.core.storage

import com.foldforge.studio.core.model.FoldForgeJson
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class SnapshotFile(val path: String, val existed: Boolean, val size: Long = 0)

@Serializable
data class Snapshot(
    val id: String,
    val timestamp: Long,
    val label: String,
    val reason: String, // "ai-edit", "manual", "delete", "git-pull", "qa-autofix", "refactor", "restore", "dependency"
    val partial: Boolean,
    val files: List<SnapshotFile>,
)

/**
 * File-level version history stored inside the project at `.foldforge/snapshots/<id>/`.
 * Full snapshots copy the whole project (excluding .git/.foldforge and very large files);
 * partial snapshots record only the given paths, including paths that did not yet exist so that
 * restoring them deletes files created afterwards (this is how "Undo AI change" works).
 */
class SnapshotStore(private val fs: ProjectFileSystem, private val maxSnapshots: Int = 60) {
    private val dir: File get() = File(fs.root, "${ProjectFileSystem.META_DIR}/snapshots").also { it.mkdirs() }

    fun create(label: String, reason: String, paths: Collection<String>? = null): Snapshot {
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val snapDir = File(dir, id)
        val filesDir = File(snapDir, "files")
        filesDir.mkdirs()
        val entries = mutableListOf<SnapshotFile>()
        val targets: List<String> = paths?.map { SafePaths.normalize(it) }?.distinct()
            ?: fs.walkFiles().filter { it.size <= MAX_FILE_BYTES }.map { it.path }.toList()
        for (path in targets) {
            val src = fs.file(path)
            if (src.isFile) {
                val dst = SafePaths.resolve(filesDir, path)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                entries += SnapshotFile(path, existed = true, size = src.length())
            } else if (!src.exists()) {
                entries += SnapshotFile(path, existed = false)
            } else if (src.isDirectory) {
                src.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = SafePaths.relativize(fs.root, f)
                    val dst = SafePaths.resolve(filesDir, rel)
                    dst.parentFile?.mkdirs()
                    f.copyTo(dst, overwrite = true)
                    entries += SnapshotFile(rel, existed = true, size = f.length())
                }
            }
        }
        val snapshot = Snapshot(id, System.currentTimeMillis(), label, reason, paths != null, entries)
        File(snapDir, "snapshot.json").writeText(FoldForgeJson.encodeToString(Snapshot.serializer(), snapshot))
        prune()
        return snapshot
    }

    fun list(): List<Snapshot> =
        (dir.listFiles() ?: emptyArray())
            .mapNotNull { d -> runCatching { read(d.name) }.getOrNull() }
            .sortedByDescending { it.timestamp }

    fun read(id: String): Snapshot {
        val f = SafePaths.resolve(dir, "$id/snapshot.json")
        return FoldForgeJson.decodeFromString(Snapshot.serializer(), f.readText())
    }

    /** Content of a file as stored in the snapshot, or null if it did not exist then. */
    fun fileContent(id: String, path: String): ByteArray? {
        val f = SafePaths.resolve(dir, "$id/files/${SafePaths.normalize(path)}")
        return if (f.isFile) f.readBytes() else null
    }

    /**
     * Restores a snapshot. A safety snapshot of the affected files is taken first and returned,
     * so a restore itself can be undone.
     */
    fun restore(id: String): Snapshot {
        val snap = read(id)
        val affected = if (snap.partial) snap.files.map { it.path } else {
            (snap.files.map { it.path } + fs.walkFiles().map { it.path }).distinct()
        }
        val safety = create("Before restoring '${snap.label}'", "restore", affected)
        if (!snap.partial) {
            // Full restore: remove files that did not exist in the snapshot.
            val keep = snap.files.map { it.path }.toSet()
            fs.walkFiles().filter { it.path !in keep }.forEach { fs.delete(it.path) }
        }
        for (entry in snap.files) {
            if (entry.existed) {
                val bytes = fileContent(id, entry.path) ?: continue
                fs.writeBytes(entry.path, bytes)
            } else {
                fs.delete(entry.path)
            }
        }
        return safety
    }

    fun delete(id: String) {
        SafePaths.resolve(dir, id).deleteRecursively()
    }

    private fun prune() {
        val all = list()
        if (all.size > maxSnapshots) all.drop(maxSnapshots).forEach { delete(it.id) }
    }

    companion object {
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
    }
}

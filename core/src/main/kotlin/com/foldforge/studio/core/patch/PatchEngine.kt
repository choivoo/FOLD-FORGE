package com.foldforge.studio.core.patch

import com.foldforge.studio.core.security.SecretScanner
import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.storage.SafePaths
import com.foldforge.studio.core.storage.UnsafePathException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchReplace(val search: String, val replace: String)

@Serializable
enum class ChangeKind {
    @SerialName("create") CREATE,
    @SerialName("modify") MODIFY,
    @SerialName("delete") DELETE,
}

/**
 * One file change proposed by the AI (or any tool). MODIFY prefers [edits] (search/replace blocks);
 * [content] replaces the whole file and is used for CREATE or when no edits are given.
 */
@Serializable
data class FileChange(
    val path: String,
    val kind: ChangeKind,
    val content: String? = null,
    val edits: List<SearchReplace> = emptyList(),
    val reason: String = "",
)

@Serializable
data class ChangeSet(val summary: String, val changes: List<FileChange>)

data class PreparedChange(
    val change: FileChange,
    val path: String,
    val before: String?,
    val after: String?,
    val added: Int,
    val removed: Int,
    val unifiedDiff: String,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
) {
    val ok get() = error == null
}

data class PreparedChangeSet(val summary: String, val files: List<PreparedChange>) {
    val added get() = files.filter { it.ok }.sumOf { it.added }
    val removed get() = files.filter { it.ok }.sumOf { it.removed }
    val applicable get() = files.filter { it.ok }
}

/**
 * Safe patch engine: validates paths, applies search/replace edits against the current file content
 * (never blindly overwriting), computes diffs for review and applies only selected changes.
 */
class PatchEngine(private val fs: ProjectFileSystem) {

    fun prepare(set: ChangeSet): PreparedChangeSet = PreparedChangeSet(set.summary, set.changes.map { prepareOne(it) })

    private fun prepareOne(change: FileChange): PreparedChange {
        val path = try {
            SafePaths.normalize(change.path).also {
                if (it.isEmpty()) throw UnsafePathException("Empty path")
                if (it.startsWith(ProjectFileSystem.META_DIR) || it.startsWith(".git/") || it == ".git") {
                    throw UnsafePathException("AI changes to internal folder '$it' are not allowed")
                }
            }
        } catch (e: UnsafePathException) {
            return PreparedChange(change, change.path, null, null, 0, 0, "", error = e.message)
        }
        val exists = fs.exists(path) && !fs.isDirectory(path)
        val before = if (exists) fs.readText(path) else null
        val after: String? = when (change.kind) {
            ChangeKind.DELETE -> {
                if (!exists) return PreparedChange(change, path, null, null, 0, 0, "", error = "File does not exist")
                null
            }
            ChangeKind.CREATE -> change.content ?: return PreparedChange(change, path, before, null, 0, 0, "", error = "No content for new file")
            ChangeKind.MODIFY -> {
                if (!exists && change.content == null) return PreparedChange(change, path, null, null, 0, 0, "", error = "File does not exist")
                if (change.edits.isNotEmpty() && before != null) {
                    applyEdits(before, change.edits).getOrElse { return PreparedChange(change, path, before, null, 0, 0, "", error = it.message) }
                } else change.content ?: return PreparedChange(change, path, before, null, 0, 0, "", error = "No edits or content")
            }
        }
        val warnings = mutableListOf<String>()
        if (change.kind == ChangeKind.CREATE && exists) warnings += "Overwrites existing file"
        if (after != null) SecretScanner.scanText(path, after).takeIf { it.isNotEmpty() }?.let { warnings += "Possible secret in change: ${it.first().rule}" }
        val (add, rem) = Diff.stats(before, after)
        return PreparedChange(change, path, before, after, add, rem, Diff.unified(path, before, after), warnings = warnings)
    }

    /** Applies the given prepared changes. Returns the list of touched paths. */
    fun apply(changes: List<PreparedChange>): List<String> {
        val touched = mutableListOf<String>()
        for (c in changes.filter { it.ok }) {
            // Guard against the file changing between review and apply.
            val current = if (fs.exists(c.path) && !fs.isDirectory(c.path)) fs.readText(c.path) else null
            if (current != c.before) throw IllegalStateException("'${c.path}' changed since the patch was prepared; re-run the request")
            if (c.after == null) fs.delete(c.path) else fs.writeText(c.path, c.after)
            touched += c.path
        }
        return touched
    }

    companion object {
        /** Applies search/replace blocks. Each search must match exactly once (whitespace-tolerant fallback). */
        fun applyEdits(original: String, edits: List<SearchReplace>): Result<String> {
            var text = original
            for ((i, e) in edits.withIndex()) {
                if (e.search.isEmpty()) {
                    text += (if (text.endsWith("\n") || text.isEmpty()) "" else "\n") + e.replace
                    continue
                }
                val idx = text.indexOf(e.search)
                if (idx >= 0) {
                    if (text.indexOf(e.search, idx + 1) >= 0) return Result.failure(IllegalArgumentException("Edit ${i + 1}: search text matches more than once; add more context"))
                    text = text.substring(0, idx) + e.replace + text.substring(idx + e.search.length)
                    continue
                }
                // Fallback: match ignoring trailing whitespace differences per line.
                val range = fuzzyFind(text, e.search)
                    ?: return Result.failure(IllegalArgumentException("Edit ${i + 1}: search text not found in file"))
                text = text.substring(0, range.first) + e.replace + text.substring(range.last + 1)
            }
            return Result.success(text)
        }

        private fun fuzzyFind(text: String, search: String): IntRange? {
            val sLines = search.trim('\n').split('\n').map { it.trim() }
            if (sLines.isEmpty()) return null
            val tLines = text.split('\n')
            val offsets = IntArray(tLines.size + 1)
            for (i in tLines.indices) offsets[i + 1] = offsets[i] + tLines[i].length + 1
            var found: IntRange? = null
            for (start in 0..tLines.size - sLines.size) {
                var ok = true
                for (j in sLines.indices) if (tLines[start + j].trim() != sLines[j]) { ok = false; break }
                if (ok) {
                    if (found != null) return null // ambiguous
                    val end = offsets[start + sLines.size] - 2
                    found = offsets[start]..end.coerceAtMost(text.length - 1)
                }
            }
            return found
        }
    }
}

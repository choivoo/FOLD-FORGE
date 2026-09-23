package com.foldforge.studio.core.storage

import java.io.File
import java.io.IOException

class UnsafePathException(message: String) : IOException(message)

/**
 * Path safety utilities. Every project-relative path that reaches the file system goes through
 * [resolve], which rejects traversal (`..`), absolute paths, drive letters, control characters and
 * anything that canonicalises outside of the project root.
 */
object SafePaths {
    private val RESERVED_NAMES = setOf(
        "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "lpt1", "lpt2", "lpt3",
    )
    private const val MAX_NAME_LENGTH = 180
    private const val MAX_PATH_LENGTH = 1024

    /** Validates a single file or folder name (no separators). Returns an error message or null. */
    fun validateName(name: String): String? {
        if (name.isBlank()) return "Name must not be empty"
        if (name.length > MAX_NAME_LENGTH) return "Name is too long"
        if (name == "." || name == "..") return "Name '$name' is not allowed"
        if (name.any { it == '/' || it == '\\' }) return "Name must not contain path separators"
        if (name.any { it.code < 0x20 || it == '\u007f' }) return "Name contains control characters"
        if (name.any { it in "<>:\"|?*" }) return "Name contains reserved characters"
        if (name.trim() != name) return "Name must not start or end with whitespace"
        if (name.substringBefore('.').lowercase() in RESERVED_NAMES) return "Name '$name' is reserved"
        return null
    }

    /**
     * Normalises a project-relative path to a canonical '/'-separated form without leading slash.
     * Throws [UnsafePathException] for anything that is not a plain relative path.
     */
    fun normalize(relative: String): String {
        if (relative.length > MAX_PATH_LENGTH) throw UnsafePathException("Path too long")
        if (relative.any { it.code == 0 }) throw UnsafePathException("Path contains NUL byte")
        val unified = relative.replace('\\', '/')
        if (unified.startsWith("/")) throw UnsafePathException("Absolute paths are not allowed: $relative")
        if (Regex("^[A-Za-z]:").containsMatchIn(unified)) throw UnsafePathException("Drive paths are not allowed: $relative")
        val parts = unified.split('/').filter { it.isNotEmpty() && it != "." }
        for (p in parts) {
            if (p == "..") throw UnsafePathException("Path traversal is not allowed: $relative")
            validateName(p)?.let { throw UnsafePathException("Invalid path segment '$p': $it") }
        }
        return parts.joinToString("/")
    }

    /** Resolves [relative] inside [root], guaranteeing the result stays within [root]. */
    fun resolve(root: File, relative: String): File {
        val normalized = normalize(relative)
        val canonicalRoot = root.canonicalFile
        val target = if (normalized.isEmpty()) canonicalRoot else File(canonicalRoot, normalized)
        val canonicalTarget = target.canonicalFile
        if (!isWithin(canonicalRoot, canonicalTarget)) {
            throw UnsafePathException("Resolved path escapes project root: $relative")
        }
        return target
    }

    fun isWithin(root: File, candidate: File): Boolean {
        val r = root.canonicalPath
        val c = candidate.canonicalPath
        return c == r || c.startsWith(r + File.separator)
    }

    fun relativize(root: File, file: File): String =
        file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
}

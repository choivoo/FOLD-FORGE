package com.foldforge.studio.core.storage

import com.foldforge.studio.core.export.AndroidProjectExporter
import com.foldforge.studio.core.export.AndroidExportOptions
import com.foldforge.studio.core.model.BuildLog
import com.foldforge.studio.core.model.BuildRecord
import com.foldforge.studio.core.model.FoldForgeJson
import com.foldforge.studio.core.model.HistoryEntry
import com.foldforge.studio.core.model.ProjectHistory
import com.foldforge.studio.core.model.ProjectMeta
import com.foldforge.studio.core.model.ProjectType
import com.foldforge.studio.core.templates.TemplateCatalog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

data class ProjectSummary(
    val id: String,
    val name: String,
    val type: ProjectType,
    val lastModified: Long,
    val fileCount: Int,
    val buildStatus: String,
    val hasGit: Boolean,
    val previewReady: Boolean,
    val path: String,
)

class InvalidProjectException(message: String) : IOException(message)

/** An opened project: file system + metadata helpers for `.foldforge/`. */
class Project(val id: String, val root: File) {
    val fs = ProjectFileSystem(root)
    val snapshots = SnapshotStore(fs)
    private val metaDir get() = File(root, ProjectFileSystem.META_DIR).also { it.mkdirs() }

    var meta: ProjectMeta = readMeta(root)
        private set

    fun updateMeta(transform: (ProjectMeta) -> ProjectMeta) {
        meta = transform(meta)
        writeJson("project.json", ProjectMeta.serializer(), meta)
    }

    fun history(): ProjectHistory = readJson("history.json", ProjectHistory.serializer()) ?: ProjectHistory()

    fun addHistory(author: String, message: String, files: List<String> = emptyList(), snapshotId: String? = null): HistoryEntry {
        val entry = HistoryEntry(UUID.randomUUID().toString(), System.currentTimeMillis(), author, message, snapshotId, files)
        val entries = (listOf(entry) + history().entries).take(500)
        writeJson("history.json", ProjectHistory.serializer(), ProjectHistory(entries))
        return entry
    }

    fun buildLog(): BuildLog = readJson("build.json", BuildLog.serializer()) ?: BuildLog()

    fun addBuildRecord(record: BuildRecord) {
        writeJson("build.json", BuildLog.serializer(), BuildLog((listOf(record) + buildLog().records).take(100)))
    }

    fun <T> writeJson(name: String, serializer: KSerializer<T>, value: T) {
        fs.writeText("${ProjectFileSystem.META_DIR}/$name", FoldForgeJson.encodeToString(serializer, value))
    }

    fun <T> readJson(name: String, serializer: KSerializer<T>): T? {
        val f = File(metaDir, name)
        if (!f.isFile) return null
        return try {
            FoldForgeJson.decodeFromString(serializer, f.readText())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun metaFile(name: String): File = SafePaths.resolve(metaDir, name)

    companion object {
        fun readMeta(root: File): ProjectMeta {
            val f = File(root, "${ProjectFileSystem.META_DIR}/project.json")
            if (!f.isFile) throw InvalidProjectException("Missing .foldforge/project.json in ${root.name}")
            val meta = try {
                FoldForgeJson.decodeFromString(ProjectMeta.serializer(), f.readText())
            } catch (e: SerializationException) {
                throw InvalidProjectException("Invalid project.json: ${e.message?.lineSequence()?.firstOrNull()}")
            } catch (e: IllegalArgumentException) {
                throw InvalidProjectException("Invalid project.json: ${e.message?.lineSequence()?.firstOrNull()}")
            }
            if (meta.name.isBlank()) throw InvalidProjectException("project.json: name must not be empty")
            SafePaths.normalize(meta.entry) // throws on unsafe entry paths
            return meta
        }
    }
}

/**
 * Manages the collection of projects in a workspace directory (app internal storage by default).
 */
class ProjectStore(
    val workspace: File,
    private val catalog: TemplateCatalog = TemplateCatalog(),
) {
    init {
        workspace.mkdirs()
    }

    fun list(): List<ProjectSummary> =
        (workspace.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") && File(it, "${ProjectFileSystem.META_DIR}/project.json").isFile }
            .mapNotNull { dir -> runCatching { summarize(dir) }.getOrNull() }
            .sortedByDescending { it.lastModified }

    fun summarize(dir: File): ProjectSummary {
        val project = Project(dir.name, dir)
        var latest = 0L
        var count = 0
        project.fs.walkFiles().take(20_000).forEach {
            count++
            if (it.lastModified > latest) latest = it.lastModified
        }
        val build = project.buildLog().records.firstOrNull()
        return ProjectSummary(
            id = dir.name,
            name = project.meta.name,
            type = project.meta.type,
            lastModified = maxOf(latest, File(dir, ProjectFileSystem.META_DIR).lastModified()),
            fileCount = count,
            buildStatus = build?.let { "${it.kind}: ${it.status}" } ?: "Not built",
            hasGit = File(dir, ".git").isDirectory,
            previewReady = project.fs.exists(project.meta.entry),
            path = dir.absolutePath,
        )
    }

    fun open(id: String): Project {
        val dir = SafePaths.resolve(workspace, id)
        if (!dir.isDirectory) throw IOException("Project '$id' not found")
        return Project(id, dir)
    }

    fun exists(id: String) = runCatching { SafePaths.resolve(workspace, id).isDirectory }.getOrDefault(false)

    fun createFromTemplate(name: String, templateId: String, description: String = ""): Project {
        val template = catalog.get(templateId)
        val dir = newProjectDir(name)
        try {
            val fs = ProjectFileSystem(dir)
            for (file in catalog.files(templateId)) {
                if (file.isText) {
                    fs.writeText(file.path, String(file.bytes, Charsets.UTF_8).replace(TemplateCatalog.NAME_TOKEN, name))
                } else {
                    fs.writeBytes(file.path, file.bytes)
                }
            }
            if (template.generator == "android-wrapper") {
                AndroidProjectExporter().writeWrapperProject(
                    targetDir = dir,
                    options = AndroidExportOptions(appName = name, packageName = AndroidProjectExporter.packageNameFor(name)),
                    webSourceDir = null,
                )
            }
            val meta = ProjectMeta(name = name, type = template.type, entry = template.entry, template = templateId, description = description)
            initMetadata(dir, meta)
            val project = Project(dir.name, dir)
            project.addHistory("system", "Project created from template '${template.name}'")
            return project
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Creates a project containing exactly [files] (used by AI Build mode). */
    fun createFromFiles(name: String, type: ProjectType, entry: String, files: Map<String, String>, description: String = ""): Project {
        val dir = newProjectDir(name)
        try {
            val fs = ProjectFileSystem(dir)
            files.forEach { (path, content) -> fs.writeText(path, content) }
            initMetadata(dir, ProjectMeta(name = name, type = type, entry = entry, description = description))
            return Project(dir.name, dir).also { it.addHistory("ai", "Project generated by AI Build (${files.size} files)", files.keys.toList()) }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    fun importZip(input: InputStream, name: String, limits: ZipLimits = ZipLimits()): Project {
        val dir = newProjectDir(name)
        try {
            ZipTools.extract(input, dir, limits)
            return adoptDirectory(dir, name, "Imported from ZIP")
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Ensures an existing folder (e.g. a fresh git clone) has FOLD FORGE metadata. */
    fun adoptDirectory(dir: File, fallbackName: String, reason: String): Project {
        val metaFile = File(dir, "${ProjectFileSystem.META_DIR}/project.json")
        val valid = metaFile.isFile && runCatching { Project.readMeta(dir) }.isSuccess
        if (!valid) {
            val detected = detect(dir)
            initMetadata(dir, ProjectMeta(name = fallbackName, type = detected.first, entry = detected.second))
        }
        val project = Project(dir.name, dir)
        project.addHistory("system", reason)
        return project
    }

    fun delete(id: String) {
        val dir = SafePaths.resolve(workspace, id)
        if (dir.canonicalFile == workspace.canonicalFile) throw UnsafePathException("Refusing to delete workspace")
        dir.deleteRecursively()
    }

    fun newProjectDir(name: String): File {
        val base = slug(name).ifEmpty { "project" }
        var candidate = File(workspace, base)
        var i = 2
        while (candidate.exists()) candidate = File(workspace, "$base-${i++}")
        if (!candidate.mkdirs()) throw IOException("Could not create project folder ${candidate.name}")
        return candidate
    }

    private fun initMetadata(dir: File, meta: ProjectMeta) {
        val project = File(dir, ProjectFileSystem.META_DIR)
        project.mkdirs()
        File(project, "project.json").writeText(FoldForgeJson.encodeToString(ProjectMeta.serializer(), meta))
        listOf("history.json" to "{\"entries\":[]}", "ai-context.json" to "{}", "qa.json" to "{}", "build.json" to "{\"records\":[]}")
            .forEach { (n, c) -> File(project, n).takeIf { !it.exists() }?.writeText(c) }
        val gitignore = File(dir, ".gitignore")
        if (!gitignore.exists()) gitignore.writeText(DEFAULT_GITIGNORE)
    }

    companion object {
        val DEFAULT_GITIGNORE = """
            # FOLD FORGE
            .foldforge/snapshots/
            .foldforge/qa/
            .foldforge/journal/
            # Secrets — never commit
            .env
            .env.*
            *.keystore
            *.jks
            local.properties
            # Build outputs
            build/
            .gradle/
            node_modules/
        """.trimIndent() + "\n"

        fun slug(name: String): String =
            name.lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
                .joinToString("").replace(Regex("-+"), "-").trim('-').take(48)

        /** Detects project type + entry from an arbitrary folder. */
        fun detect(dir: File): Pair<ProjectType, String> {
            val gradle = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts").any { File(dir, it).isFile }
            if (gradle) {
                val www = File(dir, "app/src/main/assets/www/index.html")
                return ProjectType.ANDROID to (if (www.isFile) "app/src/main/assets/www/index.html" else "build.gradle.kts")
            }
            val candidates = listOf("index.html", "public/index.html", "dist/index.html", "src/index.html", "www/index.html")
            val entry = candidates.firstOrNull { File(dir, it).isFile }
                ?: dir.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name.equals("index.html", true) }
                    ?.let { SafePaths.relativize(dir, it) }
                ?: "index.html"
            val html = runCatching { File(dir, entry).readText() }.getOrDefault("")
            val type = when {
                html.contains("three", ignoreCase = true) && html.contains("importmap") -> ProjectType.THREEJS
                html.contains("<canvas") -> ProjectType.CANVAS
                else -> ProjectType.IMPORTED
            }
            return type to entry
        }
    }
}

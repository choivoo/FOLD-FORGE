package com.foldforge.studio.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val FoldForgeJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
enum class ProjectType(val label: String, val buildProfile: BuildProfile) {
    @SerialName("web") WEB("Web", BuildProfile.WEB),
    @SerialName("html") HTML("HTML App", BuildProfile.WEB),
    @SerialName("canvas") CANVAS("Canvas Game", BuildProfile.WEB),
    @SerialName("threejs") THREEJS("Three.js", BuildProfile.WEB),
    @SerialName("android") ANDROID("Android", BuildProfile.GRADLE),
    @SerialName("imported") IMPORTED("Imported", BuildProfile.WEB);
}

@Serializable
enum class BuildProfile { WEB, GRADLE }

/** Contents of `.foldforge/project.json`. */
@Serializable
data class ProjectMeta(
    val name: String,
    val type: ProjectType = ProjectType.WEB,
    val version: String = "1.0.0",
    val entry: String = "index.html",
    val template: String? = null,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val formatVersion: Int = 1,
)

@Serializable
data class HistoryEntry(
    val id: String,
    val timestamp: Long,
    val author: String, // "user" | "ai" | "system"
    val message: String,
    val snapshotId: String? = null,
    val files: List<String> = emptyList(),
)

@Serializable
data class ProjectHistory(val entries: List<HistoryEntry> = emptyList())

@Serializable
data class BuildRecord(
    val id: String,
    val timestamp: Long,
    val kind: String, // "apk", "android-project", "zip", "web", "ci"
    val status: String, // "success" | "failed"
    val outputPath: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val message: String = "",
)

@Serializable
data class BuildLog(val records: List<BuildRecord> = emptyList())

data class FileNode(
    val path: String, // project-relative, '/' separated
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val depth: Int,
)

enum class FileKind(val badge: String) {
    HTML("HTML"), CSS("CSS"), JS("JS"), TS("TS"), JSON("JSON"), MARKDOWN("MD"),
    KOTLIN("KT"), XML("XML"), GRADLE("GRADLE"), GLSL("GLSL"), IMAGE("IMG"),
    MODEL3D("3D"), AUDIO("AUDIO"), TEXT("TXT"), BINARY("BIN");

    val isText: Boolean get() = this !in setOf(IMAGE, MODEL3D, AUDIO, BINARY)

    companion object {
        fun of(name: String): FileKind {
            val lower = name.lowercase()
            if (lower.endsWith(".gradle") || lower.endsWith(".gradle.kts")) return GRADLE
            return when (lower.substringAfterLast('.', "")) {
                "html", "htm" -> HTML
                "css" -> CSS
                "js", "mjs", "cjs", "jsx" -> JS
                "ts", "tsx" -> TS
                "json", "gltf" -> if (lower.endsWith(".gltf")) MODEL3D else JSON
                "md", "markdown" -> MARKDOWN
                "kt", "kts" -> KOTLIN
                "xml" -> XML
                "glsl", "vert", "frag", "vs", "fs" -> GLSL
                "png", "jpg", "jpeg", "webp", "gif", "svg", "bmp", "ico" -> IMAGE
                "glb", "obj", "fbx" -> MODEL3D
                "mp3", "wav", "ogg", "m4a", "aac", "flac" -> AUDIO
                "txt", "properties", "gitignore", "env", "yml", "yaml", "toml", "java", "sh", "pro", "cfg", "ini", "csv" -> TEXT
                "" -> if (lower.startsWith(".")) TEXT else TEXT
                else -> BINARY
            }
        }
    }
}

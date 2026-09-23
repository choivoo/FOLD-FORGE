package com.foldforge.studio.core.ai

import com.foldforge.studio.core.patch.ChangeSet
import com.foldforge.studio.core.patch.ChangeKind
import com.foldforge.studio.core.patch.FileChange
import com.foldforge.studio.core.patch.SearchReplace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
enum class ProviderKind(val label: String) { ANTHROPIC("Anthropic Claude"), OPENAI_COMPATIBLE("OpenAI-compatible") }

data class AiConfig(
    val provider: ProviderKind = ProviderKind.ANTHROPIC,
    val endpoint: String = "",
    val model: String = DEFAULT_ANTHROPIC_MODEL,
    val apiKey: String = "",
    val temperature: Double = 0.2,
    val maxContextChars: Int = 60_000,
    val maxOutputTokens: Int = 16_000,
) {
    val isConfigured get() = apiKey.isNotBlank() && model.isNotBlank() &&
        (provider == ProviderKind.ANTHROPIC || endpoint.isNotBlank())

    override fun toString(): String = "AiConfig(provider=$provider, endpoint=$endpoint, model=$model, apiKey=${if (apiKey.isBlank()) "<none>" else "<redacted>"})"

    companion object {
        const val DEFAULT_ANTHROPIC_MODEL = "claude-opus-5"
        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1"
    }
}

data class ChatMessage(val role: String, val content: String, val imagePng: ByteArray? = null)

data class CompletionRequest(
    val system: String,
    val messages: List<ChatMessage>,
    val maxTokens: Int,
    val jsonMode: Boolean = false,
)

data class CompletionResponse(val text: String, val model: String, val inputTokens: Long?, val outputTokens: Long?, val stopReason: String?)

class AiException(message: String, val retryable: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

data class GeneratedProject(val name: String, val type: String, val entry: String, val files: Map<String, String>, val plan: List<String>)

data class EditProposal(val plan: List<String>, val changeSet: ChangeSet, val notes: String)

data class ScreenshotIssue(val severity: String, val description: String)

/**
 * Provider abstraction. Concrete providers implement [complete]; the high-level operations are
 * implemented once in [BaseAIProvider] on top of a JSON response protocol.
 */
interface AIProvider {
    val config: AiConfig
    suspend fun complete(request: CompletionRequest): CompletionResponse

    suspend fun chat(history: List<ChatMessage>, projectSummary: String): String
    suspend fun analyze(request: String, context: String): String
    suspend fun generateFiles(prompt: String): GeneratedProject
    suspend fun editFiles(request: String, brainSummary: String, files: List<ContextFile>): EditProposal
    suspend fun fixError(errors: List<String>, brainSummary: String, files: List<ContextFile>): EditProposal
    suspend fun reviewDiff(unifiedDiff: String): String
    suspend fun generateTests(brainSummary: String, probeJson: String): String
    suspend fun analyzeScreenshot(png: ByteArray, viewport: String): List<ScreenshotIssue>
}

abstract class BaseAIProvider(final override val config: AiConfig) : AIProvider {

    override suspend fun chat(history: List<ChatMessage>, projectSummary: String): String =
        complete(CompletionRequest(Prompts.CHAT_SYSTEM + "\n\nPROJECT OVERVIEW:\n" + projectSummary, history, config.maxOutputTokens)).text

    override suspend fun analyze(request: String, context: String): String =
        complete(CompletionRequest(Prompts.CHAT_SYSTEM, listOf(ChatMessage("user", "$request\n\nCONTEXT:\n$context")), config.maxOutputTokens)).text

    override suspend fun generateFiles(prompt: String): GeneratedProject {
        val r = complete(CompletionRequest(Prompts.BUILD_SYSTEM, listOf(ChatMessage("user", prompt)), config.maxOutputTokens, jsonMode = true))
        return AgentProtocol.parseGeneratedProject(r.text)
    }

    override suspend fun editFiles(request: String, brainSummary: String, files: List<ContextFile>): EditProposal {
        val user = Prompts.editUserMessage(request, brainSummary, files)
        val r = complete(CompletionRequest(Prompts.EDIT_SYSTEM, listOf(ChatMessage("user", user)), config.maxOutputTokens, jsonMode = true))
        return AgentProtocol.parseEditProposal(r.text)
    }

    override suspend fun fixError(errors: List<String>, brainSummary: String, files: List<ContextFile>): EditProposal {
        val request = "Fix these runtime errors / failing tests. Make the smallest correct change.\n" + errors.joinToString("\n") { "- $it" }
        return editFiles(request, brainSummary, files)
    }

    override suspend fun reviewDiff(unifiedDiff: String): String =
        complete(CompletionRequest(Prompts.REVIEW_SYSTEM, listOf(ChatMessage("user", unifiedDiff.take(config.maxContextChars))), 4000)).text

    override suspend fun generateTests(brainSummary: String, probeJson: String): String =
        complete(
            CompletionRequest(
                Prompts.TESTS_SYSTEM,
                listOf(ChatMessage("user", "PROJECT:\n$brainSummary\n\nRUNTIME PROBE:\n$probeJson")),
                8000, jsonMode = true,
            ),
        ).text.let { AgentProtocol.extractJson(it) }

    override suspend fun analyzeScreenshot(png: ByteArray, viewport: String): List<ScreenshotIssue> {
        val r = complete(
            CompletionRequest(
                Prompts.VISION_SYSTEM,
                listOf(ChatMessage("user", "Viewport: $viewport. Inspect this game/app screenshot.", imagePng = png)),
                2000, jsonMode = true,
            ),
        )
        return AgentProtocol.parseScreenshotIssues(r.text)
    }
}

object Prompts {
    val CHAT_SYSTEM = """
        You are the coding assistant inside FOLD FORGE, a mobile development workstation for web games and apps
        (HTML/CSS/JavaScript/TypeScript, Three.js, Canvas, GLSL) that can also export Android projects.
        Be concise and concrete. Reference files by their project path. When suggesting code, show only the relevant part.
    """.trimIndent()

    val EDIT_SYSTEM = """
        You are the FOLD FORGE coding agent. You edit a web game/app project on the user's behalf.
        Respond with ONLY a JSON object (no prose, no markdown fences) of this shape:
        {
          "plan": ["short step", ...],
          "summary": "one line describing the change",
          "changes": [
            {"path": "src/file.js", "kind": "modify", "reason": "why",
             "edits": [{"search": "exact existing text copied from the file", "replace": "new text"}]},
            {"path": "src/new.js", "kind": "create", "content": "full file content"},
            {"path": "old.js", "kind": "delete"}
          ],
          "notes": "anything the user should know"
        }
        Rules:
        - Prefer "modify" with small search/replace edits. Each "search" must match the current file text exactly once;
          include enough surrounding lines to be unique. Use "content" (full file) only for new files or tiny files.
        - Only touch files that are necessary. Never modify .foldforge/ or .git/. Never add secrets or API keys.
        - Keep window.__foldForgeTest (the QA test adapter) working and extend it when you add gameplay systems.
        - Do not copy copyrighted game assets or code; create original designs when asked for something "like" an existing game.
        - Mobile first: touch controls, responsive layout, 60fps budget.
    """.trimIndent()

    val BUILD_SYSTEM = """
        You are the FOLD FORGE AI Build agent. Create a complete, playable, ORIGINAL web project from the user's idea.
        Respond with ONLY a JSON object (no prose, no markdown fences):
        {"name": "Project Name", "type": "canvas|threejs|html", "entry": "index.html",
         "plan": ["Planning", "Structure", "Core systems", "UI", "Gameplay", "Assets", ...],
         "files": [{"path": "index.html", "content": "..."}, {"path": "src/main.js", "content": "..."}]}
        Requirements:
        - Must run offline in a mobile WebView. For Three.js use: <script type="importmap">{"imports":{"three":"./vendor/three.module.min.js"}}</script>
          (FOLD FORGE provides vendor/three.module.min.js automatically). No other external CDNs.
        - Touch controls (on-screen buttons/joystick) plus keyboard. Responsive to any viewport. Procedural visuals (no image files needed).
        - Expose window.__foldForgeTest = { version: 1, actions: [...], getState(), pressButton(name, down), moveJoystick(x, y), restart(), debug: {...}, scenarios() }
          so the automated QA agent can play it. getState() must return JSON-safe data like {scene, player:{x,y,hp}, enemies, score, gameOver}.
        - Include README.md with controls. Keep total size reasonable (< 60 KB of code). All content must be original.
    """.trimIndent()

    val REVIEW_SYSTEM = """
        You are a senior code reviewer. Review this unified diff for bugs, crashes, performance problems on mobile,
        security issues and broken QA adapter hooks. Reply with a short list of findings (or "No issues found.").
    """.trimIndent()

    val TESTS_SYSTEM = """
        You design automated gameplay QA scenarios for the FOLD FORGE QA engine. Respond with ONLY a JSON array of scenarios:
        [{"id": "kebab-id", "name": "Readable name", "category": "smoke|movement|combat|ui|restart|death|touch|performance",
          "steps": [ ...steps... ]}]
        Allowed steps:
        {"action":"wait","ms":300} {"action":"press","button":"left|right|up|down|jump|attack|dash|interact|menu","ms":400}
        {"action":"key","key":"ArrowLeft","ms":100} {"action":"tap","x":0.5,"y":0.5} {"action":"drag","x1":0.2,"y1":0.8,"x2":0.4,"y2":0.8,"ms":300}
        {"action":"joystick","x":1,"y":0,"ms":500} {"action":"restart"} {"action":"debug","call":"<adapter debug fn>","args":[]}
        {"action":"waitUntil","path":"kills","op":"gt","from":"name"|"value":1,"press":"attack","timeout":5000} (waits on state, optionally re-pressing a button; prefer it over fixed waits)
        {"action":"snapshot","as":"name"} {"action":"canvasSnapshot","as":"name"} {"action":"sampleFps","ms":2000,"as":"perf"}
        {"expect":"noErrors"} {"expect":"stateAvailable"} {"expect":"truthy","path":"a.b"} {"expect":"falsy","path":"a.b"}
        {"expect":"changed","path":"player.x","from":"name"} {"expect":"compare","path":"score","op":"gt|gte|lt|lte|eq|neq","from":"name"|"value":1}
        {"expect":"canvasNotBlank"} {"expect":"canvasChanged","from":"name"} {"expect":"layout"} {"expect":"fps","min":45,"from":"perf"}
        Any expectation may add "name" and "severity":"warn". Use only state paths and debug hooks that exist in the probe.
    """.trimIndent()

    val VISION_SYSTEM = """
        You are a visual QA inspector for mobile games. Look for: frozen/black screen, UI outside the viewport,
        overlapping buttons, invisible player, stretched textures, broken mobile layout.
        Respond with ONLY a JSON array: [{"severity": "fail|warn", "description": "..."}]. Return [] if it looks fine.
    """.trimIndent()

    fun editUserMessage(request: String, brainSummary: String, files: List<ContextFile>): String = buildString {
        append("REQUEST:\n").append(request).append("\n\n")
        append("PROJECT BRAIN:\n").append(brainSummary).append("\n\n")
        append("FILES:\n")
        for (f in files) {
            append("===== FILE: ").append(f.path).append(" (").append(f.reason).append(") =====\n")
            append(f.content)
            if (!f.content.endsWith("\n")) append('\n')
        }
    }
}

/** Parses the JSON protocol responses. Tolerates markdown fences and leading/trailing prose. */
object AgentProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun extractJson(text: String): String {
        val t = text.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(t)
        val body = fence?.groupValues?.get(1)?.trim() ?: t
        val start = body.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) throw AiException("AI response did not contain JSON")
        val open = body[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until body.length) {
            val c = body[i]
            if (inString) {
                if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> { depth--; if (depth == 0) return body.substring(start, i + 1) }
            }
        }
        throw AiException("AI response JSON was truncated (increase max output tokens or narrow the request)")
    }

    private fun parse(text: String): JsonElement = try {
        json.parseToJsonElement(extractJson(text))
    } catch (e: AiException) {
        throw e
    } catch (e: Exception) {
        throw AiException("AI response was not valid JSON: ${e.message?.take(120)}")
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    fun parseEditProposal(text: String): EditProposal {
        val obj = parse(text) as? JsonObject ?: throw AiException("Expected a JSON object")
        val plan = (obj["plan"] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()
        val changes = (obj["changes"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            val path = c["path"].str() ?: return@mapNotNull null
            val kind = when (c["kind"].str()?.lowercase()) {
                "create", "add", "new" -> ChangeKind.CREATE
                "delete", "remove" -> ChangeKind.DELETE
                else -> ChangeKind.MODIFY
            }
            val edits = (c["edits"] as? JsonArray)?.mapNotNull { e ->
                val eo = e as? JsonObject ?: return@mapNotNull null
                SearchReplace(eo["search"].str() ?: return@mapNotNull null, eo["replace"].str() ?: "")
            } ?: emptyList()
            FileChange(path, kind, c["content"].str(), edits, c["reason"].str() ?: "")
        }
        return EditProposal(plan, ChangeSet(obj["summary"].str() ?: "AI change", changes), obj["notes"].str() ?: "")
    }

    fun parseGeneratedProject(text: String): GeneratedProject {
        val obj = parse(text) as? JsonObject ?: throw AiException("Expected a JSON object")
        val files = LinkedHashMap<String, String>()
        (obj["files"] as? JsonArray)?.forEach { f ->
            val fo = f as? JsonObject ?: return@forEach
            val p = fo["path"].str() ?: return@forEach
            files[p] = fo["content"].str() ?: ""
        }
        if (files.isEmpty()) throw AiException("AI did not generate any files")
        val entry = obj["entry"].str() ?: "index.html"
        if (entry !in files) throw AiException("Generated project is missing its entry file '$entry'")
        return GeneratedProject(
            name = obj["name"].str()?.take(60) ?: "AI Project",
            type = obj["type"].str() ?: "canvas",
            entry = entry,
            files = files,
            plan = (obj["plan"] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList(),
        )
    }

    fun parseScreenshotIssues(text: String): List<ScreenshotIssue> {
        val arr = parse(text) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ScreenshotIssue(o["severity"].str() ?: "warn", o["description"].str() ?: return@mapNotNull null)
        }
    }

    fun parseScenarioArray(text: String): JsonArray = (parse(text) as? JsonArray) ?: throw AiException("Expected a JSON array")
}

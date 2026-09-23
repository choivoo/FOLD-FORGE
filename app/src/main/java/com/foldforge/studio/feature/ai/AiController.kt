package com.foldforge.studio.feature.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.foldforge.studio.core.ai.AIProvider
import com.foldforge.studio.core.ai.AIProviderFactory
import com.foldforge.studio.core.ai.AgentEvent
import com.foldforge.studio.core.ai.AgentHost
import com.foldforge.studio.core.ai.AgentResult
import com.foldforge.studio.core.ai.AgentStage
import com.foldforge.studio.core.ai.AiException
import com.foldforge.studio.core.ai.ChatMessage
import com.foldforge.studio.core.ai.CodingAgent
import com.foldforge.studio.core.ai.ContextScope
import com.foldforge.studio.core.ai.ContextSelector
import com.foldforge.studio.core.ai.ProjectBrain
import com.foldforge.studio.core.ai.RuntimeIssue
import com.foldforge.studio.core.patch.PreparedChange
import com.foldforge.studio.core.patch.PreparedChangeSet
import com.foldforge.studio.core.qa.QaReport
import com.foldforge.studio.core.security.SecureStore
import com.foldforge.studio.core.storage.Project
import com.foldforge.studio.core.web.ConsoleLevel
import com.foldforge.studio.data.database.AiMessageEntity
import com.foldforge.studio.feature.workspace.Pane
import com.foldforge.studio.feature.workspace.WorkspaceEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AiMode { CHAT, AGENT }

data class ChatItem(val role: String, val text: String, val time: Long = System.currentTimeMillis(), val isError: Boolean = false, val result: AgentResult? = null)

data class TaskItem(val title: String, val state: TaskState)
enum class TaskState { DONE, ACTIVE, PENDING }

class PendingReview(val prepared: PreparedChangeSet, val decision: CompletableDeferred<List<PreparedChange>?>)

class AiController(private val env: WorkspaceEnv) {
    var mode by mutableStateOf(AiMode.AGENT)
    var scope by mutableStateOf(ContextScope.RELATED_FILES)
    var runQaAfter by mutableStateOf(false)
    val messages = mutableStateListOf<ChatItem>()
    val events = mutableStateListOf<AgentEvent>()
    val tasks = mutableStateListOf<TaskItem>()
    var busy by mutableStateOf(false)
        private set
    var stage by mutableStateOf<AgentStage?>(null)
        private set
    var pendingReview by mutableStateOf<PendingReview?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var largeRequestWarning by mutableStateOf<Pair<Int, () -> Unit>?>(null)
    private var lastRequest: Pair<String, AiMode>? = null
    private var job: Job? = null

    val isConfigured: Boolean
        get() = env.settings.value.aiConfig(env.container.secureStore.get(SecureStore.AI_API_KEY)).isConfigured

    fun provider(): AIProvider = AIProviderFactory.create(env.settings.value.aiConfig(env.container.secureStore.get(SecureStore.AI_API_KEY)))

    fun loadHistory() {
        val p = runCatching { env.project }.getOrNull() ?: return
        env.scope.launch {
            val items = withContext(Dispatchers.IO) { env.container.database.aiHistory().forProject(p.id) }
            messages.clear()
            messages += items.takeLast(100).map { ChatItem(it.role, it.text, it.createdAt) }
        }
    }

    private fun persist(p: Project, role: String, text: String, model: String? = null) {
        env.scope.launch(Dispatchers.IO) {
            env.container.database.aiHistory().insert(AiMessageEntity(projectId = p.id, role = role, text = text.take(20_000), model = model, createdAt = System.currentTimeMillis()))
        }
    }

    fun clearHistory() {
        val p = env.project
        messages.clear()
        env.scope.launch(Dispatchers.IO) { env.container.database.aiHistory().clear(p.id) }
    }

    fun cancel() {
        job?.cancel()
        pendingReview?.decision?.complete(null)
        pendingReview = null
        busy = false
        stage = null
    }

    fun retry() {
        val (text, m) = lastRequest ?: return
        mode = m
        send(text, confirmedLarge = true)
    }

    /** Estimated characters that would be sent for the current scope (for the large-request warning). */
    private suspend fun estimateChars(): Int = withContext(Dispatchers.IO) {
        val p = env.project
        ContextSelector(p.fs).select(scope, env.activeFile(), ProjectBrain.load(p), budgetChars = Int.MAX_VALUE).totalChars
    }

    fun send(text: String, confirmedLarge: Boolean = false) {
        val request = text.trim()
        if (request.isEmpty() || busy) return
        if (!isConfigured) {
            env.notify("Connect an AI provider first", "Settings") { env.showPane(Pane.AI) }
            return
        }
        if (scope == ContextScope.WHOLE_PROJECT && !confirmedLarge) {
            env.scope.launch {
                val chars = estimateChars()
                if (chars > LARGE_REQUEST_CHARS) largeRequestWarning = chars to { send(request, confirmedLarge = true) }
                else send(request, confirmedLarge = true)
            }
            return
        }
        val p = env.project
        lastRequest = request to mode
        lastError = null
        messages += ChatItem("user", request)
        persist(p, "user", request)
        busy = true
        job = env.scope.launch {
            try {
                val provider = withContext(Dispatchers.IO) { provider() }
                if (mode == AiMode.CHAT) {
                    stage = AgentStage.ANALYZING
                    val (history, summary) = withContext(Dispatchers.IO) {
                        val brain = ProjectBrain.load(p) ?: ProjectBrain.build(p).also { ProjectBrain.save(p, it) }
                        val ctx = ContextSelector(p.fs).select(scope, env.activeFile(), brain, env.consoleEntries().filter { it.level == ConsoleLevel.ERROR }.mapNotNull { it.path }, env.recentFiles(), provider.config.maxContextChars)
                        val files = ctx.files.joinToString("\n") { "===== FILE: ${it.path} =====\n${it.content}" }
                        val chat = messages.takeLast(12).filter { !it.isError }.map { ChatMessage(it.role, it.text) }.toMutableList()
                        val last = chat.removeAt(chat.size - 1)
                        chat += ChatMessage("user", last.content + if (files.isNotEmpty()) "\n\nRELEVANT FILES:\n$files" else "")
                        chat to ProjectBrain.summarize(brain)
                    }
                    val reply = provider.chat(history, summary)
                    messages += ChatItem("assistant", reply)
                    persist(p, "assistant", reply, provider.config.model)
                } else {
                    runAgent(provider, request)
                }
            } catch (e: CancellationException) {
                messages += ChatItem("system", "Cancelled", isError = true)
            } catch (e: AiException) {
                fail(e.message ?: "AI error")
            } catch (e: Exception) {
                fail("${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
                pendingReview = null
            }
        }
    }

    private fun fail(message: String) {
        lastError = message
        stage = AgentStage.FAILED
        messages += ChatItem("system", message, isError = true)
    }

    private suspend fun runAgent(provider: AIProvider, request: String) {
        env.saveAll()
        events.clear()
        tasks.clear()
        val s = env.settings.value
        val agent = CodingAgent(provider, host, scope, s.qaMaxFixIterations, s.qaAutoApproveFixes)
        val result = agent.run(request, runQaAfter)
        tasks.replaceAll { if (it.state != TaskState.DONE) it.copy(state = TaskState.DONE) else it }
        val summary = buildString {
            append(result.summary)
            if (result.changedFiles.isNotEmpty()) append("\nChanged ${result.changedFiles.size} file(s): +${result.added} −${result.removed}\n").append(result.changedFiles.joinToString("\n") { "• $it" })
            if (result.remainingErrors.isNotEmpty()) append("\n⚠ Remaining runtime errors:\n").append(result.remainingErrors.take(5).joinToString("\n") { "• $it" })
            result.qaReport?.let { append("\nQA: ${it.overall} — PASS ${it.checkPass} · FAIL ${it.checkFail} · WARN ${it.checkWarn}") }
            if (result.notes.isNotBlank()) append("\n\n").append(result.notes)
        }
        messages += ChatItem("assistant", summary, result = result)
        persist(env.project, "assistant", summary, provider.config.model)
    }

    fun undoAgentResult(result: AgentResult) {
        val first = result.snapshotIds.firstOrNull() ?: return
        env.scope.launch {
            val p = env.project
            val safety = withContext(Dispatchers.IO) { p.snapshots.restore(first) }
            env.recordSnapshot(safety)
            p.addHistory("user", "Undid AI change: ${result.summary}", result.changedFiles, safety.id)
            env.onFilesChanged(result.changedFiles, byAi = false)
            env.notify("AI change undone")
        }
    }

    /** QA fix loop using the same host (review dialog, preview, console) as the agent. */
    suspend fun fixQa(report: QaReport): Pair<QaReport, List<com.foldforge.studio.core.qa.QaFixAttempt>> {
        busy = true
        try {
            env.saveAll()
            val s = env.settings.value
            val agent = CodingAgent(withContext(Dispatchers.IO) { provider() }, host, scope, s.qaMaxFixIterations, s.qaAutoApproveFixes)
            return agent.fixQaFailures(report)
        } finally {
            busy = false
            pendingReview = null
        }
    }

    fun decide(approved: List<PreparedChange>?) {
        pendingReview?.decision?.complete(approved)
        pendingReview = null
    }

    private val host = object : AgentHost {
        override val project: Project get() = env.project
        private var consoleMarker = 0

        override fun activeFile(): String? = env.activeFile()
        override fun recentFiles(): List<String> = env.recentFiles()

        override suspend fun review(prepared: PreparedChangeSet, automatic: Boolean): List<PreparedChange>? {
            if (automatic) return prepared.applicable
            val d = CompletableDeferred<List<PreparedChange>?>()
            withContext(Dispatchers.Main) { pendingReview = PendingReview(prepared, d) }
            return d.await()
        }

        override suspend fun afterApply(paths: List<String>) {
            env.onFilesChanged(paths, byAi = true)
            consoleMarker = env.consoleEntries().size
        }

        override suspend fun collectRuntimeErrors(waitMs: Long): List<RuntimeIssue> {
            val preview = env.preview ?: return emptyList()
            if (!preview.isRunning) {
                if (!env.ensurePreviewRunning()) return emptyList()
                consoleMarker = 0
            } else preview.awaitReady(15_000)
            delay(waitMs)
            return env.consoleEntries().drop(consoleMarker).filter { it.level == ConsoleLevel.ERROR }
                .distinctBy { it.message }
                .map { RuntimeIssue(it.message + (it.stack?.let { s -> "\n$s" } ?: ""), it.path, it.line) }
        }

        override suspend fun runQa(scenarioIds: Set<String>?): QaReport? = env.runQa(scenarioIds)

        override fun emit(event: AgentEvent) {
            env.scope.launch(Dispatchers.Main) {
                events += event
                stage = event.stage
                if (event.message.startsWith("Plan: ")) tasks += TaskItem(event.message.removePrefix("Plan: "), TaskState.PENDING)
                val activeIdx = when (event.stage) {
                    AgentStage.APPLYING, AgentStage.TESTING, AgentStage.FIXING -> tasks.size - 1
                    AgentStage.REVIEW -> tasks.size / 2
                    else -> -1
                }
                if (activeIdx >= 0) tasks.indices.forEach { i -> tasks[i] = tasks[i].copy(state = if (i < activeIdx) TaskState.DONE else if (i == activeIdx) TaskState.ACTIVE else TaskState.PENDING) }
            }
        }
    }

    companion object {
        const val LARGE_REQUEST_CHARS = 40_000
    }
}

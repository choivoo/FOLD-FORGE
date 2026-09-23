package com.foldforge.studio.feature.qa

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.foldforge.studio.core.ai.AgentProtocol
import com.foldforge.studio.core.ai.ProjectBrain
import com.foldforge.studio.core.model.FoldForgeJson
import com.foldforge.studio.core.qa.QaCheck
import com.foldforge.studio.core.qa.QaDriver
import com.foldforge.studio.core.qa.QaOptions
import com.foldforge.studio.core.qa.QaReplay
import com.foldforge.studio.core.qa.QaReport
import com.foldforge.studio.core.qa.QaRunner
import com.foldforge.studio.core.qa.QaScenario
import com.foldforge.studio.core.qa.QaState
import com.foldforge.studio.core.qa.QaStatus
import com.foldforge.studio.core.qa.ScenarioGenerator
import com.foldforge.studio.core.qa.ScreenshotInfo
import com.foldforge.studio.core.qa.ViewportPreset
import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.web.ConsoleLevel
import com.foldforge.studio.core.web.PreviewHost
import com.foldforge.studio.data.database.QaRunEntity
import com.foldforge.studio.feature.workspace.WorkspaceEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

enum class QaPhase(val label: String) {
    IDLE("Idle"), RUNNING("Running"), FAILURE_FOUND("Failure Found"), FIXING("Fixing"), RETESTING("Re-testing"),
    COMPLETE("Complete"), BLOCKED("Blocked"),
}

/** QaDriver backed by the preview WebView. Screenshots are stored as QA artifacts. */
class WebViewQaDriver(private val host: PreviewHost, private val env: WorkspaceEnv, private val artifactDir: File) : QaDriver {
    override suspend fun evaluate(script: String): String? = host.evaluate(script)
    override suspend fun reload() { host.reload(); host.awaitReady() }
    override suspend fun setViewport(preset: ViewportPreset?) { host.setViewport(preset); delay(600) }
    override suspend fun screenshot(label: String): ScreenshotInfo? {
        val bmp = host.screenshot() ?: return null
        val (mean, variance) = PreviewHost.lumaStats(bmp)
        val png = PreviewHost.pngBytes(bmp)
        val file = File(artifactDir, "screenshots/${label.replace(Regex("[^A-Za-z0-9._-]"), "_")}.png")
        withContext(Dispatchers.IO) { file.parentFile?.mkdirs(); file.writeBytes(png) }
        return ScreenshotInfo(file.relativeTo(env.project.root).invariantSeparatorsPath, png, mean, variance)
    }
    override fun consoleErrors(): List<String> = env.consoleEntries().filter { it.level == ConsoleLevel.ERROR }.map { e ->
        e.message + (e.path?.let { " ($it:${e.line})" } ?: "")
    }
}

class QaController(private val env: WorkspaceEnv) {
    var phase by mutableStateOf(QaPhase.IDLE)
        private set
    var progress by mutableStateOf("")
        private set
    var report by mutableStateOf<QaReport?>(null)
        private set
    var lastScenarios by mutableStateOf<List<QaScenario>>(emptyList())
        private set
    var replay by mutableStateOf<QaReplay?>(null)
        private set
    var blockedReason by mutableStateOf<String?>(null)
        private set
    var aiScenarios by mutableStateOf<List<QaScenario>>(emptyList())
    private var job: Job? = null

    val running get() = phase in setOf(QaPhase.RUNNING, QaPhase.FIXING, QaPhase.RETESTING)

    fun loadState() {
        val p = runCatching { env.project }.getOrNull() ?: return
        env.scope.launch(Dispatchers.IO) {
            val state = p.readJson("qa.json", QaState.serializer())
            val ai = runCatching { File(p.root, "${ProjectFileSystem.META_DIR}/qa/ai-scenarios.json").takeIf { it.isFile }?.readText()?.let { ScenarioGenerator.parseCustom(it, "ai") } }.getOrNull()
            withContext(Dispatchers.Main) {
                report = state?.lastReport
                aiScenarios = ai.orEmpty()
                if (report != null) phase = if (report!!.checkFail > 0) QaPhase.FAILURE_FOUND else QaPhase.COMPLETE
            }
        }
    }

    fun cancel() { job?.cancel(); phase = QaPhase.IDLE; progress = "Cancelled"; env.preview?.setViewport(null) }

    private fun options(): QaOptions {
        val s = env.settings.value
        return QaOptions(pauseBetweenScenariosMs = s.effectiveQaPauseMs, screenshots = true)
    }

    private fun artifactDir(runTag: String) = File(env.project.root, "${ProjectFileSystem.META_DIR}/qa/$runTag").also { it.mkdirs() }

    private suspend fun visionHook(): (suspend (ScreenshotInfo, String) -> List<QaCheck>)? {
        val s = env.settings.value
        val ai = (env as? com.foldforge.studio.feature.workspace.WorkspaceViewModel)?.ai ?: return null
        if (!s.qaVision || !ai.isConfigured) return null
        val provider = withContext(Dispatchers.IO) { ai.provider() }
        return { shot, viewport ->
            val png = shot.png
            if (png == null) emptyList()
            else provider.analyzeScreenshot(png, viewport).map { QaCheck("vision: ${it.description.take(60)}", if (it.severity == "fail") QaStatus.FAIL else QaStatus.WARN, it.description) }
        }
    }

    /** Core run used by the panel, the agent and the fix loop. */
    private suspend fun execute(select: suspend (QaRunner) -> List<QaScenario>, label: String, shouldContinue: () -> Boolean = { true }): QaReport? {
        val preview = env.preview
        if (preview == null) { block("Preview is not available on this screen"); return null }
        progress = "Starting preview"
        if (!env.ensurePreviewRunning()) { block("Preview did not finish loading"); return null }
        delay(600)
        val tag = "run-${System.currentTimeMillis()}"
        val dir = artifactDir(tag)
        val driver = WebViewQaDriver(preview, env, dir)
        val runner = QaRunner(driver, options())
        val scenarios = try {
            select(runner)
        } catch (e: com.foldforge.studio.core.qa.QaBlockedException) {
            block(e.message ?: "QA runtime unavailable"); return null
        }
        lastScenarios = scenarios
        val mode = if (runCatching { runner.probe().adapter }.getOrDefault(false)) "adapter" else "blackbox"
        val r = runner.run(env.project.meta.name, scenarios, { i, n, name -> progress = "$label ${minOf(i + 1, n)}/$n · $name" }, visionHook(), mode, shouldContinue)
        saveArtifacts(r, scenarios, dir)
        report = r
        return r
    }

    private fun block(reason: String) {
        blockedReason = reason
        phase = QaPhase.BLOCKED
        progress = reason
    }

    private suspend fun saveArtifacts(r: QaReport, scenarios: List<QaScenario>, dir: File) = withContext(Dispatchers.IO) {
        val p = env.project
        File(dir, "qa-report.json").writeText(FoldForgeJson.encodeToString(QaReport.serializer(), r))
        File(dir, "console.log").writeText(env.consoleEntries().joinToString("\n") { "[${it.level}] ${it.message}${it.path?.let { p -> " ($p:${it.line})" } ?: ""}" })
        val rep = QaRunner.replayFor(r, scenarios)
        File(dir, "replay.json").writeText(FoldForgeJson.encodeToString(QaReplay.serializer(), rep))
        withContext(Dispatchers.Main) { replay = rep.takeIf { it.scenarios.isNotEmpty() } }
        val old = p.readJson("qa.json", QaState.serializer()) ?: QaState()
        p.writeJson("qa.json", QaState.serializer(), QaState(r, (listOf(QaRunner.summary(r)) + old.history).take(50)))
        env.container.database.qaRuns().upsert(QaRunEntity(r.runId, p.id, r.startedAt, r.finishedAt, r.checkPass, r.checkFail, r.checkWarn, r.overall.name, File(dir, "qa-report.json").absolutePath))
    }

    private suspend fun fullSuite(runner: QaRunner): List<QaScenario> {
        val probe = runner.probe()
        return ScenarioGenerator.generate(probe, runner.adapterScenarios(), options()) + aiScenarios
    }

    fun runFull() {
        if (running) return
        job = env.scope.launch {
            phase = QaPhase.RUNNING
            blockedReason = null
            try {
                val r = execute({ fullSuite(it) }, "Scenario") ?: return@launch
                phase = if (r.checkFail > 0) QaPhase.FAILURE_FOUND else QaPhase.COMPLETE
                progress = QaRunner.formatReport(r).lineSequence().first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                block("QA error: ${e.message}")
            }
        }
    }

    /** "Test this game for N minutes": full suite, then scenario-driven play sessions until the time budget ends. */
    fun runTimed(minutes: Int = env.settings.value.qaSoakMinutes) {
        if (running) return
        job = env.scope.launch {
            phase = QaPhase.RUNNING
            val end = System.currentTimeMillis() + minutes * 60_000L
            var stopReason: String? = null
            try {
                val r = execute({ runner ->
                    val probe = runner.probe()
                    // Enough deterministic sessions to fill the budget; the run stops when time is up.
                    fullSuite(runner) + (1..(minutes * 60 / 5).coerceAtLeast(3)).map { ScenarioGenerator.soak(probe, it) }
                }, "Timed test", shouldContinue = {
                    when {
                        System.currentTimeMillis() > end -> { stopReason = "time budget of $minutes min reached"; false }
                        thermalTooHot() -> { stopReason = "stopped early: device is too hot"; false }
                        else -> true
                    }
                }) ?: return@launch
                phase = if (r.checkFail > 0) QaPhase.FAILURE_FOUND else QaPhase.COMPLETE
                progress = "${r.results.size} scenarios run" + (stopReason?.let { " — $it" } ?: "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                block("QA error: ${e.message}")
            }
        }
    }

    private fun thermalTooHot(): Boolean {
        val pm = env.container.app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }

    fun replayFailures() {
        val rep = replay ?: return
        if (running) return
        job = env.scope.launch {
            phase = QaPhase.RETESTING
            val r = execute({ rep.scenarios }, "Replay") ?: return@launch
            phase = if (r.checkFail > 0) QaPhase.FAILURE_FOUND else QaPhase.COMPLETE
        }
    }

    /** AI bug fixer loop: fix → preview restart → replay failures + smoke regression (max N iterations). */
    fun autoFix() {
        val r = report ?: return
        val vm = env as? com.foldforge.studio.feature.workspace.WorkspaceViewModel ?: return
        if (!vm.ai.isConfigured) { env.notify("Connect an AI provider to use the AI bug fixer"); return }
        if (running) return
        job = env.scope.launch {
            phase = QaPhase.FIXING
            try {
                val (final, attempts) = vm.ai.fixQa(r)
                report = final
                phase = if (final.checkFail > 0) QaPhase.FAILURE_FOUND else QaPhase.COMPLETE
                progress = "Fix attempts: ${attempts.size} · " + (attempts.lastOrNull()?.outcome ?: "none")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                block("Fix loop error: ${e.message}")
            }
        }
    }

    suspend fun runForAgent(ids: Set<String>?): QaReport? {
        phase = if (ids == null) QaPhase.RUNNING else QaPhase.RETESTING
        val r = execute({ runner ->
            val all = if (lastScenarios.isEmpty()) fullSuite(runner) else lastScenarios
            if (ids == null) all else all.filter { it.id in ids || it.category == "smoke" }
        }, if (ids == null) "Scenario" else "Regression")
        phase = when { r == null -> QaPhase.BLOCKED; r.checkFail > 0 -> QaPhase.FAILURE_FOUND; else -> QaPhase.COMPLETE }
        return r
    }

    fun generateAiScenarios() {
        val vm = env as? com.foldforge.studio.feature.workspace.WorkspaceViewModel ?: return
        if (!vm.ai.isConfigured) { env.notify("Connect an AI provider first"); return }
        env.scope.launch {
            progress = "Generating AI test scenarios"
            try {
                if (!env.ensurePreviewRunning()) { block("Preview did not load"); return@launch }
                val probe = env.preview?.evaluate("window.__ff ? window.__ff.probe() : '{}'") ?: "{}"
                val p = env.project
                val brain = withContext(Dispatchers.IO) { ProjectBrain.build(p) }
                val text = vm.ai.provider().generateTests(ProjectBrain.summarize(brain), probe)
                val scenarios = ScenarioGenerator.parseCustom(text, "ai")
                AgentProtocol.parseScenarioArray(text) // validates structure
                aiScenarios = scenarios
                withContext(Dispatchers.IO) {
                    val f = File(p.root, "${ProjectFileSystem.META_DIR}/qa/ai-scenarios.json")
                    f.parentFile?.mkdirs()
                    f.writeText(FoldForgeJson.encodeToString(ListSerializer(QaScenario.serializer()), scenarios))
                }
                progress = "Added ${scenarios.size} AI scenario(s)"
            } catch (e: Exception) {
                progress = "AI scenario generation failed: ${e.message}"
            }
        }
    }
}

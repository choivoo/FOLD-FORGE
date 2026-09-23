package com.foldforge.studio.core.ai

import com.foldforge.studio.core.patch.PreparedChange
import com.foldforge.studio.core.patch.PreparedChangeSet
import com.foldforge.studio.core.qa.QaCheck
import com.foldforge.studio.core.qa.QaDriver
import com.foldforge.studio.core.qa.QaOptions
import com.foldforge.studio.core.qa.QaProbe
import com.foldforge.studio.core.qa.QaReport
import com.foldforge.studio.core.qa.QaRunner
import com.foldforge.studio.core.qa.QaScenarioResult
import com.foldforge.studio.core.qa.QaStatus
import com.foldforge.studio.core.qa.ScenarioGenerator
import com.foldforge.studio.core.qa.ScreenshotInfo
import com.foldforge.studio.core.qa.ViewportPreset
import com.foldforge.studio.core.storage.Project
import com.foldforge.studio.core.storage.ProjectStore
import com.foldforge.studio.core.tempDir
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test-only scripted provider (production never fakes AI output). */
private class ScriptedProvider(private val responses: ArrayDeque<String>) : BaseAIProvider(AiConfig(apiKey = "test", model = "test-model")) {
    val requests = mutableListOf<CompletionRequest>()
    override suspend fun complete(request: CompletionRequest): CompletionResponse {
        requests += request
        return CompletionResponse(responses.removeFirst(), "test-model", 1, 1, "end_turn")
    }
}

private class FakeHost(override val project: Project, var errorsQueue: ArrayDeque<List<RuntimeIssue>>, val qaQueue: ArrayDeque<QaReport?> = ArrayDeque()) : AgentHost {
    val events = mutableListOf<AgentEvent>()
    val applied = mutableListOf<String>()
    var reviews = 0
    var reject = false
    override fun activeFile() = "runner.js"
    override fun recentFiles() = emptyList<String>()
    override suspend fun review(prepared: PreparedChangeSet, automatic: Boolean): List<PreparedChange>? {
        reviews++
        return if (reject) null else prepared.applicable
    }
    override suspend fun afterApply(paths: List<String>) { applied += paths }
    override suspend fun collectRuntimeErrors(waitMs: Long) = errorsQueue.removeFirstOrNull() ?: emptyList()
    override suspend fun runQa(scenarioIds: Set<String>?) = qaQueue.removeFirstOrNull()
    override fun emit(event: AgentEvent) { events += event }
}

class AgentAndQaTest {
    private fun project(): Project = ProjectStore(tempDir()).createFromTemplate("Runner", "forge-runner")

    private val editJson = """
        Here is the change:
        ```json
        {"plan":["Find jump velocity","Increase it"],"summary":"Higher jump",
         "changes":[{"path":"runner.js","kind":"modify","edits":[{"search":"s.player.vy = -760;","replace":"s.player.vy = -900;"}]}],
         "notes":"ok"}
        ```
    """.trimIndent()

    @Test fun `protocol parses fenced json and tolerates noise`() {
        val p = AgentProtocol.parseEditProposal(editJson)
        assertEquals(2, p.plan.size)
        assertEquals("runner.js", p.changeSet.changes.single().path)
        assertThrows(AiException::class.java) { AgentProtocol.extractJson("no json here") }
        assertThrows(AiException::class.java) { AgentProtocol.extractJson("{\"a\": \"unterminated") }
        assertEquals("[1,{\"a\":\"]\"}]", AgentProtocol.extractJson("text [1,{\"a\":\"]\"}] tail"))
        val gen = AgentProtocol.parseGeneratedProject("""{"name":"Mon","type":"threejs","entry":"index.html","files":[{"path":"index.html","content":"<html>"}]}""")
        assertEquals("Mon", gen.name)
        assertThrows(AiException::class.java) { AgentProtocol.parseGeneratedProject("""{"entry":"index.html","files":[{"path":"a.js","content":""}]}""") }
    }

    @Test fun `agent loop edits, snapshots, detects runtime error and fixes it`() = runBlocking {
        val p = project()
        val fix = """{"summary":"Fix typo","changes":[{"path":"runner.js","kind":"modify","edits":[{"search":"s.player.vy = -900;","replace":"s.player.vy = -880;"}]}]}"""
        val provider = ScriptedProvider(ArrayDeque(listOf(editJson, fix)))
        val host = FakeHost(p, ArrayDeque(listOf(listOf(RuntimeIssue("ReferenceError: foo is not defined", "runner.js", 3)), emptyList())))
        val result = CodingAgent(provider, host, autoApproveFixes = true).run("Increase jump height", runQa = false)
        assertEquals(AgentStage.COMPLETE, result.stage)
        assertTrue(p.fs.readText("runner.js").contains("s.player.vy = -880;"))
        assertEquals(2, result.snapshotIds.size)
        assertTrue(result.remainingErrors.isEmpty())
        assertEquals(2, host.reviews)
        assertTrue(host.events.any { it.stage == AgentStage.FIXING })
        assertTrue(p.history().entries.first().author == "ai")
        // Context sent to the AI contains the active file but never secrets directories
        assertTrue(provider.requests.first().messages.first().content.contains("FILE: runner.js"))
        // Undo AI change
        p.snapshots.restore(result.snapshotIds.first())
        assertTrue(p.fs.readText("runner.js").contains("s.player.vy = -760;"))
    }

    @Test fun `agent respects rejection`() = runBlocking {
        val p = project()
        val host = FakeHost(p, ArrayDeque()).apply { reject = true }
        val result = CodingAgent(ScriptedProvider(ArrayDeque(listOf(editJson))), host).run("x", false)
        assertEquals(AgentStage.REJECTED, result.stage)
        assertTrue(p.fs.readText("runner.js").contains("-760"))
    }

    private fun report(fail: Boolean) = QaReport(
        "r", 0, 0, "p", "adapter",
        listOf(
            QaScenarioResult("combat", "Combat", "combat", if (fail) QaStatus.FAIL else QaStatus.PASS, listOf(QaCheck("monster defeated", if (fail) QaStatus.FAIL else QaStatus.PASS, "kills 0 gt 0"))),
            QaScenarioResult("smoke", "Smoke", "smoke", QaStatus.PASS, listOf(QaCheck("no errors", QaStatus.PASS))),
        ),
    )

    @Test fun `qa fix loop stops when regression passes`() = runBlocking {
        val p = project()
        val fix = """{"summary":"Fix hitbox","changes":[{"path":"runner.js","kind":"modify","edits":[{"search":"s.player.vy = -760;","replace":"s.player.vy = -770;"}]}]}"""
        val host = FakeHost(p, ArrayDeque(), ArrayDeque(listOf(report(false))))
        val (final, attempts) = CodingAgent(ScriptedProvider(ArrayDeque(listOf(fix))), host, autoApproveFixes = true).fixQaFailures(report(true))
        assertEquals(1, attempts.size)
        assertEquals("fixed", attempts.single().outcome)
        assertEquals(0, final.checkFail)
    }

    @Test fun `qa fix loop detects no progress`() = runBlocking {
        val p = project()
        val fix1 = """{"summary":"a","changes":[{"path":"runner.js","kind":"modify","edits":[{"search":"s.player.vy = -760;","replace":"s.player.vy = -761;"}]}]}"""
        val host = FakeHost(p, ArrayDeque(), ArrayDeque(listOf(report(true))))
        val (_, attempts) = CodingAgent(ScriptedProvider(ArrayDeque(listOf(fix1))), host, maxFixIterations = 3, autoApproveFixes = true).fixQaFailures(report(true))
        assertEquals(1, attempts.size)
        assertTrue(attempts.single().outcome.startsWith("no progress"))
    }

    @Test fun `scenario generator covers the required categories`() {
        val probe = QaProbe(adapter = true, actions = listOf("left", "right", "jump", "attack"), debug = listOf("killPlayer"), canvas = true, stateKeys = listOf("player", "gameOver", "score"))
        val cats = ScenarioGenerator.generate(probe, emptyList()).map { it.category }.toSet()
        listOf("smoke", "movement", "combat", "ui", "restart", "death", "touch", "orientation", "performance").forEach { assertTrue("missing $it", it in cats) }
        val blackbox = ScenarioGenerator.generate(QaProbe(adapter = false, actions = listOf("left", "right"), canvas = true), emptyList())
        assertTrue(blackbox.first { it.id == "movement" }.steps.any { (it["expect"] as? JsonPrimitive)?.contentOrNull == "canvasChanged" })
        assertEquals(ScenarioGenerator.soak(probe, 7).steps, ScenarioGenerator.soak(probe, 7).steps) // deterministic replay
    }

    /** Fake driver emulating the injected runtime's qa.start/poll protocol. */
    private class FakeDriver(val results: Map<String, String>) : QaDriver {
        var viewports = mutableListOf<String?>()
        private var last = ""
        override suspend fun evaluate(script: String): String? = when {
            script.contains("probe()") -> """{"adapter":true,"actions":["right"],"canvas":true,"stateKeys":["player"]}"""
            script.contains("customScenarios") -> "[]"
            script.contains("qa.start") -> {
                last = Regex("\\\\\"id\\\\\":\\\\\"([^\\\\]+)").find(script)!!.groupValues[1]
                "run1"
            }
            script.contains("qa.poll") -> """{"done":true,"result":${results[last] ?: """{"id":"$last","name":"$last","category":"x","status":"PASS","checks":[{"name":"ok","status":"PASS","message":"","evidence":null}],"errors":[],"timeline":[],"durationMs":5}"""}}"""
            else -> null
        }
        override suspend fun reload() {}
        override suspend fun setViewport(preset: ViewportPreset?) { viewports += preset?.id }
        override suspend fun screenshot(label: String) = ScreenshotInfo("shots/$label.png", null, 0.0, 0.0)
        override fun consoleErrors() = listOf("Uncaught TypeError: x")
    }

    @Test fun `qa runner produces report with counts, viewport handling and perf alerts`() = runBlocking {
        val perf = """{"id":"performance","name":"Performance","category":"performance","status":"WARN","checks":[{"name":"average FPS","status":"WARN","message":"Average FPS 30","evidence":{"fps":30,"longFrames":4,"worstFrameMs":80}}],"errors":[],"timeline":[],"durationMs":2500,"metrics":{"avgFps":30,"worstFrameMs":80,"loadTimeMs":120}}"""
        val driver = FakeDriver(mapOf("performance" to perf))
        val runner = QaRunner(driver, QaOptions(pauseBetweenScenariosMs = 0, maxScreenshots = 2))
        val probe = runner.probe()
        val scenarios = ScenarioGenerator.generate(probe, runner.adapterScenarios())
        val report = runner.run("Test", scenarios)
        assertEquals(scenarios.size, report.results.size)
        assertTrue(report.performance.alerts.any { it.contains("FPS 30") })
        assertTrue(report.performance.alerts.any { it.contains("80ms") })
        assertTrue("uniform screenshot flagged", report.results.first().checks.any { it.name == "screenshot" })
        assertTrue(driver.viewports.contains("fold-outer"))
        assertEquals(null, driver.viewports.last()) // viewport reset at end
        assertEquals(listOf("Uncaught TypeError: x"), report.consoleErrors)
        val json = Json.encodeToString(QaReport.serializer(), report)
        assertTrue((Json.parseToJsonElement(json) as JsonObject).containsKey("results"))
        assertFalse(QaRunner.formatReport(report).isBlank())
    }

    @Test fun `runner reports blocked when runtime missing`() = runBlocking {
        val driver = object : QaDriver {
            override suspend fun evaluate(script: String): String? = null
            override suspend fun reload() {}
            override suspend fun setViewport(preset: ViewportPreset?) {}
            override suspend fun screenshot(label: String): ScreenshotInfo? = null
            override fun consoleErrors() = emptyList<String>()
        }
        assertThrows(com.foldforge.studio.core.qa.QaBlockedException::class.java) { runBlocking { QaRunner(driver).probe() } }
        Unit
    }

    @Test fun `openai compatible provider speaks http and maps errors`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"model":"m","choices":[{"message":{"content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        server.start()
        val cfg = AiConfig(ProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString(), "m", "secret-key")
        val provider = AIProviderFactory.create(cfg)
        assertEquals("hello", provider.complete(CompletionRequest("sys", listOf(ChatMessage("user", "hi")), 10)).text)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer secret-key", recorded.getHeader("Authorization"))
        val err = runCatching { provider.complete(CompletionRequest("s", listOf(ChatMessage("user", "x")), 5)) }.exceptionOrNull()
        assertTrue(err is AiException && err.message!!.contains("401"))
        server.shutdown()
        assertFalse(cfg.toString().contains("secret-key"))
        assertThrows(AiException::class.java) { AIProviderFactory.create(AiConfig(apiKey = "")) }
        Unit
    }
}

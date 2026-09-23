package com.foldforge.studio.core.qa

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.random.Random

data class ScreenshotInfo(val path: String, val png: ByteArray?, val meanLuma: Double, val lumaVariance: Double)

/**
 * The QA agent talks to the running preview only through this interface: evaluating JavaScript in the
 * sandboxed page (the injected FOLD FORGE runtime), reloading, resizing and capturing screenshots.
 * Android implements it with WebView; tests implement it with a headless browser or fakes.
 */
interface QaDriver {
    /** Evaluates [script] in the page and returns the resulting value as a plain string (null for undefined/null). */
    suspend fun evaluate(script: String): String?
    suspend fun reload()
    suspend fun setViewport(preset: ViewportPreset?)
    suspend fun screenshot(label: String): ScreenshotInfo?
    fun consoleErrors(): List<String>
}

@Serializable
data class QaProbe(
    val adapter: Boolean = false,
    val adapterVersion: Int = 0,
    val actions: List<String> = emptyList(),
    val debug: List<String> = emptyList(),
    val customScenarios: Int = 0,
    val canvas: Boolean = false,
    val webgl: Boolean = false,
    val buttons: Int = 0,
    val stateKeys: List<String> = emptyList(),
    val viewport: List<Int> = emptyList(),
    val title: String = "",
    val readyState: String = "",
    val errors: Int = 0,
)

data class QaOptions(
    val includeOrientation: Boolean = true,
    val screenshots: Boolean = true,
    val maxScreenshots: Int = 6,
    val pauseBetweenScenariosMs: Long = 250,
    val scenarioTimeoutMs: Long = 30_000,
    val fpsThreshold: Int = 45,
    val longFrameMs: Int = 50,
)

class QaBlockedException(message: String) : Exception(message)

object ScenarioGenerator {
    private val json = Json { ignoreUnknownKeys = true }

    private fun step(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            null -> {}
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            else -> put(k, v.toString())
        }
    }

    fun parseCustom(jsonText: String, source: String): List<QaScenario> = runCatching {
        (json.parseToJsonElement(jsonText) as JsonArray).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val steps = (o["steps"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return@mapNotNull null
            QaScenario(
                id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: "scenario-${UUID.randomUUID().toString().take(6)}",
                name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: "Custom scenario",
                category = (o["category"] as? JsonPrimitive)?.contentOrNull ?: "gameplay",
                steps = steps.take(200),
                source = source,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Builds the standard scenario suite from the runtime probe: Smoke, Movement, Combat (via adapter
     * scenarios), UI, Restart, Death, Mobile Touch, Orientation and Performance.
     */
    fun generate(probe: QaProbe, adapterScenarios: List<QaScenario>, options: QaOptions = QaOptions()): List<QaScenario> {
        val out = ArrayList<QaScenario>()
        val hasPlayerX = probe.stateKeys.contains("player")
        out += QaScenario(
            "smoke", "Smoke: loads and renders without errors", "smoke",
            buildList {
                add(step("action" to "wait", "ms" to 800))
                add(step("expect" to "noErrors", "name" to "no runtime errors on load"))
                if (probe.canvas) add(step("expect" to "canvasNotBlank", "name" to "screen is rendering (not blank/frozen)"))
                add(step("expect" to "stateAvailable", "name" to "QA test adapter present", "severity" to "warn"))
            },
        )
        val moveButton = listOf("right", "left", "up").firstOrNull { it in probe.actions }
        if (moveButton != null) {
            out += QaScenario(
                "movement", "Movement: player responds to input", "movement",
                if (probe.adapter && hasPlayerX) listOf(
                    step("action" to "restart"), step("action" to "wait", "ms" to 300),
                    step("action" to "snapshot", "as" to "before"),
                    step("action" to "press", "button" to moveButton, "ms" to 600),
                    step("expect" to "changed", "path" to "player", "from" to "before", "name" to "player state changes after '$moveButton'"),
                    step("expect" to "noErrors"),
                ) else listOf(
                    step("action" to "canvasSnapshot", "as" to "before"),
                    step("action" to "press", "button" to moveButton, "ms" to 600),
                    step("expect" to "canvasChanged", "from" to "before", "name" to "screen changes after '$moveButton'", "severity" to "warn"),
                    step("expect" to "noErrors"),
                ),
            )
        }
        if ("jump" in probe.actions) {
            out += QaScenario(
                "jump", "Jump: jump input is handled", "movement",
                listOf(
                    step("action" to "snapshot", "as" to "j0"),
                    step("action" to "press", "button" to "jump", "ms" to 120),
                    step("action" to "wait", "ms" to 100),
                    step("expect" to "changed", "path" to "player", "from" to "j0", "name" to "player reacts to jump", "severity" to if (probe.adapter) "fail" else "warn"),
                    step("action" to "wait", "ms" to 900),
                    step("expect" to "noErrors"),
                ),
            )
        }
        if ("attack" in probe.actions && adapterScenarios.none { it.category == "combat" }) {
            out += QaScenario(
                "combat-input", "Combat: attack input is handled", "combat",
                listOf(step("action" to "press", "button" to "attack", "ms" to 100), step("action" to "wait", "ms" to 300), step("expect" to "noErrors")),
            )
        }
        out += adapterScenarios
        out += QaScenario(
            "ui-layout", "UI: controls inside viewport, no overlaps", "ui",
            listOf(step("expect" to "layout", "name" to "layout")),
        )
        out += QaScenario(
            "touch", "Mobile Touch: taps and drags are handled", "touch",
            listOf(
                step("action" to "tap", "x" to 0.5, "y" to 0.5),
                step("action" to "drag", "x1" to 0.3, "y1" to 0.6, "x2" to 0.6, "y2" to 0.6, "ms" to 300),
                step("action" to "wait", "ms" to 200),
                step("expect" to "noErrors", "name" to "no errors from touch input"),
            ),
        )
        out += QaScenario(
            "restart", "Restart: game restarts cleanly", "restart",
            buildList {
                add(step("action" to "restart"))
                add(step("action" to "wait", "ms" to 400))
                if (probe.stateKeys.contains("gameOver")) add(step("expect" to "falsy", "path" to "gameOver", "name" to "not game over after restart"))
                add(step("expect" to "noErrors"))
            },
        )
        if ("killPlayer" in probe.debug && adapterScenarios.none { it.category == "death" }) {
            out += QaScenario(
                "death", "Death: game over and recovery", "death",
                listOf(
                    step("action" to "restart"), step("action" to "debug", "call" to "killPlayer"), step("action" to "wait", "ms" to 250),
                    step("expect" to "truthy", "path" to "gameOver", "name" to "game over state reached"),
                    step("action" to "restart"), step("action" to "wait", "ms" to 400),
                    step("expect" to "falsy", "path" to "gameOver", "name" to "recovers after restart"),
                    step("expect" to "noErrors"),
                ),
            )
        }
        if (options.includeOrientation) {
            for (preset in listOf(ViewportPresets.FOLD_OUTER, ViewportPresets.FOLD_OUTER_LANDSCAPE, ViewportPresets.FOLD_INNER)) {
                out += QaScenario(
                    "orientation-${preset.id}", "Orientation: ${preset.label} ${preset.width}×${preset.height}", "orientation",
                    buildList {
                        add(step("action" to "wait", "ms" to 400))
                        add(step("expect" to "layout", "name" to preset.label))
                        if (probe.canvas) add(step("expect" to "canvasNotBlank", "name" to "renders at ${preset.label}"))
                        add(step("expect" to "noErrors"))
                    },
                    viewport = preset.id,
                )
            }
        }
        out += QaScenario(
            "performance", "Performance: frame rate and long frames", "performance",
            listOf(
                step("action" to "sampleFps", "ms" to 2500, "as" to "perf"),
                step("expect" to "fps", "min" to options.fpsThreshold, "from" to "perf", "name" to "average FPS"),
                step("expect" to "longFrames", "maxMs" to options.longFrameMs, "from" to "perf", "name" to "long frames"),
            ),
        )
        return out.distinctBy { it.id }
    }

    /** Scenario-driven soak sequence for timed runs ("test this game for 3 minutes"). */
    fun soak(probe: QaProbe, seed: Int, lengthSteps: Int = 12): QaScenario {
        val rnd = Random(seed)
        val actions = probe.actions.filter { it !in setOf("menu") }.ifEmpty { listOf("left", "right", "jump") }
        val steps = ArrayList<JsonObject>()
        steps += step("action" to "snapshot", "as" to "s")
        repeat(lengthSteps) {
            when (rnd.nextInt(6)) {
                0 -> steps += step("action" to "tap", "x" to (0.2 + rnd.nextDouble() * 0.6), "y" to (0.3 + rnd.nextDouble() * 0.5))
                1 -> steps += step("action" to "joystick", "x" to (rnd.nextDouble() * 2 - 1), "y" to (rnd.nextDouble() * 2 - 1), "ms" to 300 + rnd.nextInt(500))
                else -> steps += step("action" to "press", "button" to actions[rnd.nextInt(actions.size)], "ms" to 80 + rnd.nextInt(500))
            }
        }
        steps += step("expect" to "noErrors", "name" to "no errors during play session")
        if (probe.canvas) steps += step("expect" to "canvasNotBlank", "name" to "still rendering")
        return QaScenario("soak-$seed", "Play session #$seed", "soak", steps, source = "generated")
    }
}

/** Runs scenarios through a [QaDriver] and builds a [QaReport]. */
class QaRunner(private val driver: QaDriver, private val options: QaOptions = QaOptions()) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun probe(): QaProbe {
        val raw = driver.evaluate("window.__ff ? window.__ff.probe() : null")
            ?: throw QaBlockedException("FOLD FORGE runtime not present in the preview (is the preview running?)")
        return json.decodeFromString(QaProbe.serializer(), raw)
    }

    suspend fun adapterScenarios(): List<QaScenario> {
        val raw = driver.evaluate("window.__ff ? window.__ff.customScenarios() : '[]'") ?: "[]"
        return ScenarioGenerator.parseCustom(raw, "adapter")
    }

    suspend fun runScenario(s: QaScenario): QaScenarioResult {
        val payload = json.encodeToString(QaScenario.serializer(), s)
        val id = driver.evaluate("window.__ff.qa.start(${JsonPrimitive(payload)})")
            ?: return blocked(s, "Could not start scenario (runtime unavailable)")
        val stepBudget = s.steps.sumOf { (it["ms"] as? JsonPrimitive)?.intOrNull ?: 100 }.toLong()
        val timeoutMs = stepBudget + options.scenarioTimeoutMs
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(150)
            val polled = driver.evaluate("window.__ff ? window.__ff.qa.poll(${JsonPrimitive(id)}) : null")
                ?: return blocked(s, "Preview page was reloaded or closed during the scenario")
            val obj = json.parseToJsonElement(polled) as? JsonObject ?: continue
            if ((obj["done"] as? JsonPrimitive)?.contentOrNull != "true") continue
            val result = obj["result"] ?: return blocked(s, "Scenario result missing")
            return json.decodeFromJsonElement(QaScenarioResult.serializer(), result).copy(viewport = s.viewport)
        }
        return QaScenarioResult(s.id, s.name, s.category, QaStatus.FAIL, listOf(QaCheck("timeout", QaStatus.FAIL, "Scenario did not finish within ${timeoutMs / 1000}s (page hung or timers throttled)")), viewport = s.viewport)
    }

    private fun blocked(s: QaScenario, message: String) =
        QaScenarioResult(s.id, s.name, s.category, QaStatus.BLOCKED, listOf(QaCheck("runner", QaStatus.BLOCKED, message)), viewport = s.viewport)

    /**
     * Runs the scenarios. Viewport changes are applied between scenarios and reset at the end.
     * [onProgress] receives (index, total, scenario name). [vision] optionally analyses screenshots.
     */
    suspend fun run(
        projectName: String,
        scenarios: List<QaScenario>,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
        vision: (suspend (ScreenshotInfo, String) -> List<QaCheck>)? = null,
        mode: String = "adapter",
        shouldContinue: () -> Boolean = { true },
    ): QaReport {
        val started = System.currentTimeMillis()
        val results = ArrayList<QaScenarioResult>()
        var shots = 0
        var currentViewport: String? = null
        try {
            for ((i, s) in scenarios.withIndex()) {
                if (!shouldContinue()) break
                onProgress(i, scenarios.size, s.name)
                if (s.viewport != currentViewport) {
                    driver.setViewport(ViewportPresets.byId(s.viewport))
                    currentViewport = s.viewport
                    delay(400)
                }
                var r = runScenario(s)
                if (options.screenshots && shots < options.maxScreenshots && (r.status != QaStatus.PASS || s.category in setOf("smoke", "orientation"))) {
                    val shot = runCatching { driver.screenshot(s.id) }.getOrNull()
                    if (shot != null) {
                        shots++
                        val extra = ArrayList<QaCheck>()
                        if (shot.lumaVariance < 1.0) extra += QaCheck("screenshot", QaStatus.WARN, "Screenshot is uniform (mean luma ${"%.1f".format(shot.meanLuma)}) — screen may be black or frozen")
                        if (vision != null) extra += runCatching { vision(shot, s.viewport ?: "current") }.getOrElse { listOf(QaCheck("vision", QaStatus.WARN, "Vision analysis failed: ${it.message}")) }
                        r = r.copy(screenshot = shot.path, checks = r.checks + extra, status = worst(r.status, extra))
                    }
                }
                results += r
                delay(options.pauseBetweenScenariosMs)
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            if (currentViewport != null) runCatching { driver.setViewport(null) }
        }
        onProgress(scenarios.size, scenarios.size, "Complete")
        return QaReport(
            runId = "qa-" + started + "-" + UUID.randomUUID().toString().take(4),
            startedAt = started,
            finishedAt = System.currentTimeMillis(),
            projectName = projectName,
            mode = mode,
            results = results,
            performance = performance(results),
            consoleErrors = driver.consoleErrors().takeLast(50),
        )
    }

    private fun worst(status: QaStatus, extra: List<QaCheck>): QaStatus = when {
        status == QaStatus.FAIL || extra.any { it.status == QaStatus.FAIL } -> QaStatus.FAIL
        status == QaStatus.WARN || extra.any { it.status == QaStatus.WARN } -> QaStatus.WARN
        else -> status
    }

    private fun performance(results: List<QaScenarioResult>): QaPerformance {
        val perfCheck = results.firstOrNull { it.category == "performance" }?.checks?.firstOrNull { it.name == "average FPS" }
        val perfEvidence = perfCheck?.evidence as? JsonObject
        val metrics = results.lastOrNull { it.metrics is JsonObject }?.metrics as? JsonObject
        fun JsonElement?.int() = (this as? JsonPrimitive)?.intOrNull
        val fps = perfEvidence?.get("fps").int() ?: metrics?.get("avgFps").int()
        val worstFrame = perfEvidence?.get("worstFrameMs").int() ?: metrics?.get("worstFrameMs").int()
        val longFrames = perfEvidence?.get("longFrames").int() ?: metrics?.get("longFrames").int()
        val load = metrics?.get("loadTimeMs").int()
        val heap = ((metrics?.get("heap") as? JsonObject)?.get("usedMB") as? JsonPrimitive)?.doubleOrNull
        val errors = results.sumOf { it.errors.size }
        val alerts = buildList {
            if (fps != null && fps < options.fpsThreshold) add("Average FPS $fps < ${options.fpsThreshold}")
            if (worstFrame != null && worstFrame > options.longFrameMs) add("Long frame ${worstFrame}ms > ${options.longFrameMs}ms")
            if (errors > 0) add("JS errors: $errors")
        }
        return QaPerformance(fps, worstFrame, longFrames, load, heap, alerts)
    }

    companion object {
        fun replayFor(report: QaReport, scenarios: List<QaScenario>): QaReplay {
            val failing = report.results.filter { it.status == QaStatus.FAIL }.map { it.id }.toSet()
            return QaReplay(report.runId, System.currentTimeMillis(), scenarios.filter { it.id in failing }.map { it.copy(source = "replay") })
        }

        fun summary(report: QaReport) = QaRunSummary(report.runId, report.finishedAt, report.checkPass, report.checkFail, report.checkWarn, report.overall)

        /** Stable fingerprint of a set of failures, used to detect that a fix attempt made no progress. */
        fun failureFingerprint(report: QaReport): String =
            report.results.flatMap { r -> r.checks.filter { it.status == QaStatus.FAIL }.map { "${r.id}/${it.name}" } }.sorted().joinToString("|")

        fun formatReport(report: QaReport): String = buildString {
            append("QA ${report.overall}  —  PASS ${report.checkPass} · FAIL ${report.checkFail} · WARN ${report.checkWarn}\n")
            if (report.failures.isNotEmpty()) {
                append("\nFailures:\n")
                report.failures.forEach { append("- ").append(it).append('\n') }
            }
            if (report.performance.alerts.isNotEmpty()) {
                append("\nPerformance alerts:\n")
                report.performance.alerts.forEach { append("- ").append(it).append('\n') }
            }
        }
    }
}

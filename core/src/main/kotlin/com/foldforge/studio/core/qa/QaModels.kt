package com.foldforge.studio.core.qa

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class QaStatus { PASS, FAIL, WARN, BLOCKED }

@Serializable
data class QaScenario(
    val id: String,
    val name: String,
    val category: String,
    val steps: List<JsonObject>,
    /** Optional viewport preset applied by the host before running (Orientation / Fold tests). */
    val viewport: String? = null,
    val source: String = "generated", // generated | adapter | ai | replay
)

@Serializable
data class QaCheck(val name: String, val status: QaStatus, val message: String = "", val evidence: JsonElement? = null)

@Serializable
data class QaError(val kind: String = "", val message: String = "", val source: String = "", val line: Int = 0, val column: Int = 0, val stack: String = "")

@Serializable
data class QaScenarioResult(
    val id: String,
    val name: String,
    val category: String,
    val status: QaStatus,
    val checks: List<QaCheck> = emptyList(),
    val errors: List<QaError> = emptyList(),
    val timeline: List<JsonElement> = emptyList(),
    val durationMs: Long = 0,
    val metrics: JsonElement? = null,
    val viewport: String? = null,
    val screenshot: String? = null,
)

@Serializable
data class QaPerformance(
    val avgFps: Int? = null,
    val worstFrameMs: Int? = null,
    val longFrames: Int? = null,
    val loadTimeMs: Int? = null,
    val heapMB: Double? = null,
    val alerts: List<String> = emptyList(),
)

@Serializable
data class QaReport(
    val runId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val projectName: String,
    val mode: String, // adapter | blackbox
    val results: List<QaScenarioResult>,
    val performance: QaPerformance = QaPerformance(),
    val consoleErrors: List<String> = emptyList(),
    val fixAttempts: List<QaFixAttempt> = emptyList(),
) {
    val checkPass get() = results.sumOf { r -> r.checks.count { it.status == QaStatus.PASS } }
    val checkFail get() = results.sumOf { r -> r.checks.count { it.status == QaStatus.FAIL } }
    val checkWarn get() = results.sumOf { r -> r.checks.count { it.status == QaStatus.WARN } }
    val failures get() = results.flatMap { r -> r.checks.filter { it.status == QaStatus.FAIL }.map { "${r.name}: ${it.name} — ${it.message}" } }
    val overall: QaStatus
        get() = when {
            results.isEmpty() -> QaStatus.BLOCKED
            results.any { it.status == QaStatus.FAIL } -> QaStatus.FAIL
            results.any { it.status == QaStatus.WARN } -> QaStatus.WARN
            else -> QaStatus.PASS
        }
}

@Serializable
data class QaFixAttempt(val iteration: Int, val targetFailures: List<String>, val changedFiles: List<String>, val outcome: String)

/** Replay file: exact input sequences of failing scenarios, re-runnable after a fix. */
@Serializable
data class QaReplay(val runId: String, val createdAt: Long, val scenarios: List<QaScenario>)

/** Stored in `.foldforge/qa.json`. */
@Serializable
data class QaState(val lastReport: QaReport? = null, val history: List<QaRunSummary> = emptyList())

@Serializable
data class QaRunSummary(val runId: String, val timestamp: Long, val pass: Int, val fail: Int, val warn: Int, val overall: QaStatus)

/** Viewport presets (CSS px) used by Orientation / Fold tests and the preview device menu. */
@Serializable
data class ViewportPreset(val id: String, val label: String, val width: Int, val height: Int)

object ViewportPresets {
    val PHONE = ViewportPreset("phone", "Phone", 393, 852)
    val FOLD_OUTER = ViewportPreset("fold-outer", "Fold Outer", 360, 900)
    val FOLD_OUTER_LANDSCAPE = ViewportPreset("fold-outer-land", "Fold Outer Landscape", 900, 360)
    val FOLD_INNER = ViewportPreset("fold-inner", "Fold Inner", 884, 1000)
    val FOLD_INNER_LANDSCAPE = ViewportPreset("fold-inner-land", "Fold Inner Landscape", 1000, 884)
    val TABLET = ViewportPreset("tablet", "Tablet", 1024, 1366)
    val DESKTOP = ViewportPreset("desktop", "Desktop", 1440, 900)
    val ALL = listOf(PHONE, FOLD_OUTER, FOLD_OUTER_LANDSCAPE, FOLD_INNER, FOLD_INNER_LANDSCAPE, TABLET, DESKTOP)
    fun byId(id: String?) = ALL.firstOrNull { it.id == id }
}

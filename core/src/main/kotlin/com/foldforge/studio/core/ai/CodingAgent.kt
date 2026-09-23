package com.foldforge.studio.core.ai

import com.foldforge.studio.core.patch.PatchEngine
import com.foldforge.studio.core.patch.PreparedChange
import com.foldforge.studio.core.patch.PreparedChangeSet
import com.foldforge.studio.core.qa.QaFixAttempt
import com.foldforge.studio.core.qa.QaReport
import com.foldforge.studio.core.qa.QaRunner
import com.foldforge.studio.core.qa.QaStatus
import com.foldforge.studio.core.storage.Project

enum class AgentStage(val label: String) {
    ANALYZING("Analyzing"), EDITING("Editing"), REVIEW("Waiting for review"), APPLYING("Applying"),
    TESTING("Testing"), FIXING("Fixing"), BUILDING("Building"), COMPLETE("Complete"), FAILED("Failed"), REJECTED("Rejected"),
}

data class AgentEvent(val stage: AgentStage, val message: String, val timestamp: Long = System.currentTimeMillis())

data class RuntimeIssue(val message: String, val path: String?, val line: Int?)

/** Everything the agent needs from the app (UI review, preview, console, QA). */
interface AgentHost {
    val project: Project
    fun activeFile(): String?
    fun recentFiles(): List<String>

    /** Shows the diff review. Returns the approved subset, or null if the user rejected everything. */
    suspend fun review(prepared: PreparedChangeSet, automatic: Boolean): List<PreparedChange>?

    /** Called after files changed on disk: refresh editors, reload preview. */
    suspend fun afterApply(paths: List<String>)

    /** Collects runtime errors reported by the preview since the last reload, waiting up to [waitMs]. */
    suspend fun collectRuntimeErrors(waitMs: Long): List<RuntimeIssue>

    /** Runs QA. [scenarioIds] null = full suite; otherwise replay those plus smoke regression. Null if QA unavailable. */
    suspend fun runQa(scenarioIds: Set<String>?): QaReport?

    fun emit(event: AgentEvent)
}

data class AgentResult(
    val stage: AgentStage,
    val summary: String,
    val plan: List<String>,
    val changedFiles: List<String>,
    val added: Int,
    val removed: Int,
    val remainingErrors: List<String>,
    val qaReport: QaReport?,
    val fixAttempts: List<QaFixAttempt>,
    val notes: String,
    val snapshotIds: List<String>,
)

/**
 * The project-aware coding agent loop:
 * analyse → select context (Project Brain) → plan/edit → diff → review → snapshot+apply → reload preview →
 * read console → auto-fix runtime errors → QA → auto-fix QA failures → regression → report.
 */
class CodingAgent(
    private val provider: AIProvider,
    private val host: AgentHost,
    private val scope: ContextScope = ContextScope.RELATED_FILES,
    private val maxFixIterations: Int = 3,
    private val autoApproveFixes: Boolean = false,
) {
    private val project get() = host.project
    private val patcher by lazy { PatchEngine(project.fs) }
    private val changed = LinkedHashSet<String>()
    private val snapshots = ArrayList<String>()
    private var added = 0
    private var removed = 0

    private fun emit(stage: AgentStage, msg: String) = host.emit(AgentEvent(stage, msg))

    private fun brain(knownBugs: List<String> = emptyList()): BrainIndex =
        ProjectBrain.build(project, knownBugs).also { ProjectBrain.save(project, it) }

    private fun context(index: BrainIndex, errorFiles: List<String>) =
        ContextSelector(project.fs).select(scope, host.activeFile(), index, errorFiles, host.recentFiles() + changed, provider.config.maxContextChars)

    /** Returns applied paths, or null if rejected / nothing applicable. */
    private suspend fun applyProposal(proposal: EditProposal, label: String, reason: String, automatic: Boolean): List<String>? {
        val prepared = patcher.prepare(proposal.changeSet)
        prepared.files.filter { !it.ok }.forEach { emit(AgentStage.EDITING, "Skipped ${it.path}: ${it.error}") }
        if (prepared.applicable.isEmpty()) {
            emit(AgentStage.EDITING, "No applicable changes in AI response")
            return null
        }
        emit(AgentStage.REVIEW, "Files changed: ${prepared.applicable.size}  +${prepared.added} −${prepared.removed}")
        val approved = host.review(prepared, automatic) ?: return null
        if (approved.isEmpty()) return null
        emit(AgentStage.APPLYING, "Applying ${approved.size} file(s)")
        val snap = project.snapshots.create(label, reason, approved.map { it.path })
        snapshots += snap.id
        val touched = patcher.apply(approved)
        project.addHistory("ai", proposal.changeSet.summary, touched, snap.id)
        changed += touched
        added += approved.sumOf { it.added }
        removed += approved.sumOf { it.removed }
        host.afterApply(touched)
        return touched
    }

    private suspend fun fixRuntimeErrors(): List<String> {
        var remaining = emptyList<RuntimeIssue>()
        var lastFingerprint = ""
        for (iteration in 0..maxFixIterations) {
            emit(AgentStage.TESTING, if (iteration == 0) "Reading preview console" else "Re-checking console (fix ${iteration})")
            remaining = host.collectRuntimeErrors(2500)
            if (remaining.isEmpty()) {
                emit(AgentStage.TESTING, "No runtime errors")
                return emptyList()
            }
            val fingerprint = remaining.joinToString("|") { it.message }
            if (iteration == maxFixIterations || fingerprint == lastFingerprint && iteration > 0) break
            lastFingerprint = fingerprint
            emit(AgentStage.FIXING, "Fixing ${remaining.size} runtime error(s): ${remaining.first().message.take(120)}")
            val index = brain(remaining.map { it.message })
            val files = context(index, remaining.mapNotNull { it.path }).files
            val proposal = provider.fixError(remaining.map { i -> i.message + (i.path?.let { " ($it:${i.line ?: 0})" } ?: "") }, ProjectBrain.summarize(index), files)
            applyProposal(proposal, "Before AI fix: ${proposal.changeSet.summary}", "ai-edit", autoApproveFixes) ?: break
        }
        return remaining.map { it.message }
    }

    /** QA fix loop with replay of failing scenarios + smoke regression. */
    suspend fun fixQaFailures(initial: QaReport): Pair<QaReport, List<QaFixAttempt>> {
        var report = initial
        val attempts = ArrayList<QaFixAttempt>()
        var lastFingerprint = QaRunner.failureFingerprint(report)
        for (iteration in 1..maxFixIterations) {
            val failing = report.results.filter { it.status == QaStatus.FAIL }
            if (failing.isEmpty()) break
            emit(AgentStage.FIXING, "QA fix $iteration/$maxFixIterations: ${failing.size} failing scenario(s)")
            val evidence = failing.flatMap { r ->
                r.checks.filter { it.status == QaStatus.FAIL }.map { "[${r.name}] ${it.name}: ${it.message}" } +
                    r.errors.map { "[${r.name}] runtime error: ${it.message} ${it.source}:${it.line}\n${it.stack.take(500)}" }
            }
            val errorFiles = failing.flatMap { r -> r.errors.mapNotNull { e -> previewPathOf(e.source) } }.distinct()
            val index = brain(evidence.take(10))
            val proposal = provider.fixError(evidence, ProjectBrain.summarize(index), context(index, errorFiles).files)
            val touched = applyProposal(proposal, "Before QA autofix #$iteration", "qa-autofix", autoApproveFixes)
            if (touched == null) {
                attempts += QaFixAttempt(iteration, evidence.take(5), emptyList(), "no applicable fix / rejected")
                break
            }
            emit(AgentStage.TESTING, "Regression: replaying failing scenarios + smoke tests")
            val rerun = host.runQa(failing.map { it.id }.toSet()) ?: break
            val fp = QaRunner.failureFingerprint(rerun)
            val outcome = when {
                rerun.checkFail == 0 -> "fixed"
                fp == lastFingerprint -> "no progress (same failures)"
                else -> "partially fixed"
            }
            attempts += QaFixAttempt(iteration, evidence.take(5), touched, outcome)
            report = rerun
            if (outcome == "fixed" || outcome.startsWith("no progress")) break
            lastFingerprint = fp
        }
        return report.copy(fixAttempts = attempts) to attempts
    }

    suspend fun run(request: String, runQa: Boolean): AgentResult {
        try {
            emit(AgentStage.ANALYZING, "Indexing project and selecting context")
            val index = brain()
            val ctx = context(index, emptyList())
            emit(AgentStage.ANALYZING, "Context: ${ctx.files.size} file(s), ${ctx.totalChars / 1000}k chars" +
                (if (ctx.excludedSensitive.isNotEmpty()) ", excluded ${ctx.excludedSensitive.size} sensitive file(s)" else ""))
            emit(AgentStage.EDITING, "Requesting changes from ${provider.config.model}")
            val proposal = provider.editFiles(request, ProjectBrain.summarize(index), ctx.files)
            proposal.plan.forEach { emit(AgentStage.EDITING, "Plan: $it") }
            if (proposal.changeSet.changes.isEmpty()) {
                emit(AgentStage.COMPLETE, "No file changes proposed")
                return result(AgentStage.COMPLETE, proposal.changeSet.summary, proposal.plan, emptyList(), null, emptyList(), proposal.notes)
            }
            applyProposal(proposal, "Before AI: ${proposal.changeSet.summary}", "ai-edit", automatic = false)
                ?: return result(AgentStage.REJECTED, "Changes rejected", proposal.plan, emptyList(), null, emptyList(), proposal.notes).also {
                    emit(AgentStage.REJECTED, "No changes applied")
                }
            val remaining = fixRuntimeErrors()
            var qa: QaReport? = null
            var attempts: List<QaFixAttempt> = emptyList()
            if (runQa) {
                emit(AgentStage.TESTING, "Running gameplay QA")
                qa = host.runQa(null)
                if (qa != null && qa.checkFail > 0) {
                    val (fixed, a) = fixQaFailures(qa)
                    qa = fixed
                    attempts = a
                }
            }
            emit(AgentStage.COMPLETE, "Changed ${changed.size} file(s)" + (qa?.let { ", QA ${it.overall}" } ?: ""))
            return result(AgentStage.COMPLETE, proposal.changeSet.summary, proposal.plan, remaining, qa, attempts, proposal.notes)
        } catch (e: AiException) {
            emit(AgentStage.FAILED, e.message ?: "AI error")
            throw e
        }
    }

    private fun result(stage: AgentStage, summary: String, plan: List<String>, remaining: List<String>, qa: QaReport?, attempts: List<QaFixAttempt>, notes: String) =
        AgentResult(stage, summary, plan, changed.toList(), added, removed, remaining, qa, attempts, notes, snapshots.toList())

    companion object {
        /** Maps a preview URL (https://<preview-host>/project/...) to a project-relative path. */
        fun previewPathOf(source: String): String? {
            if (source.isBlank()) return null
            val marker = "/project/"
            val idx = source.indexOf(marker)
            if (idx < 0) return null
            return source.substring(idx + marker.length).substringBefore('?').substringBefore('#').ifEmpty { null }
        }
    }
}

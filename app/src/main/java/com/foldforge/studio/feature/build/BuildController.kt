package com.foldforge.studio.feature.build

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.foldforge.studio.core.apk.ApkBuildOptions
import com.foldforge.studio.core.apk.ApkBuildResult
import com.foldforge.studio.core.apk.ApkBuilder
import com.foldforge.studio.core.export.AndroidExportOptions
import com.foldforge.studio.core.export.AndroidProjectExporter
import com.foldforge.studio.core.export.CiWorkflow
import com.foldforge.studio.core.export.WebExporter
import com.foldforge.studio.core.github.GitHubClient
import com.foldforge.studio.core.github.WorkflowRun
import com.foldforge.studio.core.model.BuildRecord
import com.foldforge.studio.core.model.ProjectType
import com.foldforge.studio.core.security.ApkKeyProvider
import com.foldforge.studio.core.security.SecureStore
import com.foldforge.studio.core.security.UrlValidator
import com.foldforge.studio.core.storage.ZipLimits
import com.foldforge.studio.core.storage.ZipTools
import com.foldforge.studio.data.database.BuildHistoryEntity
import com.foldforge.studio.feature.workspace.WorkspaceEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class BuildPhase(val label: String) { IDLE("Idle"), PREPARING("Preparing"), BUILDING("Building"), TESTING("Testing"), SUCCESS("Success"), FAILED("Failed") }

enum class ExportKind(val label: String) { PROJECT_ZIP("Project ZIP"), WEB_BUILD("Web Build (ZIP)"), ANDROID_PROJECT("Android Studio Project (ZIP)"), GIT_REPO("Git Repository (ZIP)") }

data class BuildOutput(val file: File, val variant: String, val sha256: String?, val createdAt: Long, val info: ApkBuildResult? = null)

class BuildController(private val env: WorkspaceEnv) {
    var phase by mutableStateOf(BuildPhase.IDLE)
        private set
    val logs = mutableStateListOf<String>()
    var output by mutableStateOf<BuildOutput?>(null)
        private set
    var cloudRuns by mutableStateOf<List<WorkflowRun>>(emptyList())
        private set
    private val context: Context get() = env.container.app
    private val outputs: File get() = env.container.outputsDir

    private fun log(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        env.scope.launch(Dispatchers.Main) { logs += "[$ts] $line" }
    }

    private suspend fun record(kind: String, status: String, file: File?, sha: String?, message: String) = withContext(Dispatchers.IO) {
        val p = env.project
        p.addBuildRecord(BuildRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), kind, status, file?.absolutePath, file?.length(), sha, message))
        env.container.database.builds().insert(BuildHistoryEntity(projectId = p.id, kind = kind, status = status, outputPath = file?.absolutePath, sizeBytes = file?.length(), sha256 = sha, createdAt = System.currentTimeMillis(), message = message))
    }

    private val safeName: String get() = env.project.meta.name.replace(Regex("[^A-Za-z0-9]+"), "").ifEmpty { "Project" }

    /** Web root used for APK/Android exports: project root, or assets/www for Android wrapper projects. */
    private fun webRoot(): File {
        val p = env.project
        return if (p.meta.type == ProjectType.ANDROID) File(p.root, p.meta.entry).parentFile ?: p.root else p.root
    }

    fun export(kind: ExportKind, onDone: (File) -> Unit = {}) {
        if (phase == BuildPhase.BUILDING) return
        env.scope.launch {
            phase = BuildPhase.PREPARING
            logs.clear()
            try {
                env.saveAll()
                val file = withContext(Dispatchers.IO) {
                    val p = env.project
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                    when (kind) {
                        ExportKind.PROJECT_ZIP -> File(outputs, WebExporter.zipFileName(p.meta.name)).also { f ->
                            f.outputStream().use { WebExporter.exportZip(p.root, it) }
                        }
                        ExportKind.GIT_REPO -> File(outputs, "${safeName}_git_$stamp.zip").also { f ->
                            f.outputStream().use { WebExporter.exportZip(p.root, it, includeGit = true) }
                        }
                        ExportKind.WEB_BUILD -> {
                            val tmp = File(context.cacheDir, "web-${System.nanoTime()}")
                            try {
                                val n = WebExporter.exportWebBuild(webRoot(), tmp)
                                log("Copied $n web file(s)")
                                File(outputs, "${safeName}_web_$stamp.zip").also { f -> f.outputStream().use { ZipTools.zipDirectory(tmp, it) } }
                            } finally { tmp.deleteRecursively() }
                        }
                        ExportKind.ANDROID_PROJECT -> {
                            val tmp = File(context.cacheDir, "android-${System.nanoTime()}")
                            try {
                                val opts = AndroidExportOptions(p.meta.name, AndroidProjectExporter.packageNameFor(p.meta.name), p.meta.version)
                                val written = AndroidProjectExporter().exportFromWeb(webRoot(), tmp, opts)
                                log("Generated ${written.size} files (package ${opts.packageName})")
                                val problems = AndroidProjectExporter().validate(tmp)
                                if (problems.isNotEmpty()) throw IllegalStateException("Validation failed: ${problems.joinToString()}")
                                log("Structure validated: Gradle wrapper, manifest, MainActivity, resources, CI workflow")
                                File(outputs, "${safeName}_android_$stamp.zip").also { f -> f.outputStream().use { ZipTools.zipDirectory(tmp, it) } }
                            } finally { tmp.deleteRecursively() }
                        }
                    }
                }
                log("Exported ${file.name} (${file.length() / 1024} KB)")
                record(kind.name.lowercase(), "success", file, null, kind.label)
                output = BuildOutput(file, kind.label, null, System.currentTimeMillis())
                phase = BuildPhase.SUCCESS
                onDone(file)
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                record(kind.name.lowercase(), "failed", null, null, e.message ?: "")
                phase = BuildPhase.FAILED
            }
        }
    }

    private suspend fun templateApk(): File = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, "player-template.apk")
        context.assets.open("foldforge/player-template.apk").use { input -> f.outputStream().use { input.copyTo(it) } }
        f
    }

    /** Builds a real, signed APK on the device from the web project (no Gradle needed). */
    fun buildApk(appName: String, packageName: String, versionName: String, versionCode: Int) {
        if (phase == BuildPhase.BUILDING) return
        env.scope.launch {
            logs.clear()
            phase = BuildPhase.PREPARING
            try {
                env.saveAll()
                AndroidProjectExporter.validatePackageName(packageName)?.let { throw IllegalArgumentException(it) }
                val result = withContext(Dispatchers.IO) {
                    val web = File(context.cacheDir, "apkweb-${System.nanoTime()}")
                    try {
                        val n = WebExporter.exportWebBuild(webRoot(), web)
                        log("Prepared $n web file(s) from ${webRoot().name}")
                        withContext(Dispatchers.Main) { phase = BuildPhase.BUILDING }
                        val key = ApkKeyProvider.getOrCreate()
                        log("Signing key: Android Keystore (local development key, not a Play upload key)")
                        val out = File(outputs, "${safeName}-$versionName.apk")
                        ApkBuilder(templateApk()).build(web, out, ApkBuildOptions(appName, packageName, versionName, versionCode), key) { log(it) }
                    } finally { web.deleteRecursively() }
                }
                phase = BuildPhase.TESTING
                log("Validating output: exists=${result.apk.isFile}, size=${result.sizeBytes} B, schemes=${result.signatureSchemes}")
                if (!result.apk.isFile || result.sizeBytes <= 0) throw IllegalStateException("APK missing after build")
                record("apk", "success", result.apk, result.sha256, "$packageName $versionName")
                output = BuildOutput(result.apk, "release (locally signed)", result.sha256, System.currentTimeMillis(), result)
                phase = BuildPhase.SUCCESS
            } catch (e: Exception) {
                log("BUILD FAILED: ${e.message}")
                record("apk", "failed", null, null, e.message ?: "")
                phase = BuildPhase.FAILED
            }
        }
    }

    fun simulateBuildError() {
        env.scope.launch {
            logs.clear()
            phase = BuildPhase.BUILDING
            log("QA tool: simulated build error")
            log("e: src/main.js: Unexpected token (simulated)")
            record("apk", "failed", null, null, "Simulated build error")
            phase = BuildPhase.FAILED
        }
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun installIntent(file: File): Intent? {
        if (!context.packageManager.canRequestPackageInstalls()) {
            env.notify("Allow FOLD FORGE to install apps, then tap Install again")
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        }
        return Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareIntent(file: File): Intent {
        val mime = if (file.extension == "apk") "application/vnd.android.package-archive" else "application/zip"
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uriFor(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share ${file.name}")
    }

    // ---------------------------------------------------------------- CI (GitHub Actions)
    val hasWorkflow: Boolean get() = runCatching { env.project.fs.exists(".github/workflows/android.yml") }.getOrDefault(false)

    fun addCiWorkflow() {
        env.scope.launch(Dispatchers.IO) {
            env.project.fs.writeText(".github/workflows/android.yml", CiWorkflow.android(env.project.meta.name))
            env.onFilesChanged(listOf(".github/workflows/android.yml"), false)
            env.notify("Added .github/workflows/android.yml — commit and push to build in the cloud")
        }
    }

    private fun ownerRepo(): Pair<String, String>? {
        val url = runCatching { com.foldforge.studio.core.git.GitService(env.project.root).remoteUrl() }.getOrNull() ?: return null
        return UrlValidator.githubOwnerRepo(url)
    }

    fun refreshCloudRuns() {
        env.scope.launch {
            val token = env.container.secureStore.get(SecureStore.GITHUB_TOKEN) ?: run { env.notify("Connect GitHub first"); return@launch }
            val (o, r) = withContext(Dispatchers.IO) { ownerRepo() } ?: run { env.notify("Set a github.com remote first"); return@launch }
            cloudRuns = runCatching { GitHubClient().listRuns(token, o, r) }.getOrElse { env.notify("GitHub: ${it.message}"); emptyList() }
        }
    }

    /** Dispatches the Android CI workflow and follows it until it completes; downloads the APK artifact. */
    fun cloudBuild(branch: String) {
        if (phase == BuildPhase.BUILDING) return
        env.scope.launch {
            logs.clear()
            val token = env.container.secureStore.get(SecureStore.GITHUB_TOKEN) ?: run { env.notify("Connect GitHub first"); return@launch }
            val (o, r) = withContext(Dispatchers.IO) { ownerRepo() } ?: run { env.notify("Set a github.com remote first"); return@launch }
            val gh = GitHubClient()
            phase = BuildPhase.PREPARING
            try {
                val before = gh.listRuns(token, o, r, 1).firstOrNull()?.id
                gh.dispatchWorkflow(token, o, r, "android.yml", branch)
                log("Dispatched android.yml on $o/$r@$branch")
                phase = BuildPhase.BUILDING
                var run: WorkflowRun? = null
                val deadline = System.currentTimeMillis() + 45 * 60_000L
                while (System.currentTimeMillis() < deadline) {
                    delay(10_000)
                    run = gh.listRuns(token, o, r, 5).firstOrNull { it.id != before && it.name.contains("Android", true) } ?: continue
                    log("Run #${run.id}: ${run.status}${run.conclusion?.let { " / $it" } ?: ""}")
                    if (run.status == "completed") break
                }
                if (run == null || run.status != "completed") throw IllegalStateException("Timed out waiting for the workflow")
                gh.listJobs(token, o, r, run.id).forEach { job ->
                    log("Job ${job.name}: ${job.conclusion}")
                    runCatching { gh.jobLogs(token, o, r, job.id) }.getOrNull()?.lines()?.takeLast(40)?.forEach { log("  $it") }
                }
                if (run.conclusion != "success") throw IllegalStateException("Workflow concluded: ${run.conclusion} (${run.htmlUrl})")
                phase = BuildPhase.TESTING
                val artifact = gh.listArtifacts(token, o, r, run.id).firstOrNull { !it.expired } ?: throw IllegalStateException("No artifact uploaded")
                val zip = gh.downloadArtifact(token, o, r, artifact.id)
                val dir = File(outputs, "ci-${run.id}")
                withContext(Dispatchers.IO) { ZipTools.extract(ByteArrayInputStream(zip), dir, ZipLimits(maxTotalBytes = 400L * 1024 * 1024), stripSingleRoot = false) }
                val apk = dir.walkTopDown().firstOrNull { it.extension == "apk" } ?: throw IllegalStateException("Artifact contains no APK")
                val sha = com.foldforge.studio.core.apk.ApkBuilder.sha256(apk)
                record("ci", "success", apk, sha, run.htmlUrl)
                output = BuildOutput(apk, "debug (GitHub Actions)", sha, System.currentTimeMillis())
                log("Downloaded ${apk.name} (${apk.length() / 1024} KB)")
                phase = BuildPhase.SUCCESS
            } catch (e: Exception) {
                log("CLOUD BUILD FAILED: ${e.message}")
                record("ci", "failed", null, null, e.message ?: "")
                phase = BuildPhase.FAILED
            }
        }
    }
}

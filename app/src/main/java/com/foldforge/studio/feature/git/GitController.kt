package com.foldforge.studio.feature.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.foldforge.studio.core.git.ConflictParser
import com.foldforge.studio.core.git.GitBranches
import com.foldforge.studio.core.git.GitCommitInfo
import com.foldforge.studio.core.git.GitService
import com.foldforge.studio.core.git.GitStatus
import com.foldforge.studio.core.git.SecretsDetectedException
import com.foldforge.studio.core.github.DeviceCode
import com.foldforge.studio.core.github.DevicePoll
import com.foldforge.studio.core.github.GitHubClient
import com.foldforge.studio.core.security.SecretFinding
import com.foldforge.studio.core.security.SecureStore
import com.foldforge.studio.core.security.UrlValidator
import com.foldforge.studio.core.storage.ProjectStore
import com.foldforge.studio.data.database.GitCacheEntity
import com.foldforge.studio.feature.workspace.WorkspaceEnv
import com.foldforge.studio.feature.workspace.WorkspaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class GitController(private val env: WorkspaceEnv) {
    private val gh = GitHubClient()
    var status by mutableStateOf<GitStatus?>(null)
        private set
    var isRepo by mutableStateOf(false)
        private set
    var log by mutableStateOf<List<GitCommitInfo>>(emptyList())
        private set
    var branches by mutableStateOf(GitBranches(null, emptyList(), emptyList()))
        private set
    var remoteUrl by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var lastResult by mutableStateOf<String?>(null)
    var diffText by mutableStateOf<String?>(null)
    var secretFindings by mutableStateOf<List<SecretFinding>>(emptyList())
    var commitMessage by mutableStateOf("")
    var deviceCode by mutableStateOf<DeviceCode?>(null)
    var conflictExplanation by mutableStateOf<String?>(null)

    private val service get() = GitService(env.project.root)
    private fun token() = env.container.secureStore.get(SecureStore.GITHUB_TOKEN)
    val hasToken get() = env.container.secureStore.has(SecureStore.GITHUB_TOKEN)

    private fun op(label: String, block: suspend () -> String?) {
        if (busy != null) return
        busy = label
        env.scope.launch {
            try {
                val msg = withContext(Dispatchers.IO) { block() }
                lastResult = msg
                msg?.let { env.notify(it) }
            } catch (e: SecretsDetectedException) {
                secretFindings = e.findings
            } catch (e: Exception) {
                lastResult = "$label failed: ${e.message}"
                env.notify(lastResult!!)
            } finally {
                busy = null
                refresh()
            }
        }
    }

    fun refresh() {
        env.scope.launch {
            val p = runCatching { env.project }.getOrNull() ?: return@launch
            withContext(Dispatchers.IO) {
                val svc = GitService(p.root)
                val repo = svc.isRepository()
                val st = if (repo) runCatching { svc.status() }.getOrNull() else null
                val lg = if (repo) runCatching { svc.log(100) }.getOrDefault(emptyList()) else emptyList()
                val br = if (repo) runCatching { svc.branches() }.getOrNull() else null
                val remote = if (repo) runCatching { svc.remoteUrl() }.getOrNull() else null
                withContext(Dispatchers.Main) {
                    isRepo = repo; status = st; log = lg; br?.let { branches = it }; remoteUrl = remote
                }
                if (st != null) env.container.database.gitCache().upsert(
                    GitCacheEntity(p.id, st.branch, st.state.name, st.ahead, st.behind, st.changes.size, remote, System.currentTimeMillis()),
                )
            }
        }
    }

    fun init() = op("Init") {
        val gi = File(env.project.root, ".gitignore")
        if (!gi.exists()) gi.writeText(ProjectStore.DEFAULT_GITIGNORE)
        service.init()
        "Initialised Git repository (branch main)"
    }

    fun stage(paths: List<String>) = op("Stage") { service.stage(paths); null }
    fun unstage(paths: List<String>) = op("Unstage") { service.unstage(paths); null }
    fun stageAll() = op("Stage all") { service.stageAll(); null }

    fun showDiff(path: String, staged: Boolean) {
        env.scope.launch { diffText = withContext(Dispatchers.IO) { runCatching { service.diff(path, staged) }.getOrElse { it.message } }?.ifBlank { "(no textual changes)" } }
    }

    fun commit(allowSecrets: Boolean = false) {
        val msg = commitMessage.trim()
        if (msg.isEmpty()) { env.notify("Enter a commit message"); return }
        val s = env.settings.value
        op("Commit") {
            val c = service.commit(msg, s.gitAuthorName, s.gitAuthorEmail, allowSecrets)
            env.project.addHistory("user", "Git commit ${c.shortId}: ${c.message.lineSequence().first()}")
            withContext(Dispatchers.Main) { commitMessage = "" }
            "Committed ${c.shortId}"
        }
    }

    /** Unstages the flagged files and adds them to .gitignore. */
    fun protectSecrets() {
        val paths = secretFindings.map { it.path }.distinct()
        secretFindings = emptyList()
        op("Protect secrets") {
            val gi = File(env.project.root, ".gitignore")
            val existing = if (gi.exists()) gi.readText() else ""
            val add = paths.filter { p -> existing.lines().none { it.trim() == p } }
            if (add.isNotEmpty()) gi.writeText(existing.trimEnd() + "\n# Added by FOLD FORGE secret protection\n" + add.joinToString("\n") + "\n")
            service.unstage(paths)
            "Unstaged ${paths.size} file(s) and added them to .gitignore"
        }
    }

    fun generateCommitMessage() {
        val vm = env as? WorkspaceViewModel ?: return
        if (!vm.ai.isConfigured) { env.notify("Connect an AI provider to generate commit messages"); return }
        op("AI commit message") {
            val diff = service.diff(staged = true).ifBlank { service.diff() }
            if (diff.isBlank()) return@op "Nothing to describe"
            val text = vm.ai.provider().analyze(
                "Write a concise Git commit message for this diff: an imperative subject line (max 60 chars), optionally a blank line and up to 3 bullet points. Reply with the message only.",
                com.foldforge.studio.core.security.SecretScanner.redact(diff.take(30_000)),
            ).trim().removeSurrounding("```").trim()
            withContext(Dispatchers.Main) { commitMessage = text }
            null
        }
    }

    fun createBranch(name: String) = op("Create branch") { service.createBranch(name); "Switched to new branch $name" }
    fun checkout(name: String) = op("Checkout") { service.checkout(name); env.onFilesChanged(emptyList(), false); "Checked out $name" }

    fun setRemote(url: String) = op("Set remote") {
        val normalized = UrlValidator.normalizeGitHub(url)
        UrlValidator.validateGitUrl(normalized)?.let { throw IllegalArgumentException(it) }
        service.setRemote(normalized)
        "Remote origin = $normalized"
    }

    fun push() = op("Push") {
        if (remoteUrl == null) throw IllegalStateException("No remote configured")
        service.push(token()).joinToString("; ").let { "Pushed: $it" }
    }

    fun fetch() = op("Fetch") { service.fetch(token()) }

    fun pull() = op("Pull") {
        env.saveAll()
        val p = env.project
        val snap = p.snapshots.create("Before git pull", "git-pull")
        env.recordSnapshot(snap)
        val outcome = service.pull(token())
        env.onFilesChanged(p.fs.walkFiles().map { it.path }.take(500).toList(), byAi = false)
        if (outcome.conflicts.isNotEmpty()) "Pull produced ${outcome.conflicts.size} conflict(s) — resolve them in the Git panel"
        else "Pull: ${outcome.status}"
    }

    fun conflictText(path: String): String = runCatching { env.project.fs.readText(path) }.getOrDefault("")

    fun resolveConflict(path: String, side: String) = op("Resolve") {
        val fs = env.project.fs
        val resolved = ConflictParser.resolveAll(fs.readText(path), side)
        fs.writeText(path, resolved)
        service.stage(listOf(path))
        env.onFilesChanged(listOf(path), false)
        "Resolved $path using $side"
    }

    fun explainConflict(path: String) {
        val vm = env as? WorkspaceViewModel ?: return
        if (!vm.ai.isConfigured) { env.notify("Connect an AI provider first"); return }
        op("Explain conflict") {
            val text = vm.ai.provider().analyze(
                "Explain this Git merge conflict for a developer: what each side changed and which resolution is safest. Do not rewrite the whole file.",
                com.foldforge.studio.core.security.SecretScanner.redact(conflictText(path).take(20_000)),
            )
            withContext(Dispatchers.Main) { conflictExplanation = text }
            null
        }
    }

    fun abortMerge() = op("Abort merge") { service.abortMerge(); env.onFilesChanged(emptyList(), false); "Merge aborted" }

    // ---------------------------------------------------------------- GitHub
    fun connectToken(token: String) = op("Connect GitHub") {
        val user = gh.currentUser(token.trim())
        env.container.secureStore.put(SecureStore.GITHUB_TOKEN, token.trim())
        env.container.settings.update { it.copy(githubLogin = user.login) }
        "Connected to GitHub as ${user.login}"
    }

    fun disconnect() {
        env.container.secureStore.put(SecureStore.GITHUB_TOKEN, null)
        env.scope.launch { env.container.settings.update { it.copy(githubLogin = "") } }
        env.notify("GitHub disconnected")
    }

    fun startDeviceFlow() {
        val clientId = env.settings.value.githubClientId
        if (clientId.isBlank()) { env.notify("Set your GitHub OAuth App client ID in Settings → Git, or use a personal access token"); return }
        env.scope.launch {
            try {
                val code = gh.startDeviceFlow(clientId)
                deviceCode = code
                var interval = code.interval
                val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
                while (deviceCode === code && System.currentTimeMillis() < deadline) {
                    delay(interval * 1000L)
                    when (val r = gh.pollDeviceFlow(clientId, code.deviceCode)) {
                        is DevicePoll.Token -> { deviceCode = null; connectToken(r.accessToken); return@launch }
                        DevicePoll.SlowDown -> interval += 5
                        DevicePoll.Pending -> Unit
                        is DevicePoll.Failed -> { deviceCode = null; env.notify("GitHub login failed: ${r.reason}"); return@launch }
                    }
                }
                deviceCode = null
            } catch (e: Exception) {
                deviceCode = null
                env.notify("GitHub login failed: ${e.message}")
            }
        }
    }

    fun createRepository(name: String, private: Boolean, description: String, initReadme: Boolean) = op("Create repository") {
        val token = token() ?: throw IllegalStateException("Connect GitHub first")
        val repo = gh.createRepository(token, name, private, description, initReadme)
        val svc = service
        if (!svc.isRepository()) svc.init()
        svc.setRemote(repo.cloneUrl)
        if (initReadme) {
            // Remote already has a commit: integrate it before pushing.
            runCatching { svc.pull(token) }
        }
        "Created ${repo.fullName} and set it as origin"
    }

    fun repoPageUrl(): String? = remoteUrl?.let { UrlValidator.githubOwnerRepo(it) }?.let { (o, r) -> gh.repositoryPageUrl(o, r) }
}

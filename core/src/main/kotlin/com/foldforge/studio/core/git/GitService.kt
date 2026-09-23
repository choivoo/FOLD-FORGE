package com.foldforge.studio.core.git

import com.foldforge.studio.core.security.SecretFinding
import com.foldforge.studio.core.security.SecretScanner
import com.foldforge.studio.core.security.UrlValidator
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

enum class GitState { NOT_A_REPO, CLEAN, MODIFIED, AHEAD, BEHIND, DIVERGED, CONFLICT }

data class GitFileChange(val path: String, val change: String, val staged: Boolean)

data class GitStatus(
    val branch: String?,
    val changes: List<GitFileChange>,
    val conflicting: List<String>,
    val ahead: Int,
    val behind: Int,
    val hasRemote: Boolean,
) {
    val staged get() = changes.filter { it.staged }
    val unstaged get() = changes.filter { !it.staged }
    val state: GitState
        get() = when {
            conflicting.isNotEmpty() -> GitState.CONFLICT
            changes.isNotEmpty() -> GitState.MODIFIED
            ahead > 0 && behind > 0 -> GitState.DIVERGED
            ahead > 0 -> GitState.AHEAD
            behind > 0 -> GitState.BEHIND
            else -> GitState.CLEAN
        }
}

data class GitCommitInfo(val id: String, val shortId: String, val message: String, val author: String, val email: String, val time: Long)

data class GitBranches(val current: String?, val local: List<String>, val remote: List<String>)

data class PullOutcome(val success: Boolean, val status: String, val conflicts: List<String>)

class SecretsDetectedException(val findings: List<SecretFinding>) :
    IOException("Commit blocked: ${findings.size} possible secret(s) detected (${findings.first().path}: ${findings.first().rule})")

class GitException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Local Git via JGit (pure Java, works on Android without a git binary). Remote operations use HTTPS
 * with a token credential that is never logged or written to the repository config.
 */
class GitService(val workTree: File) {
    init {
        IsolatedSystemReader.install()
    }

    fun isRepository(): Boolean = File(workTree, ".git").isDirectory

    private inline fun <T> git(block: (Git) -> T): T {
        if (!isRepository()) throw GitException("Not a Git repository. Initialise Git first.")
        return try {
            Git.open(workTree).use(block)
        } catch (e: GitAPIException) {
            throw GitException(e.message ?: "Git error", e)
        }
    }

    fun init(defaultBranch: String = "main"): GitStatus {
        if (!isRepository()) {
            Git.init().setDirectory(workTree).setInitialBranch(defaultBranch).call().close()
        }
        return status()
    }

    fun status(): GitStatus = git { g ->
        val s = g.status().call()
        val changes = ArrayList<GitFileChange>()
        s.added.forEach { changes += GitFileChange(it, "added", true) }
        s.changed.forEach { changes += GitFileChange(it, "modified", true) }
        s.removed.forEach { changes += GitFileChange(it, "deleted", true) }
        s.modified.forEach { changes += GitFileChange(it, "modified", false) }
        s.missing.forEach { changes += GitFileChange(it, "deleted", false) }
        s.untracked.forEach { changes += GitFileChange(it, "untracked", false) }
        val repo = g.repository
        val branch = repo.branch
        val tracking = runCatching { BranchTrackingStatus.of(repo, branch) }.getOrNull()
        GitStatus(
            branch = branch,
            changes = changes.sortedBy { it.path },
            conflicting = s.conflicting.sorted(),
            ahead = tracking?.aheadCount ?: 0,
            behind = tracking?.behindCount ?: 0,
            hasRemote = repo.config.getSubsections("remote").isNotEmpty(),
        )
    }

    fun stage(paths: Collection<String>) = git { g ->
        val status = g.status().call()
        val deleted = status.missing
        paths.filter { it !in deleted }.takeIf { it.isNotEmpty() }?.let { list ->
            val add = g.add()
            list.forEach { add.addFilepattern(it) }
            add.call()
        }
        paths.filter { it in deleted }.takeIf { it.isNotEmpty() }?.let { list ->
            val rm = g.rm()
            list.forEach { rm.addFilepattern(it) }
            rm.call()
        }
    }

    fun stageAll() = git { g ->
        g.add().addFilepattern(".").call()
        g.add().addFilepattern(".").setUpdate(true).call()
    }

    fun unstage(paths: Collection<String>) = git { g ->
        val reset = g.reset()
        paths.forEach { reset.addPath(it) }
        reset.call()
    }

    /** Scans staged content for secret files and secret patterns. */
    fun scanStagedForSecrets(): List<SecretFinding> = git { g ->
        val s = g.status().call()
        val staged = (s.added + s.changed)
        val findings = ArrayList<SecretFinding>()
        for (path in staged) {
            if (SecretScanner.isSensitiveFile(path)) {
                findings += SecretFinding(path, 0, "Sensitive file type", path.substringAfterLast('/'))
                continue
            }
            val f = File(workTree, path)
            if (f.isFile && f.length() < 2_000_000) findings += SecretScanner.scanText(path, runCatching { f.readText() }.getOrDefault(""))
        }
        findings
    }

    fun commit(message: String, authorName: String, authorEmail: String, allowSecrets: Boolean = false): GitCommitInfo {
        require(message.isNotBlank()) { "Commit message must not be empty" }
        if (!allowSecrets) {
            val findings = scanStagedForSecrets()
            if (findings.isNotEmpty()) throw SecretsDetectedException(findings)
        }
        return git { g ->
            val ident = PersonIdent(authorName.ifBlank { "FOLD FORGE" }, authorEmail.ifBlank { "foldforge@localhost" })
            val c = g.commit().setMessage(message).setAuthor(ident).setCommitter(ident).setSign(false).call()
            GitCommitInfo(c.name, c.name.take(7), c.fullMessage.trim(), c.authorIdent.name, c.authorIdent.emailAddress, c.commitTime * 1000L)
        }
    }

    fun log(max: Int = 100): List<GitCommitInfo> = git { g ->
        if (g.repository.resolve(Constants.HEAD) == null) return@git emptyList()
        g.log().setMaxCount(max).call().map {
            GitCommitInfo(it.name, it.name.take(7), it.fullMessage.trim(), it.authorIdent.name, it.authorIdent.emailAddress, it.commitTime * 1000L)
        }
    }

    /** Unified diff. [staged] compares index↔HEAD, otherwise working tree↔index. */
    fun diff(path: String? = null, staged: Boolean = false): String = git { g ->
        val repo = g.repository
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { fmt ->
            fmt.setRepository(repo)
            if (path != null) fmt.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(path))
            if (staged) {
                val head = repo.resolve("HEAD^{tree}")
                val oldTree = if (head != null) CanonicalTreeParser().apply { repo.newObjectReader().use { reset(it, head) } } else org.eclipse.jgit.treewalk.EmptyTreeIterator()
                fmt.format(oldTree, DirCacheIterator(repo.readDirCache()))
            } else {
                fmt.format(DirCacheIterator(repo.readDirCache()), FileTreeIterator(repo))
            }
        }
        out.toString(Charsets.UTF_8.name())
    }

    fun branches(): GitBranches = git { g ->
        val local = g.branchList().call().map { Repository.shortenRefName(it.name) }
        val remote = g.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE).call().map { Repository.shortenRefName(it.name) }
        GitBranches(g.repository.branch, local, remote)
    }

    fun createBranch(name: String, checkout: Boolean = true) = git { g ->
        validateBranchName(name)
        g.branchCreate().setName(name).call()
        if (checkout) g.checkout().setName(name).call()
    }

    fun checkout(name: String) = git { g ->
        validateBranchName(name.removePrefix("origin/"))
        val isRemote = name.startsWith("origin/")
        if (isRemote) {
            val localName = name.removePrefix("origin/")
            g.checkout().setCreateBranch(true).setName(localName).setStartPoint(name)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK).call()
        } else {
            g.checkout().setName(name).call()
        }
    }

    fun setRemote(url: String, name: String = "origin") = git { g ->
        UrlValidator.validateGitUrl(url)?.let { throw GitException(it) }
        val existing = g.remoteList().call().firstOrNull { it.name == name }
        if (existing == null) g.remoteAdd().setName(name).setUri(URIish(url)).call()
        else g.remoteSetUrl().setRemoteName(name).setRemoteUri(URIish(url)).call()
    }

    fun remoteUrl(name: String = "origin"): String? = git { g -> g.repository.config.getString("remote", name, "url") }

    fun push(token: String?, remote: String = "origin"): List<String> = git { g ->
        val branch = g.repository.branch ?: throw GitException("No branch checked out")
        val results = g.push().setRemote(remote)
            .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
            .setCredentialsProvider(credentials(token))
            .call()
        val messages = ArrayList<String>()
        var ok = true
        for (r in results) for (u in r.remoteUpdates) {
            messages += "${u.remoteName}: ${u.status}${u.message?.let { " ($it)" } ?: ""}"
            if (u.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK && u.status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE) ok = false
        }
        // Set upstream so ahead/behind can be computed.
        val cfg = g.repository.config
        cfg.setString("branch", branch, "remote", remote)
        cfg.setString("branch", branch, "merge", "refs/heads/$branch")
        cfg.save()
        if (!ok) throw GitException("Push rejected: ${messages.joinToString("; ")}")
        messages
    }

    fun fetch(token: String?, remote: String = "origin"): String = git { g ->
        val r = g.fetch().setRemote(remote).setCredentialsProvider(credentials(token)).call()
        "Fetched ${r.trackingRefUpdates.size} ref update(s)"
    }

    fun pull(token: String?, remote: String = "origin"): PullOutcome = git { g ->
        val r = g.pull().setRemote(remote).setCredentialsProvider(credentials(token)).call()
        val merge = r.mergeResult
        val conflicts = merge?.conflicts?.keys?.sorted() ?: emptyList()
        PullOutcome(r.isSuccessful, merge?.mergeStatus?.toString() ?: (r.rebaseResult?.status?.toString() ?: "UNKNOWN"), conflicts)
    }

    fun abortMerge() = git { g ->
        g.repository.writeMergeCommitMsg(null)
        g.repository.writeMergeHeads(null)
        g.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call()
    }

    fun headCommitFile(path: String): String? = git { g ->
        val repo = g.repository
        val head = repo.resolve(Constants.HEAD) ?: return@git null
        RevWalk(repo).use { walk ->
            val tree = walk.parseCommit(head).tree
            org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, path, tree)?.use { tw ->
                String(repo.open(tw.getObjectId(0)).bytes, Charsets.UTF_8)
            }
        }
    }

    companion object {
        fun credentials(token: String?): CredentialsProvider? =
            token?.takeIf { it.isNotBlank() }?.let { UsernamePasswordCredentialsProvider("x-access-token", it) }

        fun validateBranchName(name: String) {
            if (!Repository.isValidRefName("refs/heads/$name") || name.startsWith("-")) throw GitException("Invalid branch name '$name'")
        }

        /** Clones [url] into [target]. The token is used only in memory for the transfer. */
        fun clone(url: String, target: File, token: String?, branch: String? = null): GitService {
            IsolatedSystemReader.install()
            val normalized = UrlValidator.normalizeGitHub(url)
            UrlValidator.validateGitUrl(normalized)?.let { throw GitException(it) }
            if (target.exists() && target.list()?.isNotEmpty() == true) throw GitException("Target folder is not empty")
            try {
                Git.cloneRepository().setURI(normalized).setDirectory(target)
                    .setCredentialsProvider(credentials(token))
                    .apply { if (branch != null) setBranch(branch) }
                    .call().close()
            } catch (e: GitAPIException) {
                target.deleteRecursively()
                throw GitException("Clone failed: ${e.message}", e)
            }
            return GitService(target)
        }

        fun openRepo(dir: File): Repository = FileRepositoryBuilder().setWorkTree(dir).setGitDir(File(dir, ".git")).build()
    }
}

/** Parses Git conflict markers so the UI can show "ours" / "theirs" per hunk. */
object ConflictParser {
    data class Hunk(val startLine: Int, val ours: String, val theirs: String, val oursLabel: String, val theirsLabel: String)

    fun parse(text: String): List<Hunk> {
        val lines = text.split('\n')
        val out = ArrayList<Hunk>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("<<<<<<<")) {
                val start = i
                val oursLabel = lines[i].removePrefix("<<<<<<<").trim()
                val ours = StringBuilder()
                val theirs = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("=======")) { ours.append(lines[i]).append('\n'); i++ }
                i++
                while (i < lines.size && !lines[i].startsWith(">>>>>>>")) { theirs.append(lines[i]).append('\n'); i++ }
                val theirsLabel = lines.getOrNull(i)?.removePrefix(">>>>>>>")?.trim() ?: ""
                out += Hunk(start + 1, ours.toString(), theirs.toString(), oursLabel, theirsLabel)
            }
            i++
        }
        return out
    }

    /** Resolves every hunk with the given side ("ours" | "theirs" | "both"). */
    fun resolveAll(text: String, side: String): String {
        val re = Regex("<<<<<<<[^\\n]*\\n([\\s\\S]*?)=======\\n([\\s\\S]*?)>>>>>>>[^\\n]*(\\n|$)")
        return re.replace(text) { m ->
            when (side) {
                "ours" -> m.groupValues[1]
                "theirs" -> m.groupValues[2]
                else -> m.groupValues[1] + m.groupValues[2]
            }
        }
    }

    fun hasConflicts(text: String) = text.contains("<<<<<<<") && text.contains(">>>>>>>")
}

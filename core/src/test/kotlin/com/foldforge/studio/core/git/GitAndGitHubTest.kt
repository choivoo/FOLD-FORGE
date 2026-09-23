package com.foldforge.studio.core.git

import com.foldforge.studio.core.github.DevicePoll
import com.foldforge.studio.core.github.GitHubClient
import com.foldforge.studio.core.github.GitHubException
import com.foldforge.studio.core.tempDir
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GitAndGitHubTest {
    init {
        IsolatedSystemReader.install()
    }

    @Test fun `init add commit log status diff branches`() {
        val dir = tempDir()
        File(dir, "index.html").writeText("<h1>v1</h1>\n")
        val git = GitService(dir)
        assertFalse(git.isRepository())
        assertThrows(GitException::class.java) { git.status() }
        val st = git.init()
        assertEquals("main", st.branch)
        assertEquals(GitState.MODIFIED, st.state)
        assertTrue(st.changes.any { it.path == "index.html" && it.change == "untracked" })
        git.stageAll()
        assertTrue(git.status().staged.any { it.path == "index.html" })
        assertTrue(git.diff(staged = true).contains("+<h1>v1</h1>"))
        val c1 = git.commit("Initial commit", "Tester", "t@example.com")
        assertEquals(7, c1.shortId.length)
        assertEquals(GitState.CLEAN, git.status().state)
        File(dir, "index.html").writeText("<h1>v2</h1>\n")
        assertTrue(git.diff().contains("-<h1>v1</h1>"))
        git.stage(listOf("index.html"))
        git.unstage(listOf("index.html"))
        assertTrue(git.status().unstaged.any { it.path == "index.html" })
        git.stage(listOf("index.html"))
        git.commit("Update title", "Tester", "t@example.com")
        val log = git.log()
        assertEquals(listOf("Update title", "Initial commit"), log.map { it.message })
        git.createBranch("feature/boss")
        assertEquals("feature/boss", git.branches().current)
        git.checkout("main")
        assertEquals(listOf("feature/boss", "main"), git.branches().local.sorted())
        assertThrows(GitException::class.java) { git.createBranch("bad..name") }
        assertEquals("<h1>v2</h1>\n", git.headCommitFile("index.html"))
        File(dir, "gone.txt").writeText("x"); git.stageAll(); git.commit("add", "T", "t@x")
        File(dir, "gone.txt").delete()
        git.stage(listOf("gone.txt"))
        assertTrue(git.status().staged.any { it.path == "gone.txt" && it.change == "deleted" })
    }

    @Test fun `commit is blocked when secrets are staged`() {
        val dir = tempDir()
        val git = GitService(dir)
        git.init()
        File(dir, ".env").writeText("TOKEN=x")
        File(dir, "config.js").writeText("const k = 'ghp_abcdefghijklmnopqrstuvwxyz0123456789';")
        git.stageAll()
        val e = assertThrows(SecretsDetectedException::class.java) { git.commit("oops", "T", "t@x") }
        assertEquals(2, e.findings.size)
        assertFalse(e.message!!.contains("ghp_abcdefghijklmnop"))
        assertTrue(git.log().isEmpty())
    }

    @Test fun `remote url validation and clone from local bare repository via jgit`() {
        val dir = tempDir()
        val git = GitService(dir)
        git.init()
        assertThrows(GitException::class.java) { git.setRemote("file:///tmp/x") }
        git.setRemote("https://github.com/example/repo.git")
        assertEquals("https://github.com/example/repo.git", git.remoteUrl())
        assertTrue(git.status().hasRemote)
        assertThrows(GitException::class.java) { GitService.clone("ftp://x/y", File(tempDir(), "c"), null) }
    }

    @Test fun `pull conflict is detected and parsed`() {
        // origin (bare) <- A pushes, B diverges and pulls → conflict
        val bare = tempDir()
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close()
        val a = tempDir()
        Git.init().setDirectory(a).setInitialBranch("main").call().use { g ->
            g.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toString())).call()
            File(a, "f.txt").writeText("base\n"); g.add().addFilepattern(".").call(); g.commit().setMessage("base").setSign(false).call()
            g.push().setRemote("origin").add("main").call()
        }
        val b = tempDir()
        Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(b).call().close()
        Git.open(a).use { g -> File(a, "f.txt").writeText("from A\n"); g.commit().setAll(true).setMessage("A").setSign(false).call(); g.push().setRemote("origin").add("main").call() }
        Git.open(b).use { g -> File(b, "f.txt").writeText("from B\n"); g.commit().setAll(true).setMessage("B").setSign(false).call() }
        val gb = GitService(b)
        val outcome = gb.pull(null)
        assertFalse(outcome.success)
        assertEquals(listOf("f.txt"), outcome.conflicts)
        assertEquals(GitState.CONFLICT, gb.status().state)
        val text = File(b, "f.txt").readText()
        val hunks = ConflictParser.parse(text)
        assertEquals(1, hunks.size)
        assertEquals("from B\n", hunks[0].ours)
        assertEquals("from A\n", hunks[0].theirs)
        assertEquals("from A\n", ConflictParser.resolveAll(text, "theirs"))
    }

    @Test fun `github client architecture against mock server`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"login":"octo","name":"Octo Cat"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"full_name":"octo/game","html_url":"https://github.com/octo/game","clone_url":"https://github.com/octo/game.git","private":true,"default_branch":"main"}"""))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"name already exists"}"""))
        server.enqueue(MockResponse().setBody("""{"workflow_runs":[{"id":42,"name":"Android CI","status":"completed","conclusion":"success","html_url":"u","head_branch":"main","created_at":"t"}]}"""))
        server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
        server.start()
        val base = server.url("").toString().trimEnd('/')
        val gh = GitHubClient(apiBase = base, webBase = base)
        assertEquals("octo", gh.currentUser("tok").login)
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
        val repo = gh.createRepository("tok", "game", true, "desc", false)
        assertEquals("octo/game", repo.fullName)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"private\":true"))
        val e = runCatching { gh.createRepository("tok", "game", true, "", false) }.exceptionOrNull()
        assertTrue(e is GitHubException && e.status == 422)
        server.takeRequest()
        assertEquals("success", gh.listRuns("tok", "octo", "game").single().conclusion)
        server.takeRequest()
        assertEquals(DevicePoll.Pending, gh.pollDeviceFlow("client", "dev"))
        server.shutdown()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { gh.createRepository("t", "bad name!", false, "", false) } }
        Unit
    }
}

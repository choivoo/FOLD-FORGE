package com.foldforge.studio.core.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubException(message: String, val status: Int = 0) : IOException(message)

data class GitHubUser(val login: String, val name: String?)
data class GitHubRepo(val fullName: String, val htmlUrl: String, val cloneUrl: String, val private: Boolean, val defaultBranch: String)
data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val interval: Int, val expiresIn: Int)
data class WorkflowRun(val id: Long, val name: String, val status: String, val conclusion: String?, val htmlUrl: String, val headBranch: String?, val createdAt: String)
data class WorkflowJob(val id: Long, val name: String, val status: String, val conclusion: String?)
data class Artifact(val id: Long, val name: String, val sizeBytes: Long, val expired: Boolean)

sealed class DevicePoll {
    data class Token(val accessToken: String) : DevicePoll()
    data object Pending : DevicePoll()
    data object SlowDown : DevicePoll()
    data class Failed(val reason: String) : DevicePoll()
}

/**
 * Minimal GitHub REST client. Tokens are passed per call and never logged. All calls are real
 * network requests; nothing is simulated.
 */
class GitHubClient(
    private val apiBase: String = "https://api.github.com",
    private val webBase: String = "https://github.com",
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.str(k: String) = this[k].s()

    private suspend fun call(token: String?, method: String, path: String, body: JsonObject? = null, accept: String = "application/vnd.github+json"): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val rb = Request.Builder().url(if (path.startsWith("http")) path else apiBase + path)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "FoldForge/1.0")
            if (!token.isNullOrBlank()) rb.header("Authorization", "Bearer $token")
            val reqBody = body?.toString()?.toRequestBody("application/json".toMediaType())
            when (method) {
                "GET" -> rb.get()
                "POST" -> rb.post(reqBody ?: "".toRequestBody(null))
                "DELETE" -> rb.delete()
                else -> throw IllegalArgumentException(method)
            }
            http.newCall(rb.build()).execute().use { it.code to (it.body?.string().orEmpty()) }
        }

    private fun fail(code: Int, text: String): Nothing {
        val msg = runCatching { (json.parseToJsonElement(text) as JsonObject).str("message") }.getOrNull()
        val hint = when (code) {
            401 -> " — token invalid or expired"
            403 -> " — token lacks permission (scopes: repo, workflow)"
            404 -> " — not found or no access"
            422 -> " — validation failed (name already taken?)"
            else -> ""
        }
        throw GitHubException("GitHub API error $code${msg?.let { ": $it" } ?: ""}$hint", code)
    }

    private suspend fun getObject(token: String?, path: String): JsonObject {
        val (code, text) = call(token, "GET", path)
        if (code !in 200..299) fail(code, text)
        return json.parseToJsonElement(text) as JsonObject
    }

    suspend fun currentUser(token: String): GitHubUser {
        val o = getObject(token, "/user")
        return GitHubUser(o.str("login") ?: throw GitHubException("No login in response"), o.str("name"))
    }

    suspend fun createRepository(token: String, name: String, private: Boolean, description: String, autoInit: Boolean): GitHubRepo {
        require(Regex("^[A-Za-z0-9._-]{1,100}$").matches(name)) { "Repository name may only contain letters, digits, '.', '_' and '-'" }
        val (code, text) = call(
            token, "POST", "/user/repos",
            buildJsonObject {
                put("name", name); put("private", private); put("description", description.take(350)); put("auto_init", autoInit)
            },
        )
        if (code !in 200..299) fail(code, text)
        return repo(json.parseToJsonElement(text) as JsonObject)
    }

    suspend fun getRepository(token: String?, owner: String, repo: String): GitHubRepo = repo(getObject(token, "/repos/$owner/$repo"))

    private fun repo(o: JsonObject) = GitHubRepo(
        o.str("full_name") ?: "", o.str("html_url") ?: "", o.str("clone_url") ?: "",
        (o["private"] as? JsonPrimitive)?.booleanOrNull ?: false, o.str("default_branch") ?: "main",
    )

    fun repositoryPageUrl(owner: String, repo: String) = "$webBase/$owner/$repo"

    // ---------------------------------------------------------------- OAuth device flow

    /** Starts the OAuth device flow for a user-registered GitHub OAuth App [clientId]. */
    suspend fun startDeviceFlow(clientId: String, scope: String = "repo workflow"): DeviceCode = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$webBase/login/device/code").header("Accept", "application/json")
            .post(FormBody.Builder().add("client_id", clientId).add("scope", scope).build()).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) fail(resp.code, text)
            val o = json.parseToJsonElement(text) as JsonObject
            o.str("error")?.let { throw GitHubException("Device flow error: $it") }
            DeviceCode(
                o.str("device_code")!!, o.str("user_code")!!, o.str("verification_uri") ?: "$webBase/login/device",
                (o["interval"] as? JsonPrimitive)?.intOrNull ?: 5, (o["expires_in"] as? JsonPrimitive)?.intOrNull ?: 900,
            )
        }
    }

    suspend fun pollDeviceFlow(clientId: String, deviceCode: String): DevicePoll = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$webBase/login/oauth/access_token").header("Accept", "application/json")
            .post(
                FormBody.Builder().add("client_id", clientId).add("device_code", deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build(),
            ).build()
        http.newCall(req).execute().use { resp ->
            val o = json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonObject ?: return@withContext DevicePoll.Failed("Bad response")
            o.str("access_token")?.let { return@withContext DevicePoll.Token(it) }
            when (val e = o.str("error")) {
                "authorization_pending" -> DevicePoll.Pending
                "slow_down" -> DevicePoll.SlowDown
                null -> DevicePoll.Failed("Unknown response")
                else -> DevicePoll.Failed(o.str("error_description") ?: e)
            }
        }
    }

    // ---------------------------------------------------------------- GitHub Actions (cloud APK build)

    suspend fun dispatchWorkflow(token: String, owner: String, repo: String, workflowFile: String, ref: String) {
        val (code, text) = call(token, "POST", "/repos/$owner/$repo/actions/workflows/$workflowFile/dispatches", buildJsonObject { put("ref", ref) })
        if (code != 204 && code !in 200..299) fail(code, text)
    }

    suspend fun listRuns(token: String, owner: String, repo: String, perPage: Int = 10): List<WorkflowRun> {
        val o = getObject(token, "/repos/$owner/$repo/actions/runs?per_page=$perPage")
        return (o["workflow_runs"] as? JsonArray).orEmpty().mapNotNull { el ->
            val r = el as? JsonObject ?: return@mapNotNull null
            WorkflowRun(
                (r["id"] as JsonPrimitive).longOrNull ?: 0, r.str("name") ?: "", r.str("status") ?: "", r.str("conclusion"),
                r.str("html_url") ?: "", r.str("head_branch"), r.str("created_at") ?: "",
            )
        }
    }

    suspend fun listJobs(token: String, owner: String, repo: String, runId: Long): List<WorkflowJob> {
        val o = getObject(token, "/repos/$owner/$repo/actions/runs/$runId/jobs")
        return (o["jobs"] as? JsonArray).orEmpty().mapNotNull { el ->
            val j = el as? JsonObject ?: return@mapNotNull null
            WorkflowJob((j["id"] as JsonPrimitive).longOrNull ?: 0, j.str("name") ?: "", j.str("status") ?: "", j.str("conclusion"))
        }
    }

    /** Raw log text of a job (GitHub redirects to a signed URL; OkHttp follows it). */
    suspend fun jobLogs(token: String, owner: String, repo: String, jobId: Long): String {
        val (code, text) = call(token, "GET", "/repos/$owner/$repo/actions/jobs/$jobId/logs", accept = "application/vnd.github+json")
        if (code !in 200..299) fail(code, text)
        return text
    }

    suspend fun listArtifacts(token: String, owner: String, repo: String, runId: Long): List<Artifact> {
        val o = getObject(token, "/repos/$owner/$repo/actions/runs/$runId/artifacts")
        return (o["artifacts"] as? JsonArray).orEmpty().mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            Artifact((a["id"] as JsonPrimitive).longOrNull ?: 0, a.str("name") ?: "", (a["size_in_bytes"] as? JsonPrimitive)?.longOrNull ?: 0, (a["expired"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
    }

    /** Downloads an artifact ZIP (bytes). */
    suspend fun downloadArtifact(token: String, owner: String, repo: String, artifactId: Long): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$apiBase/repos/$owner/$repo/actions/artifacts/$artifactId/zip")
            .header("Authorization", "Bearer $token").header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FoldForge/1.0").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) fail(resp.code, resp.body?.string().orEmpty())
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (bytes.size > 300L * 1024 * 1024) throw GitHubException("Artifact too large")
            bytes
        }
    }
}

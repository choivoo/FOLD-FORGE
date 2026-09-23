package com.foldforge.studio.core.security

import java.net.URI

data class SecretFinding(val path: String, val line: Int, val rule: String, val preview: String)

/**
 * Detects secrets before they are committed to Git or sent to an AI provider.
 * Findings never contain the full secret — only a masked preview.
 */
object SecretScanner {
    private data class Rule(val name: String, val regex: Regex)

    private val RULES = listOf(
        Rule("Private key", Regex("-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----")),
        Rule("GitHub token", Regex("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\\b")),
        Rule("GitHub fine-grained token", Regex("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b")),
        Rule("Anthropic API key", Regex("\\bsk-ant-[A-Za-z0-9_\\-]{20,}")),
        Rule("OpenAI API key", Regex("\\bsk-(?:proj-)?[A-Za-z0-9_\\-]{32,}")),
        Rule("AWS access key", Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
        Rule("Google API key", Regex("\\bAIza[0-9A-Za-z_\\-]{35}\\b")),
        Rule("Slack token", Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}")),
        Rule("Stripe secret key", Regex("\\bsk_(?:live|test)_[A-Za-z0-9]{20,}")),
        Rule("Generic secret assignment", Regex("(?i)\\b(?:api[_-]?key|secret|token|password|passwd)\\b\\s*[:=]\\s*[\"'][^\"'\\s]{12,}[\"']")),
        Rule("Keystore password", Regex("(?i)(?:storePassword|keyPassword)\\s*[=:]\\s*\\S+")),
    )

    private val SENSITIVE_FILES = listOf(
        Regex("(^|/)\\.env(\\..*)?$"), Regex("\\.(keystore|jks|p12|pfx|pem|key)$"),
        Regex("(^|/)local\\.properties$"), Regex("(^|/)credentials(\\.json)?$"),
        Regex("(^|/)id_(rsa|ed25519|ecdsa)$"), Regex("(^|/)google-services\\.json$"),
        Regex("(^|/)\\.git-credentials$"), Regex("(^|/)\\.npmrc$"),
    )

    fun isSensitiveFile(path: String): Boolean {
        val p = path.replace('\\', '/').lowercase()
        if (p.endsWith(".env.example") || p.endsWith(".env.sample")) return false
        return SENSITIVE_FILES.any { it.containsMatchIn(p) }
    }

    fun scanText(path: String, text: String, maxFindings: Int = 20): List<SecretFinding> {
        val out = ArrayList<SecretFinding>()
        if (text.length > 2_000_000) return out
        val lines = text.split('\n')
        for ((i, line) in lines.withIndex()) {
            if (line.length > 5000) continue
            for (rule in RULES) {
                val m = rule.regex.find(line) ?: continue
                out += SecretFinding(path, i + 1, rule.name, mask(m.value))
                if (out.size >= maxFindings) return out
                break
            }
        }
        return out
    }

    fun mask(value: String): String = when {
        value.length <= 8 -> "****"
        else -> value.take(4) + "…" + "*".repeat(6) + value.takeLast(2)
    }

    /** Replaces detected secrets in [text] with a placeholder (used before sending context to AI). */
    fun redact(text: String): String {
        var out = text
        for (rule in RULES) out = rule.regex.replace(out) { "[REDACTED:${rule.name}]" }
        return out
    }
}

object UrlValidator {
    private val OWNER_REPO = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$")

    /**
     * Validates a git remote URL. Only https:// (and optionally ssh-style git@host:owner/repo) are allowed;
     * embedded credentials, file://, local paths and option-injection ("-" prefixed) are rejected.
     */
    fun validateGitUrl(url: String): String? {
        val u = url.trim()
        if (u.isEmpty()) return "URL is empty"
        if (u.startsWith("-")) return "Invalid URL"
        if (u.any { it.isWhitespace() || it.code < 0x20 }) return "URL contains whitespace or control characters"
        if (u.startsWith("git@")) {
            return if (Regex("^git@[A-Za-z0-9.-]+:[A-Za-z0-9._/-]+(\\.git)?$").matches(u)) null else "Invalid SSH URL"
        }
        val uri = try { URI(u) } catch (e: Exception) { return "Malformed URL" }
        if (uri.scheme?.lowercase() != "https") return "Only https:// repository URLs are supported"
        if (uri.userInfo != null) return "Do not embed credentials in the URL; connect GitHub in Settings instead"
        if (uri.host.isNullOrBlank()) return "URL has no host"
        val path = uri.path.orEmpty().trim('/')
        if (path.isEmpty() || path.contains("..")) return "URL has no repository path"
        return null
    }

    fun normalizeGitHub(input: String): String {
        val t = input.trim()
        return if (OWNER_REPO.matches(t)) "https://github.com/$t.git" else t
    }

    /** Extracts owner/repo from a github.com URL, or null. */
    fun githubOwnerRepo(url: String): Pair<String, String>? {
        val m = Regex("github\\.com[/:]([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$").find(url.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    fun isSafeExternalUrl(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme?.lowercase() in setOf("https", "http", "mailto") && (uri.scheme == "mailto" || !uri.host.isNullOrBlank())
    } catch (e: Exception) {
        false
    }
}

/** Allow-listed Gradle invocation for desktop/CI build hosts: prevents shell injection. */
object GradleCommand {
    private val ALLOWED_TASKS = setOf("assembleDebug", "assembleRelease", "bundleRelease", "bundleDebug", "test", "lint", "clean", "testDebugUnitTest")

    fun build(tasks: List<String>, extraArgs: List<String> = emptyList()): List<String> {
        require(tasks.isNotEmpty()) { "No Gradle task" }
        tasks.forEach { require(it in ALLOWED_TASKS) { "Gradle task '$it' is not allowed" } }
        extraArgs.forEach { require(it in setOf("--stacktrace", "--info", "--offline", "--no-daemon", "--console=plain")) { "Argument '$it' is not allowed" } }
        return listOf("./gradlew") + tasks + extraArgs
    }
}

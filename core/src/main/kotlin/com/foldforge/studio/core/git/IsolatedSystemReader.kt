package com.foldforge.studio.core.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File

/**
 * Makes JGit ignore machine-wide and user git configuration (/etc/gitconfig, ~/.gitconfig, XDG).
 * FOLD FORGE manages identity/credentials itself; foreign settings such as `gpg.format=ssh` or
 * credential helpers must not change (or break) how commits are made.
 */
class IsolatedSystemReader(private val delegate: SystemReader) : SystemReader() {
    private val nowhere = File("/nonexistent/foldforge-empty-gitconfig")

    override fun getHostname(): String = delegate.hostname
    override fun getenv(variable: String?): String? = if (variable?.startsWith("GIT_") == true) null else delegate.getenv(variable)
    override fun getProperty(key: String?): String? = delegate.getProperty(key)
    override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig = FileBasedConfig(parent, nowhere, fs)
    override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig = FileBasedConfig(parent, nowhere, fs)
    override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig = FileBasedConfig(parent, nowhere, fs)
    override fun getCurrentTime(): Long = delegate.currentTime
    override fun getTimezone(`when`: Long): Int = delegate.getTimezone(`when`)

    companion object {
        @Volatile private var installed = false

        fun install() {
            if (installed) return
            synchronized(this) {
                if (installed) return
                val current = SystemReader.getInstance()
                if (current !is IsolatedSystemReader) SystemReader.setInstance(IsolatedSystemReader(current))
                installed = true
            }
        }
    }
}

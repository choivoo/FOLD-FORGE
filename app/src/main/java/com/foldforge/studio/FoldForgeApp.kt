package com.foldforge.studio

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.work.Configuration
import com.foldforge.studio.core.git.IsolatedSystemReader
import com.foldforge.studio.core.security.SecureStore
import com.foldforge.studio.core.storage.ProjectStore
import com.foldforge.studio.core.templates.TemplateCatalog
import com.foldforge.studio.data.database.FoldForgeDatabase
import com.foldforge.studio.data.repository.ProjectRepository
import com.foldforge.studio.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.security.KeyStore

/** Manual dependency container (no reflection-based DI needed for this app size). */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: FoldForgeDatabase by lazy { FoldForgeDatabase.create(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val keystoreAvailable: Boolean by lazy { runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; true }.getOrDefault(false) }
    val secureStore: SecureStore by lazy { SecureStore(app, keystoreAvailable) }
    val templates: TemplateCatalog by lazy { TemplateCatalog() }
    val workspaceDir: File get() = File(app.filesDir, "projects")
    val outputsDir: File get() = File(app.filesDir, "outputs").also { it.mkdirs() }
    val projectStore: ProjectStore by lazy { ProjectStore(workspaceDir, templates) }
    val projects: ProjectRepository by lazy { ProjectRepository(projectStore, database, appScope) }
}

class FoldForgeApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // JGit resolves ~ via user.home; point it at private storage and ignore foreign git config.
        System.setProperty("user.home", filesDir.absolutePath)
        IsolatedSystemReader.install()
        container = AppContainer(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) MemoryPressure.notify(level)
    }
}

/** Lightweight broadcast for low-memory events (search indexes and caches drop their data). */
object MemoryPressure {
    private val listeners = mutableSetOf<(Int) -> Unit>()
    fun register(l: (Int) -> Unit) = synchronized(listeners) { listeners += l }
    fun unregister(l: (Int) -> Unit) = synchronized(listeners) { listeners -= l }
    fun notify(level: Int) = synchronized(listeners) { listeners.toList() }.forEach { it(level) }
}

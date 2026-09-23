package com.foldforge.studio.feature.home

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.foldforge.studio.FoldForgeApp
import com.foldforge.studio.core.ai.AIProviderFactory
import com.foldforge.studio.core.ai.AiException
import com.foldforge.studio.core.security.SecureStore
import com.foldforge.studio.data.database.GitCacheEntity
import com.foldforge.studio.data.database.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AiBuildStep(val label: String) { PLANNING("Planning"), GENERATING("Generating files"), CREATING("Creating project"), DONE("Complete"), FAILED("Failed") }

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as FoldForgeApp).container
    val projects = container.projects.summaries
    val loading = container.projects.loading
    val templates get() = container.templates.templates
    val gitCache: StateFlow<Map<String, GitCacheEntity>> =
        container.database.gitCache().observeAll().map { l -> l.associateBy { it.projectId } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var restorable by mutableStateOf<SessionEntity?>(null)
        private set
    var aiStep by mutableStateOf<AiBuildStep?>(null)
        private set
    var aiPlan by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        container.projects.refresh()
        viewModelScope.launch(Dispatchers.IO) {
            val s = container.database.sessions().get()
            if (s?.projectId != null && container.projects.exists(s.projectId)) {
                withContext(Dispatchers.Main) { restorable = s }
            }
        }
    }

    fun refresh() = container.projects.refresh()
    fun dismissRestore() { restorable = null }

    private fun launchBusy(label: String, onSuccess: (String) -> Unit, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        error = null
        viewModelScope.launch {
            try {
                onSuccess(block())
            } catch (e: Exception) {
                error = "$label failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun create(name: String, templateId: String, onCreated: (String) -> Unit) =
        launchBusy("Create project", onCreated) { container.projects.create(name, templateId).id }

    fun importZip(uri: Uri, onCreated: (String) -> Unit) = launchBusy("Import", onCreated) {
        val resolver = getApplication<Application>().contentResolver
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.take(40) ?: "Imported"
        val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read file")
        input.use { container.projects.importZip(it, name).id }
    }

    fun clone(url: String, onCreated: (String) -> Unit) = launchBusy("Clone", onCreated) {
        container.projects.clone(url, container.secureStore.get(SecureStore.GITHUB_TOKEN)).id
    }

    fun delete(id: String) = viewModelScope.launch { container.projects.delete(id) }

    val aiConfigured: StateFlow<Boolean> = container.settings.settings
        .map { s -> s.aiConfig(container.secureStore.get(SecureStore.AI_API_KEY)).isConfigured }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** AI Build: the provider generates a complete original project, which is then materialised on disk. */
    fun aiBuild(prompt: String, onCreated: (String) -> Unit) {
        if (busy != null) return
        busy = "AI Build"
        error = null
        aiPlan = emptyList()
        viewModelScope.launch {
            try {
                aiStep = AiBuildStep.PLANNING
                val settings = container.settings.current()
                val provider = withContext(Dispatchers.IO) { AIProviderFactory.create(settings.aiConfig(container.secureStore.get(SecureStore.AI_API_KEY))) }
                aiStep = AiBuildStep.GENERATING
                val gen = provider.generateFiles(prompt)
                aiPlan = gen.plan
                aiStep = AiBuildStep.CREATING
                val three = withContext(Dispatchers.IO) {
                    javaClass.classLoader?.getResourceAsStream("foldforge/templates/_shared/vendor/three.module.min.js")?.use { it.readBytes() }
                }
                val project = container.projects.createFromAi(gen, three)
                aiStep = AiBuildStep.DONE
                onCreated(project.id)
            } catch (e: AiException) {
                aiStep = AiBuildStep.FAILED
                error = e.message
            } catch (e: Exception) {
                aiStep = AiBuildStep.FAILED
                error = "AI Build failed: ${e.message}"
            } finally {
                busy = null
            }
        }
    }
}

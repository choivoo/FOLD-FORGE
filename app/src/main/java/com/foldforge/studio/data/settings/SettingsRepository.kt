package com.foldforge.studio.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.foldforge.studio.core.ai.AiConfig
import com.foldforge.studio.core.ai.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { FORGE_DARK("Forge Dark"), OLED("OLED Black"), LIGHT("Light"), SYSTEM("System") }
enum class BatteryMode(val label: String) { PERFORMANCE("Performance"), BALANCED("Balanced"), SAVER("Battery Saver") }
enum class ExternalNavPolicy(val label: String) { ASK("Ask"), OPEN_BROWSER("Open in browser"), BLOCK("Block") }

/** Non-secret settings. Secrets (API key, GitHub token) live in [com.foldforge.studio.core.security.SecureStore]. */
data class AppSettings(
    val onboardingDone: Boolean = false,
    val theme: ThemeMode = ThemeMode.FORGE_DARK,
    val layoutPreset: String = "A",
    val editorFontSize: Int = 13,
    val autosave: Boolean = true,
    val autoReload: Boolean = true,
    val previewFpsLimit: Int = 0, // 0 = auto
    val previewNetwork: Boolean = true,
    val externalNav: ExternalNavPolicy = ExternalNavPolicy.ASK,
    val fpsOverlay: Boolean = false,
    val aiProvider: ProviderKind = ProviderKind.ANTHROPIC,
    val aiEndpoint: String = "",
    val aiModel: String = AiConfig.DEFAULT_ANTHROPIC_MODEL,
    val aiTemperature: Float = 0.2f,
    val aiMaxContext: Int = 60_000,
    val aiMaxOutput: Int = 16_000,
    val aiScope: String = "RELATED_FILES",
    val qaMaxFixIterations: Int = 3,
    val qaAutoApproveFixes: Boolean = false,
    val qaVision: Boolean = false,
    val qaPauseMs: Int = 250,
    val qaSoakMinutes: Int = 3,
    val batteryMode: BatteryMode = BatteryMode.BALANCED,
    val gitAuthorName: String = "",
    val gitAuthorEmail: String = "",
    val githubClientId: String = "",
    val githubLogin: String = "",
    val imageEndpoint: String = "",
    val imageModel: String = "",
    val controllerLayout: String = "",
) {
    fun aiConfig(apiKey: String?): AiConfig = AiConfig(
        provider = aiProvider,
        endpoint = aiEndpoint,
        model = aiModel,
        apiKey = apiKey.orEmpty(),
        temperature = aiTemperature.toDouble(),
        maxContextChars = aiMaxContext,
        maxOutputTokens = aiMaxOutput,
    )

    /** Effective preview FPS limit including battery mode. */
    val effectiveFpsLimit: Int get() = when (batteryMode) {
        BatteryMode.SAVER -> if (previewFpsLimit == 0) 30 else minOf(previewFpsLimit, 30)
        else -> previewFpsLimit
    }
    val effectiveQaPauseMs: Long get() = when (batteryMode) {
        BatteryMode.PERFORMANCE -> qaPauseMs.toLong()
        BatteryMode.BALANCED -> maxOf(qaPauseMs, 250).toLong()
        BatteryMode.SAVER -> maxOf(qaPauseMs, 1000).toLong()
    }
}

/** One instance per Application (see AppContainer). */
class SettingsRepository(context: Context) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile("foldforge_settings") },
    )

    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme")
        val layout = stringPreferencesKey("layout_preset")
        val font = intPreferencesKey("editor_font")
        val autosave = booleanPreferencesKey("autosave")
        val autoReload = booleanPreferencesKey("auto_reload")
        val fps = intPreferencesKey("preview_fps")
        val network = booleanPreferencesKey("preview_network")
        val extNav = stringPreferencesKey("external_nav")
        val fpsOverlay = booleanPreferencesKey("fps_overlay")
        val aiProvider = stringPreferencesKey("ai_provider")
        val aiEndpoint = stringPreferencesKey("ai_endpoint")
        val aiModel = stringPreferencesKey("ai_model")
        val aiTemp = floatPreferencesKey("ai_temperature")
        val aiCtx = intPreferencesKey("ai_max_context")
        val aiOut = intPreferencesKey("ai_max_output")
        val aiScope = stringPreferencesKey("ai_scope")
        val qaIter = intPreferencesKey("qa_iterations")
        val qaAuto = booleanPreferencesKey("qa_auto_approve")
        val qaVision = booleanPreferencesKey("qa_vision")
        val qaPause = intPreferencesKey("qa_pause")
        val qaSoak = intPreferencesKey("qa_soak")
        val battery = stringPreferencesKey("battery_mode")
        val gitName = stringPreferencesKey("git_name")
        val gitEmail = stringPreferencesKey("git_email")
        val ghClient = stringPreferencesKey("github_client_id")
        val ghLogin = stringPreferencesKey("github_login")
        val imgEndpoint = stringPreferencesKey("image_endpoint")
        val imgModel = stringPreferencesKey("image_model")
        val controller = stringPreferencesKey("controller_layout")
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        value?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: default

    val settings: Flow<AppSettings> = dataStore.data.map { readFrom(it) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { p ->
            val s = transform(readFrom(p))
            p[K.onboarding] = s.onboardingDone
            p[K.theme] = s.theme.name
            p[K.layout] = s.layoutPreset
            p[K.font] = s.editorFontSize.coerceIn(9, 24)
            p[K.autosave] = s.autosave
            p[K.autoReload] = s.autoReload
            p[K.fps] = s.previewFpsLimit
            p[K.network] = s.previewNetwork
            p[K.extNav] = s.externalNav.name
            p[K.fpsOverlay] = s.fpsOverlay
            p[K.aiProvider] = s.aiProvider.name
            p[K.aiEndpoint] = s.aiEndpoint.trim()
            p[K.aiModel] = s.aiModel.trim()
            p[K.aiTemp] = s.aiTemperature.coerceIn(0f, 2f)
            p[K.aiCtx] = s.aiMaxContext.coerceIn(4_000, 400_000)
            p[K.aiOut] = s.aiMaxOutput.coerceIn(1_000, 64_000)
            p[K.aiScope] = s.aiScope
            p[K.qaIter] = s.qaMaxFixIterations.coerceIn(0, 10)
            p[K.qaAuto] = s.qaAutoApproveFixes
            p[K.qaVision] = s.qaVision
            p[K.qaPause] = s.qaPauseMs.coerceIn(0, 10_000)
            p[K.qaSoak] = s.qaSoakMinutes.coerceIn(1, 30)
            p[K.battery] = s.batteryMode.name
            p[K.gitName] = s.gitAuthorName
            p[K.gitEmail] = s.gitAuthorEmail
            p[K.ghClient] = s.githubClientId.trim()
            p[K.ghLogin] = s.githubLogin
            p[K.imgEndpoint] = s.imageEndpoint.trim()
            p[K.imgModel] = s.imageModel.trim()
            p[K.controller] = s.controllerLayout
        }
    }

    private fun readFrom(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingDone = p[K.onboarding] ?: d.onboardingDone, theme = enumOr(p[K.theme], d.theme),
            layoutPreset = p[K.layout] ?: d.layoutPreset, editorFontSize = p[K.font] ?: d.editorFontSize,
            autosave = p[K.autosave] ?: d.autosave, autoReload = p[K.autoReload] ?: d.autoReload,
            previewFpsLimit = p[K.fps] ?: d.previewFpsLimit, previewNetwork = p[K.network] ?: d.previewNetwork,
            externalNav = enumOr(p[K.extNav], d.externalNav), fpsOverlay = p[K.fpsOverlay] ?: d.fpsOverlay,
            aiProvider = enumOr(p[K.aiProvider], d.aiProvider), aiEndpoint = p[K.aiEndpoint] ?: d.aiEndpoint,
            aiModel = p[K.aiModel] ?: d.aiModel, aiTemperature = p[K.aiTemp] ?: d.aiTemperature,
            aiMaxContext = p[K.aiCtx] ?: d.aiMaxContext, aiMaxOutput = p[K.aiOut] ?: d.aiMaxOutput,
            aiScope = p[K.aiScope] ?: d.aiScope, qaMaxFixIterations = p[K.qaIter] ?: d.qaMaxFixIterations,
            qaAutoApproveFixes = p[K.qaAuto] ?: d.qaAutoApproveFixes, qaVision = p[K.qaVision] ?: d.qaVision,
            qaPauseMs = p[K.qaPause] ?: d.qaPauseMs, qaSoakMinutes = p[K.qaSoak] ?: d.qaSoakMinutes,
            batteryMode = enumOr(p[K.battery], d.batteryMode), gitAuthorName = p[K.gitName] ?: d.gitAuthorName,
            gitAuthorEmail = p[K.gitEmail] ?: d.gitAuthorEmail, githubClientId = p[K.ghClient] ?: d.githubClientId,
            githubLogin = p[K.ghLogin] ?: d.githubLogin, imageEndpoint = p[K.imgEndpoint] ?: d.imageEndpoint,
            imageModel = p[K.imgModel] ?: d.imageModel, controllerLayout = p[K.controller] ?: d.controllerLayout,
        )
    }
}

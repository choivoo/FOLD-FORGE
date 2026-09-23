package com.foldforge.studio.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldforge.studio.BuildConfig
import com.foldforge.studio.FoldForgeApp
import com.foldforge.studio.core.ai.AiConfig
import com.foldforge.studio.core.ai.ProviderKind
import com.foldforge.studio.core.security.ApkKeyProvider
import com.foldforge.studio.core.security.SecureStore
import com.foldforge.studio.core.ui.components.SmallButton
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.data.settings.AppSettings
import com.foldforge.studio.data.settings.BatteryMode
import com.foldforge.studio.data.settings.ExternalNavPolicy
import com.foldforge.studio.data.settings.ThemeMode
import com.foldforge.studio.feature.workspace.LayoutPresets
import com.foldforge.studio.worker.BackupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as FoldForgeApp).container
    val s by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    fun update(t: (AppSettings) -> AppSettings) { scope.launch { container.settings.update(t) } }
    var apiKey by remember { mutableStateOf("") }
    var ghToken by remember { mutableStateOf("") }
    var imgKey by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { BackupWorker.restore(context, it) } }
                    .fold({ "Restored $it project(s)" }, { "Restore failed: ${it.message}" })
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Forge.colors.background).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Forge.colors.text) }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Forge.colors.text)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 760.dp).testTag("settings")) {
            message?.let { Text(it, color = Forge.colors.accent2, fontSize = 13.sp) }

            Section("Appearance")
            Chips(ThemeMode.entries.map { it.label }, s.theme.label) { l -> update { it.copy(theme = ThemeMode.entries.first { t -> t.label == l }) } }
            Text("Default layout (unfolded)", fontSize = 12.sp, color = Forge.colors.muted)
            Chips(LayoutPresets.ALL.map { "${it.id}: ${it.label}" }, LayoutPresets.byId(s.layoutPreset).let { "${it.id}: ${it.label}" }) { l -> update { it.copy(layoutPreset = l.substringBefore(':')) } }

            Section("Editor")
            Labeled("Font size ${s.editorFontSize}sp") { Slider(s.editorFontSize.toFloat(), { v -> update { it.copy(editorFontSize = v.toInt()) } }, valueRange = 10f..20f, steps = 9) }
            Toggle("Autosave (debounced)", s.autosave) { v -> update { it.copy(autosave = v) } }

            Section("Preview")
            Toggle("Auto reload on save", s.autoReload) { v -> update { it.copy(autoReload = v) } }
            Toggle("Allow network requests from preview", s.previewNetwork) { v -> update { it.copy(previewNetwork = v) } }
            Toggle("FPS overlay", s.fpsOverlay) { v -> update { it.copy(fpsOverlay = v) } }
            Text("FPS limit", fontSize = 12.sp, color = Forge.colors.muted)
            Chips(listOf("Auto", "60", "30"), when (s.previewFpsLimit) { 30 -> "30"; 60 -> "60"; else -> "Auto" }) { l -> update { it.copy(previewFpsLimit = l.toIntOrNull() ?: 0) } }
            Text("External navigation", fontSize = 12.sp, color = Forge.colors.muted)
            Chips(ExternalNavPolicy.entries.map { it.label }, s.externalNav.label) { l -> update { it.copy(externalNav = ExternalNavPolicy.entries.first { p -> p.label == l }) } }

            Section("AI")
            Chips(ProviderKind.entries.map { it.label }, s.aiProvider.label) { l ->
                val kind = ProviderKind.entries.first { it.label == l }
                update {
                    it.copy(
                        aiProvider = kind,
                        aiModel = if (kind == ProviderKind.ANTHROPIC && it.aiProvider != kind) AiConfig.DEFAULT_ANTHROPIC_MODEL else it.aiModel,
                        aiEndpoint = if (kind == ProviderKind.OPENAI_COMPATIBLE && it.aiEndpoint.isBlank()) AiConfig.DEFAULT_OPENAI_ENDPOINT else it.aiEndpoint,
                    )
                }
            }
            Field("Endpoint ${if (s.aiProvider == ProviderKind.ANTHROPIC) "(optional; blank = api.anthropic.com)" else ""}", s.aiEndpoint) { v -> update { it.copy(aiEndpoint = v) } }
            Field("Model", s.aiModel) { v -> update { it.copy(aiModel = v) } }
            SecretField("API key", container.secureStore.has(SecureStore.AI_API_KEY), apiKey, { apiKey = it }, onSave = {
                container.secureStore.put(SecureStore.AI_API_KEY, apiKey.trim()); apiKey = ""; update { it }; message = "AI API key saved (encrypted)"
            }, onClear = { container.secureStore.put(SecureStore.AI_API_KEY, null); update { it }; message = "AI API key removed" }, keystore = container.keystoreAvailable)
            if (s.aiProvider == ProviderKind.OPENAI_COMPATIBLE) {
                Labeled("Temperature ${"%.1f".format(s.aiTemperature)}") { Slider(s.aiTemperature, { v -> update { it.copy(aiTemperature = v) } }, valueRange = 0f..1.5f) }
            } else {
                Text("Temperature is not sent for Claude models that no longer accept sampling parameters.", fontSize = 11.sp, color = Forge.colors.muted)
            }
            Labeled("Max context ${s.aiMaxContext / 1000}k chars") { Slider(s.aiMaxContext.toFloat(), { v -> update { it.copy(aiMaxContext = v.toInt()) } }, valueRange = 10_000f..200_000f) }
            Labeled("Max output ${s.aiMaxOutput / 1000}k tokens") { Slider(s.aiMaxOutput.toFloat(), { v -> update { it.copy(aiMaxOutput = v.toInt()) } }, valueRange = 2_000f..32_000f) }
            Text("Image provider (optional, OpenAI-compatible /images/generations)", fontSize = 12.sp, color = Forge.colors.muted)
            Field("Image endpoint", s.imageEndpoint) { v -> update { it.copy(imageEndpoint = v) } }
            Field("Image model", s.imageModel) { v -> update { it.copy(imageModel = v) } }
            SecretField("Image API key", container.secureStore.has(SecureStore.IMAGE_API_KEY), imgKey, { imgKey = it }, onSave = {
                container.secureStore.put(SecureStore.IMAGE_API_KEY, imgKey.trim()); imgKey = ""; message = "Image API key saved"
            }, onClear = { container.secureStore.put(SecureStore.IMAGE_API_KEY, null); message = "Image API key removed" }, keystore = container.keystoreAvailable)

            Section("QA")
            Labeled("Max AI fix iterations: ${s.qaMaxFixIterations}") { Slider(s.qaMaxFixIterations.toFloat(), { v -> update { it.copy(qaMaxFixIterations = v.toInt()) } }, valueRange = 0f..6f, steps = 5) }
            Toggle("Auto-approve AI fixes (skip review)", s.qaAutoApproveFixes) { v -> update { it.copy(qaAutoApproveFixes = v) } }
            Toggle("Vision screenshot analysis (uses AI provider)", s.qaVision) { v -> update { it.copy(qaVision = v) } }
            Labeled("Pause between scenarios ${s.qaPauseMs}ms") { Slider(s.qaPauseMs.toFloat(), { v -> update { it.copy(qaPauseMs = v.toInt()) } }, valueRange = 0f..3000f) }
            Labeled("Timed test duration ${s.qaSoakMinutes} min") { Slider(s.qaSoakMinutes.toFloat(), { v -> update { it.copy(qaSoakMinutes = v.toInt()) } }, valueRange = 1f..15f, steps = 13) }
            Text("Battery mode", fontSize = 12.sp, color = Forge.colors.muted)
            Chips(BatteryMode.entries.map { it.label }, s.batteryMode.label) { l -> update { it.copy(batteryMode = BatteryMode.entries.first { b -> b.label == l }) } }

            Section("Git")
            Field("Author name", s.gitAuthorName) { v -> update { it.copy(gitAuthorName = v) } }
            Field("Author email", s.gitAuthorEmail) { v -> update { it.copy(gitAuthorEmail = v) } }
            SecretField("GitHub token", container.secureStore.has(SecureStore.GITHUB_TOKEN), ghToken, { ghToken = it }, onSave = {
                container.secureStore.put(SecureStore.GITHUB_TOKEN, ghToken.trim()); ghToken = ""; message = "GitHub token saved (verify it from the Git panel)"
            }, onClear = { container.secureStore.put(SecureStore.GITHUB_TOKEN, null); update { it.copy(githubLogin = "") }; message = "GitHub token removed" }, keystore = container.keystoreAvailable)
            if (s.githubLogin.isNotBlank()) Text("Connected as ${s.githubLogin}", fontSize = 12.sp, color = Forge.colors.success)
            Field("GitHub OAuth App client ID (device login)", s.githubClientId) { v -> update { it.copy(githubClientId = v) } }

            Section("Build")
            Text("On-device APKs are signed with a per-device key in the Android Keystore (development only, not a Play upload key).", fontSize = 12.sp, color = Forge.colors.muted)
            Text("Certificate SHA-256: ${ApkKeyProvider.certificateSha256() ?: "not created yet"}", fontSize = 11.sp, color = Forge.colors.text)
            SmallButton("Reset signing key", { ApkKeyProvider.reset(); message = "Signing key removed; a new one is created on next build" })

            Section("Storage")
            Text("Projects: ${container.workspaceDir.absolutePath}", fontSize = 12.sp, color = Forge.colors.muted)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                SmallButton("Backup all projects", { BackupWorker.enqueue(context); message = "Backup started → ${BackupWorker.backupDir(context).absolutePath}" })
                Spacer(Modifier.width(6.dp))
                SmallButton("Restore backup…", { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) })
                Spacer(Modifier.width(6.dp))
                SmallButton("Clear cache", { context.cacheDir.deleteRecursively(); message = "Cache cleared" })
            }

            Section("Security")
            listOf(
                "API keys and tokens encrypted with Android Keystore (AES-256-GCM); never logged",
                "Preview sandbox: no file/content access, no JavaScript bridge, external navigation intercepted",
                "ZIP import protected against Zip Slip, absolute paths, bombs and oversized archives",
                "Secret scanner blocks commits and redacts AI context (.env, keys, tokens, keystores)",
                "Git remotes: https/ssh only, no embedded credentials",
                "No all-files access, accessibility or device-admin permissions",
            ).forEach { Text("• $it", fontSize = 12.sp, color = Forge.colors.text) }
            SmallButton("Delete all stored secrets", { container.secureStore.clearAll(); message = "All secrets removed" })

            if (BuildConfig.DEBUG) {
                Section("QA Tools (debug build)")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    SmallButton("Generate Test Project", { scope.launch { runCatching { container.projects.create("QA Test ${System.currentTimeMillis() % 10000}", "rpg3d") }; message = "Test project created" } })
                    Spacer(Modifier.width(6.dp))
                    SmallButton("Reset Demo", {
                        scope.launch {
                            runCatching { if (container.projects.exists("forge-runner")) container.projects.delete("forge-runner"); container.projects.create("Forge Runner", "forge-runner") }
                            message = "Demo project reset"
                        }
                    })
                }
                Text("Simulate JS Error / Simulate Build Error are in the workspace command palette.", fontSize = 11.sp, color = Forge.colors.muted)
            }

            Section("About")
            Text("FOLD FORGE", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Forge.colors.accent)
            Text("Version ${BuildConfig.VERSION_NAME}", color = Forge.colors.text)
            Text("AI Development Workstation", color = Forge.colors.text)
            Text("Built for Foldables", color = Forge.colors.muted)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Forge.colors.accent, letterSpacing = 1.5.sp)
    HorizontalDivider(color = Forge.colors.border, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Forge.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable private fun Labeled(label: String, content: @Composable () -> Unit) {
    Text(label, fontSize = 12.sp, color = Forge.colors.muted)
    content()
}

@Composable private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(text, { text = it; onChange(it) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

@Composable private fun SecretField(label: String, stored: Boolean, value: String, onChange: (String) -> Unit, onSave: () -> Unit, onClear: () -> Unit, keystore: Boolean) {
    OutlinedTextField(
        value, onChange, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        label = { Text("$label ${if (stored) "(stored ✓)" else "(not set)"}") }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        enabled = keystore,
    )
    if (!keystore) Text("Android Keystore unavailable on this device: secrets cannot be stored securely.", fontSize = 11.sp, color = Forge.colors.error)
    Row {
        SmallButton("Save", onSave, primary = true, enabled = value.isNotBlank() && keystore)
        Spacer(Modifier.width(6.dp))
        if (stored) SmallButton("Remove", onClear)
    }
}

@Composable private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
        options.forEach { o ->
            FilterChip(o == selected, { onSelect(o) }, label = { Text(o, fontSize = 12.sp) })
            Spacer(Modifier.width(6.dp))
        }
    }
}

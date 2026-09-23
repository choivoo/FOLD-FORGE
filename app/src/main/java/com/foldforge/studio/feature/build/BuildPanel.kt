package com.foldforge.studio.feature.build

import android.text.format.DateFormat
import android.text.format.Formatter
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldforge.studio.core.export.AndroidProjectExporter
import com.foldforge.studio.core.model.ProjectType
import com.foldforge.studio.core.ui.components.KeyValue
import com.foldforge.studio.core.ui.components.PanelHeader
import com.foldforge.studio.core.ui.components.SectionLabel
import com.foldforge.studio.core.ui.components.SmallButton
import com.foldforge.studio.core.ui.components.StatusChip
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.feature.workspace.WorkspaceViewModel
import java.io.File

@Composable
fun BuildPanel(vm: WorkspaceViewModel, modifier: Modifier = Modifier) {
    val build = vm.build
    val context = LocalContext.current
    val p = vm.projectOrNull ?: return
    var appName by remember(p.id) { mutableStateOf(p.meta.name) }
    var pkg by remember(p.id) { mutableStateOf(AndroidProjectExporter.packageNameFor(p.meta.name)) }
    var versionName by remember(p.id) { mutableStateOf(p.meta.version) }
    var versionCode by remember(p.id) { mutableStateOf("1") }
    var saveTarget by remember { mutableStateOf<File?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = saveTarget ?: return@rememberLauncherForActivityResult
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            vm.notify("Saved ${f.name}")
        }.onFailure { vm.notify("Save failed: ${it.message}") }
    }
    val pkgError = AndroidProjectExporter.validatePackageName(pkg)
    val logState = rememberLazyListState()
    LaunchedEffect(build.logs.size) { if (build.logs.isNotEmpty()) logState.scrollToItem(build.logs.size - 1) }

    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        PanelHeader("Build & Export") {
            StatusChip(build.phase.label, when (build.phase) {
                BuildPhase.SUCCESS -> Forge.colors.success; BuildPhase.FAILED -> Forge.colors.error
                BuildPhase.IDLE -> Forge.colors.muted; else -> Forge.colors.warning
            })
        }
        if (build.phase in setOf(BuildPhase.PREPARING, BuildPhase.BUILDING, BuildPhase.TESTING)) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionLabel("APK Builder (on device)")
            Column(Modifier.padding(horizontal = 12.dp)) {
                KeyValue("Project", p.meta.name)
                KeyValue("Variant", "release · locally signed (Android Keystore dev key)")
                KeyValue("Profile", if (p.meta.type == ProjectType.ANDROID) "Android wrapper: packages app/src/main/assets/www" else "Web → WebView player APK")
                OutlinedTextField(appName, { appName = it.take(50) }, label = { Text("App name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pkg, { pkg = it.trim() }, label = { Text("Package name") }, singleLine = true, isError = pkgError != null, modifier = Modifier.fillMaxWidth())
                pkgError?.let { Text(it, color = Forge.colors.error, fontSize = 11.sp) }
                Row {
                    OutlinedTextField(versionName, { versionName = it.trim() }, label = { Text("Version") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(versionCode, { versionCode = it.filter(Char::isDigit).take(9) }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                SmallButton(
                    "Build APK", { build.buildApk(appName, pkg, versionName, versionCode.toIntOrNull() ?: 1) }, primary = true,
                    enabled = pkgError == null && appName.isNotBlank() && build.phase != BuildPhase.BUILDING, modifier = Modifier.testTag("build_apk"),
                )
            }
            build.output?.let { out ->
                SectionLabel("Output")
                Column(Modifier.padding(horizontal = 12.dp)) {
                    KeyValue("Filename", out.file.name)
                    KeyValue("Size", Formatter.formatFileSize(context, out.file.length()))
                    KeyValue("Path", out.file.absolutePath)
                    KeyValue("Variant", out.variant)
                    KeyValue("Build date", DateFormat.format("yyyy-MM-dd HH:mm:ss", out.createdAt).toString())
                    out.sha256?.let { KeyValue("SHA-256", it) }
                    out.info?.let { KeyValue("Package", "${it.packageName} ${it.versionName} · ${it.signatureSchemes.joinToString("+")}") }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                        if (out.file.extension == "apk") {
                            SmallButton("Install", { build.installIntent(out.file)?.let { runCatching { context.startActivity(it) } } }, primary = true)
                            Spacer(Modifier.width(6.dp))
                        }
                        SmallButton("Share", { context.startActivity(build.shareIntent(out.file)) })
                        Spacer(Modifier.width(6.dp))
                        SmallButton("Save to…", { saveTarget = out.file; saveLauncher.launch(out.file.name) })
                    }
                    if (out.file.extension == "apk") Text("Installing shows the Android system confirmation. APKs signed with this device key can be updated only by builds from the same device key.", fontSize = 11.sp, color = Forge.colors.muted)
                }
            }
            SectionLabel("Export")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                ExportKind.entries.forEach { k ->
                    SmallButton(k.label, { build.export(k) })
                    Spacer(Modifier.width(6.dp))
                }
            }
            SectionLabel("Cloud build (GitHub Actions)")
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text(
                    "Gradle cannot run on the phone. For Android projects, push to GitHub and let Actions run test → lint → assembleDebug; FOLD FORGE follows the run and downloads the APK artifact.",
                    fontSize = 12.sp, color = Forge.colors.muted,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    if (!build.hasWorkflow) { SmallButton("Add CI workflow", build::addCiWorkflow); Spacer(Modifier.width(6.dp)) }
                    SmallButton("Run cloud build", { build.cloudBuild(vm.git.status?.branch ?: "main") }, enabled = build.hasWorkflow && vm.git.remoteUrl != null)
                    Spacer(Modifier.width(6.dp))
                    SmallButton("Recent runs", build::refreshCloudRuns)
                }
                build.cloudRuns.forEach { r -> Text("#${r.id} ${r.name}: ${r.status}${r.conclusion?.let { " / $it" } ?: ""} (${r.headBranch})", fontSize = 12.sp, color = Forge.colors.text) }
            }
            SectionLabel("Build logs")
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp).background(Forge.colors.background).padding(6.dp).testTag("build_logs"), state = logState) {
            items(build.logs) { line ->
                Text(line, fontFamily = CodeFont, fontSize = 11.sp, color = if (line.contains("FAILED") || line.contains("ERROR")) Forge.colors.error else Forge.colors.text, fontWeight = if (line.contains("Output:")) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

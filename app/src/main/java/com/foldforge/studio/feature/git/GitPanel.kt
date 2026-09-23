package com.foldforge.studio.feature.git

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldforge.studio.core.git.ConflictParser
import com.foldforge.studio.core.git.GitState
import com.foldforge.studio.core.ui.components.EmptyState
import com.foldforge.studio.core.ui.components.PanelHeader
import com.foldforge.studio.core.ui.components.SmallButton
import com.foldforge.studio.core.ui.components.StatusChip
import com.foldforge.studio.core.ui.components.ToolIcon
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.feature.explorer.NameDialog
import com.foldforge.studio.feature.history.DiffText
import com.foldforge.studio.feature.workspace.WorkspaceViewModel

@Composable
fun GitPanel(vm: WorkspaceViewModel, modifier: Modifier = Modifier) {
    val git = vm.git
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var conflictPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { git.refresh() }

    Column(modifier.fillMaxSize().background(Forge.colors.panel)) {
        val st = git.status
        PanelHeader("Git", subtitle = st?.branch) {
            st?.let {
                StatusChip(
                    when (it.state) { GitState.CLEAN -> "Clean"; GitState.MODIFIED -> "Modified"; GitState.AHEAD -> "Ahead ${it.ahead}"; GitState.BEHIND -> "Behind ${it.behind}"; GitState.DIVERGED -> "Diverged"; GitState.CONFLICT -> "Conflict"; GitState.NOT_A_REPO -> "-" },
                    when (it.state) { GitState.CLEAN -> Forge.colors.success; GitState.CONFLICT -> Forge.colors.error; else -> Forge.colors.warning },
                )
            }
            ToolIcon(Icons.Filled.Refresh, "Refresh Git status", git::refresh)
            git.repoPageUrl()?.let { url -> ToolIcon(Icons.AutoMirrored.Filled.OpenInNew, "Open repository page", { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) }
        }
        git.busy?.let { Text("$it…", fontSize = 12.sp, color = Forge.colors.muted, modifier = Modifier.padding(horizontal = 12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!git.isRepo) {
            EmptyState(Icons.Filled.AccountTree, "Not a Git repository", "Initialise local Git (JGit, works offline). A .gitignore protecting secrets is created.", actionLabel = "Init Git", onAction = git::init)
            return@Column
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp)) {
            SmallButton("Push", git::push, primary = true, enabled = git.remoteUrl != null)
            Spacer(Modifier.width(6.dp)); SmallButton("Pull", git::pull, enabled = git.remoteUrl != null)
            Spacer(Modifier.width(6.dp)); SmallButton("Fetch", git::fetch, enabled = git.remoteUrl != null)
            Spacer(Modifier.width(6.dp)); SmallButton(if (git.remoteUrl == null) "Set remote" else "Remote", { dialog = "remote" })
            Spacer(Modifier.width(6.dp)); SmallButton(if (git.hasToken) "GitHub ✓" else "Connect GitHub", { dialog = "github" })
            Spacer(Modifier.width(6.dp)); SmallButton("Create repo", { dialog = "create" }, enabled = git.hasToken)
        }
        git.remoteUrl?.let { Text("origin: $it", fontSize = 11.sp, color = Forge.colors.muted, modifier = Modifier.padding(horizontal = 12.dp)) }
        if (st != null && st.conflicting.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(Forge.colors.error.copy(alpha = 0.08f)).padding(8.dp)) {
                Text("Merge conflicts", color = Forge.colors.error, fontSize = 13.sp)
                st.conflicting.forEach { p -> Text(p, fontFamily = CodeFont, fontSize = 12.sp, color = Forge.colors.text, modifier = Modifier.clickable { conflictPath = p }.padding(vertical = 4.dp)) }
                TextButton(onClick = git::abortMerge) { Text("Abort merge") }
            }
        }
        TabRow(tab, containerColor = Forge.colors.panelAlt) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Changes (${st?.unstaged?.size ?: 0})", fontSize = 12.sp) })
            Tab(tab == 1, { tab = 1 }, text = { Text("Staged (${st?.staged?.size ?: 0})", fontSize = 12.sp) })
            Tab(tab == 2, { tab = 2 }, text = { Text("History", fontSize = 12.sp) })
            Tab(tab == 3, { tab = 3 }, text = { Text("Branches", fontSize = 12.sp) })
        }
        when (tab) {
            0, 1 -> {
                val staged = tab == 1
                val files = if (staged) st?.staged.orEmpty() else st?.unstaged.orEmpty()
                Row(Modifier.padding(horizontal = 8.dp)) {
                    if (!staged) TextButton(onClick = git::stageAll, enabled = files.isNotEmpty()) { Text("Stage all") }
                    else TextButton(onClick = { git.unstage(files.map { it.path }) }, enabled = files.isNotEmpty()) { Text("Unstage all") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(files, key = { it.path + it.staged }) { f ->
                        Row(Modifier.fillMaxWidth().clickable { git.showDiff(f.path, staged) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(staged, { if (it) git.stage(listOf(f.path)) else git.unstage(listOf(f.path)) })
                            Text(f.change.take(1).uppercase(), fontSize = 12.sp, color = when (f.change) { "added", "untracked" -> Forge.colors.success; "deleted" -> Forge.colors.error; else -> Forge.colors.warning }, modifier = Modifier.width(18.dp))
                            Text(f.path, fontFamily = CodeFont, fontSize = 12.sp, color = Forge.colors.text)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    OutlinedTextField(git.commitMessage, { git.commitMessage = it }, label = { Text("Commit message") }, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("commit_message"))
                    Row {
                        SmallButton("Commit", { git.commit() }, primary = true, enabled = (st?.staged?.isNotEmpty() == true) && git.commitMessage.isNotBlank())
                        Spacer(Modifier.width(6.dp))
                        SmallButton("AI message", git::generateCommitMessage, icon = Icons.Filled.AutoAwesome)
                    }
                }
            }
            2 -> LazyColumn(Modifier.fillMaxSize()) {
                items(git.log, key = { it.id }) { c ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(c.message.lineSequence().first(), fontSize = 13.sp, color = Forge.colors.text)
                        Text("${c.shortId} · ${c.author} · ${DateFormat.format("yyyy-MM-dd HH:mm", c.time)}", fontFamily = CodeFont, fontSize = 11.sp, color = Forge.colors.muted)
                    }
                }
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                SmallButton("New branch", { dialog = "branch" })
                git.branches.local.forEach { b ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = b != git.branches.current) { git.checkout(b) }.padding(8.dp)) {
                        Text(if (b == git.branches.current) "● $b" else "  $b", fontFamily = CodeFont, fontSize = 13.sp, color = if (b == git.branches.current) Forge.colors.accent else Forge.colors.text)
                    }
                }
                if (git.branches.remote.isNotEmpty()) Text("Remote", fontSize = 11.sp, color = Forge.colors.muted, modifier = Modifier.padding(top = 8.dp))
                git.branches.remote.forEach { b ->
                    Text(b, fontFamily = CodeFont, fontSize = 13.sp, color = Forge.colors.muted, modifier = Modifier.fillMaxWidth().clickable { git.checkout(b) }.padding(8.dp))
                }
            }
        }
    }

    git.diffText?.let { d ->
        AlertDialog(onDismissRequest = { git.diffText = null }, title = { Text("Diff") },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { DiffText(d) } },
            confirmButton = { TextButton(onClick = { git.diffText = null }) { Text("Close") } })
    }
    if (git.secretFindings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { git.secretFindings = emptyList() },
            title = { Text("Commit blocked: possible secrets") },
            text = {
                Column {
                    git.secretFindings.take(10).forEach { Text("${it.path}${if (it.line > 0) ":${it.line}" else ""} — ${it.rule} (${it.preview})", fontSize = 12.sp) }
                    Spacer(Modifier.padding(4.dp))
                    Text("Secrets pushed to a remote are hard to revoke. Unstage these files and add them to .gitignore?", fontSize = 12.sp, color = Forge.colors.muted)
                }
            },
            confirmButton = { Button(onClick = git::protectSecrets) { Text("Unstage & ignore") } },
            dismissButton = { TextButton(onClick = { git.secretFindings = emptyList(); git.commit(allowSecrets = true) }) { Text("Commit anyway", color = Forge.colors.error) } },
        )
    }
    conflictPath?.let { path ->
        val text = remember(path) { git.conflictText(path) }
        val hunks = remember(text) { ConflictParser.parse(text) }
        AlertDialog(
            onDismissRequest = { conflictPath = null; git.conflictExplanation = null },
            title = { Text("Conflict: $path") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    hunks.forEachIndexed { i, h ->
                        Text("Hunk ${i + 1} (line ${h.startLine})", fontSize = 12.sp, color = Forge.colors.muted)
                        Text("OURS ${h.oursLabel}", fontSize = 11.sp, color = Forge.colors.accent)
                        Text(h.ours, fontFamily = CodeFont, fontSize = 11.sp, color = Forge.colors.text)
                        Text("THEIRS ${h.theirsLabel}", fontSize = 11.sp, color = Forge.colors.accent2)
                        Text(h.theirs, fontFamily = CodeFont, fontSize = 11.sp, color = Forge.colors.text)
                    }
                    git.conflictExplanation?.let { Text("AI: $it", fontSize = 12.sp, color = Forge.colors.accent2) }
                    TextButton(onClick = { git.explainConflict(path) }) { Text("Ask AI to explain") }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { git.resolveConflict(path, "ours"); conflictPath = null }) { Text("Use ours") }
                    TextButton(onClick = { git.resolveConflict(path, "theirs"); conflictPath = null }) { Text("Use theirs") }
                    TextButton(onClick = { git.resolveConflict(path, "both"); conflictPath = null }) { Text("Keep both") }
                }
            },
            dismissButton = { TextButton(onClick = { conflictPath = null; vm.openFile(path) }) { Text("Edit manually") } },
        )
    }
    when (dialog) {
        "remote" -> NameDialog("Remote URL (https://github.com/owner/repo.git or owner/repo)", git.remoteUrl ?: "", "Save", { dialog = null }, validate = { com.foldforge.studio.core.security.UrlValidator.validateGitUrl(com.foldforge.studio.core.security.UrlValidator.normalizeGitHub(it)).takeIf { _ -> it.isNotEmpty() } }) { git.setRemote(it); dialog = null }
        "branch" -> NameDialog("New branch name", "", "Create", { dialog = null }, validate = { b -> if (b.isEmpty() || org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/$b")) null else "Invalid branch name" }) { git.createBranch(it); dialog = null }
        "github" -> GitHubConnectDialog(git) { dialog = null }
        "create" -> CreateRepoDialog(vm.project.meta.name, onDismiss = { dialog = null }) { name, priv, desc, readme -> git.createRepository(name, priv, desc, readme); dialog = null }
    }
    git.deviceCode?.let { code ->
        AlertDialog(
            onDismissRequest = { git.deviceCode = null },
            title = { Text("Sign in to GitHub") },
            text = { Text("Open ${code.verificationUri} and enter the code:\n\n${code.userCode}\n\nWaiting for authorization…") },
            confirmButton = { TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri))) }) { Text("Open GitHub") } },
            dismissButton = { TextButton(onClick = { git.deviceCode = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GitHubConnectDialog(git: GitController, onDismiss: () -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect GitHub") },
        text = {
            Column {
                Text("Paste a fine-grained or classic personal access token (scopes: repo, workflow). It is verified with GitHub and stored encrypted with the Android Keystore.", fontSize = 12.sp, color = Forge.colors.muted)
                OutlinedTextField(token, { token = it.trim() }, singleLine = true, label = { Text("Personal access token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { onDismiss(); git.startDeviceFlow() }) { Text("Or sign in with device code (OAuth App client ID from Settings)") }
                if (git.hasToken) TextButton(onClick = { git.disconnect(); onDismiss() }) { Text("Disconnect GitHub", color = Forge.colors.error) }
            }
        },
        confirmButton = { Button(onClick = { git.connectToken(token); onDismiss() }, enabled = token.length > 10) { Text("Verify & save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CreateRepoDialog(defaultName: String, onDismiss: () -> Unit, onCreate: (String, Boolean, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf(defaultName.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')) }
    var private by remember { mutableStateOf(true) }
    var desc by remember { mutableStateOf("") }
    var readme by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create GitHub repository") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Repository name") })
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(private, { private = it }); Text("Private") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(readme, { readme = it }); Text("Initialize with README") }
            }
        },
        confirmButton = { Button(onClick = { onCreate(name, private, desc, readme) }, enabled = Regex("^[A-Za-z0-9._-]{1,100}$").matches(name)) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

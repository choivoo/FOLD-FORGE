package com.foldforge.studio.feature.workspace

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.foldforge.studio.AppContainer
import com.foldforge.studio.FoldForgeApp
import com.foldforge.studio.MemoryPressure
import com.foldforge.studio.core.editor.EditorOps
import com.foldforge.studio.core.editor.TextState
import com.foldforge.studio.core.model.FileKind
import com.foldforge.studio.core.model.FileNode
import com.foldforge.studio.core.patch.Diff
import com.foldforge.studio.core.search.Reference
import com.foldforge.studio.core.search.SearchIndex
import com.foldforge.studio.core.search.SearchResult
import com.foldforge.studio.core.storage.Project
import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.storage.SafePaths
import com.foldforge.studio.core.storage.Snapshot
import com.foldforge.studio.core.web.ConsoleEntry
import com.foldforge.studio.core.web.ConsoleLevel
import com.foldforge.studio.core.web.PreviewConfig
import com.foldforge.studio.core.web.PreviewHost
import com.foldforge.studio.data.database.SessionEntity
import com.foldforge.studio.data.database.SnapshotEntity
import com.foldforge.studio.data.settings.AppSettings
import com.foldforge.studio.feature.ai.AiController
import com.foldforge.studio.feature.assets.AssetsController
import com.foldforge.studio.feature.build.BuildController
import com.foldforge.studio.feature.git.GitController
import com.foldforge.studio.feature.qa.QaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/** Services shared by the feature controllers (AI, QA, Git, Build, Assets). */
interface WorkspaceEnv {
    val project: Project
    val container: AppContainer
    val scope: CoroutineScope
    val settings: StateFlow<AppSettings>
    val preview: PreviewHost?
    fun notify(message: String, actionLabel: String? = null, action: (() -> Unit)? = null)
    fun consoleEntries(): List<ConsoleEntry>
    fun clearConsole()
    fun activeFile(): String?
    fun recentFiles(): List<String>
    fun openFile(path: String, line: Int? = null)
    suspend fun saveAll(): Boolean
    /** Files changed on disk by a tool (AI, git pull, restore): refresh editors/tree and reload preview. */
    suspend fun onFilesChanged(paths: List<String>, byAi: Boolean)
    suspend fun ensurePreviewRunning(): Boolean
    fun recordSnapshot(s: Snapshot)
    fun showPane(pane: Pane)
    /** Runs QA: [scenarioIds] null = full suite, otherwise replay those + smoke regression. */
    suspend fun runQa(scenarioIds: Set<String>?): com.foldforge.studio.core.qa.QaReport?
}

sealed class WorkspaceLoad {
    data object Loading : WorkspaceLoad()
    data class Ready(val project: Project) : WorkspaceLoad()
    data class Failed(val message: String) : WorkspaceLoad()
}

data class SnapshotDiff(val path: String, val unified: String, val added: Int, val removed: Int)

class WorkspaceViewModel(app: Application) : AndroidViewModel(app), WorkspaceEnv {
    override val container: AppContainer = (app as FoldForgeApp).container
    override val scope: CoroutineScope get() = viewModelScope
    override val settings: StateFlow<AppSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    var load by mutableStateOf<WorkspaceLoad>(WorkspaceLoad.Loading)
        private set
    private var _project: Project? = null
    override val project: Project get() = _project ?: error("Project not loaded")
    val projectOrNull: Project? get() = _project

    // ------------------------------------------------------------------ layout
    var leftTab by mutableStateOf(Pane.EXPLORER)
    var centerTab by mutableStateOf(Pane.EDITOR)
    var rightTab by mutableStateOf(Pane.AI)
    var compactTab by mutableStateOf(Pane.EDITOR)
    var showLeft by mutableStateOf(true)
    var showRight by mutableStateOf(true)
    var splitPreview by mutableStateOf(false)
    var leftFraction by mutableFloatStateOf(0.25f)
    var rightFraction by mutableFloatStateOf(0.30f)
    var fullscreenPreview by mutableStateOf(false)
    var paletteOpen by mutableStateOf(false)
    var quickOpen by mutableStateOf(false)
    var presetId by mutableStateOf("A")
        private set

    fun applyPreset(id: String) {
        val p = LayoutPresets.byId(id)
        presetId = p.id
        showLeft = p.left != null
        p.left?.let { leftTab = it }
        centerTab = p.center
        showRight = p.right != null
        p.right?.let { rightTab = it }
        fullscreenPreview = p.id == "E"
        viewModelScope.launch { container.settings.update { it.copy(layoutPreset = p.id) } }
        persistSession()
    }

    override fun showPane(pane: Pane) {
        compactTab = pane
        when (pane) {
            Pane.EDITOR -> centerTab = Pane.EDITOR
            Pane.EXPLORER, Pane.SEARCH -> { leftTab = pane; showLeft = true }
            else -> { rightTab = pane; showRight = true }
        }
    }

    // ------------------------------------------------------------------ notices
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
    val notices: SharedFlow<Notice> = _notices
    override fun notify(message: String, actionLabel: String?, action: (() -> Unit)?) {
        _notices.tryEmit(Notice(message, actionLabel, action))
    }

    // ------------------------------------------------------------------ explorer
    val children = mutableStateMapOf<String, List<FileNode>>()
    val expanded = mutableStateMapOf<String, Boolean>()
    val selected = mutableStateMapOf<String, Boolean>()
    var clipboard by mutableStateOf<Pair<List<String>, Boolean>?>(null) // paths, isCut
    val aiEdited = mutableStateMapOf<String, Long>()

    fun refreshTree() {
        val p = _project ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val dirs = listOf("") + expanded.filterValues { it }.keys
            val loaded = dirs.associateWith { d -> runCatching { p.fs.listChildren(d) }.getOrDefault(emptyList()) }
            withContext(Dispatchers.Main) {
                children.keys.retainAll(loaded.keys)
                children.putAll(loaded)
            }
        }
    }

    fun toggleDir(path: String) {
        val open = !(expanded[path] ?: false)
        expanded[path] = open
        if (open) {
            val p = _project ?: return
            viewModelScope.launch(Dispatchers.IO) {
                val list = runCatching { p.fs.listChildren(path) }.getOrDefault(emptyList())
                withContext(Dispatchers.Main) { children[path] = list }
            }
        }
    }

    private fun fsOp(label: String, block: suspend (ProjectFileSystem) -> String?) {
        val p = _project ?: return
        viewModelScope.launch {
            try {
                val openPath = withContext(Dispatchers.IO) { block(p.fs) }
                refreshTree()
                searchIndexDirty = true
                openPath?.let { if (FileKind.of(it.substringAfterLast('/')).isText && !p.fs.isDirectory(it)) openFile(it) }
            } catch (e: Exception) {
                notify("$label failed: ${e.message}")
            }
        }
    }

    fun createFile(parent: String, name: String) = fsOp("Create file") { fs ->
        fs.createFile(parent, name, starterContent(name)).also { expanded[parent] = true }
    }

    fun createFolder(parent: String, name: String) = fsOp("Create folder") { fs -> fs.createFolder(parent, name).let { expanded[it] = true; null } }

    fun rename(path: String, newName: String) = fsOp("Rename") { fs ->
        val newPath = fs.rename(path, newName)
        withContext(Dispatchers.Main) { retargetDocs(path, newPath) }
        null
    }

    /** Safe delete: snapshot first (so it can be restored from History), then delete. */
    fun delete(paths: List<String>) = fsOp("Delete") { fs ->
        val snap = project.snapshots.create("Before deleting ${paths.joinToString(limit = 3)}", "delete", paths)
        recordSnapshot(snap)
        paths.forEach { fs.delete(it) }
        project.addHistory("user", "Deleted ${paths.joinToString(limit = 3)}", paths, snap.id)
        withContext(Dispatchers.Main) {
            paths.forEach { p -> docs.keys.filter { it == p || it.startsWith("$p/") }.forEach { closeTab(it, force = true) } }
            selected.clear()
        }
        null
    }

    fun duplicate(path: String) = fsOp("Duplicate") { fs -> fs.duplicate(path) }

    fun copyToClipboard(paths: List<String>, cut: Boolean) { clipboard = paths to cut; notify("${paths.size} item(s) ${if (cut) "cut" else "copied"}") }

    fun paste(targetDir: String) {
        val (paths, cut) = clipboard ?: return
        fsOp("Paste") { fs ->
            paths.forEach { if (cut) fs.move(it, targetDir) else fs.copy(it, targetDir) }
            if (cut) withContext(Dispatchers.Main) { clipboard = null }
            null
        }
    }

    fun move(paths: List<String>, targetDir: String) = fsOp("Move") { fs ->
        paths.forEach { old ->
            val newPath = fs.move(old, targetDir)
            withContext(Dispatchers.Main) { retargetDocs(old, newPath) }
        }
        null
    }

    /** Imports files picked via the Storage Access Framework into [targetDir]. */
    fun importUris(uris: List<Uri>, targetDir: String) {
        val p = _project ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            for (uri in uris) {
                runCatching {
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "imported-${System.currentTimeMillis()}"
                    val safeName = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").take(120)
                    val dest = p.fs.uniquePath(ProjectFileSystem.join(targetDir, safeName))
                    resolver.openInputStream(uri)?.use { p.fs.writeStream(dest, it, maxBytes = 200L * 1024 * 1024) }
                    count++
                }.onFailure { notify("Import failed: ${it.message}") }
            }
            refreshTree()
            notify("Imported $count file(s)")
        }
    }

    private fun starterContent(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "html" -> "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n  <title>${name.substringBeforeLast('.')}</title>\n</head>\n<body>\n  \n</body>\n</html>\n"
        "js", "mjs" -> "// ${name}\n"
        "css" -> "/* $name */\n"
        "json" -> "{\n}\n"
        "md" -> "# ${name.substringBeforeLast('.')}\n"
        "glsl", "frag" -> "precision mediump float;\n\nvoid main() {\n  gl_FragColor = vec4(1.0);\n}\n"
        else -> ""
    }

    // ------------------------------------------------------------------ editor
    val docs = mutableStateMapOf<String, EditorDoc>()
    val tabs = mutableStateListOf<String>()
    var activePath by mutableStateOf<String?>(null)
        private set
    var pendingLine by mutableStateOf<Int?>(null)
    val activeDoc: EditorDoc? get() = activePath?.let { docs[it] }
    private val recent = ArrayDeque<String>()
    private var autosaveJob: Job? = null
    private var journalJob: Job? = null
    var searchIndexDirty = true

    override fun activeFile(): String? = activePath
    override fun recentFiles(): List<String> = recent.toList()

    override fun openFile(path: String, line: Int?) {
        val p = _project ?: return
        val norm = runCatching { SafePaths.normalize(path) }.getOrNull() ?: return
        if (docs.containsKey(norm)) {
            activate(norm, line)
            return
        }
        viewModelScope.launch {
            val kind = FileKind.of(norm.substringAfterLast('/'))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val f = p.fs.file(norm)
                    when {
                        !f.isFile -> null
                        !kind.isText -> null
                        f.length() > MAX_EDITABLE -> "" to true
                        else -> {
                            val disk = f.readText()
                            val journal = journalFile(norm).takeIf { it.isFile }?.readText()
                            (journal ?: disk) to false
                        }
                    }
                }.getOrNull()
            }
            if (result == null) {
                if (!kind.isText) { showPane(Pane.ASSETS); assets.select(norm) } else notify("Cannot open $norm")
                return@launch
            }
            val (text, tooLarge) = result
            val doc = EditorDoc(norm, if (tooLarge) "// File is larger than 2 MB and opened read-only.\n" else text, readOnly = tooLarge)
            if (!tooLarge) {
                val disk = withContext(Dispatchers.IO) { runCatching { p.fs.readText(norm) }.getOrDefault(text) }
                doc.savedText = disk
                if (doc.dirty) { doc.saveState = SaveState.MODIFIED; notify("Recovered unsaved changes in $norm") }
            }
            docs[norm] = doc
            if (norm !in tabs) tabs += norm
            activate(norm, line)
        }
    }

    private fun activate(path: String, line: Int?) {
        activePath = path
        recent.remove(path); recent.addFirst(path); while (recent.size > 10) recent.removeLast()
        if (line != null) {
            val doc = docs[path]
            if (doc != null) {
                val off = EditorOps.offsetOfLine(doc.value.text, line - 1)
                doc.value = doc.value.copy(selection = TextRange(off))
                pendingLine = line
            }
        }
        centerTab = Pane.EDITOR
        compactTab = Pane.EDITOR
        persistSession()
    }

    fun selectTab(path: String) = activate(path, null)

    fun closeTab(path: String, force: Boolean = false) {
        val doc = docs[path]
        if (doc != null && doc.dirty && !force) {
            viewModelScope.launch { save(doc); closeTab(path, true) }
            return
        }
        val idx = tabs.indexOf(path)
        tabs.remove(path)
        docs.remove(path)
        if (activePath == path) activePath = tabs.getOrNull((idx - 1).coerceAtLeast(0)) ?: tabs.firstOrNull()
        persistSession()
    }

    private fun retargetDocs(old: String, new: String) {
        docs.keys.filter { it == old || it.startsWith("$old/") }.forEach { k ->
            val doc = docs.remove(k) ?: return@forEach
            val nk = new + k.removePrefix(old)
            val nd = EditorDoc(nk, doc.value.text).also { it.savedText = doc.savedText; it.value = doc.value }
            docs[nk] = nd
            val i = tabs.indexOf(k); if (i >= 0) tabs[i] = nk
            if (activePath == k) activePath = nk
        }
    }

    /** Applies IDE behaviour (auto-indent, auto-close pairs, pair backspace) to soft-keyboard edits. */
    fun onEditorChange(doc: EditorDoc, incoming: TextFieldValue) {
        if (doc.readOnly) return
        val old = doc.value
        if (incoming.text == old.text) {
            doc.value = incoming
            return
        }
        val composing = incoming.composition != null || old.composition != null
        var result: TextFieldValue = incoming
        var typing = true
        if (!composing && old.selection.collapsed && incoming.selection.collapsed) {
            val at = old.selection.start
            if (incoming.text.length == old.text.length + 1 && incoming.selection.start == at + 1 &&
                incoming.text.regionMatches(0, old.text, 0, at) && incoming.text.regionMatches(at + 1, old.text, at, old.text.length - at)
            ) {
                val c = incoming.text[at]
                val s = if (c == '\n') EditorOps.newline(TextState(old.text, at)) else EditorOps.typeChar(TextState(old.text, at), c)
                result = TextFieldValue(s.text, TextRange(s.selStart, s.selEnd))
            } else if (incoming.text.length == old.text.length - 1 && incoming.selection.start == at - 1 && at > 0 &&
                incoming.text.regionMatches(0, old.text, 0, at - 1)
            ) {
                val s = EditorOps.backspace(TextState(old.text, at))
                result = TextFieldValue(s.text, TextRange(s.selStart))
            } else typing = false
        } else if (!composing) typing = false
        doc.history.record(doc.textState(), typing)
        doc.value = result
        markEdited(doc)
    }

    private fun markEdited(doc: EditorDoc) {
        doc.saveState = if (doc.dirty) SaveState.MODIFIED else SaveState.SAVED
        scheduleJournal(doc)
        if (settings.value.autosave) {
            autosaveJob?.cancel()
            autosaveJob = viewModelScope.launch { delay(AUTOSAVE_MS); save(doc) }
        }
    }

    fun transform(op: (TextState) -> TextState) {
        val doc = activeDoc ?: return
        if (doc.readOnly) return
        val before = doc.textState()
        val after = op(before)
        if (after == before) return
        doc.history.record(before, typing = false)
        doc.apply(after)
        markEdited(doc)
    }

    /** Coding-bar insert. Two-character pairs like "{}" wrap the selection or place the caret inside. */
    fun insertSnippet(text: String) = transform { s ->
        if (text.length == 2 && text in setOf("{}", "()", "[]", "<>", "\"\"", "''")) {
            val inner = s.text.substring(s.min, s.max)
            val t = s.text.substring(0, s.min) + text[0] + inner + text[1] + s.text.substring(s.max)
            TextState(t, s.min + 1, s.min + 1 + inner.length)
        } else EditorOps.replaceSelection(s, text)
    }

    fun indent() = transform { EditorOps.indent(it) }
    fun outdent() = transform { EditorOps.outdent(it) }
    fun toggleComment() { val d = activeDoc ?: return; transform { EditorOps.toggleComment(it, d.language) } }

    fun format() {
        val doc = activeDoc ?: return
        val formatted = EditorOps.format(doc.value.text, doc.language)
        if (formatted == null) { notify("Formatting not available for ${doc.language.displayName} (or the file has syntax errors)"); return }
        transform { TextState(formatted, it.selStart.coerceAtMost(formatted.length)) }
    }

    fun undo() {
        val doc = activeDoc ?: return
        doc.history.undo(doc.textState())?.let { doc.apply(it); markEdited(doc) }
    }

    fun redo() {
        val doc = activeDoc ?: return
        doc.history.redo(doc.textState())?.let { doc.apply(it); markEdited(doc) }
    }

    fun replaceAllInActive(query: String, replacement: String, caseSensitive: Boolean, regex: Boolean, wholeWord: Boolean): Int {
        var count = 0
        transform { s ->
            val (t, n) = EditorOps.replaceAll(s.text, query, replacement, caseSensitive, regex, wholeWord)
            count = n
            TextState(t, s.selStart.coerceAtMost(t.length))
        }
        return count
    }

    fun select(start: Int, end: Int) {
        val doc = activeDoc ?: return
        doc.value = doc.value.copy(selection = TextRange(start.coerceIn(0, doc.value.text.length), end.coerceIn(0, doc.value.text.length)))
        pendingLine = EditorOps.lineOf(doc.value.text, start) + 1
    }

    private fun journalFile(path: String) = File(project.root, "${ProjectFileSystem.META_DIR}/journal/$path.journal")

    private fun scheduleJournal(doc: EditorDoc) {
        journalJob?.cancel()
        journalJob = viewModelScope.launch(Dispatchers.IO) {
            delay(JOURNAL_MS)
            runCatching {
                val f = journalFile(doc.path)
                if (doc.dirty) { f.parentFile?.mkdirs(); f.writeText(doc.value.text) } else f.delete()
            }
        }
    }

    suspend fun save(doc: EditorDoc): Boolean {
        if (doc.readOnly || !doc.dirty) { doc.saveState = SaveState.SAVED; return true }
        val p = _project ?: return false
        val text = doc.value.text
        doc.saveState = SaveState.SAVING
        return try {
            withContext(Dispatchers.IO) {
                p.fs.writeText(doc.path, text)
                journalFile(doc.path).delete()
            }
            doc.savedText = text
            doc.saveState = if (doc.dirty) SaveState.MODIFIED else SaveState.SAVED
            searchIndex?.update(doc.path, text)
            if (settings.value.autoReload && preview?.isRunning == true) preview?.reload()
            true
        } catch (e: Exception) {
            doc.saveState = SaveState.ERROR
            notify("Save failed: ${e.message}")
            false
        }
    }

    fun saveActive() { activeDoc?.let { viewModelScope.launch { save(it) } } }

    override suspend fun saveAll(): Boolean {
        var ok = true
        docs.values.toList().forEach { if (it.dirty) ok = save(it) && ok }
        return ok
    }

    val overallSaveState: SaveState
        get() = when {
            docs.values.any { it.saveState == SaveState.ERROR } -> SaveState.ERROR
            docs.values.any { it.saveState == SaveState.SAVING } -> SaveState.SAVING
            docs.values.any { it.dirty } -> SaveState.MODIFIED
            else -> SaveState.SAVED
        }

    override suspend fun onFilesChanged(paths: List<String>, byAi: Boolean) {
        val p = _project ?: return
        withContext(Dispatchers.Main) {
            for (path in paths) {
                if (byAi) aiEdited[path] = System.currentTimeMillis()
                val doc = docs[path] ?: continue
                val disk = withContext(Dispatchers.IO) { runCatching { p.fs.readText(path) }.getOrNull() }
                if (disk == null) closeTab(path, force = true)
                else if (disk != doc.value.text) {
                    doc.history.record(doc.textState(), typing = false)
                    doc.value = TextFieldValue(disk, TextRange(doc.value.selection.start.coerceAtMost(disk.length)))
                    doc.savedText = disk
                    doc.saveState = SaveState.SAVED
                }
            }
            refreshTree()
            searchIndexDirty = true
            if (preview?.isRunning == true) preview?.reload()
        }
    }

    // ------------------------------------------------------------------ console + preview
    val console = mutableStateListOf<ConsoleEntry>()
    override var preview: PreviewHost? = null
        private set
    var externalUrlPrompt by mutableStateOf<String?>(null)

    override fun consoleEntries(): List<ConsoleEntry> = console.toList()
    override fun clearConsole() = console.clear()

    val errorFiles: Set<String> get() = console.filter { it.level == ConsoleLevel.ERROR && it.path != null }.mapNotNull { it.path }.toSet()

    fun attachPreview(host: PreviewHost) {
        preview = host
        host.onConsole = { entry ->
            viewModelScope.launch(Dispatchers.Main) {
                if (entry.stack != null) {
                    val idx = console.indexOfLast { it.level == ConsoleLevel.ERROR }
                    if (idx >= 0) {
                        val stackText = runCatching {
                            val o = Json.parseToJsonElement(entry.stack) as JsonObject
                            val file = (o["source"] as? JsonPrimitive)?.content.orEmpty()
                            val line = (o["line"] as? JsonPrimitive)?.intOrNull ?: 0
                            ((o["stack"] as? JsonPrimitive)?.content ?: "").ifBlank { "at ${PreviewHost.previewPath(file) ?: file}:$line" }
                        }.getOrNull()
                        if (stackText != null && console[idx].stack == null) console[idx] = console[idx].copy(stack = stackText)
                    }
                } else {
                    console += entry
                    if (console.size > MAX_CONSOLE) console.removeRange(0, console.size - MAX_CONSOLE)
                }
            }
        }
        host.onExternalNavigation = { url -> viewModelScope.launch(Dispatchers.Main) { handleExternal(url) } }
    }

    fun detachPreview(host: PreviewHost) {
        if (preview === host) {
            host.onConsole = {}
            host.onExternalNavigation = {}
            preview = null
        }
    }

    private fun handleExternal(url: String) {
        when (settings.value.externalNav) {
            com.foldforge.studio.data.settings.ExternalNavPolicy.BLOCK -> notify("Blocked navigation to $url")
            else -> externalUrlPrompt = url
        }
    }

    fun previewConfig() = PreviewConfig(settings.value.effectiveFpsLimit, settings.value.previewNetwork)

    fun run() {
        val p = _project ?: return
        val host = preview ?: run { notify("Preview is not available"); return }
        viewModelScope.launch {
            saveAll()
            clearConsole()
            val entry = previewEntry(p)
            if (!p.fs.exists(entry)) { notify("Entry file '$entry' not found. Set it in project.json."); return@launch }
            host.run(p.fs, entry, previewConfig())
            if (centerTab != Pane.PREVIEW && rightTab != Pane.PREVIEW && !splitPreview) showPane(Pane.PREVIEW)
            compactTab = Pane.PREVIEW
        }
    }

    private fun previewEntry(p: Project): String {
        val entry = p.meta.entry
        return if (entry.endsWith(".html") || entry.endsWith(".htm")) entry
        else listOf("index.html", "app/src/main/assets/www/index.html").firstOrNull { p.fs.exists(it) } ?: entry
    }

    override suspend fun ensurePreviewRunning(): Boolean {
        val host = preview ?: return false
        if (host.status.value != com.foldforge.studio.core.web.PreviewStatus.RUNNING) {
            withContext(Dispatchers.Main) { run() }
            delay(200)
        }
        return host.awaitReady(20_000)
    }

    fun stopPreview() = preview?.stop()
    fun reloadPreview() = preview?.reload()

    fun simulateJsError() {
        viewModelScope.launch { preview?.evaluate("setTimeout(function(){ throw new Error('Simulated JS error from FOLD FORGE QA tools'); }, 0); 'ok'") }
    }

    // ------------------------------------------------------------------ search / references
    private var searchIndex: SearchIndex? = null
    var searchResults by mutableStateOf<List<SearchResult>>(emptyList())
    var referenceResults by mutableStateOf<List<Reference>>(emptyList())
    var searching by mutableStateOf(false)

    fun globalSearch(query: String, caseSensitive: Boolean, regex: Boolean, wholeWord: Boolean) {
        val p = _project ?: return
        viewModelScope.launch {
            searching = true
            saveAll()
            searchResults = withContext(Dispatchers.IO) {
                val idx = searchIndex ?: SearchIndex(p.fs).also { searchIndex = it }
                if (searchIndexDirty) { idx.refresh(); searchIndexDirty = false }
                idx.search(query, caseSensitive, regex, wholeWord)
            }
            searching = false
        }
    }

    fun findReferences(symbol: String) {
        val p = _project ?: return
        viewModelScope.launch {
            searching = true
            referenceResults = withContext(Dispatchers.IO) {
                val idx = searchIndex ?: SearchIndex(p.fs).also { searchIndex = it }
                idx.refresh(); searchIndexDirty = false
                idx.findReferences(symbol)
            }
            searching = false
            showPane(Pane.SEARCH)
        }
    }

    fun wordAtCursor(): String? {
        val doc = activeDoc ?: return null
        val t = doc.value.text
        var s = doc.value.selection.start.coerceIn(0, t.length)
        var e = s
        while (s > 0 && (t[s - 1].isLetterOrDigit() || t[s - 1] == '_' || t[s - 1] == '$')) s--
        while (e < t.length && (t[e].isLetterOrDigit() || t[e] == '_' || t[e] == '$')) e++
        return t.substring(s, e).takeIf { it.isNotEmpty() }
    }

    private val memoryListener: (Int) -> Unit = { searchIndex = null; searchIndexDirty = true }

    // ------------------------------------------------------------------ history / snapshots
    var historyVersion by mutableStateOf(0)

    override fun recordSnapshot(s: Snapshot) {
        val p = _project ?: return
        viewModelScope.launch(Dispatchers.IO) {
            container.database.snapshots().insert(SnapshotEntity(s.id, p.id, s.label, s.reason, s.timestamp, s.files.size))
            withContext(Dispatchers.Main) { historyVersion++ }
        }
    }

    fun createManualSnapshot() {
        val p = _project ?: return
        viewModelScope.launch(Dispatchers.IO) {
            saveAll()
            val s = p.snapshots.create("Manual snapshot", "manual")
            recordSnapshot(s)
            notify("Snapshot saved (${s.files.size} files)")
        }
    }

    fun restoreSnapshot(id: String) {
        val p = _project ?: return
        viewModelScope.launch {
            saveAll()
            val (safety, snap) = withContext(Dispatchers.IO) { p.snapshots.restore(id) to p.snapshots.read(id) }
            recordSnapshot(safety)
            p.addHistory("user", "Restored snapshot '${snap.label}'", snap.files.map { it.path }, safety.id)
            onFilesChanged(snap.files.map { it.path } + docs.keys, byAi = false)
            historyVersion++
            notify("Restored '${snap.label}'", "Undo") { restoreSnapshot(safety.id) }
        }
    }

    suspend fun compareSnapshot(id: String): List<SnapshotDiff> = withContext(Dispatchers.IO) {
        val p = project
        val snap = p.snapshots.read(id)
        snap.files.mapNotNull { f ->
            val then = p.snapshots.fileContent(id, f.path)?.toString(Charsets.UTF_8)
            val now = runCatching { if (p.fs.exists(f.path)) p.fs.readText(f.path) else null }.getOrNull()
            if (then == now) return@mapNotNull null
            if (!FileKind.of(f.path.substringAfterLast('/')).isText) return@mapNotNull SnapshotDiff(f.path, "(binary file changed)", 0, 0)
            val (a, r) = Diff.stats(then, now)
            SnapshotDiff(f.path, Diff.unified(f.path, then, now), a, r)
        }
    }

    // ------------------------------------------------------------------ session / lifecycle
    private var sessionJob: Job? = null

    fun persistSession() {
        val p = _project ?: return
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            val cursors = buildJsonObject { docs.forEach { (k, d) -> put(k, d.value.selection.start) } }
            container.database.sessions().save(
                SessionEntity(1, p.id, tabs.joinToString("\n"), activePath, cursors.toString(), presetId, System.currentTimeMillis(), cleanExit = false),
            )
        }
    }

    fun markCleanExit() {
        viewModelScope.launch(Dispatchers.IO) { container.database.sessions().markClean(true) }
    }

    // ------------------------------------------------------------------ feature controllers
    val ai = AiController(this)
    val qa = QaController(this)
    val git = GitController(this)
    val build = BuildController(this)
    val assets = AssetsController(this)

    override suspend fun runQa(scenarioIds: Set<String>?) = qa.runForAgent(scenarioIds)

    fun open(projectId: String) {
        if (_project?.id == projectId) return
        viewModelScope.launch {
            load = WorkspaceLoad.Loading
            try {
                val p = container.projects.open(projectId)
                _project = p
                val s = settings.value
                applyPreset(s.layoutPreset)
                children.clear(); expanded.clear(); docs.clear(); tabs.clear(); console.clear()
                refreshTree()
                val session = withContext(Dispatchers.IO) { container.database.sessions().get() }
                val restoreTabs = if (session?.projectId == p.id) session.openTabs.split('\n').filter { it.isNotBlank() } else emptyList()
                val toOpen = restoreTabs.ifEmpty { listOfNotNull(p.meta.entry.takeIf { p.fs.exists(it) && FileKind.of(it).isText }) }
                toOpen.filter { p.fs.exists(it) }.forEach { openFile(it) }
                if (session?.projectId == p.id) {
                    delay(100)
                    session.activeFile?.let { if (p.fs.exists(it)) openFile(it) }
                    runCatching {
                        val cursors = Json.parseToJsonElement(session.cursors) as JsonObject
                        cursors.forEach { (k, v) -> docs[k]?.let { d -> val o = (v as JsonPrimitive).intOrNull ?: 0; d.value = d.value.copy(selection = TextRange(o.coerceIn(0, d.value.text.length))) } }
                    }
                }
                load = WorkspaceLoad.Ready(p)
                git.refresh()
                ai.loadHistory()
                MemoryPressure.register(memoryListener)
                persistSession()
            } catch (e: Exception) {
                load = WorkspaceLoad.Failed(e.message ?: "Could not open project")
            }
        }
    }

    override fun onCleared() {
        MemoryPressure.unregister(memoryListener)
        super.onCleared()
    }

    companion object {
        const val AUTOSAVE_MS = 800L
        const val JOURNAL_MS = 300L
        const val MAX_EDITABLE = 2L * 1024 * 1024
        const val MAX_CONSOLE = 1000
    }
}

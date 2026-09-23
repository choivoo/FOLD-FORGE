# Architecture

## Modules

```
:core            Kotlin/JVM library — all engine logic, no Android dependency
  storage/       SafePaths, ProjectFileSystem, ProjectStore, SnapshotStore, ZipTools
  templates/     TemplateCatalog (templates are classpath resources + generated index)
  editor/        Highlighter (lexers), EditorOps (indent, pairs, brackets, find/replace, format), EditHistory
  patch/         Myers Diff + unified diff, PatchEngine (search/replace edits, review, stale-apply guard)
  security/      SecretScanner, UrlValidator, GradleCommand allow-list
  search/        SearchIndex (incremental), SymbolExtractor, references
  ai/            AIProvider, AnthropicProvider (official Java SDK), OpenAiCompatibleProvider,
                 ProjectBrain, ContextSelector, AgentProtocol, CodingAgent (agent + QA fix loop)
  qa/            QaModels, ScenarioGenerator, QaRunner, QaDriver interface
  git/           GitService (JGit), ConflictParser, IsolatedSystemReader
  github/        GitHubClient (REST, device flow, Actions runs/logs/artifacts)
  export/        AndroidProjectExporter, WebExporter, CiWorkflow
  apk/           AxmlEditor (binary manifest), ApkBuilder (apksig), ApkInspector
  assets/        ModelInspector (GLB/GLTF/OBJ), ImageInspector, AssetScanner, ProceduralAssets,
                 AssetGenerationProvider + HttpImageGenerationProvider
  resources/foldforge/runtime/foldforge-runtime.js   injected preview runtime (QA engine)

:player-template Minimal Java WebView app (no dependencies); its release APK is embedded in :app

:app             Android app
  FoldForgeApp / AppContainer        manual DI
  MainActivity                       splash, nav, WindowSizeClass, fold posture, PreviewHost owner
  data/database                      Room: projects, sessions, snapshots, ai_history, qa_runs, build_history, git_cache
  data/settings                      DataStore settings
  data/repository                    ProjectRepository
  core/security                      SecureStore (Keystore AES-GCM), ApkKeyProvider (Keystore RSA)
  core/web                           PreviewHost (sandbox WebView, runtime injection, console bridge)
  feature/*                          onboarding, home, workspace, editor, explorer, preview, console,
                                     ai, qa, git, build, assets, search, history, settings, palette
  worker/BackupWorker                WorkManager backup/restore
```

## Key flows

**Preview sandbox.** `PreviewHost` owns one WebView for the Activity lifetime, so it survives pane moves and fold/unfold (the Activity handles size config changes itself). Requests to `https://appassets.androidplatform.net/project/<path>` are served from the project through `SafePaths`. HTML responses get `window.__ffConfig` plus `/__ff/runtime.js` injected at the top of `<head>`. File and content access are disabled and no `addJavascriptInterface` is used. The app talks to the page only through `evaluateJavascript` and console messages.

**QA agent.** `QaRunner` (core) probes the runtime (`__ff.probe()`), builds scenarios with `ScenarioGenerator` plus any adapter/AI scenarios, and starts each one with `__ff.qa.start(json)`, polling `__ff.qa.poll(id)`. The runtime executes the steps (input injection, snapshots, expectations) inside the page. Viewport presets resize the WebView for orientation tests. Screenshots use PixelCopy (WebGL-safe). Reports, console logs, replays and screenshots are written to `.foldforge/qa/<run>/`.

**Coding agent.** `CodingAgent` (core): Project Brain → context selection (scoped, secrets redacted) → provider `editFiles` (JSON protocol) → `PatchEngine.prepare` → UI review → snapshot + apply → preview reload → console errors → `fixError` loop (fingerprinted, max N) → optional QA → `fixQaFailures` with replay + smoke regression. The app implements `AgentHost`.

**On-device APK build.** `ApkBuilder` copies the player template APK, drops old signatures and template web assets, rewrites `AndroidManifest.xml` with `AxmlEditor` (package, label, versionName/Code; new strings are appended to the pool so existing references stay valid), adds `assets/www/**`, then signs with apksig (v1+v2+v3) and verifies. On the device the key is an RSA key in the Android Keystore.

**State preservation.** Fold/unfold does not recreate the Activity (configChanges). The ViewModel holds editor docs, tabs, cursor, scroll, console, preview, AI and QA state. Process death is covered by the Room session (tabs, active file, cursors, layout) and per-file journals under `.foldforge/journal/`. Home offers "Restore Session".

## Threading

All file, ZIP, Git, indexing, AI, build and network work runs on `Dispatchers.IO`. The UI observes Compose state and StateFlow. WebView calls run on the main thread.

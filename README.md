# FOLD FORGE

**AI MOBILE DEVELOPMENT WORKSTATION — built for foldables (Galaxy Z Fold series)**
Version **1.0.0**

FOLD FORGE is an Android app that turns a foldable phone into a portable game/app development workstation:

```
idea → AI plan → project → code → live preview → play → automated gameplay QA → AI fix
     → regression → Git commit → GitHub push → Android export → APK build → install
```

Everything below is implemented in this repository and exercised by automated tests (see [docs/QA.md](docs/QA.md)).
Things that could not be verified in the build environment are listed under [Limitations](#limitations).

## Features

| Area | What you get |
|---|---|
| Projects | 11 templates (Blank Web, HTML App, Canvas Game, Three.js Game, 3D RPG Starter, Platformer, Puzzle, Interactive Story, Mobile UI, Android WebView Wrapper, Forge Runner demo), `.foldforge/` metadata (`project.json`, `history.json`, `ai-context.json`, `qa.json`, `build.json`), ZIP import/export, GitHub clone |
| Explorer | create / rename / delete (with snapshot) / duplicate / move / copy / cut / paste / import (SAF) / export / multi-select |
| Editor | syntax highlighting (HTML, CSS, JS, TS, JSON, Markdown, GLSL, Kotlin, XML, Gradle), line numbers, undo/redo, auto-indent, auto-closing pairs, bracket matching, find/replace (case/word/regex), format, toggle comment, tabs, horizontal scroll, mobile coding bar, hardware shortcuts, debounced autosave + crash journal |
| Preview | sandboxed WebView (`https://appassets.androidplatform.net/project/…`), Run/Stop/Reload, fullscreen, device presets (Phone, Fold Outer/Inner, Tablet, Desktop, Custom), rotate, FPS overlay, screenshot, auto-reload on save, virtual game controller (customizable), touch event inspector |
| Console | console.log/info/warn/error, uncaught errors, promise rejections, stack traces, tap to jump to file:line |
| AI | provider abstraction (`AIProvider`: chat, analyze, generateFiles, editFiles, fixError, reviewDiff, generateTests, analyzeScreenshot), Claude via the official Anthropic Java SDK (default `claude-opus-5`), any OpenAI-compatible endpoint, Project Brain index, context scopes, secret redaction, diff review (Apply All / Apply Selected / Reject), undo AI change, AI Build mode |
| QA agent | injected runtime with `FoldForgeTestInput`, `window.__foldForgeTest` adapter, black-box mode, scenario engine (smoke, movement, jump, combat, UI/layout, touch, restart, death, orientation, performance, timed play sessions), screenshots + optional vision, replay, AI fix loop with regression, artifacts (`qa-report.json`, `console.log`, `replay.json`, `screenshots/`) |
| Git / GitHub | JGit (init, status, diff, stage, commit, log, branch, checkout, push, pull, fetch, conflicts with ours/theirs/both + AI explanation), secret-scan before commit, PAT or OAuth device login, create repository, open repo page |
| Build | **on-device APK builder** (template APK + manifest rewrite + apksig v1/v2/v3 signing with an Android Keystore key), install via system installer, share; export Project ZIP / Web build / Android Studio project / Git repository ZIP; GitHub Actions cloud build (dispatch, logs, artifact download) |
| Assets | GLB/GLTF/OBJ inspection (meshes, triangles, materials, animations), 3D viewer (orbit, wireframe, animations), image metadata, WebP convert/resize, unused-asset scan, procedural 3D generator, optional AI image provider |
| Fold UX | folded: bottom nav (Home, Projects, Editor, AI, Preview); unfolded: 25/45/30 panes with draggable dividers and layout presets A–F; split editor+preview; tabletop posture: play on top, tools below; state kept across fold/unfold |

## Architecture

Gradle modules:

- `:core` — pure Kotlin/JVM engine (no Android APIs): storage, templates, editor, patch/diff, security, search, AI, QA, Git, GitHub, export, APK builder, assets. Fully unit-tested on the JVM.
- `:player-template` — minimal dependency-free WebView app whose APK is the template for on-device builds.
- `:app` — Android app (Kotlin, Jetpack Compose, Material 3, Room, DataStore, WorkManager, WindowManager, Navigation).

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Build

Requirements: JDK 17+, Android SDK (platform 36, build-tools 36).

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew test lint assembleDebug assembleRelease
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk   (signed with the local debug key — not a Play upload key)
```

Web template QA in headless Chromium: `cd tools/webqa && npm install && node run-web-qa.mjs`.

CI (`.github/workflows/android.yml`) runs tests, lint, both APK builds and the web QA, and uploads the APKs + `SHA256SUMS` as artifacts.

## Install on a Galaxy Z Fold

1. Download `FoldForge-1.0.0-apks` from the latest successful GitHub Actions run (or build locally).
2. Copy `FoldForge-1.0.0-debug.apk` to the phone and open it (allow "Install unknown apps" for your file manager), or `adb install -r FoldForge-1.0.0-debug.apk`.

More: [docs/APK_BUILD.md](docs/APK_BUILD.md).

## AI provider setup

Settings → AI: choose **Anthropic Claude** (default model `claude-opus-5`) or **OpenAI-compatible** (endpoint + model), paste the API key (stored encrypted with the Android Keystore). Without a provider the editor, preview, QA, Git, ZIP and APK builder all work offline.

## GitHub

Settings → Git or Git panel → Connect GitHub (personal access token with `repo`, `workflow` scopes, or OAuth device login with your OAuth App client ID). See [docs/GITHUB.md](docs/GITHUB.md).

## Security

API keys encrypted with the Android Keystore, never logged; sandboxed preview with no file access and no JS bridge; Zip Slip / zip-bomb protection; secret scanning before commits and AI requests; no all-files, accessibility or device-admin permissions. See [docs/SECURITY.md](docs/SECURITY.md).

## Limitations

- No KVM in the build environment, so the app was **not launched on an emulator or physical device**. Launch and UI flows were verified with Robolectric (JVM Android framework) — see docs/QA.md.
- Live AI and GitHub network calls were not exercised (no credentials in the build environment); their HTTP clients are tested against local mock servers.
- Gradle cannot run on the phone: Android-native projects build through GitHub Actions or a desktop. Web projects build APKs fully on-device.
- APKs are large (debug ≈ 94 MB, release ≈ 70 MB) because R8 minification is disabled in 1.0.0.
- Screenshots are not included: no device or emulator was available to capture them.

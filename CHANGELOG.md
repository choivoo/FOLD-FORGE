# Changelog

## 1.0.0 — 2026-09-23

First release.

- Foldable-first Compose workspace: folded bottom-nav mode, unfolded three-pane layout (25/45/30, draggable), layout presets A–F, split editor/preview, tabletop posture.
- Projects: 11 templates, `.foldforge` metadata, explorer with full file operations, snapshots and history, secure ZIP import/export, backup/restore.
- Code editor with highlighting for 10 languages, IDE editing behaviours, find/replace, format, coding bar, keyboard shortcuts, command palette, autosave and crash journal.
- Sandboxed live preview with injected runtime, console capture, device presets, FPS overlay, screenshots, virtual controller and touch inspector.
- AI: provider abstraction, Claude via the official Anthropic Java SDK, OpenAI-compatible endpoints, Project Brain, scoped and redacted context, reviewed patches, agent loop with auto-fix, AI Build mode.
- Gameplay QA agent: scenario engine, adapter and black-box modes, orientation and performance tests, timed play sessions, replay, vision option, AI fix loop with regression, artifacts.
- Git via JGit with secret protection and conflict tools; GitHub token/device login, repo creation, Actions cloud builds.
- On-device APK builder (template APK + binary manifest rewrite + apksig signing), Android Studio project exporter, web export.
- Asset manager: GLB/GLTF/OBJ inspection and 3D viewer, image tools, unused-asset scan, procedural 3D generator, optional AI image provider.
- CI workflow, 68 JVM tests, Chromium template QA.
- QA engine: `waitUntil` step (waits on game state, optionally re-pressing a button) so scenarios hold at any frame rate; the rpg3d, platformer and Forge Runner scenarios use it.
- rpg3d: an attack pressed during the cooldown is buffered instead of dropped (lost inputs on slow devices).
- Player template: back gestures navigate WebView history on Android 13+ (`OnBackInvokedDispatcher`; apps targeting API 36 no longer receive `onBackPressed`).

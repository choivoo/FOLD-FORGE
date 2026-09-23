# FOLD FORGE 1.0.0 — Release Notes

**Status: 1.0.0 Release Candidate.** Core features pass automated verification and real APKs were built.
It is not labelled "Official Release" because the app has not yet been launched on a physical
Galaxy Z Fold or an emulator: the build environment has no hardware virtualization.

## Highlights
- Build web games on a foldable: templates, editor, live preview, console
- AI coding agent (Claude / OpenAI-compatible) with reviewed diffs, snapshots, auto-fix
- Gameplay QA agent that plays your game (scenario engine, replay, AI fix + regression)
- Git + GitHub (token or device login, repo creation, push/pull, conflicts)
- APKs built on the phone itself (signed, verified), Android Studio export, GitHub Actions cloud builds

## Artifacts (built 2026-09-23)
| File | Size | SHA-256 |
|---|---|---|
| FoldForge-1.0.0-debug.apk | 94,020,648 B | see `release/SHA256SUMS` |
| FoldForge-1.0.0-release-localsigned.apk | 70,481,388 B | see `release/SHA256SUMS` |

Both are signed with the Android debug key (v2 signature verified with `apksigner`). CI rebuilds and
publishes them as the `FoldForge-1.0.0-apks` artifact. CI builds use a different debug key, so their
checksums differ.

## Requirements
Android 10+ (minSdk 29), targetSdk 36. Designed for Galaxy Z Fold devices; works on phones and tablets.

## Known limitations
See README → Limitations and docs/QA.md.

# QA — FOLD FORGE 1.0.0

All results below were produced in the build environment on 2026-09-23 by running the commands shown.
Status values: **PASS**, **FAIL**, **BLOCKED** (could not run in this environment), **NOT TESTED**.

## Automated test suites

| Suite | Command | Result |
|---|---|---|
| Core engine (JVM) | `./gradlew :core:test` | 58 tests, 0 failed, 0 skipped |
| App (Robolectric, SDK 35) | `./gradlew :app:testDebugUnitTest` | 10 tests, 0 failed |
| Android lint | `./gradlew lint` | 0 errors (warnings documented) |
| Debug + release APK | `./gradlew assembleDebug assembleRelease` | BUILD SUCCESSFUL |
| Web template QA (Chromium) | `cd tools/webqa && node run-web-qa.mjs` | 20/20 template×viewport runs PASS |
| Low-FPS stress (template QA) | `FRAME_COST_MS=150 node run-web-qa.mjs`; `FRAME_COST_MS=300 node run-web-qa.mjs rpg3d` / `forge-runner,platformer` | full suite at 150 ms/frame: only rpg3d/platformer/Forge Runner failed before the fix; after it those three PASS at 150 and 300 ms/frame (FPS checks WARN, as expected) |
| QA mutation check | `MUTATE=1 node run-web-qa.mjs rpg3d` | injected combat bug detected (2 FAIL checks, as intended) |
| Exported Android project | export → `./gradlew assembleDebug` | real `app-debug.apk`, `aapt2` confirms package |

### Core tests cover
Zip Slip, `../` and absolute entries, malformed/empty ZIPs, oversized archives and zip bombs, unsafe file names, symlink escapes, invalid `project.json`; all 11 templates create valid projects; project listing/metadata; file operations; partial and full snapshot restore (Undo AI change); editor (newline indent, pairs, backspace, indent/outdent, comment, bracket match, find/replace, JSON and brace formatting, undo coalescing, highlighter invariants over every template file); Myers diff; patch engine (apply, ambiguity, fuzzy match, rejected paths, stale-apply guard); secret scanner; URL validation; Gradle allow-list; incremental search index and references; Project Brain dependencies and context scoping/redaction; agent protocol parsing; agent loop (edit → review → snapshot → runtime-error fix → undo) with a test-only scripted provider; QA fix loop (fixed / no progress); scenario generator categories and deterministic replay; QA runner report/perf alerts/viewport reset; blocked runtime; OpenAI-compatible HTTP + error mapping against MockWebServer; Git init/add/commit/log/status/diff/branch/checkout, secret-blocked commit, remote validation, pull conflict detection + parsing; GitHub client against MockWebServer; Android export structure; web export; binary manifest round trip; **on-device APK builder: signed, verified (v1/v2/v3), package/label/version patched, `zipalign -c` OK**; GLB inspection; image headers; unused assets; procedural assets.

### App tests cover
App launch → onboarding → Forge Runner demo created → workspace (compact/folded layout, bottom nav) → Preview pane; Settings screen; Room DAOs (projects, sessions, builds); settings persistence and battery-mode derivation; SecureStore refuses plaintext storage without the Keystore; preview helpers; WorkspaceViewModel with a real project (open, auto-pair, auto-indent, debounced autosave to disk, undo, safe delete with restorable snapshot, layout presets); graceful failure for a missing project; New Project dialog (component level).

**Known test-harness limitation:** a full-app UI test that opens a Material dialog over the Home screen never reaches Compose idle under Robolectric. It was investigated: a snapshot-observer probe showed no state churn, so this is not an app recomposition loop. The dialog is covered by component tests, and the create/edit flow by the ViewModel test.

### Frame-rate robustness
The first CI run failed rpg3d combat at the fold-inner viewport: the GitHub runner's software GL gave a low frame rate, and since game time is capped per frame (`dt`), fixed wall-clock waits were too short and attack presses during the cooldown were dropped. This was reproduced locally with `FRAME_COST_MS` (burns N ms per animation frame; `CPU_THROTTLE` is also available but does not slow GPU rasterization) and fixed by buffering the attack input and replacing fixed waits with `waitUntil` state waits. The same stress run exposed and fixed identical fragility in the platformer and Forge Runner scenarios.

## Final QA matrix

| Item | Status | Evidence / reason |
|---|---|---|
| APP LAUNCH | PASS (Robolectric) | Launch → onboarding → workspace flow passes on Robolectric. **Not** launched on a device/emulator: no KVM in this environment. |
| PROJECT CREATE | PASS | Core template tests; ViewModel test; onboarding demo creation in the UI test |
| PROJECT SAVE | PASS | Autosave writes to disk (ViewModel test); atomic writes; journal |
| EDITOR | PASS | Engine tests + ViewModel IDE behaviours |
| PREVIEW | PASS (partial) | Runtime + sandbox serving verified in Chromium (same injection scheme); preview pane shown in the Robolectric flow; Android WebView rendering not observable without a device |
| CONSOLE | PASS (partial) | Runtime error capture verified in Chromium; WebView console bridge not run on a device |
| AI | PASS (architecture) / BLOCKED (live) | Agent loop, protocol and HTTP provider tested with mocks; no API key available for live Claude calls |
| QA AGENT | PASS | Scenario engine runs every template in Chromium; mutation bug detected |
| ZIP IMPORT | PASS | Secure extraction tests + project import tests |
| ZIP EXPORT | PASS | Round-trip test |
| GIT | PASS | JGit tests (init/add/commit/log/branch/checkout/conflict) |
| GITHUB | BLOCKED (live) | No credentials; REST client verified against MockWebServer only |
| ANDROID EXPORT | PASS | Exported project built with Gradle into a real APK |
| APK BUILD | PASS | FOLD FORGE debug/release APKs built and verified; on-device builder produces verified signed APKs (JVM test) |
| FOLD UI | PASS (partial) | Compact layout verified on Robolectric; unfolded/tabletop layouts compile and are implemented but not visually verified on a foldable |

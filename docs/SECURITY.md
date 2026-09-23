# Security

| Threat | Mitigation | Where |
|---|---|---|
| API key / token leakage | AES-256-GCM encryption with a non-exportable Android Keystore key; only ciphertext in SharedPreferences; refuses to store if the Keystore is unavailable; `AiConfig.toString()` redacts the key; keys never logged | `SecureStore`, `AiConfig` |
| Secrets committed to Git | Staged files scanned (sensitive filenames + token patterns); commit blocked with option to unstage + `.gitignore`; default `.gitignore` excludes `.env`, keystores, `local.properties` | `SecretScanner`, `GitService.commit` |
| Secrets sent to AI | Sensitive files excluded from context; detected secrets replaced with `[REDACTED:…]`; whole-project sends need confirmation above 40k chars | `ContextSelector`, `AiController` |
| Path traversal | Every project path normalized; `..`, absolute paths, drive letters, NUL/control characters and symlink escapes rejected | `SafePaths` |
| Zip Slip / zip bombs | Entry names sanitized; per-entry, total and entry-count limits; compression-ratio check on actual bytes; staged extraction removed on failure | `ZipTools` |
| Malicious preview code | WebView: no file/content access, no universal file URL access, no JS interface, mixed content blocked, no popups; top-level navigation outside the sandbox intercepted (Ask / Open in browser / Block); optional network block; `.foldforge/` and `.git/` never served | `PreviewHost` |
| AI writing outside the project | Patch paths validated; `.foldforge/` and `.git/` edits refused; review before apply; snapshot before every AI apply | `PatchEngine`, `CodingAgent` |
| Shell injection in builds | Gradle invocations built from an allow-list of tasks/args (desktop/CI hosts) | `GradleCommand` |
| Git URL abuse | https/ssh only, no embedded credentials, no option-injection (`-…`), no `file://` | `UrlValidator` |
| Foreign git config | JGit isolated from system/user git config | `IsolatedSystemReader` |
| Over-permission | Only `INTERNET` and `REQUEST_INSTALL_PACKAGES` (every install is confirmed by the system installer); no storage, accessibility or device-admin permissions; SAF for external files | `AndroidManifest.xml` |
| Backup exfiltration | `allowBackup=false`; data extraction rules exclude all domains | manifest, `data_extraction_rules.xml` |

## Notes

- APKs built on the device are signed with a per-device development key in the Android Keystore. Only builds signed with the same key can update each other. It is not a Google Play upload key.
- The 1.0.0 release APK in this repo is signed with the Android debug key for installability. Configure your own `signingConfig` for store distribution; never commit keystores or passwords.
- Lint reports `TrustAllX509TrustManager` inside the JGit jar. That class is only used when `http.sslVerify=false` is configured, which FOLD FORGE never sets, and foreign git config is ignored.

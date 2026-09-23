# Building APKs

## 1. Building FOLD FORGE itself

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties     # JDK 17+, Android SDK platform 36 + build-tools 36
./gradlew test lint assembleDebug assembleRelease
```

| Output | Path | Signing |
|---|---|---|
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | Android debug key |
| Release | `app/build/outputs/apk/release/app-release.apk` | Android debug key (local install only) |

For Google Play, create your own upload keystore and add a `signingConfigs.release` block that reads its passwords from environment variables. Never commit the keystore. `./gradlew bundleRelease` then produces an AAB.

Install: `adb install -r app-debug.apk`, or copy the APK to the phone and open it.

CI: every push runs `.github/workflows/android.yml` and uploads `FoldForge-1.0.0-apks` (both APKs + `SHA256SUMS`).

## 2. Building an APK of your game on the phone (no Gradle)

Workspace → **Build** → *APK Builder*:

1. Enter the app name, package name (validated), version name and code.
2. **Build APK** exports the web files (excluding `.foldforge`, `.git` and secrets), injects them into the embedded FOLD FORGE Player template, rewrites the manifest, and signs the APK (v1+v2+v3) with a per-device Android Keystore key. The signature is then verified.
3. The output shows filename, size, path, variant, build date and SHA-256. Use **Install** (system installer; allow "Install unknown apps" for FOLD FORGE once), **Share**, or **Save to…**.

For Android WebView Wrapper projects, the builder packages `app/src/main/assets/www`.

## 3. Android Studio project export

Build → **Export → Android Studio Project (ZIP)** generates a Gradle/Kotlin project (WebViewAssetLoader-based `MainActivity`, manifest, theme, adaptive icon, Gradle wrapper, CI workflow, README). The structure is validated before zipping. Open it in Android Studio or run `./gradlew assembleDebug`.

## 4. Cloud build with GitHub Actions

Build → *Cloud build*: **Add CI workflow** (if missing), commit and push, then **Run cloud build**. FOLD FORGE dispatches the workflow, polls it, shows the job logs, downloads the artifact ZIP (safely extracted) and exposes the APK for install/share. This requires a GitHub token with the `repo` and `workflow` scopes.

package com.foldforge.studio.core.export

import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.storage.ProjectStore
import com.foldforge.studio.core.storage.SafePaths
import java.io.File
import java.io.IOException

data class AndroidExportOptions(
    val appName: String,
    val packageName: String,
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val fullscreen: Boolean = true,
    val orientation: String = "unspecified", // unspecified | landscape | portrait | sensorLandscape
    val includeCiWorkflow: Boolean = true,
    val minSdk: Int = 26,
    val compileSdk: Int = 36,
    val agpVersion: String = "8.13.0",
    val kotlinVersion: String = "2.2.20",
    val webkitVersion: String = "1.14.0",
    val gradleVersion: String = "8.14.3",
)

/**
 * Generates a complete Android Studio / Gradle project that wraps web content in a secure WebView.
 * The project builds with `./gradlew assembleDebug` (JDK 17+ and Android SDK required).
 */
class AndroidProjectExporter {

    fun exportFromWeb(webRoot: File, targetDir: File, options: AndroidExportOptions): List<String> {
        if (targetDir.exists() && (targetDir.listFiles()?.isNotEmpty() == true)) {
            throw IOException("Export target '${targetDir.name}' is not empty")
        }
        return writeWrapperProject(targetDir, options, webRoot)
    }

    /**
     * Writes the Gradle wrapper project into [targetDir]. If [webSourceDir] is given its web files
     * are copied to `app/src/main/assets/www/` (excluding `.foldforge`, `.git` and secrets).
     * Returns the list of written project-relative paths.
     */
    fun writeWrapperProject(targetDir: File, options: AndroidExportOptions, webSourceDir: File?): List<String> {
        validateOptions(options)
        targetDir.mkdirs()
        val fs = ProjectFileSystem(targetDir)
        val written = mutableListOf<String>()
        fun put(path: String, text: String) { fs.writeText(path, text); written += path }
        fun putBytes(path: String, bytes: ByteArray) { fs.writeBytes(path, bytes); written += path }

        val pkgPath = options.packageName.replace('.', '/')
        put("settings.gradle.kts", settingsGradle(options))
        put("build.gradle.kts", rootBuildGradle(options))
        put("gradle.properties", "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nandroid.nonTransitiveRClass=true\n")
        put("gradle/wrapper/gradle-wrapper.properties", wrapperProperties(options))
        putBytes("gradle/wrapper/gradle-wrapper.jar", resource("gradle-wrapper.jar"))
        put("gradlew", String(resource("gradlew"), Charsets.UTF_8))
        put("gradlew.bat", String(resource("gradlew.bat"), Charsets.UTF_8))
        File(targetDir, "gradlew").setExecutable(true, false)
        put("app/build.gradle.kts", appBuildGradle(options))
        put("app/proguard-rules.pro", "# Keep WebView JavaScript interfaces if you add any.\n")
        put("app/src/main/AndroidManifest.xml", manifest(options))
        put("app/src/main/java/$pkgPath/MainActivity.kt", mainActivity(options))
        put("app/src/main/res/values/strings.xml", strings(options))
        put("app/src/main/res/values/colors.xml", COLORS)
        put("app/src/main/res/values/themes.xml", themes(options))
        put("app/src/main/res/drawable/ic_launcher_foreground.xml", ICON_FOREGROUND)
        put("app/src/main/res/drawable/ic_launcher_background.xml", ICON_BACKGROUND)
        put("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", ADAPTIVE_ICON)
        put("app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml", ADAPTIVE_ICON)
        put(".gitignore", ProjectStore.DEFAULT_GITIGNORE + "*.iml\n.idea/\ncaptures/\n.externalNativeBuild/\n.cxx/\n")
        put("README.md", readme(options))
        if (options.includeCiWorkflow) put(".github/workflows/android.yml", CiWorkflow.android(options.appName))

        if (webSourceDir != null) {
            val www = "app/src/main/assets/www"
            webSourceDir.walkTopDown()
                .onEnter { it == webSourceDir || !excludedWeb(it.name) }
                .filter { it.isFile && !excludedWeb(it.name) }
                .forEach { f ->
                    val rel = SafePaths.relativize(webSourceDir, f)
                    putBytes("$www/$rel", f.readBytes())
                }
            if (!fs.exists("$www/index.html")) throw IOException("Web project has no index.html at its root")
        }
        return written
    }

    /** Structural validation of an exported project. Returns a list of problems (empty = valid). */
    fun validate(dir: File): List<String> {
        val problems = mutableListOf<String>()
        REQUIRED.forEach { if (!File(dir, it).isFile) problems += "Missing $it" }
        val manifest = File(dir, "app/src/main/AndroidManifest.xml")
        if (manifest.isFile) {
            val text = manifest.readText()
            if (!text.contains("android.intent.action.MAIN")) problems += "Manifest has no launcher activity"
            if (text.contains("usesCleartextTraffic=\"true\"")) problems += "Manifest allows cleartext traffic"
        }
        val gradle = File(dir, "app/build.gradle.kts")
        if (gradle.isFile && !Regex("applicationId\\s*=\\s*\"[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+\"").containsMatchIn(gradle.readText())) {
            problems += "applicationId missing or invalid"
        }
        if (!File(dir, "app/src/main/assets/www/index.html").isFile) problems += "Missing web entry app/src/main/assets/www/index.html"
        if (File(dir, "gradlew").isFile && !File(dir, "gradlew").canExecute()) problems += "gradlew is not executable"
        return problems
    }

    private fun excludedWeb(name: String) =
        name == ProjectFileSystem.META_DIR || name == ".git" || name == ".env" || name.startsWith(".env.") ||
            name.endsWith(".keystore") || name.endsWith(".jks") || name == "local.properties" || name == "node_modules"

    private fun resource(name: String): ByteArray =
        (javaClass.classLoader.getResourceAsStream("foldforge/export/$name")
            ?: throw IOException("Export resource missing: $name")).use { it.readBytes() }

    companion object {
        val REQUIRED = listOf(
            "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar",
            "app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
            "app/src/main/res/values/strings.xml", "app/src/main/res/values/themes.xml",
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
        )
        private val PACKAGE_RE = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
        private val KEYWORDS = setOf(
            "abstract", "class", "default", "do", "for", "if", "import", "int", "new", "package",
            "private", "public", "return", "static", "super", "switch", "this", "throw", "try", "void",
            "while", "true", "false", "null", "fun", "val", "var", "object", "is", "in", "as", "when", "interface",
        )

        fun validatePackageName(pkg: String): String? = when {
            !PACKAGE_RE.matches(pkg) -> "Package name must look like com.example.app (lowercase letters, digits, underscores)"
            pkg.split('.').any { it in KEYWORDS } -> "Package name must not contain Java/Kotlin keywords"
            pkg.length > 150 -> "Package name is too long"
            else -> null
        }

        fun packageNameFor(appName: String): String {
            val part = appName.lowercase().filter { it.isLetterOrDigit() && it.code < 128 }
                .let { if (it.isEmpty() || it.first().isDigit()) "app$it" else it }.take(30)
            val safe = if (part in KEYWORDS) "${part}app" else part
            return "com.foldforge.generated.$safe"
        }

        fun xmlEscape(s: String) = s.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "\\\"").replace("'", "\\'")
            .let { if (it.startsWith("@") || it.startsWith("?")) "\\$it" else it }

        fun kotlinStringEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
    }

    private fun validateOptions(o: AndroidExportOptions) {
        validatePackageName(o.packageName)?.let { throw IllegalArgumentException(it) }
        require(o.appName.isNotBlank()) { "App name must not be empty" }
        require(o.versionCode in 1..2_100_000_000) { "Invalid versionCode" }
        require(Regex("^[0-9A-Za-z._-]{1,40}$").matches(o.versionName)) { "Invalid versionName" }
        require(o.orientation in setOf("unspecified", "landscape", "portrait", "sensorLandscape", "sensorPortrait", "fullSensor")) { "Invalid orientation" }
    }

    private fun settingsGradle(o: AndroidExportOptions) = """
        pluginManagement {
            repositories {
                google()
                mavenCentral()
                gradlePluginPortal()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }
        rootProject.name = "${kotlinStringEscape(o.appName)}"
        include(":app")
    """.trimIndent() + "\n"

    private fun rootBuildGradle(o: AndroidExportOptions) = """
        plugins {
            id("com.android.application") version "${o.agpVersion}" apply false
            id("org.jetbrains.kotlin.android") version "${o.kotlinVersion}" apply false
        }
    """.trimIndent() + "\n"

    private fun wrapperProperties(o: AndroidExportOptions) = """
        distributionBase=GRADLE_USER_HOME
        distributionPath=wrapper/dists
        distributionUrl=https\://services.gradle.org/distributions/gradle-${o.gradleVersion}-bin.zip
        networkTimeout=10000
        validateDistributionUrl=true
        zipStoreBase=GRADLE_USER_HOME
        zipStorePath=wrapper/dists
    """.trimIndent() + "\n"

    private fun appBuildGradle(o: AndroidExportOptions) = """
        plugins {
            id("com.android.application")
            id("org.jetbrains.kotlin.android")
        }

        android {
            namespace = "${o.packageName}"
            compileSdk = ${o.compileSdk}

            defaultConfig {
                applicationId = "${o.packageName}"
                minSdk = ${o.minSdk}
                targetSdk = ${o.compileSdk}
                versionCode = ${o.versionCode}
                versionName = "${o.versionName}"
            }

            buildTypes {
                release {
                    isMinifyEnabled = false
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            androidResources {
                // Keep web assets uncompressed-friendly; don't strip dot-folders from www.
                ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
            }
        }

        kotlin {
            compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        }

        dependencies {
            implementation("androidx.webkit:webkit:${o.webkitVersion}")
        }
    """.trimIndent() + "\n"

    private fun manifest(o: AndroidExportOptions) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">

            <uses-permission android:name="android.permission.INTERNET" />

            <application
                android:allowBackup="true"
                android:icon="@mipmap/ic_launcher"
                android:roundIcon="@mipmap/ic_launcher_round"
                android:label="@string/app_name"
                android:supportsRtl="true"
                android:usesCleartextTraffic="false"
                android:theme="@style/Theme.App">
                <activity
                    android:name=".MainActivity"
                    android:exported="true"
                    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density|uiMode"
                    android:screenOrientation="${o.orientation}"
                    android:resizeableActivity="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent() + "\n"

    private fun mainActivity(o: AndroidExportOptions) = """
        package ${o.packageName}

        import android.annotation.SuppressLint
        import android.app.Activity
        import android.content.Intent
        import android.net.Uri
        import android.os.Bundle
        import android.view.View
        import android.view.WindowInsets
        import android.view.WindowInsetsController
        import android.webkit.WebResourceRequest
        import android.webkit.WebResourceResponse
        import android.webkit.WebView
        import android.webkit.WebViewClient
        import androidx.webkit.WebViewAssetLoader

        /**
         * Hosts the bundled web app (assets/www) in a WebView served from a virtual https origin via
         * WebViewAssetLoader. File access is disabled and no JavaScript bridge is exposed.
         */
        class MainActivity : Activity() {
            private lateinit var webView: WebView

            @SuppressLint("SetJavaScriptEnabled")
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
                    .build()
                webView = WebView(this)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mediaPlaybackRequiresUserGesture = false
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        assetLoader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                        // External links open in the browser instead of inside the app.
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) }
                        return true
                    }
                }
                setContentView(webView)
                ${if (o.fullscreen) "enterImmersive()" else "// Fullscreen disabled in export options"}
                if (savedInstanceState != null) webView.restoreState(savedInstanceState)
                else webView.loadUrl("https://" + WebViewAssetLoader.DEFAULT_DOMAIN + "/assets/www/index.html")
            }

            private fun enterImmersive() {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    window.setDecorFitsSystemWindows(false)
                    window.insetsController?.let {
                        it.hide(WindowInsets.Type.systemBars())
                        it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
            }

            override fun onSaveInstanceState(outState: Bundle) {
                super.onSaveInstanceState(outState)
                webView.saveState(outState)
            }

            @Deprecated("Deprecated in Java")
            override fun onBackPressed() {
                if (webView.canGoBack()) webView.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
            }

            override fun onPause() { webView.onPause(); super.onPause() }
            override fun onResume() { super.onResume(); webView.onResume() }
            override fun onDestroy() { webView.destroy(); super.onDestroy() }
        }
    """.trimIndent() + "\n"

    private fun strings(o: AndroidExportOptions) = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name">${xmlEscape(o.appName)}</string>
        </resources>
    """.trimIndent() + "\n"

    private fun themes(o: AndroidExportOptions) = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <style name="Theme.App" parent="android:Theme.Material.NoActionBar">
                <item name="android:windowBackground">@color/app_background</item>
                <item name="android:statusBarColor">@color/app_background</item>
                <item name="android:navigationBarColor">@color/app_background</item>
                ${if (o.fullscreen) "<item name=\"android:windowFullscreen\">true</item>" else ""}
            </style>
        </resources>
    """.trimIndent() + "\n"

    private fun readme(o: AndroidExportOptions) = """
        # ${o.appName}

        Android WebView app generated by **FOLD FORGE**. The web game/app lives in
        `app/src/main/assets/www/` and is served from `https://appassets.androidplatform.net/assets/www/`
        through `WebViewAssetLoader` (no file:// access, no JavaScript bridge).

        - Package: `${o.packageName}`
        - Version: ${o.versionName} (${o.versionCode})
        - minSdk ${o.minSdk} · targetSdk ${o.compileSdk}

        ## Build

        Requirements: JDK 17+, Android SDK (platform ${o.compileSdk}). Set `ANDROID_HOME` or create `local.properties`
        with `sdk.dir=/path/to/Android/sdk`.

        ```bash
        ./gradlew assembleDebug
        # APK: app/build/outputs/apk/debug/app-debug.apk
        adb install -r app/build/outputs/apk/debug/app-debug.apk
        ```

        Or open this folder in Android Studio and press Run.

        ## CI

        `.github/workflows/android.yml` builds the debug APK on every push and uploads it as a workflow artifact.

        ## Release signing

        Release builds are unsigned by default. Configure your own keystore in `app/build.gradle.kts`
        (`signingConfigs`) — never commit keystores or passwords.
    """.trimIndent() + "\n"
}

private const val COLORS = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="app_background">#FF0F1115</color>
    <color name="icon_background">#FF14171F</color>
    <color name="icon_accent">#FFFF8A3D</color>
</resources>
"""

private const val ICON_BACKGROUND = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/icon_background" />
</shape>
"""

private const val ICON_FOREGROUND = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="@color/icon_accent" android:pathData="M38,34h32v8H46v8h20v8H46v16h-8z" />
</vector>
"""

private const val ADAPTIVE_ICON = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
"""

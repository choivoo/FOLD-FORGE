package com.foldforge.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.core.ui.theme.FoldForgeTheme
import com.foldforge.studio.core.web.PreviewHost
import com.foldforge.studio.data.settings.AppSettings
import com.foldforge.studio.feature.home.HomeScreen
import com.foldforge.studio.feature.onboarding.OnboardingScreen
import com.foldforge.studio.feature.settings.SettingsScreen
import com.foldforge.studio.feature.workspace.WorkspaceScreen
import kotlinx.coroutines.launch

/** Device form factor as seen by the layout system. */
enum class Posture { NORMAL, TABLETOP, BOOK }

data class WindowInfo(val widthClass: WindowWidthSizeClass, val posture: Posture) {
    val expanded get() = widthClass == WindowWidthSizeClass.Expanded
    val medium get() = widthClass == WindowWidthSizeClass.Medium
    val compact get() = widthClass == WindowWidthSizeClass.Compact
}

val LocalWindowInfo = staticCompositionLocalOf { WindowInfo(WindowWidthSizeClass.Compact, Posture.NORMAL) }
val LocalPreviewHost = staticCompositionLocalOf<PreviewHost?> { null }

class MainActivity : ComponentActivity() {
    private var posture by mutableStateOf(Posture.NORMAL)
    private var pendingZip by mutableStateOf<Uri?>(null)
    private lateinit var previewHost: PreviewHost

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FoldForgeApp).container
        previewHost = PreviewHost(this) { container.templates.runtimeScript() }
        handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                    val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    posture = when {
                        fold?.state == FoldingFeature.State.HALF_OPENED && fold.orientation == FoldingFeature.Orientation.HORIZONTAL -> Posture.TABLETOP
                        fold?.state == FoldingFeature.State.HALF_OPENED && fold.orientation == FoldingFeature.Orientation.VERTICAL -> Posture.BOOK
                        else -> Posture.NORMAL
                    }
                }
            }
        }

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val windowSize = calculateWindowSizeClass(this)
            val s = settings
            FoldForgeTheme(s?.theme ?: AppSettings().theme) {
                CompositionLocalProvider(
                    LocalWindowInfo provides WindowInfo(windowSize.widthSizeClass, posture),
                    LocalPreviewHost provides previewHost,
                ) {
                    Box(Modifier.fillMaxSize().background(Forge.colors.background)) {
                        if (s != null) AppNavigation(s, pendingZip) { pendingZip = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) pendingZip = intent.data
    }

    override fun onPause() {
        previewHost.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        previewHost.resume()
    }

    override fun onDestroy() {
        previewHost.destroy()
        super.onDestroy()
    }
}

@Composable
private fun AppNavigation(settings: AppSettings, pendingZip: Uri?, onZipConsumed: () -> Unit) {
    val nav = rememberNavController()
    val start = remember { if (settings.onboardingDone) "home" else "onboarding" }
    NavHost(nav, startDestination = start) {
        composable("onboarding") {
            OnboardingScreen(onDone = { demoId ->
                nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                if (demoId != null) nav.navigate("workspace/$demoId")
            })
        }
        composable("home") {
            HomeScreen(
                openProject = { nav.navigate("workspace/$it") },
                openSettings = { nav.navigate("settings") },
                incomingZip = pendingZip,
                onZipConsumed = onZipConsumed,
            )
        }
        composable("workspace/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            WorkspaceScreen(projectId = id, onHome = { nav.popBackStack("home", inclusive = false) }, openSettings = { nav.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}

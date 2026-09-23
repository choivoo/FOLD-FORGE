package com.foldforge.studio.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldforge.studio.FoldForgeApp
import com.foldforge.studio.core.ui.theme.Forge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Page(val title: String, val body: String, val icon: ImageVector)

private val PAGES = listOf(
    Page("BUILD", "Describe a game or app. The AI agent plans it, writes the files and runs it — on your phone.", Icons.Filled.AutoAwesome),
    Page("CODE", "A real code editor with syntax highlighting, search, Git, live preview and an automated gameplay QA agent.", Icons.Filled.Code),
    Page("FOLD", "Folded: one focused workspace. Unfolded: files, code and preview side by side. Tabletop: play on top, controls below.", Icons.Filled.Devices),
)

/** Three-page onboarding. [onDone] receives the id of the demo project if one was created. */
@Composable
fun OnboardingScreen(onDone: (String?) -> Unit) {
    val container = (LocalContext.current.applicationContext as FoldForgeApp).container
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var createDemo by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }

    fun finish() {
        if (working) return
        working = true
        scope.launch {
            container.settings.update { it.copy(onboardingDone = true) }
            val demo = if (createDemo) runCatching { container.projects.create("Forge Runner", "forge-runner").id }.getOrNull() else null
            withContext(Dispatchers.Main) { onDone(demo) }
        }
    }

    Box(Modifier.fillMaxSize().background(Forge.colors.background).safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("FOLD FORGE", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Forge.colors.accent, letterSpacing = 3.sp)
            Text("AI DEVELOPMENT WORKSTATION", fontSize = 11.sp, color = Forge.colors.muted, letterSpacing = 2.sp)
            Spacer(Modifier.height(40.dp))
            AnimatedContent(targetState = page, label = "onboarding") { i ->
                val p = PAGES[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(88.dp).clip(CircleShape).background(Forge.colors.panelAlt), contentAlignment = Alignment.Center) {
                        Icon(p.icon, contentDescription = null, tint = Forge.colors.accent, modifier = Modifier.size(44.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(p.title, fontSize = 26.sp, fontWeight = FontWeight.Black, color = Forge.colors.text, letterSpacing = 4.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(p.body, color = Forge.colors.muted, textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PAGES.indices.forEach { i ->
                    Box(Modifier.size(if (i == page) 22.dp else 8.dp, 8.dp).clip(CircleShape).background(if (i == page) Forge.colors.accent else Forge.colors.border))
                }
            }
            Spacer(Modifier.height(28.dp))
            if (page == PAGES.lastIndex) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = createDemo, onCheckedChange = { createDemo = it })
                    Text("Create the Forge Runner demo project", color = Forge.colors.text, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = ::finish, enabled = !working, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("start_building")) {
                    Text("START BUILDING", fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { page = PAGES.lastIndex }) { Text("Skip") }
                    Button(onClick = { page++ }) { Text("Next") }
                }
            }
        }
    }
}

package com.foldforge.studio.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.foldforge.studio.core.editor.TokenType
import com.foldforge.studio.data.settings.ThemeMode

/** IDE-specific colours beyond Material roles: panel layers, syntax, status. */
@Immutable
data class ForgeColors(
    val background: Color,
    val panel: Color,
    val panelAlt: Color,
    val border: Color,
    val gutter: Color,
    val gutterText: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accent2: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val selection: Color,
    val searchHit: Color,
    val bracket: Color,
    val syntax: Map<TokenType, Color>,
    val isDark: Boolean,
)

private val darkSyntax = mapOf(
    TokenType.KEYWORD to Color(0xFFFF8A3D), TokenType.TYPE to Color(0xFF57E3FF), TokenType.STRING to Color(0xFF9BE58A),
    TokenType.NUMBER to Color(0xFFD7A8FF), TokenType.COMMENT to Color(0xFF6B7385), TokenType.TAG to Color(0xFFFF6F91),
    TokenType.ATTRIBUTE to Color(0xFFFFC66D), TokenType.PUNCTUATION to Color(0xFF9AA3B5), TokenType.FUNCTION to Color(0xFF7FB8FF),
    TokenType.PROPERTY to Color(0xFF8FD3FF), TokenType.OPERATOR to Color(0xFFC0C7D6), TokenType.HEADING to Color(0xFFFF8A3D),
    TokenType.EMPHASIS to Color(0xFFD7A8FF), TokenType.CODE to Color(0xFF9BE58A), TokenType.LINK to Color(0xFF57E3FF),
    TokenType.CONSTANT to Color(0xFFD7A8FF),
)
private val lightSyntax = mapOf(
    TokenType.KEYWORD to Color(0xFFB4480F), TokenType.TYPE to Color(0xFF00708A), TokenType.STRING to Color(0xFF2E7D32),
    TokenType.NUMBER to Color(0xFF7B3FB0), TokenType.COMMENT to Color(0xFF7A808C), TokenType.TAG to Color(0xFFB0224A),
    TokenType.ATTRIBUTE to Color(0xFF8A6100), TokenType.PUNCTUATION to Color(0xFF555C68), TokenType.FUNCTION to Color(0xFF1F5FB8),
    TokenType.PROPERTY to Color(0xFF155E8C), TokenType.OPERATOR to Color(0xFF444A55), TokenType.HEADING to Color(0xFFB4480F),
    TokenType.EMPHASIS to Color(0xFF7B3FB0), TokenType.CODE to Color(0xFF2E7D32), TokenType.LINK to Color(0xFF00708A),
    TokenType.CONSTANT to Color(0xFF7B3FB0),
)

val ForgeDark = ForgeColors(
    background = Color(0xFF0E1015), panel = Color(0xFF151820), panelAlt = Color(0xFF1B1F29), border = Color(0xFF262B37),
    gutter = Color(0xFF12151B), gutterText = Color(0xFF5B6376), text = Color(0xFFE6E9F0), muted = Color(0xFF8B93A7),
    accent = Color(0xFFFF8A3D), accent2 = Color(0xFF57E3FF), success = Color(0xFF7CFFB2), warning = Color(0xFFFFD34D),
    error = Color(0xFFFF4D6D), selection = Color(0x553D7DFF), searchHit = Color(0x66FFD34D), bracket = Color(0x5557E3FF),
    syntax = darkSyntax, isDark = true,
)

val ForgeOled = ForgeDark.copy(
    background = Color(0xFF000000), panel = Color(0xFF07080A), panelAlt = Color(0xFF0E1014), border = Color(0xFF22262F),
    gutter = Color(0xFF000000),
)

val ForgeLight = ForgeColors(
    background = Color(0xFFF3F4F7), panel = Color(0xFFFFFFFF), panelAlt = Color(0xFFF7F8FA), border = Color(0xFFDDE1E8),
    gutter = Color(0xFFF1F3F6), gutterText = Color(0xFF9097A6), text = Color(0xFF151822), muted = Color(0xFF5B6275),
    accent = Color(0xFFD8621A), accent2 = Color(0xFF0089A8), success = Color(0xFF1B8F4E), warning = Color(0xFFB38600),
    error = Color(0xFFC62848), selection = Color(0x443D7DFF), searchHit = Color(0x66FFD34D), bracket = Color(0x4400A0C0),
    syntax = lightSyntax, isDark = false,
)

val LocalForgeColors = staticCompositionLocalOf { ForgeDark }

val CodeFont = FontFamily.Monospace

private fun scheme(c: ForgeColors): ColorScheme = if (c.isDark) {
    darkColorScheme(
        primary = c.accent, onPrimary = Color(0xFF1A0E05), secondary = c.accent2, onSecondary = Color(0xFF001F26),
        background = c.background, onBackground = c.text, surface = c.panel, onSurface = c.text,
        surfaceVariant = c.panelAlt, onSurfaceVariant = c.muted, outline = c.border, outlineVariant = c.border,
        error = c.error, surfaceContainer = c.panel, surfaceContainerHigh = c.panelAlt, surfaceContainerLow = c.background,
        surfaceContainerHighest = c.panelAlt, surfaceContainerLowest = c.background,
    )
} else {
    lightColorScheme(
        primary = c.accent, onPrimary = Color.White, secondary = c.accent2, onSecondary = Color.White,
        background = c.background, onBackground = c.text, surface = c.panel, onSurface = c.text,
        surfaceVariant = c.panelAlt, onSurfaceVariant = c.muted, outline = c.border, outlineVariant = c.border, error = c.error,
    )
}

private val ForgeTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.6.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
)

@Composable
fun FoldForgeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val colors = when (mode) {
        ThemeMode.FORGE_DARK -> ForgeDark
        ThemeMode.OLED -> ForgeOled
        ThemeMode.LIGHT -> ForgeLight
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) ForgeDark else ForgeLight
    }
    CompositionLocalProvider(LocalForgeColors provides colors) {
        MaterialTheme(colorScheme = scheme(colors), typography = ForgeTypography, content = content)
    }
}

object Forge {
    val colors: ForgeColors @Composable get() = LocalForgeColors.current
}

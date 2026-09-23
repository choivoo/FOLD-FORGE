package com.foldforge.studio.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldforge.studio.core.model.FileKind
import com.foldforge.studio.core.ui.theme.Forge

@Composable
fun PanelHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Forge.colors.panelAlt)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Forge.colors.muted)
        if (subtitle != null) {
            Spacer(Modifier.width(8.dp))
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Forge.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun ToolIcon(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = Forge.colors.text) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else Forge.colors.muted.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

fun badgeColor(kind: FileKind): Color = when (kind) {
    FileKind.HTML -> Color(0xFFFF6F91)
    FileKind.CSS -> Color(0xFF57A6FF)
    FileKind.JS -> Color(0xFFFFD34D)
    FileKind.TS -> Color(0xFF3D8BFF)
    FileKind.JSON -> Color(0xFFD7A8FF)
    FileKind.MARKDOWN -> Color(0xFF9AA3B5)
    FileKind.KOTLIN -> Color(0xFFB57BFF)
    FileKind.XML -> Color(0xFFFF9F5A)
    FileKind.GRADLE -> Color(0xFF7CD5B0)
    FileKind.GLSL -> Color(0xFF57E3FF)
    FileKind.IMAGE -> Color(0xFF7CFFB2)
    FileKind.MODEL3D -> Color(0xFFFF8A3D)
    FileKind.AUDIO -> Color(0xFFFFB0E0)
    FileKind.TEXT, FileKind.BINARY -> Color(0xFF8B93A7)
}

@Composable
fun FileBadge(kind: FileKind, modifier: Modifier = Modifier) {
    val c = badgeColor(kind)
    Box(
        modifier
            .width(38.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(c.copy(alpha = 0.16f))
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(kind.badge, fontSize = 9.sp, fontWeight = FontWeight.Black, color = c, maxLines = 1)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Forge.colors.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Forge.colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Forge.colors.muted, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Forge.colors.muted, modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null) {
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 13.sp, maxLines = 1)
    }
    if (primary) {
        Button(onClick, modifier.sizeIn(minHeight = 40.dp), enabled = enabled, contentPadding = ButtonDefaults.ContentPadding, content = content)
    } else {
        OutlinedButton(onClick, modifier.sizeIn(minHeight = 40.dp), enabled = enabled, content = content)
    }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit) = TextButton(onClick) { Text(text, fontSize = 13.sp) }

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Forge.colors.panel)
            .border(1.dp, Forge.colors.border, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
    ) { content() }
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, color = Forge.colors.muted, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(value, color = Forge.colors.text, fontSize = 12.sp, modifier = Modifier.semantics { contentDescription = "$key $value" })
    }
}

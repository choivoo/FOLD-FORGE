package com.foldforge.studio.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foldforge.studio.core.editor.EditorOps
import com.foldforge.studio.core.editor.Highlighter
import com.foldforge.studio.core.editor.SearchMatch
import com.foldforge.studio.core.editor.TokenType
import com.foldforge.studio.core.ui.theme.CodeFont
import com.foldforge.studio.core.ui.theme.Forge
import com.foldforge.studio.core.ui.theme.ForgeColors
import com.foldforge.studio.feature.workspace.EditorDoc
import kotlinx.coroutines.flow.distinctUntilChanged

private const val MAX_HIGHLIGHT_CHARS = 400_000

/** Syntax + search + bracket highlighting as a pure visual layer (offsets unchanged). */
private class HighlightTransformation(
    private val colors: ForgeColors,
    private val doc: EditorDoc,
    private val matches: List<SearchMatch>,
    private val activeMatch: Int,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val b = AnnotatedString.Builder(raw)
        if (raw.length <= MAX_HIGHLIGHT_CHARS) {
            for (t in Highlighter.highlight(raw, doc.language)) {
                if (t.end > raw.length) continue
                val color = colors.syntax[t.type] ?: continue
                val style = when (t.type) {
                    TokenType.COMMENT -> SpanStyle(color = color, fontStyle = FontStyle.Italic)
                    TokenType.HEADING, TokenType.KEYWORD -> SpanStyle(color = color, fontWeight = FontWeight.SemiBold)
                    else -> SpanStyle(color = color)
                }
                b.addStyle(style, t.start, t.end)
            }
        }
        matches.forEachIndexed { i, m ->
            if (m.end <= raw.length) b.addStyle(SpanStyle(background = if (i == activeMatch) colors.accent.copy(alpha = 0.55f) else colors.searchHit), m.start, m.end)
        }
        val sel = doc.value.selection
        if (sel.collapsed) {
            EditorOps.matchBracket(raw, sel.start)?.let { (a, c) ->
                if (a in raw.indices) b.addStyle(SpanStyle(background = colors.bracket), a, a + 1)
                if (c in raw.indices) b.addStyle(SpanStyle(background = colors.bracket), c, c + 1)
            }
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
fun CodeEditor(
    doc: EditorDoc,
    fontSize: Int,
    matches: List<SearchMatch>,
    activeMatch: Int,
    pendingLine: Int?,
    onLineConsumed: () -> Unit,
    onChange: (TextFieldValue) -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Forge.colors
    val lineHeight = (fontSize * 1.55f).sp
    val style = TextStyle(
        fontFamily = CodeFont, fontSize = fontSize.sp, lineHeight = lineHeight, color = colors.text,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    val vScroll = rememberScrollState(doc.scrollY)
    val hScroll = rememberScrollState(doc.scrollX)
    val density = LocalDensity.current
    val lineCount = remember(doc.value.text) { EditorOps.lineCount(doc.value.text) }
    val gutterText = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val transformation = remember(doc.value, matches, activeMatch, colors) { HighlightTransformation(colors, doc, matches, activeMatch) }

    LaunchedEffect(vScroll) { snapshotFlow { vScroll.value }.distinctUntilChanged().collect { doc.scrollY = it } }
    LaunchedEffect(hScroll) { snapshotFlow { hScroll.value }.distinctUntilChanged().collect { doc.scrollX = it } }
    LaunchedEffect(pendingLine, doc.path) {
        val line = pendingLine ?: return@LaunchedEffect
        val px = with(density) { lineHeight.toPx() } * (line - 1)
        vScroll.animateScrollTo((px - 120).toInt().coerceAtLeast(0))
        onLineConsumed()
    }

    BoxWithConstraints(modifier.fillMaxSize().background(colors.background)) {
        val viewportWidth = maxWidth
        Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
            Text(
                gutterText,
                style = style.copy(color = colors.gutterText, textAlign = TextAlign.End, fontSize = (fontSize - 1).sp),
                modifier = Modifier
                    .background(colors.gutter)
                    .fillMaxHeight()
                    .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 200.dp)
                    .semantics { contentDescription = "Line numbers" },
            )
            Box(Modifier.horizontalScroll(hScroll)) {
                BasicTextField(
                    value = doc.value,
                    onValueChange = onChange,
                    textStyle = style,
                    readOnly = doc.readOnly,
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = transformation,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier
                        .defaultMinSize(minWidth = viewportWidth)
                        .padding(start = 8.dp, end = 48.dp, top = 6.dp, bottom = 200.dp)
                        .testTag("code_editor")
                        .semantics { contentDescription = "Code editor for ${doc.path}" }
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) {
                                if (e.isShiftPressed) onOutdent() else onIndent()
                                true
                            } else false
                        },
                )
            }
        }
    }
}

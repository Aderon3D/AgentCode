package com.agent.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tiny markdown renderer for assistant replies: fenced code, headings,
 * bullets, quotes, bold/italic, inline code, links.
 *
 * ponytail: no tables, nesting or html — add when a real reply needs them.
 */
@Composable
internal fun MarkdownText(text: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parseMarkdown(text).forEach { block -> MarkdownBlockView(block, color) }
    }
}

internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Bullet(val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
}

private val BULLET = Regex("^([-*+]|\\d{1,2}[.)])\\s+")
// A heading needs whitespace after the hashes; "#hashtag" is text.
private val HEADING_PREFIX = Regex("^#{1,6}\\s")

/** Block-level parse; a blank line closes a paragraph. Pure — unit-tested. */
internal fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").split('\n')
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        when {
            t.startsWith("```") -> {
                flush()
                val lang = t.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out += MdBlock.Code(lang, code.toString().trimEnd())
            }
            line.isBlank() -> flush()
            t.startsWith("#") && HEADING_PREFIX.containsMatchIn(t) -> {
                flush()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                out += MdBlock.Heading(level, t.removePrefix("#".repeat(level)).trim())
            }
            t.startsWith(">") -> {
                flush()
                out += MdBlock.Quote(t.removePrefix(">").trim())
            }
            BULLET.containsMatchIn(t) -> {
                flush()
                val m = BULLET.find(t)!!
                val mark = m.groupValues[1]
                out += MdBlock.Bullet(
                    if (mark[0].isDigit()) mark else "•",
                    t.substring(m.range.last + 1).trim(),
                )
            }
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
        i++
    }
    flush()
    return out
}

/** Inline spans: **bold**, `code`, [label](url), *italic*. */
internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '[' -> {
                val close = text.indexOf("](", i)
                val end = if (close > i) text.indexOf(')', close + 2) else -1
                if (close > i && end > close) {
                    withStyle(SpanStyle(color = Color(0xFF8AB4F8))) { append(text.substring(i + 1, close)) }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MdBlock, color: Color) {
    when (block) {
        is MdBlock.Paragraph -> Text(
            inlineMarkdown(block.text),
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = color),
        )
        is MdBlock.Heading -> Text(
            inlineMarkdown(block.text),
            fontWeight = FontWeight.Bold,
            fontSize = when (block.level) { 1 -> 17.sp; 2 -> 15.sp; else -> 14.sp },
            color = color,
            modifier = Modifier.padding(top = 2.dp),
        )
        is MdBlock.Code -> Text(
            block.code,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(8.dp),
        )
        is MdBlock.Bullet -> Row {
            Text(
                block.marker,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = color,
                modifier = Modifier.width(16.dp),
            )
            Text(
                inlineMarkdown(block.text),
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = color),
            )
        }
        is MdBlock.Quote -> Text(
            "▎ ${block.text}",
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

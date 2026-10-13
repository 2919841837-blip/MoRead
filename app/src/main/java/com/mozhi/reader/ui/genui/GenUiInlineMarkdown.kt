package com.mozhi.reader.ui.genui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * 元素内文字的行内 Markdown：`**粗**`、`*斜*`、`` `代码` ``、`~~删除~~`、`[文字](链接)`。
 * 只处理行内记号；块级 Markdown 走宿主的富文本（`#md` 节点）。
 * `snake_case` 里的下划线不当作斜体：只有两侧不是字母数字时才算记号。
 */
object GenUiInlineMarkdown {

    data class Styles(val code: SpanStyle, val link: SpanStyle)

    fun append(builder: AnnotatedString.Builder, text: String, styles: Styles) {
        var i = 0
        val n = text.length
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                builder.append(plain.toString())
                plain.setLength(0)
            }
        }
        while (i < n) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < n -> {
                    plain.append(text[i + 1])
                    i += 2
                }
                (text.startsWith("**", i) || text.startsWith("__", i)) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i + 2) {
                        flush()
                        builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(this, text.substring(i + 2, end), styles) }
                        i = end + 2
                    } else {
                        plain.append(marker)
                        i += 2
                    }
                }
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        flush()
                        builder.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(this, text.substring(i + 2, end), styles) }
                        i = end + 2
                    } else {
                        plain.append("~~")
                        i += 2
                    }
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        builder.withStyle(styles.code) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }
                (c == '*' || c == '_') && isEmphasisStart(text, i) -> {
                    val end = findEmphasisEnd(text, i + 1, c)
                    if (end > i + 1) {
                        flush()
                        builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(this, text.substring(i + 1, end), styles) }
                        i = end + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }
                c == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close > 0) text.indexOf(')', close + 2) else -1
                    if (close > i && end > close) {
                        flush()
                        builder.withStyle(styles.link) { append(text.substring(i + 1, close)) }
                        i = end + 1
                    } else {
                        plain.append(c)
                        i++
                    }
                }
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flush()
    }

    fun parse(text: String, styles: Styles): AnnotatedString = buildAnnotatedString { append(this, text, styles) }

    private fun isEmphasisStart(text: String, i: Int): Boolean {
        val next = text.getOrNull(i + 1) ?: return false
        if (next.isWhitespace() || next == text[i]) return false
        val prev = text.getOrNull(i - 1)
        return text[i] == '*' || prev == null || !prev.isLetterOrDigit()
    }

    private fun findEmphasisEnd(text: String, from: Int, marker: Char): Int {
        var j = from
        while (j < text.length) {
            if (text[j] == marker && !text[j - 1].isWhitespace()) {
                val after = text.getOrNull(j + 1)
                if (marker == '*' || after == null || !after.isLetterOrDigit()) return j
            }
            j++
        }
        return -1
    }

    fun defaultStyles(ink: Color, accent: Color) = Styles(
        code = SpanStyle(fontFamily = FontFamily.Monospace, background = ink.copy(alpha = 0.08f)),
        link = SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
    )
}

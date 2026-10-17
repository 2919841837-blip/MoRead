package com.mozhi.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * 手机版 Markdown 组件：表格不再按固定列宽硬挤，而是一行表格渲染成一张小卡。
 *
 * 渲染库默认每列 160dp、超出气泡就被裁掉，四列表格在手机上基本只看得到第一列。
 * 这里把表头当作字段名、把每个单元格竖排成「字段：内容」，长中文会自动折行，
 * 不丢内容，也不需要左右滑。其余 Markdown 元素仍走渲染库默认实现。
 *
 * 颜色与字号由调用方传入：聊天气泡要跟阅读主题走，书籍详情则跟 Material 主题走。
 */
@Composable
internal fun phoneMarkdownComponents(
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    dividerColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    titleStyle: TextStyle = MaterialTheme.typography.titleSmall,
    bodyStyle: TextStyle = MaterialTheme.typography.bodySmall
): MarkdownComponents = remember(textColor, labelColor, containerColor, dividerColor, titleStyle, bodyStyle) {
    markdownComponents(table = { model ->
        PhoneMarkdownTable(
            model = model,
            textColor = textColor,
            labelColor = labelColor,
            containerColor = containerColor,
            dividerColor = dividerColor,
            titleStyle = titleStyle,
            bodyStyle = bodyStyle
        )
    })
}

@Composable
private fun PhoneMarkdownTable(
    model: MarkdownComponentModel,
    textColor: Color,
    labelColor: Color,
    containerColor: Color,
    dividerColor: Color,
    titleStyle: TextStyle,
    bodyStyle: TextStyle
) {
    val (header, rows) = remember(model.node, model.content) {
        parseTable(model.node, model.content)
    } ?: return
    if (rows.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
    ) {
        rows.forEachIndexed { index, cells ->
            if (index > 0) {
                HorizontalDivider(color = dividerColor)
            }
            TableRecord(
                header = header,
                cells = cells,
                textColor = textColor,
                labelColor = labelColor,
                titleStyle = titleStyle,
                bodyStyle = bodyStyle
            )
        }
    }
}

@Composable
private fun TableRecord(
    header: List<String>,
    cells: List<String>,
    textColor: Color,
    labelColor: Color,
    titleStyle: TextStyle,
    bodyStyle: TextStyle
) {
    val title = plainInline(cells.firstOrNull().orEmpty())
    val firstField = if (title.isNotBlank()) 1 else 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (title.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = titleStyle,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
            }
        }
        for (index in firstField until cells.size) {
            val value = plainInline(cells[index])
            if (value.isBlank()) continue
            val label = header.getOrNull(index)?.let(::plainInline).orEmpty()
            Text(
                text = buildAnnotatedString {
                    if (label.isNotBlank()) {
                        withStyle(SpanStyle(color = labelColor)) {
                            append(label)
                            append("：")
                        }
                    }
                    append(value)
                },
                style = bodyStyle,
                color = textColor
            )
        }
    }
}

/** 只取表头和表体行；GFM 的分隔行（|---|）不是表格内容，直接忽略。 */
internal fun parseTable(node: ASTNode, content: String): Pair<List<String>, List<List<String>>>? {
    val hasHeader = node.children.any { it.type == GFMElementTypes.HEADER }
    val rows = node.children
        .filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
        .map { it.cells(content) }
        .filter { it.isNotEmpty() }
    if (rows.isEmpty()) return null
    return if (hasHeader) rows.first() to rows.drop(1) else emptyList<String>() to rows
}

private fun ASTNode.cells(content: String): List<String> = children
    .filter { it.type == GFMTokenTypes.CELL }
    .map { cell ->
        val start = cell.startOffset.coerceIn(0, content.length)
        val end = cell.endOffset.coerceIn(start, content.length)
        content.substring(start, end).trim().trim('|').trim()
    }

private val inlineLink = Regex("""\[([^\]]*)]\([^)]*\)""")
private val inlineCode = Regex("`([^`]*)`")

/** 卡片里用普通 Text 显示，去掉会直接露出来的行内 Markdown 记号。 */
private fun plainInline(raw: String): String = raw
    .replace("\\|", "|")
    .replace(inlineLink, "$1")
    .replace(inlineCode, "$1")
    .replace("**", "")
    .replace("__", "")
    .replace("~~", "")
    .trim()

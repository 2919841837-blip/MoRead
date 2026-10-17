package com.mozhi.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.theme.moReadMetrics
import com.mozhi.reader.ui.theme.fieldContainerColor
import com.mozhi.reader.ui.theme.sectionHairline

/**
 * 输入框里的渲染期标记（人物编辑器用它表示「本次更新新增」）。
 *
 * [offset] 是**原始文本**里的下标，标记只出现在渲染结果里，不会进入 value：
 * 用户输入、退格、复制与保存都看不到它，所以不需要任何清洗步骤。
 */
data class TextMarker(val offset: Int)

private const val MarkerGlyph = "★"

/**
 * 在若干原始锚点前插入 [MarkerGlyph]：按锚点顺序逐段拼出渲染文本，原文的标注一并保留。
 * [MarkerOffsetMapping] 把标记的整个视觉区间折回同一个原始锚点，光标可以停在锚点两侧，
 * 但不会停进标记内部（也就没有「退格删一半」）。
 */
internal class MarkerTransformation(private val offsets: List<Int>, private val style: SpanStyle) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val anchors = offsets.map { it.coerceIn(0, text.length) }.distinct().sorted()
        if (anchors.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val transformed = buildAnnotatedString {
            var cursor = 0
            anchors.forEach { anchor ->
                append(text.subSequence(cursor, anchor))
                append(MarkerGlyph)
                addStyle(style, length - MarkerGlyph.length, length)
                cursor = anchor
            }
            append(text.subSequence(cursor, text.length))
        }
        return TransformedText(transformed, MarkerOffsetMapping(anchors, text.length))
    }
}

internal class MarkerOffsetMapping(private val anchors: List<Int>, private val originalLength: Int) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val safe = offset.coerceIn(0, originalLength)
        return safe + anchors.count { it <= safe } * MarkerGlyph.length
    }

    override fun transformedToOriginal(offset: Int): Int {
        var inserted = 0
        anchors.forEach { anchor ->
            val start = anchor + inserted
            if (offset <= start) return (offset - inserted).coerceIn(0, originalLength)
            if (offset < start + MarkerGlyph.length) return anchor
            inserted += MarkerGlyph.length
        }
        return (offset - inserted).coerceIn(0, originalLength)
    }
}

/** 命中的是哪个标记的原始锚点；文本已经溢出（内部可滚动）时坐标对不上，干脆不响应。 */
private fun markerAt(layout: TextLayoutResult, markers: List<TextMarker>, position: Offset): TextMarker? {
    if (layout.hasVisualOverflow) return null
    val offset = layout.getOffsetForPosition(position)
    val anchors = markers.map { it.offset }.distinct().sorted()
    var inserted = 0
    anchors.forEach { anchor ->
        val start = anchor + inserted
        if (offset in start until start + MarkerGlyph.length) return markers.firstOrNull { it.offset == anchor }
        inserted += MarkerGlyph.length
    }
    return null
}

/**
 * 项目里唯一的文本输入控件。
 *
 * 全库有 98 个裸 `OutlinedTextField`：M3 的描边框 + 浮动 label 是 Material 自己的语言，
 * 和这里「素面卡 + 发丝线 + 安静留白」的语言对不上；十几个描边框叠在一张卡里，
 * 每个框都在喊「我是个框」，页面就散了。
 *
 * 这里改成：label 平铺在输入区**上方**（不浮动、不遮挡、不动画），输入区是一块填充底、
 * 无描边；聚焦时只加一条强调色发丝边。说明文字在下方。
 */
@Composable
fun MoReadTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** 渲染期标记：只插入视觉层，不进入 [value]。 */
    markers: List<TextMarker> = emptyList(),
    /** 点按标记；为 null 时标记仅作提示。 */
    onMarkerClick: ((TextMarker) -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentMarkers by rememberUpdatedState(markers)
    val currentMarkerClick by rememberUpdatedState(onMarkerClick)
    val markerStyle = SpanStyle(color = MaterialTheme.colorScheme.primary,
        background = MaterialTheme.colorScheme.primaryContainer)
    val transformation = remember(markers, visualTransformation, markerStyle) {
        if (markers.isEmpty()) visualTransformation else MarkerTransformation(markers.map { it.offset }, markerStyle)
    }
    val markerTap = if (markers.isEmpty() || onMarkerClick == null) Modifier else Modifier.pointerInput(markers) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val result = textLayout ?: return@awaitEachGesture
            val marker = markerAt(result, currentMarkers, down.position) ?: return@awaitEachGesture
            // 标记上也可能起手滑动列表：位移超过 touchSlop 就不算点按。
            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial) ?: return@awaitEachGesture
            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            up.consume()
            currentMarkerClick?.invoke(marker)
        }
    }
    val fieldShape = moReadMetrics().fieldShape
    val borderColor = when {
        isError -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> sectionHairline()
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(fieldShape)
                .background(fieldContainerColor())
                .border(if (focused || isError) 1.dp else 0.5.dp, borderColor, fieldShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(18.dp)
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && !placeholder.isNullOrEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    minLines = minLines,
                    keyboardOptions = keyboardOptions,
                    visualTransformation = transformation,
                    onTextLayout = { textLayout = it },
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyMedium.copy(color = contentColor)
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 20.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .then(markerTap)
                )
            }
            if (trailing != null) {
                Box(modifier = Modifier.padding(start = 8.dp)) { trailing() }
            }
        }
        if (!supporting.isNullOrBlank()) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 2.dp, top = 6.dp)
            )
        }
    }
}

/** 表单区：若干输入项之间的统一纵向节奏，避免每处各写各的 spacedBy。 */
@Composable
fun MoReadFieldGroup(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) = Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    content = content
)

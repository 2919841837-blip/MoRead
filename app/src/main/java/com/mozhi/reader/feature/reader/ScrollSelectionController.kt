package com.mozhi.reader.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mozhi.reader.feature.reader.engine.dragSelectionHandle
import com.mozhi.reader.feature.reader.engine.hitTextPos
import com.mozhi.reader.feature.reader.engine.selectionBodyRange
import com.mozhi.reader.feature.reader.engine.selectionRects
import com.mozhi.reader.feature.reader.engine.frameToPhysical
import com.mozhi.reader.feature.reader.engine.textPosAtBodyOffset
import com.mozhi.reader.feature.reader.engine.wordSelectionAt

/** 滚动模式长按选词：用章节正文偏移保存范围，因此可跨多个排版页。 */
internal class ScrollSelectionController(
    private val holder: ScrollPaneHolder,
    private val onSelectionStarted: () -> Unit,
    private val onSelectionChanged: () -> Unit
) {
    data class ActiveSelection(
        val chapterIndex: Int,
        val anchorStartOffset: Int,
        val anchorEndOffset: Int,
        val startOffset: Int,
        val endOffset: Int,
        val dragging: Boolean
    ) {
        val bodyRange: IntRange get() = startOffset..endOffset
    }

    private enum class Handle { START, END }

    var active by mutableStateOf<ActiveSelection?>(null)
        private set
    var magnifierCenter by mutableStateOf<Offset?>(null)
        private set

    private var draggedHandle: Handle? = null

    val isActive: Boolean get() = active != null

    fun begin(position: Offset): Boolean {
        val hit = holder.resolve(position) ?: return false
        val pos = hit.page.hitTextPos(hit.local.x, hit.local.y, exact = true) ?: return false
        val word = hit.page.wordSelectionAt(pos)
        val range = hit.page.selectionBodyRange(word.first, word.second)
        draggedHandle = null
        active = ActiveSelection(
            chapterIndex = hit.chapterIndex,
            anchorStartOffset = range.first,
            anchorEndOffset = range.last,
            startOffset = range.first,
            endOffset = range.last,
            dragging = true
        )
        magnifierCenter = position
        onSelectionStarted()
        onSelectionChanged()
        return true
    }

    fun grabHandle(position: Offset, radiusPx: Float): Boolean {
        val current = active ?: return false
        val startCenter = endpointCenter(current.chapterIndex, current.startOffset, startSide = true)
        val endCenter = endpointCenter(current.chapterIndex, current.endOffset, startSide = false)
        val startDistance = startCenter?.let { (position - it).getDistance() } ?: Float.MAX_VALUE
        val endDistance = endCenter?.let { (position - it).getDistance() } ?: Float.MAX_VALUE
        draggedHandle = when {
            startDistance > radiusPx && endDistance > radiusPx -> return false
            startDistance <= endDistance -> Handle.START
            else -> Handle.END
        }
        magnifierCenter = position
        active = current.copy(dragging = true)
        onSelectionChanged()
        return true
    }

    fun drag(position: Offset) {
        magnifierCenter = position
        val current = active ?: return
        val hit = holder.resolve(position) ?: return
        if (hit.chapterIndex != current.chapterIndex) return
        val pos = hit.page.hitTextPos(hit.local.x, hit.local.y, exact = false) ?: return
        val hitRange = hit.page.selectionBodyRange(pos, pos)
        val handle = draggedHandle
        val next = if (handle != null) {
            val moved = dragSelectionHandle(
                start = current.startOffset,
                end = current.endOffset,
                hit = if (handle == Handle.START) hitRange.first else hitRange.last,
                draggingStart = handle == Handle.START
            )
            draggedHandle = if (moved.draggingStart) Handle.START else Handle.END
            current.copy(startOffset = moved.start, endOffset = moved.end)
        } else {
            when {
                hitRange.first < current.anchorStartOffset -> current.copy(
                    startOffset = hitRange.first,
                    endOffset = current.anchorEndOffset
                )
                hitRange.last > current.anchorEndOffset -> current.copy(
                    startOffset = current.anchorStartOffset,
                    endOffset = hitRange.last
                )
                else -> current.copy(
                    startOffset = current.anchorStartOffset,
                    endOffset = current.anchorEndOffset
                )
            }
        }
        if (next.startOffset != current.startOffset || next.endOffset != current.endOffset) {
            active = next
            onSelectionChanged()
        }
    }

    fun end() {
        draggedHandle = null
        magnifierCenter = null
        active = active?.copy(dragging = false)
        onSelectionChanged()
    }

    fun clear() {
        draggedHandle = null
        magnifierCenter = null
        active = null
        onSelectionChanged()
    }

    fun selectedText(): String {
        val current = active ?: return ""
        val body = holder.chapterBody(current.chapterIndex) ?: return ""
        val start = current.startOffset.coerceIn(0, body.length)
        val endExclusive = (current.endOffset + 1).coerceIn(start, body.length)
        return body.substring(start, endExclusive).replace('\uFFFC'.toString(), "［图片］")
    }

    fun bodyRange(): IntRange? = active?.bodyRange

    fun drawHighlight(drawScope: DrawScope, palette: ReaderPalette) {
        val current = active ?: return
        val pages = holder.visiblePages(current.chapterIndex)
        val color = palette.accent.copy(alpha = if (palette.isDark) 0.18f else 0.30f)
        pages.forEach { visible ->
            visible.page.selectionRects(current.bodyRange).map(visible.page::frameToPhysical).forEach { rect ->
                drawScope.drawRect(
                    color = color,
                    topLeft = Offset(visible.origin.x + rect.left, visible.origin.y + rect.top),
                    size = Size(rect.right - rect.left, rect.bottom - rect.top)
                )
            }
        }
        endpointGeometry(current.chapterIndex, current.startOffset, startSide = true)?.let { endpoint ->
            drawHandle(drawScope, palette, endpoint.top, endpoint.center)
        }
        endpointGeometry(current.chapterIndex, current.endOffset, startSide = false)?.let { endpoint ->
            drawHandle(drawScope, palette, endpoint.top, endpoint.center)
        }
    }

    /** [top] and [center] are the handle bar's two ends; the knob hangs past [center]. */
    private data class Endpoint(val center: Offset, val top: Offset)

    private fun endpointCenter(chapterIndex: Int, offset: Int, startSide: Boolean): Offset? =
        endpointGeometry(chapterIndex, offset, startSide)?.center

    private fun endpointGeometry(chapterIndex: Int, offset: Int, startSide: Boolean): Endpoint? {
        holder.visiblePages(chapterIndex).forEach { visible ->
            val pageEnd = visible.page.chapterPosition + visible.page.charLength
            if (offset !in visible.page.chapterPosition until pageEnd) return@forEach
            val pos = visible.page.textPosAtBodyOffset(offset) ?: return@forEach
            val line = visible.page.lines[pos.lineIndex]
            val column = line.columns[pos.columnIndex]
            val x = if (startSide) column.start else column.end
            val (topX, topY) = visible.page.frameToPhysical(x, line.lineTop)
            val (bottomX, bottomY) = visible.page.frameToPhysical(x, line.lineBottom)
            return Endpoint(
                center = Offset(visible.origin.x + bottomX, visible.origin.y + bottomY),
                top = Offset(visible.origin.x + topX, visible.origin.y + topY)
            )
        }
        return null
    }

    private fun drawHandle(
        drawScope: DrawScope,
        palette: ReaderPalette,
        top: Offset,
        bottom: Offset
    ) = with(drawScope) {
        val barWidth = 2.dp.toPx()
        val radius = 6.dp.toPx()
        drawLine(color = palette.accent, start = top, end = bottom, strokeWidth = barWidth)
        val length = (bottom - top).getDistance().coerceAtLeast(1f)
        drawCircle(
            color = palette.accent,
            radius = radius,
            center = bottom + (bottom - top) / length * (radius * 0.8f)
        )
    }

    fun toolbarTop(current: ActiveSelection, density: Density): Int {
        val rects = holder.visiblePages(current.chapterIndex)
            .flatMap { visible ->
                visible.page.selectionRects(current.bodyRange).map { rect -> visible.page.frameToPhysical(rect) to visible.origin }
            }
        val gap = with(density) { 12.dp.toPx() }
        val barHeight = with(density) { 48.dp.toPx() }
        val first = rects.minByOrNull { (rect, origin) -> origin.y + rect.top } ?: return 0
        val above = first.second.y + first.first.top - gap - barHeight
        if (above > with(density) { 72.dp.toPx() }) return above.toInt()
        val last = rects.maxByOrNull { (rect, origin) -> origin.y + rect.bottom } ?: return 0
        return (last.second.y + last.first.bottom + gap).toInt()
    }
}

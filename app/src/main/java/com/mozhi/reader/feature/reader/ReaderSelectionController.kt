package com.mozhi.reader.feature.reader

import android.os.SystemClock
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.engine.dragSelectionHandle
import com.mozhi.reader.feature.reader.engine.hitTextPos
import com.mozhi.reader.feature.reader.engine.selectionBodyRange
import com.mozhi.reader.feature.reader.engine.selectionRects
import com.mozhi.reader.feature.reader.engine.frameToPhysical
import com.mozhi.reader.feature.reader.engine.textPosAtBodyOffset
import com.mozhi.reader.feature.reader.engine.wordSelectionAt

/**
 * Long-press selection over the current page, Legado's `ContentTextView` selection reduced to the
 * single-page case: the long press seeds a word, dragging extends either side of it.
 */
internal class ReaderSelectionController(
    private val controller: ReaderContentController,
    private val holder: ReaderPaneHolder,
    private val onSelectionStarted: () -> Unit
) : SelectionGestureHooks {

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
    private var preserveNextPageChange = false
    private var lastAutoTurnAt = 0L
    private var edgeEnteredAt = 0L
    private var edgeDirection: PageTurnDirection? = null

    override val isActive: Boolean get() = active != null

    override fun begin(position: Offset): Boolean {
        val pageHit = holder.hitAt(position) ?: return false
        val laid = pageHit.page
        val local = pageHit.local
        val hit = laid.page.hitTextPos(local.x, local.y, exact = true) ?: return false
        val word = laid.page.wordSelectionAt(hit)
        val range = laid.page.selectionBodyRange(word.first, word.second)
        draggedHandle = null
        active = ActiveSelection(
            chapterIndex = laid.chapterIndex,
            anchorStartOffset = range.first,
            anchorEndOffset = range.last,
            startOffset = range.first,
            endOffset = range.last,
            dragging = true
        )
        magnifierCenter = position
        onSelectionStarted()
        return true
    }

    override fun grabHandle(position: Offset, radiusPx: Float): Boolean {
        val current = active ?: return false
        val pages = holder.visiblePages().filter { it.first.chapterIndex == current.chapterIndex }
        val startCenter = pages.firstNotNullOfOrNull { (laid, origin) ->
            endpointCenter(laid.page, current.startOffset, origin, startSide = true)
        }
        val endCenter = pages.firstNotNullOfOrNull { (laid, origin) ->
            endpointCenter(laid.page, current.endOffset, origin, startSide = false)
        }
        val startDistance = startCenter?.let { (position - it).getDistance() } ?: Float.MAX_VALUE
        val endDistance = endCenter?.let { (position - it).getDistance() } ?: Float.MAX_VALUE
        draggedHandle = when {
            startDistance > radiusPx && endDistance > radiusPx -> return false
            startDistance <= endDistance -> Handle.START
            else -> Handle.END
        }
        magnifierCenter = position
        active = current.copy(dragging = true)
        return true
    }

    override fun drag(position: Offset) {
        magnifierCenter = position
        val current = active ?: return
        if (autoTurnAtEdge(position, current)) return
        val pageHit = holder.hitAt(position) ?: return
        val laid = pageHit.page
        if (laid.chapterIndex != current.chapterIndex) return
        val local = pageHit.local
        val hit = laid.page.hitTextPos(local.x, local.y, exact = false) ?: return
        val hitRange = laid.page.selectionBodyRange(hit, hit)
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
        }
    }

    private fun autoTurnAtEdge(position: Offset, current: ActiveSelection): Boolean {
        val now = SystemClock.uptimeMillis()
        val horizontal = holder.spread != null
        val extent = if (horizontal) holder.viewWidth.toFloat() else holder.viewHeight.toFloat()
        val coordinate = if (horizontal) position.x else position.y
        val edge = extent * AUTO_TURN_EDGE_FRACTION
        val direction = when {
            coordinate <= edge -> PageTurnDirection.PREVIOUS
            coordinate >= extent - edge -> PageTurnDirection.NEXT
            else -> {
                edgeDirection = null
                edgeEnteredAt = 0L
                return false
            }
        }
        if (edgeDirection != direction) {
            edgeDirection = direction
            edgeEnteredAt = now
            return false
        }
        if (now - edgeEnteredAt < AUTO_TURN_DWELL_MS ||
            now - lastAutoTurnAt < AUTO_TURN_COOLDOWN_MS
        ) return false
        val candidate = when (direction) {
            PageTurnDirection.PREVIOUS -> if (controller.spreadMode) controller.prevSpread().first else controller.prevPage()
            PageTurnDirection.NEXT -> if (controller.spreadMode) controller.nextSpread().first else controller.nextPage()
        } as? RenderPage.Laid ?: return false
        if (candidate.chapterIndex != current.chapterIndex) return false
        preserveNextPageChange = true
        val moved = when (direction) {
            PageTurnDirection.PREVIOUS -> controller.moveToPrevPage()
            PageTurnDirection.NEXT -> controller.moveToNextPage()
        }
        if (!moved) preserveNextPageChange = false
        lastAutoTurnAt = now
        edgeEnteredAt = now
        return moved
    }

    fun consumePreservedPageChange(): Boolean {
        val preserve = preserveNextPageChange
        preserveNextPageChange = false
        return preserve
    }

    override fun end() {
        draggedHandle = null
        magnifierCenter = null
        edgeDirection = null
        edgeEnteredAt = 0L
        active = active?.copy(dragging = false)
    }

    override fun clear() {
        draggedHandle = null
        magnifierCenter = null
        edgeDirection = null
        edgeEnteredAt = 0L
        active = null
    }

    fun selectedText(): String {
        val current = active ?: return ""
        val body = controller.chapterBody(current.chapterIndex) ?: return ""
        val start = current.startOffset.coerceIn(0, body.length)
        val endExclusive = (current.endOffset + 1).coerceIn(start, body.length)
        return body.substring(start, endExclusive)
            .replace('\uFFFC'.toString(), "［图片］")
    }

    fun bodyRange(): IntRange? = active?.bodyRange

    fun drawHighlight(drawScope: DrawScope, palette: ReaderPalette) {
        val current = active ?: return
        for ((laid, origin) in holder.visiblePages()) {
            if (laid.chapterIndex != current.chapterIndex) continue
            val rects = laid.page.selectionRects(current.bodyRange).map(laid.page::frameToPhysical)
            val color = palette.accent.copy(alpha = if (palette.isDark) 0.18f else 0.30f)
            for (rect in rects) {
                drawScope.drawRect(
                    color = color,
                    topLeft = Offset(origin.x + rect.left, origin.y + rect.top),
                    size = Size(rect.right - rect.left, rect.bottom - rect.top)
                )
            }
            handleBar(laid.page, current.startOffset, origin, startSide = true)?.let { (top, bottom) ->
                drawHandle(drawScope, palette, top, bottom)
            }
            handleBar(laid.page, current.endOffset, origin, startSide = false)?.let { (top, bottom) ->
                drawHandle(drawScope, palette, top, bottom)
            }
        }
    }

    private fun endpointCenter(
        page: com.mozhi.reader.feature.reader.engine.TextPage,
        offset: Int,
        origin: Offset,
        startSide: Boolean
    ): Offset? = handleBar(page, offset, origin, startSide)?.second

    /** Screen-space ends of a selection handle's bar; the knob hangs past the second end. */
    private fun handleBar(
        page: com.mozhi.reader.feature.reader.engine.TextPage,
        offset: Int,
        origin: Offset,
        startSide: Boolean
    ): Pair<Offset, Offset>? {
        val pageEnd = page.chapterPosition + page.charLength
        if (offset !in page.chapterPosition until pageEnd) return null
        val pos = page.textPosAtBodyOffset(offset) ?: return null
        val line = page.lines[pos.lineIndex]
        val column = line.columns[pos.columnIndex]
        val x = if (startSide) column.start else column.end
        // 竖排页的行是物理上的列：柄随之横躺，圆头落在列的左侧。
        val (topX, topY) = page.frameToPhysical(x, line.lineTop)
        val (bottomX, bottomY) = page.frameToPhysical(x, line.lineBottom)
        return Offset(origin.x + topX, origin.y + topY) to Offset(origin.x + bottomX, origin.y + bottomY)
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
        val (laid, origin) = holder.visiblePages().firstOrNull {
            it.first.page.selectionRects(current.bodyRange).isNotEmpty()
        } ?: return 0
        val rects = laid.page.selectionRects(current.bodyRange).map(laid.page::frameToPhysical)
        val gap = with(density) { 12.dp.toPx() }
        val barHeight = with(density) { 48.dp.toPx() }
        if (rects.isEmpty()) return 0
        val above = origin.y + rects.minOf { it.top } - gap - barHeight
        if (above > origin.y * 0.4f) return above.toInt()
        return (origin.y + rects.maxOf { it.bottom } + gap).toInt()
    }

    private companion object {
        const val AUTO_TURN_EDGE_FRACTION = 0.07f
        const val AUTO_TURN_DWELL_MS = 620L
        const val AUTO_TURN_COOLDOWN_MS = 1100L
    }
}

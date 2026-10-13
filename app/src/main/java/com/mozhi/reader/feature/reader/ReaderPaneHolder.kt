package com.mozhi.reader.feature.reader

import android.content.Intent
import android.graphics.Bitmap
import android.os.BatteryManager
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.mozhi.reader.feature.reader.engine.TransientHighlightSpan
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark
import com.mozhi.reader.feature.reader.engine.ReaderPageLink
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.engine.hitTextPos
import com.mozhi.reader.feature.reader.engine.inlineMarkerLayout
import com.mozhi.reader.feature.reader.engine.ReaderPageImage
import com.mozhi.reader.feature.reader.engine.imageAt
import com.mozhi.reader.feature.reader.engine.ImmersiveArtworkFit
import com.mozhi.reader.feature.reader.engine.linkAt
import com.mozhi.reader.feature.reader.engine.ReaderVisibleReadSnapshot
import com.mozhi.reader.feature.reader.engine.frameToPhysical
import com.mozhi.reader.feature.reader.engine.physicalToFrame
import com.mozhi.reader.feature.reader.engine.lineExtent
import com.mozhi.reader.feature.reader.engine.charOffsetOf
import com.mozhi.reader.feature.reader.render.PageBitmapRenderer
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import com.mozhi.reader.feature.reader.render.SpreadGeometry
import com.mozhi.reader.feature.reader.render.Leaf
import com.mozhi.reader.feature.reader.render.PageHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class ReaderPaneHolder(private val controller: ReaderContentController) {
    var viewWidth = 0
        private set
    var viewHeight = 0
        private set
    var backgroundColor: Int = android.graphics.Color.TRANSPARENT
        private set

    private val mirrorSlots = arrayOfNulls<Bitmap>(2)
    private val mirrorSources = arrayOfNulls<Bitmap>(2)
    private val mirrorGenerations = IntArray(2)

    /** A horizontally flipped copy of [source] for right-to-left turns, reused while it is unchanged. */
    fun mirrored(source: Bitmap, slot: Int): Bitmap {
        val cached = mirrorSlots[slot]
        if (cached != null && !cached.isRecycled && mirrorSources[slot] === source &&
            mirrorGenerations[slot] == source.generationId) return cached
        val target = cached?.takeIf { !it.isRecycled && it.width == source.width && it.height == source.height }
            ?: Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { cached?.recycle() }
        target.eraseColor(android.graphics.Color.TRANSPARENT)
        android.graphics.Canvas(target).apply {
            scale(-1f, 1f, source.width / 2f, 0f)
            drawBitmap(source, 0f, 0f, null)
        }
        mirrorSlots[slot] = target
        mirrorSources[slot] = source
        mirrorGenerations[slot] = source.generationId
        return target
    }

    val compositor = PageTurnCompositor()

    var style: ReaderPageStyle? = null
        private set
    private var renderer: PageBitmapRenderer? = null
    private var includeBackgroundInPages = true
    private var appliedStyleKey: ReaderRenderStyleKey? = null
    private var appliedEnvironmentGeneration: Int? = null
    private var backgroundReadyCallback: (() -> Unit)? = null
    private var renderRevision = 0L
    private var timeText: String = timeFormat.format(Date())
    private var batteryPercent: Int = 100
    private var annotations: List<ReaderAnnotationMark> = emptyList()
    private var illustrations: List<ReaderIllustrationMark> = emptyList()
    private var transientHighlight: TransientHighlightSpan? = null
    private var dirty = true
    private var preparedTurn: PageTurnDirection? = null
    private var forwardTurn: com.mozhi.reader.feature.reader.engine.ReaderTurnSnapshot? = null
    private var backwardTurn: com.mozhi.reader.feature.reader.engine.ReaderTurnSnapshot? = null
    var followRequest: ReaderPageTurnRequest? = null

    private fun followResult(request: ReaderPageTurnRequest, committed: Boolean) = if (request.isValid?.invoke() == false) {
        ReaderTurnResult.CANCELLED
    } else readerFollowTurnResult(
        committed, request.navigationGeneration, controller.navigationGeneration,
        request.sourceGeneration, controller.sourceGeneration
    )

    fun followWasSuperseded(): Boolean = followRequest?.let {
        followResult(it, false) == ReaderTurnResult.CANCELLED
    } == true

    fun finishFollowTurn(committed: Boolean = false) {
        val request = followRequest ?: return
        followRequest = null
        val result = followResult(request, committed)
        if (result == ReaderTurnResult.COMMITTED) {
            request.focusTarget?.let { (chapter, offset) ->
                if (controller.chapterIndex == chapter) controller.focus(offset)
            }
        }
        request.onFinished?.invoke(result)
    }

    private val renderedSnapshots = java.util.IdentityHashMap<Bitmap, ReaderVisibleReadSnapshot>()
    private val renderedPages = java.util.IdentityHashMap<Bitmap, List<RenderPage>>()
    private val renderedRevisions = java.util.IdentityHashMap<Bitmap, Long>()
    private val renderedAnnotations = java.util.IdentityHashMap<Bitmap, List<ReaderAnnotationMark>>()
    private var pendingDrawnSnapshot: ReaderVisibleReadSnapshot? = null

    private fun recordRendered(bitmap: Bitmap, pages: List<RenderPage>, replaced: Bitmap? = null) {
        if (replaced != null && replaced !== bitmap) {
            renderedPages.remove(replaced)
            renderedSnapshots.remove(replaced)
            renderedRevisions.remove(replaced)
            renderedAnnotations.remove(replaced)
            if (!replaced.isRecycled) replaced.recycle()
        }
        renderedPages[bitmap] = pages.toList()
        renderedRevisions[bitmap] = renderRevision
        renderedAnnotations[bitmap] = annotations
        val snapshot = controller.captureVisibleRead(pages)
        if (snapshot == null) renderedSnapshots.remove(bitmap) else renderedSnapshots[bitmap] = snapshot
    }

    /** Called only after drawBitmap of a non-animating current frame, never during prefilling. */
    fun onCurrentBitmapDrawn(isSettled: () -> Boolean, onVisible: (ReaderVisibleReadSnapshot) -> Unit) {
        if (!isSettled()) return
        val bitmap = curBitmap ?: return
        if (bitmap.width != viewWidth || bitmap.height != viewHeight) return
        val captured = renderedSnapshots[bitmap] ?: return
        val drawn = controller.rebaseDrawnSnapshot(captured) ?: return
        if (pendingDrawnSnapshot == drawn) return
        pendingDrawnSnapshot = drawn
        mainHandler.post {
            if (pendingDrawnSnapshot != drawn) return@post
            pendingDrawnSnapshot = null
            if (isSettled() && bitmap === curBitmap && !bitmap.isRecycled &&
                controller.isCurrentVisibleRead(drawn)
            ) onVisible(drawn)
        }
    }

    var spread: SpreadGeometry? = null
        private set
    /** 邻页渲染已排到下一帧、尚未执行。 */
    private var pendingNeighbors = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    var curBitmap: Bitmap? = null
        private set
    private var nextBitmap: Bitmap? = null
    private var prevBitmap: Bitmap? = null

    fun setViewport(width: Int, height: Int): Boolean {
        if (width == viewWidth && height == viewHeight) return false
        viewWidth = width
        viewHeight = height
        return true
    }

    /** Returning from chat with an identical key does not resolve fonts, decode or typeset. */
    fun applyStyle(
        key: ReaderRenderStyleKey,
        createStyle: () -> ReaderPageStyle,
        spread: SpreadGeometry?,
        includeBackgroundInPages: Boolean,
        onBackgroundReady: () -> Unit
    ): Boolean {
        backgroundReadyCallback = onBackgroundReady
        updateTime()
        // The scroll surface can replace the controller environment while this cache is detached.
        val relayout = appliedStyleKey?.environment != key.environment ||
            appliedEnvironmentGeneration != controller.environmentGeneration
        if (appliedStyleKey != key || renderer == null) {
            val nextStyle = createStyle()
            appliedStyleKey = key
            style = nextStyle
            renderedSnapshots.clear()
            renderedPages.clear()
            renderedRevisions.clear()
            renderedAnnotations.clear()
            pendingDrawnSnapshot = null
            this.spread = spread
            this.includeBackgroundInPages = includeBackgroundInPages
            backgroundColor = nextStyle.backgroundColor
            renderer?.release()
            renderer = PageBitmapRenderer(nextStyle).also { next ->
                // This closure owns only the holder. A disposed Compose scope is never retained.
                next.prepareBackground {
                    invalidatePages()
                    backgroundReadyCallback?.invoke()
                }
            }
            invalidatePages()
        } else {
            setIncludeBackgroundInPages(includeBackgroundInPages)
        }
        if (relayout) {
            val currentStyle = checkNotNull(style)
            controller.updateEnvironment(currentStyle.spec, currentStyle.measure, spread != null)
            appliedEnvironmentGeneration = controller.environmentGeneration
        }
        return relayout
    }

    private fun invalidatePages() {
        renderRevision++
        dirty = true
    }

    fun drawBackground(canvas: android.graphics.Canvas, width: Float, height: Float) {
        renderer?.drawBackdrop(canvas, width, height) ?: canvas.drawColor(backgroundColor)
    }

    fun embedsBackgroundInPages(): Boolean = includeBackgroundInPages

    fun setIncludeBackgroundInPages(value: Boolean): Boolean {
        if (includeBackgroundInPages == value) return false
        includeBackgroundInPages = value
        invalidatePages()
        return true
    }

    fun setAnnotations(value: List<ReaderAnnotationMark>): Boolean {
        if (annotations == value) return false
        annotations = value.toList()
        invalidatePages()
        return true
    }

    fun setIllustrations(value: List<ReaderIllustrationMark>): Boolean {
        if (illustrations == value) return false
        illustrations = value
        invalidatePages()
        return true
    }

    fun setTransientHighlight(value: TransientHighlightSpan?): Boolean {
        if (transientHighlight == value) return false
        transientHighlight = value
        invalidatePages()
        return true
    }

    private fun contentOrigin(page: RenderPage.Laid, leaf: Leaf = Leaf.LEFT): Offset {
        val currentStyle = style ?: return Offset.Zero
        val reclaimsHeader = page.page.immersive || page.page.hideHeader
        return Offset(
            (spread?.originX(leaf) ?: 0f) + currentStyle.paddingLeft,
            if (reclaimsHeader) currentStyle.immersiveContentTop else currentStyle.contentTop
        )
    }

    fun visiblePages(): List<Pair<RenderPage.Laid, Offset>> {
        if (spread == null) {
            val laid = controller.curPage() as? RenderPage.Laid ?: return emptyList()
            return listOf(laid to contentOrigin(laid))
        }
        val pages = controller.curSpread()
        return listOfNotNull(
            (pages.first as? RenderPage.Laid)?.let { it to contentOrigin(it, Leaf.LEFT) },
            (pages.second as? RenderPage.Laid)?.let { it to contentOrigin(it, Leaf.RIGHT) }
        )
    }

    /** Returned local coordinates are content-local, including per-page hidden-header policy. */
    fun hitAt(position: Offset): PageHit? {
        val geometry = spread
        val hit = if (geometry != null) geometry.hitAt(position, controller.curSpread()) else {
            val page = controller.curPage() as? RenderPage.Laid ?: return null
            PageHit(Leaf.LEFT, page, position)
        } ?: return null
        val physical = position - contentOrigin(hit.page, hit.leaf)
        // 之后的命中、选区、链接、批注都按页面自己的坐标系查询；竖排页在这里转进旋转坐标系。
        val (x, y) = hit.page.page.physicalToFrame(physical.x, physical.y)
        return hit.copy(local = Offset(x, y))
    }

    fun englishWordAt(position: Offset): com.mozhi.reader.core.dictionary.DictionaryLookupHit? {
        val hit = hitAt(position) ?: return null
        val pos = hit.page.page.hitTextPos(hit.local.x, hit.local.y, exact = true) ?: return null
        val offset = hit.page.page.charOffsetOf(pos)
        val body = controller.chapterBody(hit.page.chapterIndex) ?: return null
        return com.mozhi.reader.core.dictionary.EnglishWords.at(body, offset, hit.page.chapterIndex)
    }

    fun imageAt(position: Offset): ReaderPageImage? {
        val currentStyle = style ?: return null
        val hit = hitAt(position) ?: return null
        val page = hit.page.page
        val pageX = position.x - (spread?.originX(hit.leaf) ?: 0f)
        if (pageX !in 0f..currentStyle.viewWidth.toFloat() || position.y < 0f ||
            position.y >= currentStyle.immersiveContentBottom) return null
        // The paged painter refits single full-page artwork to the screen, not the text box.
        val artwork = if (page.immersive && (page.fullPageArtwork || page.lines.singleOrNull()?.inlineImage != null)) {
            ImmersiveArtworkFit.singleArtwork(page.lines)
        } else null
        if (artwork != null) {
            val rect = ImmersiveArtworkFit.fit(artwork.width, artwork.height,
                currentStyle.viewWidth.toFloat(), currentStyle.immersiveContentBottom)
            return if (pageX >= rect.left && pageX < rect.left + rect.width &&
                position.y >= rect.top && position.y < rect.top + rect.height) {
                ReaderPageImage(artwork.imagePath, hit.page.chapterIndex, page.chapterPosition, artwork.altText)
            } else null
        }
        val (physicalX, physicalY) = page.frameToPhysical(hit.local.x, hit.local.y)
        if (!page.immersive && (physicalX < 0f || physicalX >= currentStyle.contentWidth ||
                physicalY < 0f || physicalY >= currentStyle.spec.visibleHeight)) return null
        return page.imageAt(hit.local.x, hit.local.y, hit.page.chapterIndex)
    }

    fun linkAt(position: Offset): ReaderPageLink? {
        val hit = hitAt(position) ?: return null
        val page = hit.page
        val local = hit.local
        return page.page.linkAt(local.x, local.y, page.chapterIndex)
    }

    fun annotationIdsAt(position: Offset): List<Long> {
        val currentStyle = style ?: return emptyList()
        val hit = hitAt(position) ?: return emptyList()
        val page = hit.page
        val local = hit.local
        // 热区与实际显示的批注版本一致，异步结果在翻页落定前不能提前变成可点击入口。
        val shownAnnotations = curBitmap?.let(renderedAnnotations::get) ?: annotations
        val markerRadius = (currentStyle.tipSizePx * 0.72f).coerceAtLeast(8f)
        val geometry = page.page.inlineMarkerLayout(
            annotations = shownAnnotations.filter { it.chapterIndex == page.chapterIndex },
            illustrations = illustrations.filter { it.chapterIndex == page.chapterIndex },
            markerRadius = markerRadius,
            markerGap = markerRadius * com.mozhi.reader.feature.reader.engine.INLINE_MARKER_GAP_RATIO,
            maxRight = page.page.lineExtent(currentStyle.contentWidth, currentStyle.spec.visibleHeight)
        )
        return geometry.annotationIdsAt(local.x, local.y)
    }

    fun illustrationIdsAt(position: Offset): List<Long> {
        val currentStyle = style ?: return emptyList()
        val hit = hitAt(position) ?: return emptyList()
        val page = hit.page
        val local = hit.local
        val markerRadius = (currentStyle.tipSizePx * 0.72f).coerceAtLeast(8f)
        val markers = page.page.inlineMarkerLayout(
            annotations = annotations.filter { it.chapterIndex == page.chapterIndex },
            illustrations = illustrations.filter { it.chapterIndex == page.chapterIndex },
            markerRadius = markerRadius,
            markerGap = markerRadius * com.mozhi.reader.feature.reader.engine.INLINE_MARKER_GAP_RATIO,
            maxRight = page.page.lineExtent(currentStyle.contentWidth, currentStyle.spec.visibleHeight)
        )
        val hitRadius = (currentStyle.tipSizePx * 1.35f).coerceAtLeast(18f)
        return markers.markers.firstOrNull { marker ->
            if (marker.illustrationIds.isEmpty()) return@firstOrNull false
            val dx = local.x - marker.centerX
            val dy = local.y - marker.centerY
            dx * dx + dy * dy <= hitRadius * hitRadius
        }?.illustrationIds.orEmpty()
    }

    fun bitmapFor(direction: PageTurnDirection?, front: Boolean): Bitmap? = when (direction) {
        PageTurnDirection.NEXT -> if (front) curBitmap else nextBitmap
        PageTurnDirection.PREVIOUS -> if (front) prevBitmap else curBitmap
        null -> curBitmap
    }?.takeUnless(Bitmap::isRecycled)

    fun ensureFresh() {
        if (dirty) refresh(0)
        // 翻页真要用到邻页了，把推迟的那两张立刻补上。
        if (pendingNeighbors) renderNeighborsNow()
    }

    fun startTurn() {
        ensureFresh()
        val source = curBitmap?.let(renderedPages::get)
        val next = nextBitmap?.let(renderedPages::get)
        val previous = prevBitmap?.let(renderedPages::get)
        forwardTurn = if (source != null && next != null) controller.captureTurn(true, source, next) else null
        backwardTurn = if (source != null && previous != null) controller.captureTurn(false, source, previous) else null
    }

    private fun frozenTurn(direction: PageTurnDirection) = if (direction == PageTurnDirection.NEXT) forwardTurn else backwardTurn

    fun commitTurn(direction: PageTurnDirection): Boolean =
        followRequest?.isValid?.invoke() != false && frozenTurn(direction)?.let(controller::commitTurn) == true

    fun prepareTurn(direction: PageTurnDirection) {
        preparedTurn = direction
    }

    fun cancelPreparedTurn() {
        preparedTurn = null
    }

    fun hasPreparedTurn(): Boolean = preparedTurn != null

    fun refresh(relativePosition: Int) {
        val renderer = renderer ?: return
        val turn = preparedTurn
        if (relativePosition == 0 && turn != null) {
            preparedTurn = null
            rotateAndRenderNeighbor(renderer, turn)
            dirty = false
            return
        }
        when (relativePosition) {
            -1 -> prevBitmap = renderPage(renderer, prevBitmap, RelativePage.PREV)
            1 -> nextBitmap = renderPage(renderer, nextBitmap, RelativePage.NEXT)
            else -> {
                curBitmap = renderPage(renderer, curBitmap, RelativePage.CUR)
                // 邻页推到下一帧：一张全屏 ARGB_8888 就是十来兆，进书时三张连着画
                // 必然顶掉首帧。邻页在翻页前不上屏，晚一帧毫无代价。
                scheduleNeighbors()
            }
        }
        // A neighbor-only load must not consume a pending repaint of the current page.
        if (relativePosition == 0) dirty = false
    }

    private fun scheduleNeighbors() {
        if (pendingNeighbors) return
        pendingNeighbors = true
        mainHandler.post { if (pendingNeighbors) renderNeighborsNow() }
    }

    private fun renderNeighborsNow() {
        pendingNeighbors = false
        val renderer = renderer ?: return
        nextBitmap = renderPage(renderer, nextBitmap, RelativePage.NEXT)
        prevBitmap = renderPage(renderer, prevBitmap, RelativePage.PREV)
    }

    private fun rotateAndRenderNeighbor(
        renderer: PageBitmapRenderer,
        direction: PageTurnDirection
    ) {
        val rotated = rotatePageWindow(prevBitmap, curBitmap, nextBitmap, direction)
        prevBitmap = rotated.previous
        curBitmap = rotated.current
        nextBitmap = rotated.next
        // Usually a cache hit; repaint if marks/background changed during the active turn.
        curBitmap = renderPage(renderer, curBitmap, RelativePage.CUR)
        when (direction) {
            PageTurnDirection.NEXT -> {
                nextBitmap = renderPage(renderer, rotated.reusable, RelativePage.NEXT)
            }
            PageTurnDirection.PREVIOUS -> {
                prevBitmap = renderPage(renderer, rotated.reusable, RelativePage.PREV)
            }
        }
    }

    private enum class RelativePage { PREV, CUR, NEXT }

    private fun renderPage(
        renderer: PageBitmapRenderer,
        into: Bitmap?,
        which: RelativePage
    ): Bitmap {
        val geometry = spread
        val pageSpread = if (geometry == null) null else when (which) {
            RelativePage.PREV -> controller.prevSpread()
            RelativePage.CUR -> controller.curSpread()
            RelativePage.NEXT -> controller.nextSpread()
        }
        val pages = pageSpread?.toList() ?: listOf(when (which) {
            RelativePage.PREV -> controller.prevPage()
            RelativePage.CUR -> controller.curPage()
            RelativePage.NEXT -> controller.nextPage()
        })
        if (into != null && !into.isRecycled && into.width == viewWidth && into.height == viewHeight &&
            renderedRevisions[into] == renderRevision && renderedPages[into] == pages
        ) {
            // Rebase the read watermark if an identical page survived a navigation generation.
            recordRendered(into, pages)
            return into
        }
        if (geometry != null && pageSpread != null) {
            return renderer.renderSpread(
                pages = pageSpread, into = into, geometry = geometry,
                bookProgress = (pageSpread.first as? RenderPage.Laid)?.let {
                    controller.progressAt(it.chapterIndex, it.page.chapterPosition)
                } ?: controller.bookProgress(),
                timeText = timeText, batteryPercent = batteryPercent,
                annotations = annotations, illustrations = illustrations,
                transientHighlight = transientHighlight, includeBackground = includeBackgroundInPages
            ).also { recordRendered(it, pages, into) }
        }
        val page = pages.single()
        val progress = when (page) {
            is RenderPage.Laid -> controller.progressAt(page.chapterIndex, page.page.chapterPosition)
            else -> controller.bookProgress()
        }
        return renderer.render(
            page = page, into = into, bookProgress = progress,
            timeText = timeText, batteryPercent = batteryPercent,
            annotations = annotations.filter { it.chapterIndex == page.chapterIndex },
            illustrations = illustrations.filter { it.chapterIndex == page.chapterIndex },
            transientHighlight = transientHighlight?.takeIf { it.chapterIndex == page.chapterIndex },
            includeBackground = includeBackgroundInPages
        ).also { recordRendered(it, pages, into) }
    }

    fun updateTime(): Boolean {
        val next = timeFormat.format(Date())
        if (next == timeText) return false
        timeText = next
        invalidatePages()
        return true
    }

    fun updateBattery(intent: Intent): Boolean {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return false
        val next = (level * 100) / scale
        if (next == batteryPercent) return false
        batteryPercent = next
        invalidatePages()
        return true
    }

    fun detach() {
        finishFollowTurn()
        preparedTurn = null
        forwardTurn = null
        backwardTurn = null
        backgroundReadyCallback = null
        pendingDrawnSnapshot = null
        pendingNeighbors = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun release() {
        detach()
        compositor.release()
        mirrorSlots.forEachIndexed { index, bitmap ->
            bitmap?.recycle()
            mirrorSlots[index] = null
            mirrorSources[index] = null
        }
        renderer?.release()
        renderer = null
        style = null
        appliedStyleKey = null
        appliedEnvironmentGeneration = null
        dirty = true
        curBitmap?.recycle()
        nextBitmap?.recycle()
        prevBitmap?.recycle()
        curBitmap = null
        nextBitmap = null
        prevBitmap = null
        pendingDrawnSnapshot = null
        renderedSnapshots.clear()
        renderedPages.clear()
        renderedRevisions.clear()
        renderedAnnotations.clear()
    }

    private companion object {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.ROOT)
    }
}

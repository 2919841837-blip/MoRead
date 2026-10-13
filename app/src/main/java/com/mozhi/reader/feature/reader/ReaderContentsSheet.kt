package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.R
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.ui.components.blockSheetDrag
import kotlinx.coroutines.launch

/**
 * 目录页签：顶部章节节律条（宽度即章节长短，读过填墨、当前章朱砂）、贯穿全书的一根进度线
 * （读过实心点、未读空心点）、当前章是一张带「继续」的卡，右缘刻度条拖动快速定位，
 * 底部悬浮「回到当前章 / 正序倒序」。
 */
@Composable
internal fun ContentsSheet(
    chapters: List<ChapterEntity>,
    tocEntries: List<BookTocEntryEntity>,
    currentChapterIndex: Int,
    palette: ReaderPalette,
    onChapterClick: (Int, String) -> Unit,
    onContinue: () -> Unit = {}
) {
    val tocItems = remember(chapters, tocEntries) { buildReaderTocItems(chapters, tocEntries) }
    var collapsedOrders by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var reversed by rememberSaveable { mutableStateOf(false) }
    val collapsedOrderIndices = collapsedOrders.toSet()
    val visibleItems = remember(tocItems, collapsedOrderIndices) {
        visibleReaderTocItems(tocItems, collapsedOrderIndices)
    }
    val displayItems = remember(visibleItems, reversed) { if (reversed) visibleItems.asReversed() else visibleItems }
    val currentOrder = remember(tocItems, currentChapterIndex) { currentReaderTocOrder(tocItems, currentChapterIndex) }
    val forwardIndex = currentReaderTocListIndex(tocItems, visibleItems, currentChapterIndex)
    val currentListIndex = if (reversed) visibleItems.lastIndex - forwardIndex else forwardIndex
    val volumes = remember(tocItems) { readerTocVolumes(tocItems) }
    val rhythm = remember(chapters, volumes) { buildChapterRhythm(chapters, volumes) }
    // 点节律条的一段：列表滚过去，目标章那一行亮一下再淡掉，段本身也短暂抬高——手指知道点中了哪。
    var flashChapter by remember { mutableStateOf<Int?>(null) }
    val flash = remember { Animatable(0f) }
    val chapterChars = remember(chapters) { chapters.associate { it.chapterIndex to it.charCount } }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentListIndex - 2).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    fun scrollToChapter(chapterIndex: Int) {
        val index = displayItems.indexOfFirst { (it.chapterIndex ?: -1) >= chapterIndex && !it.hasChildren }
            .takeIf { it >= 0 } ?: return
        scope.launch { listState.animateScrollToItem((index - 2).coerceAtLeast(0)) }
    }

    Column(modifier = Modifier.fillMaxHeight()) {
        if (rhythm.isNotEmpty()) {
            ChapterRhythmBar(
                segments = rhythm,
                volumes = volumes,
                currentChapterIndex = currentChapterIndex,
                palette = palette,
                flashChapter = { flashChapter },
                flashProgress = { flash.value },
                onSegmentClick = { segment ->
                    scrollToChapter(segment.firstChapter)
                    flashChapter = segment.firstChapter
                    scope.launch {
                        flash.snapTo(1f)
                        delay(FLASH_HOLD_MS)
                        flash.animateTo(0f, tween(FLASH_FADE_MS))
                    }
                },
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 10.dp)
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .blockSheetDrag(listState)
                    .testTag("contents-list"),
                contentPadding = PaddingValues(start = 12.dp, end = 30.dp, bottom = 96.dp)
            ) {
                itemsIndexed(displayItems, key = { _, item -> item.key }) { index, item ->
                    if (item.hasChildren) {
                        VolumeRow(
                            item = item,
                            childCount = tocItems.count { it.parentOrderIndex == item.orderIndex },
                            expanded = item.orderIndex !in collapsedOrderIndices,
                            current = item.orderIndex == currentOrder,
                            palette = palette,
                            onToggle = { collapsedOrders = collapsedOrderIndices.toggle(item.orderIndex).toList() },
                            onOpen = { item.chapterIndex?.let { onChapterClick(it, item.href) } }
                        )
                    } else {
                        val state = when {
                            item.orderIndex == currentOrder -> TocRowState.CURRENT
                            (item.chapterIndex ?: Int.MAX_VALUE) < currentChapterIndex -> TocRowState.READ
                            else -> TocRowState.UNREAD
                        }
                        ChapterRow(
                            item = item,
                            state = state,
                            charCount = item.chapterIndex?.let(chapterChars::get),
                            first = index == 0,
                            last = index == displayItems.lastIndex,
                            palette = palette,
                            flash = { if (item.chapterIndex != null && item.chapterIndex == flashChapter) flash.value else 0f },
                            onClick = { item.chapterIndex?.let { onChapterClick(it, item.href) } },
                            onContinue = onContinue
                        )
                    }
                }
            }
            if (displayItems.size > SCRUBBER_MIN_ITEMS) {
                TocScrubber(
                    items = displayItems,
                    palette = palette,
                    firstVisible = { listState.firstVisibleItemIndex },
                    onScrub = { index -> scope.launch { listState.scrollToItem(index) } },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 8.dp, bottom = 96.dp)
                )
            }
            TocFloatingBar(
                reversed = reversed,
                palette = palette,
                onLocate = { scope.launch { listState.animateScrollToItem((currentListIndex - 2).coerceAtLeast(0)) } },
                onToggleOrder = {
                    reversed = !reversed
                    scope.launch { listState.scrollToItem(0) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
            )
        }
    }
}

private enum class TocRowState { READ, CURRENT, UNREAD }

/** 节律条各段在给定宽度下的横向位置；绘制、点击与卷名标注共用，三者永远对齐。 */
private fun rhythmLayout(segments: List<ChapterRhythmSegment>, width: Float, gap: Float, volumeGap: Float): List<Pair<Float, Float>> {
    val gaps = gap * (segments.size - 1) + volumeGap * segments.drop(1).count { it.startsVolume }
    val usable = (width - gaps).coerceAtLeast(segments.size.toFloat())
    var x = 0f
    return segments.mapIndexed { index, segment ->
        if (index > 0) x += gap + if (segment.startsVolume) volumeGap else 0f
        val w = (segment.weight * usable).coerceAtLeast(1f)
        (x to w).also { x += w }
    }
}

@Composable
private fun ChapterRhythmBar(
    segments: List<ChapterRhythmSegment>,
    volumes: List<RhythmVolume>,
    currentChapterIndex: Int,
    palette: ReaderPalette,
    flashChapter: () -> Int?,
    flashProgress: () -> Float,
    onSegmentClick: (ChapterRhythmSegment) -> Unit,
    modifier: Modifier = Modifier
) {
    val ink = palette.onBackground
    val seal = palette.seal
    val unread = palette.onBackground.copy(alpha = if (palette.isDark) 0.18f else 0.14f)
    val density = LocalDensity.current
    val gap = with(density) { 2.dp.toPx() }
    val volumeGap = with(density) { 7.dp.toPx() }
    var pressed by remember { mutableStateOf<Int?>(null) }
    val press = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val layout = remember(segments, widthPx) { rhythmLayout(segments, widthPx, gap, volumeGap) }
        fun indexAt(x: Float): Int? = layout.indexOfFirst { (start, w) -> x <= start + w + gap / 2f }
            .takeIf { it >= 0 } ?: layout.lastIndex.takeIf { it >= 0 }
        Column {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .pointerInput(segments, layout) {
                        detectTapGestures(
                            onPress = { offset ->
                                pressed = indexAt(offset.x)
                                scope.launch { press.animateTo(1f, tween(140)) }
                                tryAwaitRelease()
                                scope.launch { press.animateTo(0f, tween(260)) }
                            },
                            onTap = { offset -> indexAt(offset.x)?.let { onSegmentClick(segments[it]) } }
                        )
                    }
            ) {
                val base = 6.dp.toPx()
                val tall = 14.dp.toPx()
                val pressedHeight = 18.dp.toPx()
                val radius = CornerRadius(2.dp.toPx())
                val flashed = flashChapter()
                val flashValue = flashProgress()
                segments.forEachIndexed { index, segment ->
                    val (x, w) = layout[index]
                    val fill = segment.fillFor(currentChapterIndex)
                    var h = if (fill == RhythmFill.CURRENT) tall else base
                    if (index == pressed) h += (pressedHeight - h) * press.value
                    val isFlashed = flashed != null && flashed in segment.firstChapter..segment.lastChapter
                    if (isFlashed) h += (pressedHeight - h) * flashValue * 0.7f
                    val top = size.height - h
                    when (fill) {
                        RhythmFill.READ -> drawRoundRect(ink.copy(alpha = 0.85f), Offset(x, top), Size(w, h), radius)
                        RhythmFill.UNREAD -> drawRoundRect(unread, Offset(x, top), Size(w, h), radius)
                        RhythmFill.CURRENT -> {
                            drawRoundRect(seal.copy(alpha = 0.26f), Offset(x, top), Size(w, h), radius)
                            val span = (segment.lastChapter - segment.firstChapter + 1).toFloat()
                            val done = ((currentChapterIndex - segment.firstChapter + 0.5f) / span).coerceIn(0.15f, 1f)
                            drawRoundRect(seal, Offset(x, top), Size(w * done, h), radius)
                        }
                    }
                    if (isFlashed && flashValue > 0f) {
                        drawRoundRect(seal.copy(alpha = 0.55f * flashValue), Offset(x, top), Size(w, h), radius)
                    }
                }
            }
            if (volumes.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 7.dp)) {
                    volumes.forEachIndexed { volumeIndex, volume ->
                        val indices = segments.indices.filter { segments[it].volume == volumeIndex }
                        if (indices.isNotEmpty()) {
                            val start = layout[indices.first()].first
                            val end = layout[indices.last()].let { it.first + it.second }
                            val current = currentChapterIndex in volume.firstChapter..volume.lastChapter
                            Text(
                                text = volume.title,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.8.sp),
                                color = if (current) palette.onBackground else palette.muted,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .offset { IntOffset(start.toInt(), 0) }
                                    .width(with(density) { (end - start).toDp() })
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val FLASH_HOLD_MS = 450L
private const val FLASH_FADE_MS = 900

@Composable
private fun VolumeRow(
    item: ReaderTocItem,
    childCount: Int,
    expanded: Boolean,
    current: Boolean,
    palette: ReaderPalette,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = if (item.chapterIndex != null) onOpen else onToggle)
            .padding(start = (10 + item.depth.coerceAtMost(4) * 14).dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
            fontWeight = FontWeight.SemiBold,
            color = if (current) palette.seal else palette.onBackground.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.toc_volume_count, childCount),
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = stringResource(if (expanded) R.string.toc_collapse_volume else R.string.toc_expand_volume),
                tint = palette.muted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ChapterRow(
    item: ReaderTocItem,
    state: TocRowState,
    charCount: Int?,
    first: Boolean,
    last: Boolean,
    palette: ReaderPalette,
    flash: () -> Float,
    onClick: () -> Unit,
    onContinue: () -> Unit
) {
    val current = state == TocRowState.CURRENT
    val ink = palette.onBackground
    val seal = palette.seal
    val line = ink.copy(alpha = if (palette.isDark) 0.18f else 0.14f)
    val readLine = ink.copy(alpha = 0.55f)
    val indent = (item.depth.coerceAtMost(4) * 12).dp
    val shape = RoundedCornerShape(20.dp)
    val rowModifier = if (current) {
        Modifier
            .padding(vertical = 6.dp)
            .clip(shape)
            .background(palette.onBackground.copy(alpha = if (palette.isDark) 0.07f else 0.045f), shape)
    } else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowModifier)
            .drawBehind {
                // 从节律条跳过来的目标章：整行淡淡一层朱砂，亮一下再退。只在绘制阶段读进度。
                val alpha = flash()
                if (alpha > 0f) drawRoundRect(palette.seal.copy(alpha = 0.16f * alpha), cornerRadius = CornerRadius(14.dp.toPx()))
            }
            .clickable(onClick = onClick)
            .heightIn(min = if (current) 76.dp else 52.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.displayNumber?.toString()?.padStart(2, '0').orEmpty(),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
            color = if (current) seal else palette.muted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp)
        )
        // 进度线：每行画自己那一截，上下相接成一根贯穿全书的线。
        Box(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
                .drawBehind {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val stroke = 1.2.dp.toPx()
                    val upper = if (state == TocRowState.UNREAD) line else readLine
                    val lower = if (state == TocRowState.READ) readLine else line
                    if (!first) drawLine(upper, Offset(cx, 0f), Offset(cx, cy), stroke)
                    if (!last) drawLine(lower, Offset(cx, cy), Offset(cx, size.height), stroke)
                    when (state) {
                        TocRowState.READ -> drawCircle(ink.copy(alpha = 0.82f), 3.dp.toPx(), Offset(cx, cy))
                        TocRowState.UNREAD -> drawCircle(palette.muted, 3.2.dp.toPx(), Offset(cx, cy), style = Stroke(1.2.dp.toPx()))
                        TocRowState.CURRENT -> {
                            drawCircle(seal.copy(alpha = 0.18f), 9.dp.toPx(), Offset(cx, cy))
                            drawCircle(seal, 5.dp.toPx(), Offset(cx, cy))
                        }
                    }
                }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp + indent, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, lineHeight = 22.sp),
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = when (state) {
                    TocRowState.READ -> ink.copy(alpha = 0.62f)
                    else -> ink
                },
                maxLines = if (current) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            if (current) {
                Text(
                    text = buildString {
                        append(stringResource(R.string.toc_reading_here))
                        charCount?.takeIf { it > 0 }?.let {
                            append(" · ")
                            append(stringResource(R.string.toc_chapter_chars, it))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        if (current) {
            Surface(
                onClick = onContinue,
                shape = CircleShape,
                color = palette.accent,
                contentColor = palette.onAccent,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.toc_continue),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * 右缘刻度条：只有滑块本身能抓（约 30×48dp 的触达区），按住拖动跳到对应位置并浮出章名，
 * 每跨一章轻震一下。轨道其余部分不接收触摸——它叠在列表右缘上，若整条都能抓，
 * 贴着右边缘正常滑列表会被当成拖刻度条，列表一下跳到别处，看起来就是上下抽搐。
 */
@Composable
private fun TocScrubber(
    items: List<ReaderTocItem>,
    palette: ReaderPalette,
    firstVisible: () -> Int,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var trackHeight by remember { mutableStateOf(1) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    val touchHeight = with(density) { SCRUBBER_TOUCH_HEIGHT.roundToPx() }
    val travel = (trackHeight - touchHeight).coerceAtLeast(1)
    val restingFraction by remember(items) {
        derivedStateOf { firstVisible().toFloat() / (items.size - 1).coerceAtLeast(1) }
    }
    val thumbY = if (dragging != null) dragY else travel * restingFraction
    Box(
        modifier = modifier
            .width(30.dp)
            .fillMaxHeight()
            .onSizeChanged { trackHeight = it.height.coerceAtLeast(1) }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight()
                .background(palette.onBackground.copy(alpha = 0.06f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbY.toInt()) }
                .width(30.dp)
                .height(SCRUBBER_TOUCH_HEIGHT)
                .pointerInput(items, travel) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragY = travel * restingFraction
                            dragging = scrubberIndex(dragY / travel, items.size)
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            dragY = (dragY + delta).coerceIn(0f, travel.toFloat())
                            val index = scrubberIndex(dragY / travel, items.size)
                            if (index != dragging) {
                                dragging = index
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onScrub(index)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(if (dragging != null) 5.dp else 3.dp)
                    .height(28.dp)
                    .background(if (dragging != null) palette.seal else palette.onBackground.copy(alpha = 0.32f), CircleShape)
            )
        }
        dragging?.let { index ->
            items.getOrNull(index)?.let { item ->
                // 不用 Surface：非点击 Surface 会吃掉触摸，浮出的章名不该挡住任何东西。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-with(density) { 38.dp.roundToPx() }, thumbY.toInt()) }
                        .widthIn(max = 220.dp)
                        .shadow(6.dp, RoundedCornerShape(14.dp))
                        .background(palette.glassStrong.compositeOver(palette.background), RoundedCornerShape(14.dp))
                        .border(0.5.dp, palette.glassBorder, RoundedCornerShape(14.dp))
                ) {
                    Text(
                        text = listOfNotNull(item.displayNumber?.toString(), item.title).joinToString("  "),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                        color = palette.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }
}

private val SCRUBBER_TOUCH_HEIGHT = 48.dp

@Composable
private fun TocFloatingBar(
    reversed: Boolean,
    palette: ReaderPalette,
    onLocate: () -> Unit,
    onToggleOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = palette.glassStrong,
        contentColor = palette.onBackground,
        border = BorderStroke(0.5.dp, palette.glassBorder),
        shadowElevation = 6.dp,
        modifier = modifier.pointerInput(Unit) {
            // 从浮条上起手的纵向拖动就地吃掉：不拖动整张弹层。
            detectVerticalDragGestures { change, _ -> change.consume() }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TocBarAction(Icons.Outlined.MyLocation, stringResource(R.string.toc_locate_current), onLocate)
            Spacer(
                Modifier
                    .width(0.5.dp)
                    .height(22.dp)
                    .background(palette.glassBorder)
            )
            TocBarAction(
                Icons.Outlined.SwapVert,
                stringResource(if (reversed) R.string.toc_order_reverse else R.string.toc_order_forward),
                onToggleOrder
            )
        }
    }
}

@Composable
private fun TocBarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

private fun Set<Int>.toggle(value: Int): Set<Int> = if (value in this) this - value else this + value

private const val SCRUBBER_MIN_ITEMS = 30

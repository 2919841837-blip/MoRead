package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity

internal data class ReaderTocItem(
    val key: String,
    val orderIndex: Int,
    val title: String,
    val depth: Int,
    val parentOrderIndex: Int?,
    val chapterIndex: Int?,
    val href: String,
    val hasChildren: Boolean,
    val displayNumber: Int?
)

internal fun buildReaderTocItems(
    chapters: List<ChapterEntity>,
    entries: List<BookTocEntryEntity>
): List<ReaderTocItem> = if (entries.isNotEmpty()) {
    val leafDestinations = entries.filter { it.chapterIndex != null && !it.hasChildren }
    val explicitChapters = leafDestinations.filter { it.title.isExplicitChapterTitle() }
    val logicalChapters = if (
        explicitChapters.size >= MIN_EXPLICIT_CHAPTERS &&
        explicitChapters.size * 2 >= leafDestinations.size
    ) {
        explicitChapters
    } else {
        leafDestinations.ifEmpty { entries.filter { it.chapterIndex != null } }
    }
    val numberedOrders = logicalChapters.mapTo(hashSetOf(), BookTocEntryEntity::orderIndex)
    var nextDisplayNumber = 0
    entries.map { entry ->
        ReaderTocItem(
            key = "toc-${entry.id}",
            orderIndex = entry.orderIndex,
            title = entry.title,
            depth = entry.depth,
            parentOrderIndex = entry.parentOrderIndex,
            chapterIndex = entry.chapterIndex,
            href = entry.href,
            hasChildren = entry.hasChildren,
            displayNumber = if (entry.orderIndex in numberedOrders) ++nextDisplayNumber else null
        )
    }
} else {
    chapters.mapIndexed { displayIndex, chapter ->
        ReaderTocItem(
            key = "chapter-${chapter.id}",
            orderIndex = chapter.chapterIndex,
            title = chapter.title,
            depth = 0,
            parentOrderIndex = null,
            chapterIndex = chapter.chapterIndex,
            href = chapter.href,
            hasChildren = false,
            displayNumber = displayIndex + 1
        )
    }
}

internal fun visibleReaderTocItems(
    items: List<ReaderTocItem>,
    collapsedOrderIndices: Set<Int>
): List<ReaderTocItem> = buildList {
    var hiddenBelowDepth: Int? = null
    items.forEach { item ->
        val hiddenDepth = hiddenBelowDepth
        if (hiddenDepth != null && item.depth > hiddenDepth) return@forEach
        if (hiddenDepth != null) hiddenBelowDepth = null
        add(item)
        if (item.hasChildren && item.orderIndex in collapsedOrderIndices) {
            hiddenBelowDepth = item.depth
        }
    }
}

internal fun currentReaderTocOrder(
    items: List<ReaderTocItem>,
    currentChapterIndex: Int
): Int? = nearestReaderTocItem(items, currentChapterIndex)?.orderIndex

internal fun readerTocDisplayCount(items: List<ReaderTocItem>): Int =
    items.count { it.displayNumber != null }

internal fun readerTocSupplementaryCount(items: List<ReaderTocItem>): Int =
    items.count { it.chapterIndex != null && !it.hasChildren && it.displayNumber == null }

internal fun currentReaderTocDisplayNumber(
    items: List<ReaderTocItem>,
    currentChapterIndex: Int
): Int? = nearestReaderTocItem(
    items = items.filter { it.displayNumber != null },
    currentChapterIndex = currentChapterIndex
)
    ?.takeIf { requireNotNull(it.chapterIndex) <= currentChapterIndex }
    ?.displayNumber

/**
 * EPUB 的一个逻辑章节可能被排版工具拆成多个连续 spine 文档。目录通常只链接第一个
 * 文档，因此读到后续正文文档时，也应继续把最近的前置目录项视为当前章节。
 */
private fun nearestReaderTocItem(
    items: List<ReaderTocItem>,
    currentChapterIndex: Int
): ReaderTocItem? {
    val linkedItems = items.filter { it.chapterIndex != null }
    return linkedItems
        .filter { requireNotNull(it.chapterIndex) <= currentChapterIndex }
        .maxWithOrNull(
            compareBy<ReaderTocItem> { it.chapterIndex }
                .thenBy { it.depth }
                .thenBy { it.orderIndex }
        )
        ?: linkedItems.minWithOrNull(
            compareBy<ReaderTocItem> { it.chapterIndex }
                .thenByDescending { it.depth }
                .thenBy { it.orderIndex }
        )
}

internal fun currentReaderTocListIndex(
    allItems: List<ReaderTocItem>,
    visibleItems: List<ReaderTocItem>,
    currentChapterIndex: Int
): Int {
    val itemByOrder = allItems.associateBy(ReaderTocItem::orderIndex)
    var targetOrder = currentReaderTocOrder(allItems, currentChapterIndex)
    val visibleOrders = visibleItems.mapTo(mutableSetOf(), ReaderTocItem::orderIndex)
    while (targetOrder != null && targetOrder !in visibleOrders) {
        targetOrder = itemByOrder[targetOrder]?.parentOrderIndex
    }
    return visibleItems.indexOfFirst { it.orderIndex == targetOrder }.coerceAtLeast(0)
}


private const val MIN_EXPLICIT_CHAPTERS = 2

/**
 * 章节节律条的一段：覆盖 [firstChapter]..[lastChapter]，[weight] 为占全书宽度的比例。
 * [volume] 是所属分卷在 [readerTocVolumes] 里的下标（不在任何卷里为 -1）；
 * [startsVolume] 的段前面多留一道卷间空隙。
 */
internal data class ChapterRhythmSegment(
    val firstChapter: Int,
    val lastChapter: Int,
    val weight: Float,
    val volume: Int = -1,
    val startsVolume: Boolean = false
)

/** 一卷覆盖的正文章节范围（含首尾），标题取目录里的卷名。 */
internal data class RhythmVolume(val title: String, val firstChapter: Int, val lastChapter: Int)

internal enum class RhythmFill { READ, CURRENT, UNREAD }

/** 目录顶层带子项的条目即分卷；范围取其后代链接到的正文章节。没有分卷的书返回空。 */
internal fun readerTocVolumes(items: List<ReaderTocItem>): List<RhythmVolume> {
    val volumes = mutableListOf<RhythmVolume>()
    var index = 0
    while (index < items.size) {
        val head = items[index]
        if (head.depth == 0 && head.hasChildren) {
            var end = index + 1
            while (end < items.size && items[end].depth > 0) end++
            val chapters = (index until end).mapNotNull { items[it].chapterIndex }
            if (chapters.isNotEmpty()) volumes += RhythmVolume(head.title, chapters.min(), chapters.max())
            index = end
        } else {
            index++
        }
    }
    return if (volumes.size >= 2) volumes else emptyList()
}

/**
 * 每段宽度按章节字数取，长篇按相邻章节聚合到最多 [maxSegments] 段（六百章的书每段也看得见）。
 * 有分卷时段落不跨卷，段数按各卷章节数分配，卷与卷之间留出空隙。
 * 过短的段落有下限，避免一章序言细成一条线点不中。
 */
internal fun buildChapterRhythm(
    chapters: List<ChapterEntity>,
    volumes: List<RhythmVolume> = emptyList(),
    maxSegments: Int = MAX_RHYTHM_SEGMENTS
): List<ChapterRhythmSegment> {
    if (chapters.size < 2 || maxSegments <= 0) return emptyList()
    val sorted = chapters.sortedBy(ChapterEntity::chapterIndex)
    fun volumeOf(chapterIndex: Int): Int = volumes.indexOfFirst { chapterIndex in it.firstChapter..it.lastChapter }
    // 相邻且同卷的章节成一组；没有分卷时整本书是一组。
    val runs = mutableListOf<MutableList<ChapterEntity>>()
    var runVolume = Int.MIN_VALUE
    sorted.forEach { chapter ->
        val volume = if (volumes.isEmpty()) -1 else volumeOf(chapter.chapterIndex)
        if (runs.isEmpty() || volume != runVolume) {
            runs += mutableListOf(chapter)
            runVolume = volume
        } else {
            runs.last() += chapter
        }
    }
    data class Raw(val first: Int, val last: Int, val chars: Long, val volume: Int, val starts: Boolean)
    val raw = runs.flatMap { run ->
        val volume = if (volumes.isEmpty()) -1 else volumeOf(run.first().chapterIndex)
        val share = (maxSegments * run.size.toFloat() / sorted.size).toInt().coerceIn(1, run.size)
        val bucket = (run.size + share - 1) / share
        run.chunked(bucket).mapIndexed { index, group ->
            Raw(
                first = group.first().chapterIndex,
                last = group.last().chapterIndex,
                chars = group.sumOf { it.charCount.coerceAtLeast(0).toLong() },
                volume = volume,
                starts = index == 0 && volume >= 0
            )
        }
    }
    val total = raw.sumOf { it.chars }
    val weights = if (total <= 0) {
        raw.map { 1f / raw.size }
    } else {
        val floor = (1f / raw.size) * MIN_SEGMENT_SHARE
        raw.map { (it.chars.toFloat() / total).coerceAtLeast(floor) }.let { w -> w.map { it / w.sum() } }
    }
    return raw.mapIndexed { index, it -> ChapterRhythmSegment(it.first, it.last, weights[index], it.volume, it.starts) }
}

internal fun ChapterRhythmSegment.fillFor(currentChapterIndex: Int): RhythmFill = when {
    lastChapter < currentChapterIndex -> RhythmFill.READ
    currentChapterIndex in firstChapter..lastChapter -> RhythmFill.CURRENT
    else -> RhythmFill.UNREAD
}

/** 右缘刻度条：手指在轨道上的比例 → 列表下标。 */
internal fun scrubberIndex(fraction: Float, count: Int): Int =
    if (count <= 0) 0 else (fraction.coerceIn(0f, 1f) * (count - 1)).let { kotlin.math.round(it).toInt() }

private const val MAX_RHYTHM_SEGMENTS = 24
private const val MIN_SEGMENT_SHARE = 0.35f
private const val CHAPTER_NUMERALS = """[0-9０-９〇零一二三四五六七八九十百千万两廿卅卌]"""

/**
 * 逻辑章节的标题形态。出版方偶尔漏掉「第」（金瓶梅第 41 回目就是「四十一回 两孩儿……」），
 * 漏掉时要求量词后紧跟空白或标点，免得把「两回事」这类正常标题也当成章节编号。
 */
private val EXPLICIT_CHAPTER_TITLE = Regex(
    pattern = """^\s*(?:第\s*$CHAPTER_NUMERALS+\s*[章节回篇卷部]""" +
        """|$CHAPTER_NUMERALS+\s*[章节回篇卷部](?=[\s、,，.。:：·—－]|$)""" +
        """|(?:chapter|chapitre|kapitel|cap[ií]tulo)\s+[0-9ivxlcdm]+\b)""",
    option = RegexOption.IGNORE_CASE
)

private fun String.isExplicitChapterTitle(): Boolean = EXPLICIT_CHAPTER_TITLE.containsMatchIn(this)

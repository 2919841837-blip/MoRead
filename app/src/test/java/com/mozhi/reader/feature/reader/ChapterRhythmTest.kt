package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterRhythmTest {
    private fun chapters(vararg chars: Int) = chars.mapIndexed { index, count ->
        ChapterEntity(id = index + 1L, bookId = 1, chapterIndex = index, title = "第${index + 1}章", href = "", charCount = count)
    }

    @Test fun segmentWidthsFollowChapterLengthAndSumToOne() {
        val rhythm = buildChapterRhythm(chapters(1000, 3000, 2000))
        assertEquals(3, rhythm.size)
        assertEquals(1f, rhythm.sumOf { it.weight.toDouble() }.toFloat(), 0.0001f)
        assertTrue(rhythm[1].weight > rhythm[2].weight && rhythm[2].weight > rhythm[0].weight)
    }

    @Test fun tinyChaptersKeepATappableMinimumWidth() {
        val rhythm = buildChapterRhythm(chapters(10, 100_000, 100_000))
        assertTrue(rhythm[0].weight > 0.1f)
    }

    @Test fun longBooksAggregateNeighbouringChapters() {
        val rhythm = buildChapterRhythm(chapters(*IntArray(600) { 3000 }), maxSegments = 40)
        assertEquals(40, rhythm.size)
        assertEquals(0, rhythm.first().firstChapter)
        assertEquals(599, rhythm.last().lastChapter)
        // Every chapter belongs to exactly one segment.
        assertEquals(600, rhythm.sumOf { it.lastChapter - it.firstChapter + 1 })
    }

    @Test fun singleChapterBooksHaveNoRhythmBar() {
        assertTrue(buildChapterRhythm(chapters(500)).isEmpty())
    }

    @Test fun zeroLengthChaptersSplitEvenly() {
        val rhythm = buildChapterRhythm(chapters(0, 0, 0, 0))
        rhythm.forEach { assertEquals(0.25f, it.weight, 0.0001f) }
    }

    @Test fun fillMarksReadCurrentAndUnreadSegments() {
        val segment = ChapterRhythmSegment(firstChapter = 10, lastChapter = 14, weight = 0.1f)
        assertEquals(RhythmFill.READ, segment.fillFor(15))
        assertEquals(RhythmFill.CURRENT, segment.fillFor(12))
        assertEquals(RhythmFill.UNREAD, segment.fillFor(9))
    }

    private fun toc(title: String, depth: Int, chapter: Int?, children: Boolean = false, order: Int = 0) = ReaderTocItem(
        key = "k$order", orderIndex = order, title = title, depth = depth, parentOrderIndex = null,
        chapterIndex = chapter, href = "", hasChildren = children, displayNumber = null
    )

    @Test fun volumesComeFromTopLevelEntriesWithChildren() {
        val items = listOf(
            toc("卷一", 0, null, children = true, order = 0),
            toc("一", 1, 0, order = 1), toc("二", 1, 1, order = 2),
            toc("卷二", 0, 2, children = true, order = 3),
            toc("三", 1, 2, order = 4), toc("四", 1, 3, order = 5)
        )
        assertEquals(listOf(RhythmVolume("卷一", 0, 1), RhythmVolume("卷二", 2, 3)), readerTocVolumes(items))
        // 只有一卷等于没有分卷：不画卷名。
        assertTrue(readerTocVolumes(items.take(3)).isEmpty())
    }

    @Test fun segmentsNeverCrossVolumesAndMarkVolumeStarts() {
        val volumes = listOf(RhythmVolume("卷一", 0, 5), RhythmVolume("卷二", 6, 11))
        val rhythm = buildChapterRhythm(chapters(*IntArray(12) { 1000 }), volumes, maxSegments = 4)
        rhythm.forEach { segment ->
            val volume = volumes[segment.volume]
            assertTrue(segment.firstChapter >= volume.firstChapter && segment.lastChapter <= volume.lastChapter)
        }
        assertEquals(listOf(0, 1), rhythm.filter { it.startsVolume }.map { it.volume })
        assertEquals(12, rhythm.sumOf { it.lastChapter - it.firstChapter + 1 })
    }

    @Test fun scrubberMapsTrackFractionToListIndex() {
        assertEquals(0, scrubberIndex(-0.2f, 100))
        assertEquals(99, scrubberIndex(1.4f, 100))
        assertEquals(50, scrubberIndex(0.505f, 100))
        assertEquals(0, scrubberIndex(0.5f, 0))
    }
}

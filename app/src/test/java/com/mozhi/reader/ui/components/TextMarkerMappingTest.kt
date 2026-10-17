package com.mozhi.reader.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ★ 只存在于渲染层：这些映射保证光标/选区换算回原始文本时不会落进标记内部，
 * 因而打字与退格永远作用在干净的值上。
 */
class TextMarkerMappingTest {
    private val source = "abcdef"
    private val mapping = MarkerOffsetMapping(listOf(0, 3), source.length)

    @Test fun transformationInsertsOneGlyphPerAnchorAndDeduplicates() {
        val result = MarkerTransformation(listOf(3, 0, 3), SpanStyle(fontWeight = FontWeight.Bold))
            .filter(AnnotatedString(source))
        assertEquals("★abc★def", result.text.text)
        assertEquals(listOf(0 to 1, 4 to 5), result.text.spanStyles.map { it.start to it.end })
    }

    @Test fun mappingRoundTripsEveryOriginalOffset() {
        for (offset in 0..source.length) {
            assertEquals(offset, mapping.transformedToOriginal(mapping.originalToTransformed(offset)))
        }
    }

    @Test fun markerInteriorFoldsBackToItsAnchor() {
        // 原文 "abcdef"、锚点 0/3 → 渲染 "★abc★def"。
        assertEquals(1, mapping.originalToTransformed(0))
        assertEquals(2, mapping.originalToTransformed(1))
        assertEquals(5, mapping.originalToTransformed(3))
        assertEquals(8, mapping.originalToTransformed(6))
        // 两个 ★ 的视觉区间都折回锚点，没有「标记中间」这个位置。
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(0, mapping.transformedToOriginal(1))
        assertEquals(3, mapping.transformedToOriginal(4))
        assertEquals(3, mapping.transformedToOriginal(5))
        assertEquals(6, mapping.transformedToOriginal(8))
    }

    @Test fun outOfRangeOffsetsAreClamped() {
        assertEquals(0, mapping.transformedToOriginal(-4))
        assertEquals(source.length, mapping.transformedToOriginal(99))
        assertEquals(source.length + 2, mapping.originalToTransformed(99))
    }

    @Test fun identityWhenThereAreNoMarkers() {
        val identity = MarkerOffsetMapping(emptyList(), source.length)
        for (offset in 0..source.length) {
            assertEquals(offset, identity.originalToTransformed(offset))
            assertEquals(offset, identity.transformedToOriginal(offset))
        }
        assertEquals(OffsetMapping.Identity.originalToTransformed(4), identity.originalToTransformed(4))
    }
}

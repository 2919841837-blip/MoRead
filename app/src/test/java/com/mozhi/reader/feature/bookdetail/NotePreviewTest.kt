package com.mozhi.reader.feature.bookdetail

import org.junit.Assert.assertEquals
import org.junit.Test

class NotePreviewTest {
    @Test
    fun `table rows flatten and separator lines are dropped`() {
        val markdown = """
            ## 未回收
            | 编号 | 状态 |
            | --- | --- |
            | F-001 | 未回收 |
        """.trimIndent()

        assertEquals("未回收 编号 · 状态 F-001 · 未回收", notePreview(markdown))
    }

    @Test
    fun `plain markdown keeps one line and strips markers`() {
        assertEquals("第一段 第二段", notePreview("**第一段**\n\n> 第二段"))
    }
}

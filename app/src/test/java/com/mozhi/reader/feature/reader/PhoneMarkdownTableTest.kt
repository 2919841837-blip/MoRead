package com.mozhi.reader.feature.reader

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneMarkdownTableTest {
    @Test
    fun `table keeps header labels and body cells in order`() {
        val markdown = """
            | 编号 | 埋设章节 | 状态 |
            | --- | :---: | ---: |
            | F-001 | 第 3 章 | 未回收 |
            | F-002 | 第 5 章 | 已回收 |
        """.trimIndent()

        val (header, rows) = requireNotNull(parseTable(tableNode(markdown), markdown))

        assertEquals(listOf("编号", "埋设章节", "状态"), header)
        assertEquals(
            listOf(
                listOf("F-001", "第 3 章", "未回收"),
                listOf("F-002", "第 5 章", "已回收")
            ),
            rows
        )
    }

    @Test
    fun `non table node yields no rows`() {
        val markdown = "普通段落"
        assertNull(parseTable(rootNode(markdown), markdown))
    }

    private fun tableNode(markdown: String) =
        rootNode(markdown).children.first { it.type == GFMElementTypes.TABLE }

    private fun rootNode(markdown: String) =
        MarkdownParser(GFMFlavourDescriptor(), false, CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(markdown as CharSequence)
}

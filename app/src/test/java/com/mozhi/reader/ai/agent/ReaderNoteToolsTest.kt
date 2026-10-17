package com.mozhi.reader.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 伏笔追踪的第二道保险：旧稿未回收编号不能在新稿里凭空消失。 */
class ReaderNoteToolsTest {

    @Test
    fun `unresolved ids may move to the resolved section`() {
        val old = """
            # 伏笔追踪（截至第 10 章）

            ## 未回收
            | 编号 | 埋设章节 | 内容 |
            | --- | --- | --- |
            | F-001 | 2 | 青铜钥匙 |
            | F-002 | 5 | 无名的信 |

            ## 已回收
            | 编号 | 埋设章节 | 回收章节 |
            | --- | --- | --- |
        """.trimIndent()
        val new = """
            # 伏笔追踪（截至第 12 章）

            ## 未回收
            | 编号 | 埋设章节 | 内容 |
            | --- | --- | --- |
            | F-001 | 2 | 青铜钥匙 |

            ## 已回收
            | 编号 | 埋设章节 | 回收章节 |
            | --- | --- | --- |
            | F-002 | 5 | 12 |
        """.trimIndent()

        assertTrue(missingUnresolvedForeshadowingIds(old, new).isEmpty())
    }

    @Test
    fun `dropped unresolved id is reported`() {
        val old = """
            ## 未回收
            | F-001 | 2 | 青铜钥匙 |
            | F-002 | 5 | 无名的信 |
        """.trimIndent()
        val new = """
            ## 未回收
            | F-001 | 2 | 青铜钥匙 |
        """.trimIndent()

        assertEquals(listOf("F-002"), missingUnresolvedForeshadowingIds(old, new))
    }

    @Test
    fun `ids outside the unresolved section are not treated as outstanding`() {
        val old = """
            ## 已回收
            | F-001 | 2 | 青铜钥匙 | 8 |
        """.trimIndent()
        val new = "# 伏笔追踪\n\n暂无未回收伏笔。"

        assertTrue(missingUnresolvedForeshadowingIds(old, new).isEmpty())
    }

    @Test
    fun `untemplated old draft does not block the first structured save`() {
        val old = "随手记：青铜钥匙是伏笔。"
        assertTrue(missingUnresolvedForeshadowingIds(old, "## 未回收\n| F-001 | 2 | 青铜钥匙 |").isEmpty())
    }
}

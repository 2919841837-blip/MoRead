package com.mozhi.reader.ai.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterDossierCodecTest {
    private val chapterOne = "序。林舟在灯塔等候多时。他说：“灯不能灭。”苏晚是他的师姐，常来看他。"
    private val chapterFive = "风雪夜里，林舟独自修好了灯。小满送来一封信。"
    private val passages = listOf(
        SourcePassage(0, 0, chapterOne),
        SourcePassage(4, 120, chapterFive)
    )

    private fun parse(raw: String) = CharacterDossierCodec.parse(raw, "林舟", listOf("小舟"), passages)

    @Test fun `passage numbers become chapter anchors on the sentence that names the person`() {
        val result = parse("""{"name":"林舟","tagline":"守灯人","bio":"一个守灯塔的少年。",
            "experiences":[{"p":2,"event":"修好了灯"},{"p":1,"event":"等候"}],
            "relationships":[{"name":"苏晚","relation":"师姐","p":1}]}""")
        val dossier = result.dossier
        assertEquals(listOf("等候", "修好了灯"), dossier.experiences.map { it.text })
        val first = dossier.experiences.first().anchor!!
        assertEquals(0, first.chapterIndex)
        assertEquals("林舟在灯塔等候多时。", chapterOne.substring(first.start, first.end))
        val later = dossier.experiences.last().anchor!!
        assertEquals(4, later.chapterIndex)
        assertEquals("风雪夜里，林舟独自修好了灯。", chapterFive.substring(later.start - 120, later.end - 120))
        val relation = dossier.relationships.single()
        assertEquals("苏晚" to "师姐", relation.target to relation.relation)
        assertEquals("苏晚是他的师姐，常来看他。", chapterOne.substring(relation.anchor!!.start, relation.anchor!!.end))
        // 旧结构回填：每条依据都能逐字对回原文。
        result.evidence.forEach { evidence ->
            val source = if (evidence.chapterIndex == 0) chapterOne else chapterFive
            val offset = if (evidence.chapterIndex == 0) 0 else 120
            assertEquals(evidence.fact.quote, source.substring(evidence.fact.start - offset, evidence.fact.end - offset))
        }
    }

    @Test fun `a legacy quotes payload is ignored instead of breaking the dossier`() {
        // 提示词升到 2 之后不再要台词：模型偶尔还带回 quotes 时只当没看见，其余字段照常解析。
        val result = parse("""{"name":"林舟","bio":"守灯人。","quotes":[{"p":1,"text":"灯不能灭"}]}""")
        assertEquals("守灯人。", result.dossier.bio)
    }

    @Test fun `a quotes only payload is still not a dossier`() {
        assertTrue(runCatching { parse("""{"name":"林舟","quotes":[{"p":1,"text":"灯不能灭"}]}""") }.isFailure)
    }

    @Test fun `invented people and out of range passages never break the dossier`() {
        val result = parse("""{"name":"林舟","bio":"守灯人。",
            "experiences":[{"p":9,"event":"去了京城"}],
            "relationships":[{"name":"王大锤","relation":"舅舅"},{"name":"小满","relation":"信使","p":"[2]"}],
            "personality":"沉稳、执着"}""")
        val dossier = result.dossier
        assertNull(dossier.experiences.single().anchor)
        assertNull(dossier.experiences.single().chapterIndex)
        assertEquals(listOf("小满"), dossier.relationships.map { it.target })
        assertEquals(listOf("沉稳", "执着"), dossier.personality.map { it.trait })
    }

    @Test fun `a renamed or foreign person falls back to the requested target`() {
        assertEquals("林舟", parse("""{"name":"苏晚","bio":"师姐。"}""").name)
        assertEquals("林舟", parse("""{"name":"林大侠","bio":"守灯人。"}""").name)
    }

    @Test fun `truncated replies still yield what was complete`() {
        val raw = """{"name":"林舟","tagline":"守灯人","bio":"一个守灯塔的少年。","experiences":[{"p":1,"event":"等候"},{"p":2,"ev"""
        val dossier = parse(raw).dossier
        assertEquals("守灯人", dossier.tagline)
        assertEquals(listOf("等候"), dossier.experiences.map { it.text })
    }

    @Test fun `aliases must appear in the passages`() {
        val result = CharacterDossierCodec.parse("""{"name":"林舟","aliases":["阿舟","林少爷","师姐"],"bio":"守灯人。"}""",
            "林舟", listOf("小舟"), passages)
        // 「小舟」「阿舟」「林少爷」都不在段落里；「师姐」虽然出现了，照样只是原文里的称呼。
        assertEquals(listOf("师姐"), result.aliases)
    }

    @Test fun `empty or prose replies ask for a correction`() {
        assertTrue(runCatching { parse("""{"name":"林舟"}""") }.isFailure)
        assertTrue(runCatching { parse("林舟是守灯人。") }.isFailure)
    }

    @Test fun `appearance and personality are back filled for the illustration studio`() {
        val result = parse("""{"name":"林舟","bio":"守灯人。","appearance":{"text":"瘦高","p":2},
            "personality":[{"trait":"执着","detail":"风雪夜修灯","p":2}]}""")
        val kinds = result.attributes.map { it.kind }
        assertTrue(CharacterAttributeKind.APPEARANCE in kinds)
        assertTrue(CharacterAttributeKind.PERSONALITY in kinds)
        assertEquals(4, result.dossier.appearanceAnchor!!.chapterIndex)
    }
}

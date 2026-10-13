package com.mozhi.reader.feature.reader

import com.mozhi.reader.ai.knowledge.*
import org.junit.Assert.*
import org.junit.Test

class CharacterDossierLayoutTest {
    private val fact = KnowledgeFact("守着灯塔", "林舟在灯塔等候。", 0, 8)
    private val lin = BookCharacter("林舟", emptyList(), role = CharacterRole.PROTAGONIST, mentions = 300, dossier = CharacterDossier(
        tagline = "守灯人", identity = "灯塔守望人", personality = listOf(DossierTrait("执着"), DossierTrait("嘴硬心软"), DossierTrait("沉默")),
        experiences = listOf(DossierEvent("后来出发", 5), DossierEvent("最初守灯", 0), DossierEvent("未注明")),
        relationships = listOf(DossierRelation("阿满", "同伴"), DossierRelation("路人甲", "邻居"))))
    private val man = BookCharacter("小满", emptyList(), attributes = listOf(BookCharacterAttribute(CharacterAttributeKind.ALIAS, "阿满", CharacterEvidence(0, fact))),
        role = CharacterRole.MAJOR, mentions = 80, dossier = CharacterDossier(tagline = "信使", relationships = listOf(DossierRelation("林舟", "旅伴"))))
    private val legacy = BookCharacter("船夫", listOf(CharacterEvidence(2, fact)),
        relationships = listOf(BookCharacterRelationship("林舟", "邻居", CharacterEvidence(2, fact))))

    @Test fun `groups follow roles and old guides stay ungrouped`() {
        val groups = characterGroups(listOf(man, lin, legacy))
        assertEquals(listOf(CharacterRole.PROTAGONIST, CharacterRole.MAJOR, CharacterRole.SUPPORTING), groups.map { it.role })
        assertEquals(listOf("船夫"), groups.last().people.map { it.name })
        assertEquals(listOf(null), characterGroups(listOf(legacy)).map { it.role })
    }

    @Test fun `card summary prefers the dossier and falls back to the first fact`() {
        val summary = characterSummary(lin)
        assertEquals("守灯人", summary.tagline)
        assertEquals(listOf("灯塔守望人", "执着", "嘴硬心软"), summary.tags)
        assertEquals(2, summary.relationCount)
        assertEquals("守着灯塔", characterSummary(legacy).tagline)
    }

    @Test fun `timeline is chronological and undated events go last`() {
        assertEquals(listOf("最初守灯", "后来出发", "未注明"), characterTimeline(lin).map { it.text })
        assertEquals(2, characterTimeline(legacy).single().chapterIndex)
    }

    @Test fun `relations merge both sides through aliases and include people who only mention them`() {
        val everyone = listOf(lin, man, legacy)
        val links = egoLinks(lin, everyone)
        val withMan = links.first { it.identity == "小满" }
        assertEquals("小满", withMan.target)
        assertEquals("同伴" to "旅伴", withMan.relation to withMan.reverse)
        assertNull(links.first { it.target == "路人甲" }.identity)
        // 船夫只在自己的旧资料里提到林舟（旧资料写的是「船夫是林舟的邻居」）：林舟的档案也要列出他。
        val ferryman = links.first { it.target == "船夫" }
        assertEquals("邻居" to "", ferryman.relation to ferryman.reverse)
    }

    @Test fun `graph keeps one edge per pair between listed people and weights the protagonist highest`() {
        val graph = relationGraph(listOf(lin, man, legacy))
        assertEquals(1, graph.edges.count { setOf(it.from, it.to) == setOf("林舟", "小满") })
        assertTrue(graph.edges.none { it.to == "路人甲" || it.from == "路人甲" })
        val weights = graph.nodes.associate { it.identity to it.weight }
        assertTrue(weights.getValue("林舟") > weights.getValue("小满"))
        assertEquals("林舟", graph.nodes.first().identity)
    }

    @Test fun `ego positions are deterministic around the centre starting at the top`() {
        val positions = egoPositions(4)
        assertEquals(positions, egoPositions(4))
        assertEquals(.5f, positions[0].first, 1e-4f)
        assertTrue(positions[0].second < .5f)
        positions.forEach { (x, y) -> assertTrue(x in 0f..1f && y in 0f..1f) }
    }
}

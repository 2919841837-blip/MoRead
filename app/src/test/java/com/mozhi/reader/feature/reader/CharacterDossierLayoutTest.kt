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

    @Test fun `a hand written role moves a person between the groups and behind it the AI value stays`() {
        val promoted = man.copy(manualAttributes = ManualCharacterAttributes(role = CharacterRole.PROTAGONIST))
        val groups = characterGroups(listOf(promoted, lin, legacy))
        assertEquals(listOf(CharacterRole.PROTAGONIST, CharacterRole.SUPPORTING), groups.map { it.role })
        assertEquals(listOf("小满", "林舟"), groups.first().people.map { it.name })
        assertEquals(listOf("船夫"), groups.last().people.map { it.name })
        // 关系图的节点顺序与权重也读手写档位：主角排在最前。
        assertEquals(CharacterRole.MAJOR, promoted.role)
        assertEquals(CharacterRole.PROTAGONIST, promoted.effectiveRole())
        assertEquals("小满", relationGraph(listOf(promoted, legacy)).nodes.first().identity)
    }

    @Test fun `a guide where nobody has a role stays ungrouped until someone writes one`() {
        val written = legacy.copy(manualAttributes = ManualCharacterAttributes(identity = "摆渡人"))
        // 只写了身份不算主次：整块照旧平铺，与改动前一致。
        assertEquals(listOf(null), characterGroups(listOf(written)).map { it.role })
        assertEquals(listOf("摆渡人"), characterSummary(written).tags)
        // 一旦有人带主次，没有主次的人就落进「其他人物」。
        val promoted = man.copy(manualAttributes = ManualCharacterAttributes(role = CharacterRole.PROTAGONIST))
        assertEquals(listOf(CharacterRole.PROTAGONIST, CharacterRole.SUPPORTING),
            characterGroups(listOf(promoted, written)).map { it.role })
        assertEquals(listOf("船夫"), characterGroups(listOf(promoted, written)).last().people.map { it.name })
    }

    @Test fun `card summary shows what the user wrote and never falls back to the AI value`() {
        val written = lin.copy(manualAttributes = ManualCharacterAttributes(
            identity = "摆渡人", tagline = "", personality = listOf(ManualTrait("怕水"))))
        val summary = characterSummary(written)
        // 简介被清空就是清空，不拿小传或原文顶上；标签换成手写的身份与性格。
        assertEquals("", summary.tagline)
        assertEquals(listOf("摆渡人", "怕水"), summary.tags)
        // 没接管的字段仍来自档案。
        assertEquals(2, summary.relationCount)
        assertEquals(listOf("灯塔守望人", "执着", "嘴硬心软"),
            characterSummary(lin.copy(manualAttributes = ManualCharacterAttributes(age = "三十"))).tags)
    }

    @Test fun `a hand written timeline replaces the dossier one and has nothing to jump back to`() {
        val written = lin.copy(manualAttributes = ManualCharacterAttributes(
            experiences = listOf(ManualEvent("在码头接应", 3), ManualEvent("未注明的事"))))
        val entries = characterTimeline(written)
        assertEquals(listOf("在码头接应", "未注明的事"), entries.map { it.text })
        // 未注明章节的排最后；手写的经历没有原文坐标，所以「跳到原文」无处可去。
        assertEquals(listOf(3, null), entries.map { it.chapterIndex })
        assertTrue(entries.all { it.source == null })
        // 清空就是清空：不回落档案里的经历。
        assertTrue(characterTimeline(lin.copy(manualAttributes = ManualCharacterAttributes(experiences = emptyList()))).isEmpty())
    }

    @Test fun `a hand written relation list retires the legacy one but keeps what other people say`() {
        val ferryman = legacy.copy(relationships = listOf(BookCharacterRelationship("林舟", "邻居", CharacterEvidence(2, fact))))
        val written = lin.copy(manualAttributes = ManualCharacterAttributes(relationships = listOf(ManualRelation("小满", "旅伴"))))
        val links = egoLinks(written, listOf(written, man, ferryman))
        // 手写的一条在；档案里的路人甲没了；别人的旧资料仍并成「对方眼中」。
        assertEquals(listOf("小满", "船夫"), links.map { it.target })
        assertEquals("旅伴", links.first { it.identity == "小满" }.relation)
        // 小满的档案写「林舟 · 旅伴」，在林舟这一侧合成「对方眼中」。
        assertEquals("旅伴", links.first { it.identity == "小满" }.reverse)
        assertEquals("邻居" to "", links.first { it.target == "船夫" }.relation to links.first { it.target == "船夫" }.reverse)
        // 关系数与摘要也认手写的那一组。
        assertEquals(1, characterSummary(written).relationCount)
    }

    @Test fun `a person the user typed in gets their one line from the first written event`() {
        val typed = BookCharacter("渡船人", emptyList(), manualAttributes = ManualCharacterAttributes(
            experiences = listOf(ManualEvent("在码头接应"), ManualEvent("送他过了河"))), userEdited = true)
        assertEquals("在码头接应", characterSummary(typed).tagline)
        assertEquals(0, characterSummary(typed).relationCount)
    }

    @Test fun `ego positions are deterministic around the centre starting at the top`() {
        val positions = egoPositions(4)
        assertEquals(positions, egoPositions(4))
        assertEquals(.5f, positions[0].first, 1e-4f)
        assertTrue(positions[0].second < .5f)
        positions.forEach { (x, y) -> assertTrue(x in 0f..1f && y in 0f..1f) }
    }
}

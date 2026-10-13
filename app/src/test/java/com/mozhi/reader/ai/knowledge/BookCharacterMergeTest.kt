package com.mozhi.reader.ai.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCharacterMergeTest {

    private var start = 0
    private fun fact(text: String, chapter: Int = 0): CharacterEvidence {
        val quote = "$text。"
        val evidence = CharacterEvidence(chapter, KnowledgeFact(text, quote, start, start + quote.length))
        start += quote.length
        return evidence
    }

    private fun person(name: String, vararg facts: CharacterEvidence, description: String? = null, source: String? = null,
        attributes: List<BookCharacterAttribute> = emptyList(), relationships: List<BookCharacterRelationship> = emptyList()) =
        BookCharacter(name, facts.toList(), description, source, attributes, relationships)

    private fun guide(vararg people: BookCharacter, scanned: Int = 10, bounded: Boolean = false, mode: String = BookCharacterGuide.MODE_QUICK) =
        BookCharacterGuide(people.toList(), scanned, 1_000, bounded, mode = mode)

    @Test fun `merging keeps every existing person and appends the new ones`() {
        val previous = guide(person("林冲", fact("八十万禁军教头")))
        val fresh = guide(person("鲁智深", fact("倒拔垂杨柳")))
        val merged = BookCharactersCodec.mergeTargeted(fresh, previous)
        assertEquals(listOf("林冲", "鲁智深"), merged.characters.map { it.name })
    }

    @Test fun `merging an existing person unions facts attributes and relationships`() {
        val previous = guide(person("林冲", fact("教头", 1),
            attributes = listOf(BookCharacterAttribute(CharacterAttributeKind.ALIAS, "豹子头", fact("人称豹子头", 1)))))
        val fresh = guide(person("林冲", fact("雪夜上梁山", 8), fact("教头", 1),
            relationships = listOf(BookCharacterRelationship("鲁智深", "结义兄弟", fact("与鲁智深结义", 8)))))
        val merged = BookCharactersCodec.mergeTargeted(fresh, previous).characters.single()
        assertEquals(listOf("教头", "雪夜上梁山"), merged.evidence.map { it.fact.text }.sorted())
        assertEquals(listOf("豹子头"), merged.aliases)
        assertEquals(listOf("鲁智深"), merged.relationships.map { it.target })
    }

    @Test fun `a manual rename keeps both its displayed name and its identity`() {
        val previous = guide(person("豹子头", fact("人称豹子头", 1), description = "我改过的人物资料", source = "豹子头"))
        val fresh = guide(person("林冲", fact("雪夜上梁山", 8), source = "林冲"))
        val merged = BookCharactersCodec.mergeTargeted(fresh, previous)
        // identity 不同 → 两条并存，手动编辑一个字都不能少。
        assertEquals(listOf("豹子头", "林冲"), merged.characters.map { it.name })
        assertEquals("我改过的人物资料", merged.characters.first().manualDescription)
    }

    @Test fun `an aliased scan result merges onto the existing person instead of duplicating it`() {
        // 模型只认出别称、anchor 把 identity 钉成用户输入的大名之后，才和已有条目对上。
        val previous = guide(person("林冲", fact("人称豹子头", 1), description = "我改过的人物资料", source = "林冲"))
        val fresh = BookCharactersCodec.anchor(guide(person("豹子头", fact("雪夜上梁山", 8))), listOf(CharacterTarget("林冲", listOf("豹子头"))))
        val merged = BookCharactersCodec.mergeTargeted(fresh, previous)
        assertEquals(listOf("林冲"), merged.characters.map { it.name })
        assertEquals("我改过的人物资料", merged.characters.single().manualDescription)
        assertEquals(listOf("人称豹子头", "雪夜上梁山"), merged.characters.single().evidence.map { it.fact.text }.sorted())
    }

    @Test fun `the merged guide stays spoiler free only when both scans were bounded`() {
        assertTrue(BookCharactersCodec.mergeTargeted(guide(person("林冲"), bounded = true), guide(person("武松"), bounded = true)).progressBounded)
        assertFalse(BookCharactersCodec.mergeTargeted(guide(person("林冲"), bounded = false), guide(person("武松"), bounded = true)).progressBounded)
    }

    @Test fun `merging advances the counters but never rewrites the mode`() {
        val previous = guide(person("林冲"), scanned = 40, mode = BookCharacterGuide.MODE_QUICK)
        val merged = BookCharactersCodec.mergeTargeted(guide(person("武松"), scanned = 12, mode = BookCharacterGuide.MODE_TARGETED), previous)
        assertEquals(40, merged.scannedChapters)
        assertEquals(BookCharacterGuide.MODE_QUICK, merged.mode)
        assertEquals(BookCharacterGuide.MODE_TARGETED, BookCharactersCodec.mergeTargeted(guide(person("武松"), mode = BookCharacterGuide.MODE_TARGETED), null).mode)
    }

    @Test fun `anchoring pins an alias hit to the name the user typed`() {
        val anchored = BookCharactersCodec.anchor(guide(person("晓明", fact("晓明出场")), person("武松", fact("武松打虎"))),
            listOf(CharacterTarget("黄晓明", listOf("晓明", "绿大暗"))))
        assertEquals(listOf("黄晓明", "武松"), anchored.characters.map { it.identity })
        assertEquals("晓明", anchored.characters.first().name)
    }

    @Test fun `anchoring leaves an already owned identity alone`() {
        val anchored = BookCharactersCodec.anchor(guide(person("晓明", fact("晓明出场"), source = "本人改过")), listOf(CharacterTarget("黄晓明", listOf("晓明"))))
        assertEquals("本人改过", anchored.characters.single().identity)
    }

    @Test fun `merging into nothing publishes the scanned guide as is`() {
        val fresh = guide(person("林冲", fact("教头")))
        assertEquals(fresh, BookCharactersCodec.mergeTargeted(fresh, null))
    }

    @Test fun `a freshly written dossier beats the evidence a full scan left behind`() {
        val previous = guide(BookCharacter("林舟", listOf(fact("在灯塔等候"))))
        val fresh = guide(BookCharacter("林舟", emptyList(), dossier = CharacterDossier(tagline = "守灯人"),
            role = CharacterRole.SUPPORTING, mentions = 7))
        val merged = BookCharactersCodec.mergeTargeted(fresh, previous).characters.single()
        assertEquals("守灯人", merged.dossier?.tagline)
        assertEquals(CharacterRole.SUPPORTING, merged.role)
        assertEquals(7, merged.mentions)
        assertEquals(listOf("在灯塔等候"), merged.evidence.map { it.fact.text })
    }

    @Test fun `a later scan without a dossier never wipes one that is already there`() {
        val previous = guide(BookCharacter("林舟", listOf(fact("在灯塔等候")),
            dossier = CharacterDossier(tagline = "守灯人"), role = CharacterRole.PROTAGONIST))
        val merged = BookCharactersCodec.mergeTargeted(guide(BookCharacter("林舟", listOf(fact("灯还亮着")))), previous).characters.single()
        assertEquals("守灯人", merged.dossier?.tagline)
        assertEquals(CharacterRole.PROTAGONIST, merged.role)
    }

    @Test fun `merged people stay uniquely keyed`() {
        val previous = guide(person("林冲"), person("武松"))
        val fresh = guide(person("林冲", fact("后来的事")), person("林冲", fact("再后来的事")))
        val merged = BookCharactersCodec.mergeTargeted(fresh, previous)
        // 合并结果里 identity 唯一，Compose 懒加载的 key 才不会撞车。
        assertEquals(merged.characters.size, merged.characters.map { it.identity }.distinct().size)
        assertEquals(listOf("林冲", "武松"), merged.characters.map { it.name })
    }
}

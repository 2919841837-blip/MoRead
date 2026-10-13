package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        attributes: List<BookCharacterAttribute> = emptyList(), relationships: List<BookCharacterRelationship> = emptyList(),
        dossier: CharacterDossier? = null, role: CharacterRole? = null, manual: ManualCharacterAttributes? = null) =
        BookCharacter(name, facts.toList(), description, source, attributes, relationships, dossier, role, manualAttributes = manual)

    /** 用户手写的一层覆盖：这里只列用得到的几项。 */
    private fun layer(identity: String? = null, age: String? = null, aliases: List<String>? = null,
        role: CharacterRole? = null) = ManualCharacterAttributes(identity = identity, age = age, aliases = aliases, role = role)

    private fun entity(guide: BookCharacterGuide, revision: String = "a".repeat(64)) =
        BookCharacterGuideEntity(1, "gen", revision, "model", "模型", BookCharactersCodec.PROMPT_VERSION,
            AiJson.encodeToString(guide), 0)

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

    @Test fun `a full rescan keeps the hand written fields and refreshes everything else`() {
        val previous = guide(person("林舟", fact("在灯塔等候"), manual = layer(identity = "摆渡人", age = "")))
        val fresh = guide(person("林舟", fact("灯还亮着"),
            attributes = listOf(BookCharacterAttribute(CharacterAttributeKind.ALIAS, "阿舟", fact("人称阿舟"))),
            dossier = CharacterDossier(gender = "男", age = "三十")))
        val merged = BookCharactersCodec.mergeManual(fresh, previous).characters.single()
        assertEquals("摆渡人", merged.attributeValues(CharacterAttributeKind.IDENTITY).single())
        // 用户清空的年龄不会被重扫填回来。
        assertEquals(emptyList<String>(), merged.attributeValues(CharacterAttributeKind.AGE))
        // 没接管的字段照旧由 AI 维护。
        assertEquals("男", merged.attributeValues(CharacterAttributeKind.GENDER).single())
        assertEquals(listOf("阿舟"), merged.aliases)
        assertEquals(listOf("灯还亮着"), merged.evidence.map { it.fact.text })
    }

    @Test fun `a person the user only edited attributes on is never swallowed by the AI result`() {
        // 手动条目 = 写过资料 **或** 改过属性：只改属性的人不能被 AI 结果整条替掉。
        val previous = guide(person("林舟", fact("在灯塔等候"), manual = layer(identity = "摆渡人")))
        val merged = BookCharactersCodec.mergeManual(guide(person("林舟", fact("灯还亮着"))), previous).characters.single()
        assertEquals("摆渡人", merged.attributeValues(CharacterAttributeKind.IDENTITY).single())
        assertEquals(listOf("灯还亮着"), merged.evidence.map { it.fact.text })
    }

    @Test fun `an attribute only person missing from the fresh scan comes back with the manual layer and nothing else`() {
        val previous = guide(person("船夫", fact("路过"), manual = layer(identity = "摆渡人")))
        val merged = BookCharactersCodec.mergeManual(guide(person("林舟", fact("灯还亮着"))), previous)
        assertEquals(listOf("林舟", "船夫"), merged.characters.map { it.name })
        val kept = merged.characters.last()
        assertEquals("摆渡人", kept.attributeValues(CharacterAttributeKind.IDENTITY).single())
        // 重扫可能换了正文版本，AI 依据一律丢弃，手写层留下。
        assertTrue(kept.evidence.isEmpty())
        assertEquals("摆渡人", kept.manualAttributes?.identity)
    }

    @Test fun `a targeted scan merges fresh AI values into a person the user has taken over`() {
        val previous = guide(person("林舟", fact("在灯塔等候"),
            manual = layer(identity = "摆渡人", role = CharacterRole.SUPPORTING)))
        val fresh = guide(person("林舟", fact("灯还亮着"), role = CharacterRole.PROTAGONIST,
            attributes = listOf(BookCharacterAttribute(CharacterAttributeKind.ALIAS, "阿舟", fact("人称阿舟")))))
        val merged = BookCharactersCodec.mergeTargeted(fresh, previous).characters.single()
        assertEquals("摆渡人", merged.attributeValues(CharacterAttributeKind.IDENTITY).single())
        assertEquals(listOf("阿舟"), merged.aliases)
        // mergeTargeted 只刷新 AI 基线：档位仍由用户压住，切回「跟随 AI」时拿到的是这次的新判定。
        assertEquals(CharacterRole.PROTAGONIST, merged.role)
        assertEquals(CharacterRole.SUPPORTING, merged.effectiveRole())
        assertEquals(listOf("在灯塔等候", "灯还亮着"), merged.evidence.map { it.fact.text })
    }

    @Test fun `a hand written role outlives both merge paths while the AI stays free to judge`() {
        val owned = person("林舟", fact("在灯塔等候"), manual = layer(role = CharacterRole.PROTAGONIST))
        val full = BookCharactersCodec.mergeManual(guide(owned.copy(role = CharacterRole.SUPPORTING)), guide(owned)).characters.single()
        assertEquals(CharacterRole.SUPPORTING, full.role)
        assertEquals(CharacterRole.PROTAGONIST, full.effectiveRole())
        val targeted = BookCharactersCodec.mergeTargeted(guide(owned.copy(role = CharacterRole.MAJOR)), guide(owned)).characters.single()
        assertEquals(CharacterRole.MAJOR, targeted.role)
        assertEquals(CharacterRole.PROTAGONIST, targeted.effectiveRole())
    }

    @Test fun `a released field goes back to the AI the moment the layer is dropped`() {
        val owned = person("林舟", fact("在灯塔等候"), manual = layer(identity = "摆渡人", role = CharacterRole.MAJOR))
            .copy(role = CharacterRole.SUPPORTING, dossier = CharacterDossier(identity = "灯塔守望人"))
        assertEquals("摆渡人", owned.attributeValues(CharacterAttributeKind.IDENTITY).single())
        assertEquals(CharacterRole.MAJOR, owned.effectiveRole())
        val released = owned.copy(manualAttributes = null)
        assertEquals("灯塔守望人", released.attributeValues(CharacterAttributeKind.IDENTITY).single())
        assertEquals(CharacterRole.SUPPORTING, released.effectiveRole())
    }

    @Test fun `a reimport throws the AI facts away but keeps everyone the user has written to`() {
        val entry = entity(guide(person("林舟", fact("在灯塔等候"), manual = layer(identity = "摆渡人")),
            person("小满", fact("送来一封信"))), revision = "旧版")
        val people = BookCharactersCodec.visible(entry, "新版")!!.guide.characters
        assertEquals(listOf("林舟"), people.map { it.name })
        assertEquals("摆渡人", people.single().attributeValues(CharacterAttributeKind.IDENTITY).single())
        assertTrue(people.single().evidence.isEmpty())
    }
}

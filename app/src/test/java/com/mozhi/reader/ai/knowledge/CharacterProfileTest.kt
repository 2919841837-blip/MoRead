package com.mozhi.reader.ai.knowledge

import org.junit.Assert.*
import org.junit.Test

class CharacterProfileTest {
    private val source = "林舟又名阿舟，今年十八岁。林舟是小满的哥哥。"
    private val raw = """{"characters":[{"name":"林舟","facts":[{"text":"林舟登场","quote":"林舟又名阿舟，今年十八岁。"}],
        "attributes":[{"kind":"ALIAS","value":"阿舟","quote":"林舟又名阿舟，今年十八岁。"},{"kind":"AGE","value":"十八岁","quote":"林舟又名阿舟，今年十八岁。"}],
        "relationships":[{"target":"小满","relation":"哥哥","quote":"林舟是小满的哥哥。"}]}]}"""

    @Test fun structuredFieldsUseVerifiedOffsetsAndExplicitAliasesMergeAcrossChapters() {
        val people = ChapterKnowledgeCodec.parseCharacters(raw, KnowledgePart(50, source))
        assertEquals(50, people.single().attributes.first().fact.start)
        val aliasFact = KnowledgeFact("阿舟守夜", "阿舟独自守夜。", 0, 7)
        val guide = BookCharactersCodec.fromChapters(listOf(0 to people, 1 to listOf(KnowledgeCharacter("阿舟", listOf(aliasFact)))), 2, 80)
        val person = guide.characters.single()
        assertEquals("林舟", person.name)
        assertEquals(listOf("阿舟"), person.aliases)
        assertEquals("哥哥", person.relationships.single().relation)
        assertEquals(2, person.evidence.size)
        val renamed = BookCharactersCodec.edit(guide, person.identity, "林先生", null).characters.single()
        assertEquals(person.attributes, renamed.attributes)
        assertTrue(ExtractedCharacterCard.from(renamed).description.contains("十八岁"))
    }

    @Test fun unrelatedOrInventedProfileQuotesAreDroppedOneByOne() {
        // 一条关系引文是编造的：只丢这一条，事实与其余属性照常保留。
        val inventedRelation = ChapterKnowledgeCodec.parseCharacters(raw.replace("林舟是小满的哥哥。", "他们是兄弟。"), KnowledgePart(0, source)).single()
        assertTrue(inventedRelation.relationships.isEmpty())
        assertEquals(2, inventedRelation.attributes.size)
        // 别名不在引文里：丢掉别名，年龄留下。
        val unrelatedAlias = ChapterKnowledgeCodec.parseCharacters(raw.replace("\"value\":\"阿舟\"", "\"value\":\"小舟\""), KnowledgePart(0, source)).single()
        assertEquals(listOf(CharacterAttributeKind.AGE), unrelatedAlias.attributes.map { it.kind })
        // 全部条目都对不上才报错。
        assertThrows(IllegalArgumentException::class.java) {
            ChapterKnowledgeCodec.parseCharacters("""{"characters":[{"name":"林舟","facts":[{"text":"x","quote":"林舟去了远方。"}]}]}""", KnowledgePart(0, source))
        }
    }

    @Test fun punctuationDriftUnknownKindsAndNearbyNamesAreTolerated() {
        val text = "林舟站在船头，一言不发。他总是把担心藏在心里，嘴上却说“没事”。"
        val drifted = """{"characters":[{"name":"林舟","facts":[{"text":"沉默","quote":"林舟站在船头, 一言不发。"}],
            "attributes":[{"kind":"personality","value":"嘴硬心软","quote":"他总是把担心藏在心里，嘴上却说\"没事\"。"},
                {"kind":"TRAIT","value":"未知种类","quote":"林舟站在船头"}]}]}"""
        val person = ChapterKnowledgeCodec.parseCharacters(drifted, KnowledgePart(0, text)).single()
        val fact = person.facts.single()
        assertEquals(text.substring(fact.start, fact.end), fact.quote)
        assertEquals("林舟站在船头，一言不发。", fact.quote)
        val trait = person.attributes.single()
        assertEquals(CharacterAttributeKind.PERSONALITY, trait.kind)
        assertEquals(text.substring(trait.fact.start, trait.fact.end), trait.fact.quote)
        assertEquals("嘴硬心软", ExtractedCharacterCard.from(BookCharactersCodec.fromChapters(listOf(0 to listOf(person)), 1, 1).characters.single()).personality)
    }
}

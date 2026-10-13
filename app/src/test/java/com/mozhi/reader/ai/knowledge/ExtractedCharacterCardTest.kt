package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.persona.SillyTavernCardParser
import org.junit.Assert.*
import org.junit.Test

class ExtractedCharacterCardTest {
    @Test fun exportedCardCanBeImportedWithoutInventedTraitsOrLosingEvidence() {
        val card = ExtractedCharacterCard("Alice", "读者，喜欢读书。\n（第 1 章依据：Alice opened the book.）")
        val imported = SillyTavernCardParser.parse(card.toJson().toByteArray())!!
        assertEquals(card.name, imported.name)
        assertTrue(imported.personality.contains(card.description))
        assertEquals("", imported.greeting)
        assertTrue(imported.worldBook.isEmpty())
    }

    @Test fun dossierCardsCarryTheBiographyRelationsAndPersonality() {
        val person = BookCharacter("林舟", emptyList(), manualDescription = "手动补充：怕水。", dossier = CharacterDossier(
            tagline = "守灯人", bio = "他守着灯塔。", identity = "灯塔守望人",
            personality = listOf(DossierTrait("执着", "雨夜也点灯"), DossierTrait("沉默")),
            experiences = listOf(DossierEvent("收到来信", 1)), relationships = listOf(DossierRelation("小满", "同伴", "一起出发"))))
        val card = ExtractedCharacterCard.from(person)
        listOf("守灯人", "身份：灯塔守望人", "他守着灯塔。", "小满（同伴）：一起出发", "- 收到来信", "手动补充：怕水。").forEach {
            assertTrue(it, card.description.contains(it))
        }
        assertEquals("执着（雨夜也点灯）、沉默", card.personality)
    }
}

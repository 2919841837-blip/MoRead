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
        val person = BookCharacter("林舟", emptyList(), dossier = CharacterDossier(
            tagline = "守灯人", bio = "他守着灯塔。", identity = "灯塔守望人",
            personality = listOf(DossierTrait("执着", "雨夜也点灯"), DossierTrait("沉默")),
            experiences = listOf(DossierEvent("收到来信", 1)), relationships = listOf(DossierRelation("小满", "同伴", "一起出发")),
            arc = "从隐居到重新点灯。"))
        val card = ExtractedCharacterCard.from(person)
        listOf("守灯人", "身份：灯塔守望人", "他守着灯塔。", "小满（同伴）：一起出发", "- 收到来信", "成长：从隐居到重新点灯。").forEach {
            assertTrue(it, card.description.contains(it))
        }
        assertEquals("执着（雨夜也点灯）、沉默", card.personality)
    }

    @Test fun handWrittenExperiencesArcAndRelationsAreExported() {
        val person = BookCharacter("林舟", emptyList(), dossier = CharacterDossier(bio = "他守着灯塔。"),
            manualAttributes = ManualCharacterAttributes(
                experiences = listOf(ManualEvent("修好了灯", 4), ManualEvent("回了老家")),
                arc = "放下执念。", relationships = listOf(ManualRelation("苏晚", "师姐", "常来看他"))))
        val card = ExtractedCharacterCard.from(person)
        listOf("- 修好了灯", "- 回了老家", "成长：放下执念。", "苏晚（师姐）：常来看他").forEach {
            assertTrue(it, card.description.contains(it))
        }
    }

    @Test fun clearingTheWrittenRelationsAndExperiencesLeavesTheDossierOut() {
        val person = BookCharacter("林舟", emptyList(), dossier = CharacterDossier(identity = "灯塔守望人",
            experiences = listOf(DossierEvent("收到来信", 1)), relationships = listOf(DossierRelation("小满", "同伴"))),
            manualAttributes = ManualCharacterAttributes(experiences = emptyList(), relationships = emptyList()))
        val card = ExtractedCharacterCard.from(person)
        assertFalse(card.description.contains("收到来信"))
        assertFalse(card.description.contains("小满"))
        assertTrue(card.description.contains("身份：灯塔守望人"))
    }

    @Test fun legacyNotesSurfaceAsExperiencesUntilTheReaderWritesTheirOwn() {
        // 上一版的「手动资料」不再是独立字段，但它写的字不能白写：当作这个人的经历导出去。
        val person = BookCharacter("林舟", emptyList(), manualDescription = "怕水。\n在灯塔长住。",
            dossier = CharacterDossier(tagline = "守灯人"))
        val card = ExtractedCharacterCard.from(person)
        assertTrue(card.description.contains("- 怕水。"))
        assertTrue(card.description.contains("- 在灯塔长住。"))
    }

    @Test fun handWrittenAttributesAreExportedWithTheirNotes() {
        val person = BookCharacter("林舟", emptyList(), manualAttributes = ManualCharacterAttributes(
            identity = "摆渡人", age = "三十", appearance = "瘦高，左手有疤",
            aliases = listOf("阿舟", "灯奴"), bio = "他守着灯塔。", personality = listOf(ManualTrait("执着", "雨夜也点灯"))))
        val card = ExtractedCharacterCard.from(person)
        listOf("身份：摆渡人", "年龄：三十", "别名：阿舟、灯奴", "外貌：瘦高，左手有疤", "他守着灯塔。").forEach {
            assertTrue(it, card.description.contains(it))
        }
        assertEquals("执着（雨夜也点灯）", card.personality)
    }

    @Test fun aClearedFieldIsExportedAsEmptyInsteadOfTheDossierValue() {
        val person = BookCharacter("林舟", emptyList(), dossier = CharacterDossier(identity = "灯塔守望人", age = "三十"),
            manualAttributes = ManualCharacterAttributes(identity = "摆渡人", age = ""))
        val card = ExtractedCharacterCard.from(person)
        assertTrue(card.description.contains("身份：摆渡人"))
        // 接管 ≠ 回落：清空的年龄既不显示模型值，也不显示手写值。
        assertFalse(card.description.contains("灯塔守望人"))
        assertFalse(card.description.contains("年龄"))
    }

    @Test fun anOldGuideWithNeitherDossierNorWrittenAttributesStillExportsItsFacts() {
        val fact = CharacterEvidence(0, KnowledgeFact("守着灯塔", "林舟在灯塔等候。", 0, 8))
        val card = ExtractedCharacterCard.from(BookCharacter("林舟", listOf(fact),
            attributes = listOf(BookCharacterAttribute(CharacterAttributeKind.IDENTITY, "守灯人", fact))))
        assertTrue(card.description.contains("身份：守灯人"))
        assertTrue(card.description.contains("第 1 章依据"))
    }
}

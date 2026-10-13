package com.mozhi.reader.ai.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterAttributeDraftTest {
    private val fact = KnowledgeFact("守着灯塔", "林舟在灯塔等候。", 0, 8)

    /** 一份完整档案的人物：编辑器的「AI 值」就是这些。 */
    private fun anchored(manual: ManualCharacterAttributes? = null) = BookCharacter("林舟", emptyList(),
        attributes = listOf(BookCharacterAttribute(CharacterAttributeKind.ALIAS, "阿舟", CharacterEvidence(0, fact))),
        dossier = CharacterDossier(tagline = "守灯人", bio = "他守着灯塔。", identity = "灯塔守望人", gender = "男",
            age = "三十", appearance = "瘦高", personality = listOf(DossierTrait("执着", "雨夜也点灯"))),
        manualAttributes = manual)

    @Test fun `aliases split on every separator a reader might type and join back with a comma`() {
        assertEquals(listOf("阿舟", "灯奴", "守灯人"), CharacterAttributeDraftCodec.parseAliases("阿舟、灯奴，守灯人"))
        assertEquals(listOf("阿舟", "灯奴"), CharacterAttributeDraftCodec.parseAliases("阿舟; 灯奴\n阿舟"))
        assertEquals(listOf("阿舟", "灯奴"), CharacterAttributeDraftCodec.parseAliases("阿舟, 灯奴；"))
        assertEquals(emptyList<String>(), CharacterAttributeDraftCodec.parseAliases("  \n  "))
        assertEquals("阿舟、灯奴", CharacterAttributeDraftCodec.formatAliases(listOf("阿舟", "灯奴")))
    }

    @Test fun `aliases are capped in count and in width`() {
        assertEquals(CharacterAttributeDraftCodec.MAX_ALIASES,
            CharacterAttributeDraftCodec.parseAliases((1..12).joinToString("、") { "别称$it" }).size)
        assertEquals(CharacterAttributeDraftCodec.MAX_ALIAS,
            CharacterAttributeDraftCodec.parseAliases("甲".repeat(60)).single().length)
    }

    @Test fun `personality is one trait per line with an optional note in either kind of bracket`() {
        assertEquals(listOf(ManualTrait("执着", "雨夜也点灯"), ManualTrait("嘴硬心软"), ManualTrait("沉默", "很少开口")),
            CharacterAttributeDraftCodec.parsePersonality("执着（雨夜也点灯）\n 嘴硬心软 \n沉默(很少开口)"))
        // 括号没写全也不吞掉说明。
        assertEquals(listOf(ManualTrait("沉默", "很少开口")), CharacterAttributeDraftCodec.parsePersonality("沉默（很少开口"))
        assertEquals("执着（雨夜也点灯）\n沉默", CharacterAttributeDraftCodec.formatPersonality(
            listOf(DossierTrait("执着", "雨夜也点灯"), DossierTrait("沉默"))))
    }

    @Test fun `personality keeps at most five traits and truncates the long ones`() {
        assertEquals(CharacterAttributeDraftCodec.MAX_TRAITS,
            CharacterAttributeDraftCodec.parsePersonality((1..9).joinToString("\n") { "性格$it" }).size)
        // 同一个词只留第一条。
        assertEquals(1, CharacterAttributeDraftCodec.parsePersonality("执着\n执着（另一说）").size)
        assertEquals(CharacterAttributeDraftCodec.MAX_TRAIT, CharacterAttributeDraftCodec.parsePersonality("执".repeat(30)).single().trait.length)
        assertEquals(CharacterAttributeDraftCodec.MAX_DETAIL, CharacterAttributeDraftCodec.parsePersonality("执着（${"灯".repeat(300)}）").single().detail.length)
    }

    @Test fun `opening the editor without touching anything hands every field back to the AI`() {
        val person = anchored()
        assertNull(CharacterAttributeDraftCodec.merge(person, CharacterAttributeDraftCodec.baseline(person)))
        // 只有真改过的那一项留下，其余全是 null。
        val layer = CharacterAttributeDraftCodec.merge(person, CharacterAttributeDraftCodec.baseline(person).copy(identity = "摆渡人"))!!
        assertEquals("摆渡人", layer.identity)
        assertNull(layer.gender)
        assertNull(layer.age)
        assertNull(layer.appearance)
        assertNull(layer.tagline)
        assertNull(layer.bio)
        assertNull(layer.aliases)
        assertNull(layer.personality)
        assertNull(layer.role)
    }

    @Test fun `clearing a field keeps it cleared instead of falling back to the AI value`() {
        val person = anchored()
        val layer = CharacterAttributeDraftCodec.merge(person, CharacterAttributeDraftCodec.baseline(person).copy(age = "", aliases = ""))!!
        assertEquals("", layer.age)
        assertEquals(emptyList<String>(), layer.aliases)
        assertNull(layer.identity)
        assertEquals(emptyList<String>(), person.copy(manualAttributes = layer).attributeValues(CharacterAttributeKind.AGE))
    }

    @Test fun `an owned field is refilled with the written value and restoring it hands it back`() {
        val owned = anchored(ManualCharacterAttributes(identity = "摆渡人"))
        assertEquals("摆渡人", CharacterAttributeDraftCodec.baseline(owned).identity)
        assertEquals("灯塔守望人", CharacterAttributeDraftCodec.aiBaseline(owned).identity)
        // 「恢复 AI 内容」把文本框填回模型原值，保存时这一项自然交回 AI —— 其余字段一个字都没动。
        val restored = CharacterAttributeDraftCodec.baseline(owned).copy(identity = CharacterAttributeDraftCodec.aiBaseline(owned).identity)
        assertNull(CharacterAttributeDraftCodec.merge(owned, restored))
    }

    @Test fun `over long typing is truncated at the storage limits`() {
        val person = anchored()
        val layer = CharacterAttributeDraftCodec.merge(person, CharacterAttributeDraftCodec.baseline(person)
            .copy(identity = "摆".repeat(80), bio = "渡".repeat(700), tagline = "灯".repeat(60), appearance = "瘦".repeat(400)))!!
        assertEquals(CharacterAttributeDraftCodec.MAX_IDENTITY, layer.identity!!.length)
        assertEquals(CharacterAttributeDraftCodec.MAX_BIO, layer.bio!!.length)
        assertEquals(CharacterAttributeDraftCodec.MAX_TAGLINE, layer.tagline!!.length)
        assertEquals(CharacterAttributeDraftCodec.MAX_APPEARANCE, layer.appearance!!.length)
    }

    @Test fun `the role picker states ownership itself and defaults to following the AI`() {
        val person = anchored().copy(role = CharacterRole.MAJOR)
        // 没写档位 = 跟随 AI：编辑器显示「AI 判定：主要」，这一项不落入手动层。
        assertNull(CharacterAttributeDraftCodec.baseline(person).role)
        assertNull(CharacterAttributeDraftCodec.merge(person, CharacterAttributeDraftCodec.baseline(person)))
        // 选一个与 AI 当前值相同的档位 = 「就按这个，别再改」：主次刻意不跟 AI 原值比较。
        assertEquals(CharacterRole.MAJOR,
            CharacterAttributeDraftCodec.merge(person, CharacterAttributeDraftCodec.baseline(person).copy(role = CharacterRole.MAJOR))!!.role)
        assertEquals(CharacterRole.SUPPORTING,
            CharacterAttributeDraftCodec.merge(person, CharacterAttributeDraftCodec.baseline(person).copy(role = CharacterRole.SUPPORTING))!!.role)
    }

    @Test fun `a manual person with nothing but a name starts from an empty baseline`() {
        val baseline = CharacterAttributeDraftCodec.baseline(BookCharacter("船夫", emptyList()))
        assertEquals(CharacterAttributeDraft("", "", "", "", "", "", "", "", null, "", "", ""), baseline)
        val layer = CharacterAttributeDraftCodec.merge(null, baseline.copy(identity = "摆渡人", aliases = "阿舟"))!!
        assertEquals("摆渡人", layer.identity)
        assertEquals(listOf("阿舟"), layer.aliases)
    }

    // —— 经历 / 成长变化 / 社会关系：一行一条的文本语法 ——

    @Test fun `an experience line may lead with a chapter number in any of the usual shapes`() {
        assertEquals(ManualEvent("修好了灯", 4), CharacterAttributeDraftCodec.parseExperiences("第5章 · 修好了灯").single())
        assertEquals(ManualEvent("修好了灯", 4), CharacterAttributeDraftCodec.parseExperiences("第5章、修好了灯").single())
        assertEquals(ManualEvent("修好了灯", 4), CharacterAttributeDraftCodec.parseExperiences("第 5 章：修好了灯").single())
        assertEquals(ManualEvent("修好了灯", 2), CharacterAttributeDraftCodec.parseExperiences("第3章 修好了灯").single())
        // 没写章节就是「未注明章节」，整行原样当事件。
        assertEquals(ManualEvent("第几章的事来着，他修好了灯", null),
            CharacterAttributeDraftCodec.parseExperiences("第几章的事来着，他修好了灯").single())
        assertEquals(listOf(ManualEvent("等他", 0), ManualEvent("回了老家", null)),
            CharacterAttributeDraftCodec.parseExperiences("第1章 · 等他\n\n回了老家\n  "))
    }

    @Test fun `experiences are formatted back with the chapter number and round trip`() {
        val events = listOf(ManualEvent("等他", 0), ManualEvent("回了老家"))
        assertEquals("第1章 · 等他\n回了老家", CharacterAttributeDraftCodec.formatExperiences(events.map { DossierEvent(it.text, it.chapterIndex) }))
        // 规范化过一次之后再解析还是同一份（幂等），编辑器「没动过」才交得回 AI。
        val once = CharacterAttributeDraftCodec.formatExperiences(events.map { DossierEvent(it.text, it.chapterIndex) })
        val twice = CharacterAttributeDraftCodec.formatExperiences(
            CharacterAttributeDraftCodec.parseExperiences(once).map { DossierEvent(it.text, it.chapterIndex) })
        assertEquals(once, twice)
    }

    @Test fun `experiences and relations are capped in count and in width`() {
        assertEquals(CharacterAttributeDraftCodec.MAX_EVENTS,
            CharacterAttributeDraftCodec.parseExperiences((1..40).joinToString("\n") { "事件$it" }).size)
        assertEquals(CharacterAttributeDraftCodec.MAX_EVENT,
            CharacterAttributeDraftCodec.parseExperiences("事".repeat(400)).single().text.length)
        assertEquals(CharacterAttributeDraftCodec.MAX_RELATIONS,
            CharacterAttributeDraftCodec.parseRelations((1..40).joinToString("\n") { "人$it" }).size)
        assertEquals(CharacterAttributeDraftCodec.MAX_REL_NAME,
            CharacterAttributeDraftCodec.parseRelations("名".repeat(60)).single().target.length)
    }

    @Test fun `one relation per line takes a bracketed role and an optional note`() {
        assertEquals(listOf(ManualRelation("小满", "同伴", "一起出发"), ManualRelation("苏晚", "师姐"), ManualRelation("船夫")),
            CharacterAttributeDraftCodec.parseRelations("小满（同伴）：一起出发\n苏晚(师姐)\n船夫"))
        // 说明能用半角冒号，括号没写全也当有括号。
        assertEquals(ManualRelation("小满", "同伴", "一起出发"), CharacterAttributeDraftCodec.parseRelations("小满（同伴: 一起出发").single())
        // 没写关系只写说明：整行当对象就错了，所以「：」前面仍是对象。
        assertEquals(ManualRelation("小满", "", "一起出发"), CharacterAttributeDraftCodec.parseRelations("小满：一起出发").single())
        assertEquals("小满（同伴）：一起出发",
            CharacterAttributeDraftCodec.formatRelations(listOf(DossierRelation("小满", "同伴", "一起出发"))))
        // 同一个人只留第一条。
        assertEquals(1, CharacterAttributeDraftCodec.parseRelations("小满（同伴）\n小满（敌人）").size)
    }

    @Test fun `opening the editor on a person with a dossier hands the whole timeline back to the AI`() {
        val person = anchored().copy(dossier = CharacterDossier(arc = "从隐居到重新点灯。",
            experiences = listOf(DossierEvent("收到来信", 1), DossierEvent("修好了灯", 4)),
            relationships = listOf(DossierRelation("小满", "同伴", "一起出发"))))
        val draft = CharacterAttributeDraftCodec.baseline(person)
        assertEquals("第2章 · 收到来信\n第5章 · 修好了灯", draft.experiences)
        assertEquals("从隐居到重新点灯。", draft.arc)
        assertEquals("小满（同伴）：一起出发", draft.relations)
        // 一个字都没动 → 三项都交回 AI。
        assertNull(CharacterAttributeDraftCodec.merge(person, draft))
    }

    @Test fun `a legacy manual note prefilled into the timeline is not claimed by saving it untouched`() {
        // 人物资料是遗留载体：它进经历框当初值，但那是「AI 值」，不动它就不算接管。
        val person = BookCharacter("林舟", emptyList(), manualDescription = "怕水。\n在灯塔长住。")
        val draft = CharacterAttributeDraftCodec.baseline(person)
        assertEquals("怕水。\n在灯塔长住。", draft.experiences)
        assertEquals(draft, CharacterAttributeDraftCodec.aiBaseline(person))
        assertNull(CharacterAttributeDraftCodec.merge(person, draft))
        // 真改一行才算接管。
        val layer = CharacterAttributeDraftCodec.merge(person, draft.copy(experiences = "怕水。\n在灯塔长住。\n守灯十年。"))!!
        assertEquals(listOf(ManualEvent("怕水。"), ManualEvent("在灯塔长住。"), ManualEvent("守灯十年。")), layer.experiences)
    }

    @Test fun `the three new fields are taken over one by one and stay empty once cleared`() {
        val person = anchored().copy(dossier = CharacterDossier(arc = "变化。", experiences = listOf(DossierEvent("收到来信", 1)),
            relationships = listOf(DossierRelation("小满", "同伴"))))
        val base = CharacterAttributeDraftCodec.baseline(person)
        val layer = CharacterAttributeDraftCodec.merge(person, base.copy(experiences = "", arc = "", relations = ""))!!
        assertEquals(emptyList<ManualEvent>(), layer.experiences)
        assertEquals("", layer.arc)
        assertEquals(emptyList<ManualRelation>(), layer.relationships)
        assertEquals(emptyList<String>(), person.copy(manualAttributes = layer).experiences().map { it.text })
        assertEquals("", person.copy(manualAttributes = layer).arc())
        // 只改一项时其余两项仍是 null（没接管）。
        val onlyArc = CharacterAttributeDraftCodec.merge(person, base.copy(arc = "换了个活法。"))!!
        assertEquals("换了个活法。", onlyArc.arc)
        assertNull(onlyArc.experiences)
        assertNull(onlyArc.relationships)
    }

    @Test fun `restoring the AI timeline hands the three fields back`() {
        val person = anchored().copy(dossier = CharacterDossier(arc = "变化。",
            relationships = listOf(DossierRelation("小满", "同伴"))))
        val owned = person.copy(manualAttributes = ManualCharacterAttributes(
            experiences = listOf(ManualEvent("我写的")), arc = "我写的", relationships = listOf(ManualRelation("我写的"))))
        val draft = CharacterAttributeDraftCodec.baseline(owned)
        assertEquals("我写的", draft.experiences)
        assertEquals("我写的", draft.arc)
        assertEquals("我写的", draft.relations)
        val restored = draft.copy(experiences = CharacterAttributeDraftCodec.aiBaseline(owned).experiences,
            arc = CharacterAttributeDraftCodec.aiBaseline(owned).arc,
            relations = CharacterAttributeDraftCodec.aiBaseline(owned).relations)
        assertNull(CharacterAttributeDraftCodec.merge(owned, restored))
    }

    @Test fun `legacy relations are prefilled behind the dossier ones and never resurrect a deleted line`() {
        val evidence = CharacterEvidence(0, fact)
        val person = anchored().copy(dossier = CharacterDossier(relationships = listOf(DossierRelation("小满", "同伴"))),
            relationships = listOf(BookCharacterRelationship("小满", "妹妹", evidence), BookCharacterRelationship("船夫", "邻居", evidence)))
        // 旧资料写的是「本人是对方的什么人」，词面照旧，但档案里的那条优先，且不重复列出小满。
        assertEquals("小满（同伴）\n船夫（邻居）", CharacterAttributeDraftCodec.baseline(person).relations)
        val layer = CharacterAttributeDraftCodec.merge(person, CharacterAttributeDraftCodec.baseline(person).copy(relations = "小满（同伴）"))!!
        val after = person.copy(manualAttributes = layer)
        // 接管后旧资料一律不算数：删掉的船夫不会被复活。
        assertEquals(listOf("小满"), after.manualRelations()!!.map { it.target })
        // 「恢复 AI 内容」把旧资料里的船夫一并填回来，这一项才交回 AI。
        assertNull(CharacterAttributeDraftCodec.merge(after, CharacterAttributeDraftCodec.aiBaseline(after)))
    }
}

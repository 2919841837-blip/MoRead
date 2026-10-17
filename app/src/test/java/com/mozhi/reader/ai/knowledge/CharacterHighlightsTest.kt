package com.mozhi.reader.ai.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterHighlightsTest {

    private fun texts(after: String, spans: List<HighlightSpan>) = spans.map { after.substring(it.start, it.end) }

    @Test fun `only the inserted words are marked`() {
        val after = "他在灯塔等候。小满来了。"
        assertEquals(listOf("小满来了。"), texts(after, CharacterHighlights.diffText("他在灯塔等候。", after)))
    }

    @Test fun `an insertion before the old text is still just the insertion`() {
        val after = "黄昏时他在灯塔等候。"
        assertEquals(listOf("黄昏时"), texts(after, CharacterHighlights.diffText("他在灯塔等候。", after)))
    }

    @Test fun `a rewrite of the whole line marks the whole line instead of crumbs`() {
        val before = "甲乙丙丁戊己庚辛壬癸。"
        val after = "天地玄黄宇宙洪荒日月盈昃辰宿列张。"
        assertEquals(listOf(HighlightSpan(0, after.length)), CharacterHighlights.diffText(before, after))
    }

    @Test fun `deleting text marks nothing because there is nowhere to show it`() {
        assertTrue(CharacterHighlights.diffText("他在灯塔等候小满。", "他在灯塔等候。").isEmpty())
    }

    @Test fun `an unchanged field is not marked`() {
        val before = person(bio = "他是灯塔的守塔人，已经在这里住了三十年。")
        assertTrue(CharacterHighlights.diff(before, before).isEmpty())
    }

    @Test fun `only the changed field is marked`() {
        val before = person(bio = "他是灯塔的守塔人，已经在这里住了三十年。", gender = "男")
        val after = before.copy(dossier = before.dossier!!.copy(
            bio = "他是灯塔的守塔人，已经在这里住了三十年。今年春天，他第一次离开了灯塔。"))
        val highlights = CharacterHighlights.diff(before, after)
        assertEquals(listOf(HighlightField.BIO), highlights.map { it.field })
        assertEquals(listOf("今年春天，他第一次离开了灯塔。"),
            texts(after.bio(), highlights.single().spans))
        assertEquals("他是灯塔的守塔人，已经在这里住了三十年。", highlights.single().before)
    }

    @Test fun `a new trait and a changed relation are marked as whole items`() {
        val before = person(dossier = CharacterDossier(
            personality = listOf(DossierTrait("果决", "遇事不犹豫")),
            relationships = listOf(DossierRelation("小满", "同伴", "常一起送信"))))
        val after = before.copy(dossier = before.dossier!!.copy(
            personality = listOf(DossierTrait("果决", "遇事不犹豫"), DossierTrait("寡言", "很少说话")),
            relationships = listOf(DossierRelation("小满", "妹妹"))))
        val highlights = CharacterHighlights.diff(before, after)
        val trait = highlights.single { it.field == HighlightField.PERSONALITY }
        assertEquals("寡言", trait.key)
        assertTrue(trait.wholeItem)
        val relation = highlights.single { it.field == HighlightField.RELATION }
        assertEquals("小满", relation.key)
        assertTrue(relation.wholeItem)
        assertEquals("同伴", relation.before)
    }

    @Test fun `a role change is marked as a whole item`() {
        val before = person(role = CharacterRole.SUPPORTING)
        val highlights = CharacterHighlights.diff(before, before.copy(role = CharacterRole.MAJOR))
        val role = highlights.single()
        assertEquals(HighlightField.ROLE, role.field)
        assertTrue(role.wholeItem)
        assertEquals("SUPPORTING", role.before)
    }

    // —— 增量范围 ——

    @Test fun `an update reads only the text after the recorded boundary`() {
        val chapters = listOf(0 to "林舟在灯塔等候。", 1 to "小满带来一封信。")
        val from = CharacterScanBoundary(0, 8)
        assertEquals(0 to 8, CharacterIncrementalUpdate.startOf(chapters, from))
        assertTrue(CharacterIncrementalUpdate.appears(chapters, from, listOf("小满")))
        // 「林舟」只出现在边界之前，这一轮不该再为他付一次请求。
        assertFalse(CharacterIncrementalUpdate.appears(chapters, from, listOf("林舟")))
    }

    @Test fun `a boundary at the end of the last chapter means there is nothing new`() {
        val chapters = listOf(0 to "林舟在灯塔等候。", 1 to "小满带来一封信。")
        val from = CharacterScanBoundary(1, chapters.last().second.length)
        assertNull(CharacterIncrementalUpdate.startOf(chapters, from))
        assertFalse(CharacterIncrementalUpdate.appears(chapters, from, listOf("小满")))
    }

    @Test fun `a whole book scan leaves nothing to update`() {
        val from = CharacterScanBoundary(Int.MAX_VALUE, Int.MAX_VALUE)
        assertNull(CharacterIncrementalUpdate.startOf(listOf(0 to "林舟"), from))
        assertFalse(CharacterIncrementalUpdate.appears(listOf(0 to "林舟"), from, listOf("林舟")))
    }

    private fun person(bio: String = "", gender: String = "", dossier: CharacterDossier? = null,
        role: CharacterRole? = null) = BookCharacter("林舟", emptyList(), dossier = dossier
        ?: CharacterDossier(bio = bio, gender = gender), role = role)
}

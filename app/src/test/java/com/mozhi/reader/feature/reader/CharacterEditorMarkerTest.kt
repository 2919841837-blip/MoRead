package com.mozhi.reader.feature.reader

import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.knowledge.CharacterAttributeDraftCodec
import com.mozhi.reader.ai.knowledge.CharacterDossier
import com.mozhi.reader.ai.knowledge.CharacterHighlight
import com.mozhi.reader.ai.knowledge.DossierRelation
import com.mozhi.reader.ai.knowledge.DossierTrait
import com.mozhi.reader.ai.knowledge.HighlightField
import com.mozhi.reader.ai.knowledge.HighlightSpan
import com.mozhi.reader.ui.components.TextMarker
import org.junit.Assert.assertEquals
import org.junit.Test

/** 性格/关系的高亮按条目名定位：每条新增条目都应得到自己的 ★，点击时也只撤掉对应那一条。 */
class CharacterEditorMarkerTest {
    private fun person(vararg highlights: CharacterHighlight) = BookCharacter(
        name = "林舟", evidence = emptyList(),
        dossier = CharacterDossier(
            personality = listOf(
                DossierTrait("执着", "风雨夜也按时点灯"),
                DossierTrait("嘴硬心软", "嘴上赶人走")),
            relationships = listOf(
                DossierRelation("小满", "同伴", "一起上路"),
                DossierRelation("林伯", "祖父"))),
        highlights = highlights.toList())

    @Test fun everyNewTraitAndRelationGetsItsOwnStar() {
        // 两条性格：一条整条新增，一条说明里有新增片段；两条关系同样各有一条。
        val person = person(
            CharacterHighlight(HighlightField.PERSONALITY, key = "执着", wholeItem = true),
            CharacterHighlight(HighlightField.PERSONALITY, key = "嘴硬心软", spans = listOf(HighlightSpan(0, 4))),
            CharacterHighlight(HighlightField.RELATION, key = "小满", wholeItem = true),
            CharacterHighlight(HighlightField.RELATION, key = "林伯", wholeItem = true))
        val draft = CharacterAttributeDraftCodec.baseline(person)

        assertEquals("执着（风雨夜也按时点灯）\n嘴硬心软（嘴上赶人走）", draft.personality)
        assertEquals("小满（同伴）：一起上路\n林伯（祖父）", draft.relations)
        // ★ 落在各自条目的行首，而不是只给第一条。
        assertEquals(listOf(TextMarker(0), TextMarker(13)),
            entryMarkers(person, HighlightField.PERSONALITY, draft.personality, draft.personality))
        assertEquals(listOf(TextMarker(0), TextMarker(12)),
            entryMarkers(person, HighlightField.RELATION, draft.relations, draft.relations))
    }

    @Test fun aStarCancelsOnlyItsOwnEntry() {
        val person = person(
            CharacterHighlight(HighlightField.PERSONALITY, key = "执着", wholeItem = true),
            CharacterHighlight(HighlightField.PERSONALITY, key = "嘴硬心软", wholeItem = true))
        val draft = CharacterAttributeDraftCodec.baseline(person)
        val text = draft.personality

        assertEquals("执着", entryKeyAt(person, HighlightField.PERSONALITY, text, text, 0))
        assertEquals("嘴硬心软", entryKeyAt(person, HighlightField.PERSONALITY, text, text, 13))
        assertEquals(null, entryKeyAt(person, HighlightField.PERSONALITY, text, text, 5))
    }

    @Test fun editingTheFieldHidesTheStarsBecauseTheAnchorsNoLongerMatch() {
        val person = person(CharacterHighlight(HighlightField.PERSONALITY, key = "执着", wholeItem = true))
        val draft = CharacterAttributeDraftCodec.baseline(person)
        assertEquals(emptyList<TextMarker>(), entryMarkers(person, HighlightField.PERSONALITY,
            draft.personality + "\n新性格", draft.personality))
    }
}

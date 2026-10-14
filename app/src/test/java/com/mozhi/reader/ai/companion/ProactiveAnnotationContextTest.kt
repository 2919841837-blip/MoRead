package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.knowledge.BookCharacterRelationship
import com.mozhi.reader.ai.knowledge.ChapterKnowledge
import com.mozhi.reader.ai.knowledge.ChapterKnowledgeCodec
import com.mozhi.reader.ai.knowledge.CharacterDossier
import com.mozhi.reader.ai.knowledge.CharacterEvidence
import com.mozhi.reader.ai.knowledge.CharacterRole
import com.mozhi.reader.ai.knowledge.DossierEvent
import com.mozhi.reader.ai.knowledge.DossierRelation
import com.mozhi.reader.ai.knowledge.DossierTrait
import com.mozhi.reader.ai.knowledge.KnowledgeCharacter
import com.mozhi.reader.ai.knowledge.KnowledgeFact
import com.mozhi.reader.ai.knowledge.SourceAnchor
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.retrieval.RetrievalCandidate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProactiveAnnotationContextTest {
    private val promise = "顾衡在雪岭立誓，要将玉佩交还给沈岚，绝不食言。"
    private val target = "顾衡取出玉佩，想起在雪岭对沈岚许下的承诺，终于决定兑现誓言。".repeat(3)

    @Test fun chapterOneHundredCanRecallChapterThreeInsteadOfOnlyRecentChapters() = runTest {
        val candidates = (0..98).map { index ->
            val text = if (index == 2) promise else "当地人忙着耕种庄稼，讨论今日天气。"
            RetrievalCandidate(1, index, 0, text, 0, text.length)
        }
        val prepared = PreparedAnnotationContext("r", candidates,
            listOf(AnnotationOutline(2, "顾衡对沈岚立下玉佩与雪岭的承诺。")), mapOf(2 to "雪岭之誓"))
        val result = prepared.forParagraph(target, ProactiveAnnotationParagraph(0, target.length), 8_000)
        assertTrue(result.background.contains(promise))
        assertTrue(result.background.contains("第 3 章"))
        assertTrue(result.background.contains("梗概"))
        assertFalse(result.background.contains("第 99 章"))
        assertEquals(target, result.target)
    }

    @Test fun outlineRecallDropsIrrelevantOutlinesBelowFloor() = runTest {
        val prepared = PreparedAnnotationContext("r", outlines = listOf(
            AnnotationOutline(0, "顾衡路过集市，买了几样东西。"),          // 弱相关，非上一章
            AnnotationOutline(1, "顾衡在雪岭立誓，要把玉佩交还给沈岚。")  // 强相关
        ))
        val body = "顾衡取出玉佩，想起在雪岭对沈岚许下的承诺，终于决定兑现誓言。".repeat(3)
        val result = prepared.forParagraph(body, ProactiveAnnotationParagraph(0, body.length), 8_000)
        assertTrue(result.background.contains("立誓"))
        assertFalse(result.background.contains("路过"))
    }

    @Test fun previousChapterOutlineNotForcedWhenOthersMatch() = runTest {
        val prepared = PreparedAnnotationContext("r", outlines = listOf(
            AnnotationOutline(0, "顾衡在雪岭立誓，要把玉佩交还给沈岚。"),  // 相关，非上一章
            AnnotationOutline(1, "顾衡路过集市。")                         // 上一章，弱相关
        ))
        val body = "顾衡取出玉佩，想起在雪岭对沈岚许下的承诺，终于决定兑现誓言。".repeat(3)
        val result = prepared.forParagraph(body, ProactiveAnnotationParagraph(0, body.length), 8_000)
        assertTrue(result.background.contains("立誓"))
        assertFalse(result.background.contains("路过"))
    }

    @Test fun previousChapterOutlineFallsBackWhenNothingRelevant() = runTest {
        val prepared = PreparedAnnotationContext("r", outlines = listOf(
            AnnotationOutline(0, "市场里人来人往，热闹非凡。")  // 唯一一章，与目标无关
        ))
        val body = "顾衡取出玉佩，想起在雪岭对沈岚许下的承诺，终于决定兑现誓言。".repeat(3)
        val result = prepared.forParagraph(body, ProactiveAnnotationParagraph(0, body.length), 8_000)
        // 召回落空时兜底补上一章
        assertTrue(result.background.contains("市场"))
    }

    @Test fun everyBudgetKeepsTargetAndClipsCurrentChapterAtTargetEnd() = runTest {
        val before = "很早的本章正文😀".repeat(8_000)
        val after = "未来真相不得泄露".repeat(300)
        val body = before + target + after
        val paragraph = ProactiveAnnotationParagraph(before.length, before.length + target.length)
        val prepared = PreparedAnnotationContext("r", listOf(RetrievalCandidate(1, 2, 0, promise, 0, promise.length)))
        for (budget in listOf(4_000, 8_000, 16_000, 32_000, 64_000)) {
            val result = prepared.forParagraph(body, paragraph, budget)
            assertTrue("budget=$budget actual=" + result.chars, result.chars <= budget)
            assertEquals(target, result.target)
            assertTrue(result.prefix.endsWith(target))
            assertFalse(result.prefix.first().isLowSurrogate())
            assertFalse((result.prefix + result.background).contains("未来真相"))
            assertTrue(result.background.contains(promise))
        }
    }

    @Test fun minimumBudgetStillRetainsAnEntireMaximumLengthTarget() = runTest {
        val text = "😀".repeat(ProactiveAnnotationParagraphs.MAX_TARGET_CHARS / 2)
        val result = PreparedAnnotationContext("r").forParagraph(text, ProactiveAnnotationParagraph(0, text.length), -1)
        assertEquals(text, result.target)
        assertEquals(text, result.prefix)
        assertTrue(result.chars <= ProactiveAnnotationContextSettings.MIN_CHARS)
    }

    @Test fun incompleteCorpusIsDisclosedAndNeverTreatedAsProofOfAbsence() = runTest {
        val result = PreparedAnnotationContext("r", partial = true)
            .forParagraph(target, ProactiveAnnotationParagraph(0, target.length), 4_000)
        assertTrue(result.background.contains("只覆盖部分正文"))
    }

    @Test fun summariesMustMatchTheirSourceAndStayBeforeTheSpoilerBoundary() {
        val entry = entry(2, promise)
        val scope = ReadingScope.upto(98, Int.MAX_VALUE)
        assertNotNull(validatedAnnotationOutline(entry, scope, "r", promise))
        assertNull(validatedAnnotationOutline(entry.copy(sourceRevision = "old"), scope, "r", promise))
        assertNull(validatedAnnotationOutline(entry, scope, "r", promise.replace("顾衡", "别人")))
        assertNull(validatedAnnotationOutline(entry, scope, "r", null))
        assertNull(validatedAnnotationOutline(entry.copy(chapterIndex = 99), scope, "r", promise))
        assertNull(validatedAnnotationOutline(entry, ReadingScope.upto(2, 5), "r", promise))
    }

    @Test fun namedCharacterFromPriorChapterIsInjectedIntoBackground() = runTest {
        val prepared = PreparedAnnotationContext("r", characters = listOf(
            AnnotationCharacter("顾衡", listOf(AnnotationCharacterFact(2, "顾衡在雪岭立誓，要把玉佩还给沈岚。")))
        ))
        val result = prepared.forParagraph(target, ProactiveAnnotationParagraph(0, target.length), 8_000)
        assertTrue(result.background.contains("【前文人物】"))
        assertTrue(result.background.contains("顾衡"))
        assertTrue(result.background.contains("雪岭"))
        assertTrue(result.background.contains("第3章"))
    }

    @Test fun unnamedParagraphFallsBackToMostRecentChapterCast() = runTest {
        val prepared = PreparedAnnotationContext("r", characters = listOf(
            AnnotationCharacter("顾衡", listOf(AnnotationCharacterFact(2, "顾衡立下雪岭之誓。"))),
            AnnotationCharacter("沈岚", listOf(AnnotationCharacterFact(4, "沈岚在城门张望，久候不至。")))
        ))
        val body = "今天天气很好，适合出门走走。"
        val result = prepared.forParagraph(body, ProactiveAnnotationParagraph(0, body.length), 8_000)
        assertTrue(result.background.contains("沈岚"))
        assertFalse(result.background.contains("顾衡"))
    }

    @Test fun charactersMergeByNameAcrossChaptersAndKeepLatestChapter() {
        val merged = mergeAnnotationCharacters(listOf(
            AnnotationChapterKnowledge(AnnotationOutline(2, "梗概"), listOf(
                KnowledgeCharacter("顾衡", listOf(KnowledgeFact("在雪岭立誓", "q", 0, 1))),
                KnowledgeCharacter("沈岚", listOf(KnowledgeFact("在城中等候", "q", 0, 1)))
            )),
            AnnotationChapterKnowledge(AnnotationOutline(4, "梗概"), listOf(
                KnowledgeCharacter("顾衡", listOf(KnowledgeFact("把玉佩交还", "q", 0, 1)))
            ))
        ))
        assertEquals(2, merged.size)
        val guheng = merged.first { it.name == "顾衡" }
        assertEquals(listOf("在雪岭立誓", "把玉佩交还"), guheng.facts.map { it.text })
        assertEquals(4, guheng.latestChapterIndex)
    }

    @Test fun cardProfileFiltersFactsAndRelationsByChapterAnchor() {
        val person = BookCharacter("顾衡",
            evidence = listOf(
                CharacterEvidence(2, KnowledgeFact("在雪岭立誓", "q", 0, 1)),
                CharacterEvidence(5, KnowledgeFact("把玉佩交还", "q", 0, 1))
            ),
            relationships = listOf(
                BookCharacterRelationship("沈岚", "师姐", CharacterEvidence(3, KnowledgeFact("师姐", "q", 0, 1))),
                BookCharacterRelationship("谢玄", "宿敌", CharacterEvidence(7, KnowledgeFact("宿敌", "q", 0, 1)))
            ),
            role = CharacterRole.PROTAGONIST
        )
        val profile = cardProfile(person, 5, true)!!
        assertTrue(profile.contains("主角"))
        assertTrue(profile.contains("在雪岭立誓"))
        assertTrue(profile.contains("沈岚"))
        assertFalse(profile.contains("把玉佩交还"))
        assertFalse(profile.contains("谢玄"))
    }

    @Test fun cardProfileReadsDossierRelationshipsWithDetail() {
        val person = BookCharacter("顾衡", emptyList(),
            dossier = CharacterDossier(relationships = listOf(
                DossierRelation("谢玄", "警惕", "顾衡曾提醒沈岚警惕谢玄，认为谢玄是枭雄", SourceAnchor(3, 0, 10))
            )))
        val profile = cardProfile(person, 5, true)!!
        assertTrue(profile.contains("谢玄"))
        assertTrue(profile.contains("枭雄"))
        // 关系锚点在第 3 章，读到第 3 章时还不可见（前文只取 < 当前章）
        assertNull(cardProfile(person, 3, true))
    }

    @Test fun cardProfileRecallsEveryDossierField() {
        val person = BookCharacter("顾衡", emptyList(),
            dossier = CharacterDossier(
                tagline = "背负血海深仇的少年",
                bio = "本是侯府世子，因家变流落江湖，一心复仇。",
                identity = "镇北侯世子",
                gender = "男",
                age = "十七岁",
                appearance = "一袭白衣",
                arc = "从隐忍到决意复仇",
                personality = listOf(DossierTrait("坚毅", "雪岭立誓足见其心", SourceAnchor(2, 0, 10))),
                experiences = listOf(DossierEvent("在雪岭立誓", 2, SourceAnchor(2, 0, 10))),
                relationships = listOf(DossierRelation("沈岚", "师姐", "同门情谊", SourceAnchor(3, 0, 10)))
            ),
            role = CharacterRole.PROTAGONIST
        )
        val profile = cardProfile(person, 5, true)!!
        assertTrue(profile.contains("主角"))         // 主次
        assertTrue(profile.contains("镇北侯世子"))   // 身份
        assertTrue(profile.contains("男"))           // 性别
        assertTrue(profile.contains("十七岁"))       // 年龄
        assertTrue(profile.contains("坚毅"))         // 性格
        assertTrue(profile.contains("在雪岭立誓"))   // 经历
        assertTrue(profile.contains("背负血海深仇")) // 简介
        assertTrue(profile.contains("复仇"))         // 小传
        assertTrue(profile.contains("决意复仇"))     // 成长
        assertTrue(profile.contains("沈岚"))         // 关系
        assertTrue(profile.contains("一袭白衣"))     // 外貌
    }

    @Test fun fullBookScanHidesUnanchoredSummaryFields() {
        val person = BookCharacter("顾衡", emptyList(),
            dossier = CharacterDossier(
                tagline = "隐藏身份的太子",
                bio = "其实是前朝遗孤。",
                arc = "最终登基称帝",
                personality = listOf(DossierTrait("隐忍", "不露声色"))
            ),
            role = CharacterRole.PROTAGONIST
        )
        // 全书扫描（bounded=false）：无锚点的简介/小传/成长/性格都不召回，避免剧透；主次保留
        val profile = cardProfile(person, 5, false)!!
        assertTrue(profile.contains("主角"))
        assertFalse(profile.contains("太子"))
        assertFalse(profile.contains("前朝"))
        assertFalse(profile.contains("登基"))
        assertFalse(profile.contains("隐忍"))
    }

    @Test fun cardIsInjectedWhenItRelatesToAnOnStageCharacter() = runTest {
        val prepared = PreparedAnnotationContext("r",
            characterCards = listOf(AnnotationCharacterCard("顾衡", emptyList(), "主角；关系：谢玄（警惕）", listOf("谢玄"))))
        val body = "谢玄此人城府极深，不可不防。"
        val result = prepared.forParagraph(body, ProactiveAnnotationParagraph(0, body.length), 8_000)
        assertTrue(result.background.contains("【角色卡】"))
        assertTrue(result.background.contains("顾衡"))
        assertTrue(result.background.contains("警惕"))
    }

    @Test fun characterCardAndUserNoteAreInjectedWhenNamed() = runTest {
        val prepared = PreparedAnnotationContext("r",
            characterCards = listOf(AnnotationCharacterCard("顾衡", listOf("顾公子"), "主角；在雪岭立誓")),
            notes = listOf(AnnotationNote(2, "顾衡这个承诺很关键，我记下了。")))
        val result = prepared.forParagraph(target, ProactiveAnnotationParagraph(0, target.length), 8_000)
        assertTrue(result.background.contains("【角色卡】"))
        assertTrue(result.background.contains("主角"))
        assertTrue(result.background.contains("【读书笔记】"))
        assertTrue(result.background.contains("承诺"))
    }

    @Test fun cardAndNoteSkippedWhenNoNameOnStage() = runTest {
        val prepared = PreparedAnnotationContext("r",
            characterCards = listOf(AnnotationCharacterCard("顾衡", emptyList(), "主角")),
            notes = listOf(AnnotationNote(2, "顾衡这个人值得留意。")))
        val body = "今天天气不错。"
        val result = prepared.forParagraph(body, ProactiveAnnotationParagraph(0, body.length), 8_000)
        assertFalse(result.background.contains("【角色卡】"))
        assertFalse(result.background.contains("【读书笔记】"))
    }

    @Test fun vectorRecallAddsSemanticallyRelevantSnippetWithNoLexicalOverlap() = runTest {
        val body = "顾衡取出玉佩，想起在雪岭对沈岚许下的承诺，终于决定兑现誓言。".repeat(3)
        val candidates = listOf(RetrievalCandidate(1, 0, 0, "lorem ipsum dolor sit amet", 0, 26))
        val vector = VectorRecall(
            embedQuery = { _ -> floatArrayOf(1f) },
            search = { _, _ -> listOf(RetrievalCandidate(1, 0, 0, "雪岭上的誓言刻骨铭心。", 0, 11, 0.1)) },
            topK = 12, recallDepth = 48
        )
        val result = PreparedAnnotationContext("r", candidates = candidates, vectorRecall = vector)
            .forParagraph(body, ProactiveAnnotationParagraph(0, body.length), 8_000)
        assertTrue(result.background.contains("雪岭上的誓言刻骨铭心"))
    }

    @Test fun vectorRecallFailureSilentlyFallsBackToBm25() = runTest {
        val body = "顾衡取出玉佩，想起在雪岭对沈岚许下的承诺。".repeat(3)
        val candidates = listOf(RetrievalCandidate(1, 0, 0, "顾衡在雪岭立誓。", 0, 9))
        val vector = VectorRecall(
            embedQuery = { _ -> throw IllegalStateException("network down") },
            search = { _, _ -> emptyList() },
            topK = 12, recallDepth = 48
        )
        val result = PreparedAnnotationContext("r", candidates = candidates, vectorRecall = vector)
            .forParagraph(body, ProactiveAnnotationParagraph(0, body.length), 8_000)
        assertTrue(result.background.contains("雪岭立誓"))
    }

    private fun entry(chapter: Int, text: String) = ChapterKnowledgeEntity(
        bookId = 1, chapterIndex = chapter, sourceRevision = "r", sourceEnd = text.length,
        sourceHash = ChapterKnowledgeCodec.hash(text), modelKey = "m", modelLabel = "m",
        promptVersion = ChapterKnowledgeCodec.PROMPT_VERSION,
        contentJson = ChapterKnowledgeCodec.encode(ChapterKnowledge(listOf(KnowledgeFact("顾衡的承诺", text, 0, text.length)))), createdAt = 0
    )
}

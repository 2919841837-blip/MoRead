package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.PersonaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 段评讨论串的上下文组装：锚点邻域防剧透截断、转写命名、系统提示词要素。 */
class AnnotationDiscussionContextTest {

    @Test
    fun neighborhoodTakesOnlyPrecedingContextWithEllipses() {
        val text = "甲".repeat(100) + "锚点" + "乙".repeat(100)
        val result = extractAnchorNeighborhood(
            chapterText = text,
            start = 100,
            end = 102,
            readLimit = null,
            radius = 10
        )
        assertEquals("……" + "甲".repeat(10) + "锚点" + "……", result)
    }

    @Test
    fun neighborhoodAtChapterHeadKeepsNoTrailingContent() {
        val result = extractAnchorNeighborhood(
            chapterText = "开头锚点后文后文",
            start = 2,
            end = 4,
            readLimit = null,
            radius = 100
        )
        assertEquals("开头锚点……", result)
    }

    @Test
    fun neighborhoodNeverCrossesReadLimitInCurrentChapter() {
        val text = "已读部分锚点未读的后半章不该出现"
        val anchorStart = text.indexOf("锚点")
        val result = extractAnchorNeighborhood(
            chapterText = text,
            start = anchorStart,
            end = anchorStart + 2,
            readLimit = anchorStart + 2,
            radius = 50
        )
        assertTrue(result.contains("已读部分锚点"))
        assertFalse(result.contains("未读"))
        // 截断在阅读位置上，不再补后省略号误导模型以为原文就到这
        assertFalse(result.endsWith("……"))
    }

    /** 走真实路径：先拼时间线（会丢弃空 note 的批注），再转写。 */
    private fun transcript(
        annotations: List<AnnotationEntity>,
        replies: List<AnnotationReplyEntity> = emptyList(),
        personaNames: Map<Long, String> = emptyMap(),
        currentPersonaId: Long = 7
    ): String = buildDiscussionTranscript(
        turns = buildDiscussionTurns(annotations, replies),
        personaNames = personaNames,
        currentPersonaId = currentPersonaId
    )

    @Test
    fun transcriptMarksSelfPeerUserAndDeletedPersona() {
        val transcript = transcript(
            annotations = listOf(annotation(personaId = 7, note = "这里的雪是伏笔")),
            replies = listOf(
                reply(id = 1, personaId = null, content = "我觉得也是"),
                reply(id = 2, personaId = 99, content = "被删角色的话")
            ),
            personaNames = mapOf(7L to "观澜")
        )
        assertEquals(
            "观澜（你）：这里的雪是伏笔\n用户：我觉得也是\n已删除角色（其他角色）：被删角色的话",
            transcript
        )
    }

    /** 空 note 的批注不占楼：用户只划线没写想法时，第一个真正开口的角色就是楼主。 */
    @Test
    fun blankNoteAnnotationNeverEntersTheTimeline() {
        val highlight = annotation(personaId = null, note = "")
        val replies = listOf(reply(id = 1, personaId = 7, content = "我先说说这段"))
        assertEquals(1, buildDiscussionTurns(listOf(highlight), replies).size)
        val transcript = transcript(
            annotations = listOf(highlight),
            replies = replies,
            personaNames = mapOf(7L to "观澜"),
            currentPersonaId = 8
        )
        assertEquals("观澜（其他角色）：我先说说这段", transcript)
        assertFalse(transcript.contains("只划了这段原文"))
    }

    @Test
    fun emptyTimelineGetsPlaceholderTranscript() {
        val transcript = transcript(annotations = listOf(annotation(personaId = null, note = "")))
        assertEquals(EMPTY_THREAD_TRANSCRIPT, transcript)
    }

    /** 时间线跨两张表按 (createdAt, id) 合并：谁先说话谁在前，与发言落在哪张表无关。 */
    @Test
    fun turnsMergeBothLayersInCreationOrder() {
        val early = annotation(personaId = 7, note = "先说的话", id = 1, createdAt = 1)
        val late = annotation(personaId = 8, note = "后说的话", id = 2, createdAt = 3)
        val middle = reply(id = 9, personaId = null, content = "中间插一句", createdAt = 2)
        val turns = buildDiscussionTurns(listOf(late, early), listOf(middle))
        assertEquals(listOf("先说的话", "中间插一句", "后说的话"), turns.map { it.text })
    }

    /** 楼主位不给任何人优先权：同锚点里 AI 段评先落地时，用户后补的想法排在它之下。 */
    @Test
    fun earlierAiAnnotationOutranksALaterUserNote() {
        val aiNote = annotation(personaId = 7, note = "AI 先段评", id = 1, createdAt = 5)
        val userNote = annotation(personaId = null, note = "用户后补的想法", id = 2, createdAt = 9)
        val turns = buildDiscussionTurns(listOf(aiNote, userNote), emptyList())
        assertEquals(listOf("AI 先段评", "用户后补的想法"), turns.map { it.text })
    }

    /** 当前角色首次插话：串里没有自己的发言，同伴仍要标出来，「（你）」不应凭空出现。 */
    @Test
    fun transcriptMarksPeerOnlyWhenSelfHasNotSpokenYet() {
        val transcript = transcript(
            annotations = listOf(annotation(personaId = 7, note = "这里的雪是伏笔")),
            replies = listOf(reply(id = 1, personaId = null, content = "我也读过")),
            personaNames = mapOf(7L to "观澜", 8L to "林致"),
            currentPersonaId = 8
        )
        assertEquals(
            "观澜（其他角色）：这里的雪是伏笔\n用户：我也读过",
            transcript
        )
        assertFalse(transcript.contains("（你）"))
    }

    /** 两个角色同名时按 personaId 判定：同伴不能因为重名被误标成「你」。 */
    @Test
    fun transcriptMarksByPersonaIdNotName() {
        val transcript = transcript(
            annotations = listOf(annotation(personaId = 7, note = "重名的楼主")),
            replies = listOf(reply(id = 1, personaId = 8, content = "重名的回复")),
            personaNames = mapOf(7L to "观澜", 8L to "观澜"),
            currentPersonaId = 8
        )
        assertEquals(
            "观澜（其他角色）：重名的楼主\n观澜（你）：重名的回复",
            transcript
        )
    }

    /** 同一角色连发多条：每条都标「你」，模型才知道这些话自己都说过了。 */
    @Test
    fun transcriptMarksEveryReplyFromSelfPersona() {
        val transcript = transcript(
            annotations = listOf(annotation(personaId = null, note = "用户开的头")),
            replies = listOf(
                reply(id = 1, personaId = 7, content = "第一次"),
                reply(id = 2, personaId = 7, content = "第二次")
            ),
            personaNames = mapOf(7L to "观澜")
        )
        assertEquals(
            "用户：用户开的头\n观澜（你）：第一次\n观澜（你）：第二次",
            transcript
        )
    }

    /** 楼主就是当前角色时，楼主层同样标「你」。 */
    @Test
    fun transcriptMarksSelfOpener() {
        val transcript = transcript(
            annotations = listOf(annotation(personaId = 7, note = "我先划的")),
            personaNames = mapOf(7L to "观澜")
        )
        assertEquals("观澜（你）：我先划的", transcript)
    }

    /** 用户说过话：以他的话为准，与旧行为一致。 */
    @Test
    fun userTurnQuotesUserWhenUserSpoke() {
        assertEquals(
            "用户在这段讨论里说：「我觉得也是」\n请以观澜的身份直接给出你在讨论串里的下一条发言。",
            buildDiscussionUserTurn("观澜", "我觉得也是", peerSpoke = true, selfSpoke = true)
        )
    }

    /** 用户留空、同伴说过话：请他接同伴的点，而不是让他独立点评原文。 */
    @Test
    fun userTurnTellsModelToPickUpAPeersPointWhenUserStayedSilent() {
        val turn = buildDiscussionUserTurn("观澜", null, peerSpoke = true, selfSpoke = false)
        assertTrue(turn.contains("已经有其他角色的发言"))
        assertTrue(turn.contains("先明确你在回应谁的哪一点"))
        assertFalse(turn.contains("点评这段划线原文"))
    }

    /** 用户留空、只有自己说过话：请他补新东西，别把自己的点评重讲一遍。 */
    @Test
    fun userTurnAsksForANewAngleWhenOnlySelfHasSpoken() {
        val turn = buildDiscussionUserTurn("观澜", null, peerSpoke = false, selfSpoke = true)
        assertTrue(turn.contains("最后是你自己的发言"))
        assertTrue(turn.contains("不要重复上面说过的话"))
        assertFalse(turn.contains("点评这段划线原文"))
    }

    /** 谁都没开口：照旧请他点评这段原文。 */
    @Test
    fun userTurnFallsBackToReviewingTheQuoteWhenNobodySpoke() {
        assertEquals(
            "用户邀请你点评这段划线原文，没有附加想法。\n请以观澜的身份直接给出你在讨论串里的下一条发言。",
            buildDiscussionUserTurn("观澜", null, peerSpoke = false, selfSpoke = false)
        )
    }

    @Test
    fun systemPromptCarriesPersonaSpoilerLineQuoteAndTranscript() {
        val prompt = buildDiscussionSystemPrompt(
            persona = persona(name = "观澜", personality = "冷静的文本细读者", isRoleplay = true),
            book = book(lastReadChapterIndex = 11),
            chapterTitle = "雪夜",
            chapterNumber = 3,
            quote = "窗外的雪下了一整夜",
            neighborhood = "……前文……窗外的雪下了一整夜……后文……",
            transcript = "用户：这里写得真好",
            readingScope = com.mozhi.reader.core.retrieval.ReadingScope.upto(11, 100)
        )
        assertTrue(prompt.contains("「观澜」"))
        assertTrue(prompt.contains("冷静的文本细读者"))
        assertTrue(prompt.contains("《测试书》"))
        assertTrue(prompt.contains("第 12 章")) // 防剧透：进度线
        assertTrue(prompt.contains("第 3 章「雪夜」"))
        assertTrue(prompt.contains("窗外的雪下了一整夜"))
        assertTrue(prompt.contains("用户：这里写得真好"))
        assertTrue(prompt.contains("search_book"))
        // 扮演型角色不追加工具助手约束
        assertFalse(prompt.contains("阅读助手"))
    }

    @Test
    fun systemPromptAddsAssistantConstraintForToolPersona() {
        val prompt = buildDiscussionSystemPrompt(
            persona = persona(name = "书僮", personality = "", isRoleplay = false),
            book = book(lastReadChapterIndex = 0),
            chapterTitle = null,
            chapterNumber = 1,
            quote = "引文",
            neighborhood = "",
            transcript = "用户：？",
            readingScope = com.mozhi.reader.core.retrieval.ReadingScope.upto(0, 100)
        )
        assertTrue(prompt.contains("阅读助手"))
    }

    @Test
    fun systemPromptInjectsRecallBackgroundBetweenNeighborhoodAndTranscript() {
        val prompt = buildDiscussionSystemPrompt(
            persona = persona(name = "观澜", personality = "", isRoleplay = true),
            book = book(lastReadChapterIndex = 5),
            chapterTitle = null,
            chapterNumber = 3,
            quote = "引文",
            neighborhood = "附近原文",
            recallBackground = "【角色卡】顾衡\n【读书笔记】顾衡的承诺",
            transcript = "用户：？",
            readingScope = com.mozhi.reader.core.retrieval.ReadingScope.upto(5, 100)
        )
        assertTrue(prompt.contains("前文资料"))
        assertTrue(prompt.contains("【角色卡】顾衡"))
        assertTrue(prompt.contains("【读书笔记】顾衡的承诺"))
        // 前文资料排在邻域之后、讨论串之前，保证模型先看资料再读对话。
        assertTrue(prompt.indexOf("附近原文") < prompt.indexOf("前文资料"))
        assertTrue(prompt.indexOf("前文资料") < prompt.indexOf("目前的讨论串"))
    }

    @Test
    fun systemPromptTellsModelToIgnoreIrrelevantRecall() {
        val prompt = buildDiscussionSystemPrompt(
            persona = persona(name = "观澜", personality = "", isRoleplay = true),
            book = book(lastReadChapterIndex = 5),
            chapterTitle = null,
            chapterNumber = 3,
            quote = "引文",
            neighborhood = "附近原文",
            recallBackground = "【角色卡】顾衡",
            transcript = "用户：？",
            readingScope = com.mozhi.reader.core.retrieval.ReadingScope.upto(5, 100)
        )
        assertTrue(prompt.contains("只围绕这段原文"))
        assertTrue(prompt.contains("与这段原文无关就忽略"))
        assertTrue(prompt.contains("直接呼应、补全或延续"))
    }

    /** 反驳要有依据但不是不许反驳；附和要具体，别退成点头。 */
    @Test
    fun systemPromptAllowsEvidencedRebuttalAndAffirmingReplies() {
        val prompt = buildDiscussionSystemPrompt(
            persona = persona(name = "观澜", personality = "", isRoleplay = true),
            book = book(lastReadChapterIndex = 5),
            chapterTitle = null,
            chapterNumber = 3,
            quote = "引文",
            neighborhood = "附近原文",
            transcript = "观澜（你）：引文",
            readingScope = com.mozhi.reader.core.retrieval.ReadingScope.upto(5, 100)
        )
        assertTrue(prompt.contains("其他角色发言"))
        assertTrue(prompt.contains("实质依据"))
        assertTrue(prompt.contains("同意"))
        assertTrue(prompt.contains("补充"))
        assertTrue(prompt.contains("延伸"))
        assertTrue(prompt.contains("共鸣"))
        assertTrue(prompt.contains("不要为了显得有观点而反驳"))
        assertTrue(prompt.contains("同意也要落到具体的点"))
        // 表态暗示软化：不再要求「直接说观点」。
        assertTrue(prompt.contains("直接说自己的想法"))
        assertFalse(prompt.contains("直接说观点"))
    }

    private fun annotation(personaId: Long?, note: String, id: Long = 1, createdAt: Long = 0) = AnnotationEntity(
        id = id,
        bookId = 1,
        personaId = personaId,
        chapterIndex = 2,
        startCharOffset = 0,
        endCharOffset = 4,
        selectedText = "引文片段",
        note = note,
        createdAt = createdAt
    )

    private fun reply(id: Long, personaId: Long?, content: String, createdAt: Long = id) = AnnotationReplyEntity(
        id = id,
        annotationId = 1,
        personaId = personaId,
        contentMarkdown = content,
        createdAt = createdAt
    )

    private fun persona(name: String, personality: String, isRoleplay: Boolean) = PersonaEntity(
        id = 7,
        name = name,
        personality = personality,
        isRoleplay = isRoleplay,
        createdAt = 0
    )

    private fun book(lastReadChapterIndex: Int) = BookEntity(
        id = 1,
        title = "测试书",
        author = "作者",
        coverPath = null,
        epubPath = "/tmp/a.epub",
        sourceType = BookSourceType.TXT,
        importedAt = 0,
        totalChapters = 20,
        lastReadChapterIndex = lastReadChapterIndex,
        lastReadCharOffset = 0
    )
}

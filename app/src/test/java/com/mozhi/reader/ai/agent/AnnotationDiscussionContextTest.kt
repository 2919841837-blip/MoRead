package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.PersonaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** 两个角色同名时按 personaId 判定：同伴不能因为重名被误标成「你」，并且按发言先后加后缀去重。 */
    @Test
    fun transcriptMarksByPersonaIdNotName() {
        val transcript = transcript(
            annotations = listOf(annotation(personaId = 7, note = "重名的楼主")),
            replies = listOf(reply(id = 1, personaId = 8, content = "重名的回复")),
            personaNames = mapOf(7L to "观澜", 8L to "观澜"),
            currentPersonaId = 8
        )
        assertEquals(
            "观澜（其他角色）：重名的楼主\n观澜2（你）：重名的回复",
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

    /** 花名册：按首次发言顺序列人，署名与 @点名 用的名字同源且唯一。 */
    @Test
    fun rosterListsSpeakersInFirstSpeechOrderWithUniqueHandles() {
        val turns = buildDiscussionTurns(
            annotations = listOf(annotation(personaId = 7, note = "我先说", createdAt = 0)),
            replies = listOf(
                reply(id = 1, personaId = 8, content = "我跟一句", createdAt = 1),
                reply(id = 2, personaId = null, content = "我也来", createdAt = 2),
                reply(id = 3, personaId = 99, content = "被删角色", createdAt = 3)
            )
        )
        val roster = discussionRoster(turns, mapOf(7L to "观澜", 8L to "观澜"), currentPersonaId = 8)
        assertEquals(listOf("观澜", "观澜2", "用户", "已删除角色"), roster.map { it.handle })
        assertEquals(
            listOf("观澜（其他角色）", "观澜2（你）", "用户", "已删除角色（其他角色）"),
            roster.map { it.label }
        )
        assertEquals(listOf(false, true, false, false), roster.map { it.isSelf })
    }

    /** 同一角色连发多条只占一个名字：不能把自己写成「观澜」「观澜2」两个可点的人。 */
    @Test
    fun rosterCountsEachSpeakerOnce() {
        val turns = buildDiscussionTurns(
            annotations = listOf(annotation(personaId = 7, note = "第一句", createdAt = 0)),
            replies = listOf(
                reply(id = 1, personaId = 7, content = "第二句", createdAt = 1),
                reply(id = 2, personaId = 7, content = "第三句", createdAt = 2)
            )
        )
        val roster = discussionRoster(turns, mapOf(7L to "观澜"), currentPersonaId = 8)
        assertEquals(listOf("观澜"), roster.map { it.handle })
        assertEquals("观澜（其他角色）", roster.single().label)
    }

    /** 谁都没开口时没有名单可给（那一条由模型起头点评原文，不涉及点名）。 */
    @Test
    fun rosterIsEmptyWhenNobodySpoke() {
        assertTrue(discussionRoster(emptyList(), emptyMap(), currentPersonaId = 7).isEmpty())
    }

    @Test
    fun rosterLineListsEverySpeakersLabel() {
        val turns = buildDiscussionTurns(
            annotations = listOf(annotation(personaId = 7, note = "我先说", createdAt = 0)),
            replies = listOf(reply(id = 1, personaId = null, content = "我也来", createdAt = 1))
        )
        val line = buildDiscussionRosterLine(discussionRoster(turns, mapOf(7L to "观澜"), currentPersonaId = 8))
        assertEquals("参与讨论的人：观澜（其他角色）、用户", line)
    }

    /** 用户说过话：以他的话为准，与旧行为一致。 */
    @Test
    fun userTurnQuotesUserWhenUserSpoke() {
        assertEquals(
            "用户在这段讨论里说：「我觉得也是」\n请以观澜的身份直接给出你在讨论串里的下一条发言。",
            buildDiscussionUserTurn("观澜", "我觉得也是", peerSpoke = true, selfSpoke = true)
        )
    }

    /** 用户留空、同伴说过话：请他一句话接同伴的点（可以点名任何人），而不是独立点评原文。 */
    @Test
    fun userTurnTellsModelToPickUpAPeersPointWhenUserStayedSilent() {
        val turn = buildDiscussionUserTurn("观澜", null, peerSpoke = true, selfSpoke = false)
        assertTrue(turn.contains("已经有其他角色的发言"))
        assertTrue(turn.contains("一句话就够"))
        assertTrue(turn.contains("@名字"))
        assertTrue(turn.contains("不必只接最后一条"))
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

    /** 谁都没开口：这一条由他起头，等于写这段原文的段评。 */
    @Test
    fun userTurnAsksForTheOpeningAnnotationWhenNobodySpoke() {
        assertEquals(
            "用户请你为这段原文写一条段评，没有附加想法。\n请以观澜的身份写下你在讨论串里的这条段评。",
            buildDiscussionUserTurn("观澜", null, peerSpoke = false, selfSpoke = false)
        )
    }

    /** 谁第一个对这段原文发表段评，谁就是楼主：空串里用户这句就是段评，写进 note。 */
    @Test
    fun userOpensTheThreadWhenNobodyHasSpokenYet() {
        assertTrue(userSpeaksAsOpener(annotation(personaId = null, note = "", createdAt = 10), emptyList()))
    }

    /**
     * 段评比划线晚落地是常态：AI 已经开口，用户这句就是回应，落回复层。
     * 早先按 createdAt 比较的写法会在这里误判成楼主，把他的话顶到 AI 段评前面。
     */
    @Test
    fun userNeverOpensOnceAnAiAnnotationHasSpoken() {
        val highlight = annotation(personaId = null, note = "", createdAt = 10)
        val turns = buildDiscussionTurns(
            listOf(annotation(personaId = 7, note = "AI 段评", id = 2, createdAt = 30)),
            emptyList()
        )
        assertFalse(userSpeaksAsOpener(highlight, turns))
    }

    /** 批注已经有想法、或那条批注属于角色：都不再往 note 里写。 */
    @Test
    fun userNeverOpensWhenTheAnnotationAlreadyHasANoteOrBelongsToAPersona() {
        assertFalse(userSpeaksAsOpener(annotation(personaId = null, note = "已经写过"), emptyList()))
        assertFalse(userSpeaksAsOpener(annotation(personaId = 7, note = ""), emptyList()))
    }

    /** 点名写法：`@名字 ` 写在最前，恒为一个；改点别人只换这一个 token，不叠第二个。 */
    @Test
    fun mentionTokenIsTheOnlyOneAtTheHead() {
        assertEquals("@林致 ", discussionMentionToken("林致"))
        assertEquals("@林致 我觉得不是这样", applyDiscussionMention("我觉得不是这样", "林致"))
        assertEquals("@观澜 我觉得不是这样", applyDiscussionMention("@林致 我觉得不是这样", "观澜"))
        assertEquals("我觉得不是这样", applyDiscussionMention("@林致 我觉得不是这样", null))
    }

    /** 只有首部的 @ 才算点名，且名字必须在花名册里；手打名单外的 @ 只是正文。 */
    @Test
    fun leadingMentionOnlyCountsRosterNamesAtTheHead() {
        val roster = mapOf("林致" to 7L, "用户" to null)
        assertEquals(7L, leadingMentionPersonaId("@林致 你觉得呢", roster))
        assertNull(leadingMentionPersonaId("我觉得不是这样", roster))
        assertNull(leadingMentionPersonaId("@路人 你怎么看", roster))
        assertNull(leadingMentionPersonaId("我觉得 @林致 说得对", roster))
        assertNull(leadingMentionPersonaId("@林致你觉得呢", roster))
    }

    /** 只有点名、没说别的：这条只请对方接话，不把 `@名字` 当正文存进讨论串。 */
    @Test
    fun mentionAloneLeavesNoBody() {
        assertEquals("", discussionBodyWithoutMention("@林致 "))
        assertEquals("我觉得不是这样", discussionBodyWithoutMention("@林致 我觉得不是这样"))
        assertEquals("", discussionBodyWithoutMention(""))
    }

    /** 发言归属：回复挂在它所属的批注上，批注发言挂在自己。 */
    @Test
    fun turnsReportTheAnnotationTheyHangFrom() {
        val opener = annotation(personaId = 7, note = "AI 段评", id = 2)
        assertEquals(2L, DiscussionTurn.FromAnnotation(opener).ownerAnnotationId())
        assertEquals(1L, DiscussionTurn.FromReply(reply(id = 9, personaId = null, content = "接着说")).ownerAnnotationId())
    }

    /** 2 楼起是回应，不是写段评：篇幅、交流感、点名规则都要落到提示词里。 */
    @Test
    fun systemPromptKeepsRepliesShortAndTeachesNamedTargets() {
        val prompt = buildDiscussionSystemPrompt(
            persona = persona(name = "观澜", personality = "", isRoleplay = true),
            book = book(lastReadChapterIndex = 5),
            chapterTitle = null,
            chapterNumber = 3,
            quote = "引文",
            neighborhood = "附近原文",
            rosterLine = "参与讨论的人：用户、观澜（你）、林致（其他角色）",
            transcript = "用户：？",
            readingScope = com.mozhi.reader.core.retrieval.ReadingScope.upto(5, 100)
        )
        assertTrue(prompt.contains("你是在跟人聊天，不是写段评"))
        assertTrue(prompt.contains("一般 30 字以内，最多不超过 60 字"))
        assertTrue(prompt.contains("不要凑成一段四平八稳的完整评论收尾"))
        assertTrue(prompt.contains("@名字"))
        assertTrue(prompt.contains("最多两三个"))
        assertTrue(prompt.contains("不必只接最后一条发言"))
        assertTrue(prompt.contains("用户点了谁，就先回应谁"))
        assertTrue(prompt.contains("回应别人的一句话时不必检索"))
        // 段评档的篇幅与口吻只属于楼主，不能漏到回应里。
        assertFalse(prompt.contains("150 字以内"))
        assertFalse(prompt.contains("等于你在这段原文旁写一条段评"))
        // 名单排在讨论串之前：模型先知道有谁在场，再读谁说过什么。
        assertTrue(prompt.indexOf("参与讨论的人") < prompt.indexOf("目前的讨论串"))
    }

    /** 楼主档：谁都没开口时这一条是段评本体，篇幅与口吻走段评方向，不发名单、也不教点名。 */
    @Test
    fun systemPromptWritesAnAnnotationWhenNobodySpokeYet() {
        val prompt = buildDiscussionSystemPrompt(
            persona = persona(name = "观澜", personality = "", isRoleplay = true),
            book = book(lastReadChapterIndex = 5),
            chapterTitle = null,
            chapterNumber = 3,
            quote = "引文",
            neighborhood = "",
            isOpeningThread = true,
            transcript = EMPTY_THREAD_TRANSCRIPT,
            readingScope = com.mozhi.reader.core.retrieval.ReadingScope.upto(5, 100)
        )
        assertTrue(prompt.contains("等于你在这段原文旁写一条段评"))
        assertTrue(prompt.contains("150 字以内"))
        assertTrue(prompt.contains("不要写成客观的编辑评语"))
        assertFalse(prompt.contains("你是在跟人聊天，不是写段评"))
        assertFalse(prompt.contains("一般 30 字以内"))
        assertFalse(prompt.contains("@名字"))
        assertFalse(prompt.contains("回应别人的一句话时不必检索"))
        // 规则条里会提到「参与讨论的人」这四个字，这里判的是名单行本身有没有出现。
        assertFalse(prompt.contains("\n\n参与讨论的人："))
        assertTrue(prompt.contains("目前的讨论串：\n" + EMPTY_THREAD_TRANSCRIPT))
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

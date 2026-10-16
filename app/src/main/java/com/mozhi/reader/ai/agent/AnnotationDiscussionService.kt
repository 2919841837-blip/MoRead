package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.companion.AnnotationReadingContext
import com.mozhi.reader.ai.companion.ProactiveAnnotationContextRepository
import com.mozhi.reader.ai.companion.ProactiveAnnotationParagraph
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first

/**
 * 段评讨论串的 AI 应答：以角色身份对一条批注的讨论作答。
 *
 * 走 [AgentLoop.runDetached]——讨论内容归 annotation_replies，绝不落聊天会话
 * （否则污染统计口径与记忆固化）。工具白名单只留检索类；上下文 = 人设 +
 * 锚点原文上文 + 完整讨论串，防剧透照旧（上文截到用户当前阅读位置）。
 */
@Singleton
class AnnotationDiscussionService @Inject constructor(
    private val agentLoop: AgentLoop,
    private val toolset: ReaderToolset,
    private val libraryRepository: LibraryRepository,
    private val annotationRepository: AnnotationRepository,
    private val contextRepository: ProactiveAnnotationContextRepository,
    private val personaRepository: PersonaRepository,
    private val settingsRepository: com.mozhi.reader.core.datastore.ReaderSettingsRepository,
    private val userMaskStore: com.mozhi.reader.core.datastore.UserMaskStore
) {
    sealed interface Event {
        data class Text(val delta: String) : Event
        data class ToolActivity(val label: String) : Event
        data class Done(val replyId: Long) : Event
        data class Failed(val message: String) : Event
    }

    /**
     * 让 [personaId] 回复 [annotationId] 所在的讨论串（最新一条用户发言视为被回复对象）。
     * 完成时把全文写入 annotation_replies 并发出 [Event.Done]。
     *
     * [threadAnnotationIds] 是弹层里看到的整组批注（同锚点的全部划线）：转录与落库都按整组
     * 算——楼主是整组里第一个真正开口的发言，新回复挂在它下面。
     */
    fun respond(
        bookId: Long,
        annotationId: Long,
        personaId: Long,
        threadAnnotationIds: List<Long>
    ): Flow<Event> = flow {
        try {
            val persona = personaRepository.getPersona(personaId)
                ?: run { emit(Event.Failed("角色不存在或已删除")); return@flow }
            val annotation = annotationRepository.getAnnotation(annotationId)
                ?: run { emit(Event.Failed("这条批注已被删除")); return@flow }
            val book = libraryRepository.getBook(bookId)
                ?: run { emit(Event.Failed("书籍不存在")); return@flow }
            // 段评讨论串默认防剧透：恒到用户已读进度，与私聊（伴聊）的防剧透开关解耦。
            val scope = ReadingScope.uptoProgress(book)
            if (annotation.bookId != bookId) {
                emit(Event.Failed("这条批注不属于当前书籍")); return@flow
            }
            // 讨论串整组：同锚点那几条划线一起拼时间线——空 note 的批注不入线，第一个真正
            // 开口的发言就是楼主（[annotationId] 兜底在列，防调用方给的组里漏了锚点）。
            val threadIds = (threadAnnotationIds + annotationId).distinct()
            val group = annotationRepository.getAnnotations(threadIds)
                .ifEmpty { listOf(annotation) }
            val replies = annotationRepository.getRepliesForAnnotations(threadIds)
            val turns = buildDiscussionTurns(group, replies)
            val personaNames = personaRepository.getPersonas().associate { it.id to it.name }
            val neighborhood = loadAnchorNeighborhood(book, annotation, scope)
            val recallContext = loadRecallContext(bookId, annotation, personaId)
            val transcript = buildDiscussionTranscript(turns, personaNames, persona.id)
            val system = buildDiscussionSystemPrompt(
                persona = persona,
                book = book,
                chapterTitle = libraryRepository.getChapterTitle(bookId, annotation.chapterIndex),
                chapterNumber = annotation.chapterIndex + 1,
                quote = annotation.selectedText,
                neighborhood = neighborhood,
                recallBackground = recallContext.background,
                transcript = transcript,
                readingScope = scope
            )
            val latestUser = turns.lastOrNull { it.personaId == null }?.text
            val memorySettings = settingsRepository.companionMemorySettings.first()
            val memoryEnabled = persona.memoryEnabled && memorySettings.longTermEnabled
            val enabledTools = CompanionToolRouter.forDiscussion(memoryEnabled)
            val tools = toolset.forBook(
                bookId = bookId,
                personaId = personaId,
                enabledTools = enabledTools,
                readingScope = scope,
                memoryScope = MemoryScope(
                    longTermEnabled = memoryEnabled,
                    crossBookChatSearch = memorySettings.crossBookChatSearchEnabled,
                    maskId = userMaskStore.activeMask()?.id ?: 0L
                )
            )
            val history = listOf(
                ChatMessage(ChatRole.SYSTEM, system),
                ChatMessage(
                    ChatRole.USER,
                    buildDiscussionUserTurn(
                        personaName = persona.name,
                        latestUserText = latestUser,
                        peerSpoke = turns.any { it.personaId != null && it.personaId != personaId },
                        selfSpoke = turns.any { it.personaId == personaId }
                    )
                )
            )
            val text = StringBuilder()
            agentLoop.runDetached(history, tools, maxRounds = 5).collect { event ->
                when (event) {
                    is AgentEvent.Text -> {
                        text.append(event.text)
                        emit(Event.Text(event.text))
                    }
                    is AgentEvent.RoundStarted -> Unit
                    is AgentEvent.RoundCommitted -> Unit
                    // 讨论串是段落里的一条短发言，不该被一大段思维链压住。
                    is AgentEvent.Reasoning -> Unit
                    is AgentEvent.ToolRun -> emit(Event.ToolActivity(event.displayName))
                    is AgentEvent.ToolFinished -> Unit
                }
            }
            val content = text.toString().trim()
            if (content.isEmpty()) {
                emit(Event.Failed("角色没有说出话来，请重试"))
                return@flow
            }
            libraryRepository.getBook(bookId) ?: return@flow
            val currentAnnotation = annotationRepository.getAnnotation(annotationId) ?: return@flow
            if (currentAnnotation.bookId != bookId) return@flow
            // 挂到楼主那条批注上：整组共用一个讨论串，回复跟楼主一起被召回。
            val replyHostId = turns.firstOrNull()?.ownerAnnotationId() ?: annotationId
            val replyHost = annotationRepository.getAnnotation(replyHostId) ?: return@flow
            if (replyHost.bookId != bookId) return@flow
            val replyId = annotationRepository.addReply(
                annotationId = replyHostId,
                personaId = personaId,
                contentMarkdown = content.take(MAX_REPLY_CHARS),
                recallJson = recallContext.recall.encode(),
                sourceScopeChapterIndex = scope.maxChapterIndex
            )
            emit(Event.Done(replyId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(Event.Failed(error.message ?: "生成失败，请重试"))
        }
    }

    /** 锚点原文上文：锚点前 ~[NEIGHBORHOOD_RADIUS] 字，不含后文；当前章截到用户实际阅读位置。 */
    private suspend fun loadAnchorNeighborhood(
        book: BookEntity,
        annotation: AnnotationEntity,
        scope: ReadingScope
    ): String {
        val chapter = libraryRepository.getChapters(book.id)
            .firstOrNull { it.chapterIndex == annotation.chapterIndex }
            ?: return annotation.selectedText
        val body = libraryRepository.readChapterText(book.id, chapter)
        val readLimit = scope.readableEnd(annotation.chapterIndex, body)
        return extractAnchorNeighborhood(
            chapterText = body,
            start = annotation.startCharOffset,
            end = annotation.endCharOffset,
            readLimit = readLimit,
            radius = NEIGHBORHOOD_RADIUS
        )
    }

    /**
     * 讨论串 AI 应答的召回上下文：与随读段评 / 聊天工具共用同一套召回（大纲 / 角色卡 / 笔记 /
     * 正文片段），让「用户手动划线、AI 自动回复」这条路径的发言同样以前文资料为依据。
     * 召回失败不阻断应答，返回空上下文（前文资料只是辅助，正文核验已通过）。
     */
    private suspend fun loadRecallContext(bookId: Long, annotation: AnnotationEntity, personaId: Long): AnnotationReadingContext {
        val chapter = libraryRepository.getChapter(bookId, annotation.chapterIndex)
            ?: return AnnotationReadingContext("", "", "")
        val body = try {
            libraryRepository.readChapterTextStrict(bookId, chapter)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return AnnotationReadingContext("", "", "")
        }
        return try {
            contextRepository.prepare(bookId, annotation.chapterIndex)
                .forParagraph(
                    body,
                    ProactiveAnnotationParagraph(annotation.startCharOffset, annotation.endCharOffset),
                    ProactiveAnnotationContextSettings().budgetChars,
                    personaId
                )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AnnotationReadingContext("", "", "")
        }
    }

    private companion object {
        const val NEIGHBORHOOD_RADIUS = 500
        const val MAX_REPLY_CHARS = 5_000
    }
}

/** 锚点上文抽取：只取锚点之前 ~[radius] 字（含锚点本身），不带后文；[readLimit] 非空时整段截到该 UTF-16 偏移。 */
internal fun extractAnchorNeighborhood(
    chapterText: String,
    start: Int,
    end: Int,
    readLimit: Int?,
    radius: Int
): String {
    if (chapterText.isEmpty()) return ""
    val limit = (readLimit ?: chapterText.length).coerceIn(0, chapterText.length)
    val safeStart = start.coerceIn(0, limit)
    val safeEnd = end.coerceIn(safeStart, limit)
    val from = (safeStart - radius).coerceAtLeast(0)
    val to = safeEnd
    if (from >= to) return ""
    return buildString {
        if (from > 0) append("……")
        append(chapterText, from, to)
        if (to < limit) append("……")
    }
}

internal fun buildDiscussionSystemPrompt(
    persona: PersonaEntity,
    book: BookEntity,
    chapterTitle: String?,
    chapterNumber: Int,
    quote: String,
    neighborhood: String,
    recallBackground: String = "",
    transcript: String,
    readingScope: ReadingScope
): String = buildString {
    append("你是「").append(persona.name).append("」。")
    if (persona.personality.isNotBlank()) {
        append('\n').append(persona.personality.trim())
    }
    if (persona.speakingStyle.isNotBlank()) {
        append("\n说话风格：").append(persona.speakingStyle.trim())
    }
    append("\n\n你正在和用户共读《").append(book.title).append("》")
    if (readingScope.isWholeBook) append("，用户已关闭防剧透限制。")
    else append("，用户最远读到第 ").append(readingScope.maxChapterIndex + 1).append(" 章内，工具只提供已读部分。")
    append("你们在第 ").append(chapterNumber).append(" 章")
    chapterTitle?.takeIf(String::isNotBlank)?.let { append("「").append(it).append("」") }
    append("的一段原文旁展开段落讨论。")
    append("\n\n被讨论的原文：\n“").append(quote.take(600)).append("”")
    if (neighborhood.isNotBlank()) {
        append("\n\n这段原文的上下文：\n").append(neighborhood)
    }
    if (recallBackground.isNotBlank()) {
        append("\n\n前文资料（与这段原文相关的大纲、角色卡、笔记、正文片段，辅助资料以原文为准）：\n")
            .append(recallBackground)
    }
    append("\n\n目前的讨论串：\n").append(transcript)
    append("\n\n规则：")
    append("\n- 只围绕这段原文和它的上下文发言；上面的前文资料只是参考——只有能直接呼应、补全或延续这段原文的内容才用，与这段原文无关就忽略，不要展开无关的前文人物、承诺或剧情")
    append("\n- 这是段落旁的讨论区，回复要短小、口语化、直接说自己的想法，一般不超过 150 字")
    append("\n- 讨论串里有其他角色发言。回应可以是同意、补充、延伸、共鸣，也可以是有实质依据的反驳——依据来自原文或人物设定上的矛盾；没有依据就不必反驳，不要为了显得有观点而反驳。同意也要落到具体的点，不要只附和")
    append("\n- 不用 Markdown 标题和列表，像聊天一样自然")
    if (!readingScope.isWholeBook) {
        append("\n- 只谈用户已读到的内容，绝不涉及第 ")
            .append(readingScope.maxChapterIndex + 1)
            .append(" 章未读部分及之后的剧情；以工具提供的可读范围为准")
    }
    append("\n- 涉及前文人物、承诺、伏笔或因果而当前材料不足时，先用 search_book / grep_book 查找，再用 read_book_section 核对。检索范围覆盖所有允许读取的章节，不限最近几章；不能把少量命中当作完整结论")
    if (!persona.isRoleplay) {
        append("\n- 你是阅读助手：观点务实、聚焦文本本身")
    }
}

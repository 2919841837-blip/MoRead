package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.companion.ProactiveAnnotationContextRepository
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AnnotationDiscussionServiceTest {
    private val loop = mockk<AgentLoop>()
    private val toolset = mockk<ReaderToolset>()
    private val library = mockk<LibraryRepository>()
    private val annotations = mockk<AnnotationRepository>()
    private val context = mockk<ProactiveAnnotationContextRepository>()
    private val personas = mockk<PersonaRepository>()
    private val settings = mockk<ReaderSettingsRepository>()
    private val masks = mockk<UserMaskStore>()
    private val history = slot<List<ChatMessage>>()
    private val names = slot<Collection<String>>()
    private val scope = slot<ReadingScope>()
    private val memory = slot<MemoryScope>()
    private val book = BookEntity(id = 1, title = "测试书", author = "", coverPath = null,
        epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 100,
        lastReadChapterIndex = 2, maxReachedChapterIndex = 98, maxReachedCharOffset = 350)

    private fun prepare(globalMemory: Boolean = true, personaMemory: Boolean = true, protect: Boolean = true): AnnotationDiscussionService {
        val persona = PersonaEntity(id = 7, name = "伴读", personality = "细读文本", isRoleplay = true, memoryEnabled = personaMemory, createdAt = 0)
        val annotation = AnnotationEntity(id = 3, bookId = 1, chapterIndex = 2,
            startCharOffset = 0, endCharOffset = 2, selectedText = "承诺", note = "他怎么又这样了？", createdAt = 0)
        coEvery { personas.getPersona(7) } returns persona
        coEvery { personas.getPersonas() } returns listOf(persona)
        coEvery { library.getBook(1) } returns book
        coEvery { library.getChapters(1) } returns emptyList()
        coEvery { library.getChapter(1, any()) } returns null
        coEvery { library.getChapterTitle(1, 2) } returns "旧事"
        coEvery { annotations.getAnnotation(3) } returns annotation
        coEvery { annotations.getAnnotations(any()) } returns listOf(annotation)
        coEvery { annotations.getRepliesForAnnotations(any()) } returns emptyList()
        coEvery { annotations.addReply(3, 7, any(), any(), any(), any(), any()) } returns 11
        every { settings.companionSpoilerProtectionEnabled } returns flowOf(protect)
        every { settings.companionMemorySettings } returns flowOf(CompanionMemorySettings(
            longTermEnabled = globalMemory, crossBookChatSearchEnabled = false))
        coEvery { masks.activeMask() } returns UserMask(id = 42, name = "读者")
        every { toolset.forBook(bookId = 1, personaId = 7, enabledTools = capture(names),
            readingScope = capture(scope), memoryScope = capture(memory)) } returns emptyList()
        every { loop.runDetached(capture(history), any(), maxRounds = 5) } returns flowOf(AgentEvent.Text("他还记得那份承诺。"))
        return AnnotationDiscussionService(loop, toolset, library, annotations, context, personas, settings, masks)
    }

    @Test fun plainQuestionGetsRetrievalToolsAndUsesActualReadBoundary() = runTest {
        val events = prepare().respond(1, 3, 7, listOf(3)).toList()
        assertTrue(events.last() is AnnotationDiscussionService.Event.Done)
        assertTrue(names.captured.containsAll(setOf("search_book", "grep_book", "read_book_section", "list_chapters", "recall_memory")))
        assertFalse(names.captured.contains("add_annotation"))
        assertEquals(ReadingScope.upto(98, 350), scope.captured)
        assertEquals(MemoryScope(true, false, 42), memory.captured)
        assertTrue(history.captured.first().content.orEmpty().contains("最远读到第 99 章"))
        assertTrue(history.captured.first().content.orEmpty().contains("章未读部分及之后"))
        coVerify(exactly = 1) { annotations.addReply(3, 7, "他还记得那份承诺。", any(), any(), any(), any()) }
    }

    @Test fun globalMemoryOffKeepsBookToolsButDisablesRecall() = runTest {
        prepare(globalMemory = false).respond(1, 3, 7, listOf(3)).toList()
        assertFalse(names.captured.contains("recall_memory"))
        assertTrue(names.captured.contains("search_book"))
        assertFalse(memory.captured.longTermEnabled)
    }

    @Test fun personaMemoryOffAlsoDisablesRecall() = runTest {
        prepare(personaMemory = false).respond(1, 3, 7, listOf(3)).toList()
        assertFalse(names.captured.contains("recall_memory"))
        assertFalse(memory.captured.longTermEnabled)
    }

    @Test fun discussionReplyIsSpoilerSafeRegardlessOfChatToggle() = runTest {
        // 私聊（伴聊）的防剧透开关与段评讨论串无关：即使关闭，讨论串回复也恒到用户已读进度。
        prepare(protect = false).respond(1, 3, 7, listOf(3)).toList()
        assertEquals(ReadingScope.upto(98, 350), scope.captured)
        assertFalse(scope.captured.isWholeBook)
        assertTrue(history.captured.first().content.orEmpty().contains("最远读到第 99 章"))
        assertTrue(history.captured.first().content.orEmpty().contains("绝不涉及第"))
        assertFalse(history.captured.first().content.orEmpty().contains("已关闭防剧透限制"))
    }

    @Test fun readerCanDiscussAnAlreadyGeneratedAnnotationBeforeItsSourceIsRead() = runTest {
        val service = prepare()
        val annotation = AnnotationEntity(id = 3, bookId = 1, personaId = 7,
            chapterIndex = 99, startCharOffset = 0, endCharOffset = 2, selectedText = "承诺", note = "这句话呼应了前文。",
            sourceScopeChapterIndex = 99, sourceScopeCharOffset = 80, createdAt = 0)
        coEvery { annotations.getAnnotation(3) } returns annotation
        coEvery { annotations.getAnnotations(any()) } returns listOf(annotation)
        coEvery { library.getChapterTitle(1, 99) } returns "重逢"
        val events = service.respond(1, 3, 7, listOf(3)).toList()
        assertTrue(events.last() is AnnotationDiscussionService.Event.Done)
        assertTrue(history.captured.first().content.orEmpty().contains("这句话呼应了前文。"))
        assertEquals(ReadingScope.upto(98, 350), scope.captured)
        coVerify(exactly = 1) { annotations.addReply(3, 7, any(), any(), any(), any(), any()) }
    }

    /** 用户只划线没写想法、同伴已经说过话：楼主位让给同伴，用户轮请他接着往下说。 */
    @Test fun blankNoteThreadWithAPeerTellsModelToPickUpThePeersPoint() = runTest {
        val service = prepare()
        val highlight = AnnotationEntity(id = 3, bookId = 1,
            chapterIndex = 2, startCharOffset = 0, endCharOffset = 2, selectedText = "承诺", note = "", createdAt = 0)
        coEvery { annotations.getAnnotation(3) } returns highlight
        coEvery { annotations.getAnnotations(any()) } returns listOf(highlight)
        coEvery { annotations.getRepliesForAnnotations(any()) } returns listOf(AnnotationReplyEntity(id = 1,
            annotationId = 3, personaId = 9, contentMarkdown = "这里埋了伏笔", createdAt = 1))
        service.respond(1, 3, 7, listOf(3)).toList()
        // 空 note 的批注不入线：第一个开口的角色就是楼主，并被标成「其他角色」。
        assertTrue(history.captured.first().content.orEmpty()
            .contains("目前的讨论串：\n已删除角色（其他角色）：这里埋了伏笔"))
        val userTurn = history.captured.last().content.orEmpty()
        assertTrue(userTurn.contains("已经有其他角色的发言"))
        assertTrue(userTurn.contains("先明确你在回应谁的哪一点"))
    }

    /** 整组时间线：同锚点的 AI 段评更早落地时它占楼主位，本次发言挂在它下面而不是用户那条划线。 */
    @Test fun peerOpenerInTheSameAnchorHostsTheNewReply() = runTest {
        val service = prepare()
        val peerOpener = AnnotationEntity(id = 5, bookId = 1, personaId = 8,
            chapterIndex = 2, startCharOffset = 0, endCharOffset = 2, selectedText = "承诺",
            note = "这里的承诺是伏笔", createdAt = 1)
        val userHighlight = AnnotationEntity(id = 3, bookId = 1,
            chapterIndex = 2, startCharOffset = 0, endCharOffset = 2, selectedText = "承诺", note = "", createdAt = 5)
        coEvery { annotations.getAnnotation(3) } returns userHighlight
        coEvery { annotations.getAnnotation(5) } returns peerOpener
        coEvery { annotations.getAnnotations(any()) } returns listOf(peerOpener, userHighlight)
        service.respond(1, 3, 7, listOf(3, 5)).toList()
        assertTrue(history.captured.first().content.orEmpty()
            .contains("目前的讨论串：\n已删除角色（其他角色）：这里的承诺是伏笔"))
        coVerify(exactly = 1) { annotations.addReply(5, 7, any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { annotations.addReply(3, 7, any(), any(), any(), any(), any()) }
    }
}

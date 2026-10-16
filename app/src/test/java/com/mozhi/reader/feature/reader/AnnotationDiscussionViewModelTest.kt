package com.mozhi.reader.feature.reader

import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.agent.AnnotationDiscussionService
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.AnnotationRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class AnnotationDiscussionViewModelTest {
    private val annotation = AnnotationEntity(id = 8, bookId = 7, chapterIndex = 2,
        startCharOffset = 20, endCharOffset = 30, selectedText = "这是一段划线原文。", createdAt = 0)

    @Test fun blankSendInvitesAiWithoutCreatingFakeUserTextAndIgnoresDoubleTap() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<AnnotationRepository>()
        val service = mockk<AnnotationDiscussionService>()
        val settings = mockk<ReaderSettingsRepository>()
        every { settings.discussionPersonaId } returns flowOf(null)
        every { service.respond(7, 8, 3, emptyList()) } returns flowOf(AnnotationDiscussionService.Event.Done(19))
        val vm = AnnotationDiscussionViewModel(repository, service, settings)
        try {
            vm.sendUserReply(7, annotation, "   \n", 3)
            vm.sendUserReply(7, annotation, "", 3)
            assertEquals(3L, vm.uiState.value.streaming?.personaId)
            runCurrent()
            verify(exactly = 1) { service.respond(7, 8, 3, emptyList()) }
            coVerify(exactly = 0) { repository.updateNote(any(), any()) }
            coVerify(exactly = 0) { repository.addReply(any(), any(), any(), any()) }
            assertNull(vm.uiState.value.streaming)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun emptyInputWithoutACompanionDoesNothingAndWrittenNotesStayUserOwned() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<AnnotationRepository>()
        val service = mockk<AnnotationDiscussionService>()
        val settings = mockk<ReaderSettingsRepository>()
        every { settings.discussionPersonaId } returns flowOf(null)
        coEvery { repository.updateNote(8, "我的想法") } just Runs
        // 弹层没打开（没有整组 id）时按单条算：这条空划线的批注本身就是楼主，写进 note。
        coEvery { repository.getAnnotations(any()) } returns listOf(annotation)
        val vm = AnnotationDiscussionViewModel(repository, service, settings)
        try {
            vm.sendUserReply(7, annotation, "", null)
            vm.sendUserReply(7, annotation, " 我的想法 ", null)
            runCurrent()
            coVerify(exactly = 1) { repository.updateNote(8, "我的想法") }
            verify(exactly = 0) { service.respond(any(), any(), any(), any()) }
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    /** 组里已经有人开口：用户的话落回复层，挂在楼主那条批注下面。 */
    @Test fun userReplyLandsUnderTheOpenerWhenSomeoneSpokeEarlier() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<AnnotationRepository>()
        val service = mockk<AnnotationDiscussionService>()
        val settings = mockk<ReaderSettingsRepository>()
        every { settings.discussionPersonaId } returns flowOf(null)
        val opener = AnnotationEntity(id = 5, bookId = 7, personaId = 3, chapterIndex = 2,
            startCharOffset = 20, endCharOffset = 30, selectedText = "这是一段划线原文。",
            note = "AI 段评", createdAt = 1)
        val highlight = annotation.copy(createdAt = 5)
        coEvery { repository.getAnnotations(any()) } returns listOf(opener, highlight)
        coEvery { repository.addReply(5, null, "我补一句", any(), any(), any(), any()) } returns 21
        val vm = AnnotationDiscussionViewModel(repository, service, settings)
        try {
            vm.sendUserReply(7, highlight, " 我补一句 ", null)
            runCurrent()
            coVerify(exactly = 1) { repository.addReply(5, null, "我补一句", any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.updateNote(any(), any()) }
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    /**
     * 段评比划线晚落地是常态：AI 已经当了楼主时，用户回一句不能升格成楼主。
     * （写 note 沿用划线的 createdAt = 5，会把这句顶到 AI 段评 createdAt = 9 前面抢位。）
     */
    @Test fun userReplyNeverClaimsOpenerWhenTheAiCommentCameLater() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<AnnotationRepository>()
        val service = mockk<AnnotationDiscussionService>()
        val settings = mockk<ReaderSettingsRepository>()
        every { settings.discussionPersonaId } returns flowOf(null)
        val opener = AnnotationEntity(id = 5, bookId = 7, personaId = 3, chapterIndex = 2,
            startCharOffset = 20, endCharOffset = 30, selectedText = "这是一段划线原文。",
            note = "AI 段评", createdAt = 9)
        val highlight = annotation.copy(createdAt = 5)
        coEvery { repository.getAnnotations(any()) } returns listOf(highlight, opener)
        coEvery { repository.addReply(5, null, "我不这么觉得", any(), any(), any(), any()) } returns 21
        val vm = AnnotationDiscussionViewModel(repository, service, settings)
        try {
            vm.sendUserReply(7, highlight, "我不这么觉得", null)
            runCurrent()
            coVerify(exactly = 1) { repository.addReply(5, null, "我不这么觉得", any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.updateNote(any(), any()) }
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}

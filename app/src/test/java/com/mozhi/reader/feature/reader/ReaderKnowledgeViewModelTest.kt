package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModelStore
import com.mozhi.reader.ai.knowledge.*
import com.mozhi.reader.core.database.entity.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class ReaderKnowledgeViewModelTest {
    @Test fun sameBookRefreshKeepsVisibleSnapshotWhileWaitingForTheDatabase() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val outlines = mockk<ChapterKnowledgeRepository>()
            val characters = mockk<BookCharactersRepository>()
            val runner = mockk<KnowledgeGenerationRunner>()
            val results = MutableSharedFlow<KnowledgeSnapshot>()
            every { outlines.observe(1) } returns results
            every { characters.observe(1) } returns MutableStateFlow(BookCharactersSnapshot())
            every { runner.states } returns MutableStateFlow(emptyMap())
            val model = ReaderKnowledgeViewModel(outlines, characters, runner, mockk(relaxed = true))
            store.put("knowledge", model)
            model.bind(1)
            runCurrent()
            val book = BookEntity(id = 1, title = "书", author = "", coverPath = null, epubPath = "", sourceType = BookSourceType.EPUB, importedAt = 1, totalChapters = 2)
            val snapshot = KnowledgeSnapshot(book)
            results.emit(snapshot)
            runCurrent()
            assertFalse(model.state.value.loading)
            assertEquals(snapshot, model.state.value.snapshot)
            model.bind(1)
            runCurrent() // The new subscription deliberately has not emitted a result yet.
            assertFalse(model.state.value.loading)
            assertEquals(snapshot, model.state.value.snapshot)
            verify(exactly = 0) { runner.stop(any(), any()) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun previewingCharactersHandsTheTypedTargetsToTheRepositoryUntouched() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val outlines = mockk<ChapterKnowledgeRepository>()
            val characters = mockk<BookCharactersRepository>()
            val runner = mockk<KnowledgeGenerationRunner>()
            every { outlines.observe(1) } returns MutableSharedFlow()
            every { characters.observe(1) } returns MutableStateFlow(BookCharactersSnapshot())
            every { runner.states } returns MutableStateFlow(emptyMap())
            val targets = listOf(CharacterTarget("黄晓明", listOf("晓明", "绿大暗")))
            val plan = mockk<BookCharactersPlan>()
            coEvery { characters.preview(1, false, false, targets) } returns plan
            val model = ReaderKnowledgeViewModel(outlines, characters, runner, mockk(relaxed = true))
            store.put("knowledge", model)
            model.bind(1)
            runCurrent()
            model.previewCharacters(false, false, targets)
            runCurrent()
            assertEquals(plan, model.state.value.pendingCharacters)
            assertNull(model.state.value.error)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun savingAnEditedOutlineGoesStraightToTheRepositoryWithoutAModelRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val outlines = mockk<ChapterKnowledgeRepository>()
            val characters = mockk<BookCharactersRepository>()
            val runner = mockk<KnowledgeGenerationRunner>()
            every { outlines.observe(1) } returns MutableSharedFlow()
            every { characters.observe(1) } returns MutableStateFlow(BookCharactersSnapshot())
            every { runner.states } returns MutableStateFlow(emptyMap())
            coEvery { outlines.saveOutline(any(), any(), any()) } just Runs
            val model = ReaderKnowledgeViewModel(outlines, characters, runner, mockk(relaxed = true))
            store.put("knowledge", model)
            model.bind(1)
            runCurrent()
            model.saveOutline(0, "手改后的大纲。")
            runCurrent()
            coVerify { outlines.saveOutline(1, 0, "手改后的大纲。") }
            assertNull(model.state.value.error)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun savingACharacterHandsTheHandWrittenAttributesStraightToTheRepositoryWithoutAModelRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val outlines = mockk<ChapterKnowledgeRepository>()
            val characters = mockk<BookCharactersRepository>()
            val runner = mockk<KnowledgeGenerationRunner>()
            every { outlines.observe(1) } returns MutableSharedFlow()
            every { characters.observe(1) } returns MutableStateFlow(BookCharactersSnapshot())
            every { runner.states } returns MutableStateFlow(emptyMap())
            val layer = ManualCharacterAttributes(identity = "摆渡人", age = "", role = CharacterRole.SUPPORTING)
            coEvery { characters.saveCharacter(1, "林舟", "林舟", "用户整理的资料", layer) } just Runs
            val model = ReaderKnowledgeViewModel(outlines, characters, runner, mockk(relaxed = true))
            store.put("knowledge", model)
            model.bind(1)
            runCurrent()
            model.saveCharacter("林舟", "林舟", "用户整理的资料", layer)
            runCurrent()
            coVerify { characters.saveCharacter(1, "林舟", "林舟", "用户整理的资料", layer) }
            assertNull(model.state.value.error)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}

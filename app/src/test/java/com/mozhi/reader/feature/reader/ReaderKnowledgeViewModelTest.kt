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

    @Test fun updatingCharactersTargetsExistingIdentitiesAndSkipsTheDeparted() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val outlines = mockk<ChapterKnowledgeRepository>()
            val characters = mockk<BookCharactersRepository>()
            val runner = mockk<KnowledgeGenerationRunner>()
            // combine 要等三条流都发过值；大纲流也要先给一帧，人物快照才会进 state。
            every { outlines.observe(1) } returns MutableStateFlow(KnowledgeSnapshot())
            // 手改过名字的人：正文里出现的是 identity「林舟」，点名时要把显示名当成别称。
            val guide = BookCharacterGuide(listOf(
                BookCharacter("阿舟", emptyList(), sourceName = "林舟"),
                BookCharacter("小满", emptyList()).copy(departed = true)
            ), 2, 100)
            every { characters.observe(1) } returns MutableStateFlow(BookCharactersSnapshot(
                saved = VisibleBookCharacters(mockk(relaxed = true), guide)))
            every { runner.states } returns MutableStateFlow(emptyMap())
            val target = CharacterTarget("林舟", listOf("阿舟"))
            val plan = mockk<BookCharactersPlan>()
            every { plan.targets } returns listOf(target)
            coEvery { characters.preview(1, true, false, listOf(target), true) } returns plan
            val model = ReaderKnowledgeViewModel(outlines, characters, runner, mockk(relaxed = true))
            store.put("knowledge", model)
            model.bind(1)
            runCurrent()
            model.updateCharacters(true)
            runCurrent()
            assertEquals(plan, model.state.value.pendingCharacters)
            assertNull(model.state.value.error)
            // 离场的小满不进入名单。
            coVerify(exactly = 1) { characters.preview(1, true, false, listOf(target), true) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun updatingCharactersWithEveryoneDepartedStopsBeforePreparingAnything() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val outlines = mockk<ChapterKnowledgeRepository>()
            val characters = mockk<BookCharactersRepository>()
            val runner = mockk<KnowledgeGenerationRunner>()
            every { outlines.observe(1) } returns MutableStateFlow(KnowledgeSnapshot())
            every { characters.observe(1) } returns MutableStateFlow(BookCharactersSnapshot(
                saved = VisibleBookCharacters(mockk(relaxed = true), BookCharacterGuide(
                    listOf(BookCharacter("林舟", emptyList()).copy(departed = true)), 1, 100))))
            every { runner.states } returns MutableStateFlow(emptyMap())
            val model = ReaderKnowledgeViewModel(outlines, characters, runner, mockk(relaxed = true))
            store.put("knowledge", model)
            model.bind(1)
            runCurrent()
            model.updateCharacters(true)
            runCurrent()
            assertEquals("已有角色都已标记离场，没有可更新的角色", model.state.value.error)
            coVerify(exactly = 0) { characters.preview(any(), any(), any(), any(), any()) }
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
            coEvery { characters.saveCharacter(1, "林舟", "林舟", layer) } just Runs
            val model = ReaderKnowledgeViewModel(outlines, characters, runner, mockk(relaxed = true))
            store.put("knowledge", model)
            model.bind(1)
            runCurrent()
            model.saveCharacter("林舟", "林舟", layer)
            runCurrent()
            coVerify { characters.saveCharacter(1, "林舟", "林舟", layer) }
            assertNull(model.state.value.error)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}

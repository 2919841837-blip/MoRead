package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.*
import com.mozhi.reader.core.database.entity.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class KnowledgeGenerationRunnerTest {
    private val outlines = mockk<ChapterKnowledgeRepository>()
    private val characters = mockk<BookCharactersRepository>()
    private val provider = AiProviderEntity(id = 1, name = "model", baseUrl = "https://example.test", apiKeyAlias = "alias", type = AiProviderType.CHAT, createdAt = 1)
    private val model = ResolvedChatClient(mockk(), ChatOptions.Default, provider, "model")
    private fun plan(index: Int) = KnowledgeGenerationPlan(KnowledgeSource(1, "book", index, "chapter", "待概括的正文。", 8, "revision"), model)

    @Test fun separateChaptersStartTogetherAndCancellingOneLeavesTheOtherRunning() = runTest {
        val first = plan(0)
        val second = plan(1)
        val finishSecond = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        coEvery { outlines.generate(any(), any()) } coAnswers {
            val index = firstArg<KnowledgeGenerationPlan>().source.chapterIndex
            started += index
            secondArg<(Int, Int) -> Unit>()(1, 2)
            if (index == 0) awaitCancellation() else finishSecond.await()
            mockk<ChapterKnowledgeEntity>()
        }
        val runner = KnowledgeGenerationRunner(outlines, characters, backgroundScope, io.mockk.mockk(relaxed = true))
        assertTrue(runner.generate(first))
        assertTrue(runner.generate(second))
        assertFalse(runner.generate(first))
        runCurrent()
        assertEquals(listOf(0, 1), started)
        runner.stop(1, 0)
        runCurrent()
        assertFalse(runner.states.value.getValue(KnowledgeTaskKey(1, 0)).active)
        assertTrue(runner.states.value.getValue(KnowledgeTaskKey(1, 1)).active)
        finishSecond.complete(Unit)
        runCurrent()
        assertEquals("已保存", runner.states.value.getValue(KnowledgeTaskKey(1, 1)).progress)
        assertTrue(runner.generate(first))
        runCurrent()
        assertEquals(listOf(0, 1, 0), started)
        runner.stop(1, 0)
    }

    @Test fun everyStartKeepsTheAppAliveAndStopAllKeepsTheLastProgressForTheUi() = runTest {
        val keepAlive = mockk<KnowledgeTaskKeepAlive>(relaxed = true)
        val quick = BookCharactersPlan(1, "灯塔来信", "revision", emptyList(), model, null, 0, quick = true)
        coEvery { characters.generateQuick(any(), any()) } coAnswers {
            secondArg<(KnowledgeProgress) -> Unit>()(KnowledgeProgress(KnowledgeProgress.Stage.PROFILES, 1, 3, finished = listOf("林舟")))
            awaitCancellation()
        }
        coEvery { outlines.generate(any(), any()) } coAnswers { awaitCancellation() }
        val runner = KnowledgeGenerationRunner(outlines, characters, backgroundScope, keepAlive)
        runner.generateCharacters(quick)
        runner.generate(plan(0))
        runCurrent()
        verify(exactly = 2) { keepAlive.onTaskStarted() }
        val running = runner.states.value.getValue(KnowledgeTaskKey(1))
        assertEquals("灯塔来信", running.bookTitle)
        assertEquals(listOf("林舟"), running.detail!!.finished)
        runner.stopAll()
        runCurrent()
        assertTrue(runner.states.value.values.none { it.active })
        val stopped = runner.states.value.getValue(KnowledgeTaskKey(1))
        assertEquals("已停止，可继续生成", stopped.progress)
        assertEquals(listOf("林舟"), stopped.detail!!.finished)
        assertEquals("灯塔来信", stopped.bookTitle)
    }

    @Test fun immediateCancellationBeforeTheCoroutineStartsDoesNotLeaveABusyChapter() = runTest {
        val runner = KnowledgeGenerationRunner(outlines, characters, backgroundScope, io.mockk.mockk(relaxed = true))
        runner.generate(plan(0))
        runner.stop(1, 0)
        runCurrent()
        assertFalse(runner.states.value.getValue(KnowledgeTaskKey(1, 0)).active)
        coVerify(exactly = 0) { outlines.generate(any(), any()) }
    }

    @Test fun failedChapterDoesNotStopWholeBookCharacterExtraction() = runTest {
        coEvery { outlines.generate(any(), any()) } throws IllegalArgumentException("模型返回格式错误")
        val finish = CompletableDeferred<Unit>()
        coEvery { characters.generate(any(), any(), any()) } coAnswers {
            finish.await()
            mockk<BookCharacterGuideEntity>()
        }
        val runner = KnowledgeGenerationRunner(outlines, characters, backgroundScope, io.mockk.mockk(relaxed = true))
        runner.generate(plan(0))
        runner.generateCharacters(BookCharactersPlan(1, "book", "revision", emptyList(), model, null, 0))
        runCurrent()
        assertEquals("模型返回格式错误", runner.states.value.getValue(KnowledgeTaskKey(1, 0)).error)
        assertTrue(runner.states.value.getValue(KnowledgeTaskKey(1)).active)
        finish.complete(Unit)
        runCurrent()
        assertEquals("已保存", runner.states.value.getValue(KnowledgeTaskKey(1)).progress)
    }

    @Test fun modelLimiterAllowsThreeConcurrentRequestsAndReleasesCancelledSlots() = runTest {
        val limiter = KnowledgeRequestLimiter()
        val entered = mutableListOf<Int>()
        val gates = List(4) { CompletableDeferred<Unit>() }
        var active = 0
        var peak = 0
        val jobs = List(4) { index -> launch {
            limiter.request {
                active++
                peak = maxOf(peak, active)
                entered += index
                try { gates[index].await() } finally { active-- }
            }
        } }
        runCurrent()
        assertEquals(listOf(0, 1, 2), entered)
        jobs[0].cancelAndJoin()
        runCurrent()
        assertEquals(listOf(0, 1, 2, 3), entered)
        gates[1].complete(Unit)
        gates[2].complete(Unit)
        gates[3].complete(Unit)
        jobs.joinAll()
        assertEquals(3, peak)
        assertEquals(0, active)
    }
}

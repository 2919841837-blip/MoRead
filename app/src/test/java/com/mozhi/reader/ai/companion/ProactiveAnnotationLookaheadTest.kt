package com.mozhi.reader.ai.companion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.LibraryRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProactiveAnnotationLookaheadTest {
    @Test fun unlimitedChaptersAlternatePaidCallsAndCommitEveryParagraphExactlyOnce() = runBlocking {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val book = BookEntity(id = 1, title = "河岸", author = "", coverPath = null, epubPath = "",
                sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 2)
            database.bookDao().insertBook(book)
            val bodies = List(2) { chapter -> List(3) { paragraph ->
                "本章${chapter}段落${paragraph}：" + "河岸的灯火映在水面，远处钟声缓缓响起。".repeat(3)
            }.joinToString("\n") }
            val library = mockk<LibraryRepository>()
            coEvery { library.getBook(1) } returns book
            coEvery { library.getChapter(1, any()) } answers {
                ChapterEntity(bookId = 1, chapterIndex = secondArg(), title = "河岸", href = "", charCount = bodies[secondArg()].length)
            }
            coEvery { library.readChapterText(1, any()) } answers { bodies[secondArg<ChapterEntity>().chapterIndex] }
            val personas = mockk<PersonaRepository>()
            coEvery { personas.getPersona(3) } returns PersonaEntity(id = 3, name = "知墨", personality = "", isRoleplay = false, createdAt = 0)
            val settings = mockk<ReaderSettingsRepository>()
            every { settings.activePersonaId } returns MutableStateFlow(3L)
            every { settings.companionAutonomySettings } returns MutableStateFlow(CompanionAutonomySettings(
                proactiveAnnotationsEnabled = true,
                annotationLimits = ProactiveAnnotationLimits(timing = ProactiveAnnotationTiming.ON_CHAPTER_ENTRY,
                    aheadChapters = 1, maxPerChapter = ProactiveAnnotationLimits.UNLIMITED, dailyMax = ProactiveAnnotationLimits.UNLIMITED)))
            val quota = mockk<ProactiveAnnotationQuota>()
            coEvery { quota.reserve(any(), any(), any(), any()) } returns ProactiveAnnotationAllowance(true, Int.MAX_VALUE)
            coEvery { quota.recordCreated(any(), any(), any(), any()) } just Runs
            val contexts = mockk<ProactiveAnnotationContextRepository>()
            coEvery { contexts.prepare(any(), any()) } returns PreparedAnnotationContext("revision")
            coEvery { contexts.isCurrent(any(), any()) } returns true
            val factory = mockk<AiClientFactory>()
            val client = mockk<ChatApiClient>()
            coEvery { factory.forRole(ModelRole.PROACTIVE_ANNOTATION) } returns ResolvedChatClient(client, ChatOptions(), mockk(), "mock")
            val paidChapters = mutableListOf<Int>()
            coEvery { client.chat(any(), any()) } answers {
                val target = firstArg<List<ChatMessage>>().last().content.orEmpty().substringAfter("唯一目标段落：\n")
                paidChapters += if (target.startsWith("本章0")) 0 else 1
                """{"quote":"$target","note":"钟声把这段景色连了起来。"}"""
            }
            val service = ProactiveAnnotationService(factory, mockk<AiMediaGenerationService>(), contexts)
            val scheduler = ProactiveAnnotationScheduler(database, library, personas, settings, quota, service, applicationScope)
            val notices = mutableListOf<ProactiveAnnotationBatchResult>()
            val noticesReady = CompletableDeferred<Unit>()
            applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                scheduler.results.collect { result ->
                    notices += result
                    if (notices.size == 2) noticesReady.complete(Unit)
                }
            }
            scheduler.setReaderBook(1)
            scheduler.onChapterEntered(1, 0)
            val annotations = withTimeout(10_000) { database.annotationDao().observeForBook(1).first { it.size == 6 } }
            withContext(Dispatchers.IO) {
                withTimeout(10_000) {
                    while ((0..1).any { chapter -> database.proactiveAnnotationJobDao().find(1, chapter, 3,
                            ProactiveAnnotationParagraphs.revision(bodies[chapter]))?.status != "DONE" }) delay(10)
                }
            }
            assertEquals(listOf(0, 1, 0, 1, 0, 1), paidChapters)
            withTimeout(10_000) { noticesReady.await() }
            assertEquals(listOf(3, 3), notices.map { it.createdCount })
            assertEquals(6, annotations.size)
            assertEquals(6, annotations.map { it.chapterIndex to it.endCharOffset }.distinct().size)
            for (chapter in 0..1) {
                val job = database.proactiveAnnotationJobDao().find(1, chapter, 3,
                    ProactiveAnnotationParagraphs.revision(bodies[chapter]))!!
                assertEquals("DONE", job.status)
                assertEquals(0, job.attempts)
                coVerify(exactly = 1) { contexts.prepare(1, chapter) }
            }
            assertEquals(0, database.bookDao().observeBook(1).first()!!.maxReachedCharOffset)
            scheduler.onChapterEntered(1, 1)
            delay(100)
            assertEquals(6, paidChapters.size)
            assertEquals(6, database.annotationDao().observeForBook(1).first().size)
        } finally { applicationScope.cancel(); database.close() }
    }
}

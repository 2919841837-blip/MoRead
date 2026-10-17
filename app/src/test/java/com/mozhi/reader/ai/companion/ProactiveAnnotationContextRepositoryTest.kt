package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.BookCharacterDao
import com.mozhi.reader.core.database.dao.ChapterKnowledgeDao
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.datastore.BookEmbeddingSettingsStore
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.NoteRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.objectbox.BoxStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProactiveAnnotationContextRepositoryTest {
    private val library = mockk<LibraryRepository>()
    private val database = mockk<MoReadDatabase>()
    private val dao = mockk<ChapterKnowledgeDao>()
    private val bookCharacterDao = mockk<BookCharacterDao>()
    private val noteRepository = mockk<NoteRepository>()
    private val clientFactory = mockk<AiClientFactory>()
    private val vectorStore = mockk<BoxStore>()
    private val embeddingSettingsStore = mockk<BookEmbeddingSettingsStore>()
    private val repository = ProactiveAnnotationContextRepository(
        library, database, noteRepository,
        dagger.Lazy { clientFactory },
        dagger.Lazy { vectorStore },
        embeddingSettingsStore
    )
    private val book = BookEntity(id = 1, title = "测试书", author = "", coverPath = null,
        epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 100)
    private val evidence = "顾衡在雪岭将玉佩交给沈岚，许诺来年相见。"

    init {
        coEvery { library.getBook(1) } returns book
        coEvery { library.bookTextRevision(1) } returns "r"
        every { database.chapterKnowledgeDao() } returns dao
        coEvery { dao.getBefore(1, any()) } returns emptyList()
        every { database.bookCharacterDao() } returns bookCharacterDao
        coEvery { bookCharacterDao.getGuide(any()) } returns null
        coEvery { noteRepository.getForBook(1) } returns emptyList()
        coEvery { embeddingSettingsStore.isEnabled(any()) } returns false
        coEvery { library.getChapter(1, any()) } answers {
            val index = arg<Int>(1)
            ChapterEntity(bookId = 1, chapterIndex = index, title = "第 $index 章", href = "", charCount = evidence.length)
        }
        coEvery { library.readChapterTextStrict(1, any()) } returns evidence
    }

    @Test fun onlyLoadsEarlierChaptersAndNeverTouchesTheTargetChapterOrItsSuffix() = runTest {
        val prepared = repository.prepare(1, 3)
        val context = prepared.forParagraph(evidence, ProactiveAnnotationParagraph(0, evidence.length), 8_000)
        assertTrue(context.background.isEmpty())
        coVerify(exactly = 1) { library.getChapter(1, 0) }
        coVerify(exactly = 1) { library.getChapter(1, 2) }
        coVerify(exactly = 0) { library.getChapter(1, match { it >= 3 }) }
        coVerify(exactly = 1) { dao.getBefore(1, 3) }
    }

    @Test fun staleChapterCountDoesNotHideKnownEarlierChapters() = runTest {
        coEvery { library.getBook(1) } returns book.copy(totalChapters = 0)
        val context = repository.prepare(1, 3).forParagraph(evidence, ProactiveAnnotationParagraph(0, evidence.length), 8_000)
        assertTrue(context.background.isEmpty())
        coVerify(exactly = 1) { library.getChapter(1, 2) }
        coVerify(exactly = 0) { library.getChapter(1, match { it >= 3 }) }
    }

    @Test fun firstChapterHasNoBackgroundAndDoesNotReadTheRestOfTheBook() = runTest {
        val result = repository.prepare(1, 0).forParagraph(evidence, ProactiveAnnotationParagraph(0, evidence.length), 8_000)
        assertTrue(result.background.isEmpty())
        coVerify(exactly = 0) { library.getChapter(any(), any()) }
        coVerify(exactly = 0) { dao.getBefore(any(), any()) }
    }

    @Test fun oneUnavailableChapterDoesNotDiscardEvidenceFromOtherChapters() = runTest {
        coEvery { library.getChapter(1, 1) } throws IllegalStateException("unavailable")
        val result = repository.prepare(1, 3).forParagraph(evidence, ProactiveAnnotationParagraph(0, evidence.length), 8_000)
        assertTrue(result.background.contains("只覆盖部分正文"))
        assertFalse(result.background.contains(evidence))
    }

    @Test fun sourceRevisionChangeRejectsPreparedSnapshot() = runTest {
        coEvery { library.bookTextRevision(1) } returnsMany listOf("r", "changed")
        try {
            repository.prepare(1, 3)
            fail("changed source must not be used")
        } catch (_: IllegalStateException) { }
    }

    @Test fun cancellationIsNeverConvertedIntoAnEmptyBackground() = runTest {
        coEvery { library.getChapter(1, 0) } throws CancellationException("reader left")
        try {
            repository.prepare(1, 3)
            fail("cancelled")
        } catch (_: CancellationException) { }
    }

    @Test fun cachedPrepareReusesTheChapterSnapshotWithinItsRevision() = runTest {
        val prepared = repository.cachedPrepare(1, 3)
        repository.cachedPrepare(1, 3)
        // 命中缓存：只做 revision 校验，不再重拉章梗概与角色卡。
        coVerify(exactly = 1) { dao.getBefore(1, 3) }
        coVerify(exactly = 1) { bookCharacterDao.getGuide(any()) }
        // 笔记不进章节快照：每一轮召回都重新读，刚新建/更新的笔记下一轮立即生效。
        prepared.forQuery("顾衡", null, 8_000)
        prepared.forQuery("顾衡", null, 8_000)
        coVerify(exactly = 2) { noteRepository.getForBook(1) }
    }

    @Test fun notesAnchoredToTheCurrentChapterOrWholeBookAreRecalled() = runTest {
        coEvery { noteRepository.getForBook(1) } returns listOf(
            note(relatedChapterIndex = 2, content = "本章手写笔记"),
            NoteEntity(
                bookId = 1, personaId = 7, title = "剧情梗概", contentMarkdown = "全书范围生成的梗概",
                sourceScopeChapterIndex = Int.MAX_VALUE, sourceScopeCharOffset = Int.MAX_VALUE,
                createdAt = 0, updatedAt = 0
            ),
            NoteEntity(
                bookId = 1, personaId = 7, title = "伏笔", contentMarkdown = "更早章节生成的伏笔",
                sourceScopeChapterIndex = 1, sourceScopeCharOffset = 100,
                createdAt = 0, updatedAt = 0
            )
        )
        val result = repository.prepare(1, 3).forQuery("顾衡", null, 8_000)
        // 笔记锚在当前章（追踪类刚更新 / 手写笔记锚在最后阅读位置）时必须能召回。
        assertTrue(result.background.contains("本章手写笔记"))
        assertTrue(result.background.contains("全书范围生成的梗概"))
        assertTrue(result.background.contains("更早章节生成的伏笔"))
    }

    @Test fun notesBeyondTheSpoilerBoundaryAreNotRecalled() = runTest {
        coEvery { noteRepository.getForBook(1) } returns listOf(
            note(relatedChapterIndex = 1, content = "顾衡在雪岭立誓。"),
            note(relatedChapterIndex = 5, content = "顾衡在黑崖坠亡。")
        )
        val result = repository.prepare(1, 3).forQuery("玉佩 雪岭 顾衡", null, 8_000)
        assertTrue(result.background.contains("雪岭立誓"))
        assertFalse(result.background.contains("黑崖坠亡"))
    }

    @Test fun cachedPrepareRevalidatesWhenTheSourceRevisionChanges() = runTest {
        repository.cachedPrepare(1, 3)
        coEvery { library.bookTextRevision(1) } returns "changed"
        repository.cachedPrepare(1, 3)
        // 源文件版本变了：缓存失效，重新召回。
        coVerify(exactly = 2) { dao.getBefore(1, 3) }
    }

    private fun note(relatedChapterIndex: Int?, content: String) = NoteEntity(
        bookId = 1,
        title = "笔记",
        contentMarkdown = content,
        relatedChapterIndex = relatedChapterIndex,
        createdAt = 0,
        updatedAt = 0
    )
}

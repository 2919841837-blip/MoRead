package com.mozhi.reader.ai.knowledge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.library.LibraryRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class BookCharactersRepositoryTest {
    private val texts = listOf("林舟在灯塔等候。", "小满带来一封信。这是完全没有读到的最后一章。")
    private val chapters = texts.mapIndexed { index, text -> ChapterEntity(bookId = 1, chapterIndex = index, title = "第${index + 1}章", href = "", charCount = text.length) }
    private val book = BookEntity(id = 1, title = "灯塔来信", author = "示例", coverPath = null, epubPath = "", sourceType = BookSourceType.EPUB,
        importedAt = 1, totalChapters = 2, maxReachedChapterIndex = 0, maxReachedCharOffset = 0)
    private var revision = "a".repeat(64)
    private var currentBook = book
    private val library = mockk<LibraryRepository>()
    private val factory = mockk<AiClientFactory>()
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private lateinit var database: MoReadDatabase
    private lateinit var repository: BookCharactersRepository
    private val client = CharacterClient()

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java).allowMainThreadQueries().build()
        database.bookDao().insertBook(book)
        currentBook = book
        coEvery { library.getBook(1) } answers { currentBook }
        coEvery { library.getChapters(1) } returns chapters
        chapters.forEachIndexed { index, chapter ->
            coEvery { library.getChapter(1, index) } returns chapter
            coEvery { library.readChapterTextStrict(1, chapter) } returns texts[index]
        }
        coEvery { library.bookTextRevision(1) } answers { revision }
        every { library.observeBook(1) } returns MutableStateFlow(book)
        val provider = AiProviderEntity(id = 1, name = "测试模型", baseUrl = "https://example.test", apiKeyAlias = "alias", type = AiProviderType.CHAT, createdAt = 1)
        coEvery { factory.forRole(ModelRole.CHEAP) } returns ResolvedChatClient(client, ChatOptions.Default, provider, "test-extractor")
        val loop = AgentLoop(chatDao, dagger.Lazy { error("Use resolved model") }, dagger.Lazy { error("No attachments") }, dagger.Lazy { error("No summaries") }, com.mozhi.reader.ai.agent.AgentToolExecutor { })
        val knowledgeAgent = ChapterKnowledgeAgent(loop, KnowledgeRequestLimiter())
        val readerTools = mockk<com.mozhi.reader.ai.agent.ReaderToolset>()
        every { readerTools.forBook(any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
        val webSettings = mockk<com.mozhi.reader.ai.search.WebSearchSettingsStore>()
        coEvery { webSettings.current() } returns com.mozhi.reader.ai.search.WebSearchSettings(enabled = false)
        repository = BookCharactersRepository(library, database, factory, knowledgeAgent,
            dagger.Lazy { CharacterResearchAgent(loop, KnowledgeJsonRequest(KnowledgeRequestLimiter()), readerTools) }, webSettings,
            dagger.Lazy { CharacterBiographer(KnowledgeJsonRequest(KnowledgeRequestLimiter())) })
    }

    @After fun close() { database.close() }

    @Test fun laterAiAliasCannotReplaceAnExistingManualRename() {
        val initial = BookCharacterGuide(listOf(BookCharacter("林舟", emptyList())), 1, 100)
        val edited = BookCharactersCodec.edit(initial, "林舟", "阿舟", null)
        val generated = initial.copy(characters = listOf(BookCharacter("阿舟", emptyList()), initial.characters.single()))
        val merged = BookCharactersCodec.mergeManual(generated, edited)
        assertEquals(1, merged.characters.size)
        assertEquals("林舟", merged.characters.single().identity)
        // 只改过名字（一个字都没手写）也不能被后一次扫描整条替掉：userEdited 记着这次经手。
        assertTrue(merged.characters.single().userEdited)
    }

    @Test fun manualCharactersNeedNoModelAndEditsSurviveExtractionAndSourceChanges() = runBlocking {
        repository.saveCharacter(1, null, "船夫", ManualCharacterAttributes(bio = "熟悉河道。"))
        assertEquals(0, client.requests)
        coVerify(exactly = 0) { factory.forRole(any()) }
        repository.generate(repository.preview(1))
        repository.saveCharacter(1, "林舟", "阿舟", ManualCharacterAttributes(identity = "灯塔守望者"))
        repository.generate(repository.preview(1))
        var people = repository.observe(1).first().saved!!.guide.characters
        assertEquals(3, people.size)
        val edited = people.first { it.name == "阿舟" }
        assertEquals("林舟", edited.identity)
        assertEquals("灯塔守望者", edited.attributeValues(CharacterAttributeKind.IDENTITY).single())
        assertTrue(edited.evidence.isNotEmpty())
        assertTrue(ExtractedCharacterCard.from(edited).description.contains("身份：灯塔守望者"))
        assertTrue(runCatching { repository.saveCharacter(1, null, "阿舟", null) }.isFailure)
        revision = "b".repeat(64)
        val snapshot = repository.observe(1).first()
        assertTrue(snapshot.outdated)
        people = snapshot.saved!!.guide.characters
        assertEquals(setOf("阿舟", "船夫"), people.map { it.name }.toSet())
        assertTrue(people.all { it.evidence.isEmpty() })
        repository.generate(repository.preview(1))
        assertTrue(repository.observe(1).first().saved!!.guide.characters.any { it.name == "阿舟" && it.manualAttributes != null && it.evidence.isNotEmpty() })
    }

    @Test fun manualEditDuringGenerationIsMergedAtPublicationAndNeverLosesToTheAiSnapshot() = runBlocking {
        repository.generate(repository.preview(1))
        database.bookCharacterDao().deleteParts(1)
        val paused = pauseAfterFirstPart()
        repository.saveCharacter(1, "林舟", "林舟", ManualCharacterAttributes(bio = "生成期间的手动修订"))
        paused.cancelAndJoin()
        client.onStream = {}
        repository.generate(repository.preview(1))
        assertEquals("生成期间的手动修订", repository.observe(1).first().saved!!.guide.characters.first { it.name == "林舟" }.manualAttributes?.bio)
    }

    @Test fun changingToReadProgressDoesNotKeepUnreadEvidenceOnManuallyEditedPeople() = runBlocking {
        repository.generate(repository.preview(1))
        repository.saveCharacter(1, "小满", "小满", ManualCharacterAttributes(bio = "用户自己的资料"))
        currentBook = book.copy(maxReachedChapterIndex = 0, maxReachedCharOffset = texts[0].length)
        repository.generate(repository.preview(1, progressBounded = true))
        val people = repository.observe(1).first().saved!!.guide.characters
        assertTrue(people.first { it.name == "小满" }.evidence.isEmpty())
        assertEquals("用户自己的资料", people.first { it.name == "小满" }.manualAttributes?.bio)
    }

    @Test fun explicitWholeBookPreviewIsLocalAndGenerationIncludesTheUnreadLastChapter() = runBlocking {
        assertNull(repository.observe(1).first().saved)
        val plan = repository.preview(1)
        assertEquals(2, plan.chapterCount)
        assertEquals(texts.sumOf { it.length.toLong() }, plan.sourceCharacters)
        assertEquals(0, client.requests)
        val entry = repository.generate(plan)
        val saved = repository.observe(1).first().saved!!
        assertEquals(2, client.requests)
        assertEquals(2, saved.guide.scannedChapters)
        assertEquals(listOf("林舟", "小满"), saved.guide.characters.map { it.name })
        val unread = saved.guide.characters.last().evidence.single()
        assertEquals(1 to 0, repository.locate(entry, unread))
        assertTrue(client.received.any { "完全没有读到的最后一章" in it })
        // 分段留作缓存，但全部归到已发布的这一代，不会被当成未完成的进度。
        val parts = database.bookCharacterDao().getParts(1)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.generationId == entry.generationId })
        assertFalse(repository.preview(1).resuming)
        coVerify(exactly = 0) { chatDao.insertMessage(any()) }
        coVerify(exactly = 0) { chatDao.insertConversation(any()) }
    }

    @Test fun cancelledUpdateRetainsPublishedGuideAndResumesVerifiedPartsWithoutAnotherRequest() = runBlocking {
        val old = repository.generate(repository.preview(1))
        database.bookCharacterDao().deleteParts(1)
        val paused = pauseAfterFirstPart()
        assertEquals(old, database.bookCharacterDao().getGuide(1))
        assertEquals(1, repository.observe(1).first().checkpointParts)
        paused.cancelAndJoin()
        client.onStream = {}
        val before = client.requests
        val resume = repository.preview(1)
        assertTrue(resume.resuming)
        assertEquals(1, resume.completedParts)
        val updated = repository.generate(resume)
        assertEquals(before + 1, client.requests)
        assertNotEquals(old.generationId, updated.generationId)
        assertFalse(repository.preview(1).resuming)
        assertEquals(updated.generationId, database.bookCharacterDao().getCheckpoint(1)?.generationId)
    }

    @Test fun sourceChangeInvalidatesCheckpointsAndCannotReplacePublishedData() = runBlocking {
        val old = repository.generate(repository.preview(1))
        database.bookCharacterDao().deleteParts(1)
        val before = client.requests
        client.onStream = { if (client.requests == before + 2) revision = "b".repeat(64) }
        assertTrue(runCatching { repository.generate(repository.preview(1)) }.isFailure)
        assertEquals(old, database.bookCharacterDao().getGuide(1))
        val snapshot = repository.observe(1).first()
        assertTrue(snapshot.outdated)
        assertNull(snapshot.saved)
        assertEquals(0, snapshot.checkpointParts)
        assertFalse(repository.preview(1).resuming)
        client.onStream = {}
        repository.generate(repository.preview(1))
        assertEquals(before + 4, client.requests)
    }

    @Test fun damagedCheckpointIsReextractedAndEmptyCharacterResultsAreValid() = runBlocking {
        val paused = pauseAfterFirstPart()
        paused.cancelAndJoin()
        val row = database.bookCharacterDao().getParts(1).single()
        database.bookCharacterDao().savePart(row.copy(contentJson = row.contentJson.replace("林舟在灯塔等候。", "林舟在海上航行。")))
        client.onStream = {}
        val before = client.requests
        client.empty = true
        val result = repository.generate(repository.preview(1))
        assertEquals(before + 2, client.requests)
        assertTrue(BookCharactersCodec.visible(result, revision)!!.guide.characters.isEmpty())
    }

    @Test fun malformedLaterChapterKeepsCompletedPartsAndResumeOnlyRequestsTheFailedChapter() = runBlocking {
        client.malformedChapter = "小满带来一封信。"
        val failure = runCatching { repository.generate(repository.preview(1)) }.exceptionOrNull()
        assertTrue(failure!!.message.orEmpty().contains("不是完整的 JSON 对象"))
        assertEquals(3, client.requests) // first chapter plus two attempts for the second
        assertEquals(1, database.bookCharacterDao().getParts(1).size)
        assertNull(database.bookCharacterDao().getGuide(1))
        val resume = repository.preview(1)
        assertTrue(resume.resuming)
        assertEquals(1, resume.completedParts)
        client.malformedChapter = null
        val saved = repository.generate(resume)
        assertEquals(4, client.requests)
        assertEquals(listOf("林舟", "小满"), BookCharactersCodec.visible(saved, revision)!!.guide.characters.map { it.name })
    }

    @Test fun permanentDeletionCascadesGuidesAndPendingParts() = runBlocking {
        repository.generate(repository.preview(1))
        database.bookCharacterDao().deleteParts(1)
        val paused = pauseAfterFirstPart()
        paused.cancelAndJoin()
        database.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = 1")
        assertNull(database.bookCharacterDao().getGuide(1))
        assertTrue(database.bookCharacterDao().getParts(1).isEmpty())
    }

    /** 防剧透是硬约束：读到哪就只送到哪，边界章截到当前进度。 */
    @Test fun progressBoundedExtractionStopsAtTheReadingPositionAndRefusesWhenNothingIsRead() = runBlocking {
        assertTrue(runCatching { repository.preview(1, progressBounded = true) }.isFailure)
        currentBook = book.copy(maxReachedChapterIndex = 1, maxReachedCharOffset = 8)

        val plan = repository.preview(1, progressBounded = true)
        assertTrue(plan.progressBounded)
        assertEquals(2, plan.chapterCount)
        assertEquals(16L, plan.sourceCharacters)

        repository.generate(plan)
        val saved = repository.observe(1).first().saved!!
        assertTrue(saved.guide.progressBounded)
        assertEquals(listOf("林舟", "小满"), saved.guide.characters.map { it.name })
        assertTrue(client.received.none { "完全没有读到的最后一章" in it })
    }

    /** 分段缓存跨代复用：读了更多之后再更新，只为新读到的正文付费。 */
    @Test fun updatingAfterMoreReadingOnlyPaysForTheNewText() = runBlocking {
        currentBook = book.copy(maxReachedChapterIndex = 1, maxReachedCharOffset = 8)
        repository.generate(repository.preview(1, progressBounded = true))
        val before = client.requests

        val updated = repository.generate(repository.preview(1))

        // 第一章原文一字未变，直接复用；只有补齐的最后一章重新计费。
        assertEquals(before + 1, client.requests)
        assertFalse(BookCharactersCodec.visible(updated, revision)!!.guide.progressBounded)
        assertTrue(client.received.any { "完全没有读到的最后一章" in it })
    }

    private suspend fun CoroutineScope.pauseAfterFirstPart(): Job {
        val entered = CompletableDeferred<Unit>()
        val before = client.requests
        client.onStream = { if (client.requests == before + 2) { entered.complete(Unit); awaitCancellation() } }
        val plan = repository.preview(1)
        val job = launch { repository.generate(plan) }
        withTimeout(10_000) { entered.await() }
        assertEquals(1, database.bookCharacterDao().getParts(1).size)
        return job
    }

    private inner class CharacterClient : ChatApiClient {
        var requests = 0
        var empty = false
        var malformedChapter: String? = null
        var onStream: suspend () -> Unit = {}
        val received = mutableListOf<String>()
        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>, options: ChatOptions): Flow<ChatDelta> = flow {
            requests++
            val source = messages.first { it.role == ChatRole.USER }.content
            received += source
            onStream()
            if (tools.isEmpty()) {
                emit(ChatDelta.Text(quickReply(messages.first().content, source)))
                return@flow
            }
            if (malformedChapter?.let(source::contains) == true) {
                emit(ChatDelta.Text("这段有人物，但结果未按格式返回。"))
                return@flow
            }
            val body = Regex("<passage[^>]*>\n(.*?)\n</passage>", RegexOption.DOT_MATCHES_ALL)
                .findAll(source).joinToString("\n") { it.groupValues[1] }
            val name = askedName(source, body)
            val quote = if (name == "小满") "小满带来一封信。" else "林舟在灯塔等候。"
            val json = if (empty) """{"characters":[]}""" else """{"characters":[{"name":"$name","facts":[{"text":"$quote","quote":"$quote"}]}]}"""
            emit(ChatDelta.ToolCalls(listOf(ToolCall("submit-$requests", tools.single().name, json))))
        }
        override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String = error("Use detached agent")
        override suspend fun embed(texts: List<String>): List<FloatArray> = error("No embeddings")
    }

    /**
     * 点名扫描的提示词写着「目标人物：大名（也称作：别称）」；模型被要求用原文里最常用的称呼，
     * 所以先在大名与别称里挑一个正文中真出现过的写法，挑不出来才退回按正文猜名字。
     */
    private fun askedName(source: String, body: String): String {
        val asked = source.lineSequence().firstOrNull { it.startsWith("目标人物：") }
            ?.removePrefix("目标人物：")?.split('（', '）', '、', '：', ',')
            ?.map(String::trim)?.filter { it.isNotEmpty() && it != "也称作" }.orEmpty()
        return asked.firstOrNull { it in body } ?: if ("小满带来一封信。" in source) "小满" else "林舟"
    }

    /** 档案请求不发工具 schema，走这条纯文本分支：正文就是提示词里那几段。 */
    private fun quickReply(system: String, user: String): String {
        val body = user.lineSequence().dropWhile { !it.startsWith("目标人物：") }.drop(1).joinToString("\n")
        val name = askedName(user, body)
        return when {
            "人物名单" in system -> """{"characters":[{"name":"林舟","role":"protagonist"},{"name":"小满","role":"major"}]}"""
            name == "林舟" -> """{"name":"林舟","tagline":"守灯人","bio":"在灯塔等候的人。"}"""
            else -> """{"name":"小满","tagline":"信使","bio":"送信的人。"}"""
        }
    }

    /** 快速模式用更长的正文：本地候选需要一个人物出现足够多次。 */
    private val quickTexts = listOf(
        "林舟在灯塔等候。林舟说：“灯不能灭。”林舟抬头看海。林舟笑道：“好。”",
        "小满带来一封信。小满道：“给你。”小满说：“走吧。”这是完全没有读到的最后一章。"
    )

    private fun useQuickTexts() {
        chapters.forEachIndexed { index, chapter ->
            coEvery { library.readChapterTextStrict(1, chapter) } returns quickTexts[index]
        }
    }

    @Test fun quickModeBuildsARosterThenOneDossierPerPersonAndPublishesThem() = runBlocking {
        useQuickTexts()
        val plan = repository.preview(1, progressBounded = false, quick = true)
        assertTrue(plan.quick)
        assertFalse(plan.webSearch)
        val progress = mutableListOf<KnowledgeProgress>()
        val published = repository.generateQuick(plan) { progress += it }
        val guide = BookCharactersCodec.visible(published, revision)!!.guide
        assertEquals(BookCharacterGuide.MODE_QUICK, guide.mode)
        assertEquals(listOf("林舟", "小满"), guide.characters.map { it.name })
        val lin = guide.characters.first()
        assertEquals(CharacterRole.PROTAGONIST, lin.role)
        assertEquals("守灯人", lin.dossier!!.tagline)
        assertEquals("在灯塔等候的人。", lin.dossier!!.bio)
        // 一次名单 + 每人一份档案；全程不发工具 schema，不写聊天记录。
        assertEquals(3, client.requests)
        assertTrue(progress.any { it.stage == KnowledgeProgress.Stage.ROSTER })
        assertEquals(listOf("林舟", "小满").toSet(), progress.last().finished.toSet())
        coVerify(exactly = 0) { chatDao.insertMessage(any()) }
        // 发布后只保留这一次用到的缓存（名单 + 两人）。
        assertEquals(0, repository.observe(1).first().pendingProfiles)
    }

    @Test fun stoppedQuickRunKeepsFinishedPeopleAndTheNextRunOnlyPaysForTheRest() = runBlocking {
        useQuickTexts()
        val entered = CompletableDeferred<Unit>()
        client.onStream = { if ("目标人物：小满" in client.received.last()) { entered.complete(Unit); awaitCancellation() } }
        val job = launch { repository.generateQuick(repository.preview(1, quick = true)) }
        withTimeout(10_000) {
            entered.await()
            while (repository.observe(1).first().pendingProfiles < 1) delay(10)
        }
        job.cancelAndJoin()
        assertNull(database.bookCharacterDao().getGuide(1))
        assertEquals(1, repository.observe(1).first().pendingProfiles)
        client.onStream = {}
        val before = client.requests
        val published = repository.generateQuick(repository.preview(1, quick = true))
        // 名单与林舟都命中缓存，只为小满再请求一次。
        assertEquals(before + 1, client.requests)
        assertEquals(listOf("林舟", "小满"), BookCharactersCodec.visible(published, revision)!!.guide.characters.map { it.name })
    }

    @Test fun quickModeWithinProgressNeverSendsUnreadText() = runBlocking {
        useQuickTexts()
        currentBook = book.copy(maxReachedChapterIndex = 0, maxReachedCharOffset = quickTexts[0].length)
        val plan = repository.preview(1, progressBounded = true, quick = true)
        repository.generateQuick(plan)
        assertTrue(client.received.none { "没有读到" in it || "小满" in it })
    }

    /** 点名扫描：用户给名字，管线跳过名单请求，只整理点名的那些人。 */
    @Test fun targetedScanProfilesOnlyTheNamedPeopleWithoutAskingForARoster() = runBlocking {
        useQuickTexts()
        val plan = repository.preview(1, targets = listOf(CharacterTarget("林舟")))
        assertTrue(plan.targeted)
        assertFalse(plan.quick)
        // 每人一次提交 + 一次修正机会，没有名单请求。
        assertEquals(2L, plan.maximumRequests)
        val progress = mutableListOf<KnowledgeProgress>()
        val guide = BookCharactersCodec.visible(repository.generateQuick(plan) { progress += it }, revision)!!.guide
        assertEquals(BookCharacterGuide.MODE_TARGETED, guide.mode)
        assertEquals(listOf("林舟"), guide.characters.map { it.name })
        assertEquals(1, client.requests)
        assertTrue(client.received.single().contains("目标人物：林舟"))
        assertTrue(progress.none { it.stage == KnowledgeProgress.Stage.ROSTER })
    }

    /** 点名扫描最要紧的一条：并入而不是覆盖，上一轮扫出来的人一个都不能少。 */
    @Test fun targetedScanMergesIntoTheExistingQuickGuideWithoutDroppingItsPeople() = runBlocking {
        useQuickTexts()
        repository.generateQuick(repository.preview(1, quick = true))
        val before = client.requests
        val published = repository.generateQuick(repository.preview(1, targets = listOf(CharacterTarget("小满"))))
        val guide = BookCharactersCodec.visible(published, revision)!!.guide
        assertEquals(listOf("林舟", "小满"), guide.characters.map { it.identity })
        // 并入已有资料时沿用原来的模式，只有首次点名扫描才标成 TARGETED。
        assertEquals(BookCharacterGuide.MODE_QUICK, guide.mode)
        assertEquals(before + 1, client.requests)
        assertTrue(client.received.last().contains("目标人物：小满"))
    }

    @Test fun aNameThatNeverAppearsInTheTextFailsLocallyWithoutRequestingTheModel() = runBlocking {
        useQuickTexts()
        val failure = runCatching { repository.generateQuick(repository.preview(1, targets = listOf(CharacterTarget("查无此人")))) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("没有在正文中找到"))
        assertEquals(0, client.requests)
        assertNull(database.bookCharacterDao().getGuide(1))
        // 失败必须释放「正在提取」的占用，否则这本书再也扫不了。
        repository.generateQuick(repository.preview(1, targets = listOf(CharacterTarget("林舟"))))
        assertEquals(1, client.requests)
        assertNotNull(database.bookCharacterDao().getGuide(1))
    }

    @Test fun aNameOnlyMatchedThroughAnAliasKeepsTheTypedNameAsItsIdentity() = runBlocking {
        useQuickTexts()
        // 正文里只有「林舟」；用户按别称点名，identity 要钉成输入的大名，显示名仍是原文的称呼。
        val plan = repository.preview(1, targets = listOf(CharacterTarget("阿舟", listOf("林舟"))))
        val person = BookCharactersCodec.visible(repository.generateQuick(plan), revision)!!.guide.characters.single()
        assertEquals("林舟", person.name)
        assertEquals("阿舟", person.identity)
        assertEquals(1, client.requests)
    }

    @Test fun cancelledTargetedScanLeavesThePublishedGuideUntouched() = runBlocking {
        useQuickTexts()
        val existing = repository.generate(repository.preview(1))
        val entered = CompletableDeferred<Unit>()
        client.onStream = { entered.complete(Unit); awaitCancellation() }
        val job = launch { repository.generateQuick(repository.preview(1, targets = listOf(CharacterTarget("林舟")))) }
        withTimeout(10_000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(existing, database.bookCharacterDao().getGuide(1))
    }

    @Test fun targetedScanKeepsEarlierCharactersAndManualEdits() = runBlocking {
        useQuickTexts()
        repository.generate(repository.preview(1))
        repository.saveCharacter(1, "林舟", "阿舟", ManualCharacterAttributes(bio = "灯塔守望者。"))
        val before = client.requests
        repository.generateQuick(repository.preview(1, targets = listOf(CharacterTarget("小满"))))
        val people = BookCharactersCodec.visible(database.bookCharacterDao().getGuide(1), revision)!!.guide.characters
        assertEquals(setOf("林舟", "小满"), people.map { it.identity }.toSet())
        // 手写的小传与手动改名一起留住：点名扫描只并入新认识的人。
        assertEquals("灯塔守望者。", people.single { it.name == "阿舟" }.manualAttributes?.bio)
        assertTrue(people.single { it.identity == "小满" }.dossier != null)
        assertEquals(before + 1, client.requests)
    }

    /** 手改的基本属性存进 contentJson 的手动层：读回来一字不差，重扫也动不了它。 */
    @Test fun handWrittenAttributesRoundTripAndSurviveEveryLaterScan() = runBlocking {
        repository.generate(repository.preview(1))
        repository.saveCharacter(1, "林舟", "林舟",
            ManualCharacterAttributes(identity = "摆渡人", age = "", aliases = listOf("阿舟")))
        val saved = repository.observe(1).first().saved!!.guide.characters.first { it.name == "林舟" }
        assertEquals("摆渡人", saved.attributeValues(CharacterAttributeKind.IDENTITY).single())
        assertEquals(emptyList<String>(), saved.attributeValues(CharacterAttributeKind.AGE))
        assertEquals(listOf("阿舟"), saved.aliases)
        repository.generate(repository.preview(1))
        val rescanned = repository.observe(1).first().saved!!.guide.characters.first { it.name == "林舟" }
        assertEquals("摆渡人", rescanned.attributeValues(CharacterAttributeKind.IDENTITY).single())
        assertEquals(emptyList<String>(), rescanned.attributeValues(CharacterAttributeKind.AGE))
        // 上一版的「人物资料」字段不再有人写：编辑过的人物身上它必须是空的。
        assertNull(rescanned.manualDescription)
        // 没接管的字段照旧由 AI 维护：依据仍是这次扫描刚写下来的。
        assertTrue(rescanned.evidence.isNotEmpty())
    }

    /** 手写的成长变化 / 关系同样存进 contentJson，读回来一字不差，重扫也动不了它。 */
    @Test fun handWrittenArcAndRelationsRoundTripAndSurviveEveryLaterScan() = runBlocking {
        repository.generate(repository.preview(1))
        repository.saveCharacter(1, "林舟", "林舟", ManualCharacterAttributes(
            arc = "放下执念。", relationships = listOf(ManualRelation("苏晚", "师姐", "常来看他"))))
        val saved = repository.observe(1).first().saved!!.guide.characters.first { it.name == "林舟" }
        assertEquals("放下执念。", saved.arc())
        assertEquals(listOf("苏晚"), saved.manualRelations()!!.map { it.target })
        repository.generate(repository.preview(1))
        val rescanned = repository.observe(1).first().saved!!.guide.characters.first { it.name == "林舟" }
        assertEquals("放下执念。", rescanned.arc())
        assertEquals("师姐", rescanned.manualRelations()!!.single().relation)
    }

    /** 快速扫描（名单 + 档案）走 mergeManual：手写的身份与档位留下，AI 基线照旧刷新。 */
    @Test fun aQuickRescanRefreshesTheAiBaselineAndLeavesTheWrittenFieldsAlone() = runBlocking {
        useQuickTexts()
        repository.generateQuick(repository.preview(1, quick = true))
        repository.saveCharacter(1, "小满", "小满",
            ManualCharacterAttributes(identity = "送信人", role = CharacterRole.PROTAGONIST))
        repository.generateQuick(repository.preview(1, quick = true))
        val xiaoman = repository.observe(1).first().saved!!.guide.characters.first { it.name == "小满" }
        assertEquals("送信人", xiaoman.attributeValues(CharacterAttributeKind.IDENTITY).single())
        assertEquals(CharacterRole.PROTAGONIST, xiaoman.effectiveRole())
        // 名单给的主次仍是「主要」：用户哪天切回「跟随 AI」，拿到的就是这一次的判定。
        assertEquals(CharacterRole.MAJOR, xiaoman.role)
        assertEquals("信使", xiaoman.dossier!!.tagline)
    }

    /** 点名扫描走 mergeTargeted（另一条发布路径）：并入新认识的 小满，绝不能碰林舟手上的手写层。 */
    @Test fun aTargetedScanMergesNewPeopleWithoutTouchingTheWrittenFields() = runBlocking {
        useQuickTexts()
        repository.generate(repository.preview(1))
        repository.saveCharacter(1, "林舟", "林舟", ManualCharacterAttributes(identity = "摆渡人"))
        repository.generateQuick(repository.preview(1, targets = listOf(CharacterTarget("小满"))))
        val people = repository.observe(1).first().saved!!.guide.characters
        assertEquals(setOf("林舟", "小满"), people.map { it.identity }.toSet())
        assertEquals("摆渡人", people.single { it.name == "林舟" }.attributeValues(CharacterAttributeKind.IDENTITY).single())
    }

}

package com.mozhi.reader.ai.knowledge

import androidx.room.withTransaction
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ResolvedChatClient
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.BookCharacterDossierEntity
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import com.mozhi.reader.core.database.entity.BookCharacterPartEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.BookContentMutation
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class BookCharactersPlan internal constructor(
    val bookId: Long, val bookTitle: String, internal val revision: String,
    internal val chapters: List<ChapterEntity>, internal val model: ResolvedChatClient,
    internal val resumeGenerationId: String?, val completedParts: Int,
    /** true = 只扫到阅读进度为止（边界章只送已读部分），资料不含未读情节。 */
    val progressBounded: Boolean = false,
    internal val scope: ReadingScope = ReadingScope.WholeBook,
    /** true = 名单 + 逐人档案；false = 逐段扫描全部正文。 */
    val quick: Boolean = false,
    /** 快速模式联网补充用的对话模型（按用户的联网搜索开关；没配对话模型时为 null，跳过联网）。 */
    internal val webModel: ResolvedChatClient? = null,
    /**
     * 用户点名的人物。非空时 [CharacterResearchAgent] 跳过名单请求，直接用这份名单逐人建档，
     * 发布时并入已有资料（见 [BookCharactersCodec.mergeTargeted]）。
     */
    val targets: List<CharacterTarget> = emptyList()
) {
    val chapterCount: Int get() = chapters.size
    val targeted: Boolean get() = targets.isNotEmpty()
    /** 本次实际要送出的字数：整章，或边界章只到已读位置。 */
    internal fun scannedChars(chapter: ChapterEntity): Int = when {
        !progressBounded -> chapter.charCount
        chapter.chapterIndex < scope.maxChapterIndex -> chapter.charCount
        else -> minOf(scope.maxCharOffset, chapter.charCount)
    }
    val sourceCharacters: Long = chapters.sumOf { scannedChars(it).toLong() }
    val modelLabel: String get() = model.modelName
    internal val modelKey = ChapterKnowledgeCodec.hash("${model.provider.id}|${model.provider.baseUrl}|${model.modelName}|${model.options}")
    val webSearch: Boolean get() = webModel != null
    val webModelLabel: String? get() = webModel?.modelName
    // A paragraph-aware part is at least half PART_CHARS, except for the final part.
    val maximumRequests: Long = when {
        // 点名的每个人一次提交，外加一次修正机会；没有名单请求，联网补充另算。
        targeted -> targets.size * 2L + if (webModel != null) CharacterResearchAgent.WEB_ROUNDS else 0
        quick -> CharacterResearchAgent.MAX_REQUESTS.toLong() + if (webModel != null) CharacterResearchAgent.WEB_ROUNDS else 0
        else -> chapters.sumOf {
            (scannedChars(it).toLong() + ChapterKnowledgeCodec.PART_CHARS / 2 - 1) / (ChapterKnowledgeCodec.PART_CHARS / 2)
        } * 2 + CharacterBiographer.MAX_PEOPLE * 2
    }
    val resuming: Boolean get() = !quick && !targeted && resumeGenerationId != null
}

/** 完整扫描写小传的缓存行用这个前缀，不计入「待继续」的人物数。 */
private const val BIO_IDENTITY_PREFIX = "#bio:"

/** 快速模式最多读入的正文量；超出部分不参与候选统计与段落检索。 */
private const val QUICK_MAX_SOURCE_CHARS = 8_000_000L

/** Whole-book extraction has its own explicit consent and never inherits the reader's progress boundary. */
@Singleton
class BookCharactersRepository @Inject constructor(
    private val library: LibraryRepository,
    private val database: MoReadDatabase,
    private val clients: AiClientFactory,
    private val agent: ChapterKnowledgeAgent,
    private val research: dagger.Lazy<CharacterResearchAgent>,
    private val webSearchSettings: com.mozhi.reader.ai.search.WebSearchSettingsStore,
    private val biographer: dagger.Lazy<CharacterBiographer>
) {
    private val dao get() = database.bookCharacterDao()
    private val generating = ConcurrentHashMap.newKeySet<Long>()

    fun observe(bookId: Long): Flow<BookCharactersSnapshot> =
        combine(library.observeBook(bookId), dao.observeGuide(bookId), dao.observeCheckpoint(bookId), dao.observeDossierCount(bookId)) { _, guide, checkpoint, dossiers ->
            Triple(guide, checkpoint, dossiers)
        }.mapLatest { (guide, checkpoint, dossiers) -> withContext(Dispatchers.IO) { BookContentMutation.withBook(bookId) {
            val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: return@withBook BookCharactersSnapshot()
            val revision = if (guide != null || checkpoint != null || dossiers > 0) library.bookTextRevision(book.id) else ""
            val visible = BookCharactersCodec.visible(guide, revision)
            val resumable = checkpoint?.takeIf { it.generationId != guide?.generationId && it.sourceRevision == revision && it.promptVersion == BookCharactersCodec.PROMPT_VERSION }
            val pending = if (dossiers > 0) dao.countPendingDossiers(bookId, revision, guide?.createdAt ?: 0L) else 0
            BookCharactersSnapshot(visible, guide != null && (guide.sourceRevision != revision || visible == null), resumable?.completedParts ?: 0, pending)
        } } }

    /**
     * Metadata only: opening the page and its confirmation never uploads book text.
     *
     * [progressBounded] 把扫描范围收到 [ReadingScope.uptoProgress]（边界章只送已读部分）；
     * 全书模式保留自己的明确同意，不继承阅读进度边界。
     * [targets] 非空时只整理这些人，结果并入已有人物资料；此时不取 CHAT 模型，也没有断点续跑。
     */
    suspend fun preview(bookId: Long, progressBounded: Boolean = false, quick: Boolean = false,
        targets: List<CharacterTarget> = emptyList()): BookCharactersPlan = withContext(Dispatchers.IO) {
        // 界面已经解析过一次名单，这里不信任调用方：重新去重、限长、限量。
        val requested = targets.map { target ->
            CharacterTarget(target.name.trim(), target.aliases.map(String::trim).filter(String::isNotEmpty).distinct())
        }.filter { it.name.isNotEmpty() }.distinctBy { it.name.lowercase() }
        require(requested.size <= CharacterScanTargets.MAX_TARGETS) { "一次最多扫描 ${CharacterScanTargets.MAX_TARGETS} 位人物" }
        require(requested.all { it.name.length <= CharacterScanTargets.MAX_NAME_LENGTH }) { "人物名不能超过 ${CharacterScanTargets.MAX_NAME_LENGTH} 字" }
        val targeted = requested.isNotEmpty()
        val model = clients.forRole(ModelRole.CHEAP)
        // 联网补充需要会调工具的对话模型；没配就跳过联网，名单与档案照样只用批量任务模型。
        val webModel = if ((quick || targeted) && webSearchSettings.current().enabled) runCatching { clients.forRole(ModelRole.CHAT) }.getOrNull() else null
        BookContentMutation.withBook(bookId) {
            val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: error("书籍正文已移除")
            val scope = if (progressBounded) ReadingScope.uptoProgress(book) else ReadingScope.WholeBook
            val all = library.getChapters(bookId).filter { it.charCount > 0 }.sortedBy { it.chapterIndex }
            val chapters = if (!progressBounded) all else all.filter { chapter ->
                scope.allowsChapter(chapter.chapterIndex) &&
                    (chapter.chapterIndex < scope.maxChapterIndex || scope.maxCharOffset > 0)
            }
            require(chapters.isNotEmpty()) {
                if (progressBounded) "还没有读过的正文可以提取" else "这本书没有可提取的正文"
            }
            val revision = library.bookTextRevision(bookId)
            val key = ChapterKnowledgeCodec.hash("${model.provider.id}|${model.provider.baseUrl}|${model.modelName}|${model.options}")
            val published = dao.getGuide(bookId)
            val checkpoint = dao.getCheckpoint(bookId)?.takeIf { it.generationId != published?.generationId &&
                it.sourceRevision == revision && it.modelKey == key && it.promptVersion == BookCharactersCodec.PROMPT_VERSION }
            BookCharactersPlan(bookId, book.title, revision, chapters, model, checkpoint?.generationId.takeUnless { quick || targeted },
                if (quick || targeted) 0 else checkpoint?.completedParts ?: 0, progressBounded, scope, quick, webModel,
                targets = requested)
        }
    }

    /**
     * 快速模式：名单 + 逐人档案，结果与逐段扫描走同一套手动资料保留与发布。
     * 每位人物整理完就进 [BookCharacterDossierEntity] 缓存——停止、失败或进程被杀后再跑只补剩下的人；
     * 失败或取消时已发布的资料不变。
     *
     * [BookCharactersPlan.targeted] 走同一条链路，只是名单换成用户点名的那些人：
     * 不请模型整理名单，因此也不会删掉点名的人；发布时改为**并入**已有人物资料
     * （[BookCharactersCodec.mergeTargeted]），不用只保留手动条目的 `mergeManual`。
     */
    suspend fun generateQuick(plan: BookCharactersPlan, onProgress: (KnowledgeProgress) -> Unit = {}): BookCharacterGuideEntity {
        require(plan.quick || plan.targeted) { "既不是快速模式，也没有点名的人物" }
        check(generating.add(plan.bookId)) { "这本书正在提取人物" }
        try {
            val chapters = withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) {
                validateLocked(plan)
                var total = 0L
                plan.chapters.mapNotNull { chapter ->
                    if (total >= QUICK_MAX_SOURCE_CHARS) return@mapNotNull null
                    val text = library.readChapterTextStrict(plan.bookId, chapter)
                    val readable = if (plan.progressBounded) plan.scope.readableText(chapter.chapterIndex, text) else text
                    total += readable.length
                    readable.takeIf { it.isNotBlank() }?.let { chapter.chapterIndex to it }
                }
            } }
            val titles = plan.chapters.associate { it.chapterIndex to it.title }
            val cache = dossierCache(plan)
            val result = research.get().run(CharacterResearchRequest(
                bookId = plan.bookId,
                bookTitle = plan.bookTitle,
                scope = plan.scope,
                progressBounded = plan.progressBounded,
                chapters = chapters,
                chapterTitle = { index -> titles[index] ?: "第${index + 1}章" },
                model = plan.model,
                webModel = plan.webModel,
                modelKey = plan.modelKey,
                cache = cache,
                // 点名的名单直接给进去，管线跳过名单请求；空列表按「没点名」处理。
                targets = plan.targets.takeIf { it.isNotEmpty() },
                validate = { validate(plan) }
            ), onProgress)
            val roleOrder = listOf(CharacterRole.PROTAGONIST, CharacterRole.MAJOR, CharacterRole.SUPPORTING)
            val characters = result.people.withIndex()
                .sortedWith(compareBy<IndexedValue<ResearchedCharacter>> { roleOrder.indexOf(it.value.entry.role) }.thenBy { it.index })
                .map { (_, person) ->
                    val profile = person.profile
                    BookCharacter(profile.name,
                        evidence = profile.evidence.distinctBy { it.fact.text }.sortedWith(compareBy({ it.chapterIndex }, { it.fact.start })).take(16),
                        attributes = profile.attributes, dossier = profile.dossier, role = person.entry.role, mentions = person.mentions)
                }
            val guide = BookCharacterGuide(characters, plan.chapterCount, plan.sourceCharacters, plan.progressBounded,
                result.externalNotes, if (plan.targeted) BookCharacterGuide.MODE_TARGETED else BookCharacterGuide.MODE_QUICK)
            val generationId = UUID.randomUUID().toString()
            return withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) {
                validateLocked(plan)
                val previous = BookCharactersCodec.visible(dao.getGuide(plan.bookId), plan.revision)?.guide
                // 只认出别称时把 identity 钉回用户输入的大名，才和已有的那个人对得上。
                val named = if (plan.targeted) BookCharactersCodec.anchor(guide, plan.targets) else guide
                val published = BookCharacterGuideEntity(plan.bookId, generationId, plan.revision, plan.modelKey,
                    listOfNotNull(plan.modelLabel, plan.webModelLabel).distinct().joinToString(" + "), BookCharactersCodec.PROMPT_VERSION,
                    AiJson.encodeToString(if (plan.targeted) BookCharactersCodec.mergeTargeted(named, previous) else BookCharactersCodec.mergeManual(guide, previous)),
                    System.currentTimeMillis())
                database.withTransaction {
                    dao.saveGuide(published)
                    // 只留下这一次用到的缓存；发布成功后才清，失败或取消时进度原样保留。
                    // 点名扫描只碰自己那几个人，清缓存会把上一次名单扫描留下的档案一起删掉。
                    if (!plan.targeted) dao.pruneDossiers(plan.bookId, result.usedKeys)
                }
                published
            } }
        } finally { generating.remove(plan.bookId) }
    }

    suspend fun generate(plan: BookCharactersPlan, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
        onWriting: (Int, Int) -> Unit = { _, _ -> }): BookCharacterGuideEntity {
        check(generating.add(plan.bookId)) { "这本书正在提取人物" }
        try {
            val generationId = withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) {
                validateLocked(plan)
                if (plan.resumeGenerationId != null) {
                    require(dao.getCheckpoint(plan.bookId)?.generationId == plan.resumeGenerationId) { "提取进度已变化，请重新确认" }
                    plan.resumeGenerationId
                } else {
                    // 不清缓存：重跑只换一代标识，正文与模型没变的段照旧复用。
                    UUID.randomUUID().toString()
                }
            } }
            val accumulator = BookCharactersCodec.Accumulator()
            for ((chapterNumber, chapter) in plan.chapters.withIndex()) {
                currentCoroutineContext().ensureActive()
                val source = withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) {
                    validateLocked(plan)
                    val text = library.readChapterTextStrict(plan.bookId, chapter)
                    if (plan.progressBounded) plan.scope.readableText(chapter.chapterIndex, text) else text
                } }
                val cached = withContext(Dispatchers.IO) { dao.getChapterParts(plan.bookId, chapter.chapterIndex).associateBy { it.start } }
                if (source.isNotBlank()) for (part in ChapterKnowledgeCodec.parts(source, enforceChapterLimit = false).filter { it.text.isNotBlank() }) {
                    currentCoroutineContext().ensureActive()
                    onProgress(chapterNumber, plan.chapterCount, chapter.title)
                    val hash = ChapterKnowledgeCodec.hash(part.text)
                    // 分段缓存不绑定本次 generationId：正文与模型没变的段，跨次提取直接复用，
                    // 「更新到当前进度」只为新读的章节付费。
                    val reused = cached[part.start]?.takeIf { row ->
                        row.sourceRevision == plan.revision && row.modelKey == plan.modelKey &&
                            row.promptVersion == BookCharactersCodec.PROMPT_VERSION && row.end == part.start + part.text.length && row.sourceHash == hash
                    }?.let { row -> cachedCharacters(row.contentJson, part) }
                    val people = reused ?: agent.extractCharacters(plan.model, plan.bookTitle, chapter.title, part) { validate(plan) }
                    withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) {
                        validateLocked(plan)
                        if (reused == null) dao.savePart(BookCharacterPartEntity(plan.bookId, chapter.chapterIndex, part.start,
                            part.start + part.text.length, generationId, plan.revision, hash, plan.modelKey,
                            BookCharactersCodec.PROMPT_VERSION, AiJson.encodeToString(people), System.currentTimeMillis()))
                    } }
                    accumulator.add(chapter.chapterIndex, people)
                }
                onProgress(chapterNumber + 1, plan.chapterCount, chapter.title)
            }
            // 收尾：为资料最多的几位写小传与性格（只送已核对的事实文字），写不出来不影响发布。
            val (guide, _) = biographer.get().enrich(accumulator.guide(plan.chapterCount, plan.sourceCharacters, plan.progressBounded),
                plan.bookTitle, plan.progressBounded, plan.model, plan.modelKey, dossierCache(plan, BIO_IDENTITY_PREFIX), { validate(plan) }, onWriting)
            val entry = BookCharacterGuideEntity(plan.bookId, generationId, plan.revision, plan.modelKey, plan.modelLabel,
                BookCharactersCodec.PROMPT_VERSION, AiJson.encodeToString(guide), System.currentTimeMillis())
            return withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) {
                validateLocked(plan)
                val previous = BookCharactersCodec.visible(dao.getGuide(plan.bookId), plan.revision)?.guide
                val published = entry.copy(contentJson = AiJson.encodeToString(BookCharactersCodec.mergeManual(guide, previous)))
                // 已核对的分段留着当缓存；清空是破坏性动作，只由调用方（删除资料）显式做一次。
                database.withTransaction {
                    dao.saveGuide(published)
                    dao.stampParts(plan.bookId, generationId)
                }
                published
            } }
        } finally { generating.remove(plan.bookId) }
    }

    suspend fun locate(entry: BookCharacterGuideEntity, evidence: CharacterEvidence): Pair<Int, Int> = withContext(Dispatchers.IO) {
        BookContentMutation.withBook(entry.bookId) {
            library.getBook(entry.bookId)?.takeIf { it.removedAt == 0L } ?: error("原书已移除，无法核对")
            require(entry.sourceRevision == library.bookTextRevision(entry.bookId)) { "正文已变化，请重新提取人物" }
            val chapter = library.getChapter(entry.bookId, evidence.chapterIndex) ?: error("原章节不存在")
            val text = library.readChapterTextStrict(entry.bookId, chapter)
            val fact = evidence.fact
            require(fact.start >= 0 && fact.end <= text.length && fact.end > fact.start && text.substring(fact.start, fact.end) == fact.quote) { "无法核对这条原文" }
            evidence.chapterIndex to fact.start
        }
    }

    /** 档案条目跳回原文：正文未改版、坐标仍落在章内才跳。 */
    suspend fun locate(entry: BookCharacterGuideEntity, anchor: SourceAnchor): Pair<Int, Int> = withContext(Dispatchers.IO) {
        BookContentMutation.withBook(entry.bookId) {
            library.getBook(entry.bookId)?.takeIf { it.removedAt == 0L } ?: error("原书已移除，无法核对")
            require(entry.sourceRevision == library.bookTextRevision(entry.bookId)) { "正文已变化，请重新提取人物" }
            val chapter = library.getChapter(entry.bookId, anchor.chapterIndex) ?: error("原章节不存在")
            val length = library.readChapterTextStrict(entry.bookId, chapter).length
            require(anchor.start >= 0 && anchor.end > anchor.start && anchor.end <= length) { "无法定位这段原文" }
            anchor.chapterIndex to anchor.start
        }
    }

    suspend fun delete(bookId: Long) {
        check(generating.add(bookId)) { "请先停止人物提取" }
        try { database.withTransaction { dao.deleteGuide(bookId); dao.deleteParts(bookId); dao.deleteDossiers(bookId) } }
        finally { generating.remove(bookId) }
    }

    suspend fun saveCharacter(bookId: Long, originalIdentity: String?, name: String,
        manualAttributes: ManualCharacterAttributes? = null) = withContext(Dispatchers.IO) {
        BookContentMutation.withBook(bookId) {
            library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: error("书籍已移除")
            val revision = library.bookTextRevision(bookId)
            val previous = dao.getGuide(bookId)
            val guide = BookCharactersCodec.visible(previous, revision)?.guide ?: BookCharacterGuide(emptyList(), 0, 0, true)
            val edited = BookCharactersCodec.edit(guide, originalIdentity, name, manualAttributes)
            val entry = previous?.copy(sourceRevision = revision, promptVersion = BookCharactersCodec.PROMPT_VERSION,
                contentJson = AiJson.encodeToString(edited), createdAt = System.currentTimeMillis())
                ?: BookCharacterGuideEntity(bookId, UUID.randomUUID().toString(), revision, "manual", "手动添加",
                    BookCharactersCodec.PROMPT_VERSION, AiJson.encodeToString(edited), System.currentTimeMillis())
            dao.saveGuide(entry)
        }
    }

    /** 缓存在写入时已逐条核对；读回只确认坐标仍指向同一段原文，规则与写入时的宽松核对一致。 */
    private fun cachedCharacters(raw: String, part: KnowledgePart): List<KnowledgeCharacter>? = runCatching {
        AiJson.decodeFromString<List<KnowledgeCharacter>>(raw).also { people ->
            require(people.size <= 24)
            people.forEach { person ->
                require(person.name.isNotBlank() && part.text.contains(person.name) && person.facts.size <= ChapterKnowledgeCodec.MAX_FACTS)
                require(person.facts.isNotEmpty() || person.attributes.isNotEmpty() || person.relationships.isNotEmpty())
                require(person.attributes.size <= 12 && person.relationships.size <= 12)
                person.attributes.forEach { require(it.value.isNotBlank()) }
                person.relationships.forEach { require(it.target.isNotBlank() && it.relation.isNotBlank() && it.fact.quote.contains(it.target)) }
                (person.facts + person.attributes.map { it.fact } + person.relationships.map { it.fact }).forEach { fact ->
                    val start = fact.start - part.start
                    val end = fact.end - part.start
                    require(fact.text.isNotBlank() && fact.quote.length in 4..300 && start >= 0 && end <= part.text.length && end > start &&
                        part.text.substring(start, end) == fact.quote)
                }
            }
        }
    }.getOrNull()

    /** 档案缓存只认同一正文修订；[identityPrefix] 区分人物档案与完整扫描的小传（后者不算「待继续」的人物）。 */
    private fun dossierCache(plan: BookCharactersPlan, identityPrefix: String = "") = object : DossierCache {
        override suspend fun load(key: String): String? = withContext(Dispatchers.IO) {
            dao.getDossier(plan.bookId, key)?.takeIf { it.sourceRevision == plan.revision }?.contentJson
        }
        override suspend fun store(key: String, identity: String, json: String) = withContext(Dispatchers.IO) {
            BookContentMutation.withBook(plan.bookId) {
                validateLocked(plan)
                dao.saveDossier(BookCharacterDossierEntity(plan.bookId, key, identityPrefix + identity, plan.revision, json, System.currentTimeMillis()))
            }
        }
    }

    private suspend fun validate(plan: BookCharactersPlan) = withContext(Dispatchers.IO) { BookContentMutation.withBook(plan.bookId) { validateLocked(plan) } }
    private suspend fun validateLocked(plan: BookCharactersPlan) {
        currentCoroutineContext().ensureActive()
        library.getBook(plan.bookId)?.takeIf { it.removedAt == 0L } ?: error("原书已移除，本次提取已停止")
        require(library.bookTextRevision(plan.bookId) == plan.revision) { "正文已变化，请重新提取人物" }
    }
}

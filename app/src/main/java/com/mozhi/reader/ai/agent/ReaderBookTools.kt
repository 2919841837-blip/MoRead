package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.library.BookQuoteLocator
import com.mozhi.reader.core.library.QuoteChapter
import com.mozhi.reader.core.library.QuoteLocation
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.Bm25LexicalRecall
import com.mozhi.reader.core.retrieval.NeighborExpander
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.retrieval.RetrievalCandidate
import com.mozhi.reader.core.retrieval.RetrievalPipeline
import com.mozhi.reader.core.retrieval.RetrievalRecall
import com.mozhi.reader.core.retrieval.RetrievalRequest
import com.mozhi.reader.core.vector.BookChunk
import com.mozhi.reader.core.vector.ChapterChunker
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import io.objectbox.query.ObjectWithScore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal data class ChapterDocument(
    val chapterIndex: Int,
    val title: String,
    val body: String,
    val readError: String? = null
)

/**
 * 按明确章节/范围读取原文，不依赖向量服务。它既是“概括第 N 章”的可靠数据源，
 * 也是 embedding 暂时不可用时的精确回退；当前章严格截到用户的 UTF-16 阅读偏移。
 */
internal class ReadBookSectionTool(
    private val libraryRepository: LibraryRepository,
    private val bookId: Long,
    private val readingScope: ReadingScope
) : AgentTool {
    override val displayName: String = "读取指定已读章节"

    override val spec: ToolSpec = ToolSpec(
        name = "read_book_section",
        description = if (!readingScope.isWholeBook) {
            "读取用户指定的已读章节或章节范围原文。概括某章或某部分时使用；不确定章节号时先调用 list_chapters 核对，不要猜测。章节号从 1 开始，当前章内容只返回到实际阅读位置。"
        } else {
            "读取用户指定的章节或章节范围原文。概括某章或某部分时使用；不确定章节号时先调用 list_chapters 核对，不要猜测。章节号从 1 开始。"
        },
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("from_chapter") {
                    put("type", "integer")
                    put("description", "起始章节号，从 1 开始")
                }
                putJsonObject("to_chapter") {
                    put("type", "integer")
                    put("description", "结束章节号（包含），省略时等于起始章节")
                }
                putJsonObject("start_char") {
                    put("type", "integer")
                    put("description", "起始章内 UTF-16 字符偏移，续读长章节时使用，默认 0")
                }
                putJsonObject("max_chars") {
                    put("type", "integer")
                    put("description", "本次最多返回的原文字符数，1000-24000，默认 12000")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("from_chapter")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val fromChapter = arguments["from_chapter"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Failure("INVALID_ARGUMENT", "缺少起始章节号 from_chapter")
        val toChapter = arguments["to_chapter"]?.jsonPrimitive?.intOrNull ?: fromChapter
        val startChar = (arguments["start_char"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val maxChars = (arguments["max_chars"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SECTION_CHARS)
            .coerceIn(MIN_SECTION_CHARS, MAX_SECTION_CHARS)
        if (fromChapter < 1 || toChapter < fromChapter) return ToolResult.Failure("INVALID_ARGUMENT", "章节范围无效：$fromChapter-$toChapter")

        val book = libraryRepository.getBook(bookId) ?: return ToolResult.Failure("SOURCE_MISSING", "未找到当前书籍")
        val maxReadableChapter = readingScope.clampLastChapter(book.totalChapters) + 1
        if (toChapter > maxReadableChapter) {
            return ToolResult.Failure("OUT_OF_SCOPE", if (!readingScope.isWholeBook) {
                "超出已读范围：用户只读到第 $maxReadableChapter 章，不能读取第 $toChapter 章。"
            } else {
                "章节超出本书范围：本书共 $maxReadableChapter 章，不能读取第 $toChapter 章。"
            })
        }
        val chapterEntities = libraryRepository.getChapters(bookId).associateBy { it.chapterIndex }
        val readableChapters = buildList {
            for (chapterNumber in fromChapter..toChapter) {
                val chapterIndex = chapterNumber - 1
                val chapter = chapterEntities[chapterIndex] ?: return ToolResult.Failure("SOURCE_MISSING", "未找到第 $chapterNumber 章")
                val fullBody = libraryRepository.readChapterText(bookId, chapter)
                add(
                    ChapterDocument(
                        chapterIndex = chapterIndex,
                        title = chapter.title,
                        body = if (!readingScope.isWholeBook && chapterIndex == readingScope.maxChapterIndex) {
                            fullBody.take(readingScope.maxCharOffset.coerceIn(0, fullBody.length))
                        } else {
                            fullBody
                        }
                    )
                )
            }
        }
        return formatBookSection(
            chapters = readableChapters,
            fromChapter = fromChapter,
            toChapter = toChapter,
            startChar = startChar,
            maxChars = maxChars
        )
    }

    private companion object {
        const val DEFAULT_SECTION_CHARS = 12_000
        const val MIN_SECTION_CHARS = 1_000
        const val MAX_SECTION_CHARS = 24_000
    }
}

internal fun formatBookSection(
    chapters: List<ChapterDocument>,
    fromChapter: Int,
    toChapter: Int,
    startChar: Int,
    maxChars: Int
): ToolResult {
    val byIndex = chapters.associateBy(ChapterDocument::chapterIndex)
    val output = StringBuilder()
    var remaining = maxChars
    for (chapterNumber in fromChapter..toChapter) {
        val chapter = byIndex[chapterNumber - 1] ?: return ToolResult.Failure("SOURCE_MISSING", "未找到第 $chapterNumber 章")
        val chapterStart = if (chapterNumber == fromChapter) startChar else 0
        if (chapterStart > chapter.body.length) {
            return ToolResult.Failure("INVALID_ARGUMENT", "第 $chapterNumber 章可读内容只到字符偏移 ${chapter.body.length}，start_char=$chapterStart 超出范围。")
        }
        val header = buildString {
            if (output.isNotEmpty()) append("\n\n")
            append("【第 ").append(chapterNumber).append(" 章")
            chapter.title.takeIf(String::isNotBlank)?.let { append("「").append(it).append("」") }
            append("】\n")
        }
        if (header.length >= remaining) {
            output.append("\n[内容未完：请从第 $chapterNumber 章 start_char=$chapterStart 继续读取]")
            break
        }
        output.append(header)
        remaining -= header.length
        val takeEnd = (chapterStart + remaining).coerceAtMost(chapter.body.length)
        output.append(chapter.body, chapterStart, takeEnd)
        remaining -= takeEnd - chapterStart
        if (takeEnd < chapter.body.length) {
            output.append(
                "\n[内容未完：请继续调用 read_book_section，from_chapter=$chapterNumber，" +
                    "to_chapter=$toChapter，start_char=$takeEnd]"
            )
            break
        }
        if (remaining <= 0 && chapterNumber < toChapter) {
            output.append(
                "\n[内容未完：请继续调用 read_book_section，from_chapter=${chapterNumber + 1}，" +
                    "to_chapter=$toChapter，start_char=0]"
            )
            break
        }
    }
    return ToolResult.Success(output.toString().ifBlank { "指定范围内还没有已读正文。" })
}

/** 逐字定位交给共享实现；批注要求唯一命中，聊天页的跳转允许多处，规则只有一份。 */
internal fun locateExactQuote(chapters: List<ChapterDocument>, quote: String, maxMatches: Int = Int.MAX_VALUE): List<QuoteLocation> =
    BookQuoteLocator.locateAll(
        chapters.map { QuoteChapter(it.chapterIndex, it.body) },
        quote, maxMatches
    )

/**
 * 本地向量查询失败（原生层/索引层的问题），与「查询向量生成失败」是两码事：
 * 后者是 embedding 请求的问题，前者说明索引查不了，只能整轮退回 BM25。
 * 注意不要因此重建索引——切片本身是好的，重建只会白花用户的 embedding 额度。
 */
private class VectorIndexQueryException(cause: Throwable) : RuntimeException(cause) {
    fun readableMessage(): String = cause?.message?.takeIf(String::isNotBlank)
        ?: cause?.javaClass?.simpleName?.takeIf(String::isNotBlank)
        ?: "ObjectBox 向量索引不可查询"
}

/**
 * 混合书内检索：优先向量，Embedding/索引不可用时自动退回本地关键词扫描。
 * 章节上限先在 ObjectBox 查询层过滤；当前章再按 UTF-16 阅读偏移做二次硬过滤，
 * 防止“已进入本章但尚未读到的后半章”通过向量命中泄露。
 */
internal class SearchBookTool(
    private val bookId: Long,
    private val getBook: suspend () -> BookEntity?,
    private val chapterTitle: suspend (Int) -> String?,
    private val embedQuery: suspend (String) -> FloatArray,
    private val store: () -> BoxStore,
    private val readingScope: ReadingScope,
    private val loadChapter: suspend (Int) -> ChapterDocument? = { null },
    private val currentScope: suspend () -> ReadingScope = { readingScope },
    private val loadChaptersThrough: suspend (Int) -> List<ChapterDocument> = { emptyList() },
    private val requestIndex: () -> Unit = {},
    private val indexingEnabled: suspend () -> Boolean = { true },
    private val canRequestIndex: Boolean = true,
    private val reranker: com.mozhi.reader.core.retrieval.ChunkReranker? = null,
    private val sourceRevision: (suspend () -> String)? = null,
    private val searchChunks: (FloatArray, Int, Int, Int) -> List<ObjectWithScore<BookChunk>> =
        { vector, recallDepth, minChapterIndex, maxChapterIndex ->
            VectorQueries.searchChunks(store(), bookId, vector, recallDepth, minChapterIndex, maxChapterIndex)
        }
) : AgentTool {
    override val displayName: String = "检索书中原文"
    override val spec = ToolSpec(
        name = "search_book",
        description = "在允许的阅读范围内用向量与 BM25 混合检索人物、场景、情节；支持自然语言、错名和同义改述，不要强制改用字面工具。" +
            "返回待核验的相关证据，不验证问题前提、不承诺穷举；BM25 是词项相关而非整串命中。" +
            "纯数字、已知引文的精确定位和字面计数优先用 grep_book；明确章节概括用 read_book_section。" +
            "可用 from_chapter/to_chapter 限定章节；只查某一章时两端填同一章号，实际范围仍受阅读水位限制。" +
            "无候选不证明事实不存在，排名不是事实成立概率。不能凭 top-k 或关键词数量声称列全所有事件。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") { put("type", "string"); put("description", "用自然语言描述人物、场景或情节，最多 512 字符") }
                putJsonObject("top_k") { put("type", "integer"); put("description", "候选片段数，1-8，默认 5") }
                putJsonObject("from_chapter") { put("type", "integer"); put("description", "起始章节号（从 1 开始），默认第 1 章") }
                putJsonObject("to_chapter") { put("type", "integer"); put("description", "结束章节号（包含），默认可读末章；超出阅读进度会截断") }
                putJsonObject("sort") {
                    put("type", "string")
                    putJsonArray("enum") { add(JsonPrimitive("chapter")); add(JsonPrimitive("relevance")) }
                    put("description", "先按相关性选候选，再按 chapter（默认）或 relevance 展示；不改变范围")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("query")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        kotlinx.coroutines.withTimeoutOrNull(15_000) { executeLocal(arguments) }
            ?: ToolResult.Failure("RESOURCE_LIMIT", "检索达到本地时间上限，正文覆盖未完成；请缩小章节范围或缩短查询后重试，不能据此断言没有匹配。")
    }

    private suspend fun executeLocal(arguments: JsonObject): ToolResult {
        val allowed = readingScope.intersect(currentScope())
        val query = (arguments["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.Failure("INVALID_ARGUMENT", "缺少检索词 query")
        if (query.length > 512) return ToolResult.Failure("INVALID_ARGUMENT", "检索词 query 过长，最多 512 字符")
        val topK = ((arguments["top_k"] as? JsonPrimitive)?.intOrNull ?: 5).coerceIn(1, 8)
        val sort = when ((arguments["sort"] as? JsonPrimitive)?.contentOrNull ?: "chapter") {
            "chapter" -> com.mozhi.reader.core.retrieval.RetrievalSort.CHAPTER
            "relevance" -> com.mozhi.reader.core.retrieval.RetrievalSort.RELEVANCE
            else -> return ToolResult.Failure("INVALID_ARGUMENT", "sort 无效，只支持 chapter 或 relevance")
        }
        val book = getBook() ?: return ToolResult.Failure("SOURCE_MISSING", "未找到当前书籍")
        if (book.removedAt > 0L) return ToolResult.Failure("SOURCE_MISSING", "本书正文已移除，当前仅保留个人记录，不能检索原文。")
        val range = try { parseChapterSearchBounds(arguments).resolve(book.totalChapters, allowed) }
            catch (error: IllegalArgumentException) { return ToolResult.Failure("INVALID_ARGUMENT", error.message ?: "章节范围无效") }
        val scope = range.scope
        val maxChapterIndex = scope.clampLastChapter(book.totalChapters)
        val revision = sourceRevision?.invoke()
        val corpus = loadReadableCorpus(bookId, book.totalChapters, scope, loadChapter, loadChaptersThrough, range.firstIndex)
        suspend fun validateSource() {
            check(getBook()?.removedAt == 0L) { "书籍正文已移除，请重新检索" }
            check(currentScope().contains(scope)) { "已读范围已变化，请重新检索" }
            check(sourceRevision?.invoke() == revision) { "正文已变化，请重新检索" }
        }
        var indexFailure: Throwable? = null
        val allowVectorIndex = try { indexingEnabled() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { indexFailure = error; false }
        val indexedChapters = if (!allowVectorIndex) emptySet() else try {
            VectorQueries.chaptersWithChunks(store(), bookId).toSet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            indexFailure = VectorIndexQueryException(error)
            emptySet()
        }
        val hasIndexInRange = indexedChapters.any { it in range.firstIndex..maxChapterIndex }
        var scheduleFailure: Exception? = null
        if (allowVectorIndex && indexFailure == null && !hasIndexInRange && canRequestIndex) {
            try { requestIndex() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { scheduleFailure = error }
        }
        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall { request ->
                indexFailure?.let { throw it }
                if (!allowVectorIndex || !hasIndexInRange || corpus.candidates.isEmpty()) return@RetrievalRecall emptyList()
                val vector = embedQuery(request.query)
                var depth = request.recallDepth
                var accepted: List<RetrievalCandidate>
                // Bounded replenishment after the strict current-chapter offset gate; embed only once.
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val hits = try { searchChunks(vector, depth, range.firstIndex, maxChapterIndex) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { throw VectorIndexQueryException(error) }
                    accepted = hits.mapNotNull { hit ->
                        hit.get().takeIf { it.bookId == bookId }?.let(corpus::vectorCandidate)
                            ?.copy(vectorDistance = hit.score)
                    }.distinctBy(RetrievalCandidate::key)
                    if (accepted.size >= request.topK || hits.size < depth || depth >= 480) break
                    depth = (depth * 2).coerceAtMost(480)
                }
                accepted.take(request.recallDepth)
            },
            lexicalRecall = RetrievalRecall { request ->
                val context = kotlinx.coroutines.currentCoroutineContext()
                Bm25LexicalRecall.rank(corpus.candidates, request.query, request.recallDepth) { context.ensureActive() }
            },
            reranker = reranker?.let { delegate -> com.mozhi.reader.core.retrieval.ChunkReranker { query, candidates ->
                validateSource()
                delegate.rerank(query, candidates).also { validateSource() }
            } },
            expander = NeighborExpander { hits, radius, scope ->
                expandNeighborWindows(hits, corpus.candidates, radius, scope, corpus.bodies)
            }
        )
        val result = pipeline.retrieve(RetrievalRequest(
            bookId, query, scope, topK = topK, recallDepth = maxOf(topK * 8, 60), sort = sort,
            firstChapterIndex = range.firstIndex,
            // A chapter-specific search should not lose results to a whole-book diversity quota.
            maxChunksPerChapter = if (range.firstIndex == maxChapterIndex &&
                ("from_chapter" in arguments || "to_chapter" in arguments)) topK else 2
        ))
        val notes = buildList {
            val vectorFailure = result.vectorFailure
            when {
                !allowVectorIndex && indexFailure != null -> add("向量配置读取失败；本次降级为本地 BM25 关键词检索")
                !allowVectorIndex -> add("本书未启用 AI 索引，本次为本地 BM25 关键词检索")
                vectorFailure is VectorIndexQueryException -> add("本地向量索引查询失败：${vectorFailure.readableMessage()}；已切换到本地 BM25 关键词检索")
                vectorFailure != null -> add("查询向量生成失败：${vectorFailure.message ?: "embedding 失败"}；本地索引已保留，已自动切换到本地 BM25 关键词检索")
                !hasIndexInRange && !canRequestIndex -> add("当前范围没有向量索引，本次使用本地 BM25 关键词检索；未新建索引")
                !hasIndexInRange -> add(if (scheduleFailure != null) "向量索引任务未能启动；已自动尝试本地 BM25 关键词检索"
                    else "本书向量索引正在后台建立；已自动尝试本地 BM25 关键词检索")
            }
            if (!corpus.complete) {
                add(buildString {
                    append("正文覆盖不完整：")
                    if (corpus.failureCount > 0) append("缺失或读取失败 ").append(corpus.failureCount).append(" 章")
                    if (corpus.failures.isNotEmpty()) append("（章节 ").append(corpus.failures.joinToString { (it + 1).toString() }).append("）")
                    if (corpus.resourceLimited) append("；达到本地扫描资源上限")
                    append("；不能据此断言没有匹配")
                })
            }
            if (result.lexicalFailure != null) add("词法通道失败；本次正文覆盖不完整，仅能提供其他通道的降级结果")
            if (result.rerankFailure != null) add("重排暂不可用，已回退到 RRF 融合排序")
        }
        val degraded = result.vectorFailure != null || result.lexicalFailure != null || !corpus.complete
        if (!currentScope().contains(scope)) return ToolResult.Failure("SCOPE_CHANGED", "阅读范围已缩小，本轮检索结果已丢弃，请重新查询。")
        if (getBook()?.removedAt != 0L || sourceRevision?.invoke() != revision) return ToolResult.Failure("CONTENT_CHANGED", "正文已变化或移除，本轮检索结果已丢弃，请重新查询。")
        val content = buildString {
            notes.forEach { append(it).append("。\n") }
            if (result.hits.isEmpty()) {
                append(if (degraded) "降级检索没有候选。" else "正常检索但没有候选。")
                append("这不是对问题前提的验证，也不证明相关事件不存在；可换用描述再次 search_book，")
                append("核对确切字面串请用 grep_book。")
                return@buildString
            }
            append(if (degraded) "降级检索返回候选。\n" else "正常检索返回候选。\n")
            append("本次检索范围：第 ${range.firstIndex + 1} 至 ${maxChapterIndex + 1} 章")
            append(if (allowed.isWholeBook) "。\n" else "（不超过当前阅读水位，边界章节仅含已读正文）。\n")
            append("这些是待核验的证据，不代表问题前提成立；非穷举，排序分数不是事实概率。\n")
            val delivered = result.diagnostics.selectedCandidates
            append("请求 $topK 段，本轮选择 $delivered 个候选；相邻候选合并后展示 ${result.hits.size} 个窗口。")
            append("这不代表全书只有这些内容。\n")
            result.hits.forEach { hit ->
                append("\n【第 ").append(hit.chapterIndex + 1).append(" 章")
                chapterTitle(hit.chapterIndex)?.takeIf(String::isNotBlank)?.let { append('「').append(it).append('」') }
                append("】\n")
                append("原文窗口 UTF-16 [").append(hit.startCharOffset).append(", ").append(hit.endCharOffset).append(")；")
                append("命中锚点：").append(hit.anchors.joinToString { "[${it.startCharOffset}, ${it.endCharOffset})" })
                append("。窗口内其余文字仅为补充上下文，并非直接命中。\n")
                append(hit.text).append('\n')
            }
        }
        val diagnostics = corpus.diagnostics + listOfNotNull(
            indexFailure?.let { ToolDiagnostic("INDEX_UNAVAILABLE", it) },
            result.vectorFailure?.takeUnless { it === indexFailure }?.let { ToolDiagnostic("VECTOR_RECALL_FAILED", it) },
            result.lexicalFailure?.let { ToolDiagnostic("LEXICAL_RECALL_FAILED", it) },
            result.rerankFailure?.let { ToolDiagnostic("RERANK_FAILED", it) },
            scheduleFailure?.let { ToolDiagnostic("INDEX_SCHEDULE_FAILED", it) }
        )
        return if (result.hits.isEmpty() && (!corpus.complete || result.lexicalFailure != null)) {
            ToolResult.Failure(if (corpus.resourceLimited) "RESOURCE_LIMIT" else "SOURCE_UNAVAILABLE", content,
                result.lexicalFailure ?: corpus.diagnostics.firstOrNull()?.cause)
        } else {
            ToolResult.Success(content, partial = degraded, diagnostics = diagnostics)
        }
    }
}

internal fun expandNeighborWindows(
    hits: List<RetrievalCandidate>,
    corpus: List<RetrievalCandidate>,
    radius: Int,
    scope: ReadingScope,
    chapterBodies: Map<Int, String> = emptyMap()
): List<RetrievalCandidate> {
    if (hits.isEmpty()) return emptyList()
    val byChapter = corpus.filter { scope.allowsChunk(it.chapterIndex, it.startCharOffset, it.endCharOffset) }
        .groupBy(RetrievalCandidate::chapterIndex)
    return hits.groupBy(RetrievalCandidate::chapterIndex).flatMap { (chapterIndex, chapterHits) ->
        val intervals = chapterHits.map { hit ->
            (hit.chunkIndex - radius).coerceAtLeast(0)..(hit.chunkIndex + radius)
        }.sortedBy(IntRange::first)
        val merged = mutableListOf<IntRange>()
        intervals.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else {
                merged += range
            }
        }
        val chapterChunks = byChapter[chapterIndex].orEmpty().associateBy(RetrievalCandidate::chunkIndex)
        merged.mapNotNull { range ->
            val chunks = range.mapNotNull(chapterChunks::get).sortedBy(RetrievalCandidate::chunkIndex)
            val windowHits = chapterHits.filter { it.chunkIndex in range }
            val body = chapterBodies[chapterIndex]
            if (chunks.isEmpty()) null else RetrievalCandidate(
                bookId = chunks.first().bookId,
                chapterIndex = chapterIndex,
                chunkIndex = chunks.first().chunkIndex,
                text = body?.substring(chunks.first().startCharOffset, chunks.last().endCharOffset)
                    ?: chunks.joinToString("\n") { it.text },
                startCharOffset = chunks.first().startCharOffset,
                endCharOffset = chunks.last().endCharOffset,
                vectorDistance = windowHits.mapNotNull(RetrievalCandidate::vectorDistance).minOrNull(),
                lexicalScore = windowHits.mapNotNull(RetrievalCandidate::lexicalScore).maxOrNull(),
                anchors = windowHits.flatMap { it.anchors }.distinct()
            )
        }
    }
}

internal data class TextSearchHit(val chapterIndex: Int, val text: String, val score: Int = 0)

/** Pure local fallback for books whose persisted chunk index does not exist yet. */
internal fun rankLexicalChapters(
    chapters: List<ChapterDocument>,
    query: String,
    topK: Int
): List<TextSearchHit> {
    val candidates = chapters.flatMap { chapter ->
        ChapterChunker.chunkWithOffsets(chapter.body).mapIndexed { index, chunk ->
            RetrievalCandidate(
                bookId = 0,
                chapterIndex = chapter.chapterIndex,
                chunkIndex = index,
                text = chunk.text,
                startCharOffset = chunk.startCharOffset,
                endCharOffset = chunk.endCharOffset
            )
        }
    }
    return Bm25LexicalRecall.rank(candidates, query, topK).map { hit ->
        TextSearchHit(hit.chapterIndex, hit.text, ((hit.lexicalScore ?: 0.0) * 1000).toInt())
    }
}

/**
 * 角色长期记忆检索。记忆按 personaId 隔离，再按两条边界收窄：
 * [bookId] 非 null 时只回忆这本书（跨书对话检索关闭），[maskId] 保证面具间互不穿帮。
 */
internal class RecallMemoryTool(
    private val personaId: Long,
    private val embedQuery: suspend (String) -> FloatArray,
    private val store: () -> BoxStore,
    private val bookId: Long? = null,
    private val currentBookId: Long? = bookId,
    private val maskId: Long = 0L,
    private val readingScope: ReadingScope
) : AgentTool {

    override val displayName: String = "回忆过往交流"

    override val spec: ToolSpec = ToolSpec(
        name = "recall_memory",
        description = "检索你与用户过往交流沉淀下来的长期记忆。用户提到之前说过的话、个人偏好或约定时使用。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "要回忆的内容线索")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("query")) }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.Failure("INVALID_ARGUMENT", "缺少检索词 query")
        val vector = try {
            embedQuery(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ToolResult.Failure("EMBEDDING_FAILED", "记忆检索不可用：${error.message ?: "embedding 失败"}", error)
        }
        val hits = try {
            VectorQueries.searchMemories(
                store(), personaId, vector, TOP_K, bookId, maskId,
                currentBookId, readingScope.maxChapterIndex, readingScope.maxCharOffset
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 记忆是增益项：本地向量库查不了就说清楚，不要把异常抛回 Agent 循环。
            return ToolResult.Failure("MEMORY_QUERY_FAILED", "记忆检索不可用：${error.message ?: error.javaClass.simpleName}", error)
        }
        if (hits.isEmpty()) return ToolResult.Success("还没有与此相关的长期记忆。")
        return ToolResult.Success("相关记忆（按相关度排序）：\n" +
            hits.joinToString("\n") { "- ${it.get().summary}" })
    }

    private companion object {
        const val TOP_K = 5
    }
}

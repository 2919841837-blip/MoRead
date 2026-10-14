package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.agent.ChapterDocument
import com.mozhi.reader.ai.agent.ReadableCorpus
import com.mozhi.reader.ai.agent.loadReadableCorpus
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.knowledge.BookCharactersCodec
import com.mozhi.reader.ai.knowledge.ChapterKnowledgeCodec
import com.mozhi.reader.ai.knowledge.CharacterAttributeKind
import com.mozhi.reader.ai.knowledge.CharacterRole
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.BookEmbeddingSettingsStore
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import com.mozhi.reader.core.library.AnnotationRecall
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.RecallCard
import com.mozhi.reader.core.library.RecallDiscussion
import com.mozhi.reader.core.library.RecallOutline
import com.mozhi.reader.core.library.RecallProactiveNote
import com.mozhi.reader.core.library.RecallSnippet
import com.mozhi.reader.core.retrieval.Bm25LexicalRecall
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.retrieval.RetrievalCandidate
import com.mozhi.reader.core.retrieval.RetrievalQuery
import com.mozhi.reader.core.retrieval.RetrievalSelection
import com.mozhi.reader.core.retrieval.RrfFusion
import com.mozhi.reader.core.vector.Embeddings
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only prior chapters are cached, for the lifetime of one chapter job. Vector recall (paid
 *  embedding) is opt-in per book and silently falls back to BM25 whenever it is unavailable. */
@Singleton
class ProactiveAnnotationContextRepository @Inject constructor(
    private val library: LibraryRepository,
    private val database: MoReadDatabase,
    private val clientFactory: dagger.Lazy<AiClientFactory>,
    private val vectorStore: dagger.Lazy<BoxStore>,
    private val embeddingSettingsStore: BookEmbeddingSettingsStore
) {
    internal suspend fun prepare(bookId: Long, chapterIndex: Int): PreparedAnnotationContext = withContext(Dispatchers.IO) {
        val book = library.getBook(bookId)?.takeIf { it.removedAt == 0L } ?: error("书籍正文已移除")
        val revision = library.bookTextRevision(bookId)
        if (chapterIndex <= 0) return@withContext PreparedAnnotationContext(revision)
        val scope = ReadingScope.upto(chapterIndex - 1, Int.MAX_VALUE)
        val titles = mutableMapOf<Int, String>()
        // The target chapter is already loaded; old imports may have a stale chapter count.
        val corpus = loadReadableCorpus(bookId, maxOf(book.totalChapters, chapterIndex), scope,
            loadChapter = { index -> library.getChapter(bookId, index)?.let { chapter ->
                titles[index] = chapter.title
                ChapterDocument(index, chapter.title, library.readChapterTextStrict(bookId, chapter))
            } }, loadChaptersThrough = { emptyList() })
        val outlines = database.chapterKnowledgeDao().getBefore(bookId, chapterIndex).mapNotNull { entry ->
            currentCoroutineContext().ensureActive()
            validatedAnnotationOutline(entry, scope, revision, corpus.bodies[entry.chapterIndex])
        }
        check(isCurrent(bookId, revision)) { "正文已变化，请重新准备段评上下文" }
        val vectorRecall = resolveVectorRecall(bookId, corpus, chapterIndex)
        PreparedAnnotationContext(revision, corpus.candidates, outlines, titles,
            !corpus.complete,
            recallCharacterCards(bookId, revision, chapterIndex),
            recallConclusions(bookId, chapterIndex), vectorRecall)
    }

    /** 向量召回：本书启用了 AI 索引、前文已有切片、EMBEDDING 模型可用三者齐备才启用；缺任意一环静默退回纯 BM25。 */
    private suspend fun resolveVectorRecall(bookId: Long, corpus: ReadableCorpus, currentChapter: Int): VectorRecall? {
        if (currentChapter <= 0) return null
        val enabled = try {
            embeddingSettingsStore.isEnabled(bookId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (!enabled) return null
        val store = try {
            vectorStore.get()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val hasIndex = try {
            VectorQueries.chaptersWithChunks(store, bookId).any { it in 0 until currentChapter }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (!hasIndex) return null
        val embedQuery: suspend (String) -> FloatArray = try {
            val client = clientFactory.get().forRole(ModelRole.EMBEDDING).client
            { query: String -> Embeddings.conformToIndex(client.embed(listOf(query)).first()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val search: suspend (FloatArray, Int) -> List<RetrievalCandidate> = { vector, depth ->
            VectorQueries.searchChunks(store, bookId, vector, depth, 0, currentChapter - 1)
                .mapNotNull { hit ->
                    hit.get().takeIf { it.bookId == bookId }
                        ?.let(corpus::vectorCandidate)
                        ?.copy(vectorDistance = hit.score)
                }.distinctBy(RetrievalCandidate::key)
        }
        return VectorRecall(embedQuery, search, SNIPPET_TOP_K, SNIPPET_RECALL_DEPTH)
    }

    internal suspend fun isCurrent(bookId: Long, revision: String): Boolean = try {
        library.getBook(bookId)?.removedAt == 0L && library.bookTextRevision(bookId) == revision
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { false }

    /** 角色卡：按当前章过滤档案的全部字段（事实/关系/性格/经历等），拼成每位人物的档案；没有资料就整块跳过。 */
    private suspend fun recallCharacterCards(bookId: Long, revision: String, currentChapter: Int): List<AnnotationCharacterCard> {
        val guide = BookCharactersCodec.visible(database.bookCharacterDao().getGuide(bookId), revision)?.guide ?: return emptyList()
        return guide.characters.mapNotNull { person -> cardFor(person, currentChapter, guide.progressBounded) }
    }

    /** 前文结论：AI 段评的想法 + 讨论串里的 AI 回复，合并成一个召回源；章锚与写时进度线已在 SQL 里双重过滤。 */
    private suspend fun recallConclusions(bookId: Long, currentChapter: Int): List<AnnotationConclusion> {
        if (currentChapter <= 0) return emptyList()
        val proactive = database.annotationDao().getAiAnnotations(bookId, currentChapter)
            .filter { it.note.isNotBlank() }
            .map { AnnotationConclusion(it.chapterIndex, it.note, ConclusionSource.PROACTIVE_NOTE) }
        val discussions = database.annotationDao().getAiDiscussionConclusions(bookId, currentChapter)
            .filter { it.contentMarkdown.isNotBlank() }
            .map { AnnotationConclusion(it.chapterIndex, it.contentMarkdown, ConclusionSource.DISCUSSION) }
        return proactive + discussions
    }
}

internal data class AnnotationOutline(val chapterIndex: Int, val text: String)

/** Never reuse a whole-chapter outline whose source reaches beyond the allowed boundary. */
internal fun validatedAnnotationOutline(
    entry: ChapterKnowledgeEntity, scope: ReadingScope, revision: String, body: String?
): AnnotationOutline? {
    val visible = ChapterKnowledgeCodec.visible(entry, scope, revision) ?: return null
    if (body == null || entry.sourceEnd > body.length ||
        ChapterKnowledgeCodec.hash(body.take(entry.sourceEnd)) != entry.sourceHash) return null
    return AnnotationOutline(entry.chapterIndex, visible.content.readableOutline)
}

/** 角色卡一段字段的收窄策略：[IDENTITY]/[TRAIT] 本人出场即注入，[RELATION] 逐条按目标名，[CONTENT] 走相关性闸。 */
internal enum class CardFieldKind { IDENTITY, RELATION, TRAIT, CONTENT }

/** 角色卡里一条可独立注入/收窄的字段；[line] 是完整行（含标签），[target] 仅关系字段有值。 */
internal data class CardField(
    val kind: CardFieldKind,
    val line: String,
    val text: String,
    val target: String? = null
)

/** 角色卡里一位上场人物的结构化档案：字段逐条保留，注入时按当前段收窄（本人出场注入自身字段，关系只留目标在场的）。 */
internal data class AnnotationCharacterCard(
    val name: String,
    val aliases: List<String>,
    val fields: List<CardField>
) {
    /** 关系对象名（仅用于名字召回匹配）。 */
    val relationTargets: List<String> get() = fields.mapNotNull { it.target }
}

/** 前文结论的来源：AI 段评的想法（楼主层）或讨论串里的 AI 回复。 */
internal enum class ConclusionSource { PROACTIVE_NOTE, DISCUSSION }

/** 一条前文 AI 结论（段评想法或讨论回复），带章锚与来源标记；两类合并成一个召回源。 */
internal data class AnnotationConclusion(val chapterIndex: Int, val text: String, val source: ConclusionSource)

/** 每条段评最多注入几位角色卡人物。 */
private const val MAX_CARDS = 4

/** 每位角色卡人物最多保留几条前文经历。 */
private const val CARD_EXPERIENCES = 6

/** 每位角色卡人物最多保留几条关系。 */
private const val CARD_RELATIONS = 4

/** 角色卡内容字段（经历/简介/小传/成长/外貌）的相关性下限：BM25 不到本轮最高分这个比例就不注入。 */
private const val CARD_CONTENT_RELEVANCE_RATIO = 0.35

/** 每条段评最多召回几条前文结论（段评想法 + 讨论回复合并计数）。 */
private const val MAX_CONCLUSIONS = 8

/** 每条段评正文片段召回的名额与向量/BM25 的召回深度。 */
private const val SNIPPET_TOP_K = 12
private const val SNIPPET_RECALL_DEPTH = 48

/** 向量余弦距离闸：超过该距离的语义片段不算相关（与 RetrievalRequest.maxVectorDistance 一致）。 */
private const val VECTOR_MAX_DISTANCE = 0.80

private fun roleLabel(role: CharacterRole): String = when (role) {
    CharacterRole.PROTAGONIST -> "主角"
    CharacterRole.MAJOR -> "主要人物"
    CharacterRole.SUPPORTING -> "配角"
}

/** 一条前文社会关系（[target] 是「对方」）。 */
private data class CardRelation(val target: String, val label: String, val chapter: Int)

private fun relationLabel(target: String, relation: String, detail: String): String {
    val detailText = detail.takeIf(String::isNotBlank)?.let { "（" + it + "）" }.orEmpty()
    return if (relation.isBlank()) target + detailText else target + "：" + relation + detailText
}

/** 取这个人物的社会关系：手写 > 档案（dossier，带 [detail]）> 老结构（evidence 锚点），只留前文、按最近排序去重。 */
private fun cardRelations(person: BookCharacter, currentChapter: Int): List<CardRelation> {
    val manual = person.manualRelations()?.mapNotNull { relation ->
        relation.target.takeIf(String::isNotBlank)?.let {
            CardRelation(it, relationLabel(it, relation.relation, relation.detail), Int.MAX_VALUE)
        }
    }
    if (manual != null) return manual.distinctBy { it.target }.take(CARD_RELATIONS)
    val dossier = person.dossier?.relationships.orEmpty().mapNotNull { relation ->
        val chapter = relation.anchor?.chapterIndex ?: return@mapNotNull null  // 无章节锚点不召回，防剧透
        if (chapter >= currentChapter) return@mapNotNull null
        CardRelation(relation.target, relationLabel(relation.target, relation.relation, relation.detail), chapter)
    }
    val evidence = person.relationships.mapNotNull { relation ->
        if (relation.evidence.chapterIndex >= currentChapter) return@mapNotNull null
        CardRelation(relation.target, relationLabel(relation.target, relation.relation, ""), relation.evidence.chapterIndex)
    }
    return (dossier + evidence).distinctBy { it.target }.sortedByDescending { it.chapter }.take(CARD_RELATIONS)
}

/** 前文性格：手写（词 + 说明）> 档案（词 + 说明，章锚过滤）> 旧属性性格词（evidence 锚点）。 */
private fun traitsBefore(person: BookCharacter, currentChapter: Int, bounded: Boolean): List<String> {
    val manual = person.manualTraits()
    if (manual != null) return manual.map { trait ->
        trait.trait + trait.detail.takeIf(String::isNotBlank)?.let { "（" + it + "）" }.orEmpty()
    }.filter(String::isNotBlank)
    val dossier = person.dossier?.personality.orEmpty().mapNotNull { trait ->
        val chapter = trait.anchor?.chapterIndex
        val keep = if (chapter != null) chapter < currentChapter else bounded
        if (!keep) return@mapNotNull null
        trait.trait + trait.detail.takeIf(String::isNotBlank)?.let { "（" + it + "）" }.orEmpty()
    }.filter(String::isNotBlank)
    if (dossier.isNotEmpty()) return dossier
    return person.attributes.filter { it.kind == CharacterAttributeKind.PERSONALITY && it.evidence.chapterIndex < currentChapter }
        .map { it.value }.distinct()
}

/** 前文经历：手写 > 上一版「人物资料」> 档案经历 > 逐条事实；AI 来源按章锚过滤，手写始终算数。 */
private fun experiencesBefore(person: BookCharacter, currentChapter: Int): List<String> {
    val manual = person.manualExperiences()
    if (manual != null) return manual.map { it.text }.filter(String::isNotBlank)
    val legacy = person.manualDescription?.takeIf(String::isNotBlank)
        ?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toList()
    if (!legacy.isNullOrEmpty()) return legacy
    val dossier = person.dossier?.experiences.orEmpty().mapNotNull { event ->
        val chapter = event.chapterIndex ?: event.anchor?.chapterIndex ?: return@mapNotNull null
        event.text.takeIf { chapter < currentChapter && it.isNotBlank() }
    }
    if (dossier.isNotEmpty()) return dossier
    return person.evidence.filter { it.chapterIndex < currentChapter }.map { it.fact.text }
}

/** 无章节锚点的标量字段（简介/小传/成长）：手写优先且始终安全；AI 值只在进度受限扫描时召回（全书扫描可能含未读情节）。 */
private fun summaryField(manual: String?, ai: String, bounded: Boolean): String? =
    manual?.takeIf(String::isNotBlank) ?: ai.takeIf { bounded }.orEmpty().takeIf(String::isNotBlank)

/** 把一位人物压成结构化角色卡字段：主次/身份/性别年龄/性格为自身字段，关系逐条，经历/简介/小传/成长/外貌为内容字段，全部只取前文或可信来源。 */
private fun cardFields(person: BookCharacter, currentChapter: Int, bounded: Boolean): List<CardField>? {
    if (person.name.isBlank()) return null
    val fields = buildList {
        person.effectiveRole()?.let { add(CardField(CardFieldKind.IDENTITY, "主次：" + roleLabel(it), roleLabel(it))) }
        person.attributeValues(CharacterAttributeKind.IDENTITY).firstOrNull()?.takeIf(String::isNotBlank)
            ?.let { add(CardField(CardFieldKind.IDENTITY, "身份：" + it, it)) }
        val genderAge = listOfNotNull(
            person.attributeValues(CharacterAttributeKind.GENDER).firstOrNull()?.takeIf(String::isNotBlank),
            person.attributeValues(CharacterAttributeKind.AGE).firstOrNull()?.takeIf(String::isNotBlank)
        )
        if (genderAge.isNotEmpty()) {
            val text = genderAge.joinToString("，")
            add(CardField(CardFieldKind.IDENTITY, "性别年龄：" + text, text))
        }
        cardRelations(person, currentChapter).forEach { relation ->
            add(CardField(CardFieldKind.RELATION, "关系：" + relation.label, relation.label, relation.target))
        }
        val traits = traitsBefore(person, currentChapter, bounded)
        if (traits.isNotEmpty()) {
            val text = traits.joinToString("；")
            add(CardField(CardFieldKind.TRAIT, "性格：" + text, text))
        }
        val experiences = experiencesBefore(person, currentChapter).takeLast(CARD_EXPERIENCES)
        if (experiences.isNotEmpty()) {
            val text = experiences.joinToString("；")
            add(CardField(CardFieldKind.CONTENT, "经历：" + text, text))
        }
        summaryField(person.manualAttributes?.tagline, person.dossier?.tagline.orEmpty(), bounded)
            ?.let { add(CardField(CardFieldKind.CONTENT, "简介：" + it, it)) }
        summaryField(person.manualAttributes?.bio, person.dossier?.bio.orEmpty(), bounded)
            ?.let { add(CardField(CardFieldKind.CONTENT, "小传：" + it, it)) }
        summaryField(person.manualAttributes?.arc, person.dossier?.arc.orEmpty(), bounded)
            ?.let { add(CardField(CardFieldKind.CONTENT, "成长：" + it, it)) }
        person.attributeValues(CharacterAttributeKind.APPEARANCE).firstOrNull()?.takeIf(String::isNotBlank)
            ?.let { add(CardField(CardFieldKind.CONTENT, "外貌：" + it, it)) }
    }
    return fields.ifEmpty { null }
}

/** 完整档案（全部字段逐行）；仅用于既有测试断言字段提取是否齐全，注入路径走 [narrowedCardLines] 收窄。 */
internal fun cardProfile(person: BookCharacter, currentChapter: Int, bounded: Boolean): String? =
    cardFields(person, currentChapter, bounded)?.joinToString("\n") { it.line }?.ifBlank { null }

internal fun cardFor(person: BookCharacter, currentChapter: Int, bounded: Boolean): AnnotationCharacterCard? =
    cardFields(person, currentChapter, bounded)?.let { AnnotationCharacterCard(person.name, person.aliases, it) }

/** 角色卡内容字段的相关性闸：对当前段做 BM25 打分，只保留不低于最高分 [CARD_CONTENT_RELEVANCE_RATIO] 的字段。 */
private fun contentFieldsOnStage(fields: List<CardField>, window: String, checkActive: () -> Unit): List<CardField> {
    if (fields.isEmpty()) return emptyList()
    val candidates = fields.mapIndexed { index, field -> RetrievalCandidate(0, 0, index, field.text) }
    val ranked = Bm25LexicalRecall.rank(candidates, window, fields.size, checkActive)
    val best = ranked.firstOrNull()?.lexicalScore ?: 0.0
    if (best <= 0.0) return emptyList()
    val scores = ranked.associateBy { it.chunkIndex }
    return fields.filterIndexed { index, _ -> (scores[index]?.lexicalScore ?: 0.0) >= best * CARD_CONTENT_RELEVANCE_RATIO }
}

/** 收窄一位角色卡到当前段：本人出场注入自身字段 + 相关内容字段，关系只留目标在场的。返回选中的字段行（无名字头）。 */
internal fun narrowedCardLines(card: AnnotationCharacterCard, window: String, checkActive: () -> Unit): List<String> {
    val selfOnStage = (listOf(card.name) + card.aliases).any { it.length >= 2 && window.contains(it) }
    val selfLines = if (selfOnStage) {
        val fixed = card.fields.filter { it.kind == CardFieldKind.IDENTITY || it.kind == CardFieldKind.TRAIT }.map { it.line }
        fixed + contentFieldsOnStage(card.fields.filter { it.kind == CardFieldKind.CONTENT }, window, checkActive).map { it.line }
    } else emptyList()
    val relationLines = card.fields.filter { it.kind == CardFieldKind.RELATION }
        .filter { field -> field.target?.let { it.length >= 2 && window.contains(it) } == true }
        .map { it.line }
    return selfLines + relationLines
}

internal data class AnnotationReadingContext(
    val prefix: String,
    val target: String,
    val background: String,
    /** 这条段评实际召回的前文资料明细；随批注落库，供详情页折叠展示。 */
    val recall: AnnotationRecall = AnnotationRecall()
) {
    val chars: Int get() = prefix.length + target.length + background.length
}

/** 前文正文的向量召回通道。一次失败即静默禁用，本段评不再重试，确保 EMBEDDING 出问题时退回纯 BM25。 */
internal class VectorRecall(
    private val embedQuery: suspend (String) -> FloatArray,
    private val search: suspend (FloatArray, Int) -> List<RetrievalCandidate>,
    private val topK: Int,
    private val recallDepth: Int
) {
    @Volatile private var disabled = false

    suspend fun recall(query: String): List<RetrievalCandidate> {
        if (disabled) return emptyList()
        return try {
            val vector = embedQuery(query)
            var depth = recallDepth.coerceAtLeast(1)
            var accepted: List<RetrievalCandidate>
            while (true) {
                currentCoroutineContext().ensureActive()
                val hits = search(vector, depth)
                accepted = hits
                if (accepted.size >= topK || hits.size < depth || depth >= MAX_DEPTH) break
                depth = (depth * 2).coerceAtMost(MAX_DEPTH)
            }
            accepted.take(recallDepth)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            disabled = true
            emptyList()
        }
    }

    private companion object { const val MAX_DEPTH = 480 }
}

internal class PreparedAnnotationContext(
    val revision: String,
    private val candidates: List<RetrievalCandidate> = emptyList(),
    private val outlines: List<AnnotationOutline> = emptyList(),
    private val titles: Map<Int, String> = emptyMap(),
    private val partial: Boolean = false,
    private val characterCards: List<AnnotationCharacterCard> = emptyList(),
    private val conclusions: List<AnnotationConclusion> = emptyList(),
    private val vectorRecall: VectorRecall? = null
) {
    suspend fun forParagraph(body: String, paragraph: ProactiveAnnotationParagraph, budgetChars: Int): AnnotationReadingContext =
        withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val budget = budgetChars.coerceIn(ProactiveAnnotationContextSettings.MIN_CHARS, ProactiveAnnotationContextSettings.MAX_CHARS)
            val target = body.substring(paragraph.start, paragraph.end)
            // Target is repeated for reliable quote grounding. Its two copies both consume budget.
            val backgroundBudget = ((budget - target.length * 2).coerceAtLeast(0) * 0.45).toInt()
            val query = ProactiveAnnotationParagraphs.prefix(body, paragraph, target.length + 400)
            val ranked = Bm25LexicalRecall.rank(candidates, query, SNIPPET_RECALL_DEPTH) { context.ensureActive() }
            val vectorHits = vectorRecall?.recall(query).orEmpty()
            val fused = RrfFusion.fuse(
                vector = vectorHits,
                lexical = ranked,
                lexicalWeight = RetrievalQuery.lexicalWeightFor(query)
            )
            val bestLexical = ranked.firstOrNull()?.lexicalScore ?: 0.0
            val hits = RetrievalSelection.select(
                fused.filter { candidate ->
                    (candidate.lexicalScore != null && candidate.lexicalScore >= bestLexical * 0.35) ||
                        (candidate.vectorDistance != null && candidate.vectorDistance <= VECTOR_MAX_DISTANCE)
                },
                topK = SNIPPET_TOP_K, maxPerChapter = 2, literalKeys = emptySet(), literalReserve = 0
            ).selected
            val outlineCandidates = outlines.map { outline ->
                RetrievalCandidate(0, outline.chapterIndex, 0, outline.text)
            }
            val rankedOutlines = Bm25LexicalRecall.rank(outlineCandidates, query, 4) { context.ensureActive() }
            // 相关性下限：低于最高分 35% 的梗概不召回，避免只撞几个高频字就当相关。
            val bestOutline = rankedOutlines.firstOrNull()?.lexicalScore ?: 0.0
            val relatedOutlines = rankedOutlines.filter { (it.lexicalScore ?: 0.0) >= bestOutline * 0.35 }
            // 召回落空就空着：不兜底注入上一章梗概，避免把无关前文带进提示词污染输出。
            val selectedOutlines = relatedOutlines
            fun chapterLabel(index: Int): String = "第 "+ (index + 1) + " 章" +
                titles[index]?.takeIf { it.isNotBlank() }?.let { "「" + it.take(80) + "」" }.orEmpty()
            val onStageCards = cardsOnStage(query).mapNotNull { card ->
                narrowedCardLines(card, query) { context.ensureActive() }
                    .takeIf { it.isNotEmpty() }?.let { card to it }
            }
            val conclusionCandidates = conclusions.mapIndexed { index, conclusion ->
                RetrievalCandidate(0, conclusion.chapterIndex, index, conclusion.text)
            }
            val rankedConclusions = Bm25LexicalRecall.rank(conclusionCandidates, query, MAX_CONCLUSIONS) { context.ensureActive() }
            val bestConclusion = rankedConclusions.firstOrNull()?.lexicalScore ?: 0.0
            val onStageConclusions = rankedConclusions
                .filter { (it.lexicalScore ?: 0.0) >= bestConclusion * 0.35 }
                .mapNotNull { conclusions.getOrNull(it.chunkIndex) }
            val cardsText = packAnnotationBackground(
                if (onStageCards.isEmpty()) emptyList() else
                    listOf("【角色卡】与本文相关人物的档案（辅助资料，以原文为准）：") +
                        onStageCards.map { (card, lines) -> card.name + "\n" + lines.joinToString("\n") },
                backgroundBudget / 4
            )
            val conclusionsText = packAnnotationBackground(
                if (onStageConclusions.isEmpty()) emptyList() else
                    listOf("【前文结论】你此前写下的段评/讨论结论（辅助资料）：") +
                        onStageConclusions.map { chapterLabel(it.chapterIndex) + conclusionPrefix(it.source) + it.text },
                backgroundBudget / 4
            )
            val summaries = packAnnotationBackground(selectedOutlines.map {
                chapterLabel(it.chapterIndex) + "梗概（辅助资料，以原文为准）：\n" + it.text
            }, backgroundBudget / 4)
            val snippets = packAnnotationBackground(hits.map {
                chapterLabel(it.chapterIndex) + "原文：\n" + it.text
            }, (backgroundBudget - summaries.length - cardsText.length - conclusionsText.length - 6).coerceAtLeast(0))
            val warning = if (partial) "前文检索只覆盖部分正文；未命中不代表书中没有。" else ""
            val background = packAnnotationBackground(listOf(warning, summaries, cardsText, conclusionsText, snippets), backgroundBudget)
            val prefix = ProactiveAnnotationParagraphs.prefix(body, paragraph, budget - target.length - background.length)
            val recall = AnnotationRecall(
                outlines = selectedOutlines.map { RecallOutline(chapterLabel(it.chapterIndex), it.text) },
                characterCards = onStageCards.map { (card, lines) -> RecallCard(card.name, lines.joinToString("\n")) },
                snippets = hits.map { RecallSnippet(chapterLabel(it.chapterIndex), it.text, snippetSource(it)) },
                discussions = onStageConclusions.filter { it.source == ConclusionSource.DISCUSSION }
                    .map { RecallDiscussion(chapterLabel(it.chapterIndex), it.text) },
                proactiveNotes = onStageConclusions.filter { it.source == ConclusionSource.PROACTIVE_NOTE }
                    .map { RecallProactiveNote(chapterLabel(it.chapterIndex), it.text) }
            )
            AnnotationReadingContext(prefix, target, background, recall)
        }

    /** 角色卡：目标段点名该人物（名字或别称），或点名它的关系对象时注入，最多 [MAX_CARDS] 位。 */
    private fun cardsOnStage(window: String): List<AnnotationCharacterCard> =
        characterCards.filter { card ->
            (listOf(card.name) + card.aliases + card.relationTargets).any { it.length >= 2 && window.contains(it) }
        }.take(MAX_CARDS)
}

/** 片段来源标签：向量 / BM25 / 两者融合，只用于召回明细展示，不参与召回逻辑。 */
private fun snippetSource(candidate: RetrievalCandidate): String = when {
    candidate.vectorDistance != null && candidate.lexicalScore != null -> "向量+BM25"
    candidate.vectorDistance != null -> "向量"
    else -> "BM25"
}

/** 前文结论的来源前缀：合并成一个召回块后仍保留「段评/讨论」来路，便于追溯。 */
private fun conclusionPrefix(source: ConclusionSource): String = when (source) {
    ConclusionSource.PROACTIVE_NOTE -> "段评："
    ConclusionSource.DISCUSSION -> "讨论："
}

/** Clip on a UTF-16 boundary; labels and separators count against the same reading budget. */
private fun packAnnotationBackground(blocks: List<String>, budget: Int): String = buildString {
    for (block in blocks.filter { it.isNotBlank() }) {
        val separator = if (isEmpty()) "" else "\n\n"
        val room = budget - length - separator.length
        if (room <= 0) break
        var end = minOf(room, block.length)
        if (end < block.length && end > 0 && block[end - 1].isHighSurrogate() && block[end].isLowSurrogate()) end--
        if (end <= 0) break
        append(separator).append(block, 0, end)
    }
}

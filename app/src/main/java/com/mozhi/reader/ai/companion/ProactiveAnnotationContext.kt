package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.agent.ChapterDocument
import com.mozhi.reader.ai.agent.ReadableCorpus
import com.mozhi.reader.ai.agent.loadReadableCorpus
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.ai.knowledge.BookCharactersCodec
import com.mozhi.reader.ai.knowledge.ChapterKnowledgeCodec
import com.mozhi.reader.ai.knowledge.CharacterAttributeKind
import com.mozhi.reader.ai.knowledge.CharacterRole
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.AnnotationThreadCandidate
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.BookEmbeddingSettingsStore
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import com.mozhi.reader.core.library.AnnotationRecall
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.RecallCard
import com.mozhi.reader.core.library.RecallOutline
import com.mozhi.reader.core.library.RecallThread
import com.mozhi.reader.core.library.RecallThreadTurn
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
    private val personaRepository: PersonaRepository,
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
            } }, buildCandidates = false, loadChaptersThrough = { emptyList() })
        val outlines = database.chapterKnowledgeDao().getBefore(bookId, chapterIndex).mapNotNull { entry ->
            currentCoroutineContext().ensureActive()
            validatedAnnotationOutline(entry, scope, revision, corpus.bodies[entry.chapterIndex])
        }
        check(isCurrent(bookId, revision)) { "正文已变化，请重新准备段评上下文" }
        val vectorRecall = if (outlines.isEmpty()) null else resolveVectorRecall(bookId, corpus, chapterIndex)
        PreparedAnnotationContext(revision, outlines, titles,
            !corpus.complete,
            recallCharacterCards(bookId, revision, chapterIndex),
            recallThreads(bookId, chapterIndex), vectorRecall)
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
        return VectorRecall(embedQuery, search, MAX_OUTLINES, OUTLINE_RECALL_DEPTH)
    }

    internal suspend fun isCurrent(bookId: Long, revision: String): Boolean = try {
        library.getBook(bookId)?.removedAt == 0L && library.bookTextRevision(bookId) == revision
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { false }

    /**
     * 按 (bookId, chapterIndex) 缓存章节级召回上下文，revision 校验；随读段评与私聊共用同一份缓存，
     * 两边互相预热。容量 8 是给两条调用方共享留的余量。
     */
    private val contextCache = linkedMapOf<Pair<Long, Int>, PreparedAnnotationContext>()

    internal suspend fun cachedPrepare(bookId: Long, chapterIndex: Int): PreparedAnnotationContext {
        val key = bookId to chapterIndex
        val cached = synchronized(contextCache) { contextCache[key] }
        if (cached != null && isCurrent(bookId, cached.revision)) return cached
        val prepared = prepare(bookId, chapterIndex)
        synchronized(contextCache) {
            contextCache.remove(key)
            contextCache[key] = prepared
            while (contextCache.size > CONTEXT_CACHE_CAPACITY) contextCache.remove(contextCache.keys.first())
        }
        return prepared
    }

    /** 角色卡：按当前章过滤档案的全部字段（事实/关系/性格/经历等），拼成每位人物的档案；没有资料就整块跳过。 */
    private suspend fun recallCharacterCards(bookId: Long, revision: String, currentChapter: Int): List<AnnotationCharacterCard> {
        val guide = BookCharactersCodec.visible(database.bookCharacterDao().getGuide(bookId), revision)?.guide ?: return emptyList()
        return guide.characters.mapNotNull { person -> cardFor(person, currentChapter, guide.progressBounded) }
    }

    /**
     * 前文讨论串：一条有 AI 参与的批注（AI 段评，或用户划线发起的讨论）整体作为一个召回单元，
     * 楼主层在前、回复层按时间升序，每层带发言人身份。只按章锚防剧透，串内各层完整保留——
     * 用户在讨论里可能主动向 AI 剧透，这段对话记忆要完整保留。
     */
    private suspend fun recallThreads(bookId: Long, currentChapter: Int): List<AnnotationThread> {
        if (currentChapter <= 0) return emptyList()
        val candidates = database.annotationDao().getThreadCandidates(bookId, currentChapter)
        if (candidates.isEmpty()) return emptyList()
        val repliesByAnnotation = database.annotationDao()
            .getRepliesForAnnotations(candidates.map { it.id })
            .groupBy { it.annotationId }
        val personaNames = personaRepository.getPersonas().associate { it.id to it.name }
        return candidates.mapNotNull { candidate ->
            val turns = buildList {
                candidate.note.takeIf(String::isNotBlank)?.let { note ->
                    add(ThreadTurn(candidate.personaId, speakerName(candidate.personaId, personaNames), note))
                }
                repliesByAnnotation[candidate.id].orEmpty().forEach { reply ->
                    reply.contentMarkdown.takeIf(String::isNotBlank)?.let { content ->
                        add(ThreadTurn(reply.personaId, speakerName(reply.personaId, personaNames), content))
                    }
                }
            }
            if (turns.isEmpty()) null else AnnotationThread(candidate.id, candidate.chapterIndex, turns)
        }
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

/** 前文的一条讨论串（或单层 AI 段评）：楼主层在前，回复层按时间升序，每层标发言人身份。 */
internal data class AnnotationThread(
    val id: Long,
    val chapterIndex: Int,
    val turns: List<ThreadTurn>
) {
    /** 参与过这条串的 AI 角色（去重，用户不算）；用于「当前角色参与过」的优先级判断。 */
    val participants: Set<Long> get() = turns.mapNotNull { it.personaId }.toSet()

    /** BM25 打分用的整串正文（不含发言人名字，避免角色名拉高词频）。 */
    val bm25Text: String get() = turns.joinToString("\n") { it.content }
}

/** 讨论串的一层：发言人展示名 + 正文；[personaId] 为 null 表示用户，仅用于参与者判断。 */
internal data class ThreadTurn(
    val personaId: Long?,
    val speaker: String,
    val content: String
)

/** 讨论串一层发言人的展示名：用户 / 角色名 / 已删除角色。 */
private fun speakerName(personaId: Long?, personaNames: Map<Long, String>): String =
    if (personaId == null) "用户" else personaNames[personaId] ?: "已删除角色"

/** 章节级召回上下文的缓存条数（随读段评 + 私聊共享）。 */
private const val CONTEXT_CACHE_CAPACITY = 8

/** 每条段评最多注入几位角色卡人物。 */
private const val MAX_CARDS = 6

/** 每位角色卡人物最多保留几条前文经历。 */
private const val CARD_EXPERIENCES = 6

/** 每位角色卡人物最多保留几条关系。 */
private const val CARD_RELATIONS = 4

/** 角色卡内容字段（经历/简介/小传/成长/外貌）的相关性下限：BM25 不到本轮最高分这个比例就不注入。 */
private const val CARD_CONTENT_RELEVANCE_RATIO = 0.35

/** 每条段评最多召回几条前文讨论串（单层段评或多层讨论，合并计数）。 */
private const val MAX_CONCLUSIONS = 8

/** 每条段评最多召回几条大纲，以及大纲向量腿的召回深度（正文切片命中反查章）。 */
private const val MAX_OUTLINES = 25
private const val OUTLINE_RECALL_DEPTH = 48

/** 三类召回资料占 backgroundBudget 的比例（合计 100%，正文片段已移除）。 */
private const val OUTLINE_BUDGET_RATIO = 0.6
private const val CARD_BUDGET_RATIO = 0.3
private const val CONCLUSION_BUDGET_RATIO = 0.1

/** 大纲向量腿的余弦距离闸：比通用片段闸更紧——「切片命中 → 反查章大纲」是有损映射，切片语义近不代表该章梗概必然相关。 */
private const val OUTLINE_VECTOR_MAX_DISTANCE = 0.60

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

/** 查询期召回结果：打包好的前文资料文本 + 结构化明细（供落库/折叠展示）。 */
internal data class NarrowedRecall(
    val background: String,
    val recall: AnnotationRecall
)

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
    private val outlines: List<AnnotationOutline> = emptyList(),
    private val titles: Map<Int, String> = emptyMap(),
    private val partial: Boolean = false,
    private val characterCards: List<AnnotationCharacterCard> = emptyList(),
    private val threads: List<AnnotationThread> = emptyList(),
    private val vectorRecall: VectorRecall? = null
) {
    suspend fun forParagraph(body: String, paragraph: ProactiveAnnotationParagraph, budgetChars: Int, personaId: Long? = null): AnnotationReadingContext =
        withContext(Dispatchers.Default) {
            val budget = budgetChars.coerceIn(ProactiveAnnotationContextSettings.MIN_CHARS, ProactiveAnnotationContextSettings.MAX_CHARS)
            val target = body.substring(paragraph.start, paragraph.end)
            // Target is repeated for reliable quote grounding. Its two copies both consume budget.
            val backgroundBudget = ((budget - target.length * 2).coerceAtLeast(0) * 0.45).toInt()
            val query = ProactiveAnnotationParagraphs.prefix(body, paragraph, target.length + 400)
            val narrowed = narrow(query, personaId, backgroundBudget)
            val prefix = ProactiveAnnotationParagraphs.prefix(
                body, paragraph,
                minOf(budget - target.length - narrowed.background.length, ProactiveAnnotationParagraphs.MAX_PREFIX_CHARS)
            )
            AnnotationReadingContext(prefix, target, narrowed.background, narrowed.recall)
        }

    /** 私聊等无「目标段落」路径的召回入口：整段预算都当召回预算，不扣 target、不乘 0.45。 */
    suspend fun forQuery(query: String, personaId: Long?, budgetChars: Int): NarrowedRecall =
        withContext(Dispatchers.Default) {
            narrow(query, personaId, budgetChars.coerceIn(ProactiveAnnotationContextSettings.MIN_CHARS, ProactiveAnnotationContextSettings.MAX_CHARS))
        }

    /** 大纲/角色卡/讨论串的查询期收窄与打包；与目标段落无关，只吃一个查询文本。 */
    private suspend fun narrow(query: String, personaId: Long?, backgroundBudget: Int): NarrowedRecall {
        val context = currentCoroutineContext()
        fun chapterLabel(index: Int): String = "第 "+ (index + 1) + " 章" +
            titles[index]?.takeIf { it.isNotBlank() }?.let { "「" + it.take(80) + "」" }.orEmpty()
        val outlineCandidates = outlines.map { outline ->
            RetrievalCandidate(0, outline.chapterIndex, 0, outline.text)
        }
        val rankedOutlines = Bm25LexicalRecall.rank(outlineCandidates, query, MAX_OUTLINES) { context.ensureActive() }
        // 向量腿：正文切片向量命中后反查该章大纲；同章多个切片只算一条。
        val outlineByChapter = outlines.associateBy { it.chapterIndex }
        val outlineVectorHits = vectorRecall?.recall(query).orEmpty()
            .mapNotNull { hit -> outlineByChapter[hit.chapterIndex]?.let {
                RetrievalCandidate(0, hit.chapterIndex, 0, it.text, vectorDistance = hit.vectorDistance)
            } }
            .distinctBy { it.chapterIndex }
        val fusedOutlines = RrfFusion.fuse(
            vector = outlineVectorHits,
            lexical = rankedOutlines,
            lexicalWeight = RetrievalQuery.lexicalWeightFor(query)
        )
        val bestOutline = rankedOutlines.firstOrNull()?.lexicalScore ?: 0.0
        // 相关性闸：向量距离达标，或 BM25 ≥ 本轮最高分 35%；召回落空就空着，不兜底上一章。
        val selectedOutlines = RetrievalSelection.select(
            fusedOutlines.filter { candidate ->
                (candidate.lexicalScore != null && candidate.lexicalScore >= bestOutline * 0.35) ||
                    (candidate.vectorDistance != null && candidate.vectorDistance <= OUTLINE_VECTOR_MAX_DISTANCE)
            },
            topK = MAX_OUTLINES, maxPerChapter = 1, literalKeys = emptySet(), literalReserve = 0
        ).selected
        val onStageCards = cardsOnStage(query).mapNotNull { card ->
            narrowedCardLines(card, query) { context.ensureActive() }
                .takeIf { it.isNotEmpty() }?.let { card to it }
        }
        val conclusionCandidates = threads.mapIndexed { index, thread ->
            RetrievalCandidate(0, thread.chapterIndex, index, thread.bm25Text)
        }
        // 全量 rank（不只 topK）：tier 重排后「当前角色参与的串」可能在 BM25 前 8 之外。
        val rankedConclusions = Bm25LexicalRecall.rank(conclusionCandidates, query, conclusionCandidates.size) { context.ensureActive() }
        val bestConclusion = rankedConclusions.firstOrNull()?.lexicalScore ?: 0.0
        val onStageConclusions = rankedConclusions
            .filter { (it.lexicalScore ?: 0.0) >= bestConclusion * 0.35 }
            // 更高优先级 tier：当前角色参与过的串优先；sortedByDescending 稳定，同 tier 内保持 BM25 降序。
            .sortedByDescending { candidate -> personaId != null && threads[candidate.chunkIndex].participants.contains(personaId) }
            .take(MAX_CONCLUSIONS)
            .mapNotNull { threads.getOrNull(it.chunkIndex) }
        val outlineBlocks = selectedOutlines.map {
            chapterLabel(it.chapterIndex) + "梗概（辅助资料，以原文为准）：\n" + it.text
        }
        val cardBlocks = if (onStageCards.isEmpty()) emptyList() else
            listOf("【角色卡】与本文相关人物的档案（辅助资料，以原文为准）：") +
                onStageCards.map { (card, lines) -> card.name + "\n" + lines.joinToString("\n") }
        val conclusionBlocks = if (onStageConclusions.isEmpty()) emptyList() else
            listOf("【前文讨论】你此前参与过的段评与讨论串（辅助资料，发言人以标注为准）：") +
                onStageConclusions.map { threadBlock(it, ::chapterLabel) }
        // 三类各占 backgroundBudget 的固定比例作硬上限；某类没用满的预算按 大纲 → 角色卡 → 讨论串 回流补给。
        val outlineCap = (backgroundBudget * OUTLINE_BUDGET_RATIO).toInt()
        val cardCap = (backgroundBudget * CARD_BUDGET_RATIO).toInt()
        val conclusionCap = (backgroundBudget * CONCLUSION_BUDGET_RATIO).toInt()
        var summaries = packAnnotationBackground(outlineBlocks, outlineCap)
        var cardsText = packAnnotationBackground(cardBlocks, cardCap)
        var conclusionsText = packAnnotationBackground(conclusionBlocks, conclusionCap)
        var leftover = (backgroundBudget - summaries.length - cardsText.length - conclusionsText.length).coerceAtLeast(0)
        summaries = packAnnotationBackground(outlineBlocks, outlineCap + leftover)
        leftover = (backgroundBudget - summaries.length - cardsText.length - conclusionsText.length).coerceAtLeast(0)
        cardsText = packAnnotationBackground(cardBlocks, cardCap + leftover)
        leftover = (backgroundBudget - summaries.length - cardsText.length - conclusionsText.length).coerceAtLeast(0)
        conclusionsText = packAnnotationBackground(conclusionBlocks, conclusionCap + leftover)
        val warning = if (partial) "前文检索只覆盖部分正文；未命中不代表书中没有。" else ""
        val background = packAnnotationBackground(listOf(warning, summaries, cardsText, conclusionsText), backgroundBudget)
        val recall = AnnotationRecall(
            outlines = selectedOutlines.map { RecallOutline(chapterLabel(it.chapterIndex), it.text) },
            characterCards = onStageCards.map { (card, lines) -> RecallCard(card.name, lines.joinToString("\n")) },
            threads = onStageConclusions.map { thread ->
                RecallThread(chapterLabel(thread.chapterIndex), thread.turns.map { turn -> RecallThreadTurn(turn.speaker, turn.content) })
            }
        )
        return NarrowedRecall(background, recall)
    }

    /** 角色卡：目标段点名该人物（名字或别称），或点名它的关系对象时注入，最多 [MAX_CARDS] 位。 */
    private fun cardsOnStage(window: String): List<AnnotationCharacterCard> =
        characterCards.filter { card ->
            (listOf(card.name) + card.aliases + card.relationTargets).any { it.length >= 2 && window.contains(it) }
        }.take(MAX_CARDS)
}

/** 一条前文讨论串注入提示词的文本：章锚 + 每层「发言人：内容」。 */
private fun threadBlock(thread: AnnotationThread, chapterLabel: (Int) -> String): String =
    chapterLabel(thread.chapterIndex) + "：\n" +
        thread.turns.joinToString("\n") { it.speaker + "：" + it.content }

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

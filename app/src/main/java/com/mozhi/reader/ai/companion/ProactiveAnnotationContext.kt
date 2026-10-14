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
import com.mozhi.reader.ai.knowledge.KnowledgeCharacter
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.BookEmbeddingSettingsStore
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import com.mozhi.reader.core.library.LibraryRepository
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
        val knowledge = database.chapterKnowledgeDao().getBefore(bookId, chapterIndex).mapNotNull { entry ->
            currentCoroutineContext().ensureActive()
            validatedAnnotationOutline(entry, scope, revision, corpus.bodies[entry.chapterIndex])
        }
        check(isCurrent(bookId, revision)) { "正文已变化，请重新准备段评上下文" }
        val vectorRecall = resolveVectorRecall(bookId, corpus, chapterIndex)
        PreparedAnnotationContext(revision, corpus.candidates, knowledge.map { it.outline }, titles,
            !corpus.complete, mergeAnnotationCharacters(knowledge),
            recallCharacterCards(bookId, revision, chapterIndex),
            recallUserNotes(bookId, chapterIndex), vectorRecall)
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

    /** 用户笔记：只取前文章节、有想法（非纯高亮）的批注。 */
    private suspend fun recallUserNotes(bookId: Long, currentChapter: Int): List<AnnotationNote> {
        if (currentChapter <= 0) return emptyList()
        return database.annotationDao().getForChapterRangeByUser(bookId, 0, currentChapter - 1)
            .filter { it.note.isNotBlank() }
            .map { AnnotationNote(it.chapterIndex, it.note) }
    }
}

internal data class AnnotationOutline(val chapterIndex: Int, val text: String)

/** 一章知识里能进段评上下文的部分：梗概 + 已核对的人物。 */
internal data class AnnotationChapterKnowledge(
    val outline: AnnotationOutline,
    val characters: List<KnowledgeCharacter>
)

/** Never reuse a whole-chapter outline whose source reaches beyond the allowed boundary. */
internal fun validatedAnnotationOutline(
    entry: ChapterKnowledgeEntity, scope: ReadingScope, revision: String, body: String?
): AnnotationChapterKnowledge? {
    val visible = ChapterKnowledgeCodec.visible(entry, scope, revision) ?: return null
    if (body == null || entry.sourceEnd > body.length ||
        ChapterKnowledgeCodec.hash(body.take(entry.sourceEnd)) != entry.sourceHash) return null
    return AnnotationChapterKnowledge(
        AnnotationOutline(entry.chapterIndex, visible.content.readableOutline),
        visible.content.characters
    )
}

/** 一位前文人物的单条事实，带出处章。 */
internal data class AnnotationCharacterFact(val chapterIndex: Int, val text: String)

/** 前文各章知识合并出的一位人物；[latestChapterIndex] 是它最近一次出场的章。 */
internal data class AnnotationCharacter(val name: String, val facts: List<AnnotationCharacterFact>) {
    val latestChapterIndex: Int get() = facts.maxOfOrNull(AnnotationCharacterFact::chapterIndex) ?: -1
}

/** 角色卡里一位上场人物的精简档案（名字 + 别称 + 已按章节锚点过滤的一句话档案 + 关系对象名）。 */
internal data class AnnotationCharacterCard(
    val name: String,
    val aliases: List<String>,
    val profile: String,
    val relatesTo: List<String> = emptyList()
)

/** 用户的一条前文批注笔记（想法 + 章锚）。 */
internal data class AnnotationNote(val chapterIndex: Int, val text: String)

/** 每位人物跨章合并后最多保留几条事实。 */
private const val MAX_FACTS_PER_CHARACTER = 6

/** 每条段评最多注入几位人物。 */
private const val MAX_CHARACTERS = 4

/** 每条段评最多注入几位角色卡人物。 */
private const val MAX_CARDS = 4

/** 每位角色卡人物最多保留几条前文经历。 */
private const val CARD_EXPERIENCES = 6

/** 每位角色卡人物最多保留几条关系。 */
private const val CARD_RELATIONS = 4

/** 每条段评最多召回几条用户笔记。 */
private const val MAX_NOTES = 4

/** 每条段评正文片段召回的名额与向量/BM25 的召回深度。 */
private const val SNIPPET_TOP_K = 12
private const val SNIPPET_RECALL_DEPTH = 48

/** 向量余弦距离闸：超过该距离的语义片段不算相关（与 RetrievalRequest.maxVectorDistance 一致）。 */
private const val VECTOR_MAX_DISTANCE = 0.80

/** 跨章按名字合并人物：同一人只留一条，事实去重后保留最近 [MAX_FACTS_PER_CHARACTER] 条。 */
internal fun mergeAnnotationCharacters(knowledge: List<AnnotationChapterKnowledge>): List<AnnotationCharacter> {
    val byName = linkedMapOf<String, MutableList<AnnotationCharacterFact>>()
    knowledge.forEach { chapter ->
        chapter.characters.forEach { person ->
            val facts = byName.getOrPut(person.name) { mutableListOf() }
            person.facts.forEach { fact -> facts += AnnotationCharacterFact(chapter.outline.chapterIndex, fact.text) }
        }
    }
    return byName.mapNotNull { (name, facts) ->
        facts.takeIf { it.isNotEmpty() }?.let {
            AnnotationCharacter(name, it.distinctBy { it.text }.takeLast(MAX_FACTS_PER_CHARACTER))
        }
    }
}

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

/** 把一位人物压成多行档案：主次 / 身份 / 性别年龄 / 关系 / 性格 / 经历 / 简介 / 小传 / 成长 / 外貌，全部只取前文或可信来源。 */
internal fun cardProfile(person: BookCharacter, currentChapter: Int, bounded: Boolean): String? {
    if (person.name.isBlank()) return null
    val relations = cardRelations(person, currentChapter)
    val sections = buildList {
        person.effectiveRole()?.let { add("主次：" + roleLabel(it)) }
        person.attributeValues(CharacterAttributeKind.IDENTITY).firstOrNull()?.takeIf(String::isNotBlank)?.let { add("身份：" + it) }
        val genderAge = listOfNotNull(
            person.attributeValues(CharacterAttributeKind.GENDER).firstOrNull()?.takeIf(String::isNotBlank),
            person.attributeValues(CharacterAttributeKind.AGE).firstOrNull()?.takeIf(String::isNotBlank)
        )
        if (genderAge.isNotEmpty()) add("性别年龄：" + genderAge.joinToString("，"))
        if (relations.isNotEmpty()) add("关系：" + relations.joinToString("；") { it.label })
        val traits = traitsBefore(person, currentChapter, bounded)
        if (traits.isNotEmpty()) add("性格：" + traits.joinToString("；"))
        val experiences = experiencesBefore(person, currentChapter).takeLast(CARD_EXPERIENCES)
        if (experiences.isNotEmpty()) add("经历：" + experiences.joinToString("；"))
        summaryField(person.manualAttributes?.tagline, person.dossier?.tagline.orEmpty(), bounded)?.let { add("简介：" + it) }
        summaryField(person.manualAttributes?.bio, person.dossier?.bio.orEmpty(), bounded)?.let { add("小传：" + it) }
        summaryField(person.manualAttributes?.arc, person.dossier?.arc.orEmpty(), bounded)?.let { add("成长：" + it) }
        person.attributeValues(CharacterAttributeKind.APPEARANCE).firstOrNull()?.takeIf(String::isNotBlank)?.let { add("外貌：" + it) }
    }
    return sections.joinToString("\n").ifBlank { null }
}

internal fun cardFor(person: BookCharacter, currentChapter: Int, bounded: Boolean): AnnotationCharacterCard? =
    cardProfile(person, currentChapter, bounded)?.let {
        AnnotationCharacterCard(person.name, person.aliases, it, cardRelations(person, currentChapter).map { r -> r.target })
    }

internal data class AnnotationReadingContext(val prefix: String, val target: String, val background: String) {
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
    private val characters: List<AnnotationCharacter> = emptyList(),
    private val characterCards: List<AnnotationCharacterCard> = emptyList(),
    private val notes: List<AnnotationNote> = emptyList(),
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
            // 上一章不再无条件追加：只在「一条相关的都没召回」时兜底补它，保持顺序阅读的连贯。
            val selectedOutlines = relatedOutlines.ifEmpty { outlineCandidates.takeLast(1) }
            fun chapterLabel(index: Int): String = "第 "+ (index + 1) + " 章" +
                titles[index]?.takeIf { it.isNotBlank() }?.let { "「" + it.take(80) + "」" }.orEmpty()
            val charactersText = packAnnotationBackground(charactersOnStage(query).let { onStage ->
                if (onStage.isEmpty()) emptyList() else
                    listOf("【前文人物】与本文相关的人物（辅助资料，以原文为准）：") +
                        onStage.map { person ->
                            person.name + "：" + person.facts.joinToString("；") { fact ->
                                "${fact.text}（第${fact.chapterIndex + 1}章）"
                            }
                        }
            }, backgroundBudget / 4)
            val cardsText = packAnnotationBackground(cardsOnStage(query).let { onStage ->
                if (onStage.isEmpty()) emptyList() else
                    listOf("【角色卡】与本文相关人物的档案（辅助资料，以原文为准）：") +
                        onStage.map { card -> card.name + "\n" + card.profile }
            }, backgroundBudget / 4)
            val notesText = packAnnotationBackground(notesOnStage(namesOnStage(query)).let { onStage ->
                if (onStage.isEmpty()) emptyList() else
                    listOf("【读书笔记】你此前的批注（辅助资料）：") +
                        onStage.map { note -> chapterLabel(note.chapterIndex) + "笔记：" + note.text }
            }, backgroundBudget / 8)
            val summaries = packAnnotationBackground(selectedOutlines.map {
                chapterLabel(it.chapterIndex) + "梗概（辅助资料，以原文为准）：\n" + it.text
            }, backgroundBudget / 4)
            val snippets = packAnnotationBackground(hits.map {
                chapterLabel(it.chapterIndex) + "原文：\n" + it.text
            }, (backgroundBudget - summaries.length - charactersText.length - cardsText.length - notesText.length - 6).coerceAtLeast(0))
            val warning = if (partial) "前文检索只覆盖部分正文；未命中不代表书中没有。" else ""
            val background = packAnnotationBackground(listOf(warning, summaries, charactersText, cardsText, notesText, snippets), backgroundBudget)
            val prefix = ProactiveAnnotationParagraphs.prefix(body, paragraph, budget - target.length - background.length)
            AnnotationReadingContext(prefix, target, background)
        }

    /** 目标段附近点名的人物优先；没人点名就退回最近一章的人物，保持「上一章刚发生什么」的连贯。 */
    private fun charactersOnStage(window: String): List<AnnotationCharacter> {
        if (characters.isEmpty()) return emptyList()
        val onStage = characters.filter { it.name.length >= 2 && window.contains(it.name) }
        if (onStage.isNotEmpty()) return onStage.take(MAX_CHARACTERS)
        val latest = characters.maxOf { it.latestChapterIndex }
        return characters.filter { it.latestChapterIndex == latest }.take(MAX_CHARACTERS)
    }

    /** 角色卡：目标段点名该人物（名字或别称），或点名它的关系对象时注入，最多 [MAX_CARDS] 位。 */
    private fun cardsOnStage(window: String): List<AnnotationCharacterCard> =
        characterCards.filter { card ->
            (listOf(card.name) + card.aliases + card.relatesTo).any { it.length >= 2 && window.contains(it) }
        }.take(MAX_CARDS)

    /** 前文上下文里出现过的所有人名（角色卡 + 每章人物），用于给读书笔记做名字匹配。 */
    private fun namesOnStage(window: String): List<String> =
        (characterCards.flatMap { listOf(it.name) + it.aliases } + characters.map { it.name })
            .filter { it.length >= 2 && window.contains(it) }.distinct()

    /** 用户笔记：只召回提到当前上场人物的前文批注，取最近几条。 */
    private fun notesOnStage(names: List<String>): List<AnnotationNote> =
        if (names.isEmpty()) emptyList()
        else notes.filter { note -> names.any { note.text.contains(it) } }.takeLast(MAX_NOTES)
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

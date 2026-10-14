package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.agent.ChapterDocument
import com.mozhi.reader.ai.agent.loadReadableCorpus
import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.knowledge.BookCharactersCodec
import com.mozhi.reader.ai.knowledge.ChapterKnowledgeCodec
import com.mozhi.reader.ai.knowledge.CharacterRole
import com.mozhi.reader.ai.knowledge.KnowledgeCharacter
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.retrieval.Bm25LexicalRecall
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.retrieval.RetrievalCandidate
import com.mozhi.reader.core.retrieval.RetrievalSelection
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only prior chapters are cached, for the lifetime of one chapter job. No paid embedding calls. */
@Singleton
class ProactiveAnnotationContextRepository @Inject constructor(
    private val library: LibraryRepository,
    private val database: MoReadDatabase
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
        PreparedAnnotationContext(revision, corpus.candidates, knowledge.map { it.outline }, titles,
            !corpus.complete, mergeAnnotationCharacters(knowledge),
            recallCharacterCards(bookId, revision, chapterIndex),
            recallUserNotes(bookId, chapterIndex))
    }

    internal suspend fun isCurrent(bookId: Long, revision: String): Boolean = try {
        library.getBook(bookId)?.removedAt == 0L && library.bookTextRevision(bookId) == revision
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { false }

    /** 角色卡：按当前章过滤已核对的事实与关系，拼成每位人物的精简档案；没有资料就整块跳过。 */
    private suspend fun recallCharacterCards(bookId: Long, revision: String, currentChapter: Int): List<AnnotationCharacterCard> {
        val guide = BookCharactersCodec.visible(database.bookCharacterDao().getGuide(bookId), revision)?.guide ?: return emptyList()
        return guide.characters.mapNotNull { person ->
            cardProfile(person, currentChapter)?.let { AnnotationCharacterCard(person.name, person.aliases, it) }
        }
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

/** 角色卡里一位上场人物的精简档案（名字 + 别称 + 已按章节锚点过滤的一句话档案）。 */
internal data class AnnotationCharacterCard(val name: String, val aliases: List<String>, val profile: String)

/** 用户的一条前文批注笔记（想法 + 章锚）。 */
internal data class AnnotationNote(val chapterIndex: Int, val text: String)

/** 每位人物跨章合并后最多保留几条事实。 */
private const val MAX_FACTS_PER_CHARACTER = 6

/** 每条段评最多注入几位人物。 */
private const val MAX_CHARACTERS = 4

/** 每条段评最多注入几位角色卡人物。 */
private const val MAX_CARDS = 4

/** 每位角色卡人物最多保留几条前文事实。 */
private const val CARD_FACTS = 4

/** 每位角色卡人物最多保留几条关系。 */
private const val CARD_RELATIONS = 4

/** 每条段评最多召回几条用户笔记。 */
private const val MAX_NOTES = 4

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

/** 把一位人物压成一句话档案：主次 + 前文事实 + 关系，全部只取 [currentChapter] 之前的章节锚点。 */
internal fun cardProfile(person: BookCharacter, currentChapter: Int): String? {
    if (person.name.isBlank()) return null
    val parts = buildList {
        person.effectiveRole()?.let { add(roleLabel(it)) }
        addAll(person.evidence
            .filter { it.chapterIndex < currentChapter }
            .map { it.fact.text }.distinct().takeLast(CARD_FACTS))
        person.relationships
            .filter { it.evidence.chapterIndex < currentChapter }
            .map { it.target + "（" + it.relation + "）" }.distinct().take(CARD_RELATIONS)
            .takeIf { it.isNotEmpty() }?.let { add("关系：" + it.joinToString("、")) }
    }
    val profile = parts.joinToString("；")
    return profile.ifBlank { null }
}

internal data class AnnotationReadingContext(val prefix: String, val target: String, val background: String) {
    val chars: Int get() = prefix.length + target.length + background.length
}

internal class PreparedAnnotationContext(
    val revision: String,
    private val candidates: List<RetrievalCandidate> = emptyList(),
    private val outlines: List<AnnotationOutline> = emptyList(),
    private val titles: Map<Int, String> = emptyMap(),
    private val partial: Boolean = false,
    private val characters: List<AnnotationCharacter> = emptyList(),
    private val characterCards: List<AnnotationCharacterCard> = emptyList(),
    private val notes: List<AnnotationNote> = emptyList()
) {
    suspend fun forParagraph(body: String, paragraph: ProactiveAnnotationParagraph, budgetChars: Int): AnnotationReadingContext =
        withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val budget = budgetChars.coerceIn(ProactiveAnnotationContextSettings.MIN_CHARS, ProactiveAnnotationContextSettings.MAX_CHARS)
            val target = body.substring(paragraph.start, paragraph.end)
            // Target is repeated for reliable quote grounding. Its two copies both consume budget.
            val backgroundBudget = ((budget - target.length * 2).coerceAtLeast(0) * 0.45).toInt()
            val query = ProactiveAnnotationParagraphs.prefix(body, paragraph, target.length + 400)
            val ranked = Bm25LexicalRecall.rank(candidates, query, 48) { context.ensureActive() }
            val best = ranked.firstOrNull()?.lexicalScore ?: 0.0
            val hits = RetrievalSelection.select(
                ranked.filter { (it.lexicalScore ?: 0.0) >= best * 0.35 },
                topK = 12, maxPerChapter = 2, literalKeys = emptySet(), literalReserve = 0
            ).selected
            val outlineCandidates = outlines.map { outline ->
                RetrievalCandidate(0, outline.chapterIndex, 0, outline.text)
            }
            val relatedOutlines = Bm25LexicalRecall.rank(outlineCandidates, query, 4) { context.ensureActive() }
            // Include immediate continuity when available, without replacing relevant distant chapters.
            val selectedOutlines = (relatedOutlines + outlineCandidates.takeLast(1)).distinctBy { it.chapterIndex }
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
                        onStage.map { card -> card.name + "：" + card.profile }
            }, backgroundBudget / 6)
            val notesText = packAnnotationBackground(notesOnStage(namesOnStage(query)).let { onStage ->
                if (onStage.isEmpty()) emptyList() else
                    listOf("【读书笔记】你此前的批注（辅助资料）：") +
                        onStage.map { note -> chapterLabel(note.chapterIndex) + "笔记：" + note.text }
            }, backgroundBudget / 6)
            val summaries = packAnnotationBackground(selectedOutlines.map {
                chapterLabel(it.chapterIndex) + "梗概（辅助资料，以原文为准）：\n" + it.text
            }, backgroundBudget / 3)
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

    /** 角色卡：只在目标段点名（名字或别称）该人物时注入，最多 [MAX_CARDS] 位。 */
    private fun cardsOnStage(window: String): List<AnnotationCharacterCard> =
        characterCards.filter { card ->
            (listOf(card.name) + card.aliases).any { it.length >= 2 && window.contains(it) }
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

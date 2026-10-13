package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.AgentTool
import com.mozhi.reader.ai.agent.MemoryScope
import com.mozhi.reader.ai.agent.ReaderToolset
import com.mozhi.reader.ai.agent.ToolResult
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.client.ResolvedChatClient
import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.retrieval.ReadingScope
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 人物档案的断点缓存：由仓库实现（按书、按正文修订），这里只认键。 */
internal interface DossierCache {
    suspend fun load(key: String): String?
    suspend fun store(key: String, identity: String, json: String)
}

/** 快速人物提取一次运行所需的全部输入；正文已截到 [scope] 以内。 */
internal class CharacterResearchRequest(
    val bookId: Long,
    val bookTitle: String,
    val scope: ReadingScope,
    val progressBounded: Boolean,
    /** (章节序号, 可读正文)，按章节顺序。 */
    val chapters: List<Pair<Int, String>>,
    val chapterTitle: (Int) -> String,
    /** 名单与档案都用它（批量任务走 CHEAP）。 */
    val model: ResolvedChatClient,
    /** 只在联网补充时使用：需要会调工具的对话模型。 */
    val webModel: ResolvedChatClient?,
    val modelKey: String,
    val cache: DossierCache,
    /**
     * 用户点名的人物（`黄晓明（晓明、绿大暗）` 里括号内是别称）。非空时跳过名单请求：
     * 名字就是用户给的，不让模型再挑一遍（既省一次调用，也免得它把点名的人删掉）。
     */
    val targets: List<CharacterTarget>? = null,
    val validate: suspend () -> Unit
)

internal data class ResearchedCharacter(val entry: RosterEntry, val profile: ProfiledCharacter, val mentions: Int)

internal data class CharacterResearchResult(
    val people: List<ResearchedCharacter>,
    val externalNotes: List<ExternalCharacterNote>,
    /** 这一次实际用到的缓存键（含名单），发布后其余缓存可以清掉。 */
    val usedKeys: List<String>,
    val failed: List<String>
)

/**
 * 快速人物提取：确定性的四步，不依赖模型会不会调工具。
 *
 * 1. 本地统计候选人名（[CharacterCandidates]）；
 * 2. 一次请求整理成名单：剔除非人名、合并称呼、定主次（失败回落本地候选）；
 * 3. 每位人物一次请求写档案（[CharacterDossierCodec]），经 [KnowledgeRequestLimiter] 并行，整理完立即落缓存；
 *    单人失败只记下名字，不影响其他人；
 * 4. 打开联网时，最后用对话模型补几条书外资料，失败不影响结果。
 *
 * 请求数与人物数相关而与书的长度无关；进程被杀后再跑，缓存命中的人物不再计费。
 */
class CharacterResearchAgent @Inject internal constructor(
    private val loop: AgentLoop,
    private val requests: KnowledgeJsonRequest,
    private val readerTools: ReaderToolset
) {
    internal suspend fun run(request: CharacterResearchRequest, onProgress: (KnowledgeProgress) -> Unit): CharacterResearchResult {
        val counter = OccurrenceCounter(request.chapters)
        val named = request.targets
        // 点名的名单不发请求、也不整理，所以没有「整理名单」这一步可报，直接进档案阶段。
        val (roster, rosterKey) = if (named != null) namedRoster(request, named) else {
            onProgress(KnowledgeProgress(KnowledgeProgress.Stage.ROSTER))
            val candidates = CharacterCandidates.rank(request.chapters, limit = CANDIDATES)
            roster(request, candidates, counter)
        }
        request.validate()
        require(roster.isNotEmpty()) { "正文里没有找到明显的人物，可以手动添加，或改用完整扫描" }

        val cast = roster.map { it.name }
        val mutex = Mutex()
        val finished = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val working = linkedSetOf<String>()
        var firstFailure: String? = null
        suspend fun report() = mutex.withLock {
            onProgress(KnowledgeProgress(KnowledgeProgress.Stage.PROFILES, finished.size + failed.size, roster.size,
                working.toList(), finished.toList(), failed.toList()))
        }
        report()
        val results = coroutineScope {
            roster.map { entry ->
                async {
                    mutex.withLock { working += entry.name }
                    report()
                    val outcome = try {
                        profile(request, entry, cast, counter)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logFailure("profile failed: ${error.javaClass.simpleName}")
                        mutex.withLock { if (firstFailure == null) firstFailure = error.message?.take(80) }
                        null
                    }
                    mutex.withLock {
                        working -= entry.name
                        if (outcome != null) finished += entry.name else failed += entry.name
                    }
                    report()
                    outcome
                }
            }.awaitAll()
        }
        request.validate()
        val people = results.filterNotNull()
        require(people.isNotEmpty()) {
            val reason = firstFailure?.let { "（$it）" }.orEmpty()
            "没有整理出人物档案$reason，可以稍后继续，或在设置里换一个批量任务模型"
        }
        val notes = if (request.webModel != null) {
            onProgress(KnowledgeProgress(KnowledgeProgress.Stage.WEB, people.size, people.size, finished = finished.toList()))
            externalNotes(request, request.webModel, people)
        } else emptyList()
        return CharacterResearchResult(
            people = canonicalize(people.map { it.first }),
            externalNotes = notes,
            usedKeys = listOfNotNull(rosterKey) + people.map { it.second },
            failed = failed.toList()
        )
    }

    /**
     * 用户点名的名单：一个请求都不发，只在正文里核对名字。
     *
     * 每个人都**单独**检索一次（[CharacterPassages.collect] 有全局 5000 次命中上限，
     * 合并成一次会让高频的名字把后面的挤成假「未找到」）。全都对不上说明名字写错或不在
     * 读到的范围内，就在本地停下——不花任何模型调用。零命中的少数名字仍留在名单里，
     * 走到档案阶段会直接失败，界面把它们列进「没整理成」。
     */
    private fun namedRoster(request: CharacterResearchRequest, targets: List<CharacterTarget>):
        Pair<List<RosterEntry>, String?> {
        // 主次由点名的人自己定不了；统一按配角收进「其他人物」，与逐段扫描的默认一致。
        val entries = targets.map { RosterEntry(it.name, it.aliases, CharacterRole.SUPPORTING) }
        val found = HashSet<String>()
        entries.forEach { entry ->
            if (CharacterPassages.collect(request.chapters, (listOf(entry.name) + entry.aliases).distinct()).isNotEmpty()) {
                found += entry.name
            }
        }
        require(found.isNotEmpty()) {
            val missing = targets.filterNot { it.name in found }.joinToString("、") { it.name }
            "没有在正文中找到$missing，检查书名，或把别称写进括号。"
        }
        return entries to null
    }

    /** 名单：命中缓存直接用；请求失败回落本地候选（回落结果不缓存，下次还会再试一次名单）。 */
    private suspend fun roster(request: CharacterResearchRequest, candidates: List<CharacterCandidates.Candidate>,
        counter: OccurrenceCounter): Pair<List<RosterEntry>, String?> {
        if (candidates.isEmpty()) return emptyList<RosterEntry>() to null
        val clues = CharacterRoster.clues(request.chapters, candidates)
        val user = CharacterRoster.userMessage(clues)
        val key = ChapterKnowledgeCodec.hash("roster|${request.modelKey}|${CharacterRoster.PROMPT_VERSION}|${request.progressBounded}|$user")
        request.cache.load(key)?.let { cached ->
            runCatching { AiJson.decodeFromString(ListSerializer(RosterEntry.serializer()), cached) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { return it to key }
        }
        return try {
            val entries = requests.request(request.model, CharacterRoster.prompt(request.bookTitle, request.progressBounded), user,
                request.validate) { raw -> CharacterRoster.parse(raw, candidates, counter::count) }
            request.cache.store(key, ROSTER_IDENTITY, AiJson.encodeToString(ListSerializer(RosterEntry.serializer()), entries))
            entries to key
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure("roster fell back to local candidates: ${error.javaClass.simpleName}")
            CharacterRoster.fallback(candidates) to null
        }
    }

    private suspend fun profile(request: CharacterResearchRequest, entry: RosterEntry, cast: List<String>,
        counter: OccurrenceCounter): Pair<ResearchedCharacter, String>? {
        val names = (listOf(entry.name) + entry.aliases).distinct()
        val related = cast.filter { it != entry.name && it !in entry.aliases }.take(RELATED_NAMES)
        val passages = CharacterPassages.collect(request.chapters, names, related)
        if (passages.isEmpty()) return null
        val key = ChapterKnowledgeCodec.hash(buildString {
            append("dossier|").append(request.modelKey).append('|').append(CharacterDossierCodec.PROMPT_VERSION)
            append('|').append(request.progressBounded).append('|').append(names.joinToString(",")).append('|').append(related.joinToString(","))
            passages.forEach { append('|').append(it.chapterIndex).append(':').append(it.start).append(':').append(ChapterKnowledgeCodec.hash(it.text)) }
        })
        val mentions = names.sumOf(counter::count)
        request.cache.load(key)?.let { cached ->
            runCatching { AiJson.decodeFromString(ProfiledCharacter.serializer(), cached) }.getOrNull()
                ?.takeIf { anchorsFit(it, request.chapters) }?.let { return ResearchedCharacter(entry, it, mentions) to key }
        }
        val profiled = requests.request(request.model, CharacterDossierCodec.prompt(request.bookTitle, request.progressBounded),
            CharacterDossierCodec.userMessage(entry.name, entry.aliases, related, passages, request.chapterTitle), request.validate) { raw ->
            CharacterDossierCodec.parse(raw, entry.name, entry.aliases, passages)
        }
        request.cache.store(key, entry.name, AiJson.encodeToString(ProfiledCharacter.serializer(), profiled))
        return ResearchedCharacter(entry, profiled, mentions) to key
    }

    /** 缓存读回时再确认一次坐标仍落在当前可读正文之内（防剧透范围缩小后误用）。 */
    private fun anchorsFit(profile: ProfiledCharacter, chapters: List<Pair<Int, String>>): Boolean {
        val texts = chapters.toMap()
        fun fits(chapter: Int, start: Int, end: Int) = texts[chapter]?.let { start >= 0 && end in (start + 1)..it.length } == true
        return profile.dossier.anchors().all { fits(it.chapterIndex, it.start, it.end) } &&
            (profile.evidence.map { it.chapterIndex to it.fact } + profile.attributes.map { it.evidence.chapterIndex to it.evidence.fact })
                .all { (chapter, fact) -> fits(chapter, fact.start, fact.end) && texts.getValue(chapter).substring(fact.start, fact.end) == fact.quote }
    }

    /** 关系对象写的是别名时换成名单上的正式称呼，关系图才连得上；自己指向自己的关系丢掉。 */
    private fun canonicalize(people: List<ResearchedCharacter>): List<ResearchedCharacter> {
        val canonical = HashMap<String, String>()
        people.forEach { person ->
            (person.profile.aliases + person.entry.aliases).forEach { canonical.putIfAbsent(it, person.profile.name) }
        }
        people.forEach { canonical[it.profile.name] = it.profile.name }
        return people.distinctBy { it.profile.name }.map { person ->
            val own = person.profile.name
            val relations = person.profile.dossier.relationships.map { it.copy(target = canonical[it.target] ?: it.target) }
                .filter { it.target != own }.distinctBy { it.target }
            person.copy(profile = person.profile.copy(dossier = person.profile.dossier.copy(relationships = relations)))
        }
    }

    /** 联网补充：只用 web 工具与 note_external，最多 [WEB_ROUNDS] 轮，任何失败都只记日志。 */
    private suspend fun externalNotes(request: CharacterResearchRequest, model: ResolvedChatClient,
        people: List<Pair<ResearchedCharacter, String>>): List<ExternalCharacterNote> {
        val notes = mutableListOf<ExternalCharacterNote>()
        val mutex = Mutex()
        val webTools = setOf("web_search", "web_scrape")
        val tools = buildList {
            add(tool("note_external", "记录一条联网查到的书外资料（附来源网址）。它会单独标为书外资料，不会写进人物档案。",
                buildJsonObject {
                    putJsonObject("text") { put("type", "string"); put("description", "200 字以内") }
                    putJsonObject("character") { put("type", "string") }
                    putJsonObject("url") { put("type", "string") }
                    putJsonObject("title") { put("type", "string") }
                }, required = listOf("text")) { arguments ->
                val text = arguments["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return@tool ToolResult.Failure("INVALID_ARGUMENT", "缺少 text")
                mutex.withLock {
                    if (notes.size < MAX_EXTERNAL_NOTES) notes += ExternalCharacterNote(
                        text.take(200),
                        arguments["character"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(40),
                        arguments["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(500),
                        arguments["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(80)
                    )
                }
                ToolResult.Success("已记录。")
            })
            addAll(readerTools.forBook(request.bookId, enabledTools = webTools, readingScope = request.scope,
                memoryScope = MemoryScope(longTermEnabled = false), buildMissingIndex = false).filter { it.spec.name in webTools })
        }
        if (tools.none { it.spec.name in webTools }) return emptyList()
        val system = buildString {
            append("你为《").append(request.bookTitle).append("》的人物档案补充少量书外资料。")
            if (request.progressBounded) {
                append("读者还没读完：只能查书名、作者、通行译名与人物称呼的写法，严禁查询、记录或透露读者尚未读到的任何剧情。")
            } else {
                append("可以查人物背景与通行译名。")
            }
            append("每条资料用 note_external 记录并附来源网址，最多 ").append(MAX_EXTERNAL_NOTES).append(" 条；查不到就直接结束，不要编造。")
        }
        val names = people.joinToString("、") { it.first.profile.name }
        try {
            loop.runDetachedWith(
                history = listOf(ChatMessage(ChatRole.SYSTEM, system), ChatMessage(ChatRole.USER, "主要人物：$names")),
                tools = tools,
                maxRounds = WEB_ROUNDS
            ) {
                AgentLoop.Streamer { messages, specs ->
                    flow {
                        request.validate()
                        emitAll(model.client.chatStream(messages, specs, model.options))
                    }
                }
            }.collect { }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure("web notes stopped: ${error.javaClass.simpleName}")
        }
        return mutex.withLock { notes.toList() }
    }

    private fun tool(name: String, description: String, properties: JsonObject, required: List<String> = emptyList(),
        run: suspend (JsonObject) -> ToolResult): AgentTool = object : AgentTool {
        override val displayName = name
        override val spec = ToolSpec(name, description, buildJsonObject {
            put("type", "object")
            put("properties", properties)
            putJsonArray("required") { required.forEach { add(it) } }
        })
        override suspend fun execute(arguments: JsonObject): ToolResult = run(arguments)
    }

    /** 称呼在可读正文里出现了几次；同一称呼只数一遍。 */
    internal class OccurrenceCounter(private val chapters: List<Pair<Int, String>>) {
        private val cache = HashMap<String, Int>()
        fun count(name: String): Int = synchronized(cache) {
            cache.getOrPut(name) {
                if (name.isEmpty()) 0 else chapters.sumOf { (_, text) ->
                    var total = 0
                    var from = 0
                    while (total < MAX_COUNT) {
                        val at = text.indexOf(name, from)
                        if (at < 0) break
                        total++
                        from = at + name.length
                    }
                    total
                }.coerceAtMost(MAX_COUNT)
            }
        }
        private companion object { const val MAX_COUNT = 100_000 }
    }

    internal companion object {
        const val CANDIDATES = 48
        const val MAX_EXTERNAL_NOTES = 20
        const val WEB_ROUNDS = 3
        /** 交给每位人物的「其他主要人物」名单长度，用于识别关系与挑同场段落。 */
        const val RELATED_NAMES = 12
        /** 费用提示：名单最多两次（含修正），每人最多两次。 */
        val MAX_REQUESTS: Int get() = 2 + CharacterRoster.MAX_PEOPLE * 2
        private const val ROSTER_IDENTITY = com.mozhi.reader.core.database.entity.BookCharacterDossierEntity.ROSTER_IDENTITY
        private const val TAG = "CharacterResearch"

        /** 只记失败类型，不含正文与模型输出。JVM 单测里 android.util.Log 不可用，静默跳过。 */
        fun logFailure(message: String) {
            runCatching { android.util.Log.w(TAG, message) }
        }
    }
}

/**
 * 为一个人物从可读正文里挑段落：覆盖首次出场、中段与最近的出现，合并重叠窗口，总量有上限。
 * 出场很多的人物（多半是主角）给更多段落；另外专门挑几处与其他主要人物同场的段落，
 * 人物关系几乎只在这种段落里交代——均匀抽样很容易全落在独角戏上。
 */
internal object CharacterPassages {
    private const val BEFORE = 300
    private const val AFTER = 420
    private const val MAX_PASSAGES = 8
    private const val MAX_CHARS = 9_000
    private const val MAJOR_HITS = 120
    private const val MAJOR_PASSAGES = 14
    private const val MAJOR_CHARS = 15_000

    fun collect(chapters: List<Pair<Int, String>>, names: List<String>, related: List<String> = emptyList()): List<SourcePassage> {
        val hits = ArrayList<Pair<Int, Int>>()
        chapters.forEachIndexed { position, (_, text) ->
            names.forEach { name ->
                var from = 0
                while (hits.size < 5_000) {
                    val at = text.indexOf(name, from)
                    if (at < 0) break
                    hits += position to at
                    from = at + name.length
                }
            }
        }
        if (hits.isEmpty()) return emptyList()
        hits.sortWith(compareBy({ it.first }, { it.second }))
        val major = hits.size >= MAJOR_HITS
        val maxPassages = if (major) MAJOR_PASSAGES else MAX_PASSAGES
        val maxChars = if (major) MAJOR_CHARS else MAX_CHARS
        val others = related.map(String::trim)
            .filter { other -> other.isNotEmpty() && names.none { it.contains(other) || other.contains(it) } }.distinct()
        val shared = ArrayList<Pair<Int, Int>>()
        if (others.isNotEmpty()) {
            val covered = HashSet<String>()
            val step = (hits.size / 600).coerceAtLeast(1)
            for (k in hits.indices step step) {
                if (shared.size >= maxPassages * 2 / 5) break
                val (position, at) = hits[k]
                val text = chapters[position].second
                val window = text.substring((at - BEFORE).coerceAtLeast(0), (at + AFTER).coerceAtMost(text.length))
                val partner = others.firstOrNull { it !in covered && window.contains(it) } ?: continue
                covered += partner
                shared += hits[k]
            }
        }
        val spread = maxPassages - shared.size
        val even = if (hits.size <= spread) hits else
            (0 until spread).map { hits[(it.toLong() * (hits.size - 1) / (spread - 1).coerceAtLeast(1)).toInt()] }
        val picks = (even + shared).distinct()
        val windows = picks.map { (position, at) ->
            val text = chapters[position].second
            val start = sentenceStart(text, (at - BEFORE).coerceAtLeast(0))
            val end = sentenceEnd(text, (at + AFTER).coerceAtMost(text.length))
            Triple(position, start, end)
        }.groupBy { it.first }.flatMap { (_, rows) ->
            rows.sortedBy { it.second }.fold(mutableListOf<Triple<Int, Int, Int>>()) { merged, row ->
                val last = merged.lastOrNull()
                if (last != null && row.second <= last.third) merged[merged.lastIndex] = last.copy(third = maxOf(last.third, row.third))
                else merged += row
                merged
            }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        var budget = maxChars
        return windows.mapNotNull { (position, start, end) ->
            if (budget <= 0) return@mapNotNull null
            val clippedEnd = minOf(end, start + budget)
            budget -= clippedEnd - start
            val (chapterIndex, text) = chapters[position]
            SourcePassage(chapterIndex, start, text.substring(start, clippedEnd))
        }
    }

    private fun sentenceStart(text: String, from: Int): Int {
        if (from == 0) return 0
        val boundary = (from downTo maxOf(0, from - 80)).firstOrNull { it > 0 && text[it - 1] in "\n。！？!?" }
        return boundary ?: from
    }

    private fun sentenceEnd(text: String, to: Int): Int {
        if (to >= text.length) return text.length
        val boundary = (to until minOf(text.length, to + 80)).firstOrNull { text[it] in "\n。！？!?" }
        return boundary?.plus(1) ?: to
    }
}

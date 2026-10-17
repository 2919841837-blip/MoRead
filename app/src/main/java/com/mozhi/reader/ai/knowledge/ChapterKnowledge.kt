package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.text.QuoteMatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class KnowledgeFact(val text: String, val quote: String, val start: Int, val end: Int)

@Serializable
data class KnowledgeCharacter(val name: String, val facts: List<KnowledgeFact>,
    val attributes: List<KnowledgeCharacterAttribute> = emptyList(),
    val relationships: List<KnowledgeCharacterRelationship> = emptyList())

@Serializable
enum class CharacterAttributeKind { ALIAS, AGE, GENDER, IDENTITY, APPEARANCE, PERSONALITY }
@Serializable
data class KnowledgeCharacterAttribute(val kind: CharacterAttributeKind, val value: String, val fact: KnowledgeFact)
@Serializable
data class KnowledgeCharacterRelationship(val target: String, val relation: String, val fact: KnowledgeFact)

@Serializable
data class ChapterKnowledge(
    val summary: List<KnowledgeFact>,
    val characters: List<KnowledgeCharacter> = emptyList(),
    val outline: String = "",
    /** 用户手改过大纲：重新生成前需要二次确认。 */
    val manualOutline: Boolean = false
) {
    val readableOutline: String get() = outline.ifBlank { summary.joinToString("\n\n") { it.text } }
}

data class VisibleChapterKnowledge(val entry: ChapterKnowledgeEntity, val content: ChapterKnowledge)

internal data class KnowledgePart(val start: Int, val text: String)

/** 从某章 [start] 处截出的一段已读正文，供整理单个人物的档案使用。 */
internal data class SourcePassage(val chapterIndex: Int, val start: Int, val text: String)

internal object ChapterKnowledgeCodec {
    const val PROMPT_VERSION = 2
    const val MAX_SOURCE_CHARS = 60_000
    const val PART_CHARS = 10_000

    @Serializable private data class DraftFact(val text: String, val quote: String)
    @Serializable private data class DraftAttribute(val kind: CharacterAttributeKind, val value: String, val quote: String)
    @Serializable private data class DraftRelationship(val target: String, val relation: String, val quote: String)
    @Serializable private data class DraftCharacter(val name: String, val facts: List<DraftFact>,
        val attributes: List<DraftAttribute> = emptyList(), val relationships: List<DraftRelationship> = emptyList())
    @Serializable private data class Draft(val outline: String, val summary: List<DraftFact>, val characters: List<DraftCharacter> = emptyList())

    fun parts(source: String, enforceChapterLimit: Boolean = true): List<KnowledgePart> {
        require(source.isNotBlank() && (!enforceChapterLimit || source.length <= MAX_SOURCE_CHARS)) { "每章最多整理 60000 字已读内容" }
        val parts = mutableListOf<KnowledgePart>()
        var start = 0
        while (start < source.length) {
            var end = minOf(start + PART_CHARS, source.length)
            if (end < source.length && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate()) end--
            if (end < source.length) {
                val boundary = (end - 1 downTo start + PART_CHARS / 2).firstOrNull { source[it] in "\n。！？.!?" }
                if (boundary != null) end = boundary + 1
            }
            parts += KnowledgePart(start, source.substring(start, end))
            start = end
        }
        return parts
    }

    /** All offsets are derived locally; fabricated and out-of-range quotes fail closed; repeated quotes use the first occurrence. */
    fun parse(raw: String, part: KnowledgePart, maxOutlineChars: Int = 2400): ChapterKnowledge {
        require(raw.length <= 64_000) { "整理结果过长" }
        val clean = KnowledgeResponseJson.clean(raw)
        val draft = try { AiJson.decodeFromString<Draft>(clean) }
            catch (_: Exception) { throw IllegalArgumentException("整理结果格式不完整，请重试") }
        require(draft.summary.size in 1..8 && draft.characters.size <= 16) { "整理结果条目过多或缺少摘要" }
        return ChapterKnowledge(draft.summary.map { verify(it, part) }, verifyCharacters(draft.characters, part), validateOutline(draft.outline, maxOutlineChars))
    }

    fun parseCharacters(raw: String, part: KnowledgePart): List<KnowledgeCharacter> {
        require(raw.length <= 64_000) { "人物结果过长" }
        val clean = KnowledgeResponseJson.characters(raw)
        val draft = try { decodeCharacterDrafts(clean) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("人物结果格式不完整，请重试或提高模型输出上限") }
        val people = verifyCharactersLeniently(draft, part)
        // 截断后救回的结果若一个人物都没剩下，多半是还没写到就断了：要求重写，不能当成「本段没有人物」缓存。
        require(people.isNotEmpty() || !KnowledgeResponseJson.isSalvaged(clean)) { "人物结果被截断，请精简后重新提交完整的 JSON" }
        return people
    }

    /**
     * 按条目宽松解码：缺字段、未知的属性种类、大小写不一的枚举只丢那一条。整体不是 JSON 对象
     * （例如被截断）仍然报格式错误——截断的 JSON 从不修补。
     */
    private fun decodeCharacterDrafts(clean: String): List<DraftCharacter> {
        val root = AiJson.parseToJsonElement(clean) as? JsonObject ?: throw IllegalArgumentException("not an object")
        val rows = root["characters"] as? JsonArray ?: throw IllegalArgumentException("missing characters")
        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return rows.mapNotNull { row ->
            val person = row as? JsonObject ?: return@mapNotNull null
            val name = person.string("name") ?: return@mapNotNull null
            DraftCharacter(
                name = name,
                facts = person.objects("facts").mapNotNull { fact ->
                    DraftFact(fact.string("text") ?: return@mapNotNull null, fact.string("quote") ?: return@mapNotNull null)
                },
                attributes = person.objects("attributes").mapNotNull { attribute ->
                    val kind = attribute.string("kind")?.trim()?.uppercase()
                        ?.let { key -> CharacterAttributeKind.entries.firstOrNull { it.name == key } } ?: return@mapNotNull null
                    DraftAttribute(kind, attribute.string("value") ?: return@mapNotNull null, attribute.string("quote") ?: return@mapNotNull null)
                },
                relationships = person.objects("relationships").mapNotNull { relation ->
                    DraftRelationship(relation.string("target") ?: return@mapNotNull null,
                        relation.string("relation") ?: return@mapNotNull null, relation.string("quote") ?: return@mapNotNull null)
                }
            )
        }
    }

    fun validateOutline(value: String, limit: Int = 2400): String {
        val clean = value.trim()
        require(clean.length in 1..limit) { "请按要求生成长度适中的章节梗概" }
        require(!Regex("(?m)^\\s*(?:[-*•]|[0-9]+[.)、])\\s+").containsMatchIn(clean)) { "请写成连贯自然段，不要罗列分散要点" }
        return clean
    }

    /** 用户手改大纲：沿用模型输出的同一套格式约束，并打上手动标记。 */
    fun editOutline(content: ChapterKnowledge, value: String): ChapterKnowledge {
        require(value.isNotBlank()) { "大纲不能为空" }
        return content.copy(outline = validateOutline(value), manualOutline = true)
    }

    private fun verify(fact: DraftFact, part: KnowledgePart): KnowledgeFact = locateQuote(fact, part)

    /**
     * 摘要与人物资料共用的引文核对：容忍空白与标点写法的走样，记录的永远是原文本身；同一句话出现多次时
     * 取第一处（文字相同，跳转到哪一处都不误导）。编造的原文照样找不到。
     */
    private fun locateQuote(fact: DraftFact, part: KnowledgePart): KnowledgeFact {
        val text = fact.text.trim()
        val quote = fact.quote.trim()
        require(text.length in 1..600 && quote.length in 4..300) { "整理结果缺少简短描述或原文依据" }
        val span = QuoteMatcher.find(part.text, quote) ?: throw IllegalArgumentException("部分引文无法在原文中核对，请重新整理")
        val source = part.text.substring(span.start, span.end)
        require(source.length in 4..300) { "整理结果缺少简短描述或原文依据" }
        return KnowledgeFact(text, source, part.start + span.start, part.start + span.end)
    }

    /** 引文本身含人名，或人名就在引文前不远处（「林舟……他拜了师」这类常见写法）。 */
    private fun mentions(part: KnowledgePart, fact: KnowledgeFact, name: String): Boolean {
        if (fact.quote.contains(name)) return true
        val local = fact.start - part.start
        return part.text.substring((local - NAME_LOOKBEHIND).coerceAtLeast(0), local).contains(name)
    }

    /**
     * 逐条核对、逐条取舍：一条引文对不上只丢那一条，不让整份人物资料作废。模型越弱，单份结果里
     * 出错的条目越多，而条目最多的恰恰是主角——全有或全无的核对会让主角最容易整份落空。
     * 全部条目都对不上时才报错（报第一条原因，供模型修正一次）。
     */
    private fun verifyCharactersLeniently(characters: List<DraftCharacter>, part: KnowledgePart): List<KnowledgeCharacter> {
        val problems = mutableListOf<String>()
        fun <T> keep(block: () -> T): T? = try { block() } catch (error: IllegalArgumentException) {
            problems += error.message ?: "人物资料格式无效"; null
        }
        val people = characters.take(MAX_CHARACTERS).mapNotNull { character ->
            val name = character.name.trim()
            keep {
                require(name.length in 1..60) { "人物资料格式无效" }
                require(name !in PRONOUNS) { "请使用人名或稳定称呼" }
                require(part.text.contains(name)) { "人物名称未出现在提供的原文中" }
            } ?: return@mapNotNull null
            val facts = character.facts.mapNotNull { keep { locateQuote(it, part) } }.distinctBy { it.start to it.text }.take(MAX_FACTS)
            val attributes = character.attributes.mapNotNull { attribute -> keep {
                val value = attribute.value.trim()
                require(value.length in 1..80) { "人物属性格式无效" }
                val fact = locateQuote(DraftFact(value, attribute.quote), part)
                require(mentions(part, fact, name)) { "属性引文需包含人物称呼" }
                if (attribute.kind == CharacterAttributeKind.ALIAS) {
                    require(value != name && fact.quote.contains(value) && fact.quote.contains(name)) { "别名需在同一段原文中明确关联" }
                }
                KnowledgeCharacterAttribute(attribute.kind, value, fact)
            } }.distinctBy { it.kind to it.value }.take(MAX_ITEMS)
            val relationships = character.relationships.mapNotNull { relationship -> keep {
                val target = relationship.target.trim()
                val relation = relationship.relation.trim()
                require(target.length in 1..60 && relation.length in 1..80 && target != name) { "人物关系格式无效" }
                val fact = locateQuote(DraftFact("$name → $target：$relation", relationship.quote), part)
                require(fact.quote.contains(target) && mentions(part, fact, name)) { "关系引文需包含双方姓名或稳定称呼" }
                KnowledgeCharacterRelationship(target, relation, fact)
            } }.distinctBy { it.target to it.relation }.take(MAX_ITEMS)
            if (facts.isEmpty() && attributes.isEmpty() && relationships.isEmpty()) null
            else KnowledgeCharacter(name, facts, attributes, relationships)
        }
        if (people.isEmpty() && problems.isNotEmpty()) throw IllegalArgumentException(problems.first())
        return people
    }

    private val PRONOUNS = setOf("他", "她", "我", "你", "他们", "她们", "旁白")
    private const val MAX_CHARACTERS = 24
    const val MAX_FACTS = 6
    private const val MAX_ITEMS = 12
    private const val NAME_LOOKBEHIND = 120

    private fun verifyCharacters(characters: List<DraftCharacter>, part: KnowledgePart): List<KnowledgeCharacter> {
        require(characters.size <= 24) { "单段人物过多，请保留有依据的主要人物" }
        return characters.map { character ->
            val name = character.name.trim()
            require(name.length in 1..60 && character.facts.size in 1..4) { "人物资料格式无效" }
            require(name !in setOf("他", "她", "我", "你", "他们", "她们", "旁白")) { "请使用人名或稳定称呼" }
            require(part.text.contains(name)) { "人物名称未出现在提供的原文中" }
            require(character.attributes.size <= 12 && character.relationships.size <= 12) { "单个人物属性或关系过多" }
            val attributes = character.attributes.map { attribute ->
                val value = attribute.value.trim()
                require(value.length in 1..80) { "人物属性格式无效" }
                require(attribute.quote.contains(name)) { "属性引文需包含人物称呼" }
                if (attribute.kind == CharacterAttributeKind.ALIAS) require(attribute.quote.contains(value) && value != name) { "别名需在同一段原文中明确关联" }
                KnowledgeCharacterAttribute(attribute.kind, value, verify(DraftFact(value, attribute.quote), part))
            }.distinctBy { it.kind to it.value }
            val relationships = character.relationships.map { relationship ->
                val target = relationship.target.trim()
                val relation = relationship.relation.trim()
                require(target.length in 1..60 && relation.length in 1..80 && target != name) { "人物关系格式无效" }
                require(relationship.quote.contains(name) && relationship.quote.contains(target)) { "关系引文需包含双方姓名或稳定称呼" }
                KnowledgeCharacterRelationship(target, relation, verify(DraftFact("$name → $target：$relation", relationship.quote), part))
            }.distinctBy { it.target to it.relation }
            KnowledgeCharacter(name, character.facts.map { verify(it, part) }, attributes, relationships)
        }
    }

    fun merge(parts: List<ChapterKnowledge>): ChapterKnowledge = ChapterKnowledge(
        parts.flatMap { it.summary }.distinct(),
        parts.flatMap { it.characters }.groupBy { it.name }.map { (name, rows) ->
            KnowledgeCharacter(name, rows.flatMap { it.facts }.distinct(), rows.flatMap { it.attributes }.distinct(), rows.flatMap { it.relationships }.distinct())
        }, parts.joinToString("\n\n") { it.readableOutline }
    )

    fun encode(value: ChapterKnowledge): String = AiJson.encodeToString(value)

    fun visible(entry: ChapterKnowledgeEntity, scope: ReadingScope, revision: String): VisibleChapterKnowledge? {
        if (entry.sourceRevision != revision || entry.promptVersion !in 1..PROMPT_VERSION || entry.sourceEnd <= 0 ||
            !scope.allowsChunk(entry.chapterIndex, 0, entry.sourceEnd)) return null
        val content = runCatching { AiJson.decodeFromString<ChapterKnowledge>(entry.contentJson) }.getOrNull() ?: return null
        val facts = content.summary + content.characters.flatMap { it.facts }
        if (facts.isEmpty() || facts.any { it.start < 0 || it.end <= it.start || it.end > entry.sourceEnd || it.end - it.start != it.quote.length }) return null
        return VisibleChapterKnowledge(entry, content)
    }

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

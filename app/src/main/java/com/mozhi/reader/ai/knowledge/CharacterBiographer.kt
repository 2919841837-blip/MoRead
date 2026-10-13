package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ResolvedChatClient
import com.mozhi.reader.ai.knowledge.CharacterJsonSalvage.objects
import com.mozhi.reader.ai.knowledge.CharacterJsonSalvage.strings
import com.mozhi.reader.ai.knowledge.CharacterJsonSalvage.text
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/**
 * 完整扫描的收尾：为资料最多的几位人物写一句话简介、小传与性格，让两种模式产出同一种档案。
 *
 * 只把已经逐条核对过的事实文字交给模型，不再发送原文，所以很便宜；也因此不给出处——
 * 经历时间线仍用核对过的原文依据。写不出来只是少一份小传，从不阻断发布。
 */
class CharacterBiographer @Inject constructor(private val requests: KnowledgeJsonRequest) {

    @Serializable
    internal data class Biography(val tagline: String = "", val bio: String = "", val arc: String = "",
        val personality: List<DossierTrait> = emptyList(), val role: CharacterRole? = null)

    /** 有 [MIN_FACTS] 条以上事实的人物才值得写小传；最多 [MAX_PEOPLE] 位。 */
    internal fun candidates(people: List<BookCharacter>): List<BookCharacter> = people
        .filter { it.manualDescription == null && it.evidence.size >= MIN_FACTS }
        .sortedByDescending { it.evidence.size + it.attributes.size }
        .take(MAX_PEOPLE)

    internal suspend fun enrich(
        guide: BookCharacterGuide, bookTitle: String, progressBounded: Boolean, model: ResolvedChatClient, modelKey: String,
        cache: DossierCache, validate: suspend () -> Unit, onProgress: (done: Int, total: Int) -> Unit
    ): Pair<BookCharacterGuide, List<String>> {
        val targets = candidates(guide.characters)
        if (targets.isEmpty()) return guide to emptyList()
        var done = 0
        onProgress(0, targets.size)
        val results = coroutineScope {
            targets.map { person ->
                async {
                    val user = userMessage(person, guide.characters.map { it.name })
                    val key = ChapterKnowledgeCodec.hash("bio|$modelKey|$PROMPT_VERSION|$progressBounded|$user")
                    val biography = try {
                        cache.load(key)?.let { runCatching { AiJson.decodeFromString(Biography.serializer(), it) }.getOrNull() }
                            ?: requests.request(model, prompt(bookTitle, progressBounded), user, validate, ::parse).also {
                                cache.store(key, person.identity, AiJson.encodeToString(Biography.serializer(), it))
                            }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        CharacterResearchAgent.logFailure("biography failed: ${error.javaClass.simpleName}")
                        null
                    }
                    synchronized(this@CharacterBiographer) { done++; onProgress(done, targets.size) }
                    biography?.let { person.identity to (it to key) }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        val enriched = guide.copy(characters = guide.characters.map { person ->
            val (biography, _) = results[person.identity] ?: return@map person
            person.copy(role = biography.role ?: person.role, dossier = CharacterDossier(
                tagline = biography.tagline, bio = biography.bio, arc = biography.arc, personality = biography.personality,
                identity = person.attributes.firstOrNull { it.kind == CharacterAttributeKind.IDENTITY }?.value.orEmpty(),
                gender = person.attributes.firstOrNull { it.kind == CharacterAttributeKind.GENDER }?.value.orEmpty(),
                age = person.attributes.firstOrNull { it.kind == CharacterAttributeKind.AGE }?.value.orEmpty()))
        })
        return enriched to results.values.map { it.second }
    }

    internal fun prompt(bookTitle: String, progressBounded: Boolean): String = """
        你是读书助手，根据已经整理好的人物事实，为《$bookTitle》里的一位人物写简短的档案。${if (progressBounded) "这些事实只来自读者已经读过的部分，不要提及之后的情节。" else ""}
        只依据给出的事实，不使用书外知识，不补充事实里没有的情节。
        只输出一个 JSON 对象，不要解释：
        {"tagline":"一句话介绍，30字以内","bio":"人物小传，100–200字，一段自然段，按先后讲清来历与经历",
         "personality":[{"trait":"四字以内的性格词","detail":"一句话说明"}],"arc":"成长与变化，一两句；看不出就留空",
         "role":"protagonist / major / supporting 之一，按他在事实里的分量判断"}
        personality 2–4 条。写不出的字段留空字符串或空数组。
    """.trimIndent()

    internal fun userMessage(person: BookCharacter, cast: List<String>): String = buildString {
        append("人物：").append(person.name)
        if (person.aliases.isNotEmpty()) append("（也称作：").append(person.aliases.joinToString("、")).append('）')
        val others = cast.filter { it != person.name }.take(16)
        if (others.isNotEmpty()) append("\n书中其他人物：").append(others.joinToString("、"))
        val attributes = person.attributes.filter { it.kind != CharacterAttributeKind.ALIAS }
        if (attributes.isNotEmpty()) {
            append("\n\n已知属性：\n")
            attributes.forEach { append("- ").append(it.kind.label()).append("：").append(it.value).append('\n') }
        }
        if (person.relationships.isNotEmpty()) {
            append("\n人物关系（他相对对方）：\n")
            person.relationships.forEach { append("- ").append(it.target).append("：").append(it.relation).append('\n') }
        }
        append("\n按章节先后的事实：\n")
        person.evidence.sortedWith(compareBy({ it.chapterIndex }, { it.fact.start })).forEach {
            append("- 第").append(it.chapterIndex + 1).append("章：").append(it.fact.text).append('\n')
        }
    }

    internal fun parse(raw: String): Biography {
        val root = CharacterJsonSalvage.parseObject(raw, "personality") ?: throw IllegalArgumentException("没有找到 JSON 对象")
        val traits = root.objects("personality", "性格").mapNotNull { item ->
            item.text("trait", "name", "性格")?.take(12)?.let { DossierTrait(it, item.text("detail", "说明")?.take(100).orEmpty()) }
        }.ifEmpty { root.strings("personality", "性格").map { DossierTrait(it.take(12)) } }.distinctBy { it.trait }.take(5)
        val role = root.text("role", "主次")?.lowercase()?.let { value ->
            when {
                "protagonist" in value || "主角" in value -> CharacterRole.PROTAGONIST
                "major" in value || "主要" in value -> CharacterRole.MAJOR
                "supporting" in value || "配角" in value -> CharacterRole.SUPPORTING
                else -> null
            }
        }
        val biography = Biography(root.text("tagline", "简介")?.take(40).orEmpty(), root.text("bio", "小传")?.take(500).orEmpty(),
            root.text("arc", "成长")?.take(240).orEmpty(), traits, role)
        require(biography.tagline.isNotEmpty() || biography.bio.isNotEmpty()) { "没有写出简介或小传" }
        return biography
    }

    internal companion object {
        const val PROMPT_VERSION = 1
        const val MIN_FACTS = 3
        const val MAX_PEOPLE = 12
    }
}

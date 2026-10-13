package com.mozhi.reader.ai.knowledge

/**
 * 人物编辑器里那份可编辑文本与 [BookCharacter] 之间的转换（纯函数，带单测）。
 *
 * 规则与 [BookCharacter.attributeValues] 配套：编辑器每个字段回填「用户接管值优先，其次 AI 值」，
 * 保存时**与 AI 原值相同就交回 AI**（null），不同（含清空成空串）才算用户接管。
 * 于是「打开编辑器但没动这个字段」不会意外交还所有权，「恢复 AI 内容」把文本填回模型原值后
 * 保存时自然也变回 null。
 *
 * 唯一的例外是主次：枚举没有「空」态，所有权由档位直接表达（null = 跟随 AI），不与 AI 原值比较 ——
 * 所以选一个恰好与 AI 当前值相同的档位表示「就按这个，别再改」。
 */
internal data class CharacterAttributeDraft(
    val identity: String = "", val gender: String = "", val age: String = "", val appearance: String = "",
    val tagline: String = "", val bio: String = "", val aliases: String = "", val personality: String = "",
    val role: CharacterRole? = null)

internal object CharacterAttributeDraftCodec {
    const val MAX_IDENTITY = 60
    const val MAX_SHORT = 20
    const val MAX_APPEARANCE = 300
    const val MAX_TAGLINE = 40
    const val MAX_BIO = 600
    const val MAX_ALIASES = 8
    const val MAX_ALIAS = 20
    const val MAX_TRAITS = 5
    const val MAX_TRAIT = 12
    const val MAX_DETAIL = 100

    /** 编辑器里显示的值：用户接管优先，其次 AI。 */
    fun baseline(person: BookCharacter?): CharacterAttributeDraft = CharacterAttributeDraft(
        identity = person?.attributeValues(CharacterAttributeKind.IDENTITY)?.firstOrNull().orEmpty(),
        gender = person?.attributeValues(CharacterAttributeKind.GENDER)?.firstOrNull().orEmpty(),
        age = person?.attributeValues(CharacterAttributeKind.AGE)?.firstOrNull().orEmpty(),
        appearance = person?.attributeValues(CharacterAttributeKind.APPEARANCE)?.joinToString("；").orEmpty(),
        tagline = person?.tagline().orEmpty(),
        bio = person?.bio().orEmpty(),
        aliases = formatAliases(person?.attributeValues(CharacterAttributeKind.ALIAS).orEmpty()),
        personality = formatPersonality(person?.manualTraits() ?: person?.dossier?.personality.orEmpty()),
        role = person?.manualAttributes?.role)

    /** AI 独占时的取值：编辑器的「恢复 AI 内容」填回它，[merge] 也拿它判断有没有真的改过。 */
    fun aiBaseline(person: BookCharacter?): CharacterAttributeDraft = baseline(person?.copy(manualAttributes = null))

    /** 草稿 → 手写层：与 AI 原值相同的文本字段交回 AI；一项都没接管时返回 null。 */
    fun merge(person: BookCharacter?, draft: CharacterAttributeDraft): ManualCharacterAttributes? {
        val ai = aiBaseline(person)
        val layer = ManualCharacterAttributes(
            identity = ownedText(draft.identity, ai.identity, MAX_IDENTITY),
            gender = ownedText(draft.gender, ai.gender, MAX_SHORT),
            age = ownedText(draft.age, ai.age, MAX_SHORT),
            appearance = ownedText(draft.appearance, ai.appearance, MAX_APPEARANCE),
            tagline = ownedText(draft.tagline, ai.tagline, MAX_TAGLINE),
            bio = ownedText(draft.bio, ai.bio, MAX_BIO),
            aliases = ownedList(parseAliases(draft.aliases), parseAliases(ai.aliases)),
            personality = ownedList(parsePersonality(draft.personality), parsePersonality(ai.personality)),
            role = draft.role)
        return layer.takeUnless { it.isEmpty }
    }

    /** 别名用「、」分隔，逗号、分号与换行都认；去重、限量。 */
    fun parseAliases(input: String): List<String> = input.split('、', '，', ',', ';', '；', '\n')
        .map { it.trim() }.filter(String::isNotEmpty).map { it.take(MAX_ALIAS) }.distinct().take(MAX_ALIASES)

    fun formatAliases(values: List<String>): String = values.joinToString("、")

    /** 性格每行一条：「性格词」或「性格词（一句话说明）」，全角半角括号都认。 */
    fun parsePersonality(input: String): List<ManualTrait> = input.lineSequence()
        .map { it.trim() }.filter(String::isNotEmpty).map(::splitTrait).filter { it.first.isNotBlank() }
        .map { ManualTrait(it.first.take(MAX_TRAIT), it.second.take(MAX_DETAIL)) }
        .distinctBy { it.trait }.take(MAX_TRAITS).toList()

    fun formatPersonality(values: List<DossierTrait>): String = values.joinToString("\n") {
        it.trait + if (it.detail.isBlank()) "" else "（${it.detail}）"
    }

    private fun splitTrait(line: String): Pair<String, String> {
        val open = line.indexOfFirst { it == '（' || it == '(' }
        if (open < 0) return line to ""
        val close = line.lastIndexOfAny(charArrayOf('）', ')'))
        val detail = if (close > open) line.substring(open + 1, close) else line.substring(open + 1)
        return line.substring(0, open).trim() to detail.trim()
    }

    /** 与 AI 原值相同 → 交回 AI；不同（含清空成空串）→ 用户接管，超长截断。 */
    private fun ownedText(value: String, ai: String, limit: Int): String? {
        val clean = value.trim()
        return if (clean == ai.trim()) null else clean.take(limit)
    }

    private fun <T> ownedList(values: List<T>, ai: List<T>): List<T>? = values.takeIf { it != ai }
}

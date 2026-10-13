package com.mozhi.reader.ai.knowledge

/**
 * 人物编辑器里那份可编辑文本与 [BookCharacter] 之间的转换（纯函数，带单测）。
 *
 * 规则与 [BookCharacter.attributeValues] 配套：编辑器每个字段回填「用户接管值优先，其次 AI 值」，
 * 保存时**与 AI 原值相同就交回 AI**（null），不同（含清空成空串）才算用户接管。
 * 于是「打开编辑器但没动这个字段」不会意外交还所有权，「恢复 AI 内容」把文本填回模型原值后
 * 保存时自然也变回 null。
 *
 * 这里不追求「解析无损」：真正要保的是合并中性——没接管时草稿与 AI 原值来自同一个函数
 * （经历走 [BookCharacter.experiences]、成长走 [BookCharacter.arc]、关系走 [baselineRelations]），
 * 两边逐字相同，规范化由谁做都不影响判定。
 *
 * 唯一的例外是主次：枚举没有「空」态，所有权由档位直接表达（null = 跟随 AI），不与 AI 原值比较 ——
 * 所以选一个恰好与 AI 当前值相同的档位表示「就按这个，别再改」。
 */
internal data class CharacterAttributeDraft(
    val identity: String = "", val gender: String = "", val age: String = "", val appearance: String = "",
    val tagline: String = "", val bio: String = "", val aliases: String = "", val personality: String = "",
    val role: CharacterRole? = null,
    val experiences: String = "", val arc: String = "", val relations: String = "")

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
    const val MAX_EVENTS = 24
    const val MAX_EVENT = 160
    const val MAX_ARC = 240
    const val MAX_RELATIONS = 16
    const val MAX_REL_NAME = 20
    const val MAX_RELATION = 16

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
        role = person?.manualAttributes?.role,
        experiences = formatExperiences(person?.experiences().orEmpty()),
        arc = person?.arc().orEmpty(),
        relations = formatRelations(baselineRelations(person)))

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
            role = draft.role,
            experiences = ownedList(parseExperiences(draft.experiences), parseExperiences(ai.experiences)),
            arc = ownedText(draft.arc, ai.arc, MAX_ARC),
            relationships = ownedList(parseRelations(draft.relations), parseRelations(ai.relations)))
        return layer.takeUnless { it.isEmpty }
    }

    /**
     * 关系初值：手写或档案在前，旧资料提到的人补在后面。旧资料写的是「本人是对方的什么人」，
     * 方向与档案相反，但界面上的连线标签本来就取同一个词面，所以这里按原词回填，不翻转词义。
     *
     * 已经接管时不再补旧资料 —— 用户删掉的那条不该被它复活。
     */
    private fun baselineRelations(person: BookCharacter?): List<DossierRelation> {
        person ?: return emptyList()
        val written = person.manualRelations()
        val known = written ?: person.dossier?.relationships.orEmpty()
        if (written != null) return known
        return known + person.relationships.filter { legacy -> known.none { it.target == legacy.target } }
            .map { DossierRelation(it.target, it.relation) }
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

    /** 经历每行一条；行首可写「第 3 章」标出章节，与正文之间用 ·、冒号或空格都行。 */
    fun parseExperiences(input: String): List<ManualEvent> = input.lineSequence()
        .map { it.trim() }.filter(String::isNotEmpty).mapNotNull { line ->
            val head = CHAPTER_HEADLINE.find(line)
            val number = head?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }
            val text = (head?.groupValues?.get(2) ?: line).trim().take(MAX_EVENT)
            text.takeIf(String::isNotEmpty)?.let { ManualEvent(it, number?.minus(1)) }
        }
        .distinctBy { it.chapterIndex to it.text }.take(MAX_EVENTS).toList()

    fun formatExperiences(events: List<DossierEvent>): String = events.joinToString("\n") { event ->
        event.chapterIndex?.let { "第${it + 1}章 · " }.orEmpty() + event.text
    }

    /** 社会关系每行一条：「对象（关系）」或「对象（关系）：说明」；括号与说明都可省，全角半角都认。 */
    fun parseRelations(input: String): List<ManualRelation> = input.lineSequence()
        .map { it.trim() }.filter(String::isNotEmpty).map(::splitRelation)
        .filter { it.target.isNotEmpty() }.distinctBy { it.target }.take(MAX_RELATIONS).toList()

    fun formatRelations(values: List<DossierRelation>): String = values.joinToString("\n") { relation ->
        relation.target + relation.relation.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty() +
            relation.detail.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()
    }

    private fun splitRelation(line: String): ManualRelation {
        val open = line.indexOfFirst { it == '（' || it == '(' }
        val close = line.lastIndexOfAny(charArrayOf('）', ')'))
        // 只写了开括号（「小满（同伴」）也照 personality 的宽容规则办：括号里的字全算关系，别把括号吞进对象名。
        if (open > 0 && close <= open) {
            val rest = line.substring(open + 1)
            val cut = rest.indexOfFirst { it == '：' || it == ':' }
            return ManualRelation(line.substring(0, open).trim().take(MAX_REL_NAME),
                (if (cut >= 0) rest.substring(0, cut) else rest).trim().take(MAX_RELATION),
                (if (cut >= 0) rest.substring(cut + 1) else "").trim().take(MAX_DETAIL))
        }
        val bracketed = open > 0 && close > open
        // 括号之后（或整行没写括号时）用「：」分隔对象与说明。
        val head = if (bracketed) line.substring(close + 1) else line
        val colon = head.indexOfFirst { it == '：' || it == ':' }
        val target = (if (bracketed) line.substring(0, open) else if (colon >= 0) head.substring(0, colon) else head).trim()
        val relation = if (bracketed) line.substring(open + 1, close).trim() else ""
        val detail = if (colon >= 0) head.substring(colon + 1).trim() else ""
        return ManualRelation(target.take(MAX_REL_NAME), relation.take(MAX_RELATION), detail.take(MAX_DETAIL))
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

    private val CHAPTER_HEADLINE = Regex("""^\s*第\s*(\d+)\s*章\s*[·•・、,，:：|\-—]?\s*(.*)$""")
}

package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import kotlinx.serialization.Serializable

@Serializable
data class CharacterEvidence(val chapterIndex: Int, val fact: KnowledgeFact)
@Serializable
data class BookCharacterAttribute(val kind: CharacterAttributeKind, val value: String, val evidence: CharacterEvidence)
@Serializable
data class BookCharacterRelationship(val target: String, val relation: String, val evidence: CharacterEvidence)

/** 用户手写的性格：词 + 可选说明。手写值不是书里那句话，所以没有原文锚点。 */
@Serializable
data class ManualTrait(val trait: String, val detail: String = "")

/** 用户手写的一条关系；方向与档案一致：[relation] 是「对方是本人物的什么人」。 */
@Serializable
data class ManualRelation(val target: String, val relation: String = "", val detail: String = "")

/**
 * 用户亲手写的属性。null 的字段仍由 AI 维护；非 null（含空串 / 空列表）表示用户接管 —— 接管后
 * 不再回落到 AI 值，否则用户清空的意图会被下一次重扫悄悄填回。
 *
 * 手写值**不进** [BookCharacter.attributes] 列表：那里的每一条都带逐字核对过的原文依据，
 * 插图形象与「跳到原文」都靠它，所以手写值改由读取时覆盖（见 [BookCharacter.attributeValues]）。
 */
@Serializable
data class ManualCharacterAttributes(
    val identity: String? = null, val gender: String? = null, val age: String? = null,
    val appearance: String? = null, val tagline: String? = null, val bio: String? = null,
    val aliases: List<String>? = null, val personality: List<ManualTrait>? = null,
    /** 主次：null = 仍由 AI 判定。枚举没有「空」态，退回 AI 由编辑器的「跟随 AI」档位表达。 */
    val role: CharacterRole? = null,
    /** 手写的成长变化 / 社会关系；与上面几项同规则，空列表表示用户要求一条都不显示。 */
    val arc: String? = null,
    val relationships: List<ManualRelation>? = null) {
    val isEmpty: Boolean get() = identity == null && gender == null && age == null && appearance == null &&
        tagline == null && bio == null && aliases == null && personality == null && role == null &&
        arc == null && relationships == null
}

@Serializable
data class BookCharacter(val name: String, val evidence: List<CharacterEvidence>,
    /**
     * 上一版的「人物资料」：编辑器里那个自由文本框写下的字。仅作为旧存档的**所有权标记**保留，
     * 避免只写过这段文字的人物在重扫或版本门控时被整条丢掉；内容本身不读取、不展示、不导出。
     */
    val manualDescription: String? = null, val sourceName: String? = null,
    val attributes: List<BookCharacterAttribute> = emptyList(),
    val relationships: List<BookCharacterRelationship> = emptyList(),
    /** 结构化人物档案；完整扫描的旧存档与手动人物没有。 */
    val dossier: CharacterDossier? = null,
    val role: CharacterRole? = null,
    /** 称呼在提取范围内出现的次数，用于排序与关系图节点大小。 */
    val mentions: Int = 0,
    /** 用户手写的属性（身份/性别/年龄/外貌/别名/性格/简介/小传/主次/成长变化/关系）；重扫不会覆盖。 */
    val manualAttributes: ManualCharacterAttributes? = null,
    /** 用户经手过这一条（新建、改名或保存过编辑）：[BookCharactersCodec.edit] 置真，重扫合并时整条保留。 */
    val userEdited: Boolean = false) {
    val identity: String get() = sourceName ?: name
    val aliases: List<String> get() = attributeValues(CharacterAttributeKind.ALIAS)

    /** 这一条由用户经手过：重扫合并整条保留，插图选角也算数。旧存档没有 [userEdited]，靠手写层与人物资料认人。 */
    val userOwned: Boolean get() = userEdited || manualDescription != null || manualAttributes != null

    /** 某项属性的当前取值：用户接管优先，其次档案，最后旧资料的属性条目。 */
    fun attributeValues(kind: CharacterAttributeKind): List<String> = ownedValues(kind)
        ?: dossierValues(kind)?.filter(String::isNotBlank)?.takeIf { it.isNotEmpty() }
        ?: attributes.filter { it.kind == kind }.map { it.value }.distinct()

    /** 用户接管时的取值；没接管返回 null（空列表表示用户要求留空）。 */
    private fun ownedValues(kind: CharacterAttributeKind): List<String>? = manualAttributes?.let { manual ->
        when (kind) {
            CharacterAttributeKind.ALIAS -> manual.aliases
            CharacterAttributeKind.IDENTITY -> manual.identity?.let { listOf(it) }
            CharacterAttributeKind.GENDER -> manual.gender?.let { listOf(it) }
            CharacterAttributeKind.AGE -> manual.age?.let { listOf(it) }
            CharacterAttributeKind.APPEARANCE -> manual.appearance?.let { listOf(it) }
            CharacterAttributeKind.PERSONALITY -> manual.personality?.map { it.trait }
        }
    }?.filter(String::isNotBlank)

    /** 档案里的同名取值；别名与性格在档案里没有对应字段（性格的说明走 [manualTraits]）。 */
    private fun dossierValues(kind: CharacterAttributeKind): List<String>? = dossier?.let { full ->
        when (kind) {
            CharacterAttributeKind.IDENTITY -> listOf(full.identity)
            CharacterAttributeKind.GENDER -> listOf(full.gender)
            CharacterAttributeKind.AGE -> listOf(full.age)
            CharacterAttributeKind.APPEARANCE -> listOf(full.appearance)
            CharacterAttributeKind.PERSONALITY -> full.personality.map { it.trait }
            CharacterAttributeKind.ALIAS -> null
        }
    }

    /** 性格（含说明）：未接管时返回 null，调用方沿用现有的档案 / 旧资料分支。 */
    fun manualTraits(): List<DossierTrait>? = manualAttributes?.personality?.map { DossierTrait(it.trait, it.detail) }

    /** 手写的社会关系：未接管时返回 null。方向与档案一致（[DossierRelation.relation] 是「对方是他的什么人」）。 */
    fun manualRelations(): List<DossierRelation>? = manualAttributes?.relationships?.map { DossierRelation(it.target, it.relation, it.detail) }

    /** 成长变化：手写优先，其次档案；都没有就是空串（那一栏整块不显示）。 */
    fun arc(): String = manualAttributes?.arc ?: dossier?.arc.orEmpty()

    fun tagline(): String = manualAttributes?.tagline ?: dossier?.tagline.orEmpty()
    fun bio(): String = manualAttributes?.bio ?: dossier?.bio.orEmpty()

    /** 外貌被接管时没有可跳的原文：手写值不是书里那句话。 */
    fun appearanceAnchor(): SourceAnchor? = if (manualAttributes?.appearance != null) null else dossier?.appearanceAnchor

    /** 主次：用户指定优先，其次模型判定。分组、色环、徽标与关系图权重都读它。 */
    fun effectiveRole(): CharacterRole? = manualAttributes?.role ?: role
}
@Serializable
data class BookCharacterGuide(
    val characters: List<BookCharacter>,
    val scannedChapters: Int,
    val sourceCharacters: Long,
    /** true = 只扫到阅读进度为止，资料不含未读情节；旧存档解码成 false（全书）。 */
    val progressBounded: Boolean = false,
    /** 联网查到的书外资料：单独展示，不作为原文依据，也不混进人物事实。 */
    val externalNotes: List<ExternalCharacterNote> = emptyList(),
    /** QUICK = 主 agent 检索主要人物；FULL = 逐段扫描；TARGETED = 用户点名的若干人物。旧存档都是逐段扫描。 */
    val mode: String = MODE_FULL
) {
    companion object {
        const val MODE_FULL = "FULL"
        const val MODE_QUICK = "QUICK"
        /** 按用户输入的名单扫描；[BookCharactersCodec.visible] 不读 mode，新旧存档都解得开。 */
        const val MODE_TARGETED = "TARGETED"
    }
}

@Serializable
data class ExternalCharacterNote(val text: String, val character: String = "", val url: String = "", val title: String = "")
data class VisibleBookCharacters(val entry: BookCharacterGuideEntity, val guide: BookCharacterGuide)
data class BookCharactersSnapshot(val saved: VisibleBookCharacters? = null, val outdated: Boolean = false, val checkpointParts: Int = 0,
    /** 快速模式上次没跑完、已整理好的人物数；再点提取会直接复用它们。 */
    val pendingProfiles: Int = 0)

internal object BookCharactersCodec {
    const val PROMPT_VERSION = 2
    fun edit(guide: BookCharacterGuide, originalIdentity: String?, name: String,
        manualAttributes: ManualCharacterAttributes?): BookCharacterGuide {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80) { "请填写 1–80 字的人物姓名" }
        // 一项都没接管（全是 null）时不存这层：contentJson 保持干净，读取规则也不必区分「空层」。
        val layer = manualAttributes?.takeUnless { it.isEmpty }
        val original = originalIdentity?.let { key -> guide.characters.firstOrNull { it.identity == key } ?: error("人物已变化，请重新打开编辑") }
        require(guide.characters.none { it !== original && (it.name.equals(cleanName, true) || it.identity.equals(cleanName, true)) }) { "已有同名人物，请直接编辑该人物" }
        // userEdited 是「这条由用户经手」的持久标记：新建的人（可能只填了名字）与只改了名的人，重扫时才不会被整条丢掉。
        // manualDescription 不再读取，但 copy 会原样保留它，旧存档的所有权标记因此不会丢。
        val person = original?.copy(name = cleanName, sourceName = original.identity,
            manualAttributes = layer, userEdited = true)
            ?: BookCharacter(cleanName, emptyList(), sourceName = cleanName, manualAttributes = layer, userEdited = true)
        return guide.copy(characters = if (original == null) guide.characters + person else guide.characters.map { if (it === original) person else it })
    }

    fun mergeManual(generated: BookCharacterGuide, previous: BookCharacterGuide?): BookCharacterGuide {
        // 手动条目 = 用户经手过这一条：只改过属性的人也不能被 AI 结果整条替掉。
        val edits = previous?.characters.orEmpty().filter { it.userOwned }
        val result = generated.characters.mapNotNull { current ->
            edits.firstOrNull { it.identity == current.identity }?.copy(evidence = current.evidence,
                attributes = current.attributes, relationships = current.relationships,
                dossier = current.dossier, role = current.role, mentions = current.mentions)
                ?: current.takeUnless { candidate -> edits.any { it.name.equals(candidate.name, true) } }
        }.toMutableList()
        edits.filter { edit -> result.none { it.identity == edit.identity } }.forEach { edit ->
            // A manual rename owns the displayed name; avoid duplicate lazy-list keys/names.
            result.removeAll { it.name.equals(edit.name, true) }
            result += edit.copy(evidence = emptyList(), attributes = emptyList(), relationships = emptyList(), dossier = null)
        }
        return generated.copy(characters = result.distinctBy { it.name })
    }

    /**
     * 点名扫描的结果**并入**已发布资料：按 identity 取并集，不删除任何已有人物。
     *
     * 与 [mergeManual] 相反——后者只保留手动条目，会把上一次 AI 扫描出来的人整批丢掉，
     * 所以两条发布路径互斥，不能先后调用。手动改名仍拥有显示名，新证据扩充到该条目上。
     */
    fun mergeTargeted(generated: BookCharacterGuide, previous: BookCharacterGuide?): BookCharacterGuide {
        previous ?: return generated
        val result = previous.characters.toMutableList()
        generated.characters.forEach { fresh ->
            val index = result.indexOfFirst { it.identity == fresh.identity || it.name.equals(fresh.name, true) }
            if (index < 0) {
                result += fresh
            } else {
                val old = result[index]
                // copy 保留 old 的 name、手写层与 userEdited：手动改名拥有显示名，手写值重扫不丢。
                result[index] = old.copy(
                    evidence = unionEvidence(old.evidence, fresh.evidence),
                    attributes = (old.attributes + fresh.attributes).distinctBy { it.kind to it.value }.takeLast(48),
                    relationships = (old.relationships + fresh.relationships).distinctBy { it.target to it.relation }.takeLast(96),
                    // 本次新写的档案与主次要盖过旧的（旧条目可能只是逐段扫描留下的 evidence）；
                    // 但手动条目从来没有档案，不能被 null 抹掉。
                    dossier = fresh.dossier ?: old.dossier,
                    role = fresh.role ?: old.role,
                    mentions = maxOf(old.mentions, fresh.mentions),
                    sourceName = old.sourceName ?: fresh.sourceName
                )
            }
        }
        return previous.copy(
            characters = result.distinctBy { it.identity },
            scannedChapters = maxOf(previous.scannedChapters, generated.scannedChapters),
            sourceCharacters = maxOf(previous.sourceCharacters, generated.sourceCharacters),
            // 只有两次都没越过阅读进度，合并结果才仍然无剧透。
            progressBounded = previous.progressBounded && generated.progressBounded,
            externalNotes = (previous.externalNotes + generated.externalNotes).distinctBy { it.text to it.url },
            mode = previous.mode
        )
    }

    /**
     * 把人名钉到用户输入的大名上：模型被要求用「原文中最常用的称呼」，可能只认出别称。
     * 命中大名或任一别称时把 [BookCharacter.sourceName] 设为该大名，使 identity 成为用户点名的那个名字，
     * 后续并入与手动编辑才能对上同一个人。
     */
    fun anchor(guide: BookCharacterGuide, targets: List<CharacterTarget>): BookCharacterGuide {
        if (targets.isEmpty()) return guide
        val characters = guide.characters.map { person ->
            val hit = targets.firstOrNull { target ->
                person.name.equals(target.name, true) || target.aliases.any { it.equals(person.name, true) }
            }
            if (hit != null && person.sourceName == null) person.copy(sourceName = hit.name) else person
        }
        return guide.copy(characters = characters)
    }

    /** 与 [Accumulator] 相同的保留策略：介绍留前 4 条，最近的留 12 条。 */
    private fun unionEvidence(first: List<CharacterEvidence>, second: List<CharacterEvidence>): List<CharacterEvidence> {
        val merged = (first + second).distinctBy { it.fact.text }
            .sortedWith(compareBy({ it.chapterIndex }, { it.fact.start }))
        return if (merged.size <= 16) merged else merged.take(4) + merged.takeLast(12)
    }

    fun fromChapters(
        chapters: List<Pair<Int, List<KnowledgeCharacter>>>,
        scannedChapters: Int,
        sourceCharacters: Long,
        progressBounded: Boolean = false
    ): BookCharacterGuide {
        val accumulator = Accumulator()
        chapters.forEach { (index, people) -> accumulator.add(index, people) }
        return accumulator.guide(scannedChapters, sourceCharacters, progressBounded)
    }

    /** Process one source part at a time, retaining a bounded introduction and recent facts per person. */
    class Accumulator {
        private val people = linkedMapOf<String, BookCharacter>()
        fun add(chapterIndex: Int, characters: List<KnowledgeCharacter>) {
            characters.forEach { person ->
                val previous = people[person.name]
                val distinct = (previous?.evidence.orEmpty() + person.facts.map { CharacterEvidence(chapterIndex, it) })
                    .distinctBy { it.fact.text }.sortedWith(compareBy({ it.chapterIndex }, { it.fact.start }))
                people[person.name] = BookCharacter(person.name,
                    if (distinct.size <= 16) distinct else distinct.take(4) + distinct.takeLast(12),
                    attributes = (previous?.attributes.orEmpty() + person.attributes.map {
                        BookCharacterAttribute(it.kind, it.value, CharacterEvidence(chapterIndex, it.fact))
                    }).distinctBy { it.kind to it.value }.takeLast(48),
                    relationships = (previous?.relationships.orEmpty() + person.relationships.map {
                        BookCharacterRelationship(it.target, it.relation, CharacterEvidence(chapterIndex, it.fact))
                    }).distinctBy { it.target to it.relation }.takeLast(96))
            }
        }
        fun guide(scannedChapters: Int, sourceCharacters: Long, progressBounded: Boolean = false): BookCharacterGuide {
            // Merge only an explicit, uniquely owned alias. Shared titles never collapse two people.
            val owners = people.values.flatMap { p -> p.aliases.map { it to p.name } }.groupBy({ it.first }, { it.second })
            val canonical = people.keys.associateWith { name -> owners[name]?.distinct()?.singleOrNull()
                ?.takeIf { owner -> owner != name && owners[owner].isNullOrEmpty() } ?: name }
            val merged = people.values.groupBy { canonical[it.name] ?: it.name }.map { (name, rows) ->
                BookCharacter(name, rows.flatMap { it.evidence }.distinctBy { it.fact.text }.let { if (it.size <= 16) it else it.take(4) + it.takeLast(12) },
                    attributes = rows.flatMap { it.attributes }.distinctBy { it.kind to it.value },
                    relationships = rows.flatMap { it.relationships }.map { it.copy(target = canonical[it.target] ?: it.target) }
                        .filter { it.target != name }.distinctBy { it.target to it.relation })
            }
            return BookCharacterGuide(merged, scannedChapters, sourceCharacters, progressBounded)
        }
    }

    fun visible(entry: BookCharacterGuideEntity?, revision: String): VisibleBookCharacters? {
        entry ?: return null
        val guide = runCatching { AiJson.decodeFromString<BookCharacterGuide>(entry.contentJson) }.getOrNull() ?: return null
        if (entry.sourceRevision != revision || entry.promptVersion !in 1..PROMPT_VERSION) {
            val manual = guide.characters.filter { it.userOwned && it.name.isNotBlank() }
                .map { it.copy(evidence = emptyList(), attributes = emptyList(), relationships = emptyList(), dossier = null) }
            return manual.takeIf { it.isNotEmpty() }?.let { VisibleBookCharacters(entry, BookCharacterGuide(it, 0, 0, true)) }
        }
        if (guide.characters.any { it.name.isBlank() || (it.evidence + it.attributes.map { a -> a.evidence } + it.relationships.map { r -> r.evidence }).any { e -> e.chapterIndex < 0 || e.fact.start < 0 ||
                e.fact.end <= e.fact.start || e.fact.end - e.fact.start != e.fact.quote.length } ||
                it.dossier?.anchors().orEmpty().any { a -> a.chapterIndex < 0 || a.start < 0 || a.end <= a.start } }) return null
        return VisibleBookCharacters(entry, guide)
    }
}

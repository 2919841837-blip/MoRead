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
@Serializable
data class BookCharacter(val name: String, val evidence: List<CharacterEvidence>,
    /** User prose is distinct from verified AI source facts and survives later extractions. */
    val manualDescription: String? = null, val sourceName: String? = null,
    val attributes: List<BookCharacterAttribute> = emptyList(),
    val relationships: List<BookCharacterRelationship> = emptyList()) {
    val identity: String get() = sourceName ?: name
    val aliases: List<String> get() = attributes.filter { it.kind == CharacterAttributeKind.ALIAS }.map { it.value }.distinct()
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
data class BookCharactersSnapshot(val saved: VisibleBookCharacters? = null, val outdated: Boolean = false, val checkpointParts: Int = 0)

internal object BookCharactersCodec {
    const val PROMPT_VERSION = 2
    fun edit(guide: BookCharacterGuide, originalIdentity: String?, name: String, description: String): BookCharacterGuide {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80) { "请填写 1–80 字的人物姓名" }
        require(description.length <= 24_000) { "人物资料不能超过 24000 字" }
        val original = originalIdentity?.let { key -> guide.characters.firstOrNull { it.identity == key } ?: error("人物已变化，请重新打开编辑") }
        require(guide.characters.none { it !== original && (it.name.equals(cleanName, true) || it.identity.equals(cleanName, true)) }) { "已有同名人物，请直接编辑该人物" }
        val person = BookCharacter(cleanName, original?.evidence.orEmpty(), description.trim(), original?.identity ?: cleanName,
            original?.attributes.orEmpty(), original?.relationships.orEmpty())
        return guide.copy(characters = if (original == null) guide.characters + person else guide.characters.map { if (it === original) person else it })
    }

    fun mergeManual(generated: BookCharacterGuide, previous: BookCharacterGuide?): BookCharacterGuide {
        val edits = previous?.characters.orEmpty().filter { it.manualDescription != null }
        val result = generated.characters.mapNotNull { current ->
            edits.firstOrNull { it.identity == current.identity }?.copy(evidence = current.evidence,
                attributes = current.attributes, relationships = current.relationships)
                ?: current.takeUnless { candidate -> edits.any { it.name.equals(candidate.name, true) } }
        }.toMutableList()
        edits.filter { edit -> result.none { it.identity == edit.identity } }.forEach { edit ->
            // A manual rename owns the displayed name; avoid duplicate lazy-list keys/names.
            result.removeAll { it.name.equals(edit.name, true) }
            result += edit.copy(evidence = emptyList(), attributes = emptyList(), relationships = emptyList())
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
                // copy 保留 old 的 name 与 manualDescription：手动改名拥有显示名。
                result[index] = old.copy(
                    evidence = unionEvidence(old.evidence, fresh.evidence),
                    attributes = (old.attributes + fresh.attributes).distinctBy { it.kind to it.value }.takeLast(48),
                    relationships = (old.relationships + fresh.relationships).distinctBy { it.target to it.relation }.takeLast(96),
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
            val manual = guide.characters.filter { it.manualDescription != null && it.name.isNotBlank() }.map { it.copy(evidence = emptyList(), attributes = emptyList(), relationships = emptyList()) }
            return manual.takeIf { it.isNotEmpty() }?.let { VisibleBookCharacters(entry, BookCharacterGuide(it, 0, 0, true)) }
        }
        if (guide.characters.any { it.name.isBlank() || (it.evidence + it.attributes.map { a -> a.evidence } + it.relationships.map { r -> r.evidence }).any { e -> e.chapterIndex < 0 || e.fact.start < 0 ||
                e.fact.end <= e.fact.start || e.fact.end - e.fact.start != e.fact.quote.length } }) return null
        return VisibleBookCharacters(entry, guide)
    }
}

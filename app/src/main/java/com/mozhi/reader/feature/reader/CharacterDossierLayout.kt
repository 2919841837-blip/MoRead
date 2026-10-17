package com.mozhi.reader.feature.reader

import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.knowledge.CharacterAttributeKind
import com.mozhi.reader.ai.knowledge.CharacterEvidence
import com.mozhi.reader.ai.knowledge.CharacterRole
import com.mozhi.reader.ai.knowledge.SourceAnchor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * 人物页与档案页的版面规则（纯函数，带单测）：分组、卡片摘要、关系合并与关系图布局。
 * 新档案读 [BookCharacter.dossier]；完整扫描与旧存档没有档案，就从 evidence / attributes / relationships 拼出同样的结构。
 */

/** 一处可以跳回原文的位置：新档案给锚点，旧资料给逐字核对过的依据。 */
internal sealed interface CharacterSource {
    data class Anchor(val anchor: SourceAnchor) : CharacterSource
    data class Evidence(val evidence: CharacterEvidence) : CharacterSource
}

internal val CharacterSource.chapterIndex: Int get() = when (this) {
    is CharacterSource.Anchor -> anchor.chapterIndex
    is CharacterSource.Evidence -> evidence.chapterIndex
}

internal data class CharacterGroup(val role: CharacterRole?, val people: List<BookCharacter>)

/**
 * 有主次信息时按「主角 / 主要人物 / 其他人物」分组；全无主次（旧存档、手动人物）时不分组，保持原顺序。
 * 手写主次也算数：一旦有人带主次，没有主次的人就落进「其他人物」。
 */
internal fun characterGroups(people: List<BookCharacter>): List<CharacterGroup> {
    if (people.none { it.effectiveRole() != null }) return listOf(CharacterGroup(null, people))
    val order = listOf(CharacterRole.PROTAGONIST, CharacterRole.MAJOR, CharacterRole.SUPPORTING)
    return order.mapNotNull { role ->
        people.filter { (it.effectiveRole() ?: CharacterRole.SUPPORTING) == role }.takeIf { it.isNotEmpty() }?.let { CharacterGroup(role, it) }
    }
}

internal data class CharacterSummary(val tagline: String, val tags: List<String>, val relationCount: Int)

/** 卡片上的一句话与标签：手写值优先，其次档案，最后旧资料的第一条事实。 */
internal fun characterSummary(person: BookCharacter): CharacterSummary {
    val dossier = person.dossier
    val tags = (person.attributeValues(CharacterAttributeKind.IDENTITY) + person.attributeValues(CharacterAttributeKind.PERSONALITY))
        .filter(String::isNotBlank).distinct()
    val manualTagline = person.manualAttributes?.tagline
    val tagline = when {
        // 用户接管（含清空成空串）时卡片就显示他的值，不拿小传或原文顶上。
        manualTagline != null -> manualTagline
        person.tagline().isNotBlank() -> person.tagline()
        dossier != null -> dossier.bio.take(60)
        person.bio().isNotBlank() -> person.bio().take(60)
        // 手动新建的人物没有档案也没有简介时，卡片上的话来自第一条原文事实。
        else -> person.evidence.firstOrNull()?.fact?.text.orEmpty()
    }
    val relations = person.manualRelations()?.size
        ?: if (dossier != null) dossier.relationships.size else person.relationships.map { it.target }.distinct().size
    return CharacterSummary(tagline, tags.take(MAX_CARD_TAGS), relations)
}

/**
 * 一位关系人：[relation] 是「对方是他的什么人」，[reverse] 是对方档案里写的「他是对方的什么人」。
 * [identity] 不为空表示对方也在人物列表里，可以点过去看档案。
 */
internal data class EgoLink(val target: String, val identity: String?, val relation: String, val reverse: String,
    val detail: String, val source: CharacterSource?)

/**
 * 合并双向关系：A 的档案说「苏晚 · 师姐」、苏晚的档案说「林舟 · 师弟」，在两人各自的档案里都显示为一条。
 * 旧资料（完整扫描）的关系写的是「本人是对方的什么人」，正好对应 [EgoLink.reverse]。
 *
 * 手写了一组关系之后旧资料一律不算数：用户删掉的那条不该被它复活。别人档案里提到的他照旧并进来 ——
 * 那是「对方眼中」的说法，属于别人的资料，不因为本人改了自己的卡片就消失。
 */
internal fun egoLinks(person: BookCharacter, everyone: List<BookCharacter>): List<EgoLink> {
    val byName = everyone.flatMap { other -> (listOf(other.name, other.identity) + other.aliases).map { it to other } }.toMap()
    val names = (listOf(person.name, person.identity) + person.aliases).toSet()
    val links = linkedMapOf<String, EgoLink>()
    val written = person.manualRelations()
    val own = written ?: person.dossier?.relationships.orEmpty()
    own.forEach { relation ->
        val other = byName[relation.target]
        val key = other?.identity ?: relation.target
        links.putIfAbsent(key, EgoLink(other?.name ?: relation.target, other?.identity, relation.relation, "",
            relation.detail, relation.anchor?.let(CharacterSource::Anchor)))
    }
    if (written == null) person.relationships.forEach { legacy ->
        val other = byName[legacy.target]
        val key = other?.identity ?: legacy.target
        val existing = links[key]
        links[key] = existing?.copy(reverse = existing.reverse.ifBlank { legacy.relation })
            ?: EgoLink(other?.name ?: legacy.target, other?.identity, "", legacy.relation, "", CharacterSource.Evidence(legacy.evidence))
    }
    // 别人的资料里提到他：补上反向称谓，或补上他自己的档案没写到的关系人。
    everyone.filter { it.identity != person.identity }.forEach { other ->
        other.dossier?.relationships.orEmpty().filter { it.target in names }.forEach { relation ->
            val existing = links[other.identity]
            links[other.identity] = existing?.copy(reverse = existing.reverse.ifBlank { relation.relation },
                detail = existing.detail.ifBlank { relation.detail }, source = existing.source ?: relation.anchor?.let(CharacterSource::Anchor))
                ?: EgoLink(other.name, other.identity, "", relation.relation, relation.detail, relation.anchor?.let(CharacterSource::Anchor))
        }
        // 别人的旧资料写的是「对方相对本人」（船夫是林舟的邻居），正对应本人这一侧的 relation。
        other.relationships.filter { it.target in names }.forEach { legacy ->
            val existing = links[other.identity]
            links[other.identity] = existing?.copy(relation = existing.relation.ifBlank { legacy.relation })
                ?: EgoLink(other.name, other.identity, legacy.relation, "", "", CharacterSource.Evidence(legacy.evidence))
        }
    }
    return links.values.toList()
}

/** 关系图连线上的短标签：优先「对方是他的什么人」，没有就用反向称谓。 */
internal fun EgoLink.label(): String = relation.ifBlank { reverse }

internal data class GraphNode(val identity: String, val name: String, val role: CharacterRole?, val weight: Float)
internal data class GraphEdge(val from: String, val to: String, val label: String)
internal data class RelationGraph(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

/** 全书关系图：只连列表里都有的人；一对人只画一条边，标签取主次更靠前一方的说法。节点大小按出场次数取对数。 */
internal fun relationGraph(everyone: List<BookCharacter>, limit: Int = MAX_GRAPH_NODES): RelationGraph {
    val ranked = everyone.withIndex().sortedWith(compareBy({ roleRank(it.value.effectiveRole()) }, { it.index })).map { it.value }.take(limit)
    val identities = ranked.map { it.identity }.toSet()
    val maxMentions = ranked.maxOfOrNull { it.mentions }?.coerceAtLeast(1) ?: 1
    val nodes = ranked.map { person ->
        val role = person.effectiveRole()
        val base = when (role) { CharacterRole.PROTAGONIST -> 1f; CharacterRole.MAJOR -> .7f; else -> .45f }
        val mention = if (person.mentions <= 0) 0f else (ln(1f + person.mentions) / ln(1f + maxMentions))
        GraphNode(person.identity, person.name, role, (base * .6f + mention * .4f).coerceIn(.3f, 1f))
    }
    val edges = linkedMapOf<Pair<String, String>, GraphEdge>()
    ranked.forEach { person ->
        egoLinks(person, ranked).filter { it.identity != null && it.identity in identities }.forEach { link ->
            val pair = listOf(person.identity, link.identity!!).sorted().let { it[0] to it[1] }
            edges.putIfAbsent(pair, GraphEdge(person.identity, link.identity, link.label()))
        }
    }
    return RelationGraph(nodes, edges.values.toList())
}

/**
 * 自我中心图：关系人排在中心周围，返回 0..1 归一化坐标，中心在 (0.5, 0.5)。
 * 一两位时左右摆开（上下摆会让连线太短、标签压住名字）；三位起均匀排一圈，从正上方顺时针。
 */
internal fun egoPositions(count: Int, radius: Float = .36f): List<Pair<Float, Float>> = when (count) {
    0 -> emptyList()
    1 -> listOf((.5f + radius) to .5f)
    2 -> listOf((.5f - radius) to .5f, (.5f + radius) to .5f)
    else -> (0 until count).map { index ->
        val angle = -PI / 2 + 2 * PI * index / count
        (.5f + radius * cos(angle).toFloat()) to (.5f + radius * sin(angle).toFloat())
    }
}

private fun roleRank(role: CharacterRole?): Int = when (role) {
    CharacterRole.PROTAGONIST -> 0
    CharacterRole.MAJOR -> 1
    CharacterRole.SUPPORTING -> 2
    null -> 3
}

internal const val MAX_CARD_TAGS = 3
internal const val MAX_GRAPH_NODES = 24
/** 档案页上自我中心图最多画几位关系人，其余只在下方列表里。 */
internal const val MAX_EGO_NODES = 8

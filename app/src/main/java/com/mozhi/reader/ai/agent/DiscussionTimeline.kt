package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity

/**
 * 讨论串时间线上的一条发言。
 *
 * 楼主层（annotations.note）与回复层（annotation_replies）合并成一条时间线：一条发言可能
 * 来自批注行，也可能来自回复行；上层只消费 [DiscussionTurn]，不必再关心它落在哪张表。
 */
internal sealed interface DiscussionTurn {
    val id: Long
    val personaId: Long?
    val text: String
    val createdAt: Long

    /** 批注行发起的发言：用户写的想法，或 AI 自动段评。 */
    data class FromAnnotation(val annotation: AnnotationEntity) : DiscussionTurn {
        override val id: Long get() = annotation.id
        override val personaId: Long? get() = annotation.personaId
        override val text: String get() = annotation.note
        override val createdAt: Long get() = annotation.createdAt
    }

    /** 回复行发起的发言。 */
    data class FromReply(val reply: AnnotationReplyEntity) : DiscussionTurn {
        override val id: Long get() = reply.id
        override val personaId: Long? get() = reply.personaId
        override val text: String get() = reply.contentMarkdown
        override val createdAt: Long get() = reply.createdAt
    }
}

/** 谁都没说过话时的讨论串占位——讨论串区块不能是空的。 */
internal const val EMPTY_THREAD_TRANSCRIPT = "（还没有人发言）"

/**
 * 讨论串时间线：按 (createdAt, id) 升序合并两层发言。
 *
 * **note 为空的批注不产生发言**——用户只划了原文、还没写想法时，那条批注只是划线的载体，
 * 不该占楼；谁先真正开口谁就是楼主。这条口径与段评前文召回一致：召回讨论串时同样丢弃
 * 空 note 的楼主层（见 ProactiveAnnotationContext 的 recallThreads）。
 *
 * 楼主位一律按时间定，不给人任何优先权：批注行比回复行早是常态（回复总是发生在批注之后），
 * 但同锚点里 AI 段评先落地、用户后补想法时，后补的想法就排在 AI 段评之下——时间线如实反映
 * 发言先后。
 */
internal fun buildDiscussionTurns(
    annotations: List<AnnotationEntity>,
    replies: List<AnnotationReplyEntity>
): List<DiscussionTurn> = (
    annotations.filter { it.note.isNotBlank() }.map { DiscussionTurn.FromAnnotation(it) } +
        replies.map { DiscussionTurn.FromReply(it) }
    ).sortedWith(compareBy<DiscussionTurn> { it.createdAt }.thenBy { it.id })

/** 一条发言挂在哪条批注上：回复挂在它所属的批注，批注发言挂在自己。 */
internal fun DiscussionTurn.ownerAnnotationId(): Long = when (this) {
    is DiscussionTurn.FromAnnotation -> annotation.id
    is DiscussionTurn.FromReply -> reply.annotationId
}

/**
 * 用户这条发言写进楼主层（自己批注的 note）还是落回复层。
 *
 * 只有「用户自己那条空批注不晚于组内任何发言」时才写楼主层——写 note 不改 createdAt，
 * 所以判定和「写完之后再排一遍时间线」是同一个结果，不会刚说完就发现自己的话排在别人
 * 下面。用户已经有想法、或批注属于角色、或组里已经有人更早开口，都落回复层。
 */
internal fun userSpeaksAsOpener(target: AnnotationEntity, turns: List<DiscussionTurn>): Boolean =
    target.personaId == null &&
        target.note.isBlank() &&
        turns.none { it.createdAt < target.createdAt }

internal const val USER_SPEAKER_NAME = "用户"
internal const val DELETED_PERSONA_NAME = "已删除角色"
private const val SELF_SUFFIX = "（你）"
private const val PEER_SUFFIX = "（其他角色）"

/**
 * 讨论串里的一位发言人。转写的署名（[label]）与 @点名 用的名字（[handle]）出自同一份花名册，
 * 两边不会漂移：模型看到的「林致（其他角色）」和它能写的 `@林致` 指同一个人。
 *
 * 角色名在库里可以重复，[handle] 因此按发言先后加数字后缀去重（「观澜」「观澜2」）——只在真
 * 撞名时出现，日常零成本；[label] 不参与匹配，只是给人看的署名。
 */
internal data class DiscussionSpeaker(
    val personaId: Long?,
    val label: String,
    val handle: String,
    val isSelf: Boolean
)

/**
 * 按时间线首次发言顺序列出参与讨论的人。判定只用 personaId，不比名字：同名同伴不能被误标成
 * 「你」（同一角色连发多条也只出现一次）。
 */
internal fun discussionRoster(
    turns: List<DiscussionTurn>,
    personaNames: Map<Long, String>,
    currentPersonaId: Long
): List<DiscussionSpeaker> {
    val baseNames = LinkedHashMap<Long?, String>()
    turns.forEach { turn ->
        val personaId = turn.personaId
        if (!baseNames.containsKey(personaId)) {
            baseNames[personaId] = personaId?.let { personaNames[it] ?: DELETED_PERSONA_NAME }
                ?: USER_SPEAKER_NAME
        }
    }
    val used = mutableMapOf<String, Int>()
    return baseNames.map { (personaId, base) ->
        val count = (used[base] ?: 0) + 1
        used[base] = count
        val handle = if (count == 1) base else base + count
        val label = if (personaId == null) {
            handle
        } else {
            handle + if (personaId == currentPersonaId) SELF_SUFFIX else PEER_SUFFIX
        }
        DiscussionSpeaker(personaId, label, handle, personaId == currentPersonaId)
    }
}

/** SYSTEM 里的「参与讨论的人」一行：模型点名时的合法名单。谁都没开口时不出现（由调用方决定）。 */
internal fun buildDiscussionRosterLine(roster: List<DiscussionSpeaker>): String =
    "参与讨论的人：" + roster.joinToString("、") { it.label }

/**
 * 讨论串转写成给模型看的对话记录。发言按时间线顺序排，发言人按 personaId 标
 * 「（你）/（其他角色）/用户」——空 note 的批注不入线，所以第一条就是楼主。
 */
internal fun buildDiscussionTranscript(
    turns: List<DiscussionTurn>,
    personaNames: Map<Long, String>,
    currentPersonaId: Long
): String {
    if (turns.isEmpty()) return EMPTY_THREAD_TRANSCRIPT
    val byPersona = discussionRoster(turns, personaNames, currentPersonaId).associateBy { it.personaId }
    return turns.joinToString("\n") { turn ->
        // 名单由这批 turns 自己推出来，查不到只可能是 personaId 为 null 的用户（也在名单里）；
        // 真兜底也宁可显示成「已删除角色」，不能把某条发言误标成用户说的。
        (byPersona[turn.personaId]?.label ?: DELETED_PERSONA_NAME) + "：" + turn.text
    }
}

/**
 * 讨论串 AI 应答的用户轮。用户留空（全场没写过想法）时不能一路让他「点评这段原文」——
 * 时间线上已有谁说过话，决定他是接着这场讨论往下说，还是独立点评。三个判断量都由
 * [buildDiscussionTurns] 的结果推出来。
 *
 * 用户说过话时以他的话为准（旧行为）；只有同伴说过 → 请他接同伴的点（可以点名回应任何一个人，
 * 不必只接最后一条）；只有自己说过 → 请他补新东西而不是把点评重讲一遍；谁都没说过 → 照旧点评原文。
 */
internal fun buildDiscussionUserTurn(
    personaName: String,
    latestUserText: String?,
    peerSpoke: Boolean,
    selfSpoke: Boolean
): String = when {
    latestUserText != null ->
        "用户在这段讨论里说：「$latestUserText」\n" +
            "请以${personaName}的身份直接给出你在讨论串里的下一条发言。"
    peerSpoke ->
        "讨论串里已经有其他角色的发言，用户没有补充想法。\n" +
            "请以${personaName}的身份接着这场讨论往下说，一句话就够：可以回应其中任何一个人，" +
            "也可以同时回应几个人，用 @名字 点出你在回应谁；不必只接最后一条。"
    selfSpoke ->
        "用户没有补充想法，讨论串里最后是你自己的发言。\n" +
            "请以${personaName}的身份补充一点前面没说到的新东西，不要重复上面说过的话。"
    else ->
        "用户邀请你点评这段划线原文，没有附加想法。\n" +
            "请以${personaName}的身份直接给出你在讨论串里的下一条发言。"
}

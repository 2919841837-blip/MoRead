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
    // 只比 personaId，不比名字：角色名可以在库里重复，按名字判会把同名同伴误标成「你」。
    fun label(personaId: Long?): String {
        if (personaId == null) return "用户"
        val name = personaNames[personaId] ?: "已删除角色"
        return if (personaId == currentPersonaId) "$name（你）" else "$name（其他角色）"
    }
    return turns.joinToString("\n") { label(it.personaId) + "：" + it.text }
}

/**
 * 讨论串 AI 应答的用户轮。用户留空（全场没写过想法）时不能一路让他「点评这段原文」——
 * 时间线上已有谁说过话，决定他是接着这场讨论往下说，还是独立点评。三个判断量都由
 * [buildDiscussionTurns] 的结果推出来。
 *
 * 用户说过话时以他的话为准（旧行为）；只有同伴说过 → 明确请他接同伴的点；只有自己说过 →
 * 请他补新东西而不是把点评重讲一遍；谁都没说过 → 照旧点评原文。
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
            "请以${personaName}的身份接着这场讨论往下说：先明确你在回应谁的哪一点，再给出你的看法。"
    selfSpoke ->
        "用户没有补充想法，讨论串里最后是你自己的发言。\n" +
            "请以${personaName}的身份补充一点前面没说到的新东西，不要重复上面说过的话。"
    else ->
        "用户邀请你点评这段划线原文，没有附加想法。\n" +
            "请以${personaName}的身份直接给出你在讨论串里的下一条发言。"
}

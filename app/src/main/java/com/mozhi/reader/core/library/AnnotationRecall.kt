package com.mozhi.reader.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条段评实际召回的前文资料明细，随 [AnnotationEntity.recallJson] 落库，供段评详情页折叠展示。
 *
 * 只在「随读段评」生成时写入，记录的是召回这一步挑出来的东西——大纲、角色卡、笔记、正文片段
 * （片段带来源：向量 / BM25 / 两者融合），而不是拼进提示词后被预算裁剪后的最终文本。用户借此
 * 检查「这段评为什么会这么写」。
 */
@Serializable
data class AnnotationRecall(
    val outlines: List<RecallOutline> = emptyList(),
    val characterCards: List<RecallCard> = emptyList(),
    val notes: List<RecallNote> = emptyList(),
    val snippets: List<RecallSnippet> = emptyList(),
    val discussions: List<RecallDiscussion> = emptyList()
) {
    val isEmpty: Boolean
        get() = outlines.isEmpty() && characterCards.isEmpty() && notes.isEmpty() && snippets.isEmpty() &&
            discussions.isEmpty()

    fun encode(): String = if (isEmpty) "{}" else codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }

        fun decode(raw: String?): AnnotationRecall = raw
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { codec.decodeFromString(serializer(), it) }.getOrNull() }
            ?: AnnotationRecall()
    }
}

/** 召回的一条章梗概；[label] 是「第 X 章「标题」」，[text] 是梗概正文。 */
@Serializable
data class RecallOutline(val label: String = "", val text: String = "")

/** 召回的一位角色卡；[name] 是人物名，[profile] 是已按章节锚点过滤、并收窄到当前段涉及字段后的档案正文。 */
@Serializable
data class RecallCard(val name: String = "", val profile: String = "")

/** 召回的一条用户前文笔记；[label] 是章锚，[text] 是笔记正文。 */
@Serializable
data class RecallNote(val label: String = "", val text: String = "")

/**
 * 召回的一段前文正文；[source] 标记它来自向量召回还是 BM25 词召回（或两者融合）。
 * 只用于展示，不参与任何召回逻辑。
 */
@Serializable
data class RecallSnippet(val label: String = "", val text: String = "", val source: String = "")

/** 召回的一条讨论结论（AI 在前文讨论串里的回复）；[label] 是章锚，[text] 是回复正文。 */
@Serializable
data class RecallDiscussion(val label: String = "", val text: String = "")

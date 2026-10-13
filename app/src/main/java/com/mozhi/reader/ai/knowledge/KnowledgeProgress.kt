package com.mozhi.reader.ai.knowledge

/**
 * 人物任务的结构化进度：通知栏、阅读页悬浮球与人物页进度卡共用。
 * [finished] 按完成先后排列，界面用它逐个点亮头像；[failed] 是没整理成的人名，不阻断其余人。
 */
data class KnowledgeProgress(
    val stage: Stage,
    val done: Int = 0,
    val total: Int = 0,
    val working: List<String> = emptyList(),
    val finished: List<String> = emptyList(),
    val failed: List<String> = emptyList()
) {
    enum class Stage { ROSTER, PROFILES, WEB, SCANNING, WRITING }

    /** 0..1；名单阶段还不知道总数，返回 null 表示不定进度。 */
    val fraction: Float? get() = if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)
}

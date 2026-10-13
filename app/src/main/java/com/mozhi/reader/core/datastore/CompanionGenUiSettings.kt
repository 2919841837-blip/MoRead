package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 伴读「互动回答」出现得多频繁（design/companion-genui-plan.md §15）。 */
enum class GenUiLevel {
    /** 提示词里一个字都不写；模型写了互动标签也只按纯文字显示。 */
    OFF,
    /** 默认：模型自行判断，闲聊不出现，交互或可视化明显更好时才用。 */
    MODERATE,
    /** 更愿意用界面来组织回答。 */
    ACTIVE
}

/** 互动卡片的样式自由度。 */
enum class GenUiStyleFreedom {
    /** 默认：模型可以自由写样式（颜色、渐变、阴影、字体、动效…），宿主只兜可读性。 */
    FREE,
    /** 只用墨知令牌，渲染层忽略 style。 */
    HOUSE
}

/** 回答里插图（生图）的触发方式。 */
enum class GenUiIllustrationPolicy {
    /** 默认：先显示占位卡，点按才生成，不悄悄花钱。 */
    TAP,
    /** 每条回复至多自动生成 1 张，其余回到点按。 */
    AUTO_ONE,
    OFF
}

/** 网页小程序（AppBlock）的运行方式。 */
enum class GenUiAppBlockPolicy {
    /** 默认：进入可见区域时运行；离开后换成快照，滚动不卡。 */
    WHEN_VISIBLE,
    TAP,
    OFF
}

@Serializable
data class CompanionGenUiSettings(
    val level: GenUiLevel = GenUiLevel.MODERATE,
    val style: GenUiStyleFreedom = GenUiStyleFreedom.FREE,
    val illustrations: GenUiIllustrationPolicy = GenUiIllustrationPolicy.TAP,
    val appBlocks: GenUiAppBlockPolicy = GenUiAppBlockPolicy.WHEN_VISIBLE,
    /** 夜间把模型写死的浅色底「保色相、翻明度」。 */
    val nightAdapt: Boolean = true
) {
    val enabled: Boolean get() = level != GenUiLevel.OFF

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String?): CompanionGenUiSettings =
            raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: CompanionGenUiSettings()

        fun encode(value: CompanionGenUiSettings): String = json.encodeToString(serializer(), value)
    }
}

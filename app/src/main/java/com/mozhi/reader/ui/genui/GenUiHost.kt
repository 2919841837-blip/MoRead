package com.mozhi.reader.ui.genui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.mozhi.reader.ai.genui.GenUiNode
import kotlinx.serialization.json.JsonArray

/** 用户在界面上的操作：处理器编号 + 参数，交给引擎 invoke。 */
fun interface GenUiEvents {
    fun dispatch(handlerId: String, args: JsonArray)
}

/**
 * 宿主插槽：依赖具体页面组件的部分由调用方提供（原文引用卡要跳回阅读页、图表用聊天页的图表卡、
 * Markdown 用聊天气泡同一套富文本）。`ui/genui` 不反向依赖 feature 包。
 */
interface GenUiHost {
    /** `#md` 节点（顶层控制块里的 Markdown）。 */
    @Composable
    fun Markdown(text: String, modifier: Modifier)

    /**
     * 宿主组件（`Quote`、`Chart`、`Character`、`AppBlock`…）。
     * 返回 false 表示宿主不支持，渲染器改用纯文字投影显示。
     */
    @Composable
    fun Component(node: GenUiNode, modifier: Modifier): Boolean

    /** `image src` 与样式里 `url(asset:…)` 的图片模型（Coil 可加载的 File / Uri），不认识返回 null。 */
    fun imageModel(src: String): Any?

    /** `font-family` 里写的字体库字体名；找不到返回 null（跟随默认字体）。 */
    fun fontFamily(name: String): FontFamily?

    /** 系统「移除动画」开着时为 false：所有预设动效与过渡停用。 */
    val animationsEnabled: Boolean
}

/** 不提供宿主时的最小实现：Markdown 按纯文字、宿主组件交给纯文字投影。 */
object PlainGenUiHost : GenUiHost {
    @Composable
    override fun Markdown(text: String, modifier: Modifier) {
        GenUiPlainText(text, modifier)
    }

    @Composable
    override fun Component(node: GenUiNode, modifier: Modifier): Boolean = false

    override fun imageModel(src: String): Any? = null

    override fun fontFamily(name: String): FontFamily? = null

    override val animationsEnabled: Boolean = true
}

val LocalGenUiHost = staticCompositionLocalOf<GenUiHost> { PlainGenUiHost }

val LocalGenUiEvents = staticCompositionLocalOf<GenUiEvents> { GenUiEvents { _, _ -> } }

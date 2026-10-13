package com.mozhi.reader.ai.genui

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 运行时交出的界面树节点。
 *
 * - [type]：组件名（`box`、`slider`、`Quote`…），或三种内部节点：
 *   `#island`（一个互动岛的根）、`#frag`（`{#each}` 的一项）、`#text`（文字）。
 * - [key]：从岛根算起的结构路径，跨重编译稳定；Compose 用它复用正在被操作的控件。
 * - 事件处理器在 [props] 里是 `{"__fn":"<编号>"}`，函数本体留在引擎里，回调时把编号交回去。
 */
@Immutable
data class GenUiNode(
    val type: String,
    val key: String,
    val props: Map<String, JsonElement> = emptyMap(),
    val children: List<GenUiNode> = emptyList(),
    val text: String? = null
) {
    val isText: Boolean get() = type == TEXT

    fun prop(name: String): JsonElement? = props[name]?.takeUnless { it is JsonNull }

    fun string(name: String): String? = (prop(name) as? JsonPrimitive)?.let { primitive ->
        if (primitive.isString) primitive.content else primitive.contentOrNull
    }

    fun number(name: String): Double? = (prop(name) as? JsonPrimitive)?.let { primitive ->
        primitive.doubleOrNull ?: primitive.content.trim().toDoubleOrNull()
    }

    fun int(name: String): Int? = number(name)?.takeIf { it.isFinite() }?.toInt()

    /** 布尔属性：裸写的属性编译为 true；字符串 "false" 也按假处理（模型常这么写）。 */
    fun flag(name: String): Boolean {
        val primitive = prop(name) as? JsonPrimitive ?: return false
        primitive.booleanOrNull?.let { return it }
        return when (primitive.content.trim().lowercase()) {
            "", "false", "0", "no", "off" -> false
            else -> true
        }
    }

    /** 事件处理器编号；没写或不是函数时为 null。 */
    fun handler(name: String): String? =
        ((prop(name) as? JsonObject)?.get(HANDLER_FIELD) as? JsonPrimitive)?.contentOrNull

    /**
     * 子节点，`{#each}` 产生的片段就地展开：`radio-group` 找 `radio`、`tabs` 找 `tab` 这类
     * 「父组件按子组件类型取数」的地方都用它，循环生成的选项与手写的选项一视同仁。
     */
    val flatChildren: List<GenUiNode> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (children.none { it.type == FRAGMENT }) children
        else buildList { children.forEach { child -> if (child.type == FRAGMENT) addAll(child.flatChildren) else add(child) } }
    }

    /** 子树里所有文字拼起来（标签、按钮文字这类只需要纯文本的地方用）。 */
    fun plainText(): String = buildString { appendText(this@GenUiNode) }

    private fun StringBuilder.appendText(node: GenUiNode) {
        if (node.isText) {
            append(node.text.orEmpty())
            return
        }
        node.children.forEach { appendText(it) }
    }

    companion object {
        const val ISLAND = "#island"
        const val FRAGMENT = "#frag"
        const val TEXT = "#text"
        const val HANDLER_FIELD = "__fn"

        fun fromJson(element: JsonElement): GenUiNode? {
            val obj = element as? JsonObject ?: return null
            val type = (obj["t"] as? JsonPrimitive)?.contentOrNull ?: return null
            if (type == TEXT) {
                return GenUiNode(type = TEXT, key = "", text = (obj["v"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
            val key = (obj["k"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val props = (obj["p"] as? JsonObject)?.toMap().orEmpty()
            val children = (obj["c"] as? JsonArray)?.mapNotNull(::fromJson).orEmpty()
            return GenUiNode(type = type, key = key, props = props, children = children)
        }
    }
}

/** 一次求值（加载、重编译或事件）的结果。 */
@Immutable
data class GenUiRenderResult(
    val islands: List<GenUiNode>,
    /** 所有具名状态的当前值。 */
    val state: JsonObject,
    /** 程序声明的初值：与 [state] 对比得到「用户改过什么」（交给下一轮模型）。 */
    val initial: JsonObject,
    val actions: List<GenUiAction>,
    val errors: List<GenUiRuntimeError>
) {
    companion object {
        val Empty = GenUiRenderResult(emptyList(), JsonObject(emptyMap()), JsonObject(emptyMap()), emptyList(), emptyList())

        fun fromJson(element: JsonElement): GenUiRenderResult {
            val obj = element as? JsonObject ?: return Empty
            return GenUiRenderResult(
                islands = (obj["tree"] as? JsonArray)?.mapNotNull(GenUiNode::fromJson).orEmpty(),
                state = obj["state"] as? JsonObject ?: JsonObject(emptyMap()),
                initial = obj["initial"] as? JsonObject ?: JsonObject(emptyMap()),
                actions = (obj["actions"] as? JsonArray)?.mapNotNull { action ->
                    val a = action as? JsonObject ?: return@mapNotNull null
                    val type = (a["type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    GenUiAction(type, a["payload"] as? JsonObject ?: JsonObject(emptyMap()))
                }.orEmpty(),
                errors = (obj["errors"] as? JsonArray)?.mapNotNull { error ->
                    val e = error as? JsonObject ?: return@mapNotNull null
                    GenUiRuntimeError(
                        code = e["code"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        detail = (e["detail"] as? JsonPrimitive)?.contentOrNull
                    )
                }.orEmpty()
            )
        }
    }
}

/** 程序在用户手势事件里请求的对外动作，尚未校验；由宿主按白名单与参数规则执行。 */
@Immutable
data class GenUiAction(val type: String, val payload: JsonObject)

/** 运行期错误：表达式抛错、渲染失败、动作越权等。只用于诊断，不进日志正文。 */
@Immutable
data class GenUiRuntimeError(val code: String, val detail: String?)

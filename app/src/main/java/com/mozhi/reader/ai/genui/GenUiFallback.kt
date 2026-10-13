package com.mozhi.reader.ai.genui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 互动回答的纯文字投影：凡是「需要文字而不是界面」的地方共用这一处——
 * 复制消息、朗读整条、记忆固化与滚动摘要、导出、历史压缩、编译失败时的兜底显示。
 *
 * 有渲染结果时按界面树投影（含计算出的值与用户当前的选择）；没有时按语法树做静态投影，
 * 条件块与循环（结果未知）略去。交互控件只保留标签与当前值，按钮不出现。
 */
object GenUiFallback {

    /**
     * 整条消息：Markdown 段原样，岛换成投影。
     * @param rendered 各岛的渲染结果（按岛序号），缺失的岛用静态投影
     */
    fun project(document: GenUiDocument, rendered: List<GenUiNode>? = null): String {
        if (!document.isInteractive && document.statements.isEmpty()) return document.source.trim()
        val parts = document.segments.mapNotNull { segment ->
            when (segment) {
                is GenUiSegment.Markdown -> segment.text.trim('\n').takeIf { it.isNotBlank() }
                is GenUiSegment.Island -> {
                    val tree = rendered?.getOrNull(segment.index)
                    (if (tree != null) projectTree(tree) else projectNodes(segment.nodes)).takeIf { it.isNotBlank() }
                }
            }
        }
        return tidy(parts.joinToString("\n\n"))
    }

    /** 原文的纯文字投影（不解析也能直接调用）：没有 DIL 痕迹时原样返回。 */
    fun projectSource(source: String): String {
        if (!GenUiDocument.mightContainDil(source)) return source
        return project(GenUiDocument.parse(source))
    }

    /* ---------------------------- 语法树（静态） ---------------------------- */

    fun projectNodes(nodes: List<DilNode>): String = tidy(buildString { nodes.forEach { appendStatic(it) } })

    private fun StringBuilder.appendStatic(node: DilNode) {
        when (node) {
            is DilText -> append(Codec.jsxText(node.value).orEmpty())
            is DilMarkdown -> {
                newline()
                append(node.value.trim('\n'))
                newline()
            }
            is DilExpr, is DilIf, is DilEach -> Unit
            is DilRaw -> {
                newline()
                append("（小程序")
                node.attrs.firstOrNull { it.name == "title" && it.kind == DilAttrKind.STRING }?.let { append("：").append(it.value) }
                append("）")
                newline()
            }
            is DilElement -> appendStaticElement(node)
        }
    }

    private fun StringBuilder.appendStaticElement(node: DilElement) {
        fun literal(name: String): String? = node.attr(name)?.takeIf { it.kind == DilAttrKind.STRING }?.value
        fun inline(): String = tidyInline(buildString { node.children.forEach { appendStatic(it) } })
        when (node.name) {
            "title" -> line("**${inline()}**")
            "text", "caption", "label", "badge" -> line(inline())
            "bold" -> append("**").append(inline()).append("**")
            "italic" -> append('*').append(inline()).append('*')
            "link" -> append(inline())
            "code" -> append('`').append(node.children.filterIsInstance<DilText>().joinToString("") { it.value }).append('`')
            "blockquote" -> line("> " + inline())
            "list-item" -> line("- " + inline())
            "radio", "checkbox", "switch" -> line("- " + inline())
            "table-row" -> line(node.children.filterIsInstance<DilElement>().joinToString(" | ") { cell ->
                tidyInline(buildString { cell.children.forEach { appendStatic(it) } })
            })
            "tab", "disclosure" -> {
                literal("label")?.let { line("**$it**") }
                node.children.forEach { appendStatic(it) }
            }
            "slider", "stepper", "stat", "progress", "select", "segmented" -> {
                val label = literal("label") ?: return
                val value = literal("value")
                line(if (value != null) "$label：$value" else label)
            }
            "step", "event" -> {
                val title = listOfNotNull(literal("time"), literal("title")).joinToString(" · ")
                val body = inline()
                line(listOf(title, body).filter { it.isNotBlank() }.joinToString("："))
            }
            "Quote" -> literal("text")?.let { text ->
                line("> 「$text」" + (literal("chapter")?.let { "（第${it}章）" } ?: ""))
            }
            "Character" -> literal("name")?.let { append(it) }
            "Illustration" -> literal("scene")?.let { line("（插图：$it）") }
            "Voice" -> literal("text")?.let { line(it) }
            "Line" -> line(listOfNotNull(literal("who"), inline().takeIf { it.isNotBlank() }).joinToString("："))
            "Book" -> Unit
            "Source" -> literal("title")?.let { line(it) }
            "button", "input", "textarea", "image", "icon", "divider", "spacer", "chips" -> Unit
            else -> {
                newline()
                node.children.forEach { appendStatic(it) }
                newline()
            }
        }
    }

    /* ---------------------------- 界面树（渲染后） ---------------------------- */

    fun projectTree(island: GenUiNode): String = tidy(buildString { island.children.forEach { appendTree(it) } })

    private fun StringBuilder.appendTree(node: GenUiNode) {
        if (node.isText) {
            append(node.text.orEmpty())
            return
        }
        if (node.flag("hidden")) return
        fun inline(): String = tidyInline(buildString { node.children.forEach { appendTree(it) } })
        when (node.type) {
            GenUiNode.FRAGMENT, GenUiNode.ISLAND -> node.children.forEach { appendTree(it) }
            "#md" -> {
                newline()
                append(node.children.joinToString("") { it.text.orEmpty() }.trim('\n'))
                newline()
            }
            "title" -> line("**${inline()}**")
            "text", "caption", "label", "badge" -> line(inline())
            "bold" -> append("**").append(inline()).append("**")
            "italic" -> append('*').append(inline()).append('*')
            "link" -> append(inline())
            "code" -> append('`').append(inline()).append('`')
            "blockquote" -> line("> " + inline())
            "list-item" -> line("- " + inline())
            "checkbox", "switch" -> line((if (node.flag("checked")) "- [x] " else "- [ ] ") + inline())
            "radio-group" -> {
                val selected = node.prop("value")?.let(::scalar)
                node.flatChildren.filter { it.type == "radio" }.forEach { radio ->
                    val mark = if (selected != null && scalar(radio.prop("value")) == selected) "(•) " else "( ) "
                    line(mark + radio.plainText().trim())
                }
            }
            "table-row" -> line(node.flatChildren.filter { !it.isText }.joinToString(" | ") { cell ->
                tidyInline(buildString { cell.children.forEach { appendTree(it) } })
            })
            "tabs" -> {
                val selected = node.prop("value")?.let(::scalar)
                val tabs = node.flatChildren.filter { it.type == "tab" }
                val active = tabs.firstOrNull { scalar(it.prop("value")) == selected } ?: tabs.firstOrNull()
                active?.let { tab ->
                    tab.string("label")?.let { line("**$it**") }
                    tab.children.forEach { appendTree(it) }
                }
            }
            "tab", "disclosure" -> {
                node.string("label")?.let { line("**$it**") }
                node.children.forEach { appendTree(it) }
            }
            "slider", "stepper", "stat", "progress" -> {
                val label = node.string("label")
                val value = node.prop("value")?.let(::scalar)
                val unit = node.string("unit").orEmpty()
                val text = listOfNotNull(label, value?.let { it + unit }).joinToString("：")
                if (text.isNotBlank()) line(text)
            }
            "select", "segmented" -> {
                val value = node.prop("value")?.let(::scalar)
                val label = optionLabel(node, value) ?: value
                line(listOfNotNull(node.string("label"), label).joinToString("："))
            }
            "chips" -> {
                val values = (node.prop("value") as? JsonArray)?.mapNotNull { scalar(it) }.orEmpty()
                if (values.isNotEmpty()) line(values.joinToString("、") { optionLabel(node, it) ?: it })
            }
            "steps" -> node.flatChildren.filter { it.type == "step" }.forEach { step ->
                val done = step.flag("done")
                line((if (done) "- [x] " else "- [ ] ") + listOfNotNull(step.string("title"), step.plainText().trim().takeIf { it.isNotEmpty() }).joinToString("："))
            }
            "event" -> line(listOfNotNull(node.string("time"), node.string("title"), inline().takeIf { it.isNotBlank() }).joinToString(" · "))
            "Quote" -> node.string("text")?.let { text ->
                line("> 「$text」" + (node.int("chapter")?.let { "（第${it}章）" } ?: ""))
            }
            "Character" -> node.string("name")?.let { append(it) }
            "Illustration" -> node.string("scene")?.let { line("（插图：$it）") }
            "Voice" -> node.string("text")?.let { line(it) }
            "Line" -> line(listOfNotNull(node.string("who"), inline().takeIf { it.isNotBlank() }).joinToString("："))
            "Source" -> node.string("title")?.let { line(it) }
            "AppBlock" -> line("（小程序" + (node.string("title")?.let { "：$it" } ?: "") + "）")
            "button", "input", "textarea", "image", "icon", "divider", "spacer", "Book", "Chart" -> Unit
            else -> {
                newline()
                node.children.forEach { appendTree(it) }
                newline()
            }
        }
    }

    private fun optionLabel(node: GenUiNode, value: String?): String? {
        value ?: return null
        val options = node.prop("options") as? JsonArray ?: return null
        options.forEach { option ->
            when (option) {
                is JsonObject -> if (scalar(option["value"]) == value) return scalar(option["label"]) ?: value
                is JsonPrimitive -> if (option.contentOrNull == value) return value
                else -> Unit
            }
        }
        return null
    }

    private fun scalar(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull

    /* ---------------------------- 排版 ---------------------------- */

    private fun StringBuilder.newline() {
        if (isNotEmpty() && last() != '\n') append('\n')
    }

    private fun StringBuilder.line(text: String) {
        if (text.isBlank()) return
        newline()
        append(text.trim())
        append('\n')
    }

    private val BLANK_RUN = Regex("\n{3,}")
    private val SPACE_RUN = Regex("[ \\t]{2,}")

    private fun tidy(text: String): String = BLANK_RUN.replace(text.trim(), "\n\n")

    private fun tidyInline(text: String): String = SPACE_RUN.replace(text.replace('\n', ' '), " ").trim()

    /** 与编译器共用 JSX 空白规则。 */
    private object Codec {
        fun jsxText(raw: String): String? = GenUiCompiler.jsxText(raw)
    }
}

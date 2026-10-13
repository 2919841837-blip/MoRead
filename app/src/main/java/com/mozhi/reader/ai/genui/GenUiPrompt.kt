package com.mozhi.reader.ai.genui

import com.mozhi.reader.core.datastore.GenUiLevel

/** 本轮实际可用的界面能力；关着的能力在提示词里一个字都不写（与「工具关了就不注册」同一条纪律）。 */
data class GenUiCapabilities(
    val level: GenUiLevel = GenUiLevel.OFF,
    val freeStyle: Boolean = true,
    /** 有书（原文引用、跳回原文、读书进度数据）。 */
    val book: Boolean = false,
    val characters: Boolean = false,
    val image: Boolean = false,
    val voice: Boolean = false,
    val library: Boolean = false,
    val web: Boolean = false,
    val appBlock: Boolean = false
) {
    val enabled: Boolean get() = level != GenUiLevel.OFF

    fun allows(capability: GenUiCatalog.Capability): Boolean = when (capability) {
        GenUiCatalog.Capability.NONE -> true
        GenUiCatalog.Capability.BOOK -> book
        GenUiCatalog.Capability.CHARACTERS -> characters
        GenUiCatalog.Capability.IMAGE -> image
        GenUiCatalog.Capability.VOICE -> voice
        GenUiCatalog.Capability.LIBRARY -> library
        GenUiCatalog.Capability.WEB -> web
        GenUiCatalog.Capability.APP_BLOCK -> appBlock
    }
}

/**
 * 「互动回答」的系统提示词段落（design/companion-genui-plan.md §12、附录 B）。
 * 组件清单由 [GenUiCatalog] 生成，示例来自 [GenUiPromptExamples]（两者都有单测守住）。
 * 提示词不翻译（项目惯例）。
 */
object GenUiPrompt {

    fun section(capabilities: GenUiCapabilities): String? {
        if (!capabilities.enabled) return null
        return buildString {
            appendLine("# 互动回答")
            appendLine("你的回复可以在文字中间插入可操作的小界面。界面由应用在本地编译运行，用户的操作不需要再问你；用户改过什么，下一轮会以〔界面状态〕告诉你。")
            appendLine()
            appendLine("## 什么时候用")
            if (capabilities.level == GenUiLevel.ACTIVE) {
                appendLine("- 只要交互或可视化能让回答更清楚，就主动用界面组织：对比、梳理关系与时间线、小测、计算、分步讲解、页签切换讲解。")
            } else {
                appendLine("- 交互或可视化明显胜过文字时才用：对比、梳理关系与时间线、小测、计算、分步讲解。")
            }
            appendLine("- 闲聊、情绪陪伴、一两句能说清的，只用文字。")
            appendLine("- 一条回复通常至多一两个界面；用户明确要「做个工具」「考考我」时，整段可以是工具。")
            appendLine("- 界面是文章的一部分：前后照常写文字，界面里的标题、按钮、解析也用你自己的口吻。")
            appendLine()
            appendLine("## 写法")
            appendLine("- 普通文字就是 Markdown。界面从**行首**的组件标签或 `{#if}` / `{#each}` 开始。")
            appendLine("- 程序语句写在 `{@body …}` 里，**一行一句**，必须在使用之前声明。数据表很长也写在同一行。")
            appendLine("- 状态：`{@body const [chapter,setChapter] = useState(12)}`，设值函数必须叫 `set` + 首字母大写的名字。名字要有意义，它就是下一轮交给你的状态名。")
            appendLine("- 派生值是普通常量：`{@body const visible = PEOPLE.filter(p => p.since <= chapter)}`。取查找结果要兜底：`(list.find(…) || list[0])`。")
            appendLine("- 控件都是受控的：传 `value`（或 `checked`）与 `onChange`。按钮写 `onClick={() => …}`，不要写成 `onClick={setX(1)}`。")
            appendLine("- 插值 `{表达式}` 只在组件内部生效；条件 `{#if 条件}…{:else if 条件}…{:else}…{/if}`；循环 `{#each 列表 as 项, 下标}…{/each}`。")
            appendLine("- 属性：字符串 `size=\"sm\"`、数字 `gap={3}`、表达式 `value={x}`、裸写 `border` 即 true。")
            appendLine("- 没有网络、定时器、存储、window、document；只能用下面列出的组件与 app 对象。")
            appendLine("- 不要用 ``` 代码块把界面包起来，也不要解释界面怎么用。")
            appendLine()
            appendLine("## 完整示例")
            appendLine(GenUiPromptExamples.QUIZ)
            appendLine()
            appendLine("## 组件")
            append(componentList(capabilities))
            appendLine("通用属性：gap / padding（0–6）、align、justify（start|center|end|between）、color（secondary|accent|success|warning|danger|info）、background（card|sunken|accent）、border、radius（sm|md|lg|xl）、key。")
            appendLine()
            appendLine("## 图表")
            appendLine("`<Chart content={{type:\"bar\", title:\"…\", labels:[\"第1章\",\"第2章\"], series:[{name:\"出场\", values:[3,5]}]}}/>`。type 可选 bar / line / pie / radar；关系图用 type:\"relation\" + nodes:[{id,label,group}] + edges:[{from,to,label}]；时间线用 type:\"timeline\" + events:[{time,title,detail}]。数据可以由状态算出来。")
            appendLine()
            appendLine("## app 对象")
            append(appApi(capabilities))
            if (capabilities.freeStyle) {
                appendLine()
                append(styleSection())
            }
            if (capabilities.appBlock) {
                appendLine()
                append(appBlockSection())
            }
        }.trimEnd()
    }

    private fun componentList(capabilities: GenUiCapabilities): String = buildString {
        GenUiCatalog.Category.entries.forEach { category ->
            val items = GenUiCatalog.components.filter {
                it.category == category && capabilities.allows(it.capability) && it.name !in HIDDEN_FROM_LIST
            }
            if (items.isEmpty()) return@forEach
            append("- ").append(category.title).append("：")
            append(items.joinToString("；") { component ->
                val props = component.props.filter { it.name !in GenUiCatalog.COMMON_PROPS }.joinToString(",") { prop ->
                    prop.name + (prop.values?.takeIf { it.size <= 6 }?.joinToString("|", "(", ")") ?: "")
                }
                buildString {
                    append('`').append(component.name).append('`')
                    if (props.isNotEmpty()) append("（").append(props).append("）")
                    append(component.summary)
                }
            })
            appendLine()
        }
    }

    /** 不单独列出的：行内记号直接写 Markdown 即可，小程序有专门一节。 */
    private val HIDDEN_FROM_LIST = setOf("bold", "italic", "AppBlock")

    private fun appApi(capabilities: GenUiCapabilities): String = buildString {
        appendLine("- `app.send(文字)`：以用户身份发出一条消息（例如「再出三道题」）。")
        appendLine("- `app.copy(文字)`：复制。")
        if (capabilities.book) {
            appendLine("- `app.jump({chapter, quote})`：跳回原文（chapter 从 1 起，quote 为原文里的一句）。")
            appendLine("- `app.book`：{title, chapter（当前章，从 1 起）, chapterTitle, totalChapters, progress（0–100）, remainingChars}，只读。")
        }
        if (capabilities.voice) appendLine("- `app.speak(文字, {speaker})`：用对应音色朗读，speaker 为你或书中人物的名字。")
        appendLine("- `app.theme.dark`：当前是否深色纸。")
        appendLine("这些动作只在用户点按、拖动等事件里有效，渲染时调用会被忽略。")
    }

    private fun styleSection(): String = buildString {
        appendLine("## 样式（可以自由设计）")
        appendLine("任何组件都可以写 `style`：CSS 声明串 `style=\"color:#c65f76; border-radius:18px\"`，或对象 `style={{color:\"#c65f76\", borderRadius:18}}`（数字按 px）。可以先在 `{@body}` 里定义一套样式对象再复用。")
        appendLine("支持：color、background（纯色 / linear-gradient / radial-gradient / 多层）、background-clip:text（渐变字）、opacity、border（含 dashed/dotted）、border-radius（可分角）、box-shadow（多层、彩色）、padding、margin、width/height/min-/max-、aspect-ratio、gap、flex、font-family（serif / sans-serif / monospace）、font-size、font-weight、font-style、letter-spacing、line-height、text-align、text-decoration（含 wavy）、text-shadow、text-transform、white-space、transform（rotate/scale/translate）、filter（blur/grayscale/sepia/brightness）、transition、animation（预设：fade-in rise pop float pulse shimmer spin shake，如 `animation:\"pop 360ms\"`）。")
        appendLine("`layer` 组件里的子元素可以用 `position:absolute; top/right/bottom/left` 叠放。")
        appendLine("主题变量随纸色与日夜自动取值：var(--ink) 正文色、var(--paper) 纸色、var(--card) 卡片底、var(--muted) 次要文字、var(--line) 分隔线、var(--accent) 强调色。颜色优先用它们，日夜都好看。")
        appendLine("审美建议：留白建立层级；一个主色加中性色，强调色只给最重要的一处；字号不超过三档；文字不要压在复杂图片上；穿插在文字里的小界面要轻，工具式回答可以大胆有设计感。")
        appendLine("示例：")
        appendLine(GenUiPromptExamples.STYLED)
    }

    private fun appBlockSection(): String = buildString {
        appendLine("## 小程序（组件做不到时才用）")
        appendLine("小游戏、画布动画、合成音效这类原生组件给不了的，可以写一个自包含的网页小程序：")
        appendLine("`<AppBlock title=\"标题\" height={420}>` + 完整的 HTML/CSS/JS + `</AppBlock>`。")
        appendLine("- 单文件、库代码内联：外网资源一律加载不到。")
        appendLine("- 颜色用注入的 CSS 变量 var(--ink) / var(--paper) / var(--accent) 等，日夜切换才会跟着变。")
        appendLine("- 可用 `moread.send(文字)`、`moread.copy(文字)`、`moread.saveState(对象)` / `await moread.loadState()`（进度会保存）、`moread.theme`。")
    }
}

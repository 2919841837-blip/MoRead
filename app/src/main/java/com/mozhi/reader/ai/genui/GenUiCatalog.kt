package com.mozhi.reader.ai.genui

/**
 * 互动界面的组件目录：**唯一真源**。
 *
 * 同一份声明同时用于三处，单测守住三者一致：
 * 1. [GenUiPrompt] 生成提示词里的组件清单（能力关着的组件不写进去）；
 * 2. [GenUiCompiler] 编译期校验（未知属性记诊断后丢弃，数值/布尔字面量转型）；
 * 3. 渲染层的组件注册表（目录里的每个名字都必须有渲染器）。
 *
 * 小写 = 基础组件；大写开头 = 宿主组件（数据由宿主解析、校验，模型只写描述或引用）。
 */
object GenUiCatalog {

    enum class Category(val title: String) {
        LAYOUT("布局"),
        SWITCH("切换"),
        TEXT("文字"),
        CONTROL("控件"),
        DATA("数据"),
        MEDIA("媒体"),
        HOST("宿主组件")
    }

    enum class PropType { STRING, NUMBER, BOOLEAN, HANDLER, ANY }

    /** 宿主能力：对应能力不可用时，组件不进提示词（渲染层仍能降级显示）。 */
    enum class Capability { NONE, BOOK, CHARACTERS, IMAGE, VOICE, LIBRARY, WEB, APP_BLOCK }

    data class Prop(
        val name: String,
        val type: PropType,
        /** 枚举取值；null 表示不限。 */
        val values: Set<String>? = null,
        /** 进提示词时的简短说明，空串表示不必单独解释。 */
        val hint: String = ""
    )

    data class Component(
        val name: String,
        val category: Category,
        val summary: String,
        val props: List<Prop> = emptyList(),
        /** false = 空元素：`<slider .../>`，写成未闭合的 `<divider>` 也立即闭合。 */
        val acceptsChildren: Boolean = true,
        /** 与常见 HTML 同名的标签：文档里没有 `{@body}` 时不把它当作互动岛的开头（见 [isWeakTag]）。 */
        val sharedWithHtml: Boolean = false,
        val capability: Capability = Capability.NONE,
        /** 子元素必须是这些组件（`tabs` > `tab`）；仅用于提示词与诊断。 */
        val childOf: String? = null,
        /** 行内文字组件：渲染时并入所在文字段落，而不是独立成块。 */
        val inline: Boolean = false
    ) {
        val isHost: Boolean get() = name.first().isUpperCase()
        fun prop(name: String): Prop? = props.firstOrNull { it.name == name } ?: COMMON_PROPS[name]
    }

    // 枚举取值放宽到 ChatGPT DIL 的写法（xs–2xl、surface 等），渲染层再映射到墨知令牌。
    private val SIZE = setOf("xs", "sm", "md", "lg", "xl", "2xl")
    private val TONE = setOf("default", "primary", "secondary", "tertiary", "accent", "success", "warning", "danger", "info", "muted")
    private val ALIGN = setOf("start", "center", "end", "stretch", "baseline", "left", "right")
    private val JUSTIFY = setOf("start", "center", "end", "between", "around", "evenly")
    private val RADIUS = setOf("none", "xs", "sm", "md", "lg", "xl", "2xl", "full")

    /** 所有组件都接受的属性。 */
    val COMMON_PROPS: Map<String, Prop> = listOf(
        Prop("key", PropType.ANY),
        Prop("style", PropType.ANY, hint = "自由样式，见「样式」"),
        Prop("gap", PropType.ANY, hint = "0–6 或 xs/sm/md/lg/xl"),
        Prop("padding", PropType.ANY, hint = "0–6 或 xs/sm/md/lg/xl"),
        Prop("align", PropType.STRING, ALIGN),
        Prop("justify", PropType.STRING, JUSTIFY),
        Prop("color", PropType.STRING, TONE),
        Prop("background", PropType.STRING, setOf("none", "card", "surface", "sunken", "accent")),
        Prop("border", PropType.BOOLEAN),
        Prop("radius", PropType.STRING, RADIUS),
        Prop("width", PropType.ANY),
        Prop("height", PropType.ANY),
        Prop("grow", PropType.NUMBER),
        Prop("hidden", PropType.BOOLEAN)
    ).associateBy(Prop::name)

    val components: List<Component> = listOf(
        // ---- 布局 ----
        Component("box", Category.LAYOUT, "纵向排列", listOf(Prop("wrap", PropType.BOOLEAN))),
        Component("row", Category.LAYOUT, "横向排列，wrap 换行", listOf(Prop("wrap", PropType.BOOLEAN))),
        Component("grid", Category.LAYOUT, "等宽网格", listOf(Prop("columns", PropType.NUMBER, hint = "1–3"))),
        Component("layer", Category.LAYOUT, "叠放容器，子元素可用 style 绝对定位"),
        Component("card", Category.LAYOUT, "卡片"),
        Component("scroller", Category.LAYOUT, "横向滑动的一排"),
        Component("divider", Category.LAYOUT, "分隔线", acceptsChildren = false),
        Component("spacer", Category.LAYOUT, "留白", listOf(Prop("size", PropType.NUMBER, hint = "0–6")), acceptsChildren = false),
        // ---- 切换 ----
        Component(
            "tabs", Category.SWITCH, "页签，子元素为 tab",
            listOf(Prop("value", PropType.ANY), Prop("onChange", PropType.HANDLER))
        ),
        Component(
            "tab", Category.SWITCH, "一个页签及其内容",
            listOf(Prop("value", PropType.ANY), Prop("label", PropType.STRING)), childOf = "tabs"
        ),
        Component(
            "disclosure", Category.SWITCH, "可展开的一段",
            listOf(Prop("label", PropType.STRING), Prop("open", PropType.BOOLEAN))
        ),
        // ---- 文字 ----
        Component("title", Category.TEXT, "标题", listOf(Prop("size", PropType.STRING, SIZE))),
        Component(
            "text", Category.TEXT, "正文段落，支持 **粗体** 等行内 Markdown",
            listOf(Prop("size", PropType.STRING, SIZE), Prop("weight", PropType.STRING, setOf("regular", "medium", "bold")))
        ),
        Component("caption", Category.TEXT, "说明小字"),
        Component("label", Category.TEXT, "标签文字", sharedWithHtml = true),
        Component("badge", Category.TEXT, "小徽标"),
        Component("icon", Category.TEXT, "图标", listOf(Prop("name", PropType.STRING), Prop("size", PropType.NUMBER)), acceptsChildren = false),
        Component("code", Category.TEXT, "等宽代码，内容按原样显示", sharedWithHtml = true),
        Component("blockquote", Category.TEXT, "引用样式块（不核对原文；要核对用 Quote）", sharedWithHtml = true),
        Component("link", Category.TEXT, "链接，点按经确认后打开", listOf(Prop("href", PropType.STRING)), sharedWithHtml = true, inline = true),
        Component("bold", Category.TEXT, "行内粗体", inline = true),
        Component("italic", Category.TEXT, "行内斜体", inline = true),
        // ---- 控件 ----
        Component(
            "button", Category.CONTROL, "按钮",
            listOf(
                Prop("onClick", PropType.HANDLER),
                Prop("variant", PropType.STRING, setOf("solid", "soft", "outline", "ghost", "primary", "secondary", "link")),
                Prop("disabled", PropType.BOOLEAN),
                Prop("icon", PropType.STRING),
                Prop("block", PropType.BOOLEAN),
                Prop("submit", PropType.BOOLEAN, hint = "在 form 里触发 onSubmit"),
                Prop("type", PropType.STRING, setOf("button", "submit"))
            ),
            sharedWithHtml = true
        ),
        Component(
            "segmented", Category.CONTROL, "分段选择",
            listOf(Prop("value", PropType.ANY), Prop("onChange", PropType.HANDLER), Prop("options", PropType.ANY, hint = "[{label,value}]")),
            acceptsChildren = false
        ),
        Component(
            "select", Category.CONTROL, "下拉选择（底部弹层）",
            listOf(
                Prop("value", PropType.ANY), Prop("onChange", PropType.HANDLER),
                Prop("options", PropType.ANY, hint = "[{label,value}]"), Prop("label", PropType.STRING)
            ),
            acceptsChildren = false, sharedWithHtml = true
        ),
        Component(
            "chips", Category.CONTROL, "多选胶囊，value 是数组",
            listOf(Prop("value", PropType.ANY), Prop("onChange", PropType.HANDLER), Prop("options", PropType.ANY, hint = "[{label,value}]")),
            acceptsChildren = false
        ),
        Component(
            "checkbox", Category.CONTROL, "勾选框，子元素是文字",
            listOf(Prop("checked", PropType.BOOLEAN), Prop("onChange", PropType.HANDLER), Prop("disabled", PropType.BOOLEAN))
        ),
        Component(
            "switch", Category.CONTROL, "开关，子元素是文字",
            listOf(Prop("checked", PropType.BOOLEAN), Prop("onChange", PropType.HANDLER), Prop("disabled", PropType.BOOLEAN))
        ),
        Component(
            "radio-group", Category.CONTROL, "单选组，子元素为 radio",
            listOf(Prop("value", PropType.ANY), Prop("onChange", PropType.HANDLER), Prop("disabled", PropType.BOOLEAN))
        ),
        Component("radio", Category.CONTROL, "单选项", listOf(Prop("value", PropType.ANY)), childOf = "radio-group"),
        Component(
            "slider", Category.CONTROL, "滑条",
            listOf(
                Prop("value", PropType.NUMBER), Prop("onChange", PropType.HANDLER),
                Prop("min", PropType.NUMBER), Prop("max", PropType.NUMBER), Prop("step", PropType.NUMBER),
                Prop("label", PropType.STRING), Prop("unit", PropType.STRING)
            ),
            acceptsChildren = false
        ),
        Component(
            "stepper", Category.CONTROL, "− 数字 + 步进",
            listOf(
                Prop("value", PropType.NUMBER), Prop("onChange", PropType.HANDLER),
                Prop("min", PropType.NUMBER), Prop("max", PropType.NUMBER), Prop("step", PropType.NUMBER),
                Prop("label", PropType.STRING), Prop("unit", PropType.STRING)
            ),
            acceptsChildren = false
        ),
        Component(
            "input", Category.CONTROL, "单行输入",
            listOf(
                Prop("value", PropType.ANY), Prop("onChange", PropType.HANDLER),
                Prop("placeholder", PropType.STRING), Prop("label", PropType.STRING),
                Prop("type", PropType.STRING, setOf("text", "number"))
            ),
            acceptsChildren = false, sharedWithHtml = true
        ),
        Component(
            "textarea", Category.CONTROL, "多行输入",
            listOf(
                Prop("value", PropType.ANY), Prop("onChange", PropType.HANDLER),
                Prop("placeholder", PropType.STRING), Prop("label", PropType.STRING), Prop("rows", PropType.NUMBER)
            ),
            acceptsChildren = false, sharedWithHtml = true
        ),
        Component("form", Category.CONTROL, "表单，onSubmit 由带 submit 的按钮触发", listOf(Prop("onSubmit", PropType.HANDLER)), sharedWithHtml = true),
        // ---- 数据 ----
        Component("table", Category.DATA, "表格，子元素为 table-row", sharedWithHtml = true),
        Component("table-row", Category.DATA, "表格行，header 为表头", listOf(Prop("header", PropType.BOOLEAN)), childOf = "table"),
        Component("table-cell", Category.DATA, "单元格", listOf(Prop("align", PropType.STRING, ALIGN)), childOf = "table-row"),
        Component("list", Category.DATA, "列表，子元素为 list-item", listOf(Prop("marker", PropType.STRING, setOf("bullet", "number", "none")))),
        Component("list-item", Category.DATA, "列表项", childOf = "list"),
        Component(
            "progress", Category.DATA, "进度条",
            listOf(Prop("value", PropType.NUMBER), Prop("max", PropType.NUMBER), Prop("label", PropType.STRING)),
            acceptsChildren = false, sharedWithHtml = true
        ),
        Component(
            "stat", Category.DATA, "大数字 + 标签",
            listOf(Prop("value", PropType.ANY), Prop("label", PropType.STRING), Prop("unit", PropType.STRING)),
            acceptsChildren = false
        ),
        Component("steps", Category.DATA, "分步清单，子元素为 step", listOf(Prop("value", PropType.ANY), Prop("onChange", PropType.HANDLER))),
        Component(
            "step", Category.DATA, "一步，可勾选",
            listOf(Prop("title", PropType.STRING), Prop("done", PropType.BOOLEAN), Prop("onToggle", PropType.HANDLER)),
            childOf = "steps"
        ),
        Component("timeline", Category.DATA, "时间线，子元素为 event"),
        Component("event", Category.DATA, "时间线上一项", listOf(Prop("time", PropType.STRING), Prop("title", PropType.STRING)), childOf = "timeline"),
        // ---- 媒体 ----
        Component(
            "image", Category.MEDIA, "图片：只接受 asset:图片库编号 或 本条回复生成的插图",
            listOf(Prop("src", PropType.STRING), Prop("alt", PropType.STRING), Prop("aspect", PropType.STRING)),
            acceptsChildren = false
        ),
        // ---- 宿主组件 ----
        Component(
            "Quote", Category.HOST, "原文引用：应用在已读部分核对后显示，点按跳回原文",
            listOf(
                Prop("chapter", PropType.NUMBER, hint = "章序号，从 1 起"),
                Prop("text", PropType.STRING, hint = "原文中的一句，逐字"),
                Prop("note", PropType.STRING)
            ),
            acceptsChildren = false, capability = Capability.BOOK
        ),
        Component(
            "Character", Category.HOST, "书中人物胶囊，点按看资料",
            listOf(Prop("name", PropType.STRING)), acceptsChildren = false, capability = Capability.CHARACTERS
        ),
        Component(
            "Chart", Category.HOST, "图表",
            listOf(Prop("content", PropType.ANY, hint = "图表规格对象，见「图表」")), acceptsChildren = false
        ),
        Component(
            "Illustration", Category.HOST, "插图：写场景描述，应用按本书画风生成",
            listOf(
                Prop("scene", PropType.STRING), Prop("characters", PropType.ANY),
                Prop("aspect", PropType.STRING, setOf("1:1", "3:4", "4:3", "16:9", "9:16"))
            ),
            acceptsChildren = false, capability = Capability.IMAGE
        ),
        Component(
            "Voice", Category.HOST, "语音条，点按用对应音色朗读",
            listOf(Prop("text", PropType.STRING), Prop("speaker", PropType.STRING)),
            acceptsChildren = false, capability = Capability.VOICE
        ),
        Component(
            "Line", Category.HOST, "带头像的一句台词，voice 时可朗读",
            listOf(Prop("who", PropType.STRING), Prop("voice", PropType.BOOLEAN)), capability = Capability.VOICE
        ),
        Component(
            "Book", Category.HOST, "书库里的一本书", listOf(Prop("id", PropType.NUMBER)),
            acceptsChildren = false, capability = Capability.LIBRARY
        ),
        Component(
            "Source", Category.HOST, "联网来源", listOf(Prop("url", PropType.STRING), Prop("title", PropType.STRING)),
            acceptsChildren = false, capability = Capability.WEB
        ),
        Component(
            "AppBlock", Category.HOST, "网页小程序（HTML/CSS/JS 单文件），组件做不到时才用",
            listOf(Prop("title", PropType.STRING), Prop("height", PropType.NUMBER)), capability = Capability.APP_BLOCK
        )
    )

    private val byName: Map<String, Component> = components.associateBy(Component::name)

    /** 模型常写的别名（ChatGPT DIL、HTML、React 习惯）→ 目录名。 */
    val ALIASES: Map<String, String> = mapOf(
        "column" to "box",
        "vstack" to "box",
        "stack" to "box",
        "hstack" to "row",
        "grid-item" to "box",
        "segmented-control" to "segmented",
        "b" to "bold",
        "strong" to "bold",
        "i" to "italic",
        "em" to "italic",
        "a" to "link",
        "hr" to "divider",
        "ul" to "list",
        "ol" to "list",
        "li" to "list-item",
        "pre" to "code",
        "img" to "image",
        "toggle" to "switch",
        "MemoryCite" to "Quote"
    )

    fun resolve(name: String): Component? = byName[name] ?: ALIASES[name]?.let(byName::get) ?: fuzzyHost(name)

    fun canonicalName(name: String): String? = resolve(name)?.name

    /**
     * 与 HTML 同名、在普通回答里也常见的标签（table / code / blockquote / button…）。
     * 文档里没有任何 `{@body}` 时，它们开头的行按普通 Markdown 处理，交给现有 HTML 白名单转换，
     * 避免把模型随手写的 HTML 表格当成互动岛。
     */
    fun isWeakTag(name: String): Boolean {
        if (name in HTML_ALIASES) return true
        return resolve(name)?.sharedWithHtml == true
    }

    private val HTML_ALIASES = setOf("b", "strong", "i", "em", "a", "hr", "ul", "ol", "li", "pre", "img")

    /** 内容按原样显示、不解析花括号与标签的组件。 */
    fun isOpaque(name: String): Boolean = canonicalName(name) == "code"

    /** 宿主组件名拼错一个字母（`<Qoute>`）时纠正；只对大写开头的名字生效，避免误伤正文。 */
    private fun fuzzyHost(name: String): Component? {
        if (name.isEmpty() || !name.first().isUpperCase() || name.length < 4) return null
        return components.firstOrNull { it.isHost && editDistance(it.name, name) == 1 }
    }

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    dp[i][j] = minOf(dp[i][j], dp[i - 2][j - 2] + 1)
                }
            }
        }
        return dp[a.length][b.length]
    }
}

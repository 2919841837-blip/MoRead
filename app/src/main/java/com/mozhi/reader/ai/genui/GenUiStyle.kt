package com.mozhi.reader.ai.genui

import androidx.compose.runtime.Immutable
import com.mozhi.reader.core.datastore.ReaderStyleCss
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * 互动卡片的「自由样式」（design/companion-genui-plan.md §5.4）。
 *
 * 模型可以写 CSS 声明串（style="color:#c65f76; border-radius:18px"）或 JS 对象
 * （style={{color:"#c65f76", borderRadius:18}}，数字按 dp）。这里只做**解析**成平台无关的
 * 描述，主题变量（var(--ink) 等）保留为引用，到渲染时按当前纸色解析；对比度校正与夜间适配
 * 也在渲染层做。不认识的属性与写错的值只丢自己。
 */

@Immutable
sealed interface StyleColor {
    data class Literal(val argb: Int) : StyleColor

    /** 主题变量：ink / paper / accent / muted / card / line / seal；[alpha] 来自 color-mix 之类的写法时才有。 */
    data class Theme(val name: String, val alpha: Float? = null) : StyleColor
}

@Immutable
data class GradientStop(val color: StyleColor, val position: Float?)

@Immutable
sealed interface StylePaint {
    data class Solid(val color: StyleColor) : StylePaint

    /** [angle] 为 CSS 角度：0deg 向上、90deg 向右。 */
    data class Linear(val angle: Float, val stops: List<GradientStop>) : StylePaint

    /** 圆心为相对坐标（0–1）；[radius] 为相对较长边的比例，null = 到最远角。 */
    data class Radial(val centerX: Float, val centerY: Float, val radius: Float?, val stops: List<GradientStop>) : StylePaint

    /** 只接受宿主引用：asset:图片库编号 / gen:本条回复生成的插图。 */
    data class Image(val src: String) : StylePaint
}

enum class StyleUnit { DP, PERCENT, EM }

@Immutable
data class StyleLength(val value: Float, val unit: StyleUnit) {
    companion object {
        fun dp(value: Float) = StyleLength(value, StyleUnit.DP)
    }
}

@Immutable
data class StyleShadow(
    val dx: Float,
    val dy: Float,
    val blur: Float,
    val spread: Float,
    val color: StyleColor,
    val inset: Boolean
)

@Immutable
data class StyleEdges(val top: Float? = null, val right: Float? = null, val bottom: Float? = null, val left: Float? = null) {
    val isEmpty: Boolean get() = top == null && right == null && bottom == null && left == null

    fun merge(other: StyleEdges) = StyleEdges(other.top ?: top, other.right ?: right, other.bottom ?: bottom, other.left ?: left)

    companion object {
        fun all(v: Float) = StyleEdges(v, v, v, v)
    }
}

@Immutable
data class StyleCorners(val topStart: Float? = null, val topEnd: Float? = null, val bottomEnd: Float? = null, val bottomStart: Float? = null) {
    val isEmpty: Boolean get() = topStart == null && topEnd == null && bottomEnd == null && bottomStart == null

    fun merge(other: StyleCorners) = StyleCorners(
        other.topStart ?: topStart, other.topEnd ?: topEnd, other.bottomEnd ?: bottomEnd, other.bottomStart ?: bottomStart
    )

    companion object {
        fun all(v: Float) = StyleCorners(v, v, v, v)
    }
}

enum class BorderStyle { SOLID, DASHED, DOTTED, DOUBLE, NONE }

@Immutable
data class StyleBorder(val width: Float, val color: StyleColor?, val style: BorderStyle)

@Immutable
data class StyleTransform(
    val rotate: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val skewX: Float = 0f
)

@Immutable
data class StyleFilter(val blur: Float = 0f, val grayscale: Float = 0f, val sepia: Float = 0f, val brightness: Float = 1f)

@Immutable
data class StyleTransition(val properties: Set<String>, val durationMs: Int, val easing: String)

/** 原生层不解析任意 @keyframes，只提供预设（完全自定义的动画用小程序）。 */
enum class AnimationPreset { FADE_IN, RISE, POP, FLOAT, PULSE, SHIMMER, SPIN, SHAKE, TYPEWRITER }

@Immutable
data class StyleAnimation(val preset: AnimationPreset, val durationMs: Int, val iterations: Int /* -1 = 无限 */, val delayMs: Int)

@Immutable
data class GenUiStyle(
    val color: StyleColor? = null,
    /** background-clip:text 时的文字渐变。 */
    val textPaint: StylePaint? = null,
    /** 自上而下的背景层（CSS 多层背景第一层在最上）。 */
    val backgrounds: List<StylePaint> = emptyList(),
    val opacity: Float? = null,
    val border: StyleBorder? = null,
    val borderSides: Map<Char, StyleBorder> = emptyMap(),
    val radius: StyleCorners = StyleCorners(),
    val padding: StyleEdges = StyleEdges(),
    val margin: StyleEdges = StyleEdges(),
    val width: StyleLength? = null,
    val height: StyleLength? = null,
    val minWidth: StyleLength? = null,
    val minHeight: StyleLength? = null,
    val maxWidth: StyleLength? = null,
    val maxHeight: StyleLength? = null,
    val aspectRatio: Float? = null,
    val gap: Float? = null,
    val flex: Float? = null,
    val alignSelf: String? = null,
    val overflowHidden: Boolean = false,
    val shadows: List<StyleShadow> = emptyList(),
    val fontFamily: String? = null,
    val fontSize: StyleLength? = null,
    val fontWeight: Int? = null,
    val italic: Boolean? = null,
    val letterSpacing: StyleLength? = null,
    val lineHeight: StyleLength? = null,
    val textAlign: String? = null,
    val underline: Boolean? = null,
    val lineThrough: Boolean? = null,
    val decorationStyle: String? = null,
    val textShadow: StyleShadow? = null,
    val textTransform: String? = null,
    val noWrap: Boolean = false,
    val position: String? = null,
    val inset: StyleEdges = StyleEdges(),
    val zIndex: Float? = null,
    val transform: StyleTransform? = null,
    val filter: StyleFilter? = null,
    val transition: StyleTransition? = null,
    val animation: StyleAnimation? = null
) {
    val isEmpty: Boolean get() = this == EMPTY

    /** 自带背景（模型自己设计了卡片）：岛外壳据此退为透明。 */
    val hasOwnSurface: Boolean get() = backgrounds.isNotEmpty() || border != null || shadows.isNotEmpty()

    companion object {
        val EMPTY = GenUiStyle()
    }
}

object GenUiStyleParser {

    private val cache = object : LinkedHashMap<String, GenUiStyle>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GenUiStyle>?): Boolean = size > 512
    }

    /** `style` 属性：字符串或对象；null / 其它类型返回空样式。 */
    fun parse(element: JsonElement?): GenUiStyle {
        return when (element) {
            null, is JsonNull -> GenUiStyle.EMPTY
            is JsonPrimitive -> if (element.isString) parseCss(element.content) else GenUiStyle.EMPTY
            is JsonObject -> parseCss(element.toCss())
            else -> GenUiStyle.EMPTY
        }
    }

    fun parseCss(css: String): GenUiStyle {
        if (css.isBlank()) return GenUiStyle.EMPTY
        synchronized(cache) { cache[css] }?.let { return it }
        val parsed = Builder().apply { declarations(css).forEach { (k, v) -> runCatching { apply(k, v) } } }.build()
        synchronized(cache) { cache[css] = parsed }
        return parsed
    }

    /** 对象写法 → CSS：驼峰键转连字符，纯数字按 px（=dp）。不需要单位的属性保留裸数字。 */
    private fun JsonObject.toCss(): String = entries.joinToString(";") { (key, value) ->
        val name = key.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
        val text = when (value) {
            is JsonPrimitive -> {
                val number = if (value.isString) null else value.doubleOrNull
                when {
                    number != null && name in UNITLESS -> GenUiCompiler.numberLiteral(number)
                    number != null -> GenUiCompiler.numberLiteral(number) + "px"
                    else -> value.contentOrNull.orEmpty()
                }
            }
            else -> ""
        }
        "$name:$text"
    }

    private val UNITLESS = setOf("opacity", "flex", "flex-grow", "z-index", "font-weight", "line-height", "aspect-ratio", "scale")

    /** 按分号切声明，括号与引号里的分号不切。 */
    private fun declarations(css: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val cleaned = css.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        var depth = 0
        var quote: Char? = null
        val current = StringBuilder()
        fun flush() {
            val entry = current.toString().trim()
            current.setLength(0)
            if (entry.isEmpty()) return
            val colon = entry.indexOf(':')
            if (colon <= 0) return
            val key = entry.substring(0, colon).trim().lowercase()
            val value = entry.substring(colon + 1).trim().removeSuffix("!important").trim()
            if (value.isNotEmpty()) out += key to value
        }
        for (c in cleaned) {
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    current.append(c)
                }
                c == '(' -> {
                    depth++
                    current.append(c)
                }
                c == ')' -> {
                    depth--
                    current.append(c)
                }
                c == ';' && depth <= 0 -> flush()
                else -> current.append(c)
            }
        }
        flush()
        return out
    }

    private class Builder {
        var s = GenUiStyle()

        fun build(): GenUiStyle = s

        fun apply(key: String, value: String) {
            val lower = value.lowercase()
            s = when (key) {
                "color", "-webkit-text-fill-color" -> {
                    if (lower == "transparent" && key == "-webkit-text-fill-color") s
                    else if (lower.contains("gradient(")) s.copy(textPaint = paint(value))
                    else s.copy(color = color(value) ?: return)
                }
                "background", "background-image" -> {
                    if (lower == "none") return run { s = s.copy(backgrounds = emptyList()) }
                    val layers = splitTopLevel(value, ',').let { parts ->
                        // 渐变参数里有逗号：按顶层逗号切后，只有以函数名或颜色开头的段才是独立层
                        regroupLayers(parts)
                    }.mapNotNull { paint(it) }
                    if (layers.isEmpty()) return
                    s.copy(backgrounds = layers)
                }
                "background-color" -> s.copy(backgrounds = s.backgrounds.filterNot { it is StylePaint.Solid } + StylePaint.Solid(color(value) ?: return))
                "background-clip", "-webkit-background-clip" -> if (lower == "text") {
                    val gradient = s.backgrounds.firstOrNull { it !is StylePaint.Solid } ?: return
                    s.copy(textPaint = gradient, backgrounds = s.backgrounds - gradient)
                } else s
                "opacity" -> s.copy(opacity = number(value)?.coerceIn(0f, 1f) ?: return)
                "border" -> s.copy(border = border(value) ?: return)
                "border-top", "border-right", "border-bottom", "border-left" -> {
                    val side = key.removePrefix("border-").first()
                    s.copy(borderSides = s.borderSides + (side to (border(value) ?: return)))
                }
                "border-width" -> s.copy(border = (s.border ?: StyleBorder(1f, null, BorderStyle.SOLID)).copy(width = length(value)?.value ?: return))
                "border-color" -> s.copy(border = (s.border ?: StyleBorder(1f, null, BorderStyle.SOLID)).copy(color = color(value) ?: return))
                "border-style" -> s.copy(border = (s.border ?: StyleBorder(1f, null, BorderStyle.SOLID)).copy(style = borderStyle(lower) ?: return))
                "border-radius", "radius" -> s.copy(radius = s.radius.merge(corners(value) ?: return))
                "border-top-left-radius" -> s.copy(radius = s.radius.merge(StyleCorners(topStart = dp(value) ?: return)))
                "border-top-right-radius" -> s.copy(radius = s.radius.merge(StyleCorners(topEnd = dp(value) ?: return)))
                "border-bottom-right-radius" -> s.copy(radius = s.radius.merge(StyleCorners(bottomEnd = dp(value) ?: return)))
                "border-bottom-left-radius" -> s.copy(radius = s.radius.merge(StyleCorners(bottomStart = dp(value) ?: return)))
                "padding" -> s.copy(padding = edges(value) ?: return)
                "padding-top", "padding-right", "padding-bottom", "padding-left" -> s.copy(padding = s.padding.merge(side(key, value) ?: return))
                "padding-inline", "padding-x" -> dp(value)?.let { s.copy(padding = s.padding.merge(StyleEdges(left = it, right = it))) } ?: return
                "padding-block", "padding-y" -> dp(value)?.let { s.copy(padding = s.padding.merge(StyleEdges(top = it, bottom = it))) } ?: return
                "margin" -> s.copy(margin = edges(value) ?: return)
                "margin-top", "margin-right", "margin-bottom", "margin-left" -> s.copy(margin = s.margin.merge(side(key, value) ?: return))
                "width" -> s.copy(width = length(value) ?: return)
                "height" -> s.copy(height = length(value) ?: return)
                "min-width" -> s.copy(minWidth = length(value) ?: return)
                "min-height" -> s.copy(minHeight = length(value) ?: return)
                "max-width" -> s.copy(maxWidth = length(value) ?: return)
                "max-height" -> s.copy(maxHeight = length(value) ?: return)
                "aspect-ratio" -> s.copy(aspectRatio = ratio(value) ?: return)
                "gap", "row-gap", "column-gap" -> s.copy(gap = dp(value) ?: return)
                "flex", "flex-grow" -> s.copy(flex = value.trim().split(Regex("\\s+")).first().toFloatOrNull()?.takeIf { it >= 0f } ?: return)
                "align-self" -> s.copy(alignSelf = lower)
                "overflow" -> s.copy(overflowHidden = lower == "hidden" || lower == "clip")
                "box-shadow" -> s.copy(shadows = if (lower == "none") emptyList() else splitTopLevel(value, ',').mapNotNull { shadow(it) }.take(4))
                "font-family" -> s.copy(fontFamily = genericFamily(splitTopLevel(value, ',').first().trim().trim('"', '\'')))
                "font-size" -> s.copy(fontSize = length(value)?.takeIf { it.unit != StyleUnit.PERCENT } ?: return)
                "font-weight" -> s.copy(fontWeight = when (lower) {
                    "bold", "bolder" -> 700
                    "normal" -> 400
                    "lighter" -> 300
                    else -> lower.toIntOrNull()?.coerceIn(100, 900) ?: return
                })
                "font-style" -> s.copy(italic = lower == "italic" || lower == "oblique")
                "letter-spacing" -> s.copy(letterSpacing = length(value) ?: return)
                "line-height" -> s.copy(lineHeight = value.toFloatOrNull()?.let { StyleLength(it, StyleUnit.EM) } ?: length(value) ?: return)
                "text-align" -> s.copy(textAlign = lower)
                "text-decoration", "text-decoration-line" -> s.copy(
                    underline = "underline" in lower,
                    lineThrough = "line-through" in lower,
                    decorationStyle = listOf("wavy", "dashed", "dotted", "double").firstOrNull { it in lower } ?: s.decorationStyle
                )
                "text-decoration-style" -> s.copy(decorationStyle = lower)
                "text-shadow" -> s.copy(textShadow = if (lower == "none") null else shadow(splitTopLevel(value, ',').first()))
                "text-transform" -> s.copy(textTransform = lower)
                "white-space" -> s.copy(noWrap = lower == "nowrap" || lower == "pre")
                "position" -> s.copy(position = lower)
                "top", "right", "bottom", "left" -> s.copy(inset = s.inset.merge(side("inset-$key", value) ?: return))
                "inset" -> s.copy(inset = edges(value) ?: return)
                "z-index" -> s.copy(zIndex = value.toFloatOrNull() ?: return)
                "transform" -> s.copy(transform = transform(value) ?: return)
                "rotate" -> s.copy(transform = (s.transform ?: StyleTransform()).copy(rotate = angle(value) ?: return))
                "scale" -> s.copy(transform = (s.transform ?: StyleTransform()).copy(scaleX = value.toFloatOrNull() ?: return, scaleY = value.toFloatOrNull() ?: return))
                "filter" -> s.copy(filter = filter(value) ?: return)
                "transition" -> s.copy(transition = transition(value) ?: return)
                "animation" -> s.copy(animation = animation(value) ?: return)
                else -> s
            }
        }

        private fun side(key: String, value: String): StyleEdges? {
            val v = dp(value) ?: return null
            return when (key.substringAfterLast('-')) {
                "top" -> StyleEdges(top = v)
                "right" -> StyleEdges(right = v)
                "bottom" -> StyleEdges(bottom = v)
                "left" -> StyleEdges(left = v)
                else -> null
            }
        }
    }

    /* ---------------------------- 值解析 ---------------------------- */

    /** 模型常写的中文字体名归到通用族（衬线 = 宋/明/楷，无衬线 = 黑体/苹方/雅黑）。 */
    private fun genericFamily(name: String): String = when (name.lowercase()) {
        "宋体", "songti", "songti sc", "明朝", "noto serif sc", "source han serif", "思源宋体", "楷体", "kaiti" -> "serif"
        "黑体", "heiti", "苹方", "pingfang sc", "微软雅黑", "microsoft yahei", "思源黑体", "noto sans sc" -> "sans-serif"
        else -> name
    }

    private val THEME_VAR = Regex("^var\\(\\s*--([a-z-]+)\\s*(?:,[^)]*)?\\)$", RegexOption.IGNORE_CASE)
    private val THEME_NAMES = setOf("ink", "paper", "accent", "muted", "card", "line", "seal", "text", "background", "surface")

    fun color(raw: String): StyleColor? {
        val value = raw.trim()
        THEME_VAR.find(value)?.let { m ->
            val name = m.groupValues[1].lowercase().let {
                when (it) {
                    "text" -> "ink"
                    "background" -> "paper"
                    "surface" -> "card"
                    else -> it
                }
            }
            return if (name in THEME_NAMES) StyleColor.Theme(name) else null
        }
        if (value.startsWith("hsl", ignoreCase = true)) return hsl(value)
        val named = NAMED_COLORS[value.lowercase()]
        if (named != null) return StyleColor.Literal(named)
        return ReaderStyleCss.color(value.replace(" ", ""))?.let(StyleColor::Literal)
    }

    private fun hsl(value: String): StyleColor? {
        if (!value.endsWith(')')) return null
        val parts = value.substringAfter('(').dropLast(1).split(',', ' ', '/').map(String::trim).filter(String::isNotEmpty)
        if (parts.size < 3) return null
        val h = parts[0].removeSuffix("deg").toFloatOrNull() ?: return null
        val sat = parts[1].removeSuffix("%").toFloatOrNull()?.div(100f) ?: return null
        val l = parts[2].removeSuffix("%").toFloatOrNull()?.div(100f) ?: return null
        val a = parts.getOrNull(3)?.let { p -> if (p.endsWith('%')) p.dropLast(1).toFloatOrNull()?.div(100f) else p.toFloatOrNull() } ?: 1f
        return StyleColor.Literal(hslToArgb(h, sat.coerceIn(0f, 1f), l.coerceIn(0f, 1f), a.coerceIn(0f, 1f)))
    }

    fun hslToArgb(h: Float, s: Float, l: Float, a: Float = 1f): Int {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val hp = ((h % 360 + 360) % 360) / 60f
        val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0f)
            hp < 2 -> Triple(x, c, 0f)
            hp < 3 -> Triple(0f, c, x)
            hp < 4 -> Triple(0f, x, c)
            hp < 5 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return ((a * 255).toInt().coerceIn(0, 255) shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }

    fun paint(raw: String): StylePaint? {
        val value = raw.trim()
        val lower = value.lowercase()
        return when {
            lower.startsWith("linear-gradient(") || lower.startsWith("repeating-linear-gradient(") -> linear(value)
            lower.startsWith("radial-gradient(") || lower.startsWith("repeating-radial-gradient(") -> radial(value)
            lower.startsWith("url(") -> {
                val url = value.substringAfter('(').substringBeforeLast(')').trim().trim('"', '\'')
                if (url.startsWith("asset:") || url.startsWith("gen:")) StylePaint.Image(url) else null
            }
            else -> color(value)?.let(StylePaint::Solid)
        }
    }

    private fun linear(value: String): StylePaint? {
        val args = splitTopLevel(value.substringAfter('(').substringBeforeLast(')'), ',')
        if (args.isEmpty()) return null
        var angle = 180f
        var stopsStart = 0
        val first = args[0].trim().lowercase()
        if (first.endsWith("deg") || first.endsWith("turn") || first.endsWith("rad")) {
            angle = angle(first) ?: 180f
            stopsStart = 1
        } else if (first.startsWith("to ")) {
            angle = when (first.removePrefix("to ").trim()) {
                "top" -> 0f
                "right" -> 90f
                "bottom" -> 180f
                "left" -> 270f
                "top right", "right top" -> 45f
                "bottom right", "right bottom" -> 135f
                "bottom left", "left bottom" -> 225f
                "top left", "left top" -> 315f
                else -> 180f
            }
            stopsStart = 1
        }
        val stops = stops(args.drop(stopsStart)) ?: return null
        return StylePaint.Linear(angle, stops)
    }

    private fun radial(value: String): StylePaint? {
        val args = splitTopLevel(value.substringAfter('(').substringBeforeLast(')'), ',')
        if (args.isEmpty()) return null
        var cx = 0.5f
        var cy = 0.5f
        var start = 0
        val first = args[0].trim().lowercase()
        val isShape = first.startsWith("circle") || first.startsWith("ellipse") || first.startsWith("at ") ||
            first.startsWith("closest") || first.startsWith("farthest")
        if (isShape) {
            val at = first.substringAfter("at ", "").trim()
            if (at.isNotEmpty()) {
                val parts = at.split(Regex("\\s+"))
                cx = position(parts.getOrNull(0), horizontal = true) ?: 0.5f
                cy = position(parts.getOrNull(1) ?: "center", horizontal = false) ?: 0.5f
            }
            start = 1
        }
        val stops = stops(args.drop(start)) ?: return null
        return StylePaint.Radial(cx, cy, null, stops)
    }

    private fun position(token: String?, horizontal: Boolean): Float? = when (token?.lowercase()) {
        null -> null
        "center" -> 0.5f
        "left" -> 0f
        "right" -> 1f
        "top" -> 0f
        "bottom" -> 1f
        else -> token.removeSuffix("%").toFloatOrNull()?.div(100f)?.takeIf { token.endsWith("%") }
    }

    private fun stops(parts: List<String>): List<GradientStop>? {
        val out = parts.mapNotNull { part ->
            val trimmed = part.trim()
            // 颜色在前、位置在后：`#fff 20%`、`var(--card) 60%`、`rgba(0,0,0,.4) 50%`
            val tokens = splitTopLevel(trimmed, ' ').filter { it.isNotBlank() }
            if (tokens.isEmpty()) return@mapNotNull null
            val colorToken = color(tokens[0]) ?: return@mapNotNull null
            val position = tokens.getOrNull(1)?.let { t -> t.removeSuffix("%").toFloatOrNull()?.div(100f)?.takeIf { t.endsWith("%") } }
            GradientStop(colorToken, position?.coerceIn(0f, 1f))
        }
        return out.takeIf { it.size >= 2 }
    }

    private fun regroupLayers(parts: List<String>): List<String> = parts.map(String::trim).filter(String::isNotEmpty)

    /** 顶层（不在括号、引号里）切分。 */
    fun splitTopLevel(value: String, separator: Char): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        val current = StringBuilder()
        for (c in value) {
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    current.append(c)
                }
                c == '(' -> {
                    depth++
                    current.append(c)
                }
                c == ')' -> {
                    depth--
                    current.append(c)
                }
                c == separator && depth == 0 -> {
                    out += current.toString()
                    current.setLength(0)
                }
                separator == ' ' && c.isWhitespace() && depth == 0 -> {
                    if (current.isNotEmpty()) out += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    fun length(raw: String): StyleLength? {
        val v = raw.trim().lowercase()
        if (v == "auto" || v.isEmpty()) return null
        return when {
            v.endsWith("px") -> v.dropLast(2).toFloatOrNull()?.let { StyleLength(it, StyleUnit.DP) }
            v.endsWith("dp") -> v.dropLast(2).toFloatOrNull()?.let { StyleLength(it, StyleUnit.DP) }
            v.endsWith("rem") -> v.dropLast(3).toFloatOrNull()?.let { StyleLength(it, StyleUnit.EM) }
            v.endsWith("em") -> v.dropLast(2).toFloatOrNull()?.let { StyleLength(it, StyleUnit.EM) }
            v.endsWith("%") -> v.dropLast(1).toFloatOrNull()?.let { StyleLength(it / 100f, StyleUnit.PERCENT) }
            v.endsWith("vw") -> v.dropLast(2).toFloatOrNull()?.let { StyleLength(it / 100f, StyleUnit.PERCENT) }
            else -> v.toFloatOrNull()?.let { StyleLength(it, StyleUnit.DP) }
        }?.takeIf { it.value.isFinite() }
    }

    private fun dp(raw: String): Float? = length(raw)?.let { if (it.unit == StyleUnit.EM) it.value * 14f else if (it.unit == StyleUnit.DP) it.value else null }

    private fun number(raw: String): Float? = raw.trim().let { v ->
        if (v.endsWith("%")) v.dropLast(1).toFloatOrNull()?.div(100f) else v.toFloatOrNull()
    }

    private fun ratio(raw: String): Float? {
        val v = raw.trim()
        if ('/' in v) {
            val (a, b) = v.split('/').map { it.trim().toFloatOrNull() }.let { it.getOrNull(0) to it.getOrNull(1) }
            if (a != null && b != null && b > 0f) return a / b
            return null
        }
        if (':' in v) {
            val parts = v.split(':').map { it.trim().toFloatOrNull() }
            val a = parts.getOrNull(0)
            val b = parts.getOrNull(1)
            if (a != null && b != null && b > 0f) return a / b
            return null
        }
        return v.toFloatOrNull()?.takeIf { it > 0f }
    }

    private fun edges(raw: String): StyleEdges? {
        val parts = splitTopLevel(raw, ' ').mapNotNull { dp(it) }
        return when (parts.size) {
            1 -> StyleEdges.all(parts[0])
            2 -> StyleEdges(parts[0], parts[1], parts[0], parts[1])
            3 -> StyleEdges(parts[0], parts[1], parts[2], parts[1])
            4 -> StyleEdges(parts[0], parts[1], parts[2], parts[3])
            else -> null
        }
    }

    private fun corners(raw: String): StyleCorners? {
        val parts = splitTopLevel(raw.substringBefore('/'), ' ').mapNotNull { token ->
            if (token.trim().endsWith("%")) token.trim().dropLast(1).toFloatOrNull()?.let { if (it >= 50f) 999f else it } else dp(token)
        }
        return when (parts.size) {
            1 -> StyleCorners.all(parts[0])
            2 -> StyleCorners(parts[0], parts[1], parts[0], parts[1])
            3 -> StyleCorners(parts[0], parts[1], parts[2], parts[1])
            4 -> StyleCorners(parts[0], parts[1], parts[2], parts[3])
            else -> null
        }
    }

    private fun borderStyle(lower: String): BorderStyle? = when (lower) {
        "solid" -> BorderStyle.SOLID
        "dashed" -> BorderStyle.DASHED
        "dotted" -> BorderStyle.DOTTED
        "double" -> BorderStyle.DOUBLE
        "none", "hidden" -> BorderStyle.NONE
        else -> null
    }

    private fun border(raw: String): StyleBorder? {
        if (raw.trim().equals("none", true) || raw.trim() == "0") return StyleBorder(0f, null, BorderStyle.NONE)
        var width = 1f
        var color: StyleColor? = null
        var style = BorderStyle.SOLID
        splitTopLevel(raw, ' ').forEach { token ->
            val t = token.trim()
            borderStyle(t.lowercase())?.let { style = it; return@forEach }
            dp(t)?.let { width = it; return@forEach }
            color(t)?.let { color = it }
        }
        return StyleBorder(width, color, style)
    }

    private fun shadow(raw: String): StyleShadow? {
        val tokens = splitTopLevel(raw.trim(), ' ')
        var inset = false
        var color: StyleColor? = null
        val lengths = mutableListOf<Float>()
        tokens.forEach { token ->
            val t = token.trim()
            when {
                t.equals("inset", true) -> inset = true
                dp(t) != null && color(t) == null -> lengths += dp(t)!!
                else -> color(t)?.let { color = it }
            }
        }
        if (lengths.size < 2) return null
        return StyleShadow(
            dx = lengths[0],
            dy = lengths[1],
            blur = lengths.getOrElse(2) { 0f }.coerceAtLeast(0f),
            spread = lengths.getOrElse(3) { 0f },
            color = color ?: StyleColor.Literal(0x40000000),
            inset = inset
        )
    }

    private fun angle(raw: String): Float? {
        val v = raw.trim().lowercase()
        return when {
            v.endsWith("deg") -> v.dropLast(3).toFloatOrNull()
            v.endsWith("turn") -> v.dropLast(4).toFloatOrNull()?.times(360f)
            v.endsWith("rad") -> v.dropLast(3).toFloatOrNull()?.times((180.0 / Math.PI).toFloat())
            else -> v.toFloatOrNull()
        }
    }

    private val FUNCTION = Regex("([a-zA-Z]+)\\(([^)]*)\\)")

    private fun transform(raw: String): StyleTransform? {
        if (raw.trim().equals("none", true)) return StyleTransform()
        var t = StyleTransform()
        FUNCTION.findAll(raw).forEach { m ->
            val args = m.groupValues[2].split(',').map(String::trim)
            when (m.groupValues[1].lowercase()) {
                "rotate", "rotatez" -> t = t.copy(rotate = angle(args[0]) ?: 0f)
                "scale" -> {
                    val x = args[0].toFloatOrNull() ?: 1f
                    t = t.copy(scaleX = x, scaleY = args.getOrNull(1)?.toFloatOrNull() ?: x)
                }
                "scalex" -> t = t.copy(scaleX = args[0].toFloatOrNull() ?: 1f)
                "scaley" -> t = t.copy(scaleY = args[0].toFloatOrNull() ?: 1f)
                "translate" -> t = t.copy(translateX = dp(args[0]) ?: 0f, translateY = args.getOrNull(1)?.let(::dp) ?: 0f)
                "translatex" -> t = t.copy(translateX = dp(args[0]) ?: 0f)
                "translatey" -> t = t.copy(translateY = dp(args[0]) ?: 0f)
                "skew", "skewx" -> t = t.copy(skewX = angle(args[0]) ?: 0f)
            }
        }
        return t
    }

    private fun filter(raw: String): StyleFilter? {
        if (raw.trim().equals("none", true)) return StyleFilter()
        var f = StyleFilter()
        FUNCTION.findAll(raw).forEach { m ->
            val arg = m.groupValues[2].trim()
            when (m.groupValues[1].lowercase()) {
                "blur" -> f = f.copy(blur = dp(arg)?.coerceIn(0f, 40f) ?: 0f)
                "grayscale" -> f = f.copy(grayscale = number(arg)?.coerceIn(0f, 1f) ?: 1f)
                "sepia" -> f = f.copy(sepia = number(arg)?.coerceIn(0f, 1f) ?: 1f)
                "brightness" -> f = f.copy(brightness = number(arg)?.coerceIn(0f, 3f) ?: 1f)
            }
        }
        return f
    }

    private fun duration(token: String): Int? {
        val t = token.trim().lowercase()
        return when {
            t.endsWith("ms") -> t.dropLast(2).toFloatOrNull()?.toInt()
            t.endsWith("s") -> t.dropLast(1).toFloatOrNull()?.times(1000)?.toInt()
            else -> null
        }?.coerceIn(0, 10_000)
    }

    private fun transition(raw: String): StyleTransition? {
        if (raw.trim().equals("none", true)) return null
        val properties = mutableSetOf<String>()
        var durationMs = 250
        var easing = "ease"
        splitTopLevel(raw, ',').forEach { part ->
            val tokens = splitTopLevel(part.trim(), ' ')
            tokens.forEach { token ->
                val d = duration(token)
                when {
                    d != null -> durationMs = d
                    token.startsWith("ease") || token == "linear" || token.startsWith("cubic-bezier") -> easing = token
                    else -> properties += token.lowercase()
                }
            }
        }
        return StyleTransition(properties.ifEmpty { setOf("all") }, durationMs, easing)
    }

    private fun animation(raw: String): StyleAnimation? {
        val tokens = splitTopLevel(raw.trim(), ' ')
        var preset: AnimationPreset? = null
        val durations = mutableListOf<Int>()
        var iterations = 1
        tokens.forEach { token ->
            val t = token.lowercase()
            val d = duration(t)
            when {
                d != null -> durations += d
                t == "infinite" -> iterations = -1
                t.toIntOrNull() != null -> iterations = t.toInt().coerceIn(1, 20)
                else -> PRESETS[t]?.let { preset = it }
            }
        }
        val p = preset ?: return null
        return StyleAnimation(p, durations.getOrElse(0) { 400 }, iterations, durations.getOrElse(1) { 0 })
    }

    private val PRESETS = mapOf(
        "fade-in" to AnimationPreset.FADE_IN, "fadein" to AnimationPreset.FADE_IN, "fade" to AnimationPreset.FADE_IN,
        "rise" to AnimationPreset.RISE, "slide-up" to AnimationPreset.RISE, "fade-up" to AnimationPreset.RISE,
        "pop" to AnimationPreset.POP, "zoom-in" to AnimationPreset.POP, "bounce-in" to AnimationPreset.POP,
        "float" to AnimationPreset.FLOAT, "bob" to AnimationPreset.FLOAT,
        "pulse" to AnimationPreset.PULSE, "breathe" to AnimationPreset.PULSE,
        "shimmer" to AnimationPreset.SHIMMER, "shine" to AnimationPreset.SHIMMER,
        "spin" to AnimationPreset.SPIN, "rotate" to AnimationPreset.SPIN,
        "shake" to AnimationPreset.SHAKE, "wiggle" to AnimationPreset.SHAKE,
        "typewriter" to AnimationPreset.TYPEWRITER, "type" to AnimationPreset.TYPEWRITER
    )

    private val NAMED_COLORS = mapOf(
        "black" to 0xff000000.toInt(), "white" to 0xffffffff.toInt(), "red" to 0xffff0000.toInt(),
        "green" to 0xff008000.toInt(), "blue" to 0xff0000ff.toInt(), "gray" to 0xff808080.toInt(),
        "grey" to 0xff808080.toInt(), "orange" to 0xffffa500.toInt(), "gold" to 0xffffd700.toInt(),
        "purple" to 0xff800080.toInt(), "pink" to 0xffffc0cb.toInt(), "brown" to 0xffa52a2a.toInt(),
        "navy" to 0xff000080.toInt(), "teal" to 0xff008080.toInt(), "crimson" to 0xffdc143c.toInt(),
        "ivory" to 0xfffffff0.toInt(), "beige" to 0xfff5f5dc.toInt(), "silver" to 0xffc0c0c0.toInt(),
        "transparent" to 0x00000000
    )
}

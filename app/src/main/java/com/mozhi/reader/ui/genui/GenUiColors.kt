package com.mozhi.reader.ui.genui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.mozhi.reader.ai.genui.GradientStop
import com.mozhi.reader.ai.genui.StyleColor
import com.mozhi.reader.ai.genui.StylePaint
import kotlin.math.max
import kotlin.math.min

/**
 * 互动卡片的配色来源。由调用方从所在页面的配色构造（聊天页从 `ReaderPalette` 来），
 * 渲染器本身不认识阅读主题，因此书库伴读等其它界面也能复用。
 *
 * 主题变量 `var(--ink)` 等按这里解析，日夜切换时模型写的界面自动跟着变。
 */
@Immutable
data class GenUiColors(
    val ink: Color,
    val paper: Color,
    val muted: Color,
    val card: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val seal: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val dark: Boolean,
    /** 夜间把模型写死的浅色底「保色相、翻明度」（设置可关）。 */
    val nightAdapt: Boolean = true
) {
    fun theme(name: String): Color = when (name) {
        "ink" -> ink
        "paper" -> paper
        "muted" -> muted
        "card" -> card
        "line" -> line
        "accent" -> accent
        "seal" -> seal
        else -> ink
    }

    /** 语气色：`color="success"` 之类的令牌。 */
    fun tone(name: String?): Color? = when (name) {
        null, "default" -> null
        "primary", "accent" -> accent
        "secondary", "muted" -> muted
        "tertiary" -> muted.copy(alpha = 0.72f)
        "success" -> success
        "warning" -> warning
        "danger" -> danger
        "info" -> info
        else -> null
    }

    fun resolve(color: StyleColor): Color = when (color) {
        is StyleColor.Literal -> Color(color.argb)
        is StyleColor.Theme -> theme(color.name).let { c -> color.alpha?.let { c.copy(alpha = it) } ?: c }
    }

    companion object {
        /** 低饱和、与纸色协调的四个语气色；中性灰方案下语义色位全是灰，答对/答错必须另有颜色。 */
        fun toneSet(dark: Boolean): List<Color> = if (dark) {
            listOf(Color(0xFF8FC09A), Color(0xFFE0B060), Color(0xFFE08A80), Color(0xFF8FB0E0))
        } else {
            listOf(Color(0xFF4F7F5A), Color(0xFF9A6A22), Color(0xFFB0443A), Color(0xFF4A6FA5))
        }
    }
}

val LocalGenUiColors = staticCompositionLocalOf<GenUiColors> { error("GenUiColors not provided") }

/**
 * 可读性兜底（纯函数，单测覆盖）。只保证「看得清」，不评判好不好看：
 * 对比度不够时只调文字的明度，色相与饱和度保持模型的选择。
 */
object GenUiContrast {
    const val BODY = 4.5f
    const val LARGE = 3f

    fun ratio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    /**
     * 把 [foreground] 调到与 [background] 至少 [minRatio] 的对比度：沿 HSL 明度朝能拉开对比的
     * 方向（深底变亮、浅底变暗）二分查找最小改动。半透明前景先合成到背景上再判断。
     */
    fun ensure(foreground: Color, background: Color, minRatio: Float = BODY): Color {
        val bg = if (background.alpha < 1f) background.compositeOver(Color.White) else background
        val fgOpaque = if (foreground.alpha < 1f) foreground.compositeOver(bg) else foreground
        if (ratio(fgOpaque, bg) >= minRatio) return foreground
        val hsl = toHsl(fgOpaque)
        val lighten = bg.luminance() < 0.5f
        var lo = hsl[2]
        var hi = if (lighten) 1f else 0f
        var best = fromHsl(hsl[0], hsl[1], hi)
        repeat(18) {
            val mid = (lo + hi) / 2f
            val candidate = fromHsl(hsl[0], hsl[1], mid)
            if (ratio(candidate, bg) >= minRatio) {
                best = candidate
                hi = mid
            } else {
                lo = mid
            }
        }
        if (ratio(best, bg) < minRatio) best = if (lighten) Color.White else Color.Black
        return best.copy(alpha = max(foreground.alpha, 0.92f))
    }

    /**
     * 夜间适配：深色纸上出现明显的浅色底（亮度 > 0.55）时，保色相与饱和度、把明度翻到深色区间。
     * 模型用主题变量或自己按 `app.theme.dark` 分支时，颜色本来就是深的，不会被改动。
     */
    fun adaptBackground(color: Color, colors: GenUiColors): Color {
        if (!colors.dark || !colors.nightAdapt || color.alpha == 0f) return color
        if (color.luminance() <= 0.55f) return color
        val hsl = toHsl(color)
        val flipped = (1f - hsl[2]).coerceIn(0.08f, 0.32f)
        return fromHsl(hsl[0], hsl[1] * 0.85f, flipped).copy(alpha = color.alpha)
    }

    /** 夜间把很深的文字色翻亮（之后仍会过一遍对比度校正）。 */
    fun adaptForeground(color: Color, colors: GenUiColors): Color {
        if (!colors.dark || !colors.nightAdapt) return color
        if (color.luminance() >= 0.12f) return color
        val hsl = toHsl(color)
        return fromHsl(hsl[0], hsl[1], (1f - hsl[2]).coerceIn(0.62f, 0.9f)).copy(alpha = color.alpha)
    }

    /** 背景层叠在 [under] 上之后的近似底色（渐变取色标平均），供子元素的对比度判断。 */
    fun effectiveSurface(layers: List<StylePaint>, under: Color, colors: GenUiColors): Color {
        var surface = under
        for (layer in layers.asReversed()) {
            val top = when (layer) {
                is StylePaint.Solid -> adaptBackground(colors.resolve(layer.color), colors)
                is StylePaint.Linear -> average(layer.stops, colors)
                is StylePaint.Radial -> average(layer.stops, colors)
                is StylePaint.Image -> null
            } ?: continue
            surface = top.compositeOver(surface)
        }
        return surface
    }

    private fun average(stops: List<GradientStop>, colors: GenUiColors): Color? {
        if (stops.isEmpty()) return null
        var r = 0f
        var g = 0f
        var b = 0f
        var a = 0f
        stops.forEach { stop ->
            val c = adaptBackground(colors.resolve(stop.color), colors)
            r += c.red
            g += c.green
            b += c.blue
            a += c.alpha
        }
        val n = stops.size
        return Color(r / n, g / n, b / n, a / n)
    }

    fun toHsl(color: Color): FloatArray {
        val r = color.red
        val g = color.green
        val b = color.blue
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2f
        if (maxC == minC) return floatArrayOf(0f, 0f, l)
        val d = maxC - minC
        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        val h = when (maxC) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return floatArrayOf(h, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Color {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = ((h % 360f + 360f) % 360f) / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f))
    }
}

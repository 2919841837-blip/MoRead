package com.mozhi.reader.ui.genui

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mozhi.reader.ai.genui.AnimationPreset
import com.mozhi.reader.ai.genui.BorderStyle
import com.mozhi.reader.ai.genui.GenUiStyle
import com.mozhi.reader.ai.genui.StyleColor
import com.mozhi.reader.ai.genui.StyleCorners
import com.mozhi.reader.ai.genui.StyleLength
import com.mozhi.reader.ai.genui.StylePaint
import com.mozhi.reader.ai.genui.StyleShadow
import com.mozhi.reader.ai.genui.StyleUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** 组件自身的默认外观（令牌派生）；模型写了 style 时逐项覆盖。 */
internal data class BoxDefaults(
    val background: Color? = null,
    val borderColor: Color? = null,
    val borderWidth: Dp = 0.dp,
    val radius: Dp = 0.dp,
    val padding: Dp? = null
)

internal fun cornerShape(corners: StyleCorners, fallback: Dp): Shape {
    if (corners.isEmpty && fallback == 0.dp) return RectangleShape
    fun c(v: Float?): Dp = v?.coerceIn(0f, 999f)?.dp ?: fallback
    return RoundedCornerShape(c(corners.topStart), c(corners.topEnd), c(corners.bottomEnd), c(corners.bottomStart))
}

/**
 * 按 CSS 盒模型的顺序把样式落成 modifier：外边距 → 尺寸 → 变换/透明度/动效 → 阴影 →
 * 背景层 → 边框 → 内边距。返回的 [surface] 是叠上本层背景后的近似底色，子元素据此做对比度校正。
 */
@Composable
internal fun Modifier.genUiBox(
    style: GenUiStyle,
    defaults: BoxDefaults,
    colors: GenUiColors,
    parentSurface: Color,
    onSurface: (Color) -> Unit = {}
): Modifier {
    val host = LocalGenUiHost.current
    val animate = host.animationsEnabled
    val transition = style.transition?.takeIf { animate }
    val shape = cornerShape(style.radius, defaults.radius)

    // ---- 背景层（解析主题变量 + 夜间适配，带过渡） ----
    val layers = style.backgrounds
    val solidTarget: Color? = when {
        layers.isEmpty() -> defaults.background
        layers.size == 1 && layers[0] is StylePaint.Solid ->
            GenUiContrast.adaptBackground(colors.resolve((layers[0] as StylePaint.Solid).color), colors)
        else -> null
    }
    val solid = solidTarget?.let { animatedColor(it, transition, "background") }
    val gradientLayers = if (solidTarget == null) layers else emptyList()
    val surface = when {
        solid != null -> solid.compositeIfTranslucent(parentSurface)
        gradientLayers.isNotEmpty() -> GenUiContrast.effectiveSurface(gradientLayers, parentSurface, colors)
        else -> parentSurface
    }
    onSurface(surface)

    // ---- 边框 ----
    val border = style.border
    val borderColor = border?.color?.let { colors.resolve(it) } ?: defaults.borderColor ?: colors.line
    val borderWidth = when {
        border == null -> defaults.borderWidth
        border.style == BorderStyle.NONE -> 0.dp
        else -> border.width.coerceIn(0f, 12f).dp
    }

    // ---- 透明度、变换 ----
    val opacityTarget = style.opacity ?: 1f
    val opacity = if (transition != null) animateFloatAsState(opacityTarget, tween(transition.durationMs, easing = easing(transition.easing)), label = "opacity").value else opacityTarget
    val t = style.transform
    val rotate = t?.rotate ?: 0f
    val scaleX = t?.scaleX ?: 1f
    val scaleY = t?.scaleY ?: 1f
    val animatedRotate = if (transition != null) animateFloatAsState(rotate, tween(transition.durationMs), label = "rotate").value else rotate
    val animatedScaleX = if (transition != null) animateFloatAsState(scaleX, tween(transition.durationMs), label = "scaleX").value else scaleX
    val animatedScaleY = if (transition != null) animateFloatAsState(scaleY, tween(transition.durationMs), label = "scaleY").value else scaleY

    var m = this
    // 外边距
    if (!style.margin.isEmpty) {
        m = m.padding(
            start = (style.margin.left ?: 0f).coerceIn(0f, 200f).dp,
            top = (style.margin.top ?: 0f).coerceIn(0f, 200f).dp,
            end = (style.margin.right ?: 0f).coerceIn(0f, 200f).dp,
            bottom = (style.margin.bottom ?: 0f).coerceIn(0f, 200f).dp
        )
    }
    if (style.zIndex != null) m = m.zIndex(style.zIndex)
    m = m.sized(style)
    style.aspectRatio?.takeIf { it in 0.1f..10f }?.let { m = m.aspectRatio(it) }

    val needsLayer = opacity < 1f || t != null || style.filter != null
    if (needsLayer) {
        val tx = t?.translateX ?: 0f
        val ty = t?.translateY ?: 0f
        val skew = t?.skewX ?: 0f
        val filter = style.filter
        m = m.graphicsLayer {
            alpha = opacity
            rotationZ = animatedRotate
            this.scaleX = animatedScaleX
            this.scaleY = animatedScaleY
            translationX = tx.dp.toPx()
            translationY = ty.dp.toPx()
            if (skew != 0f) rotationY = 0f // 斜切在原生图层里没有直接对应，忽略
            if (filter != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                renderEffect = filterEffect(filter.blur.dp.toPx(), filter.grayscale, filter.sepia, filter.brightness)
            }
        }
    }
    style.animation?.takeIf { animate }?.let { m = m.presetAnimation(it.preset, it.durationMs, it.iterations, it.delayMs) }

    // 阴影（外阴影；内阴影暂不支持）
    val shadows = style.shadows.filterNot { it.inset }
    if (shadows.isNotEmpty()) m = m.shadows(shadows, shape, colors)

    // 背景
    if (solid != null && solid.alpha > 0f) {
        m = m.drawBehind { drawOutline(shape.createOutline(size, layoutDirection, this), solid) }
    } else if (gradientLayers.isNotEmpty()) {
        val brushes = gradientLayers.asReversed().mapNotNull { paintBrush(it, colors) }
        if (brushes.isNotEmpty()) {
            m = m.drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                brushes.forEach { drawOutline(outline, it) }
            }
        }
    }
    if (style.overflowHidden || gradientLayers.any { it is StylePaint.Image }) m = m.clip(shape)

    // 边框
    if (borderWidth > 0.dp) {
        val dashed = border?.style == BorderStyle.DASHED || border?.style == BorderStyle.DOTTED
        val dotted = border?.style == BorderStyle.DOTTED
        m = m.drawWithContent {
            drawContent()
            val strokePx = borderWidth.toPx()
            val inset = strokePx / 2f
            val outline = shape.createOutline(Size(size.width - strokePx, size.height - strokePx), layoutDirection, this)
            val effect = if (dashed) {
                val on = if (dotted) strokePx else strokePx * 4f
                PathEffect.dashPathEffect(floatArrayOf(on, strokePx * 2.5f))
            } else null
            translate(inset, inset) {
                drawOutline(outline, borderColor, style = Stroke(width = strokePx, pathEffect = effect))
            }
        }
    }

    // 内边距
    val p = style.padding
    if (!p.isEmpty) {
        val fallback = defaults.padding?.value ?: 0f
        m = m.padding(
            start = (p.left ?: fallback).coerceIn(0f, 120f).dp,
            top = (p.top ?: fallback).coerceIn(0f, 120f).dp,
            end = (p.right ?: fallback).coerceIn(0f, 120f).dp,
            bottom = (p.bottom ?: fallback).coerceIn(0f, 120f).dp
        )
    } else if (defaults.padding != null) {
        m = m.padding(defaults.padding)
    }
    return m
}

private fun Color.compositeIfTranslucent(under: Color): Color =
    if (alpha >= 1f) this else compositeOver(under)

private fun Modifier.sized(style: GenUiStyle): Modifier {
    var m = this
    style.width?.let { m = m.length(it, horizontal = true) }
    style.height?.let { m = m.length(it, horizontal = false) }
    val minW = style.minWidth?.dpOrNull()
    val maxW = style.maxWidth?.dpOrNull()
    if (minW != null || maxW != null) m = m.widthIn(min = minW ?: Dp.Unspecified, max = maxW ?: Dp.Unspecified)
    val minH = style.minHeight?.dpOrNull()
    val maxH = style.maxHeight?.dpOrNull()?.coerceAtMost(2000.dp)
    if (minH != null || maxH != null) m = m.heightIn(min = minH ?: Dp.Unspecified, max = maxH ?: Dp.Unspecified)
    return m
}

private fun Modifier.length(length: StyleLength, horizontal: Boolean): Modifier = when (length.unit) {
    StyleUnit.PERCENT -> if (horizontal) fillMaxWidth(length.value.coerceIn(0f, 1f)) else fillMaxHeight(length.value.coerceIn(0f, 1f))
    StyleUnit.DP -> if (horizontal) width(length.value.coerceIn(0f, 2000f).dp) else height(length.value.coerceIn(0f, 2000f).dp)
    StyleUnit.EM -> (length.value * 14f).coerceIn(0f, 2000f).dp.let { if (horizontal) width(it) else height(it) }
}

private fun StyleLength.dpOrNull(): Dp? = when (unit) {
    StyleUnit.DP -> value.coerceIn(0f, 4000f).dp
    StyleUnit.EM -> (value * 14f).coerceIn(0f, 4000f).dp
    StyleUnit.PERCENT -> null
}

/** 渐变与图片背景的画笔：按实际尺寸生成着色器，角度与圆心随尺寸换算。 */
internal fun paintBrush(paint: StylePaint, colors: GenUiColors): Brush? = when (paint) {
    is StylePaint.Solid -> androidx.compose.ui.graphics.SolidColor(GenUiContrast.adaptBackground(colors.resolve(paint.color), colors))
    is StylePaint.Linear -> {
        val stopColors = paint.stops.map { GenUiContrast.adaptBackground(colors.resolve(it.color), colors) }
        val stops = stopPositions(paint.stops.map { it.position })
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val rad = (paint.angle - 90f) * PI.toFloat() / 180f
                val half = (kotlin.math.abs(size.width * sin(paint.angle * PI.toFloat() / 180f)) +
                    kotlin.math.abs(size.height * cos(paint.angle * PI.toFloat() / 180f))) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val dir = Offset(cos(rad), sin(rad))
                return LinearGradientShader(center - dir * half, center + dir * half, stopColors, stops, TileMode.Clamp)
            }
        }
    }
    is StylePaint.Radial -> {
        val stopColors = paint.stops.map { GenUiContrast.adaptBackground(colors.resolve(it.color), colors) }
        val stops = stopPositions(paint.stops.map { it.position })
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val center = Offset(size.width * paint.centerX, size.height * paint.centerY)
                val farthest = listOf(
                    Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)
                ).maxOf { hypot(it.x - center.x, it.y - center.y) }
                val radius = paint.radius?.let { it * max(size.width, size.height) } ?: farthest
                return RadialGradientShader(center, radius.coerceAtLeast(1f), stopColors, stops, TileMode.Clamp)
            }
        }
    }
    is StylePaint.Image -> null
}

private fun stopPositions(raw: List<Float?>): List<Float> {
    if (raw.isEmpty()) return emptyList()
    val out = FloatArray(raw.size)
    raw.forEachIndexed { index, value ->
        out[index] = value ?: when (index) {
            0 -> 0f
            raw.lastIndex -> 1f
            else -> Float.NaN
        }
    }
    // 中间缺省的位置均匀插值
    var i = 0
    while (i < out.size) {
        if (out[i].isNaN()) {
            val start = i - 1
            var end = i
            while (end < out.size && out[end].isNaN()) end++
            val from = out[start]
            val to = out[end]
            for (k in i until end) out[k] = from + (to - from) * (k - start) / (end - start)
            i = end
        } else {
            if (i > 0 && out[i] < out[i - 1]) out[i] = out[i - 1]
            i++
        }
    }
    return out.toList()
}

private fun Modifier.shadows(shadows: List<StyleShadow>, shape: Shape, colors: GenUiColors): Modifier = drawBehind {
    shadows.asReversed().forEach { shadow ->
        val color = colors.resolve(shadow.color).let { if (colors.dark) it.copy(alpha = (it.alpha * 1.3f).coerceAtMost(1f)) else it }
        val spread = shadow.spread.dp.toPx()
        val outline = shape.createOutline(Size(size.width + spread * 2, size.height + spread * 2), layoutDirection, this)
        val blur = shadow.blur.dp.toPx().coerceAtMost(120f)
        drawIntoCanvas { canvas ->
            val paint = Paint().apply { this.color = color }
            val frameworkPaint = paint.asFrameworkPaint()
            if (blur > 0f) frameworkPaint.maskFilter = BlurMaskFilter(blur / 2f, BlurMaskFilter.Blur.NORMAL)
            canvas.save()
            canvas.translate(shadow.dx.dp.toPx() - spread, shadow.dy.dp.toPx() - spread)
            when (outline) {
                is Outline.Rectangle -> canvas.drawRect(outline.rect, paint)
                is Outline.Rounded -> {
                    val r = outline.roundRect
                    canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, paint)
                }
                is Outline.Generic -> canvas.drawPath(outline.path, paint)
            }
            canvas.restore()
        }
    }
}

private fun filterEffect(blurPx: Float, grayscale: Float, sepia: Float, brightness: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val matrix = ColorMatrix()
    if (grayscale > 0f) matrix.setToSaturation(1f - grayscale)
    if (sepia > 0f) {
        val s = sepia
        val sepiaMatrix = ColorMatrix(
            floatArrayOf(
                1 - 0.607f * s, 0.769f * s, 0.189f * s, 0f, 0f,
                0.349f * s, 1 - 0.314f * s, 0.168f * s, 0f, 0f,
                0.272f * s, 0.534f * s, 1 - 0.869f * s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        matrix.timesAssign(sepiaMatrix)
    }
    if (brightness != 1f) {
        val b = ColorMatrix(floatArrayOf(brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        matrix.timesAssign(b)
    }
    val colorFilter = android.graphics.ColorMatrixColorFilter(matrix.values)
    val colorEffect = android.graphics.RenderEffect.createColorFilterEffect(colorFilter)
    val effect = if (blurPx > 0f) {
        android.graphics.RenderEffect.createChainEffect(
            colorEffect,
            android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.DECAL)
        )
    } else colorEffect
    return effect.asComposeRenderEffect()
}

/* ---------------------------- 动效 ---------------------------- */

private fun easing(name: String): Easing = when {
    name == "linear" -> LinearEasing
    name == "ease-in" -> CubicBezierEasing(0.42f, 0f, 1f, 1f)
    name == "ease-out" -> CubicBezierEasing(0f, 0f, 0.58f, 1f)
    name == "ease-in-out" -> CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    name.startsWith("cubic-bezier(") -> name.substringAfter('(').substringBefore(')').split(',').mapNotNull { it.trim().toFloatOrNull() }
        .takeIf { it.size == 4 }?.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) } ?: FastOutSlowInEasing
    else -> FastOutSlowInEasing
}

@Composable
internal fun animatedColor(target: Color, transition: com.mozhi.reader.ai.genui.StyleTransition?, property: String): Color {
    if (transition == null || !(transition.properties.contains("all") || transition.properties.any { property.startsWith(it) || it.startsWith(property) })) {
        return target
    }
    return animateColorAsState(target, tween(transition.durationMs, easing = easing(transition.easing)), label = property).value
}

/**
 * 预设动效。无限循环的动效只在条目组合期间运行（滑出聊天列表即释放）；
 * 节奏快于 3Hz 的无限动效拉慢到安全频率（光敏安全）。
 */
@Composable
private fun Modifier.presetAnimation(preset: AnimationPreset, durationMs: Int, iterations: Int, delayMs: Int): Modifier {
    val infinite = iterations < 0
    val duration = if (infinite) durationMs.coerceAtLeast(600) else durationMs.coerceIn(80, 4000)
    return when (preset) {
        AnimationPreset.FADE_IN, AnimationPreset.RISE, AnimationPreset.POP, AnimationPreset.TYPEWRITER -> {
            val progress = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                progress.animateTo(1f, tween(duration, delayMillis = delayMs, easing = if (preset == AnimationPreset.POP) CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f) else FastOutSlowInEasing))
            }
            graphicsLayer {
                val p = progress.value
                alpha = p.coerceIn(0f, 1f)
                when (preset) {
                    AnimationPreset.RISE -> translationY = (1f - p) * 14.dp.toPx()
                    AnimationPreset.POP -> {
                        scaleX = 0.85f + 0.15f * p
                        scaleY = 0.85f + 0.15f * p
                    }
                    else -> Unit
                }
            }
        }
        AnimationPreset.FLOAT, AnimationPreset.PULSE, AnimationPreset.SPIN, AnimationPreset.SHAKE -> {
            val transition = rememberInfiniteTransition(label = "genui-preset")
            val spec = when (preset) {
                AnimationPreset.SPIN -> infiniteRepeatable<Float>(tween(duration, easing = LinearEasing), RepeatMode.Restart)
                AnimationPreset.SHAKE -> infiniteRepeatable(keyframes {
                    durationMillis = duration
                    0f at 0
                    -1f at duration / 8
                    1f at duration * 3 / 8
                    -1f at duration * 5 / 8
                    0f at duration * 3 / 4
                }, RepeatMode.Restart)
                else -> infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse)
            }
            val value by transition.animateFloat(0f, 1f, spec, label = "genui-preset-value")
            // 有限次数的循环动效：播放 iterations 轮后停在原位
            val finished = remember { Animatable(0f) }
            if (!infinite) {
                LaunchedEffect(Unit) { finished.animateTo(1f, tween(duration * iterations.coerceAtLeast(1), delayMillis = delayMs)) }
            }
            graphicsLayer {
                val active = infinite || finished.value < 1f
                val v = if (active) value else 0f
                when (preset) {
                    AnimationPreset.FLOAT -> translationY = -4.dp.toPx() * v
                    AnimationPreset.PULSE -> {
                        scaleX = 1f + 0.04f * v
                        scaleY = 1f + 0.04f * v
                    }
                    AnimationPreset.SPIN -> rotationZ = if (active) 360f * v else 0f
                    AnimationPreset.SHAKE -> translationX = 3.dp.toPx() * v
                    else -> Unit
                }
            }
        }
        AnimationPreset.SHIMMER -> {
            val transition = rememberInfiniteTransition(label = "genui-shimmer")
            val x by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(duration.coerceAtLeast(1200), easing = LinearEasing)), label = "shimmer-x")
            drawWithContent {
                drawContent()
                val band = size.width * 0.35f
                val start = x * size.width
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                        start = Offset(start - band, 0f),
                        end = Offset(start + band, size.height)
                    ),
                    blendMode = BlendMode.SrcAtop
                )
            }
        }
    }
}

/* ---------------------------- 文字 ---------------------------- */

/**
 * 文字样式：主题字号 → 模型样式覆盖；颜色解析主题变量、夜间翻亮、再过对比度校正（只调明度）。
 * 渐变字（background-clip:text）不做对比度校正：那是模型有意为之的装饰。
 */
@Composable
internal fun genUiTextStyle(
    base: TextStyle,
    style: GenUiStyle,
    defaultColor: Color,
    surface: Color,
    colors: GenUiColors,
    large: Boolean = false
): TextStyle {
    val host = LocalGenUiHost.current
    val transition = style.transition?.takeIf { host.animationsEnabled }
    val fontSize: TextUnit = style.fontSize?.let { size ->
        when (size.unit) {
            StyleUnit.EM -> (base.fontSize.takeIf { it.isSpecified() }?.value ?: 14f).times(size.value)
            else -> size.value
        }.coerceIn(10f, 56f).sp
    } ?: base.fontSize
    val isLarge = large || (fontSize.isSpecified() && fontSize.value >= 18f)
    val requested = style.color?.let { GenUiContrast.adaptForeground(colors.resolve(it), colors) } ?: defaultColor
    val readable = GenUiContrast.ensure(requested, surface, if (isLarge) GenUiContrast.LARGE else GenUiContrast.BODY)
    val color = animatedColor(readable, transition, "color")
    val family = style.fontFamily?.let { name ->
        when (name.lowercase()) {
            "serif", "georgia", "times new roman" -> FontFamily.Serif
            "sans-serif", "sans", "system-ui", "helvetica", "arial" -> FontFamily.SansSerif
            "monospace", "mono", "courier", "menlo" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> host.fontFamily(name)
        }
    } ?: base.fontFamily
    val decorations = buildList {
        if (style.underline == true) add(TextDecoration.Underline)
        if (style.lineThrough == true) add(TextDecoration.LineThrough)
    }
    val brush = style.textPaint?.let { paintBrush(it, colors) }
    var result = base.copy(
        color = color,
        fontSize = fontSize,
        fontFamily = family,
        fontWeight = style.fontWeight?.let { FontWeight(it) } ?: base.fontWeight,
        fontStyle = style.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal } ?: base.fontStyle,
        letterSpacing = style.letterSpacing?.let { ls ->
            when (ls.unit) {
                StyleUnit.EM -> ls.value.coerceIn(-0.1f, 1f).em
                StyleUnit.DP -> ls.value.coerceIn(-2f, 20f).sp
                StyleUnit.PERCENT -> base.letterSpacing
            }
        } ?: base.letterSpacing,
        lineHeight = style.lineHeight?.let { lh ->
            when (lh.unit) {
                StyleUnit.EM -> lh.value.coerceIn(0.8f, 3f).em
                StyleUnit.DP -> lh.value.coerceIn(8f, 120f).sp
                StyleUnit.PERCENT -> base.lineHeight
            }
        } ?: base.lineHeight,
        textAlign = when (style.textAlign) {
            "center" -> TextAlign.Center
            "right", "end" -> TextAlign.End
            "left", "start" -> TextAlign.Start
            "justify" -> TextAlign.Justify
            else -> base.textAlign
        },
        textDecoration = if (decorations.isEmpty()) base.textDecoration else TextDecoration.combine(decorations),
        shadow = style.textShadow?.let { s ->
            Shadow(colors.resolve(s.color), Offset(s.dx * 2f, s.dy * 2f), s.blur.coerceAtMost(40f) * 2f)
        } ?: base.shadow
    )
    if (brush != null) result = result.copy(brush = brush)
    return result
}

private fun TextUnit.isSpecified(): Boolean = this != TextUnit.Unspecified

internal fun applyTextTransform(text: String, transform: String?): String = when (transform) {
    "uppercase" -> text.uppercase()
    "lowercase" -> text.lowercase()
    "capitalize" -> text.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    else -> text
}

/** 用于 Layer 里的绝对定位：[LayoutDirection] 只用于左右换算。 */
@Composable
internal fun isRtl(): Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl

package com.mozhi.reader.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「翻开书」转场：从书架续读位或书籍详情进入阅读页时，被点的那本书从原位置长成整页，
 * 封面沿左缘（书脊）向外翻开到 −124°，露出下面的纸页，再淡出交给阅读页。
 * 时间线照设计稿：纸页 620ms 长满、封面 740ms 翻开、约 500ms 时进入阅读页、700ms 起 300ms 淡出。
 *
 * 封面是点下那一刻对原组件的快照（GraphicsLayer），所以动画里的书与书架上的一模一样；
 * 系统关闭动画（开发者选项「动画时长 0」）时直接进入阅读页。
 */
@Stable
class BookOpenerState {
    var request by mutableStateOf<BookOpenRequest?>(null)
        internal set
}

class BookOpenRequest(val cover: ImageBitmap, val bounds: Rect)

val LocalBookOpener = staticCompositionLocalOf<BookOpenerState?> { null }

/** 挂在封面组件上，随时记录它的画面与在根布局里的位置，供翻书转场取用。 */
@Stable
class BookCoverCapture internal constructor(internal val layer: GraphicsLayer) {
    internal var bounds: Rect = Rect.Zero
}

@Composable
fun rememberBookCoverCapture(): BookCoverCapture {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BookCoverCapture(layer) }
}

fun Modifier.bookCoverCapture(capture: BookCoverCapture): Modifier = this
    .onGloballyPositioned { capture.bounds = it.boundsInRoot() }
    .drawWithContent {
        capture.layer.record { this@drawWithContent.drawContent() }
        drawLayer(capture.layer)
    }

/**
 * 以翻书转场打开阅读页：没有转场宿主、封面还没布局、或系统关了动画时直接 [open]。
 * [open] 在转场中途调用（阅读页在纸页底下组合，淡出时已经画好）。
 */
fun BookOpenerState?.openBook(
    capture: BookCoverCapture,
    scope: CoroutineScope,
    animationsEnabled: Boolean,
    open: () -> Unit
) {
    val state = this
    if (state == null || !animationsEnabled || capture.bounds.width < 8f || state.request != null) {
        open()
        return
    }
    scope.launch {
        val cover = runCatching { capture.layer.toImageBitmap() }.getOrNull()
        if (cover == null) {
            open()
            return@launch
        }
        state.request = BookOpenRequest(cover, capture.bounds)
        delay(OPEN_NAVIGATE_AT_MS)
        open()
    }
}

/** 系统「动画时长缩放」为 0 时视为减少动态效果。 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) > 0f
    }
}

/** 放在根布局最上层；有请求时画一次翻书动画，结束后自行清空。转场期间吞掉触摸，避免连点。 */
@Composable
fun BookOpenOverlay(state: BookOpenerState) {
    val request = state.request ?: return
    val grow = remember(request) { Animatable(0f) }
    val swing = remember(request) { Animatable(0f) }
    val fade = remember(request) { Animatable(1f) }
    LaunchedEffect(request) {
        launch { grow.animateTo(1f, tween(GROW_MS, easing = OpenEase)) }
        launch { swing.animateTo(1f, tween(SWING_MS, easing = SwingEase)) }
        delay(FADE_AT_MS)
        fade.animateTo(0f, tween(FADE_MS))
        state.request = null
    }
    val paper = MaterialTheme.colorScheme.surface
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .pointerInput(request) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
    ) {
        val from = request.bounds
        Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    // 只在布局阶段读动画值：每帧重新摆放，不重组。
                    val p = grow.value
                    val left = lerp(from.left, 0f, p)
                    val top = lerp(from.top, 0f, p)
                    val width = lerp(from.width, constraints.maxWidth.toFloat(), p).roundToInt().coerceAtLeast(1)
                    val height = lerp(from.height, constraints.maxHeight.toFloat(), p).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(left.roundToInt(), top.roundToInt())
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(18.dp, RoundedCornerShape(2.dp))
                    .background(paper, RoundedCornerShape(2.dp))
            )
            Image(
                bitmap = request.cover,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = swing.value
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        rotationY = -124f * p
                        cameraDistance = 14f * density
                        // 翻过 90° 后看到的是封面背面，设计稿里此时封面已淡去。
                        alpha = when {
                            p < 0.62f -> 1f
                            else -> (1f - (p - 0.62f) / 0.2f).coerceIn(0f, 1f)
                        }
                    }
            )
        }
    }
}

private val OpenEase = CubicBezierEasing(0.22f, 0.9f, 0.24f, 1f)
private val SwingEase = CubicBezierEasing(0.3f, 0.1f, 0.2f, 1f)
private const val GROW_MS = 620
private const val SWING_MS = 740
private const val OPEN_NAVIGATE_AT_MS = 500L
private const val FADE_AT_MS = 720L
private const val FADE_MS = 300

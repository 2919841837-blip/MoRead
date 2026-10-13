package com.mozhi.reader.feature.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ai.knowledge.KnowledgeProgress
import com.mozhi.reader.ai.knowledge.KnowledgeTaskState
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.theme.onAccent
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class KnowledgeOrbPhase { HIDDEN, RUNNING, DONE, FAILED }

/** 进行中一直显示；完成或失败后停留片刻再收起；用户自己停止的直接收起。 */
internal fun knowledgeOrbPhase(task: KnowledgeTaskState?, lingering: Boolean): KnowledgeOrbPhase = when {
    task == null -> KnowledgeOrbPhase.HIDDEN
    task.active -> KnowledgeOrbPhase.RUNNING
    !lingering || task.stopped -> KnowledgeOrbPhase.HIDDEN
    task.error != null -> KnowledgeOrbPhase.FAILED
    else -> KnowledgeOrbPhase.DONE
}

/** 球下角标：「7/18」；名单阶段还不知道总数时不显示。 */
internal fun knowledgeOrbBadge(detail: KnowledgeProgress?): String? = detail?.takeIf { it.total > 0 }?.let { "${it.done}/${it.total}" }

/**
 * 人物整理的悬浮进度球：任务在跑、目录弹层又关着时浮在阅读页上。
 * 外圈是进度环，中间轮流显示刚整理好的人物头像，下角标是「已整理 / 总数」；点一下直接打开「人物」页。
 * 完成时打勾停留几秒再收起。可拖动，松手吸到最近的左右边缘，位置在页面内保留。
 *
 * 它只负责「看得见」。切到后台不被冻结靠的是 KnowledgeTaskService 前台服务，不是这颗球。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderKnowledgeTaskOrb(
    task: KnowledgeTaskState?,
    palette: ReaderPalette,
    visible: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    var wasRunning by remember { mutableStateOf(false) }
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(task?.active, task?.error, task?.stopped) {
        when {
            task?.active == true -> { wasRunning = true; lingering = false }
            wasRunning -> {
                wasRunning = false
                lingering = true
                delay(if (task?.error != null) FAILED_LINGER_MS else DONE_LINGER_MS)
                lingering = false
            }
        }
    }
    val phase = knowledgeOrbPhase(task, lingering)
    if (!visible || phase == KnowledgeOrbPhase.HIDDEN) return
    val detail = task?.detail
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val orbPx = with(density) { ORB_SIZE.toPx() }
        val insetPx = with(density) { EDGE_INSET.toPx() }
        val topPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density).toFloat() + with(density) { TOP_LIMIT.toPx() }
        val maxXPx = (constraints.maxWidth - orbPx - insetPx).coerceAtLeast(insetPx)
        val maxYPx = (constraints.maxHeight - orbPx - with(density) { BOTTOM_LIMIT.toPx() }).coerceAtLeast(topPx)
        // 默认停在左侧中上方：与右侧伴读球、左上听书球错开。
        var savedX by rememberSaveable { mutableStateOf(-1f) }
        var savedY by rememberSaveable { mutableStateOf(-1f) }
        val offsetX = remember { Animatable(if (savedX >= 0f) savedX else insetPx) }
        val offsetY = remember { Animatable(if (savedY >= 0f) savedY else (constraints.maxHeight * 0.38f)) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(maxXPx, maxYPx, topPx) {
            offsetX.snapTo(offsetX.value.coerceIn(insetPx, maxXPx))
            offsetY.snapTo(offsetY.value.coerceIn(topPx, maxYPx))
        }
        val description = when (phase) {
            KnowledgeOrbPhase.DONE -> stringResource(R.string.characters_orb_done, detail?.finished?.size ?: 0)
            KnowledgeOrbPhase.FAILED -> stringResource(R.string.characters_orb_failed)
            else -> knowledgeOrbBadge(detail)?.let { stringResource(R.string.characters_orb_running_count, it) }
                ?: stringResource(R.string.characters_orb_running)
        }
        Box(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .pointerInput(maxXPx, maxYPx, topPx) {
                    detectDragGestures(onDragEnd = {
                        val target = if (offsetX.value + orbPx / 2 < constraints.maxWidth / 2f) insetPx else maxXPx
                        scope.launch {
                            offsetX.animateTo(target, spring(dampingRatio = 0.78f))
                            savedX = offsetX.value
                            savedY = offsetY.value
                        }
                    }) { change, drag ->
                        change.consume()
                        scope.launch {
                            offsetX.snapTo((offsetX.value + drag.x).coerceIn(insetPx, maxXPx))
                            offsetY.snapTo((offsetY.value + drag.y).coerceIn(topPx, maxYPx))
                        }
                    }
                }
                .testTag("knowledge-task-orb")
                .semantics { contentDescription = description }
        ) {
            KnowledgeOrbBody(phase, detail, palette, onOpen)
        }
    }
}

@Composable
private fun KnowledgeOrbBody(phase: KnowledgeOrbPhase, detail: KnowledgeProgress?, palette: ReaderPalette, onClick: () -> Unit) {
    val target = when (phase) {
        KnowledgeOrbPhase.DONE -> 1f
        else -> detail?.fraction ?: 0f
    }
    val fraction by animateFloatAsState(target, tween(420), label = "knowledge-orb-progress")
    val accent = palette.accent
    val track = palette.glassBorder
    val failed = MaterialTheme.colorScheme.error
    Box(contentAlignment = Alignment.Center) {
        Surface(onClick = onClick, shape = CircleShape, color = palette.glassStrong, border = BorderStroke(1.dp, palette.glassBorder),
            modifier = Modifier.size(ORB_SIZE)) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    val indeterminate = phase == KnowledgeOrbPhase.RUNNING && detail?.fraction == null
                    drawArc(if (phase == KnowledgeOrbPhase.FAILED) failed else accent, -90f,
                        if (indeterminate) 72f else 360f * fraction, false, Offset(inset, inset), arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                AnimatedContent(targetState = phase to detail?.finished?.lastOrNull(),
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "knowledge-orb-face") { (face, latest) ->
                    when {
                        face == KnowledgeOrbPhase.DONE -> Icon(Icons.Outlined.Check, null, tint = accent, modifier = Modifier.size(22.dp))
                        face == KnowledgeOrbPhase.FAILED -> Icon(Icons.Outlined.ErrorOutline, null, tint = failed, modifier = Modifier.size(22.dp))
                        latest != null -> PersonaAvatarImage(latest, null, Modifier.size(AVATAR_SIZE),
                            fallbackTextStyle = MaterialTheme.typography.titleSmall)
                        else -> Icon(Icons.Outlined.PeopleAlt, null, tint = palette.muted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        val badge = if (phase == KnowledgeOrbPhase.RUNNING) knowledgeOrbBadge(detail) else null
        if (badge != null) Surface(shape = CircleShape, color = accent, modifier = Modifier.align(Alignment.BottomCenter).offset(y = 8.dp)) {
            Text(badge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = accent.onAccent(), modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}

private val ORB_SIZE = 50.dp
private val AVATAR_SIZE = 34.dp
private val EDGE_INSET = 10.dp
private val TOP_LIMIT = 58.dp
private val BOTTOM_LIMIT = 110.dp
private const val DONE_LINGER_MS = 4_500L
private const val FAILED_LINGER_MS = 8_000L

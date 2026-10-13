package com.mozhi.reader.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.ui.components.BlurredGlassSurface
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.isDarkTheme
import dev.chrisbanes.haze.HazeState

/**
 * 手机悬浮导航舱（按设计稿：62dp 高的玻璃胶囊，图标在上、字在下，选中项是一块淡墨底）。
 * 页签宽度由可用宽度算出：书架页右侧还要放一枚搜索钮，舱和钮无论屏幕多窄都不会叠在一起。
 * 收起时只剩当前页一枚（不带字）；书架搜索展开时舱收起、搜索胶囊占满其余宽度。
 */
@Composable
internal fun FloatingRootDock(
    hazeState: HazeState,
    selectedRoute: String?,
    dockState: RootDockState?,
    trailing: DockAction?,
    onSelect: (RootDestination) -> Unit,
    modifier: Modifier = Modifier,
    /** 只有书架随滚动收起；其余根页舱的形状始终不变。 */
    collapsible: Boolean = false
) {
    val search = trailing as? DockAction.Search
    val searchOpen = search != null && dockState?.searchOpen == true
    val collapsed = (collapsible && dockState?.collapsed == true) || searchOpen
    val shadowDark = isDarkTheme()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tabCount = RootDestination.entries.size
        // 右侧动作位在四个根页始终预留：页签宽度与舱的位置因此跨页不变，切页时舱不会挪动。
        val tabWidth = dockTabWidth(maxWidth, RootDockHeight + RootDockGap, tabCount)
        BlurredGlassSurface(
            hazeState = hazeState,
            shape = MoReadTokens.CapsuleShape,
            tint = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .floatingGlassShadow(shadowDark)
                .selectableGroup()
        ) {
            Row(
                modifier = Modifier
                    .height(RootDockHeight)
                    .padding(DockPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RootDestination.entries.forEach { item ->
                    val selected = selectedRoute == item.route
                    val visible = selected || !collapsed
                    val width by animateDpAsState(
                        targetValue = when {
                            !visible -> 0.dp
                            collapsed -> DockCompactTabWidth
                            else -> tabWidth
                        },
                        animationSpec = tween(DOCK_MOTION_MS),
                        label = "nav-dock-tab-width"
                    )
                    val alpha by animateFloatAsState(
                        targetValue = if (visible) 1f else 0f,
                        animationSpec = tween(if (visible) 220 else 140),
                        label = "nav-dock-tab-alpha"
                    )
                    if (width > 0.5.dp) {
                        DockTab(
                            item = item,
                            selected = selected,
                            compact = collapsed,
                            modifier = Modifier
                                .width(width)
                                .fillMaxHeight()
                                .graphicsLayer { this.alpha = alpha },
                            onClick = {
                                when {
                                    searchOpen -> {
                                        search?.onQueryChange?.invoke("")
                                        dockState?.closeSearch()
                                    }
                                    collapsed -> dockState?.collapsed = false
                                    else -> onSelect(item)
                                }
                            }
                        )
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedContent(
            targetState = trailing?.kind,
            modifier = Modifier.align(Alignment.CenterEnd),
            transitionSpec = {
                (androidx.compose.animation.fadeIn(tween(200)) + androidx.compose.animation.scaleIn(tween(260), initialScale = 0.8f))
                    .togetherWith(androidx.compose.animation.fadeOut(tween(120)))
            },
            contentAlignment = Alignment.CenterEnd,
            label = "dock-trailing"
        ) { kind ->
            when (kind) {
                DockAction.Kind.SEARCH -> if (search != null) {
                    val openWidth = (maxWidth - RootDockHeight - RootDockGap).coerceAtLeast(RootDockHeight)
                    val width by animateDpAsState(
                        targetValue = if (searchOpen) openWidth else RootDockHeight,
                        animationSpec = tween(DOCK_MOTION_MS),
                        label = "shelf-search-width"
                    )
                    ShelfDockSearch(
                        hazeState = hazeState,
                        open = searchOpen,
                        query = search.query,
                        width = width,
                        onQueryChange = search.onQueryChange,
                        onOpen = { dockState?.openSearch() },
                        onClose = {
                            search.onQueryChange("")
                            dockState?.closeSearch()
                        },
                        modifier = Modifier.floatingGlassShadow(shadowDark)
                    )
                }
                DockAction.Kind.BUTTON -> (trailing as? DockAction.Button)?.let { action ->
                    DockRoundButton(hazeState, action, Modifier.floatingGlassShadow(shadowDark))
                }
                null -> Box(Modifier.size(RootDockHeight))
            }
        }
    }
}

/** 舱右侧的上下文动作：书架是可展开的搜索，伴读页是「新的书库伴读」，其余页留空。 */
internal sealed interface DockAction {
    val kind: Kind

    enum class Kind { SEARCH, BUTTON }

    data class Search(val query: String, val onQueryChange: (String) -> Unit) : DockAction {
        override val kind = Kind.SEARCH
    }

    data class Button(
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val label: String,
        val onClick: () -> Unit
    ) : DockAction {
        override val kind = Kind.BUTTON
    }
}

@Composable
private fun DockRoundButton(hazeState: HazeState, action: DockAction.Button, modifier: Modifier) {
    BlurredGlassSurface(
        hazeState = hazeState,
        shape = MoReadTokens.CapsuleShape,
        tint = MaterialTheme.colorScheme.surface,
        modifier = modifier.size(RootDockHeight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(MoReadTokens.CapsuleShape)
                .clickable(onClickLabel = action.label, onClick = action.onClick)
                .semantics { contentDescription = action.label },
            contentAlignment = Alignment.Center
        ) {
            Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
    }
}

/** 页签宽度：先扣掉搜索钮与舱内边距，再四等分，最宽 68dp（设计稿值）、最窄 48dp。 */
internal fun dockTabWidth(
    available: androidx.compose.ui.unit.Dp,
    reserved: androidx.compose.ui.unit.Dp,
    tabCount: Int
): androidx.compose.ui.unit.Dp =
    ((available - reserved - DockPadding * 2) / tabCount.coerceAtLeast(1)).coerceIn(DockTabMinWidth, DockTabMaxWidth)

@Composable
private fun DockTab(
    item: RootDestination,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val background by animateColorAsState(
        targetValue = if (selected && !compact) ink.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = tween(240),
        label = "nav-dock-tab-bg"
    )
    val content by animateColorAsState(
        targetValue = if (selected) ink else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "nav-dock-tab-fg"
    )
    Column(
        modifier = modifier
            .clip(MoReadTokens.CapsuleShape)
            .background(background)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = if (compact) item.label else null,
            tint = content,
            modifier = Modifier.size(22.dp)
        )
        if (!compact) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.sp),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = content,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/**
 * 悬浮玻璃件的投影：只画在形状外沿（clipOutPath），不会从半透明玻璃底下透出来把它染脏；
 * Android 9 以下 BlurMaskFilter 不走硬件加速，直接不画。
 */
internal fun Modifier.floatingGlassShadow(dark: Boolean): Modifier = drawWithCache {
    val radius = size.height / 2f
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = if (dark) android.graphics.Color.argb(150, 0, 0, 0) else android.graphics.Color.argb(60, 46, 34, 16)
        maskFilter = android.graphics.BlurMaskFilter(16.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    val outline = android.graphics.Path().apply {
        addRoundRect(0f, 0f, size.width, size.height, radius, radius, android.graphics.Path.Direction.CW)
    }
    val drop = 10.dp.toPx()
    val inset = 4.dp.toPx()
    onDrawBehind {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return@onDrawBehind
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipOutPath(outline)
            native.drawRoundRect(inset, drop, size.width - inset, size.height + drop * 0.6f, radius, radius, paint)
            native.restore()
        }
    }
}

private val DockPadding = 5.dp
private val DockTabMaxWidth = 68.dp
private val DockTabMinWidth = 48.dp
private val DockCompactTabWidth = 52.dp
private const val DOCK_MOTION_MS = 450

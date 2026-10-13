package com.mozhi.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ui.components.BlurredGlassSurface
import com.mozhi.reader.ui.theme.MoReadTokens
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay

/**
 * 根页悬浮导航舱的「可变」状态：内容下滑时收成一粒（只剩当前页），上滑展开；
 * 书架右下角的搜索钮展开成输入框时，导航舱同样收成一粒给它让位。
 */
@Stable
internal class RootDockState {
    var collapsed by mutableStateOf(false)
    var searchOpen by mutableStateOf(false)
        private set

    private var travel = 0f

    /** 只观察已被内容消费的纵向位移，不改变任何滚动行为。 */
    val scrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            onContentScrolled(consumed.y)
            return Offset.Zero
        }
    }

    /**
     * [deltaY] 为负表示内容向末尾滚（手指上推）。方向反转时清零累计量，
     * 同一方向累计超过 [COLLAPSE_TRAVEL_PX] 才切换，惯性末尾的小抖动不会让舱来回闪。
     */
    fun onContentScrolled(deltaY: Float) {
        if (searchOpen || deltaY == 0f) return
        if (travel != 0f && (travel < 0f) != (deltaY < 0f)) travel = 0f
        travel += deltaY
        when {
            travel <= -COLLAPSE_TRAVEL_PX -> { collapsed = true; travel = 0f }
            travel >= COLLAPSE_TRAVEL_PX -> { collapsed = false; travel = 0f }
        }
    }

    fun openSearch() {
        searchOpen = true
        collapsed = true
    }

    fun closeSearch() {
        searchOpen = false
        collapsed = false
        travel = 0f
    }

    /** 切换根页时恢复展开：新页面从顶部开始，没有理由继承上一页的收起态。 */
    fun reset() {
        collapsed = false
        travel = 0f
    }

    internal companion object {
        const val COLLAPSE_TRAVEL_PX = 36f
    }
}

@Composable
internal fun rememberRootDockState(): RootDockState = remember { RootDockState() }

/** 书架右下角的搜索：收起时是一枚与导航舱等高的玻璃圆钮，展开后是输入胶囊。 */
@Composable
internal fun ShelfDockSearch(
    hazeState: HazeState,
    open: Boolean,
    query: String,
    width: Dp,
    onQueryChange: (String) -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(open) {
        if (open) {
            // 等胶囊展开过半再弹键盘，避免输入框还是圆钮时就抢焦点。
            delay(SEARCH_FOCUS_DELAY_MS)
            runCatching { focusRequester.requestFocus() }
        } else {
            focusManager.clearFocus()
        }
    }
    val searchLabel = stringResource(R.string.shelf_dock_search)
    BlurredGlassSurface(
        hazeState = hazeState,
        shape = MoReadTokens.CapsuleShape,
        tint = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .width(width)
            .height(RootDockHeight)
            .testTag("shelf-dock-search")
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clip(MoReadTokens.CapsuleShape)
                .clickable(enabled = !open, onClickLabel = searchLabel, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(RootDockHeight), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = if (open) null else searchLabel,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (open) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.shelf_dock_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .testTag("shelf-search")
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(44.dp)
                        .clip(MoReadTokens.CapsuleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.shelf_dock_search_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** 导航舱与搜索钮共用的高度：两者并排时上下沿对齐。 */
internal val RootDockHeight = 62.dp
internal val RootDockGap = 10.dp
private const val SEARCH_FOCUS_DELAY_MS = 180L

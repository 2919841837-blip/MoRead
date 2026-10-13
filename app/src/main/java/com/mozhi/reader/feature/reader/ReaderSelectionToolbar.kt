package com.mozhi.reader.feature.reader

import androidx.compose.material.icons.outlined.MoreHoriz
import com.mozhi.reader.R
import androidx.compose.ui.res.stringResource
import com.mozhi.reader.core.datastore.SelectionToolbarItems
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.ai.prompt.SelectionAiAction

@Composable
internal fun BoxScope.SelectionToolbar(
    palette: ReaderPalette,
    topPx: Int,
    onParagraphTranslation: () -> Unit,
    onAi: (SelectionAiAction) -> Unit,
    onAnnotation: () -> Unit,
    onTts: () -> Unit,
    onImage: () -> Unit,
    onEdit: (() -> Unit)?,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    onDictionary: () -> Unit = {},
    extras: Set<String> = emptySet()
) {
    // 第一排只放常用几项，其余收进「更多」；不再放「取消」——点选区外或按返回键即可退出。
    val (primary, overflow) = remember(extras, onEdit != null) { SelectionToolbarItems.split(extras, onEdit != null) }
    var moreOpen by remember { mutableStateOf(false) }
    val actions: Map<String, () -> Unit> = mapOf(
        SelectionToolbarItems.ANNOTATE to onAnnotation,
        SelectionToolbarItems.COPY to onCopy,
        SelectionToolbarItems.DICTIONARY to onDictionary,
        SelectionToolbarItems.TRANSLATE to { onAi(SelectionAiAction.TRANSLATE) },
        SelectionToolbarItems.ASK to { onAi(SelectionAiAction.ASK) },
        SelectionToolbarItems.ANALYZE to { onAi(SelectionAiAction.ANALYZE) },
        SelectionToolbarItems.PARAGRAPH to onParagraphTranslation,
        SelectionToolbarItems.SPEAK to onTts,
        SelectionToolbarItems.IMAGE to onImage,
        SelectionToolbarItems.EDIT to { onEdit?.invoke(); Unit }
    )
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth(0.96f)
            .offset { IntOffset(0, topPx) }
            .testTag("reader-selection-toolbar"),
        shape = RoundedCornerShape(17.dp),
        color = palette.glassStrong,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, palette.glassBorder),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            primary.forEach { id ->
                SelectionToolItem(selectionToolIcon(id), selectionToolLabel(id),
                    if (id == SelectionToolbarItems.COPY) palette.onBackground else palette.accent, palette, actions.getValue(id))
            }
            if (overflow.isNotEmpty()) Box {
                SelectionToolItem(Icons.Outlined.MoreHoriz, stringResource(R.string.selection_more), palette.muted, palette) { moreOpen = true }
                com.mozhi.reader.ui.components.MoReadDropdownMenu(
                    expanded = moreOpen,
                    onDismissRequest = { moreOpen = false },
                    containerColor = palette.glassStrong,
                    contentColor = palette.onBackground,
                    borderColor = palette.glassBorder,
                    minWidth = 180.dp,
                    maxWidth = 240.dp
                ) {
                    overflow.forEach { id ->
                        com.mozhi.reader.ui.components.MoReadMenuItem(text = selectionToolLabel(id), icon = selectionToolIcon(id),
                            onClick = { moreOpen = false; actions.getValue(id)() })
                    }
                }
            }
        }
    }
}

private fun selectionToolIcon(id: String): ImageVector = when (id) {
    SelectionToolbarItems.ANNOTATE -> Icons.Outlined.BorderColor
    SelectionToolbarItems.COPY -> Icons.Outlined.ContentCopy
    SelectionToolbarItems.DICTIONARY -> Icons.AutoMirrored.Outlined.MenuBook
    SelectionToolbarItems.TRANSLATE -> SelectionAiAction.TRANSLATE.toolbarIcon()
    SelectionToolbarItems.ASK -> SelectionAiAction.ASK.toolbarIcon()
    SelectionToolbarItems.ANALYZE -> SelectionAiAction.ANALYZE.toolbarIcon()
    SelectionToolbarItems.PARAGRAPH -> Icons.Outlined.Translate
    SelectionToolbarItems.SPEAK -> Icons.Outlined.Headphones
    SelectionToolbarItems.IMAGE -> Icons.Outlined.Image
    else -> Icons.Outlined.Edit
}

@Composable
internal fun selectionToolLabel(id: String): String = stringResource(when (id) {
    SelectionToolbarItems.ANNOTATE -> R.string.selection_annotate
    SelectionToolbarItems.COPY -> R.string.selection_copy
    SelectionToolbarItems.DICTIONARY -> R.string.selection_dictionary
    SelectionToolbarItems.TRANSLATE -> R.string.selection_translate
    SelectionToolbarItems.ASK -> R.string.selection_ask
    SelectionToolbarItems.ANALYZE -> R.string.selection_analyze
    SelectionToolbarItems.PARAGRAPH -> R.string.selection_paragraph
    SelectionToolbarItems.SPEAK -> R.string.selection_speak
    SelectionToolbarItems.IMAGE -> R.string.selection_image
    else -> R.string.selection_edit
})

/** 图标在上、小字在下的选区操作项，替代早期的纯文字按钮。 */
@Composable
private fun SelectionToolItem(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = palette.muted
        )
    }
}

private fun SelectionAiAction.toolbarIcon(): ImageVector = when (this) {
    SelectionAiAction.TRANSLATE -> Icons.Outlined.Translate
    SelectionAiAction.ANALYZE -> Icons.Outlined.Psychology
    SelectionAiAction.ASK -> Icons.AutoMirrored.Outlined.HelpOutline
}

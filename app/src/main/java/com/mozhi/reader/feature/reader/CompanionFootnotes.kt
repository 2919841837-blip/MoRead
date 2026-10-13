package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.mozhi.reader.R
import com.mozhi.reader.ai.agent.WebSource
import com.mozhi.reader.ai.companion.CompanionFootnotes
import com.mozhi.reader.ai.companion.Footnote
import com.mozhi.reader.ai.companion.FootnoteTarget

/** 会话里所有网页工具结果，按网址索引；由聊天页提供给气泡，用来给网页脚注补标题与摘要。 */
internal val LocalCompanionWebSources = staticCompositionLocalOf<Map<String, WebSource>> { emptyMap() }

/** 脚注在界面上的样子：标签、引文（或网页标题）、上下文与点开后的动作。 */
internal data class FootnoteDisplay(
    val number: Int,
    val label: String,
    val text: String,
    val before: String = "",
    val after: String = "",
    val web: Boolean = false,
    val onOpen: (() -> Unit)? = null
)

/**
 * 带脚注的 AI 正文：上标编号可点，点击处弹出小卡片；[showList] 时在下方列出全部来源，
 * 点原文跳回书中、点网页用浏览器打开。卡片用阅读纸色与衬线字，读起来像正文本身。
 */
@Composable
internal fun FootnotedRichText(
    content: String,
    palette: ReaderPalette,
    footnotes: List<FootnoteDisplay>,
    showList: Boolean,
    modifier: Modifier = Modifier
) {
    val fallbackHandler = LocalUriHandler.current
    var lastPress by remember { mutableStateOf(Offset.Zero) }
    var opened by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    val handler = remember(fallbackHandler, footnotes) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val number = CompanionFootnotes.numberOf(uri)
                if (number == null) fallbackHandler.openUri(uri)
                else if (footnotes.any { it.number == number }) opened = number to lastPress
            }
        }
    }
    Column(modifier = modifier) {
        Box(
            modifier = Modifier.pointerInput(Unit) {
                // 只记下按下的位置给弹卡定位，不消费事件：链接点击与气泡长按照常工作。
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.pressed }?.let { lastPress = it.position }
                    }
                }
            }
        ) {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                StreamingAiRichText(content = content, palette = palette)
            }
            opened?.let { (number, at) ->
                val footnote = footnotes.firstOrNull { it.number == number }
                if (footnote != null) {
                    val gap = with(LocalDensity.current) { 14.dp.roundToPx() }
                    Popup(
                        offset = IntOffset(0, at.y.toInt() + gap),
                        onDismissRequest = { opened = null },
                        properties = PopupProperties(focusable = true)
                    ) {
                        FootnotePopover(footnote, palette) { opened = null }
                    }
                }
            }
        }
        if (showList && footnotes.isNotEmpty()) FootnoteList(footnotes, palette)
    }
}

/**
 * 引文卡：被引用的原文用宋体单独成卡，下沿是脚注编号与出处，点一下回到正文那一段。
 * 卡比纸亮一档、一圈发丝线，不投影——它是纸上的一张小卡片，不是浮层。
 */
@Composable
private fun FootnoteList(footnotes: List<FootnoteDisplay>, palette: ReaderPalette) {
    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        footnotes.forEach { footnote ->
            val enabled = footnote.onOpen != null
            Surface(
                color = palette.glassStrong,
                contentColor = palette.onBackground,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, palette.glassBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(enabled = enabled) { footnote.onOpen?.invoke() }
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
                    Text(
                        text = footnote.text,
                        style = if (footnote.web) {
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, lineHeight = 26.sp)
                        },
                        color = if (enabled) palette.onBackground else palette.muted,
                        maxLines = if (footnote.web) 2 else 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = footnote.number.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                            color = palette.seal,
                            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                        )
                        Text(
                            text = footnote.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (enabled) {
                            Text(
                                text = stringResource(if (footnote.web) R.string.footnote_open_web else R.string.footnote_jump),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.seal,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FootnoteNumber(number: Int, palette: ReaderPalette, enabled: Boolean) {
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .size(18.dp)
            .background(if (enabled) palette.accent.copy(alpha = 0.16f) else palette.glassBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            color = if (enabled) palette.accent else palette.muted
        )
    }
}

@Composable
private fun FootnotePopover(footnote: FootnoteDisplay, palette: ReaderPalette, onDismiss: () -> Unit) {
    Surface(
        color = palette.background,
        contentColor = palette.onBackground,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.glassBorder),
        shadowElevation = 6.dp,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .widthIn(max = 320.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FootnoteNumber(footnote.number, palette, enabled = footnote.onOpen != null)
                Text(
                    text = footnote.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            val highlight = palette.accent.copy(alpha = if (palette.isDark) 0.28f else 0.18f)
            Text(
                text = buildAnnotatedString {
                    if (footnote.web) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(footnote.text) }
                        if (footnote.after.isNotBlank()) {
                            append('\n')
                            withStyle(SpanStyle(color = palette.muted)) { append(footnote.after) }
                        }
                    } else {
                        if (footnote.before.isNotBlank()) withStyle(SpanStyle(color = palette.muted)) { append("…"); append(footnote.before) }
                        withStyle(SpanStyle(background = highlight)) { append(footnote.text) }
                        if (footnote.after.isNotBlank()) withStyle(SpanStyle(color = palette.muted)) { append(footnote.after); append("…") }
                    }
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (footnote.web) FontFamily.Default else FontFamily.Serif,
                    lineHeight = 26.sp
                ),
                maxLines = 9,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                if (footnote.onOpen != null) {
                    TextButton(onClick = { onDismiss(); footnote.onOpen.invoke() }) {
                        Text(
                            stringResource(if (footnote.web) R.string.footnote_open_web else R.string.footnote_jump),
                            color = palette.accent
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.footnote_not_found),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/** 书内伴读：原文脚注配上已核对的位置与上下文，网页脚注配上标题与摘要。 */
@Composable
internal fun readerFootnoteDisplays(
    footnotes: List<Footnote>,
    located: List<LocatedCompanionCitation>,
    webSources: Map<String, WebSource>,
    onLocate: (LocatedCompanionCitation) -> Unit,
    onOpenUrl: (String) -> Unit
): List<FootnoteDisplay> {
    val chapterLabel = stringResource(R.string.footnote_chapter)
    val unknownChapter = stringResource(R.string.footnote_book_quote)
    return footnotes.map { footnote ->
        when (val target = footnote.target) {
            is FootnoteTarget.Book -> {
                val hit = located.firstOrNull { it.citation.quote.trim() == target.quote }
                val chapter = hit?.chapterIndex?.plus(1) ?: target.chapterNumber
                FootnoteDisplay(
                    number = footnote.number,
                    label = chapter?.let { chapterLabel.format(it) } ?: unknownChapter,
                    text = target.quote,
                    before = hit?.contextBefore.orEmpty(),
                    after = hit?.contextAfter.orEmpty(),
                    onOpen = hit?.let { { onLocate(it) } }
                )
            }
            is FootnoteTarget.Web -> {
                val source = webSources[target.url]
                FootnoteDisplay(
                    number = footnote.number,
                    label = hostOf(target.url),
                    text = source?.title?.ifBlank { null } ?: target.url,
                    after = source?.snippet.orEmpty(),
                    web = true,
                    onOpen = { onOpenUrl(target.url) }
                )
            }
        }
    }
}

internal fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

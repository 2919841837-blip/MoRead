package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.mozhi.reader.R
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.ai.agent.DiscussionTurn
import com.mozhi.reader.ai.agent.applyDiscussionMention
import com.mozhi.reader.ai.agent.buildDiscussionTurns
import com.mozhi.reader.ai.agent.discussionBodyWithoutMention
import com.mozhi.reader.ai.agent.discussionRoster
import com.mozhi.reader.ai.agent.leadingMentionPersonaId
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.library.AnnotationMedia
import com.mozhi.reader.core.library.AnnotationRecall
import com.mozhi.reader.feature.reader.render.AnnotationInk
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.components.NoteStyleColorPalette
import com.mozhi.reader.ui.components.blockSheetDrag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 划线本色 → Compose Color；面板与正文渲染共用 AnnotationInk，保证所见即所得。 */
internal fun annotationSolidColor(colorTag: String, palette: ReaderPalette): Color =
    Color(AnnotationInk.solidColor(colorTag, palette.isDark, palette.accent.toArgb()))

/**
 * 样式微调条：上排三样式、下排四预设色 + 自定义调色。即划即改浮条与讨论串头部共用；
 * 点击即回调，由调用方实时写库。自定义色以 "#RRGGBB" 存进 colorTag。
 */
@Composable
internal fun AnnotationStylePanel(
    selectedStyle: AnnotationStyle,
    selectedColor: String,
    palette: ReaderPalette,
    onChange: (AnnotationStyle, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val customActive = selectedColor.startsWith("#")
    Column(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnnotationStyle.entries.forEach { style ->
                StyleSample(
                    style = style,
                    colorTag = selectedColor,
                    selected = style == selectedStyle,
                    palette = palette,
                    onClick = { onChange(style, selectedColor) }
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnnotationColors.ALL.forEach { tag ->
                val color = annotationSolidColor(tag, palette)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.9f))
                        .border(
                            width = if (tag == selectedColor) 2.dp else 0.dp,
                            color = if (tag == selectedColor) palette.onBackground else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable { onChange(selectedStyle, tag) }
                )
            }
            // 自定义调色：未启用时是描边取色器图标，启用后显示当前自定义色
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (customActive) {
                            annotationSolidColor(selectedColor, palette).copy(alpha = 0.9f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .border(
                        width = if (customActive) 2.dp else 1.dp,
                        color = if (customActive) palette.onBackground else palette.glassBorder,
                        shape = CircleShape
                    )
                    .clickable { pickerOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Colorize,
                    contentDescription = "自定义划线颜色",
                    tint = if (customActive) palette.background else palette.muted,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
    if (pickerOpen) {
        InkColorPickerDialog(
            initial = annotationSolidColor(selectedColor, palette),
            onDismiss = { pickerOpen = false },
            onConfirm = { hex ->
                pickerOpen = false
                onChange(selectedStyle, hex)
            }
        )
    }
}

/** 与外观、排版页共用笔记软件式色板；确认回调 "#RRGGBB"。 */
@Composable
private fun InkColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var preview by remember { mutableStateOf(initial) }
    val hex = String.format(Locale.ROOT, "#%06X", preview.toArgb() and 0x00FFFFFF)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义划线颜色") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NoteStyleColorPalette(
                    color = preview,
                    onColorChange = { preview = it }
                )
                Text(
                    "荧光会自动降透明度垫在文字下方，直线与波浪使用原色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hex) }) { Text("使用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 单个样式示例：以「文字」实绘该样式，选中态加边框。 */
@Composable
private fun StyleSample(
    style: AnnotationStyle,
    colorTag: String,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    val solid = annotationSolidColor(colorTag, palette)
    val label = when (style) {
        AnnotationStyle.HIGHLIGHT -> "荧光"
        AnnotationStyle.UNDERLINE -> "直线"
        AnnotationStyle.WAVY -> "波浪"
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) palette.accentContainer.copy(alpha = 0.5f) else Color.Transparent,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, if (selected) palette.accent else palette.glassBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "文字",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.drawBehind {
                    when (style) {
                        AnnotationStyle.HIGHLIGHT -> drawRoundRect(
                            color = solid.copy(alpha = if (palette.isDark) 0.20f else 0.28f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                        )
                        AnnotationStyle.UNDERLINE -> drawLine(
                            color = solid,
                            start = Offset(0f, size.height - 1.dp.toPx()),
                            end = Offset(size.width, size.height - 1.dp.toPx()),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        AnnotationStyle.WAVY -> {
                            val amplitude = 1.6.dp.toPx()
                            val baseY = size.height - amplitude
                            val segments = AnnotationInk.wavySegments(size.width, 8.dp.toPx())
                            if (segments > 0) {
                                val path = Path()
                                val step = size.width / segments
                                path.moveTo(0f, baseY)
                                var up = true
                                var x = 0f
                                repeat(segments) {
                                    val controlY = if (up) baseY - amplitude else baseY + amplitude
                                    path.quadraticTo(x + step / 2f, controlY, x + step, baseY)
                                    x += step
                                    up = !up
                                }
                                drawPath(path, solid, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
                            }
                        }
                    }
                }
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = palette.muted
            )
        }
    }
}

@Composable
private fun ParticipantAvatar(
    personaId: Long?,
    personas: List<PersonaEntity>,
    palette: ReaderPalette,
    size: androidx.compose.ui.unit.Dp
) {
    val persona = personaId?.let { id -> personas.firstOrNull { it.id == id } }
    if (personaId == null || (persona == null && personaId < 0)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(palette.onBackground.copy(alpha = if (palette.isDark) 0.85f else 0.88f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = "我",
                tint = palette.background,
                modifier = Modifier.size(size * 0.58f)
            )
        }
    } else if (persona != null) {
        PersonaAvatarImage(
            name = persona.name,
            avatarPath = persona.avatarPath,
            modifier = Modifier.size(size)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(palette.glass),
            contentAlignment = Alignment.Center
        ) {
            Text("？", color = palette.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * 段评讨论串弹层，「页边批注」的写法：
 * 被讨论的原文用宋体排在顶部，并按它在正文里的样子画出划线（荧光 / 直线 / 波浪），
 * 样式与颜色就地可改；批注沿一根朱砂线挂下来，每条用一小段横线挂在线上，回复再缩进一级。
 * 不再套引文胶囊、描边卡、弹层边三层容器——纸是同一张纸。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnnotationDiscussionSheet(
    annotations: List<AnnotationEntity>,
    replies: List<AnnotationReplyEntity>,
    streaming: DiscussionStreaming?,
    error: String?,
    personas: List<PersonaEntity>,
    illustrations: List<IllustrationEntity>,
    palette: ReaderPalette,
    onPlayAudio: (String) -> Unit,
    onSend: (target: AnnotationEntity, text: String, respondPersonaId: Long?) -> Unit,
    onUpdateStyle: (annotationId: Long, style: AnnotationStyle, colorTag: String) -> Unit,
    onDeleteAnnotation: (Long) -> Unit,
    /** 头部「删除批注」：这一段的全部批注（同锚点）与整串讨论一起删。 */
    onDeleteThread: (List<Long>) -> Unit,
    onDeleteReply: (Long) -> Unit,
    onCancelStreaming: () -> Unit,
    onDismiss: () -> Unit,
    defaultRespondPersonaId: Long? = null,
    onRememberRespondPersona: (Long) -> Unit = {}
) {
    var input by remember { mutableStateOf("") }
    var confirmDeleteThread by remember { mutableStateOf(false) }
    // 点名默认落在「上次点过的角色 → 当前伴读角色 → 第一个角色」上，不必每次重选。
    // 取消点名（再点一次选中的胶囊）只对这一条发言生效，不写回记忆。
    var overridden by rememberSaveable { mutableStateOf(false) }
    var overriddenTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    val fallbackTarget = personas.firstOrNull { it.id == defaultRespondPersonaId }?.id
        ?: personas.firstOrNull()?.id
    val respondTarget = if (overridden) overriddenTarget else fallbackTarget
    val respondPersona = personas.firstOrNull { it.id == respondTarget }
    val clipboard = LocalClipboardManager.current
    val ownAnnotation = annotations.firstOrNull { it.personaId == null }
    val quoteSource = ownAnnotation ?: annotations.firstOrNull()
    val quote = quoteSource?.selectedText.orEmpty()
    // 讨论串 = 楼主层与回复层合成的时间线：只划线没写想法的批注不占楼，第一个开口的发言
    // 就是楼主，渲染在最外层。
    val turns = buildDiscussionTurns(annotations, replies)
    val talkCount = turns.size
    // @点名 的染色、解析与 SYSTEM 里那份花名册同源（同名角色同样带数字后缀），所以 @观澜2 也
    // 染得上色、也点得中。currentPersonaId 传一个不存在的 id：署名里的「（你）」与点名无关。
    val roster = discussionRoster(turns, personas.associate { it.id to it.name }, currentPersonaId = -1L)
    val mentionHandles = roster.map { it.handle }.toSet()
    val handleByPersonaId = roster.associate { it.personaId to it.handle }
    val handleToPersonaId = roster.associate { it.handle to it.personaId }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val focusRequester = remember { FocusRequester() }
    val seal = palette.seal

    // 新回复落地或 AI 开始应答时跟到底部；流式正文逐 token 长高不再拽视口，
    // 回复在视口下方生长，生成期间可自由上滑回看。
    LaunchedEffect(replies.size, streaming != null) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1)
    }

    // 键盘弹出时弹层整体上抬（见调用处的 imePadding），列表被压缩，尾部讨论会被裁掉。
    // 只在可见性翻转时跟一次底，不逐帧跟随 IME 动画。
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val keyboardVisible by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    LaunchedEffect(keyboardVisible) {
        if (!keyboardVisible) return@LaunchedEffect
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1)
    }
    // 点名：输入框里恒为一条 `@名字 `，写在最前面。点「回复」与改选胶囊都走这里——被点到的人
    // 就是回话的人；再点一次已选中的胶囊 = 取消点名（这条只记想法，不叫人回复）。
    // 花名册里没有的人（还没发过言、或角色已删除）不写 @，只改变由谁来答。
    val setMention: (Long?) -> Unit = { personaId ->
        val target = personaId?.takeIf { id -> personas.any { it.id == id } }
        overridden = true
        overriddenTarget = target
        if (target != null) onRememberRespondPersona(target)
        input = applyDiscussionMention(input, target?.let { handleByPersonaId[it] })
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append("段落讨论")
                    if (talkCount > 0) append(" · ").append(talkCount)
                },
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                fontWeight = FontWeight.SemiBold,
                color = seal,
                modifier = Modifier.weight(1f)
            )
            // 「删除批注」：这一段（同锚点）的全部划线与批注、连同整串讨论一起删。
            if (annotations.isNotEmpty()) {
                TextButton(
                    onClick = { confirmDeleteThread = true },
                    modifier = Modifier.testTag("discussion-delete-thread")
                ) {
                    Text(stringResource(R.string.discussion_delete_annotation), color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onDismiss) {
                Text("完成", color = palette.onBackground, fontWeight = FontWeight.SemiBold)
            }
        }

        if (confirmDeleteThread) {
            AlertDialog(
                onDismissRequest = { confirmDeleteThread = false },
                title = { Text(stringResource(R.string.discussion_delete_thread_title)) },
                text = { Text(stringResource(R.string.discussion_delete_thread_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDeleteThread = false
                            onDeleteThread(annotations.map { it.id })
                            onDismiss()
                        },
                        modifier = Modifier.testTag("discussion-delete-confirm")
                    ) {
                        Text(stringResource(R.string.discussion_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteThread = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }

        if (quote.isNotBlank() && quoteSource != null) {
            val quoteStyle = AnnotationStyle.fromWire(quoteSource.style)
            val quoteInk = annotationSolidColor(
                quoteSource.colorTag.ifBlank {
                    quoteSource.personaId?.let(AnnotationColors::forPersona) ?: AnnotationColors.AMBER
                },
                palette
            )
            QuoteExcerpt(
                text = quote,
                style = quoteStyle,
                ink = quoteInk,
                palette = palette,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
        }
        ownAnnotation?.let { own ->
            InlineStyleControls(
                selectedStyle = AnnotationStyle.fromWire(own.style),
                selectedColor = AnnotationColors.normalize(own.colorTag),
                palette = palette,
                onChange = { style, color -> onUpdateStyle(own.id, style, color) },
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                // fill=false：内容短时按内容高，键盘弹出时优先压缩列表、保住输入区
                .weight(1f, fill = false)
                .blockSheetDrag(listState)
                .drawBehind {
                    // 一根朱砂线从原文下沿垂下来，越往下越淡。
                    val x = THREAD_LINE_X.toPx()
                    drawLine(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(seal.copy(alpha = 0.75f), seal.copy(alpha = 0.08f))
                        ),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.5.dp.toPx()
                    )
                },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (annotations.isEmpty()) {
                item {
                    Text(
                        "这段批注已删除。",
                        color = palette.muted,
                        modifier = Modifier.padding(start = THREAD_CONTENT_START, top = 8.dp, bottom = 8.dp)
                    )
                }
            }
            turns.forEachIndexed { index, turn ->
                val isOpener = index == 0
                item(key = turn.renderKey()) {
                    ThreadComment(
                        authorId = turn.personaId,
                        personas = personas,
                        palette = palette,
                        timeText = timeFormat.format(Date(turn.createdAt)),
                        depth = if (isOpener) 0 else 1,
                        isOpener = isOpener,
                        // 用户自己那条不给「回复」：@自己无意义；要点名别人用下面的胶囊。
                        onReply = turn.personaId?.let { id -> { setMention(id) } },
                        onCopy = { clipboard.setText(AnnotatedString(turn.text)) },
                        deleteLabel = stringResource(
                            if (turn is DiscussionTurn.FromAnnotation) R.string.discussion_delete_annotation
                            else R.string.discussion_delete
                        ),
                        onDelete = {
                            when (turn) {
                                is DiscussionTurn.FromAnnotation -> onDeleteAnnotation(turn.annotation.id)
                                is DiscussionTurn.FromReply -> onDeleteReply(turn.reply.id)
                            }
                        }
                    ) {
                        // 召回明细在上、发言正文在下，用 Column 竖向排布避免重叠；展开召回时正文自动下移。
                        // 用户发言（personaId == null）没有召回，RecallBreakdown 自动不渲染。
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RecallBreakdown(
                                turn.recallJson(),
                                aiGenerated = turn.personaId != null,
                                palette = palette
                            )
                            ThreadText(turn.text, palette, mentionHandles)
                            AnnotationMediaContent(
                                media = AnnotationMedia.decode(turn.mediaJson()),
                                text = turn.text,
                                illustrations = illustrations,
                                palette = palette,
                                onPlayAudio = onPlayAudio
                            )
                        }
                    }
                }
            }
            if (talkCount == 0 && annotations.isNotEmpty() && streaming == null) {
                item(key = "empty") {
                    Text(
                        text = "还没有讨论，说点什么吧",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        modifier = Modifier.padding(start = THREAD_CONTENT_START, top = 4.dp, bottom = 4.dp)
                    )
                }
            }
            streaming?.let { active ->
                item(key = "streaming") {
                    StreamingReplyRow(
                        streaming = active,
                        personas = personas,
                        palette = palette,
                        mentions = mentionHandles,
                        onCancel = onCancelStreaming
                    )
                }
            }
            error?.let { message ->
                item(key = "error") {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = THREAD_CONTENT_START, top = 2.dp)
                    )
                }
            }
        }

        if (personas.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "@",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(end = 2.dp)
                )
                personas.forEach { persona ->
                    val selected = respondTarget == persona.id
                    Surface(
                        // 选中即点名：输入框里的 @ 跟着换；再点一次取消点名。
                        onClick = { setMention(if (selected) null else persona.id) },
                        shape = CircleShape,
                        color = if (selected) palette.background else Color.Transparent,
                        contentColor = if (selected) palette.onBackground else palette.muted,
                        border = BorderStroke(
                            if (selected) 1.5.dp else 0.5.dp,
                            if (selected) palette.onBackground else palette.glassBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            PersonaAvatarImage(
                                name = persona.name,
                                avatarPath = persona.avatarPath,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                persona.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 96.dp)
                            )
                        }
                    }
                }
            }
        }

        val canSend = (input.isNotBlank() || respondPersona != null) && annotations.isNotEmpty() && streaming == null
        ReaderComposerBar(
            input = input,
            onInputChange = { input = it },
            placeholder = respondPersona?.let { stringResource(R.string.discussion_placeholder_persona, it.name) }
                ?: stringResource(R.string.discussion_placeholder),
            canSend = canSend,
            isStreaming = streaming != null,
            palette = palette,
            onSend = {
                val target = annotations.firstOrNull { it.personaId == null } ?: annotations.first()
                // 正文里点谁的名，就由谁来回应；正文只有点名、没说别的时按「请他写这段段评」发。
                val mentioned = leadingMentionPersonaId(input, handleToPersonaId)
                    ?.takeIf { id -> personas.any { it.id == id } }
                val hasBody = discussionBodyWithoutMention(input).isNotBlank()
                onSend(target, if (hasBody) input.trim() else "", mentioned ?: respondTarget)
                input = ""
            },
            onStop = onCancelStreaming,
            maxLines = 4,
            textFieldModifier = Modifier.focusRequester(focusRequester),
            modifier = Modifier.requiredHeightIn(min = COMPOSER_FOOTER_MIN).padding(top = 10.dp, bottom = 10.dp)
        )
    }
}

/** 朱砂线的横坐标与批注正文的起始缩进；挂钩横线从线伸到头像。 */
private val THREAD_LINE_X = 5.dp
private val THREAD_HOOK_END = 18.dp
private val THREAD_CONTENT_START = 22.dp
private val THREAD_REPLY_INDENT = 34.dp

/**
 * 被讨论的原文：宋体，按它在正文里的样子画划线——读者一眼看出这是哪一种划线、什么颜色，
 * 改样式时这里立刻跟着变。逐行取文字边界作画，换行处不会连成一整块。
 */
@Composable
private fun StyledQuote(
    text: String,
    style: AnnotationStyle,
    ink: Color,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val display = remember(text) { if (text.length > QUOTE_MAX_CHARS) text.take(QUOTE_MAX_CHARS) + "…" else text }
    Text(
        text = display,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            fontSize = 17.sp,
            lineHeight = 31.sp
        ),
        color = palette.onBackground,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier.drawBehind {
            val result = layout ?: return@drawBehind
            for (line in 0 until result.lineCount) {
                val left = result.getLineLeft(line)
                val right = result.getLineRight(line)
                if (right - left < 1f) continue
                val baseline = result.getLineBaseline(line)
                when (style) {
                    AnnotationStyle.HIGHLIGHT -> drawRoundRect(
                        color = ink.copy(alpha = if (palette.isDark) 0.26f else 0.30f),
                        topLeft = Offset(left - 2.dp.toPx(), baseline - 19.sp.toPx()),
                        size = androidx.compose.ui.geometry.Size(right - left + 4.dp.toPx(), 24.sp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                    )
                    AnnotationStyle.UNDERLINE -> drawLine(
                        color = ink,
                        start = Offset(left, baseline + 5.dp.toPx()),
                        end = Offset(right, baseline + 5.dp.toPx()),
                        strokeWidth = 1.6.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    AnnotationStyle.WAVY -> {
                        val amplitude = 1.8.dp.toPx()
                        val y = baseline + 5.dp.toPx()
                        val segments = AnnotationInk.wavySegments(right - left, 8.dp.toPx())
                        if (segments > 0) {
                            val path = Path()
                            val step = (right - left) / segments
                            path.moveTo(left, y)
                            var x = left
                            var up = true
                            repeat(segments) {
                                path.quadraticTo(x + step / 2f, if (up) y - amplitude else y + amplitude, x + step, y)
                                x += step
                                up = !up
                            }
                            drawPath(path, ink, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                }
            }
        }
    )
}

private const val QUOTE_MAX_CHARS = 200

/**
 * 原文摘录：书里的字和讨论里的话必须一眼分开。原文是宋体、带划线、垫一层淡纸色、
 * 左上角一枚朱砂引号，上方一行「原文」小标；讨论用界面黑体，直接排在纸上。
 */
@Composable
private fun QuoteExcerpt(
    text: String,
    style: AnnotationStyle,
    ink: Color,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.discussion_quote_label),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = palette.muted,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.onBackground.copy(alpha = if (palette.isDark) 0.06f else 0.035f))
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
        ) {
            Text(
                text = "\u201C",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontSize = 40.sp,
                    lineHeight = 40.sp
                ),
                color = palette.seal.copy(alpha = 0.55f),
                modifier = Modifier.offset(x = (-2).dp, y = (-8).dp)
            )
            StyledQuote(
                text = text,
                style = style,
                ink = ink,
                palette = palette,
                modifier = Modifier.padding(start = 18.dp)
            )
        }
    }
}

/** 紧跟原文的样式条：三段小分段 + 四枚色点 + 自定义取色，点一下上面那句立刻变。 */
@Composable
private fun InlineStyleControls(
    selectedStyle: AnnotationStyle,
    selectedColor: String,
    palette: ReaderPalette,
    onChange: (AnnotationStyle, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val customActive = selectedColor.startsWith("#")
    Row(
        // 三段分段 + 五枚色点约 350dp，窄屏上横向滑动而不是被裁掉。
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(palette.onBackground.copy(alpha = if (palette.isDark) 0.08f else 0.055f))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            AnnotationStyle.entries.forEach { style ->
                val selected = style == selectedStyle
                Box(
                    modifier = Modifier
                        .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(9.dp)) else Modifier)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) palette.background else Color.Transparent)
                        .clickable { onChange(style, selectedColor) }
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = stringResource(
                            when (style) {
                                AnnotationStyle.HIGHLIGHT -> R.string.annotation_style_highlight
                                AnnotationStyle.UNDERLINE -> R.string.annotation_style_underline
                                AnnotationStyle.WAVY -> R.string.annotation_style_wavy
                            }
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) palette.onBackground else palette.muted
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnnotationColors.ALL.forEach { tag ->
                InkDot(
                    color = annotationSolidColor(tag, palette),
                    selected = tag == selectedColor,
                    palette = palette,
                    onClick = { onChange(selectedStyle, tag) }
                )
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (customActive) annotationSolidColor(selectedColor, palette) else Color.Transparent)
                    .border(
                        width = if (customActive) 2.dp else 1.dp,
                        color = if (customActive) palette.background else palette.glassBorder,
                        shape = CircleShape
                    )
                    .clickable { pickerOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Colorize,
                    contentDescription = "自定义划线颜色",
                    tint = if (customActive) palette.background else palette.muted,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
    if (pickerOpen) {
        InkColorPickerDialog(
            initial = annotationSolidColor(selectedColor, palette),
            onDismiss = { pickerOpen = false },
            onConfirm = { hex ->
                pickerOpen = false
                onChange(selectedStyle, hex)
            }
        )
    }
}

@Composable
private fun InkDot(color: Color, selected: Boolean, palette: ReaderPalette, onClick: () -> Unit) {
    // 选中态：外圈一道同色细环，与纸之间留一线空隙，像印泥盒里拿起的那一枚。
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) color else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(if (selected) 4.dp else 1.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** 讨论正文：界面黑体、比正文略大一档的行高——它是对话，不是书。原文那段才用宋体。 */
@Composable
private fun ThreadText(content: String, palette: ReaderPalette, mentions: Set<String> = emptySet()) {
    val base = MaterialTheme.typography
    val talk = remember(base) {
        base.copy(bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp))
    }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = talk) {
        AiRichText(content = content, palette = palette, mentions = mentions)
    }
}

/** 列表项 key、召回明细与媒体都跟着发言的来源走：批注行取批注，回复行取回复。 */
private fun DiscussionTurn.renderKey(): String = when (this) {
    is DiscussionTurn.FromAnnotation -> "annotation-${annotation.id}"
    is DiscussionTurn.FromReply -> "reply-${reply.id}"
}

private fun DiscussionTurn.recallJson(): String = when (this) {
    is DiscussionTurn.FromAnnotation -> annotation.recallJson
    is DiscussionTurn.FromReply -> reply.recallJson
}

private fun DiscussionTurn.mediaJson(): String = when (this) {
    is DiscussionTurn.FromAnnotation -> annotation.mediaJson
    is DiscussionTurn.FromReply -> reply.mediaJson
}

/**
 * 讨论串里的一条：挂在朱砂线上的头像、名字与身份、时间、宋体正文，下面一行「回复 / 复制」。
 * [depth] 为 1 时是回复：再缩进一级。讨论串是严格时间线，回复不按父子嵌套，所以不画接回上一条
 * 的折角线——回了谁由发言里的 @点名 自己说明。[isOpener] 标出楼主（讨论串第一条有内容的发言）。
 * [onReply] 为空时不给「回复」这一项：用户自己那条不点自己的名。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadComment(
    authorId: Long?,
    personas: List<PersonaEntity>,
    palette: ReaderPalette,
    timeText: String,
    depth: Int,
    onReply: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    deleteLabel: String,
    onDelete: () -> Unit,
    isOpener: Boolean = false,
    content: @Composable () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val seal = palette.seal
    val authorName = authorId?.let { id ->
        personas.firstOrNull { it.id == id }?.name ?: "已删除角色"
    } ?: "我"
    val avatarSize = if (depth == 0) 30.dp else 26.dp
    val start = if (depth == 0) THREAD_CONTENT_START else THREAD_CONTENT_START + THREAD_REPLY_INDENT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val cy = 10.dp.toPx() + avatarSize.toPx() / 2f
                if (depth == 0) {
                    drawLine(
                        color = seal.copy(alpha = 0.7f),
                        start = Offset(THREAD_LINE_X.toPx(), cy),
                        end = Offset(THREAD_HOOK_END.toPx(), cy),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
            }
            .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
            .padding(start = start, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        ParticipantAvatar(authorId, personas, palette, avatarSize)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = avatarSize)) {
                Text(
                    authorName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
                Text(
                    text = stringResource(if (authorId == null) R.string.discussion_role_me else R.string.discussion_role_persona),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier
                        .padding(start = 7.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.onBackground.copy(alpha = if (palette.isDark) 0.09f else 0.06f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
                // 楼主标：讨论串第一条有内容的发言（用户只划线没写想法时，让给最早开口的角色）。
                if (isOpener) {
                    Text(
                        text = stringResource(R.string.discussion_role_opener),
                        style = MaterialTheme.typography.labelSmall,
                        color = seal,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(seal.copy(alpha = if (palette.isDark) 0.18f else 0.10f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Box(Modifier.padding(top = 2.dp)) { content() }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                onReply?.let { reply -> ThreadAction(stringResource(R.string.discussion_reply), palette, reply) }
                onCopy?.let { copy -> ThreadAction(stringResource(R.string.discussion_copy), palette, copy) }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(deleteLabel) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun ThreadAction(label: String, palette: ReaderPalette, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = palette.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
    )
}

@Composable
private fun AnnotationMediaContent(
    media: AnnotationMedia,
    text: String,
    illustrations: List<IllustrationEntity>,
    palette: ReaderPalette,
    onPlayAudio: (String) -> Unit
) {
    if (media.isEmpty) return
    media.audioPath?.takeIf(String::isNotBlank)?.let { path ->
        CompanionVoiceBubble(
            text = text,
            clip = VoiceClipState(path = path),
            palette = palette,
            onPrepare = {},
            onRegenerate = {},
            onPlay = onPlayAudio
        )
    }
    media.illustrationId?.let { id ->
        illustrations.firstOrNull { it.id == id }?.let { illustration ->
            coil3.compose.AsyncImage(
                model = illustration.imagePath,
                contentDescription = "批注配图",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

/**
 * 段评召回明细折叠区：默认收起一行「召回明细 · N 项」，展开后按大纲 / 角色卡 / 笔记 / 片段
 * 分组列出这条段评生成时实际召回的前文资料。只展示，不参与任何召回逻辑；旧行或手动划线
 * （recallJson 为空且无 persona）不渲染，AI 段评（随读 / 聊天工具）即使召回为空也展示空态。
 */
@Composable
internal fun RecallBreakdown(recallJson: String?, aiGenerated: Boolean, palette: ReaderPalette) {
    val recall = remember(recallJson) { AnnotationRecall.decode(recallJson) }
    // 手动划线不召回也不渲染；AI 生成的段评（随读 / 聊天工具）即使召回为空也保留折叠区，好让人看出「确实没有前文可召回」。
    if (recall.isEmpty && !aiGenerated) return
    var expanded by rememberSaveable(recallJson) { mutableStateOf(false) }
    val total = recall.outlines.size + recall.characterCards.size + recall.notes.size + recall.snippets.size + recall.threads.size + recall.discussions.size + recall.proactiveNotes.size
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.onBackground.copy(alpha = if (palette.isDark) 0.06f else 0.04f))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (total > 0) stringResource(R.string.discussion_recall_title_count, total) else stringResource(R.string.discussion_recall_title),
                style = MaterialTheme.typography.labelMedium,
                color = palette.muted,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(if (expanded) R.string.discussion_recall_collapse else R.string.discussion_recall_expand),
                style = MaterialTheme.typography.labelSmall,
                color = palette.seal
            )
        }
        if (expanded) {
            if (recall.isEmpty) {
                Text(
                    text = stringResource(R.string.discussion_recall_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted
                )
            }
            if (recall.outlines.isNotEmpty()) {
                RecallGroup(stringResource(R.string.discussion_recall_group_outline), recall.outlines.map { it.label to it.text }, palette)
            }
            if (recall.characterCards.isNotEmpty()) {
                RecallGroup(stringResource(R.string.discussion_recall_group_card), recall.characterCards.map { it.name to it.profile }, palette)
            }
            if (recall.notes.isNotEmpty()) {
                RecallGroup(stringResource(R.string.discussion_recall_group_note), recall.notes.map { it.label to it.text }, palette)
            }
            if (recall.threads.isNotEmpty()) {
                RecallGroup(stringResource(R.string.discussion_recall_group_thread), recall.threads.map { thread ->
                    thread.label to thread.turns.joinToString("\n") { it.speaker + "：" + it.text }
                }, palette)
            }
            if (recall.proactiveNotes.isNotEmpty()) {
                RecallGroup(stringResource(R.string.discussion_recall_group_proactive_note), recall.proactiveNotes.map { it.label to it.text }, palette)
            }
            if (recall.discussions.isNotEmpty()) {
                RecallGroup(stringResource(R.string.discussion_recall_group_discussion), recall.discussions.map { it.label to it.text }, palette)
            }
            if (recall.snippets.isNotEmpty()) {
                RecallGroup(stringResource(R.string.discussion_recall_group_snippet), recall.snippets.map { snippet ->
                    (snippet.label + snippet.source.takeIf(String::isNotBlank)?.let { " · " + it }.orEmpty()) to snippet.text
                }, palette)
            }
        }
    }
}

/** 一组召回条目：小标 + 若干「章锚 / 正文」两行，正文比普通讨论略淡一档。 */
@Composable
private fun RecallGroup(title: String, items: List<Pair<String, String>>, palette: ReaderPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = palette.seal,
            fontWeight = FontWeight.SemiBold
        )
        items.forEach { (label, text) ->
            Column {
                if (label.isNotBlank()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackground.copy(alpha = 0.82f)
                )
            }
        }
    }
}

/** AI 正在回复：挂在讨论串末尾的一条回复，头像 + 流式正文 + 工具活动行 + 停止。 */
@Composable
private fun StreamingReplyRow(
    streaming: DiscussionStreaming,
    personas: List<PersonaEntity>,
    palette: ReaderPalette,
    mentions: Set<String>,
    onCancel: () -> Unit
) {
    val persona = personas.firstOrNull { it.id == streaming.personaId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = THREAD_CONTENT_START + THREAD_REPLY_INDENT, top = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        ParticipantAvatar(streaming.personaId, personas, palette, 26.dp)
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 26.dp)) {
                Text(
                    persona?.name ?: "角色",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onBackground
                )
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    color = palette.seal,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(11.dp)
                )
            }
            streaming.toolLabel?.let { label ->
                Text(
                    text = "$label…",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (streaming.text.isNotBlank()) {
                val base = MaterialTheme.typography
                val serif = remember(base) {
                    base.copy(bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp))
                }
                MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = serif) {
                    StreamingAiRichText(
                        content = streaming.text,
                        palette = palette,
                        modifier = Modifier.padding(top = 2.dp),
                        mentions = mentions
                    )
                }
            }
        }
        Surface(
            onClick = onCancel,
            shape = CircleShape,
            color = palette.onBackground.copy(alpha = if (palette.isDark) 0.10f else 0.06f),
            contentColor = palette.muted,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Stop,
                    contentDescription = "停止生成",
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

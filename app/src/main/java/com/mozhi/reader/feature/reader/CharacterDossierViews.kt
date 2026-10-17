package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.R
import com.mozhi.reader.ai.agent.RelationLayout
import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.knowledge.CharacterAttributeKind
import com.mozhi.reader.ai.knowledge.CharacterHighlight
import com.mozhi.reader.ai.knowledge.CharacterRole
import com.mozhi.reader.ai.knowledge.HighlightField
import com.mozhi.reader.ai.knowledge.HighlightSpan
import com.mozhi.reader.ai.knowledge.ExternalCharacterNote
import com.mozhi.reader.ai.knowledge.KnowledgeProgress
import com.mozhi.reader.ai.knowledge.KnowledgeTaskState
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.components.blockSheetDrag
import com.mozhi.reader.ui.theme.MoReadSpacing
import kotlin.math.roundToInt

/** 人物头像：有定妆图用图，否则渐变首字；主角外圈强调色，主要人物外圈淡强调色。 */
@Composable
internal fun CharacterAvatar(name: String, thumbnail: String?, role: CharacterRole?, size: Dp, palette: ReaderPalette,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, contentDescription: String? = null) {
    val ring = when (role) {
        CharacterRole.PROTAGONIST -> palette.accent
        CharacterRole.MAJOR -> palette.accent.copy(alpha = .45f)
        else -> Color.Transparent
    }
    val style = when {
        size >= 72.dp -> MaterialTheme.typography.headlineMedium
        size >= 48.dp -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleSmall
    }
    Box(modifier.size(size).border(2.dp, ring, CircleShape).padding(3.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        PersonaAvatarImage(name, thumbnail, Modifier.fillMaxSize(), fallbackTextStyle = style)
    }
}

/** 主次徽标文案；人物编辑器的主次档位也用它，两处叫法才不会打架。 */
@Composable
internal fun characterRoleBadge(role: CharacterRole): String = stringResource(when (role) {
    CharacterRole.PROTAGONIST -> R.string.characters_role_badge_protagonist
    CharacterRole.MAJOR -> R.string.characters_role_badge_major
    CharacterRole.SUPPORTING -> R.string.characters_role_badge_supporting
})

@Composable
internal fun characterGroupTitle(role: CharacterRole): String = stringResource(when (role) {
    CharacterRole.PROTAGONIST -> R.string.characters_role_protagonist
    CharacterRole.MAJOR -> R.string.characters_role_major
    CharacterRole.SUPPORTING -> R.string.characters_role_supporting
})

/** 人物列表里的一张卡：头像、姓名与主次、一句话简介、两三个标签。点卡片看档案，点头像去生成定妆图。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharacterRosterCard(person: BookCharacter, thumbnail: String?, palette: ReaderPalette, onOpen: () -> Unit,
    onAvatar: () -> Unit, menu: @Composable () -> Unit) {
    val summary = remember(person) { characterSummary(person) }
    Surface(onClick = onOpen, shape = RoundedCornerShape(18.dp), color = palette.glass,
        modifier = Modifier.fillMaxWidth().testTag("person-${person.name}")) {
        Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = MoReadSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
            CharacterAvatar(person.name, thumbnail, person.effectiveRole(), 56.dp, palette, onClick = onAvatar,
                contentDescription = stringResource(R.string.dossier_avatar_cd, person.name))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MoReadSpacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 姓名优先拿到整行剩余宽度；状态徽标移到下一行，不能把名字挤没。
                    Text(person.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = palette.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    person.effectiveRole()?.let { role ->
                        Spacer(Modifier.width(MoReadSpacing.s))
                        Text(characterRoleBadge(role), style = MaterialTheme.typography.labelSmall,
                            color = if (role == CharacterRole.SUPPORTING) palette.muted else palette.accent,
                            modifier = Modifier.border(1.dp, if (role == CharacterRole.SUPPORTING) palette.glassBorder else palette.accent.copy(alpha = .5f),
                                RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                    menu()
                }
                if (summary.tagline.isNotBlank()) Text(summary.tagline, style = MaterialTheme.typography.bodyMedium,
                    color = palette.onBackground.copy(alpha = .86f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (person.departed || person.highlights.isNotEmpty() || summary.tags.isNotEmpty()) {
                    FlowRow(Modifier.clipToBounds(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp), maxLines = 1) {
                        CharacterStatusBadge(person, palette)
                        summary.tags.forEach { tag -> DossierChip(tag, palette,
                            highlighted = person.highlightFor(HighlightField.PERSONALITY, tag) != null) }
                    }
                }
                val meta = listOfNotNull(
                    stringResource(R.string.characters_manual_attributes).takeIf { person.manualAttributes != null },
                    stringResource(R.string.characters_relations_count, summary.relationCount).takeIf { summary.relationCount > 0 }
                )
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = palette.muted)
            }
        }
    }
}

@Composable
private fun DossierChip(text: String, palette: ReaderPalette, onClick: (() -> Unit)? = null,
    highlighted: Boolean = false) {
    Surface(onClick = onClick ?: {}, enabled = onClick != null, shape = RoundedCornerShape(8.dp),
        color = if (highlighted) palette.accent else palette.accentContainer) {
        Text(text, color = if (highlighted) palette.onAccent else palette.accent, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable
private fun StatusBadge(text: String, palette: ReaderPalette) {
    Surface(shape = RoundedCornerShape(6.dp), color = palette.accentContainer) {
        Text(text, color = palette.accent, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** 「已离场 / 有更新」：放在姓名之外的独立一行，避免和长名字抢宽度。 */
@Composable
private fun CharacterStatusBadge(person: BookCharacter, palette: ReaderPalette) {
    when {
        person.departed -> StatusBadge(stringResource(R.string.characters_departed_badge), palette)
        person.highlights.isNotEmpty() -> StatusBadge(stringResource(R.string.characters_updated_badge), palette)
    }
}

/** 可点击的「新」角标：点按询问是否清除这条高亮。与旁边的正文/关系行分开，避免抢走它们的点击。 */
@Composable
private fun NewMarker(palette: ReaderPalette, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(6.dp), color = palette.accentContainer) {
        Text(stringResource(R.string.characters_highlight_new), color = palette.accent,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/**
 * 正文 + 「本次更新新增」高亮。点按高亮片段会回调 [onTap]（由档案页弹清除确认），
 * 普通文字部分不拦截点击。
 */
@Composable
private fun HighlightedText(text: String, highlight: CharacterHighlight?, palette: ReaderPalette, style: TextStyle,
    modifier: Modifier = Modifier, onTap: ((CharacterHighlight, HighlightSpan?) -> Unit)? = null,
    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip) {
    if (highlight == null || (!highlight.wholeItem && highlight.spans.isEmpty())) {
        Text(text, modifier, style = style, color = palette.onBackground, maxLines = maxLines, overflow = overflow)
        return
    }
    val annotated: AnnotatedString = remember(text, highlight, palette.accent, palette.accentContainer) {
        buildAnnotatedString {
            append(text)
            val paint = SpanStyle(background = palette.accentContainer, color = palette.accent)
            if (highlight.wholeItem) addStyle(paint, 0, text.length)
            else highlight.spans.forEach { span ->
                val start = span.start.coerceIn(0, text.length)
                val end = span.end.coerceIn(start, text.length)
                if (end > start) addStyle(paint, start, end)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap = if (onTap == null) Modifier else Modifier.pointerInput(annotated, highlight) {
        detectTapGestures { position ->
            val result = layout ?: return@detectTapGestures
            val offset = result.getOffsetForPosition(position)
            val span = highlight.spans.firstOrNull { offset >= it.start && offset < it.end }
            if (highlight.wholeItem || span != null) onTap(highlight, span)
        }
    }
    Text(annotated, modifier.then(tap), style = style, color = palette.onBackground, maxLines = maxLines,
        overflow = overflow, onTextLayout = { layout = it })
}

/** 人物页顶部的固定状态行：高度不随状态变化，进度更新不会挪动列表。 */
@Composable
internal fun CharacterTaskStrip(task: KnowledgeTaskState?, idleText: String, palette: ReaderPalette) {
    val busy = task?.active == true
    val detail = task?.detail
    Column(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = MoReadSpacing.xl), verticalArrangement = Arrangement.Center) {
        val text = when {
            task?.error != null -> task.error
            busy && detail != null -> characterProgressText(detail)
            busy -> task.progress
            else -> idleText
        }
        Text(text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = if (task?.error != null) MaterialTheme.colorScheme.error else palette.muted)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (busy) {
                val fraction = detail?.fraction
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxSize(), color = palette.accent, trackColor = palette.glassBorder)
                else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxSize(), color = palette.accent,
                    trackColor = palette.glassBorder, drawStopIndicator = {})
            }
        }
    }
}

@Composable
internal fun characterProgressText(detail: KnowledgeProgress): String = when (detail.stage) {
    KnowledgeProgress.Stage.ROSTER -> stringResource(R.string.characters_progress_planning)
    KnowledgeProgress.Stage.WEB -> stringResource(R.string.characters_progress_searching)
    KnowledgeProgress.Stage.SCANNING -> stringResource(R.string.knowledge_notification_scanning, detail.done, detail.total)
    else -> buildString {
        append(stringResource(R.string.knowledge_notification_profiles, detail.done, detail.total))
        if (detail.working.isNotEmpty()) append(" · ").append(detail.working.joinToString("、"))
    }
}

/**
 * 人物档案页：弹层里的二级页，带返回。从上到下是头像与一句话、基本信息、小传、性格、外貌、
 * 成长变化、关系（自我中心图 + 列表），书外资料排在最后。依据不再单列成清单：
 * 性格标签与关系各自就能点回原文（手写的那几条没有原文坐标，因此不提供跳转）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharacterDossierPage(
    person: BookCharacter, everyone: List<BookCharacter>, titles: Map<Int, String>, thumbnails: Map<String, String>,
    externalNotes: List<ExternalCharacterNote>, progressBounded: Boolean, palette: ReaderPalette, listState: LazyListState,
    onBack: () -> Unit, onOpenPerson: (String) -> Unit, onLocate: (CharacterSource) -> Unit,
    onAvatar: () -> Unit, onEdit: () -> Unit, onCard: () -> Unit,
    onDeparted: (Boolean) -> Unit = {}, onCancelHighlight: (HighlightField, String) -> Unit = { _, _ -> },
    onClearHighlights: () -> Unit = {}
) {
    val dossier = person.dossier
    val links = remember(person, everyone) { egoLinks(person, everyone) }
    // 点按高亮后确认：一条高亮消掉整段（性格/关系会连同说明一起撤掉标记），内容本身不动。
    var clearing by remember(person.identity) { mutableStateOf<CharacterHighlight?>(null) }
    clearing?.let { highlight ->
        AlertDialog(onDismissRequest = { clearing = null },
            title = { Text(stringResource(R.string.characters_highlight_clear_title)) },
            text = { Text(highlight.before.takeIf(String::isNotBlank)?.let {
                stringResource(R.string.characters_highlight_before, it.take(240))
            } ?: stringResource(R.string.characters_highlight_clear_body)) },
            confirmButton = { TextButton(onClick = {
                onCancelHighlight(highlight.field, highlight.key)
                clearing = null
            }) { Text(stringResource(R.string.characters_highlight_clear_confirm)) } },
            dismissButton = { TextButton(onClick = { clearing = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
    Column(Modifier.fillMaxSize().testTag("dossier-${person.name}")) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = MoReadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.characters_back), tint = palette.muted) }
            Text(person.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = palette.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.dossier_menu_cd), tint = palette.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.dossier_menu_edit)) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.dossier_menu_avatar)) }, onClick = { menu = false; onAvatar() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.dossier_menu_card)) }, onClick = { menu = false; onCard() })
                    DropdownMenuItem(text = { Text(stringResource(if (person.departed) R.string.characters_departed_off
                        else R.string.characters_departed_on)) }, onClick = { menu = false; onDeparted(!person.departed) })
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().blockSheetDrag(listState).testTag("dossier-list"),
            contentPadding = PaddingValues(start = MoReadSpacing.xl, end = MoReadSpacing.xl, bottom = MoReadSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
            item(key = "hero") {
                Column(Modifier.fillMaxWidth().padding(top = MoReadSpacing.s, bottom = MoReadSpacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                    CharacterAvatar(person.name, thumbnails[person.identity], person.effectiveRole(), 96.dp, palette, onClick = onAvatar,
                        contentDescription = stringResource(R.string.dossier_avatar_cd, person.name))
                    // 名字过长时让主次/状态徽标换行，而不是把名字压没。
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s),
                        verticalArrangement = Arrangement.spacedBy(MoReadSpacing.xs),
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(person.name, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold, color = palette.onBackground)
                        val roleHighlight = person.highlightFor(HighlightField.ROLE)
                        person.effectiveRole()?.let { role ->
                            Text(characterRoleBadge(role), style = MaterialTheme.typography.labelMedium,
                                color = if (roleHighlight != null) palette.onAccent else palette.accent,
                                modifier = Modifier.then(if (roleHighlight != null)
                                    Modifier.background(palette.accent, RoundedCornerShape(6.dp)) else Modifier).padding(horizontal = 6.dp))
                            if (roleHighlight != null) NewMarker(palette) { clearing = roleHighlight }
                        }
                        CharacterStatusBadge(person, palette)
                    }
                    if (person.aliases.isNotEmpty()) Text(stringResource(R.string.dossier_aliases, person.aliases.joinToString("、")),
                        style = MaterialTheme.typography.bodySmall, color = palette.muted)
                    person.tagline().takeIf(String::isNotBlank)?.let { tagline ->
                        HighlightedText(tagline, person.highlightFor(HighlightField.TAGLINE), palette,
                            MaterialTheme.typography.bodyLarge, onTap = { highlight, _ -> clearing = highlight })
                    }
                }
            }
            if (person.highlights.isNotEmpty()) item(key = "updates") {
                Surface(shape = RoundedCornerShape(14.dp), color = palette.accentContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = MoReadSpacing.m, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.characters_highlight_banner), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = palette.accent)
                        Text(stringResource(R.string.characters_clear_highlights), style = MaterialTheme.typography.labelMedium,
                            color = palette.accent, modifier = Modifier.clickable(onClick = onClearHighlights).padding(MoReadSpacing.xs))
                    }
                }
            }
            // 手写值优先，其次档案；完整扫描的旧资料没有档案，回落到同名属性（同一属性有多个说法时取第一条）。
            fun attribute(kind: CharacterAttributeKind) = person.attributeValues(kind).firstOrNull()
            val facts = listOfNotNull(
                attribute(CharacterAttributeKind.GENDER)?.let { Triple(R.string.dossier_gender, it, HighlightField.GENDER) },
                attribute(CharacterAttributeKind.AGE)?.let { Triple(R.string.dossier_age, it, HighlightField.AGE) },
                attribute(CharacterAttributeKind.IDENTITY)?.let { Triple(R.string.dossier_identity, it, HighlightField.IDENTITY) })
            if (facts.isNotEmpty()) item(key = "facts") {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                    facts.forEach { (label, value, field) ->
                        Surface(shape = RoundedCornerShape(14.dp), color = palette.glass, modifier = Modifier.weight(if (label == R.string.dossier_identity) 2f else 1f).fillMaxHeight()) {
                            Column(Modifier.padding(horizontal = MoReadSpacing.m, vertical = 10.dp)) {
                                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = palette.muted)
                                HighlightedText(value, person.highlightFor(field), palette, MaterialTheme.typography.bodyMedium,
                                    onTap = { highlight, _ -> clearing = highlight }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            person.bio().takeIf(String::isNotBlank)?.let { bio -> item(key = "bio") {
                DossierSection(stringResource(R.string.dossier_bio), palette) {
                    HighlightedText(bio, person.highlightFor(HighlightField.BIO), palette,
                        MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp), onTap = { highlight, _ -> clearing = highlight })
                }
            } }
            val written = person.manualTraits()
            val traits = written ?: dossier?.personality.orEmpty()
            // 手写性格不是书里的那句话，没有可跳的原文；只有旧资料的属性条目才带依据。
            val legacyTraits = if (written == null && traits.isEmpty()) person.attributes.filter { it.kind == CharacterAttributeKind.PERSONALITY } else emptyList()
            if (traits.isNotEmpty() || legacyTraits.isNotEmpty()) item(key = "personality") {
                DossierSection(stringResource(R.string.dossier_personality), palette) {
                    Column(verticalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
                        traits.forEach { trait ->
                            val highlight = person.highlightFor(HighlightField.PERSONALITY, trait.trait)
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
                                DossierChip(trait.trait, palette,
                                    trait.anchor?.let { anchor -> { onLocate(CharacterSource.Anchor(anchor)) } },
                                    highlighted = highlight != null)
                                if (highlight != null) NewMarker(palette) { clearing = highlight }
                                if (trait.detail.isNotBlank()) HighlightedText(trait.detail, highlight, palette,
                                    MaterialTheme.typography.bodyMedium, Modifier.weight(1f).padding(top = 4.dp),
                                    onTap = { it, _ -> clearing = it })
                            }
                        }
                        if (legacyTraits.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            legacyTraits.forEach { attribute -> DossierChip(attribute.value, palette,
                                onClick = { onLocate(CharacterSource.Evidence(attribute.evidence)) }) }
                        }
                    }
                }
            }
            val appearance = person.attributeValues(CharacterAttributeKind.APPEARANCE).joinToString("；").takeIf(String::isNotBlank)
            if (appearance != null) item(key = "appearance") {
                DossierSection(stringResource(R.string.dossier_appearance), palette,
                    onJump = person.appearanceAnchor()?.let { anchor -> { onLocate(CharacterSource.Anchor(anchor)) } }) {
                    HighlightedText(appearance, person.highlightFor(HighlightField.APPEARANCE), palette,
                        MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp), onTap = { highlight, _ -> clearing = highlight })
                }
            }
            person.arc().takeIf(String::isNotBlank)?.let { arc -> item(key = "arc") {
                DossierSection(stringResource(R.string.dossier_arc), palette) {
                    HighlightedText(arc, person.highlightFor(HighlightField.ARC), palette,
                        MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp), onTap = { highlight, _ -> clearing = highlight })
                }
            } }
            if (links.isNotEmpty()) item(key = "relations") {
                DossierSection(stringResource(R.string.dossier_relations), palette) {
                    Column(verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                        CharacterEgoGraph(person, links.take(MAX_EGO_NODES), thumbnails, palette, onOpenPerson)
                        links.forEach { link ->
                            RelationRow(person, link, thumbnails, palette, onOpenPerson, onLocate,
                                person.relationHighlight(link, everyone)) { clearing = it }
                        }
                    }
                }
            }
            val notes = externalNotes.filter { it.character == person.name || it.character in person.aliases }
            if (notes.isNotEmpty()) item(key = "external") {
                var open by rememberSaveable(person.identity) { mutableStateOf(!progressBounded) }
                DossierSection(stringResource(R.string.characters_external_title, notes.size), palette,
                    trailing = stringResource(if (open) R.string.characters_external_collapse else R.string.characters_external_expand),
                    onTrailing = { open = !open }) {
                    Text(stringResource(if (progressBounded) R.string.characters_external_spoiler else R.string.characters_external_hint),
                        style = MaterialTheme.typography.labelSmall, color = palette.muted)
                    if (open) notes.forEach { note -> Text(note.text, style = MaterialTheme.typography.bodyMedium, color = palette.onBackground,
                        modifier = Modifier.padding(top = MoReadSpacing.s)) }
                }
            }
        }
    }
}

@Composable
private fun DossierSection(title: String, palette: ReaderPalette, onJump: (() -> Unit)? = null,
    trailing: String? = null, onTrailing: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = palette.glass, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(MoReadSpacing.l), verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = palette.muted)
                if (onJump != null) Text(stringResource(R.string.dossier_jump), style = MaterialTheme.typography.labelMedium, color = palette.accent,
                    modifier = Modifier.clickable(onClick = onJump).padding(MoReadSpacing.xs))
                if (trailing != null && onTrailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = palette.accent,
                    modifier = Modifier.clickable(onClick = onTrailing).padding(MoReadSpacing.xs))
            }
            content()
        }
    }
}

@Composable
private fun RelationRow(person: BookCharacter, link: EgoLink, thumbnails: Map<String, String>, palette: ReaderPalette,
    onOpenPerson: (String) -> Unit, onLocate: (CharacterSource) -> Unit,
    highlight: CharacterHighlight? = null, onClear: (CharacterHighlight) -> Unit = {}) {
    val open = link.identity?.let { identity -> { onOpenPerson(identity) } } ?: link.source?.let { source -> { onLocate(source) } }
    Surface(onClick = open ?: {}, enabled = open != null, shape = RoundedCornerShape(14.dp), color = palette.background.copy(alpha = .5f),
        modifier = Modifier.fillMaxWidth().testTag("relation-${person.identity}-${link.target}")) {
        Row(Modifier.padding(horizontal = MoReadSpacing.m, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
            CharacterAvatar(link.target, link.identity?.let(thumbnails::get), null, 36.dp, palette)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                    HighlightedText(link.target, highlight, palette, MaterialTheme.typography.titleSmall,
                        Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (link.relation.isNotBlank()) DossierChip(link.relation, palette, highlighted = highlight != null)
                    if (highlight != null) NewMarker(palette) { onClear(highlight) }
                }
                val secondary = listOfNotNull(
                    link.reverse.takeIf(String::isNotBlank)?.let { stringResource(R.string.dossier_relation_reverse, it) },
                    link.detail.takeIf(String::isNotBlank)
                ).joinToString(" · ")
                if (secondary.isNotBlank()) Text(secondary, style = MaterialTheme.typography.bodySmall, color = palette.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 关系高亮的 key 是 AI 写下的对象名；界面上可能已经换成人物卡上的正式称呼，两个名字都认。 */
private fun BookCharacter.relationHighlight(link: EgoLink, everyone: List<BookCharacter>): CharacterHighlight? {
    if (highlights.isEmpty()) return null
    val names = buildSet {
        add(link.target)
        link.identity?.let { add(it) }
        everyone.firstOrNull { it.identity == link.identity }?.let { other -> add(other.name); addAll(other.aliases) }
    }
    return highlights.firstOrNull { it.field == HighlightField.RELATION && it.key in names }
}

/** 档案页上的小关系图：本人在中心，关系人排成一圈，连线中点标关系。 */
@Composable
private fun CharacterEgoGraph(person: BookCharacter, links: List<EgoLink>, thumbnails: Map<String, String>, palette: ReaderPalette,
    onOpenPerson: (String) -> Unit) {
    val positions = remember(links.size) { egoPositions(links.size) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = palette.accent, fontWeight = FontWeight.Medium)
    val line = palette.glassBorder
    val labelBackground = palette.glassStrong
    // 一两位关系人左右摆开，用不着一整块方形画布。
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (links.size <= 2) 140.dp else 230.dp)) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            links.forEachIndexed { index, link ->
                val (x, y) = positions[index]
                val end = Offset(x * size.width, y * size.height)
                drawLine(line, center, end, 1.5.dp.toPx())
                val label = link.label()
                if (label.isNotBlank()) {
                    val layout = measurer.measure(label, labelStyle, maxLines = 1)
                    val mid = Offset((center.x + end.x) / 2, (center.y + end.y) / 2)
                    drawRoundRect(labelBackground, Offset(mid.x - layout.size.width / 2f - 4f, mid.y - layout.size.height / 2f - 1f),
                        Size(layout.size.width + 8f, layout.size.height + 2f), androidx.compose.ui.geometry.CornerRadius(8f))
                    drawText(layout, topLeft = Offset(mid.x - layout.size.width / 2f, mid.y - layout.size.height / 2f))
                }
            }
        }
        val node = 40.dp
        val nodePx = with(density) { node.toPx() }
        links.forEachIndexed { index, link ->
            val (x, y) = positions[index]
            Column(Modifier.offset { IntOffset((x * widthPx - nodePx / 2).roundToInt(), (y * heightPx - nodePx / 2).roundToInt()) }
                .width(node).then(if (link.identity != null) Modifier.clickable { onOpenPerson(link.identity) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally) {
                CharacterAvatar(link.target, link.identity?.let(thumbnails::get), null, node, palette)
            }
            Text(link.target, style = MaterialTheme.typography.labelSmall, color = palette.onBackground, maxLines = 1,
                modifier = Modifier.offset { IntOffset((x * widthPx - nodePx).roundToInt(), (y * heightPx + nodePx / 2).roundToInt()) }
                    .width(node * 2), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Box(Modifier.align(Alignment.Center)) {
            CharacterAvatar(person.name, thumbnails[person.identity], person.effectiveRole(), 56.dp, palette)
        }
    }
}

/** 全书关系图：确定性力导向布局（同一份资料永远同一张图），双指缩放平移，点人物进档案。 */
@Composable
internal fun BookRelationGraph(people: List<BookCharacter>, thumbnails: Map<String, String>, palette: ReaderPalette,
    onOpenPerson: (String) -> Unit, modifier: Modifier = Modifier) {
    val graph = remember(people) { relationGraph(people) }
    val positions = remember(graph) { RelationLayout.layout(graph.nodes.map { it.identity }, graph.edges.map { it.from to it.to }) }
    var scale by remember(graph) { mutableFloatStateOf(1f) }
    var pan by remember(graph) { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = palette.muted)
    val line = palette.glassBorder
    val labelBackground = palette.background
    Box(modifier.fillMaxSize().clipToBounds().testTag("characters-graph")) {
        if (graph.edges.isEmpty()) Text(stringResource(R.string.characters_graph_empty), style = MaterialTheme.typography.bodySmall,
            color = palette.muted, modifier = Modifier.align(Alignment.TopCenter).padding(top = MoReadSpacing.s))
        else Text(stringResource(R.string.characters_graph_hint), style = MaterialTheme.typography.labelSmall,
            color = palette.muted, modifier = Modifier.align(Alignment.TopCenter).padding(top = MoReadSpacing.s))
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = MoReadSpacing.xxl)
            .pointerInput(graph) {
                detectTransformGestures { _, delta, zoom, _ ->
                    scale = (scale * zoom).coerceIn(.6f, 3f)
                    pan += delta
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }) {
            val density = LocalDensity.current
            // 节点下方还有名字：上下比左右多留一些，最外圈的人物不被裁掉。
            val padX = with(density) { 48.dp.toPx() }
            val padY = with(density) { 60.dp.toPx() }
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            fun at(identity: String): Offset {
                val (x, y) = positions[identity] ?: (.5f to .5f)
                return Offset(padX + x * (widthPx - 2 * padX), padY + y * (heightPx - 2 * padY))
            }
            Canvas(Modifier.fillMaxSize()) {
                graph.edges.forEach { edge ->
                    val a = at(edge.from)
                    val b = at(edge.to)
                    drawLine(line, a, b, 1.2.dp.toPx())
                    if (edge.label.isNotBlank()) {
                        val layout = measurer.measure(edge.label, labelStyle, maxLines = 1)
                        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                        drawRect(labelBackground, Offset(mid.x - layout.size.width / 2f - 2f, mid.y - layout.size.height / 2f),
                            Size(layout.size.width + 4f, layout.size.height.toFloat()))
                        drawText(layout, topLeft = Offset(mid.x - layout.size.width / 2f, mid.y - layout.size.height / 2f))
                    }
                }
            }
            graph.nodes.forEach { node ->
                val size = (30 + 26 * node.weight).dp
                val sizePx = with(density) { size.toPx() }
                val p = at(node.identity)
                Column(Modifier.offset { IntOffset((p.x - sizePx).roundToInt(), (p.y - sizePx / 2).roundToInt()) }.width(size * 2)
                    .clickable { onOpenPerson(node.identity) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    CharacterAvatar(node.name, thumbnails[node.identity], node.role, size, palette)
                    Text(node.name, style = MaterialTheme.typography.labelSmall, color = palette.onBackground, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

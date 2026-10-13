package com.mozhi.reader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import com.mozhi.reader.R
import com.mozhi.reader.ai.knowledge.ExternalCharacterNote
import com.mozhi.reader.ai.knowledge.BookCharacterGuide
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.knowledge.*
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.ui.components.blockSheetDrag

internal data class ReaderKnowledgeActions(
    val preview: (Int) -> Unit = {}, val cancel: (Int) -> Unit = {}, val delete: (Int) -> Unit = {},
    val locate: (ChapterKnowledgeEntity, KnowledgeFact) -> Unit = { _, _ -> },
    val previewCharacters: (Boolean, Boolean, List<CharacterTarget>) -> Unit = { _, _, _ -> },
    val cancelCharacters: () -> Unit = {}, val deleteCharacters: () -> Unit = {},
    val locateCharacter: (BookCharacterGuideEntity, CharacterEvidence) -> Unit = { _, _ -> },
    val saveCharacterCard: (ExtractedCharacterCard) -> Unit = {},
    val saveCharacter: (String?, String, String) -> Unit = { _, _, _ -> },
    val openLook: (String) -> Unit = {},
    val saveOutline: (Int, String) -> Unit = { _, _ -> },
    val locateAnchor: (BookCharacterGuideEntity, SourceAnchor) -> Unit = { _, _ -> }
)

/** 目录弹层「人物」页签的序号，供进度球直接打开。 */
internal const val KNOWLEDGE_CHARACTERS_TAB = 2

@Composable
internal fun ReaderKnowledgeContentsSheet(
    bookId: Long, contentRevision: Int, chapters: List<ChapterEntity>, tocEntries: List<BookTocEntryEntity>,
    currentChapterIndex: Int, palette: ReaderPalette, onChapterClick: (Int, String) -> Unit,
    onLocate: (Int, Int) -> Unit, onDismiss: () -> Unit, initialTab: Int = 0,
    bookmarks: List<com.mozhi.reader.core.database.entity.BookmarkEntity> = emptyList(),
    onBookmarkClick: (com.mozhi.reader.core.database.entity.BookmarkEntity) -> Unit = {},
    onDeleteBookmark: (Long) -> Unit = {},
    viewModel: ReaderKnowledgeViewModel = hiltViewModel()
) {
    val observed by viewModel.state.collectAsStateWithLifecycle()
    val previews: com.mozhi.reader.feature.illustration.CharacterLookPreviewViewModel = hiltViewModel()
    val thumbnails by previews.thumbnails.collectAsStateWithLifecycle()
    LaunchedEffect(bookId) { previews.bind(bookId) }
    val state = observed.takeIf { it.bookId == bookId } ?: KnowledgeUiState(bookId)
    val locate by rememberUpdatedState(onLocate)
    var imageCharacter by remember(bookId) { mutableStateOf<String?>(null) }
    imageCharacter?.let { key ->
        com.mozhi.reader.feature.illustration.ImageStudio(bookId,
            com.mozhi.reader.feature.illustration.StudioEntry(page = com.mozhi.reader.feature.illustration.StudioPage.LOOK, characterKey = key),
            onDismiss = { imageCharacter = null }, onEvidence = { evidence ->
                if (evidence.chapterIndex != null && evidence.start != null) {
                    imageCharacter = null; onLocate(evidence.chapterIndex, evidence.start)
                }
            })
    }
    LaunchedEffect(bookId, contentRevision) { viewModel.bind(bookId) }
    LaunchedEffect(viewModel) { viewModel.locateEvents.collect { (chapter, offset) -> locate(chapter, offset) } }
    ReaderKnowledgePages(state.copy(lookThumbnails = thumbnails), chapters, tocEntries, currentChapterIndex, palette, onChapterClick, onDismiss,
        ReaderKnowledgeActions(viewModel::preview, viewModel::cancel, viewModel::delete, viewModel::locate,
            viewModel::previewCharacters, viewModel::cancelCharacters, viewModel::deleteCharacters, viewModel::locateCharacter,
            viewModel::saveCharacterCard, viewModel::saveCharacter, openLook = { imageCharacter = it },
            saveOutline = viewModel::saveOutline, locateAnchor = viewModel::locateAnchor), initialTab = initialTab,
        bookmarks = bookmarks, onBookmarkClick = onBookmarkClick, onDeleteBookmark = onDeleteBookmark)
    // 人物整理会跑好几分钟：开始时顺手要通知权限，切到后台也能在通知栏看到进度（拒绝也不影响整理）。
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    val startCharacters = {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.generateCharacters()
    }
    // 大纲的确认框由 OutlinePreviewHost 提供（1.4.0 的旧确认框已被它取代：手改过的大纲要在同一个框里警告会被覆盖）。
    OutlinePreviewHost(state, viewModel::dismissPreview, viewModel::generate)
    state.pendingCharacters?.takeIf { it.quick }?.let { plan -> AlertDialog(
        onDismissRequest = viewModel::dismissPreview,
        title = { Text(stringResource(if (plan.progressBounded) R.string.characters_quick_title_bounded else R.string.characters_quick_title_full)) },
        text = { Text(buildString {
            append(plan.bookTitle).append('\n')
            append(stringResource(if (plan.progressBounded) R.string.characters_scope_bounded else R.string.characters_scope_full, plan.chapterCount))
            append('\n')
            append(stringResource(R.string.characters_quick_models, plan.modelLabel)).append("\n\n")
            append(stringResource(R.string.characters_quick_body, plan.maximumRequests))
            plan.webModelLabel?.let { webModel ->
                append("\n\n")
                append(stringResource(if (plan.progressBounded) R.string.characters_quick_web_bounded else R.string.characters_quick_web_full, webModel))
            }
        }) },
        confirmButton = { TextButton(onClick = startCharacters) { Text(stringResource(R.string.characters_quick_start)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissPreview) { Text(stringResource(R.string.characters_cancel)) } }
    ) }
    // 点名扫描有自己的确认文案：名单是用户给的，与「整理主要人物」不是一回事。
    state.pendingCharacters?.takeIf { it.targeted }?.let { plan -> AlertDialog(
        onDismissRequest = viewModel::dismissPreview,
        title = { Text(stringResource(R.string.characters_targets_title)) },
        text = { Text(buildString {
            append(plan.bookTitle).append('\n')
            append(stringResource(if (plan.progressBounded) R.string.characters_scope_bounded else R.string.characters_scope_full, plan.chapterCount))
            append('\n')
            append(stringResource(R.string.characters_targets_models, plan.modelLabel)).append("\n\n")
            append(stringResource(R.string.characters_targets_body, plan.targets.joinToString("、") { it.name }.take(160)))
            append('\n')
            append(stringResource(R.string.characters_targets_cost, plan.maximumRequests))
        }) },
        confirmButton = { TextButton(onClick = viewModel::generateCharacters) { Text(stringResource(R.string.characters_targets_start)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissPreview) { Text(stringResource(R.string.characters_cancel)) } }
    ) }
    state.pendingCharacters?.takeUnless { it.quick || it.targeted }?.let { plan -> AlertDialog(
        onDismissRequest = viewModel::dismissPreview,
        title = { Text(when {
            plan.resuming -> if (plan.progressBounded) "继续提取读过的人物？" else "继续提取全书人物？"
            plan.progressBounded -> "提取读过的人物？"
            else -> "提取全书人物？"
        }) },
        text = { Text(buildString {
            append(plan.bookTitle).append('\n')
            append(if (plan.progressBounded) "读到此处 · 前 ${plan.chapterCount} 章" else "整本书 · ${plan.chapterCount} 章")
            append(" · ${plan.sourceCharacters} 字\n模型：${plan.modelLabel}\n\n")
            append(if (plan.progressBounded) {
                "只发送已经读过的正文，最后一章截到当前进度，资料不会剧透后续情节。"
            } else {
                "将逐章发送正文给模型，包含尚未阅读的内容，资料可能涉及后续情节。"
            })
            append("全程最多 ${plan.maximumRequests} 次调用，按服务商计费。")
            append("已核对过的段落会直接复用，不再重复计费。")
            if (plan.resuming) append("\n已保存 ${plan.completedParts} 段进度，核对通过后会直接复用。")
            append("\n\n可以随时停止，完成后整份保存。")
        }) },
        confirmButton = { TextButton(onClick = startCharacters) {
            Text(when {
                plan.resuming -> "继续提取"
                plan.progressBounded -> "提取到此处"
                else -> "提取全书"
            })
        } },
        dismissButton = { TextButton(onClick = viewModel::dismissPreview) { Text("取消") } }
    ) }
}

/** 生成前的预览框：手改过这一章的大纲时，把「会被覆盖」的警告并进同一个框，不另叠一层确认。
 * 单独抽出来是因为它要能脱离 viewModel 渲染，回归测试才看得到它的两副面孔。
 */
@Composable
internal fun OutlinePreviewHost(state: KnowledgeUiState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val plan = state.pending ?: return
    val manual = state.snapshot.chapters.any {
        it.entry.chapterIndex == plan.source.chapterIndex && it.content.manualOutline
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成章节大纲？") },
        text = { Text("${plan.source.chapterTitle}\n${if (plan.source.partial) "已读部分" else "完整章节"} · ${plan.source.text.length} 字\n模型：${plan.modelLabel}\n最多 ${plan.maximumRequests} 次调用，按服务商计费。\n\n大纲会保存在本机。可以同时生成其他章节，离开页面后仍会继续。" +
            if (manual) "\n\n" + stringResource(R.string.reader_outline_overwrite_warning) else "") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (manual) stringResource(R.string.reader_outline_overwrite_confirm) else "生成大纲")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** The same page body is exercised inside the real NavigationSheet by gesture regression tests. */
@Composable
internal fun ReaderKnowledgePages(
    state: KnowledgeUiState, chapters: List<ChapterEntity>, tocEntries: List<BookTocEntryEntity>,
    currentChapterIndex: Int, palette: ReaderPalette, onChapterClick: (Int, String) -> Unit,
    onDismiss: () -> Unit, actions: ReaderKnowledgeActions = ReaderKnowledgeActions(), initialTab: Int = 0,
    bookmarks: List<com.mozhi.reader.core.database.entity.BookmarkEntity> = emptyList(),
    onBookmarkClick: (com.mozhi.reader.core.database.entity.BookmarkEntity) -> Unit = {},
    onDeleteBookmark: (Long) -> Unit = {}
) {
    var tab by rememberSaveable(state.bookId) { mutableIntStateOf(initialTab) }
    val pages = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }
    val book = state.snapshot.book
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ReaderKnowledgeTabs(tab, palette, onDismiss,
                bookTitle = book?.title.orEmpty(),
                position = if (chapters.isEmpty()) "" else stringResource(R.string.toc_position, currentChapterIndex + 1, chapters.size),
                coverPath = book?.coverPath
            ) { tab = it }
            Box(Modifier.weight(1f)) {
                pages.SaveableStateProvider("${state.bookId}:$tab") {
                    when (tab) {
                        0 -> ContentsSheet(chapters, tocEntries, currentChapterIndex, palette, onChapterClick, onContinue = onDismiss)
                        1 -> ChapterKnowledgePanel(state, chapters, currentChapterIndex, palette, actions)
                        KNOWLEDGE_BOOKMARKS_TAB -> BookmarksSheet(bookmarks, palette, onBookmarkClick, onDeleteBookmark, showHeader = false)
                        else -> BookCharactersPanel(state, chapters, palette, actions)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** 目录弹层「书签」页签的序号。 */
internal const val KNOWLEDGE_BOOKMARKS_TAB = 3

/**
 * 弹层头部：书封小样 + 朱砂眉标 + 宋体书名 + 位置；下面一条四段的分段控件
 * （下沉的轨道上浮起一块纸色滑块，不再用强调色填满）。
 */
@Composable
internal fun ReaderKnowledgeTabs(
    selectedTab: Int, palette: ReaderPalette, onDismiss: () -> Unit = {},
    bookTitle: String = "", position: String = "", coverPath: String? = null, onSelect: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(92.dp).padding(start = 22.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TocCoverThumb(bookTitle, coverPath, palette)
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.toc_eyebrow), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                    color = palette.seal, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(bookTitle.ifBlank { stringResource(R.string.toc_eyebrow) },
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
                    color = palette.onBackground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (position.isNotBlank()) {
                    Text(position, style = MaterialTheme.typography.labelSmall, color = palette.muted, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = CircleShape, color = palette.onBackground.copy(alpha = if (palette.isDark) 0.10f else 0.05f)) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.toc_close), tint = palette.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(46.dp)
            .clip(RoundedCornerShape(16.dp)).background(palette.onBackground.copy(alpha = if (palette.isDark) 0.08f else 0.055f)).padding(4.dp)
            .selectableGroup().testTag("knowledge-tabs"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // 页签序号与页面一致：0 目录、1 大纲、2 人物、3 书签。
            listOf(R.string.toc_tab_contents, R.string.toc_tab_outline, R.string.toc_tab_characters, R.string.toc_tab_bookmarks)
                .forEachIndexed { index, labelRes ->
                    val selected = index == selectedTab
                    val background by animateColorAsState(if (selected) palette.background else Color.Transparent, label = "navigation-tab")
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(12.dp)) else Modifier)
                        .clip(RoundedCornerShape(12.dp)).background(background)
                        .selectable(selected, role = Role.Tab, onClick = { onSelect(index) }),
                        contentAlignment = Alignment.Center) {
                        Text(stringResource(labelRes), style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) palette.accent else palette.muted)
                    }
                }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun TocCoverThumb(title: String, coverPath: String?, palette: ReaderPalette) {
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 7.dp, bottomEnd = 7.dp)
    Box(
        Modifier.size(width = 46.dp, height = 64.dp).shadow(4.dp, shape).clip(shape)
            .background(if (palette.isDark) Color(0xFF3A4652) else Color(0xFF243A44)),
        contentAlignment = Alignment.Center
    ) {
        val file = remember(coverPath) {
            coverPath?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::exists)
        }
        if (file != null) {
            coil3.compose.AsyncImage(model = file, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(title.take(4).toList().joinToString("\n"), color = Color(0xFFF3EFE6),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold, lineHeight = 12.sp, fontSize = 10.sp))
        }
    }
}

private typealias File = java.io.File

@Composable
private fun KnowledgeHeading(title: String, subtitle: String, palette: ReaderPalette, onInfo: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(78.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = palette.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        }
        IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, "书籍资料说明", tint = palette.muted, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
internal fun ChapterKnowledgePanel(
    state: KnowledgeUiState, chapters: List<ChapterEntity>, currentChapterIndex: Int,
    palette: ReaderPalette, actions: ReaderKnowledgeActions
) {
    val snapshot = state.snapshot
    val scope = snapshot.book?.let(ReadingScope::uptoProgress)
    val eligible = remember(chapters, scope) { chapters.filter { chapter ->
        chapter.charCount > 0 && scope != null && scope.allowsChapter(chapter.chapterIndex) &&
            (chapter.chapterIndex < scope.maxChapterIndex || scope.maxCharOffset > 0)
    } }
    val current = eligible.firstOrNull { it.chapterIndex == currentChapterIndex } ?: eligible.lastOrNull()
    val savedByChapter = remember(snapshot.chapters) { snapshot.chapters.associateBy { it.entry.chapterIndex } }
    var expandedChapters by rememberSaveable(state.bookId) { mutableStateOf(listOf(currentChapterIndex)) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    var information by remember { mutableStateOf(false) }
    var editingOutline by remember(state.bookId) { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        KnowledgeHeading("章节大纲", "已保存 ${snapshot.chapters.size} 章 · 展开回顾本章来龙去脉", palette) { information = true }
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            FilledTonalButton(onClick = { current?.let { actions.preview(it.chapterIndex) } },
                enabled = current != null && !state.loading && state.tasks[current.chapterIndex]?.active != true && state.previewingChapter == null,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.accentContainer, contentColor = palette.accent)) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (current?.chapterIndex == currentChapterIndex) "生成当前章" else "生成已读章")
            }
            TextButton(onClick = {
                expandedChapters = if (expandedChapters.isEmpty()) eligible.map { it.chapterIndex } else emptyList()
            }) { Text(if (expandedChapters.isEmpty()) "全部展开" else "全部收起", color = palette.accent) }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()
            .blockSheetDrag(listState).testTag("outline-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (eligible.isEmpty()) item(key = "empty") {
                KnowledgeEmpty("留住每一章的脉络", if (state.loading) "正在读取章节…" else "读过正文后，手动生成章节梗概，随时展开回顾。", palette)
            }
            items(eligible, key = { "outline-${it.chapterIndex}" }, contentType = { "outline" }) { chapter ->
                val saved = savedByChapter[chapter.chapterIndex]
                val expanded = chapter.chapterIndex in expandedChapters
                val task = state.tasks[chapter.chapterIndex]
                val busy = task?.active == true
                Column(Modifier.testTag("outline-${chapter.chapterIndex}")) {
                    Surface(onClick = {
                        expandedChapters = if (expanded) expandedChapters - chapter.chapterIndex else expandedChapters + chapter.chapterIndex
                    }, shape = RoundedCornerShape(16.dp), color = palette.glass, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                                if (expanded) "收起章节大纲" else "展开章节大纲", tint = palette.muted, modifier = Modifier.size(20.dp))
                            Text(chapter.title, Modifier.weight(1f).padding(horizontal = 8.dp), color = palette.onBackground,
                                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(when { busy -> "生成中"; task?.error != null -> "待重试"; saved != null -> "已保存"; else -> "待生成" },
                                color = if (busy || saved != null) palette.accent else palette.muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (expanded) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            (if (saved == null) "还没有这一章的大纲。" else if (saved.entry.sourceEnd == chapter.charCount)
                                "完整章节" else "已读部分 · 前 ${saved.entry.sourceEnd} 字") +
                                (if (saved?.content?.manualOutline == true) " · " + stringResource(R.string.reader_outline_edited_badge) else ""),
                            color = palette.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 10.dp))
                        if (saved != null) {
                            Text(saved.content.readableOutline, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 29.sp),
                                color = palette.onBackground)
                            var evidence by rememberSaveable(chapter.chapterIndex) { mutableStateOf(false) }
                            TextButton(onClick = { evidence = !evidence }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                                Text(if (evidence) "收起原文依据" else "原文依据 · ${saved.content.summary.size} 处", color = palette.muted)
                            }
                            if (evidence) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                saved.content.summary.forEach { fact ->
                                    Text(fact.text, color = palette.onBackground, style = MaterialTheme.typography.bodyMedium)
                                    Text("“${fact.quote}”", color = palette.muted, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 22.sp))
                                    TextButton(onClick = { actions.locate(saved.entry, fact) }, contentPadding = PaddingValues(0.dp)) {
                                        Text("核对原文", color = palette.accent)
                                    }
                                }
                            }
                        }
                        // Status shares a fixed action row, so progress updates never resize the item.
                        Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (busy) TextButton(onClick = { actions.cancel(chapter.chapterIndex) }) { Text("停止本章", color = palette.accent) }
                            else TextButton(onClick = { actions.preview(chapter.chapterIndex) }, enabled = state.previewingChapter != chapter.chapterIndex) {
                                Text(if (state.previewingChapter == chapter.chapterIndex) "准备中…" else if (saved == null) "生成概括" else "重新生成", color = palette.accent)
                            }
                            if (saved != null) TextButton(onClick = { editingOutline = chapter.chapterIndex }, enabled = !busy,
                                modifier = Modifier.testTag("outline-edit")) {
                                Text(stringResource(R.string.reader_outline_edit), color = palette.accent)
                            }
                            if (saved != null) TextButton(onClick = { deleting = chapter.chapterIndex }, enabled = !busy) { Text("删除", color = palette.muted) }
                            Text(task?.error ?: task?.progress.orEmpty(), modifier = Modifier.weight(1f).padding(start = 8.dp),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                color = if (task?.error != null) MaterialTheme.colorScheme.error else palette.muted)
                        }
                    }
                }
            }
            if (snapshot.staleChapters > 0) item(key = "stale") {
                Text("有 ${snapshot.staleChapters} 章大纲因正文或已读范围变化而隐藏，可重新生成。", color = palette.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (information) KnowledgeInfoDialog("章节大纲", "从已经读过的内容生成连贯梗概，长章会整合为完整脉络。\n\n每章独立生成和停止，可同时提交多章，最多同时请求两个模型任务，其余自动排队。离开页面后继续运行；退出应用后需重新生成未完成的章节。\n\n大纲保存在本机，原文依据可展开核对。修改正文或重置已读范围后，旧大纲会隐藏。", { information = false })
    deleting?.let { index -> ConfirmKnowledgeDelete("删除本章大纲？", "保存的大纲及其原文依据将被删除。", { deleting = null }) {
        deleting = null; actions.delete(index)
    } }
    editingOutline?.let { index -> savedByChapter[index]?.let { saved ->
        ChapterOutlineEditorDialog(initial = saved.content.readableOutline, onDismiss = { editingOutline = null }, onSave = { text ->
            editingOutline = null; actions.saveOutline(index, text)
        })
    } }
}

@Composable
internal fun BookCharactersPanel(state: KnowledgeUiState, chapters: List<ChapterEntity>, palette: ReaderPalette, actions: ReaderKnowledgeActions) {
    val saved = state.characters.saved
    val people = saved?.guide?.characters.orEmpty()
    var card by remember(state.bookId) { mutableStateOf<ExtractedCharacterCard?>(null) }
    var editing by remember(state.bookId) { mutableStateOf<BookCharacter?>(null) }
    var editorVisible by rememberSaveable(state.bookId) { mutableStateOf(false) }
    var query by rememberSaveable(state.bookId) { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var pointing by rememberSaveable { mutableStateOf(false) }
    var information by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val task = state.characterTask
    val busy = task?.active == true
    val listState = rememberLazyListState()
    val filtered = remember(people, query) { people.filter { person ->
        (listOf(person.name, person.identity) + person.aliases).any { it.contains(query.trim(), ignoreCase = true) }
    } }
    val titles = remember(chapters) { chapters.associate { it.chapterIndex to it.title } }
    var targetsInput by rememberSaveable(state.bookId) { mutableStateOf("") }
    var progressBounded by rememberSaveable(state.bookId, saved?.guide?.progressBounded) { mutableStateOf(saved?.guide?.progressBounded ?: true) }
    var quickMode by rememberSaveable(state.bookId) { mutableStateOf(saved?.guide?.mode != BookCharacterGuide.MODE_FULL || saved == null) }
    var openedIdentity by rememberSaveable(state.bookId) { mutableStateOf<String?>(null) }
    var graphMode by rememberSaveable(state.bookId) { mutableStateOf(false) }
    // 档案页是弹层里的二级页：系统返回先回到人物列表，再交给弹层关闭。
    androidx.activity.compose.BackHandler(enabled = openedIdentity != null) { openedIdentity = null }
    val locateSource: (CharacterSource) -> Unit = { source ->
        saved?.let { visible ->
            when (source) {
                is CharacterSource.Anchor -> actions.locateAnchor(visible.entry, source.anchor)
                is CharacterSource.Evidence -> actions.locateCharacter(visible.entry, source.evidence)
            }
        }
    }
    val idleText = when {
        state.characters.pendingProfiles > 0 -> stringResource(R.string.characters_pending_resume, state.characters.pendingProfiles)
        state.characters.checkpointParts > 0 -> "已有提取进度，点击「提取」继续"
        state.characters.outdated -> "正文已变化，可重新提取；手动资料保留"
        // 点名扫描的结果并进旧资料，scannedChapters 沿用旧值，不能说成「已扫描全书」。
        saved?.guide?.mode == BookCharacterGuide.MODE_TARGETED -> stringResource(R.string.characters_targets_status, people.size)
        saved?.guide?.scannedChapters == 0 -> "手动整理"
        saved != null -> if (saved.guide.progressBounded) "已扫描到第 ${saved.guide.scannedChapters} 章" else "已扫描全书 ${saved.guide.scannedChapters} 章"
        else -> "可从正文提取，也可手动添加"
    }
    androidx.compose.animation.AnimatedContent(targetState = openedIdentity, label = "character-dossier",
        transitionSpec = {
            val forward = targetState != null
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(260)) { if (forward) it / 6 else -it / 6}) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
        }) { identity ->
        val person = identity?.let { id -> people.firstOrNull { it.identity == id } }
        if (person != null) {
            CharacterDossierPage(person, people, titles, state.lookThumbnails, saved?.guide?.externalNotes.orEmpty(),
                saved?.guide?.progressBounded == true, palette, rememberLazyListState(),
                onBack = { openedIdentity = null }, onOpenPerson = { openedIdentity = it }, onLocate = locateSource,
                onAvatar = { actions.openLook(person.identity) }, onEdit = { editing = person; editorVisible = true },
                onCard = { card = ExtractedCharacterCard.from(person) })
        } else Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp).testTag("character-tools"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${people.size} 位人物", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = palette.onBackground)
                    if (query.isNotBlank()) Text("${filtered.size} 位匹配 · $query", style = MaterialTheme.typography.labelSmall,
                        color = palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(shape = CircleShape, color = palette.glass) {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "查找书中人物", tint = palette.muted, modifier = Modifier.size(20.dp)) }
                }
                Surface(shape = CircleShape, color = if (graphMode) palette.accentContainer else palette.glass) {
                    IconButton(onClick = { graphMode = !graphMode }, enabled = people.size > 1) {
                        Icon(if (graphMode) Icons.Outlined.ViewAgenda else Icons.Outlined.Hub,
                            stringResource(if (graphMode) R.string.characters_toggle_list_cd else R.string.characters_toggle_graph_cd),
                            tint = if (graphMode) palette.accent else palette.muted, modifier = Modifier.size(20.dp))
                    }
                }
                Surface(shape = CircleShape, color = palette.glass) {
                    IconButton(onClick = { editing = null; editorVisible = true }, enabled = !state.loading) {
                        Icon(Icons.Outlined.Add, "新增人物", tint = palette.accent, modifier = Modifier.size(20.dp))
                    }
                }
                FilledTonalButton(onClick = { options = true }, shape = CircleShape, contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.accentContainer, contentColor = palette.accent)) {
                    Text(if (busy) "提取中" else "提取")
                }
            }
            // Fixed status line: progress changes never move the list or its scroll anchor.
            CharacterTaskStrip(task, idleText, palette)
            if (graphMode && people.size > 1) {
                BookRelationGraph(filtered.ifEmpty { people }, state.lookThumbnails, palette, onOpenPerson = { openedIdentity = it },
                    modifier = Modifier.weight(1f).fillMaxWidth())
            } else LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().blockSheetDrag(listState).testTag("characters-list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filtered.isEmpty()) item(key = "empty") {
                    KnowledgeEmpty(if (query.isNotBlank()) "没有找到这个人物" else "还没有人物",
                        if (query.isNotBlank()) "试试其他姓名，或清除搜索。" else "点击「提取」汇集书中人物，或用 + 手动添加。", palette)
                }
                saved?.guide?.externalNotes?.takeIf { it.isNotEmpty() && query.isBlank() }?.let { notes ->
                    item(key = "external-notes", contentType = "external") { ExternalNotesCard(notes, saved.guide.progressBounded, palette) }
                }
                characterGroups(filtered).forEach { group ->
                    group.role?.let { role -> item(key = "group-$role", contentType = "group") {
                        Text("${characterGroupTitle(role)} · ${group.people.size}", style = MaterialTheme.typography.labelLarge, color = palette.muted,
                            modifier = Modifier.padding(top = 4.dp))
                    } }
                    items(group.people, key = { it.identity }, contentType = { "character" }) { person ->
                        CharacterRosterCard(person, state.lookThumbnails[person.identity], palette, onOpen = { openedIdentity = person.identity },
                            onAvatar = { actions.openLook(person.identity) }) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.characters_person_actions, person.name), tint = palette.muted)
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.dossier_menu_edit)) }, onClick = { menu = false; editing = person; editorVisible = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.dossier_menu_card)) }, onClick = { menu = false; card = ExtractedCharacterCard.from(person) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (searching) {
        var draft by rememberSaveable { mutableStateOf(query) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { searching = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            // A bounded window also keeps IME and text-field remeasurement away from the sheet.
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 400.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("查找书中人物", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(draft, { draft = it.take(80) }, label = { Text("姓名或称呼") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("character-search"))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { query = ""; searching = false }) { Text("清除搜索") }
                            TextButton(onClick = { searching = false }) { Text("取消") }
                            TextButton(onClick = { query = draft.trim(); searching = false }) { Text("查找") }
                        }
                    }
                }
            }
        }
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text("人物提取") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().selectableGroup().testTag("character-scope"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to "读到此处", false to "全书").forEach { (bounded, label) ->
                    FilterChip(selected = progressBounded == bounded, onClick = { progressBounded = bounded }, enabled = !busy, label = { Text(label) })
                }
            }
            Text(if (progressBounded) "只扫描读过的正文。" else "包含未读章节，资料可能涉及后续情节。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().selectableGroup().testTag("character-mode"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to stringResource(R.string.characters_mode_quick), false to stringResource(R.string.characters_mode_full)).forEach { (quick, label) ->
                    FilterChip(selected = quickMode == quick, onClick = { quickMode = quick }, enabled = !busy && state.characters.checkpointParts == 0, label = { Text(label) })
                }
            }
            Text(stringResource(if (quickMode) R.string.characters_mode_quick_hint else R.string.characters_mode_full_hint), style = MaterialTheme.typography.bodySmall)
            // 名单输入有自己的一页：AlertDialog 装文本框会让重组停不下来（见 CharacterScanTargetsDialog）。
            TextButton(onClick = { options = false; pointing = true }, enabled = !busy) { Text(stringResource(R.string.characters_targets_entry)) }
            TextButton(onClick = { options = false; information = true }) { Icon(Icons.Outlined.Info, "书籍资料说明"); Spacer(Modifier.width(6.dp)); Text("提取说明") }
            if (saved != null || state.characters.checkpointParts > 0) TextButton(onClick = { options = false; deleting = true }, enabled = !busy) { Text("删除人物资料") }
        }
    }, confirmButton = {
        TextButton(onClick = {
            options = false
            if (busy) actions.cancelCharacters()
            else actions.previewCharacters(progressBounded, quickMode && state.characters.checkpointParts == 0, emptyList())
        }, enabled = !state.loading && !state.preparingCharacters) {
            Text(when {
                busy -> "停止提取"
                state.preparingCharacters -> "准备中…"
                state.characters.checkpointParts > 0 -> "继续提取"
                saved != null -> if (progressBounded) "更新到当前进度" else "更新全书人物"
                else -> if (progressBounded) "提取读过的人物" else "提取全书人物"
            })
        }
    }, dismissButton = { TextButton(onClick = { options = false }) { Text("关闭") } })
    if (pointing) CharacterScanTargetsDialog(input = targetsInput, onInputChange = { targetsInput = it }, enabled = !busy,
        onDismiss = { pointing = false }, onConfirm = { targets ->
            pointing = false
            actions.previewCharacters(progressBounded, false, targets)
        })
    if (editorVisible) BookCharacterEditorDialog(editing, people, onDismiss = { editorVisible = false }, onSave = { key, name, description ->
        actions.saveCharacter(key, name, description); editorVisible = false
    })
    card?.let { draft -> ExtractedCharacterCardDialog(draft, onDismiss = { card = null }, onSave = { actions.saveCharacterCard(it); card = null }) }
    if (information) KnowledgeInfoDialog("书中人物", "由 AI 逐章扫描正文，提取人物、身份与关系。\n\n「读到此处」只发送已经读过的内容，最后一章截到当前进度；「全书」包括还没读到的章节，可能涉及后续情节。\n\nAI 资料保留原文依据，可随时核对；手动新增或编辑的资料会标注为「手动整理」，后续提取不会覆盖。\n\n已核对的段落保留缓存，更新只为新内容计费。停止后可继续，更新期间仍能查看已有资料。", { information = false })
    if (deleting) ConfirmKnowledgeDelete("删除人物资料？", "删除这本书的人物资料（包括手动整理）及未完成的提取进度。", { deleting = false }) {
        deleting = false; actions.deleteCharacters()
    }
}


@Composable
private fun KnowledgeEmpty(title: String, description: String, palette: ReaderPalette) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AutoStories, null, tint = palette.muted, modifier = Modifier.size(32.dp))
        Text(title, color = palette.onBackground, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(description, color = palette.muted, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp),
            modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun KnowledgeInfoDialog(title: String, description: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(description) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}

@Composable
private fun ConfirmKnowledgeDelete(title: String, description: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(description) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

/** 联网查到的书外资料：和原文依据分开放，「读到此处」时默认收起并提示可能含剧透。 */
@Composable
private fun ExternalNotesCard(notes: List<ExternalCharacterNote>, progressBounded: Boolean, palette: ReaderPalette) {
    var expanded by rememberSaveable { mutableStateOf(!progressBounded) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Surface(shape = RoundedCornerShape(18.dp), color = palette.glass, modifier = Modifier.fillMaxWidth().testTag("external-notes")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Icon(Icons.Outlined.Info, null, tint = palette.muted, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(stringResource(R.string.characters_external_title, notes.size), style = MaterialTheme.typography.titleSmall, color = palette.onBackground)
                    Text(stringResource(if (progressBounded) R.string.characters_external_spoiler else R.string.characters_external_hint),
                        style = MaterialTheme.typography.labelSmall, color = palette.muted)
                }
                Text(stringResource(if (expanded) R.string.characters_external_collapse else R.string.characters_external_expand),
                    style = MaterialTheme.typography.labelMedium, color = palette.accent)
            }
            if (expanded) notes.forEach { note ->
                Column(Modifier.fillMaxWidth().clickable(enabled = note.url.isNotBlank()) { runCatching { uriHandler.openUri(note.url) } }) {
                    if (note.character.isNotBlank()) Text(note.character, style = MaterialTheme.typography.labelMedium, color = palette.accent)
                    Text(note.text, style = MaterialTheme.typography.bodyMedium, color = palette.onBackground)
                    if (note.url.isNotBlank()) Text(note.title.ifBlank { note.url }, style = MaterialTheme.typography.labelSmall, color = palette.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

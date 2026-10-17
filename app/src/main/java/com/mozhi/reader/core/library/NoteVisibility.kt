package com.mozhi.reader.core.library

import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.retrieval.ReadingScope

/**
 * 笔记在当前阅读水位内是否可见：用户手写笔记始终可见，角色生成的笔记按来源锚点判定。
 * 私聊读笔记、段评召回笔记共用同一套规则，避免两边防剧透口径不一致。
 */
internal fun NoteEntity.visibleIn(scope: ReadingScope): Boolean =
    personaId == null || scope.isWholeBook || sourceScopeChapterIndex?.let { chapter ->
        scope.allowsPosition(chapter, sourceScopeCharOffset ?: Int.MAX_VALUE)
    } == true

/**
 * 笔记是否产生于当前书库阅读范围内：有来源锚点就必须落在范围内；
 * 角色生成但没有锚点的笔记不当作可引用资料；用户手写且未标章的笔记按全书资料处理。
 */
/**
 * 召回用可见性：笔记是用户/伴读自己整理的资料，锚点通常就在当前章（追踪类笔记刚在本章更新，
 * 手写笔记锚在最后阅读位置），所以召回边界必须包含当前章，不能套用大纲的「只看前文」。
 *
 * - 无来源锚点的角色笔记没有可信水位，不召回。
 * - 防剧透关闭时生成的笔记来源章是 [Int.MAX_VALUE]（[ReadingScope.WholeBook]），按全书资料处理。
 * - 其余仍按来源章防剧透：只有晚于当前章的笔记不召回。
 */
internal fun NoteEntity.visibleForRecall(scope: ReadingScope): Boolean {
    if (personaId == null) {
        return relatedChapterIndex?.let {
            scope.allowsPosition(it, relatedCharOffset ?: Int.MAX_VALUE)
        } ?: true
    }
    val chapter = sourceScopeChapterIndex ?: return false
    if (chapter == Int.MAX_VALUE) return true
    return scope.allowsPosition(chapter, sourceScopeCharOffset ?: Int.MAX_VALUE)
}

internal fun NoteEntity.withinLibraryScope(scope: ReadingScope): Boolean {
    if (sourceScopeChapterIndex != null || sourceScopeCharOffset != null) {
        return sourceScopeChapterIndex != null && sourceScopeCharOffset != null &&
            sourceScopeChapterIndex >= 0 && sourceScopeCharOffset >= 0 &&
            scope.allowsPosition(sourceScopeChapterIndex, sourceScopeCharOffset)
    }
    // Unanchored handwritten notes are supplied by the user, never advertised as source evidence.
    if (personaId == null) return relatedChapterIndex?.let {
        it >= 0 && scope.allowsPosition(it, relatedCharOffset ?: Int.MAX_VALUE)
    } ?: true
    return false
}

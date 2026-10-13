package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.library.NoteRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal class WriteNoteTool(
    private val bookId: Long,
    private val personaId: Long,
    private val conversationId: Long?,
    private val getBook: suspend () -> BookEntity?,
    private val notes: NoteRepository,
    private val readingScope: ReadingScope
) : AgentTool {
    override val displayName: String = "写读书笔记"

    override val spec: ToolSpec = ToolSpec(
        name = "write_note",
        description = "把用户明确要求保存的读书笔记写入本书笔记库。给出 note_id 时更新该条，否则新建；用户手写和其他角色的笔记不可改写。内容使用 Markdown；不要在用户没有要求保存时擅自调用。",
        parameters = noteParameters("笔记标题", "笔记 Markdown 正文")
    )

    override suspend fun execute(arguments: JsonObject): ToolResult = saveNote(
        arguments = arguments,
        kind = NoteRepository.KIND_NOTE,
        defaultTitle = "伴读笔记"
    )

    private suspend fun saveNote(
        arguments: JsonObject,
        kind: String,
        defaultTitle: String
    ): ToolResult {
        val content = arguments["content_md"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) return ToolResult.Failure("INVALID_ARGUMENT", "缺少笔记正文 content_md")
        val book = getBook() ?: return ToolResult.Failure("SOURCE_MISSING", "未找到当前书籍")
        val title = arguments["title"]?.jsonPrimitive?.contentOrNull?.trim()
            .orEmpty().ifBlank { defaultTitle }
        val requestedId = arguments["note_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val requested = requestedId?.let { notes.getNote(it) }
        if (requestedId != null && requested == null) return ToolResult.Failure("SOURCE_MISSING", "未找到第 $requestedId 条笔记")
        return when (val target = resolveNoteWriteTarget(
            requested = requested,
            latest = null,
            bookId = bookId,
            personaId = personaId,
            kind = kind,
            asNew = false
        )) {
            NoteWriteTarget.Create -> {
                val noteId = notes.create(
                    bookId = bookId,
                    personaId = personaId,
                    title = title.take(120),
                    contentMarkdown = content.take(MAX_NOTE_CHARS),
                    kind = kind,
                    sourceConversationId = conversationId,
                    relatedChapterIndex = if (readingScope.isWholeBook) null else readingScope.maxChapterIndex,
                    relatedCharOffset = if (readingScope.isWholeBook) null else readingScope.maxCharOffset,
                    sourceScopeChapterIndex = readingScope.maxChapterIndex,
                    sourceScopeCharOffset = readingScope.maxCharOffset
                )
                ToolResult.Success("已保存到《${book.title}》的笔记（编号 $noteId），来源范围：${scopeLabel(readingScope)}。")
            }
            is NoteWriteTarget.Update -> {
                val before = target.note.contentMarkdown.length
                val updatedContent = content.take(MAX_NOTE_CHARS)
                notes.updateContentAndPosition(
                    noteId = target.note.id,
                    title = title.take(120),
                    contentMarkdown = updatedContent,
                    relatedChapterIndex = if (readingScope.isWholeBook) null else readingScope.maxChapterIndex,
                    relatedCharOffset = if (readingScope.isWholeBook) null else readingScope.maxCharOffset,
                    sourceScopeChapterIndex = readingScope.maxChapterIndex,
                    sourceScopeCharOffset = readingScope.maxCharOffset
                )
                ToolResult.Success("已更新第 ${target.note.id} 条读书笔记（$before → ${updatedContent.length} 字），来源范围：${scopeLabel(readingScope)}。")
            }
            is NoteWriteTarget.Reject -> ToolResult.Failure("WRITE_REJECTED", target.reason)
        }
    }
}

internal class SavePlotSummaryTool(
    private val bookId: Long,
    private val personaId: Long,
    private val conversationId: Long?,
    private val getBook: suspend () -> BookEntity?,
    private val notes: NoteRepository,
    private val readingScope: ReadingScope
) : AgentTool {
    override val displayName: String = "保存剧情梗概"

    override val spec: ToolSpec = ToolSpec(
        name = "save_plot_summary",
        description = "保存或更新截至当前阅读进度的剧情梗概。梗概是滚动文档，默认覆盖当前角色最新一条；先用 list_notes 读回旧稿，只有 as_new=true 才新建。严禁写入未读剧情或改写用户手写内容。",
        parameters = plotSummaryParameters()
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val content = arguments["content_md"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) return ToolResult.Failure("INVALID_ARGUMENT", "缺少梗概正文 content_md")
        val book = getBook() ?: return ToolResult.Failure("SOURCE_MISSING", "未找到当前书籍")
        val currentChapter = readingScope.clampLastChapter(book.totalChapters) + 1
        val fromChapter = arguments["from_chapter"]?.jsonPrimitive?.intOrNull ?: 1
        val toChapter = arguments["to_chapter"]?.jsonPrimitive?.intOrNull ?: currentChapter
        if (fromChapter < 1 || toChapter < fromChapter) {
            return ToolResult.Failure("INVALID_ARGUMENT", "梗概章节范围无效：$fromChapter-$toChapter")
        }
        if (toChapter > currentChapter) {
            return ToolResult.Failure("OUT_OF_SCOPE", "梗概超出已读范围：用户只读到第 $currentChapter 章，不能保存到第 $toChapter 章。")
        }
        val defaultTitle = if (fromChapter == 1 && toChapter == currentChapter) {
            "剧情梗概 · 截至第 $toChapter 章"
        } else if (fromChapter == toChapter) {
            "剧情梗概 · 第 $fromChapter 章"
        } else {
            "剧情梗概 · 第 $fromChapter-$toChapter 章"
        }
        val title = arguments["title"]?.jsonPrimitive?.contentOrNull?.trim()
            .orEmpty().ifBlank { defaultTitle }
        val requestedId = arguments["note_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val requested = requestedId?.let { notes.getNote(it) }
        if (requestedId != null && requested == null) return ToolResult.Failure("SOURCE_MISSING", "未找到第 $requestedId 条笔记")
        val asNew = arguments["as_new"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val latest = if (requested == null && !asNew) {
            notes.latestByKind(bookId, personaId, NoteRepository.KIND_PLOT_SUMMARY)
        } else null
        return when (val target = resolveNoteWriteTarget(
            requested = requested,
            latest = latest,
            bookId = bookId,
            personaId = personaId,
            kind = NoteRepository.KIND_PLOT_SUMMARY,
            asNew = asNew
        )) {
            NoteWriteTarget.Create -> {
                val noteId = notes.create(
                    bookId = bookId,
                    personaId = personaId,
                    title = title.take(120),
                    contentMarkdown = content.take(MAX_NOTE_CHARS),
                    kind = NoteRepository.KIND_PLOT_SUMMARY,
                    sourceConversationId = conversationId,
                    relatedChapterIndex = toChapter - 1,
                    relatedCharOffset = if (!readingScope.isWholeBook && toChapter == currentChapter) readingScope.maxCharOffset else null,
                    sourceScopeChapterIndex = readingScope.maxChapterIndex,
                    sourceScopeCharOffset = readingScope.maxCharOffset
                )
                ToolResult.Success("第 $fromChapter-$toChapter 章剧情梗概已保存（编号 $noteId），可在书籍详情的「剧情梗概与笔记」中回顾。")
            }
            is NoteWriteTarget.Update -> {
                val previousTo = target.note.relatedChapterIndex?.plus(1)
                val before = target.note.contentMarkdown.length
                notes.updateContentAndPosition(
                    noteId = target.note.id,
                    title = title.take(120),
                    contentMarkdown = content.take(MAX_NOTE_CHARS),
                    relatedChapterIndex = toChapter - 1,
                    relatedCharOffset = if (!readingScope.isWholeBook && toChapter == currentChapter) readingScope.maxCharOffset else null,
                    sourceScopeChapterIndex = readingScope.maxChapterIndex,
                    sourceScopeCharOffset = readingScope.maxCharOffset
                )
                val oldRange = previousTo?.let { "原覆盖至第 $it 章 → " }.orEmpty()
                ToolResult.Success("已更新第 ${target.note.id} 条剧情梗概（${oldRange}现第 $fromChapter-$toChapter 章，$before → ${content.take(MAX_NOTE_CHARS).length} 字）。")
            }
            is NoteWriteTarget.Reject -> ToolResult.Failure("WRITE_REJECTED", target.reason)
        }
    }
}

private fun scopeLabel(scope: ReadingScope): String =
    if (scope.isWholeBook) "全书" else "第 ${scope.maxChapterIndex + 1} 章字符 ${scope.maxCharOffset}"

private fun noteParameters(titleDescription: String, contentDescription: String): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") {
                put("type", "string")
                put("description", titleDescription)
            }
            putJsonObject("content_md") {
                put("type", "string")
                put("description", contentDescription)
            }
            putJsonObject("note_id") {
                put("type", "integer")
                put("description", "要覆盖的笔记编号；省略则新建")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("title"))
            add(JsonPrimitive("content_md"))
        }
    }

private fun plotSummaryParameters(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("title") {
            put("type", "string")
            put("description", "梗概标题；可留空由应用按章节范围生成")
        }
        putJsonObject("content_md") {
            put("type", "string")
            put("description", "完整的 Markdown 剧情梗概")
        }
        putJsonObject("from_chapter") {
            put("type", "integer")
            put("description", "梗概起始章节号，从 1 开始；默认 1")
        }
        putJsonObject("to_chapter") {
            put("type", "integer")
            put("description", "梗概结束章节号（包含）；默认用户当前章节")
        }
        putJsonObject("note_id") {
            put("type", "integer")
            put("description", "要覆盖的梗概编号；省略时默认更新当前角色最新梗概")
        }
        putJsonObject("as_new") {
            put("type", "boolean")
            put("description", "是否强制新建一条梗概，默认 false")
        }
    }
    putJsonArray("required") { add(JsonPrimitive("content_md")) }
}

private const val MAX_NOTE_CHARS = 50_000

package com.mozhi.reader.feature.reader

import android.net.Uri
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.PersonaChatAppearance
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.ReaderFontAsset
import com.mozhi.reader.core.library.AttachmentStore

enum class AgentStepState { RUNNING, SUCCEEDED, FAILED }

data class AgentExecutionStep(
    val callId: String,
    val toolName: String,
    val displayName: String,
    val state: AgentStepState,
    val detail: String = "",
    /** 模型给出的原始参数 JSON；界面用 ToolCallSummary 压成一行摘要。 */
    val arguments: String = "{}",
    /** 工具返回的结果预览（已截断），只在「过程」卡展开后显示。 */
    val resultPreview: String = ""
)

/** 输入区待发送的附件（尚未落盘）；发送时经 AttachmentStore 压缩/保存。 */
data class PendingAttachment(
    val uri: Uri,
    val isImage: Boolean,
    val name: String
)

data class VoiceClipState(
    val path: String? = null,
    val loading: Boolean = false,
    val missing: Boolean = false,
    val failed: Boolean = false
)

/** 全屏聊天页顶栏与 sceneQuote 素材。 */
/** 聊天页要交给阅读页去做的事。 */
sealed interface CompanionChatEvent {
    /** 跳到正文某处并短暂高亮；坐标是章内 UTF-16 偏移。 */
    data class LocateInBook(
        val chapterIndex: Int,
        val startCharOffset: Int,
        val endCharOffset: Int,
        val sourceAnchorJson: String
    ) : CompanionChatEvent

    data class Message(val text: String) : CompanionChatEvent

    /** 互动界面在用户手势里请求的对外动作（尚未校验），由页面按白名单执行。 */
    data class GenUi(val messageKey: String, val action: com.mozhi.reader.ai.genui.GenUiAction) : CompanionChatEvent
}

data class CompanionChatContext(
    val bookTitle: String = "",
    val sceneQuote: String = ""
)

data class StoppedCompanionReply(
    val conversationId: Long,
    val roundId: String,
    val text: String,
    val reasoning: String
)

data class CompanionChatUiState(
    val personas: List<PersonaEntity> = emptyList(),
    val activePersona: PersonaEntity? = null,
    val conversationId: Long? = null,
    val isLoadingMessages: Boolean = true,
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val composerDraft: String = "",
    val stoppedReply: StoppedCompanionReply? = null,
    val isSavingStoppedReply: Boolean = false,
    val liveEntryId: String = "live-initial",
    /** 仅包含已在本书已读正文中实际命中的引用，按消息 id 索引。 */
    val locatedCitations: Map<Long, List<LocatedCompanionCitation>> = emptyMap(),
    val streamingText: String? = null,
    /** 本轮正在流的思维链；null = 该模型不产出 reasoning，界面整条不出现。 */
    val streamingReasoning: String? = null,
    val isStreaming: Boolean = false,
    /** Status line while a tool runs, e.g. "正在检索书中原文…". */
    val toolStatus: String? = null,
    val executionSteps: List<AgentExecutionStep> = emptyList(),
    val embeddingProgress: BookEmbeddingProgress? = null,
    /** Default-on guard that limits book tools to the user's current reading position. */
    val spoilerProtectionEnabled: Boolean = true,
    /** AI 回合结束后生成的建议回复，点击即发送；发送/切换会话时清空。 */
    val suggestions: List<String> = emptyList(),
    /** 当前角色的聊天外观；未自定义时是 DEFAULT，界面据此跟随阅读主题。 */
    val appearance: PersonaChatAppearance = PersonaChatAppearance.DEFAULT,
    /** 外观选中的背景图的真实路径；图片被删掉时为 null。 */
    val backgroundImagePath: String? = null,
    /** 共享字体库，供气泡按 [appearance] 的 fontId 取字体。 */
    val fontLibrary: List<ReaderFontAsset> = emptyList(),
    /** 多气泡：AI 回复按行拆成独立气泡。 */
    val multiBubbleEnabled: Boolean = false,
    /** 「自主发语音」开关开着且当前角色已绑音色；两者缺一即为 false。 */
    val voiceRepliesEnabled: Boolean = false,
    val voiceClips: Map<String, VoiceClipState> = emptyMap(),
    val error: String? = null
)

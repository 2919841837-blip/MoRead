package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.agent.AnnotationDiscussionService
import com.mozhi.reader.ai.agent.buildDiscussionTurns
import com.mozhi.reader.ai.agent.ownerAnnotationId
import com.mozhi.reader.ai.agent.userSpeaksAsOpener
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** AI 正在生成的讨论回复。 */
data class DiscussionStreaming(
    val personaId: Long,
    val text: String = "",
    val toolLabel: String? = null
)

data class DiscussionUiState(
    val replies: List<AnnotationReplyEntity> = emptyList(),
    val streaming: DiscussionStreaming? = null,
    val error: String? = null,
    /** 上次点名过的角色；弹层用它预选，null = 还没点过名。 */
    val rememberedPersonaId: Long? = null
)

/**
 * 段评讨论串状态：回复层观察 + 用户发言落库 + 单角色 AI 应答（批次一；
 * 「让大家聊聊」多角色编排在批次二）。讨论不进聊天会话。
 */
@HiltViewModel
class AnnotationDiscussionViewModel @Inject constructor(
    private val annotationRepository: AnnotationRepository,
    private val discussionService: AnnotationDiscussionService,
    private val settings: ReaderSettingsRepository
) : ViewModel() {

    private val mutableState = MutableStateFlow(DiscussionUiState())
    val uiState = mutableState.asStateFlow()

    private var repliesJob: Job? = null
    private var respondJob: Job? = null

    /** 当前弹层绑定的一组批注 id（同锚点的全部划线），讨论串按整组拼时间线。 */
    private var threadIds: List<Long> = emptyList()

    /** 流式正文真源；UI 快照按 显示帧节拍发布，避免逐 token 重组。 */
    private val streamBuffer = StringBuilder()

    init {
        // 点名记忆常驻：弹层是即开即用的，晚半拍到达就会错过首帧预选。
        viewModelScope.launch {
            settings.discussionPersonaId.collect { id ->
                mutableState.update { it.copy(rememberedPersonaId = id) }
            }
        }
    }

    /**
     * 记住这次点名的角色。只记正向选择：取消点名仅对当前这一条发言生效，
     * 否则「忘记选所以没人回复」会原样换成「上次取消过所以没人回复」。
     */
    fun rememberRespondPersona(personaId: Long) {
        viewModelScope.launch { settings.setDiscussionPersonaId(personaId) }
    }

    /** 弹层打开时绑定该讨论串（同锚点的全部批注 id）。 */
    fun open(annotationIds: List<Long>) {
        repliesJob?.cancel()
        respondJob?.cancel()
        threadIds = annotationIds
        mutableState.update { DiscussionUiState(rememberedPersonaId = it.rememberedPersonaId) }
        if (annotationIds.isEmpty()) return
        repliesJob = viewModelScope.launch {
            annotationRepository.observeReplies(annotationIds).collect { replies ->
                mutableState.update { it.copy(replies = replies) }
            }
        }
    }

    fun close() {
        repliesJob?.cancel()
        respondJob?.cancel()
        mutableState.update { DiscussionUiState(rememberedPersonaId = it.rememberedPersonaId) }
    }

    /**
     * 用户发言：谁第一个对这段原文发表段评，谁就是楼主——串里还完全没人开口时，他这句话就是
     * 段评，写进楼主层（note，照旧进「想法」）；已经有人开口过，他就是在回应，落回复层并挂在
     * 楼主那条批注下面（与 AI 回复同构，不许再升格成楼主）。
     * [respondPersonaId] 非空时随后让该角色应答——它就是被用户 @ 到的那个人。
     */
    fun sendUserReply(
        bookId: Long,
        target: AnnotationEntity,
        text: String,
        respondPersonaId: Long?
    ) {
        val content = text.trim()
        if (content.isEmpty()) {
            respondPersonaId?.let { respond(bookId, target.id, it) }
            return
        }
        if (respondJob?.isActive == true) return
        viewModelScope.launch {
            val group = annotationRepository.getAnnotations((threadIds + target.id).distinct())
            val turns = buildDiscussionTurns(group.ifEmpty { listOf(target) }, mutableState.value.replies)
            if (userSpeaksAsOpener(target, turns)) {
                annotationRepository.updateNote(target.id, content)
            } else {
                annotationRepository.addReply(
                    annotationId = turns.firstOrNull()?.ownerAnnotationId() ?: target.id,
                    personaId = null,
                    contentMarkdown = content
                )
            }
            if (respondPersonaId != null) {
                respond(bookId, target.id, respondPersonaId)
            }
        }
    }

    /** 单角色应答；已在生成时忽略新请求（一次一条，防连点失控）。 */
    fun respond(bookId: Long, annotationId: Long, personaId: Long) {
        if (respondJob?.isActive == true || mutableState.value.streaming != null) return
        mutableState.update { it.copy(streaming = DiscussionStreaming(personaId), error = null) }
        respondJob = viewModelScope.launch {
            mutableState.update {
                it.copy(streaming = DiscussionStreaming(personaId), error = null)
            }
            streamBuffer.setLength(0)
            val ticker = launchStreamingTicker {
                val state = mutableState.value
                val current = state.streaming ?: return@launchStreamingTicker
                val text = streamBuffer.toString()
                if (current.text != text) {
                    mutableState.value = state.copy(streaming = current.copy(text = text))
                }
            }
            try {
                discussionService.respond(bookId, annotationId, personaId, threadIds).collect { event ->
                    when (event) {
                        is AnnotationDiscussionService.Event.Text -> {
                            streamBuffer.append(event.delta)
                            if (mutableState.value.streaming?.toolLabel != null) {
                                mutableState.update { state ->
                                    val current = state.streaming ?: return@update state
                                    state.copy(streaming = current.copy(toolLabel = null))
                                }
                            }
                        }
                        is AnnotationDiscussionService.Event.ToolActivity -> mutableState.update { state ->
                            val current = state.streaming ?: return@update state
                            state.copy(streaming = current.copy(toolLabel = event.label))
                        }
                        is AnnotationDiscussionService.Event.Done -> mutableState.update {
                            it.copy(streaming = null)
                        }
                        is AnnotationDiscussionService.Event.Failed -> mutableState.update {
                            it.copy(streaming = null, error = event.message)
                        }
                    }
                }
            } finally {
                ticker.cancel()
            }
        }
    }

    /** 停止生成：丢弃半截回复（半条讨论没有保存价值）。 */
    fun cancelStreaming() {
        respondJob?.cancel()
        streamBuffer.setLength(0)
        mutableState.update { it.copy(streaming = null) }
    }

    fun deleteReply(replyId: Long) {
        viewModelScope.launch { annotationRepository.deleteReply(replyId) }
    }
}

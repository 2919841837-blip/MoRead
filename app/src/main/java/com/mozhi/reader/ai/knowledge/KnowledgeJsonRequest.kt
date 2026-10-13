package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.client.ChatDelta
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatOptions
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.client.ResolvedChatClient
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 人物整理用的「只要一段 JSON 文本」请求：不发工具 schema，不支持工具调用的模型、各家方言都能用。
 *
 * 和 [ChapterKnowledgeAgent] 里的工具式提交相比，对模型的要求低三处：
 * - 时限按「有没有在出字」算：首个增量（含思维链）[FIRST_DELTA_MS] 内到达、相邻增量间隔不超过 [IDLE_MS]，
 *   单轮另有 [ROUND_MS] 的总上限。慢但一直在写的模型不会被掐断，卡死的连接也不会无限等。
 * - 网络抖动、超时、限流与 5xx 自动退避重试，不计入解析修正。
 * - 解析失败给一次修正机会，带上失败原因；解析器本身会救回被截断的 JSON（[CharacterJsonSalvage]）。
 */
class KnowledgeJsonRequest @Inject constructor(private val limiter: KnowledgeRequestLimiter) {

    /** [parse] 抛 IllegalArgumentException 表示「结果不可用」，触发一次带原因的修正；其他异常原样上抛。 */
    internal suspend fun <T : Any> request(
        model: ResolvedChatClient,
        system: String,
        user: String,
        validate: suspend () -> Unit,
        parse: (String) -> T
    ): T {
        var messages = listOf(ChatMessage(ChatRole.SYSTEM, system), ChatMessage(ChatRole.USER, user))
        var failure: Exception? = null
        repeat(PARSE_ATTEMPTS) { attempt ->
            val text = streamWithRetry(model, messages, validate)
            validate()
            try {
                return parse(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                failure = error
                if (attempt + 1 < PARSE_ATTEMPTS) {
                    messages = messages + listOfNotNull(
                        text.takeIf(String::isNotBlank)?.let { ChatMessage(ChatRole.ASSISTANT, it.take(MAX_ECHO_CHARS)) },
                        ChatMessage(ChatRole.USER, "上面的输出无法使用：${error.message ?: "格式不正确"}。" +
                            "请只输出一个完整的 JSON 对象，不加解释、不用 Markdown；内容可以更精简，宁可少写几条也要写完整。")
                    )
                }
            }
        }
        throw failure ?: IllegalArgumentException("模型没有返回可用的结果")
    }

    private suspend fun streamWithRetry(model: ResolvedChatClient, messages: List<ChatMessage>, validate: suspend () -> Unit): String =
        withRetry(validate) { limiter.request { streamOnce(model, messages) } }

    private suspend fun streamOnce(model: ResolvedChatClient, messages: List<ChatMessage>): String {
        val text = StringBuilder()
        collectWatched(model.client.chatStream(messages, emptyList(), options(model))) { delta ->
            when (delta) {
                is ChatDelta.Text -> {
                    require(text.length + delta.text.length <= MAX_OUTPUT_CHARS) { "输出过长，请精简" }
                    text.append(delta.text)
                }
                // 个别服务商即便没给工具也会把结果放进工具调用参数里。
                is ChatDelta.ToolCalls -> delta.calls.forEach { text.append(it.arguments) }
                else -> Unit
            }
        }
        if (text.isBlank()) throw AiClientException.Empty()
        return text.toString()
    }

    internal class StreamStalled(val first: Boolean, val total: Boolean = false) : Exception(
        when {
            total -> "模型 ${ROUND_MS / 60_000} 分钟内没有写完"
            first -> "模型 ${FIRST_DELTA_MS / 1000} 秒内没有开始回复"
            else -> "模型连续 ${IDLE_MS / 1000} 秒没有新内容"
        }
    )

    internal companion object {
        const val PARSE_ATTEMPTS = 2
        const val MAX_RETRIES = 2
        const val FIRST_DELTA_MS = 120_000L
        const val IDLE_MS = 90_000L
        const val ROUND_MS = 480_000L
        const val DEFAULT_MAX_TOKENS = 8192
        const val MAX_OUTPUT_CHARS = 64_000
        private const val MAX_ECHO_CHARS = 6_000

        fun options(model: ResolvedChatClient): ChatOptions = model.options.copy(temperature = model.options.temperature ?: 0.3f,
            maxTokens = model.options.maxTokens ?: DEFAULT_MAX_TOKENS)

        /**
         * 按「有没有在出字」限时地收完一次流：首个增量 [FIRST_DELTA_MS]、相邻增量 [IDLE_MS]、总共 [ROUND_MS]。
         * [onDelta] 在调用方协程里执行，可以安全地改局部状态；不要在里面 emit 到外层 Flow。
         */
        suspend fun collectWatched(stream: Flow<ChatDelta>, onDelta: (ChatDelta) -> Unit) {
            val finished = withTimeoutOrNull(ROUND_MS) {
                coroutineScope {
                    val deltas = produce { stream.collect { send(it) } }
                    var first = true
                    while (true) {
                        val next = withTimeoutOrNull(if (first) FIRST_DELTA_MS else IDLE_MS) { deltas.receiveCatching() }
                        if (next == null) {
                            deltas.cancel()
                            throw StreamStalled(first)
                        }
                        if (next.isClosed) {
                            next.exceptionOrNull()?.let { throw it }
                            break
                        }
                        first = false
                        onDelta(next.getOrThrow())
                    }
                    true
                }
            }
            if (finished == null) throw StreamStalled(first = false, total = true)
        }

        /** 网络抖动、卡住、限流与 5xx 退避后整次重来；其他失败与取消原样上抛。 */
        suspend fun <T> withRetry(validate: suspend () -> Unit, block: suspend () -> T): T {
            var attempt = 0
            while (true) {
                validate()
                try {
                    return block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val wait = retryDelay(error, attempt) ?: throw describe(error)
                    CharacterResearchAgent.logFailure("request retry ${attempt + 1}: ${error.javaClass.simpleName}")
                    attempt++
                    delay(wait)
                }
            }
        }

        /** 可恢复的失败返回等待毫秒数；不可恢复（Key 无效、参数错误等）返回 null。 */
        fun retryDelay(error: Exception, attempt: Int): Long? {
            if (attempt >= MAX_RETRIES) return null
            val transient = when (error) {
                is StreamStalled, is AiClientException.Timeout, is AiClientException.Network, is AiClientException.Empty -> true
                is AiClientException.RateLimited -> return if (attempt == 0) 10_000L else 30_000L
                is AiClientException.Http -> error.code >= 500 || error.code == 408
                is java.io.IOException -> true
                else -> false
            }
            return if (transient) (if (attempt == 0) 3_000L else 10_000L) else null
        }

        private fun describe(error: Exception): Exception = when (error) {
            is StreamStalled -> IllegalStateException("${error.message}，已自动重试 $MAX_RETRIES 次。可稍后继续，已整理好的人物不会丢")
            else -> error
        }
    }
}

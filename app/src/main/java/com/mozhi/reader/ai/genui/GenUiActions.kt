package com.mozhi.reader.ai.genui

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 互动界面对外动作的白名单与参数校验（design/companion-genui-plan.md §7）。
 * 运行时已保证动作只来自用户手势、每次手势至多一次；这里再卡类型与长度，
 * 页面只执行校验通过的结果。
 */
sealed interface GenUiCommand {
    data class Send(val text: String) : GenUiCommand
    data class Copy(val text: String) : GenUiCommand
    data class Jump(val chapterNumber: Int?, val quote: String) : GenUiCommand
    data class Speak(val text: String, val speaker: String?) : GenUiCommand
    /** 只放行 http(s)；执行前必须由用户在确认层里看到完整域名后同意。 */
    data class OpenUrl(val url: String, val host: String) : GenUiCommand
}

object GenUiActions {
    const val MAX_SEND_CHARS = 500
    const val MAX_COPY_CHARS = 8_000
    const val MAX_SPEAK_CHARS = 300
    private const val MAX_QUOTE_CHARS = 300

    fun validate(action: GenUiAction): GenUiCommand? {
        val payload = action.payload
        fun text(name: String): String? = (payload[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return when (action.type) {
            "send" -> text("text")?.take(MAX_SEND_CHARS)?.let(GenUiCommand::Send)
            "copy" -> text("text")?.take(MAX_COPY_CHARS)?.let(GenUiCommand::Copy)
            "jump" -> {
                val quote = text("quote")?.take(MAX_QUOTE_CHARS) ?: return null
                val chapter = (payload["chapter"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
                GenUiCommand.Jump(chapter?.takeIf { it > 0 }, quote)
            }
            "speak" -> text("text")?.take(MAX_SPEAK_CHARS)?.let { GenUiCommand.Speak(it, text("speaker")) }
            "openUrl" -> {
                val url = text("url") ?: return null
                val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
                val scheme = uri.scheme?.lowercase()
                val host = uri.host?.lowercase()
                if ((scheme != "http" && scheme != "https") || host.isNullOrBlank() || uri.userInfo != null) return null
                GenUiCommand.OpenUrl(uri.toString(), host)
            }
            else -> null
        }
    }
}

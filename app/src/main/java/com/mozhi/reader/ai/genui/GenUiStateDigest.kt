package com.mozhi.reader.ai.genui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 「模型知道用户在界面上做了什么」（design/companion-genui-plan.md §11.2）。
 *
 * ChatGPT 原版的模型看不到界面状态；这里在用户发下一条消息时，取最近几条带互动的回复里
 * **相对初值改动过的状态**，压成一段文字随本轮请求交给模型（不显示、存进 uiContextJson）。
 * 有编译诊断时附一行，让模型下次自己改正同样的错误。
 */
object GenUiStateDigest {
    const val MAX_CHARS = 600
    private const val MAX_VALUE_CHARS = 120
    private val json = Json { ignoreUnknownKeys = true }

    /** 一条带互动的回复：按时间从旧到新传入。 */
    data class Entry(val state: JsonObject, val initial: JsonObject, val diagnostics: List<GenUiDiagnostic> = emptyList())

    fun digest(entries: List<Entry>): String? {
        val lines = mutableListOf<String>()
        val recent = entries.takeLast(2)
        recent.forEachIndexed { index, entry ->
            val changed = changedState(entry)
            if (changed.isEmpty()) return@forEachIndexed
            val which = if (index == recent.lastIndex) "上一条回复" else "更早一条回复"
            lines += "〔界面状态〕${which}中用户改动过的互动：" + changed.joinToString("；") { (k, v) -> "$k=$v" }
        }
        recent.lastOrNull()?.let { last -> diagnosticsLine(last.diagnostics)?.let(lines::add) }
        if (lines.isEmpty()) return null
        return lines.joinToString("\n").let { if (it.length > MAX_CHARS) it.take(MAX_CHARS - 1) + "…" else it }
    }

    fun changedState(entry: Entry): List<Pair<String, String>> = entry.state.mapNotNull { (key, value) ->
        if (key.startsWith("__") || key.startsWith("#")) return@mapNotNull null
        if (entry.initial[key] == value) return@mapNotNull null
        key to render(value)
    }

    private fun render(value: JsonElement): String {
        val text = when (value) {
            is JsonPrimitive -> if (value.isString) "\"${value.content}\"" else value.contentOrNull ?: "null"
            else -> value.toString()
        }
        return if (text.length > MAX_VALUE_CHARS) text.take(MAX_VALUE_CHARS - 1) + "…" else text
    }

    /** 只回传模型自己能改的错误（未知属性、写错的值、坏语句），流式半截的恢复不算。 */
    private val ACTIONABLE = mapOf(
        "unknown_prop" to "不存在的属性",
        "invalid_literal" to "取值无效",
        "invalid_statement" to "语句无法解析",
        "invalid_expression" to "表达式无法解析",
        "implicit_state" to "状态未声明",
        "handler_wrapped" to "事件写成了直接调用",
        "duplicate_declaration" to "重复声明"
    )

    private fun diagnosticsLine(diagnostics: List<GenUiDiagnostic>): String? {
        val relevant = diagnostics.filter { it.code in ACTIONABLE }
        if (relevant.isEmpty()) return null
        val parts = relevant.groupBy { it.code }.entries.take(3).joinToString("；") { (code, items) ->
            val examples = items.mapNotNull { it.detail }.distinct().take(2).joinToString("、")
            ACTIONABLE.getValue(code) + (if (examples.isNotEmpty()) "（$examples）" else "") + " ×${items.size}"
        }
        return "〔界面诊断〕上一条回复的界面已自动修复：$parts。下次请按规范书写。"
    }

    fun encodeContext(text: String): String = buildJsonObject { put("text", text) }.toString()

    fun decodeContext(raw: String?): String? = raw?.let {
        runCatching { (json.parseToJsonElement(it).jsonObject["text"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
    }?.takeIf { it.isNotBlank() }
}

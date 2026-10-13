package com.mozhi.reader.ai.knowledge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 人物档案与名单的 JSON 读取：模型给的东西常常裹着说明文字、带尾逗号，或者因为输出上限被拦腰截断。
 *
 * 截断时退回到最后一个完整的成员 / 元素，再补齐括号——只丢掉没写完的那一条，不臆造任何内容。
 * 这只用于人物档案这类「少一条也照样能看」的结果；章节大纲等仍走 [KnowledgeResponseJson] 的严格路径。
 */
internal object CharacterJsonSalvage {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
        allowComments = true
    }

    /** 尝试的截断点上限：长输出里只回退最近的若干个，避免病态输入反复解析。 */
    private const val MAX_CUTS = 64

    fun parse(raw: String): JsonElement? {
        if (raw.length > 64_000) return null
        val text = raw.trim().removePrefix(Char(0xFEFF).toString())
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val body = text.substring(start)
        val close = if (body[0] == '{') '}' else ']'
        val end = body.lastIndexOf(close)
        if (end > 0) decode(body.substring(0, end + 1))?.let { return it }
        return repair(body)
    }

    /** 根是对象时原样返回；根是数组时包成 `{key: [...]}`，兼容只交一个数组的模型。 */
    fun parseObject(raw: String, arrayKey: String): JsonObject? = when (val value = parse(raw)) {
        is JsonObject -> value
        is JsonArray -> buildJsonObject { put(arrayKey, value) }
        else -> null
    }

    private fun decode(value: String): JsonElement? =
        runCatching { json.parseToJsonElement(value) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }

    /**
     * 扫一遍记下每个「完整值之后」的位置（字符串外的逗号、闭合括号之后）及当时的括号栈，
     * 从最靠后的位置开始补齐括号试解析。
     */
    private fun repair(body: String): JsonElement? {
        val stack = StringBuilder()
        val cuts = ArrayList<Pair<Int, String>>()
        var inString = false
        var escaped = false
        for (i in body.indices) {
            val c = body[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.append(c)
                '}', ']' -> {
                    if (stack.isEmpty()) break
                    stack.setLength(stack.length - 1)
                    if (stack.isEmpty()) {
                        decode(body.substring(0, i + 1))?.let { return it }
                        break
                    }
                    cuts += (i + 1) to stack.toString()
                }
                ',' -> if (stack.isNotEmpty()) cuts += i to stack.toString()
            }
        }
        for ((cut, open) in cuts.asReversed().take(MAX_CUTS)) {
            val closing = open.reversed().map { if (it == '{') '}' else ']' }.joinToString("")
            decode(body.substring(0, cut) + closing)?.let { return it }
        }
        return null
    }

    // ---- 宽容取值：模型写成数字、带引号的数字、「[3]」、数组或顿号串都认 ----

    fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        when (val value = this[key]) {
            is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            is JsonObject -> value.text("text", "value", "content", "描述")
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter(String::isNotEmpty).joinToString("、").takeIf(String::isNotEmpty)
            else -> null
        }
    }

    fun JsonObject.strings(vararg keys: String): List<String> = keys.firstNotNullOfOrNull { key ->
        when (val value = this[key]) {
            is JsonArray -> value.mapNotNull { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull
                    is JsonObject -> item.text("name", "text", "value")
                    else -> null
                }
            }
            is JsonPrimitive -> value.contentOrNull?.split('、', '，', ',', '/', '；', ';')
            else -> null
        }
    }.orEmpty().map(String::trim).filter { it.isNotEmpty() && it != "null" }.distinct()

    fun JsonObject.objects(vararg keys: String): List<JsonObject> = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
    }.orEmpty()

    /** 段落编号：`3`、`"3"`、`"[3]"`、`"段落3"`、`[3, 5]`（取第一个）。 */
    fun JsonObject.passageNumber(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        when (val value = this[key]) {
            is JsonPrimitive -> value.intOrNull ?: value.contentOrNull?.let { DIGITS.find(it)?.value?.toIntOrNull() }
            is JsonArray -> (value.firstOrNull() as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.let { s -> DIGITS.find(s)?.value?.toIntOrNull() } }
            else -> null
        }
    }

    private val DIGITS = Regex("\\d{1,3}")
}

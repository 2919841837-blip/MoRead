package com.mozhi.reader.ai.knowledge

/** 点名扫描的目标：大名 + 用户标注的别称。 */
data class CharacterTarget(val name: String, val aliases: List<String> = emptyList())

/** 名单输入的解析结果；[rejected] 与 [overflowed] 只用于界面提示，解析本身永不抛异常。 */
internal data class ParsedCharacterTargets(
    val targets: List<CharacterTarget> = emptyList(),
    /** 无法识别的片段（已截断到可显示的长度）。 */
    val rejected: List<String> = emptyList(),
    /** 超过 [CharacterScanTargets.MAX_TARGETS]，多余的目标被丢弃。 */
    val overflowed: Boolean = false
)

/**
 * 解析用户输入的扫描名单，形如 `黄晓明（晓明、绿大暗）`：括号外是大名，括号内是别称。
 *
 * 多条名单用换行或 `、 ， , ; ；` 分隔，括号全半角都接受。同一大名的多个片段会合并别称。
 * 因为换行与括号本身就是分隔符，名字和别称里不可能出现换行，用户输入也就无法往提示词里注入新行。
 * 姓名长度上限沿用 [CharacterResearchAgent] 派发子 agent 时的口径，避免出现两套规则。
 */
internal object CharacterScanTargets {
    const val MAX_TARGETS = 20
    const val MAX_ALIASES = 6
    const val MAX_NAME_LENGTH = 40

    /** 解析只读前 [MAX_INPUT] 个字符，界面据此限制输入，免得用户打了字却被悄悄丢掉。 */
    const val MAX_INPUT = 600
    private const val SEPARATORS = "\n\r;；，,、"

    fun parse(input: String): ParsedCharacterTargets {
        // 按出现顺序保留首次写法；同名（忽略大小写）合并别称。
        val targets = linkedMapOf<String, CharacterTarget>()
        val rejected = mutableListOf<String>()
        val name = StringBuilder()
        val alias = StringBuilder()
        val aliases = mutableListOf<String>()
        var depth = 0
        var closed = false

        fun finishAlias() {
            val value = alias.toString().trim()
            alias.setLength(0)
            val main = name.toString().trim()
            if (value.isEmpty() || value == main || value.length > MAX_NAME_LENGTH) return
            if (value !in aliases) aliases += value
        }

        fun commit() {
            if (depth > 0) finishAlias()
            depth = 0
            closed = false
            val main = name.toString().trim()
            name.setLength(0)
            val group = aliases.toList()
            aliases.clear()
            if (main.isEmpty()) {
                // 只有括号没有大名，例如「（晓明）」：整段无法识别。
                if (group.isNotEmpty()) rejected += group.joinToString("、").take(MAX_NAME_LENGTH)
                return
            }
            if (main.length > MAX_NAME_LENGTH) {
                rejected += main.take(MAX_NAME_LENGTH)
                return
            }
            val key = main.lowercase()
            val existing = targets[key]
            targets[key] = if (existing == null) CharacterTarget(main, group.take(MAX_ALIASES))
            else existing.copy(aliases = (existing.aliases + group).distinct().take(MAX_ALIASES))
        }

        input.take(MAX_INPUT).forEach { char ->
            when {
                char == '(' || char == '（' -> if (depth == 0) { closed = false; depth = 1 } else alias.append(char)
                char == ')' || char == '）' -> if (depth == 1) { finishAlias(); depth = 0; closed = true }
                char in SEPARATORS -> if (depth == 1) finishAlias() else commit()
                !closed -> if (depth == 1) alias.append(char) else name.append(char)
                // 括号闭合后、下一个分隔符之前的游离文字直接忽略。
            }
        }
        commit()

        val all = targets.values.toList()
        return ParsedCharacterTargets(all.take(MAX_TARGETS), rejected, overflowed = all.size > MAX_TARGETS)
    }
}

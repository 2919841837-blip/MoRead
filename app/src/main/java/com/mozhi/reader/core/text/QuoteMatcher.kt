package com.mozhi.reader.core.text

/**
 * 把模型「照抄」的引文定位回原文。模型常见的走样是空白、全半角标点、引号样式和省略号写法，
 * 文字本身没变；逐字 indexOf 会把这些都判成编造。这里只容忍这类排版差异，返回的永远是原文坐标，
 * 调用方应以 `text.substring(start, end)` 作为最终引文，而不是模型给的字符串。
 */
object QuoteMatcher {
    /** 原文中的半开区间 [start, end)。 */
    data class Span(val start: Int, val end: Int)

    /** 先逐字，再按归一化文字匹配；都找不到返回 null。 */
    fun find(text: String, quote: String): Span? {
        if (quote.isEmpty()) return null
        val exact = text.indexOf(quote)
        if (exact >= 0) return Span(exact, exact + quote.length)
        val source = Normalized.of(text)
        val target = Normalized.of(quote).value
        if (target.length < MIN_NORMALIZED_CHARS) return null
        val at = source.value.indexOf(target)
        if (at < 0) return null
        val span = source.span(at, at + target.length)
        // 句末的句号/省略号不参与匹配；引文本来以它结尾时，把原文里紧随的那几个也收进来。
        if (quote.trimEnd().lastOrNull()?.let(::isSentenceEnd) != true) return span
        var end = span.end
        while (end < text.length && isSentenceEnd(text[end])) end++
        return span.copy(end = end)
    }

    private fun isSentenceEnd(char: Char): Boolean = char == '…' || char == '.' || char == '·' || char == '。'

    /**
     * 模型改写了引文的一部分（多抄一句、漏几个字）时，取它与原文最长的公共片段，片段至少
     * [minChars] 个归一化字符且覆盖引文的一半。只用于「本来就只许从这一小段里选」的场景，
     * 如随读段评的目标段落；人物资料的原文依据不走这条路。
     */
    fun bestOverlap(text: String, quote: String, minChars: Int = 6): Span? {
        find(text, quote)?.let { return it }
        val source = Normalized.of(text)
        val target = Normalized.of(quote).value
        val a = source.value
        if (a.isEmpty() || target.isEmpty()) return null
        var bestLength = 0
        var bestEnd = 0
        var previous = IntArray(target.length + 1)
        var current = IntArray(target.length + 1)
        for (i in 1..a.length) {
            for (j in 1..target.length) {
                current[j] = if (a[i - 1] == target[j - 1]) previous[j - 1] + 1 else 0
                if (current[j] > bestLength) { bestLength = current[j]; bestEnd = i }
            }
            val swap = previous; previous = current; current = swap
            current.fill(0)
        }
        if (bestLength < minChars || bestLength * 2 < target.length) return null
        return source.span(bestEnd - bestLength, bestEnd)
    }

    private const val MIN_NORMALIZED_CHARS = 4

    private class Normalized(val value: String, private val origin: IntArray) {
        fun span(start: Int, end: Int): Span = Span(origin[start], origin[end - 1] + 1)

        companion object {
            fun of(text: String): Normalized {
                val out = StringBuilder(text.length)
                val origin = IntArray(text.length)
                text.forEachIndexed { index, char ->
                    val mapped = canonical(char) ?: return@forEachIndexed
                    origin[out.length] = index
                    out.append(mapped)
                }
                return Normalized(out.toString(), origin)
            }

            /** null = 忽略这个字符。 */
            private fun canonical(char: Char): Char? = when {
                char.isWhitespace() || char.code == 0x200B || char.code == 0xFEFF -> null
                char == '…' || char == '.' || char == '·' || char == '。' -> null
                char in "“”„‟「」＂\"″" -> '"'
                char in "‘’‛『』＇'′" -> '\''
                char in "—–―－-" -> '-'
                char in '！'..'～' -> (char - 0xFEE0).lowercaseChar()
                char == '、' -> ','
                else -> char.lowercaseChar()
            }
        }
    }
}

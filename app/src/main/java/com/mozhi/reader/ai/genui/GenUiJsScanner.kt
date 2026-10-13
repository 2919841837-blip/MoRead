package com.mozhi.reader.ai.genui

/**
 * 认得 JS 字符串、模板字符串与注释的括号配平扫描。
 *
 * DIL 里的 `{…}` 内是 JS：`{@body const T = {a:"}"}}`、`style={{color:"#fff"}}`、
 * 模板字符串里的 `${…}` 都可能含有花括号，只数括号会切错位置。正则字面量不识别
 * （`/}/` 极少见，且切错只坏这一处）。
 */
internal object GenUiJsScanner {

    /**
     * 从 [start]（位于开括号**之后**）起扫描，返回与之配平的闭括号下标；
     * [stopAtLineEnd] 为 true 时遇到换行仍未配平即返回 -2；扫到末尾仍未配平返回 -1。
     */
    fun findClosingBrace(source: String, start: Int, stopAtLineEnd: Boolean = false): Int {
        var depth = 1
        var i = start
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c == '\n' && stopAtLineEnd -> return -2
                c == '"' || c == '\'' -> {
                    i = skipString(source, i, c, stopAtLineEnd)
                    if (i < 0) return i
                    continue
                }
                c == '`' -> {
                    i = skipTemplate(source, i)
                    if (i < 0) return -1
                    continue
                }
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    val end = source.indexOf('\n', i)
                    if (end < 0) return -1
                    if (stopAtLineEnd) return -2
                    i = end
                    continue
                }
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    val end = source.indexOf("*/", i + 2)
                    if (end < 0) return -1
                    i = end + 2
                    continue
                }
                c == '{' || c == '(' || c == '[' -> depth++
                c == '}' || c == ')' || c == ']' -> {
                    depth--
                    if (depth == 0) return if (c == '}') i else -3
                }
            }
            i++
        }
        return -1
    }

    /** 返回字符串结束引号之后的下标；未闭合返回 -1（或行尾 -2）。 */
    fun skipString(source: String, start: Int, quote: Char, stopAtLineEnd: Boolean = false): Int {
        var i = start + 1
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' -> i += 2
                c == quote -> return i + 1
                c == '\n' -> return if (stopAtLineEnd) -2 else -1
                else -> i++
            }
        }
        return -1
    }

    /** 模板字符串：跳过 `${…}` 嵌套；返回结束反引号之后的下标，未闭合 -1。 */
    fun skipTemplate(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' -> i += 2
                c == '`' -> return i + 1
                c == '$' && i + 1 < source.length && source[i + 1] == '{' -> {
                    val close = findClosingBrace(source, i + 2)
                    if (close < 0) return -1
                    i = close + 1
                }
                else -> i++
            }
        }
        return -1
    }

    /** 顶层（不在括号、字符串里）有没有逗号：`const a = 1, b = 2` 这类多声明不做包装改写。 */
    fun hasTopLevelComma(code: String): Boolean {
        var depth = 0
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' || c == '\'' -> {
                    i = skipString(code, i, c).takeIf { it > 0 } ?: return false
                    continue
                }
                c == '`' -> {
                    i = skipTemplate(code, i).takeIf { it > 0 } ?: return false
                    continue
                }
                c == '{' || c == '(' || c == '[' -> depth++
                c == '}' || c == ')' || c == ']' -> depth--
                c == ',' && depth == 0 -> return true
            }
            i++
        }
        return false
    }

    /** 粗略地取出一段 JS 里出现的标识符（用于声明名、引用名的推断，不追求精确）。 */
    fun identifiers(code: String): Set<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' || c == '\'' -> {
                    i = skipString(code, i, c).takeIf { it > 0 } ?: return out
                    continue
                }
                c == '`' -> {
                    i = skipTemplate(code, i).takeIf { it > 0 } ?: return out
                    continue
                }
                c.isJsIdentifierStart() && (i == 0 || code[i - 1] != '.') -> {
                    var end = i + 1
                    while (end < code.length && code[end].isJsIdentifierPart()) end++
                    out += code.substring(i, end)
                    i = end
                    continue
                }
                c.isJsIdentifierStart() -> {
                    // 属性访问 a.b 里的 b 不算引用
                    var end = i + 1
                    while (end < code.length && code[end].isJsIdentifierPart()) end++
                    i = end
                    continue
                }
            }
            i++
        }
        return out
    }

    fun Char.isJsIdentifierStart(): Boolean = this == '_' || this == '$' || isLetter()
    fun Char.isJsIdentifierPart(): Boolean = isJsIdentifierStart() || isDigit()
}

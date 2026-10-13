package com.mozhi.reader.ai.genui

import com.mozhi.reader.ai.genui.GenUiJsScanner.isJsIdentifierPart

/** 编译诊断。只记代码与位置，不记正文（日志纪律）。 */
data class GenUiDiagnostic(val code: String, val position: Int, val detail: String? = null)

/* ------------------------------ 语法树 ------------------------------ */

sealed interface DilNode {
    val pos: Int
}

enum class DilAttrKind { STRING, EXPR, BARE }

data class DilAttr(val name: String, val kind: DilAttrKind, val value: String, val pos: Int)

/** [name] 为目录里的规范名（别名已解析），[rawName] 为模型原文写的名字。 */
data class DilElement(
    val name: String,
    val rawName: String,
    val attrs: List<DilAttr>,
    val children: List<DilNode>,
    override val pos: Int
) : DilNode {
    fun attr(name: String): DilAttr? = attrs.lastOrNull { it.name == name }
}

/** 元素内的文字（行内 Markdown 由渲染层解释）。 */
data class DilText(val value: String, override val pos: Int) : DilNode

/** 顶层控制块里的整段 Markdown（`{#if 答对}恭喜！{/if}` 这种）。 */
data class DilMarkdown(val value: String, override val pos: Int) : DilNode

data class DilExpr(val code: String, override val pos: Int) : DilNode

data class DilBranch(val condition: String?, val body: List<DilNode>)

data class DilIf(val branches: List<DilBranch>, override val pos: Int) : DilNode

data class DilEach(
    val list: String,
    val item: String,
    val index: String?,
    val body: List<DilNode>,
    val empty: List<DilNode>,
    override val pos: Int
) : DilNode

/** 内容不解析、原样保留的块：目前只有 `<AppBlock>` 小程序。 */
data class DilRaw(
    val name: String,
    val attrs: List<DilAttr>,
    val content: String,
    val complete: Boolean,
    override val pos: Int
) : DilNode

data class DilStatement(val code: String, val pos: Int)

/* ------------------------------ 文档 ------------------------------ */

sealed interface GenUiSegment {
    val start: Int
    val end: Int

    /** 普通 Markdown：沿用现有的流式富文本、多气泡、语音行与原文脚注。 */
    data class Markdown(val text: String, override val start: Int, override val end: Int) : GenUiSegment

    /** 一个互动岛：一个或多个紧挨着的顶层元素 / 控制块。 */
    data class Island(
        val index: Int,
        val nodes: List<DilNode>,
        val complete: Boolean,
        override val start: Int,
        override val end: Int
    ) : GenUiSegment
}

/**
 * 一条回复按 DIL 方言切开后的结果（见 design/companion-genui-plan.md §4）。
 *
 * 顶层文字是纯 Markdown（不解释花括号）；行首是目录里的标签或 `{#if}` / `{#each}` 时开始一个
 * 互动岛；`{@body …}` 是不可见的程序语句。解析永不抛异常：半截输入是流式期间的常态，
 * 未写完的标签与语句丢弃并记诊断，已有内容的未闭合元素自动闭合。
 */
class GenUiDocument private constructor(
    val source: String,
    val segments: List<GenUiSegment>,
    val statements: List<DilStatement>,
    val diagnostics: List<GenUiDiagnostic>
) {
    val islands: List<GenUiSegment.Island> = segments.filterIsInstance<GenUiSegment.Island>()

    /** 有岛或语句才需要编译与引擎；纯文字回复走原来的渲染路径，零开销。 */
    val isInteractive: Boolean get() = islands.isNotEmpty()

    /** 去掉岛与语句后的纯 Markdown（岛的位置不留占位）。 */
    val markdownOnly: String
        get() = segments.filterIsInstance<GenUiSegment.Markdown>().joinToString("\n\n") { it.text.trim('\n') }

    companion object {
        /**
         * @param complete 回复是否已经写完。写完时，悬空的 `{@body` 按 DJJ 的保险在行尾截断；
         *   流式期间则暂不输出，等下一个节拍。
         */
        fun parse(source: String, complete: Boolean = true): GenUiDocument =
            DilDocumentParser(source, complete).parse()

        /** 快速预判：不含任何 DIL 痕迹的回复跳过完整解析（每个流式节拍都会调到这里）。 */
        fun mightContainDil(source: String): Boolean {
            if (source.indexOf('<') < 0 && source.indexOf("{@") < 0 && source.indexOf("{#") < 0) return false
            return true
        }

        internal fun create(
            source: String,
            segments: List<GenUiSegment>,
            statements: List<DilStatement>,
            diagnostics: List<GenUiDiagnostic>
        ) = GenUiDocument(source, segments, statements, diagnostics)
    }
}

/* ------------------------------ 解析器 ------------------------------ */

private class DilDocumentParser(private val source: String, private val complete: Boolean) {
    private val n = source.length
    private val segments = mutableListOf<GenUiSegment>()
    private val statements = mutableListOf<DilStatement>()
    private val diagnostics = mutableListOf<GenUiDiagnostic>()
    private val hasStatements = STATEMENT_LINE.containsMatchIn(source)

    private val markdown = StringBuilder()
    private var markdownStart = 0
    private var markdownEnd = 0

    fun parse(): GenUiDocument {
        var pos = 0
        var fence: String? = null
        var strippedFence: String? = null
        while (pos < n) {
            val lineEnd = source.indexOf('\n', pos).let { if (it < 0) n else it }
            val next = if (lineEnd < n) lineEnd + 1 else n
            val line = source.substring(pos, lineEnd)
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length

            if (fence != null) {
                appendMarkdown(pos, next)
                if (isClosingFence(trimmed, fence)) fence = null
                pos = next
                continue
            }
            val marker = fenceMarker(trimmed)
            if (marker != null) {
                if (strippedFence != null && isClosingFence(trimmed, strippedFence)) {
                    diagnostics += GenUiDiagnostic("stripped_code_fence", pos)
                    strippedFence = null
                    pos = next
                    continue
                }
                if (isWrapperFence(trimmed, next)) {
                    diagnostics += GenUiDiagnostic("stripped_code_fence", pos)
                    strippedFence = marker
                    pos = next
                    continue
                }
                fence = marker
                appendMarkdown(pos, next)
                pos = next
                continue
            }
            if (isStatementStart(trimmed)) {
                val end = parseStatementLine(pos + indent, lineEnd)
                if (end < 0) break // 流式中尚未写完的语句：后面的内容都属于它，等下一个节拍
                pos = skipBlankRest(end)
                continue
            }
            if (startsIsland(trimmed)) {
                flushMarkdown()
                pos = parseIslandRun(pos + indent)
                continue
            }
            if (!complete && next == n && lineEnd == n && looksLikeUnfinishedConstruct(trimmed)) {
                // 最后一行正写到 `<car` / `{@bo`：先不显示，免得半截标签在正文里闪一下。
                break
            }
            appendMarkdown(pos, next)
            pos = next
        }
        flushMarkdown()
        return GenUiDocument.create(source, segments, statements, diagnostics)
    }

    /* ---------- Markdown 累积 ---------- */

    private fun appendMarkdown(from: Int, to: Int) {
        if (markdown.isEmpty()) markdownStart = from
        markdown.append(source, from, to)
        markdownEnd = to
    }

    private fun flushMarkdown() {
        if (markdown.isNotBlank()) {
            segments += GenUiSegment.Markdown(markdown.toString().trimEnd('\n'), markdownStart, markdownEnd)
        }
        markdown.setLength(0)
    }

    /* ---------- 代码围栏 ---------- */

    private fun fenceMarker(trimmed: String): String? = when {
        trimmed.startsWith("```") -> "```"
        trimmed.startsWith("~~~") -> "~~~"
        else -> null
    }

    private fun isClosingFence(trimmed: String, marker: String): Boolean =
        trimmed.startsWith(marker) && trimmed.trimEnd().all { it == marker[0] }

    /**
     * 整篇回答被包在 ```html / ```jsx / ``` 里（通用模型的常见错误）：围栏里紧接着就是
     * `{@body` 或互动标签时，去掉围栏行而不是把整篇当代码块显示。
     */
    private fun isWrapperFence(trimmed: String, contentStart: Int): Boolean {
        val lang = trimmed.trimStart('`', '~').trim().lowercase()
        if (lang !in WRAPPER_FENCE_LANGS) return false
        var pos = contentStart
        while (pos < n) {
            val end = source.indexOf('\n', pos).let { if (it < 0) n else it }
            val line = source.substring(pos, end).trim()
            if (line.isNotEmpty()) {
                return isStatementStart(line) || line.startsWith("{#") || startsStrongTag(line)
            }
            pos = end + 1
        }
        return false
    }

    /* ---------- 语句 ---------- */

    private fun isStatementStart(trimmed: String): Boolean = STATEMENT_START.containsMatchIn(trimmed)

    /**
     * 解析从 [start]（`{@` 所在位置）开始的一条语句，返回语句之后的位置；
     * 流式中尚未写完时返回 -1。
     */
    private fun parseStatementLine(start: Int, lineEnd: Int): Int {
        val keyword = STATEMENT_START.find(source.substring(start, minOf(n, start + 12)))!!
        val bodyStart = start + keyword.value.length
        val keepKeyword = keyword.groupValues[1] == "const"
        val sameLine = GenUiJsScanner.findClosingBrace(source, start + 1, stopAtLineEnd = true)
        if (sameLine >= 0) {
            addStatement(source.substring(bodyStart, sameLine), start, keepKeyword)
            return sameLine + 1
        }
        // 跨行：只有后续行里没有新的结构（另一条语句、互动标签）时才接受，
        // 否则按 DJJ 的保险在第一行行尾截断，漏一个右括号只坏这一行。
        val multi = GenUiJsScanner.findClosingBrace(source, start + 1)
        if (multi >= 0 && !containsNewConstruct(lineEnd, multi) && lineCount(start, multi) <= MAX_STATEMENT_LINES) {
            addStatement(source.substring(bodyStart, multi), start, keepKeyword)
            return multi + 1
        }
        if (multi == -1 && !complete && !containsNewConstruct(lineEnd, n)) return -1
        var code = source.substring(bodyStart, lineEnd).trimEnd()
        // 漏掉的多半是包裹语句的那个 `}`：语句本身配平就整行保留；
        // 只有去掉行尾一个 `}` 才配平时才去掉它。
        if (!isBalanced(code) && code.endsWith("}") && isBalanced(code.dropLast(1))) code = code.dropLast(1)
        diagnostics += GenUiDiagnostic("truncated_statement", start)
        addStatement(code, start, keepKeyword)
        return lineEnd
    }

    private fun isBalanced(code: String): Boolean =
        GenUiJsScanner.findClosingBrace("{$code}", 1) == code.length + 1

    private fun addStatement(raw: String, pos: Int, keepConstKeyword: Boolean) {
        val code = raw.trim().let { if (keepConstKeyword) "const $it" else it }.trimEnd(';').trim()
        if (code.isNotEmpty()) statements += DilStatement(code, pos)
    }

    private fun containsNewConstruct(from: Int, to: Int): Boolean {
        var pos = from
        while (pos < to && pos < n) {
            val end = source.indexOf('\n', pos).let { if (it < 0) n else it }
            val trimmed = source.substring(pos, end).trimStart()
            if (pos > from && (isStatementStart(trimmed) || startsStrongTag(trimmed) || trimmed.startsWith("{#"))) return true
            pos = end + 1
        }
        return false
    }

    private fun lineCount(from: Int, to: Int): Int = source.substring(from, to).count { it == '\n' } + 1

    private fun skipBlankRest(pos: Int): Int {
        var i = pos
        while (i < n && (source[i] == ' ' || source[i] == '\t' || source[i] == '\r')) i++
        return if (i < n && source[i] == '\n') i + 1 else i
    }

    /* ---------- 互动岛 ---------- */

    private fun startsIsland(trimmed: String): Boolean {
        if (trimmed.startsWith("{#if") || trimmed.startsWith("{#each")) return true
        return startsTag(trimmed, allowWeak = hasStatements)
    }

    private fun startsStrongTag(trimmed: String): Boolean = startsTag(trimmed, allowWeak = false)

    private fun startsTag(trimmed: String, allowWeak: Boolean): Boolean {
        if (trimmed.length < 2 || trimmed[0] != '<' || !trimmed[1].isLetter()) return false
        val name = readName(trimmed, 1)
        if (name.isEmpty()) return false
        val after = trimmed.getOrNull(1 + name.length)
        if (after != null && after != '>' && after != '/' && !after.isWhitespace()) return false
        GenUiCatalog.resolve(name) ?: return false
        return allowWeak || !GenUiCatalog.isWeakTag(name)
    }

    private fun looksLikeUnfinishedConstruct(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("{")) {
            return trimmed.length < 7 && ("{@body".startsWith(trimmed) || "{@const".startsWith(trimmed) ||
                "{#if".startsWith(trimmed) || "{#each".startsWith(trimmed)) ||
                isStatementStart(trimmed) || trimmed.startsWith("{#")
        }
        if (trimmed[0] != '<') return false
        if (trimmed.length == 1) return true
        val name = readName(trimmed, 1)
        if (name.length != trimmed.length - 1) return false // 名字已写完，交给正常判断
        return GenUiCatalog.components.any { it.name.startsWith(name) } ||
            GenUiCatalog.ALIASES.keys.any { it.startsWith(name) }
    }

    /**
     * 解析从 [start] 开始的一串互动岛：紧挨着的（中间只隔空行或语句）合并成同一个岛，
     * 这样同一张卡片的几个部分不会被拆成几个列表条目。
     */
    private fun parseIslandRun(start: Int): Int {
        val nodes = mutableListOf<DilNode>()
        var allComplete = true
        var pos = start
        var end = start
        while (true) {
            val parser = IslandParser(pos)
            val result = parser.parseTopLevel()
            nodes += result.nodes
            allComplete = allComplete && result.complete
            end = result.end
            if (!result.complete) break
            // 岛之后同一行若还有文字，交回 Markdown。
            val rest = skipBlankRest(end)
            if (rest < n && rest > 0 && source[rest - 1] != '\n') {
                end = rest
                break
            }
            // 往后看：跳过空行与语句，下一行仍是岛就并进来。
            var probe = rest
            var merged = false
            while (probe < n) {
                val lineEnd = source.indexOf('\n', probe).let { if (it < 0) n else it }
                val line = source.substring(probe, lineEnd)
                val trimmed = line.trimStart()
                if (trimmed.isEmpty()) {
                    probe = if (lineEnd < n) lineEnd + 1 else n
                    continue
                }
                if (isStatementStart(trimmed)) {
                    val stEnd = parseStatementLine(probe + (line.length - trimmed.length), lineEnd)
                    if (stEnd < 0) {
                        probe = n
                        break
                    }
                    probe = skipBlankRest(stEnd)
                    continue
                }
                if (startsIsland(trimmed)) {
                    pos = probe + (line.length - trimmed.length)
                    merged = true
                }
                break
            }
            if (!merged) {
                end = rest
                break
            }
        }
        if (nodes.isNotEmpty()) {
            segments += GenUiSegment.Island(segments.count { it is GenUiSegment.Island }, nodes, allComplete, start, end)
        }
        return if (allComplete) end else n
    }

    private data class IslandResult(val nodes: List<DilNode>, val complete: Boolean, val end: Int)

    private enum class Mode { ELEMENT, MARKDOWN }

    private sealed interface Stop {
        data object Eof : Stop
        data class CloseTag(val name: String, val end: Int) : Stop
        data class Control(val token: String, val payload: String, val end: Int) : Stop
    }

    private data class Children(val nodes: List<DilNode>, val stop: Stop)

    private inner class IslandParser(private var i: Int) {
        private val openTags = ArrayDeque<String>()

        /** 外层正在等待的控制结束标记（`{/if}`、`{:else}`…）；元素内部遇到时自动闭合并向上交还。 */
        private val activeTerminators = ArrayDeque<Set<String>>()

        fun parseTopLevel(): IslandResult {
            val start = i
            val nodes = mutableListOf<DilNode>()
            val stop = parseOne(nodes, Mode.MARKDOWN)
            val completeNode = stop !is Stop.Eof
            return IslandResult(nodes, completeNode, if (completeNode) i else n).also {
                if (!completeNode && nodes.isEmpty()) diagnostics += GenUiDiagnostic("incomplete_island", start)
            }
        }

        /** 解析一个元素或控制块；成功返回 null，遇到末尾返回 Eof。 */
        private fun parseOne(out: MutableList<DilNode>, mode: Mode): Stop? {
            return when {
                source.startsWith("{#if", i) -> parseIf(out, mode)
                source.startsWith("{#each", i) -> parseEach(out, mode)
                source.startsWith("<", i) -> parseElement(out)
                else -> null
            }
        }

        private fun isTerminator(token: String, own: Set<String>): Boolean =
            token in own || activeTerminators.any { token in it }

        private fun parseChildren(mode: Mode, terminators: Set<String>): Children {
            val nodes = mutableListOf<DilNode>()
            val text = StringBuilder()
            var textStart = i
            fun flushText() {
                if (text.isNotEmpty()) {
                    nodes += if (mode == Mode.MARKDOWN) DilMarkdown(text.toString(), textStart) else DilText(text.toString(), textStart)
                    text.setLength(0)
                }
            }
            while (i < n) {
                val c = source[i]
                if (c == '<') {
                    if (source.startsWith("<!--", i)) {
                        flushText()
                        val end = source.indexOf("-->", i + 4)
                        if (end < 0) return Children(nodes, Stop.Eof)
                        i = end + 3
                        textStart = i
                        continue
                    }
                    if (source.startsWith("</", i)) {
                        val name = readName(source, i + 2)
                        val close = source.indexOf('>', i + 2)
                        if (close < 0) {
                            flushText()
                            return Children(nodes, Stop.Eof)
                        }
                        if (name.isNotEmpty() && GenUiCatalog.resolve(name) != null) {
                            flushText()
                            return Children(nodes, Stop.CloseTag(name, close + 1))
                        }
                    } else if (i + 1 < n && source[i + 1].isLetter()) {
                        val name = readName(source, i + 1)
                        val after = source.getOrNull(i + 1 + name.length)
                        if (name.isNotEmpty() && GenUiCatalog.resolve(name) != null &&
                            (after == null || after == '>' || after == '/' || after.isWhitespace())
                        ) {
                            flushText()
                            val stop = parseElement(nodes)
                            if (stop != null) return Children(nodes, stop)
                            textStart = i
                            continue
                        }
                        if (name.isNotEmpty() && i + 1 + name.length >= n && !complete) {
                            // 末尾半截的标签名：等写完再判断
                            flushText()
                            return Children(nodes, Stop.Eof)
                        }
                    }
                    if (text.isEmpty()) textStart = i
                    text.append(c)
                    i++
                    continue
                }
                if (c == '{') {
                    flushText()
                    when {
                        source.startsWith("{#if", i) -> {
                            val stop = parseIf(nodes, mode)
                            if (stop != null) return Children(nodes, stop)
                        }
                        source.startsWith("{#each", i) -> {
                            val stop = parseEach(nodes, mode)
                            if (stop != null) return Children(nodes, stop)
                        }
                        source.startsWith("{:", i) || source.startsWith("{/", i) -> {
                            val close = GenUiJsScanner.findClosingBrace(source, i + 1)
                            if (close < 0) return Children(nodes, Stop.Eof)
                            val inner = source.substring(i + 1, close).trim()
                            val token = CONTROL_TOKEN.find(inner)?.value ?: inner
                            val payload = inner.removePrefix(token).trim()
                            val end = close + 1
                            if (isTerminator(token, terminators)) {
                                return Children(nodes, Stop.Control(token, payload, end))
                            }
                            diagnostics += GenUiDiagnostic("stray_block_tag", i, token)
                            i = end
                        }
                        source.startsWith("{@", i) && STATEMENT_START.containsMatchIn(source.substring(i, minOf(n, i + 12))) -> {
                            val close = GenUiJsScanner.findClosingBrace(source, i + 1)
                            if (close < 0) return Children(nodes, Stop.Eof)
                            val keyword = STATEMENT_START.find(source.substring(i, minOf(n, i + 12)))!!
                            addStatement(source.substring(i + keyword.value.length, close), i, keyword.groupValues[1] == "const")
                            i = close + 1
                        }
                        else -> {
                            val close = GenUiJsScanner.findClosingBrace(source, i + 1)
                            if (close < 0) {
                                diagnostics += GenUiDiagnostic("unterminated_braced_value", i)
                                return Children(nodes, Stop.Eof)
                            }
                            val code = source.substring(i + 1, close).trim()
                            if (code.isEmpty()) {
                                text.append("{}")
                            } else {
                                nodes += DilExpr(code, i)
                            }
                            i = close + 1
                        }
                    }
                    textStart = i
                    continue
                }
                if (text.isEmpty()) textStart = i
                text.append(c)
                i++
            }
            flushText()
            return Children(nodes, Stop.Eof)
        }

        /**
         * 解析一个元素。返回 null = 正常结束；Eof = 末尾未写完；
         * Control = 元素内部遇到了外层控制块的结束标记，本元素已自动闭合，标记交还外层。
         */
        private fun parseElement(out: MutableList<DilNode>): Stop? {
            val start = i
            val rawName = readName(source, i + 1)
            val component = GenUiCatalog.resolve(rawName)
            if (rawName.isEmpty() || component == null) {
                i++
                return null
            }
            i += 1 + rawName.length
            val attrs = mutableListOf<DilAttr>()
            var selfClosed = false
            while (true) {
                while (i < n && source[i].isWhitespace()) i++
                if (i >= n) {
                    diagnostics += GenUiDiagnostic("unterminated_tag", start, rawName)
                    return Stop.Eof
                }
                val c = source[i]
                if (c == '>') {
                    i++
                    break
                }
                if (c == '/' && source.startsWith("/>", i)) {
                    i += 2
                    selfClosed = true
                    break
                }
                if (c == '{') {
                    // `{...props}` 展开：不支持，跳过
                    val close = GenUiJsScanner.findClosingBrace(source, i + 1)
                    if (close < 0) {
                        diagnostics += GenUiDiagnostic("unterminated_tag", start, rawName)
                        return Stop.Eof
                    }
                    diagnostics += GenUiDiagnostic("unsupported_spread", i)
                    i = close + 1
                    continue
                }
                val attrStart = i
                val name = readAttrName(i)
                if (name.isEmpty()) {
                    i++
                    continue
                }
                i += name.length
                while (i < n && (source[i] == ' ' || source[i] == '\t')) i++
                if (i < n && source[i] == '=') {
                    i++
                    while (i < n && (source[i] == ' ' || source[i] == '\t')) i++
                    if (i >= n) {
                        diagnostics += GenUiDiagnostic("unterminated_tag", start, rawName)
                        return Stop.Eof
                    }
                    when (val q = source[i]) {
                        '"', '\'' -> {
                            val close = source.indexOf(q, i + 1)
                            if (close < 0) {
                                diagnostics += GenUiDiagnostic("unterminated_tag", start, rawName)
                                return Stop.Eof
                            }
                            attrs += DilAttr(name, DilAttrKind.STRING, decodeEntities(source.substring(i + 1, close)), attrStart)
                            i = close + 1
                        }
                        '{' -> {
                            val close = GenUiJsScanner.findClosingBrace(source, i + 1)
                            if (close < 0) {
                                diagnostics += GenUiDiagnostic("unterminated_tag", start, rawName)
                                return Stop.Eof
                            }
                            attrs += DilAttr(name, DilAttrKind.EXPR, source.substring(i + 1, close).trim(), attrStart)
                            i = close + 1
                        }
                        else -> {
                            var end = i
                            while (end < n && !source[end].isWhitespace() && source[end] != '>' &&
                                !(source[end] == '/' && source.startsWith("/>", end))
                            ) end++
                            attrs += DilAttr(name, DilAttrKind.STRING, source.substring(i, end), attrStart)
                            i = end
                        }
                    }
                } else {
                    attrs += DilAttr(name, DilAttrKind.BARE, "true", attrStart)
                }
            }

            if (component.name == "AppBlock") {
                if (selfClosed) {
                    out += DilRaw(component.name, attrs, "", complete = true, pos = start)
                    return null
                }
                val closeTag = "</$rawName>"
                val close = source.indexOf(closeTag, i)
                if (close < 0) {
                    out += DilRaw(component.name, attrs, source.substring(i), complete = false, pos = start)
                    i = n
                    return Stop.Eof
                }
                out += DilRaw(component.name, attrs, source.substring(i, close), complete = true, pos = start)
                i = close + closeTag.length
                return null
            }

            if (selfClosed || !component.acceptsChildren) {
                out += DilElement(component.name, rawName, attrs, emptyList(), start)
                if (!selfClosed) skipImmediateClose(rawName)
                return null
            }

            if (GenUiCatalog.isOpaque(rawName)) {
                val closeTag = "</$rawName>"
                val close = source.indexOf(closeTag, i)
                val content = if (close < 0) source.substring(i) else source.substring(i, close)
                out += DilElement(component.name, rawName, attrs, listOf(DilText(content, i)), start)
                if (close < 0) {
                    i = n
                    return Stop.Eof
                }
                i = close + closeTag.length
                return null
            }

            openTags.addLast(component.name)
            val children = parseChildren(Mode.ELEMENT, emptySet())
            openTags.removeLast()
            return when (val stop = children.stop) {
                is Stop.CloseTag -> {
                    val closing = GenUiCatalog.canonicalName(stop.name)
                    when {
                        closing == component.name -> i = stop.end
                        // 关的是更外层的标签：本元素自动闭合，关闭标签留给外层消费
                        closing != null && closing in openTags -> diagnostics += GenUiDiagnostic("auto_closed", start, rawName)
                        // 不相干的关闭标签（多半是拼错的闭合名）：吞掉，当作本元素结束
                        else -> {
                            diagnostics += GenUiDiagnostic("mismatched_close", stop.end, stop.name)
                            i = stop.end
                        }
                    }
                    out += DilElement(component.name, rawName, attrs, children.nodes, start)
                    null
                }
                is Stop.Control -> {
                    diagnostics += GenUiDiagnostic("auto_closed", start, rawName)
                    out += DilElement(component.name, rawName, attrs, children.nodes, start)
                    stop
                }
                Stop.Eof -> {
                    // 流式半截：有内容就自动闭合显示，没内容（或只有空白）先不出现
                    if (children.nodes.any { !(it is DilText && it.value.isBlank()) }) {
                        diagnostics += GenUiDiagnostic("unclosed_element", start, rawName)
                        out += DilElement(component.name, rawName, attrs, children.nodes, start)
                    }
                    Stop.Eof
                }
            }
        }

        private fun parseIf(out: MutableList<DilNode>, mode: Mode): Stop? {
            val start = i
            val close = GenUiJsScanner.findClosingBrace(source, i + 1)
            if (close < 0) return Stop.Eof
            var condition: String? = source.substring(i + 4, close).trim()
            i = close + 1
            val branches = mutableListOf<DilBranch>()
            val own = setOf(":else", ":else if", "/if")
            activeTerminators.addLast(own)
            try {
                while (true) {
                    val children = parseChildren(mode, own)
                    val stop = children.stop
                    branches += DilBranch(condition, children.nodes)
                    when {
                        stop is Stop.Control && stop.token == "/if" -> {
                            i = stop.end
                            out += DilIf(branches, start)
                            return null
                        }
                        stop is Stop.Control && stop.token == ":else if" -> {
                            condition = stop.payload
                            i = stop.end
                        }
                        stop is Stop.Control && stop.token == ":else" -> {
                            condition = null
                            i = stop.end
                        }
                        else -> {
                            // 末尾未写完，或外层的结束标记：有内容就自动闭合
                            if (branches.any { it.body.isNotEmpty() }) {
                                diagnostics += GenUiDiagnostic("unclosed_block", start, "if")
                                out += DilIf(branches, start)
                            }
                            return stop
                        }
                    }
                }
            } finally {
                activeTerminators.removeLast()
            }
        }

        private fun parseEach(out: MutableList<DilNode>, mode: Mode): Stop? {
            val start = i
            val close = GenUiJsScanner.findClosingBrace(source, i + 1)
            if (close < 0) return Stop.Eof
            val head = source.substring(i + 6, close).trim()
            i = close + 1
            val parsed = parseEachHead(head)
            if (parsed == null) diagnostics += GenUiDiagnostic("invalid_each", start)
            val body = mutableListOf<DilNode>()
            var empty = emptyList<DilNode>()
            var inEmpty = false
            val own = setOf(":else", "/each")
            activeTerminators.addLast(own)
            try {
                while (true) {
                    val children = parseChildren(mode, own)
                    val stop = children.stop
                    if (inEmpty) empty = children.nodes else body += children.nodes
                    when {
                        stop is Stop.Control && stop.token == "/each" -> {
                            i = stop.end
                            if (parsed != null) out += DilEach(parsed.first, parsed.second, parsed.third, body, empty, start)
                            return null
                        }
                        stop is Stop.Control && stop.token == ":else" -> {
                            inEmpty = true
                            i = stop.end
                        }
                        else -> {
                            if (parsed != null && body.isNotEmpty()) {
                                diagnostics += GenUiDiagnostic("unclosed_block", start, "each")
                                out += DilEach(parsed.first, parsed.second, parsed.third, body, empty, start)
                            }
                            return stop
                        }
                    }
                }
            } finally {
                activeTerminators.removeLast()
            }
        }

        private fun skipImmediateClose(rawName: String) {
            var j = i
            while (j < n && source[j].isWhitespace() && source[j] != '\n') j++
            val closeTag = "</$rawName>"
            if (source.startsWith(closeTag, j)) i = j + closeTag.length
        }

        private fun readAttrName(at: Int): String {
            if (at >= n) return ""
            val first = source[at]
            if (!(first.isLetter() || first == '_' || first == ':' || first == '@')) return ""
            var end = at + 1
            while (end < n && (source[end].isJsIdentifierPart() || source[end] == '-' || source[end] == ':' || source[end] == '.')) end++
            return source.substring(at, end)
        }
    }

    /** `items as item, i (item.id)` / `rows as {name, value}, idx` → (列表, 项, 下标)。 */
    private fun parseEachHead(head: String): Triple<String, String, String?>? {
        val asIndex = topLevelAs(head)
        if (asIndex < 0) return null
        val list = head.substring(0, asIndex).trim()
        var rest = head.substring(asIndex + 4).trim()
        // Svelte 的 keyed each：末尾 (key) 去掉
        if (rest.endsWith(")")) {
            val open = rest.lastIndexOf('(')
            if (open > 0) rest = rest.substring(0, open).trim()
        }
        val item: String
        var index: String? = null
        if (rest.startsWith("{") || rest.startsWith("[")) {
            val close = GenUiJsScanner.findClosingBrace(source = rest.replace('[', '{').replace(']', '}'), start = 1)
            if (close < 0) return null
            item = rest.substring(0, close + 1)
            index = rest.substring(close + 1).trim().removePrefix(",").trim().takeIf { it.isNotEmpty() }
        } else {
            val parts = rest.split(',').map(String::trim)
            item = parts.first()
            index = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        }
        if (list.isEmpty() || item.isEmpty()) return null
        return Triple(list, item, index)
    }

    private fun topLevelAs(head: String): Int {
        var depth = 0
        var i = 0
        var found = -1
        while (i < head.length) {
            val c = head[i]
            when {
                c == '"' || c == '\'' -> {
                    i = GenUiJsScanner.skipString(head, i, c).takeIf { it > 0 } ?: return found
                    continue
                }
                c == '`' -> {
                    i = GenUiJsScanner.skipTemplate(head, i).takeIf { it > 0 } ?: return found
                    continue
                }
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                depth == 0 && head.startsWith(" as ", i) -> found = i
            }
            i++
        }
        return found
    }

    companion object {
        private val STATEMENT_START = Regex("^\\{@(body|const)\\s")
        private val STATEMENT_LINE = Regex("(?m)^[ \\t]*\\{@(body|const)\\s")
        private val CONTROL_TOKEN = Regex("^(:else if|:else|/if|/each)")
        private val WRAPPER_FENCE_LANGS = setOf("", "html", "dil", "jsx", "tsx", "xml", "svelte", "vue", "md", "markdown", "mdx")
        private const val MAX_STATEMENT_LINES = 80
    }
}

internal fun readName(source: String, start: Int): String {
    var end = start
    while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '-' || source[end] == '_')) end++
    return source.substring(start, end)
}

private val ENTITY = Regex("&(lt|gt|amp|quot|apos|nbsp|#\\d{1,6}|#x[0-9a-fA-F]{1,6});")

internal fun decodeEntities(value: String): String {
    if (value.indexOf('&') < 0) return value
    return ENTITY.replace(value) { m ->
        when (val e = m.groupValues[1]) {
            "lt" -> "<"
            "gt" -> ">"
            "amp" -> "&"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                val code = if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
}

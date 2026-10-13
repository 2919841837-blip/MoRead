package com.mozhi.reader.ai.genui

import com.mozhi.reader.ai.genui.runtime.GenUiEngine
import kotlinx.serialization.json.JsonPrimitive

/** 只编译不执行的语法检查；生产环境由 QuickJS 的 compile() 提供。 */
fun interface GenUiSyntaxChecker {
    suspend fun isValid(code: String): Boolean
}

/** 带缓存的语法检查：流式期间每个节拍都会重编译，绝大多数片段与上一拍相同。 */
class CachingSyntaxChecker(
    private val delegate: GenUiSyntaxChecker,
    private val capacity: Int = 4_000
) : GenUiSyntaxChecker {
    private val cache = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
    }

    override suspend fun isValid(code: String): Boolean {
        synchronized(cache) { cache[code] }?.let { return it }
        val ok = delegate.isValid(code)
        synchronized(cache) { cache[code] = ok }
        return ok
    }
}

data class GenUiCompilation(
    val document: GenUiDocument,
    /** 交给 [GenUiEngine.load] 的程序；槽编号以 [GenUiEngine.SLOT_PLACEHOLDER] 占位。 */
    val program: String,
    val stateKeys: List<String>,
    val diagnostics: List<GenUiDiagnostic>,
    /** true = 程序整体无法通过语法检查，已退成各岛的纯文字。 */
    val degraded: Boolean
) {
    val islandCount: Int get() = document.islands.size
}

/**
 * DIL 文档 → 运行时程序（设计见 design/companion-genui-plan.md §8）。
 *
 * 编译从不抛异常。容错分三层：
 * 1. **修复**：未声明却被受控控件使用的状态自动补声明；HTML 式字符串事件、`onClick={setX(1)}`
 *    这类会在渲染期执行的写法包成箭头函数；重复声明丢弃后一条。
 * 2. **隔离**：常量初值、条件、列表、插值、含表达式的元素各套一层 `__safe`，抛错只丢自己。
 * 3. **校验**：先整体检查一次（绝大多数节拍走这条快路）；不通过再逐段检查，换掉坏片段重拼；
 *    仍不通过就退成纯文字程序，界面不会白屏。
 *
 * 参照 Disdjj/intelligent-ui-demo（MIT）复刻编译器的容错规则，Kotlin 重写。
 */
class GenUiCompiler(private val checker: GenUiSyntaxChecker) {

    suspend fun compile(document: GenUiDocument): GenUiCompilation {
        val diagnostics = document.diagnostics.toMutableList()
        val statements = prepareStatements(document, diagnostics)

        // 第一遍：不做逐段校验，收集所有片段。
        val firstPass = Codegen(document, statements, diagnostics, verdict = null)
        val optimistic = firstPass.program()
        if (checker.isValid(optimistic)) {
            return GenUiCompilation(document, optimistic, statements.stateKeys, firstPass.diagnostics(), degraded = false)
        }

        // 第二遍：逐段检查，坏片段换掉重拼。
        val verdict = HashMap<String, Boolean>()
        for (fragment in firstPass.fragments) {
            verdict[fragment] = checker.isValid(fragment)
        }
        for (statement in statements.items) {
            verdict[statement.checkCode] = checker.isValid(statement.checkCode)
        }
        val secondDiagnostics = document.diagnostics.toMutableList().also { it += statements.diagnostics }
        val secondPass = Codegen(document, statements, secondDiagnostics, verdict)
        val repaired = secondPass.program()
        if (checker.isValid(repaired)) {
            return GenUiCompilation(document, repaired, statements.stateKeys, secondPass.diagnostics(), degraded = false)
        }

        val fallbackDiagnostics = secondPass.diagnostics() + GenUiDiagnostic("invalid_program", 0)
        return GenUiCompilation(
            document = document,
            program = fallbackProgram(document),
            stateKeys = emptyList(),
            diagnostics = fallbackDiagnostics,
            degraded = true
        )
    }

    /* ---------------------------- 语句 ---------------------------- */

    private class PreparedStatement(
        /** 改写后进入程序的语句。 */
        val code: String,
        /** 逐段校验时的包装（函数体上下文）。 */
        val checkCode: String,
        val pos: Int
    )

    private class PreparedStatements(
        val items: List<PreparedStatement>,
        val stateKeys: List<String>,
        val declared: Set<String>,
        val diagnostics: List<GenUiDiagnostic>
    )

    private fun prepareStatements(document: GenUiDocument, sink: MutableList<GenUiDiagnostic>): PreparedStatements {
        val diagnostics = mutableListOf<GenUiDiagnostic>()
        val declared = LinkedHashSet<String>()
        val stateKeys = mutableListOf<String>()
        val items = mutableListOf<PreparedStatement>()
        for (statement in document.statements) {
            val rewritten = rewriteStatement(statement.code)
            val names = rewritten.declares
            val clash = names.firstOrNull { it in declared || it in RESERVED }
            if (clash != null) {
                diagnostics += GenUiDiagnostic(
                    if (clash in RESERVED) "reserved_name" else "duplicate_declaration",
                    statement.pos,
                    clash
                )
                continue
            }
            declared += names
            rewritten.stateKey?.let(stateKeys::add)
            items += PreparedStatement(rewritten.code, "(function(){${rewritten.code}\n;})", statement.pos)
        }

        // 修复：受控控件 value={x} onChange={setX}，x 从未声明 → 补一条 useState。
        val implicit = mutableListOf<PreparedStatement>()
        for (pair in controlPairs(document)) {
            if (pair.name in declared || pair.setter in declared) continue
            declared += pair.name
            declared += pair.setter
            stateKeys += pair.name
            val code = "const [${pair.name}, ${pair.setter}] = useState(__safe(() => (${pair.seed}), undefined), {key: ${pair.name.js()}})"
            implicit += PreparedStatement(code, "(function(){$code\n;})", pair.pos)
            diagnostics += GenUiDiagnostic("implicit_state", pair.pos, pair.name)
        }
        sink += diagnostics
        return PreparedStatements(implicit + items, stateKeys, declared, diagnostics)
    }

    private data class Rewritten(val code: String, val declares: Set<String>, val stateKey: String? = null)

    private fun rewriteStatement(raw: String): Rewritten {
        val code = raw.trim().trimEnd(';').trim()
        USE_STATE.matchEntire(code)?.let { m ->
            val name = m.groupValues[2]
            val setter = m.groupValues[3].ifEmpty { null }
            val init = m.groupValues[4].trim().ifEmpty { "undefined" }
            val pattern = if (setter != null) "[$name, $setter]" else "[$name]"
            val rewritten = "${m.groupValues[1]} $pattern = useState(__safe(() => ($init\n), undefined), {key: ${name.js()}})"
            return Rewritten(rewritten, setOfNotNull(name, setter), stateKey = name)
        }
        FUNCTION_DECL.find(code)?.let { m ->
            return Rewritten(code, setOf(m.groupValues[1]))
        }
        SIMPLE_DECL.matchEntire(code)?.let { m ->
            val keyword = m.groupValues[1]
            val name = m.groupValues[2]
            val expr = m.groupValues[3]
            if (!GenUiJsScanner.hasTopLevelComma(expr)) {
                return Rewritten("$keyword $name = __safe(() => ($expr\n), undefined)", setOf(name))
            }
            return Rewritten(code, declaredNames(code))
        }
        DESTRUCTURE.matchEntire(code)?.let { m ->
            val keyword = m.groupValues[1]
            val pattern = m.groupValues[2]
            val expr = m.groupValues[3]
            val fallback = if (pattern.startsWith("{")) "{}" else "[]"
            return Rewritten("$keyword $pattern = __safe(() => ($expr\n), $fallback)", declaredNames(code))
        }
        if (BARE_DECL.matches(code) || DECLARATION.containsMatchIn(code)) {
            return Rewritten(code, declaredNames(code))
        }
        // 其余语句（循环、赋值、调用）：包 try，抛错不影响后面的语句。
        return Rewritten("try { $code\n; } catch (__e) {}", emptySet())
    }

    /** 声明语句里引入的名字（粗略推断，用于查重；漏判只会让重复声明走到整体校验那一关）。 */
    private fun declaredNames(code: String): Set<String> {
        val out = LinkedHashSet<String>()
        DECL_PATTERN.find(code)?.let { m ->
            val target = m.groupValues[2]
            if (target.startsWith("{") || target.startsWith("[")) {
                GenUiJsScanner.identifiers(target).forEach { id ->
                    // 解构里冒号前的是属性名：{a: b} 只声明 b
                    if (!Regex("\\b${Regex.escape(id)}\\s*:").containsMatchIn(target)) out += id
                }
            } else {
                out += target
            }
        }
        MORE_DECLARATORS.findAll(code).forEach { out += it.groupValues[1] }
        return out
    }

    private data class ControlPair(val name: String, val setter: String, val seed: String, val pos: Int)

    private fun controlPairs(document: GenUiDocument): List<ControlPair> {
        val out = mutableListOf<ControlPair>()
        fun visit(nodes: List<DilNode>) {
            for (node in nodes) {
                when (node) {
                    is DilElement -> {
                        val value = (node.attr("value") ?: node.attr("checked"))?.takeIf { it.kind == DilAttrKind.EXPR }
                        val change = node.attr("onChange")?.takeIf { it.kind == DilAttrKind.EXPR }
                        if (value != null && change != null) {
                            val name = value.value.trim()
                            val setter = change.value.trim()
                            if (IDENTIFIER.matches(name) && setter == "set" + name.replaceFirstChar(Char::uppercaseChar)) {
                                out += ControlPair(name, setter, seedFor(node), node.pos)
                            }
                        }
                        visit(node.children)
                    }
                    is DilIf -> node.branches.forEach { visit(it.body) }
                    is DilEach -> {
                        visit(node.body)
                        visit(node.empty)
                    }
                    else -> Unit
                }
            }
        }
        document.islands.forEach { visit(it.nodes) }
        return out.distinctBy { it.name }
    }

    /** 未声明状态的初值：选项取第一个、滑条取 min、勾选框取 false、单选取第一项、其余空串。 */
    private fun seedFor(node: DilElement): String {
        node.attr("options")?.let { options ->
            if (options.kind == DilAttrKind.EXPR) {
                val o = "(${options.value})"
                return "(Array.isArray($o) && $o.length) ? ($o[0] && typeof $o[0] === 'object' ? ($o[0].value ?? $o[0].label) : $o[0]) : \"\""
            }
        }
        return when (node.name) {
            "checkbox", "switch" -> "false"
            "chips" -> "[]"
            "slider", "stepper" -> node.attr("min")?.let { min ->
                if (min.kind == DilAttrKind.STRING) min.value.toDoubleOrNull()?.let(::numberLiteral) ?: "0" else "(${min.value})"
            } ?: "0"
            "radio-group" -> node.children.firstNotNullOfOrNull { child ->
                (child as? DilElement)?.takeIf { it.name == "radio" }?.attr("value")?.let { v ->
                    if (v.kind == DilAttrKind.STRING) v.value.js() else "(${v.value})"
                }
            } ?: "\"\""
            "tabs" -> node.children.firstNotNullOfOrNull { child ->
                (child as? DilElement)?.takeIf { it.name == "tab" }?.attr("value")?.let { v ->
                    if (v.kind == DilAttrKind.STRING) v.value.js() else "(${v.value})"
                }
            } ?: "\"\""
            else -> "\"\""
        }
    }

    /* ---------------------------- 代码生成 ---------------------------- */

    /**
     * @param verdict null = 第一遍（乐观，收集片段）；非 null = 第二遍，按校验结果丢弃坏片段
     */
    private inner class Codegen(
        private val document: GenUiDocument,
        private val statements: PreparedStatements,
        private val sink: MutableList<GenUiDiagnostic>,
        private val verdict: Map<String, Boolean>?
    ) {
        val fragments = LinkedHashSet<String>()
        private val local = mutableListOf<GenUiDiagnostic>()

        fun diagnostics(): List<GenUiDiagnostic> = sink + local

        fun program(): String = buildString {
            append("__genui.define(").append(GenUiEngine.SLOT_PLACEHOLDER)
            append(", function (h, useState, useMemo, useRef, useCallback, __each, __safe, app, DIL, GenUI) {\n")
            append("\"use strict\";\nconst React = DIL;\n")
            for (statement in statements.items) {
                if (verdict != null && verdict[statement.checkCode] == false) {
                    local += GenUiDiagnostic("invalid_statement", statement.pos)
                    continue
                }
                append(statement.code).append(";\n")
            }
            append("return [")
            document.islands.forEachIndexed { index, island ->
                if (index > 0) append(",\n")
                append('[')
                append(nodes(island.nodes, prefix = "").joinToString(", "))
                append(']')
            }
            append("];\n});")
        }

        /** 片段是否可用；第一遍一律可用并登记。 */
        private fun ok(check: String): Boolean {
            if (verdict == null) {
                fragments += check
                return true
            }
            return verdict[check] != false
        }

        private fun expressionOk(code: String, pos: Int): Boolean {
            val good = ok(exprCheck(code))
            if (!good) local += GenUiDiagnostic("invalid_expression", pos)
            return good
        }

        private fun nodes(list: List<DilNode>, prefix: String): List<String> {
            val out = mutableListOf<String>()
            list.forEachIndexed { index, node ->
                val path = if (prefix.isEmpty()) "$index" else "$prefix.$index"
                node(node, path)?.let(out::add)
            }
            return out
        }

        private fun node(node: DilNode, path: String): String? = when (node) {
            is DilElement -> element(node, path)
            is DilText -> jsxText(node.value)?.let(::jsString)
            is DilMarkdown -> node.value.trim('\n', ' ', '\t', '\r').takeIf { it.isNotBlank() }
                ?.let { "h(\"#md\", {__k: ${path.js()}}, ${jsString(it)})" }
            is DilExpr -> if (expressionOk(node.code, node.pos)) "__safe(() => (${node.code}\n), null)" else null
            is DilIf -> ifBlock(node, path)
            is DilEach -> eachBlock(node, path)
            is DilRaw -> raw(node, path)
        }

        private fun element(node: DilElement, path: String): String? {
            val component = GenUiCatalog.resolve(node.name) ?: return null
            val props = mutableListOf("__k: ${path.js()}")
            var dynamic = false
            for (attr in node.attrs) {
                val name = PROP_ALIASES[attr.name] ?: attr.name
                if (name == "class" || name == "className") {
                    local += GenUiDiagnostic("unknown_prop", attr.pos, name)
                    continue
                }
                val spec = component.prop(name)
                if (spec == null) {
                    local += GenUiDiagnostic("unknown_prop", attr.pos, "${component.name}.$name")
                    continue
                }
                val value = propValue(spec, attr) ?: continue
                if (attr.kind == DilAttrKind.EXPR || spec.type == GenUiCatalog.PropType.HANDLER) dynamic = true
                props += "${name.jsKey()}: $value"
            }
            val children = nodes(node.children, path)
            val code = buildString {
                append("h(").append(component.name.js()).append(", {").append(props.joinToString(", ")).append('}')
                children.forEach { append(", ").append(it) }
                append(')')
            }
            return if (dynamic) "__safe(() => $code, null)" else code
        }

        private fun propValue(spec: GenUiCatalog.Prop, attr: DilAttr): String? {
            if (spec.type == GenUiCatalog.PropType.HANDLER) return handlerValue(attr)
            return when (attr.kind) {
                DilAttrKind.BARE -> "true"
                DilAttrKind.EXPR -> if (expressionOk(attr.value, attr.pos)) "(${attr.value}\n)" else null
                DilAttrKind.STRING -> literal(spec, attr)
            }
        }

        private fun literal(spec: GenUiCatalog.Prop, attr: DilAttr): String? {
            val raw = attr.value
            return when (spec.type) {
                GenUiCatalog.PropType.NUMBER -> raw.trim().removeSuffix("px").toDoubleOrNull()?.let(::numberLiteral) ?: run {
                    local += GenUiDiagnostic("invalid_literal", attr.pos, "${attr.name}=$raw")
                    null
                }
                GenUiCatalog.PropType.BOOLEAN -> when (raw.trim().lowercase()) {
                    "", "true", "1", "yes", attr.name.lowercase() -> "true"
                    "false", "0", "no" -> "false"
                    else -> {
                        local += GenUiDiagnostic("invalid_literal", attr.pos, "${attr.name}=$raw")
                        null
                    }
                }
                GenUiCatalog.PropType.STRING -> {
                    val values = spec.values
                    if (values != null && raw !in values) {
                        local += GenUiDiagnostic("invalid_literal", attr.pos, "${attr.name}=$raw")
                        null
                    } else {
                        raw.js()
                    }
                }
                else -> raw.js()
            }
        }

        /**
         * 事件处理器：
         * - `onClick={() => …}` / `onChange={setX}` 原样；
         * - `onClick={setTab("a")}` 会在渲染期就执行，包成 `() => (setTab("a"))`；
         * - HTML 式字符串 `onClick="go()"` 当函数体。
         */
        private fun handlerValue(attr: DilAttr): String? {
            val code = when (attr.kind) {
                DilAttrKind.STRING -> {
                    local += GenUiDiagnostic("handler_wrapped", attr.pos, attr.name)
                    "() => { ${attr.value}\n}"
                }
                DilAttrKind.BARE -> return null
                DilAttrKind.EXPR -> {
                    val v = attr.value.trim()
                    if (looksLikeFunction(v)) v else {
                        local += GenUiDiagnostic("handler_wrapped", attr.pos, attr.name)
                        "() => ($v\n)"
                    }
                }
            }
            return if (expressionOk(code, attr.pos)) "($code\n)" else null
        }

        private fun ifBlock(node: DilIf, path: String): String? {
            val parts = mutableListOf<Pair<String?, String>>()
            node.branches.forEachIndexed { index, branch ->
                val body = "[" + nodes(branch.body, "$path.b$index").joinToString(", ") + "]"
                val condition = branch.condition?.let { cond ->
                    if (cond.isBlank()) "false" else if (expressionOk(cond, node.pos)) "($cond\n)" else "false"
                }
                parts += condition to body
            }
            if (parts.isEmpty()) return null
            val expr = buildString {
                var closed = false
                for ((condition, body) in parts) {
                    if (condition == null) {
                        append(body)
                        closed = true
                        break
                    }
                    append(condition).append(" ? ").append(body).append(" : ")
                }
                if (!closed) append("[]")
            }
            return "__safe(() => ($expr), null)"
        }

        private fun eachBlock(node: DilEach, path: String): String? {
            if (!expressionOk(node.list, node.pos)) return null
            val index = node.index ?: "__i"
            val head = "(${node.item}, $index)"
            if (!ok("(function(){ return $head => 0; })")) {
                local += GenUiDiagnostic("invalid_each", node.pos)
                return null
            }
            val body = "[" + nodes(node.body, "$path.e").joinToString(", ") + "]"
            val each = "__each(__l, $head => $body, ${path.js()})"
            return if (node.empty.isEmpty()) {
                "__safe(() => { const __l = (${node.list}\n); return $each; }, null)"
            } else {
                val empty = "[" + nodes(node.empty, "$path.empty").joinToString(", ") + "]"
                "__safe(() => { const __l = (${node.list}\n); return (__l && __l.length) ? $each : $empty; }, null)"
            }
        }

        private fun raw(node: DilRaw, path: String): String {
            val props = mutableListOf("__k: ${path.js()}", "source: ${jsString(node.content)}", "complete: ${node.complete}")
            node.attrs.forEach { attr ->
                if (attr.kind == DilAttrKind.STRING && attr.name in setOf("title", "height")) {
                    props += "${attr.name}: " + if (attr.name == "height") {
                        attr.value.toDoubleOrNull()?.let(::numberLiteral) ?: "null"
                    } else attr.value.js()
                } else if (attr.kind == DilAttrKind.EXPR && attr.name == "height" && expressionOk(attr.value, attr.pos)) {
                    props += "height: (${attr.value}\n)"
                }
            }
            return "h(\"AppBlock\", {${props.joinToString(", ")}})"
        }
    }

    /** 整体校验失败时的兜底：每个岛显示自己的纯文字投影。 */
    private fun fallbackProgram(document: GenUiDocument): String = buildString {
        append("__genui.define(").append(GenUiEngine.SLOT_PLACEHOLDER)
        append(", function (h) {\nreturn [")
        document.islands.forEachIndexed { index, island ->
            if (index > 0) append(", ")
            val text = GenUiFallback.projectNodes(island.nodes)
            append("[h(\"#md\", {__k: \"0\"}, ").append(jsString(text)).append(")]")
        }
        append("];\n});")
    }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_$][\\w$]*")
        // Android 使用 ICU 正则：字面量右花括号也必须转义，桌面 JVM 接受的裸 `}` 会让
        // 本类初始化抛出 PatternSyntaxException，第一条互动回复出现时直接闪退。
        private val USE_STATE = Regex(
            "^(const|let|var)\\s*\\[\\s*([A-Za-z_$][\\w$]*)\\s*(?:,\\s*([A-Za-z_$][\\w$]*)\\s*)?\\]\\s*=\\s*(?:DIL\\.|React\\.)?useState\\s*\\(([\\s\\S]*)\\)$"
        )
        private val FUNCTION_DECL = Regex("^(?:async\\s+)?function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*\\(")
        private val SIMPLE_DECL = Regex("^(const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*([\\s\\S]+)$")
        private val DESTRUCTURE = Regex("^(const|let|var)\\s+(\\{[\\s\\S]*?\\}|\\[[\\s\\S]*?\\])\\s*=\\s*([\\s\\S]+)$")
        private val BARE_DECL = Regex("^(let|var)\\s+[A-Za-z_$][\\w$]*$")
        private val DECLARATION = Regex("^(const|let|var)\\s")
        private val DECL_PATTERN = Regex("^(const|let|var)\\s+([A-Za-z_$][\\w$]*|\\{[^=]*\\}|\\[[^=]*\\])")
        private val MORE_DECLARATORS = Regex(",\\s*([A-Za-z_$][\\w$]*)\\s*=")

        /** 程序参数与头部声明，模型不能再声明同名变量（否则整段程序 SyntaxError）。 */
        private val RESERVED = setOf(
            "h", "useState", "useMemo", "useRef", "useCallback", "__each", "__safe", "app", "DIL", "GenUI", "React"
        )

        private val PROP_ALIASES = mapOf(
            "onclick" to "onClick",
            "onPress" to "onClick",
            "onTap" to "onClick",
            "onchange" to "onChange",
            "onValueChange" to "onChange",
            "oninput" to "onChange",
            "onInput" to "onChange",
            "onsubmit" to "onSubmit",
            "defaultValue" to "value",
            "selected" to "value"
        )

        private val FUNCTION_LIKE = Regex("^(async\\s+)?(\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>")
        private val REFERENCE = Regex("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*$")

        private fun looksLikeFunction(code: String): Boolean =
            REFERENCE.matches(code) || code.startsWith("function") || FUNCTION_LIKE.containsMatchIn(code)

        internal fun exprCheck(code: String): String = "(function(){ return ($code\n); })"

        internal fun numberLiteral(value: Double): String =
            if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

        private fun String.js(): String = JsonPrimitive(this).toString()

        private fun jsString(value: String): String = JsonPrimitive(value).toString()

        private fun String.jsKey(): String = if (IDENTIFIER.matches(this)) this else js()

        /**
         * JSX 的空白规则：含换行的文字逐行裁剪、丢空行后拼接；不含换行的原样保留（`价格 {x} 元`
         * 里的空格有意义）。中文行与行之间直接相连，不插空格。
         */
        internal fun jsxText(raw: String): String? {
            val text = decodeEntities(raw)
            if (!text.contains('\n')) return text.takeIf { it.isNotEmpty() }
            val lines = text.split('\n')
            val kept = mutableListOf<String>()
            lines.forEachIndexed { index, line ->
                var piece = line.replace('\t', ' ')
                piece = when (index) {
                    0 -> piece.trimEnd()
                    lines.lastIndex -> piece.trimStart()
                    else -> piece.trim()
                }
                if (piece.isNotBlank()) kept += piece
            }
            if (kept.isEmpty()) return null
            return buildString {
                kept.forEachIndexed { index, piece ->
                    if (index > 0) {
                        val prev = last()
                        val next = piece.first()
                        if (!(prev.isCjk() || next.isCjk())) append(' ')
                    }
                    append(piece)
                }
            }
        }

        private fun Char.isCjk(): Boolean {
            val block = Character.UnicodeBlock.of(this)
            return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        }
    }
}

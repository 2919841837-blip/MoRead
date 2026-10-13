package com.mozhi.reader.ai.genui

import com.mozhi.reader.ai.genui.runtime.GenUiEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GenUiCompilerTest {
    private lateinit var engine: GenUiEngine
    private lateinit var compiler: GenUiCompiler

    @Before
    fun setUp() = runBlocking {
        engine = GenUiEngine.create(File("src/main/assets/genui/runtime.js").readText())
        compiler = GenUiCompiler(CachingSyntaxChecker(GenUiSyntaxChecker { engine.isSyntaxValid(it) }))
    }

    @After
    fun tearDown() {
        engine.close()
    }

    @Test
    fun quizExampleCompilesCleanlyAndAnswersFlowThroughState() = runBlocking {
        val compiled = compiler.compile(GenUiDocument.parse(GenUiPromptExamples.QUIZ))
        assertFalse(compiled.degraded)
        assertEquals(emptyList<GenUiDiagnostic>(), compiled.diagnostics)
        val first = engine.load("quiz", compiled.program)
        assertTrue(first.errors.toString(), first.errors.isEmpty())

        val card = first.islands.single().children.single()
        val groups = card.findAll("radio-group")
        assertEquals(2, groups.size)
        assertEquals(3, groups[0].findAll("radio").size)
        val submit = card.findAll("button").single()
        assertTrue(submit.flag("disabled"))

        var state = first
        groups.forEachIndexed { index, group ->
            val current = state.islands.single().children.single().findAll("radio-group")[index]
            state = engine.invoke("quiz", current.handler("onChange")!!, JsonArray(listOf(JsonPrimitive(index * 2))))
        }
        val ready = state.islands.single().children.single().findAll("button").single()
        assertFalse(ready.flag("disabled"))
        val submitted = engine.invoke("quiz", ready.handler("onClick")!!, JsonArray(emptyList()))
        val after = submitted.islands.single().children.single()
        assertEquals("2 / 2", after.findAll("badge").single().plainText())
        assertEquals(2, after.findAll("Quote").size)
        val again = after.findAll("button").last()
        val action = engine.invoke("quiz", again.handler("onClick")!!, JsonArray(emptyList())).actions.single()
        assertEquals("send", action.type)
    }

    @Test
    fun styledExampleCompilesCleanlyAndRestylesWithState() = runBlocking {
        val compiled = compiler.compile(GenUiDocument.parse(GenUiPromptExamples.STYLED))
        assertEquals(emptyList<GenUiDiagnostic>(), compiled.diagnostics)
        val first = engine.load("styled", compiled.program)
        val box = first.islands.single().children.single()
        val name = box.findAll("text").single()
        val sliders = box.findAll("slider")
        assertEquals(3, sliders.size)
        // 勇气拉满、谋略拉到底：最像「主角」或「老兵」，不会是「军师」
        engine.invoke("styled", sliders[0].handler("onChange")!!, JsonArray(listOf(JsonPrimitive(100))))
        val moved = engine.invoke("styled", sliders[2].handler("onChange")!!, JsonArray(listOf(JsonPrimitive(0))))
        val renamed = moved.islands.single().children.single().findAll("text").single().plainText()
        assertTrue(renamed, renamed != "军师")
        assertTrue(name.prop("style").toString().contains("fontFamily"))
        assertTrue(name.key.contains("k:"))
    }

    @Test
    fun everyStreamedPrefixOfRealModelOutputCompilesAndRuns() = runBlocking {
        val fixtures = File("src/test/resources/genui/fixtures").listFiles { f -> f.name.endsWith(".dil.md") }!!.sortedBy { it.name }
        val sources = fixtures.map { it.name to it.readText() } +
            listOf("quiz" to GenUiPromptExamples.QUIZ, "styled" to GenUiPromptExamples.STYLED)
        for ((name, source) in sources) {
            val slot = "stream-$name"
            var lastIslands = 0
            var n = 1
            while (n <= source.length) {
                val prefix = source.substring(0, n)
                val done = n == source.length
                val compiled = compiler.compile(GenUiDocument.parse(prefix, complete = done))
                assertFalse("$name prefix $n fell back to text: ${compiled.diagnostics.takeLast(3)}", compiled.degraded)
                if (compiled.islandCount > 0) {
                    val result = engine.load(slot, compiled.program)
                    assertTrue("$name prefix $n render failed: ${result.errors}", result.errors.none { it.code == "render_failed" })
                    lastIslands = result.islands.size
                }
                n = if (n == source.length) n + 1 else minOf(source.length, n + STEP)
            }
            assertTrue("$name never produced an island", lastIslands > 0)
        }
    }

    @Test
    fun undeclaredControlledStateIsDeclaredFromTheControl() = runBlocking {
        val source = File("src/test/resources/genui/fixtures/model-t3.dil.md").readText()
        val compiled = compiler.compile(GenUiDocument.parse(source))
        assertTrue(compiled.diagnostics.any { it.code == "implicit_state" && it.detail == "purpose" })
        assertTrue("purpose" in compiled.stateKeys)
        val result = engine.load("t3", compiled.program)
        val segmented = result.islands.single().findAll("segmented").first()
        assertEquals("office", (segmented.prop("value") as JsonPrimitive).content)
    }

    @Test
    fun handlerCallsAreWrappedSoTheyDoNotRunDuringRender() = runBlocking {
        val source = "{@body const [tab,setTab] = useState(\"a\")}\n<card><button onClick={setTab(\"b\")}>B</button><text>{tab}</text></card>"
        val compiled = compiler.compile(GenUiDocument.parse(source))
        assertTrue(compiled.diagnostics.any { it.code == "handler_wrapped" })
        val first = engine.load("h", compiled.program)
        assertEquals("a", first.islands.single().findAll("text").single().plainText())
        val button = first.islands.single().findAll("button").single()
        val clicked = engine.invoke("h", button.handler("onClick")!!, JsonArray(emptyList()))
        assertEquals("b", clicked.islands.single().findAll("text").single().plainText())
    }

    @Test
    fun duplicateDeclarationsAndBrokenStatementsOnlyLoseThemselves() = runBlocking {
        val source = """
            {@body const [n,setN] = useState(1)}
            {@body const [n,setN] = useState(2)}
            {@body const broken = (1 + }
            {@body const label = "第 " + n + " 章"}
            <card><text>{label}</text><text>{broken}</text></card>
        """.trimIndent()
        val compiled = compiler.compile(GenUiDocument.parse(source))
        assertFalse(compiled.degraded)
        assertTrue(compiled.diagnostics.any { it.code == "duplicate_declaration" })
        assertTrue(compiled.diagnostics.any { it.code == "invalid_statement" })
        val result = engine.load("dup", compiled.program)
        val texts = result.islands.single().findAll("text").map { it.plainText() }
        assertEquals("第 1 章", texts.first())
    }

    @Test
    fun invalidLiteralsAndUnknownPropsAreDroppedWithDiagnostics() = runBlocking {
        val source = "<card fill=\"red\" className=\"x\"><title size=\"huge\">标题</title><slider min=\"abc\" max={10}/></card>"
        val compiled = compiler.compile(GenUiDocument.parse(source))
        val codes = compiled.diagnostics.map { it.code }
        assertTrue(codes.count { it == "unknown_prop" } >= 2)
        assertTrue(codes.count { it == "invalid_literal" } >= 2)
        val result = engine.load("lit", compiled.program)
        val title = result.islands.single().findAll("title").single()
        assertEquals(null, title.prop("size"))
        assertEquals("标题", title.plainText())
    }

    @Test
    fun treeProjectionReflectsCurrentState() = runBlocking {
        val compiled = compiler.compile(GenUiDocument.parse(GenUiPromptExamples.QUIZ))
        val result = engine.load("proj", compiled.program)
        val text = GenUiFallback.project(compiled.document, result.islands)
        assertTrue(text.contains("第五章 · 小测"))
        assertTrue(text.contains("主角离开故乡的直接原因是什么"))
        assertTrue(text.contains("( ) 被人陷害"))
        assertTrue(text.contains("答错的那题"))
        assertFalse(text.contains("{@body"))
    }

    @Test
    fun compileTimingForTheLargestFixtureStaysInterative() = runBlocking {
        val source = File("src/test/resources/genui/fixtures/model-g2.dil.md").readText()
        // 预热（JIT、缓存），再测一次完整的「解析 + 编译 + 求值」
        repeat(3) { engine.load("warm", compiler.compile(GenUiDocument.parse(source)).program) }
        val start = System.nanoTime()
        val compiled = compiler.compile(GenUiDocument.parse(source + " "))
        val compiledAt = System.nanoTime()
        engine.load("timed", compiled.program)
        val end = System.nanoTime()
        println("genui timing: ${source.length} chars, compile=${(compiledAt - start) / 1_000_000}ms, eval=${(end - compiledAt) / 1_000_000}ms")
        assertTrue((end - start) / 1_000_000 < 500)
    }

    private fun GenUiNode.findAll(type: String): List<GenUiNode> {
        val out = mutableListOf<GenUiNode>()
        fun visit(node: GenUiNode) {
            if (node.type == type) out += node
            node.children.forEach(::visit)
        }
        visit(this)
        return out
    }

    private companion object {
        const val STEP = 13
    }
}

package com.mozhi.reader.ai.genui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mozhi.reader.ai.genui.runtime.GenUiEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真 Android 的 ICU 正则与 QuickJS；桌面 JVM 单测无法发现字面量 `}` 的平台差异。 */
@RunWith(AndroidJUnit4::class)
class GenUiAndroidRuntimeTest {
    private lateinit var engine: GenUiEngine
    private lateinit var compiler: GenUiCompiler

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = context.assets.open("genui/runtime.js").bufferedReader().use { it.readText() }
        engine = GenUiEngine.create(runtime)
        compiler = GenUiCompiler(CachingSyntaxChecker(GenUiSyntaxChecker { engine.isSyntaxValid(it) }))
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.close()
    }

    @Test
    fun demoReplyCompilesRendersAndHandlesQuizAndSliders() = runBlocking {
        // 与 /genui-demo 插入的整条回复一致，覆盖编译器首次初始化与两座互动岛。
        val source = GenUiPromptExamples.QUIZ + "\n\n" + GenUiPromptExamples.STYLED
        val compiled = compiler.compile(GenUiDocument.parse(source))
        assertFalse(compiled.degraded)
        assertTrue(compiled.diagnostics.toString(), compiled.diagnostics.isEmpty())
        var result = engine.load("demo", compiled.program)
        assertEquals(2, result.islands.size)
        assertTrue(result.errors.toString(), result.errors.isEmpty())

        repeat(2) { index ->
            val group = result.islands[0].findAll("radio-group")[index]
            result = engine.invoke("demo", requireNotNull(group.handler("onChange")), args(index * 2))
        }
        val submit = result.islands[0].findAll("button").single()
        assertFalse(submit.flag("disabled"))
        result = engine.invoke("demo", requireNotNull(submit.handler("onClick")), JsonArray(emptyList()))
        assertEquals("2 / 2", result.islands[0].findAll("badge").single().plainText())

        val slider = result.islands[1].findAll("slider").first()
        result = engine.invoke("demo", requireNotNull(slider.handler("onChange")), args(100))
        assertEquals(100, result.islands[1].findAll("slider").first().int("value"))
        assertTrue(result.errors.toString(), result.errors.isEmpty())

        // 重开槽位恢复两张卡片的状态，初始值不覆盖已经交卷和拖动的值。
        engine.dispose("demo")
        val restored = engine.load("demo", compiled.program, result.state)
        assertEquals("2 / 2", restored.islands[0].findAll("badge").single().plainText())
        assertEquals(100, restored.islands[1].findAll("slider").first().int("value"))
    }

    @Test
    fun objectAndArrayDestructuringKeepDuplicateDetectionOnAndroid() = runBlocking {
        val source = """
            {@body const {value: label} = {value: "object"}}
            {@body const [count] = [7]}
            {@body const label = "duplicate"}
            {@body const count = 9}
            <card><text>{label}:{count}</text></card>
        """.trimIndent()
        val compiled = compiler.compile(GenUiDocument.parse(source))
        assertFalse(compiled.degraded)
        assertEquals(setOf("label", "count"), compiled.diagnostics.filter { it.code == "duplicate_declaration" }.map { it.detail }.toSet())
        val result = engine.load("destructure", compiled.program)
        assertEquals("object:7", result.islands.single().findAll("text").single().plainText())
        assertTrue(result.errors.toString(), result.errors.isEmpty())
    }

    private fun args(value: Int) = JsonArray(listOf(JsonPrimitive(value)))

    private fun GenUiNode.findAll(type: String): List<GenUiNode> =
        (if (this.type == type) listOf(this) else emptyList()) + children.flatMap { it.findAll(type) }
}

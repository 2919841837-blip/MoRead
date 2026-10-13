package com.mozhi.reader.ai.genui.runtime

import com.mozhi.reader.ai.genui.GenUiNode
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GenUiEngineTest {
    private lateinit var engine: GenUiEngine

    @Before
    fun setUp() = runBlocking {
        engine = GenUiEngine.create(runtimeScript())
    }

    @After
    fun tearDown() {
        engine.close()
    }

    @Test
    fun rendersTreeWithStableKeysAndHandlerIds() = runBlocking {
        val result = engine.load("m1", PRICING)
        val island = result.islands.single()
        assertEquals("#island", island.type)
        val box = island.children.single()
        assertEquals("i0/0", box.key)
        val slider = box.children[0]
        assertEquals("slider", slider.type)
        assertEquals(8, slider.int("value"))
        assertEquals("i0/0/0.0.onChange", slider.handler("onChange"))
        assertEquals("$232/mo", box.children[1].plainText())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun eventUpdatesStateAndRerendersWithoutTouchingInitialValue() = runBlocking {
        val first = engine.load("m1", PRICING)
        val slider = first.islands.single().children.single().children[0]
        val next = engine.invoke("m1", slider.handler("onChange")!!, JsonArray(listOf(JsonPrimitive(9))))
        assertEquals("$261/mo", next.islands.single().children.single().children[1].plainText())
        assertEquals(9, next.state["seats"]!!.jsonPrimitive.int)
        assertEquals(8, next.initial["seats"]!!.jsonPrimitive.int)
    }

    @Test
    fun recompilingTheSameSlotKeepsUserState() = runBlocking {
        val first = engine.load("m1", PRICING)
        val handler = first.islands.single().children.single().children[0].handler("onChange")!!
        engine.invoke("m1", handler, JsonArray(listOf(JsonPrimitive(20))))
        // 流式期间同一条消息的程序被替换（多了一个元素），状态仍按键保留。
        val grown = PRICING.replace("\"/mo\")", "\"/mo\"), h(\"caption\", {__k: \"0.3\"}, \"含税\")")
        val second = engine.load("m1", grown)
        val box = second.islands.single().children.single()
        assertEquals(20, box.children[0].int("value"))
        assertEquals("$580/mo", box.children[1].plainText())
    }

    @Test
    fun restoredStateWinsOverProgramInitialValue() = runBlocking {
        val restored = JsonObject(mapOf("seats" to JsonPrimitive(3)))
        val result = engine.load("m2", PRICING, restoredState = restored)
        assertEquals("$87/mo", result.islands.single().children.single().children[1].plainText())
        assertEquals(8, result.initial["seats"]!!.jsonPrimitive.int)
    }

    @Test
    fun actionsAreAcceptedOnlyInsideUserEvents() = runBlocking {
        val rendered = engine.load("m1", PRICING)
        assertTrue(rendered.actions.isEmpty())
        val button = rendered.islands.single().children.single().children[2]
        val clicked = engine.invoke("m1", button.handler("onClick")!!, JsonArray(emptyList()))
        val action = clicked.actions.single()
        assertEquals("send", action.type)
        assertEquals("seats 8", action.payload["text"]!!.jsonPrimitive.content)

        val sneaky = engine.load("m3", SEND_DURING_RENDER)
        assertTrue(sneaky.actions.isEmpty())
        assertTrue(sneaky.errors.any { it.code == "action_outside_event" })
    }

    @Test
    fun throwingExpressionOnlyDropsItsOwnElement() = runBlocking {
        val result = engine.load("m4", BROKEN_EXPRESSION)
        val box = result.islands.single().children.single()
        assertEquals(listOf("text"), box.children.map(GenUiNode::type))
        assertEquals("still here", box.children.single().plainText())
        assertTrue(result.errors.any { it.code == "expression_error" })
    }

    @Test
    fun runawayProgramIsInterruptedAndQuarantinedWithoutBreakingOtherSlots() = runBlocking {
        try {
            engine.load("loop", INFINITE_LOOP)
            fail("expected interruption")
        } catch (_: GenUiEngineException.Interrupted) {
        }
        assertTrue(engine.isQuarantined("loop"))
        val other = engine.load("m1", PRICING)
        assertEquals("$232/mo", other.islands.single().children.single().children[1].plainText())
    }

    @Test
    fun dynamicCodeEvaluationIsLockedDown() = runBlocking {
        val result = engine.load("m5", PROBE_LOCKDOWN)
        assertEquals("undefined|undefined|blocked", result.islands.single().children.single().plainText())
    }

    @Test
    fun syntaxCheckCompilesWithoutExecuting() = runBlocking {
        assertTrue(engine.isSyntaxValid("const a = [1,2].map(x => x * 2)"))
        assertFalse(engine.isSyntaxValid("const T = {a: 1"))
        assertFalse(engine.isSyntaxValid("a := b"))
    }

    private fun runtimeScript(): String = File("src/main/assets/genui/runtime.js").readText()

    private companion object {
        const val HEADER = "__genui.define(__GENUI_SLOT__, function (h, useState, useMemo, useRef, useCallback, __each, __safe, app, DIL, GenUI) {\n\"use strict\";\n"

        val PRICING = HEADER + """
            const [seats, setSeats] = useState(8, {key: "seats"});
            const price = __safe(() => seats * 29, undefined);
            return [h("box", {__k: "0", gap: 2},
              h("slider", {__k: "0.0", value: seats, onChange: setSeats, min: 1, max: 50}),
              h("title", {__k: "0.1"}, "$", __safe(() => price, null), "/mo"),
              h("button", {__k: "0.2", onClick: () => app.send("seats " + seats)}, "Ask"))];
            });
        """.trimIndent()

        val SEND_DURING_RENDER = HEADER + """
            app.send("auto");
            return [h("text", {__k: "0"}, "x")];
            });
        """.trimIndent()

        val BROKEN_EXPRESSION = HEADER + """
            return [h("box", {__k: "0"},
              __safe(() => h("text", {__k: "0.0"}, missing.value), null),
              h("text", {__k: "0.1"}, "still here"))];
            });
        """.trimIndent()

        val INFINITE_LOOP = HEADER + """
            while (true) {}
            });
        """.trimIndent()

        val PROBE_LOCKDOWN = HEADER + """
            let blocked = "open";
            try { Object.prototype.polluted = 1; } catch (e) { blocked = "blocked"; }
            return [h("text", {__k: "0"}, String(typeof eval === "function" ? "eval" : undefined) + "|" + String(typeof Function === "function" ? "fn" : undefined) + "|" + blocked)];
            });
        """.trimIndent()
    }
}

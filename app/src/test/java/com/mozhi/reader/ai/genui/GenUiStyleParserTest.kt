package com.mozhi.reader.ai.genui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenUiStyleParserTest {

    @Test
    fun cssStringAndObjectFormsAgree() {
        val css = GenUiStyleParser.parseCss("color:#c65f76; border-radius:18px; padding: 12px 20px; font-family: serif")
        val obj = GenUiStyleParser.parse(
            JsonObject(
                mapOf(
                    "color" to JsonPrimitive("#c65f76"),
                    "borderRadius" to JsonPrimitive(18),
                    "padding" to JsonPrimitive("12px 20px"),
                    "fontFamily" to JsonPrimitive("serif")
                )
            )
        )
        assertEquals(css, obj)
        assertEquals(StyleColor.Literal(0xFFC65F76.toInt()), css.color)
        assertEquals(StyleCorners.all(18f), css.radius)
        assertEquals(StyleEdges(12f, 20f, 12f, 20f), css.padding)
    }

    @Test
    fun themeVariablesStayAsReferences() {
        val style = GenUiStyleParser.parseCss("color: var(--muted); background: var(--card)")
        assertEquals(StyleColor.Theme("muted"), style.color)
        assertEquals(listOf(StylePaint.Solid(StyleColor.Theme("card"))), style.backgrounds)
    }

    @Test
    fun layeredGradientsKeepTheirCommasInsideFunctions() {
        val style = GenUiStyleParser.parseCss(
            "background: radial-gradient(circle at 18% 0%, #c65f7655, transparent 62%), var(--card)"
        )
        assertEquals(2, style.backgrounds.size)
        val radial = style.backgrounds[0] as StylePaint.Radial
        assertEquals(0.18f, radial.centerX, 0.001f)
        assertEquals(0f, radial.centerY, 0.001f)
        assertEquals(2, radial.stops.size)
        assertEquals(0.62f, radial.stops[1].position!!, 0.001f)
        assertTrue(style.hasOwnSurface)
    }

    @Test
    fun gradientTextMovesTheGradientOffTheBackground() {
        val style = GenUiStyleParser.parseCss(
            "background: linear-gradient(90deg, #c65f76, #567bce); background-clip: text; color: transparent"
        )
        assertTrue(style.textPaint is StylePaint.Linear)
        assertTrue(style.backgrounds.isEmpty())
        assertEquals(90f, (style.textPaint as StylePaint.Linear).angle, 0.001f)
    }

    @Test
    fun shadowsTransformsAndAnimationsParse() {
        val style = GenUiStyleParser.parseCss(
            "box-shadow: 0 8px 24px #2b1d3a55, inset 0 1px 0 #fff; transform: rotate(-3deg) scale(1.05); " +
                "animation: pop 360ms; transition: background 420ms ease"
        )
        assertEquals(2, style.shadows.size)
        assertEquals(24f, style.shadows[0].blur)
        assertTrue(style.shadows[1].inset)
        assertEquals(-3f, style.transform!!.rotate)
        assertEquals(1.05f, style.transform!!.scaleX)
        assertEquals(AnimationPreset.POP, style.animation!!.preset)
        assertEquals(360, style.animation!!.durationMs)
        assertEquals(setOf("background"), style.transition!!.properties)
    }

    @Test
    fun remoteUrlsAreNeverAccepted() {
        assertTrue(GenUiStyleParser.parseCss("background-image: url(https://evil.example/x.png)").backgrounds.isEmpty())
        val local = GenUiStyleParser.parseCss("background-image: url(\"asset:abc_1\")")
        assertEquals(listOf(StylePaint.Image("asset:abc_1")), local.backgrounds)
    }

    @Test
    fun badDeclarationsOnlyLoseThemselves() {
        val style = GenUiStyleParser.parseCss("color: notacolor; border-radius: 12px; width: ???; opacity: .5")
        assertNull(style.color)
        assertEquals(StyleCorners.all(12f), style.radius)
        assertNull(style.width)
        assertEquals(0.5f, style.opacity)
    }

    @Test
    fun hslAndNamedColorsResolve() {
        assertNotNull(GenUiStyleParser.color("hsl(340, 45%, 57%)"))
        assertEquals(StyleColor.Literal(0xFFFFFFFF.toInt()), GenUiStyleParser.color("white"))
        assertEquals(StyleColor.Literal(0x7FFF0000), GenUiStyleParser.color("rgba(255, 0, 0, 0.5)"))
        assertEquals("serif", GenUiStyleParser.parseCss("font-family: '宋体', serif").fontFamily)
    }
}

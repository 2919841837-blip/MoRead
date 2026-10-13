package com.mozhi.reader.ui.genui

import androidx.compose.ui.graphics.Color
import com.mozhi.reader.ai.genui.GradientStop
import com.mozhi.reader.ai.genui.StyleColor
import com.mozhi.reader.ai.genui.StylePaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenUiContrastTest {
    private fun colors(dark: Boolean, nightAdapt: Boolean = true): GenUiColors {
        val paper = if (dark) Color(0xFF1C1B1A) else Color(0xFFF7F3EA)
        val ink = if (dark) Color(0xFFE6E1D6) else Color(0xFF2A2724)
        val tones = GenUiColors.toneSet(dark)
        return GenUiColors(
            ink = ink, paper = paper, muted = ink.copy(alpha = 0.6f), card = paper, line = ink.copy(alpha = 0.12f),
            accent = Color(0xFF567BCE), onAccent = Color.White, seal = Color(0xFFB0443A),
            success = tones[0], warning = tones[1], danger = tones[2], info = tones[3],
            dark = dark, nightAdapt = nightAdapt
        )
    }

    @Test
    fun readableTextIsLeftAlone() {
        val ink = Color(0xFF2A2724)
        assertEquals(ink, GenUiContrast.ensure(ink, Color(0xFFF7F3EA)))
    }

    @Test
    fun lowContrastTextOnlyChangesLightness() {
        val paleRose = Color(0xFFF2C6CF)
        val fixed = GenUiContrast.ensure(paleRose, Color(0xFFF7F3EA))
        assertTrue(GenUiContrast.ratio(fixed, Color(0xFFF7F3EA)) >= GenUiContrast.BODY - 0.01f)
        val before = GenUiContrast.toHsl(paleRose)
        val after = GenUiContrast.toHsl(fixed)
        assertEquals(before[0], after[0], 2f)
        assertTrue(after[2] < before[2])
    }

    @Test
    fun darkBackgroundLightensText() {
        val navy = Color(0xFF1A2340)
        val fixed = GenUiContrast.ensure(navy, Color(0xFF1C1B1A), GenUiContrast.LARGE)
        assertTrue(GenUiContrast.ratio(fixed, Color(0xFF1C1B1A)) >= GenUiContrast.LARGE - 0.01f)
    }

    @Test
    fun nightAdaptFlipsGlaringBackgroundsButKeepsHue() {
        val cream = Color(0xFFFFF4D6)
        val adapted = GenUiContrast.adaptBackground(cream, colors(dark = true))
        assertTrue(adapted.luminance() < 0.2f)
        assertEquals(GenUiContrast.toHsl(cream)[0], GenUiContrast.toHsl(adapted)[0], 3f)
        assertEquals(cream, GenUiContrast.adaptBackground(cream, colors(dark = false)))
        assertEquals(cream, GenUiContrast.adaptBackground(cream, colors(dark = true, nightAdapt = false)))
    }

    @Test
    fun gradientSurfaceUsesTheStopAverage() {
        val surface = GenUiContrast.effectiveSurface(
            listOf(StylePaint.Linear(90f, listOf(GradientStop(StyleColor.Literal(0xFF000000.toInt()), 0f), GradientStop(StyleColor.Literal(0xFF000000.toInt()), 1f)))),
            Color.White,
            colors(dark = false)
        )
        assertTrue(surface.luminance() < 0.05f)
    }

    private fun Color.luminance(): Float = GenUiContrast.ratio(this, Color.Black).let { (it * 0.05f) - 0.05f }
}

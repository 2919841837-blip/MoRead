package com.mozhi.reader.ai.genui

import com.mozhi.reader.core.datastore.GenUiLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenUiPromptTest {

    @Test
    fun offWritesNothing() {
        assertNull(GenUiPrompt.section(GenUiCapabilities(level = GenUiLevel.OFF)))
    }

    @Test
    fun disabledCapabilitiesAreNeverMentioned() {
        val text = GenUiPrompt.section(GenUiCapabilities(level = GenUiLevel.MODERATE, freeStyle = false))!!
        // 能力关着就一个字都不写
        assertFalse(text.contains("`Illustration`"))
        assertFalse(text.contains("`Voice`"))
        assertFalse(text.contains("app.speak"))
        assertFalse(text.contains("AppBlock"))
        assertFalse(text.contains("`Quote`"))
        assertFalse(text.contains("app.jump"))
        assertFalse(text.contains("## 样式"))
        assertTrue(text.contains("`slider`"))
        assertTrue(text.contains(GenUiPromptExamples.QUIZ))
    }

    @Test
    fun enabledCapabilitiesAreListed() {
        val text = GenUiPrompt.section(
            GenUiCapabilities(level = GenUiLevel.ACTIVE, freeStyle = true, book = true, characters = true, voice = true, appBlock = true)
        )!!
        assertTrue(text.contains("`Quote`"))
        assertTrue(text.contains("app.jump"))
        assertTrue(text.contains("app.speak"))
        assertTrue(text.contains("## 样式"))
        assertTrue(text.contains(GenUiPromptExamples.STYLED))
        assertTrue(text.contains("<AppBlock"))
        assertTrue(text.contains("主动用界面组织"))
    }

    @Test
    fun everyListedComponentIsKnownToTheCompiler() {
        GenUiCatalog.components.forEach { component ->
            assertTrue(component.name, GenUiCatalog.resolve(component.name) === component)
        }
        GenUiCatalog.ALIASES.forEach { (alias, target) ->
            assertTrue(alias, GenUiCatalog.resolve(alias)?.name == target)
        }
    }

    @Test
    fun examplesParseIntoIslands() {
        assertTrue(GenUiDocument.parse(GenUiPromptExamples.QUIZ).islands.isNotEmpty())
        assertTrue(GenUiDocument.parse(GenUiPromptExamples.STYLED).islands.isNotEmpty())
    }
}

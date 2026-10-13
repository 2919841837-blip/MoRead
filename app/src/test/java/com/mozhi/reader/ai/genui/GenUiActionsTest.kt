package com.mozhi.reader.ai.genui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenUiActionsTest {
    private fun action(type: String, vararg pairs: Pair<String, Any>) = GenUiAction(
        type,
        JsonObject(pairs.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
    )

    @Test
    fun sendIsTrimmedAndCapped() {
        val command = GenUiActions.validate(action("send", "text" to "  再来三道  " + "啊".repeat(600)))
        assertTrue(command is GenUiCommand.Send)
        assertEquals(GenUiActions.MAX_SEND_CHARS, (command as GenUiCommand.Send).text.length)
        assertNull(GenUiActions.validate(action("send", "text" to "   ")))
    }

    @Test
    fun openUrlOnlyAllowsPlainHttpLinks() {
        val ok = GenUiActions.validate(action("openUrl", "url" to "https://example.org/a?b=1")) as GenUiCommand.OpenUrl
        assertEquals("example.org", ok.host)
        assertNull(GenUiActions.validate(action("openUrl", "url" to "javascript:alert(1)")))
        assertNull(GenUiActions.validate(action("openUrl", "url" to "file:///sdcard/x")))
        assertNull(GenUiActions.validate(action("openUrl", "url" to "intent://scan#Intent;end")))
        assertNull(GenUiActions.validate(action("openUrl", "url" to "https://user:pass@evil.example/")))
    }

    @Test
    fun jumpKeepsChapterOnlyWhenPositive() {
        val jump = GenUiActions.validate(action("jump", "chapter" to 3, "quote" to "风起了")) as GenUiCommand.Jump
        assertEquals(3, jump.chapterNumber)
        val noChapter = GenUiActions.validate(action("jump", "chapter" to 0, "quote" to "风起了")) as GenUiCommand.Jump
        assertNull(noChapter.chapterNumber)
        assertNull(GenUiActions.validate(action("jump", "chapter" to 2)))
    }

    @Test
    fun unknownActionsAreRejected() {
        assertNull(GenUiActions.validate(action("saveNote", "text" to "x")))
        assertNull(GenUiActions.validate(action("deleteBook", "id" to 1)))
    }
}

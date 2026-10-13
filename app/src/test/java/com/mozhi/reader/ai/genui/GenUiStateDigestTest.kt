package com.mozhi.reader.ai.genui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenUiStateDigestTest {
    private fun obj(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) ->
        k to if (v is Number) JsonPrimitive(v) else if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString())
    })

    @Test
    fun onlyChangedStateIsReported() {
        val entry = GenUiStateDigest.Entry(
            state = obj("chapter" to 18, "tab" to "a", "submitted" to true),
            initial = obj("chapter" to 12, "tab" to "a", "submitted" to false)
        )
        val text = GenUiStateDigest.digest(listOf(entry))!!
        assertTrue(text.contains("chapter=18"))
        assertTrue(text.contains("submitted=true"))
        assertTrue(!text.contains("tab="))
        assertTrue(text.startsWith("〔界面状态〕上一条回复"))
    }

    @Test
    fun untouchedInterfacesProduceNoDigest() {
        val entry = GenUiStateDigest.Entry(obj("a" to 1), obj("a" to 1))
        assertNull(GenUiStateDigest.digest(listOf(entry)))
    }

    @Test
    fun onlyTheLastTwoRepliesAreConsideredAndLengthIsBounded() {
        val old = GenUiStateDigest.Entry(obj("old" to 2), obj("old" to 1))
        val mid = GenUiStateDigest.Entry(obj("mid" to "x".repeat(400)), obj("mid" to ""))
        val last = GenUiStateDigest.Entry(obj("last" to 5), obj("last" to 0))
        val text = GenUiStateDigest.digest(listOf(old, mid, last))!!
        assertTrue(!text.contains("old="))
        assertTrue(text.contains("更早一条回复"))
        assertTrue(text.length <= GenUiStateDigest.MAX_CHARS)
    }

    @Test
    fun actionableDiagnosticsAreFedBack() {
        val entry = GenUiStateDigest.Entry(
            obj(), obj(),
            diagnostics = listOf(
                GenUiDiagnostic("unknown_prop", 1, "icon.fill"),
                GenUiDiagnostic("unclosed_element", 2, "card")
            )
        )
        val text = GenUiStateDigest.digest(listOf(entry))!!
        assertTrue(text.contains("〔界面诊断〕"))
        assertTrue(text.contains("icon.fill"))
        assertTrue(!text.contains("card"))
    }

    @Test
    fun contextRoundTrips() {
        val raw = GenUiStateDigest.encodeContext("〔界面状态〕x=1")
        assertEquals("〔界面状态〕x=1", GenUiStateDigest.decodeContext(raw))
        assertNull(GenUiStateDigest.decodeContext("not json"))
        assertNull(GenUiStateDigest.decodeContext(null))
    }
}

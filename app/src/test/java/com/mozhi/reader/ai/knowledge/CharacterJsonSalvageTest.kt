package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.knowledge.CharacterJsonSalvage.passageNumber
import com.mozhi.reader.ai.knowledge.CharacterJsonSalvage.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterJsonSalvageTest {
    private val complete = """{"name":"林舟","experiences":[{"p":1,"event":"守灯塔"},{"p":2,"event":"收到来信"}],"quotes":[]}"""

    @Test fun `complete json inside prose and fences parses as is`() {
        val value = CharacterJsonSalvage.parse("整理如下：\n```json\n$complete\n```\n以上。") as JsonObject
        assertEquals(2, value["experiences"]!!.jsonArray.size)
    }

    @Test fun `trailing commas and comments are tolerated`() {
        val value = CharacterJsonSalvage.parse("""{"name":"林舟", // 主角
            "aliases":["小舟",],}""") as JsonObject
        assertEquals("小舟", value["aliases"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun `truncated output keeps every complete element and drops the unfinished one`() {
        val cut = complete.substring(0, complete.indexOf("收到来") )
        val value = CharacterJsonSalvage.parse(cut) as JsonObject
        assertEquals("林舟", value["name"]!!.jsonPrimitive.content)
        val events = value["experiences"]!!.jsonArray
        assertEquals("守灯塔", events.first().jsonObject["event"]!!.jsonPrimitive.content)
        // 没写完的那一条最多留下已完整的键，正文半句不会被当成内容。
        assertTrue(events.drop(1).none { "event" in it.jsonObject })
    }

    @Test fun `truncation inside a string value with escaped quotes still recovers`() {
        val raw = """{"name":"林舟","bio":"他说\"等我\"然后","experiences":[{"p":1,"event":"守"}],"arc":"从怯懦到"""
        val value = CharacterJsonSalvage.parse(raw) as JsonObject
        assertEquals("""他说"等我"然后""", value["bio"]!!.jsonPrimitive.content)
        assertEquals(1, value["experiences"]!!.jsonArray.size)
        assertNull(value["arc"])
    }

    @Test fun `every cut point of a complete document either recovers a prefix or returns null without throwing`() {
        for (end in 1 until complete.length) {
            val value = CharacterJsonSalvage.parse(complete.substring(0, end))
            if (value != null) assertTrue(value is JsonObject)
        }
    }

    @Test fun `prose without json yields null and bare arrays are wrapped`() {
        assertNull(CharacterJsonSalvage.parse("人物包括林舟和小满。"))
        val wrapped = CharacterJsonSalvage.parseObject("""[{"name":"林舟"}]""", "characters")!!
        assertTrue(wrapped["characters"] is JsonArray)
    }

    @Test fun `loose value shapes are read leniently`() {
        val value = CharacterJsonSalvage.parse("""{"a":3,"b":"3","c":"[段落4]","d":[5,6],"t":"嘴硬心软、谨慎，多疑"}""") as JsonObject
        assertEquals(3, value.passageNumber("a"))
        assertEquals(3, value.passageNumber("b"))
        assertEquals(4, value.passageNumber("c"))
        assertEquals(5, value.passageNumber("d"))
        assertNull(value.passageNumber("missing"))
        assertEquals(listOf("嘴硬心软", "谨慎", "多疑"), value.strings("t"))
    }
}

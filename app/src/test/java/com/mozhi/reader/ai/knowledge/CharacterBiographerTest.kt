package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.*
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CharacterBiographerTest {
    private val client = ScriptedClient()
    private val model = ResolvedChatClient(client, ChatOptions.Default,
        AiProviderEntity(id = 1, name = "test", baseUrl = "https://example.test", apiKeyAlias = "alias",
            type = AiProviderType.CHAT, createdAt = 1), "helper")
    private val biographer = CharacterBiographer(KnowledgeJsonRequest(KnowledgeRequestLimiter()))
    private val cache = object : DossierCache {
        val rows = mutableMapOf<String, String>()
        override suspend fun load(key: String) = rows[key]
        override suspend fun store(key: String, identity: String, json: String) { rows[key] = json }
    }

    private fun fact(chapter: Int, text: String) = CharacterEvidence(chapter, KnowledgeFact(text, "原文$chapter", 0, 3))
    private val lin = BookCharacter("林舟", listOf(fact(2, "修好了灯"), fact(0, "守着灯塔"), fact(1, "收到来信")),
        attributes = listOf(BookCharacterAttribute(CharacterAttributeKind.IDENTITY, "守灯人", fact(0, "身份"))))
    private val passer = BookCharacter("路人", listOf(fact(0, "路过")))
    private val guide = BookCharacterGuide(listOf(lin, passer), 3, 300)

    @Test fun `only people with enough verified facts get a biography and only the facts are sent`() = runTest {
        client.reply = """{"tagline":"守灯人","bio":"他守着灯塔。","personality":[{"trait":"执着","detail":"雨夜也点灯"}],"role":"protagonist"}"""
        val progress = mutableListOf<Pair<Int, Int>>()
        val (enriched, keys) = biographer.enrich(guide, "灯塔", true, model, "key", cache, {}) { done, total -> progress += done to total }
        val person = enriched.characters.first()
        assertEquals("守灯人", person.dossier!!.tagline)
        assertEquals("守灯人", person.dossier!!.identity)
        assertEquals(CharacterRole.PROTAGONIST, person.role)
        assertNull(enriched.characters.last().dossier)
        assertEquals(1, keys.size)
        assertEquals(1 to 1, progress.last())
        val sent = client.users.single()
        // 事实按章节先后排列；不送原文引文。
        assertTrue(sent.indexOf("守着灯塔") < sent.indexOf("收到来信") && sent.indexOf("收到来信") < sent.indexOf("修好了灯"))
        assertFalse(sent.contains("原文0"))
    }

    @Test fun `a failed biography leaves the guide as it was and the result is cached`() = runTest {
        client.reply = "写不出来"
        val (unchanged, _) = biographer.enrich(guide, "灯塔", false, model, "key", cache, {}) { _, _ -> }
        assertNull(unchanged.characters.first().dossier)
        client.reply = """{"tagline":"守灯人","bio":"他守着灯塔。"}"""
        biographer.enrich(guide, "灯塔", false, model, "key", cache, {}) { _, _ -> }
        val calls = client.calls
        biographer.enrich(guide, "灯塔", false, model, "key", cache, {}) { _, _ -> }
        assertEquals(calls, client.calls)
    }

    private class ScriptedClient : ChatApiClient {
        var reply = "{}"
        var calls = 0
        val users = mutableListOf<String>()
        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>, options: ChatOptions): Flow<ChatDelta> = flow {
            calls++
            users += messages.first { it.role == ChatRole.USER }.content
            emit(ChatDelta.Text(reply))
        }
        override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String = error("unused")
        override suspend fun embed(texts: List<String>): List<FloatArray> = error("unused")
    }
}

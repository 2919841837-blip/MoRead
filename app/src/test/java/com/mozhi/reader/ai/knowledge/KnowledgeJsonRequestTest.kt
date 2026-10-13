package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.*
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KnowledgeJsonRequestTest {
    private val client = ScriptedClient()
    private val model = ResolvedChatClient(client, ChatOptions.Default,
        AiProviderEntity(id = 1, name = "test", baseUrl = "https://example.test", apiKeyAlias = "alias",
            type = AiProviderType.CHAT, createdAt = 1), "helper")
    private val request = KnowledgeJsonRequest(KnowledgeRequestLimiter())

    private suspend fun run(): String = request.request(model, "system", "user", {}) { raw ->
        require(raw.contains("{")) { "缺少 JSON" }
        raw
    }

    @Test fun `a slow model that keeps writing is never cut off`() = runTest {
        client.script += { emitSlowly(List(7) { "{\"a\":$it}" }, gapMs = 60_000) }
        assertTrue(run().contains("{\"a\":6}"))
        assertEquals(1, client.calls)
    }

    @Test fun `reasoning deltas count as activity before the first text`() = runTest {
        client.script += {
            repeat(3) { delay(80_000); emit(ChatDelta.Reasoning("思考中")) }
            emit(ChatDelta.Text("{}"))
        }
        assertEquals("{}", run())
    }

    @Test fun `a silent connection is retried and then reported with an actionable message`() = runTest {
        repeat(3) { client.script += { awaitCancellation() } }
        val error = runCatching { run() }.exceptionOrNull()!!
        assertEquals(3, client.calls)
        assertTrue(error.message!!.contains("没有开始回复"))
        assertTrue(error.message!!.contains("已整理好的人物不会丢"))
    }

    @Test fun `a stall in the middle is retried from scratch`() = runTest {
        client.script += { emit(ChatDelta.Text("{\"a\":")); awaitCancellation() }
        client.script += { emit(ChatDelta.Text("{\"a\":1}")) }
        assertEquals("{\"a\":1}", run())
        assertEquals(2, client.calls)
    }

    @Test fun `server errors are retried but client errors fail at once`() = runTest {
        client.script += { throw AiClientException.Http(503, "busy") }
        client.script += { emit(ChatDelta.Text("{}")) }
        assertEquals("{}", run())
        assertEquals(2, client.calls)
        client.script += { throw AiClientException.Http(400, "bad request") }
        assertTrue(runCatching { run() }.exceptionOrNull() is AiClientException.Http)
        assertEquals(3, client.calls)
        client.script += { throw AiClientException.InvalidKey() }
        assertTrue(runCatching { run() }.exceptionOrNull() is AiClientException.InvalidKey)
        assertEquals(4, client.calls)
    }

    @Test fun `unusable output gets exactly one correction that explains why`() = runTest {
        client.script += { emit(ChatDelta.Text("人物有林舟。")) }
        client.script += { emit(ChatDelta.Text("{}")) }
        assertEquals("{}", run())
        val correction = client.requests.last()
        assertEquals(ChatRole.ASSISTANT, correction[correction.size - 2].role)
        assertTrue(correction.last().content.contains("缺少 JSON"))
        repeat(2) { client.script += { emit(ChatDelta.Text("还是没有")) } }
        assertEquals("缺少 JSON", runCatching { run() }.exceptionOrNull()!!.message)
        assertEquals(4, client.calls)
    }

    @Test fun `cancellation is never retried`() = runTest {
        client.script += { throw CancellationException("stopped") }
        assertTrue(runCatching { run() }.exceptionOrNull() is CancellationException)
        assertEquals(1, client.calls)
    }

    @Test fun `no tool schema is sent so any provider can answer`() = runTest {
        client.script += { emit(ChatDelta.Text("{}")) }
        run()
        assertTrue(client.tools.single().isEmpty())
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatDelta>.emitSlowly(parts: List<String>, gapMs: Long) {
        parts.forEach { delay(gapMs); emit(ChatDelta.Text(it)) }
    }

    private class ScriptedClient : ChatApiClient {
        val script = ArrayDeque<suspend kotlinx.coroutines.flow.FlowCollector<ChatDelta>.() -> Unit>()
        val requests = mutableListOf<List<ChatMessage>>()
        val tools = mutableListOf<List<ToolSpec>>()
        var calls = 0
        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>, options: ChatOptions): Flow<ChatDelta> = flow {
            calls++
            requests += messages
            this@ScriptedClient.tools += tools
            script.removeFirst().invoke(this)
        }
        override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String = error("unused")
        override suspend fun embed(texts: List<String>): List<FloatArray> = error("unused")
    }
}

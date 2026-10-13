package com.mozhi.reader.ai.genui.runtime

import android.util.Log
import androidx.compose.runtime.Immutable
import com.mozhi.reader.ai.genui.CachingSyntaxChecker
import com.mozhi.reader.ai.genui.GenUiAction
import com.mozhi.reader.ai.genui.GenUiCompiler
import com.mozhi.reader.ai.genui.GenUiDiagnostic
import com.mozhi.reader.ai.genui.GenUiDocument
import com.mozhi.reader.ai.genui.GenUiNode
import com.mozhi.reader.ai.genui.GenUiRenderResult
import com.mozhi.reader.ai.genui.GenUiStateDigest
import com.mozhi.reader.ai.genui.GenUiSyntaxChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 一条消息的互动界面在界面层看到的样子。 */
@Immutable
data class GenUiMessageView(
    val status: Status = Status.PENDING,
    val document: GenUiDocument? = null,
    val islands: List<GenUiNode> = emptyList(),
    val state: JsonObject = JsonObject(emptyMap()),
    val initial: JsonObject = JsonObject(emptyMap()),
    val diagnostics: List<GenUiDiagnostic> = emptyList()
) {
    enum class Status {
        /** 还没有任何一次求值结果：条目保持预估高度不可见，就绪后一次性显示。 */
        PENDING,
        READY,

        /** 编译整体失败已退成纯文字，或引擎不可用 / 该槽被看门狗隔离：显示纯文字投影。 */
        FALLBACK
    }
}

/**
 * 一个聊天页的互动界面会话（design/companion-genui-plan.md §9.3、§10）。
 *
 * - 引擎在第一次需要时创建；创建失败时所有岛退成纯文字投影，聊天照常。
 * - 每条消息一个槽：流式期间的源码按节拍合并，同一槽的编译串行进行、两次之间至少隔
 *   [STREAM_COMPILE_INTERVAL_MS]；写完的一次立即编译。
 * - 用户事件按槽排队，同一个处理器的连续事件（拖滑块）只保留最新一次。
 * - 状态变化防抖后交给 [Callbacks.persistState]；动作交给 [Callbacks.onAction] 由页面执行。
 * - 引擎里最多同时装 [MAX_LOADED_SLOTS] 个程序；被换出的槽保留最后一棵树照常显示，
 *   下次交互时按已保存的状态重新装入。
 */
class GenUiSession(
    private val scope: CoroutineScope,
    private val runtimeScript: suspend () -> String,
    private val callbacks: Callbacks,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    interface Callbacks {
        /** 程序在用户手势里请求的对外动作（尚未校验）。 */
        fun onAction(messageKey: String, action: GenUiAction)

        /** 状态变化（已防抖）：调用方按消息身份写进 messages.uiStateJson。 */
        suspend fun persistState(messageKey: String, state: JsonObject)
    }

    private class Slot(val key: String) {
        val view = MutableStateFlow(GenUiMessageView())
        var source: String? = null
        var complete = false
        var compiledSource: String? = null
        var program: String? = null
        var restored: JsonObject? = null
        var loaded = false
        var compileJob: Job? = null
        var compileRequested = false
        var lastCompileAt = 0L
        val pendingEvents = LinkedHashMap<String, JsonArray>()
        var eventJob: Job? = null
        var persistJob: Job? = null
        var lastPersisted: JsonObject? = null
    }

    private val slots = HashMap<String, Slot>()
    private val loadOrder = ArrayDeque<String>()
    private val engineMutex = Mutex()
    private var engine: GenUiEngine? = null
    private var engineFailed = false
    private var compiler: GenUiCompiler? = null
    private var appData: JsonObject? = null
    private var closed = false

    /** 界面层订阅的视图；没有提交过源码的消息先给一个 PENDING 视图。 */
    fun view(messageKey: String): StateFlow<GenUiMessageView> = slot(messageKey).view.asStateFlow()

    /** 当前视图的快照（发消息时汇总界面状态用）。 */
    fun snapshot(messageKey: String): GenUiMessageView? = slots[messageKey]?.view?.value

    /**
     * 提交一条消息的源码。相同内容重复提交是空操作（同一条消息的几个岛各自调用也没关系）。
     * @param restoredState 持久化的状态，只在槽第一次装入引擎时生效
     */
    fun submit(messageKey: String, source: String, complete: Boolean, restoredState: JsonObject? = null) {
        if (closed) return
        val slot = slot(messageKey)
        if (slot.source == source && slot.complete == complete) return
        slot.source = source
        slot.complete = complete
        if (restoredState != null && slot.restored == null && !slot.loaded) slot.restored = restoredState
        slot.compileRequested = true
        if (slot.compileJob?.isActive != true) slot.compileJob = scope.launch { compileLoop(slot) }
    }

    /** 用户事件：同一处理器的未处理事件被新值覆盖（拖滑块时只算最后一下）。 */
    fun dispatch(messageKey: String, handlerId: String, args: JsonArray) {
        if (closed) return
        val slot = slots[messageKey] ?: return
        slot.pendingEvents.remove(handlerId)
        slot.pendingEvents[handlerId] = args
        if (slot.eventJob?.isActive != true) slot.eventJob = scope.launch { eventLoop(slot) }
    }

    /** 只读数据（书、角色、主题）；变化后已加载的程序在下次渲染时看到新值。 */
    fun setAppData(data: JsonObject) {
        if (appData == data) return
        appData = data
        val current = engine ?: return
        scope.launch { runCatching { current.setAppData(data) } }
    }

    /** 最近几条带互动的回复里用户改过的状态摘要（[keys] 按时间从旧到新）。 */
    fun digest(keys: List<String>): String? = GenUiStateDigest.digest(
        keys.mapNotNull { key ->
            val view = slots[key]?.view?.value ?: return@mapNotNull null
            if (view.document?.isInteractive != true) return@mapNotNull null
            GenUiStateDigest.Entry(view.state, view.initial, view.diagnostics)
        }
    )

    fun forget(messageKey: String) {
        val slot = slots.remove(messageKey) ?: return
        slot.compileJob?.cancel()
        slot.eventJob?.cancel()
        loadOrder.remove(messageKey)
        engine?.let { e -> scope.launch { runCatching { e.dispose(messageKey) } } }
    }

    fun close() {
        if (closed) return
        closed = true
        slots.values.forEach {
            it.compileJob?.cancel()
            it.eventJob?.cancel()
        }
        engine?.close()
        engine = null
    }

    private fun slot(key: String): Slot = slots.getOrPut(key) { Slot(key) }

    /* ---------------------------- 编译与装载 ---------------------------- */

    private suspend fun compileLoop(slot: Slot) {
        while (slot.compileRequested && !closed) {
            slot.compileRequested = false
            val source = slot.source ?: return
            val complete = slot.complete
            if (!complete) {
                val wait = STREAM_COMPILE_INTERVAL_MS - (System.currentTimeMillis() - slot.lastCompileAt)
                if (wait > 0) delay(wait)
                // 等待期间来了更新的源码：直接编最新的
                if (slot.compileRequested) continue
            }
            slot.lastCompileAt = System.currentTimeMillis()
            try {
                compileAndLoad(slot, source, complete)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "genui compile failed: ${e.javaClass.simpleName}")
                slot.view.value = slot.view.value.copy(status = GenUiMessageView.Status.FALLBACK)
            }
        }
    }

    private suspend fun compileAndLoad(slot: Slot, source: String, complete: Boolean) {
        val document = withContext(workDispatcher) { GenUiDocument.parse(source, complete) }
        if (!document.isInteractive) {
            slot.view.value = GenUiMessageView(GenUiMessageView.Status.READY, document)
            return
        }
        val engine = engine() ?: run {
            slot.view.value = GenUiMessageView(GenUiMessageView.Status.FALLBACK, document)
            return
        }
        if (engine.isQuarantined(slot.key)) {
            slot.view.value = slot.view.value.copy(status = GenUiMessageView.Status.FALLBACK, document = document)
            return
        }
        // 编译是纯 CPU（几毫秒），放后台；语法检查自己会切到引擎线程
        val compilation = withContext(workDispatcher) { compiler!!.compile(document) }
        if (compilation.program == slot.program && slot.loaded) {
            slot.view.value = slot.view.value.copy(document = document)
            return
        }
        slot.program = compilation.program
        slot.compiledSource = source
        val result = load(engine, slot) ?: return
        publish(slot, document, result, compilation.diagnostics, compilation.degraded)
    }

    private suspend fun load(engine: GenUiEngine, slot: Slot): GenUiRenderResult? {
        val program = slot.program ?: return null
        val restored = if (!slot.loaded) slot.restored ?: slot.view.value.state.takeIf { it.isNotEmpty() } else null
        return try {
            val result = engine.load(slot.key, program, restored)
            if (!slot.loaded) {
                slot.loaded = true
                slot.restored = null
                markLoaded(engine, slot.key)
            }
            result
        } catch (e: GenUiEngineException.Interrupted) {
            Log.w(TAG, "genui slot quarantined by watchdog")
            slot.view.value = slot.view.value.copy(status = GenUiMessageView.Status.FALLBACK)
            null
        } catch (e: GenUiEngineException) {
            Log.w(TAG, "genui load failed: ${e.javaClass.simpleName}")
            slot.view.value = slot.view.value.copy(status = GenUiMessageView.Status.FALLBACK)
            null
        }
    }

    private fun publish(
        slot: Slot,
        document: GenUiDocument,
        result: GenUiRenderResult,
        diagnostics: List<GenUiDiagnostic>,
        degraded: Boolean
    ) {
        slot.view.value = GenUiMessageView(
            status = if (degraded) GenUiMessageView.Status.FALLBACK else GenUiMessageView.Status.READY,
            document = document,
            islands = result.islands,
            state = result.state,
            initial = result.initial,
            diagnostics = diagnostics
        )
        if (result.errors.isNotEmpty()) Log.d(TAG, "genui runtime errors: ${result.errors.joinToString { it.code }}")
    }

    /** LRU：装入新槽时把最久没碰的槽从引擎里卸下（界面仍显示它最后一棵树）。 */
    private suspend fun markLoaded(engine: GenUiEngine, key: String) {
        loadOrder.remove(key)
        loadOrder.addLast(key)
        while (loadOrder.size > MAX_LOADED_SLOTS) {
            val evicted = loadOrder.removeFirst()
            slots[evicted]?.loaded = false
            runCatching { engine.dispose(evicted) }
        }
    }

    private fun touch(key: String) {
        if (loadOrder.remove(key)) loadOrder.addLast(key)
    }

    private suspend fun engine(): GenUiEngine? = engineMutex.withLock {
        engine?.let { return@withLock it }
        if (engineFailed || closed) return@withLock null
        try {
            val created = GenUiEngine.create(runtimeScript())
            appData?.let { created.setAppData(it) }
            engine = created
            compiler = GenUiCompiler(CachingSyntaxChecker(GenUiSyntaxChecker { created.isSyntaxValid(it) }))
            created
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 原生库加载失败（极少见的设备 / ABI 问题）：互动界面整体降级为纯文字，聊天照常
            Log.w(TAG, "genui engine unavailable: ${e.javaClass.simpleName}")
            engineFailed = true
            null
        }
    }

    /* ---------------------------- 事件 ---------------------------- */

    private suspend fun eventLoop(slot: Slot) {
        while (slot.pendingEvents.isNotEmpty() && !closed) {
            val (handlerId, args) = slot.pendingEvents.entries.first().let { it.key to it.value }
            slot.pendingEvents.remove(handlerId)
            val engine = engine() ?: return
            if (engine.isQuarantined(slot.key)) return
            if (!slot.loaded) {
                // 被 LRU 卸下过：按当前状态重新装入后再派发
                load(engine, slot) ?: return
            } else {
                touch(slot.key)
            }
            val result = try {
                engine.invoke(slot.key, handlerId, args)
            } catch (e: GenUiEngineException.Interrupted) {
                slot.view.value = slot.view.value.copy(status = GenUiMessageView.Status.FALLBACK)
                return
            } catch (e: GenUiEngineException) {
                Log.w(TAG, "genui event failed: ${e.javaClass.simpleName}")
                continue
            }
            val current = slot.view.value
            slot.view.value = current.copy(islands = result.islands, state = result.state, initial = result.initial)
            result.actions.forEach { callbacks.onAction(slot.key, it) }
            schedulePersist(slot, result.state)
        }
    }

    private fun schedulePersist(slot: Slot, state: JsonObject) {
        if (state == slot.lastPersisted) return
        slot.persistJob?.cancel()
        slot.persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            slot.lastPersisted = state
            runCatching { callbacks.persistState(slot.key, state) }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "genui persist failed: ${it.javaClass.simpleName}") }
        }
    }

    companion object {
        private const val TAG = "GenUi"
        const val STREAM_COMPILE_INTERVAL_MS = 120L
        const val PERSIST_DEBOUNCE_MS = 400L
        const val MAX_LOADED_SLOTS = 8
    }
}

package com.mozhi.reader.ai.genui.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.mozhi.reader.ai.genui.GenUiRenderResult
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 互动界面的 JS 引擎：一个 QuickJS 实例 + 运行时脚本（assets/genui/runtime.js）。
 *
 * 所有调用都在专用单线程上串行执行：QuickJS 不是线程安全的，栈溢出检查也依赖固定线程。
 * 引擎里没有任何 I/O 绑定，模型写的程序只能计算并交出界面树；对外动作随结果返回，
 * 由调用方按白名单执行。
 *
 * 看门狗：每次求值都有超时。被打断的槽会被标记隔离（[GenUiEngineException.Interrupted]），
 * 调用方应换成纯文字投影显示，不再给它派事件。
 */
class GenUiEngine private constructor(
    private val quickJs: QuickJs,
    private val dispatcher: CoroutineDispatcher,
    private val ownedDispatcher: ExecutorCoroutineDispatcher?,
    private val limits: GenUiEngineLimits
) : Closeable {

    private val closed = AtomicBoolean(false)
    private val quarantined = HashSet<String>()

    val isClosed: Boolean get() = closed.get()

    /**
     * 加载（或替换）一条消息的程序并渲染。同一个槽重复调用时保留已有状态——
     * 流式期间每次重编译都走这里，用户拖过的滑块不会跳回初值。
     *
     * @param restoredState 持久化下来的状态（首次加载时传），优先于程序里的初值
     */
    suspend fun load(slot: String, program: String, restoredState: JsonObject? = null): GenUiRenderResult =
        call(slot, limits.loadTimeoutMillis) {
            buildString {
                if (restoredState != null && restoredState.isNotEmpty()) {
                    append("__genui.restore(").append(slot.jsString()).append(',').append(restoredState.toString()).append(");\n")
                }
                append(program.replace(SLOT_PLACEHOLDER, slot.jsString()))
                append("\n;__genui.render(").append(slot.jsString()).append(')')
            }
        }

    /** 用户事件：调用界面树里编号为 [handlerId] 的处理器，返回重渲染后的结果与动作。 */
    suspend fun invoke(slot: String, handlerId: String, args: JsonArray): GenUiRenderResult =
        call(slot, limits.eventTimeoutMillis) {
            "__genui.invoke(${slot.jsString()},${handlerId.jsString()},$args)"
        }

    /** 只读数据（书、角色、主题）对所有槽生效，下次渲染起可见。 */
    suspend fun setAppData(data: JsonObject) {
        evaluateRaw("__genui.setAppData($data);'ok'", limits.eventTimeoutMillis)
    }

    suspend fun dispose(slot: String) {
        quarantined.remove(slot)
        if (!closed.get()) evaluateRaw("__genui.dispose(${slot.jsString()});'ok'", limits.eventTimeoutMillis)
    }

    fun isQuarantined(slot: String): Boolean = slot in quarantined

    /** 只编译不执行：用于在拼装程序前逐段检查模型写的 JS 片段能否通过语法分析。 */
    suspend fun isSyntaxValid(code: String): Boolean = withContext(dispatcher) {
        if (closed.get()) return@withContext false
        try {
            quickJs.compile(code, SYNTAX_FILE, false)
            true
        } catch (_: QuickJsException) {
            false
        }
    }

    private suspend fun call(slot: String, timeoutMillis: Long, script: () -> String): GenUiRenderResult {
        if (slot in quarantined) throw GenUiEngineException.Interrupted(slot)
        val raw = try {
            evaluateRaw(script(), timeoutMillis)
        } catch (e: QuickJsInterruptedException) {
            quarantined += slot
            throw GenUiEngineException.Interrupted(slot)
        }
        return GenUiRenderResult.fromJson(json.parseToJsonElement(raw))
    }

    private suspend fun evaluateRaw(script: String, timeoutMillis: Long): String = withContext(dispatcher) {
        if (closed.get()) throw GenUiEngineException.Closed()
        quickJs.evaluationTimeoutMillis = timeoutMillis
        try {
            quickJs.evaluate<String>(script)
        } catch (e: QuickJsInterruptedException) {
            throw e
        } catch (e: QuickJsException) {
            throw GenUiEngineException.Script(e.message ?: "script error")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { quickJs.close() }
        ownedDispatcher?.close()
    }

    companion object {
        /** 编译器产出的程序里用它占位槽编号，加载时替换。 */
        const val SLOT_PLACEHOLDER = "__GENUI_SLOT__"
        private const val SYNTAX_FILE = "fragment.js"
        private const val RUNTIME_FILE = "genui-runtime.js"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * @param dispatcher 不传则自建一条大栈的单线程；测试可传入自己的单线程调度器
         */
        suspend fun create(
            runtimeScript: String,
            limits: GenUiEngineLimits = GenUiEngineLimits(),
            dispatcher: CoroutineDispatcher? = null
        ): GenUiEngine {
            val owned = if (dispatcher == null) newEngineDispatcher() else null
            val target = dispatcher ?: owned!!
            return withContext(target) {
                val quickJs = QuickJs.create(target)
                try {
                    quickJs.memoryLimit = limits.memoryLimitBytes
                    quickJs.maxStackSize = limits.maxStackBytes
                    quickJs.evaluationTimeoutMillis = limits.loadTimeoutMillis
                    quickJs.evaluate<Any?>(runtimeScript, RUNTIME_FILE, false)
                } catch (e: Throwable) {
                    runCatching { quickJs.close() }
                    owned?.close()
                    throw e
                }
                GenUiEngine(quickJs, target, owned, limits)
            }
        }

        private fun newEngineDispatcher(): ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(null, runnable, "genui-engine", ENGINE_THREAD_STACK_BYTES).apply { isDaemon = true }
            }.asCoroutineDispatcher()

        /** 线程栈要比 QuickJS 的 maxStackSize 大，否则深递归会先撞线程栈。 */
        private const val ENGINE_THREAD_STACK_BYTES = 8L * 1024 * 1024
    }
}

data class GenUiEngineLimits(
    val loadTimeoutMillis: Long = 1_000,
    val eventTimeoutMillis: Long = 250,
    val memoryLimitBytes: Long = 48L * 1024 * 1024,
    val maxStackBytes: Long = 2L * 1024 * 1024
)

sealed class GenUiEngineException(message: String) : Exception(message) {
    /** 看门狗超时：该槽已隔离，换成纯文字投影。 */
    class Interrupted(val slot: String) : GenUiEngineException("genui slot interrupted")
    /** 运行时脚本本身报错（程序里的错误由运行时吞下记进 errors，不会走到这里）。 */
    class Script(message: String) : GenUiEngineException(message)
    class Closed : GenUiEngineException("genui engine closed")
}

private fun String.jsString(): String = JsonPrimitive(this).toString()

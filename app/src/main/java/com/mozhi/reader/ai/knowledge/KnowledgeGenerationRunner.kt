package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.core.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A null chapter identifies the whole-book character job, separate from every outline job. */
data class KnowledgeTaskKey(val bookId: Long, val chapterIndex: Int? = null)
data class KnowledgeTaskState(val active: Boolean = false, val progress: String = "", val error: String? = null,
    /** 人物任务的结构化进度；章节大纲任务为 null。结束后保留最后一次，界面用它显示「整理了谁」。 */
    val detail: KnowledgeProgress? = null,
    /** 通知栏显示用。 */
    val bookTitle: String = "",
    /** 用户主动停止（而不是完成或失败）；悬浮进度球据此直接收起。 */
    val stopped: Boolean = false)

/**
 * Tasks survive closing the sheet. A cancelled chapter never cancels its siblings.
 * 第一个任务开始时拉起 [KnowledgeTaskService]，切到后台也不会被冻结断网。
 */
@Singleton
class KnowledgeGenerationRunner @Inject constructor(
    private val outlines: ChapterKnowledgeRepository,
    private val characters: BookCharactersRepository,
    @ApplicationScope private val scope: CoroutineScope,
    private val keepAlive: KnowledgeTaskKeepAlive
) {
    private val lock = Any()
    private val jobs = mutableMapOf<KnowledgeTaskKey, Job>()
    private val mutable = MutableStateFlow<Map<KnowledgeTaskKey, KnowledgeTaskState>>(emptyMap())
    val states = mutable.asStateFlow()

    fun generate(plan: KnowledgeGenerationPlan) = start(KnowledgeTaskKey(plan.source.bookId, plan.source.chapterIndex), plan.source.bookTitle) { key ->
        outlines.generate(plan) { index, total -> progress(key, "生成中 $index / $total") }
    }

    fun generateCharacters(plan: BookCharactersPlan) = start(KnowledgeTaskKey(plan.bookId), plan.bookTitle) { key ->
        // 点名扫描与快速模式是同一条链路（名单 → 逐人档案），只是名单来自用户。
        if (plan.quick || plan.targeted) characters.generateQuick(plan) { detail -> progress(key, "", detail) }
        else characters.generate(plan, { completed, total, _ ->
            progress(key, "已扫描 $completed / $total 章", KnowledgeProgress(KnowledgeProgress.Stage.SCANNING, completed, total))
        }) { done, total -> progress(key, "正在写小传 $done / $total", KnowledgeProgress(KnowledgeProgress.Stage.WRITING, done, total)) }
    }

    fun stop(bookId: Long, chapterIndex: Int? = null) {
        synchronized(lock) { jobs[KnowledgeTaskKey(bookId, chapterIndex)]?.cancel() }
    }

    /** 通知栏「停止」：停掉全部书籍资料任务，已整理好的部分照常留作断点。 */
    fun stopAll() {
        synchronized(lock) { jobs.values.toList() }.forEach { it.cancel() }
    }

    private fun progress(key: KnowledgeTaskKey, label: String, detail: KnowledgeProgress? = null) {
        mutable.update { rows -> rows + (key to (rows[key] ?: KnowledgeTaskState()).copy(active = true, progress = label, error = null, detail = detail)) }
    }

    private fun finish(key: KnowledgeTaskKey, progress: String = "", error: String? = null, stopped: Boolean = false) {
        mutable.update { rows -> rows + (key to (rows[key] ?: KnowledgeTaskState())
            .copy(active = false, progress = progress, error = error, stopped = stopped)) }
    }

    private fun start(key: KnowledgeTaskKey, bookTitle: String, work: suspend (KnowledgeTaskKey) -> Unit): Boolean = synchronized(lock) {
        if (jobs.containsKey(key)) return@synchronized false
        mutable.update { rows ->
            val keep = rows.filterValues { it.active } + rows.filterValues { !it.active }.entries.toList().takeLast(128).associate { it.toPair() }
            keep + (key to KnowledgeTaskState(active = true, progress = "等待模型…", bookTitle = bookTitle))
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                work(key)
                finish(key, progress = "已保存")
            } catch (cancelled: CancellationException) {
                finish(key, progress = "已停止，可继续生成", stopped = true)
                throw cancelled
            } catch (error: Exception) {
                finish(key, error = error.message ?: "生成失败，请重试")
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { cause -> synchronized(lock) {
            if (jobs[key] === job) {
                if (cause is CancellationException && mutable.value[key]?.active == true) finish(key, progress = "已停止，可继续生成", stopped = true)
                jobs.remove(key)
            }
        } }
        job.start()
        keepAlive.onTaskStarted()
        true
    }
}

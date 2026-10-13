package com.mozhi.reader.ai.knowledge

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mozhi.reader.MainActivity
import com.mozhi.reader.R
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 任务开始时由 [KnowledgeGenerationRunner] 调用；单测里换成空实现。 */
@Singleton
class KnowledgeTaskKeepAlive @Inject constructor(@ApplicationContext private val context: Context) {
    fun onTaskStarted() {
        // 用户刚在前台点了「开始」，满足 Android 14 起对后台启动前台服务的限制；失败只是少了保活，不影响任务。
        runCatching { ContextCompat.startForegroundService(context, Intent(context, KnowledgeTaskService::class.java)) }
            .onFailure { CharacterResearchAgent.logFailure("keep-alive start failed: ${it.javaClass.simpleName}") }
    }
}

/**
 * 书籍资料任务的前台服务：只管进程优先级与通知，任务本身仍跑在 [KnowledgeGenerationRunner] 的应用级作用域里。
 *
 * 没有它，切到后台后系统会冻结缓存进程、断开网络，正在流式返回的请求就会卡到超时；进程被回收则整轮白跑。
 * 前台服务让进程保持可联网，通知栏显示进度并能停止；所有任务结束后自行退出。
 * 熄屏时持有带超时、随进度续期的 partial WakeLock，任务卡死也不会一直耗电。
 */
@AndroidEntryPoint
class KnowledgeTaskService : Service() {

    @Inject lateinit var runner: KnowledgeGenerationRunner

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    /** 上一轮仍在进行的人物任务：结束时据此发「已整理好」。 */
    private var runningCharacters = emptyMap<KnowledgeTaskKey, KnowledgeTaskState>()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildProgress(runner.states.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        acquireWakeLock()
        serviceScope.launch {
            runner.states.collect { states ->
                announceFinished(states)
                if (states.values.none { it.active }) {
                    ServiceCompat.stopForeground(this@KnowledgeTaskService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    acquireWakeLock()
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildProgress(states))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) runner.stopAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        wakeLock?.takeIf { it.isHeld }?.runCatching { release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildProgress(states: Map<KnowledgeTaskKey, KnowledgeTaskState>): Notification {
        val active = states.filterValues { it.active }
        val characters = active.entries.firstOrNull { it.key.chapterIndex == null }?.value
        val outlines = active.count { it.key.chapterIndex != null }
        val detail = characters?.detail
        val title = when {
            characters != null -> getString(R.string.knowledge_notification_characters_title, characters.bookTitle)
            else -> getString(R.string.knowledge_notification_outlines_title, outlines)
        }
        val text = when {
            characters == null -> getString(R.string.knowledge_notification_outlines_text)
            detail == null -> getString(R.string.knowledge_notification_preparing)
            detail.stage == KnowledgeProgress.Stage.ROSTER -> getString(R.string.characters_progress_planning)
            detail.stage == KnowledgeProgress.Stage.WEB -> getString(R.string.characters_progress_searching)
            detail.stage == KnowledgeProgress.Stage.SCANNING -> getString(R.string.knowledge_notification_scanning, detail.done, detail.total)
            else -> getString(R.string.knowledge_notification_profiles, detail.done, detail.total) +
                detail.working.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = " · ").orEmpty()
        }
        val fraction = detail?.fraction
        return NotificationCompat.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(PROGRESS_MAX, ((fraction ?: 0f) * PROGRESS_MAX).toInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.knowledge_notification_stop), PendingIntent.getService(this, 1,
                Intent(this, KnowledgeTaskService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    /** 人物任务从「进行中」变成「已保存」时，用户若不在应用里，就发一条可点开的完成通知。 */
    private fun announceFinished(states: Map<KnowledgeTaskKey, KnowledgeTaskState>) {
        val finished = runningCharacters.filterKeys { key -> states[key]?.active == false }
        runningCharacters = states.filter { (key, state) -> key.chapterIndex == null && state.active }
        if (finished.isEmpty() || inForeground()) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        finished.forEach { (key, before) ->
            val after = states.getValue(key)
            val text = when {
                after.error != null -> getString(R.string.knowledge_notification_failed, after.error)
                else -> getString(R.string.knowledge_notification_done_text, after.detail?.finished?.size ?: 0)
            }
            manager.notify(DONE_NOTIFICATION_BASE + key.bookId.hashCode(), NotificationCompat.Builder(this, DONE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(if (after.error != null) R.string.knowledge_notification_failed_title else R.string.knowledge_notification_done_title,
                    before.bookTitle))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build())
        }
    }

    private fun inForeground(): Boolean = runCatching {
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance <=
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moread:knowledge").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        // 每次有进度就续期；任务长时间没有任何进展时锁自动过期。
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL,
            getString(R.string.knowledge_notification_channel), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(DONE_CHANNEL,
            getString(R.string.knowledge_notification_done_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private companion object {
        const val PROGRESS_CHANNEL = "knowledge_tasks"
        const val DONE_CHANNEL = "knowledge_done"
        const val NOTIFICATION_ID = 4107
        const val DONE_NOTIFICATION_BASE = 4200
        const val PROGRESS_MAX = 1000
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        const val ACTION_STOP = "com.mozhi.reader.knowledge.STOP"
    }
}

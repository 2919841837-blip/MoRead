package com.mozhi.reader

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import com.mozhi.reader.core.backup.BackupRestoreBootstrap
import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.di.ApplicationScope
import com.mozhi.reader.core.i18n.AppLocales
import com.mozhi.reader.core.text.ChineseTextConverter
import com.mozhi.reader.core.update.pruneUpdateDownloads
import com.mozhi.reader.feature.importer.BookTextMaterializeWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class MoReadApplication : Application() {
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var chineseTextConverter: ChineseTextConverter

    @Inject
    lateinit var readerSettingsRepository: ReaderSettingsRepository

    override fun onCreate() {
        // 待恢复包必须先于 Hilt component 创建；否则 Room/DataStore 可能已经持有旧文件句柄。
        BackupRestoreBootstrap.applyPending(this)
        // 早于任何经 Application Context 取字符串的组件（Worker、通知、Hilt 单例）。
        AppLocales.applyToApplication(this)
        super.onCreate()
        val startedAt = System.currentTimeMillis()
        applicationScope.launch(Dispatchers.IO) {
            // 新版装好后旧安装包再也用不上，每个十几 MB，不清会一直堆在缓存里。
            runCatching { pruneUpdateDownloads(cacheDir, BuildConfig.VERSION_NAME, staleBefore = startedAt) }
                .onFailure { Log.w("MoReadApplication", "Update download cleanup skipped", it) }
        }
        applicationScope.launch {
            runCatching {
                // Wait for persisted settings; cachedSettings initially contains defaults.
                val conversions = readerSettingsRepository.settings.first().bookChineseConversions
                if (conversions.values.any { it != ChineseConversionMode.OFF }) {
                    chineseTextConverter.warmUp()
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.w("MoReadApplication", "Chinese conversion warm-up skipped", error)
            }
        }
        // 正文补齐仍需启动兜底；向量索引改为按需（首次检索时按书触发），不再全库补扫。
        BookTextMaterializeWorker.enqueueStartup(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android 12 及以下系统配置变化会把 Application 资源重置回系统语言。
        AppLocales.applyToApplication(this)
    }
}

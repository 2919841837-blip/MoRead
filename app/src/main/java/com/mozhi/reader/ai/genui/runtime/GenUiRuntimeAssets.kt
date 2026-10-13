package com.mozhi.reader.ai.genui.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 运行时脚本（assets/genui/runtime.js）：全应用只读一次。 */
@Singleton
class GenUiRuntimeAssets @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()
    @Volatile private var cached: String? = null

    suspend fun runtimeScript(): String = cached ?: mutex.withLock {
        cached ?: withContext(Dispatchers.IO) {
            context.assets.open(PATH).bufferedReader().use { it.readText() }
        }.also { cached = it }
    }

    private companion object {
        const val PATH = "genui/runtime.js"
    }
}

package com.mozhi.reader.core.update

import java.io.File

/** 应用内更新的下载目录（位于 cacheDir 下）。 */
internal const val UPDATE_DOWNLOAD_DIRECTORY = "updates"

/**
 * 更新目录里的文件是否已无用。下载校验要求版本号高于当前版本，所以装好新版后，
 * 不高于当前版本的安装包永远用不上；半截的 .part 与其他文件只在早于 [staleBefore] 时清理，
 * 免得删掉正在下载的文件。
 */
internal fun isStaleUpdateDownload(name: String, modifiedAt: Long, currentVersion: String, staleBefore: Long): Boolean =
    if (name.endsWith(".apk") && !name.endsWith(".apk.part")) {
        compareVersionNames(name.removeSuffix(".apk"), currentVersion) <= 0
    } else {
        modifiedAt < staleBefore
    }

/** 启动维护：删掉已安装或已过时的更新包。返回释放的字节数。 */
internal fun pruneUpdateDownloads(cacheDir: File, currentVersion: String, staleBefore: Long): Long {
    val files = File(cacheDir, UPDATE_DOWNLOAD_DIRECTORY).listFiles() ?: return 0L
    return files.filter { it.isFile && isStaleUpdateDownload(it.name, it.lastModified(), currentVersion, staleBefore) }
        .sumOf { file -> file.length().takeIf { file.delete() } ?: 0L }
}

package com.mozhi.reader.core.storage

import com.mozhi.reader.core.update.UPDATE_DOWNLOAD_DIRECTORY
import com.mozhi.reader.core.update.isStaleUpdateDownload
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

data class StorageFile(val file: File, val relativePath: String, val bytes: Long, val modifiedAt: Long)

/** No symlink traversal. Counts on-disk files once, not references from books or settings. */
internal fun storageFiles(root: File, prefix: String): List<StorageFile> {
    if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
    val base = root.canonicalFile.toPath()
    return Files.walk(base).use { paths ->
        paths.iterator().asSequence()
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .map { path ->
                val file = path.toFile()
                StorageFile(file, "$prefix/${base.relativize(path).toString().replace('\\', '/')}", file.length(), file.lastModified())
            }.toList()
    }
}

internal const val STORAGE_CLEANUP_MIN_AGE_MS = 24L * 60 * 60 * 1000

/** 「临时与系统缓存」的下一级来源；只对 [categoryOf] 归入 CACHE 的路径有意义。 */
internal fun cacheBucketOf(path: String): CacheBucket = when (path.removePrefix("cache/").substringBefore('/')) {
    UPDATE_DOWNLOAD_DIRECTORY -> CacheBucket.UPDATES
    "WebView", "org.chromium.android_webview" -> CacheBucket.WEB_VIEW
    "lan-inbox", "reader-font-import", "reader-image-import", "epub-image-editor", "book-cover-drafts" -> CacheBucket.IMPORTS
    "image-api-test", "voice-design-previews" -> CacheBucket.PREVIEWS
    "export", "chart-exports" -> CacheBucket.EXPORTS
    else -> CacheBucket.OTHER
}

internal fun isOldStorageFile(file: StorageFile, now: Long): Boolean =
    file.modifiedAt > 0 && now - file.modifiedAt >= STORAGE_CLEANUP_MIN_AGE_MS

internal fun isSafeTemporaryFile(file: StorageFile, now: Long, versionName: String): Boolean {
    val path = file.relativePath
    // 已安装或已过时的更新包随时可删；残留的 .part 仍按 24 小时判定。
    if (path.startsWith("cache/$UPDATE_DOWNLOAD_DIRECTORY/")) {
        return isStaleUpdateDownload(file.file.name, file.modifiedAt, versionName, now - STORAGE_CLEANUP_MIN_AGE_MS)
    }
    if (!isOldStorageFile(file, now)) return false
    // Do not sweep all cacheDir: it also contains active imports, playback and HTTP caches.
    return path.startsWith("cache/export/")
}

internal fun isOrphanStorageFile(
    file: StorageFile,
    bookIds: Set<Long>,
    conversationIds: Set<Long>,
    originalPaths: Set<String>,
    now: Long
): Boolean {
    if (!isOldStorageFile(file, now)) return false
    val segments = file.relativePath.split('/')
    if (segments.size < 3) return false
    if (segments[0] == "cache" && segments[1] in setOf("agent-speech", "book-dom")) {
        return segments[2].toLongOrNull()?.let { it !in bookIds } == true
    }
    if (segments[0] != "files") return false
    return when (segments[1]) {
        "books" -> file.file.canonicalPath !in originalPaths
        "book-text", "book-media", "book-layout", "speech-cache", "illustrations" ->
            segments[2].toLongOrNull()?.let { it !in bookIds } == true
        "attachments" -> segments[2].toLongOrNull()?.let { it !in conversationIds } == true
        else -> false // Library assets are user data, even if not currently selected.
    }
}

/** A preview is not a deletion permit for a changed file or a replaced symlink. */
internal fun deleteUnchangedStorageFile(entry: StorageFile): Boolean {
    val file = entry.file
    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
    if (file.canonicalFile != file.absoluteFile || file.length() != entry.bytes || file.lastModified() != entry.modifiedAt) return false
    return file.delete()
}

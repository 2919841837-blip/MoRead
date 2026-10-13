package com.mozhi.reader.core.update

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun installedAndOlderPackagesAreStaleNewerOnesAreKept() {
        assertTrue(isStaleUpdateDownload("v1.1.0.apk", 0, "1.1.1", staleBefore = 0))
        assertTrue(isStaleUpdateDownload("v1.1.1.apk", 0, "1.1.1", staleBefore = 0))
        assertTrue(isStaleUpdateDownload("v1.2.0-rc.1.apk", 0, "1.2.0", staleBefore = 0))
        assertFalse(isStaleUpdateDownload("v1.2.0.apk", 0, "1.1.1", staleBefore = 0))
        assertFalse(isStaleUpdateDownload("v1.2.0.apk", 0, "1.2.0-rc.1", staleBefore = 0))
    }

    @Test fun partialDownloadsAreOnlyStaleWhenOlderThanTheCutoff() {
        // 版本号比当前旧也不能凭名字删 .part：判定只看时间，避免误删正在写入的文件。
        assertFalse(isStaleUpdateDownload("v1.0.0.apk.part", modifiedAt = 2_000, currentVersion = "1.1.1", staleBefore = 1_000))
        assertTrue(isStaleUpdateDownload("v1.2.0.apk.part", modifiedAt = 500, currentVersion = "1.1.1", staleBefore = 1_000))
        assertTrue(isStaleUpdateDownload("notes.txt", modifiedAt = 500, currentVersion = "1.1.1", staleBefore = 1_000))
    }

    @Test fun pruneDeletesStalePackagesAndReportsFreedBytes() {
        val cache = temporary.newFolder("cache")
        val dir = File(cache, UPDATE_DOWNLOAD_DIRECTORY).apply { mkdirs() }
        val installed = File(dir, "v1.1.1.apk").apply { writeBytes(ByteArray(10)) }
        val older = File(dir, "v1.0.9.apk").apply { writeBytes(ByteArray(20)) }
        val pending = File(dir, "v1.2.0.apk").apply { writeBytes(ByteArray(30)) }
        val active = File(dir, "v1.2.1.apk.part").apply { writeBytes(ByteArray(5)) }
        val cutoff = active.lastModified() - 1

        assertEquals(30L, pruneUpdateDownloads(cache, "1.1.1", staleBefore = cutoff))
        assertFalse(installed.exists())
        assertFalse(older.exists())
        assertTrue(pending.exists())
        assertTrue(active.exists())
        assertEquals(0L, pruneUpdateDownloads(File(cache, "missing"), "1.1.1", staleBefore = cutoff))
    }
}

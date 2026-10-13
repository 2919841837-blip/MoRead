package com.mozhi.reader.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "book_character_guides", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class BookCharacterGuideEntity(
    @PrimaryKey val bookId: Long,
    val generationId: String,
    val sourceRevision: String,
    val modelKey: String,
    val modelLabel: String,
    val promptVersion: Int,
    val contentJson: String,
    val createdAt: Long
)

/** Verified extraction checkpoints. A cancelled run can resume without paying for these parts again. */
@Entity(tableName = "book_character_parts", primaryKeys = ["bookId", "chapterIndex", "start"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")])
data class BookCharacterPartEntity(
    val bookId: Long,
    val chapterIndex: Int,
    val start: Int,
    val end: Int,
    val generationId: String,
    val sourceRevision: String,
    val sourceHash: String,
    val modelKey: String,
    val promptVersion: Int,
    val contentJson: String,
    val createdAt: Long
)

/**
 * 人物档案的断点与缓存：每位人物整理完立刻落一行，进程被杀或停止后再跑只补剩下的人。
 * [cacheKey] 由模型、提示版本、称呼与送给模型的段落正文共同决定——段落没变的人物下次直接复用、不再计费。
 * 名单请求也存一行（[identity] 为 [ROSTER_IDENTITY]），续跑时名单不变，人物的缓存键才对得上。
 */
@Entity(tableName = "book_character_dossiers", primaryKeys = ["bookId", "cacheKey"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")])
data class BookCharacterDossierEntity(
    val bookId: Long,
    val cacheKey: String,
    val identity: String,
    val sourceRevision: String,
    val contentJson: String,
    val createdAt: Long
) {
    companion object {
        const val ROSTER_IDENTITY = "#roster"
    }
}

package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import kotlinx.coroutines.flow.Flow

data class AnnotationReplyCount(
    val annotationId: Long,
    val replyCount: Int
)

data class AnnotationVisibleCounts(val total: Int, val currentChapter: Int)

/** 讨论串召回用的楼主层投影：一条批注的 id / 想法 / 楼主身份 / 章锚。 */
data class AnnotationThreadCandidate(
    val id: Long,
    val note: String,
    val personaId: Long?,
    val chapterIndex: Int
)

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<AnnotationEntity>>

    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Query("UPDATE annotations SET note = :note, colorTag = :colorTag WHERE id = :annotationId")
    suspend fun updateContent(annotationId: Long, note: String, colorTag: String)

    @Query("UPDATE annotations SET style = :style, colorTag = :colorTag WHERE id = :annotationId")
    suspend fun updateStyle(annotationId: Long, style: String, colorTag: String)

    @Query("UPDATE annotations SET note = :note WHERE id = :annotationId")
    suspend fun updateNote(annotationId: Long, note: String)

    @Query("DELETE FROM annotations WHERE id = :annotationId")
    suspend fun delete(annotationId: Long)

    @Query("SELECT * FROM annotations WHERE id = :annotationId")
    suspend fun getAnnotation(annotationId: Long): AnnotationEntity?

    /** 按 id 批量取批注，供段评讨论串按整组（同锚点的全部划线）拼时间线。 */
    @Query("SELECT * FROM annotations WHERE id IN (:annotationIds)")
    suspend fun getAnnotations(annotationIds: List<Long>): List<AnnotationEntity>

    @Query(
        "SELECT * FROM annotations WHERE bookId = :bookId " +
            "ORDER BY chapterIndex ASC, startCharOffset ASC"
    )
    fun observeForBook(bookId: Long): Flow<List<AnnotationEntity>>

    @Query(
        "SELECT * FROM annotations WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "ORDER BY startCharOffset ASC"
    )
    suspend fun getForChapter(bookId: Long, chapterIndex: Int): List<AnnotationEntity>

    @Query(
        "SELECT * FROM annotations WHERE bookId = :bookId " +
            "AND chapterIndex BETWEEN :fromIndex AND :toIndex " +
            "ORDER BY chapterIndex ASC, startCharOffset ASC"
    )
    suspend fun getForChapterRange(
        bookId: Long,
        fromIndex: Int,
        toIndex: Int
    ): List<AnnotationEntity>

    /** 用户自己的批注（personaId IS NULL），按章区间取前文笔记用。 */
    @Query(
        "SELECT * FROM annotations WHERE bookId = :bookId AND personaId IS NULL " +
            "AND chapterIndex BETWEEN :fromIndex AND :toIndex " +
            "ORDER BY chapterIndex ASC, startCharOffset ASC"
    )
    suspend fun getForChapterRangeByUser(
        bookId: Long,
        fromIndex: Int,
        toIndex: Int
    ): List<AnnotationEntity>

    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN chapterIndex = :chapterIndex THEN 1 ELSE 0 END), 0) AS currentChapter " +
        "FROM annotations WHERE bookId = :bookId AND " + com.mozhi.reader.core.retrieval.AnnotationVisibility.SQL_PREDICATE)
    suspend fun getVisibleCounts(bookId: Long, chapterIndex: Int, wholeBook: Boolean, maxChapter: Int,
        maxOffset: Int, whitespace: String): AnnotationVisibleCounts

    @Query("SELECT COUNT(*) FROM annotations WHERE bookId = :bookId")
    suspend fun getCountForBook(bookId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM annotations WHERE bookId = :bookId AND chapterIndex = :chapterIndex"
    )
    suspend fun getCountForChapter(bookId: Long, chapterIndex: Int): Int

    @Query("SELECT COUNT(*) FROM annotations WHERE bookId = :bookId")
    fun observeCountForBook(bookId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM annotations")
    fun observeCount(): Flow<Int>

    // ---- 段评讨论串回复层 ----

    @Insert
    suspend fun insertReply(reply: AnnotationReplyEntity): Long

    @Query("SELECT * FROM annotation_replies WHERE annotationId IN (:annotationIds) ORDER BY createdAt ASC, id ASC")
    fun observeReplies(annotationIds: List<Long>): Flow<List<AnnotationReplyEntity>>

    @Query("SELECT * FROM annotation_replies WHERE annotationId = :annotationId ORDER BY createdAt ASC, id ASC")
    suspend fun getReplies(annotationId: Long): List<AnnotationReplyEntity>

    @Query(
        "SELECT annotationId, COUNT(*) AS replyCount FROM annotation_replies " +
            "WHERE annotationId IN (:annotationIds) GROUP BY annotationId"
    )
    suspend fun getReplyCounts(annotationIds: List<Long>): List<AnnotationReplyCount>

    @Query("DELETE FROM annotation_replies WHERE id = :replyId")
    suspend fun deleteReply(replyId: Long)

    /** 今日已生成的段评条数；与调度器的每日闸门读同一口径（跨书、跨角色共享）。 */
    @Query("SELECT COUNT(*) FROM annotations WHERE proactiveJobId IS NOT NULL AND createdAt >= :since")
    fun observeProactiveCreatedSince(since: Long): Flow<Int>

    /** 有回复的批注 id 集合：纯高亮一旦有讨论也要在正文显示评论小点。 */
    @Query(
        "SELECT DISTINCT annotationId FROM annotation_replies WHERE annotationId IN " +
            "(SELECT id FROM annotations WHERE bookId = :bookId)"
    )
    fun observeRepliedAnnotationIds(bookId: Long): Flow<List<Long>>

    /**
     * 讨论串召回：当前章之前的、有 AI 参与的批注（AI 段评，或楼主为用户但有 AI 回复的讨论串）。
     * 只按章锚防剧透；串内各层不做进度线过滤——用户在讨论里可能主动向 AI 剧透，这段对话记忆要完整保留。
     */
    @Query(
        "SELECT a.id, a.note, a.personaId, a.chapterIndex FROM annotations a " +
            "WHERE a.bookId = :bookId AND a.chapterIndex < :currentChapter " +
            "AND (a.personaId IS NOT NULL OR a.id IN (" +
            "SELECT annotationId FROM annotation_replies WHERE personaId IS NOT NULL)) " +
            "ORDER BY a.chapterIndex ASC, a.id ASC"
    )
    suspend fun getThreadCandidates(bookId: Long, currentChapter: Int): List<AnnotationThreadCandidate>

    /** 批量拉取回复层，供讨论串召回按 annotationId 组装整串；不按进度线过滤（见 [getThreadCandidates]）。 */
    @Query("SELECT * FROM annotation_replies WHERE annotationId IN (:annotationIds) ORDER BY annotationId ASC, createdAt ASC, id ASC")
    suspend fun getRepliesForAnnotations(annotationIds: List<Long>): List<AnnotationReplyEntity>
}

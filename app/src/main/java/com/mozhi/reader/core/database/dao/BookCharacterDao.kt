package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mozhi.reader.core.database.entity.BookCharacterDossierEntity
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import com.mozhi.reader.core.database.entity.BookCharacterPartEntity
import kotlinx.coroutines.flow.Flow

data class BookCharacterCheckpoint(
    val generationId: String, val sourceRevision: String, val modelKey: String,
    val promptVersion: Int, val completedParts: Int
)

@Dao
interface BookCharacterDao {
    @Query("SELECT * FROM book_character_guides WHERE bookId = :bookId")
    fun observeGuide(bookId: Long): Flow<BookCharacterGuideEntity?>
    @Query("SELECT * FROM book_character_guides WHERE bookId = :bookId")
    suspend fun getGuide(bookId: Long): BookCharacterGuideEntity?
    @Query("SELECT * FROM book_character_parts WHERE bookId = :bookId ORDER BY chapterIndex, start")
    suspend fun getParts(bookId: Long): List<BookCharacterPartEntity>
    @Query("SELECT * FROM book_character_parts WHERE bookId = :bookId AND chapterIndex = :chapterIndex ORDER BY start")
    suspend fun getChapterParts(bookId: Long, chapterIndex: Int): List<BookCharacterPartEntity>
    @Query("SELECT generationId, sourceRevision, modelKey, promptVersion, COUNT(*) AS completedParts FROM book_character_parts WHERE bookId = :bookId GROUP BY generationId, sourceRevision, modelKey, promptVersion ORDER BY MAX(createdAt) DESC LIMIT 1")
    suspend fun getCheckpoint(bookId: Long): BookCharacterCheckpoint?
    @Query("SELECT generationId, sourceRevision, modelKey, promptVersion, COUNT(*) AS completedParts FROM book_character_parts WHERE bookId = :bookId GROUP BY generationId, sourceRevision, modelKey, promptVersion ORDER BY MAX(createdAt) DESC LIMIT 1")
    fun observeCheckpoint(bookId: Long): Flow<BookCharacterCheckpoint?>
    @Upsert suspend fun saveGuide(guide: BookCharacterGuideEntity)
    @Upsert suspend fun savePart(part: BookCharacterPartEntity)
    /** 发布后把分段归到已发布的那一代，剩下的缓存才不会被当成「未完成的进度」。 */
    @Query("UPDATE book_character_parts SET generationId = :generationId WHERE bookId = :bookId")
    suspend fun stampParts(bookId: Long, generationId: String)
    @Query("DELETE FROM book_character_parts WHERE bookId = :bookId")
    suspend fun deleteParts(bookId: Long)
    @Query("DELETE FROM book_character_guides WHERE bookId = :bookId")
    suspend fun deleteGuide(bookId: Long)

    @Query("SELECT * FROM book_character_dossiers WHERE bookId = :bookId AND cacheKey = :cacheKey")
    suspend fun getDossier(bookId: Long, cacheKey: String): BookCharacterDossierEntity?
    @Upsert suspend fun saveDossier(dossier: BookCharacterDossierEntity)
    /** 只用来在缓存变化时触发重新计算；具体口径见 [countPendingDossiers]。 */
    @Query("SELECT COUNT(*) FROM book_character_dossiers WHERE bookId = :bookId")
    fun observeDossierCount(bookId: Long): Flow<Int>
    /** 已发布资料之后才整理好的人物数：大于 0 说明上次没跑完，可以继续。名单与小传（以 # 开头的行）不算。 */
    @Query("SELECT COUNT(*) FROM book_character_dossiers WHERE bookId = :bookId AND sourceRevision = :revision AND createdAt > :after AND identity NOT LIKE '#%'")
    suspend fun countPendingDossiers(bookId: Long, revision: String, after: Long): Int
    /** 发布后只留下这一次用到的缓存；旧进度、旧称呼的条目不会越积越多。 */
    @Query("DELETE FROM book_character_dossiers WHERE bookId = :bookId AND cacheKey NOT IN (:keep)")
    suspend fun pruneDossiers(bookId: Long, keep: List<String>)
    @Query("DELETE FROM book_character_dossiers WHERE bookId = :bookId")
    suspend fun deleteDossiers(bookId: Long)
}

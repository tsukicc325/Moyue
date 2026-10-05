package com.harness.inkreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Query("UPDATE books SET title = :title WHERE id = :bookId")
    suspend fun rename(bookId: Long, title: String)

    @Query("UPDATE books SET groupName = :group WHERE id = :bookId")
    suspend fun setGroup(bookId: Long, group: String?)

    @Query("UPDATE books SET coverPath = :path WHERE id = :bookId")
    suspend fun setCover(bookId: Long, path: String?)

    @Query("UPDATE books SET lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun touchLastRead(bookId: Long, timestamp: Long)

    @Query(
        """
        UPDATE books SET encoding = :encoding, encodingManual = :manual, totalBytes = :totalBytes,
            chapterCount = :chapterCount, usedVirtualChapters = :usedVirtual, indexed = 1,
            fileSize = :fileSize, fileMtime = :fileMtime
        WHERE id = :bookId
        """
    )
    suspend fun saveIndexInfo(
        bookId: Long,
        encoding: String,
        manual: Boolean,
        totalBytes: Long,
        chapterCount: Int,
        usedVirtual: Boolean,
        fileSize: Long,
        fileMtime: Long,
    )

    @Query("UPDATE books SET indexed = 0 WHERE id = :bookId")
    suspend fun markNotIndexed(bookId: Long)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun delete(bookId: Long)

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun byId(bookId: Long): BookEntity?

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, addedAt DESC")
    suspend fun all(): List<BookEntity>

    @Query("SELECT * FROM books WHERE filePath = :filePath LIMIT 1")
    suspend fun byPath(filePath: String): BookEntity?

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int
}

@Dao
interface ChapterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx")
    suspend fun byBook(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx")
    fun observeByBook(bookId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun countByBook(bookId: Long): Int

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND idx = :idx")
    suspend fun byIndex(bookId: Long, idx: Int): ChapterEntity?

    /**
     * 找出包含给定字节偏移的章节（用于按文件位置恢复阅读进度）。走主键前缀扫描。
     */
    @Query(
        """
        SELECT * FROM chapters WHERE bookId = :bookId AND startByte <= :byteOffset
        ORDER BY startByte DESC LIMIT 1
        """
    )
    suspend fun chapterAtByte(bookId: Long, byteOffset: Long): ChapterEntity?
}

@Dao
interface ProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun byBook(bookId: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    fun observeByBook(bookId: Long): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress")
    fun observeAll(): Flow<List<ProgressEntity>>

    @Query("DELETE FROM progress WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)
}

@Dao
interface BookmarkDao {

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmarks SET preview = :preview WHERE id = :id")
    suspend fun updatePreview(id: Long, preview: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIdx, blockIndex, charOffsetInBlock")
    fun observeByBook(bookId: Long): Flow<List<BookmarkEntity>>
}

@Dao
interface AnnotationDao {

    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM annotations WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY chapterIdx, blockIndex, startOffsetInBlock")
    fun observeByBook(bookId: Long): Flow<List<AnnotationEntity>>
}

@Dao
interface ReadingSessionDao {

    @Insert
    suspend fun insert(session: ReadingSessionEntity): Long

    @Query("SELECT * FROM reading_sessions WHERE bookId = :bookId ORDER BY startedAt DESC")
    suspend fun byBook(bookId: Long): List<ReadingSessionEntity>

    @Query("SELECT COALESCE(SUM(endedAt - startedAt), 0) FROM reading_sessions WHERE startedAt >= :since")
    fun observeMillisSince(since: Long): Flow<Long>

    @Query("SELECT DISTINCT dayEpoch FROM reading_sessions WHERE startedAt >= :since ORDER BY dayEpoch")
    fun observeActiveDaysSince(since: Long): Flow<List<Long>>

    @Query("SELECT COALESCE(SUM(endedAt - startedAt), 0) FROM reading_sessions WHERE dayEpoch = :dayEpoch")
    suspend fun millisOnDay(dayEpoch: Long): Long

    @Query("SELECT COALESCE(SUM(charsRead), 0) FROM reading_sessions WHERE startedAt >= :since")
    suspend fun charsSince(since: Long): Long

    @Query("SELECT COALESCE(SUM(endedAt - startedAt), 0) FROM reading_sessions")
    suspend fun totalMillis(): Long

    @Query("SELECT COALESCE(SUM(endedAt - startedAt), 0) FROM reading_sessions WHERE dayEpoch >= :since")
    suspend fun millisSince(since: Long): Long

    @Query("SELECT COUNT(*) FROM reading_sessions WHERE dayEpoch >= :since")
    suspend fun sessionsSince(since: Long): Int

    @Query("SELECT COUNT(DISTINCT dayEpoch) FROM reading_sessions WHERE dayEpoch >= :since")
    suspend fun activeDaysSince(since: Long): Int

    @Query(
        """
        SELECT dayEpoch AS dayEpoch, COALESCE(SUM(endedAt - startedAt), 0) AS total
        FROM reading_sessions WHERE dayEpoch >= :since GROUP BY dayEpoch ORDER BY dayEpoch
        """
    )
    suspend fun dailySince(since: Long): List<DailyReadingRow>
}

data class DailyReadingRow(val dayEpoch: Long, val total: Long)

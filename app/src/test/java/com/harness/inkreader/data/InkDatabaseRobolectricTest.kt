package com.harness.inkreader.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InkDatabaseRobolectricTest {

    private lateinit var db: InkDatabase

    @Before
    fun setUp() {
        db = InkDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun book(
        title: String = "夜航船",
        path: String = "/data/books/1.txt",
        size: Long = 100L * 1024 * 1024,
    ) = BookEntity(
        title = title,
        filePath = path,
        fileSize = size,
        fileMtime = 1_700_000_000_000,
        encoding = "GB18030",
        totalBytes = size,
        chapterCount = 23_000,
        addedAt = 1_700_000_000_000,
    )

    @Test
    fun `book insert and read back`() = runBlocking {
        val id = db.books().insert(book())
        assertTrue(id > 0)

        val loaded = db.books().byId(id)
        assertNotNull(loaded)
        assertEquals("夜航船", loaded!!.title)
        assertEquals(100L * 1024 * 1024, loaded.fileSize)
        assertEquals(1, db.books().count())
        assertEquals(id, db.books().byPath("/data/books/1.txt")?.id)
    }

    @Test
    fun `bulk chapter insert of 23000 rows stays fast and ordered`() = runBlocking {
        val bookId = db.books().insert(book())

        val total = 23_000
        val chapters = List(total) { index ->
            ChapterEntity(
                bookId = bookId,
                idx = index,
                title = "第${index + 1}章 风雪",
                startByte = index.toLong() * 4_400L,
                endByte = (index + 1).toLong() * 4_400L,
            )
        }

        val startedAt = System.currentTimeMillis()
        db.chapters().insertAll(chapters)
        val elapsed = System.currentTimeMillis() - startedAt
        println("BENCH 批量写入 $total 行章节索引耗时 ${elapsed}ms")
        assertTrue("批量写入 ${elapsed}ms 过慢", elapsed < 5_000)

        assertEquals(total, db.chapters().countByBook(bookId))
        val loaded = db.chapters().byBook(bookId)
        assertEquals(total, loaded.size)
        assertEquals("第1章 风雪", loaded.first().title)
        assertEquals("第${total}章 风雪", loaded.last().title)
        assertTrue(loaded.zipWithNext().all { (a, b) -> a.idx < b.idx })
    }

    @Test
    fun `chapterAtByte resolves the containing chapter`() = runBlocking {
        val bookId = db.books().insert(book())
        db.chapters().insertAll(
            listOf(
                ChapterEntity(bookId, 0, "第一章", 0, 1_000),
                ChapterEntity(bookId, 1, "第二章", 1_000, 2_500),
                ChapterEntity(bookId, 2, "第三章", 2_500, 9_000),
            )
        )

        assertEquals(0, db.chapters().chapterAtByte(bookId, 0)?.idx)
        assertEquals(0, db.chapters().chapterAtByte(bookId, 999)?.idx)
        assertEquals(1, db.chapters().chapterAtByte(bookId, 1_000)?.idx)
        assertEquals(2, db.chapters().chapterAtByte(bookId, 8_999)?.idx)
        assertEquals(2, db.chapters().chapterAtByte(bookId, 500_000)?.idx)
    }

    @Test
    fun `progress upsert replaces the row instead of piling up`() = runBlocking {
        val bookId = db.books().insert(book())

        db.progress().upsert(
            ProgressEntity(bookId = bookId, chapterIdx = 3, blockIndex = 1, charOffsetInBlock = 100, percent = 0.1f, updatedAt = 1_000)
        )
        db.progress().upsert(
            ProgressEntity(bookId = bookId, chapterIdx = 9, blockIndex = 2, charOffsetInBlock = 55, percent = 0.4f, updatedAt = 2_000)
        )

        val progress = db.progress().byBook(bookId)
        assertNotNull(progress)
        assertEquals(9, progress!!.chapterIdx)
        assertEquals(2, progress.blockIndex)
        assertEquals(55, progress.charOffsetInBlock)
        assertEquals(0.4f, progress.percent, 0.0001f)
    }

    @Test
    fun `deleting a book clears its derived rows`() = runBlocking {
        val bookId = db.books().insert(book())
        db.chapters().insertAll(listOf(ChapterEntity(bookId, 0, "第一章", 0, 100)))
        db.progress().upsert(ProgressEntity(bookId = bookId, chapterIdx = 0, blockIndex = 0, charOffsetInBlock = 0, percent = 0f, updatedAt = 1))
        db.bookmarks().insert(
            BookmarkEntity(bookId = bookId, chapterIdx = 0, blockIndex = 0, charOffsetInBlock = 5, preview = "夜色", createdAt = 1)
        )

        // Room 的 cascade 需要显式外键，这里走显式清理路径（与 M4 的删除逻辑一致）
        db.chapters().deleteByBook(bookId)
        db.progress().deleteByBook(bookId)
        db.bookmarks().deleteByBook(bookId)
        db.books().delete(bookId)

        assertEquals(0, db.books().count())
        assertEquals(0, db.chapters().countByBook(bookId))
        assertNull(db.progress().byBook(bookId))
    }

    @Test
    fun `reading sessions aggregate by day`() = runBlocking {
        val bookId = db.books().insert(book())
        val day = 20_000L

        db.sessions().insert(ReadingSessionEntity(bookId = bookId, startedAt = 0, endedAt = 60_000, charsRead = 1_200, dayEpoch = day))
        db.sessions().insert(ReadingSessionEntity(bookId = bookId, startedAt = 0, endedAt = 120_000, charsRead = 3_000, dayEpoch = day))

        assertEquals(180_000L, db.sessions().millisOnDay(day))
        assertEquals(4_200L, db.sessions().charsSince(0))
        assertEquals(2, db.sessions().byBook(bookId).size)
    }
}

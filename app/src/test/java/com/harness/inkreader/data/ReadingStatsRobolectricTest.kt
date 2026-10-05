package com.harness.inkreader.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset

/** 阅读统计聚合与书签/笔记落库。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingStatsRobolectricTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: InkDatabase
    private lateinit var repo: BookRepository
    private val gbk: Charset = Charset.forName("GBK")

    private val dayMillis = 24L * 60 * 60 * 1000
    private val minute = 60_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = InkDatabase.inMemory(context)
        repo = BookRepository(context, db)
    }

    @After
    fun tearDown() = db.close()

    private fun importNovel(): Long = runBlocking {
        val text = buildString {
            for (chapter in 1..3) {
                append("第").append(chapter).append("章 风雪").append('\n')
                repeat(4) {
                    append("　　").append("他站在窗前看着外面的雪，想起很多年前的旧事。".repeat(2)).append('\n')
                }
            }
        }
        val source = File(temp.newFolder(), "统计.txt")
        source.writeBytes(text.toByteArray(gbk))
        repo.importFromFile(source)
    }

    @Test
    fun `sessions aggregate into totals, daily buckets and speed`() = runBlocking {
        val bookId = importNovel()
        val today = Days.startOfDay(System.currentTimeMillis())

        repo.recordSession(bookId, today + 1_000, today + 1_000 + 30 * minute, 600)
        repo.recordSession(bookId, today - dayMillis + 1_000, today - dayMillis + 1_000 + 20 * minute, 400)
        // 10 天前：不在 7 天窗口内
        repo.recordSession(bookId, today - 10 * dayMillis, today - 10 * dayMillis + 15 * minute, 999)

        val stats = repo.stats(7)

        assertEquals("近 7 天 30+20 分钟", 50 * minute, stats.millisLast7Days)
        assertEquals("累计 30+20+15 分钟", 65 * minute, stats.totalMillis)
        assertEquals(1_000L, stats.charsLast7Days)
        assertEquals(2, stats.activeDaysLast7Days)
        assertEquals(2, stats.sessionsLast7Days)
        assertEquals(7, stats.daily.size)
        assertEquals("今天是最后一个桶", 30 * minute, stats.daily.last().millis)
        assertEquals("昨天是倒数第二个桶", 20 * minute, stats.daily[stats.daily.size - 2].millis)
        assertEquals("最早那天没有记录", 0L, stats.daily.first().millis)
        assertEquals("1000 字 / 50 分", 20, stats.charsPerMinute)
        assertEquals(1, stats.bookCount)
    }

    @Test
    fun `zero or negative length sessions are ignored`() = runBlocking {
        val bookId = importNovel()
        val now = System.currentTimeMillis()

        assertEquals(-1L, repo.recordSession(bookId, now, now, 100))
        assertEquals(-1L, repo.recordSession(bookId, now, now - 1_000, 100))

        assertEquals(0L, repo.stats(7).totalMillis)
        assertTrue(repo.sessions(bookId).isEmpty())
    }

    @Test
    fun `speed is zero when nothing was read`() = runBlocking {
        importNovel()
        val stats = repo.stats(7)

        assertEquals(0, stats.charsPerMinute)
        assertEquals(0L, stats.millisLast7Days)
        assertEquals(7, stats.daily.size)
    }

    @Test
    fun `last days returns consecutive midnights ending today`() {
        val now = System.currentTimeMillis()
        val days = Days.lastDays(7, now)

        assertEquals(7, days.size)
        assertEquals(Days.startOfDay(now), days.last())
        assertTrue(days.zipWithNext().all { (a, b) -> b - a == dayMillis })
        // 每个都应当是「本地零点」：再取一次 startOfDay 必须原地不动
        assertTrue(days.all { Days.startOfDay(it) == it })
    }

    @Test
    fun `bookmarks round trip and preview is truncated`() = runBlocking {
        val bookId = importNovel()

        val id = repo.addBookmark(bookId, 1, 2, 34, "夜".repeat(300))

        val stored = repo.bookmarks(bookId).single()
        assertEquals(id, stored.id)
        assertEquals(1, stored.chapterIdx)
        assertEquals(2, stored.blockIndex)
        assertEquals(34, stored.charOffsetInBlock)
        assertEquals("预览要截断，否则列表会很长", 80, stored.preview.length)

        repo.deleteBookmark(id)
        assertTrue(repo.bookmarks(bookId).isEmpty())
    }

    @Test
    fun `annotations round trip with highlight colour and note`() = runBlocking {
        val bookId = importNovel()

        val id = repo.addAnnotation(
            bookId = bookId,
            chapterIdx = 0,
            blockIndex = 1,
            startOffsetInBlock = 10,
            endOffsetInBlock = 26,
            selectedText = "他站在窗前看着外面的雪",
            note = "  这句很好  ",
        )

        val stored = repo.annotations(bookId).single()
        assertEquals(id, stored.id)
        assertEquals(10, stored.startOffsetInBlock)
        assertEquals(26, stored.endOffsetInBlock)
        assertEquals("笔记要 trim", "这句很好", stored.note)
        assertEquals(BookRepository.DEFAULT_HIGHLIGHT_COLOR, stored.color)

        repo.deleteAnnotation(id)
        assertTrue(repo.annotations(bookId).isEmpty())
    }

    @Test
    fun `blank note is stored as null`() = runBlocking {
        val bookId = importNovel()

        repo.addAnnotation(bookId, 0, 0, 1, 5, "选中文字", "   ")

        assertNull(repo.annotations(bookId).single().note)
    }

    @Test
    fun `deleting a book also clears its bookmarks and annotations`() = runBlocking {
        val bookId = importNovel()
        repo.addBookmark(bookId, 0, 0, 5, "书签")
        repo.addAnnotation(bookId, 0, 0, 1, 5, "划线", "笔记")

        repo.deleteBook(bookId)

        assertTrue(repo.bookmarks(bookId).isEmpty())
        assertTrue(repo.annotations(bookId).isEmpty())
        assertNull(repo.book(bookId))
    }
}

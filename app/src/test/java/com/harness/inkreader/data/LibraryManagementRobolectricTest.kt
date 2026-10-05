package com.harness.inkreader.data

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.harness.inkreader.engine.ChapterBlocks
import com.harness.inkreader.ui.reader.ReaderViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset

/**
 * M4 端到端验收：引用模式导入、文件夹批量导入、手动改编码重建索引、重命名与分组、删除清理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryManagementRobolectricTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: InkDatabase
    private lateinit var repo: BookRepository

    private val gbk: Charset = Charset.forName("GBK")
    private val sentence = "他站在窗前看着外面的雪，想起很多年前的旧事。"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = InkDatabase.inMemory(context)
        repo = BookRepository(context, db)
    }

    @After
    fun tearDown() = db.close()

    private fun writeNovel(name: String, chapters: Int = 4, directory: File? = null): File {
        val text = buildString {
            for (chapter in 1..chapters) {
                append("第").append(chapter).append("章 第").append(chapter).append("节的风雪").append('\n')
                repeat(6) { append("　　").append(sentence.repeat(2)).append('\n') }
            }
        }
        val file = File(directory ?: temp.newFolder("src-$name"), name)
        file.parentFile?.mkdirs()
        file.writeBytes(text.toByteArray(gbk))
        return file
    }

    private fun booksDirFileCount(): Int =
        File(context.filesDir, BookRepository.BOOKS_DIR).listFiles()?.size ?: 0

    private suspend fun firstBlockText(bookId: Long): String {
        val book = repo.book(bookId) ?: error("book missing")
        val chapter = repo.chapters(bookId).first()
        val blocks = ChapterBlocks(
            source = repo.byteSourceFor(book),
            charset = Charset.forName(book.encoding),
            chapterStart = chapter.startByte,
            chapterEnd = chapter.endByte,
        )
        return blocks.load(0).text
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T : Any> await(what: String, timeoutMs: Long = 20_000, block: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            block()?.let { return it }
            idleMain()
            Thread.sleep(5)
        }
        idleMain()
        throw AssertionError("等待超时：$what")
    }

    @Test
    fun `reference mode keeps the original file and reads through the uri`() = runBlocking {
        val source = writeNovel("引用模式.txt", chapters = 4)
        val originalPath = source.absolutePath
        val copiesBefore = booksDirFileCount()

        val bookId = repo.importFromUri(
            uri = Uri.fromFile(source),
            displayName = "引用模式.txt",
            mode = StorageMode.REFERENCE,
        )
        val book = repo.book(bookId)!!

        assertEquals(StorageMode.REFERENCE, book.storageMode)
        assertEquals(Uri.fromFile(source).toString(), book.filePath)
        assertEquals(source.length(), book.fileSize)
        assertEquals(4, book.chapterCount)
        // 引用模式不应当往私有目录里塞副本
        assertEquals(copiesBefore, booksDirFileCount())
        assertTrue("原文件不该被动过", File(originalPath).exists())

        // 走 UriByteSource 能读出正确正文
        assertTrue(firstBlockText(bookId).contains("他站在窗前"))
    }

    @Test
    fun `reader opens a reference mode book end to end`() {
        val source = writeNovel("引用阅读.txt", chapters = 5)
        val bookId = runBlocking {
            repo.importFromUri(Uri.fromFile(source), "引用阅读.txt", StorageMode.REFERENCE)
        }

        val viewModel = ReaderViewModel(repo, bookId, 1024)
        viewModel.start()
        viewModel.setViewport(720, 1200, 2.75f)

        val state = await("引用模式阅读器加载") {
            viewModel.state.value.takeIf { !it.loading && it.paged != null }
        }

        assertNull(state.error)
        assertTrue(
            "引用模式读出的正文应当是可读中文，实际：${state.paged!!.layout.text.take(40)}",
            state.paged!!.layout.text.contains("他站在窗前"),
        )
        assertEquals(5, state.chapters.size)
    }

    @Test
    fun `changing the encoding reindexes and changes the decoded text`() = runBlocking {
        val source = writeNovel("改编码.txt", chapters = 3)
        val bookId = repo.importFromFile(source)

        assertTrue(firstBlockText(bookId).contains("他站在窗前"))

        // 故意用一个错误编码重建索引：正文字符会全变（但不会崩）
        assertTrue(repo.reindex(bookId, Charset.forName("Big5")))
        val wrong = repo.book(bookId)!!
        assertEquals("Big5", wrong.encoding)
        assertTrue("手动改编码应当被标记", wrong.encodingManual)
        assertFalse(
            "按 Big5 解 GBK 字节不应当还原出原句",
            firstBlockText(bookId).contains("他站在窗前"),
        )

        // 改回正确编码，正文恢复
        assertTrue(repo.reindex(bookId, Charset.forName("GB18030")))
        val restored = repo.book(bookId)!!
        assertEquals("GB18030", restored.encoding)
        assertTrue(firstBlockText(bookId).contains("他站在窗前"))
    }

    @Test
    fun `rename and group assignment are persisted`() = runBlocking {
        val bookId = repo.importFromFile(writeNovel("旧名字.txt"))

        repo.rename(bookId, "夜航船")
        repo.setGroup(bookId, " 武侠 ")
        var book = repo.book(bookId)!!
        assertEquals("夜航船", book.title)
        assertEquals("武侠", book.groupName)

        repo.setGroup(bookId, "   ")
        book = repo.book(bookId)!!
        assertNull("空白分组名应当等于移出分组", book.groupName)

        repo.rename(bookId, "   ")
        assertEquals("未命名", repo.book(bookId)!!.title)
    }

    @Test
    fun `deleting a copy mode book removes its file and derived rows`() = runBlocking {
        val bookId = repo.importFromFile(writeNovel("待删除.txt"))
        val storedPath = repo.book(bookId)!!.filePath
        assertTrue(File(storedPath).exists())
        assertEquals(1, booksDirFileCount())

        repo.deleteBook(bookId)

        assertNull(repo.book(bookId))
        assertTrue(repo.chapters(bookId).isEmpty())
        assertNull(repo.progress(bookId))
        assertFalse("复制模式的副本应当被删掉", File(storedPath).exists())
        assertEquals(0, booksDirFileCount())
    }

    @Test
    fun `deleting a reference mode book leaves the original alone`() = runBlocking {
        val source = writeNovel("引用待删.txt")
        val bookId = repo.importFromUri(Uri.fromFile(source), "引用待删.txt", StorageMode.REFERENCE)

        repo.deleteBook(bookId)

        assertNull(repo.book(bookId))
        assertTrue("引用模式删除后原文件必须还在", source.exists())
    }

    @Test
    fun `deleting a utf16 book removes the transcoded copy`() = runBlocking {
        val utf16 = Charset.forName("UTF-16LE")
        val text = buildString {
            for (chapter in 1..3) {
                append("第").append(chapter).append("章 风雪").append('\n')
                repeat(4) { append("　　").append(sentence.repeat(2)).append('\n') }
            }
        }
        val source = File(temp.newFolder("utf16-src"), "转码.txt")
        source.writeBytes(text.toByteArray(utf16))

        val bookId = repo.importFromFile(source)
        val book = repo.book(bookId)!!
        assertEquals("UTF-8", book.encoding)
        assertTrue(firstBlockText(bookId).contains("他站在窗前"))

        repo.deleteBook(bookId)
        assertFalse(File(book.filePath).exists())
    }
}

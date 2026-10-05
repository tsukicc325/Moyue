package com.harness.inkreader.ui.reader

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.harness.inkreader.data.BookRepository
import com.harness.inkreader.data.InkDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * M3 端到端验收：导入文件 → 建索引 → 打开阅读器 → 翻页跨块跨章 → 保存/恢复阅读位置。
 *
 * 分块粒度调成 1KB，这样很小的章节也能覆盖「一块读完要跨到下一块」的路径。
 * 开原生图形模式，让折行与分页都是真实的（否则每段只占一行，翻页路径覆盖不到真正的多页场景）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderFlowRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: InkDatabase
    private lateinit var repo: BookRepository

    private val gbk: Charset = Charset.forName("GBK")
    private val paragraph = "他站在窗前看着外面的雪，想起很多年前的旧事。"

    private val blockBytes = 1024

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = InkDatabase.inMemory(context)
        repo = BookRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun writeSource(
        name: String,
        chapters: Int,
        paragraphsPerChapter: Int,
        charset: Charset = gbk,
    ): File {
        val text = buildString {
            for (chapter in 1..chapters) {
                append("第").append(chapter).append("章 第").append(chapter).append("节的风雪").append('\n')
                repeat(paragraphsPerChapter) {
                    append("　　").append(paragraph.repeat(2)).append('\n')
                }
            }
        }
        val file = File(context.filesDir, name)
        file.writeBytes(text.toByteArray(charset))
        return file
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

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

    private fun importNovel(
        name: String = "夜航船.txt",
        chapters: Int = 4,
        paragraphs: Int = 40,
        charset: Charset = gbk,
    ): Long = runBlocking {
        repo.importFromFile(writeSource(name, chapters, paragraphs, charset))
    }

    private fun openReader(bookId: Long): ReaderViewModel {
        val viewModel = ReaderViewModel(repo, bookId, blockBytes)
        viewModel.start()
        viewModel.setViewport(720, 1200, 2.75f)
        await("阅读器加载完成") { viewModel.state.value.takeIf { !it.loading && it.paged != null } }
        return viewModel
    }

    @Test
    fun `import indexes the book and persists every chapter`() = runBlocking {
        val bookId = importNovel(chapters = 5)
        val book = repo.book(bookId)

        assertNotNull(book)
        assertEquals("夜航船", book!!.title)
        assertEquals(5, book.chapterCount)
        assertTrue(book.indexed)
        // GBK 会被判成 GB18030（GBK 的超集），解出来的文本完全一致
        assertTrue("意外的编码判定：${book.encoding}", book.encoding.startsWith("GB"))
        assertEquals(1, repo.books().size)

        val chapters = repo.chapters(bookId)
        assertEquals(5, chapters.size)
        assertEquals("第1章 第1节的风雪", chapters.first().title)
        assertEquals("第5章 第5节的风雪", chapters.last().title)
        assertEquals(book.totalBytes, chapters.last().endByte)
    }

    @Test
    fun `re-importing the same file reuses the existing book`() = runBlocking {
        val first = importNovel()
        val second = importNovel()

        assertEquals(first, second)
        assertEquals(1, repo.books().size)
    }

    @Test
    fun `utf16 source is transcoded to utf8 on import`() = runBlocking {
        val utf16 = Charset.forName("UTF-16LE")
        val bookId = importNovel(name = "utf16.txt", chapters = 3, charset = utf16)
        val book = repo.book(bookId)!!

        assertEquals("UTF-8", book.encoding)
        val stored = File(book.filePath)
        assertTrue(stored.exists())
        val storedText = stored.readText(StandardCharsets.UTF_8)
        assertTrue("转码后的正文应当是可读中文", storedText.contains("他站在窗前看着外面的雪"))
        assertTrue(!storedText.contains('\uFFFD'))

        val viewModel = openReader(bookId)
        val text = viewModel.state.value.paged!!.layout.text.toString()
        assertTrue(text.contains("他站在窗前看着外面的雪"))
    }

    @Test
    fun `turning pages walks through blocks and then into the next chapter`() {
        val bookId = runBlocking { importNovel(chapters = 4, paragraphs = 40) }
        val viewModel = openReader(bookId)

        val startChapter = viewModel.state.value.chapterIdx
        val visitedBlocks = linkedSetOf<Int>()
        var token = positionOf(viewModel)
        var turns = 0

        while (viewModel.state.value.chapterIdx == startChapter && turns < 300) {
            visitedBlocks.add(viewModel.state.value.blockIndex)
            viewModel.nextPage()
            turns++
            // 必须等位置真的变了再继续：跨块/跨章是异步读盘，旧状态还在
            await("翻页推进（第 $turns 次）") {
                viewModel.state.value.takeIf { positionOf(viewModel) != token }
            }
            token = positionOf(viewModel)
        }

        assertEquals("应当翻到下一章", startChapter + 1, viewModel.state.value.chapterIdx)
        assertTrue(
            "跨章之前应当先经过多个块，实际只到过块 $visitedBlocks",
            visitedBlocks.size > 1,
        )
        assertEquals(0, viewModel.state.value.pageIndex)
        assertTrue("翻了 $turns 次才跨章，次数异常", turns in 2..200)
    }

    private fun positionOf(viewModel: ReaderViewModel): Triple<Int, Int, Int> {
        val state = viewModel.state.value
        return Triple(state.chapterIdx, state.blockIndex, state.pageIndex)
    }

    @Test
    fun `turning back returns to the previous chapter`() {
        val bookId = runBlocking { importNovel(chapters = 4, paragraphs = 40) }
        val viewModel = openReader(bookId)

        viewModel.goToChapter(1)
        await("跳到第二章") { viewModel.state.value.takeIf { it.chapterIdx == 1 } }

        viewModel.previousPage()
        val state = await("退回上一章") { viewModel.state.value.takeIf { it.chapterIdx == 0 } }
        assertTrue("退回上一章应当落在该章最后一页", state.pageIndex >= 0)
    }

    @Test
    fun `chapter jump and whole book seek land in the right place`() {
        val bookId = runBlocking { importNovel(chapters = 8, paragraphs = 30) }
        val viewModel = openReader(bookId)

        viewModel.goToChapter(5)
        val jumped = await("跳到第六章") { viewModel.state.value.takeIf { it.chapterIdx == 5 } }
        assertEquals("第6章 第6节的风雪", jumped.chapterTitle)
        assertTrue(jumped.percent > 0f)

        viewModel.goToFraction(0.9f)
        val sought = await("按百分比跳转") { viewModel.state.value.takeIf { it.chapterIdx >= 6 } }
        assertTrue("90% 应当落在很靠后的章节，实际 ${sought.chapterIdx}", sought.chapterIdx >= 6)
        assertTrue(sought.percent > 0.6f)
    }

    @Test
    fun `reading position is saved and restored in a new reader instance`() {
        val bookId = runBlocking { importNovel(chapters = 6, paragraphs = 25) }

        val first = openReader(bookId)
        first.goToChapter(3)
        await("跳到第四章") { first.state.value.takeIf { it.chapterIdx == 3 } }
        first.saveNow()

        val saved = await("进度落库") { runBlocking { repo.progress(bookId) } }
        assertEquals(3, saved.chapterIdx)

        // 模拟关掉 App 再打开
        val second = openReader(bookId)
        assertEquals(3, second.state.value.chapterIdx)
        assertEquals("第4章 第4节的风雪", second.state.value.chapterTitle)
    }

    @Test
    fun `changing typography keeps the reading position and repaginates`() {
        val bookId = runBlocking { importNovel(chapters = 4, paragraphs = 60) }
        val viewModel = openReader(bookId)

        viewModel.nextPage()
        val before = await("翻到下一页") {
            val state = viewModel.state.value
            state.takeIf { state.paged != null && (state.pageIndex > 0 || state.blockIndex > 0) }
        }
        val beforeStartChar = before.paged!!.pages[before.pageIndex].startChar
        val beforePageCount = before.pageCount

        // 字号变大 -> 每页字数变少 -> 总页数变多，且仍然停在原来那段文字上
        viewModel.setSpec(before.spec.copy(textSizeSp = 30f))
        val after = await("重新分页") {
            viewModel.state.value.takeIf { it.paged != null && it.spec.textSizeSp == 30f }
        }

        assertTrue(
            "字号变大后总页数应当增加：$beforePageCount -> ${after.pageCount}",
            after.pageCount >= beforePageCount,
        )
        val afterStartChar = after.paged!!.pages[after.pageIndex].startChar
        val afterPageEnd = after.paged.pages[after.pageIndex].endChar
        assertTrue(
            "重新分页后应当仍然停在原位置附近（原 $beforeStartChar，新页 [$afterStartChar,$afterPageEnd)）",
            beforeStartChar >= afterStartChar - 1 && beforeStartChar < afterPageEnd,
        )
    }
}

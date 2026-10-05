package com.harness.inkreader.ui.reader

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.harness.inkreader.data.BookRepository
import com.harness.inkreader.data.InkDatabase
import com.harness.inkreader.data.settings.ReaderSettings
import com.harness.inkreader.engine.ChapterBlocks
import com.harness.inkreader.engine.ChapterIndexer
import com.harness.inkreader.engine.FileByteSource
import com.harness.inkreader.engine.FullTextSearcher
import com.harness.inkreader.engine.TextPaginator
import com.harness.inkreader.engine.TextSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.charset.Charset
import kotlin.random.Random

/**
 * 长程稳定性：反复「分块读取 → 排版 → 搜索 → 重建索引」，以及连续翻几百页。
 *
 * 这类问题在单次功能测试里看不出来：句柄没关、缓存无限增长、状态慢慢漂移，
 * 都只有跑够次数才会现形。所以这里既断言**内存有界**，也断言**位置单调不回退**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StabilitySoakRobolectricTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: InkDatabase
    private lateinit var repo: BookRepository
    private val gbk: Charset = Charset.forName("GBK")

    private val paragraph = "他站在窗前看着外面的雪，想起很多年前的旧事。"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = InkDatabase.inMemory(context)
        repo = BookRepository(context, db)
    }

    @After
    fun tearDown() = db.close()

    private class HeapSampler : Thread() {
        @Volatile
        private var running = true

        @Volatile
        var peak: Long = 0
            private set

        override fun run() {
            val runtime = Runtime.getRuntime()
            while (running) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                if (used > peak) peak = used
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        fun stopSampling(): Long {
            running = false
            interrupt()
            return peak
        }
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** 约 6MB 的 GBK 小说，20 章，每章里放一段 ASCII 标记便于搜索。 */
    private fun writeBigNovel(chapters: Int = 20, paragraphs: Int = 260): File {
        val file = File(temp.newFolder(), "soak.txt")
        file.outputStream().buffered(1 shl 20).use { out ->
            val writer = java.io.BufferedWriter(
                java.io.OutputStreamWriter(out, gbk),
                1 shl 16,
            )
            for (chapter in 1..chapters) {
                writer.write("第${chapter}章 风雪\n")
                repeat(paragraphs) { index ->
                    if (index == 3) writer.write("　　MARKER-ALPHA 这一行用来做搜索验证。\n")
                    writer.write("　　" + paragraph.repeat(3) + "\n")
                }
            }
            writer.flush()
        }
        return file
    }

    @Test
    fun `repeated block reads, pagination, search and reindexing stay bounded`() {
        val file = writeBigNovel()
        val chapters = ChapterIndexer().index(file, gbk).chapters
        assertEquals(20, chapters.size)

        System.gc()
        Thread.sleep(50)
        val before = usedHeap()
        val sampler = HeapSampler().apply { isDaemon = true; start() }

        val random = Random(20260101)
        var blockLoads = 0
        var paginations = 0
        val blockBytes = 8 * 1024

        repeat(150) {
            val chapter = chapters[random.nextInt(chapters.size)]
            val blocks = ChapterBlocks(
                source = FileByteSource(file),
                charset = gbk,
                chapterStart = chapter.startByte,
                chapterEnd = chapter.endByte,
                blockBytes = blockBytes,
            )
            val last = blocks.lastIndex()
            val block = blocks.load(random.nextInt(last + 1))
            blockLoads++

            if (block.text.isNotEmpty()) {
                val paged = TextPaginator.paginate(
                    text = block.text,
                    spec = TextSpec(),
                    widthPx = 720,
                    heightPx = 1200,
                    density = 2.75f,
                )
                paginations++
                // 每次排版都必须完整覆盖这一块，不能因为反复调用而漂移
                assertEquals(0, paged.pages.first().startChar)
                assertEquals(block.text.length, paged.pages.last().endChar)
                assertTrue(paged.pageCount >= 1)
                assertTrue(paged.pages.all { it.bottomPx > it.topPx })
            }
        }

        // 连续搜索三次，每次都要能命中标记行
        repeat(3) {
            val hits = FullTextSearcher(blockBytes = blockBytes).search(
                source = FileByteSource(file),
                charset = gbk,
                chapters = chapters,
                query = "MARKER-ALPHA",
            )
            assertEquals("每一章都有一行标记", 20, hits.size)
        }

        // 重建索引两次，确认不残留状态
        repeat(2) {
            assertEquals(20, ChapterIndexer().index(file, gbk).chapters.size)
        }

        val peak = sampler.stopSampling()
        val peakDelta = peak - before
        println(
            "BENCH 长稳：块读取=$blockLoads 次，排版=$paginations 次，峰值堆增量=" +
                "${"%.1f".format(peakDelta / 1024.0 / 1024.0)} MB",
        )

        assertEquals(150, blockLoads)
        assertTrue("排版次数应当足够多，实际 $paginations", paginations > 100)
        assertTrue(
            "反复读写后峰值堆增量 ${"%.1f".format(peakDelta / 1024.0 / 1024.0)}MB 过大，疑似泄漏",
            peakDelta < 160L * 1024 * 1024,
        )
    }

    @Test
    fun `hundreds of page turns never move the position backwards`() {
        val file = writeBigNovel(chapters = 6, paragraphs = 120)
        val bookId = runBlocking { repo.importFromFile(file) }

        val settings = MutableStateFlow(ReaderSettings())
        val viewModel = ReaderViewModel(repo, bookId, 8 * 1024, settings)
        viewModel.start()
        viewModel.setViewport(720, 1200, 2.75f)

        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        var state = viewModel.state.value
        val deadline = System.currentTimeMillis() + 20_000
        while (state.paged == null && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(5)
            state = viewModel.state.value
        }
        assertTrue("阅读器没加载出来", state.paged != null)

        var previousChapter = state.chapterIdx
        var previousBlock = state.blockIndex
        var previousPercent = -1f
        var turns = 0

        repeat(400) {
            viewModel.nextPage()
            idle()
            // 跨块/跨章是异步读盘，给一点时间；不追求每一次都推进
            var guard = 0
            val current = viewModel.state.value
            while (guard++ < 40 &&
                current.chapterIdx == previousChapter &&
                current.blockIndex == previousBlock &&
                current.pageIndex == state.pageIndex
            ) {
                idle()
                Thread.sleep(2)
            }
            val now = viewModel.state.value
            assertTrue(
                "章节号回退了：$previousChapter -> ${now.chapterIdx}",
                now.chapterIdx >= previousChapter,
            )
            if (now.chapterIdx == previousChapter) {
                assertTrue(
                    "块号回退了：$previousBlock -> ${now.blockIndex}",
                    now.blockIndex >= previousBlock,
                )
            }
            assertTrue(
                "进度回退了：$previousPercent -> ${now.percent}",
                now.percent >= previousPercent - 1e-4f,
            )
            assertTrue(now.pageIndex in 0 until now.pageCount)
            assertTrue(now.chapterIdx in now.chapters.indices)
            assertTrue(now.percent in 0f..1f)

            previousChapter = now.chapterIdx
            previousBlock = now.blockIndex
            previousPercent = now.percent
            state = now
            turns++
        }

        println("BENCH 长稳：连续翻页 $turns 次，最终章节=${state.chapterIdx} 进度=${state.percent}")
        assertEquals(400, turns)
        assertTrue("翻了 400 次应当读到很后面，实际进度 ${state.percent}", state.percent > 0.2f)
        assertTrue("不应当越过全书末尾", state.chapterIdx <= state.chapters.lastIndex)
    }
}

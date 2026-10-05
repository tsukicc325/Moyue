package com.harness.inkreader.ui.reader

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.harness.inkreader.data.BookRepository
import com.harness.inkreader.data.InkDatabase
import com.harness.inkreader.data.settings.ReaderSettings
import com.harness.inkreader.data.settings.ReadingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.charset.Charset
import java.time.Duration

/**
 * M6 端到端验收：全文搜索命中并跳转、书签、长按选中成句→划线笔记、自动翻页、阅读时长落库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderM6FlowRobolectricTest {

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

    private fun importNovel(chapters: Int = 5, paragraphs: Int = 30): Long = runBlocking {
        val text = buildString {
            for (chapter in 1..chapters) {
                append("第").append(chapter).append("章 第").append(chapter).append("节的风雪").append('\n')
                repeat(paragraphs) {
                    append("　　").append(sentence.repeat(2)).append('\n')
                }
            }
        }
        val source = File(temp.newFolder(), "夜航船.txt")
        source.writeBytes(text.toByteArray(gbk))
        repo.importFromFile(source)
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
    fun `scroll track can extend backwards into the previous chapter`() {
        val bookId = runBlocking { importNovel(chapters = 5, paragraphs = 20) }
        val viewModel = open(bookId, settings = ReaderSettings(readingMode = ReadingMode.SCROLL.name))

        // 模拟「从目录跳到第 3 章」：轨道这时只有那一章
        viewModel.goToChapter(2)
        val jumped = await("跳到第 3 章") {
            viewModel.state.value.takeIf { it.chapterIdx == 2 && it.scrollChunks.size == 1 }
        }
        val head = jumped.scrollChunks.first()

        viewModel.prependScrollChunk()
        val extended = await("往上接一块") {
            viewModel.state.value.takeIf {
                it.scrollChunks.size > 1 || it.scrollTrackStartReached
            }
        }

        assertFalse("上面还有内容，不该判定为已到全书开头", extended.scrollTrackStartReached)
        assertEquals("轨道头部应当多出一块", 2, extended.scrollChunks.size)
        val newHead = extended.scrollChunks.first()
        assertTrue("新头部应当来自更前面的章节", newHead.chapterIdx < head.chapterIdx)
        // 与往下接同一套不变式：上一块的 endByte 正好是下一块的 startByte
        assertEquals(
            "往上接的那一块结尾必须正好接住原来的头部",
            head.block.startByte,
            newHead.block.endByte,
        )
    }

    @Test
    fun `scroll track reports when it reaches the very beginning`() {
        val bookId = runBlocking { importNovel(chapters = 2, paragraphs = 10) }
        val viewModel = open(bookId, settings = ReaderSettings(readingMode = ReadingMode.SCROLL.name))

        viewModel.prependScrollChunk()
        val reached = await("识别到全书开头") {
            viewModel.state.value.takeIf { it.scrollTrackStartReached }
        }
        assertEquals("到顶了就不该再变长", 1, reached.scrollChunks.size)
    }

    @Test
    fun `scroll track keeps chunks contiguous so chapters join seamlessly`() {
        val bookId = runBlocking { importNovel(chapters = 4, paragraphs = 30) }
        val viewModel = open(bookId, settings = ReaderSettings(readingMode = ReadingMode.SCROLL.name))

        assertEquals("轨道初始只该有一块", 1, viewModel.state.value.scrollChunks.size)

        repeat(4) {
            // 模拟用户滚到轨道末尾：活跃块跟着可见位置走，窗口才会继续往后接。
            // （不模拟的话窗口会拒绝丢弃「正在读的那一块」，这是有意的保护。）
            viewModel.onScrollPosition(viewModel.state.value.scrollChunks.lastIndex, 0)
            val before = viewModel.state.value.scrollChunks.last().let { it.chapterIdx to it.blockIndex }
            viewModel.appendScrollChunk()
            val after = await("预取下一块") {
                viewModel.state.value.takeIf { state ->
                    state.scrollTrackComplete ||
                        state.scrollChunks.last().let { it.chapterIdx to it.blockIndex } != before
                }
            }
            assertTrue(after.scrollChunks.isNotEmpty())
        }

        val track = viewModel.state.value.scrollChunks
        assertTrue("轨道上应当有多块内容，实际 ${track.size}", track.size >= 2)
        // 这是「跨章无缝」的核心不变式：相邻块在文件里必须首尾相接，中间不能漏内容
        for (index in 0 until track.size - 1) {
            val current = track[index]
            val next = track[index + 1]
            // endByte 是开区间，所以「上一块结束」正好是「下一块开始」
            assertTrue(
                "第 ${index + 1} 块(${current.chapterIdx}章/${current.blockIndex}块 字节" +
                    "${current.block.startByte}-${current.block.endByte}) 与下一块" +
                    "(${next.chapterIdx}章/${next.blockIndex}块 字节${next.block.startByte}) 之间断了",
                next.block.startByte == current.block.endByte,
            )
            assertTrue("章节顺序不应当回退", next.chapterIdx >= current.chapterIdx)
        }
    }

    @Test
    fun `scroll track window is bounded and position follows the visible chunk`() {
        val bookId = runBlocking { importNovel(chapters = 6, paragraphs = 20) }
        val viewModel = open(bookId, settings = ReaderSettings(readingMode = ReadingMode.SCROLL.name))

        repeat(6) {
            // 同样：先假装用户滚到了末尾
            viewModel.onScrollPosition(viewModel.state.value.scrollChunks.lastIndex, 0)
            val before = viewModel.state.value.scrollChunks.last().let { it.chapterIdx to it.blockIndex }
            viewModel.appendScrollChunk()
            val after = await("预取") {
                viewModel.state.value.takeIf { state ->
                    state.scrollTrackComplete ||
                        state.scrollChunks.last().let { it.chapterIdx to it.blockIndex } != before
                }
            }
            assertTrue(after.scrollChunks.isNotEmpty())
        }

        val track = viewModel.state.value.scrollChunks
        assertTrue("轨道窗口必须有上限，实际 ${track.size}", track.size <= 3)
        assertTrue("轨道应当已经往前推进", track.first().chapterIdx > 0)

        // 滚到第二块 → 顶部条/进度/书签用的坐标要跟着切过去
        viewModel.onScrollPosition(1, 0)
        assertEquals(track[1].chapterIdx, viewModel.state.value.chapterIdx)
        assertEquals(track[1].blockIndex, viewModel.state.value.blockIndex)
    }

    @Test
    fun `scroll mode follows the visible page and continues to the next part`() {
        val bookId = runBlocking { importNovel(chapters = 4, paragraphs = 30) }
        val viewModel = open(bookId, settings = ReaderSettings(readingMode = ReadingMode.SCROLL.name))

        val pageCount = viewModel.state.value.paged!!.pageCount
        assertTrue("这个测试需要多页内容，实际 pageCount=$pageCount", pageCount >= 2)

        // 滚动到第 2 页 → 位置跟着更新
        viewModel.onScrollToPage(1)
        assertEquals(1, viewModel.state.value.pageIndex)

        // 越界页码不应当改变状态
        viewModel.onScrollToPage(pageCount + 5)
        assertEquals(1, viewModel.state.value.pageIndex)
        viewModel.onScrollToPage(-1)
        assertEquals(1, viewModel.state.value.pageIndex)

        // 滚到末尾 → 接下一块 / 下一章，并且新位置从第一页开始
        val before = positionOf(viewModel)
        viewModel.continueToNextBlock()
        val after = await("接下一块或下一章") {
            positionOf(viewModel).takeIf { it != before }
        }
        assertEquals("接续后应当从新内容的第一页开始", 0, after.third)
    }

    @Test
    fun `scroll mode does not advance by itself`() {
        val bookId = runBlocking { importNovel(chapters = 3, paragraphs = 20) }
        val viewModel = open(bookId, settings = ReaderSettings(readingMode = ReadingMode.SCROLL.name))

        val before = positionOf(viewModel)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        // 之前有个 bug：列表项被提前组合就触发「自动接下一章」，一打开就自己往前跑
        assertEquals("静置后位置不应当自己变化", before, positionOf(viewModel))
    }

    private fun positionOf(viewModel: ReaderViewModel): Triple<Int, Int, Int> {
        val state = viewModel.state.value
        return Triple(state.chapterIdx, state.blockIndex, state.pageIndex)
    }

    private fun open(
        bookId: Long,
        now: (() -> Long)? = null,
        settings: ReaderSettings = ReaderSettings(),
    ): ReaderViewModel {
        val settingsFlow = MutableStateFlow(settings)
        val viewModel = if (now == null) {
            ReaderViewModel(repo, bookId, 1024, settingsFlow)
        } else {
            ReaderViewModel(repo, bookId, 1024, settingsFlow, now = now)
        }
        viewModel.start()
        viewModel.setViewport(720, 1200, 2.75f)
        await("阅读器加载") { viewModel.state.value.takeIf { !it.loading && it.paged != null } }
        return viewModel
    }

    @Test
    fun `search finds text and jumping lands on the hit`() {
        val bookId = runBlocking { importNovel(chapters = 5, paragraphs = 30) }
        val viewModel = open(bookId)

        viewModel.search("窗前")
        val search = await("搜索完成") { viewModel.state.value.search?.takeIf { it.finished } }

        assertNull("搜索不应当报错：" + search.message, search.message)
        assertTrue("命中应当很多，实际 ${search.hits.size}", search.hits.size >= 100)
        assertFalse(search.running)

        val hit = search.hits[40]
        viewModel.goToHit(hit)
        val landed = await("跳到命中位置") {
            viewModel.state.value.takeIf {
                it.chapterIdx == hit.chapterIdx && it.blockIndex == hit.blockIndex && !it.loading
            }
        }

        val page = landed.paged!!.pages[landed.pageIndex]
        assertTrue(
            "命中处应当落在当前页里：命中 ${hit.charOffsetInBlock}，当前页 [${page.startChar},${page.endChar})",
            hit.charOffsetInBlock >= page.startChar && hit.charOffsetInBlock < page.endChar,
        )
        assertEquals(
            "命中位置的前四个字应当就是搜索词",
            "窗前",
            landed.paged.layout.text.substring(hit.charOffsetInBlock, hit.charOffsetInBlock + 2),
        )
    }

    @Test
    fun `clearing the search drops the results`() {
        val bookId = runBlocking { importNovel(chapters = 2, paragraphs = 10) }
        val viewModel = open(bookId)

        viewModel.search("窗前")
        await("搜索完成") { viewModel.state.value.search?.takeIf { it.finished } }

        viewModel.clearSearch()
        assertNull(viewModel.state.value.search)
    }

    @Test
    fun `bookmark toggles on the current page`() {
        val bookId = runBlocking { importNovel(chapters = 3, paragraphs = 20) }
        val viewModel = open(bookId)

        assertNull("初始没有书签", viewModel.state.value.currentBookmark)

        viewModel.toggleBookmarkHere()
        val bookmarks = await("书签落库") { viewModel.state.value.bookmarks.takeIf { it.isNotEmpty() } }
        assertEquals(1, bookmarks.size)
        assertEquals(viewModel.state.value.chapterIdx, bookmarks.first().chapterIdx)
        assertNotNull("当前页应当被识别为已加书签", viewModel.state.value.currentBookmark)

        viewModel.toggleBookmarkHere()
        await("书签被取消") { viewModel.state.value.bookmarks.takeIf { it.isEmpty() } }
    }

    @Test
    fun `long press selects a whole sentence and it can be annotated`() {
        val bookId = runBlocking { importNovel(chapters = 3, paragraphs = 20) }
        val viewModel = open(bookId)

        val text = viewModel.state.value.paged!!.layout.text.toString()
        val anchor = text.indexOf("他站在窗前")
        assertTrue("测试文本里应当有这句话", anchor >= 0)

        // 界面把长按坐标换算成块内偏移后调用它
        viewModel.selectAt(anchor + 3)

        val selection = viewModel.state.value.selection
        assertNotNull(selection)
        assertTrue("应当选中整句：${selection!!.text}", selection.text.startsWith("他站在窗前"))
        assertTrue("应当选到句末标点：${selection.text}", selection.text.endsWith("。"))
        assertTrue("不应当跨段选一大片", selection.text.length < 120)

        viewModel.saveAnnotation("这段写得真好")
        val annotations = await("划线落库") {
            viewModel.state.value.annotations.takeIf { it.isNotEmpty() }
        }
        assertEquals(selection.text, annotations.first().selectedText)
        assertEquals("这段写得真好", annotations.first().note)
        assertEquals("当前块应当能看到这条划线", 1, viewModel.state.value.annotationsInBlock.size)
        assertNull("保存后应当清掉选中", viewModel.state.value.selection)
    }

    @Test
    fun `highlight without a note is allowed and can be deleted`() {
        val bookId = runBlocking { importNovel(chapters = 2, paragraphs = 10) }
        val viewModel = open(bookId)

        val text = viewModel.state.value.paged!!.layout.text.toString()
        viewModel.selectAt(text.indexOf("他站在窗前") + 1)
        viewModel.saveAnnotation(null)
        val annotation = await("划线落库") {
            viewModel.state.value.annotations.takeIf { it.isNotEmpty() }?.first()
        }
        assertNull(annotation.note)

        viewModel.deleteAnnotation(annotation.id)
        await("划线被删除") { viewModel.state.value.annotations.takeIf { it.isEmpty() } }
    }

    @Test
    fun `reading session is recorded with elapsed time and chars read`() {
        val bookId = runBlocking { importNovel(chapters = 4, paragraphs = 40) }
        // 起点就用「现在」：统计按最近 7 天开窗，用历史时间戳会落到窗口之外
        var clock = System.currentTimeMillis()
        val viewModel = open(bookId, now = { clock })

        // 在同一块内向前翻两页，用来累计字数
        repeat(2) {
            viewModel.nextPage()
            idleMain()
        }
        clock += 60_000L
        viewModel.endSession()

        val stats = await("会话落库") {
            runBlocking { repo.stats(7) }.takeIf { it.totalMillis > 0 }
        }
        assertEquals(60_000L, stats.totalMillis)
        assertTrue("向前翻页应当累计到字数，实际 ${stats.charsLast7Days}", stats.charsLast7Days > 0)
        assertEquals(1, stats.sessionsLast7Days)
    }

    @Test
    fun `very short sessions are not recorded`() {
        val bookId = runBlocking { importNovel(chapters = 2, paragraphs = 10) }
        var clock = System.currentTimeMillis()
        val viewModel = open(bookId, now = { clock })

        clock += 1_000L
        viewModel.endSession()
        idleMain()

        assertEquals(0L, runBlocking { repo.stats(7) }.totalMillis)
    }

    @Test
    fun `auto page turn advances on a timer and stops on toggle`() {
        val bookId = runBlocking { importNovel(chapters = 5, paragraphs = 40) }
        // 自动翻页只作用于「左右翻页」模式；上下滚动的自动滚屏由界面驱动
        val viewModel = open(bookId, settings = ReaderSettings(readingMode = ReadingMode.PAGE.name))

        viewModel.setAutoPageTurnInterval(5)
        viewModel.toggleAutoPageTurn()
        assertTrue(viewModel.state.value.autoPageTurn)

        val before = positionOf(viewModel)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        val after = await("自动翻页推进") {
            viewModel.state.value.takeIf { positionOf(viewModel) != before }
        }
        assertTrue(after.pageIndex > 0 || after.blockIndex > 0)

        viewModel.toggleAutoPageTurn()
        assertFalse(viewModel.state.value.autoPageTurn)
    }

    @Test
    fun `auto page turn does not double-drive scroll mode`() {
        val bookId = runBlocking { importNovel(chapters = 3, paragraphs = 20) }
        val viewModel = open(bookId, settings = ReaderSettings(readingMode = ReadingMode.SCROLL.name))

        viewModel.setAutoPageTurnInterval(5)
        viewModel.toggleAutoPageTurn()
        assertTrue("自动滚屏的开关仍然是开着的", viewModel.state.value.autoPageTurn)

        val before = positionOf(viewModel)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        // 滚动模式的位置由界面滚动驱动，VM 不应当自己翻页
        assertEquals("滚动模式下 VM 不应当自己翻页", before, positionOf(viewModel))
    }

    @Test
    fun `auto page turn interval is clamped to a sane range`() {
        val bookId = runBlocking { importNovel(chapters = 1, paragraphs = 5) }
        val viewModel = open(bookId)

        viewModel.setAutoPageTurnInterval(1)
        assertEquals(5, viewModel.state.value.autoPageTurnIntervalSeconds)
        viewModel.setAutoPageTurnInterval(9_999)
        assertEquals(120, viewModel.state.value.autoPageTurnIntervalSeconds)
    }
}

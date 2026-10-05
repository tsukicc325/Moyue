package com.harness.inkreader.ui.reader

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.harness.inkreader.data.BookRepository
import com.harness.inkreader.data.InkDatabase
import com.harness.inkreader.data.settings.PageTurnMode
import com.harness.inkreader.data.settings.ReaderSettings
import com.harness.inkreader.data.settings.ReaderThemeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

/**
 * 设置变化如何驱动阅读页：
 * - 改排版 → 重新分页，并且仍然停在原来那段文字上；
 * - 改亮度/翻页方式这类**与排版无关**的项目 → 不重新分页（省掉一次昂贵的全章重排）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSettingsFlowRobolectricTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: InkDatabase
    private lateinit var repo: BookRepository
    private val gbk: Charset = Charset.forName("GBK")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = InkDatabase.inMemory(context)
        repo = BookRepository(context, db)
    }

    @After
    fun tearDown() = db.close()

    private fun importNovel(chapters: Int = 4, paragraphs: Int = 40): Long = runBlocking {
        val text = buildString {
            for (chapter in 1..chapters) {
                append("第").append(chapter).append("章 第").append(chapter).append("节的风雪").append('\n')
                repeat(paragraphs) {
                    append("　　").append("他站在窗前看着外面的雪，想起很多年前的旧事。".repeat(2)).append('\n')
                }
            }
        }
        val source = File(temp.newFolder("novel-$chapters-$paragraphs"), "夜航船.txt")
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

    private fun open(settings: MutableStateFlow<ReaderSettings>): Pair<ReaderViewModel, ReaderUiState> {
        val bookId = importNovel()
        val viewModel = ReaderViewModel(repo, bookId, 1024, settings)
        viewModel.start()
        viewModel.setViewport(720, 1200, 2.75f)
        val state = await("阅读器加载") {
            viewModel.state.value.takeIf { !it.loading && it.paged != null }
        }
        return viewModel to state
    }

    @Test
    fun `changing typography repaginates and keeps the reading position`() {
        val settings = MutableStateFlow(ReaderSettings(textSizeSp = 18f))
        val (viewModel, before) = open(settings)

        viewModel.nextPage()
        val positioned = await("翻一页") {
            viewModel.state.value.takeIf { it.pageIndex > 0 || it.blockIndex > 0 }
        }
        val anchorChar = positioned.paged!!.pages[positioned.pageIndex].startChar
        val beforePageCount = positioned.pageCount

        settings.value = settings.value.copy(textSizeSp = 32f)
        val after = await("按新字号重新分页") {
            viewModel.state.value.takeIf { it.spec.textSizeSp == 32f && it.paged !== positioned.paged }
        }

        assertTrue(
            "字号变大后同一块的总页数应当增加：$beforePageCount -> ${after.pageCount}",
            after.pageCount >= beforePageCount,
        )
        val newStart = after.paged!!.pages[after.pageIndex].startChar
        val newEnd = after.paged.pages[after.pageIndex].endChar
        assertTrue(
            "重新分页后应当仍然停在原处附近（原 $anchorChar，新页 [$newStart,$newEnd)）",
            anchorChar >= newStart - 1 && anchorChar < newEnd,
        )
        assertSame("设置对象应当被原样保存进状态", settings.value, after.settings)
    }

    @Test
    fun `changing brightness or page turn mode does not repaginate`() {
        val settings = MutableStateFlow(ReaderSettings())
        val (viewModel, before) = open(settings)
        val pagedBefore = before.paged

        settings.value = settings.value.copy(brightness = 0.35f)
        await("亮度生效") { viewModel.state.value.takeIf { it.settings.brightness == 0.35f } }

        settings.value = settings.value.copy(pageTurnMode = PageTurnMode.COVER.name)
        await("翻页方式生效") {
            viewModel.state.value.takeIf { it.settings.pageTurn == PageTurnMode.COVER }
        }

        settings.value = settings.value.copy(showFooter = false, volumeKeyReversed = true)
        val final = await("页脚开关生效") { viewModel.state.value.takeIf { !it.settings.showFooter } }

        assertSame(
            "只改亮度/翻页方式/页脚，不应该重新分页（旧的排版结果应当被复用）",
            pagedBefore,
            final.paged,
        )
    }

    @Test
    fun `theme change updates the spec colour`() {
        val settings = MutableStateFlow(ReaderSettings(themeId = ReaderThemeId.PAPER.name))
        val (viewModel, _) = open(settings)

        settings.value = settings.value.copy(themeId = ReaderThemeId.NIGHT.name)
        val after = await("主题生效") {
            viewModel.state.value.takeIf { it.settings.theme == ReaderThemeId.NIGHT }
        }

        assertEquals(
            "排版规格里的文字颜色必须跟着主题走，否则夜间模式下会黑字黑底",
            ReaderThemeId.NIGHT.text.toInt(),
            after.spec.textColor,
        )
    }

    @Test
    fun `indent and paragraph spacing changes reach the spec`() {
        val settings = MutableStateFlow(ReaderSettings())
        val (viewModel, _) = open(settings)

        settings.value = settings.value.copy(firstLineIndentEm = 0f, paragraphSpacingEm = 0.9f)
        val after = await("缩进与段距生效") {
            viewModel.state.value.takeIf { it.spec.paragraphSpacingEm == 0.9f }
        }

        assertEquals(0f, after.spec.firstLineIndentEm, 1e-4f)
        assertEquals(0.9f, after.spec.paragraphSpacingEm, 1e-4f)
    }
}

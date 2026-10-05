package com.harness.inkreader.ui.reader

import android.graphics.Bitmap
import com.harness.inkreader.data.settings.ReaderSettings
import com.harness.inkreader.data.settings.ReaderThemeId
import com.harness.inkreader.engine.TextPaginator
import com.harness.inkreader.engine.TextSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 设置 → 排版规格的映射，以及翻页动画的快照捕获。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextSpecsAndCaptureRobolectricTest {

    private val text = buildString {
        repeat(20) { index ->
            append("第").append(index + 1).append("段：他站在窗前看着外面的雪。").append('\n')
        }
    }.trimEnd('\n')

    private fun paged(spec: TextSpec = TextSpec()) =
        TextPaginator.paginate(text, spec, widthPx = 600, heightPx = 300, density = 2.75f)

    @Test
    fun `every typography field is carried from settings into the spec`() {
        val settings = ReaderSettings(
            textSizeSp = 23f,
            lineSpacingMultiplier = 1.9f,
            letterSpacingEm = 0.08f,
            firstLineIndentEm = 1.5f,
            paragraphSpacingEm = 0.7f,
            bold = true,
            justify = true,
            themeId = ReaderThemeId.NIGHT.name,
        )

        val spec = TextSpecs.from(settings, android.graphics.Typeface.MONOSPACE)

        assertEquals(23f, spec.textSizeSp, 1e-4f)
        assertEquals(1.9f, spec.lineSpacingMultiplier, 1e-4f)
        assertEquals(0.08f, spec.letterSpacingEm, 1e-4f)
        assertEquals(1.5f, spec.firstLineIndentEm, 1e-4f)
        assertEquals(0.7f, spec.paragraphSpacingEm, 1e-4f)
        assertTrue(spec.bold)
        assertTrue(spec.justify)
        assertEquals(android.graphics.Typeface.MONOSPACE, spec.typeface)
    }

    @Test
    fun `text colour follows the selected theme`() {
        val paper = TextSpecs.from(
            ReaderSettings(themeId = ReaderThemeId.PAPER.name),
            android.graphics.Typeface.SERIF,
        )
        val night = TextSpecs.from(
            ReaderSettings(themeId = ReaderThemeId.NIGHT.name),
            android.graphics.Typeface.SERIF,
        )

        assertEquals(ReaderThemeId.PAPER.text.toInt(), paper.textColor)
        assertEquals(ReaderThemeId.NIGHT.text.toInt(), night.textColor)
        assertTrue(paper.textColor != night.textColor)
    }

    @Test
    fun `captured page has the requested size and is not empty`() {
        val pagedText = paged()
        val bitmap = PageTurns.capturePage(
            paged = pagedText,
            pageIndex = 0,
            widthPx = 600,
            heightPx = 300,
            backgroundArgb = 0xFFFAF7F0.toInt(),
        )

        assertNotNull(bitmap)
        assertEquals(600, bitmap!!.width)
        assertEquals(300, bitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        // 左上角应当是背景色（正文有内缩进，不会画到最左上）
        assertEquals(0xFFFAF7F0.toInt(), bitmap.getPixel(0, 0))
    }

    @Test
    fun `capturing an out of range page or zero size returns null instead of crashing`() {
        val pagedText = paged()

        assertNull(PageTurns.capturePage(pagedText, 999, 600, 300, 0))
        assertNull(PageTurns.capturePage(pagedText, 0, 0, 300, 0))
        assertNull(PageTurns.capturePage(pagedText, 0, 600, 0, 0))
    }

    @Test
    fun `the snapshot of the second page differs from the first`() {
        val pagedText = paged(TextSpec(textSizeSp = 30f))
        assertTrue("这段文字至少要分出两页，否则测不到翻页", pagedText.pageCount >= 2)

        val first = PageTurns.capturePage(pagedText, 0, 600, 300, 0xFFFFFFFF.toInt())!!
        val second = PageTurns.capturePage(pagedText, 1, 600, 300, 0xFFFFFFFF.toInt())!!

        assertTrue(
            "不同页的快照不该完全一样",
            !first.sameAs(second),
        )
    }
}

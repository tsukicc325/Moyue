package com.harness.inkreader.engine

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 段间距加在哪一行：只有**段落最后一行**才允许加高。
 *
 * 这条规则如果写错（例如对每一行都加），正文会变成「每行都有大空隙」—— 这是肉眼一眼能看出、
 * 但换行相关的自动测试又抓不到的错误（Robolectric 不换行），所以在这里用合成的行区间直接验证判断逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParagraphSpacingSpanTest {

    private val span = ParagraphSpacingSpan(extraPx = 30)

    private fun metrics(): Paint.FontMetricsInt = Paint.FontMetricsInt().apply {
        ascent = -40
        descent = 10
        top = -45
        bottom = 15
    }

    private fun applyTo(text: String, start: Int, end: Int): Paint.FontMetricsInt {
        val fm = metrics()
        span.chooseHeight(text, start, end, 0, 0, fm)
        return fm
    }

    @Test
    fun `a line in the middle of a paragraph is not enlarged`() {
        val text = "ABCDEF\nGH"
        // 行区间 (0,6)，结尾字符是 F，不是换行 —— 这是被折行切出来的中间行
        val fm = applyTo(text, 0, 6)

        assertEquals("段中行不应当加高", 10, fm.descent)
        assertEquals("段中行不应当加高", 15, fm.bottom)
    }

    @Test
    fun `a line ending with a newline is enlarged`() {
        // 注意 StaticLayout 的行区间是**包含换行符**的：行 "GH\n" 的区间是 [6,10)
        val text = "ABCDEF\nGH\n"
        val fm = applyTo(text, 6, 10)

        assertEquals(40, fm.descent)
        assertEquals(45, fm.bottom)
    }

    @Test
    fun `the last line of the text is enlarged even without a trailing newline`() {
        val text = "ABCDEF\nGH"
        val fm = applyTo(text, 6, 9)

        assertEquals(40, fm.descent)
        assertEquals(45, fm.bottom)
    }

    @Test
    fun `ascent and top are untouched`() {
        val text = "ABCDEF\nGH\n"
        val fm = applyTo(text, 6, 10)

        assertEquals("只抬 descent/bottom，不动 ascent/top", -40, fm.ascent)
        assertEquals(-45, fm.top)
    }

    @Test
    fun `an empty line range does not crash and is not enlarged`() {
        val text = "ABCDEF\nGH"
        val fm = applyTo(text, 5, 5)

        assertEquals(10, fm.descent)
    }
}

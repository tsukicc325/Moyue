package com.harness.inkreader.engine

import android.text.Spanned
import android.text.style.LeadingMarginSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 分页器与 Android 排版 API 的集成测试。
 *
 * 开 [GraphicsMode.Mode.NATIVE] 是关键：legacy 图形模式下字体测量会退化（不折行），
 * 「页高变小页数不变少」这类断言会永远成立、等于没测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextPaginatorRobolectricTest {

    private val body = buildString {
        repeat(60) { index ->
            append("第")
            append(index + 1)
            append("段：夜色像一匹浸了水的绸子，沉沉地压在江面上。")
            append('\n')
        }
    }.trimEnd('\n')

    private fun paginate(
        text: CharSequence = body,
        spec: TextSpec = TextSpec(),
        widthPx: Int = 720,
        heightPx: Int = 1_200,
    ) = TextPaginator.paginate(text, spec, widthPx, heightPx, density = 2.75f)

    @Test
    fun `paginate covers the whole text exactly once`() {
        val paged = paginate()
        assertTrue(paged.pageCount >= 1)
        assertEquals(0, paged.pages.first().startChar)
        assertEquals(body.length, paged.pages.last().endChar)

        for (i in 1 until paged.pageCount) {
            assertEquals(paged.pages[i - 1].endChar, paged.pages[i].startChar)
            assertTrue(paged.pages[i].topPx >= paged.pages[i - 1].topPx)
        }
        assertTrue(paged.pages.all { it.startChar < it.endChar })
    }

    @Test
    fun `smaller page height never yields fewer pages`() {
        val tall = paginate(heightPx = 1_200)
        val short = paginate(heightPx = 400)
        assertTrue(short.pageCount >= tall.pageCount)
    }

    @Test
    fun `first line indent is applied to every non-empty paragraph only`() {
        val paged = paginate()
        val spans = (paged.layout.text as Spanned)
            .getSpans(0, paged.layout.text.length, LeadingMarginSpan::class.java)
        assertEquals(60, spans.size)
    }

    @Test
    fun `indent can be disabled`() {
        val paged = paginate(spec = TextSpec(firstLineIndentEm = 0f))
        // 关闭缩进时返回的是原始 CharSequence（不是 Spanned），因此没有 span 可查。
        val spans = (paged.layout.text as? Spanned)
            ?.getSpans(0, paged.layout.text.length, LeadingMarginSpan::class.java)
        assertEquals(0, spans?.size ?: 0)
    }

    @Test
    fun `empty text yields one degenerate page instead of crashing`() {
        val paged = paginate(text = "")
        assertEquals(1, paged.pageCount)
        assertEquals(0, paged.pages[0].startChar)
        assertEquals(0, paged.pages[0].endChar)
    }
}

package com.harness.inkreader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 切页数学的穷举测试。纯 JVM，不需要 Robolectric，也不依赖真实字体测量。
 */
class PageSplitterTest {

    @Test
    fun `no lines yields one page covering whole text`() {
        val pages = PageSplitter.split(IntArray(0), IntArray(0), 100, 42)
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].startChar)
        assertEquals(42, pages[0].endChar)
        assertEquals(0, pages[0].topPx)
    }

    @Test
    fun `single line yields single page`() {
        val pages = PageSplitter.split(intArrayOf(0), intArrayOf(40), 100, 10)
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].topPx)
        assertEquals(40, pages[0].bottomPx)
    }

    @Test
    fun `lines that exactly fill the page stay on one page`() {
        // 30 + 30 + 40 = 100，正好等于页高，不应切页
        val pages = PageSplitter.split(
            lineStarts = intArrayOf(0, 10, 20),
            lineHeights = intArrayOf(30, 30, 40),
            pageHeightPx = 100,
            textLength = 30,
        )
        assertEquals(1, pages.size)
    }

    @Test
    fun `one pixel over the page height starts a new page`() {
        // 30 + 30 + 41 = 101 > 100，第三行必须另起一页
        val pages = PageSplitter.split(
            lineStarts = intArrayOf(0, 10, 20),
            lineHeights = intArrayOf(30, 30, 41),
            pageHeightPx = 100,
            textLength = 30,
        )
        assertEquals(2, pages.size)
        assertEquals(0, pages[0].startChar)
        assertEquals(20, pages[0].endChar)
        assertEquals(20, pages[1].startChar)
        assertEquals(30, pages[1].endChar)
        assertEquals(60, pages[1].topPx)
    }

    @Test
    fun `line taller than the whole page still occupies exactly one page`() {
        // 防死循环：第一行 500px 塞进 100px 的页，它独占一页且不产生空页；
        // 后面两个 20px 的行仍然合并到第二页。
        val pages = PageSplitter.split(
            lineStarts = intArrayOf(0, 10, 20),
            lineHeights = intArrayOf(500, 20, 20),
            pageHeightPx = 100,
            textLength = 30,
        )
        assertEquals(2, pages.size)
        assertEquals(500, pages[0].bottomPx)
        assertEquals(500, pages[1].topPx)
        assertEquals(540, pages[1].bottomPx)
        assertTrue(pages.all { it.startChar < it.endChar })
    }

    @Test
    fun `larger line heights never reduce the page count`() {
        val starts = IntArray(200) { it * 12 }
        val small = PageSplitter.split(starts, IntArray(200) { 22 }, 800, 200 * 12)
        val large = PageSplitter.split(starts, IntArray(200) { 44 }, 800, 200 * 12)
        assertTrue(large.size >= small.size)
    }

    @Test
    fun `invariants hold across randomized layouts`() {
        val random = Random(20261001)
        repeat(2_000) { case ->
            val lineCount = random.nextInt(0, 400)
            val lineStarts = IntArray(lineCount)
            val lineHeights = IntArray(lineCount)
            var cursor = 0
            for (line in 0 until lineCount) {
                lineStarts[line] = cursor
                cursor += random.nextInt(1, 40)
                lineHeights[line] = random.nextInt(1, 120)
            }
            val textLength = cursor + random.nextInt(0, 40)
            val pageHeight = random.nextInt(1, 1_500)

            val pages = PageSplitter.split(lineStarts, lineHeights, pageHeight, textLength)

            assertTrue("case $case: no pages", pages.isNotEmpty())
            assertEquals("case $case: first page must start at 0", 0, pages[0].startChar)
            assertEquals("case $case: last page must cover text", textLength, pages.last().endChar)

            // 没有行可排版时（空文本等）会得到一个零高度的退化页，不参与下面的高度断言
            if (lineCount == 0) return@repeat
            val maxLineHeight = lineHeights.maxOrNull() ?: 0

            for (i in pages.indices) {
                val page = pages[i]
                assertTrue("case $case: page $i is empty", page.startChar < page.endChar)
                // 这条断言就是用来抓「相对高度 / 绝对偏移混用」那类 bug 的
                assertTrue(
                    "case $case: page $i has non-positive height (top=${page.topPx} bottom=${page.bottomPx})",
                    page.bottomPx > page.topPx,
                )
                assertTrue(
                    "case $case: page $i is taller than a page can be " +
                        "(height=${page.bottomPx - page.topPx} pageHeight=$pageHeight maxLine=$maxLineHeight)",
                    page.bottomPx - page.topPx <= maxOf(pageHeight, maxLineHeight),
                )
                if (i > 0) {
                    assertEquals(
                        "case $case: page $i must start where page ${i - 1} ended",
                        pages[i - 1].endChar,
                        page.startChar,
                    )
                    assertEquals(
                        "case $case: page $i top must equal previous bottom",
                        pages[i - 1].bottomPx,
                        page.topPx,
                    )
                }
            }
        }
    }
}

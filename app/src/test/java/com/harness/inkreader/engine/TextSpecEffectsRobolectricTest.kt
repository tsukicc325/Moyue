package com.harness.inkreader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 排版规格对实际布局的影响。
 *
 * 必须开 [GraphicsMode.Mode.NATIVE]：默认的 legacy 图形模式虽然能算出非零行高，
 * 但**不做真正的换行**（900px 与 320px 宽度得到相同行数），会让这里所有关于折行的断言
 * 变成「永远成立的空断言」。开原生图形后测量与折行都是真的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextSpecEffectsRobolectricTest {

    private val paragraphs = 12

    private val LETTER_PROBE = "他站在窗前看着外面的雪"

    private val text: String = buildString {
        repeat(paragraphs) { index ->
            append("第").append(index + 1).append("段：")
            append("他站在窗前看着外面的雪，想起很多年前的旧事。".repeat(3))
            append('\n')
        }
    }.trimEnd('\n')

    private fun paginate(
        spec: TextSpec,
        widthPx: Int = 720,
        heightPx: Int = 2_000,
    ): PagedText = TextPaginator.paginate(text, spec, widthPx, heightPx, density = 2.75f)

    private fun lineHeightSum(paged: PagedText): Int =
        (0 until paged.layout.lineCount).sumOf {
            paged.layout.getLineBottom(it) - paged.layout.getLineTop(it)
        }

    @Test
    fun `measurement probe`() {
        val paged = paginate(TextSpec())
        val lines = paged.layout.lineCount
        val height = paged.layout.height
        val firstLineWidth = paged.layout.getLineWidth(0)
        println(
            "BENCH 排版测量探针：行数=$lines 布局高度=$height 首行宽度=$firstLineWidth " +
                "页数=${paged.pageCount} 行高合计=${lineHeightSum(paged)}"
        )
        assertTrue("布局必须有行", lines > 0)
    }

    @Test
    fun `paragraph spacing only grows the layout`() {
        val plain = paginate(TextSpec(paragraphSpacingEm = 0f))
        val spaced = paginate(TextSpec(paragraphSpacingEm = 0.8f))

        println("BENCH 段距 0 -> 高度=${plain.layout.height}；段距 0.8 -> 高度=${spaced.layout.height}")

        assertTrue("段距不应当减少页数", spaced.pageCount >= plain.pageCount)
        if (plain.layout.height > 0) {
            assertTrue(
                "段距 0.8em 应当让布局变高：${plain.layout.height} -> ${spaced.layout.height}",
                spaced.layout.height > plain.layout.height,
            )
        }
    }

    @Test
    fun `the paint is built exactly from the spec`() {
        val spec = TextSpec(
            textSizeSp = 22f,
            letterSpacingEm = 0.25f,
            bold = true,
            typeface = android.graphics.Typeface.MONOSPACE,
            textColor = 0xFF123456.toInt(),
        )

        val paint = TextPaginator.paintFor(spec, density = 2f)

        assertEquals("字号要按 density 换算成像素", 44f, paint.textSize, 1e-3f)
        assertEquals(0.25f, paint.letterSpacing, 1e-6f)
        assertEquals(0xFF123456.toInt(), paint.color)
        assertTrue("必须开抗锯齿，否则中文边缘很难看", paint.isAntiAlias)
        assertTrue("中文需要亚像素定位", paint.isSubpixelText)
    }

    @Test
    fun `letter spacing either widens the layout or the environment cannot do it`() {
        // 先判断本环境是否把 letterSpacing 用在了**布局测量**上。
        // 实测：Paint.measureText 会变宽（550 -> 800），但 StaticLayout 的折行不变，
        // 所以字距的最终视觉效果只能靠真机确认；这里只保证「不会让排版缩水」。
        val plain = android.text.TextPaint().apply { textSize = 50f }
        val spaced = android.text.TextPaint().apply {
            textSize = 50f
            letterSpacing = 0.5f
        }
        val plainWidth = plain.measureText(LETTER_PROBE)
        val spacedWidth = spaced.measureText(LETTER_PROBE)
        println("BENCH letterSpacing 探针：0 -> $plainWidth，0.5 -> $spacedWidth")

        val tight = paginate(TextSpec(letterSpacingEm = 0f))
        val loose = paginate(TextSpec(letterSpacingEm = 0.2f))
        println("BENCH 字距 0 -> 行数=${tight.layout.lineCount}；字距 0.2 -> 行数=${loose.layout.lineCount}")

        assertTrue("加字距不应当让行数变少", loose.layout.lineCount >= tight.layout.lineCount)

        if (spacedWidth > plainWidth && loose.layout.lineCount == tight.layout.lineCount) {
            println(
                "BENCH 结论：本环境 measureText 认字距、但 StaticLayout 折行不认 —— " +
                    "字距效果需真机验证（画笔设置本身已由上一条测试覆盖）"
            )
        }
    }

    @Test
    fun `bigger font size or line spacing never shrinks the content`() {
        val base = paginate(TextSpec(textSizeSp = 18f, lineSpacingMultiplier = 1.5f))
        val biggerFont = paginate(TextSpec(textSizeSp = 26f, lineSpacingMultiplier = 1.5f))
        val looserLines = paginate(TextSpec(textSizeSp = 18f, lineSpacingMultiplier = 2.4f))

        assertTrue(biggerFont.pageCount >= base.pageCount)
        assertTrue(looserLines.pageCount >= base.pageCount)
        if (base.layout.height > 0) {
            assertTrue(looserLines.layout.height >= base.layout.height)
        }
    }

    @Test
    fun `paragraph spacing adds a span and can be turned off`() {
        val withoutSpacing = paginate(TextSpec(firstLineIndentEm = 0f, paragraphSpacingEm = 0f))
        assertEquals("两项都为 0 时不应当产生 span", false, withoutSpacing.layout.text is android.text.Spanned)

        val withIndent = paginate(TextSpec(firstLineIndentEm = 2f, paragraphSpacingEm = 0f))
        val spans = (withIndent.layout.text as android.text.Spanned)
            .getSpans(0, withIndent.layout.text.length, android.text.style.LeadingMarginSpan::class.java)
        assertEquals(paragraphs, spans.size)
    }

    @Test
    fun `justify does not break pagination`() {
        val justified = paginate(TextSpec(justify = true))
        assertEquals(0, justified.pages.first().startChar)
        assertEquals(text.length, justified.pages.last().endChar)
        assertTrue(justified.pageCount >= 1)
    }

    @Test
    fun `padding changes the available width and therefore the line count`() {
        val wide = paginate(TextSpec(), widthPx = 900)
        val narrow = paginate(TextSpec(), widthPx = 320)

        println("BENCH 宽度 900 -> 行数=${wide.layout.lineCount}；宽度 320 -> 行数=${narrow.layout.lineCount}")

        assertTrue(
            "更窄的排版宽度应当产生更多的行：${wide.layout.lineCount} -> ${narrow.layout.lineCount}",
            narrow.layout.lineCount > wide.layout.lineCount,
        )
        assertTrue(
            "窄宽度下每行能放的字更少",
            narrow.layout.getLineEnd(0) < wide.layout.getLineEnd(0),
        )
    }
}

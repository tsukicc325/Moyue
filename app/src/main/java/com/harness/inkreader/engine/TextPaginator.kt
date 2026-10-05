package com.harness.inkreader.engine

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan

/**
 * 排版规格。任何一项变化都会导致重新分页。
 */
data class TextSpec(
    val textSizeSp: Float = 18f,
    val lineSpacingMultiplier: Float = 1.5f,
    val letterSpacingEm: Float = 0f,
    val firstLineIndentEm: Float = 2f,
    /** 段间距（em）。实现方式是在段落最后一行的行高上加余量。 */
    val paragraphSpacingEm: Float = 0f,
    val typeface: Typeface = Typeface.SERIF,
    val bold: Boolean = false,
    val justify: Boolean = false,
    val textColor: Int = 0xFF2A2A2A.toInt(),
)

/** 一页覆盖的字符区间（左闭右开）与它在排版中的纵向像素位置。 */
data class PageRange(
    val startChar: Int,
    val endChar: Int,
    val topPx: Int,
    val bottomPx: Int,
)

/**
 * 一次分页的结果。
 *
 * 关键设计：整块文字只建 **一个** StaticLayout，翻页时靠平移画布取不同纵向区间来显示。
 * 这样首行缩进、段落样式、两端对齐都天然正确；如果改成「每页单独建 layout」，
 * 页首若落在段落中间就会错误地多出一个首行缩进。
 */
class PagedText(
    val layout: StaticLayout,
    val pages: List<PageRange>,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
) {
    val pageCount: Int get() = pages.size

    fun pageAt(index: Int): PageRange? = pages.getOrNull(index)
}

/**
 * 切页数学。刻意与 Android 排版 API 解耦：输入只是「每行起始字符下标」与「每行像素高度」，
 * 因此可以脱离真实字体测量做穷举测试（Robolectric 下字体测量是不可靠的）。
 */
internal object PageSplitter {

    fun split(
        lineStarts: IntArray,
        lineHeights: IntArray,
        pageHeightPx: Int,
        textLength: Int,
    ): List<PageRange> {
        require(lineStarts.size == lineHeights.size) { "lineStarts/lineHeights size mismatch" }
        require(pageHeightPx > 0) { "pageHeightPx must be > 0" }

        if (lineStarts.isEmpty()) {
            return listOf(PageRange(0, textLength, 0, 0))
        }

        val pages = ArrayList<PageRange>(lineStarts.size / 16 + 2)
        var pageStartLine = 0
        // pageTopPx 是**绝对**纵向偏移（第一行顶部为 0），consumedPx 是本页已占高度。
        // 两者绝不能混用：早期版本把相对高度当成绝对偏移，导致第 3 页起取错文字区间。
        var pageTopPx = 0
        var consumedPx = 0

        for (line in lineStarts.indices) {
            val lineHeight = lineHeights[line]

            // consumedPx > 0 保证每页至少有一行：遇到比整页还高的行时也不会切出空页。
            if (consumedPx > 0 && consumedPx + lineHeight > pageHeightPx) {
                pages.add(
                    PageRange(
                        startChar = lineStarts[pageStartLine],
                        endChar = lineStarts[line],
                        topPx = pageTopPx,
                        bottomPx = pageTopPx + consumedPx,
                    )
                )
                pageStartLine = line
                pageTopPx += consumedPx
                consumedPx = 0
            }
            consumedPx += lineHeight
        }

        pages.add(
            PageRange(
                startChar = lineStarts[pageStartLine],
                endChar = textLength,
                topPx = pageTopPx,
                bottomPx = pageTopPx + consumedPx,
            )
        )
        return pages
    }
}

object TextPaginator {

    /**
     * 把一段文字按给定尺寸切页。
     *
     * [text] 应当是一个**有界的块**（一章，或约 2 万字的虚拟块），不要传整本书，
     * 否则 StaticLayout 本身就会吃掉大量内存。
     */
    @SuppressLint("WrongConstant")
    fun paginate(
        text: CharSequence,
        spec: TextSpec,
        widthPx: Int,
        heightPx: Int,
        density: Float,
    ): PagedText {
        require(widthPx > 0) { "widthPx must be > 0" }
        require(heightPx > 0) { "heightPx must be > 0" }

        val paint = paintFor(spec, density)

        val styled = applyParagraphSpans(
            text = text,
            indentPx = spec.firstLineIndentEm * spec.textSizeSp * density,
            paragraphSpacingPx = (spec.paragraphSpacingEm * spec.textSizeSp * density).toInt(),
        )

        val builder = StaticLayout.Builder
            .obtain(styled, 0, styled.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spec.lineSpacingMultiplier)
            .setIncludePad(false)
            // Layout.BREAK_STRATEGY_* / JUSTIFICATION_MODE_* 与 LineBreaker 里的同名常量
            // 是同一个值；LineBreaker 需要 API 29，而这里是 minSdk 26 的工程，
            // 所以用 Layout 上的别名。lint 的 WrongConstant 在这两条上是误报。
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)

        if (spec.justify) {
            builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }

        val layout = builder.build()

        val lineCount = layout.lineCount
        val lineStarts = IntArray(lineCount)
        val lineHeights = IntArray(lineCount)
        for (line in 0 until lineCount) {
            lineStarts[line] = layout.getLineStart(line)
            lineHeights[line] = layout.getLineBottom(line) - layout.getLineTop(line)
        }

        val pages = PageSplitter.split(
            lineStarts = lineStarts,
            lineHeights = lineHeights,
            pageHeightPx = heightPx,
            textLength = text.length,
        )

        return PagedText(
            layout = layout,
            pages = pages,
            contentWidthPx = widthPx,
            contentHeightPx = heightPx,
        )
    }

    /**
     * 排版用的画笔。抽成独立函数是为了让「设置 → 画笔」这层接线可以被直接断言，
     * 而不是只能靠肉眼看渲染结果。
     */
    internal fun paintFor(spec: TextSpec, density: Float): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = spec.textSizeSp * density
            typeface = if (spec.bold) {
                Typeface.create(spec.typeface, Typeface.BOLD)
            } else {
                spec.typeface
            }
            letterSpacing = spec.letterSpacingEm
            color = spec.textColor
            isSubpixelText = true
        }

    /**
     * 给每个非空段落加首行缩进，并在段落最后一行加段间距。
     *
     * 段间距用 [LineHeightSpan] 实现：静态排版里「行」是唯一的纵向单位，把段末行的
     * descent/bottom 抬高，就等于在段与段之间留出空隙 —— 这比插入空行干净，
     * 因为空行会混进正文、影响按字符偏移定位。
     */
    private fun applyParagraphSpans(
        text: CharSequence,
        indentPx: Float,
        paragraphSpacingPx: Int,
    ): CharSequence {
        val indent = indentPx.toInt()
        val needsIndent = indent > 0
        val needsSpacing = paragraphSpacingPx > 0
        if ((!needsIndent && !needsSpacing) || text.isEmpty()) return text

        val spannable = SpannableString(text)
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        if (needsSpacing) {
            spannable.setSpan(
                ParagraphSpacingSpan(paragraphSpacingPx),
                0,
                text.length,
                flag,
            )
        }

        if (needsIndent) {
            var paragraphStart = 0
            for (i in 0..text.length) {
                val atEnd = i == text.length
                if (atEnd || text[i] == '\n') {
                    if (i > paragraphStart) {
                        spannable.setSpan(
                            LeadingMarginSpan.Standard(indent, 0),
                            paragraphStart,
                            i,
                            flag,
                        )
                    }
                    paragraphStart = i + 1
                }
            }
        }
        return spannable
    }
}

/**
 * 只在**段落最后一行**下方加空隙。
 *
 * `chooseHeight` 会带着每一行的区间被调用；行区间末尾是换行符（或已是文末）就说明
 * 这是段落末行，此时抬高 descent 与 bottom。
 */
internal class ParagraphSpacingSpan(private val extraPx: Int) : LineHeightSpan {

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanStartVertical: Int,
        spanEndVertical: Int,
        fontMetricsInt: Paint.FontMetricsInt,
    ) {
        val isParagraphEnd = end >= text.length || (end > start && text[end - 1] == '\n')
        if (!isParagraphEnd) return
        fontMetricsInt.descent += extraPx
        fontMetricsInt.bottom += extraPx
    }
}

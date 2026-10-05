package com.harness.inkreader.engine

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 一个可以安全放进内存、交给 StaticLayout 排版的文本块。 */
data class TextBlock(
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val text: String,
    val atChapterStart: Boolean,
    val atChapterEnd: Boolean,
) {
    val byteLength: Long get() = (endByte - startByte).coerceAtLeast(0)
    val isEmpty: Boolean get() = text.isEmpty()

    /** 把块内字符比例换算成块内字节偏移，用于估算全书进度。 */
    fun byteOffsetOfChar(charOffsetInBlock: Int): Long {
        if (text.isEmpty()) return startByte
        val ratio = charOffsetInBlock.toDouble() / text.length.toDouble()
        return startByte + (byteLength * ratio.coerceIn(0.0, 1.0)).toLong()
    }
}

/**
 * 章节的分块读取器。
 *
 * ## 为什么章节也要分块
 * 「100MB 只有 5 章」意味着单章 20MB，整体读进内存必然 OOM；因此章节内部再切成
 * [blockBytes] 大小的块，一次只把一块交给 StaticLayout。
 *
 * ## 分块规则是确定性的
 * 第 k 块从 `chapterStart + k * blockBytes` 开始，**向前吸附到最近的换行之后**，
 * 块尾就是第 k+1 块的起点。这样：
 * - 前后翻页都能 O(1) 算出相邻块边界，不需要从头遍历；
 * - 同一个文件每次算出的块边界完全一致，所以保存下来的阅读位置永远有效。
 *
 * 吸附只会把块首往后推、不会往前，因此 [indexAt] 的修正循环必然收敛。
 *
 * 读取走 [ByteSource]，因此「复制进私有目录」与「只引用原文件」两种模式共用同一套逻辑。
 */
class ChapterBlocks(
    private val source: ByteSource,
    private val charset: Charset,
    private val chapterStart: Long,
    private val chapterEnd: Long,
    private val blockBytes: Int = DEFAULT_BLOCK_BYTES,
) {

    private val asciiSafe = Charsets.supportsByteLevelNewlineScan(charset)
    private val sourceLength = source.length

    init {
        require(blockBytes in 1_024..MAX_BLOCK_BYTES) { "blockBytes 超出允许范围: $blockBytes" }
    }

    /** 第 [index] 块的起始字节偏移（已吸附到行首）。 */
    fun startOf(index: Int): Long {
        if (index <= 0) return chapterStart
        val raw = chapterStart + index.toLong() * blockBytes
        if (raw >= chapterEnd) return chapterEnd
        return snapForward(raw)
    }

    /** 包含 [byteOffset] 的块号。 */
    fun indexAt(byteOffset: Long): Int {
        if (byteOffset <= chapterStart) return 0
        val clamped = byteOffset.coerceAtMost((chapterEnd - 1).coerceAtLeast(chapterStart))
        var guess = ((clamped - chapterStart) / blockBytes).toInt().coerceAtLeast(0)
        while (guess > 0 && startOf(guess) > clamped) guess--
        return guess
    }

    /** 下一个块号；已经到了章末返回 -1。 */
    fun nextIndex(index: Int): Int {
        val next = index + 1
        return if (startOf(next) < chapterEnd) next else -1
    }

    /** 上一个块号；已经在第一块返回 -1。 */
    fun previousIndex(index: Int): Int = if (index > 0) index - 1 else -1

    /** 最后一个有效块号。从估算值往回收敛，用于「跳到本章最后一块」。 */
    fun lastIndex(): Int {
        var candidate = (((chapterEnd - chapterStart) / blockBytes).toInt()) + 1
        while (candidate > 0 && startOf(candidate) >= chapterEnd) candidate--
        return candidate.coerceAtLeast(0)
    }

    fun load(index: Int): TextBlock {
        val start = startOf(index)
        val rawEnd = startOf(index + 1).coerceAtMost(chapterEnd)
        val cappedEnd = if (sourceLength > 0L) rawEnd.coerceAtMost(sourceLength) else rawEnd
        val wanted = (cappedEnd - start).toInt().coerceIn(0, MAX_BLOCK_BYTES)
        val bytes = if (wanted <= 0) ByteArray(0) else source.readAt(start, wanted)
        val text = if (bytes.isEmpty()) "" else decode(bytes)
        val end = start + bytes.size
        return TextBlock(
            index = index,
            startByte = start,
            endByte = end,
            text = text,
            atChapterStart = start <= chapterStart,
            atChapterEnd = end >= chapterEnd,
        )
    }

    /**
     * 从 [offset] 起向前找到第一个换行之后的位置。
     * UTF-16/32 无法按字节找换行，直接原样返回（导入时已转码为 UTF-8，因此这条路径极少走到）。
     */
    private fun snapForward(offset: Long): Long {
        if (!asciiSafe) return offset
        if (offset <= chapterStart) return chapterStart
        val window = source.readAt(offset, SNAP_WINDOW)
        for (i in window.indices) {
            val byte = window[i].toInt()
            if (byte == BYTE_LF || byte == BYTE_CR) return offset + i + 1
        }
        return offset
    }

    private fun decode(bytes: ByteArray): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val chars = CharBuffer.allocate(bytes.size + 1)
        decoder.decode(ByteBuffer.wrap(bytes), chars, true)
        chars.flip()
        var text = chars.toString()
        // 块首可能落在字符中间（无换行可吸附时），或撞上文件头的 BOM
        if (text.isNotEmpty() && (text[0] == '\uFFFD' || text[0] == '\uFEFF')) {
            text = text.substring(1)
        }
        return text
    }

    companion object {
        /** 32KB ≈ 1.6 万汉字，一整块的 StaticLayout 只占几百 KB。 */
        const val DEFAULT_BLOCK_BYTES = 32 * 1024

        private const val MAX_BLOCK_BYTES = 4 * 1024 * 1024
        private const val SNAP_WINDOW = 4 * 1024
        private const val BYTE_LF = 0x0A
        private const val BYTE_CR = 0x0D
    }
}

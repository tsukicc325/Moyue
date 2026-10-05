package com.harness.inkreader.engine

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 索引出来的章节。[startByte]/[endByte] 是**精确**的文件字节偏移。 */
data class IndexedChapter(
    val idx: Int,
    val title: String,
    val startByte: Long,
    val endByte: Long,
) {
    val byteLength: Long get() = (endByte - startByte).coerceAtLeast(0)
}

data class IndexProgress(
    val bytesScanned: Long,
    val totalBytes: Long,
    val chaptersFound: Int,
    val currentTitle: String?,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (bytesScanned.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

data class IndexResult(
    val chapters: List<IndexedChapter>,
    val totalBytes: Long,
    val charset: Charset,
    /** true 表示没有识别到足够章节，改用固定字数的虚拟分章。 */
    val usedVirtualChapters: Boolean,
    val elapsedMillis: Long,
    val linesScanned: Long,
)

class IndexCancelledException : RuntimeException("章节索引已取消")

/**
 * 流式章节索引器。
 *
 * ## 为什么快且内存有界
 *
 * - 全程只保留一个 [bufferSize] 的字节缓冲，**从不把文件读成字符串**。
 * - 章节起始偏移来自「在字节流里找换行」。对 UTF-8 / GBK / GB18030 / Big5 而言，
 *   `0x0A`(LF) 与 `0x0D`(CR) **不可能**出现在多字节字符的后续字节里
 *   （UTF-8 续字节是 0x80–0xBF；GBK/GB18030 尾字节是 0x40–0xFE 且第 2/4 字节是 0x30–0x39），
 *   所以字节级找换行是精确的，章节偏移**零成本**得到，不需要回写重编码去推算。
 * - 章节标题必然是很短的一行，因此先用**字节长度**过滤（≤120 字节），
 *   只对极少数候选行做解码与正则匹配。100MB 的正文段落一个字节都不会被解码。
 * - 超过缓冲大小的超长行（例如整本没有换行的 100MB txt）不会被解码，
 *   只按 [virtualBlockBytes] 记录可导航的虚拟边界，因此不会 OOM。
 *
 * 输入用 `openStream` 而非 `File`，这样「复制到私有目录」和「引用原文件 content://」
 * 两种导入模式走同一条代码路径。
 */
class ChapterIndexer(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val virtualBlockBytes: Int = DEFAULT_VIRTUAL_BLOCK_BYTES,
    private val minRealChapters: Int = DEFAULT_MIN_REAL_CHAPTERS,
    private val progressStepBytes: Long = DEFAULT_PROGRESS_STEP_BYTES,
) {

    fun index(
        file: File,
        charset: Charset,
        rules: List<ChapterRule> = ChapterRules.DEFAULT,
        isCancelled: () -> Boolean = { false },
        onProgress: (IndexProgress) -> Unit = {},
    ): IndexResult = index(
        openStream = { file.inputStream() },
        totalBytes = file.length(),
        charset = charset,
        rules = rules,
        isCancelled = isCancelled,
        onProgress = onProgress,
    )

    fun index(
        openStream: () -> InputStream,
        totalBytes: Long,
        charset: Charset,
        rules: List<ChapterRule> = ChapterRules.DEFAULT,
        isCancelled: () -> Boolean = { false },
        onProgress: (IndexProgress) -> Unit = {},
    ): IndexResult {
        val startedAt = System.currentTimeMillis()
        val buffer = ByteArray(bufferSize)
        val candidates = ArrayList<Candidate>(INITIAL_CANDIDATE_CAPACITY)
        val virtualBounds = ArrayList<Long>(INITIAL_VIRTUAL_CAPACITY)

        var linesScanned = 0L
        var carry = 0
        var lineStartValid = true
        var absoluteBase = 0L
        var lastVirtualAt = 0L
        var lastProgressAt = 0L
        var lastTitle: String? = null

        fun reportProgress(force: Boolean) {
            if (!force && absoluteBase - lastProgressAt < progressStepBytes) return
            lastProgressAt = absoluteBase
            onProgress(IndexProgress(absoluteBase, totalBytes, candidates.size, lastTitle))
        }

        // 复用的解码缓冲与解码器：索引过程中**不按行分配对象**。
        // 放在 index() 内是因为 charset 是本次调用的参数。
        val lineChars = CharBuffer.allocate(ChapterRules.MAX_TITLE_BYTES)
        val lineDecoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        /**
         * 把一行解码进复用缓冲并去掉首尾空白（含全角空格），返回 CharSequence 视图。
         * 返回 null 表示解码失败或去空白后为空。
         *
         * 返回 CharSequence 而非 String 是有意的：正则可以直接在这个视图上匹配，
         * 只有真的命中章节规则时才需要建 String。
         */
        fun decodeLine(from: Int, length: Int): CharSequence? {
            lineChars.clear()
            lineDecoder.reset()
            val result = lineDecoder.decode(ByteBuffer.wrap(buffer, from, length), lineChars, true)
            if (result.isError) return null
            lineChars.flip()

            var start = 0
            var end = lineChars.limit()
            while (start < end && lineChars.get(start).isWhitespace()) start++
            while (end > start && lineChars.get(end - 1).isWhitespace()) end--
            if (end <= start) return null
            return lineChars.subSequence(start, end)
        }

        fun handleLine(from: Int, to: Int, absoluteOffset: Long) {
            linesScanned++
            val byteLength = to - from
            // 标题行必然很短：先用字节长度挡掉绝大多数正文段落，避免无谓解码
            if (byteLength in 1..ChapterRules.MAX_TITLE_BYTES) {
                // 关键性能点：这里**不创建 String**。100MB 小说有上百万行，
                // 每行分配一个 String 会产生上百 MB 垃圾；改用复用的字符缓冲，
                // 只有真的匹配上章节规则时才 toString()。
                val line = decodeLine(from, byteLength)
                if (line != null && line.length <= ChapterRules.MAX_TITLE_CHARS) {
                    val rule = rules.firstOrNull { it.matches(line) }
                    // 只对**紧凑写法**要求结尾干净。带分隔符的写法本身已经够具体，
                    // 再卡结尾会把「第5章 凶手是谁？」这种真实标题一起丢掉。
                    val acceptable = rule != null &&
                        !(rule.requireCleanEnding && ChapterRules.hasProseEnding(line))
                    if (acceptable) {
                        val title = line.toString()
                        candidates.add(Candidate(title = title, startByte = absoluteOffset))
                        lastTitle = title
                    }
                }
            }
            if (absoluteOffset - lastVirtualAt >= virtualBlockBytes) {
                virtualBounds.add(absoluteOffset)
                lastVirtualAt = absoluteOffset
            }
        }

        openStream().use { rawStream ->
            val input = if (rawStream is BufferedInputStream) {
                rawStream
            } else {
                BufferedInputStream(rawStream, bufferSize)
            }

            while (true) {
                if (isCancelled()) throw IndexCancelledException()
                val read = input.read(buffer, carry, buffer.size - carry)
                if (read <= 0) break

                val filled = carry + read
                var pos = 0
                var lineStart = 0

                while (pos < filled) {
                    val byte = buffer[pos].toInt()
                    if (byte == BYTE_LF || byte == BYTE_CR) {
                        if (lineStartValid) {
                            handleLine(lineStart, pos, absoluteBase + lineStart)
                        }
                        // CRLF 只算一个换行
                        if (byte == BYTE_CR && pos + 1 < filled && buffer[pos + 1].toInt() == BYTE_LF) {
                            pos++
                        }
                        lineStart = pos + 1
                        lineStartValid = true
                    }
                    pos++
                }

                if (lineStart == 0 && filled == buffer.size) {
                    // 整块缓冲都没有换行 —— 这是个超过 bufferSize 的超长行。
                    // 标题必须是很短的一行，所以这块不可能含标题，直接丢弃，内存不增长。
                    carry = 0
                    lineStartValid = false
                    // 但必须给出可导航的虚拟边界，否则「整本无换行」的文件只有一个章节
                    if (absoluteBase + filled - lastVirtualAt >= virtualBlockBytes) {
                        virtualBounds.add(absoluteBase)
                        lastVirtualAt = absoluteBase
                    }
                    absoluteBase += filled
                } else {
                    // lineStart > 0 当且仅当本块里出现过换行，此时 tail 一定紧跟在换行之后
                    if (lineStart > 0) lineStartValid = true
                    carry = filled - lineStart
                    if (carry > 0) {
                        System.arraycopy(buffer, lineStart, buffer, 0, carry)
                    }
                    absoluteBase += lineStart
                }

                reportProgress(force = false)
            }

            // 文件最后一行没有换行符时不能漏掉它（很多 txt 的最后一章就是这样）
            if (carry > 0 && lineStartValid) {
                handleLine(0, carry, absoluteBase)
                absoluteBase += carry
                carry = 0
            }
        }

        reportProgress(force = true)

        val effectiveTotal = if (totalBytes > 0) totalBytes else absoluteBase

        // 刻意**不做**任何「序号必须递增」的过滤。
        //
        // 曾经的实现要求章节序号递增且相邻跨度 ≤50，结果是：合集类 txt 每一卷都从「第1章」
        // 重新编号，第二卷起全被丢掉（实测 180 章只剩 60 章）；编号有大跨度时更惨
        // （实测 10 章只剩 2 章）。丢章节是灾难性的，而多一条误报只是目录里多一行 ——
        // 两者的代价完全不对称，所以这里一律保留。
        val realCandidates = candidates

        val usedVirtual: Boolean
        val chapters: List<IndexedChapter>
        if (realCandidates.size >= minRealChapters) {
            usedVirtual = false
            chapters = finalizeChapters(realCandidates, effectiveTotal)
        } else {
            usedVirtual = true
            chapters = buildVirtualChapters(virtualBounds, effectiveTotal)
        }

        return IndexResult(
            chapters = chapters,
            totalBytes = effectiveTotal,
            charset = charset,
            usedVirtualChapters = usedVirtual,
            elapsedMillis = System.currentTimeMillis() - startedAt,
            linesScanned = linesScanned,
        )
    }

    private fun finalizeChapters(candidates: List<Candidate>, totalBytes: Long): List<IndexedChapter> {
        val sorted = candidates.sortedBy { it.startByte }
        val result = ArrayList<IndexedChapter>(sorted.size + 1)

        // 第一章之前的内容（封面文案、作者的话）单独成章，避免那部分文字无法阅读
        val firstStart = sorted.firstOrNull()?.startByte ?: 0L
        if (firstStart >= MIN_PREAMBLE_BYTES) {
            result.add(IndexedChapter(0, PREAMBLE_TITLE, 0L, firstStart))
        }

        for (candidate in sorted) {
            val index = result.size
            result.add(IndexedChapter(index, candidate.title, candidate.startByte, -1L))
        }

        return result.mapIndexed { index, chapter ->
            val end = result.getOrNull(index + 1)?.startByte ?: totalBytes
            chapter.copy(endByte = if (end > chapter.startByte) end else totalBytes)
        }
    }

    /** 没有可用章节时，按固定字节间隔切虚拟分章，让 TOC 与进度条仍然可用。 */
    private fun buildVirtualChapters(bounds: List<Long>, totalBytes: Long): List<IndexedChapter> {
        val cuts = ArrayList<Long>(bounds.size + 2)
        cuts.add(0L)
        for (bound in bounds) {
            if (bound > cuts.last() && bound < totalBytes) cuts.add(bound)
        }
        cuts.add(totalBytes)

        val result = ArrayList<IndexedChapter>(cuts.size - 1)
        for (i in 0 until cuts.size - 1) {
            val start = cuts[i]
            val end = cuts[i + 1]
            if (end <= start) continue
            val index = result.size
            result.add(IndexedChapter(index, "第 ${index + 1} 部分", start, end))
        }
        if (result.isEmpty()) {
            result.add(IndexedChapter(0, "第 1 部分", 0L, totalBytes))
        }
        return result
    }

    private data class Candidate(
        val title: String,
        val startByte: Long,
    )

    companion object {
        const val DEFAULT_BUFFER_SIZE = 1 shl 20              // 1MB
        const val DEFAULT_VIRTUAL_BLOCK_BYTES = 32 * 1024     // 虚拟分章：32KB ≈ 1.6 万汉字

        /**
         * 至少要识别出几个章节，才采信真实章节、放弃虚拟分章。
         *
         * 取 2 而不是 3：两章的书（短篇集、上下部）很常见，阈值设 3 会把它们的真实章节名
         * 整个丢掉、变成「第 1 部分」；而 1 个候选又太容易是正文里偶然出现的「第一章」。
         */
        const val DEFAULT_MIN_REAL_CHAPTERS = 2
        const val DEFAULT_PROGRESS_STEP_BYTES = 2L * 1024 * 1024

        private const val BYTE_LF = 0x0A
        private const val BYTE_CR = 0x0D

        private const val MIN_PREAMBLE_BYTES = 512L
        private const val INITIAL_CANDIDATE_CAPACITY = 512
        private const val INITIAL_VIRTUAL_CAPACITY = 4_096

        const val PREAMBLE_TITLE = "卷首"
    }
}

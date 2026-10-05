package com.harness.inkreader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class ChapterIndexerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val gbk: Charset = Charset.forName("GBK")

    private val body = "他站在窗前看着外面的雪，想起很多年前的旧事。".repeat(3)

    /**
     * 造一本有 [count] 章的小说。正文段落刻意做长（>120 字节），
     * 确保不会被标题规则误判，从而让断言只反映真正的章节识别能力。
     */
    private fun novel(
        count: Int,
        lineEnding: String = "\n",
        preambleParagraphs: Int = 0,
    ): String = buildString {
        repeat(preambleParagraphs) {
            append("　　").append(body).append(lineEnding)
        }
        if (preambleParagraphs > 0) append(lineEnding)
        for (i in 1..count) {
            append("第").append(i).append("章 第").append(i).append("节的风雪").append(lineEnding)
            repeat(4) {
                append("　　").append(body).append(lineEnding)
            }
            append(lineEnding)
        }
    }

    private fun write(name: String, text: String, charset: Charset): File {
        val file = temp.newFile(name)
        file.writeBytes(text.toByteArray(charset))
        return file
    }

    /** 从给定字节偏移开始读一整行并解码 —— 用来验证偏移「精确落在一行的开头」。 */
    private fun lineAt(file: File, offset: Long, charset: Charset): String {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(1024)
            val read = raf.read(buffer)
            val end = (0 until read).firstOrNull {
                buffer[it].toInt() == 0x0A || buffer[it].toInt() == 0x0D
            } ?: read
            return String(buffer, 0, end, charset)
        }
    }

    @Test
    fun `gbk chapters get exact byte offsets`() {
        val count = 24
        val text = novel(count)
        val file = write("gbk.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertFalse(result.usedVirtualChapters)
        assertEquals(count, result.chapters.size)
        assertEquals(file.length(), result.chapters.last().endByte)

        result.chapters.forEachIndexed { index, chapter ->
            val expectedTitle = "第${index + 1}章 第${index + 1}节的风雪"
            assertEquals(expectedTitle, chapter.title)
            assertEquals(index, chapter.idx)
            // 核心断言：按索引偏移 seek 回去，读到的正是那一章的标题行
            assertEquals(
                "第 ${index + 1} 章的字节偏移不精确",
                expectedTitle,
                lineAt(file, chapter.startByte, gbk),
            )
            if (index > 0) {
                assertEquals(chapter.startByte, result.chapters[index - 1].endByte)
            }
        }
    }

    @Test
    fun `utf8 chapters get exact byte offsets`() {
        val count = 12
        val text = novel(count)
        val file = write("utf8.txt", text, StandardCharsets.UTF_8)

        val result = ChapterIndexer().index(file, StandardCharsets.UTF_8)

        assertEquals(count, result.chapters.size)
        result.chapters.forEachIndexed { index, chapter ->
            assertEquals(
                "第${index + 1}章 第${index + 1}节的风雪",
                lineAt(file, chapter.startByte, StandardCharsets.UTF_8),
            )
        }
    }

    @Test
    fun `crlf line endings do not leak into titles`() {
        val count = 10
        val file = write("crlf.txt", novel(count, lineEnding = "\r\n"), gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertEquals(count, result.chapters.size)
        result.chapters.forEachIndexed { index, chapter ->
            val title = chapter.title
            assertEquals("第${index + 1}章 第${index + 1}节的风雪", title)
            assertFalse("标题里混入了回车", title.contains('\r'))
            assertEquals(title, lineAt(file, chapter.startByte, gbk))
        }
    }

    @Test
    fun `last chapter without trailing newline is still found`() {
        val text = novel(5).trimEnd('\n')
        val file = write("no-trailing.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertEquals(5, result.chapters.size)
        assertEquals(file.length(), result.chapters.last().endByte)
        assertEquals("第5章 第5节的风雪", result.chapters.last().title)
    }

    @Test
    fun `prose that merely mentions a chapter is filtered out`() {
        val count = 40
        val text = buildString {
            for (i in 1..count) {
                append("第").append(i).append("章 正题").append('\n')
                repeat(3) { append("　　").append(body).append('\n') }
                if (i == 12) {
                    // 短行、以「章」开头的正文，形状过滤挡不住，必须靠序号序列过滤
                    append("他翻到第一章想起往事").append('\n')
                }
                if (i == 30) {
                    // 以句号结尾，形状过滤直接挡掉
                    append("他翻到第九章，忽然停住了。").append('\n')
                }
            }
        }
        val file = write("false-positive.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertEquals(count, result.chapters.size)
        assertTrue(
            "误报没有被过滤掉：${result.chapters.map { it.title }}",
            result.chapters.none { it.title.contains("想起往事") || it.title.contains("忽然停住") },
        )
    }

    @Test
    fun `a two chapter book keeps both real chapter titles`() {
        // 阈值曾经是 3，会导致两章的书被整体降级成「第 1 部分」，真实章节名全丢
        val file = write("two-chapters.txt", novel(2), gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertFalse(result.usedVirtualChapters)
        assertEquals(2, result.chapters.size)
        assertEquals("第1章 第1节的风雪", result.chapters[0].title)
        assertEquals("第2章 第2节的风雪", result.chapters[1].title)
    }

    @Test
    fun `a single chapter heading is not trusted`() {
        // 只有一个候选时宁可当成分章失败：很可能是正文里偶然提到了「第一章」
        val text = novel(1) + "　　" + body + "\n"
        val file = write("one-chapter.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertTrue(result.usedVirtualChapters)
    }

    @Test
    fun `headings indented with a full width space are still detected`() {
        // 很多 txt 的章节标题前面是两个全角空格 U+3000，
        // 而 Java 正则的 \s 默认**不匹配** U+3000 —— 漏掉它会导致整本书只剩少数章节。
        val count = 120
        val text = buildString {
            for (i in 1..count) {
                append("\u3000\u3000").append("第").append(i).append("章 风雪").append('\n')
                repeat(3) { append("\u3000\u3000").append(body).append('\n') }
            }
        }
        val file = write("indented.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertFalse("有标题却报了虚拟分章，说明标题没被识别", result.usedVirtualChapters)
        assertEquals(count, result.chapters.size)
    }

    @Test
    fun `chapter numbering may restart between volumes`() {
        // 合集类 txt 很常见：每一卷都从「第1章」重新编号。
        // 任何「序号必须递增」的过滤都会在这里把后面的卷全部丢掉。
        val volumes = 3
        val perVolume = 60
        val text = buildString {
            for (volume in 1..volumes) {
                append("第").append(volume).append("卷 卷首").append('\n')
                for (i in 1..perVolume) {
                    append("第").append(i).append("章 风雪").append('\n')
                    repeat(3) { append("\u3000\u3000").append(body).append('\n') }
                }
            }
        }
        val file = write("volumes.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertTrue(
            "编号重启后不应当丢掉章节：期望至少 ${volumes * perVolume} 章，实际 ${result.chapters.size}",
            result.chapters.size >= volumes * perVolume,
        )
    }

    @Test
    fun `large gaps in numbering do not drop later chapters`() {
        val numbers = listOf(1, 40, 300, 600, 900, 1_200, 1_500, 1_800, 2_100, 2_400)
        val text = buildString {
            numbers.forEach { number ->
                append("第").append(number).append("章 风雪").append('\n')
                repeat(3) { append("\u3000\u3000").append(body).append('\n') }
            }
        }
        val file = write("gaps.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertEquals("大跨度编号不应当丢章节", numbers.size, result.chapters.size)
    }

    @Test
    fun `text without any chapter heading falls back to virtual chapters`() {
        val text = buildString {
            repeat(3_000) { append("　　").append(body).append('\n') }
        }
        val file = write("no-chapters.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertTrue(result.usedVirtualChapters)
        assertTrue("虚拟分章太少：${result.chapters.size}", result.chapters.size >= 5)
        assertEquals(0L, result.chapters.first().startByte)
        assertEquals(file.length(), result.chapters.last().endByte)
        result.chapters.forEachIndexed { index, chapter ->
            assertTrue("第 $index 章为空", chapter.endByte > chapter.startByte)
            assertTrue(
                "第 $index 章超过了虚拟分章粒度",
                chapter.byteLength <= ChapterIndexer.DEFAULT_VIRTUAL_BLOCK_BYTES + 1024L,
            )
        }
    }

    @Test
    fun `file with no newline at all is indexed without loading it into memory`() {
        // 5MB 单行：缓冲只有 1MB，实现必须丢弃超长行而不是把它读进字符串
        val sb = StringBuilder(5 * 1024 * 1024)
        while (sb.length < 5 * 1024 * 1024) sb.append(body)
        val file = write("single-line.txt", sb.toString(), gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertEquals(file.length(), result.totalBytes)
        assertEquals(0L, result.chapters.first().startByte)
        assertEquals(file.length(), result.chapters.last().endByte)
        assertTrue("单行文件也应当给出可导航的分块", result.chapters.size >= 1)
        assertEquals(0L, result.linesScanned)
    }

    @Test
    fun `preamble before the first chapter becomes its own chapter`() {
        val text = novel(6, preambleParagraphs = 12)
        val file = write("preamble.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertTrue(result.chapters.size >= 7)
        val first = result.chapters.first()
        assertEquals(ChapterIndexer.PREAMBLE_TITLE, first.title)
        assertEquals(0L, first.startByte)
        assertEquals("第1章 第1节的风雪", result.chapters[1].title)
        assertEquals(result.chapters[1].startByte, first.endByte)
    }

    @Test
    fun `short preamble is not promoted to its own chapter`() {
        val text = "作者的话\n\n" + novel(6)
        val file = write("short-preamble.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertEquals(6, result.chapters.size)
        assertEquals("第1章 第1节的风雪", result.chapters.first().title)
    }

    @Test
    fun `english chapter headings are recognised`() {
        val text = buildString {
            for (i in 1..12) {
                append("Chapter ").append(i).append('\n')
                repeat(3) { append(body).append('\n') }
            }
        }
        val file = write("english.txt", text, StandardCharsets.UTF_8)

        val result = ChapterIndexer().index(file, StandardCharsets.UTF_8)

        assertEquals(12, result.chapters.size)
        assertEquals("Chapter 1", result.chapters.first().title)
        assertEquals("Chapter 12", result.chapters.last().title)
    }

    @Test
    fun `chinese numeral chapter headings are recognised`() {
        val text = buildString {
            listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")
                .forEach { numeral ->
                    append("第").append(numeral).append("章 旧事").append('\n')
                    repeat(3) { append("　　").append(body).append('\n') }
                }
        }
        val file = write("numerals.txt", text, gbk)

        val result = ChapterIndexer().index(file, gbk)

        assertEquals(12, result.chapters.size)
        assertEquals("第一章 旧事", result.chapters.first().title)
        assertEquals("第十二章 旧事", result.chapters.last().title)
    }

    @Test
    fun `progress callbacks are monotonic and end at 100 percent`() {
        val text = buildString { repeat(4_000) { append("　　").append(body).append('\n') } }
        val file = write("progress.txt", text, gbk)

        val samples = ArrayList<IndexProgress>()
        ChapterIndexer(progressStepBytes = 64 * 1024).index(
            file = file,
            charset = gbk,
            onProgress = { samples.add(it) },
        )

        assertTrue(samples.isNotEmpty())
        var previous = -1L
        samples.forEach {
            assertTrue("进度回退：$previous -> ${it.bytesScanned}", it.bytesScanned >= previous)
            previous = it.bytesScanned
        }
        assertEquals(1f, samples.last().fraction, 0.0001f)
        assertEquals(file.length(), samples.last().bytesScanned)
    }

    @Test
    fun `cancellation aborts the scan`() {
        val text = buildString { repeat(40_000) { append("　　").append(body).append('\n') } }
        val file = write("cancel.txt", text, gbk)

        var calls = 0
        val failure = runCatching {
            ChapterIndexer(progressStepBytes = 4096).index(
                file = file,
                charset = gbk,
                isCancelled = { calls++ > 1 },
            )
        }.exceptionOrNull()

        assertTrue("应当抛出 IndexCancelledException，实际 $failure", failure is IndexCancelledException)
    }
}

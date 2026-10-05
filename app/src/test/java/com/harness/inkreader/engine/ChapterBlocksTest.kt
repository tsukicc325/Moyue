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

/**
 * 章节分块的正确性。
 *
 * 最关键的一条是 [concatenated blocks reproduce the whole chapter]：
 * 把所有块按顺序拼起来必须**逐字符等于**原章节文本 —— 这同时证明了「不丢字、不重复、
 * 块边界恰好落在行首、块间无缝衔接」。
 */
class ChapterBlocksTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val gbk: Charset = Charset.forName("GBK")

    private val paragraph = "他站在窗前看着外面的雪，想起很多年前的旧事。"

    private fun novelText(chapters: Int, paragraphsPerChapter: Int): String = buildString {
        for (chapter in 1..chapters) {
            append("第").append(chapter).append("章 第").append(chapter).append("节的风雪").append('\n')
            repeat(paragraphsPerChapter) { index ->
                append("　　").append(paragraph.repeat(2)).append(index).append('\n')
            }
        }
    }

    private fun writeNovel(
        name: String,
        charset: Charset,
        chapters: Int = 1,
        paragraphsPerChapter: Int = 40,
    ): Pair<File, String> {
        val text = novelText(chapters, paragraphsPerChapter)
        val file = temp.newFile(name)
        file.writeBytes(text.toByteArray(charset))
        return file to text
    }

    private fun longestLineBytes(text: String, charset: Charset): Int =
        text.split('\n').maxOf { it.toByteArray(charset).size }

    private fun byteBefore(file: File, offset: Long): Int =
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset - 1)
            raf.read()
        }

    private fun blocks(
        file: File,
        charset: Charset,
        start: Long,
        end: Long,
        blockBytes: Int,
    ): ChapterBlocks = ChapterBlocks(FileByteSource(file), charset, start, end, blockBytes)

    private fun allBlocks(blocks: ChapterBlocks): List<TextBlock> {
        val result = ArrayList<TextBlock>()
        var index = 0
        var guard = 0
        while (index >= 0 && guard++ < 100_000) {
            result.add(blocks.load(index))
            index = blocks.nextIndex(index)
        }
        return result
    }

    @Test
    fun `concatenated blocks reproduce the whole chapter`() {
        val (file, text) = writeNovel("gbk-novel.txt", gbk)
        val blocks = blocks(file, gbk, 0, file.length(), 1024)

        val assembled = allBlocks(blocks).joinToString(separator = "") { it.text }

        assertEquals(text, assembled)
        assertTrue("块数应当多于 1，否则没测到分块", allBlocks(blocks).size > 1)
    }

    @Test
    fun `the same is true for utf8 and for a chapter in the middle of a file`() {
        val (file, text) = writeNovel("utf8-novel.txt", StandardCharsets.UTF_8, chapters = 3)
        // 取中间那一章，验证「章节不是从文件头开始」时也正确
        val indexer = ChapterIndexer()
        val index = indexer.index(file, StandardCharsets.UTF_8)
        assertEquals(3, index.chapters.size)

        val chapter = index.chapters[1]
        val blocks = ChapterBlocks(
            source = FileByteSource(file),
            charset = StandardCharsets.UTF_8,
            chapterStart = chapter.startByte,
            chapterEnd = chapter.endByte,
            blockBytes = 1024,
        )
        val assembled = allBlocks(blocks).joinToString(separator = "") { it.text }
        val expected = text.toByteArray(StandardCharsets.UTF_8)
            .let { bytes -> String(bytes, chapter.startByte.toInt(), (chapter.endByte - chapter.startByte).toInt(), StandardCharsets.UTF_8) }

        assertEquals(expected, assembled)
    }

    @Test
    fun `block sizes stay within the bound plus one line`() {
        val (file, text) = writeNovel("bound.txt", gbk)
        val blockBytes = 1024
        val blocks = blocks(file, gbk, 0, file.length(), blockBytes)
        val slack = longestLineBytes(text, gbk)

        allBlocks(blocks).forEach { block ->
            assertTrue(
                "块 ${block.index} 长度 ${block.byteLength} 超过上限 $blockBytes + $slack",
                block.byteLength <= blockBytes + slack,
            )
        }
    }

    @Test
    fun `every block starts right after a newline`() {
        val (file, _) = writeNovel("boundary.txt", gbk)
        val blocks = blocks(file, gbk, 0, file.length(), 1024)

        allBlocks(blocks).forEach { block ->
            if (block.startByte > 0) {
                val previous = byteBefore(file, block.startByte)
                assertTrue(
                    "块 ${block.index} 的首字节上一字节是 $previous，说明块首没有吸附到行首",
                    previous == 0x0A || previous == 0x0D,
                )
            }
        }
    }

    @Test
    fun `indexAt maps any offset inside a block back to that block`() {
        val (file, _) = writeNovel("index-at.txt", gbk)
        val blocks = blocks(file, gbk, 0, file.length(), 1024)

        allBlocks(blocks).forEach { block ->
            assertEquals(block.index, blocks.indexAt(block.startByte))
            if (block.endByte > block.startByte) {
                assertEquals(block.index, blocks.indexAt(block.endByte - 1))
            }
        }
        // 越界输入应当被夹到合法范围
        assertEquals(0, blocks.indexAt(-100))
        assertEquals(blocks.lastIndex(), blocks.indexAt(file.length() + 1_000))
    }

    @Test
    fun `block boundaries are reproducible`() {
        val (file, _) = writeNovel("reproducible.txt", gbk)
        val first = blocks(file, gbk, 0, file.length(), 1024)
        val second = blocks(file, gbk, 0, file.length(), 1024)

        val last = first.lastIndex()
        assertEquals(last, second.lastIndex())
        for (index in 0..last) {
            assertEquals(
                "第 $index 块的首字节两次计算结果不一致，保存的阅读位置就会失效",
                first.startOf(index),
                second.startOf(index),
            )
        }
    }

    @Test
    fun `last block ends exactly at the chapter end`() {
        val (file, _) = writeNovel("last.txt", gbk)
        val blocks = blocks(file, gbk, 0, file.length(), 1024)

        val last = blocks.load(blocks.lastIndex())
        assertTrue(last.atChapterEnd)
        assertEquals(file.length(), last.endByte)
        assertTrue(last.text.isNotEmpty())
    }

    @Test
    fun `empty chapter yields an empty block instead of crashing`() {
        val file = temp.newFile("empty.txt")
        file.writeBytes(ByteArray(0))

        val blocks = blocks(file, gbk, 0, 0, 1024)
        val block = blocks.load(0)

        assertTrue(block.isEmpty)
        assertEquals(0L, block.byteLength)
        assertEquals(0, blocks.lastIndex())
    }

    @Test
    fun `utf16 blocks are readable and free of replacement characters`() {
        // 生产中 UTF-16 会在导入时被转码成 UTF-8，这里只验证极端情况下不会崩、不会满屏乱码
        val utf16 = Charset.forName("UTF-16LE")
        val (file, _) = writeNovel("utf16.txt", utf16)

        val blocks = blocks(file, utf16, 0, file.length(), 1024)
        val loaded = allBlocks(blocks)

        assertTrue(loaded.isNotEmpty())
        loaded.forEach { block ->
            assertFalse("块 ${block.index} 出现替换字符", block.text.contains('\uFFFD'))
        }
    }

    @Test
    fun `byteOffsetOfChar maps proportionally inside a block`() {
        val (file, _) = writeNovel("offset.txt", gbk)
        val blocks = blocks(file, gbk, 0, file.length(), 1024)
        val block = blocks.load(0)

        assertEquals(block.startByte, block.byteOffsetOfChar(0))
        val end = block.byteOffsetOfChar(block.text.length)
        assertTrue(end <= block.endByte)
        assertTrue(end >= block.startByte)

        val middle = block.byteOffsetOfChar(block.text.length / 2)
        assertTrue(middle in block.startByte..block.endByte)
    }
}

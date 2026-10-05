package com.harness.inkreader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 全文搜索。
 *
 * 最关键的一条断言是 [hits point at the exact text]：拿搜索返回的
 * (章节, 块号, 块内偏移) 重新加载那一块，取出的子串**必须正好等于搜索词**。
 * 只要有一处偏移算错，点搜索结果就会跳到错误的位置。
 */
class FullTextSearcherTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val gbk: Charset = Charset.forName("GBK")
    private val sentence = "他站在窗前看着外面的雪，想起很多年前的旧事。"

    private fun novel(
        name: String = "search.txt",
        chapters: Int = 3,
        paragraphs: Int = 6,
        charset: Charset = gbk,
        body: (Int) -> String = { sentence },
    ): Pair<File, List<IndexedChapter>> {
        val text = buildString {
            for (chapter in 1..chapters) {
                append("第").append(chapter).append("章 风雪").append('\n')
                repeat(paragraphs) { index ->
                    append("　　").append(body(index)).append('\n')
                }
            }
        }
        val file = temp.newFile(name)
        file.writeBytes(text.toByteArray(charset))
        val chapters = ChapterIndexer().index(file, charset).chapters
        return file to chapters
    }

    private fun textAt(
        file: File,
        chapters: List<IndexedChapter>,
        hit: SearchHit,
        charset: Charset = gbk,
    ): String {
        val chapter = chapters[hit.chapterIdx]
        val blocks = ChapterBlocks(
            source = FileByteSource(file),
            charset = charset,
            chapterStart = chapter.startByte,
            chapterEnd = chapter.endByte,
        )
        val block = blocks.load(hit.blockIndex)
        return block.text.substring(hit.charOffsetInBlock, hit.charOffsetInBlock + hit.matchLength)
    }

    @Test
    fun `hits point at the exact text`() {
        val (file, chapters) = novel(chapters = 3, paragraphs = 6)

        val hits = FullTextSearcher().search(FileByteSource(file), gbk, chapters, "窗前")

        assertEquals("3 章 × 6 段 = 18 处", 18, hits.size)
        hits.forEach { hit ->
            assertEquals(
                "第 ${hit.chapterIdx} 章 第 ${hit.blockIndex} 块偏移 ${hit.charOffsetInBlock} 处不是搜索词",
                "窗前",
                textAt(file, chapters, hit),
            )
        }
    }

    @Test
    fun `hits report the right chapter and its title`() {
        val (file, chapters) = novel(chapters = 4, paragraphs = 3)

        val hits = FullTextSearcher().search(FileByteSource(file), gbk, chapters, "旧事")

        assertEquals(12, hits.size)
        assertEquals(listOf(0, 1, 2, 3), hits.map { it.chapterIdx }.distinct().sorted())
        hits.forEach { hit ->
            assertEquals(chapters[hit.chapterIdx].title, hit.chapterTitle)
        }
    }

    @Test
    fun `snippet contains the match with context`() {
        val (file, chapters) = novel(chapters = 1, paragraphs = 2)

        val hits = FullTextSearcher().search(FileByteSource(file), gbk, chapters, "窗前")

        assertTrue(hits.isNotEmpty())
        hits.forEach { assertTrue("上下文里应当有搜索词：${it.snippet}", it.snippet.contains("窗前")) }
        assertTrue("应当带上前后文", hits.first().snippet.length > "窗前".length)
    }

    @Test
    fun `latin search is case insensitive by default`() {
        val (file, chapters) = novel(
            name = "english.txt",
            chapters = 1,
            paragraphs = 3,
            charset = StandardCharsets.UTF_8,
            body = { "The Night Boat drifted slowly." },
        )

        val hits = FullTextSearcher().search(
            source = FileByteSource(file),
            charset = StandardCharsets.UTF_8,
            chapters = chapters,
            query = "night boat",
        )

        assertEquals(3, hits.size)
        hits.forEach {
            assertEquals("Night Boat", textAt(file, chapters, it, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `case sensitivity can be required`() {
        val (file, chapters) = novel(
            name = "case.txt",
            chapters = 1,
            paragraphs = 2,
            charset = StandardCharsets.UTF_8,
            body = { "Alpha beta Gamma" },
        )

        val insensitive = FullTextSearcher().search(
            FileByteSource(file), StandardCharsets.UTF_8, chapters, "gamma",
        )
        val sensitive = FullTextSearcher().search(
            FileByteSource(file), StandardCharsets.UTF_8, chapters, "gamma", ignoreCase = false,
        )

        assertEquals(2, insensitive.size)
        assertEquals(0, sensitive.size)
    }

    @Test
    fun `max hits caps the result`() {
        val (file, chapters) = novel(chapters = 5, paragraphs = 6)

        val hits = FullTextSearcher(maxHits = 4).search(FileByteSource(file), gbk, chapters, "窗前")

        assertEquals(4, hits.size)
    }

    @Test
    fun `blank query and empty chapters return nothing`() {
        val (file, chapters) = novel(chapters = 1, paragraphs = 1)

        assertTrue(FullTextSearcher().search(FileByteSource(file), gbk, chapters, "   ").isEmpty())
        assertTrue(FullTextSearcher().search(FileByteSource(file), gbk, emptyList(), "窗前").isEmpty())
    }

    @Test
    fun `missing word returns nothing`() {
        val (file, chapters) = novel(chapters = 2, paragraphs = 2)

        val hits = FullTextSearcher().search(FileByteSource(file), gbk, chapters, "绝不可能出现的词")

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `progress advances to one and reports hits`() {
        val (file, chapters) = novel(chapters = 4, paragraphs = 4)

        val samples = ArrayList<SearchProgress>()
        FullTextSearcher().search(FileByteSource(file), gbk, chapters, "窗前") { samples.add(it) }

        assertTrue(samples.isNotEmpty())
        assertEquals(chapters.size, samples.last().chaptersDone)
        assertEquals(1f, samples.last().fraction, 1e-4f)
        assertEquals(16, samples.last().hits)
    }

    @Test
    fun `cancellation stops the scan early`() {
        val (file, chapters) = novel(chapters = 40, paragraphs = 20)
        var calls = 0

        val hits = FullTextSearcher().search(
            source = FileByteSource(file),
            charset = gbk,
            chapters = chapters,
            query = "窗前",
            isCancelled = { calls++ > 2 },
        )

        assertTrue("取消后不应当扫完整本：${hits.size}", hits.size < 40 * 20)
        assertTrue(hits.isNotEmpty())
    }

    @Test
    fun `works on a utf8 file with the whole book scanned`() {
        val (file, chapters) = novel(
            name = "utf8.txt",
            chapters = 6,
            paragraphs = 8,
            charset = StandardCharsets.UTF_8,
        )

        val hits = FullTextSearcher().search(
            FileByteSource(file), StandardCharsets.UTF_8, chapters, "很多年前",
        )

        assertEquals(48, hits.size)
        hits.take(5).forEach {
            assertEquals("很多年前", textAt(file, chapters, it, StandardCharsets.UTF_8))
        }
    }
}

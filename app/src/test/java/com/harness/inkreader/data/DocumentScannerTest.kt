package com.harness.inkreader.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.harness.inkreader.engine.ChapterBlocks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset

/**
 * 文件夹扫描（[DocumentScanner]）与批量导入。
 *
 * 用 `DocumentFile.fromFile(目录)` 指向真实临时目录，所以扫描逻辑是真的走了一遍递归，
 * 而不是靠打桩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: InkDatabase
    private lateinit var repo: BookRepository

    private val gbk: Charset = Charset.forName("GBK")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = InkDatabase.inMemory(context)
        repo = BookRepository(context, db)
    }

    @After
    fun tearDown() = db.close()

    private fun writeNovel(file: File, chapters: Int = 3) {
        val text = buildString {
            for (chapter in 1..chapters) {
                append("第").append(chapter).append("章 风雪").append('\n')
                repeat(4) {
                    append("　　").append("他站在窗前看着外面的雪，想起很多年前的旧事。".repeat(2)).append('\n')
                }
            }
        }
        file.parentFile?.mkdirs()
        file.writeBytes(text.toByteArray(gbk))
    }

    @Test
    fun `only txt files are collected, recursively, case insensitively`() {
        val root = temp.newFolder("library")
        writeNovel(File(root, "a.txt"))
        writeNovel(File(root, "b.TXT"))
        writeNovel(File(root, "sub/c.txt"))
        writeNovel(File(root, "sub/deeper/d.text"))
        File(root, "d.epub").writeText("not a novel")
        File(root, "e.md").writeText("not a novel")

        val found = DocumentScanner.collectTextFiles(DocumentFile.fromFile(root))

        assertEquals(
            listOf("a.txt", "b.TXT", "c.txt", "d.text"),
            found.map { it.name }.sorted(),
        )
    }

    @Test
    fun `max depth stops the walk`() {
        val root = temp.newFolder("deep")
        writeNovel(File(root, "top.txt"))
        writeNovel(File(root, "l1/a.txt"))
        writeNovel(File(root, "l1/l2/b.txt"))

        val shallow = DocumentScanner.collectTextFiles(DocumentFile.fromFile(root), maxDepth = 0)
        assertEquals(listOf("top.txt"), shallow.map { it.name })

        val oneDeep = DocumentScanner.collectTextFiles(DocumentFile.fromFile(root), maxDepth = 1)
        assertEquals(listOf("a.txt", "top.txt"), oneDeep.map { it.name }.sorted())
    }

    @Test
    fun `max files caps the result`() {
        val root = temp.newFolder("many")
        repeat(10) { index -> writeNovel(File(root, "n$index.txt"), chapters = 1) }

        val found = DocumentScanner.collectTextFiles(DocumentFile.fromFile(root), maxFiles = 4)

        assertEquals(4, found.size)
    }

    @Test
    fun `isTextFile accepts only txt and text`() {
        assertTrue(DocumentScanner.isTextFile("小说.txt"))
        assertTrue(DocumentScanner.isTextFile("NOVEL.TXT"))
        assertTrue(DocumentScanner.isTextFile("a.text"))
        assertFalse(DocumentScanner.isTextFile("a.epub"))
        assertFalse(DocumentScanner.isTextFile("novel"))
        assertFalse(DocumentScanner.isTextFile("txt"))
    }

    @Test
    fun `batch import brings in every txt under a folder`() = runBlocking {
        val root = temp.newFolder("import-all")
        writeNovel(File(root, "夜航船.txt"), chapters = 4)
        writeNovel(File(root, "江防志.txt"), chapters = 3)
        writeNovel(File(root, "sub/旧事.txt"), chapters = 5)
        File(root, "无关.epub").writeText("ignore me")

        val candidates = DocumentScanner.collectTextFiles(DocumentFile.fromFile(root))
        assertEquals(3, candidates.size)

        val succeeded = repo.importMany(candidates, StorageMode.COPY)

        assertEquals(3, succeeded)
        val books = repo.books()
        assertEquals(3, books.size)
        assertEquals(
            setOf("夜航船", "江防志", "旧事"),
            books.map { it.title }.toSet(),
        )
        books.forEach { book ->
            assertTrue("${book.title} 没有索引出章节", book.chapterCount >= 3)
        }
    }
}

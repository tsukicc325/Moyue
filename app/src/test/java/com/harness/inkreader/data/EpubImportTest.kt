package com.harness.inkreader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.harness.inkreader.engine.FileByteSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 导入：解析成规范化文本、用 EPUB 自己的目录当章节表、去重，
 * 以及「名字像 EPUB 其实是 txt」时能退回文本流程；同时验证 v2→v3 迁移不丢数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpubImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: InkDatabase
    private lateinit var repo: BookRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = InkDatabase.inMemory(context)
        repo = BookRepository(context, db)
    }

    @After
    fun tearDown() = db.close()

    // ------------------------------------------------------------------ 样本

    /** 造一个最小但合规的 EPUB：3 章 + 目录（nav 里的标题故意和正文小标题不同）。 */
    private fun buildEpub(name: String = "夜航船.epub"): File {
        val file = File(temp.newFolder(), name)
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>夜航船</dc:title>
                <dc:creator>张三</dc:creator>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="chap2.xhtml" media-type="application/xhtml+xml"/>
                <item id="c3" href="chap3.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/>
              </spine>
            </package>
        """.trimIndent()
        val nav = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body><nav epub:type="toc"><ol>
                <li><a href="chap1.xhtml">第一章 起航</a></li>
                <li><a href="chap2.xhtml">第二章 风雪</a></li>
                <li><a href="chap3.xhtml">第三章 归途</a></li>
              </ol></nav></body>
            </html>
        """.trimIndent()
        fun doc(title: String, body: String) = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
            <h2>$title</h2>$body
            </body></html>
        """.trimIndent()

        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            entry(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """.trimIndent(),
            )
            entry("OEBPS/content.opf", opf)
            entry("OEBPS/nav.xhtml", nav)
            entry("OEBPS/chap1.xhtml", doc("正文小标题甲", "<p>第一段。</p><p>第二段。</p>"))
            entry("OEBPS/chap2.xhtml", doc("正文小标题乙", "<p>风雪很大。</p>"))
            entry("OEBPS/chap3.xhtml", doc("正文小标题丙", "<p>回家了。</p>"))
        }
        return file
    }

    // ------------------------------------------------------------------ 测试

    @Test
    fun `imports epub as normalized text with the epub toc titles`() = runBlocking {
        val bookId = repo.importFromFile(buildEpub())

        val book = db.books().byId(bookId)
        assertNotNull(book)
        assertEquals("书名取自 EPUB 元数据", "夜航船", book!!.title)
        assertEquals(BookFormat.EPUB, book.format)
        assertEquals(3, book.chapterCount)
        // 读的是规范化文本（私有目录里的 .txt），不是那个 epub
        assertTrue("filePath 应指向规范化文本", book.filePath.endsWith(".txt"))
        assertTrue(File(book.filePath).exists())
        assertNotNull("原文件副本要留下来，便于以后重新解析", book.sourcePath)
        assertTrue(book.sourcePath!!.endsWith(".epub"))
        assertTrue(File(book.sourcePath!!).exists())

        val chapters = db.chapters().byBook(bookId).sortedBy { it.idx }
        assertEquals(
            "章节标题应当来自 EPUB 的目录，而不是正文小标题",
            listOf("第一章 起航", "第二章 风雪", "第三章 归途"),
            chapters.map { it.title },
        )
        // 章节区间必须首尾相接、覆盖整个规范化文本
        assertEquals(0L, chapters.first().startByte)
        assertEquals(book.totalBytes, chapters.last().endByte)
        for (index in 0 until chapters.size - 1) {
            assertEquals(chapters[index + 1].startByte, chapters[index].endByte)
        }

        // 读第一章：字节来源就是那份文本，内容应当已经提取成纯文本
        val source = FileByteSource(File(book.filePath))
        val bytes = ByteArray((chapters[0].endByte - chapters[0].startByte).toInt())
        source.openStream().use { input ->
            input.skip(chapters[0].startByte)
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n <= 0) break
                read += n
            }
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        assertTrue("正文应当有内容，实际：$text", text.contains("第一段。"))
        assertTrue(text.contains("第二段。"))
        assertFalse("XHTML 标签不该留在正文里", text.contains("<p>"))
    }

    @Test
    fun `an epub-named plain text file still imports as text`() = runBlocking {
        // 扩展名骗人：其实是普通 txt（用户改错过名、或某些下载站乱来）
        val fake = File(temp.newFolder(), "假装是书.epub")
        fake.writeBytes("第一章 开头\n正文内容。\n第二章 结尾\n结束。\n".toByteArray(Charsets.UTF_8))

        val bookId = repo.importFromFile(fake)

        val book = db.books().byId(bookId)
        assertEquals("应当按文本导入，而不是报错拒绝", BookFormat.TXT, book!!.format)
        assertTrue(book.chapterCount >= 2)
    }

    @Test
    fun `re-importing the same epub does not duplicate it`() = runBlocking {
        val epub = buildEpub()
        val first = repo.importFromFile(epub, displayName = "夜航船.epub")
        val second = repo.importFromFile(epub, displayName = "夜航船.epub")

        assertEquals("同一本书重复导入应当复用记录", first, second)
        assertEquals(1, db.books().count())
        // 重复导入时多出来的原文件副本要清理掉，不能越导越多
        val books = db.books().all()
        val sources = File(context.filesDir, BookRepository.BOOKS_DIR)
            .listFiles { file -> file.name.endsWith(".epub") } ?: emptyArray()
        assertEquals("原文件副本只应留 1 份", 1, sources.size)
        assertEquals(1, books.size)
    }

    @Test
    fun `v2 to v3 migration keeps existing books and defaults them to TXT`() {
        val file = File(temp.newFolder(), "old-v2.db")
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL(
            """
            CREATE TABLE books (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                filePath TEXT NOT NULL,
                storageMode TEXT NOT NULL,
                fileSize INTEGER NOT NULL,
                fileMtime INTEGER NOT NULL,
                encoding TEXT NOT NULL,
                encodingManual INTEGER NOT NULL,
                encodingEvidence TEXT NOT NULL,
                totalBytes INTEGER NOT NULL,
                chapterCount INTEGER NOT NULL,
                usedVirtualChapters INTEGER NOT NULL,
                groupName TEXT,
                coverSeed INTEGER NOT NULL,
                coverPath TEXT,
                indexed INTEGER NOT NULL,
                addedAt INTEGER NOT NULL,
                lastReadAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        old.execSQL(
            "INSERT INTO books (title, filePath, storageMode, fileSize, fileMtime, encoding, " +
                "encodingManual, encodingEvidence, totalBytes, chapterCount, usedVirtualChapters, " +
                "groupName, coverSeed, coverPath, indexed, addedAt, lastReadAt) " +
                "VALUES ('凡人修仙传', '/x.txt', 'copy', 11, 22, 'UTF-8', 1, '手动', 23353347, 2446, 0, " +
                "NULL, 7, NULL, 1, 44, 55)",
        )

        old.execSQL(InkDatabase.SQL_ADD_FORMAT)
        old.execSQL(InkDatabase.SQL_ADD_SOURCE_PATH)

        val cursor = old.rawQuery("SELECT title, chapterCount, format, sourcePath FROM books", null)
        assertTrue("老库里的书必须还在", cursor.moveToFirst())
        assertEquals("凡人修仙传", cursor.getString(0))
        assertEquals("索引和进度所依赖的章节数不能变", 2446, cursor.getInt(1))
        assertEquals("老书全部是 TXT，迁移后要有默认值", BookFormat.TXT, cursor.getString(2))
        assertTrue("新列默认为空", cursor.isNull(3))
        assertEquals(1, cursor.count)
        cursor.close()
        old.close()
    }
}

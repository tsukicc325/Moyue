package com.harness.inkreader.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 解析：容器 / OPF / 目录（EPUB3 nav 与 EPUB2 NCX）/ 正文提取 / 封面 / 容错。
 *
 * 这些都是纯 JVM 能跑的（只用 java.util.zip 与 javax.xml），所以不需要 Robolectric。
 */
class EpubParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ------------------------------------------------------------------ 构造样本

    /** 1×1 的透明 PNG，用来验证封面抽取。 */
    private val tinyPng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    private fun buildEpub(
        name: String,
        opf: String,
        entries: Map<String, ByteArray>,
        withMimetypeEntry: Boolean = true,
    ): File {
        val file = File(temp.newFolder(), name)
        ZipOutputStream(file.outputStream()).use { zip ->
            if (withMimetypeEntry) {
                // 规范要求 mimetype 是第一个条目且不压缩
                zip.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = 20; compressedSize = 20; crc = java.util.zip.CRC32().apply { update("application/epub+zip".toByteArray()) }.value })
                zip.write("application/epub+zip".toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(opf.toByteArray())
            zip.closeEntry()
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun epub3Opf(chapterCount: Int = 3): String {
        val items = (1..chapterCount).joinToString("\n") {
            """    <item id="c$it" href="chap$it.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val spine = (1..chapterCount).joinToString("\n") { """    <itemref idref="c$it"/>""" }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>夜航船</dc:title>
                <dc:creator>张三</dc:creator>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
            $items
              </manifest>
              <spine>
            $spine
              </spine>
            </package>
        """.trimIndent()
    }

    private fun nav(entries: List<Pair<String, String>>): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body>
            <nav epub:type="toc">
              <ol>
        ${entries.joinToString("\n") { """        <li><a href="${it.first}">${it.second}</a></li>""" }}
              </ol>
            </nav>
          </body>
        </html>
    """.trimIndent()

    private fun chapter(title: String, body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>ignored</title><style>p{color:red}</style></head>
          <body>
            <h2>$title</h2>
            $body
          </body>
        </html>
    """.trimIndent()

    // ------------------------------------------------------------------ 测试

    @Test
    fun `parses container opf metadata spine and nav titles`() {
        val epub = buildEpub(
            "a.epub",
            epub3Opf(3),
            mapOf(
                "OEBPS/nav.xhtml" to nav(
                    listOf(
                        "chap1.xhtml" to "第一章 起航",
                        "chap2.xhtml" to "第二章 风雪",
                        "chap3.xhtml" to "第三章 归途",
                    ),
                ).toByteArray(),
                // 正文里的小标题故意和目录标题**不一样**：这样如果目录解析坏了，
                // 断言就会失败（原来两者相同，目录坏了也能靠正文首行蒙对）
                "OEBPS/chap1.xhtml" to chapter("正文标题甲", "<p>第一段。</p><p>第二段。</p>").toByteArray(),
                "OEBPS/chap2.xhtml" to chapter("正文标题乙", "<p>风雪很大。</p>").toByteArray(),
                "OEBPS/chap3.xhtml" to chapter("正文标题丙", "<p>回家了。</p>").toByteArray(),
                "OEBPS/cover.png" to tinyPng,
            ),
        )

        val book = EpubParser.parse(epub)

        assertEquals("夜航船", book.title)
        assertEquals("张三", book.author)
        assertEquals(3, book.chapters.size)
        assertEquals("第一章 起航", book.chapters[0].title)
        assertEquals("第二章 风雪", book.chapters[1].title)
        assertEquals("第三章 归途", book.chapters[2].title)
        assertArrayEquals(tinyPng, book.coverBytes)
        // 正文：小标题行 + 每段一行，段落不带缩进（缩进交给阅读器排版设置）
        assertEquals("正文标题甲\n第一段。\n第二段。", book.chapters[0].text)
        // head/style 里的内容必须丢掉
        assertFalse(book.chapters[0].text.contains("ignored"))
        assertFalse(book.chapters[0].text.contains("color:red"))
    }

    @Test
    fun `falls back to ncx when there is no epub3 nav`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>旧书</dc:title>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="chap2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="c1"/>
                <itemref idref="c2"/>
              </spine>
            </package>
        """.trimIndent()
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1"><navLabel><text>楔子</text></navLabel><content src="chap1.xhtml"/></navPoint>
                <navPoint id="n2"><navLabel><text>正文</text></navLabel><content src="chap2.xhtml"/></navPoint>
              </navMap>
            </ncx>
        """.trimIndent()
        val epub = buildEpub(
            "b.epub",
            opf,
            mapOf(
                "OEBPS/toc.ncx" to ncx.toByteArray(),
                "OEBPS/chap1.xhtml" to chapter("", "<p>楔子内容。</p>").toByteArray(),
                "OEBPS/chap2.xhtml" to chapter("", "<p>正文内容。</p>").toByteArray(),
            ),
        )

        val book = EpubParser.parse(epub)

        assertEquals("旧书", book.title)
        assertEquals("楔子", book.chapters[0].title)
        assertEquals("正文", book.chapters[1].title)
        assertNull("没有封面图时应当是 null", book.coverBytes)
    }

    @Test
    fun `decodes named entities and collapses whitespace`() {
        val body = """
            <p>他&nbsp;说：&ldquo;走吧&rdquo;&mdash;&mdash;然后&hellip;&hellip;</p>
            <p>   多   空格   要   收敛   </p>
        """.trimIndent()
        val epub = buildEpub(
            "c.epub",
            epub3Opf(1),
            mapOf(
                "OEBPS/nav.xhtml" to nav(listOf("chap1.xhtml" to "第一章")).toByteArray(),
                "OEBPS/chap1.xhtml" to chapter("第一章", body).toByteArray(),
            ),
        )

        val text = EpubParser.parse(epub).chapters[0].text

        assertEquals(
            "第一章\n他 说：“走吧”——然后……\n多 空格 要 收敛",
            text,
        )
    }

    @Test
    fun `survives malformed xhtml by falling back to a lenient extraction`() {
        // 标签没闭合：DOM 会解析失败，必须还能把正文捞出来
        val broken = """
            <html><body>
            <h1>第一章</h1>
            <p>第一段<b>加粗
            <p>第二段
            </body></html>
        """.trimIndent()
        val epub = buildEpub(
            "d.epub",
            epub3Opf(1),
            mapOf(
                "OEBPS/nav.xhtml" to nav(listOf("chap1.xhtml" to "第一章")).toByteArray(),
                "OEBPS/chap1.xhtml" to broken.toByteArray(),
            ),
        )

        val text = EpubParser.parse(epub).chapters[0].text

        assertTrue("应当包含第一段，实际：$text", text.contains("第一段"))
        assertTrue("应当包含第二段，实际：$text", text.contains("第二段"))
        assertFalse("标签不该留在正文里", text.contains("<p>"))
    }

    @Test
    fun `skips spine entries marked linear no`() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
              <manifest>
                <item id="coverpage" href="cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="coverpage" linear="no"/>
                <itemref idref="c1"/>
              </spine>
            </package>
        """.trimIndent()
        val epub = buildEpub(
            "e.epub",
            opf,
            mapOf(
                "OEBPS/cover.xhtml" to chapter("封面页", "<p>出版社</p>").toByteArray(),
                "OEBPS/chap1.xhtml" to chapter("第一章", "<p>正文</p>").toByteArray(),
            ),
        )

        val chapters = EpubParser.parse(epub).chapters

        assertEquals(1, chapters.size)
        assertEquals("正文", chapters[0].text.lines().last())
    }

    @Test
    fun `detects epub and rejects plain zip or txt`() {
        val real = buildEpub(
            "real.epub",
            epub3Opf(1),
            mapOf(
                "OEBPS/nav.xhtml" to nav(listOf("chap1.xhtml" to "第一章")).toByteArray(),
                "OEBPS/chap1.xhtml" to chapter("第一章", "<p>x</p>").toByteArray(),
            ),
        )
        assertTrue("合规 EPUB 应当被识别", EpubParser.looksLikeEpub(real))

        // 改名成 .epub 的普通 zip：扩展名对但没有 container.xml
        val fake = File(temp.newFolder(), "fake.epub")
        ZipOutputStream(fake.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("hi".toByteArray())
            zip.closeEntry()
        }
        assertFalse("普通 zip 改名不该被当成 EPUB", EpubParser.looksLikeEpub(fake))

        val txt = File(temp.newFolder(), "a.txt")
        txt.writeText("第一章 测试")
        assertFalse(EpubParser.looksLikeEpub(txt))

        // 文件不存在也不该抛
        assertFalse(EpubParser.looksLikeEpub(File(temp.root, "nope.epub")))
    }

    @Test
    fun `resolves relative hrefs and percent encoding`() {
        assertEquals("OEBPS/chap1.xhtml", EpubParser.resolvePath("OEBPS", "chap1.xhtml"))
        assertEquals("OEBPS/text/chap1.xhtml", EpubParser.resolvePath("OEBPS", "text/chap1.xhtml"))
        assertEquals("OEBPS/text/chap2.xhtml", EpubParser.resolvePath("OEBPS/text", "../text/chap2.xhtml"))
        assertEquals("OEBPS/other/chap3.xhtml", EpubParser.resolvePath("OEBPS/text", "../other/chap3.xhtml"))
        assertEquals("OEBPS/第一章.xhtml", EpubParser.resolvePath("OEBPS", "%E7%AC%AC%E4%B8%80%E7%AB%A0.xhtml"))
        assertEquals("Images/a b.png", EpubParser.resolvePath("", "Images/a%20b.png"))
    }
}

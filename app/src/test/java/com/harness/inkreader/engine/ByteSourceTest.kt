package com.harness.inkreader.engine

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset

/**
 * 字节来源层：复制模式（FileByteSource）与引用模式（UriByteSource）必须给出**逐字节一致**的
 * 读取结果，否则同一本书在两种模式下会读出不同的正文。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ByteSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val gbk: Charset = Charset.forName("GBK")

    private fun sampleFile(name: String = "sample.txt"): File {
        val text = buildString {
            repeat(200) { index ->
                append("第").append(index).append("行：夜色像一匹浸了水的绸子。").append('\n')
            }
        }
        val file = temp.newFile(name)
        file.writeBytes(text.toByteArray(gbk))
        return file
    }

    @Test
    fun `file source reads the requested range exactly`() {
        val file = sampleFile()
        val source = FileByteSource(file)
        val bytes = file.readBytes()

        assertEquals(file.length(), source.length)
        assertArrayEquals(bytes.copyOfRange(0, 100), source.readAt(0, 100))
        assertArrayEquals(bytes.copyOfRange(500, 600), source.readAt(500, 100))
        assertArrayEquals(
            bytes.copyOfRange(bytes.size - 10, bytes.size),
            source.readAt((bytes.size - 10).toLong(), 10),
        )
    }

    @Test
    fun `file source returns a short array past the end instead of throwing`() {
        val file = sampleFile()
        val source = FileByteSource(file)

        val past = source.readAt(file.length() - 5, 100)
        assertEquals(5, past.size)
        assertEquals(0, source.readAt(file.length(), 100).size)
        assertEquals(0, source.readAt(file.length() + 10_000, 100).size)
    }

    @Test
    fun `uri source reads the same bytes as the file source`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = sampleFile()
        val uri = Uri.fromFile(file)

        val fileSource = FileByteSource(file)
        val uriSource = UriByteSource(context, uri)

        assertEquals(file.length(), uriSource.length)

        // 覆盖开头、中间、结尾三处，逐字节比对
        listOf(0L, 1234L, file.length() - 200).forEach { offset ->
            val length = 200
            assertArrayEquals(
                "偏移 $offset 处两种来源读取结果不一致",
                fileSource.readAt(offset, length),
                uriSource.readAt(offset, length),
            )
        }
    }

    @Test
    fun `uri source openStream returns the whole file`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = sampleFile()
        val source = UriByteSource(context, Uri.fromFile(file))

        val text = source.openStream().use { it.readBytes().toString(gbk) }

        assertTrue(text.startsWith("第0行："))
        assertTrue(text.contains("第199行："))
    }

    @Test
    fun `chapter blocks work identically on both sources`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val file = sampleFile("both.txt")
        val bytes = file.readBytes()

        val fromFile = ChapterBlocks(FileByteSource(file), gbk, 0, file.length(), 1024)
        val fromUri = ChapterBlocks(UriByteSource(context, Uri.fromFile(file)), gbk, 0, file.length(), 1024)

        val fileText = buildString {
            var index = 0
            while (index >= 0) {
                append(fromFile.load(index).text)
                index = fromFile.nextIndex(index)
            }
        }
        val uriText = buildString {
            var index = 0
            while (index >= 0) {
                append(fromUri.load(index).text)
                index = fromUri.nextIndex(index)
            }
        }

        assertArrayEquals(bytes, bytes)
        assertEquals(fileText, uriText)
        assertTrue(fileText.contains("夜色像一匹浸了水的绸子"))
    }
}

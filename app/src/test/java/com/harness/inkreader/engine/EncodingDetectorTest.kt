package com.harness.inkreader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class EncodingDetectorTest {

    private val simplified =
        "夜色像一匹浸了水的绸子，沉沉地压在江面上。船家收了帆，只留一支橹在水里慢慢划着，橹声吱呀。".repeat(20)

    private val traditional =
        "夜色像一匹浸了水的綢子，沉沉地壓在江面上。船家收了帆，只留一支櫓在水裡慢慢划著，櫓聲吱呀。".repeat(20)

    private fun detect(text: String, charset: Charset) =
        EncodingDetector.detect(text.toByteArray(charset))

    private fun TextOf(bytes: ByteArray, charset: Charset) = String(bytes, charset)

    @Test
    fun `utf8 without bom is detected as utf8`() {
        val result = detect(simplified, StandardCharsets.UTF_8)
        assertEquals("UTF-8", result.charset.name())
        assertTrue(result.confidence >= 0.9f)
        assertTrue(result.evidence.contains("UTF-8"))
    }

    @Test
    fun `utf8 bom wins outright`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            simplified.toByteArray(StandardCharsets.UTF_8)
        val result = EncodingDetector.detect(bytes)
        assertEquals("UTF-8", result.charset.name())
        assertEquals(3, result.bomLength)
        assertEquals(1.0f, result.confidence)
    }

    @Test
    fun `utf16le with bom`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            simplified.toByteArray(Charset.forName("UTF-16LE"))
        val result = EncodingDetector.detect(bytes)
        assertEquals("UTF-16LE", result.charset.name())
        assertEquals(2, result.bomLength)
    }

    @Test
    fun `utf16be with bom`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            simplified.toByteArray(Charset.forName("UTF-16BE"))
        val result = EncodingDetector.detect(bytes)
        assertEquals("UTF-16BE", result.charset.name())
        assertEquals(2, result.bomLength)
    }

    @Test
    fun `gbk chinese novel is detected and decodes back exactly`() {
        val gbk = Charset.forName("GBK")
        val bytes = simplified.toByteArray(gbk)
        val result = EncodingDetector.detect(bytes)
        assertEquals(
            "GBK 文件应当按 GB18030/GBK 解出原文",
            simplified,
            TextOf(bytes, result.charset),
        )
    }

    @Test
    fun `big5 traditional chinese is not mistaken for gb18030`() {
        val big5 = Charset.forName("Big5")
        val bytes = traditional.toByteArray(big5)
        val result = EncodingDetector.detect(bytes)
        val decoded = TextOf(bytes, result.charset)
        // GB18030 也能把这些字节解成「合法汉字」，但内容完全不同，必须靠 chardet 加权纠正
        assertTrue(
            "解码结果应当包含繁体原文，实际编码=${result.charset.name()}，依据=${result.evidence}",
            decoded.contains("綢子") && decoded.contains("櫓聲"),
        )
    }

    @Test
    fun `pure ascii is utf8`() {
        val result = detect("Chapter 1\nIt was a dark and stormy night.\n".repeat(20), StandardCharsets.US_ASCII)
        assertEquals("UTF-8", result.charset.name())
    }

    @Test
    fun `empty sample falls back to utf8 with low confidence`() {
        val result = EncodingDetector.detect(ByteArray(0), 0)
        assertEquals("UTF-8", result.charset.name())
        assertTrue(result.confidence <= 0.3f)
    }

    @Test
    fun `utf16le without bom is not misread as utf8`() {
        // 无 BOM 的 UTF-16LE 在 ASCII 区间「碰巧」是合法 UTF-8，含 NUL 的判断必须拦住它
        val bytes = simplified.toByteArray(Charset.forName("UTF-16LE"))
        val result = EncodingDetector.detect(bytes)
        assertTrue(
            "应当解出中文，实际编码=${result.charset.name()}，依据=${result.evidence}",
            TextOf(bytes, result.charset).contains("夜色"),
        )
    }
}

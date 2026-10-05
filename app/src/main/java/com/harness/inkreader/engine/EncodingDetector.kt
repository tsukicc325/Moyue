package com.harness.inkreader.engine

import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class EncodingDetection(
    val charset: Charset,
    /** 0..1，越高越可信。低置信度时上层应提示用户手动指定编码。 */
    val confidence: Float,
    /** 人类可读的判断依据，会显示在书籍详情里方便排查乱码。 */
    val evidence: String,
    val bomLength: Int,
    /** 是否由用户手动指定（手动指定时不走探测）。 */
    val manual: Boolean = false,
)

/**
 * 中文 txt 最常见的乱码来源就是编码猜错。
 *
 * 判定顺序：
 * 1. BOM —— 一票定音。
 * 2. 严格 UTF-8 校验通过且样本里没有 NUL 字节 —— 直接判 UTF-8。
 *    GBK/Big5 的字节串在 64KB 尺度上几乎不可能整体通过严格 UTF-8 校验，所以这个信号非常强。
 *    含 NUL 时除外：无 BOM 的 UTF-16 文件在 ASCII 区间会「碰巧」是合法 UTF-8。
 * 3. juniversalchardet。
 * 4. GB18030 兜底 + Big5 候补。
 *
 * 第 3、4 步的候选会**实际解码打分**（汉字多、替换字符少者胜）。
 * 关键点：Big5 的字节范围与 GBK 重叠，用 GB18030 去解 Big5 同样能得到合法的汉字，
 * 光靠打分分不出来，所以 chardet 的判定会获得加权，且同分时优先。
 */
object EncodingDetector {

    const val SAMPLE_BYTES = 64 * 1024

    private const val UTF8_BOM = "\uFEFF"
    private const val CHARDET_BONUS = 0.6f
    private const val UTF16_HINT_BONUS = 0.3f

    fun detect(file: File): EncodingDetection {
        val sample = ByteArray(SAMPLE_BYTES)
        val length = file.inputStream().use { it.read(sample) }.coerceAtLeast(0)
        return detect(sample, length)
    }

    fun detect(sample: ByteArray, length: Int = sample.size): EncodingDetection {
        val size = length.coerceIn(0, sample.size)
        if (size == 0) {
            return EncodingDetection(
                charset = StandardCharsets.UTF_8,
                confidence = 0.2f,
                evidence = "文件为空，默认 UTF-8",
                bomLength = 0,
            )
        }

        detectBom(sample, size)?.let { return it }

        val containsNul = (0 until size).any { sample[it].toInt() == 0 }

        if (!containsNul && isStrictUtf8(sample, size)) {
            return EncodingDetection(
                charset = StandardCharsets.UTF_8,
                confidence = 0.97f,
                evidence = "严格 UTF-8 校验通过（${size} 字节样本）",
                bomLength = 0,
            )
        }

        val candidates = LinkedHashMap<String, Candidate>()

        chardetCharset(sample, size)?.let { detected ->
            candidates[detected.name()] = Candidate(
                charset = detected,
                evidence = "juniversalchardet 判定为 ${detected.name()}",
                bonus = CHARDET_BONUS,
            )
        }

        if (containsNul) {
            // 无 BOM 的 UTF-16：样本里必然出现大量 NUL，靠这个信号补进候选
            charsetOrNull("UTF-16LE")?.let {
                candidates.putIfAbsent(
                    it.name(),
                    Candidate(it, "样本含 NUL，候选 ${it.name()}", UTF16_HINT_BONUS),
                )
            }
            charsetOrNull("UTF-16BE")?.let {
                candidates.putIfAbsent(
                    it.name(),
                    Candidate(it, "样本含 NUL，候选 ${it.name()}", UTF16_HINT_BONUS),
                )
            }
        }

        candidates.putIfAbsent(
            StandardCharsets.UTF_8.name(),
            Candidate(StandardCharsets.UTF_8, "候选：UTF-8（未通过严格校验）", 0f),
        )
        // 简体中文 txt 的绝对主力编码，GBK/GB2312 的超集，永远作为候选
        charsetOrNull("GB18030")?.let {
            candidates.putIfAbsent(
                it.name(),
                Candidate(it, "候选：GB18030（GBK/GB2312 超集）", 0f),
            )
        }
        // 繁体小说常见
        charsetOrNull("Big5")?.let {
            candidates.putIfAbsent(it.name(), Candidate(it, "候选：Big5（繁体）", 0f))
        }

        val scored = candidates.values.map { candidate ->
            Scored(candidate, scoreSample(sample, size, candidate.charset) + candidate.bonus)
        }
        // maxByOrNull 返回第一个最大值，因此同分时靠前的候选胜出（chardet 优先）
        val best = scored.maxByOrNull { it.value }
            ?: return EncodingDetection(
                charset = StandardCharsets.UTF_8,
                confidence = 0.3f,
                evidence = "没有可用候选，退回 UTF-8",
                bomLength = 0,
            )
        val runnerUp = scored.filter { it !== best }.maxOfOrNull { it.value } ?: 0f
        val margin = best.value - runnerUp

        val confidence = when {
            margin >= 1.5f -> 0.95f
            margin >= 0.6f -> 0.85f
            margin >= 0.15f -> 0.7f
            else -> 0.5f
        }.let { if (best.candidate.bonus > 0f) maxOf(it, 0.7f) else it }

        return EncodingDetection(
            charset = best.candidate.charset,
            confidence = confidence.coerceIn(0f, 1f),
            evidence = "%s（打分 %.2f，与次优差距 %.2f）".format(
                best.candidate.evidence,
                best.value,
                margin,
            ),
            bomLength = 0,
        )
    }

    private class Candidate(
        val charset: Charset,
        val evidence: String,
        val bonus: Float,
    )

    private class Scored(val candidate: Candidate, val value: Float)

    private fun detectBom(sample: ByteArray, size: Int): EncodingDetection? {
        fun check(vararg expected: Int): Boolean {
            if (size < expected.size) return false
            for (i in expected.indices) {
                if (sample[i].toInt() and 0xFF != expected[i]) return false
            }
            return true
        }

        // UTF-32LE 必须在 UTF-16LE 之前判断：FF FE 00 00 也是合法的 UTF-16LE 前缀
        if (check(0xFF, 0xFE, 0x00, 0x00)) return bom("UTF-32LE", 4)
        if (check(0x00, 0x00, 0xFE, 0xFF)) return bom("UTF-32BE", 4)
        if (check(0xEF, 0xBB, 0xBF)) return bom("UTF-8", 3)
        if (check(0xFF, 0xFE)) return bom("UTF-16LE", 2)
        if (check(0xFE, 0xFF)) return bom("UTF-16BE", 2)
        return null
    }

    private fun bom(name: String, length: Int): EncodingDetection = EncodingDetection(
        charset = charsetOrNull(name) ?: StandardCharsets.UTF_8,
        confidence = 1.0f,
        evidence = "$name BOM",
        bomLength = length,
    )

    /** 末尾可能是被截断的多字节字符，校验时留出 3 字节余量。 */
    private fun isStrictUtf8(sample: ByteArray, size: Int): Boolean {
        val usable = (size - 3).coerceAtLeast(0)
        if (usable == 0) return true
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(sample, 0, usable))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun chardetCharset(sample: ByteArray, size: Int): Charset? {
        return try {
            val detector = UniversalDetector(null)
            detector.handleData(sample, 0, size)
            detector.dataEnd()
            val name = detector.detectedCharset ?: return null
            when (name.uppercase()) {
                "GB2312", "GBK", "GB18030", "X-GBK" -> charsetOrNull("GB18030")
                "UTF-8", "UTF8" -> StandardCharsets.UTF_8
                "BIG5", "BIG-5", "BIG5-HKSCS" -> charsetOrNull("Big5")
                "UTF-16LE", "UTF-16BE", "UTF-16" -> charsetOrNull(name)
                // windows-1252 这类单字节猜测对中文小说没有意义，直接丢弃
                "WINDOWS-1252", "ISO-8859-1", "ASCII", "US-ASCII" -> null
                else -> charsetOrNull(name)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (_: Exception) {
        null
    }

    /**
     * 给一个候选编码打分：解码样本后统计
     * 汉字/全角标点（加分）、替换字符与控制字符（重罚）。
     */
    internal fun scoreSample(sample: ByteArray, size: Int, charset: Charset): Float {
        val text = try {
            String(sample, 0, size, charset)
        } catch (_: Exception) {
            return Float.NEGATIVE_INFINITY
        }
        if (text.isEmpty()) return 0f

        var good = 0
        var bad = 0
        var replacementLike = 0
        for (ch in text) {
            val code = ch.code
            when {
                ch == '\uFFFD' -> {
                    replacementLike++
                    bad += 8
                }
                code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> bad += 4
                code in 0x4E00..0x9FFF -> good += 3          // 基本汉字
                code in 0x3400..0x4DBF -> good += 3          // 扩展 A
                code in 0xF900..0xFAFF -> good += 2          // 兼容汉字
                code in 0x3000..0x303F -> good += 2          // 中日韩标点
                code in 0xFF00..0xFFEF -> good += 2          // 全角
                code < 0x80 -> good += 1                     // ASCII
                // 拉丁乱码（把 GBK 字节当单字节编码解出来的结果）不给分
                else -> good += 0
            }
        }
        // 替换字符比例过高直接判死
        if (replacementLike * 20 > text.length) bad += text.length
        return (good - bad).toFloat() / text.length
    }

    /** 去掉可能的 BOM 前缀，便于上层从文件头开始解析正文。 */
    fun stripBom(text: String): String =
        if (text.startsWith(UTF8_BOM)) text.substring(1) else text
}

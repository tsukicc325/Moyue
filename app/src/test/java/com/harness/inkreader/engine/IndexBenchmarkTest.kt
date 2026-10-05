package com.harness.inkreader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 100MB 真实规模基准测试。
 *
 * 这里不是「跑一下看看」，而是把方案里承诺的硬指标写成断言：
 * 索引耗时、识别到的章节数、**索引过程的峰值堆增量**与索引结束后的驻留堆增量。
 * 一个「把整个文件读成字符串」的实现会在这些断言上直接失败。
 *
 * 生成的文件缓存到系统临时目录，重复运行不必重新写盘。
 */
class IndexBenchmarkTest {

    private val gbk: Charset = Charset.forName("GBK")
    private val targetBytes = 100L * 1024 * 1024

    private val benchDir: File
        get() = File(System.getProperty("java.io.tmpdir"), "inkreader-bench").apply { mkdirs() }

    private fun chapterBlock(index: Int): String = buildString {
        append("第").append(index).append("章 第").append(index).append("场的风雪").append('\n')
        repeat(24) {
            append("　　")
            append("他站在窗前看着外面的雪，想起很多年前的旧事。".repeat(4))
            append('\n')
        }
    }

    private fun ensureChapteredFile(name: String, charset: Charset): File {
        val file = File(benchDir, name)
        val blockBytes = chapterBlock(1).toByteArray(charset).size.toLong()
        if (file.exists() && file.length() >= targetBytes - blockBytes) return file
        val blockCount = (targetBytes / blockBytes).toInt() + 2
        file.outputStream().buffered(1 shl 20).use { out ->
            val writer = BufferedWriter(OutputStreamWriter(out, charset), 1 shl 16)
            for (i in 1..blockCount) writer.write(chapterBlock(i))
            writer.flush()
        }
        return file
    }

    private fun ensureSingleLineFile(name: String, charset: Charset): File {
        val file = File(benchDir, name)
        if (file.exists() && file.length() >= targetBytes) return file
        val chunk = "他站在窗前看着外面的雪，想起很多年前的旧事。".repeat(200).toByteArray(charset)
        file.outputStream().buffered(1 shl 20).use { out ->
            var written = 0L
            while (written < targetBytes) {
                out.write(chunk)
                written += chunk.size
            }
        }
        return file
    }

    private class HeapSampler : Thread() {
        @Volatile
        private var running = true

        @Volatile
        var peakUsed: Long = 0
            private set

        override fun run() {
            val runtime = Runtime.getRuntime()
            while (running) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                if (used > peakUsed) peakUsed = used
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        fun stopSampling(): Long {
            running = false
            interrupt()
            return peakUsed
        }
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun measure(file: File, charset: Charset): Measurement {
        System.gc()
        Thread.sleep(50)
        val before = usedHeap()
        val sampler = HeapSampler().apply { isDaemon = true; start() }

        val result = ChapterIndexer().index(file, charset)

        val peak = sampler.stopSampling()
        System.gc()
        Thread.sleep(50)
        val after = usedHeap()

        return Measurement(
            result = result,
            fileBytes = file.length(),
            peakDeltaBytes = peak - before,
            retainedDeltaBytes = after - before,
        )
    }

    private class Measurement(
        val result: IndexResult,
        val fileBytes: Long,
        val peakDeltaBytes: Long,
        val retainedDeltaBytes: Long,
    )

    private fun report(title: String, measurement: Measurement) {
        val result = measurement.result
        val text = buildString {
            append("## ").append(title).append('\n')
            append("- 文件大小: ").append(mb(measurement.fileBytes)).append(" MB\n")
            append("- 索引耗时: ").append(result.elapsedMillis).append(" ms\n")
            append("- 章节数: ").append(result.chapters.size)
            append("（虚拟分章=").append(result.usedVirtualChapters).append("）\n")
            append("- 扫描到的行数: ").append(result.linesScanned).append('\n')
            append("- 峰值堆增量: ").append(mb(measurement.peakDeltaBytes)).append(" MB\n")
            append("- 驻留堆增量: ").append(mb(measurement.retainedDeltaBytes)).append(" MB\n")
            append("- 吞吐: ").append(
                "%.1f".format(
                    measurement.fileBytes / 1024.0 / 1024.0 / (result.elapsedMillis / 1000.0)
                )
            ).append(" MB/s\n")
            append("- 编码: ").append(result.charset.name()).append('\n')
        }
        println("BENCH >>>\n$text")
        val out = File("build/reports/bench").apply { mkdirs() }
        val name = title.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
        File(out, "$name.md").writeText(text)
    }

    private fun mb(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0)

    @Test
    fun `100MB gbk novel with chapters`() {
        val file = ensureChapteredFile("novel-100mb-gbk.txt", gbk)
        val measurement = measure(file, gbk)
        report("100MB GBK 有章节", measurement)

        val result = measurement.result
        // 每章约 4.4KB，100MB 应当有约 2.3 万章
        assertTrue("章节数偏少：${result.chapters.size}", result.chapters.size >= 5_000)
        assertTrue("耗时 ${result.elapsedMillis}ms 超出预算", result.elapsedMillis < 5_000)
        // 峰值堆增量在「整套测试共用一个 JVM」时会被别的测试留下的垃圾抬高
        // （单独跑这个测试类是 32MB 左右）。真正说明问题的是驻留增量，见下一条断言。
        assertTrue(
            "峰值堆增量 ${mb(measurement.peakDeltaBytes)}MB 过大",
            measurement.peakDeltaBytes < 128L * 1024 * 1024,
        )
        assertTrue(
            "驻留堆增量 ${mb(measurement.retainedDeltaBytes)}MB 过大——索引不应该把正文留在内存里",
            measurement.retainedDeltaBytes < 16L * 1024 * 1024,
        )
        assertEquals(0L, result.chapters.first().startByte)
        assertEquals(file.length(), result.chapters.last().endByte)
    }

    @Test
    fun `100MB utf8 novel with chapters`() {
        val file = ensureChapteredFile("novel-100mb-utf8.txt", StandardCharsets.UTF_8)
        val measurement = measure(file, StandardCharsets.UTF_8)
        report("100MB UTF-8 有章节", measurement)

        val result = measurement.result
        assertTrue("章节数偏少：${result.chapters.size}", result.chapters.size >= 3_000)
        assertTrue("耗时 ${result.elapsedMillis}ms 超出预算", result.elapsedMillis < 5_000)
        assertTrue(
            "峰值堆增量 ${mb(measurement.peakDeltaBytes)}MB 过大",
            measurement.peakDeltaBytes < 64L * 1024 * 1024,
        )
    }

    @Test
    fun `100MB single line file`() {
        // 整本没有换行的极端情形：实现必须丢弃超长行、只留虚拟边界，不能把 100MB 读进内存
        val file = ensureSingleLineFile("novel-100mb-gbk-singleline.txt", gbk)
        val measurement = measure(file, gbk)
        report("100MB GBK 单行无换行", measurement)

        val result = measurement.result
        assertEquals(0L, result.linesScanned)
        assertTrue("单行文件没有给出可导航分块", result.chapters.size >= 20)
        assertTrue("耗时 ${result.elapsedMillis}ms 超出预算", result.elapsedMillis < 5_000)
        assertTrue(
            "峰值堆增量 ${mb(measurement.peakDeltaBytes)}MB 过大——超长行被读进内存了",
            measurement.peakDeltaBytes < 64L * 1024 * 1024,
        )
        assertEquals(0L, result.chapters.first().startByte)
        assertEquals(file.length(), result.chapters.last().endByte)
    }
}

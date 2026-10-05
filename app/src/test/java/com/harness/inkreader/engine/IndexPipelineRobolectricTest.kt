package com.harness.inkreader.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.harness.inkreader.data.BookEntity
import com.harness.inkreader.data.ChapterEntity
import com.harness.inkreader.data.InkDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * M2 端到端验收：真实文件 → 探测编码 → 建章节索引 → 落 Room → 重载后
 * **按数据库里的字节偏移 seek 回读**，验证整条链路对得上。
 *
 * 这是「用户导入一本 GBK 小说，关掉 App 再打开，仍然精确停在上次那一章」的最小闭环。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IndexPipelineRobolectricTest {

    private val gbk: Charset = Charset.forName("GBK")
    private val body = "他站在窗前看着外面的雪，想起很多年前的旧事。".repeat(3)

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
    fun `real gbk file flows from detection to persisted byte offsets`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val chapterCount = 20
        val text = buildString {
            for (i in 1..chapterCount) {
                append("第").append(i).append("章 第").append(i).append("节的风雪").append('\n')
                repeat(4) { append("　　").append(body).append('\n') }
                append('\n')
            }
        }
        val file = File(context.cacheDir, "夜航船.txt")
        file.writeBytes(text.toByteArray(gbk))

        // 1. 编码探测：GBK 文件不能出现乱码
        val detection = EncodingDetector.detect(file)
        val decoded = String(file.readBytes(), detection.charset)
        assertTrue(
            "编码探测失败（${detection.charset.name()}，依据=${detection.evidence}）",
            decoded.contains("他站在窗前看着外面的雪"),
        )

        // 2. 章节索引
        val index = ChapterIndexer().index(file, detection.charset)
        assertEquals(chapterCount, index.chapters.size)
        assertTrue(!index.usedVirtualChapters)

        // 3. 落库
        val db = InkDatabase.inMemory(context)
        try {
            val bookId = db.books().insert(
                BookEntity(
                    title = "夜航船",
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    fileMtime = file.lastModified(),
                    encoding = detection.charset.name(),
                    totalBytes = index.totalBytes,
                    chapterCount = index.chapters.size,
                    usedVirtualChapters = index.usedVirtualChapters,
                    indexed = true,
                    addedAt = System.currentTimeMillis(),
                )
            )
            db.chapters().insertAll(
                index.chapters.map {
                    ChapterEntity(bookId, it.idx, it.title, it.startByte, it.endByte)
                }
            )

            // 4. 重载后按库里的偏移 seek 回读，必须仍然落在章节标题行上
            val reloaded = db.chapters().byBook(bookId)
            assertEquals(chapterCount, reloaded.size)
            reloaded.forEachIndexed { i, stored ->
                assertEquals(index.chapters[i].title, stored.title)
                assertEquals(index.chapters[i].startByte, stored.startByte)
                assertEquals(index.chapters[i].endByte, stored.endByte)
                assertEquals(
                    "第 ${i + 1} 章按库里的偏移回读不对",
                    stored.title,
                    lineAt(file, stored.startByte, detection.charset),
                )
            }

            // 5. 按字节偏移恢复阅读位置
            val target = index.chapters[7]
            val located = db.chapters().chapterAtByte(bookId, target.startByte + 10)
            assertEquals(7, located?.idx)

            // 6. 保存并读回阅读进度
            db.progress().upsert(
                com.harness.inkreader.data.ProgressEntity(
                    bookId = bookId,
                    chapterIdx = 7,
                    blockIndex = 0,
                    charOffsetInBlock = 10,
                    percent = 0.4f,
                    updatedAt = 1L,
                )
            )
            val progress = db.progress().byBook(bookId)
            assertEquals(7, progress?.chapterIdx)
            assertEquals(
                "恢复位置应当落在第八章",
                "第8章 第8节的风雪",
                lineAt(file, located!!.startByte, detection.charset),
            )
        } finally {
            db.close()
        }
    }
}

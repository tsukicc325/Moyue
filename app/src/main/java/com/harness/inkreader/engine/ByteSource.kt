package com.harness.inkreader.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * 随机读取的字节来源。
 *
 * 引入这一层的目的是让「复制到私有目录」与「只引用原文件（content://）」两种导入模式
 * **共用同一条读取链路**：索引、分块、解码都只面对 [readAt] / [openStream]。
 */
interface ByteSource {

    /** 总字节数；未知时返回 -1（某些云盘 provider 不提供长度）。 */
    val length: Long

    /** 读取 [offset] 起的至多 [length] 个字节；到文件末尾会返回更短的数组。 */
    fun readAt(offset: Long, length: Int): ByteArray

    /** 顺序读取（章节索引用）。每次调用都返回一个新的流。 */
    fun openStream(): InputStream

    /** 展示给用户的来源描述，出现在书籍详情里。 */
    fun describe(): String
}

class FileByteSource(private val file: File) : ByteSource {

    override val length: Long get() = file.length()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val size = file.length()
        if (offset >= size) return ByteArray(0)
        val wanted = minOf(length.toLong(), size - offset).toInt()
        if (wanted <= 0) return ByteArray(0)
        RandomAccessFile(file, "r").use { access ->
            access.seek(offset)
            val buffer = ByteArray(wanted)
            access.readFully(buffer)
            return buffer
        }
    }

    override fun openStream(): InputStream = file.inputStream()

    override fun describe(): String = "文件：${file.absolutePath}"
}

/**
 * `content://` 来源。
 *
 * 快路径走 `openFileDescriptor` + `FileChannel.position`，本地文件是真正的随机读；
 * 拿不到可 seek 的描述符时（部分网盘 provider 只能顺序读）退化为
 * 「重新打开流 → 跳过前 N 字节」，功能正确但会慢，这一点在导入时已向用户说明。
 */
class UriByteSource(
    private val context: Context,
    private val uri: Uri,
) : ByteSource {

    override val length: Long by lazy { resolveLength() }

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val viaDescriptor = runCatching { readViaDescriptor(offset, length) }.getOrNull()
        if (viaDescriptor != null) return viaDescriptor
        return openStream().use { input ->
            skipFully(input, offset)
            readUpTo(input, length)
        }
    }

    override fun openStream(): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: error("无法打开 $uri")

    override fun describe(): String = "原文件：$uri"

    private fun readViaDescriptor(offset: Long, length: Int): ByteArray? {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use {
            FileInputStream(it.fileDescriptor).use { stream ->
                stream.channel.position(offset)
                return readUpTo(stream, length)
            }
        }
    }

    private fun resolveLength(): Long {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > 0L) return descriptor.length
            }
        }
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    return cursor.getLong(index)
                }
            }
        }
        return -1L
    }

    private fun skipFully(input: InputStream, target: Long) {
        var remaining = target
        val scratch = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = runCatching { input.skip(remaining) }.getOrDefault(0L)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read <= 0) return
            remaining -= read
        }
    }
}

private fun readUpTo(input: InputStream, length: Int): ByteArray {
    val buffer = ByteArray(length)
    var filled = 0
    while (filled < length) {
        val read = input.read(buffer, filled, length - filled)
        if (read <= 0) break
        filled += read
    }
    return if (filled == length) buffer else buffer.copyOf(filled)
}

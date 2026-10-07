package com.harness.inkreader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 自定义封面：把用户选的图片**复制进应用私有目录**，等比缩小后存成 JPEG。
 *
 * 为什么是复制而不是记住 URI：封面要在书架上长期显示，而相册/下载目录里的原图随时可能被
 * 删掉，SAF 的临时授权重启后也会失效。封面很小（最长边 640px、通常几十 KB），
 * 复制一份最省心，也和外接存储的权限模型解耦。
 *
 * 全部操作都在 IO 线程；失败一律返回 null / 静默返回，不抛给界面。
 */
object Covers {

    private const val DIR = "covers"

    /** 最长边缩到这么多像素。书架上的封面只有几十 dp，640 已远超实需。 */
    const val MAX_EDGE_PX = 640

    private const val QUALITY = 88

    fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context, bookId: Long): File = File(dir(context), "cover_$bookId.jpg")

    /**
     * 纯逻辑：路径有效且文件存在就返回它，否则 null。
     * 书架据此决定「显示自定义封面」还是「回退到生成的渐变封面」——
     * 即使文件被外部清掉也不会崩，只是回到默认封面。
     */
    fun existing(path: String?): File? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        return if (file.isFile && file.length() > 0L) file else null
    }

    /** 导入一张封面，成功返回新文件的绝对路径。 */
    suspend fun importCover(context: Context, bookId: Long, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            val decoded = decode(context, uri) ?: return@withContext null
            writeCover(context, bookId, decoded)
        }

    /**
     * 从内存字节导入封面 —— EPUB 里抽出来的封面图就是字节，没有 URI。
     * 不走 EXIF 转正：EPUB 里的封面图不是相机照片。
     */
    suspend fun importCoverBytes(context: Context, bookId: Long, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            val decoded = decodeBytes(bytes) ?: return@withContext null
            writeCover(context, bookId, decoded)
        }

    /** 缩放后写入封面文件（先临时文件再改名，失败不留半张图），返回新路径。 */
    private fun writeCover(context: Context, bookId: Long, decoded: Bitmap): String? {
        val scaled = scaleDown(decoded)
        val target = fileFor(context, bookId)
        // 先写临时文件再改名：中途失败也不会在书架上留下半张图
        val temp = File(target.parentFile, "${target.name}.tmp")
        val written = runCatching {
            FileOutputStream(temp).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
        }.getOrDefault(false)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (!written) {
            temp.delete()
            return null
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return null
        }
        return target.absolutePath
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    /** 等比缩到 [MAX_EDGE_PX] 以内；本来就够小则原样返回。 */
    internal fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE_PX) return bitmap
        val ratio = MAX_EDGE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 更好（自带 EXIF 转正、能直接按目标尺寸解码），但它并非到处都可用
            // —— 测试环境（Robolectric）里它就是 stub，会抛 "Only supported on Android"。
            // 所以失败就退回 BitmapFactory，两条路都留着。
            runCatching { decodeModern(context, uri) }.getOrNull()?.let { return it }
        }
        return runCatching { decodeLegacy(context, uri) }.getOrNull()
    }

    /** 字节版本的解码，同样保留 ImageDecoder → BitmapFactory 两条路。 */
    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { decodeModernBytes(bytes) }.getOrNull()?.let { return it }
        }
        return runCatching { decodeLegacyBytes(bytes) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModernBytes(bytes: ByteArray): Bitmap? {
        val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_EDGE_PX) {
                val ratio = MAX_EDGE_PX.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * ratio).toInt().coerceAtLeast(1),
                    (info.size.height * ratio).toInt().coerceAtLeast(1),
                )
            }
        }
    }

    private fun decodeLegacyBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((longest / MAX_EDGE_PX).coerceAtLeast(1))
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** API 28+：ImageDecoder 会按 EXIF 自动转正，也能直接给目标尺寸。 */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(context: Context, uri: Uri): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 软件位图：之后要压缩写文件，硬件位图取不到像素
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_EDGE_PX) {
                val ratio = MAX_EDGE_PX.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * ratio).toInt().coerceAtLeast(1),
                    (info.size.height * ratio).toInt().coerceAtLeast(1),
                )
            }
        }
    }

    /** API 26/27：先读尺寸算采样率，再按 EXIF 手动转正。 */
    private fun decodeLegacy(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((longest / MAX_EDGE_PX).coerceAtLeast(1))
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).rotationDegrees
            } ?: 0
        }.getOrDefault(0)
        if (rotation == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { decoded.recycle() }
    }
}

package com.harness.inkreader.data.settings

import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 字体文件的合法性判断。
 *
 * 只看文件头的魔数 + 最小体积，**不依赖 Android 的 Typeface**，因此这条判断可以在纯 JVM 上
 * 穷举测试。（真机上 `Typeface.createFromFile` 会再兜一层。）
 */
object FontFiles {

    private val VALID_TAGS = setOf("OTTO", "true", "ttcf", "wOFF")

    fun isLikelyFont(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_FONT_BYTES) return false
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        if (read < 4) return false
        // TrueType：0x00010000
        if (header[0].toInt() == 0x00 && header[1].toInt() == 0x01 &&
            header[2].toInt() == 0x00 && header[3].toInt() == 0x00
        ) {
            return true
        }
        val tag = String(header, StandardCharsets.US_ASCII)
        return tag in VALID_TAGS
    }

    private const val MIN_FONT_BYTES = 1024L
}

/**
 * 导入字体的仓库。只做文件管理（复制、校验、列举、删除），
 * 从 id 到 `Typeface` 的转换留给界面层，因此这里可以在纯 JVM 上测试。
 */
class FontStore(
    private val directory: File,
    private val validator: (File) -> Boolean = FontFiles::isLikelyFont,
) {

    fun directory(): File = directory

    fun listImported(): List<FontOption> {
        val files = directory.listFiles() ?: return emptyList()
        return files.filter { it.isFile && validator(it) }
            .sortedBy { it.name.lowercase() }
            .map { file ->
                FontOption(
                    id = ID_PREFIX + file.name,
                    label = file.name.substringBeforeLast('.', file.name),
                    filePath = file.absolutePath,
                )
            }
    }

    fun import(displayName: String, openStream: () -> InputStream): Result<FontOption> {
        if (!directory.exists() && !directory.mkdirs()) {
            return Result.failure(IllegalStateException("无法创建字体目录"))
        }
        val extension = displayName.substringAfterLast('.', "ttf").lowercase()
        if (extension !in ALLOWED_EXTENSIONS) {
            return Result.failure(IllegalArgumentException("只支持 ttf / otf / ttc 字体文件"))
        }
        val baseName = sanitize(displayName.substringBeforeLast('.', displayName))
        val target = File(directory, "$baseName-${UUID.randomUUID().toString().take(8)}.$extension")

        return try {
            openStream().use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (!validator(target)) {
                target.delete()
                return Result.failure(IllegalArgumentException("这不是一个有效的字体文件"))
            }
            Result.success(
                FontOption(
                    id = ID_PREFIX + target.name,
                    label = baseName,
                    filePath = target.absolutePath,
                )
            )
        } catch (failure: Throwable) {
            runCatching { target.delete() }
            Result.failure(failure)
        }
    }

    fun delete(id: String): Boolean {
        val option = listImported().firstOrNull { it.id == id } ?: return false
        val path = option.filePath ?: return false
        return runCatching { File(path).delete() }.getOrDefault(false)
    }

    /** 删掉所有已经不合法/找不到的字体文件，用于清理历史垃圾。 */
    fun prune(): Int {
        val files = directory.listFiles() ?: return 0
        var removed = 0
        files.forEach { file ->
            if (file.isFile && !validator(file)) {
                if (runCatching { file.delete() }.getOrDefault(false)) removed++
            }
        }
        return removed
    }

    private fun sanitize(name: String): String {
        val cleaned = name.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")
        return cleaned.ifBlank { "font" }.take(40)
    }

    companion object {
        const val ID_PREFIX = "file:"
        private val ALLOWED_EXTENSIONS = setOf("ttf", "otf", "ttc")
    }
}

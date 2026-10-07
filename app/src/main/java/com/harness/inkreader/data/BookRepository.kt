package com.harness.inkreader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.harness.inkreader.engine.ByteSource
import com.harness.inkreader.engine.ChapterIndexer
import com.harness.inkreader.engine.Charsets
import com.harness.inkreader.engine.EncodingDetector
import com.harness.inkreader.engine.EpubParser
import com.harness.inkreader.engine.FileByteSource
import com.harness.inkreader.engine.IndexResult
import com.harness.inkreader.engine.IndexedChapter
import com.harness.inkreader.engine.UriByteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID

data class ImportProgress(
    val phase: Phase,
    val fraction: Float,
    val detail: String = "",
) {
    enum class Phase { COPY, DETECT, INDEX, SAVE, DONE }
}

/**
 * 书籍仓库：导入 → 探测编码 → 建索引 → 落库 → 读取/保存进度。
 *
 * ## 两种存储模式
 * - [StorageMode.COPY]（默认）：复制进 `files/books/`，翻页最快、不怕原文件被删。
 *   复制过程中若发现源文件是 UTF-16/32，会**顺便转码成 UTF-8** —— 这两类编码无法按字节
 *   找换行、也无法按字节切块，转码后整条读取链路只面对 ASCII 安全编码。
 * - [StorageMode.REFERENCE]：只记住 `content://`，不占空间，但原文件失效就读不了，
 *   且部分来源只能顺序读（会慢）。UTF-16 源无法在引用模式下工作，会自动降级为复制模式。
 */
class BookRepository(
    private val context: Context,
    private val db: InkDatabase = InkDatabase.get(context),
) {

    private val booksDir: File
        get() = File(context.filesDir, BOOKS_DIR).apply { if (!exists()) mkdirs() }

    fun observeBooks(): Flow<List<BookEntity>> = db.books().observeAll()

    fun observeProgress(): Flow<List<ProgressEntity>> = db.progress().observeAll()

    suspend fun books(): List<BookEntity> = withContext(Dispatchers.IO) { db.books().all() }

    suspend fun book(bookId: Long): BookEntity? = withContext(Dispatchers.IO) { db.books().byId(bookId) }

    suspend fun chapters(bookId: Long): List<ChapterEntity> =
        withContext(Dispatchers.IO) { db.chapters().byBook(bookId) }

    suspend fun progress(bookId: Long): ProgressEntity? =
        withContext(Dispatchers.IO) { db.progress().byBook(bookId) }

    suspend fun rename(bookId: Long, title: String) = withContext(Dispatchers.IO) {
        db.books().rename(bookId, title.trim().ifEmpty { "未命名" })
    }

    suspend fun setGroup(bookId: Long, group: String?) = withContext(Dispatchers.IO) {
        db.books().setGroup(bookId, group?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * 设置自定义封面：把用户选的图复制进私有目录并缩小，成功后才写库。
     * 换了新封面会顺手删掉旧文件，不留垃圾。
     */
    suspend fun setCover(bookId: Long, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val path = Covers.importCover(context, bookId, uri) ?: return@withContext false
        val previous = db.books().byId(bookId)?.coverPath
        db.books().setCover(bookId, path)
        if (previous != null && previous != path) Covers.delete(previous)
        true
    }

    /** 移除自定义封面，回到「书名首字 + 稳定渐变色」的生成封面。 */
    suspend fun clearCover(bookId: Long) = withContext(Dispatchers.IO) {
        val previous = db.books().byId(bookId)?.coverPath
        db.books().setCover(bookId, null)
        Covers.delete(previous)
    }

    /** 阅读器按书籍记录拿到字节来源，两种存储模式在这里统一。 */
    fun byteSourceFor(book: BookEntity): ByteSource =
        if (book.storageMode == StorageMode.COPY) {
            FileByteSource(File(book.filePath))
        } else {
            UriByteSource(context, Uri.parse(book.filePath))
        }

    suspend fun byteSourceFor(bookId: Long): ByteSource? {
        val book = book(bookId) ?: return null
        return byteSourceFor(book)
    }

    suspend fun importFromFile(
        source: File,
        displayName: String? = null,
        mode: String = StorageMode.COPY,
        onProgress: (ImportProgress) -> Unit = {},
    ): Long {
        val name = displayName ?: source.name
        // EPUB 走独立路径：先解析成「规范化文本」，之后的阅读链路（分块/索引/进度/书签）
        // 与 TXT 完全共用。扩展名像 EPUB 但其实是普通 zip 或改了名的 txt 时，自动退回文本流程。
        if (isEpubName(name) && EpubParser.looksLikeEpub(source)) {
            return importEpub(source, name, mode, null, onProgress)
        }
        return importStream(
            displayName = name,
            knownLength = source.length(),
            openStream = { source.inputStream() },
            mode = mode,
            referenceUri = null,
            onProgress = onProgress,
        )
    }

    suspend fun importFromUri(
        uri: Uri,
        displayName: String? = null,
        mode: String = StorageMode.COPY,
        onProgress: (ImportProgress) -> Unit = {},
    ): Long {
        val name = displayName ?: displayNameOf(uri) ?: uri.lastPathSegment.orEmpty()
        val claimsEpub = isEpubName(name) ||
            runCatching { context.contentResolver.getType(uri) }.getOrNull() == EPUB_MIME
        if (claimsEpub) {
            // ZipFile 需要能随机访问的本地文件，所以先落一份到缓存目录看真身
            val scratch = File(context.cacheDir, "epub-${UUID.randomUUID()}.epub")
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    scratch.outputStream().use { input.copyTo(it) }
                } != null
            }.getOrDefault(false)
            if (copied && EpubParser.looksLikeEpub(scratch)) {
                if (mode == StorageMode.REFERENCE) persistReadPermission(uri)
                try {
                    return importEpub(scratch, name, mode, uri.toString(), onProgress)
                } finally {
                    scratch.delete()
                }
            }
            val scratchLength = scratch.length()
            scratch.delete()
            // 名字/类型像 EPUB 其实不是：按文本导入，不要把用户的书拒之门外
            return importStream(
                displayName = name,
                knownLength = if (scratchLength > 0L) scratchLength else sizeOf(uri),
                openStream = {
                    context.contentResolver.openInputStream(uri) ?: error("无法打开 $uri")
                },
                mode = mode,
                referenceUri = if (mode == StorageMode.REFERENCE) uri else null,
                onProgress = onProgress,
            )
        }
        if (mode == StorageMode.REFERENCE) persistReadPermission(uri)
        return importStream(
            displayName = name,
            knownLength = sizeOf(uri),
            openStream = { context.contentResolver.openInputStream(uri) ?: error("无法打开 $uri") },
            mode = mode,
            referenceUri = uri,
            onProgress = onProgress,
        )
    }

    /**
     * 导入 EPUB。
     *
     * 关键设计：EPUB 解析出来的正文会被写成一份**规范化文本**（一章一段连续 UTF-8 字节，
     * 章节表直接用 EPUB 自己的目录标题），`filePath` 指向这份文本。这样阅读器完全不知道
     * 格式差异，TXT 的代码路径一行都不用改 —— 「TXT 保持正常可用」是结构上保证的。
     */
    private suspend fun importEpub(
        source: File,
        displayName: String,
        mode: String,
        referenceUri: String?,
        onProgress: (ImportProgress) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        onProgress(ImportProgress(ImportProgress.Phase.DETECT, 0f, "正在解析 EPUB"))

        // 复制模式留下原文件副本，便于以后重新解析（重建索引、换封面）；
        // 引用模式不复制，只保存规范化文本与那个 URI。
        val storedSource = if (mode == StorageMode.REFERENCE) {
            null
        } else {
            val copy = File(booksDir, "${UUID.randomUUID()}.epub")
            runCatching {
                source.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
            }.getOrElse { failure ->
                runCatching { copy.delete() }
                throw failure
            }
            copy
        }

        try {
            onProgress(ImportProgress(ImportProgress.Phase.INDEX, 0.2f, "正在提取正文"))
            val epub = EpubParser.parse(storedSource ?: source)

            val usable = epub.chapters.filter { it.text.isNotBlank() }
            if (usable.isEmpty()) {
                error("这本书里没有提取到文字（可能是纯图片漫画，或文件已损坏）")
            }

            val normalized = File(booksDir, "${UUID.randomUUID()}.txt")
            val indexed = ArrayList<IndexedChapter>(usable.size)
            var offset = 0L
            try {
                normalized.outputStream().buffered().use { out ->
                    usable.forEachIndexed { position, chapter ->
                        val bytes = (chapter.text.trim() + "\n").toByteArray(StandardCharsets.UTF_8)
                        indexed.add(
                            IndexedChapter(
                                idx = position,
                                title = chapter.title.ifBlank { "第 ${position + 1} 部分" },
                                startByte = offset,
                                endByte = offset + bytes.size,
                            )
                        )
                        out.write(bytes)
                        offset += bytes.size
                    }
                }
            } catch (failure: Throwable) {
                runCatching { normalized.delete() }
                throw failure
            }

            onProgress(ImportProgress(ImportProgress.Phase.SAVE, 1f, "正在写入书库"))
            val index = IndexResult(
                chapters = indexed,
                totalBytes = offset,
                charset = StandardCharsets.UTF_8,
                usedVirtualChapters = false,
                elapsedMillis = 0L,
                // EPUB 的章节是解析出来的，不是扫行扫出来的；行数按正文行数记，仅供详情显示
                linesScanned = indexed.sumOf { chapter ->
                    usable[chapter.idx].text.count { it == '\n' } + 1L
                },
            )
            val bookId = persist(
                title = epub.title?.takeIf { it.isNotBlank() } ?: titleFromFileName(displayName),
                filePath = normalized.absolutePath,
                // 阅读读的是规范化文本，它永远在私有目录里，所以存储方式按复制记录
                mode = StorageMode.COPY,
                fileSize = offset,
                fileMtime = normalized.lastModified(),
                charset = StandardCharsets.UTF_8,
                detectionEvidence = "EPUB 解析" + (epub.author?.let { "，作者 $it" } ?: ""),
                encodingManual = false,
                index = index,
                orphanCopy = normalized,
                format = BookFormat.EPUB,
                sourcePath = storedSource?.absolutePath ?: referenceUri,
            )

            // 书里自带封面就用它（失败不影响导入 —— 书架会回退到生成封面）
            epub.coverBytes?.let { bytes ->
                Covers.importCoverBytes(context, bookId, bytes)?.let { path ->
                    db.books().setCover(bookId, path)
                }
            }

            // 命中去重复用了已有记录时，这次多出来的原文件副本要删掉
            val saved = db.books().byId(bookId)
            if (storedSource != null && saved?.sourcePath != storedSource.absolutePath) {
                runCatching { storedSource.delete() }
            }
            bookId
        } catch (failure: Throwable) {
            storedSource?.let { runCatching { it.delete() } }
            throw failure
        } finally {
            onProgress(ImportProgress(ImportProgress.Phase.DONE, 1f))
        }
    }

    /**
     * 批量导入（文件夹扫描后用）。单个文件失败不影响其余文件。
     * 返回成功导入的数量。
     */
    suspend fun importMany(
        candidates: List<ImportCandidate>,
        mode: String = StorageMode.COPY,
        onProgress: (done: Int, total: Int, currentName: String) -> Unit = { _, _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        var succeeded = 0
        candidates.forEachIndexed { index, candidate ->
            onProgress(index, candidates.size, candidate.name)
            val result = runCatching { importFromUri(candidate.uri, candidate.name, mode) }
            if (result.isSuccess) succeeded++
        }
        onProgress(candidates.size, candidates.size, "")
        succeeded
    }

    suspend fun scanTree(treeUri: Uri): List<ImportCandidate> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        DocumentScanner.collectTextFiles(root)
    }

    /** 重新建索引。手动改编码、文件被替换后重新导入都用它。 */
    suspend fun reindex(
        bookId: Long,
        charset: Charset? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val book = db.books().byId(bookId) ?: return@withContext false
        val source = byteSourceFor(book)
        val effective = charset ?: charsetOrUtf8(book.encoding)

        onProgress(ImportProgress(ImportProgress.Phase.INDEX, 0f, "正在重建索引"))
        val index = ChapterIndexer().index(
            openStream = { source.openStream() },
            totalBytes = source.length,
            charset = effective,
        ) { progress ->
            onProgress(
                ImportProgress(
                    ImportProgress.Phase.INDEX,
                    progress.fraction,
                    progress.currentTitle ?: "正在重建索引",
                )
            )
        }

        db.chapters().deleteByBook(bookId)
        db.chapters().insertAll(index.chapters.map { it.toEntity(bookId) })
        db.books().saveIndexInfo(
            bookId = bookId,
            encoding = effective.name(),
            manual = charset != null,
            totalBytes = index.totalBytes,
            chapterCount = index.chapters.size,
            usedVirtual = index.usedVirtualChapters,
            fileSize = if (book.storageMode == StorageMode.COPY) File(book.filePath).length() else book.fileSize,
            fileMtime = if (book.storageMode == StorageMode.COPY) File(book.filePath).lastModified() else book.fileMtime,
        )
        onProgress(ImportProgress(ImportProgress.Phase.DONE, 1f))
        true
    }

    suspend fun deleteBook(bookId: Long) = withContext(Dispatchers.IO) {
        val book = db.books().byId(bookId)
        db.chapters().deleteByBook(bookId)
        db.progress().deleteByBook(bookId)
        db.bookmarks().deleteByBook(bookId)
        db.annotations().deleteByBook(bookId)
        db.books().delete(bookId)
        if (book != null && book.storageMode == StorageMode.COPY) {
            runCatching { File(book.filePath).delete() }
        }
        // 自定义封面也要一起清掉
        Covers.delete(book?.coverPath)
    }

    suspend fun saveProgress(
        bookId: Long,
        chapterIdx: Int,
        blockIndex: Int,
        charOffsetInBlock: Int,
        percent: Float,
    ) = withContext(Dispatchers.IO) {
        db.progress().upsert(
            ProgressEntity(
                bookId = bookId,
                chapterIdx = chapterIdx,
                blockIndex = blockIndex,
                charOffsetInBlock = charOffsetInBlock,
                percent = percent.coerceIn(0f, 1f),
                updatedAt = System.currentTimeMillis(),
            )
        )
        db.books().touchLastRead(bookId, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ 书签与笔记

    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> = db.bookmarks().observeByBook(bookId)

    suspend fun bookmarks(bookId: Long): List<BookmarkEntity> =
        withContext(Dispatchers.IO) { db.bookmarks().observeByBook(bookId).first() }

    suspend fun addBookmark(
        bookId: Long,
        chapterIdx: Int,
        blockIndex: Int,
        charOffsetInBlock: Int,
        preview: String,
    ): Long = withContext(Dispatchers.IO) {
        db.bookmarks().insert(
            BookmarkEntity(
                bookId = bookId,
                chapterIdx = chapterIdx,
                blockIndex = blockIndex,
                charOffsetInBlock = charOffsetInBlock,
                preview = preview.take(80),
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteBookmark(id: Long) = withContext(Dispatchers.IO) { db.bookmarks().delete(id) }

    fun observeAnnotations(bookId: Long): Flow<List<AnnotationEntity>> =
        db.annotations().observeByBook(bookId)

    suspend fun annotations(bookId: Long): List<AnnotationEntity> =
        withContext(Dispatchers.IO) { db.annotations().observeByBook(bookId).first() }

    suspend fun addAnnotation(
        bookId: Long,
        chapterIdx: Int,
        blockIndex: Int,
        startOffsetInBlock: Int,
        endOffsetInBlock: Int,
        selectedText: String,
        note: String?,
    ): Long = withContext(Dispatchers.IO) {
        db.annotations().insert(
            AnnotationEntity(
                bookId = bookId,
                chapterIdx = chapterIdx,
                blockIndex = blockIndex,
                startOffsetInBlock = startOffsetInBlock,
                endOffsetInBlock = endOffsetInBlock,
                selectedText = selectedText.take(500),
                note = note?.trim()?.takeIf { it.isNotEmpty() }?.take(2000),
                color = DEFAULT_HIGHLIGHT_COLOR,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteAnnotation(id: Long) = withContext(Dispatchers.IO) { db.annotations().delete(id) }

    // ------------------------------------------------------------------ 阅读统计

    /** 记一段阅读。时长按墙钟算，字数由阅读页累计（顺序翻页时每页的字符数之和）。 */
    suspend fun recordSession(
        bookId: Long,
        startedAt: Long,
        endedAt: Long,
        charsRead: Long,
    ): Long = withContext(Dispatchers.IO) {
        if (endedAt <= startedAt) return@withContext -1L
        db.sessions().insert(
            ReadingSessionEntity(
                bookId = bookId,
                startedAt = startedAt,
                endedAt = endedAt,
                charsRead = charsRead.coerceAtLeast(0L),
                dayEpoch = Days.startOfDay(startedAt),
            )
        )
    }

    suspend fun sessions(bookId: Long): List<ReadingSessionEntity> =
        withContext(Dispatchers.IO) { db.sessions().byBook(bookId) }

    suspend fun stats(days: Int = 7): ReadingStats = withContext(Dispatchers.IO) {        val window = Days.lastDays(days)
        val since = window.first()
        val dailyRows = db.sessions().dailySince(since).associate { it.dayEpoch to it.total }
        ReadingStats(
            totalMillis = db.sessions().totalMillis(),
            millisLast7Days = db.sessions().millisSince(since),
            charsLast7Days = db.sessions().charsSince(since),
            activeDaysLast7Days = db.sessions().activeDaysSince(since),
            sessionsLast7Days = db.sessions().sessionsSince(since),
            daily = window.map { day -> DailyReading(day, dailyRows[day] ?: 0L) },
            bookCount = db.books().count(),
        )
    }

    // ------------------------------------------------------------------ 内部实现

    private suspend fun importStream(
        displayName: String,
        knownLength: Long,
        openStream: () -> InputStream,
        mode: String,
        referenceUri: Uri?,
        onProgress: (ImportProgress) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val title = titleFromFileName(displayName)

        // 1. 取样本探测编码（单独开一个流，不影响后面的复制）
        onProgress(ImportProgress(ImportProgress.Phase.DETECT, 0f, "正在识别编码"))
        val detection = EncodingDetector.detect(readSample(openStream))
        val needsTranscode = !Charsets.supportsByteLevelNewlineScan(detection.charset)

        // 引用模式无法改写原文件，遇到 UTF-16/32 只能改为复制（顺便转码）
        var effectiveMode = mode
        if (needsTranscode && effectiveMode == StorageMode.REFERENCE) {
            effectiveMode = StorageMode.COPY
        }
        val storedCharset = if (needsTranscode) StandardCharsets.UTF_8 else detection.charset

        val referenceSource = if (effectiveMode == StorageMode.REFERENCE && referenceUri != null) {
            UriByteSource(context, referenceUri)
        } else {
            null
        }

        // 先把「字节来源 + 落库路径」确定下来，失败时好清理
        val prepared = if (referenceSource != null) {
            PreparedSource(referenceSource, referenceUri.toString(), null)
        } else {
            val copy = File(booksDir, "${UUID.randomUUID()}.txt")
            try {
                if (needsTranscode) {
                    transcodeCopy(openStream, copy, detection.charset, onProgress)
                } else {
                    byteCopy(openStream, copy, knownLength, onProgress)
                }
            } catch (failure: Throwable) {
                runCatching { copy.delete() }
                throw failure
            }
            PreparedSource(FileByteSource(copy), copy.absolutePath, copy)
        }

        try {
            // 2. 建章节索引（两种模式都通过 ByteSource 顺序读）
            onProgress(ImportProgress(ImportProgress.Phase.INDEX, 0f, "正在建立章节索引"))
            val byteSource = prepared.source
            val index: IndexResult = ChapterIndexer().index(
                openStream = { byteSource.openStream() },
                totalBytes = byteSource.length,
                charset = storedCharset,
            ) { progress ->
                onProgress(
                    ImportProgress(
                        ImportProgress.Phase.INDEX,
                        progress.fraction,
                        progress.currentTitle ?: "正在建立章节索引",
                    )
                )
            }

            // 3. 落库
            onProgress(ImportProgress(ImportProgress.Phase.SAVE, 1f, "正在写入书库"))
            val fileSize = when {
                prepared.orphanCopy != null -> prepared.orphanCopy.length()
                byteSource.length > 0L -> byteSource.length
                else -> index.totalBytes
            }
            persist(
                title = title,
                filePath = prepared.filePath,
                mode = effectiveMode,
                fileSize = fileSize,
                fileMtime = prepared.orphanCopy?.lastModified() ?: 0L,
                charset = storedCharset,
                detectionEvidence = detection.evidence,
                encodingManual = false,
                index = index,
                orphanCopy = prepared.orphanCopy,
            )
        } catch (failure: Throwable) {
            prepared.orphanCopy?.let { runCatching { it.delete() } }
            throw failure
        } finally {
            onProgress(ImportProgress(ImportProgress.Phase.DONE, 1f))
        }
    }

    /** 导入过程中「读哪里 / 落库路径 / 失败要清理谁」。 */
    private class PreparedSource(
        val source: ByteSource,
        val filePath: String,
        val orphanCopy: File?,
    )

    private suspend fun persist(
        title: String,
        filePath: String,
        mode: String,
        fileSize: Long,
        fileMtime: Long,
        charset: Charset,
        detectionEvidence: String,
        encodingManual: Boolean,
        index: IndexResult,
        orphanCopy: File?,
        format: String = BookFormat.TXT,
        sourcePath: String? = null,
    ): Long {
        // 同一本书（同名且大小相同，或就是同一个来源）重复导入时复用已有记录
        val existing = db.books().all().firstOrNull { book ->
            book.title == title && (
                book.filePath == filePath ||
                    (fileSize > 0L && book.fileSize == fileSize)
                )
        }
        if (existing != null) {
            orphanCopy?.let { runCatching { it.delete() } }
            return existing.id
        }

        val bookId = db.books().insert(
            BookEntity(
                title = title,
                filePath = filePath,
                storageMode = mode,
                fileSize = fileSize,
                fileMtime = fileMtime,
                encoding = charset.name(),
                encodingManual = encodingManual,
                encodingEvidence = detectionEvidence,
                totalBytes = index.totalBytes,
                chapterCount = index.chapters.size,
                usedVirtualChapters = index.usedVirtualChapters,
                coverSeed = title.hashCode(),
                format = format,
                sourcePath = sourcePath,
                indexed = true,
                addedAt = System.currentTimeMillis(),
                lastReadAt = 0L,
            )
        )

        db.chapters().deleteByBook(bookId)
        db.chapters().insertAll(index.chapters.map { it.toEntity(bookId) })
        db.books().saveIndexInfo(
            bookId = bookId,
            encoding = charset.name(),
            manual = encodingManual,
            totalBytes = index.totalBytes,
            chapterCount = index.chapters.size,
            usedVirtual = index.usedVirtualChapters,
            fileSize = fileSize,
            fileMtime = fileMtime,
        )
        return bookId
    }

    private fun byteCopy(
        openStream: () -> InputStream,
        target: File,
        totalBytes: Long,
        onProgress: (ImportProgress) -> Unit,
    ) {
        onProgress(ImportProgress(ImportProgress.Phase.COPY, 0f, "正在复制文件"))
        var copied = 0L
        target.outputStream().buffered(COPY_BUFFER).use { out ->
            openStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    val fraction = if (totalBytes > 0L) {
                        (copied.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    onProgress(
                        ImportProgress(
                            ImportProgress.Phase.COPY,
                            fraction,
                            "已复制 ${copied / 1024 / 1024} MB",
                        )
                    )
                }
            }
        }
    }

    /** UTF-16/32 → UTF-8。用 Reader/Writer 走字符层，自动处理跨块的多字节边界。 */
    private fun transcodeCopy(
        openStream: () -> InputStream,
        target: File,
        sourceCharset: Charset,
        onProgress: (ImportProgress) -> Unit,
    ) {
        onProgress(ImportProgress(ImportProgress.Phase.COPY, 0f, "正在转码为 UTF-8"))
        var chars = 0L
        InputStreamReader(openStream(), sourceCharset).use { reader ->
            OutputStreamWriter(
                target.outputStream().buffered(COPY_BUFFER),
                StandardCharsets.UTF_8,
            ).use { writer ->
                val buffer = CharArray(COPY_BUFFER)
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    writer.write(buffer, 0, read)
                    chars += read
                    onProgress(
                        ImportProgress(
                            ImportProgress.Phase.COPY,
                            0f,
                            "已转码 ${chars / 1024} 千字",
                        )
                    )
                }
            }
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun sizeOf(uri: Uri): Long = UriByteSource(context, uri).length

    private fun readSample(openStream: () -> InputStream): ByteArray {
        val sample = ByteArray(EncodingDetector.SAMPLE_BYTES)
        var filled = 0
        openStream().use { input ->
            while (filled < sample.size) {
                val read = input.read(sample, filled, sample.size - filled)
                if (read <= 0) break
                filled += read
            }
        }
        return if (filled == sample.size) sample else sample.copyOf(filled)
    }

    private fun charsetOrUtf8(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrDefault(StandardCharsets.UTF_8)

    private fun titleFromFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val withoutExtension = base.substringBeforeLast('.', base).trim()
        return withoutExtension.ifEmpty { "未命名" }
    }

    private fun isEpubName(name: String): Boolean =
        name.substringAfterLast('.', "").equals("epub", ignoreCase = true)

    private fun com.harness.inkreader.engine.IndexedChapter.toEntity(bookId: Long) = ChapterEntity(
        bookId = bookId,
        idx = idx,
        title = title,
        startByte = startByte,
        endByte = endByte,
    )

    companion object {
        const val BOOKS_DIR = "books"

        /** EPUB 的 MIME 类型（文件选择器与 content:// 的 type 都用它）。 */
        const val EPUB_MIME = "application/epub+zip"

        /** 划线高亮色（半透明琥珀），直接画在正文底层。 */
        const val DEFAULT_HIGHLIGHT_COLOR = 0x66FFC107
        private const val COPY_BUFFER = 1 shl 20
    }
}

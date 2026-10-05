package com.harness.inkreader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 书库存储方式：复制进私有目录（默认）或只引用原文件。 */
object StorageMode {
    const val COPY = "copy"
    const val REFERENCE = "reference"
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 复制模式下是私有目录绝对路径；引用模式下是 content:// URI 字符串。 */
    val filePath: String,
    val storageMode: String = StorageMode.COPY,
    val fileSize: Long = 0,
    val fileMtime: Long = 0,
    /** **实际存储文件**的编码名（UTF-16 导入时已被转码成 UTF-8）。 */
    val encoding: String = "UTF-8",
    val encodingManual: Boolean = false,
    /** 探测依据，显示在书籍详情里方便排查乱码。 */
    val encodingEvidence: String = "",
    val totalBytes: Long = 0,
    val chapterCount: Int = 0,
    val usedVirtualChapters: Boolean = false,
    val groupName: String? = null,
    /** 封面渐变配色的种子，由书名哈希得来，保证同一本书封面颜色稳定。 */
    val coverSeed: Int = 0,
    /**
     * 自定义封面图的绝对路径（图片已复制进应用私有目录并缩小）。
     * null 表示没有自定义封面，书架会回退到「书名首字 + 稳定渐变色」的生成封面。
     */
    val coverPath: String? = null,
    val indexed: Boolean = false,
    val addedAt: Long = 0,
    val lastReadAt: Long = 0,
)

/**
 * 章节索引。主键是 (bookId, idx)，所以按书查询天然有序且走主键索引。
 */
@Entity(tableName = "chapters", primaryKeys = ["bookId", "idx"])
data class ChapterEntity(
    val bookId: Long,
    val idx: Int,
    val title: String,
    val startByte: Long,
    val endByte: Long,
)

/**
 * 阅读进度。每本书一行。
 *
 * 位置用 (章节, 块号, 块内字符偏移) 表达：章节可能大到 20MB，必须落到块内才精确。
 * 块边界由 [com.harness.inkreader.engine.ChapterBlocks] 确定性算出，所以这个位置永远可复现。
 */
@Entity(tableName = "progress", primaryKeys = ["bookId"])
data class ProgressEntity(
    val bookId: Long,
    val chapterIdx: Int = 0,
    val blockIndex: Int = 0,
    val charOffsetInBlock: Int = 0,
    val percent: Float = 0f,
    val updatedAt: Long = 0,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIdx: Int,
    val blockIndex: Int,
    val charOffsetInBlock: Int,
    val preview: String,
    val createdAt: Long,
)

/** 阅读时长统计用。每段阅读一行，[dayEpoch] 是本地日期零点，便于按天聚合。 */
@Entity(tableName = "reading_sessions", indices = [Index("bookId"), Index("dayEpoch")])
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val charsRead: Long,
    val dayEpoch: Long,
)

/** 划线 / 笔记（M6 使用）。 */
@Entity(tableName = "annotations", indices = [Index("bookId")])
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIdx: Int,
    val blockIndex: Int,
    val startOffsetInBlock: Int,
    val endOffsetInBlock: Int,
    val selectedText: String,
    val note: String? = null,
    val color: Int = 0,
    val createdAt: Long = 0,
)

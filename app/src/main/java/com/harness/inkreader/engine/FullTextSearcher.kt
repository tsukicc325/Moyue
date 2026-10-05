package com.harness.inkreader.engine

import java.nio.charset.Charset

/** 一条搜索结果。位置用 (章节, 块号, 块内字符偏移) 表达，与阅读进度同一套坐标系。 */
data class SearchHit(
    val chapterIdx: Int,
    val chapterTitle: String,
    val blockIndex: Int,
    val charOffsetInBlock: Int,
    val matchLength: Int,
    val snippet: String,
)

data class SearchProgress(
    val chaptersDone: Int,
    val totalChapters: Int,
    val hits: Int,
) {
    val fraction: Float
        get() = if (totalChapters <= 0) 0f else (chaptersDone.toFloat() / totalChapters).coerceIn(0f, 1f)
}

/**
 * 整本 txt 的全文搜索。
 *
 * 直接复用 [ChapterBlocks] 逐块解码搜索 —— 这样命中的位置天然就是
 * (章节, 块号, 块内字符偏移)，**不需要任何字符↔字节的换算**，因此结果点一下就能跳过去。
 *
 * 已知取舍：匹配不跨块边界。块边界落在换行之后，所以只有「搜索词里跨越换行」这种
 * 中文小说里几乎不存在的输入才会漏掉。
 */
class FullTextSearcher(
    private val blockBytes: Int = ChapterBlocks.DEFAULT_BLOCK_BYTES,
    private val maxHits: Int = DEFAULT_MAX_HITS,
    private val snippetRadius: Int = DEFAULT_SNIPPET_RADIUS,
) {

    fun search(
        source: ByteSource,
        charset: Charset,
        chapters: List<IndexedChapter>,
        query: String,
        ignoreCase: Boolean = true,
        isCancelled: () -> Boolean = { false },
        onProgress: (SearchProgress) -> Unit = {},
    ): List<SearchHit> {
        val needle = query.trim()
        if (needle.isEmpty() || chapters.isEmpty()) return emptyList()

        val hits = ArrayList<SearchHit>()
        chapters.forEachIndexed { position, chapter ->
            if (isCancelled()) return hits
            if (hits.size >= maxHits) return hits

            val blocks = ChapterBlocks(
                source = source,
                charset = charset,
                chapterStart = chapter.startByte,
                chapterEnd = chapter.endByte,
                blockBytes = blockBytes,
            )

            var blockIndex = 0
            var guard = 0
            while (blockIndex >= 0 && guard++ < MAX_BLOCKS_PER_CHAPTER) {
                val block = blocks.load(blockIndex)
                val text = block.text
                if (text.length >= needle.length) {
                    var from = 0
                    while (from <= text.length - needle.length) {
                        val at = text.indexOf(needle, from, ignoreCase)
                        if (at < 0) break
                        hits.add(
                            SearchHit(
                                chapterIdx = chapter.idx,
                                chapterTitle = chapter.title,
                                blockIndex = blockIndex,
                                charOffsetInBlock = at,
                                matchLength = needle.length,
                                snippet = snippetOf(text, at, needle.length),
                            )
                        )
                        if (hits.size >= maxHits) break
                        from = at + needle.length
                    }
                }
                if (hits.size >= maxHits) break
                blockIndex = blocks.nextIndex(blockIndex)
            }

            onProgress(SearchProgress(position + 1, chapters.size, hits.size))
        }
        return hits
    }

    /** 命中的上下文。换行与全角空格压成普通空格，避免结果列表里出现诡异的换行。 */
    private fun snippetOf(text: String, at: Int, length: Int): String {
        val from = (at - snippetRadius).coerceAtLeast(0)
        val to = (at + length + snippetRadius).coerceAtMost(text.length)
        val raw = text.substring(from, to)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\u3000', ' ')
            .replace(Regex(" {2,}"), " ")
            .trim()
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        return prefix + raw + suffix
    }

    companion object {
        const val DEFAULT_MAX_HITS = 500
        private const val DEFAULT_SNIPPET_RADIUS = 24
        private const val MAX_BLOCKS_PER_CHAPTER = 200_000
    }
}

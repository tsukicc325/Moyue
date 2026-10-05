package com.harness.inkreader.ui.bookshelf

import java.text.Collator
import java.util.Locale

enum class ShelfSort(val label: String) {
    RECENT("最近阅读"),
    ADDED("添加时间"),
    TITLE("书名"),
    PROGRESS("阅读进度"),
    SIZE("文件大小"),
}

data class ShelfQuery(
    val keyword: String = "",
    /** null 表示「全部」。 */
    val group: String? = null,
    val sort: ShelfSort = ShelfSort.RECENT,
)

/**
 * 书架的筛选与排序。刻意做成纯函数，这样排序规则可以脱离界面穷举测试。
 */
object ShelfQueryEngine {

    /** 中文书名按拼音排，而不是按码点排。 */
    private val chineseCollator: Collator = Collator.getInstance(Locale.CHINA)

    fun groups(items: List<ShelfItem>): List<String> =
        items.mapNotNull { it.book.groupName?.takeIf { name -> name.isNotBlank() } }
            .distinct()
            .sortedWith(chineseCollator)

    fun apply(items: List<ShelfItem>, query: ShelfQuery): List<ShelfItem> {
        val keyword = query.keyword.trim()
        val filtered = items.filter { item ->
            val matchesKeyword = keyword.isEmpty() ||
                item.book.title.contains(keyword, ignoreCase = true)
            val matchesGroup = query.group == null || item.book.groupName == query.group
            matchesKeyword && matchesGroup
        }

        return when (query.sort) {
            ShelfSort.RECENT -> filtered.sortedWith(
                compareByDescending<ShelfItem> { it.book.lastReadAt }
                    .thenByDescending { it.book.addedAt }
            )

            ShelfSort.ADDED -> filtered.sortedByDescending { it.book.addedAt }

            ShelfSort.TITLE -> filtered.sortedWith { left, right ->
                chineseCollator.compare(left.book.title, right.book.title)
            }

            ShelfSort.PROGRESS -> filtered.sortedByDescending { it.percent }

            ShelfSort.SIZE -> filtered.sortedByDescending { it.book.fileSize }
        }
    }
}

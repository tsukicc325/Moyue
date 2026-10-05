package com.harness.inkreader.ui.bookshelf

import com.harness.inkreader.data.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

/** 书架的筛选与排序是纯函数，这里把每种排序和组合筛选都跑一遍。 */
class ShelfQueryEngineTest {

    private var nextId = 1L

    private fun item(
        title: String,
        percent: Float = 0f,
        group: String? = null,
        addedAt: Long = 0,
        lastReadAt: Long = 0,
        size: Long = 0,
    ): ShelfItem = ShelfItem(
        book = BookEntity(
            id = nextId++,
            title = title,
            filePath = "/books/$title.txt",
            groupName = group,
            addedAt = addedAt,
            lastReadAt = lastReadAt,
            fileSize = size,
        ),
        percent = percent,
    )

    @Test
    fun `empty keyword keeps everything and is case insensitive`() {
        val items = listOf(
            item("Night Boat"),
            item("夜航船"),
            item("江防志"),
        )

        assertEquals(3, ShelfQueryEngine.apply(items, ShelfQuery()).size)
        assertEquals(
            listOf("Night Boat"),
            ShelfQueryEngine.apply(items, ShelfQuery(keyword = "night")).map { it.book.title },
        )
        assertEquals(
            listOf("夜航船"),
            ShelfQueryEngine.apply(items, ShelfQuery(keyword = "夜航")).map { it.book.title },
        )
    }

    @Test
    fun `keyword is trimmed`() {
        val items = listOf(item("夜航船"), item("江防志"))
        assertEquals(1, ShelfQueryEngine.apply(items, ShelfQuery(keyword = "  夜航  ")).size)
    }

    @Test
    fun `group filter combines with keyword`() {
        val items = listOf(
            item("夜航船", group = "武侠"),
            item("夜行记", group = "科幻"),
            item("江防志", group = "武侠"),
        )

        assertEquals(3, ShelfQueryEngine.apply(items, ShelfQuery(group = null)).size)
        assertEquals(
            listOf("夜航船", "江防志"),
            ShelfQueryEngine.apply(items, ShelfQuery(group = "武侠")).map { it.book.title },
        )
        assertEquals(
            listOf("夜航船"),
            ShelfQueryEngine.apply(items, ShelfQuery(group = "武侠", keyword = "夜航"))
                .map { it.book.title },
        )
        assertEquals(0, ShelfQueryEngine.apply(items, ShelfQuery(group = "不存在")).size)
    }

    @Test
    fun `recent sort prefers last read then added`() {
        val items = listOf(
            item("A", addedAt = 100, lastReadAt = 0),
            item("B", addedAt = 100, lastReadAt = 500),
            item("C", addedAt = 900, lastReadAt = 500),
        )

        val sorted = ShelfQueryEngine.apply(items, ShelfQuery(sort = ShelfSort.RECENT))

        assertEquals(listOf("C", "B", "A"), sorted.map { it.book.title })
    }

    @Test
    fun `added and size and progress sorts are descending`() {
        val items = listOf(
            item("A", addedAt = 100, size = 300, percent = 0.2f),
            item("B", addedAt = 300, size = 100, percent = 0.9f),
            item("C", addedAt = 200, size = 200, percent = 0.5f),
        )

        assertEquals(
            listOf("B", "C", "A"),
            ShelfQueryEngine.apply(items, ShelfQuery(sort = ShelfSort.ADDED)).map { it.book.title },
        )
        assertEquals(
            listOf("A", "C", "B"),
            ShelfQueryEngine.apply(items, ShelfQuery(sort = ShelfSort.SIZE)).map { it.book.title },
        )
        assertEquals(
            listOf("B", "C", "A"),
            ShelfQueryEngine.apply(items, ShelfQuery(sort = ShelfSort.PROGRESS)).map { it.book.title },
        )
    }

    @Test
    fun `title sort follows chinese collation`() {
        val items = listOf(
            item("Zebra"),
            item("苹果"),
            item("Alpha"),
            item("香蕉"),
            item("橘子"),
        )

        val sorted = ShelfQueryEngine.apply(items, ShelfQuery(sort = ShelfSort.TITLE))
        val collator = Collator.getInstance(Locale.CHINA)

        assertTrue(
            "书名排序应当与中文 Collator 一致，实际 ${sorted.map { it.book.title }}",
            sorted.zipWithNext().all { (left, right) ->
                collator.compare(left.book.title, right.book.title) <= 0
            },
        )
        assertEquals(listOf("Alpha", "Zebra"), sorted.map { it.book.title }.filter { it.first() in 'A'..'Z' })
    }

    @Test
    fun `groups are distinct, trimmed and skip blanks`() {
        val items = listOf(
            item("A", group = "武侠"),
            item("B", group = "武侠"),
            item("C", group = "科幻"),
            item("D", group = null),
            item("E", group = "  "),
        )

        val groups = ShelfQueryEngine.groups(items)

        assertEquals(setOf("科幻", "武侠"), groups.toSet())
        assertEquals(2, groups.size)
    }
}

package com.harness.inkreader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 自定义封面：复制进私有目录、等比缩小、失败不影响书架、删书时一并清理，
 * 以及**数据库迁移不能丢数据**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverStoreRobolectricTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: InkDatabase
    private lateinit var repo: BookRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = InkDatabase.inMemory(context)
        repo = BookRepository(context, db)
    }

    @After
    fun tearDown() = db.close()

    /** 造一张真图当"用户选的封面"。 */
    private fun writeImage(name: String, width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // 画点东西，避免全透明图被压缩成极小文件
        for (x in 0 until width step 8) {
            for (y in 0 until height step 8) {
                bitmap.setPixel(x, y, 0xFF3366CC.toInt())
            }
        }
        val file = File(temp.newFolder("img-$name"), name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private suspend fun importNovel(): Long {
        val file = File(temp.newFolder("novel"), "封面测试.txt")
        val text = buildString {
            for (chapter in 1..3) {
                append("第").append(chapter).append("章 风雪").append('\n')
                repeat(4) { append("　　他站在窗前看着外面的雪，想起很多年前的旧事。").append('\n') }
            }
        }
        file.writeBytes(text.toByteArray(Charsets.UTF_8))
        return repo.importFromFile(file)
    }

    @Test
    fun `imported cover is copied into private dir and scaled down`() = runBlocking {
        val bookId = importNovel()

        val path = Covers.importCover(context, bookId, writeImage("big.png", 1200, 1800))

        assertNotNull("应当返回新封面路径", path)
        val file = File(path!!)
        assertTrue("封面文件应当存在", file.isFile)
        assertTrue("应当写在私有目录里", file.absolutePath.startsWith(context.filesDir.absolutePath))

        val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull("封面应当能解码", decoded)
        assertEquals("长边应当缩到上限", Covers.MAX_EDGE_PX, maxOf(decoded.width, decoded.height))
        // 等比：1200×1800 的长边是"高"，所以缩成 426×640
        assertEquals(426, decoded.width)
        assertEquals(640, decoded.height)
    }

    @Test
    fun `small cover is kept as is instead of being upscaled`() = runBlocking {
        val bookId = importNovel()

        val path = Covers.importCover(context, bookId, writeImage("small.png", 100, 150))

        val decoded = android.graphics.BitmapFactory.decodeFile(path!!)
        assertEquals("小图不该被放大", 100, decoded.width)
        assertEquals(150, decoded.height)
    }

    @Test
    fun `scaleDown keeps aspect ratio and respects the cap`() {
        val wide = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        val scaled = Covers.scaleDown(wide)
        assertEquals(640, scaled.width)
        assertEquals(320, scaled.height)

        val alreadySmall = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        assertTrue("本来就小就原样返回", Covers.scaleDown(alreadySmall) === alreadySmall)
    }

    @Test
    fun `missing or blank cover path falls back to the generated cover`() {
        assertNull(Covers.existing(null))
        assertNull(Covers.existing(""))
        assertNull(Covers.existing("/definitely/not/here.jpg"))

        val empty = File(temp.newFolder("empty"), "zero.jpg")
        empty.createNewFile()
        assertNull("空文件也算没有封面", Covers.existing(empty.absolutePath))
    }

    @Test
    fun `repository stores the cover path and clears it together with the file`() = runBlocking {
        val bookId = importNovel()
        assertNull("新导入的书没有自定义封面", repo.book(bookId)!!.coverPath)

        assertTrue(repo.setCover(bookId, writeImage("cover.png", 900, 900)))

        val stored = repo.book(bookId)!!.coverPath
        assertNotNull("库里应当记下封面路径", stored)
        assertTrue("文件应当存在", File(stored!!).isFile)

        repo.clearCover(bookId)

        assertNull("清掉之后库里应当为空", repo.book(bookId)!!.coverPath)
        assertFalse("文件也应当删掉", File(stored).exists())
    }

    @Test
    fun `deleting a book also removes its cover file`() = runBlocking {
        val bookId = importNovel()
        assertTrue(repo.setCover(bookId, writeImage("cover2.png", 800, 800)))
        val stored = repo.book(bookId)!!.coverPath!!
        assertTrue(File(stored).isFile)

        repo.deleteBook(bookId)

        assertFalse("删书应当连封面一起清掉", File(stored).exists())
        assertNull(repo.book(bookId))
    }

    @Test
    fun `unreadable image is rejected without leaving a broken cover`() = runBlocking {
        val bookId = importNovel()
        val junk = File(temp.newFolder("junk"), "not-an-image.png")
        junk.writeText("这不是图片")

        val path = Covers.importCover(context, bookId, Uri.fromFile(junk))

        assertNull("读不出来就返回 null", path)
        assertNull("库里不该被写入路径", repo.book(bookId)!!.coverPath)
        assertFalse("不该留下临时文件", File(Covers.dir(context), "cover_$bookId.jpg.tmp").exists())
    }

    /**
     * v1 → v2 的迁移必须**保住旧数据**。
     *
     * 这里直接跑迁移里的 SQL（Room 的接线由真机升级安装来验证：装了旧版的手机上升级后
     * 书还在、阅读进度还在）。用破坏性迁移会清空用户的几千章索引，所以这条必须有。
     */
    @Test
    fun `migration adds the cover column and keeps existing rows`() {
        val file = File(temp.newFolder("mig"), "v1.db")
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL(
            """
            CREATE TABLE books (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                filePath TEXT NOT NULL,
                storageMode TEXT NOT NULL,
                fileSize INTEGER NOT NULL,
                fileMtime INTEGER NOT NULL,
                encoding TEXT NOT NULL,
                encodingManual INTEGER NOT NULL,
                encodingEvidence TEXT NOT NULL,
                totalBytes INTEGER NOT NULL,
                chapterCount INTEGER NOT NULL,
                usedVirtualChapters INTEGER NOT NULL,
                groupName TEXT,
                coverSeed INTEGER NOT NULL,
                indexed INTEGER NOT NULL,
                addedAt INTEGER NOT NULL,
                lastReadAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        old.execSQL(
            "INSERT INTO books (title, filePath, storageMode, fileSize, fileMtime, encoding, " +
                "encodingManual, encodingEvidence, totalBytes, chapterCount, usedVirtualChapters, " +
                "groupName, coverSeed, indexed, addedAt, lastReadAt) " +
                "VALUES ('旧书', '/x.txt', 'copy', 11, 22, 'UTF-8', 0, '', 33, 2445, 0, NULL, 7, 1, 44, 55)",
        )

        old.execSQL(InkDatabase.SQL_ADD_COVER)

        val cursor = old.rawQuery("SELECT title, chapterCount, coverPath FROM books", null)
        assertTrue("旧数据必须还在", cursor.moveToFirst())
        assertEquals("旧书", cursor.getString(0))
        assertEquals(2445, cursor.getInt(1))
        assertTrue("新列应当存在且默认为空", cursor.isNull(2))
        assertEquals(1, cursor.count)
        cursor.close()
        old.close()
    }
}

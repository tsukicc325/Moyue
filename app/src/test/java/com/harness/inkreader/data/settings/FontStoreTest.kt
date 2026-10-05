package com.harness.inkreader.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** 字体导入的文件管理逻辑（纯 JVM，不碰 Typeface）。 */
class FontStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): FontStore = FontStore(File(temp.root, "fonts"))

    private fun fontBytes(tag: String = "OTTO", size: Int = 4096): ByteArray =
        tag.toByteArray(StandardCharsets.US_ASCII) + ByteArray(size)

    @Test
    fun `imports a valid otf and lists it`() {
        val store = store()

        val result = store.import("思源宋体.otf") { ByteArrayInputStream(fontBytes("OTTO")) }

        assertTrue(result.isSuccess)
        val option = result.getOrThrow()
        assertEquals("思源宋体", option.label)
        assertTrue(option.isImported)
        assertTrue(File(option.filePath!!).exists())

        val listed = store.listImported()
        assertEquals(1, listed.size)
        assertEquals(option.id, listed.first().id)
    }

    @Test
    fun `accepts truetype magic number as well`() {
        val store = store()
        val bytes = byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(4096)

        assertTrue(store.import("ttf-test.ttf") { ByteArrayInputStream(bytes) }.isSuccess)
        assertTrue(store.import("collection.ttc") { ByteArrayInputStream(fontBytes("ttcf")) }.isSuccess)
        assertEquals(2, store.listImported().size)
    }

    @Test
    fun `rejects a file whose content is not a font and leaves nothing behind`() {
        val store = store()

        val result = store.import("假字体.ttf") { ByteArrayInputStream(ByteArray(8192) { 0x41 }) }

        assertTrue(result.isFailure)
        assertEquals(0, store.listImported().size)
        assertEquals(
            "被拒绝的字体不应当留下垃圾文件",
            0,
            store.directory().listFiles()?.size ?: 0,
        )
    }

    @Test
    fun `rejects tiny files and unsupported extensions`() {
        val store = store()

        assertTrue(
            store.import("太小.ttf") { ByteArrayInputStream(fontBytes(size = 10)) }.isFailure,
        )
        assertTrue(
            store.import("字体.woff2") { ByteArrayInputStream(fontBytes()) }.isFailure,
        )
        assertEquals(0, store.listImported().size)
    }

    @Test
    fun `same display name twice produces two distinct files`() {
        val store = store()

        val first = store.import("同名.ttf") { ByteArrayInputStream(fontBytes()) }.getOrThrow()
        val second = store.import("同名.ttf") { ByteArrayInputStream(fontBytes()) }.getOrThrow()

        assertFalse(first.id == second.id)
        assertEquals(2, store.listImported().size)
    }

    @Test
    fun `delete removes only the requested font`() {
        val store = store()
        val keep = store.import("保留.ttf") { ByteArrayInputStream(fontBytes()) }.getOrThrow()
        val drop = store.import("删除.ttf") { ByteArrayInputStream(fontBytes()) }.getOrThrow()

        assertTrue(store.delete(drop.id))

        val remaining = store.listImported()
        assertEquals(1, remaining.size)
        assertEquals(keep.id, remaining.first().id)
        assertFalse(File(drop.filePath!!).exists())
        assertFalse("删不存在的字体应当返回 false", store.delete("file:不存在.ttf"))
    }

    @Test
    fun `prune clears damaged leftovers`() {
        val store = store()
        store.import("好字体.ttf") { ByteArrayInputStream(fontBytes()) }
        val junk = File(store.directory(), "坏掉的-1234.ttf")
        junk.writeBytes(ByteArray(4096) { 0x00 })

        val removed = store.prune()

        assertEquals(1, removed)
        assertEquals(1, store.listImported().size)
        assertFalse(junk.exists())
    }

    @Test
    fun `unsafe characters in the name are sanitized`() {
        val store = store()

        val option = store.import("../../etc/passwd.ttf") { ByteArrayInputStream(fontBytes()) }.getOrThrow()

        val file = File(option.filePath!!)
        assertNotNull(file)
        assertEquals(
            "字体文件必须落在字体目录里",
            store.directory().canonicalPath,
            file.canonicalFile.parentFile!!.canonicalPath,
        )
    }

    @Test
    fun `validator rejects a file that only pretends by size`() {
        val store = store()
        val fake = File(temp.root, "fake.ttf")
        fake.writeBytes(ByteArray(64 * 1024))

        assertFalse("只有体积没有魔数，不该被当成字体", FontFiles.isLikelyFont(fake))
    }
}

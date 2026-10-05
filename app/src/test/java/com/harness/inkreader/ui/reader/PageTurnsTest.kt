package com.harness.inkreader.ui.reader

import com.harness.inkreader.data.settings.PageTurnMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页动画的几何学。
 *
 * 这里的关键不变量是「动画必须**结束在正确的位置**」：progress = 1 时新页一定归位
 * （偏移 0）、旧页一定完全移出屏幕。写反一个符号，动画结束就会出现半页错位的闪烁 ——
 * 这种错在动图里很难盯出来，但用数值断言一秒能验完。
 */
class PageTurnsTest {

    private val epsilon = 1e-6f

    private fun geometry(mode: PageTurnMode, forward: Boolean) =
        PageTurns.geometry(mode, forward)!!

    @Test
    fun `no animation has no geometry`() {
        assertNull(PageTurns.geometry(PageTurnMode.NONE, forward = true))
        assertNull(PageTurns.geometry(PageTurnMode.NONE, forward = false))
    }

    @Test
    fun `every animated mode ends with the new page exactly in place`() {
        PageTurnMode.entries.filter { it != PageTurnMode.NONE }.forEach { mode ->
            listOf(true, false).forEach { forward ->
                val geometry = geometry(mode, forward)
                assertEquals(
                    "$mode forward=$forward：动画结束时新页必须归位",
                    0f,
                    geometry.liveOffsetFactor(1f),
                    epsilon,
                )
            }
        }
    }

    @Test
    fun `every animated mode starts with the old page exactly in place`() {
        PageTurnMode.entries.filter { it != PageTurnMode.NONE }.forEach { mode ->
            listOf(true, false).forEach { forward ->
                val geometry = geometry(mode, forward)
                assertEquals(
                    "$mode forward=$forward：动画开始时旧页必须仍在原位",
                    0f,
                    geometry.snapshotOffsetFactor(0f),
                    epsilon,
                )
            }
        }
    }

    @Test
    fun `the old page never stays visible on top of the new one`() {
        PageTurnMode.entries.filter { it != PageTurnMode.NONE }.forEach { mode ->
            listOf(true, false).forEach { forward ->
                val geometry = geometry(mode, forward)
                if (geometry.snapshotOnTop) {
                    // 旧页盖在新页上面，所以它必须移出屏幕
                    val snapshotAtEnd = kotlin.math.abs(geometry.snapshotOffsetFactor(1f))
                    assertTrue(
                        "$mode forward=$forward：旧页盖在上面，结束时必须完全移出（现在 $snapshotAtEnd 屏宽）",
                        snapshotAtEnd >= 1f - epsilon,
                    )
                } else {
                    // 覆盖模式：旧页原地不动，由新页滑进来盖住它，此时旧页不能有位移
                    assertEquals(
                        "$mode forward=$forward：旧页应当原地不动，由新页盖住",
                        0f,
                        geometry.snapshotOffsetFactor(1f),
                        epsilon,
                    )
                }
            }
        }
    }

    @Test
    fun `a page that moves always starts fully off screen`() {
        PageTurnMode.entries.filter { it != PageTurnMode.NONE }.forEach { mode ->
            listOf(true, false).forEach { forward ->
                val geometry = geometry(mode, forward)
                val liveAtStart = kotlin.math.abs(geometry.liveOffsetFactor(0f))
                assertTrue(
                    "$mode forward=$forward：新页若会移动，必须从屏外开始（现在 $liveAtStart 屏宽）",
                    liveAtStart <= epsilon || liveAtStart >= 1f - epsilon,
                )
            }
        }
    }

    @Test
    fun `slide forward pushes the old page left over a still new page`() {
        val geometry = geometry(PageTurnMode.SLIDE, forward = true)

        assertEquals(0f, geometry.liveOffsetFactor(0.5f), epsilon)
        assertEquals(-0.5f, geometry.snapshotOffsetFactor(0.5f), epsilon)
        assertTrue("滑动时旧页盖在新页上面", geometry.snapshotOnTop)
    }

    @Test
    fun `slide backward brings the previous page in from the left`() {
        val geometry = geometry(PageTurnMode.SLIDE, forward = false)

        assertEquals(-1f, geometry.liveOffsetFactor(0f), epsilon)
        assertEquals(0f, geometry.liveOffsetFactor(1f), epsilon)
        assertEquals(0.5f, geometry.snapshotOffsetFactor(0.5f), epsilon)
        assertTrue(geometry.snapshotOnTop)
    }

    @Test
    fun `cover forward is the new page sliding in on top`() {
        val geometry = geometry(PageTurnMode.COVER, forward = true)

        assertEquals(1f, geometry.liveOffsetFactor(0f), epsilon)
        assertEquals(0.5f, geometry.liveOffsetFactor(0.5f), epsilon)
        assertEquals("覆盖模式下旧页不动", 0f, geometry.snapshotOffsetFactor(0.5f), epsilon)
        assertFalse("覆盖模式是新页盖住旧页", geometry.snapshotOnTop)
    }

    @Test
    fun `cover backward slides the old page away revealing the previous one`() {
        val geometry = geometry(PageTurnMode.COVER, forward = false)

        assertEquals("新页（上一页）原地不动", 0f, geometry.liveOffsetFactor(0.5f), epsilon)
        assertEquals(0.5f, geometry.snapshotOffsetFactor(0.5f), epsilon)
        assertTrue(geometry.snapshotOnTop)
    }
}

package com.harness.inkreader.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置的纯逻辑部分：主题解析、配色、亮度换算。 */
class ReaderSettingsTest {

    @Test
    fun `unknown theme or page turn ids fall back to defaults`() {
        assertEquals(ReaderThemeId.DEFAULT, ReaderThemeId.resolve(null))
        assertEquals(ReaderThemeId.DEFAULT, ReaderThemeId.resolve("不存在的主题"))
        assertEquals(ReaderThemeId.NIGHT, ReaderThemeId.resolve("NIGHT"))
        assertEquals(PageTurnMode.DEFAULT, PageTurnMode.resolve(null))
        assertEquals(PageTurnMode.COVER, PageTurnMode.resolve("COVER"))
    }

    @Test
    fun `settings expose resolved theme font and page turn`() {
        val settings = ReaderSettings(
            themeId = ReaderThemeId.SEPIA.name,
            fontId = FontOption.SYSTEM_MONO,
            pageTurnMode = PageTurnMode.NONE.name,
        )
        assertEquals(ReaderThemeId.SEPIA, settings.theme)
        assertEquals(FontOption.SYSTEM_MONO, settings.font.id)
        assertEquals(PageTurnMode.NONE, settings.pageTurn)
    }

    @Test
    fun `dark themes are detected from their background colour`() {
        assertTrue(!ReaderPalettes.of(ReaderThemeId.PAPER).isDark)
        assertTrue(!ReaderPalettes.of(ReaderThemeId.SEPIA).isDark)
        assertTrue(!ReaderPalettes.of(ReaderThemeId.GREEN).isDark)
        assertTrue(ReaderPalettes.of(ReaderThemeId.NIGHT).isDark)
        assertTrue(ReaderPalettes.of(ReaderThemeId.BLACK).isDark)
    }

    @Test
    fun `every theme has readable contrast against its background`() {
        ReaderThemeId.entries.forEach { theme ->
            val palette = ReaderPalettes.of(theme)
            val backgroundLuma = luma(palette.background)
            val textLuma = luma(palette.text)
            assertTrue(
                "$theme 的文字与背景亮度差太小：${backgroundLuma} vs ${textLuma}",
                kotlin.math.abs(backgroundLuma - textLuma) > 60.0,
            )
        }
    }

    @Test
    fun `theme list grows to a full set with distinct labels and colours`() {
        val themes = ReaderThemeId.entries
        assertTrue("主题应当够选，实际 ${themes.size} 套", themes.size >= 8)
        assertEquals("标签不能重复", themes.size, themes.map { it.label }.toSet().size)
        assertEquals("背景色不能重复", themes.size, themes.map { it.background }.toSet().size)
        assertTrue(
            "应当有樱粉",
            themes.any { it.label == "樱粉" },
        )
        // 樱粉是偏粉的：红分量明显高于绿和蓝
        val sakura = themes.first { it.label == "樱粉" }
        val r = (sakura.background shr 16) and 0xFF
        val g = (sakura.background shr 8) and 0xFF
        val b = sakura.background and 0xFF
        assertTrue("樱粉底色应当偏红：r=$r g=$g b=$b", r > g && r > b)
    }

    @Test
    fun `derived chrome outline and accent keep the frame in step with the theme`() {
        ReaderThemeId.entries.forEach { theme ->
            val palette = ReaderPalettes.of(theme)
            val name = theme.label
            // 外框色必须与正文底色不同，否则顶栏/底栏和正文糊在一起
            assertTrue("$name 的外框色与背景色相同", palette.chrome != palette.background)
            // 边框色要同时区别于背景和文字，才看得见
            assertTrue("$name 的边框色与背景色相同", palette.outline != palette.background)
            assertTrue("$name 的边框色与文字色相同", palette.outline != palette.text)
            assertTrue("$name 的卡片色与背景色相同", palette.chromeVariant != palette.background)
            // 强调色上的文字要有对比
            assertTrue(
                "$name 的强调色与它上面的文字太接近",
                kotlin.math.abs(luma(palette.accent) - luma(palette.onAccent)) > 60.0,
            )
            // 外框色方向：亮色主题压暗一点（像工具栏），暗色主题提亮一点（否则看不出分层）
            val chromeBrighter = luma(palette.chrome) > luma(palette.background)
            assertEquals("$name 的外框色方向不对", palette.isDark, chromeBrighter)
        }
    }

    @Test
    fun `brightness maps follow system to minus one`() {
        assertEquals(-1f, Brightness.windowValue(ReaderSettings.FOLLOW_SYSTEM_BRIGHTNESS))
        assertEquals(-1f, Brightness.windowValue(-0.5f))
        assertEquals(0.5f, Brightness.windowValue(0.5f))
        // 0 会让屏幕全黑，必须夹到一个下限
        assertEquals(0.01f, Brightness.windowValue(0f))
        assertEquals(1f, Brightness.windowValue(2f))
        assertEquals("跟随系统", Brightness.label(-1f))
        assertEquals("42%", Brightness.label(0.42f))
    }

    @Test
    fun `imported font is resolved by id and unknown ids fall back`() {
        val imported = listOf(FontOption("file:abc.ttf", "自定义", "/fonts/abc.ttf"))
        assertEquals("自定义", FontOption.resolve("file:abc.ttf", imported).label)
        assertEquals("自定义", FontOption.resolve("file:abc.ttf", imported).label)
        assertEquals(FontOption.SYSTEM_SERIF, FontOption.resolve("file:gone.ttf", imported).id)
        assertEquals(FontOption.SYSTEM_SERIF, FontOption.resolve(null, imported).id)
        assertTrue(FontOption.resolve("file:abc.ttf", imported).isImported)
        assertTrue(!FontOption.resolve(FontOption.SYSTEM_SERIF, imported).isImported)
    }

    private fun luma(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toDouble()
        val g = ((argb shr 8) and 0xFF).toDouble()
        val b = (argb and 0xFF).toDouble()
        return r * 0.299 + g * 0.587 + b * 0.114
    }
}

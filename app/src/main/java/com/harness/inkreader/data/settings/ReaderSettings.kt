package com.harness.inkreader.data.settings

/**
 * 阅读主题。颜色用 ARGB 的 Long 表示，这样模型层不依赖 Compose / Android，
 * 可以直接用纯 JVM 测试验证「主题名 → 配色」的映射。
 *
 * [accent] 是该主题的强调色（滑杆、选中态、进度条），让每套配色有自己的性格；
 * 外框色与边框色由 [ReaderPalette] 从背景和文字色推导，所以新增主题只要给这三个值。
 */
enum class ReaderThemeId(
    val label: String,
    val background: Long,
    val text: Long,
    val accent: Long,
) {
    PAPER("纸白", 0xFFFAF7F0, 0xFF2A2A2A, 0xFF8D6E63),
    SEPIA("米黄", 0xFFF3E9D2, 0xFF3B3226, 0xFFB08D57),
    KRAFT("牛皮纸", 0xFFE8DCC8, 0xFF3E3427, 0xFF9C7A4A),
    GREEN("护眼绿", 0xFFCCE8CF, 0xFF2B3A2E, 0xFF4E7A5A),
    SAKURA("樱粉", 0xFFFBE7EC, 0xFF4A2C33, 0xFFD4738C),
    SKY("天青", 0xFFE3EEF7, 0xFF23323F, 0xFF5B8DB8),
    LAVENDER("淡紫", 0xFFEFE9F7, 0xFF332C42, 0xFF8A7BB8),
    GRAY("石墨灰", 0xFF4A4A4A, 0xFFD8D8D8, 0xFFB0B0B0),
    NIGHT("夜间", 0xFF1A1A1A, 0xFFB9B9B9, 0xFF7E9EC4),
    BLACK("纯黑", 0xFF000000, 0xFF9E9E9E, 0xFF6E8FB5),
    ;

    companion object {
        val DEFAULT = PAPER

        fun resolve(id: String?): ReaderThemeId =
            entries.firstOrNull { it.name == id } ?: DEFAULT
    }
}

enum class PageTurnMode(val label: String) {
    NONE("无动画"),
    SLIDE("滑动"),
    COVER("覆盖"),
    ;

    companion object {
        val DEFAULT = SLIDE

        fun resolve(id: String?): PageTurnMode =
            entries.firstOrNull { it.name == id } ?: DEFAULT
    }
}

/**
 * 阅读方式。上下滚动把整块文字按页切片竖着排，切片首尾严格相接，
 * 所以滚动起来是连续的；左右翻页则一次一屏。
 */
enum class ReadingMode(val label: String) {
    PAGE("左右翻页"),
    SCROLL("上下滚动"),
    ;

    companion object {
        val DEFAULT = SCROLL

        fun resolve(id: String?): ReadingMode =
            entries.firstOrNull { it.name == id } ?: DEFAULT
    }
}

/** 内置字体。导入的字体由 [FontStore] 追加。 */
data class FontOption(
    val id: String,
    val label: String,
    val filePath: String? = null,
) {
    val isImported: Boolean get() = filePath != null

    companion object {
        const val SYSTEM_SERIF = "system:serif"
        const val SYSTEM_SANS = "system:sans"
        const val SYSTEM_MONO = "system:mono"

        val BUILT_IN = listOf(
            FontOption(SYSTEM_SERIF, "衬线（默认）"),
            FontOption(SYSTEM_SANS, "无衬线"),
            FontOption(SYSTEM_MONO, "等宽"),
        )

        fun resolve(id: String?, imported: List<FontOption> = emptyList()): FontOption {
            imported.firstOrNull { it.id == id }?.let { return it }
            return BUILT_IN.firstOrNull { it.id == id } ?: BUILT_IN.first()
        }
    }
}

/** 阅读设置。全部有默认值，任何一项都能单独改。 */
data class ReaderSettings(
    // ---- 排版 ----
    val textSizeSp: Float = 18f,
    val lineSpacingMultiplier: Float = 1.5f,
    val letterSpacingEm: Float = 0f,
    val firstLineIndentEm: Float = 2f,
    val paragraphSpacingEm: Float = 0f,
    val fontId: String = FontOption.SYSTEM_SERIF,
    val bold: Boolean = false,
    val justify: Boolean = false,
    val horizontalPaddingDp: Int = 22,
    val verticalPaddingDp: Int = 42,
    // ---- 主题 ----
    val themeId: String = ReaderThemeId.DEFAULT.name,
    val backgroundImagePath: String? = null,
    val backgroundImageAlpha: Float = 0.25f,
    /** -1 表示跟随系统亮度。 */
    val brightness: Float = FOLLOW_SYSTEM_BRIGHTNESS,
    // ---- 交互 ----
    val readingMode: String = ReadingMode.DEFAULT.name,
    val pageTurnMode: String = PageTurnMode.DEFAULT.name,
    val volumeKeyPaging: Boolean = true,
    val volumeKeyReversed: Boolean = false,
    /** false：左区上一页（默认）。true：左区下一页（左手习惯）。 */
    val tapLeftIsNext: Boolean = false,
    val keepScreenOn: Boolean = true,
    val showFooter: Boolean = true,
) {
    val theme: ReaderThemeId get() = ReaderThemeId.resolve(themeId)
    val font: FontOption get() = FontOption.resolve(fontId)
    val pageTurn: PageTurnMode get() = PageTurnMode.resolve(pageTurnMode)
    val mode: ReadingMode get() = ReadingMode.resolve(readingMode)
    val followsSystemBrightness: Boolean get() = brightness < 0f

    companion object {
        const val FOLLOW_SYSTEM_BRIGHTNESS = -1f

        val MIN_TEXT_SIZE_SP = 12f
        val MAX_TEXT_SIZE_SP = 40f
        val MIN_LINE_SPACING = 1.0f
        val MAX_LINE_SPACING = 2.6f
        val MIN_LETTER_SPACING_EM = 0f
        val MAX_LETTER_SPACING_EM = 0.3f
        val MIN_INDENT_EM = 0f
        val MAX_INDENT_EM = 4f
        val MIN_PARAGRAPH_SPACING_EM = 0f
        val MAX_PARAGRAPH_SPACING_EM = 2f
        val MIN_PADDING_DP = 8
        val MAX_PADDING_DP = 56
    }
}

/**
 * 阅读页实际用的配色。
 *
 * [chrome] 是顶栏/底栏/抽屉的底色，[outline] 是分隔线与描边，[chromeVariant] 是卡片/标签的底色 ——
 * 都由背景与文字色推导，这样每套主题的「外框」都会跟着一起变，不会出现
 * 「正文是夜间、边框还是亮色」的突兀感。
 */
data class ReaderPalette(
    val background: Long,
    val text: Long,
    val accent: Long,
    val footerAlpha: Float = 0.45f,
) {
    val isDark: Boolean
        get() {
            val r = (background shr 16) and 0xFF
            val g = (background shr 8) and 0xFF
            val b = background and 0xFF
            return (r * 0.299 + g * 0.587 + b * 0.114) < 128.0
        }

    /** 顶栏/底栏/抽屉底色：亮色主题压暗一点，暗色主题提亮一点。 */
    val chrome: Long get() = mix(background, if (isDark) 0xFFFFFFFF else 0xFF000000, 0.055)

    /** 卡片、标签、选中项的底色。 */
    val chromeVariant: Long get() = mix(background, text, 0.09)

    /** 分隔线与描边。用户说的「边框」就是它。 */
    val outline: Long get() = mix(background, text, 0.24)

    /** 强调色上的文字色（按强调色亮度选黑或白，保证对比度）。 */
    val onAccent: Long
        get() {
            val r = (accent shr 16) and 0xFF
            val g = (accent shr 8) and 0xFF
            val b = accent and 0xFF
            return if ((r * 0.299 + g * 0.587 + b * 0.114) < 150.0) 0xFFF5F5F5 else 0xFF1B1B1B
        }

    private fun mix(from: Long, to: Long, ratio: Double): Long {
        fun channel(shift: Int): Long {
            val a = ((from shr shift) and 0xFF).toDouble()
            val b = ((to shr shift) and 0xFF).toDouble()
            return (a + (b - a) * ratio).toLong().coerceIn(0, 255)
        }
        return 0xFF000000L or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

object ReaderPalettes {
    fun of(theme: ReaderThemeId): ReaderPalette =
        ReaderPalette(
            background = theme.background,
            text = theme.text,
            accent = theme.accent,
        )
}

/** 亮度取的换算：设置值 -1 表示跟随系统，否则映射到 0.01..1 的窗口亮度。 */
object Brightness {
    fun windowValue(setting: Float): Float =
        if (setting < 0f) -1f else setting.coerceIn(0.01f, 1f)

    fun label(setting: Float): String =
        if (setting < 0f) "跟随系统" else "${(setting * 100).toInt()}%"
}

package com.harness.inkreader.ui.reader

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.harness.inkreader.data.settings.ReaderPalette

/**
 * 把阅读主题的配色映射成一套 Material3 [ColorScheme]，套在整个阅读界面上。
 *
 * 为什么需要它：阅读界面里除了正文画布，其余全是 Material 组件（顶栏、底栏、目录/搜索/笔记
 * 抽屉、对话框、芯片、滑杆、分隔线），它们读的是 `MaterialTheme.colorScheme`。之前那一套
 * 用的是应用自身的亮色主题，所以选了夜间/纯黑之后，正文是黑的、外框还是亮的 ——
 * 就是用户说的「非常突兀」。套上这套方案后，外框、边框、抽屉会**一起**跟着主题变。
 *
 * 各字段的取值都来自 [ReaderPalette] 推导出的 chrome / chromeVariant / outline，
 * 所以新增主题不用改这里。
 */
fun readerColorScheme(palette: ReaderPalette): ColorScheme {
    val base = if (palette.isDark) darkColorScheme() else lightColorScheme()
    val background = Color(palette.background)
    val text = Color(palette.text)
    val chrome = Color(palette.chrome)
    val chromeVariant = Color(palette.chromeVariant)
    val outline = Color(palette.outline)
    val accent = Color(palette.accent)
    val onAccent = Color(palette.onAccent)

    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = chromeVariant,
        onPrimaryContainer = text,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = chromeVariant,
        onSecondaryContainer = text,
        background = background,
        onBackground = text,
        surface = chrome,
        onSurface = text,
        surfaceVariant = chromeVariant,
        onSurfaceVariant = text.copy(alpha = 0.78f),
        // Material3 的抽屉/对话框/卡片读的是这一族容器色，必须一起给，
        // 否则它们会退回默认的浅紫灰
        surfaceContainerLowest = background,
        surfaceContainerLow = chrome,
        surfaceContainer = chrome,
        surfaceContainerHigh = chromeVariant,
        surfaceContainerHighest = chromeVariant,
        surfaceDim = background,
        surfaceBright = chromeVariant,
        outline = outline,
        outlineVariant = outline.copy(alpha = 0.45f),
        inverseSurface = text,
        inverseOnSurface = chrome,
        scrim = Color.Black.copy(alpha = 0.55f),
    )
}

package com.harness.inkreader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// 中文界面统一放宽行高，避免汉字贴行。
val InkTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
)

package com.harness.inkreader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harness.inkreader.InkApp
import com.harness.inkreader.data.settings.Brightness
import com.harness.inkreader.data.settings.FontOption
import com.harness.inkreader.data.settings.PageTurnMode
import com.harness.inkreader.data.settings.ReaderPalettes
import com.harness.inkreader.data.settings.ReaderSettings
import com.harness.inkreader.data.settings.ReaderThemeId
import com.harness.inkreader.data.settings.ReadingMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as InkApp }
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app))
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val fonts by viewModel.fonts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var fontDialogVisible by remember { mutableStateOf(false) }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFont(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.pickBackgroundImage(uri)
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("阅读设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            SectionTitle("排版")

            SettingSlider(
                label = "字号",
                valueText = "${settings.textSizeSp.roundToInt()} sp",
                value = settings.textSizeSp,
                range = ReaderSettings.MIN_TEXT_SIZE_SP..ReaderSettings.MAX_TEXT_SIZE_SP,
                onChange = { value -> viewModel.update { it.copy(textSizeSp = value) } },
            )
            SettingSlider(
                label = "行距",
                valueText = "%.1f 倍".format(settings.lineSpacingMultiplier),
                value = settings.lineSpacingMultiplier,
                range = ReaderSettings.MIN_LINE_SPACING..ReaderSettings.MAX_LINE_SPACING,
                onChange = { value -> viewModel.update { it.copy(lineSpacingMultiplier = value) } },
            )
            SettingSlider(
                label = "字距",
                valueText = "%.2f em".format(settings.letterSpacingEm),
                value = settings.letterSpacingEm,
                range = ReaderSettings.MIN_LETTER_SPACING_EM..ReaderSettings.MAX_LETTER_SPACING_EM,
                onChange = { value -> viewModel.update { it.copy(letterSpacingEm = value) } },
            )
            SettingSlider(
                label = "首行缩进",
                valueText = "%.1f 字".format(settings.firstLineIndentEm),
                value = settings.firstLineIndentEm,
                range = ReaderSettings.MIN_INDENT_EM..ReaderSettings.MAX_INDENT_EM,
                onChange = { value -> viewModel.update { it.copy(firstLineIndentEm = value) } },
            )
            SettingSlider(
                label = "段间距",
                valueText = "%.1f 行".format(settings.paragraphSpacingEm),
                value = settings.paragraphSpacingEm,
                range = ReaderSettings.MIN_PARAGRAPH_SPACING_EM..ReaderSettings.MAX_PARAGRAPH_SPACING_EM,
                onChange = { value -> viewModel.update { it.copy(paragraphSpacingEm = value) } },
            )
            SettingSlider(
                label = "左右边距",
                valueText = "${settings.horizontalPaddingDp} dp",
                value = settings.horizontalPaddingDp.toFloat(),
                range = ReaderSettings.MIN_PADDING_DP.toFloat()..ReaderSettings.MAX_PADDING_DP.toFloat(),
                steps = 0,
                onChange = { value -> viewModel.update { it.copy(horizontalPaddingDp = value.roundToInt()) } },
            )
            SettingSlider(
                label = "上下边距",
                valueText = "${settings.verticalPaddingDp} dp",
                value = settings.verticalPaddingDp.toFloat(),
                range = ReaderSettings.MIN_PADDING_DP.toFloat()..ReaderSettings.MAX_PADDING_DP.toFloat(),
                steps = 0,
                onChange = { value -> viewModel.update { it.copy(verticalPaddingDp = value.roundToInt()) } },
            )
            SwitchRow(
                label = "正文加粗",
                checked = settings.bold,
                onChange = { value -> viewModel.update { it.copy(bold = value) } },
            )
            SwitchRow(
                label = "两端对齐",
                checked = settings.justify,
                onChange = { value -> viewModel.update { it.copy(justify = value) } },
            )
            ActionRow(
                label = "字体",
                value = settings.font.label,
                onClick = { fontDialogVisible = true },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            SectionTitle("主题")

            ThemePicker(
                current = settings.theme,
                onSelect = { theme -> viewModel.update { it.copy(themeId = theme.name) } },
                previewBackground = settings.theme.background,
                previewText = settings.theme.text,
                previewAccent = settings.theme.accent,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ActionRow(
                label = "自定义背景图",
                value = if (settings.backgroundImagePath == null) "未设置" else "已设置",
                onClick = { imagePicker.launch(arrayOf("image/*")) },
            )
            if (settings.backgroundImagePath != null) {
                SettingSlider(
                    label = "背景图透明度",
                    valueText = "${(settings.backgroundImageAlpha * 100).roundToInt()}%",
                    value = settings.backgroundImageAlpha,
                    range = 0.05f..1f,
                    onChange = { value -> viewModel.update { it.copy(backgroundImageAlpha = value) } },
                )
                TextButton(onClick = { viewModel.clearBackgroundImage() }) { Text("清除背景图") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            SectionTitle("亮度")

            SwitchRow(
                label = "跟随系统亮度",
                checked = settings.followsSystemBrightness,
                onChange = { follow ->
                    viewModel.update {
                        it.copy(
                            brightness = if (follow) {
                                ReaderSettings.FOLLOW_SYSTEM_BRIGHTNESS
                            } else {
                                0.6f
                            },
                        )
                    }
                },
            )
            if (!settings.followsSystemBrightness) {
                SettingSlider(
                    label = "亮度",
                    valueText = Brightness.label(settings.brightness),
                    value = settings.brightness.coerceAtLeast(0.01f),
                    range = 0.01f..1f,
                    onChange = { value -> viewModel.update { it.copy(brightness = value) } },
                )
                Text(
                    text = "阅读时在屏幕左侧上下滑动也能直接调亮度。",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            SectionTitle("翻页与交互")

            Text(
                text = "阅读方式",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.mode == mode,
                        onClick = { viewModel.update { it.copy(readingMode = mode.name) } },
                        label = { Text(mode.label) },
                    )
                }
            }
            Text(
                text = if (settings.mode == ReadingMode.SCROLL) {
                    "上下滚动：整章连着滑，切片首尾相接，可以一路滑到下一章；点屏幕呼出菜单。"
                } else {
                    "左右翻页：一次一屏，点左/中/右分区或按音量键翻页。"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )

            if (settings.mode == ReadingMode.PAGE) {
                Text(
                    text = "翻页方式",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageTurnMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.pageTurn == mode,
                            onClick = { viewModel.update { it.copy(pageTurnMode = mode.name) } },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
            SwitchRow(
                label = "音量键翻页 / 滚动一屏",
                checked = settings.volumeKeyPaging,
                onChange = { value -> viewModel.update { it.copy(volumeKeyPaging = value) } },
            )
            if (settings.volumeKeyPaging) {
                SwitchRow(
                    label = "音量下键翻下一页",
                    checked = !settings.volumeKeyReversed,
                    onChange = { value -> viewModel.update { it.copy(volumeKeyReversed = !value) } },
                )
            }
            SwitchRow(
                label = "左手模式（点左侧翻下一页）",
                checked = settings.tapLeftIsNext,
                onChange = { value -> viewModel.update { it.copy(tapLeftIsNext = value) } },
            )
            SwitchRow(
                label = "阅读时屏幕常亮",
                checked = settings.keepScreenOn,
                onChange = { value -> viewModel.update { it.copy(keepScreenOn = value) } },
            )
            SwitchRow(
                label = "显示页脚（章节名 / 页码）",
                checked = settings.showFooter,
                onChange = { value -> viewModel.update { it.copy(showFooter = value) } },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            SectionTitle("关于")
            Text(
                text = "墨阅 · 本地 txt 阅读器\n" +
                    "索引引擎对 100MB 小说的实测：PC 上 0.27 秒建好索引，峰值内存增量 27MB；" +
                    "整本没有换行的极端文件峰值只有 2.5MB。\n" +
                    "设置改动会立刻生效并自动重新分页。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (fontDialogVisible) {
        FontDialog(
            fonts = fonts,
            currentId = settings.fontId,
            onSelect = { option ->
                viewModel.update { it.copy(fontId = option.id) }
                fontDialogVisible = false
            },
            onImport = {
                fontDialogVisible = false
                fontPicker.launch(arrayOf("font/*", "application/octet-stream", "*/*"))
            },
            onDelete = { option -> viewModel.deleteFont(option) },
            onDismiss = { fontDialogVisible = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ThemePicker(
    current: ReaderThemeId,
    onSelect: (ReaderThemeId) -> Unit,
    previewBackground: Long,
    previewText: Long,
    previewAccent: Long,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        // 10 套主题，一行放不下就分两行 —— 用简单的网格，不引实验性 API
        ReaderThemeId.entries.chunked(5).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                row.forEach { theme ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .background(Color(theme.background), CircleShape)
                                .border(
                                    width = if (theme == current) 3.dp else 1.dp,
                                    color = if (theme == current) {
                                        Color(theme.accent)
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { onSelect(theme) },
                        ) {
                            Text(text = "文", color = Color(theme.text), fontSize = 16.sp)
                        }
                        Text(
                            text = theme.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 11.sp,
                            color = if (theme == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        // 实时预览：让人在设置页就能看出这套主题长什么样（正文 + 外框 + 边框 + 强调色）
        val palette = ReaderPalettes.of(current)
        Surface(
            color = Color(previewBackground),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(palette.outline), RoundedCornerShape(10.dp)),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "第 1 章 预览",
                    color = Color(previewText).copy(alpha = 0.75f),
                    fontSize = 12.sp,
                )
                Text(
                    text = "他站在窗前，看着外面的雪，想起很多年前的旧事。",
                    color = Color(previewText),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(previewAccent), CircleShape),
                    )
                    Text(
                        text = "  强调色 / 滑杆",
                        color = Color(previewAccent),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun FontDialog(
    fonts: List<FontOption>,
    currentId: String,
    onSelect: (FontOption) -> Unit,
    onImport: () -> Unit,
    onDelete: (FontOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择字体") },
        text = {
            Column {
                fonts.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            text = if (option.id == currentId) "✓ ${option.label}" else option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (option.id == currentId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (option.isImported) {
                            TextButton(onClick = { onDelete(option) }) { Text("删除") }
                        }
                    }
                }
                Text(
                    text = "把 ttf / otf 字体文件放进手机，再从这里导入即可。",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onImport) { Text("导入字体") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

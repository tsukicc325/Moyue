package com.harness.inkreader.ui.bookshelf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harness.inkreader.data.Covers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.harness.inkreader.InkApp
import com.harness.inkreader.data.BookEntity
import com.harness.inkreader.data.BookFormat
import com.harness.inkreader.data.ImportProgress
import com.harness.inkreader.data.StorageMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val ENCODING_CHOICES = listOf("UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onOpenBook: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as InkApp }
    val viewModel: BookshelfViewModel = viewModel(
        factory = BookshelfViewModel.Factory(app.repository),
    )

    val items by viewModel.items.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val query by viewModel.shelfQuery.collectAsStateWithLifecycle()
    val importMode by viewModel.importMode.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val batch by viewModel.batch.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pendingImportPath by app.pendingImportPath.collectAsStateWithLifecycle()
    val pendingImportUri by app.pendingImportUri.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var searching by remember { mutableStateOf(false) }
    var sortMenuVisible by remember { mutableStateOf(false) }
    var overflowVisible by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<BookDialog?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    // 选择器里要同时能看到 txt 和 epub；部分文件管理器对 application/epub+zip 归类不同，
    // 所以再兜一个 */* —— 读不动的东西反正会在导入时报错，比「选不到文件」体验好。
    val importMimeTypes = arrayOf("text/plain", "application/epub+zip", "*/*")

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) viewModel.importFolder(treeUri)
    }

    // 自定义封面：先记住是哪本书，选完图再回来写库
    var coverTarget by remember { mutableStateOf<BookEntity?>(null) }
    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        val target = coverTarget
        coverTarget = null
        if (uri != null && target != null) viewModel.setCover(target.id, uri)
    }

    // 外部触发导入（文件管理器「用墨阅打开」，或 adb 的 inkreader.import_path 参数）
    LaunchedEffect(pendingImportPath) {
        val path = pendingImportPath ?: return@LaunchedEffect
        app.pendingImportPath.value = null
        viewModel.importFile(File(path))
    }

    // 「打开方式 / 分享到墨阅」送来的是 content:// URI，只能按复制模式导入
    // （ACTION_VIEW 给的读权限只在本次任务有效，引用模式重启后就打不开原文件）
    LaunchedEffect(pendingImportUri) {
        val uri = pendingImportUri ?: return@LaunchedEffect
        app.pendingImportUri.value = null
        viewModel.importCopied(Uri.parse(uri))
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
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query.keyword,
                            onValueChange = viewModel::setKeyword,
                            singleLine = true,
                            placeholder = { Text("搜索书名") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(text = "墨阅", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) viewModel.setKeyword("")
                    }) {
                        Icon(
                            imageVector = if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searching) "退出搜索" else "搜索",
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenuVisible = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "排序")
                        }
                        DropdownMenu(
                            expanded = sortMenuVisible,
                            onDismissRequest = { sortMenuVisible = false },
                        ) {
                            ShelfSort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (sort == query.sort) "✓ ${sort.label}" else sort.label,
                                        )
                                    },
                                    onClick = {
                                        viewModel.setSort(sort)
                                        sortMenuVisible = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { overflowVisible = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = overflowVisible,
                            onDismissRequest = { overflowVisible = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("导入整个文件夹") },
                                onClick = {
                                    overflowVisible = false
                                    folderPicker.launch(null)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (importMode == StorageMode.COPY) {
                                            "导入方式：复制到应用"
                                        } else {
                                            "导入方式：只引用原文件"
                                        },
                                    )
                                },
                                onClick = {
                                    overflowVisible = false
                                    viewModel.toggleImportMode()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("阅读统计") },
                                onClick = {
                                    overflowVisible = false
                                    onOpenStats()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    overflowVisible = false
                                    onOpenSettings()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePicker.launch(importMimeTypes) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("导入") },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (groups.isNotEmpty() || query.group != null) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = query.group == null,
                                onClick = { viewModel.filterByGroup(null) },
                                label = { Text("全部 $totalCount") },
                            )
                        }
                        items(groups, key = { it }) { group ->
                            FilterChip(
                                selected = query.group == group,
                                onClick = {
                                    viewModel.filterByGroup(if (query.group == group) null else group)
                                },
                                label = { Text(group) },
                            )
                        }
                    }
                    HorizontalDivider()
                }

                when {
                    totalCount == 0 -> EmptyShelf()
                    items.isEmpty() -> NoMatch()
                    else -> BookList(
                        items = items,
                        onOpen = onOpenBook,
                        onAction = { dialog = it },
                        onPickCover = { book ->
                            coverTarget = book
                            coverPicker.launch("image/*")
                        },
                        onClearCover = { viewModel.clearCover(it.id) },
                    )
                }
            }

            importProgress?.let { progress -> ImportDialog(progress) }
            batch?.let { state -> BatchDialog(state) }
        }
    }

    dialog?.let { current ->
        BookDialogs(
            dialog = current,
            groups = groups,
            onDismiss = { dialog = null },
            onRename = { bookId, title ->
                viewModel.rename(bookId, title)
                dialog = null
            },
            onAssignGroup = { bookId, group ->
                viewModel.assignGroup(bookId, group)
                dialog = null
            },
            onChangeEncoding = { bookId, charset ->
                viewModel.changeEncoding(bookId, charset)
                dialog = null
            },
            onDelete = { bookId ->
                viewModel.delete(bookId)
                dialog = null
            },
            onToggleImportMode = { viewModel.toggleImportMode() },
        )
    }
}

@Composable
private fun BookList(
    items: List<ShelfItem>,
    onOpen: (Long) -> Unit,
    onAction: (BookDialog) -> Unit,
    onPickCover: (BookEntity) -> Unit,
    onClearCover: (BookEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.book.id }) { item ->
            BookRow(
                item = item,
                onClick = { onOpen(item.book.id) },
                onAction = onAction,
                onPickCover = onPickCover,
                onClearCover = onClearCover,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    item: ShelfItem,
    onClick: () -> Unit,
    onAction: (BookDialog) -> Unit,
    onPickCover: (BookEntity) -> Unit,
    onClearCover: (BookEntity) -> Unit,
) {
    var menuVisible by remember { mutableStateOf(false) }
    val book = item.book

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuVisible = true })
                .padding(12.dp),
        ) {
            BookCover(title = book.title, seed = book.coverSeed, coverPath = book.coverPath)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(book.chapterCount).append(" 章 · ")
                        append(formatSize(book.fileSize)).append(" · ")
                        // EPUB 显示格式而不是编码：它内部正文是解析出来的规范化文本，
                        // 写「UTF-8」会让人以为导入的是个文本文件
                        append(if (book.format == BookFormat.EPUB) BookFormat.EPUB else book.encoding)
                        book.groupName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                        if (book.storageMode == StorageMode.REFERENCE) append(" · 引用")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (item.percent > 0f) {
                    Text(
                        text = "已读 ${(item.percent * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    LinearProgressIndicator(
                        progress = { item.percent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(3.dp),
                    )
                } else {
                    Text(
                        text = "未开始",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Box {
                IconButton(onClick = { menuVisible = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                    DropdownMenuItem(
                        text = { Text(if (book.coverPath == null) "设置封面" else "更换封面") },
                        onClick = { menuVisible = false; onPickCover(book) },
                    )
                    if (book.coverPath != null) {
                        DropdownMenuItem(
                            text = { Text("移除封面") },
                            onClick = { menuVisible = false; onClearCover(book) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuVisible = false; onAction(BookDialog.Rename(book)) },
                    )
                    DropdownMenuItem(
                        text = { Text("移动到分组") },
                        onClick = { menuVisible = false; onAction(BookDialog.Group(book)) },
                    )
                    DropdownMenuItem(
                        text = { Text("修改编码并重建索引") },
                        onClick = { menuVisible = false; onAction(BookDialog.Encoding(book)) },
                    )
                    DropdownMenuItem(
                        text = { Text("书籍详情") },
                        onClick = { menuVisible = false; onAction(BookDialog.Details(book)) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = { menuVisible = false; onAction(BookDialog.Delete(book)) },
                    )
                }
            }
        }
    }
}

sealed interface BookDialog {
    data class Rename(val book: BookEntity) : BookDialog
    data class Group(val book: BookEntity) : BookDialog
    data class Encoding(val book: BookEntity) : BookDialog
    data class Details(val book: BookEntity) : BookDialog
    data class Delete(val book: BookEntity) : BookDialog
}

@Composable
private fun BookDialogs(
    dialog: BookDialog,
    groups: List<String>,
    onDismiss: () -> Unit,
    onRename: (Long, String) -> Unit,
    onAssignGroup: (Long, String?) -> Unit,
    onChangeEncoding: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onToggleImportMode: () -> Unit,
) {
    when (dialog) {
        is BookDialog.Rename -> {
            var text by remember(dialog.book.id) { mutableStateOf(dialog.book.title) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("重命名") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("书名") },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { onRename(dialog.book.id, text) }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            )
        }

        is BookDialog.Group -> {
            var text by remember(dialog.book.id) { mutableStateOf(dialog.book.groupName.orEmpty()) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("移动到分组") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            label = { Text("分组名") },
                        )
                        if (groups.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(groups, key = { it }) { group ->
                                    FilterChip(
                                        selected = text == group,
                                        onClick = { text = group },
                                        label = { Text(group) },
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onAssignGroup(dialog.book.id, text.ifBlank { null }) }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { onAssignGroup(dialog.book.id, null) }) { Text("移出分组") }
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                },
            )
        }

        is BookDialog.Encoding -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("正文编码") },
            text = {
                Column {
                    Text(
                        text = "当前：${dialog.book.encoding}。改成别的编码会按新编码重新切分章节，正文乱码时用它救回来。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ENCODING_CHOICES.forEach { name ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChangeEncoding(dialog.book.id, name) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                text = if (name == dialog.book.encoding) "✓ $name" else name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (name == dialog.book.encoding) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )

        is BookDialog.Details -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("书籍详情") },
            text = {
                val book = dialog.book
                Column {
                    DetailRow("书名", book.title)
                    DetailRow("格式", book.format)
                    DetailRow("章节数", "${book.chapterCount}" + if (book.usedVirtualChapters) "（按字数虚拟分章）" else "")
                    DetailRow("存储方式", if (book.storageMode == StorageMode.COPY) "复制到应用内" else "只引用原文件")
                    DetailRow("文件大小", formatSize(book.fileSize))
                    DetailRow("正文编码", book.encoding + if (book.encodingManual) "（手动指定）" else "")
                    DetailRow("判定依据", book.encodingEvidence.ifBlank { "—" })
                    DetailRow("位置", book.filePath)
                    book.sourcePath?.let { DetailRow("原文件", it) }
                    DetailRow("添加时间", formatTime(book.addedAt))
                    DetailRow("最近阅读", if (book.lastReadAt > 0) formatTime(book.lastReadAt) else "还没读过")
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = { onToggleImportMode() }) { Text("切换导入方式") }
            },
        )

        is BookDialog.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("删除书籍") },
            text = {
                Text(
                    text = "确认删除《${dialog.book.title}》？" +
                        if (dialog.book.storageMode == StorageMode.COPY) {
                            "应用内的副本会被一并删除。"
                        } else {
                            "只解除引用，原文件不受影响。"
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(dialog.book.id) }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyShelf() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = "书架还是空的",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "点右下角「导入」选一个 txt 文件，或者在右上角菜单里导入整个文件夹。\n" +
                    "支持 GBK / UTF-8 / UTF-16，100MB 也不会卡。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp,
            )
        }
    }
}

@Composable
private fun NoMatch() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "没有符合条件的书",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 书架封面。
 *
 * 有自定义封面就显示图片（在 IO 线程解码并按「路径 + 修改时间」缓存，滚动时不会反复解码）；
 * 没有、或者文件被清掉了，就回退到「书名首字 + 由书名哈希得到的稳定渐变色」。
 */
@Composable
private fun BookCover(title: String, seed: Int, coverPath: String?) {
    // 用 remember + LaunchedEffect 而不是 produceState：语义一样，
    // 而 lint 对 `by produceState` 里的赋值判定有误报（ProduceStateDoesNotAssignValue）
    var bitmap by remember(coverPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverPath) {
        bitmap = if (coverPath == null) {
            null
        } else {
            withContext(Dispatchers.IO) { ShelfCoverCache.load(coverPath) }
        }
    }

    val shape = RoundedCornerShape(7.dp)
    val size = Modifier.size(width = 52.dp, height = 72.dp)
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = size.clip(shape),
        )
        return
    }

    val hue = ((seed % 360) + 360) % 360
    val top = Color.hsl(hue.toFloat(), 0.34f, 0.80f)
    val bottom = Color.hsl(((hue + 26) % 360).toFloat(), 0.40f, 0.62f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = size.background(
            brush = Brush.verticalGradient(listOf(top, bottom)),
            shape = shape,
        ),
    ) {
        Text(
            text = title.take(1),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 封面位图缓存。键里带上文件修改时间，换了封面自然失效，不用手工清理。 */
private object ShelfCoverCache {
    private val cache = LruCache<String, Bitmap>(16)

    fun load(path: String): Bitmap? {
        val file = Covers.existing(path) ?: return null
        val key = "$path:${file.lastModified()}"
        cache.get(key)?.let { return it }
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }
}

@Composable
private fun ImportDialog(progress: ImportProgress) {
    val label = when (progress.phase) {
        ImportProgress.Phase.COPY -> "正在复制文件"
        ImportProgress.Phase.DETECT -> "正在识别编码"
        ImportProgress.Phase.INDEX -> "正在建立章节索引"
        ImportProgress.Phase.SAVE -> "正在写入书库"
        ImportProgress.Phase.DONE -> "完成"
    }

    AlertDialog(
        onDismissRequest = { },
        confirmButton = { },
        title = { Text("导入中") },
        text = {
            Column {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = progress.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (progress.fraction > 0f) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
    )
}

@Composable
private fun BatchDialog(state: BookshelfViewModel.BatchState) {
    AlertDialog(
        onDismissRequest = { },
        confirmButton = { },
        title = { Text("批量导入") },
        text = {
            Column {
                Text(
                    text = if (state.total == 0) {
                        "正在扫描文件夹…"
                    } else {
                        "正在导入 ${state.done} / ${state.total}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.currentName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.done.toFloat() / state.total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

package com.harness.inkreader.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harness.inkreader.InkApp
import androidx.core.view.WindowCompat
import com.harness.inkreader.data.AnnotationEntity
import com.harness.inkreader.data.ChapterEntity
import com.harness.inkreader.data.settings.Brightness
import com.harness.inkreader.data.settings.ReaderPalettes
import com.harness.inkreader.data.settings.ReaderSettings
import com.harness.inkreader.data.settings.ReadingMode
import com.harness.inkreader.engine.PageRange
import com.harness.inkreader.engine.PagedText
import com.harness.inkreader.engine.SearchHit
import com.harness.inkreader.engine.TextSpec
import com.harness.inkreader.ui.Fonts
import com.harness.inkreader.ui.VolumeKeyBus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as InkApp }
    val viewModel: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = ReaderViewModel.Factory(
            repo = app.repository,
            bookId = bookId,
            settingsFlow = app.settingsStore.settings,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val palette = ReaderPalettes.of(settings.theme)
    val pageBackground = Color(palette.background)
    val textColor = Color(palette.text)

    var barsVisible by remember { mutableStateOf(false) }
    var tocVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var menuVisible by remember { mutableStateOf(false) }
    var noteDialogVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var gestureBrightness by remember { mutableStateOf<Float?>(null) }
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val turnProgress = remember { Animatable(1f) }
    var turnGeometry by remember { mutableStateOf<TurnGeometry?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current

    // 划线与选区都直接往 StaticLayout 上画路径，这两个对象复用即可
    val highlightPath = remember { android.graphics.Path() }
    val highlightPaint = remember { android.graphics.Paint() }
    val scrollState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.saveNow()
        viewModel.endSession()
    }

    // 背景图：按路径缓存解码结果，路径变了才重新解码
    val backgroundImage = remember(settings.backgroundImagePath) {
        settings.backgroundImagePath?.let { path ->
            runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }

    // 独立亮度（跟手拖动时用 gestureBrightness 覆盖）
    val effectiveBrightness = gestureBrightness ?: settings.brightness
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(effectiveBrightness) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness ?: ReaderSettings.FOLLOW_SYSTEM_BRIGHTNESS
        window?.let { target ->
            val attributes = target.attributes
            attributes.screenBrightness = Brightness.windowValue(effectiveBrightness)
            target.attributes = attributes
        }
        onDispose {
            window?.let { target ->
                val attributes = target.attributes
                attributes.screenBrightness = previous
                target.attributes = attributes
            }
        }
    }

    DisposableEffect(settings.keepScreenOn) {
        val window = activity?.window
        if (settings.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun persist(transform: (ReaderSettings) -> ReaderSettings) {
        scope.launch { app.settingsStore.update(transform) }
    }

    var contentWidthPx by remember { mutableIntStateOf(0) }
    var contentHeightPx by remember { mutableIntStateOf(0) }

    /** 翻页：先给当前页拍张快照，再推进，播放动画。动画关闭或拍不到时直接推进。 */
    fun turn(forward: Boolean) {
        val currentState = state
        val paged = currentState.paged
        val geometry = PageTurns.geometry(currentState.settings.pageTurn, forward)
        // 跨块/跨章要读盘，此时新页还没准备好，动画会闪；这种情况不做动画
        val canAnimate = geometry != null &&
            paged != null &&
            contentWidthPx > 0 &&
            contentHeightPx > 0 &&
            (if (forward) currentState.pageIndex < paged.pageCount - 1 else currentState.pageIndex > 0)

        if (!canAnimate) {
            if (forward) viewModel.nextPage() else viewModel.previousPage()
            return
        }

        val bitmap = PageTurns.capturePage(
            paged = paged!!,
            pageIndex = currentState.pageIndex,
            widthPx = contentWidthPx,
            heightPx = contentHeightPx,
            backgroundArgb = pageBackground.toArgb(),
        )
        if (bitmap == null) {
            if (forward) viewModel.nextPage() else viewModel.previousPage()
            return
        }

        snapshot = bitmap.asImageBitmap()
        turnGeometry = geometry
        if (forward) viewModel.nextPage() else viewModel.previousPage()
        scope.launch {
            turnProgress.snapTo(0f)
            turnProgress.animateTo(1f, tween(durationMillis = TURN_DURATION_MS, easing = LinearEasing))
            snapshot = null
            turnGeometry = null
            turnProgress.snapTo(1f)
        }
    }

    val turnNext = rememberUpdatedState {
        if (state.settings.mode == ReadingMode.SCROLL) {
            scope.launch { scrollState.animateScrollBy(contentHeightPx.toFloat()) }
        } else {
            turn(forward = true)
        }
    }
    val turnPrevious = rememberUpdatedState {
        if (state.settings.mode == ReadingMode.SCROLL) {
            scope.launch { scrollState.animateScrollBy(-contentHeightPx.toFloat()) }
        } else {
            turn(forward = false)
        }
    }

    // 音量键翻页：系统会先接走音量键，必须在 Activity 层拦截
    DisposableEffect(settings.volumeKeyPaging, settings.volumeKeyReversed) {
        if (!settings.volumeKeyPaging) {
            VolumeKeyBus.handler = null
            onDispose { }
        } else {
            VolumeKeyBus.handler = { keyCode ->
                val nextKey = if (settings.volumeKeyReversed) {
                    KeyEvent.KEYCODE_VOLUME_UP
                } else {
                    KeyEvent.KEYCODE_VOLUME_DOWN
                }
                when (keyCode) {
                    nextKey -> {
                        turnNext.value(); true
                    }
                    else -> {
                        turnPrevious.value(); true
                    }
                }
            }
            onDispose { VolumeKeyBus.handler = null }
        }
    }

    // 主题配色 → Material3 配色方案。套上之后，顶栏/底栏/目录与搜索抽屉/对话框/芯片/滑杆/
    // 分隔线会**一起**跟着阅读主题变 —— 之前它们用的是应用自身的亮色主题，
    // 所以选了夜间主题时正文是黑的、外框还是亮的（用户说的「非常突兀」）。
    val readerScheme = remember(palette) { readerColorScheme(palette) }
    // 状态栏 / 导航栏的图标明暗也要跟着主题走，否则夜间主题下时间电量的字看不清
    val view = LocalView.current
    val hostActivity = LocalContext.current.findActivity()
    SideEffect {
        val window = hostActivity?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !palette.isDark
                isAppearanceLightNavigationBars = !palette.isDark
            }
        }
    }

    MaterialTheme(colorScheme = readerScheme) {
    // 底色与背景图铺满**整个屏幕**（含状态栏与导航栏那两条），
    // 否则夜间主题下上下会留两条亮边，一样突兀。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawBackgroundImage(backgroundImage, settings.backgroundImageAlpha)
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
        val horizontalPadding = settings.horizontalPaddingDp.dp
        val verticalPadding = settings.verticalPaddingDp.dp
        val widthPx = with(density) { (maxWidth - horizontalPadding * 2).toPx().roundToInt() }
        val heightPx = with(density) { (maxHeight - verticalPadding * 2).toPx().roundToInt() }

        // 注意：不能在组合期间写 state（会触发反复重组），放到 effect 里
        LaunchedEffect(widthPx, heightPx, density.density) {
            contentWidthPx = widthPx
            contentHeightPx = heightPx
            viewModel.setViewport(widthPx, heightPx, density.density)
        }

        val padLeft = with(density) { horizontalPadding.toPx() }
        val padTop = with(density) { verticalPadding.toPx() }

        val readingMode = settings.mode

        if (readingMode == ReadingMode.SCROLL) {
            // ---- 上下滚动（连续轨道 + 稳定 key 锚定）---------------------------
            // 轨道上的每块是一整章，各用**一个画布一次画完**。
            //
            // 为什么用 LazyColumn 而不是 verticalScroll：
            //  ① 每块的 key 稳定，往轨道**头部**插入上一章时 Compose 会自己保持可见位置不动，
            //     不需要我手工去补滚动偏移（我试过手工补，时序上不可靠：
            //     新内容还没排好时补偏移会被夹住，结果反复触发预取、画面乱跳）。
            //  ② 一次画完保证文字落在自己的边界内 —— 之前按页切片、把整块布局平移 -topPx 再裁剪，
            //     文字被画到节点边界外，真机（硬件加速）裁掉后就是「只有每章开头有字」。
            val chunks = state.scrollChunks
            val topPadPx = with(density) { verticalPadding.toPx() }
            val leftPadPx = with(density) { horizontalPadding.toPx() }
            val viewportPx = contentHeightPx
            val activeChunkIndex = chunks.indexOfFirst {
                it.chapterIdx == state.chapterIdx && it.blockIndex == state.blockIndex
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = if (settings.showFooter) 38.dp else 0.dp)
                        // 手势挂在列表自己身上（盖一层全屏透明 Box 会把手势全截走）
                        .pointerInput(state.scrollResetToken, chunks.size) {
                            detectTapGestures(
                                onLongPress = { point ->
                                    val info = scrollState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { point.y.toInt() in it.offset until (it.offset + it.size) }
                                    val chunk = info?.let { chunks.getOrNull(it.index) }
                                    if (info != null && chunk != null) {
                                        val layout = chunk.paged.layout
                                        val within = (point.y - info.offset)
                                            .coerceIn(0f, (layout.height - 1).coerceAtLeast(0).toFloat())
                                        val line = layout.getLineForVertical(within.toInt())
                                        val offset = layout.getOffsetForHorizontal(
                                            line,
                                            (point.x - leftPadPx).coerceAtLeast(0f),
                                        )
                                        viewModel.onScrollPosition(info.index, offset)
                                        viewModel.selectAt(offset)
                                        barsVisible = false
                                    }
                                },
                                onTap = { barsVisible = !barsVisible },
                            )
                        },
                    contentPadding = PaddingValues(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = verticalPadding,
                        bottom = verticalPadding,
                    ),
                ) {
                    items(
                        items = chunks,
                        key = { chunk -> "${chunk.chapterIdx}:${chunk.blockIndex}" },
                    ) { chunk ->
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(with(density) { chunk.heightPx.toDp() }),
                        ) {
                            drawIntoCanvas { canvas ->
                                val native = canvas.nativeCanvas
                                drawHighlights(
                                    native = native,
                                    paged = chunk.paged,
                                    annotations = state.annotations.filter {
                                        it.chapterIdx == chunk.chapterIdx &&
                                            it.blockIndex == chunk.blockIndex
                                    },
                                    selection = if (chunk.chapterIdx == state.chapterIdx &&
                                        chunk.blockIndex == state.blockIndex
                                    ) {
                                        state.selection
                                    } else {
                                        null
                                    },
                                    path = highlightPath,
                                    paint = highlightPaint,
                                )
                                chunk.paged.layout.draw(native)
                            }
                        }
                    }
                    if (state.scrollTrackComplete) {
                        item {
                            Text(
                                text = "— 全书完 —",
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            // 可见位置 → 精确的字符位置（第几块 + 块内像素 → 字符偏移）
            var resetPending by remember { mutableStateOf(false) }
            LaunchedEffect(scrollState, chunks) {
                snapshotFlow {
                    scrollState.firstVisibleItemIndex to scrollState.firstVisibleItemScrollOffset
                }.collect { (index, offsetPx) ->
                    // 归位还没落地时不要回写位置，否则恢复出来的阅读位置会被 0 覆盖掉
                    if (resetPending) return@collect
                    val chunk = chunks.getOrNull(index) ?: return@collect
                    val layout = chunk.paged.layout
                    val y = offsetPx.coerceIn(0, (layout.height - 1).coerceAtLeast(0))
                    val line = layout.getLineForVertical(y)
                    viewModel.onScrollPosition(index, layout.getOffsetForHorizontal(line, 0f))
                }
            }
            // 轨道重置（打开 / 跳章 / 跳搜索 / 改排版）后归位。
            // 必须等列表真的有 items 再滚 —— 否则 scrollToItem 会被夹到 0，
            // 恢复出来的阅读位置就丢了（上一版就是这个 bug）。
            LaunchedEffect(state.scrollResetToken) {
                resetPending = true
                val target = state.scrollResetPx
                snapshotFlow { scrollState.layoutInfo.totalItemsCount }.first { it > 0 }
                scrollState.scrollToItem(0, target)
                resetPending = false
            }
            // 按**真实剩余距离**预取，而不是「看到第几项」。
            //
            // 之前写的是 `last >= total - 2`，但轨道里每块高达十几屏，这个条件只要看到
            // 倒数第二块就成立，于是不停往后预取；轨道超出上限后把头部的块丢掉，
            // 而用户正在读的恰好就是那一块 —— 列表只能跳走。真机表现就是「读着读着突然跳页」。
            LaunchedEffect(scrollState, chunks, viewportPx) {
                if (viewportPx <= 0) return@LaunchedEffect
                snapshotFlow {
                    val info = scrollState.layoutInfo
                    val first = info.visibleItemsInfo.firstOrNull()
                    val last = info.visibleItemsInfo.lastOrNull()
                    val belowViewport = if (last != null) {
                        (last.offset + last.size) - info.viewportEndOffset
                    } else {
                        Int.MAX_VALUE
                    }
                    val intoFirstItem = if (first != null) -first.offset else 0
                    Triple(first?.index ?: -1, intoFirstItem, belowViewport)
                }.collect { (firstIndex, intoFirstItem, belowViewport) ->
                    if (belowViewport < viewportPx) viewModel.appendScrollChunk()
                    if (firstIndex == 0 && intoFirstItem < viewportPx) viewModel.prependScrollChunk()
                }
            }
            // 轨道重置（打开 / 跳章 / 跳搜索 / 改排版）后归位由上面的 effect 负责
            // 自动滚屏：每个间隔平滑滚过一屏
            LaunchedEffect(state.autoPageTurn, readingMode, state.autoPageTurnIntervalSeconds) {
                if (!state.autoPageTurn || readingMode != ReadingMode.SCROLL) return@LaunchedEffect
                while (isActive) {
                    val height = contentHeightPx
                    if (height <= 0 || !state.autoPageTurn) break
                    scrollState.animateScrollBy(
                        height.toFloat(),
                        tween(state.autoPageTurnIntervalSeconds * 1000, easing = LinearEasing),
                    )
                }
            }
        } else {
            // ---- 左右翻页 ----------------------------------------------------
            Canvas(modifier = Modifier.fillMaxSize()) {
                val paged = state.paged
                val page = paged?.pageAt(state.pageIndex)
                val geometry = turnGeometry
                val progress = turnProgress.value

                val drawLive: DrawScope.() -> Unit = {
                    if (paged != null && page != null) {
                        val offsetX = (geometry?.liveOffsetFactor?.invoke(progress) ?: 0f) * widthPx
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            val checkpoint = native.save()
                            native.translate(padLeft + offsetX, padTop - page.topPx)
                            native.clipRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
                            drawHighlights(
                                native = native,
                                paged = paged,
                                annotations = state.annotationsInBlock,
                                selection = state.selection,
                                path = highlightPath,
                                paint = highlightPaint,
                            )
                            paged.layout.draw(native)
                            native.restoreToCount(checkpoint)
                        }
                    }
                }

                val drawSnapshot: DrawScope.() -> Unit = {
                    val image = snapshot
                    if (image != null) {
                        val offsetX = (geometry?.snapshotOffsetFactor?.invoke(progress) ?: 0f) * widthPx
                        drawImage(
                            image = image,
                            dstOffset = IntOffset(
                                (padLeft + offsetX).roundToInt(),
                                padTop.roundToInt(),
                            ),
                        )
                    }
                }

                if (geometry?.snapshotOnTop == false) {
                    drawSnapshot()
                    drawLive()
                } else {
                    drawLive()
                    drawSnapshot()
                }
            }
        }

        // 点击分区翻页 / 长按选中 / 左侧拖动调亮度 —— **只给左右翻页模式用**。
        // 上下滚动模式的手势挂在列表自己身上（见上面的 LazyColumn），
        // 否则这个覆盖全屏的层会把列表的手势全部截走。
        if (readingMode == ReadingMode.PAGE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(state.pageCount, horizontalPadding, verticalPadding) {
                        detectTapGestures(
                            onLongPress = { point ->
                                val offset = charOffsetAt(
                                    point = point,
                                    paged = state.paged,
                                    pageIndex = state.pageIndex,
                                    padLeft = padLeft,
                                    padTop = padTop,
                                )
                                if (offset != null) {
                                    viewModel.selectAt(offset)
                                    barsVisible = false
                                }
                            },
                        ) { point ->
                            val third = size.width / 3f
                            val leftIsNext = state.settings.tapLeftIsNext
                            when {
                                point.x < third -> if (leftIsNext) turnNext.value() else turnPrevious.value()
                                point.x > third * 2 -> if (leftIsNext) turnPrevious.value() else turnNext.value()
                                else -> barsVisible = !barsVisible
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        var active = false
                        detectVerticalDragGestures(
                            onDragStart = { offset -> active = offset.x < size.width * 0.25f },
                            onDragEnd = {
                                if (active) {
                                    gestureBrightness?.let { value ->
                                        persist { it.copy(brightness = value) }
                                    }
                                }
                                active = false
                                gestureBrightness = null
                            },
                            onDragCancel = {
                                active = false
                                gestureBrightness = null
                            },
                        ) { _, dragAmount ->
                            if (!active) return@detectVerticalDragGestures
                            val current = gestureBrightness
                                ?: settings.brightness.takeIf { it >= 0f }
                                ?: 0.5f
                            gestureBrightness = (current - dragAmount / size.height).coerceIn(0.01f, 1f)
                        }
                    }
            )
        }

        // 拖动亮度时给个提示
        gestureBrightness?.let { value ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = "亮度 ${(value * 100).roundToInt()}%",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }

        if (state.error != null) {
            ErrorOverlay(message = state.error!!, onBack = onBack, textColor = textColor)
        } else if (state.loading || state.paged == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = textColor.copy(alpha = 0.5f))
            }
        }

        if (state.paged != null && state.error == null && settings.showFooter) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.chapterTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = palette.footerAlpha),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${state.pageIndex + 1}/${state.pageCount}  ${(state.percent * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = palette.footerAlpha),
                )
            }
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                bookTitle = state.bookTitle,
                chapterTitle = state.chapterTitle,
                bookmarked = state.currentBookmark != null,
                menuExpanded = menuVisible,
                onBack = onBack,
                onOpenToc = { tocVisible = true },
                onSearch = {
                    searchQuery = ""
                    sheet = ReaderSheet.SEARCH
                },
                onToggleBookmark = { viewModel.toggleBookmarkHere() },
                onMenuExpand = { menuVisible = it },
                onOpenBookmarks = { sheet = ReaderSheet.BOOKMARKS },
                onOpenNotes = { sheet = ReaderSheet.NOTES },
                onToggleAutoPageTurn = { viewModel.toggleAutoPageTurn() },
                onOpenStats = onOpenStats,
                onOpenSettings = onOpenSettings,
            )
        }

        // 自动翻页进行中的提示条
        if (state.autoPageTurn) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                ) {
                    Text(
                        text = "自动翻页 ${state.autoPageTurnIntervalSeconds}s",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    TextButton(onClick = { viewModel.toggleAutoPageTurn() }) {
                        Text("停止", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }

        // 选中一句话后的操作条
        state.selection?.let { selection ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 18.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(
                        text = selection.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Row {
                        SelectionAction("复制") {
                            clipboard.setText(AnnotatedString(selection.text))
                            viewModel.clearSelection()
                        }
                        SelectionAction("划线") { viewModel.saveAnnotation(null) }
                        SelectionAction("搜索") {
                            searchQuery = selection.text.take(40)
                            viewModel.clearSelection()
                            viewModel.search(searchQuery)
                            sheet = ReaderSheet.SEARCH
                        }
                        SelectionAction("笔记") { noteDialogVisible = true }
                        SelectionAction("取消") { viewModel.clearSelection() }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                settings = settings,
                percent = state.percent,
                pageLabel = "${state.pageIndex + 1} / ${state.pageCount}",
                onSeek = { viewModel.goToFraction(it) },
                onPreviewSpec = { preview -> viewModel.setSpec(preview) },
                onPersist = { transform -> persist(transform) },
            )
        }
    }
    }

    if (tocVisible) {
        ModalBottomSheet(onDismissRequest = { tocVisible = false }) {
            ChapterList(
                chapters = state.chapters,
                currentIndex = state.chapterIdx,
                onSelect = { index ->
                    viewModel.goToChapter(index)
                    tocVisible = false
                },
            )
        }
    }

    when (sheet) {
        ReaderSheet.SEARCH -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            SearchSheet(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { viewModel.search(searchQuery) },
                search = state.search,
                onOpenHit = { hit ->
                    viewModel.goToHit(hit)
                    sheet = null
                },
            )
        }

        ReaderSheet.BOOKMARKS -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            ItemListSheet(
                title = "书签 · 共 ${state.bookmarks.size} 条",
                empty = "还没有书签。阅读时点顶部星标即可添加。",
                items = state.bookmarks.map { bookmark ->
                    ListItem(
                        title = state.chapters.getOrNull(bookmark.chapterIdx)?.title
                            ?: "第 ${bookmark.chapterIdx + 1} 章",
                        detail = bookmark.preview,
                        onOpen = {
                            viewModel.goToBookmark(bookmark)
                            sheet = null
                        },
                        onDelete = { viewModel.deleteBookmark(bookmark.id) },
                    )
                },
            )
        }

        ReaderSheet.NOTES -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            ItemListSheet(
                title = "笔记与划线 · 共 ${state.annotations.size} 条",
                empty = "还没有划线。长按正文选中一句话，再点「划线」或「笔记」。",
                items = state.annotations.map { annotation ->
                    ListItem(
                        title = annotation.selectedText,
                        detail = annotation.note
                            ?: state.chapters.getOrNull(annotation.chapterIdx)?.title.orEmpty(),
                        onOpen = {
                            viewModel.goToAnnotation(annotation)
                            sheet = null
                        },
                        onDelete = { viewModel.deleteAnnotation(annotation.id) },
                    )
                },
            )
        }

        null -> Unit
    }

    if (noteDialogVisible) {
        NoteDialog(
            onSave = { note ->
                viewModel.saveAnnotation(note)
                noteDialogVisible = false
            },
            onDismiss = { noteDialogVisible = false },
        )
    }
    }
}

/** 背景图按「居中裁剪铺满」绘制，避免被拉变形。 */
private fun DrawScope.drawBackgroundImage(image: android.graphics.Bitmap?, alpha: Float) {
    if (image == null || image.width <= 0 || image.height <= 0) return
    val targetWidth = size.width
    val targetHeight = size.height
    if (targetWidth <= 0f || targetHeight <= 0f) return
    val scale = maxOf(targetWidth / image.width, targetHeight / image.height)
    val cropWidth = (targetWidth / scale).toInt().coerceIn(1, image.width)
    val cropHeight = (targetHeight / scale).toInt().coerceIn(1, image.height)
    drawImage(
        image = image.asImageBitmap(),
        srcOffset = IntOffset((image.width - cropWidth) / 2, (image.height - cropHeight) / 2),
        srcSize = IntSize(cropWidth, cropHeight),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(targetWidth.toInt(), targetHeight.toInt()),
        alpha = alpha.coerceIn(0f, 1f),
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun ErrorOverlay(message: String, onBack: () -> Unit, textColor: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = message, style = MaterialTheme.typography.bodyLarge, color = textColor)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "返回书架",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onBack() },
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    bookTitle: String,
    chapterTitle: String,
    bookmarked: Boolean,
    menuExpanded: Boolean,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onSearch: () -> Unit,
    onToggleBookmark: () -> Unit,
    onMenuExpand: (Boolean) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenNotes: () -> Unit,
    onToggleAutoPageTurn: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(horizontal = 2.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapterTitle.ifEmpty { bookTitle },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chapterTitle.isNotEmpty()) {
                    Text(
                        text = bookTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = if (bookmarked) "取消书签" else "添加书签",
                    tint = if (bookmarked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "搜索")
            }
            IconButton(onClick = onOpenToc) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "目录")
            }
            Box {
                IconButton(onClick = { onMenuExpand(true) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpand(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text("书签列表") },
                        onClick = { onMenuExpand(false); onOpenBookmarks() },
                    )
                    DropdownMenuItem(
                        text = { Text("笔记与划线") },
                        onClick = { onMenuExpand(false); onOpenNotes() },
                    )
                    DropdownMenuItem(
                        text = { Text("自动翻页") },
                        onClick = { onMenuExpand(false); onToggleAutoPageTurn() },
                    )
                    DropdownMenuItem(
                        text = { Text("阅读统计") },
                        onClick = { onMenuExpand(false); onOpenStats() },
                    )
                    DropdownMenuItem(
                        text = { Text("阅读设置") },
                        onClick = { onMenuExpand(false); onOpenSettings() },
                    )
                }
            }
        }
    }
}

/** 把点击位置换算成块内字符偏移，用于长按选中（左右翻页模式）。 */
private fun charOffsetAt(
    point: androidx.compose.ui.geometry.Offset,
    paged: PagedText?,
    pageIndex: Int,
    padLeft: Float,
    padTop: Float,
): Int? {
    val page = paged?.pages?.getOrNull(pageIndex) ?: return null
    val layout = paged.layout
    if (layout.lineCount <= 0) return null
    val x = point.x - padLeft
    val y = point.y - padTop + page.topPx
    val line = layout.getLineForVertical(y.roundToInt()).coerceIn(0, layout.lineCount - 1)
    val clampedX = x.coerceIn(layout.getLineLeft(line), layout.getLineRight(line))
    return layout.getOffsetForHorizontal(line, clampedX)
}

@Composable
private fun SelectionAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun SearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    search: SearchUiState?,
    onOpenHit: (SearchHit) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text("在本书中搜索") },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onSearch,
                enabled = query.isNotBlank() && search?.running != true,
            ) {
                Text("搜索")
            }
        }

        val current = search
        if (current != null && current.running) {
            LinearProgressIndicator(
                progress = { current.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Text(
            text = when {
                current == null -> "输入关键词后点「搜索」。搜索会扫描整本书，100MB 小说约几秒。"
                current.running -> "正在搜索…已找到 ${current.hits.size} 处"
                else -> current.message ?: "找到 ${current.hits.size} 处"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(current?.hits.orEmpty()) { hit ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenHit(hit) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = hit.chapterTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Text(
                        text = hit.snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private class ListItem(
    val title: String,
    val detail: String,
    val onOpen: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
private fun ItemListSheet(
    title: String,
    empty: String,
    items: List<ListItem>,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        HorizontalDivider()
        if (items.isEmpty()) {
            Text(
                text = empty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(items) { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { item.onOpen() }
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = item.onDelete) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun NoteDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("写笔记") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("笔记内容（可留空，仅划线）") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "选中的文字会标记为已划线，并保存这条笔记。",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private enum class ReaderSheet { SEARCH, BOOKMARKS, NOTES }

private const val SELECTION_COLOR = 0x662196F3.toInt()

@Composable
private fun ReaderBottomBar(
    settings: ReaderSettings,
    percent: Float,
    pageLabel: String,
    onSeek: (Float) -> Unit,
    onPreviewSpec: (TextSpec) -> Unit,
    onPersist: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    // 拖动时用本地值即时重排，松手才写库 —— 避免一次拖动往 DataStore 写几十次
    var localFontSize by remember(settings.textSizeSp) { mutableFloatStateOf(settings.textSizeSp) }
    var localLineSpacing by remember(settings.lineSpacingMultiplier) {
        mutableFloatStateOf(settings.lineSpacingMultiplier)
    }

    fun preview(fontSize: Float = localFontSize, lineSpacing: Float = localLineSpacing) {
        onPreviewSpec(
            TextSpecs.from(
                settings.copy(textSizeSp = fontSize, lineSpacingMultiplier = lineSpacing),
                Fonts.typefaceOf(settings.font),
            )
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pageLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = percent,
                    onValueChange = onSeek,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                Text(
                    text = "${(percent * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LabelledSlider(
                label = "字号",
                valueText = "${localFontSize.roundToInt()}",
                value = localFontSize,
                valueRange = ReaderSettings.MIN_TEXT_SIZE_SP..ReaderSettings.MAX_TEXT_SIZE_SP,
                onValueChange = {
                    localFontSize = it
                    preview(fontSize = it)
                },
                onValueChangeFinished = {
                    onPersist { it.copy(textSizeSp = localFontSize) }
                },
            )
            LabelledSlider(
                label = "行距",
                valueText = "%.1f".format(localLineSpacing),
                value = localLineSpacing,
                valueRange = ReaderSettings.MIN_LINE_SPACING..ReaderSettings.MAX_LINE_SPACING,
                onValueChange = {
                    localLineSpacing = it
                    preview(lineSpacing = it)
                },
                onValueChangeFinished = {
                    onPersist { it.copy(lineSpacingMultiplier = localLineSpacing) }
                },
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(32.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChapterList(
    chapters: List<ChapterEntity>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex, chapters.size) {
        if (currentIndex in chapters.indices) listState.scrollToItem(currentIndex)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "目录 · 共 ${chapters.size} 章",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            items(chapters, key = { it.idx }) { chapter ->
                val selected = chapter.idx == currentIndex
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(chapter.idx) }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                )
            }
        }
    }
}

/** 划线与当前选中都画在文字底下，不影响可读性。翻页与滚动两种模式共用。 */
private fun drawHighlights(
    native: android.graphics.Canvas,
    paged: PagedText,
    annotations: List<AnnotationEntity>,
    selection: Selection?,
    path: android.graphics.Path,
    paint: android.graphics.Paint,
) {
    annotations.forEach { annotation ->
        path.reset()
        paged.layout.getSelectionPath(
            annotation.startOffsetInBlock,
            annotation.endOffsetInBlock,
            path,
        )
        paint.color = annotation.color
        paint.style = android.graphics.Paint.Style.FILL
        native.drawPath(path, paint)
    }
    selection?.let { current ->
        path.reset()
        paged.layout.getSelectionPath(current.startChar, current.endChar, path)
        paint.color = SELECTION_COLOR
        paint.style = android.graphics.Paint.Style.FILL
        native.drawPath(path, paint)
    }
}

private const val TURN_DURATION_MS = 220

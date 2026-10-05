package com.harness.inkreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.harness.inkreader.data.AnnotationEntity
import com.harness.inkreader.data.BookEntity
import com.harness.inkreader.data.BookRepository
import com.harness.inkreader.data.BookmarkEntity
import com.harness.inkreader.data.ChapterEntity
import com.harness.inkreader.data.settings.ReaderSettings
import com.harness.inkreader.data.settings.ReadingMode
import com.harness.inkreader.engine.ChapterBlocks
import com.harness.inkreader.engine.FullTextSearcher
import com.harness.inkreader.engine.IndexedChapter
import com.harness.inkreader.engine.PagedText
import com.harness.inkreader.engine.SearchHit
import com.harness.inkreader.engine.TextBlock
import com.harness.inkreader.engine.TextPaginator
import com.harness.inkreader.engine.TextSpec
import com.harness.inkreader.ui.Fonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

data class Viewport(val widthPx: Int, val heightPx: Int, val density: Float)

/** 正文里被选中的一段（用于复制/书签/搜索/记笔记）。 */
data class Selection(
    val startChar: Int,
    val endChar: Int,
    val text: String,
)

data class SearchUiState(
    val query: String,
    val running: Boolean = true,
    val hits: List<SearchHit> = emptyList(),
    val progress: Float = 0f,
    val finished: Boolean = false,
    val message: String? = null,
)

/**
 * 上下滚动轨道最多保留几块。每块通常是一整章，保留 3 块意味着用户往回翻两三章都还在，
 * 同时内存里始终只有 3 份排版。
 */
private const val SCROLL_TRACK_MAX = 3

/**
 * 上下滚动轨道上的一块内容。
 *
 * 一块 = 某一章内 32KB 的一段（中文小说里通常正好是一整章）。界面把这些块按顺序竖着排，
 * 所以从上一章滑到下一章时，上面的文字还留在原地 —— 是「接着往下读」，不是「跳到新章开头」。
 *
 * 保留 [block] 是为了让进度能用它把块内字符偏移精确换算成字节偏移。
 */
data class ScrollChunk(
    val chapterIdx: Int,
    val chapterTitle: String,
    val blockIndex: Int,
    val paged: PagedText,
    val block: TextBlock,
) {
    val heightPx: Int get() = paged.layout.height
}

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bookId: Long = 0,
    val bookTitle: String = "",
    val encoding: String = "",
    val usedVirtualChapters: Boolean = false,
    val chapters: List<ChapterEntity> = emptyList(),
    val chapterIdx: Int = 0,
    val chapterTitle: String = "",
    val blockIndex: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val percent: Float = 0f,
    val spec: TextSpec = TextSpec(),
    /** 当前生效的阅读设置（主题、亮度、翻页方式等）。 */
    val settings: ReaderSettings = ReaderSettings(),
    /** 当前块的排版结果。UI 直接拿它的 layout 画，翻页只是换 pageIndex，不重新排版。 */
    val paged: PagedText? = null,
    val bookmarks: List<BookmarkEntity> = emptyList(),
    /** 整本书的划线/笔记。数量很少，界面按当前块过滤即可。 */
    val annotations: List<AnnotationEntity> = emptyList(),
    val search: SearchUiState? = null,
    val selection: Selection? = null,
    val autoPageTurn: Boolean = false,
    val autoPageTurnIntervalSeconds: Int = DEFAULT_AUTO_TURN_SECONDS,
    /** 当前块之后还有没有内容（决定上下滚动模式要不要继续加载）。 */
    val hasNextBlock: Boolean = false,
    /**
     * 上下滚动模式的连续轨道：按顺序排好的若干块，界面把它们竖着排。
     * 跨章时上方内容还在，所以不会「跳到新章开头」。
     */
    val scrollChunks: List<ScrollChunk> = emptyList(),
    /** 正在后台预取下一块（界面可以据此显示一点点提示）。 */
    val appendingChunk: Boolean = false,
    /** 正在往上预取上一块。 */
    val prependingChunk: Boolean = false,
    /** 轨道已经接到全书末尾，没有更多内容了。 */
    val scrollTrackComplete: Boolean = false,
    /** 轨道已经顶到全书开头，上面没有内容了。 */
    val scrollTrackStartReached: Boolean = false,
    /** 轨道被重置（打开 / 跳章 / 跳搜索命中 / 改排版）时自增，界面据此把滚动位置归位。 */
    val scrollResetToken: Int = 0,
    /** 归位目标：重置后应滚到的块内像素位置。 */
    val scrollResetPx: Int = 0,
) {
    val atFirstPage: Boolean get() = pageIndex <= 0
    val canGoPreviousChapter: Boolean get() = chapterIdx > 0
    val atBookEnd: Boolean get() = chapterIdx >= chapters.lastIndex && pageIndex >= pageCount - 1
    val atDocumentEnd: Boolean get() = !hasNextBlock && chapterIdx >= chapters.lastIndex

    /** 当前块里与当前章节+块号匹配的划线。 */
    val annotationsInBlock: List<AnnotationEntity>
        get() = annotations.filter { it.chapterIdx == chapterIdx && it.blockIndex == blockIndex }

    val currentBookmark: BookmarkEntity?
        get() = bookmarks.firstOrNull {
            it.chapterIdx == chapterIdx && it.blockIndex == blockIndex &&
                it.charOffsetInBlock == (paged?.pages?.getOrNull(pageIndex)?.startChar ?: -1)
        }

    companion object {
        const val DEFAULT_AUTO_TURN_SECONDS = 15
    }
}

/**
 * 阅读器状态机。
 *
 * 位置由 (章节, 块号, 块内字符偏移) 三元组表达。章节内部再分块，是因为
 * 「100MB 只有 5 章」意味着单章 20MB，不能整体读进内存排版。
 */
class ReaderViewModel(
    private val repo: BookRepository,
    private val bookId: Long,
    /** 章节内部的分块粒度。测试里会调小，以便用很小的章节覆盖多块导航。 */
    private val blockBytes: Int = ChapterBlocks.DEFAULT_BLOCK_BYTES,
    /** 阅读设置来源。设置面板写库 → 这里收到新值 → 自动重新分页。 */
    settingsFlow: Flow<ReaderSettings> = flowOf(ReaderSettings()),
    private val specFor: (ReaderSettings) -> TextSpec = { settings ->
        TextSpecs.from(settings, Fonts.typefaceOf(settings.font))
    },
    /** 可注入的时钟：阅读时长统计要能在测试里确定性地推进。 */
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ReaderUiState(bookId = bookId, spec = specFor(ReaderSettings())),
    )
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var started = false

    private var book: BookEntity? = null
    private var chapters: List<ChapterEntity> = emptyList()
    private var blocks: ChapterBlocks? = null
    private var block: TextBlock? = null
    private var viewport: Viewport? = null
    private var pending: PendingPosition? = null
    private var saveJob: Job? = null

    /** 每次「用户主动改变位置」（翻页、跳章、换块）自增，用来识别重排期间的位置变化。 */
    private var positionGeneration = 0

    private var searchJob: Job? = null
    private var autoPageTurnJob: Job? = null

    /** 本次阅读会话的起点；结束（onPause）时落一条统计。 */
    private var sessionStartedAt = 0L
    private var charsReadThisSession = 0L

    private var lastObservedChar: Int? = null
    private var lastObservedBlock = -1
    private var lastObservedChapter = -1

    private class PendingPosition(
        val chapterIdx: Int,
        val blockIndex: Int,
        val charOffsetInBlock: Int,
    )

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch { loadBook() }
    }

    init {
        viewModelScope.launch {
            settingsFlow.collect { applySettings(it) }
        }
        viewModelScope.launch {
            repo.observeBookmarks(bookId).collect { list ->
                _state.update { it.copy(bookmarks = list) }
            }
        }
        viewModelScope.launch {
            repo.observeAnnotations(bookId).collect { list ->
                _state.update { it.copy(annotations = list) }
            }
        }
    }

    // ---------------------------------------------------------------- 全文搜索

    fun search(query: String) {
        searchJob?.cancel()
        val needle = query.trim()
        if (needle.isEmpty()) {
            _state.update { it.copy(search = null) }
            return
        }
        _state.update { it.copy(search = SearchUiState(query = needle)) }

        searchJob = viewModelScope.launch {
            val currentBook = book ?: return@launch
            val source = repo.byteSourceFor(currentBook)
            val charset = charsetOf(currentBook.encoding)
            val targets = chapters.map {
                IndexedChapter(it.idx, it.title, it.startByte, it.endByte)
            }
            var hits: List<SearchHit> = emptyList()
            var failure: String? = null
            try {
                hits = withContext(Dispatchers.IO) {
                    // 必须用与阅读页完全相同的分块粒度：搜索返回的是 (块号, 块内偏移)，
                    // 分块粒度不同就会算出一套对不上的坐标，点结果会跳到错误的位置。
                    FullTextSearcher(blockBytes = blockBytes).search(
                        source = source,
                        charset = charset,
                        chapters = targets,
                        query = needle,
                        isCancelled = { !isActive },
                    ) { progress ->
                        _state.update { current ->
                            current.copy(
                                search = (current.search ?: SearchUiState(needle)).copy(
                                    progress = progress.fraction,
                                    running = true,
                                )
                            )
                        }
                    }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
            }
            _state.update {
                it.copy(
                    search = SearchUiState(
                        query = needle,
                        running = false,
                        hits = hits,
                        progress = 1f,
                        finished = true,
                        message = failure ?: if (hits.isEmpty()) "没有找到「$needle」" else null,
                    )
                )
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        _state.update { it.copy(search = null) }
    }

    fun goToHit(hit: SearchHit) {
        viewModelScope.launch {
            mutex.withLock { loadBlockLocked(hit.chapterIdx, hit.blockIndex, hit.charOffsetInBlock) }
        }
    }

    // ---------------------------------------------------------------- 书签

    fun toggleBookmarkHere() {
        val snapshot = _state.value
        val page = snapshot.paged?.pages?.getOrNull(snapshot.pageIndex) ?: return
        val existing = snapshot.currentBookmark
        viewModelScope.launch {
            if (existing != null) {
                repo.deleteBookmark(existing.id)
            } else {
                val preview = snapshot.paged.layout.text
                    .subSequence(page.startChar, page.endChar)
                    .toString()
                    .replace('\n', ' ')
                    .trim()
                repo.addBookmark(
                    bookId = bookId,
                    chapterIdx = snapshot.chapterIdx,
                    blockIndex = snapshot.blockIndex,
                    charOffsetInBlock = page.startChar,
                    preview = preview.ifEmpty { snapshot.chapterTitle },
                )
            }
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repo.deleteBookmark(id) }
    }

    fun goToBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            mutex.withLock {
                loadBlockLocked(bookmark.chapterIdx, bookmark.blockIndex, bookmark.charOffsetInBlock)
            }
        }
    }

    // ---------------------------------------------------------------- 选中与笔记

    /**
     * 长按某处 → 选中「一句话」。中文按句末标点断句，并且不跨段，
     * 比按词选中更符合读小说的使用习惯。
     */
    fun selectAt(charOffsetInBlock: Int) {
        val text = block?.text ?: return
        if (text.isEmpty()) return
        val anchor = charOffsetInBlock.coerceIn(0, text.length - 1)

        var sentenceStart = anchor
        while (sentenceStart > 0) {
            val previous = text[sentenceStart - 1]
            if (previous == '\n' || isSentenceEnd(previous)) break
            sentenceStart--
        }
        var sentenceEnd = anchor
        while (sentenceEnd < text.length && !isSentenceEnd(text[sentenceEnd])) sentenceEnd++
        if (sentenceEnd < text.length) sentenceEnd++

        val selected = text.substring(sentenceStart, sentenceEnd).trim()
        if (selected.isEmpty()) {
            _state.update { it.copy(selection = null) }
            return
        }
        _state.update {
            it.copy(
                selection = Selection(
                    startChar = sentenceStart,
                    endChar = sentenceEnd,
                    text = selected,
                )
            )
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = null) }
    }

    /** 保存划线（可带笔记），保存后清掉选中状态。 */
    fun saveAnnotation(note: String?) {
        val snapshot = _state.value
        val selection = snapshot.selection ?: return
        viewModelScope.launch {
            repo.addAnnotation(
                bookId = bookId,
                chapterIdx = snapshot.chapterIdx,
                blockIndex = snapshot.blockIndex,
                startOffsetInBlock = selection.startChar,
                endOffsetInBlock = selection.endChar,
                selectedText = selection.text,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
            )
            _state.update { it.copy(selection = null) }
        }
    }

    fun deleteAnnotation(id: Long) {
        viewModelScope.launch { repo.deleteAnnotation(id) }
    }

    fun goToAnnotation(annotation: AnnotationEntity) {
        viewModelScope.launch {
            mutex.withLock {
                loadBlockLocked(
                    annotation.chapterIdx,
                    annotation.blockIndex,
                    annotation.startOffsetInBlock,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 上下滚动

    /**
     * 上下滚动模式：可见页变化时同步阅读位置。
     * 只更新位置与进度，**不重新分页**（滚动必须跟手）。
     */
    fun onScrollToPage(index: Int) {
        val snapshot = _state.value
        val paged = snapshot.paged ?: return
        if (index !in 0 until paged.pageCount) return
        if (index == snapshot.pageIndex) return
        positionGeneration++
        _state.update { it.copy(pageIndex = index) }
        afterPageChange()
    }

    /**
     * 上下滚动模式：滚动位置变化（轨道上第 [chunkIndex] 块的 [charOffsetInChunk] 处）。
     *
     * 一切以这里为准 —— 顶部条、进度、书签、划线都用同一份坐标，
     * 所以从上一章滑到下一章时它们会自动跟着变。
     */
    fun onScrollPosition(chunkIndex: Int, charOffsetInChunk: Int) {
        val snapshot = _state.value
        val chunk = snapshot.scrollChunks.getOrNull(chunkIndex) ?: return
        val pageIndex = pageContaining(chunk.paged, charOffsetInChunk)
        val sameChunk = snapshot.chapterIdx == chunk.chapterIdx &&
            snapshot.blockIndex == chunk.blockIndex
        if (sameChunk && pageIndex == snapshot.pageIndex) return

        positionGeneration++
        block = chunk.block
        _state.update {
            it.copy(
                chapterIdx = chunk.chapterIdx,
                chapterTitle = chunk.chapterTitle,
                blockIndex = chunk.blockIndex,
                paged = chunk.paged,
                pageIndex = pageIndex,
                pageCount = chunk.paged.pageCount,
                percent = percentFor(
                    chunk.block,
                    chunk.paged,
                    pageIndex,
                    book?.totalBytes ?: 0L,
                ),
            )
        }
        afterPageChange()
    }

    /**
     * 上下滚动模式：往轨道尾部预取一块。
     *
     * 界面在滚到当前内容末尾**之前**调用它，这样新内容已经排好在那里，
     * 用户滑过章与章的接缝时不会看到任何跳变。
     */
    fun appendScrollChunk() {
        if (_state.value.appendingChunk) return
        viewModelScope.launch {
            mutex.withLock {
                if (_state.value.appendingChunk) return@withLock
                val last = _state.value.scrollChunks.lastOrNull() ?: return@withLock
                _state.update { it.copy(appendingChunk = true) }
                val next = buildScrollChunkAfter(last)
                _state.update { current ->
                    if (next == null) {
                        current.copy(appendingChunk = false, scrollTrackComplete = true)
                    } else {
                        val grown = current.scrollChunks + next
                        current.copy(
                            scrollChunks = trimScrollTrack(
                                grown = grown,
                                activeChapter = current.chapterIdx,
                                activeBlock = current.blockIndex,
                            ),
                            appendingChunk = false,
                            scrollTrackComplete = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * 上下滚动模式：往轨道**头部**预取一块。
     *
     * 从目录跳章过来之后轨道只有那一章，用户往回滑本来会卡在章首；有了这个，
     * 往上滑就能接着读上一章的结尾 —— 和往下滑是对称的。
     */
    fun prependScrollChunk() {
        if (_state.value.prependingChunk || _state.value.scrollTrackStartReached) return
        viewModelScope.launch {
            mutex.withLock {
                if (_state.value.prependingChunk || _state.value.scrollTrackStartReached) {
                    return@withLock
                }
                val first = _state.value.scrollChunks.firstOrNull() ?: return@withLock
                _state.update { it.copy(prependingChunk = true) }
                val previous = buildScrollChunkBefore(first)
                _state.update { current ->
                    if (previous == null) {
                        // 已经到全书开头了，别再反复试
                        current.copy(prependingChunk = false, scrollTrackStartReached = true)
                    } else {
                        val grown = listOf(previous) + current.scrollChunks
                        current.copy(
                            scrollChunks = trimScrollTrack(
                                grown = grown,
                                activeChapter = current.chapterIdx,
                                activeBlock = current.blockIndex,
                            ),
                            prependingChunk = false,
                        )
                    }
                }
            }
        }
    }

    private suspend fun buildScrollChunkBefore(current: ScrollChunk): ScrollChunk? {
        val currentBlocks = blocksFor(current.chapterIdx)
        val previous = currentBlocks.previousIndex(current.blockIndex)
        if (previous >= 0) return buildScrollChunk(current.chapterIdx, previous, currentBlocks)
        val previousChapter = current.chapterIdx - 1
        if (previousChapter < 0) return null
        val previousBlocks = blocksFor(previousChapter)
        val lastBlock = previousBlocks.lastIndex()
        if (lastBlock < 0) return null
        return buildScrollChunk(previousChapter, lastBlock, previousBlocks)
    }

    /**
     * 轨道超出窗口上限时收缩，但**绝不丢掉用户正在读的那一块**。
     *
     * 丢正在读的块会让列表为了保持可见项而跳走 —— 真机上就是「读着读着突然跳页」。
     * 所以按「活跃块在哪一半」来决定从哪一头丢。
     */
    private fun trimScrollTrack(
        grown: List<ScrollChunk>,
        activeChapter: Int,
        activeBlock: Int,
    ): List<ScrollChunk> {
        if (grown.size <= SCROLL_TRACK_MAX) return grown
        val activeIndex = grown.indexOfFirst {
            it.chapterIdx == activeChapter && it.blockIndex == activeBlock
        }
        val overflow = grown.size - SCROLL_TRACK_MAX
        // 活跃块在前半 → 从尾部丢；否则从头部丢
        val dropFromHead = activeIndex < 0 || activeIndex >= overflow
        return if (dropFromHead) {
            grown.subList(overflow, grown.size).toList()
        } else {
            grown.subList(0, grown.size - overflow)
        }
    }

    private suspend fun buildScrollChunkAfter(current: ScrollChunk): ScrollChunk? {        val currentBlocks = blocksFor(current.chapterIdx)
        val next = currentBlocks.nextIndex(current.blockIndex)
        if (next >= 0) return buildScrollChunk(current.chapterIdx, next, currentBlocks)
        val nextChapter = current.chapterIdx + 1
        if (nextChapter >= chapters.size) return null
        return buildScrollChunk(nextChapter, 0, blocksFor(nextChapter))
    }

    private suspend fun buildScrollChunk(
        chapterIdx: Int,
        blockIndex: Int,
        currentBlocks: ChapterBlocks,
    ): ScrollChunk? {
        val vp = viewport ?: return null
        val chapter = chapters.getOrNull(chapterIdx) ?: return null
        val loaded = withContext(Dispatchers.IO) { currentBlocks.load(blockIndex) }
        val spec = _state.value.spec
        val paged = withContext(Dispatchers.Default) {
            TextPaginator.paginate(loaded.text, spec, vp.widthPx, vp.heightPx, vp.density)
        }
        return ScrollChunk(
            chapterIdx = chapterIdx,
            chapterTitle = chapter.title,
            blockIndex = blockIndex,
            paged = paged,
            block = loaded,
        )
    }

    /** 上下滚动模式：滚到当前块末尾时接着加载下一块 / 下一章。 */
    fun continueToNextBlock() {
        viewModelScope.launch {
            mutex.withLock {
                if (_state.value.atDocumentEnd) return@withLock
                val next = blocks?.nextIndex(_state.value.blockIndex) ?: -1
                if (next >= 0) {
                    loadBlockLocked(_state.value.chapterIdx, next, 0)
                } else {
                    val nextChapter = _state.value.chapterIdx + 1
                    if (nextChapter < chapters.size) loadBlockLocked(nextChapter, 0, 0)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 自动翻页

    fun toggleAutoPageTurn() {
        val next = !_state.value.autoPageTurn
        _state.update { it.copy(autoPageTurn = next) }
        if (next) startAutoPageTurn() else stopAutoPageTurn()
    }

    fun setAutoPageTurnInterval(seconds: Int) {
        _state.update { it.copy(autoPageTurnIntervalSeconds = seconds.coerceIn(5, 120)) }
        if (_state.value.autoPageTurn) {
            stopAutoPageTurn()
            startAutoPageTurn()
        }
    }

    private fun startAutoPageTurn() {
        autoPageTurnJob?.cancel()
        autoPageTurnJob = null
        // 上下滚动模式的「自动滚屏」由界面做平滑滚动；这里只负责左右翻页模式。
        if (_state.value.settings.mode != ReadingMode.PAGE) return
        autoPageTurnJob = viewModelScope.launch {
            while (isActive && _state.value.autoPageTurn) {
                delay(_state.value.autoPageTurnIntervalSeconds * 1000L)
                if (!_state.value.autoPageTurn) break
                if (_state.value.atBookEnd) {
                    _state.update { it.copy(autoPageTurn = false) }
                    break
                }
                nextPage()
            }
        }
    }

    private fun stopAutoPageTurn() {
        autoPageTurnJob?.cancel()
        autoPageTurnJob = null
    }

    // ---------------------------------------------------------------- 阅读时长

    /** 由界面在 onPause 调用：把这一段阅读落库（不足 5 秒的忽略）。 */
    fun endSession() {
        val startedAt = sessionStartedAt
        val endedAt = now()
        sessionStartedAt = endedAt
        val chars = charsReadThisSession
        charsReadThisSession = 0
        lastObservedChar = null
        if (startedAt <= 0L) return
        if (endedAt - startedAt < MIN_SESSION_MILLIS) return
        viewModelScope.launch { repo.recordSession(bookId, startedAt, endedAt, chars) }
    }

    /**
     * 累计「真的读了几个字」。只在同一块内**向前**翻页时累加；跳章、拖进度条都不算，
     * 这样速度统计不会被跳读污染。
     */
    private fun accountChars() {
        val snapshot = _state.value
        val pageChar = snapshot.paged?.pages?.getOrNull(snapshot.pageIndex)?.startChar
        if (pageChar == null) {
            lastObservedChar = null
            return
        }
        val previous = lastObservedChar
        val samePosition = lastObservedBlock == snapshot.blockIndex &&
            lastObservedChapter == snapshot.chapterIdx
        if (previous != null && samePosition) {
            val page = snapshot.paged.pages.getOrNull(snapshot.pageIndex)
            val pageChars = ((page?.endChar ?: 0) - (page?.startChar ?: 0)).coerceAtLeast(1)
            val delta = pageChar - previous
            // 只累计**小幅前进**：跳章、拖进度条、滚动模式里大幅回翻都不算，
            // 否则来回滚动会把「读过多少字」越滚越多。
            if (delta in 1..(pageChars * 4)) charsReadThisSession += delta
        }
        lastObservedChar = pageChar
        lastObservedBlock = snapshot.blockIndex
        lastObservedChapter = snapshot.chapterIdx
    }

    /**
     * 设置变化：先记住新设置，排版规格真的变了才重新分页（并且尽量停在原来那段文字上）。
     * 只改主题颜色/亮度/翻页方式这类不影响排版的项目时，不会触发重新分页。
     *
     * 关键点：**spec 的发布必须在锁内**与重排配对。否则一个带着旧 spec 的在途重排会随后
     * 写回「旧排版 + 页码」，让状态出现「settings 是 32 号字、排版却是 18 号字」的错配，
     * 用户会看到先按旧字号渲染一帧。
     */
    private suspend fun applySettings(settings: ReaderSettings) {
        val newSpec = specFor(settings)
        if (newSpec == _state.value.spec) {
            val modeChanged = _state.value.settings.mode != settings.mode
            _state.update { it.copy(settings = settings) }
            // 排版没变，但阅读方式可能变了：自动翻页 / 自动滚屏的驱动方式要跟着换
            if (modeChanged && _state.value.autoPageTurn) {
                stopAutoPageTurn()
                startAutoPageTurn()
            }
            return
        }
        mutex.withLock {
            _state.update { it.copy(settings = settings, spec = newSpec) }
            repaginateLocked(keepCharOffset = currentPageStartChar())
        }
        if (_state.value.autoPageTurn) {
            stopAutoPageTurn()
            startAutoPageTurn()
        }
    }

    private suspend fun loadBook() = mutex.withLock {
        val loaded = repo.book(bookId)
        if (loaded == null) {
            _state.update { it.copy(loading = false, error = "找不到这本书") }
            return@withLock
        }
        val chapterList = repo.chapters(bookId)
        if (chapterList.isEmpty()) {
            _state.update {
                it.copy(loading = false, error = "这本书还没有章节索引", bookTitle = loaded.title)
            }
            return@withLock
        }

        book = loaded
        chapters = chapterList
        if (sessionStartedAt <= 0L) sessionStartedAt = now()

        val progress = repo.progress(bookId)
        pending = PendingPosition(
            chapterIdx = (progress?.chapterIdx ?: 0).coerceIn(0, chapterList.lastIndex),
            blockIndex = (progress?.blockIndex ?: 0).coerceAtLeast(0),
            charOffsetInBlock = (progress?.charOffsetInBlock ?: 0).coerceAtLeast(0),
        )

        _state.update {
            it.copy(
                loading = true,
                error = null,
                bookTitle = loaded.title,
                encoding = loaded.encoding,
                usedVirtualChapters = loaded.usedVirtualChapters,
                chapters = chapterList,
            )
        }

        if (viewport != null) loadPendingLocked()
    }

    fun setViewport(widthPx: Int, heightPx: Int, density: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        val requested = Viewport(widthPx, heightPx, density)
        val previous = viewport
        if (requested == previous) return
        viewport = requested

        viewModelScope.launch {
            mutex.withLock {
                if (book == null || chapters.isEmpty()) return@withLock
                if (pending != null) {
                    loadPendingLocked()
                } else {
                    repaginateLocked(keepCharOffset = currentPageStartChar())
                }
            }
        }
    }

    fun setSpec(spec: TextSpec) {
        if (spec == _state.value.spec) return
        _state.update { it.copy(spec = spec) }
        viewModelScope.launch {
            mutex.withLock { repaginateLocked(keepCharOffset = currentPageStartChar()) }
        }
    }

    fun nextPage() {
        val snapshot = _state.value
        val paged = snapshot.paged ?: return
        if (snapshot.pageIndex + 1 < paged.pageCount) {
            positionGeneration++
            _state.update { it.copy(pageIndex = it.pageIndex + 1) }
            afterPageChange()
            return
        }
        viewModelScope.launch {
            mutex.withLock {
                val currentBlocks = blocks ?: return@withLock
                val nextBlock = currentBlocks.nextIndex(_state.value.blockIndex)
                if (nextBlock >= 0) {
                    loadBlockLocked(_state.value.chapterIdx, nextBlock, 0)
                } else {
                    val nextChapter = _state.value.chapterIdx + 1
                    if (nextChapter < chapters.size) loadBlockLocked(nextChapter, 0, 0)
                }
            }
        }
    }

    fun previousPage() {
        val snapshot = _state.value
        if (snapshot.pageIndex > 0) {
            positionGeneration++
            _state.update { it.copy(pageIndex = it.pageIndex - 1) }
            afterPageChange()
            return
        }
        viewModelScope.launch {
            mutex.withLock {
                val currentBlocks = blocks ?: return@withLock
                val previous = currentBlocks.previousIndex(_state.value.blockIndex)
                if (previous >= 0) {
                    loadBlockLocked(_state.value.chapterIdx, previous, Int.MAX_VALUE)
                } else {
                    val previousChapter = _state.value.chapterIdx - 1
                    if (previousChapter >= 0) {
                        val target = blocksFor(previousChapter)
                        loadBlockLocked(previousChapter, target.lastIndex(), Int.MAX_VALUE)
                    }
                }
            }
        }
    }

    fun goToChapter(chapterIdx: Int) {
        viewModelScope.launch {
            mutex.withLock {
                if (chapterIdx !in chapters.indices) return@withLock
                loadBlockLocked(chapterIdx, 0, 0)
            }
        }
    }

    fun goToFraction(fraction: Float) {
        viewModelScope.launch {
            mutex.withLock {
                val currentBook = book ?: return@withLock
                if (currentBook.totalBytes <= 0L || chapters.isEmpty()) return@withLock
                val target = (fraction.coerceIn(0f, 1f) * currentBook.totalBytes).toLong()
                val chapterIdx = chapterIndexAt(target)
                val chapter = chapters[chapterIdx]
                val blockIndex = blocksFor(chapterIdx).indexAt(target.coerceAtLeast(chapter.startByte))
                loadBlockLocked(chapterIdx, blockIndex, 0)
            }
        }
    }

    /** 屏幕 onPause 时调用，保证进程被回收也不会丢阅读位置。 */
    fun saveNow() {
        viewModelScope.launch { persistProgress() }
    }

    // ---------------------------------------------------------------- 内部实现

    private suspend fun loadPendingLocked() {
        val position = pending ?: return
        pending = null
        loadBlockLocked(position.chapterIdx, position.blockIndex, position.charOffsetInBlock)
    }

    private suspend fun loadBlockLocked(
        chapterIdx: Int,
        blockIndex: Int,
        charOffsetInBlock: Int,
    ) {
        val currentBook = book ?: return
        val vp = viewport ?: return
        val chapter = chapters.getOrNull(chapterIdx) ?: return

        val currentBlocks = blocksFor(chapterIdx)
        val safeBlockIndex = blockIndex.coerceAtLeast(0)
        val loaded = withContext(Dispatchers.IO) { currentBlocks.load(safeBlockIndex) }
        val spec = _state.value.spec
        val paged = withContext(Dispatchers.Default) {
            TextPaginator.paginate(
                text = loaded.text,
                spec = spec,
                widthPx = vp.widthPx,
                heightPx = vp.heightPx,
                density = vp.density,
            )
        }

        blocks = currentBlocks
        block = loaded
        positionGeneration++
        val hasMoreBlocks = currentBlocks.nextIndex(safeBlockIndex) >= 0

        val page = pageContaining(paged, charOffsetInBlock)
        _state.update {
            it.copy(
                loading = false,
                error = null,
                chapterIdx = chapterIdx,
                chapterTitle = chapter.title,
                blockIndex = safeBlockIndex,
                paged = paged,
                pageIndex = page,
                pageCount = paged.pageCount,
                percent = percentFor(loaded, paged, page, currentBook.totalBytes),
                hasNextBlock = hasMoreBlocks,
                // 换章 / 跳转后轨道重置成这一块，并让界面把滚动位置归位到目标页
                scrollChunks = listOf(
                    ScrollChunk(chapterIdx, chapter.title, safeBlockIndex, paged, loaded),
                ),
                scrollResetToken = it.scrollResetToken + 1,
                scrollResetPx = paged.pages.getOrNull(page)?.topPx ?: 0,
                scrollTrackComplete = false,
                scrollTrackStartReached = false,
            )
        }
        scheduleProgressSave()
        accountChars()
        if (loaded.isEmpty && loaded.byteLength == 0L) {
            _state.update { it.copy(error = "这一章读不出内容，可能编码判断有误") }
        }
    }

    private suspend fun repaginateLocked(keepCharOffset: Int?) {
        val vp = viewport ?: return
        val loaded = block ?: return
        val spec = _state.value.spec
        // 分页是异步的（要跑 StaticLayout）。如果这期间用户翻了页，绝不能把它撤销掉 ——
        // 用世代号识别这种情况：位置变了就保留用户的新位置，只把新排版换上。
        val positionGenerationAtStart = positionGeneration
        val paged = withContext(Dispatchers.Default) {
            TextPaginator.paginate(loaded.text, spec, vp.widthPx, vp.heightPx, vp.density)
        }
        val positionMovedDuringPagination = positionGeneration != positionGenerationAtStart
        val page = if (positionMovedDuringPagination) {
            pageContaining(paged, currentPageStartChar() ?: 0)
        } else {
            keepCharOffset?.let { offset -> pageContaining(paged, offset) } ?: 0
        }
        _state.update {
            val safePage = page.coerceIn(0, (paged.pageCount - 1).coerceAtLeast(0))
            it.copy(
                paged = paged,
                pageIndex = safePage,
                pageCount = paged.pageCount,
                percent = percentFor(loaded, paged, safePage, book?.totalBytes ?: 0L),
                // 排版变了，轨道里其它块还是旧排版 —— 只留当前这一块，位置不变
                scrollChunks = listOf(
                    ScrollChunk(
                        chapterIdx = it.chapterIdx,
                        chapterTitle = it.chapterTitle,
                        blockIndex = it.blockIndex,
                        paged = paged,
                        block = loaded,
                    ),
                ),
                scrollResetToken = it.scrollResetToken + 1,
                scrollResetPx = paged.pages.getOrNull(safePage)?.topPx ?: 0,
                scrollTrackStartReached = false,
            )
        }
        scheduleProgressSave()
    }

    private fun afterPageChange() {
        val snapshot = _state.value
        val loaded = block
        val paged = snapshot.paged
        if (loaded != null && paged != null) {
            _state.update {
                it.copy(
                    percent = percentFor(
                        loaded,
                        paged,
                        snapshot.pageIndex,
                        book?.totalBytes ?: 0L,
                    )
                )
            }
        }
        accountChars()
        scheduleProgressSave()
    }

    private fun pageContaining(paged: PagedText, charOffset: Int): Int {
        if (paged.pageCount <= 0) return 0
        val index = paged.pages.indexOfFirst { charOffset < it.endChar }
        return if (index >= 0) index else paged.pageCount - 1
    }

    private fun currentPageStartChar(): Int? {
        val snapshot = _state.value
        return snapshot.paged?.pages?.getOrNull(snapshot.pageIndex)?.startChar
    }

    private fun percentFor(
        loaded: TextBlock,
        paged: PagedText,
        page: Int,
        totalBytes: Long,
    ): Float {
        if (totalBytes <= 0L) return 0f
        val pageStart = paged.pages.getOrNull(page)?.startChar ?: 0
        val absolute = loaded.byteOffsetOfChar(pageStart)
        return (absolute.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    private fun blocksFor(chapterIdx: Int): ChapterBlocks {
        val currentBook = book ?: error("book not loaded")
        val chapter = chapters[chapterIdx]
        return ChapterBlocks(
            // 复制模式与引用模式在这里统一：仓库按存储模式给出对应的 ByteSource
            source = repo.byteSourceFor(currentBook),
            charset = charsetOf(currentBook.encoding),
            chapterStart = chapter.startByte,
            chapterEnd = chapter.endByte,
            blockBytes = blockBytes,
        )
    }

    private fun chapterIndexAt(byteOffset: Long): Int {
        var low = 0
        var high = chapters.lastIndex
        var answer = 0
        while (low <= high) {
            val mid = (low + high) / 2
            if (chapters[mid].startByte <= byteOffset) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    private fun charsetOf(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrDefault(StandardCharsets.UTF_8)

    private fun scheduleProgressSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            persistProgress()
        }
    }

    private suspend fun persistProgress() {
        val snapshot = _state.value
        val currentBook = book ?: return
        val paged = snapshot.paged ?: return
        val pageStart = paged.pages.getOrNull(snapshot.pageIndex)?.startChar ?: 0
        repo.saveProgress(
            bookId = currentBook.id,
            chapterIdx = snapshot.chapterIdx,
            blockIndex = snapshot.blockIndex,
            charOffsetInBlock = pageStart,
            percent = snapshot.percent,
        )
    }

    class Factory(
        private val repo: BookRepository,
        private val bookId: Long,
        private val blockBytes: Int = ChapterBlocks.DEFAULT_BLOCK_BYTES,
        private val settingsFlow: Flow<ReaderSettings> = flowOf(ReaderSettings()),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(repo, bookId, blockBytes, settingsFlow) as T
    }

    private companion object {
        const val PROGRESS_SAVE_DEBOUNCE_MS = 400L

        /** 不足 5 秒的停留不记入阅读统计（翻一下就走的误触）。 */
        const val MIN_SESSION_MILLIS = 5_000L
    }

    /** 句末标点：中文按句号/问号/叹号/分号断句。 */
    private fun isSentenceEnd(ch: Char): Boolean = ch in "。！？；…!?;"
}

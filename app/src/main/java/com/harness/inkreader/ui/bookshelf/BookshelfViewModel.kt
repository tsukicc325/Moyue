package com.harness.inkreader.ui.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.harness.inkreader.data.BookEntity
import com.harness.inkreader.data.BookRepository
import com.harness.inkreader.data.ImportProgress
import com.harness.inkreader.data.StorageMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.Charset

data class ShelfItem(
    val book: BookEntity,
    val percent: Float,
)

class BookshelfViewModel(private val repo: BookRepository) : ViewModel() {

    private val query = MutableStateFlow(ShelfQuery())
    val shelfQuery: StateFlow<ShelfQuery> = query.asStateFlow()

    private val _importMode = MutableStateFlow(StorageMode.COPY)
    val importMode: StateFlow<String> = _importMode.asStateFlow()

    private val allItems: StateFlow<List<ShelfItem>> = combine(
        repo.observeBooks(),
        repo.observeProgress(),
    ) { books, progress ->
        val byBook = progress.associateBy { it.bookId }
        books.map { ShelfItem(it, byBook[it.id]?.percent ?: 0f) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<ShelfItem>> = combine(allItems, query) { list, current ->
        ShelfQueryEngine.apply(list, current)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<String>> = allItems
        .map { ShelfQueryEngine.groups(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCount: StateFlow<Int> = allItems
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    data class BatchState(val done: Int, val total: Int, val currentName: String)

    // ------------------------------------------------------------------ 界面查询

    fun setKeyword(text: String) {
        query.value = query.value.copy(keyword = text)
    }

    fun setSort(sort: ShelfSort) {
        query.value = query.value.copy(sort = sort)
    }

    fun filterByGroup(group: String?) {
        query.value = query.value.copy(group = group)
    }

    fun toggleImportMode() {
        _importMode.value = if (_importMode.value == StorageMode.COPY) {
            StorageMode.REFERENCE
        } else {
            StorageMode.COPY
        }
        _message.value = if (_importMode.value == StorageMode.COPY) {
            "导入方式：复制到应用（默认）"
        } else {
            "导入方式：只引用原文件"
        }
    }

    // ------------------------------------------------------------------ 导入

    fun import(uri: Uri) {
        runImport { repo.importFromUri(uri, null, _importMode.value) { report(it) } }
    }

    fun importFile(file: File) {
        runImport { repo.importFromFile(file, null, StorageMode.COPY) { report(it) } }
    }

    fun importFolder(treeUri: Uri) {
        if (_batch.value != null || _importProgress.value != null) return
        viewModelScope.launch {
            _batch.value = BatchState(0, 0, "正在扫描文件夹")
            try {
                val candidates = repo.scanTree(treeUri)
                if (candidates.isEmpty()) {
                    _message.value = "这个文件夹里没有找到 txt 文件"
                    return@launch
                }
                _batch.value = BatchState(0, candidates.size, "准备导入")
                val succeeded = repo.importMany(candidates, _importMode.value) { done, total, name ->
                    _batch.value = BatchState(done, total, name)
                }
                _message.value = "文件夹导入完成：成功 $succeeded / ${candidates.size}"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _message.value = "文件夹导入失败：${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                _batch.value = null
            }
        }
    }

    // ------------------------------------------------------------------ 书籍操作

    fun rename(bookId: Long, title: String) {
        viewModelScope.launch {
            repo.rename(bookId, title)
            _message.value = "已重命名"
        }
    }

    fun assignGroup(bookId: Long, group: String?) {
        viewModelScope.launch {
            repo.setGroup(bookId, group)
            _message.value = if (group.isNullOrBlank()) "已移出分组" else "已归入「$group」"
        }
    }

    /** 设置 / 更换自定义封面。图片会复制进私有目录并缩小，失败给出明确提示。 */
    fun setCover(bookId: Long, uri: Uri) {
        viewModelScope.launch {
            val ok = repo.setCover(bookId, uri)
            _message.value = if (ok) "封面已更新" else "封面设置失败：这张图读不出来"
        }
    }

    fun clearCover(bookId: Long) {
        viewModelScope.launch {
            repo.clearCover(bookId)
            _message.value = "已恢复默认封面"
        }
    }

    /** 手动改编码：改完必须重建索引，否则章节边界是按旧编码算出来的。 */
    fun changeEncoding(bookId: Long, charsetName: String) {
        if (_importProgress.value != null) return
        viewModelScope.launch {
            _importProgress.value = ImportProgress(ImportProgress.Phase.INDEX, 0f, "正在按 $charsetName 重建索引")
            try {
                val charset = runCatching { Charset.forName(charsetName) }.getOrNull()
                if (charset == null) {
                    _message.value = "不认识的编码：$charsetName"
                    return@launch
                }
                val ok = repo.reindex(bookId, charset) { report(it) }
                _message.value = if (ok) "已按 $charsetName 重建索引" else "重建索引失败"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _message.value = "重建索引失败：${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                _importProgress.value = null
            }
        }
    }

    fun delete(bookId: Long) {
        viewModelScope.launch {
            repo.deleteBook(bookId)
            _message.value = "已删除"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun report(text: String) {
        _message.value = text
    }

    private fun report(progress: ImportProgress) {
        _importProgress.value = progress
    }

    private fun runImport(block: suspend () -> Long) {
        if (_importProgress.value != null) return
        _importProgress.value = ImportProgress(ImportProgress.Phase.COPY, 0f, "准备导入")
        viewModelScope.launch {
            try {
                block()
                _message.value = "导入完成"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _message.value = "导入失败：${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                _importProgress.value = null
            }
        }
    }

    class Factory(private val repo: BookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BookshelfViewModel(repo) as T
    }
}

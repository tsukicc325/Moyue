package com.harness.inkreader.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.harness.inkreader.InkApp
import com.harness.inkreader.data.settings.FontOption
import com.harness.inkreader.data.settings.ReaderSettings
import com.harness.inkreader.ui.Fonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(private val app: InkApp) : ViewModel() {

    val settings: StateFlow<ReaderSettings> = app.settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    private val importedFonts = MutableStateFlow(app.fontStore.listImported())

    val fonts: StateFlow<List<FontOption>> = importedFonts
        .map { FontOption.BUILT_IN + it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FontOption.BUILT_IN)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { app.settingsStore.update(transform) }
    }

    fun importFont(uri: Uri) {
        viewModelScope.launch {
            val displayName = queryDisplayName(uri) ?: "字体.ttf"
            val result = withContext(Dispatchers.IO) {
                app.fontStore.import(displayName) {
                    app.contentResolver.openInputStream(uri) ?: error("无法读取字体文件")
                }
            }
            result
                .onSuccess { option ->
                    Fonts.invalidate()
                    importedFonts.value = app.fontStore.listImported()
                    app.settingsStore.update { it.copy(fontId = option.id) }
                    _message.value = "已导入「${option.label}」并启用"
                }
                .onFailure { failure ->
                    _message.value = "字体导入失败：${failure.message ?: failure.javaClass.simpleName}"
                }
        }
    }

    fun deleteFont(option: FontOption) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { app.fontStore.delete(option.id) }
            Fonts.invalidate()
            importedFonts.value = app.fontStore.listImported()
            // 正在用的字体被删掉时必须退回默认，否则阅读页会一直去找已经不存在的文件
            app.settingsStore.update { current ->
                if (current.fontId == option.id) current.copy(fontId = FontOption.SYSTEM_SERIF) else current
            }
            _message.value = if (removed) "已删除「${option.label}」" else "删除失败"
        }
    }

    fun pickBackgroundImage(uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { copyBackgroundImage(uri) }
            if (path == null) {
                _message.value = "背景图设置失败"
                return@launch
            }
            app.settingsStore.update { it.copy(backgroundImagePath = path) }
            _message.value = "背景图已设置"
        }
    }

    fun clearBackgroundImage() {
        viewModelScope.launch {
            val old = settings.value.backgroundImagePath
            app.settingsStore.update { it.copy(backgroundImagePath = null) }
            if (old != null) {
                withContext(Dispatchers.IO) { runCatching { File(old).delete() } }
            }
            _message.value = "背景图已清除"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun copyBackgroundImage(uri: Uri): String? = runCatching {
        val input = app.contentResolver.openInputStream(uri) ?: return@runCatching null
        val directory = File(app.filesDir, WALLPAPER_DIR).apply { mkdirs() }
        val target = File(directory, "bg-${System.currentTimeMillis()}.img")
        input.use { source ->
            target.outputStream().buffered().use { output -> source.copyTo(output) }
        }
        target.absolutePath
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    class Factory(private val app: InkApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(app) as T
    }

    private companion object {
        const val WALLPAPER_DIR = "wallpaper"
    }
}

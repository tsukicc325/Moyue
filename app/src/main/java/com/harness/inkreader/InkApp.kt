package com.harness.inkreader

import android.app.Application
import com.harness.inkreader.data.BookRepository
import com.harness.inkreader.data.settings.DataStoreSettingsStore
import com.harness.inkreader.data.settings.FontStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class InkApp : Application() {

    val repository: BookRepository by lazy { BookRepository(this) }

    val settingsStore: DataStoreSettingsStore by lazy { DataStoreSettingsStore(this) }

    val fontStore: FontStore by lazy { FontStore(File(filesDir, FONTS_DIR)) }

    /**
     * 由外部（文件管理器、`adb shell am start --es inkreader.import_path <路径>`）触发的导入请求。
     * 书架界面观察它并执行导入。有了这条通道，真机验证脚本可以完全脚本化地灌入一本
     * 100MB 小说，不必去点系统的文件选择器。
     */
    val pendingImportPath = MutableStateFlow<String?>(null)

    companion object {
        const val EXTRA_IMPORT_PATH = "inkreader.import_path"
        const val FONTS_DIR = "fonts"
    }
}

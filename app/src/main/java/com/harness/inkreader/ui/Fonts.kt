package com.harness.inkreader.ui

import android.graphics.Typeface
import com.harness.inkreader.data.settings.FontOption
import java.io.File

/**
 * 字体 id → [Typeface]。导入字体走文件加载，失败时退回衬线，绝不让阅读页因为字体坏掉而打不开。
 * 结果按路径缓存，避免每次重新分页都重新解析字体文件。
 */
object Fonts {

    private val cache = HashMap<String, Typeface>()

    fun typefaceOf(option: FontOption): Typeface {
        val path = option.filePath
        if (path != null) {
            cache[path]?.let { return it }
            val loaded = runCatching {
                if (File(path).isFile) Typeface.createFromFile(path) else null
            }.getOrNull()
            val resolved = loaded ?: Typeface.SERIF
            cache[path] = resolved
            return resolved
        }
        return when (option.id) {
            FontOption.SYSTEM_SANS -> Typeface.SANS_SERIF
            FontOption.SYSTEM_MONO -> Typeface.MONOSPACE
            else -> Typeface.SERIF
        }
    }

    /** 字体文件被删掉后要把缓存清掉，否则会一直用着已删除的字体。 */
    fun invalidate() {
        cache.clear()
    }
}

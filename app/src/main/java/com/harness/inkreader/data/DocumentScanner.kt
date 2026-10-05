package com.harness.inkreader.data

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** 待导入的一个文件。 */
data class ImportCandidate(
    val name: String,
    val uri: Uri,
)

object DocumentScanner {

    private val TEXT_EXTENSIONS = setOf("txt", "text")

    fun isTextFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in TEXT_EXTENSIONS
    }

    /**
     * 递归收集目录下的 txt 文件。
     *
     * 用 [DocumentFile] 抽象（而不是直接走 SAF URI），是因为 `DocumentFile.fromFile(dir)`
     * 可以指向普通目录，这样扫描逻辑能在单元测试里用临时目录真实跑一遍。
     */
    fun collectTextFiles(
        root: DocumentFile,
        maxDepth: Int = 6,
        maxFiles: Int = 2_000,
    ): List<ImportCandidate> {
        val result = ArrayList<ImportCandidate>()
        walk(root, 0, maxDepth, maxFiles, result)
        return result
    }

    private fun walk(
        directory: DocumentFile,
        depth: Int,
        maxDepth: Int,
        maxFiles: Int,
        out: MutableList<ImportCandidate>,
    ) {
        if (depth > maxDepth || out.size >= maxFiles) return
        val children = runCatching { directory.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (out.size >= maxFiles) return
            when {
                child.isDirectory -> walk(child, depth + 1, maxDepth, maxFiles, out)
                child.isFile && isTextFile(child.name.orEmpty()) -> out.add(
                    ImportCandidate(
                        name = child.name.orEmpty(),
                        uri = child.uri,
                    )
                )
            }
        }
    }
}

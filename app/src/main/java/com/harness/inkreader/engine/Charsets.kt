package com.harness.inkreader.engine

import java.nio.charset.Charset

object Charsets {

    /**
     * `0x0A`(LF) / `0x0D`(CR) 是否**绝不**出现在多字节字符内部。
     *
     * 满足的情况（可以用字节级扫描精确找换行）：
     * - UTF-8：续字节是 0x80–0xBF
     * - GBK / GB18030：尾字节 0x40–0xFE 且不含 0x7F，4 字节形式的第 2/4 字节是 0x30–0x39
     * - Big5：尾字节 0x40–0x7E 或 0xA1–0xFE
     * - EUC-*：尾字节 0xA1–0xFE
     *
     * 不满足：UTF-16 / UTF-32（例如 U+4E0A 在 UTF-16LE 下是 `0A 4E`，第 1 个字节就是 0x0A）。
     * 因此这两类编码既不能按字节找换行，也不能按字节切块。
     * M4 的导入流程会在复制时把 UTF-16/32 转码成 UTF-8，从根上规避这个限制。
     */
    fun supportsByteLevelNewlineScan(charset: Charset): Boolean {
        val name = charset.name().uppercase()
        return !name.startsWith("UTF-16") &&
            !name.startsWith("UTF-32") &&
            !name.startsWith("X-UTF-16") &&
            !name.startsWith("X-UTF-32")
    }
}

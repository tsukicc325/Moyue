package com.harness.inkreader.engine

/**
 * 章节识别规则。
 *
 * [numericGroup] 存在时表示这条规则能抽出章节序号（[ChineseNumerals] 会用它解析中文数字）。
 * [requireCleanEnding] 为 true 表示该标题行不能以句末标点结尾（用来挡掉正文误报）。
 * 只对**不带分隔符**的紧凑写法开启；带分隔符的写法（`第5章 凶手是谁？`）本身就够具体，
 * 再要求结尾干净反而会丢掉真实章节。
 */
data class ChapterRule(
    val id: String,
    val displayName: String,
    val regex: Regex,
    val numericGroup: Int = 1,
    val requireCleanEnding: Boolean = false,
) {
    fun matches(line: CharSequence): Boolean = regex.containsMatchIn(line)

    /** 抽出章节序号；纯非数字型标题（楔子、番外、Chapter One 之前已单独处理）返回 null。 */
    fun extractNumber(line: CharSequence): Int? {
        val match = regex.find(line) ?: return null
        if (numericGroup > match.groupValues.lastIndex) return null
        val raw = match.groupValues[numericGroup].trim()
        if (raw.isEmpty()) return null
        return ChineseNumerals.parse(raw)
    }
}

object ChapterRules {

    private const val NUM = "0-9零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟"
    private const val UNIT = "章节節回卷篇集部幕折話话"

    /** 带分隔符的标准写法：第X章 标题 / 第X章：标题 / 第X章、标题 */
    val NUMBERED_WITH_DELIMITER = ChapterRule(
        id = "cn_numbered_delim",
        displayName = "第X章（含分隔符）",
        regex = Regex(
            """^\s*第\s*([$NUM]{1,12})\s*[$UNIT](?=[\s:：、.．·—\-（(【\[　]|$)"""
        ),
    )

    /** 无分隔符的紧凑写法：第一章夜航船（只允许很短的整行，避免把正文句首误判成标题） */
    val NUMBERED_TIGHT = ChapterRule(
        id = "cn_numbered_tight",
        displayName = "第X章（紧凑写法）",
        regex = Regex("""^\s*第\s*([$NUM]{1,12})\s*[$UNIT].{0,14}$"""),
        requireCleanEnding = true,
    )

    /** 卷首型：卷三 / 第三卷 */
    val VOLUME = ChapterRule(
        id = "cn_volume",
        displayName = "卷X",
        regex = Regex("""^\s*卷\s*([$NUM]{1,8})(?=[\s:：、.．·—\-]|$)"""),
    )

    /** 固定名目：楔子、序章、番外、后记……（无序号） */
    val SPECIAL = ChapterRule(
        id = "cn_special",
        displayName = "楔子/序章/番外/后记等",
        regex = Regex(
            """^\s*(?:楔子|序章|序幕|序言|序|前言|引言|引子|后记|後記|尾声|尾聲|终章|終章|大结局|大結局|番外|外传|外傳|附录|附錄|终篇|終篇|正文)(?=[\s:：、.．·—\-（(【\[]|$)"""
        ),
        numericGroup = 0,
    )

    /** 英文章节：Chapter 12 / CHAPTER IV */
    val ENGLISH = ChapterRule(
        id = "en_chapter",
        displayName = "Chapter N",
        regex = Regex("""(?i)^\s*chapter[\s\-_.]*([0-9]{1,6}|[ivxlc]{1,8})(?![a-z0-9])"""),
        numericGroup = 1,
    )

    /** 括号包裹： 【第一章】 / （第 3 章） */
    val BRACKETED = ChapterRule(
        id = "cn_bracketed",
        displayName = "【第X章】",
        regex = Regex(
            """^\s*[【\[（(]\s*第\s*([$NUM]{1,12})\s*[$UNIT]\s*[】\]）)]"""
        ),
    )

    val DEFAULT: List<ChapterRule> = listOf(
        NUMBERED_WITH_DELIMITER,
        NUMBERED_TIGHT,
        BRACKETED,
        VOLUME,
        SPECIAL,
        ENGLISH,
    )

    /**
     * 标题行最多多少字符。放宽到 60 是因为有小说会写很长的章节名
     * （如「第1234章 她终于说出了那句话，所有人都愣住了」），
     * 卡得太紧会把真实章节当正文丢掉 —— **丢章节的代价远大于多一条误报**。
     */
    const val MAX_TITLE_CHARS = 60

    /** 标题行最多多少字节。先用字节长度过滤，避免为超长段落白白解码。 */
    const val MAX_TITLE_BYTES = 240

    /**
     * 结尾是否「不像标题」。只对不带分隔符的紧凑写法启用，
     * 见 [ChapterRule.requireCleanEnding]。
     */
    fun hasProseEnding(line: CharSequence): Boolean {
        if (line.isEmpty()) return false
        return line[line.length - 1] in "。！？；，、…"
    }
}

/** 中文数字 → 阿拉伯数字。支持「十」「二十三」「一百零五」「一千零二十」等写法。 */
internal object ChineseNumerals {

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5,
        '陆' to 6, '柒' to 7, '捌' to 8, '玖' to 9,
    )

    private val UNITS = mapOf(
        '十' to 10, '拾' to 10,
        '百' to 100, '佰' to 100,
        '千' to 1000, '仟' to 1000,
        '万' to 10_000, '亿' to 100_000_000,
    )

    fun parse(text: String): Int? {
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) return text.toIntOrNull()
        // 罗马数字交给英文规则
        if (text.all { it.lowercaseChar() in "ivxlc" }) return romanToInt(text)

        var total = 0
        var section = 0
        var number = 0
        for (ch in text) {
            val digit = DIGITS[ch]
            if (digit != null) {
                number = digit
                continue
            }
            val unit = UNITS[ch] ?: return null
            if (unit >= 10_000) {
                section = (section + number) * unit
                total += section
                section = 0
                number = 0
            } else {
                if (number == 0) number = 1
                section += number * unit
                number = 0
            }
        }
        val result = total + section + number
        return if (result in 1..99_999) result else null
    }

    private fun romanToInt(text: String): Int? {
        val values = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        val lower = text.lowercase()
        var sum = 0
        for (i in lower.indices) {
            val value = values[lower[i]] ?: return null
            val next = lower.getOrNull(i + 1)?.let { values[it] } ?: 0
            sum += if (value < next) -value else value
        }
        return if (sum in 1..99_999) sum else null
    }
}

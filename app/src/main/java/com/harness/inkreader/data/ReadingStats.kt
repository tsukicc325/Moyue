package com.harness.inkreader.data

import java.time.Instant
import java.time.ZoneId

object Days {
    /** 本地时区当天零点。统计按天聚合时用它当键。 */
    fun startOfDay(millis: Long): Long {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** 最近 [count] 天的零点，从最早到最晚。 */
    fun lastDays(count: Int, now: Long = System.currentTimeMillis()): List<Long> {
        val today = startOfDay(now)
        val dayMillis = 24L * 60 * 60 * 1000
        return (count - 1 downTo 0).map { today - it * dayMillis }
    }
}

data class DailyReading(val dayEpoch: Long, val millis: Long)

data class ReadingStats(
    val totalMillis: Long,
    val millisLast7Days: Long,
    val charsLast7Days: Long,
    val activeDaysLast7Days: Int,
    val sessionsLast7Days: Int,
    val daily: List<DailyReading>,
    val bookCount: Int,
) {
    /** 汉字/分钟。按 7 天窗口算，避免被很久以前的记录拖偏。 */
    val charsPerMinute: Int
        get() = if (millisLast7Days <= 0L) {
            0
        } else {
            (charsLast7Days * 60_000.0 / millisLast7Days).toInt()
        }

    companion object {
        val EMPTY = ReadingStats(0, 0, 0, 0, 0, emptyList(), 0)
    }
}

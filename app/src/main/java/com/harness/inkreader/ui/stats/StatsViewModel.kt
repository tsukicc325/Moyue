package com.harness.inkreader.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.harness.inkreader.data.BookRepository
import com.harness.inkreader.data.ReadingStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StatsViewModel(private val repo: BookRepository) : ViewModel() {

    private val _stats = MutableStateFlow(ReadingStats.EMPTY)
    val stats: StateFlow<ReadingStats> = _stats.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _stats.value = repo.stats(DAYS) }
    }

    class Factory(private val repo: BookRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StatsViewModel(repo) as T
    }

    private companion object {
        const val DAYS = 7
    }
}

/** 把毫秒说成人话：「1 小时 23 分」「12 分钟」「不足 1 分钟」。 */
fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "0 分钟"
    val totalMinutes = millis / 60_000L
    if (totalMinutes <= 0L) return "不足 1 分钟"
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours <= 0L -> "$minutes 分钟"
        minutes == 0L -> "$hours 小时"
        else -> "$hours 小时 $minutes 分"
    }
}

fun formatDayLabel(dayEpoch: Long): String {
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = dayEpoch
    return "${calendar.get(java.util.Calendar.MONTH) + 1}/${calendar.get(java.util.Calendar.DAY_OF_MONTH)}"
}

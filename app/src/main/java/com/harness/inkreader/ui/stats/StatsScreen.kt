package com.harness.inkreader.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harness.inkreader.InkApp
import com.harness.inkreader.data.DailyReading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as InkApp
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory(app.repository))
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "最近 7 天",
                    value = formatDuration(stats.millisLast7Days),
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "累计阅读",
                    value = formatDuration(stats.totalMillis),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "阅读速度",
                    value = "${stats.charsPerMinute} 字/分",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "7 天读书天数",
                    value = "${stats.activeDaysLast7Days} 天",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "每天读多久",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                DailyChart(
                    daily = stats.daily,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "从记录开始到现在共读了 ${formatDuration(stats.totalMillis)}，" +
                    "最近 7 天读了 ${stats.charsLast7Days} 字，书架上有 ${stats.bookCount} 本书。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
            Text(
                text = "字数只统计「在同一章里连续向前翻页」读过的内容，跳章与拖进度条不计入，" +
                    "所以速度不会被跳读拉高。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 7 天柱状图。用 Compose 的 Box 高度做柱子，而不是 Canvas 画字 ——
 * 标签排版交给框架，不用自己算基线。
 */
@Composable
private fun DailyChart(daily: List<DailyReading>, modifier: Modifier = Modifier) {
    if (daily.isEmpty()) {
        Text(text = "还没有阅读记录", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val maxMillis = daily.maxOf { it.millis }.coerceAtLeast(1L)
    val chartHeight = 120.dp
    val barColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier.height(chartHeight + 46.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        daily.forEach { day ->
            val ratio = day.millis.toDouble() / maxMillis.toDouble()
            val barHeight = (chartHeight.value * ratio).dp
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (day.millis > 0) "${day.millis / 60_000}" else "",
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(if (barHeight.value < 3f) 3.dp else barHeight)
                        .background(
                            color = if (day.millis > 0) barColor else emptyColor,
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                        ),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = formatDayLabel(day.dayEpoch),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

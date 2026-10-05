package com.harness.inkreader.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import com.harness.inkreader.data.settings.PageTurnMode
import com.harness.inkreader.engine.PagedText

/**
 * 翻页动画的几何参数。
 *
 * 只有两件事要决定：**新页怎么动**、**旧页快照怎么动**，以及谁盖在谁上面。
 *
 * | 模式 | 新页（实时绘制） | 旧页（快照） | 叠放 |
 * |---|---|---|---|
 * | 滑动·向后 | 原位 | 向左滑出 | 快照在上 |
 * | 滑动·向前 | 从左侧滑入 | 向右滑出 | 快照在上 |
 * | 覆盖·向后 | 原位 | 向右滑出 | 快照在上 |
 * | 覆盖·向前 | 从右侧滑入盖住旧页 | 原位 | 新页在上 |
 */
data class TurnGeometry(
    val liveOffsetFactor: (Float) -> Float,
    val snapshotOffsetFactor: (Float) -> Float,
    val snapshotOnTop: Boolean,
)

object PageTurns {

    fun geometry(mode: PageTurnMode, forward: Boolean): TurnGeometry? = when (mode) {
        PageTurnMode.NONE -> null

        PageTurnMode.SLIDE -> if (forward) {
            TurnGeometry(
                liveOffsetFactor = { 0f },
                snapshotOffsetFactor = { progress -> -progress },
                snapshotOnTop = true,
            )
        } else {
            TurnGeometry(
                liveOffsetFactor = { progress -> progress - 1f },
                snapshotOffsetFactor = { progress -> progress },
                snapshotOnTop = true,
            )
        }

        PageTurnMode.COVER -> if (forward) {
            TurnGeometry(
                liveOffsetFactor = { progress -> 1f - progress },
                snapshotOffsetFactor = { 0f },
                snapshotOnTop = false,
            )
        } else {
            TurnGeometry(
                liveOffsetFactor = { 0f },
                snapshotOffsetFactor = { progress -> progress },
                snapshotOnTop = true,
            )
        }
    }

    /**
     * 把当前页画进一张位图，作为动画里的「旧页」。
     *
     * 只是把同一个 StaticLayout 平移后画一遍 —— 和屏幕上的画法完全一致，所以动画不会有排版跳动。
     */
    fun capturePage(
        paged: PagedText,
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
        backgroundArgb: Int,
    ): Bitmap? {
        val page = paged.pageAt(pageIndex) ?: return null
        if (widthPx <= 0 || heightPx <= 0) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(bitmap)
            canvas.drawColor(backgroundArgb)
            val checkpoint = canvas.save()
            canvas.translate(0f, -page.topPx.toFloat())
            canvas.clipRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
            paged.layout.draw(canvas)
            canvas.restoreToCount(checkpoint)
            bitmap
        }.getOrNull()
    }
}

package net.darkrelay.microdeck

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Rule-of-thirds grid plus a centre crosshair, drawn over the preview. */
class GridOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = 2f
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC4FC3F7.toInt()
        strokeWidth = 3f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..2) {
            canvas.drawLine(w * i / 3, 0f, w * i / 3, h, line)
            canvas.drawLine(0f, h * i / 3, w, h * i / 3, line)
        }
        val cx = w / 2
        val cy = h / 2
        val arm = minOf(w, h) / 20
        canvas.drawLine(cx - arm, cy, cx + arm, cy, cross)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, cross)
    }
}

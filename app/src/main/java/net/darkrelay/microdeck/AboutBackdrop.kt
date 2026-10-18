package net.darkrelay.microdeck

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * The DarkRelay banner behind the About screen, drawn the way Lazarus PDF's About does it: the
 * banner's full height, cropped to the view's shape with the relay node at about 78% across, then
 * darkened left to right (more on the left, where the text is) so the text stays easy to read.
 */
class AboutBackdrop @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val banner: Bitmap? = BitmapFactory.decodeResource(resources, R.drawable.darkrelay_banner,
        BitmapFactory.Options().apply { inScaled = false })
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint()
    private val src = Rect()
    private val dst = Rect()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val b = banner
        if (b != null && w > 0 && h > 0) {
            val sw = b.width
            val sh = b.height
            val cw = minOf(sw, (sh.toLong() * w / h).toInt())
            val x0 = (sw / 2 - cw * NODE_AT).toInt().coerceIn(0, sw - cw)
            src.set(x0, 0, x0 + cw, sh)
        }
        dst.set(0, 0, w, h)
        shadePaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Color.argb(235, 5, 7, 10), Color.argb(205, 5, 7, 10), Color.argb(120, 5, 7, 10)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BASE)
        banner?.let { canvas.drawBitmap(it, src, dst, imagePaint) }
        canvas.drawRect(dst, shadePaint)
    }

    private companion object {
        const val NODE_AT = 0.78f
        val BASE = Color.rgb(0x0a, 0x0e, 0x14)
    }
}

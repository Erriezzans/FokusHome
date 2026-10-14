package app.olauncher.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.ceil
import kotlin.math.min

/** Grey checkerboard with rounded corners: the "no color" tile. */
class CheckerDrawable(private val cell: Float, private val radius: Float) : Drawable() {
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt() }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCFCFCF.toInt() }

    override fun draw(canvas: Canvas) {
        val area = RectF(bounds)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(area, radius, radius, Path.Direction.CW) })
        canvas.drawRect(area, light)
        val columns = ceil(area.width() / cell).toInt()
        val rows = ceil(area.height() / cell).toInt()
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if ((row + column) % 2 == 1) {
                    val x = area.left + column * cell
                    val y = area.top + row * cell
                    canvas.drawRect(x, y, min(x + cell, area.right), min(y + cell, area.bottom), dark)
                }
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

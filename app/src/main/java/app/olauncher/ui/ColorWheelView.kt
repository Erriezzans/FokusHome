package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Color picker: a hue ring around a tilted square (diamond) for saturation and brightness.
 * Drag on the ring to pick the hue, drag inside to pick how light / strong the color is.
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = floatArrayOf(200f, 0.8f, 0.6f)

    var color: Int
        get() = Color.HSVToColor(hsv)
        set(value) {
            Color.colorToHSV(value, hsv)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }
    private val handleDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x99000000.toInt()
    }

    private var cx = 0f
    private var cy = 0f
    private var outerRadius = 0f
    private var ringWidth = 0f
    private var ringRadius = 0f
    private var innerRadius = 0f
    private var half = 0f
    private var dragging = DRAG_NONE

    private val cos45 = cos(Math.PI / 4).toFloat()
    private val sin45 = sin(Math.PI / 4).toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resolveSize((280 * density).toInt(), widthMeasureSpec)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        ringWidth = w * 0.11f
        outerRadius = w / 2f - 8 * density
        ringRadius = outerRadius - ringWidth / 2f
        innerRadius = outerRadius - ringWidth
        half = innerRadius * 0.66f
        ringPaint.strokeWidth = ringWidth
        // Hue grows counter-clockwise from the right (red), like most color wheels
        val colors = IntArray(13) { i ->
            val clockwise = i * 30f
            Color.HSVToColor(floatArrayOf((360f - clockwise) % 360f, 1f, 1f))
        }
        ringPaint.shader = SweepGradient(cx, cy, colors, null)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)

        // Saturation (left to right) and brightness (top to bottom) square, drawn tilted by 45 degrees
        val pureHue = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val left = cx - half
        val top = cy - half
        val right = cx + half
        val bottom = cy + half
        squarePaint.shader = LinearGradient(left, 0f, right, 0f, Color.WHITE, pureHue, Shader.TileMode.CLAMP)
        shadePaint.shader = LinearGradient(0f, top, 0f, bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.rotate(45f, cx, cy)
        canvas.drawRect(left, top, right, bottom, squarePaint)
        canvas.drawRect(left, top, right, bottom, shadePaint)
        canvas.restore()

        // Handle on the ring
        val angle = Math.toRadians(hsv[0].toDouble())
        drawHandle(
            canvas,
            cx + ringRadius * cos(angle).toFloat(),
            cy - ringRadius * sin(angle).toFloat(),
            pureHue
        )

        // Handle in the square
        val localX = left + hsv[1] * 2f * half
        val localY = top + (1f - hsv[2]) * 2f * half
        val dx = localX - cx
        val dy = localY - cy
        drawHandle(
            canvas,
            cx + dx * cos45 - dy * sin45,
            cy + dx * sin45 + dy * cos45,
            color
        )
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, fill: Int) {
        val radius = 11f * density
        handleFill.color = fill
        canvas.drawCircle(x, y, radius, handleFill)
        canvas.drawCircle(x, y, radius, handleWhite)
        canvas.drawCircle(x, y, radius + 1.5f * density, handleDark)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val distance = hypot(event.x - cx, event.y - cy)
                dragging = when {
                    distance < innerRadius - 2 * density -> DRAG_SQUARE
                    distance <= outerRadius + 14 * density -> DRAG_RING
                    else -> DRAG_NONE
                }
                if (dragging == DRAG_NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                update(event.x, event.y)
            }

            MotionEvent.ACTION_MOVE -> if (dragging != DRAG_NONE) update(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = DRAG_NONE
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        if (dragging == DRAG_RING) {
            var degrees = Math.toDegrees(atan2((cy - y).toDouble(), (x - cx).toDouble())).toFloat()
            if (degrees < 0f) degrees += 360f
            hsv[0] = degrees
        } else {
            // Undo the 45 degree tilt to find the spot inside the square
            val dx = x - cx
            val dy = y - cy
            val localX = dx * cos45 + dy * sin45
            val localY = -dx * sin45 + dy * cos45
            hsv[1] = ((localX + half) / (2f * half)).coerceIn(0f, 1f)
            hsv[2] = (1f - (localY + half) / (2f * half)).coerceIn(0f, 1f)
        }
        invalidate()
        onColorChanged?.invoke(color)
    }

    private companion object {
        const val DRAG_NONE = 0
        const val DRAG_RING = 1
        const val DRAG_SQUARE = 2
    }
}

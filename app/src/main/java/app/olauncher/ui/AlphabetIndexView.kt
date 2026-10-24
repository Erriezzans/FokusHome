package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.olauncher.R
import kotlin.math.ceil
import kotlin.math.min

/**
 * A-Z strip. Touching / dragging over it reports the letter under the finger.
 * Only the letters that actually have apps are shown. It sits on a translucent pill in the
 * "inverse" theme colour so the letters stay readable on any wallpaper.
 */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Called with the letter and the centre of its slot along the strip (vertical, or horizontal when at the bottom)
    var onLetterSelected: ((Char, Float) -> Unit)? = null
    var onTouchEnd: (() -> Unit)? = null

    // Bottom strip: letters run left to right instead of top to bottom
    var horizontal = false
        set(value) {
            field = value
            invalidate()
        }

    // Opacity of the round background pill, in percent
    var opacityPercent = 75
        set(value) {
            field = value.coerceIn(0, 100)
            pillPaint.alpha = field * 255 / 100
            invalidate()
        }

    private var letters: List<Char> = emptyList()
    private var activeIndex = -1

    private val density = resources.displayMetrics.density
    private val minSlotPx = 16f * density
    private val inset = 8f * density
    private val pillRect = RectF()

    private val textColor = resolveThemeColor(R.attr.primaryColor)
    private val backColor = resolveThemeColor(R.attr.primaryInverseColor)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        typeface = Typeface.DEFAULT_BOLD
        color = textColor
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backColor
        alpha = 75 * 255 / 100
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 130
    }

    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.data else 0xFFFFFFFF.toInt()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Ask the system not to use swipes on the strip for back / navigation where it is allowed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
    }

    fun setLetters(newLetters: List<Char>) {
        if (newLetters == letters) return
        letters = newLetters
        visibility = if (letters.size < 2) INVISIBLE else VISIBLE
        invalidate()
    }

    // Length of the part of the strip that holds letters
    private fun innerLength() = ((if (horizontal) width else height) - 2 * inset).coerceAtLeast(1f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return

        val radius = min(width, height) / 2f
        pillRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(pillRect, radius, radius, pillPaint)

        val slot = innerLength() / letters.size
        // If there are too many letters to fit, draw only every n-th one (touch still maps to all).
        val step = if (slot >= minSlotPx) 1 else ceil(minSlotPx / slot).toInt()
        val metrics = textPaint.fontMetrics
        val textOffset = -(metrics.ascent + metrics.descent) / 2f
        val cross = (if (horizontal) height else width) / 2f
        val highlight = min(cross - 2 * density, 13 * density)

        letters.forEachIndexed { i, letter ->
            val along = inset + slot * i + slot / 2f
            val cx = if (horizontal) along else width / 2f
            val cy = if (horizontal) height / 2f else along
            if (i == activeIndex) {
                // Selected letter: filled circle in the text colour, letter in the background colour
                canvas.drawCircle(cx, cy, highlight, activePaint)
                textPaint.color = backColor
                canvas.drawText(letter.toString(), cx, cy + textOffset, textPaint)
                textPaint.color = textColor
            } else if (i % step == 0) {
                canvas.drawText(letter.toString(), cx, cy + textOffset, textPaint)
            } else {
                // Letter skipped for lack of room: a small dot keeps the rhythm
                canvas.drawCircle(cx, cy, 1.5f * density, dotPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val slot = innerLength() / letters.size
                val position = (if (horizontal) event.x else event.y) - inset
                val index = (position / slot).toInt().coerceIn(0, letters.size - 1)
                if (index != activeIndex) {
                    activeIndex = index
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onLetterSelected?.invoke(letters[index], inset + slot * index + slot / 2f)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeIndex = -1
                onTouchEnd?.invoke()
                invalidate()
            }
        }
        return true
    }
}

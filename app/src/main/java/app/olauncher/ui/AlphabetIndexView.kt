package app.olauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.olauncher.R
import kotlin.math.ceil

/**
 * Thin vertical A-Z strip. Touching / dragging over it reports the letter under the finger.
 * Only the letters that actually have apps are shown.
 */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Called with the letter and the vertical centre of its slot (in this view's coordinates)
    var onLetterSelected: ((Char, Float) -> Unit)? = null
    var onTouchEnd: (() -> Unit)? = null

    private var letters: List<Char> = emptyList()
    private var activeIndex = -1

    private val minSlotPx = 16f * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
        )
        color = resolveThemeColor(R.attr.primaryColor)
    }

    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.data else 0xFFFFFFFF.toInt()
    }

    fun setLetters(newLetters: List<Char>) {
        if (newLetters == letters) return
        letters = newLetters
        visibility = if (letters.size < 2) INVISIBLE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty()) return
        val slot = height.toFloat() / letters.size
        // If there are too many letters to fit, draw only every n-th one (touch still maps to all).
        val step = if (slot >= minSlotPx) 1 else ceil(minSlotPx / slot).toInt()
        val cx = width / 2f
        val metrics = paint.fontMetrics
        letters.forEachIndexed { i, letter ->
            val isActive = i == activeIndex
            if (!isActive && i % step != 0) return@forEachIndexed
            paint.typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.alpha = if (isActive) 255 else 150
            val cy = slot * i + slot / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(letter.toString(), cx, cy, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val index = (event.y / height * letters.size).toInt().coerceIn(0, letters.size - 1)
                if (index != activeIndex) {
                    activeIndex = index
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    val slot = height.toFloat() / letters.size
                    onLetterSelected?.invoke(letters[index], slot * index + slot / 2f)
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

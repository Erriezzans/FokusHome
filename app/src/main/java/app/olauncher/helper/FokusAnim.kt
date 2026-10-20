package app.olauncher.helper

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import app.olauncher.R

/** Animation helpers: screen transitions, touch feedback and the focus space switch. */
object FokusAnim {
    const val TRANSITION_DEFAULT = 0
    const val TRANSITION_FADE = 1
    const val TRANSITION_SLIDE = 2
    const val TRANSITION_ZOOM = 3
    const val TRANSITION_OFF = 4

    const val TOUCH_OFF = 0
    const val TOUCH_PRESS = 1
    const val TOUCH_BOUNCE = 2

    const val FOCUS_OFF = 0
    const val FOCUS_FADE = 1
    const val FOCUS_SLIDE = 2
    const val FOCUS_ZOOM = 3

    /**
     * NavOptions for the chosen screen transition. Returns null for "Default" so the animations
     * written in nav_graph.xml stay in charge.
     */
    fun navOptions(mode: Int, popUpTo: Int? = null, inclusive: Boolean = false): NavOptions? {
        if (mode == TRANSITION_DEFAULT) return null
        val builder = NavOptions.Builder()
        when (mode) {
            TRANSITION_FADE -> builder
                .setEnterAnim(R.anim.fade_enter).setExitAnim(R.anim.fade_exit)
                .setPopEnterAnim(R.anim.fade_enter).setPopExitAnim(R.anim.fade_exit)

            TRANSITION_SLIDE -> builder
                .setEnterAnim(R.anim.slide_up_enter).setExitAnim(R.anim.fade_exit)
                .setPopEnterAnim(R.anim.fade_enter).setPopExitAnim(R.anim.slide_down_exit)

            TRANSITION_ZOOM -> builder
                .setEnterAnim(R.anim.zoom_enter).setExitAnim(R.anim.fade_exit)
                .setPopEnterAnim(R.anim.fade_enter).setPopExitAnim(R.anim.zoom_exit)

            else -> builder.setEnterAnim(0).setExitAnim(0).setPopEnterAnim(0).setPopExitAnim(0)
        }
        if (popUpTo != null) builder.setPopUpTo(popUpTo, inclusive)
        return builder.build()
    }

    /** Press feedback for one view. Never consumes the touch, so click and long press keep working. */
    @SuppressLint("ClickableViewAccessibility")
    fun applyTouch(view: View, mode: Int) {
        if (mode == TOUCH_OFF) {
            view.setOnTouchListener(null)
            return
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90)
                        .setInterpolator(DecelerateInterpolator()).start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val release = v.animate().scaleX(1f).scaleY(1f)
                    if (mode == TOUCH_BOUNCE)
                        release.setDuration(280).setInterpolator(OvershootInterpolator(3.5f)).start()
                    else
                        release.setDuration(120).setInterpolator(DecelerateInterpolator()).start()
                }
            }
            false
        }
    }
}

/** Navigate with the user's screen transition setting. */
fun NavController.navigateAnimated(
    resId: Int,
    mode: Int,
    args: Bundle? = null,
    popUpTo: Int? = null,
    inclusive: Boolean = false,
) = navigate(resId, args, FokusAnim.navOptions(mode, popUpTo, inclusive))

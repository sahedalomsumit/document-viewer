package com.sahed.document_viewer

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** View equivalents of Money Tracker's gentleEntrance and bouncyPressEffect. */
object DesignSystem {
    private fun animationsEnabled(view: View): Boolean =
        if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
        else Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    fun entrance(view: View, index: Int = 0) {
        view.animate().cancel()
        if (!animationsEnabled(view)) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = 24f
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(index.coerceIn(0, 6) * 35L).setDuration(380)
            .setInterpolator(PathInterpolatorCompat.create(0.4f, 0f, 0.2f, 1f)).start()
    }

    @Suppress("ClickableViewAccessibility") // Only observes touch; normal click/accessibility handling is retained.
    fun bouncyPress(view: View) {
        val x = SpringAnimation(view, SpringAnimation.SCALE_X)
        val y = SpringAnimation(view, SpringAnimation.SCALE_Y)
        listOf(x, y).forEach {
            it.spring = SpringForce(1f).setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
        }
        view.setOnTouchListener { _, event ->
            if (view.isEnabled && animationsEnabled(view)) {
                val target = if (event.actionMasked == MotionEvent.ACTION_DOWN) 0.97f else 1f
                if (event.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                    x.animateToFinalPosition(target)
                    y.animateToFinalPosition(target)
                }
            }
            false
        }
    }
}

package com.tobirg.rootmate

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View

/**
 * D-pad focus highlight for TV: a thick white outline drawn above the view
 * (drawn inside the view bounds, so no parent can clip it). The outline is a foreground, so it is visible on
 * filled, outlined and text buttons alike. Dimmed ("gated") views are brought
 * back to full opacity while focused, so the focused one is always readable.
 */
object TvFocus {

    private const val DIMMED_ALPHA = 0.6f

    fun apply(view: View, cornerDp: Float = 12f, dimmed: () -> Boolean = { false }) {
        val density = view.resources.displayMetrics.density
        val ring = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = cornerDp * density
            setStroke((3 * density).toInt(), Color.WHITE)
        }
        view.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring)
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
        view.setOnFocusChangeListener { v, focused ->
            v.alpha = if (!focused && dimmed()) DIMMED_ALPHA else 1f
        }
    }

    /** Re-applies the resting alpha, e.g. after the permission state changed. */
    fun refreshAlpha(view: View, dimmed: Boolean) {
        view.alpha = if (dimmed && !view.isFocused) DIMMED_ALPHA else 1f
    }
}

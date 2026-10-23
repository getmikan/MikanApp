package com.github.kr328.clash.design.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.kr328.clash.design.ui.Insets

internal fun safeSurfaceInsets(
    bars: androidx.core.graphics.Insets,
    cutout: androidx.core.graphics.Insets,
    rtl: Boolean,
): Insets {
    val left = maxOf(bars.left, cutout.left)
    val right = maxOf(bars.right, cutout.right)
    return Insets(
        if (rtl) right else left,
        maxOf(bars.top, cutout.top),
        if (rtl) left else right,
        maxOf(bars.bottom, cutout.bottom),
    )
}

private fun WindowInsetsCompat.surfaceInsets(view: View): Insets = safeSurfaceInsets(
    getInsets(WindowInsetsCompat.Type.systemBars()),
    getInsets(WindowInsetsCompat.Type.displayCutout()),
    view.layoutDirection == View.LAYOUT_DIRECTION_RTL,
)

/** Snapshot avoids an initial zero-inset layout when replacing the content design. */
fun View.currentWindowInsetsSnapshot(adaptLandscape: Boolean = true): Insets? {
    val insets = ViewCompat.getRootWindowInsets(this)?.surfaceInsets(this) ?: return null
    return if (adaptLandscape) insets.landscape(context) else insets
}

fun View.setOnInsertsChangedListener(adaptLandscape: Boolean = true, listener: (Insets) -> Unit) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, compat ->
        val insets = compat.surfaceInsets(view)
        listener(if (adaptLandscape) insets.landscape(view.context) else insets)
        compat
    }
    ViewCompat.requestApplyInsets(this)
}

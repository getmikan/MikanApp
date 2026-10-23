package com.github.kr328.clash.common.compat

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat

fun Window.enableEdgeToEdgeCompat() {
    // Target 35 windows are already edge-to-edge on Android 15 and newer.
    if (Build.VERSION.SDK_INT < 35) {
        WindowCompat.setDecorFitsSystemWindows(this, false)
    }
    if (Build.VERSION.SDK_INT >= 28) {
        attributes = attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}

var Window.isAllowForceDarkCompat: Boolean
    get() = Build.VERSION.SDK_INT >= 29 && decorView.isForceDarkAllowed
    set(value) {
        if (Build.VERSION.SDK_INT >= 29) {
            decorView.isForceDarkAllowed = value
        }
    }

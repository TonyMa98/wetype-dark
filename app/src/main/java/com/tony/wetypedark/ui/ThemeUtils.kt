package com.tony.wetypedark.ui

import android.content.Context

/** 窗口主题是不是亮色（跟随 AppTheme，而不是系统 night mode） */
internal fun resolveIsLightTheme(context: Context): Boolean {
    val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
    return try {
        ta.getBoolean(0, true)
    } finally {
        ta.recycle()
    }
}

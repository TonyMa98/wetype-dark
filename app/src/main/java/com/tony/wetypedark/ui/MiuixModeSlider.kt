package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context

/**
 * 每应用模式选择器：亮色圆角 / 亮色直角 / 深色 / 极暗。
 *
 * 实现全部在 [SegmentedControl] 里，这里只固定三档文案，
 * 保留原有类名以免调用方（`SettingsActivity`）改动。
 */
class MiuixModeSlider(context: Context) : SegmentedControl(context) {

    init {
        options = listOf("亮色圆角", "亮色直角", "深色", "极暗")
    }
}

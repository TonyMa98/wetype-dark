package com.tony.wetypedark.ui

import android.content.Context

/** 配置键名（与早期模块保持一致，方便以后接回 :hld 进程） */
object ConfigStore {

    const val GROUP = LocalConfig.GROUP

    const val KEY_APP_MODES = LocalConfig.KEY_APP_MODES
    const val KEY_FROSTED_ENABLED = LocalConfig.KEY_FROSTED_ENABLED
    const val KEY_BLOCK_WETYPE_UPDATE = LocalConfig.KEY_BLOCK_WETYPE_UPDATE
    const val KEY_PLATFORM = LocalConfig.KEY_PLATFORM

    const val KEY_GESTURE_SETTINGS = "gesture_settings"
    const val KEY_KEY_TEXT_TUNING = "key_text_tuning"
    const val KEY_APPEARANCE = "appearance"
    const val KEY_APPEARANCE_TS = "appearance_ts"

    private var app: Context? = null

    fun attach(context: Context) {
        app = context.applicationContext
    }

    /** 下滑功能整份 JSON（GestureLabels 等页面读取用） */
    fun gestureSettingsJson(): String? {
        val c = app ?: return null
        return LocalConfig.prefs(c).getString(KEY_GESTURE_SETTINGS, null)
    }
}

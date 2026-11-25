package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** 本地配置副本：字段名与早期模块保持一致，方便后续接回 :hld 进程 */
object LocalConfig {

    const val GROUP = "fk_wetype"
    const val KEY_APP_MODES = "app_modes"
    const val KEY_FROSTED_ENABLED = "frosted_enabled"
    const val KEY_BLOCK_WETYPE_UPDATE = "block_wetype_update"
    const val KEY_PLATFORM = "platform"
    const val KEY_LIVE_COLORS_PC = "live_colors_pc"

    const val PLATFORM_COLOROS = 0
    const val PLATFORM_HYPEROS = 1

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(GROUP, Context.MODE_PRIVATE)

    fun modes(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_APP_MODES, "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    fun writeMode(context: Context, pkg: String, mode: Int?) {
        val obj = modes(context)
        if (mode == null) obj.remove(pkg) else obj.put(pkg, mode)
        prefs(context).edit().putString(KEY_APP_MODES, obj.toString()).apply()
    }
}

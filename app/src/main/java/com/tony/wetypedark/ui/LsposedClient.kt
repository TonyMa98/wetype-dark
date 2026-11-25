package com.tony.wetypedark.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * 原工程里这是 LSPosed service 的 binder 桥；复刻版把它换成同一份本地配置，
 * 调用点（三级页面）全部保持原样：get / whenBound / remote。
 */
object LsposedClient {

    class Svc(private val prefs: SharedPreferences) {
        fun getRemotePreferences(group: String): SharedPreferences = prefs
    }

    private var cached: Svc? = null

    fun get(context: Context): Svc {
        ConfigStore.attach(context)
        cached?.let { return it }
        return Svc(LocalConfig.prefs(context)).also { cached = it }
    }

    fun whenBound(block: (Svc) -> Unit) {
        cached?.let(block)
    }

    fun remote(context: Context, group: String): SharedPreferences {
        get(context)
        return LocalConfig.prefs(context)
    }
}

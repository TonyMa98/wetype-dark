package com.tony.wetypedark.ui

import android.content.Context

/**
 * 把 App 侧配置推给 `:hld` 进程。
 *
 * 注入进程读不到模块 App 的 SharedPreferences，所以这里把「下滑功能 / 按键文字位置 /
 * 系统平台 / 屏蔽自动更新 / 磨砂开关」拼成一份 JSON，用 root 塞进微信输入法自己的目录，
 * 宿主侧的 `HostConfig` 按 mtime 重读（最多 300ms 一次）。
 *
 * 与 [AppearanceLiveSync] 同一套路：先写 /sdcard 直连路径，失败再退到 media 路径。
 */
internal object HostConfigSync {

    private const val MEDIA_PATH =
        "/data/media/0/Android/data/com.tencent.wetype/files/wetype-dark-host.json"
    private const val SD_PATH =
        "/sdcard/Android/data/com.tencent.wetype/files/wetype-dark-host.json"

    private const val WETYPE_UID = 10428

    /** 读当前配置拼 JSON 并落盘；无 root 时静默失败（走宿主默认值） */
    fun sync(context: Context) {
        val prefs = LocalConfig.prefs(context)
        val root = org.json.JSONObject()
        putRaw(root, "gesture_settings", prefs.getString(ConfigStore.KEY_GESTURE_SETTINGS, null))
        putRaw(root, "key_text_tuning", prefs.getString(ConfigStore.KEY_KEY_TEXT_TUNING, null))
        root.put("platform_mode", prefs.getInt(LocalConfig.KEY_PLATFORM, 0))
        root.put("block_update", prefs.getBoolean(LocalConfig.KEY_BLOCK_WETYPE_UPDATE, true))
        root.put("frosted_enabled", prefs.getBoolean(LocalConfig.KEY_FROSTED_ENABLED, true))

        val f = java.io.File(context.cacheDir, "wetype-dark-host.json")
        if (runCatching { f.writeText(root.toString()) }.isFailure) return
        val src = f.absolutePath
        val chown = "chown $WETYPE_UID:$WETYPE_UID"
        val media = su("cp '$src' '$MEDIA_PATH' && $chown '$MEDIA_PATH' && chmod 666 '$MEDIA_PATH'")
        val sd = su("cp '$src' '$SD_PATH' && $chown '$SD_PATH' && chmod 666 '$SD_PATH'")
        FkLog.d("host config sync: media=$media sd=$sd")
    }

    /** 能把字符串解析成对象就嵌成对象，否则原样存字符串 */
    private fun putRaw(root: org.json.JSONObject, key: String, raw: String?) {
        if (raw.isNullOrBlank()) return
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull()
        if (obj != null) root.put(key, obj) else root.put(key, raw)
    }

    private fun su(cmd: String): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor()
        p.exitValue() == 0
    }.getOrDefault(false)
}

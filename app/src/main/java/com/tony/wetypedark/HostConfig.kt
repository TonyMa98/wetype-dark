package com.tony.wetypedark

import android.os.SystemClock
import org.json.JSONObject

/**
 * `:hld` 进程里的配置入口。
 *
 * 模块 App 侧的配置存在自己的 SharedPreferences 里（注入进程读不到），
 * 所以 App 每次保存设置都会把这几个键拼成一份 JSON 推到微信输入法目录，
 * 这里按 mtime 变化重新读，最多 300ms 一次。
 *
 * 文件内容：
 * ```json
 * { "gesture_settings": {...}, "key_text_tuning": {"t9":[dy,dx],...},
 *   "platform_mode": 0, "block_update": true, "frosted_enabled": true }
 * ```
 */
object HostConfig {

    private val PATHS = arrayOf(
        "/data/user/0/com.tencent.wetype/files/wetype-dark-host.json",
        "/data/media/0/Android/data/com.tencent.wetype/files/wetype-dark-host.json",
        "/storage/emulated/0/Android/data/com.tencent.wetype/files/wetype-dark-host.json",
    )

    const val PLATFORM_COLOROS = 0
    const val PLATFORM_HYPEROS = 1

    @Volatile private var root: JSONObject? = null
    @Volatile private var mtime: Long = Long.MIN_VALUE
    @Volatile private var length: Long = -1L
    @Volatile private var lastCheck = 0L

    private fun reload(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastCheck < 300L) return
        lastCheck = now
        runCatching {
            var file: java.io.File? = null
            for (p in PATHS) {
                val f = java.io.File(p)
                if (f.isFile) {
                    file = f
                    break
                }
            }
            val f = file ?: run {
                if (root != null) {
                    root = null
                    mtime = Long.MIN_VALUE
                    length = -1L
                }
                return
            }
            if (f.lastModified() == mtime && f.length() == length) return
            val text = StringBuilder()
            java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(f), "UTF-8"))
                .use { r ->
                    var line = r.readLine()
                    while (line != null) {
                        text.append(line)
                        line = r.readLine()
                    }
                }
            root = JSONObject(text.toString())
            mtime = f.lastModified()
            length = f.length()
        }
    }

    private fun obj(): JSONObject? {
        reload()
        return root
    }

    private fun raw(key: String): String? {
        val o = obj() ?: return null
        val v = o.opt(key) ?: return null
        return when (v) {
            is JSONObject -> v.toString()
            is String -> v.ifBlank { null }
            else -> null
        }
    }

    /** 下滑功能整份 JSON（[com.tony.wetypedark.ui.GestureSettings]） */
    fun gestureSettingsJson(): String? = raw("gesture_settings")

    /** 按键文字位置整份 JSON（`{"t9":[dy,dx], ...}`，单位 dp） */
    fun keyTextTuningJson(): String? = raw("key_text_tuning")

    /** 0 = ColorOS（Oplus 背景模糊） / 1 = HyperOS（无 Oplus 类，走纯色降级） */
    fun platform(): Int {
        val o = obj() ?: return autoPlatform()
        val saved = o.optInt("platform_mode", -1)
        if (saved == PLATFORM_COLOROS || saved == PLATFORM_HYPEROS) return saved
        return autoPlatform()
    }

    private fun autoPlatform(): Int {
        val m = runCatching { android.os.Build.MANUFACTURER.orEmpty() }.getOrDefault("")
        return if (m.equals("Xiaomi", true) || m.equals("Redmi", true)) PLATFORM_HYPEROS
        else PLATFORM_COLOROS
    }

    fun isHyperOs(): Boolean = platform() == PLATFORM_HYPEROS

    /** 屏蔽微信输入法自动更新 */
    fun blockUpdate(): Boolean = obj()?.optBoolean("block_update", true) ?: true

    /** 面板磨砂开关（关掉 = 纯色面板） */
    fun frostedEnabled(): Boolean = obj()?.optBoolean("frosted_enabled", true) ?: true
}

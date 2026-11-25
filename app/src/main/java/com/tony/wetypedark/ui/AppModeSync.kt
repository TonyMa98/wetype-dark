package com.tony.wetypedark.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File

/**
 * 把「逐应用模式」表同步给 :hld 进程。
 *
 * 注入进程（微信输入法 :hld）读不到本应用的私有 SharedPreferences，
 * 只能像 appearance-live 一样落到微信输入法自己的目录里让它读文件。
 * 拖动滑块会连续触发，这里做 250ms 防抖 + 后台线程，避免反复起 su。
 */
internal object AppModeSync {

    private const val INTERNAL = "/data/user/0/com.tencent.wetype/files/wetype-dark-apps.json"
    private const val MEDIA = "/data/media/0/Android/data/com.tencent.wetype/files/wetype-dark-apps.json"
    private const val SD = "/sdcard/Android/data/com.tencent.wetype/files/wetype-dark-apps.json"

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun schedule(context: Context) {
        val app = context.applicationContext
        pending?.let { handler.removeCallbacks(it) }
        val run = Runnable { Thread { push(app) }.start() }
        pending = run
        handler.postDelayed(run, 250)
    }

    fun push(context: Context) {
        val json = LocalConfig.modes(context).toString()
        // 本地库空了（卸载重装 / pm clear / 首次启动）时绝不能拿 "{}" 去盖设备上那份，
        // 否则用户之前勾好的逐应用模式会被静默清空。
        if (json == "{}" && remoteModes().length() > 0) {
            adoptFromDevice(context)
            FkLog.d("app modes sync skipped: local empty, device kept")
            return
        }
        val f = File(context.cacheDir, "wetype-dark-apps.json")
        if (runCatching { f.writeText(json) }.isFailure) return
        val uid = runCatching {
            context.packageManager.getApplicationInfo("com.tencent.wetype", 0).uid
        }.getOrDefault(0)
        val owner = if (uid > 0) "chown $uid:$uid" else "chown 2000:2000"
        val src = f.absolutePath
        // 三处都写：任意一处被系统/权限挡住时，其余仍是可用的最新副本。
        val okInternal = su("cp '$src' '$INTERNAL' && $owner '$INTERNAL' && chmod 644 '$INTERNAL'")
        val okMedia = su("cp '$src' '$MEDIA' && $owner '$MEDIA' && chmod 666 '$MEDIA'")
        val okSd = su("cp '$src' '$SD' && $owner '$SD' && chmod 666 '$SD'")
        FkLog.d("app modes sync: ok=$okInternal/$okMedia/$okSd uid=$uid json=$json")
    }

    /** 设备上已有的逐应用模式（按优先级读三处副本中第一份非空的） */
    fun remoteModes(): JSONObject {
        val tmp = "/data/local/tmp/wetype-dark-apps.peek.json"
        for (path in listOf(INTERNAL, MEDIA, SD)) {
            val ok = su("cat '$path' 2>/dev/null > '$tmp' && chmod 666 '$tmp'")
            if (!ok) continue
            val text = runCatching { File(tmp).readText() }.getOrNull() ?: continue
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
            if (obj.length() > 0) return obj
        }
        return JSONObject()
    }

    /** 本地库为空时，从设备上把逐应用模式捡回来（清数据/重装后不至于丢） */
    fun adoptFromDevice(context: Context) {
        if (LocalConfig.modes(context).length() > 0) return
        val remote = remoteModes()
        if (remote.length() == 0) return
        LocalConfig.prefs(context).edit()
            .putString(LocalConfig.KEY_APP_MODES, remote.toString())
            .commit()
        FkLog.d("app modes adopted: " + remote)
    }

    private fun su(cmd: String): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor()
        p.exitValue() == 0
    }.getOrDefault(false)
}

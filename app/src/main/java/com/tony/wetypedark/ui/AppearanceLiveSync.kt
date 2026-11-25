package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context

internal object AppearanceLiveSync {
    /** /sdcard 是 FUSE 视图，ColorOS 上 root cp 常报 Transport endpoint 断开，直写真存储更稳 */
    private const val MEDIA_PATH =
        "/data/media/0/Android/data/com.tencent.wetype/files/appearance-live.json"
    private const val SD_PATH =
        "/sdcard/Android/data/com.tencent.wetype/files/appearance-live.json"

    /** 与 tools/live-colors/serve.py 的 WETYPE_UID 一致 */
    private const val WETYPE_UID = 10428

    /** 有 root：整份镜像（FileObserver 立刻回调）；没 root：删掉 live，让数据库说了算 */
    fun sync(context: Context, json: String) {
        val f = java.io.File(context.cacheDir, "appearance-live.json")
        if (runCatching { f.writeText(json) }.isFailure) return
        val src = f.absolutePath
        val chown = "chown $WETYPE_UID:$WETYPE_UID"
        val ok = su("cp '$src' '$MEDIA_PATH' && $chown '$MEDIA_PATH' && chmod 666 '$MEDIA_PATH'") ||
            su("cp '$src' '$SD_PATH' && $chown '$SD_PATH' && chmod 666 '$SD_PATH'")
        if (!ok) clear()
        FkLog.d("appearance live sync: ok=$ok src=$src")
    }

    /** 删掉 live 预览文件：DELETE 事件也会让 :hld 重读数据库并强制重绘 */
    fun clear() {
        su("rm -f '$MEDIA_PATH' '$SD_PATH'")
        FkLog.d("appearance live sync: cleared")
    }

    private fun su(cmd: String): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor()
        p.exitValue() == 0
    }.getOrDefault(false)
}

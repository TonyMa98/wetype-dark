package com.tony.wetypedark.ui

import android.content.Context

/**
 * 安装包内置的默认主题配置。
 *
 * 把一份 appearance JSON 打进 assets（`default-appearance.json`），
 * 首次打开模块 App、本地还没有 `appearance` 这份数据时直接落库，
 * 并镜像成 appearance-live.json，保证「装完打开就是这套配色」。
 * 之后用户无论怎么改，都不会再动这份内置值。
 */
internal object DefaultConfig {

    const val ASSET_NAME = "default-appearance.json"

    fun text(context: Context): String? = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private const val KEY_SEEDED = "appearance_seeded"

    /** 只有本地完全没有 appearance 时才写入；返回是否写了 */
    fun seedIfEmpty(context: Context): Boolean {
        val prefs = LocalConfig.prefs(context)
        if (prefs.getString(ConfigStore.KEY_APPEARANCE, null) != null) return false
        val json = text(context) ?: return false
        prefs.edit()
            .putString(ConfigStore.KEY_APPEARANCE, json)
            .putString(ConfigStore.KEY_APPEARANCE_TS, System.currentTimeMillis().toString())
            .putBoolean(KEY_SEEDED, true)
            .commit()
        AppearanceLiveSync.sync(context, json)
        FkLog.d("default appearance seeded")
        return true
    }

    /**
     * 种子值只是「首次启动的那一下」。
     *
     * 之后调色台可能又推送过新配置到 appearance-live.json（那份才是输入法真正在用的），
     * 这时库里的种子值已经过期；如果不跟着更新，用户下次在模块里改任意一项，
     * 整份旧配置会被回写、把后来的改动全部盖掉（表现就是「配色被弄乱了」）。
     * 所以：只要是种子写入的、且 live 内容和库里不一致，就以 live 为准。
     */
    fun syncFromLive(context: Context) {
        val prefs = LocalConfig.prefs(context)
        val saved = prefs.getString(ConfigStore.KEY_APPEARANCE, null)
        // 权限顺序：输入法真正在用的是 live 那份，库只是它的副本；
        // 两边不一致就以 live 为准（应用里保存时两边一定同时写，不会误判）
        val live = readLive(context) ?: return
        if (live.isBlank() || live == saved) {
            return
        }
        prefs.edit()
            .putString(ConfigStore.KEY_APPEARANCE, live)
            .putString(ConfigStore.KEY_APPEARANCE_TS, System.currentTimeMillis().toString())
            .putBoolean(KEY_SEEDED, false)
            .commit()
        FkLog.d("appearance adopted from live file")
    }

    private const val MEDIA_PATH =
        "/data/media/0/Android/data/com.tencent.wetype/files/appearance-live.json"

    /** live 文件属于微信输入法自己的数据目录，普通应用读不到，走 su 拷一份到缓存 */
    private fun readLive(context: Context): String? = runCatching {
        val tmp = java.io.File(context.cacheDir, "live-appearance.json")
        val ok = su("cp '$MEDIA_PATH' '${tmp.absolutePath}' && chmod 666 '${tmp.absolutePath}'")
        if (!ok) return@runCatching null
        tmp.readText()
    }.getOrNull()

    private fun su(cmd: String): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor()
        p.exitValue() == 0
    }.getOrDefault(false)
}

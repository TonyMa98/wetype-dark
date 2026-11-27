package com.tony.wetypedark.ui

import com.tony.wetypedark.PaletteServerService
import com.tony.wetypedark.R

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * wetype-dark 主界面（2026-11-22 用 COUI-Kit 重做，移植进 1119 功能正常版）。
 *
 * 页面在 [HomeScreen] 里用 Compose 装配：COUI Scaffold + 顶栏 + 悬浮胶囊底栏，
 * 内容分「应用」「设置」两栏，在本 Activity 内切换（不再起第二个 Activity）。
 *
 * 和 fork 版重做稿的关键差别：配置仍然走在 1119 的本地通道
 * [LocalConfig] → [AppModeSync] / [HostConfigSync] 文件推送，
 * 不碰 LSPosed RemotePreferences —— 这是 1119「功能正常」的前提。
 *
 * 保留 1119 既有行为：
 *  - 四档模式（含 MODE_ULTRA_DARK = 3 极暗）
 *  - 进页面 adoptFromDevice / seedIfEmpty / syncFromLive / schedule
 *  - 导出 schema 2 版、网页调色地址、appops 收紧安装权限
 */
class AppsActivity : ComponentActivity() {

    companion object {
        private const val TARGET_PKG = "com.tencent.wetype"
        private const val DefaultPcHost = "192.168.2.5"

        private const val REQ_NOTIFY = 1003

        private const val SERVER_PREFS = "wetype-dark-phone"
        private const val KEY_SERVER_ON = "palette_server"

        private const val PALETTE_REAL =
            "/data/media/0/Android/data/com.tencent.wetype/files/appearance-live.json"
        private const val PALETTE_SD =
            "/sdcard/Android/data/com.tencent.wetype/files/appearance-live.json"
        private const val PALETTE_INNER =
            "/data/user/0/com.tencent.wetype/files/appearance-live.json"
        private const val RUNTIME_CFG =
            "/data/user/0/com.tencent.wetype/files/wetype-dark.json"
        private const val REQ_EXPORT = 1001
        private const val REQ_IMPORT = 1002

        const val MODE_LIGHT_ROUNDED = 0
        const val MODE_LIGHT_FLAT = 1
        const val MODE_DARK = 2

        /** 极暗：在暗色基础上再压一档（近纯黑面板） */
        const val MODE_ULTRA_DARK = 3

        /** 从设置页转发进来时带的 tab：0 应用 / 1 设置 */
        const val EXTRA_TAB = "tab"
    }

    private var isDark: Boolean = false

    private var apps by mutableStateOf<List<AppEntry>>(emptyList())
    private var appsLoaded by mutableStateOf(false)
    private var shownApps by mutableStateOf<List<AppEntry>>(emptyList())
    private var query by mutableStateOf("")
    private var showSystem by mutableStateOf(false)
    private var modes by mutableStateOf<Map<String, Int>>(emptyMap())
    private var tab by mutableIntStateOf(0)
    private var platform by mutableIntStateOf(LocalConfig.PLATFORM_COLOROS)
    private var frosted by mutableStateOf(true)
    private var updateBlock by mutableStateOf(true)
    private var liveHost by mutableStateOf(DefaultPcHost)
    private var editHostVisible by mutableStateOf(false)
    private var editHostValue by mutableStateOf("")
    private var paletteServerOn by mutableStateOf(false)

    /** 当前列表顺序（包名）。勾选时不重排，靠它维持原顺序。 */
    private var displayOrder: List<String> = emptyList()

    private fun setPaletteServer(on: Boolean) {
        paletteServerOn = on
        getSharedPreferences(SERVER_PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_SERVER_ON, on).apply()
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            }
            PaletteServerService.start(this)
            toast("调色台已开：${PaletteServerService.url()}")
        } else {
            PaletteServerService.stop(this)
            toast("调色台已关")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = !resolveIsLightTheme(this)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDark
        tab = intent?.getIntExtra(EXTRA_TAB, 0)?.coerceIn(0, 1) ?: 0

        // 首次启动：把安装包内置的那套配色落库 + 镜像成 live 文件
        AppModeSync.adoptFromDevice(this)
        DefaultConfig.seedIfEmpty(this)
        DefaultConfig.syncFromLive(this)

        loadConfig()

        // 手机端调色台：开关状态存在自己的 prefs 里，进程重建后自动恢复服务
        paletteServerOn = getSharedPreferences(SERVER_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_SERVER_ON, false)
        if (paletteServerOn) runCatching { PaletteServerService.start(this) }

        setContent {
            HomeScreen(
                dark = isDark,
                appsLoaded = appsLoaded,
                shownApps = shownApps,
                query = query,
                onQueryChange = {
                    query = it
                    refreshShown(reorder = true)
                },
                showSystem = showSystem,
                onShowSystemChange = {
                    showSystem = it
                    refreshShown(reorder = true)
                },
                modes = modes,
                tab = tab,
                onTabSelected = { tab = it },
                platform = platform,
                onPlatformChange = { onPlatformSelected(it) },
                frosted = frosted,
                onFrostedChange = { onFrostedSelected(it) },
                updateBlock = updateBlock,
                onUpdateBlockChange = { onUpdateBlockSelected(it) },
                liveUrl = urlOf(liveHost),
                onCopyLive = { copyToClipboard(urlOf(liveHost)) },
                onEditLive = {
                    editHostValue = liveHost
                    editHostVisible = true
                },
                onOpenGesture = { startActivity(Intent(this, GestureActivity::class.java)) },
                onOpenKeyText = { startActivity(Intent(this, KeyTextTuningActivity::class.java)) },
                onOpenColor = { startActivity(Intent(this, ColorConfigActivity::class.java)) },
                onExport = { exportConfig() },
                onImport = { importConfig() },
                onRestart = { restartWetype() },
                onModeChange = { pkg, mode -> writeMode(pkg, mode) },
                paletteServerOn = paletteServerOn,
                paletteServerUrl = PaletteServerService.url(),
                onPaletteServerChange = { on -> setPaletteServer(on) },
                onCopyPaletteServerUrl = { copyToClipboard(PaletteServerService.url()) },
                editHostVisible = editHostVisible,
                editHostValue = editHostValue,
                onEditHostValueChange = { editHostValue = it },
                onEditHostConfirm = {
                    val v = editHostValue.trim()
                    editHostVisible = false
                    if (v.isNotEmpty()) {
                        liveHost = v
                        LocalConfig.prefs(this).edit()
                            .putString(LocalConfig.KEY_LIVE_COLORS_PC, v)
                            .apply()
                        HostConfigSync.sync(this)
                        toast("已更新：${urlOf(v)}")
                    }
                },
                onEditHostDismiss = { editHostVisible = false },
            )
        }

        loadApps()
        // 开关是持久化的，AppOps 之前只在「拨开关」那一下写一次，
        // 重装模块 / 系统重置过权限就会漏掉，这里每次进页面按当前开关值重设一次
        applyInstallAppOp(updateBlock)
        // 进页面就把已有勾选推一次，避免设备上还没生成过文件时 :hld 读不到
        AppModeSync.schedule(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tab = intent.getIntExtra(EXTRA_TAB, tab).coerceIn(0, 1)
    }

    // ---------------------------------------------------------------- 配置（1119 本地通道）

    private fun prefs() = LocalConfig.prefs(this)

    private fun loadConfig() {
        platform = runCatching {
            val saved = prefs().getInt(LocalConfig.KEY_PLATFORM, -1)
            if (saved == LocalConfig.PLATFORM_COLOROS ||
                saved == LocalConfig.PLATFORM_HYPEROS
            ) saved else defaultPlatform()
        }.getOrDefault(defaultPlatform())
        frosted = runCatching {
            prefs().getBoolean(LocalConfig.KEY_FROSTED_ENABLED, true)
        }.getOrDefault(true)
        updateBlock = runCatching {
            prefs().getBoolean(LocalConfig.KEY_BLOCK_WETYPE_UPDATE, true)
        }.getOrDefault(true)
        liveHost = runCatching {
            prefs().getString(LocalConfig.KEY_LIVE_COLORS_PC, "") ?: ""
        }.getOrDefault("").ifEmpty { DefaultPcHost }
        reloadModes()
        refreshShown(reorder = true)
    }

    private fun defaultPlatform(): Int {
        val m = runCatching { android.os.Build.MANUFACTURER.orEmpty() }.getOrDefault("")
        return if (m.equals("Xiaomi", true) || m.equals("Redmi", true)) {
            LocalConfig.PLATFORM_HYPEROS
        } else {
            LocalConfig.PLATFORM_COLOROS
        }
    }

    private fun reloadModes() {
        val obj = LocalConfig.modes(this)
        val map = HashMap<String, Int>()
        obj.keys().forEach { k ->
            val v = obj.optInt(k, -1)
            if (v >= 0) map[k] = v
        }
        modes = map
    }

    private fun writeMode(pkg: String, mode: Int?) {
        // 先改内存副本：列表立刻按新状态显示勾选
        modes = if (mode == null) modes - pkg else modes + (pkg to mode)
        LocalConfig.writeMode(this, pkg, mode)
        AppModeSync.schedule(this)
    }

    private fun onPlatformSelected(index: Int) {
        val v = if (index == 0) LocalConfig.PLATFORM_COLOROS else LocalConfig.PLATFORM_HYPEROS
        platform = v
        prefs().edit().putInt(LocalConfig.KEY_PLATFORM, v).apply()
        HostConfigSync.sync(this)
        toast(
            if (v == LocalConfig.PLATFORM_COLOROS) "已切换为 ColorOS 17，下次唤起生效"
            else "已切换为 AOSP，下次唤起生效"
        )
    }

    private fun onFrostedSelected(v: Boolean) {
        frosted = v
        prefs().edit().putBoolean(LocalConfig.KEY_FROSTED_ENABLED, v).apply()
        HostConfigSync.sync(this)
        toast(if (v) "已开启背景模糊，下次唤起键盘生效" else "已关闭背景模糊，下次唤起键盘生效")
        // 不再 force-stop 微信输入法：会把它打进 stopped 状态，IME 唤不起来。
    }

    private fun onUpdateBlockSelected(v: Boolean) {
        updateBlock = v
        prefs().edit().putBoolean(LocalConfig.KEY_BLOCK_WETYPE_UPDATE, v).apply()
        HostConfigSync.sync(this)
        applyInstallAppOp(v)
        toast(if (v) "已开启：微信输入法下次唤起后拦截更新" else "已关闭：允许微信输入法更新")
    }

    /** 收紧/恢复微信输入法的「安装未知应用」权限，防止它绕过 Xposed 钩子用系统安装器。 */
    private fun applyInstallAppOp(enabled: Boolean) {
        Thread {
            runCatching {
                val mode = if (enabled) "deny" else "allow"
                val p = ProcessBuilder(
                    "su", "-c",
                    "appops set com.tencent.wetype REQUEST_INSTALL_PACKAGES $mode"
                ).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                FkLog.d("settings: appops REQUEST_INSTALL_PACKAGES -> $mode")
            }.onFailure { FkLog.e("settings: appops update block failed", it) }
        }.start()
    }

    // ---------------------------------------------------------------- 应用列表

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val collator = java.text.Collator.getInstance(Locale.CHINA)
            val result = LinkedHashMap<String, AppEntry>()

            runCatching {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0).forEach { ri ->
                    val pkg = ri.activityInfo?.packageName ?: return@forEach
                    if (pkg == packageName || pkg == TARGET_PKG) return@forEach
                    if (result.containsKey(pkg)) return@forEach
                    val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                    result[pkg] = AppEntry(
                        runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg),
                        pkg,
                        runCatching { ri.loadIcon(pm) }.getOrNull(),
                        ai?.let { isSystemApp(it) } ?: false,
                    )
                }
            }

            runCatching {
                pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA).forEach { ai ->
                    val pkg = ai.packageName ?: return@forEach
                    if (pkg == packageName || pkg == TARGET_PKG) return@forEach
                    if (result.containsKey(pkg)) return@forEach
                    result[pkg] = AppEntry(
                        runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg),
                        pkg,
                        runCatching { ai.loadIcon(pm) }.getOrNull(),
                        isSystemApp(ai),
                    )
                }
            }

            val entries = result.values.sortedWith(compareBy(collator) { it.label })
            runOnUiThread {
                apps = entries
                appsLoaded = true
                refreshShown(reorder = true)
            }
        }.start()
    }

    private fun isSystemApp(ai: android.content.pm.ApplicationInfo): Boolean {
        val flags = ai.flags
        return (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    /**
     * @param reorder true = 重新分区（已勾选置顶）；false = 保持当前显示顺序。
     *   勾选/取消勾选时不重排 —— 勾选只是勾上，下一次刷新才置顶。
     */
    private fun refreshShown(reorder: Boolean = true) {
        val base = if (showSystem) apps else apps.filter { !it.isSystem }
        val q = query.lowercase()
        val filtered = if (q.isEmpty()) base else base.filter {
            it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
        }
        val byPkg = filtered.associateBy { it.pkg }
        displayOrder = if (reorder || displayOrder.isEmpty()) {
            val (checked, unchecked) = filtered.partition { modes.containsKey(it.pkg) }
            (checked + unchecked).map { it.pkg }
        } else {
            val kept = displayOrder.mapNotNull { byPkg[it] }.map { it.pkg }
            val keptSet = kept.toHashSet()
            kept + filtered.filterNot { it.pkg in keptSet }.map { it.pkg }
        }
        shownApps = displayOrder.mapNotNull { byPkg[it] }
    }

    // ---------------------------------------------------------------- 导出 / 恢复（1119 schema v2）

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    private fun exportConfig() {
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "wetype-config-${stamp()}.json")
                }, REQ_EXPORT
            )
        }.onFailure { toast("没有可用的文件管理器") }
    }

    private fun importConfig() {
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
                }, REQ_IMPORT
            )
        }.onFailure { toast("没有可用的文件管理器") }
    }

    private fun suCat(path: String): String? = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '$path'"))
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        if (proc.exitValue() == 0 && out.isNotBlank()) out else null
    }.getOrNull()

    private fun JSONObject.putObject(key: String, raw: String?) {
        if (raw.isNullOrBlank()) return
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj != null) put(key, obj) else put(key, raw)
    }

    private fun flatten(v: Any?): String? = when (v) {
        is JSONObject -> v.toString()
        is String -> v.ifBlank { null }
        else -> null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val p = prefs()
        when (requestCode) {
            REQ_EXPORT -> {
                // 全量导出：应用勾选、开关、下滑、按键文字、颜色配置（设备上整份 appearance-live.json，
                // 里面有 light/dark/ultra 以及任何自己加过的主题，一律原样带走）+ 运行时 live 配置。
                val modeJson = LocalConfig.modes(this@AppsActivity)
                Thread {
                    val paletteRaw = suCat(PALETTE_REAL) ?: suCat(PALETTE_SD) ?: suCat(PALETTE_INNER)
                    val runtimeRaw = suCat(RUNTIME_CFG)
                    val root = JSONObject().apply {
                        put("schema", "fk-wetype-config")
                        put("version", 3)
                        put("exportedAt", System.currentTimeMillis())
                        put("app_modes", modeJson)
                        put("frosted_enabled", p.getBoolean(LocalConfig.KEY_FROSTED_ENABLED, true))
                        put("block_wetype_update", p.getBoolean(LocalConfig.KEY_BLOCK_WETYPE_UPDATE, true))
                        put("platform_mode", p.getInt(LocalConfig.KEY_PLATFORM, 0))
                        putObject("gesture_settings", p.getString(ConfigStore.KEY_GESTURE_SETTINGS, null))
                        putObject("key_text_tuning", p.getString(ConfigStore.KEY_KEY_TEXT_TUNING, null))
                        putObject("appearance", p.getString(ConfigStore.KEY_APPEARANCE, null))
                        putObject("palette", paletteRaw)
                        putObject("runtime_config", runtimeRaw)
                        put("live_colors_pc", p.getString(LocalConfig.KEY_LIVE_COLORS_PC, "") ?: "")
                    }
                    val ok = runCatching {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(root.toString(2).toByteArray(Charsets.UTF_8))
                        } ?: false
                        true
                    }.getOrDefault(false)
                    runOnUiThread {
                        toast(
                            if (ok) "已导出（含 ${(paletteRaw?.let { r -> runCatching { JSONObject(r).length() }.getOrDefault(0) } ?: 0)} 套主题配色）"
                            else "写入失败"
                        )
                    }
                }.start()
            }

            REQ_IMPORT -> {
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
                if (text.isNullOrBlank()) {
                    toast("读不到文件内容")
                    return
                }
                val root = runCatching { JSONObject(text) }.getOrNull()
                if (root == null || root.optString("schema") != "fk-wetype-config") {
                    toast("这不是 Wetype Dark 的配置文件")
                    return
                }
                val ed = p.edit()
                root.opt("app_modes")?.let { ed.putString(LocalConfig.KEY_APP_MODES, it.toString()) }
                ed.putBoolean(LocalConfig.KEY_FROSTED_ENABLED, root.optBoolean("frosted_enabled", true))
                ed.putBoolean(LocalConfig.KEY_BLOCK_WETYPE_UPDATE, root.optBoolean("block_wetype_update", true))
                ed.putInt(LocalConfig.KEY_PLATFORM, root.optInt("platform_mode", 0))
                flatten(root.opt("gesture_settings"))?.let {
                    ed.putString(ConfigStore.KEY_GESTURE_SETTINGS, it)
                }
                flatten(root.opt("key_text_tuning"))?.let {
                    ed.putString(ConfigStore.KEY_KEY_TEXT_TUNING, it)
                }
                flatten(root.opt("appearance"))?.let {
                    ed.putString(ConfigStore.KEY_APPEARANCE, it)
                }
                root.optString("live_colors_pc", "").takeIf { it.isNotBlank() }?.let {
                    ed.putString(LocalConfig.KEY_LIVE_COLORS_PC, it)
                }
                ed.apply()
                // 恢复完立刻推给 :hld：配置 + 配色文件，不用等下次唤起
                HostConfigSync.sync(this)
                flatten(root.opt("appearance"))?.let { AppearanceLiveSync.sync(this, it) }
                flatten(root.opt("palette"))?.let { AppearanceLiveSync.sync(this, it) }
                flatten(root.opt("runtime_config"))?.let { raw ->
                    Thread {
                        runCatching {
                            val tmp = java.io.File(cacheDir, "wetype-dark.json")
                            tmp.writeText(raw)
                            Runtime.getRuntime()
                                .exec(arrayOf("su", "-c", "cp '${tmp.absolutePath}' '$RUNTIME_CFG' && chmod 644 '$RUNTIME_CFG'"))
                                .waitFor()
                        }
                    }.start()
                }
                loadConfig()
                toast("已恢复")
            }
        }
    }

    // ---------------------------------------------------------------- 网页调色

    private fun urlOf(host: String): String {
        val h = host.trim().ifEmpty { DefaultPcHost }
        val port = if (h.contains(":")) "" else ":8777"
        return "http://$h$port"
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("网页调色地址", text))
            toast("已复制，去电脑浏览器粘贴")
        }.onFailure { toast("复制失败：${it.message}") }
    }

    // ---------------------------------------------------------------- 重启微信输入法

    private fun restartWetype() {
        toast("正在重启微信输入法…")
        Thread {
            val script = """
                BEFORE=${'$'}(pidof com.tencent.wetype com.tencent.wetype:hld 2>/dev/null)
                echo "BEFORE:${'$'}{BEFORE:-none}"
                am force-stop com.tencent.wetype >/dev/null 2>&1
                sleep 0.6
                for p in ${'$'}(pidof com.tencent.wetype com.tencent.wetype:hld 2>/dev/null); do kill -9 ${'$'}p 2>/dev/null; done
                sleep 1.2
                LEFT=${'$'}(pidof com.tencent.wetype com.tencent.wetype:hld 2>/dev/null)
                echo "RESULT:${'$'}{LEFT:-none}"
            """.trimIndent()
            var out = ""
            runCatching {
                val p = ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start()
                out = p.inputStream.bufferedReader().readText()
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            }.onFailure { out = "${it.javaClass.simpleName}: ${it.message}" }
            FkLog.d("restart wetype: ${out.replace('\n', '|').take(200)}")

            val before = out.lineSequence().firstOrNull { it.startsWith("BEFORE:") }
                ?.removePrefix("BEFORE:")?.trim()?.split(" ")?.filter { it.isNotBlank() }?.toSet()
                ?: emptySet()
            val left = out.lineSequence().firstOrNull { it.startsWith("RESULT:") }
                ?.removePrefix("RESULT:")?.trim()
            val leftPids = left?.split(" ")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            val stillOld = leftPids.intersect(before)

            val msg = when {
                out.contains("denied", true) || out.contains("not allowed", true) ||
                    out.contains("Permission", true) -> "重启失败：KernelSU 未授权本应用"
                out.contains("No such file", true) -> "重启失败：找不到 su"
                left == null -> "重启失败：su 无输出"
                left == "none" || leftPids.isEmpty() -> "已重启微信输入法"
                stillOld.isEmpty() -> "已重启微信输入法"
                else -> "重启失败：进程 ${stillOld.joinToString(" ")} 仍在运行"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    // ---------------------------------------------------------------- 工具

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun resolveIsLightTheme(context: Context): Boolean {
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
        return try {
            ta.getBoolean(0, true)
        } finally {
            ta.recycle()
        }
    }
}

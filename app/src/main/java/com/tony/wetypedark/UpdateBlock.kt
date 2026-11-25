package com.tony.wetypedark

import android.app.Activity
import android.content.Intent
import io.github.libxposed.api.XposedModule

/**
 * 「屏蔽微信输入法自动更新」
 *
 * 微信输入法内置腾讯 Upgrade SDK（com.tencent.upgrade），会自己检查版本、下载 APK
 * 并拉起安装。这个开关打开后做三层拦截：
 *  1. 升级弹窗/升级 Activity 直接 finish；
 *  2. 带 APK 或 package-installer 的 startActivity 直接吞掉；
 *  3. PackageInstaller.Session.commit 直接跳过，不提交安装会话。
 *
 * 额外在设置页开关里把 REQUEST_INSTALL_PACKAGES AppOps 设为 deny，
 * 阻止它用系统安装器装自己的新版（关掉开关时恢复 allow）。
 */
object UpdateBlock {

    private const val TAG = "UpdateBlock"

    fun install(module: XposedModule, loader: ClassLoader) {
        // 升级 SDK 的两个 Activity：onResume 后 finish，避免用户误点「立即更新」
        for (name in listOf(
            "com.tencent.upgrade.ui.UpgradeDialogActivity",
            "com.tencent.upgrade.ui.UpgradeActiveActivity",
        )) {
            runCatching {
                val cls = Class.forName(name, false, loader)
                val m = cls.getDeclaredMethod("onResume").apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    val enabled = HostConfig.blockUpdate()
                    if (enabled) {
                        FkLogBridge.d("$TAG: blocked upgrade activity ${chain.thisObject.javaClass.name}")
                        runCatching { (chain.thisObject as Activity).finish() }
                        null
                    } else chain.proceed()
                }
            }.onFailure { FkLogBridge.d("$TAG: hook $name skipped: ${it.javaClass.simpleName}") }
        }

        // 安装包 / 安装器 Intent：不启动
        runCatching {
            val m = Activity::class.java.getDeclaredMethod("startActivity", Intent::class.java)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val intent = chain.args.getOrNull(0) as? Intent
                if (HostConfig.blockUpdate() && isInstallIntent(intent)) {
                    FkLogBridge.d("$TAG: blocked install startActivity ${intent?.action} ${intent?.data}")
                    null
                } else chain.proceed()
            }
        }.onFailure { FkLogBridge.d("$TAG: startActivity hook skipped: ${it.javaClass.simpleName}") }

        // 自带安装器提交会话：直接不提交（不抛异常，避免把宿主进程搞崩）
        runCatching {
            val cls = Class.forName("android.content.pm.PackageInstaller\$Session", false, loader)
            for (m in cls.declaredMethods) {
                if (m.name == "commit" || m.name == "commitSession") {
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        if (HostConfig.blockUpdate()) {
                            FkLogBridge.d("$TAG: blocked PackageInstaller.${m.name}")
                            null
                        } else chain.proceed()
                    }
                }
            }
        }.onFailure { FkLogBridge.d("$TAG: PackageInstaller hook skipped: ${it.javaClass.simpleName}") }
    }

    private fun isInstallIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val type = intent.type.orEmpty()
        val data = intent.data
        if (type == "application/vnd.android.package-archive") return true
        if (data != null) {
            val s = data.toString()
            if (s.endsWith(".apk") || s.contains("package_installer") || s.contains("packageinstaller")) return true
        }
        val cls = intent.component?.className.orEmpty()
        return cls.contains("PackageInstaller") || cls.contains("packageinstaller")
    }
}

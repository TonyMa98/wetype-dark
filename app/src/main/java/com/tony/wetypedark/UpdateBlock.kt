package com.tony.wetypedark

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「屏蔽微信输入法自动更新」
 *
 * 微信输入法有两套升级路径，都得拦：
 *
 * ① 自研 Shiply + 插件里的 UpgradeMgr（主路径）
 *    `plugin.hld.upgrade.f.t(Context, String)` 拿到下载好的 apk 后
 *    用 `context.startActivity(ACTION_VIEW + application/vnd.android.package-archive)` 拉系统安装器。
 *    这个 context 是 Application 上下文 → 走 ContextImpl，不走 Activity.startActivity，
 *    所以只 hook Activity 是拦不到的（旧版的漏点）。
 *    另外 `shiply.n.n0(Context)` 负责弹「版本更新」浮窗，`shiply.n.l0(boolean)` 负责埋三处红点提示。
 *
 * ② 腾讯 Apollo Upgrade SDK
 *    `com.tencent.upgrade.ui.UpgradeDialogActivity` / `UpgradeActiveActivity`。
 *
 * 这些代码分布在主进程和 `:hld` 里都有（IPC 回调 `MainProcessIPCService` 里也会调 t()），
 * 所以本 hook 必须在**每个**微信输入法进程里安装，不能只在 `:hld`。
 */
object UpdateBlock {

    private const val TAG = "UpdateBlock"
    private const val HLD_PKG = "com.tencent.wetype.plugin.hld"

    private val installed = AtomicBoolean(false)

    fun install(module: XposedModule, loader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        hookUpgradeActivities(module)
        hookStartActivity(module)
        hookPackageInstaller(module)
        hookWeTypeUpgrade(module, loader)

        FkLogBridge.d("$TAG: installed pid=${android.os.Process.myPid()} proc=$processTag")
    }

    private val processTag: String
        get() = runCatching {
            val f = java.io.File("/proc/self/cmdline")
            f.readText().trim().replace("\u0000", "")
        }.getOrDefault("?")

    // ------------------------------------------------------------ ② Apollo 升级 Activity

    private fun hookUpgradeActivities(module: XposedModule) {
        for (name in listOf(
            "com.tencent.upgrade.ui.UpgradeDialogActivity",
            "com.tencent.upgrade.ui.UpgradeActiveActivity",
        )) {
            runCatching {
                val cls = Class.forName(name, false, Activity::class.java.classLoader)
                val m = cls.getDeclaredMethod("onResume").apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    if (HostConfig.blockUpdate()) {
                        FkLogBridge.d("$TAG: blocked upgrade activity ${chain.thisObject.javaClass.name}")
                        runCatching { (chain.thisObject as Activity).finish() }
                        null
                    } else chain.proceed()
                }
            }.onFailure { FkLogBridge.d("$TAG: hook $name skipped: ${it.javaClass.simpleName}") }
        }
    }

    // ------------------------------------------------------------ 安装 Intent 拦截

    private fun hookStartActivity(module: XposedModule) {
        // Activity 的四个入口（Activity 自己实现，不经过 ContextImpl）
        hookStart(module, Activity::class.java, "startActivity",
            arrayOf(Intent::class.java), "wetype_dark_ub_act1")
        hookStart(module, Activity::class.java, "startActivity",
            arrayOf(Intent::class.java, android.os.Bundle::class.java), "wetype_dark_ub_act2")
        hookStart(module, Activity::class.java, "startActivityForResult",
            arrayOf(Intent::class.java, Integer.TYPE), "wetype_dark_ub_act3")
        hookStart(module, Activity::class.java, "startActivityForResult",
            arrayOf(Intent::class.java, Integer.TYPE, android.os.Bundle::class.java),
            "wetype_dark_ub_act4")

        // Application / Service 等非 Activity 上下文（微信输入法装包走的就是这条）
        runCatching {
            val ctxImpl = Class.forName("android.app.ContextImpl", false, null)
            hookStart(module, ctxImpl, "startActivity",
                arrayOf(Intent::class.java), "wetype_dark_ub_ctx1")
            hookStart(module, ctxImpl, "startActivity",
                arrayOf(Intent::class.java, android.os.Bundle::class.java), "wetype_dark_ub_ctx2")
        }.onFailure { FkLogBridge.d("$TAG: ContextImpl hook skipped: ${it.javaClass.simpleName}") }
    }

    private fun hookStart(
        module: XposedModule,
        cls: Class<*>,
        name: String,
        params: Array<Class<*>>,
        id: String,
    ) {
        runCatching {
            val m = cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val intent = chain.args.getOrNull(0) as? Intent
                if (HostConfig.blockUpdate() && isInstallIntent(intent)) {
                    FkLogBridge.d("$TAG: blocked install $name ${intent?.action} ${intent?.data}")
                    null
                } else chain.proceed()
            }
        }.onFailure { FkLogBridge.d("$TAG: $id skipped: ${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------ 自带安装器

    private fun hookPackageInstaller(module: XposedModule) {
        runCatching {
            val cls = Class.forName("android.content.pm.PackageInstaller\$Session", false, null)
            for (m in cls.declaredMethods) {
                if (m.name != "commit" && m.name != "commitSession") continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    if (HostConfig.blockUpdate()) {
                        FkLogBridge.d("$TAG: blocked PackageInstaller.${m.name}")
                        null
                    } else chain.proceed()
                }
            }
        }.onFailure { FkLogBridge.d("$TAG: PackageInstaller hook skipped: ${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------ ① 微信输入法自研升级

    private fun hookWeTypeUpgrade(module: XposedModule, loader: ClassLoader) {
        // 装包动作：upgrade.f.t(Context, String)。
        // 名字会随版本变，所以按「参数 (Context, String)、返回 void」匹配。
        runCatching {
            val cls = Class.forName("$HLD_PKG.upgrade.f", false, loader)
            var installHooks = 0

            for (m in cls.declaredMethods) {
                if (m.returnType != Void.TYPE) continue
                val ps = m.parameterTypes
                if (ps.size != 2) continue
                if (ps[0] != Context::class.java || ps[1] != String::class.java) continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    if (HostConfig.blockUpdate()) {
                        FkLogBridge.d("$TAG: blocked UpgradeUtil.${m.name} apk=${chain.args.getOrNull(1)}")
                        null
                    } else chain.proceed()
                }
                installHooks++
            }

            // about 页展示的「新版本信息」：任何返回 PlatformUpgradeInfo 的都返回 null
            var infoHooks = 0
            for (m in cls.declaredMethods) {
                if (!m.returnType.name.endsWith("PlatformUpgradeInfo")) continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    if (HostConfig.blockUpdate()) null else chain.proceed()
                }
                infoHooks++
            }

            // 缓存升级信息对象 / 「是否有更新」标志 / 点「版本更新」后的下载动作
            for (m in cls.declaredMethods) {
                m.isAccessible = true
                if (m.name == "s" && m.parameterCount == 0
                    && m.returnType == Boolean::class.javaPrimitiveType) {
                    module.hook(m).intercept { chain ->
                        if (HostConfig.blockUpdate()) false else chain.proceed()
                    }
                } else if (m.name == "q" && m.parameterCount == 0 && m.returnType != Void.TYPE) {
                    module.hook(m).intercept { chain ->
                        if (HostConfig.blockUpdate()) null else chain.proceed()
                    }
                } else if (m.name == "H" && m.parameterCount == 1
                    && m.parameterTypes[0] == Boolean::class.javaPrimitiveType
                    && m.returnType == Void.TYPE) {
                    module.hook(m).intercept { chain ->
                        if (HostConfig.blockUpdate()) {
                            FkLogBridge.d("$TAG: blocked UpgradeUtil.H fromManual=${chain.args.getOrNull(0)}")
                            null
                        } else chain.proceed()
                    }
                }
            }

            FkLogBridge.d("$TAG: upgrade f hooks install=$installHooks info=$infoHooks")
        }.onFailure { FkLogBridge.d("$TAG: upgrade f hook skipped: ${it.javaClass.simpleName}") }

        runCatching {
            val cls = Class.forName("$HLD_PKG.upgrade.shiply.n", false, loader)
            var strategyHooks = 0
            var checkHooks = 0
            var tipHooks = 0

            // 读缓存策略的 getter（z() 及同类变体）：一律 null。
            // 没有策略 → isAppNeedUpdate / isAppCanShowUpdateDialog / 版本号判断全都认为没有更新。
            for (m in cls.declaredMethods) {
                if (m.parameterCount != 0) continue
                if (!m.returnType.name.endsWith("UpgradeStrategy")) continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    if (HostConfig.blockUpdate()) null else chain.proceed()
                }
                strategyHooks++
            }

            // checkUpgrade(boolean, from)：连网络请求都不发
            for (m in cls.declaredMethods) {
                if (m.parameterCount != 2) continue
                if (m.returnType != Void.TYPE) continue
                if (m.parameterTypes[0] != Boolean::class.javaPrimitiveType) continue
                if (!m.parameterTypes[1].name.startsWith(cls.name)) continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    if (HostConfig.blockUpdate()) {
                        FkLogBridge.d("$TAG: blocked shiply checkUpgrade")
                        null
                    } else chain.proceed()
                }
                checkHooks++
            }

            // 三处红点提示的读取（about 页 / 键盘 logo / 设置页）
            for (name in listOf("x", "B", "C")) {
                val m = runCatching { cls.getDeclaredMethod(name) }.getOrNull() ?: continue
                if (m.returnType != Boolean::class.javaPrimitiveType) continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    if (HostConfig.blockUpdate()) false else chain.proceed()
                }
                tipHooks++
            }

            // 弹「版本更新」浮窗
            runCatching {
                val m = cls.getDeclaredMethod("n0", Context::class.java).apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    if (HostConfig.blockUpdate()) {
                        FkLogBridge.d("$TAG: blocked shiply showUpdateDialog")
                        null
                    } else chain.proceed()
                }
            }.onFailure { FkLogBridge.d("$TAG: shiply n0 skipped: ${it.javaClass.simpleName}") }

            // 把三处红点置亮的入口：true 直接丢掉
            runCatching {
                val m = cls.getDeclaredMethod("l0", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    val on = chain.args.getOrNull(0) as? Boolean ?: false
                    if (HostConfig.blockUpdate() && on) null else chain.proceed()
                }
            }.onFailure { FkLogBridge.d("$TAG: shiply l0 skipped: ${it.javaClass.simpleName}") }

            FkLogBridge.d("$TAG: shiply hooks strategy=$strategyHooks check=$checkHooks tip=$tipHooks")
        }.onFailure { FkLogBridge.d("$TAG: shiply hooks skipped: ${it.javaClass.simpleName}") }
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

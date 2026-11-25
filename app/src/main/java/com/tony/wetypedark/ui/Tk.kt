package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper

/**
 * COUI 反射桥：把「系统设置 App（com.android.settings）里的 COUI 资源与主题」
 * 包装成一个可直接用的对象。
 *
 * 移植自 `Color OS17/samples/kotlin/00_Tk.kt`（原样保留其结论与坑位说明）。
 *
 * ## 为什么必须这么绕
 *
 * COUI 是 ColorOS 的私有 UI 库，代码和资源都打包在 com.android.settings
 * （以及其他系统 App）的 APK 里。第三方 App 既不能 `import com.coui.*`，
 * 也不能用自己进程的 Resources 去查它的资源 ID。唯一可行的办法：
 *
 * ```
 * createPackageContext("com.android.settings", INCLUDE_CODE | IGNORE_SECURITY)
 *   → 拿到一个"能加载 Settings 代码和资源"的 Context
 *   → 用它的 classLoader.loadClass("com.coui.xxx") 反射构造控件
 *   → 用它的 resources.getIdentifier(...) 查资源
 * ```
 *
 * ## 三个必踩的坑（都在本项目里发生过）
 *
 * **坑 1｜查资源必须用 base.resources，不能用 app.resources**
 * ```
 * ❌ app.resources.getIdentifier("coui_bottom_...", "dimen", "com.android.settings")  // 恒为 0
 * ✅ base.resources.getIdentifier(...)   // base = createPackageContext 出来的
 * ```
 *
 * **坑 2｜取 drawable 时，资源表用 base，主题必须用 themed**
 * ```
 * ❌ base.resources.getDrawable(id, base.theme)
 *    base.theme 实际是 Theme.Material.Light，?couiColorPrimaryNeutral 全部解析失败 → 图标纯白
 * ✅ base.resources.getDrawable(id, themed.theme)
 * ```
 *
 * **坑 3｜构造控件时传 themed，不是 base**
 * ```
 * ✅ cls.getConstructor(Context::class.java).newInstance(tk.themed)
 * ```
 *
 * ## 用法
 *
 * ```
 * val tk = Tk.get(context)
 * tk.color("couiColorLabelPrimary")                        // → 0xE6000000
 * tk.dimen("coui_bottom_floating_navigation_bar_height")   // → 196.0f (56dp @3.5)
 * tk.hasClass(Tk.FLOATING_BAR)                             // → true
 * ```
 */
object Tk {

    // ⚠️ Kotlin 里 object 内部已有隐式成员 `Tk.instance`，
    //    所以千万不要声明 `val instance`——会重名冲突。

    /** COUI 的宿主包 */
    private const val HOST_PACKAGE = "com.android.settings"

    // ── 常用类名常量 ──
    const val FLOATING_BAR =
        "com.coui.appcompat.bottomfloatingnavigationbar.COUIBottomFloatingNavigationBar"
    const val FLOATING_BAR_ITEM =
        "com.coui.appcompat.bottomfloatingnavigationbar.COUIBottomFloatingNavigationBarItem\$Builder"
    const val TOOLBAR = "com.coui.appcompat.toolbar.COUIToolbar"
    const val NAV_VIEW = "com.coui.appcompat.bottomnavigation.COUINavigationView"

    @Volatile
    private var cached: Ctx? = null

    class Ctx(
        val app: Context,
        /** 宿主包名 */
        val pkg: String,
        /** 宿主的 Context（含 code + resources） */
        val base: Context,
        /** 带 COUI 主题的 wrapper —— 构造控件时传这个 */
        val themed: ContextThemeWrapper,
        val themeRes: Int,
    ) {
        /** 查资源 ID。必须用 base.resources（见坑 1） */
        fun resId(name: String, defType: String): Int =
            base.resources.getIdentifier(name, defType, pkg)

        /** 解析 COUI 的 attr 颜色，例如 couiColorLabelPrimary → 0xE6000000 */
        fun color(name: String): Int {
            val id = resId(name, "attr")
            if (id == 0) return 0
            val tv = TypedValue()
            if (!themed.theme.resolveAttribute(id, tv, true)) return 0
            return if (tv.resourceId != 0) themed.getColor(tv.resourceId) else tv.data
        }

        /** 查 dimen，返回 px */
        fun dimen(name: String): Float {
            val id = resId(name, "dimen")
            if (id == 0) return 0f
            return base.resources.getDimension(id)
        }

        /** 类是否存在于宿主 APK */
        fun hasClass(name: String): Boolean = try {
            base.classLoader.loadClass(name)
            true
        } catch (_: Throwable) {
            false
        }

        /** 底栏预留高度（px） */
        fun barHeight(): Float = if (hasClass(FLOATING_BAR)) {
            dimen("coui_bottom_floating_navigation_bar_min_height_without_padding_vertical")
        } else {
            dimen("coui_navigation_normal_item_height") +
                dimen("coui_floating_toolbar_menu_view_padding")
        }

        /** 取 COUI 的 drawable，**资源表 base、主题 themed**（见坑 2） */
        fun drawable(name: String): android.graphics.drawable.Drawable? {
            val id = resId(name, "drawable")
            if (id == 0) return null
            return runCatching { base.resources.getDrawable(id, themed.theme) }.getOrNull()
        }
    }

    /** 单例获取（线程安全）。任何异常都返回 null，调用方自行降级。 */
    fun get(context: Context): Ctx? = cached ?: synchronized(this) {
        cached ?: runCatching {
            val app = context.applicationContext ?: context

            // 与 OPLUS盒子 tk2 完全相同的 flag
            val base = app.createPackageContext(
                HOST_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            val cfgCtx = base.createConfigurationContext(Configuration(base.resources.configuration))

            // 按当前深/浅色选主题
            val dark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val styleName = if (dark) "Theme.COUI.Main.Dark" else "Theme.COUI.Main"
            val styleId = base.resources.getIdentifier(styleName, "style", HOST_PACKAGE)

            val wrapper = ContextThemeWrapper(
                cfgCtx,
                if (styleId != 0) styleId else android.R.style.Theme_DeviceDefault,
            )
            Ctx(app, HOST_PACKAGE, base, wrapper, styleId)
        }.getOrNull()?.also {
            cached = it
            FkLog.d("Tk: host=${it.pkg} theme=0x${it.themeRes.toString(16)}")
        }
    }

    fun hasCouiBar(context: Context): Boolean =
        runCatching { get(context)?.hasClass(FLOATING_BAR) == true }.getOrDefault(false)

    fun hasToolbar(context: Context): Boolean =
        runCatching { get(context)?.hasClass(TOOLBAR) == true }.getOrDefault(false)
}

package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context

/**
 * COUI 材质系统（磨砂 / 描边 / 高光 / 焦散阴影）。
 *
 * 移植自 `Color OS17/samples/kotlin/07_CouiMaterial.kt`。
 *
 * ## 一、这是「最容易漏」的一步
 *
 * 不注册观察者，这几个静态 boolean 永远是初值 `false`：
 * ```
 * MATERIAL_STROKE_ENABLE / MATERIAL_BLUR_ENABLE / MATERIAL_FEATURE_ENABLE
 * ```
 * → 底栏的磨砂、白描边、高光、焦散阴影**全部消失**，
 * 只剩一个纯白胶囊 + 阴影的降级样式（就是本项目第一版截图里的样子）。
 *
 * 症状：换控件之后「高光啊什么的，效果又没了」。
 * 原因：`register()` 写在旧的底栏文件里，换成新底栏后走不到那段代码。
 *
 * → **只要页面上有 COUI 悬浮控件，就必须先 register 一次。**
 *
 * ## 二、OPLUS盒子 只做这一件事
 *
 * 反汇编 + 真机日志证据：
 * ```
 * OplusBoxNativeUi: System material observer: com.android.settings; flags=31
 * OplusBoxNativeUi: Floating navigation:
 *     com.coui.appcompat.bottomfloatingnavigationbar.COUIBottomFloatingNavigationBar
 * ```
 * 它只做两件事：`registerMaterialStateObserver(appCtx, 31)` + `new bar() + addItems()`。
 * 它**不**调用 `setUseMaterialEffectAttr` / `setViewBlurEffect` / `setViewStrokeEffect`，
 * 也**不**设 `clipToOutline`。
 *
 * ## 三、⚠️ 不要自己「强行开启」材质效果
 *
 * 试过 `FORCE_ENABLE + setViewBlurEffect + clipToOutline`，结果把 COUI 的降级路径
 * （白色胶囊 + 大阴影）覆盖成了磨砂 + 白描边 + 阴影被裁，和真机不一致。
 *
 * 阴影、描边、磨砂全部由 COUI 内部 `initTexture()` 自己决定：
 * ```
 * BaseBottomFloatingMenuView.initTexture()
 *   ├─ updateOuterShadow()
 *   │    isCausticShadowEffectEnabled() 为真 → setCausticShadowEffect()
 *   │    否则 → ShadowUtils.setElevationToView(
 *   │             view,
 *   │             support_shadow_size_level_three,
 *   │             coui_bottom_floating_navigation_bar_menu_shadow_color,
 *   │             2)
 *   ├─ updateStrokeEffect()   → 版本/开关不满足时 TYPE_NO_EFFECT
 *   └─ updateBlurEffect()     → 不满足时背景换成 coui_tab_navigation_view_bg
 *                               （solid #FFFFFFFF + corners 20dp，即不通明白胶囊）
 * ```
 * → **注册完就撒手**，让 COUI 自己按设备 / 主题 / 动画档决定。
 */
object CouiMaterial {

    private const val TAG = "FkModule"

    private const val MATERIAL_STATE_MANAGER = "com.coui.appcompat.COUIMaterialStateManager"

    // flags 位（从 onMaterialStateChange(flag, enabled) 反推）
    const val FLAG_SPOTLIGHT = 1
    const val FLAG_BLUR = 2
    const val FLAG_STROKE = 4
    const val FLAG_CAUSTIC_SHADOW = 8
    const val FLAG_UNKNOWN_16 = 16

    /** OPLUS盒子 实际传的值：flags=31（0b11111，即上列全部位） */
    const val FLAGS_OPLUS_BOX =
        FLAG_SPOTLIGHT or FLAG_BLUR or FLAG_STROKE or FLAG_CAUSTIC_SHADOW or FLAG_UNKNOWN_16

    @Volatile
    private var registered = false

    /**
     * 注册材质状态观察者。**必须在造 COUI 悬浮控件之前调用**。
     *
     * @param flags 用 [FLAGS_OPLUS_BOX]（31），和 OPLUS盒子 一致
     */
    fun register(context: Context, flags: Int = FLAGS_OPLUS_BOX): Boolean {
        if (registered) return true
        val tk = runCatching { Tk.get(context) }.getOrNull() ?: return false

        // ⚠️ context 不能为 null：Kotlin 生成的签名 (Context, int) 对 null 会抛
        //    "Parameter specified as non-null is null"
        val appCtx = tk.app.applicationContext ?: tk.app

        return runCatching {
            val mgrClass = tk.base.classLoader.loadClass(MATERIAL_STATE_MANAGER)
            val inst = mgrClass.getField("INSTANCE").get(null)   // Kotlin object
            mgrClass.getMethod(
                "registerMaterialStateObserver",
                Context::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(inst, appCtx, flags)
            registered = true
            FkLog.d("System material observer: ${tk.pkg}; flags=$flags")
            true
        }.onFailure {
            FkLog.w("registerMaterialStateObserver(flags=$flags) 失败: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    fun isRegistered(): Boolean = registered

    /**
     * 只读探测：把 COUI 内部当前的材质开关打出来，便于和真机日志对照。
     * 不修改任何状态。
     *
     * 正常日志应该长这样：
     * ```
     * material probe: stroke=true blur=true spotLight=true caustic=true feature=true animationLevel=1
     * ```
     */
    fun probe(context: Context) {
        val cl = runCatching { Tk.get(context)?.base?.classLoader }.getOrNull() ?: return
        runCatching {
            val mgrClass = cl.loadClass(MATERIAL_STATE_MANAGER)
            val inst = mgrClass.getField("INSTANCE").get(null)
            val stroke = mgrClass.getMethod("isStrokeEffectEnable").invoke(inst)
            val blur = mgrClass.getMethod("isBlurEffectEnable").invoke(inst)
            val spot = mgrClass.getMethod("isSpotLightEffectEnable").invoke(inst)
            val caustic = mgrClass.getMethod("isCausticShadowEffectEnable").invoke(inst)
            val feature = mgrClass.getMethod("isFeatureEnabled").invoke(inst)
            val level = mgrClass.getMethod("getAnimationLevel").invoke(inst)
            FkLog.d(
                "material probe: stroke=$stroke blur=$blur spotLight=$spot " +
                    "caustic=$caustic feature=$feature animationLevel=$level"
            )
        }.onFailure { FkLog.w("material probe 失败: ${it.javaClass.simpleName}") }
    }
}

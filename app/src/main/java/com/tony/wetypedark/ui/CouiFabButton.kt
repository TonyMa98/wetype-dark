package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import java.lang.reflect.Proxy

/**
 * COUI 原生彩色圆形按钮（用作工具栏右上角的「重启」入口）。
 *
 * 移植自 `Color OS17/samples/kotlin/03_CouiFab.kt` + `11_ColorFabPalette.kt`。
 *
 * ## 颜色
 *
 * 取自 `11_ColorFabPalette.kt` 的实测锚点：
 * ```
 * CORAL #EE4235   珊瑚红   ← 录音 com.coloros.soundrecorder 实测
 * ```
 * 该表是「4 个系统 App 实测色 + 按 COUI 规律（明度 L 恒定 50~57%）反推」得到，
 * 不是随手挑的红。
 *
 * ## 尺寸：mButtonSize，不是 LayoutParams
 *
 * `COUIFloatingButton` 构造里读 `R.styleable.COUIFloatingButton_fabButtonSize` 存进
 * `mButtonSize`，`onMeasure` 直接用它，**LayoutParams 会被覆盖**。
 * → 必须反射写 int 字段 `mButtonSize`（单位 px）。
 *
 * 本项目要求 40dp（系统 FAB 默认 56dp）。
 *
 * ## 上色顺序不能反
 *
 * ```
 * ✅ 先 setMainFabDrawable / 后 setMainFloatingButtonBackgroundColor
 * ```
 * `applyMainFabBackgroundColor()` 里判断「背景是否已经是 ColorDrawable」，
 * 是就直接 return；先设色后设图标会被图标那一步重置掉。
 *
 * ## ⚠️ 图标为什么不用 COUI 内置的
 *
 * 实测把 `mButtonSize` 从 56dp 改成 40dp 后，COUI 内部那个主图标
 * （`createMainFab()` 造的 AppCompatImageView）仍按 56dp 的缩放/位移摆，
 * 结果**偏到右下角**。尝试改它的 layoutParams 又把它推到了左上角
 * —— 位置由 COUI 内部 `animateNormal()` / `updateMainFabPosition()` 之类的
 * 私有逻辑控制，外部插手必然打架。
 *
 * 所以这里改成：**只让 COUI 负责红色圆底与阴影**，图标由外层 [FrameLayout]
 * 叠一个居中的 ImageView。尺寸是确定的（40dp 容器 + 24dp 图标），不依赖任何内部状态。
 *
 * ## ⚠️ 点击必须走 COUI 的 listener
 *
 * `createMainFab()` 造的内部 `AppCompatImageView` 是 `setClickable(true)`，而且
 * `setFloatingButtonExpandEnable()` 会给它挂 OnClickListener —— 落在圆钮上的 touch
 * 全被它吃掉。只在外层 [FrameLayout] 上挂 `setOnClickListener` 的话，
 * **按下有波纹但回调永远不触发**（右上角红钮"点了没反应"就是这么来的）。
 *
 * 正确做法：`setFloatingButtonClickListener(OnFloatingButtonClickListener 代理)`。
 */
object CouiFabButton {

    private const val FAB_CLASS = "com.coui.appcompat.floatingactionbutton.COUIFloatingButton"
    private const val LISTENER_CLASS =
        "com.coui.appcompat.floatingactionbutton.COUIFloatingButton\$OnFloatingButtonClickListener"

    /** `11_ColorFabPalette.kt` 的 CORAL —— 录音 App 实测 #EE4235 */
    private const val COLOR_CORAL = 0xFFEE4235.toInt()

    /** 默认直径：本项目工具栏要求 40dp（系统 FAB 默认 56dp，不要用） */
    const val DEFAULT_SIZE_DP = 40

    /** 图标尺寸。40dp 圆钮配 20dp 图标（比例参照系统 56dp→24dp ≈ 0.43，这里略收） */
    private const val ICON_DP = 20

    /**
     * 造一个彩色圆形按钮（外层容器，点击事件挂在整个容器上）。
     *
     * @param icon   叠在圆底上的图标，建议白色填充
     * @param color  背景色，默认 [COLOR_CORAL]
     * @param sizeDp 直径，**尺寸走 mButtonSize 反射**
     * @return null 表示系统没有 COUIFloatingButton，调用方应降级
     */
    fun build(
        context: Context,
        icon: Drawable?,
        onClick: () -> Unit,
        color: Int = COLOR_CORAL,
        sizeDp: Int = DEFAULT_SIZE_DP,
        iconDp: Int = ICON_DP,
    ): View? {
        val tk = runCatching { Tk.get(context) }.getOrNull() ?: return null
        if (!tk.hasClass(FAB_CLASS)) {
            FkLog.w("COUIFloatingButton 不存在，走降级分支")
            return null
        }

        return runCatching {
            // 彩色圆底吃材质开关
            CouiMaterial.register(context)

            val cls = tk.base.classLoader.loadClass(FAB_CLASS)
            val fab = cls.getConstructor(Context::class.java).newInstance(tk.themed) as View

            val dm = context.resources.displayMetrics
            val sizePx = (sizeDp * dm.density).toInt()

            // ① 尺寸：反射写 mButtonSize（px）
            runCatching {
                cls.getDeclaredField("mButtonSize").apply { isAccessible = true }
                    .setInt(fab, sizePx)
            }.onFailure {
                FkLog.w("mButtonSize 字段不可写，尺寸会走默认值: ${it.javaClass.simpleName}")
            }

            // ② 上色（不传图标，图标由外层叠）
            runCatching {
                cls.getMethod(
                    "setMainFloatingButtonBackgroundColor", ColorStateList::class.java
                ).invoke(fab, ColorStateList.valueOf(color))
            }.onFailure {
                FkLog.w("setMainFloatingButtonBackgroundColor 失败: ${it.javaClass.simpleName}")
            }

            fab.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)

            // ③ 外层容器：圆底 + 居中图标
            val holder = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            }
            holder.addView(fab, FrameLayout.LayoutParams(sizePx, sizePx))

            if (icon != null) {
                val iv = ImageView(context).apply {
                    setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    isClickable = false
                    isFocusable = false
                }
                val iconPx = (iconDp * dm.density).toInt()
                holder.addView(
                    iv,
                    FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER).apply {
                        // 圆底在 40dp 容器里，视觉重心略偏上一点更顺眼，这里严格居中
                        gravity = Gravity.CENTER
                    }
                )
            }

            // ④ 点击：必须走 COUI 自己的 listener，不能只挂外层容器。
            //
            // COUI 在 init() 里 `createMainFab()` 造的那个内部 AppCompatImageView
            // 是 `setClickable(true)`（`COUIFloatingButton.java:483`），并且
            // `setFloatingButtonExpandEnable()` 给它挂了 OnClickListener
            // （`COUIFloatingButton.java:1402`，里面回调 mFloatingButtonClickListener）。
            // 它是 FAB 的子 View，touch 到它就被消费掉了 —— 外层 holder 的
            // onClickListener **永远收不到**（现象：按下去有波纹、什么都不发生）。
            //
            // 所以把我们的回调塞进 `setFloatingButtonClickListener`
            // （接口 OnFloatingButtonClickListener，唯一方法 onClick()）。
            // holder 上的监听保留，作为"点在阴影/边缘"时的兜底。
            val wiredByCoui = runCatching {
                val listenerCls = tk.base.classLoader.loadClass(LISTENER_CLASS)
                val proxy = Proxy.newProxyInstance(
                    listenerCls.classLoader, arrayOf(listenerCls)
                ) { _, method, _ ->
                    when (method.name) {
                        "onClick" -> { runCatching { onClick() }; null }
                        "toString" -> "FkFabClick"
                        "hashCode" -> 0
                        "equals" -> false
                        else -> null
                    }
                }
                cls.getMethod("setFloatingButtonClickListener", listenerCls)
                    .invoke(fab, proxy)
                true
            }.onFailure {
                FkLog.w("setFloatingButtonClickListener 失败: ${it.javaClass.simpleName}")
            }.getOrDefault(false)

            holder.isClickable = true
            holder.setOnClickListener { onClick() }
            FkLog.d("FAB 点击：couiListener=$wiredByCoui holder=true")

            holder.post {
                FkLog.d(
                    "FAB 实测 ${fab.width}x${fab.height}px holder=${holder.width}x${holder.height}px " +
                        "color=#%06X".format(color and 0xFFFFFF)
                )
            }

            FkLog.d("FAB 就绪: ${cls.name} size=${sizeDp}dp")
            holder
        }.onFailure {
            FkLog.w("COUIFloatingButton 构建失败: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }
}

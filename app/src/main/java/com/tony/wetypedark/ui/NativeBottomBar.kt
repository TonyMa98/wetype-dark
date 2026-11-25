package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.lang.reflect.Proxy

/**
 * COUI 原生浮动底栏（胶囊 tab）。
 *
 * 移植自 `Color OS17/samples/kotlin/01_NativeBottomBar.kt`，改成纯 View 版（原版是 Compose）。
 *
 * ## 调用顺序（严格照抄，不能换序）
 *
 * ```
 * 1. new COUIBottomFloatingNavigationBar(tk.themed)
 * 2. 每个 tab 用 Item$Builder 造：setId / setTitle / setDescription / setIcon / build
 * 3. addItems(List)                     ← 必须先加 item
 * 4. setItemsTitleTextColor(ColorStateList)
 * 5. setMenuViewCentered(true)
 * 6. setSelectedItemId(id)              ← 必须在 addItems 之后
 * 7. setOnItemSelectedListener(proxy)
 * 8. post { 给每个 item 的 mIcon 设 imageTintList }
 * ```
 *
 * ## 选中态着色的真相（整个项目最大的坑）
 *
 * **文字**：`COUIBottomFloatingToolbarItemView.updateTextColor()` 如果有
 * `mCustomTitleTextColor` 就用它（优先级最高），否则回退默认。
 * 所以 `setItemsTitleTextColor(csl)` 一次就够，**之后不要**再反射改 `mTitle`，
 * 否则会把 COUI 的选中态着色盖掉。
 *
 * **图标**：`setIcon(Drawable)` 内部只有 `mIcon.setImageDrawable(d)`，**不染色**。
 * 图标颜色完全由 drawable 自身决定，不会随选中态变 → 必须自己设 `imageTintList`。
 *
 * ## ColorStateList 顺序铁律
 *
 * ColorStateList 是「第一个匹配的行生效」：
 * ```
 * ✅ [state_selected] ← 必须最前
 *    [state_pressed]
 *    []              ← default，必须最后
 *
 * ❌ [state_enabled] ← 所有 View 的 drawableState 都带它，
 *                      放第一行会导致每一行都命中，选中色永远轮不到
 * ```
 */
object NativeBottomBar {

    /**
     * **本 App 的** Context，用来取 `R.drawable.ic_tab_*`。
     *
     * ⚠️ 千万别用 `bar.context` 取图标：它是 COUI 的 `ContextThemeWrapper`，
     * 资源表指向宿主 `com.android.settings`，用它 `getDrawable(本App的 id)`
     * 会**返回 null** 而不是抛异常，静默不生效。这个坑踩过一次，
     * 表现是「日志说换了图，画面没变」。
     */
    private var appContext: android.content.Context? = null


    /**
     * tab 定义：id 必须唯一且 ≥ 1。
     *
     * [iconRes] / [iconResSelected] 是**两张不同的图**（描边粗细不同），
     * 不是同一张图染两个色 —— 用户提供的 sun1/sun2、set1/set 就是两套。
     */
    class Item(
        val id: Int,
        val title: String,
        val iconRes: Int,
        val iconResSelected: Int = iconRes,
    )

    // ── 颜色兜底值（真机实测，正常会用 tk.color() 覆盖）──
    /** couiColorLabelPrimary —— 选中态，实测 #E6000000 */
    private const val COLOR_SELECTED = 0xE6000000.toInt()
    /** couiColorLabelSecondary —— 未选中，实测 #8A000000 */
    private const val COLOR_UNSELECTED = 0x8A000000.toInt()

    /** 底部 tab 标题：选中态（couiColorLabelPrimary） */
    private const val TITLE_SELECTED = 0xE6000000.toInt()

    /** 底部 tab 标题：未选中态（couiColorLabelSecondary） */
    private const val TITLE_UNSELECTED = 0x8A000000.toInt()

    // Android 框架 state 常量
    private const val STATE_SELECTED = 0x10100a1
    private const val STATE_PRESSED = 0x10100a7

    /**
     * 构建底栏。返回 null 表示当前系统没有这个 COUI 控件，调用方应降级。
     *
     * @param onSelect 点击回调，参数是 tab 的 **id**（不是下标）
     */
    fun build(
        context: Context,
        items: List<Item>,
        selectedId: Int,
        onSelect: (Int) -> Unit,
    ): View? {
        appContext = context.applicationContext
        val tk = runCatching { Tk.get(context) }.getOrNull() ?: return null
        if (!tk.hasClass(Tk.FLOATING_BAR)) {
            FkLog.w("COUIBottomFloatingNavigationBar 不存在，走降级分支")
            return null
        }

        return runCatching {
            // ★ 必须最先做：注册材质观察者，否则磨砂/白描边/高光/焦散阴影全部是 false，
            //   只能拿到「纯白胶囊 + 普通阴影」的降级样式。见 CouiMaterial 文件头。
            CouiMaterial.register(context)

            val cl = tk.base.classLoader
            val barClass = cl.loadClass(Tk.FLOATING_BAR)
            val builderCls = cl.loadClass(Tk.FLOATING_BAR_ITEM)
            val listenerCls = cl.loadClass("${Tk.FLOATING_BAR}\$OnItemSelectedListener")

            // ① 构造。传 tk.themed（带 Theme.COUI.Main 的 Context）
            val bar = barClass.getConstructor(Context::class.java)
                .newInstance(tk.themed) as View

            // ② 颜色
            val selected = tk.color("couiColorLabelPrimary").takeIf { it != 0 } ?: COLOR_SELECTED
            val unselected = tk.color("couiColorLabelSecondary").takeIf { it != 0 } ?: COLOR_UNSELECTED

            // ③ 造 item —— setId/setTitle/setIcon 全在 $Builder 上，不在 item 本身
            val built: List<Any> = items.map { item ->
                val b = builderCls.getConstructor().newInstance()
                builderCls.getMethod("setId", Integer.TYPE).invoke(b, item.id)
                builderCls.getMethod("setTitle", String::class.java).invoke(b, item.title)
                builderCls.getMethod("setDescription", CharSequence::class.java)
                    .invoke(b, item.title)
                // 图标用本 App 的 drawable（自带的太阳/齿轮），用 app resources 取
                runCatching { context.getDrawable(item.iconRes) }.getOrNull()?.let { icon ->
                    builderCls.getMethod("setIcon", Drawable::class.java).invoke(b, icon)
                }
                builderCls.getMethod("build").invoke(b)
            }

            // ④ 严格同序
            barClass.getMethod("addItems", List::class.java).invoke(bar, built)

            val titleCsl = ColorStateList(
                arrayOf(intArrayOf(STATE_SELECTED), intArrayOf()),
                intArrayOf(selected, unselected),
            )
            barClass.getMethod("setItemsTitleTextColor", ColorStateList::class.java)
                .invoke(bar, titleCsl)

            barClass.getMethod("setMenuViewCentered", Boolean::class.javaPrimitiveType)
                .invoke(bar, true)

            barClass.getMethod("setSelectedItemId", Integer.TYPE).invoke(bar, selectedId)

            // ⑤ 点击回调：COUI 的 OnItemSelectedListener 是接口，用动态代理。
            //    post 一次 syncIcons —— 那时 COUI 已经把选中态改完，
            //    两套描边图标才能换成对应的那张。
            runCatching {
                val proxy = Proxy.newProxyInstance(
                    listenerCls.classLoader,
                    arrayOf(listenerCls),
                    ItemListener { id ->
                        bar.post { syncIcons(bar, items) }
                        onSelect(id)
                    },
                )
                barClass.getMethod("setOnItemSelectedListener", listenerCls)
                    .invoke(bar, proxy)
            }.onFailure { FkLog.w("setOnItemSelectedListener 失败: ${it.javaClass.simpleName}") }

            // ⑥ 图标着色 + 绑定两套图标 + 材质探测（都必须布局完成后）
            bar.post {
                bindItemIcons(bar, items)
                // 再等一帧：COUI 内部 setSelectedItemId 的选中态可能晚于这次 layout
                bar.post { syncIcons(bar, items) }
                CouiMaterial.probe(context)
            }

            // ⑦ 阴影留白。
            //    COUI 的胶囊会往外投「焦散阴影」，模糊半径远超 88dp 容器内的 16dp 余量，
            //    直接塞进 FrameLayout 会被容器底边硬裁出一道横线。
            //    → 外层套一个 FrameLayout，只负责给上方/下方各留 [SHADOW_PAD_DP]，
            //      并把整条链的 clipChildren / clipToPadding 关掉。
            val dm = context.resources.displayMetrics
            val pad = (SHADOW_PAD_DP * dm.density).toInt()
            val holder = android.widget.FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                setPadding(0, pad, 0, 0)   // 只留上方；下方由调用方的 bottomMargin 负责
            }
            holder.addView(bar, android.widget.FrameLayout.LayoutParams(-1, -2))

            bar.post { dumpTree(bar, 0) }
            FkLog.d("底栏就绪: ${barClass.name} shadowPad=${SHADOW_PAD_DP}dp")
            holder
        }.onFailure {
            FkLog.w("构建底栏失败: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /**
     * 给每个 item 的图标挂两态 tint（只处理**颜色**）。
     *
     * ⚠️ 光靠这个不够：本项目用的是两套不同描边的图标，
     * 颜色 tint 只是把两张都染成同一个色，看不出选中。真正的切换见 [bindItemIcons]。
     */
    private fun tintIcons(bar: View, selected: Int, unselected: Int) {
        runCatching {
            val menu = menuViewOf(bar) ?: return

            // ★ 顺序铁律：selected 最前、default 最后
            val tint = ColorStateList(
                arrayOf(
                    intArrayOf(STATE_SELECTED),
                    intArrayOf(STATE_PRESSED),
                    intArrayOf(),
                ),
                intArrayOf(selected, selected, unselected),
            )

            var n = 0
            for (i in 0 until menu.childCount) {
                val child = menu.getChildAt(i)
                runCatching {
                    iconOf(child)?.imageTintList = tint
                    n++
                }.onFailure { FkLog.w("第 $i 个 item 着色失败: ${it.javaClass.simpleName}") }
            }
            FkLog.d("图标着色完成，共 $n 项")
        }.onFailure { FkLog.w("tintIcons 失败: ${it.javaClass.simpleName}") }
    }

    /** 诊断：打印底栏内部 View 树，确认灰方块是谁的背景 */
    private fun dumpTree(v: View, depth: Int) {
        if (depth > 4) return
        val pad = "  ".repeat(depth)
        val bg = v.background?.javaClass?.simpleName ?: "-"
        FkLog.d(
            "tree $pad${v.javaClass.simpleName} " +
                "${v.width}x${v.height}@${v.left},${v.top} bg=$bg alpha=${v.alpha}"
        )
        if (v is ViewGroup) for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1)
    }

    private fun menuViewOf(bar: View): ViewGroup? = runCatching {
        val f = bar.javaClass.getDeclaredField("mMenuView").apply { isAccessible = true }
        f.get(bar) as? ViewGroup
    }.getOrNull()

    private fun iconOf(itemView: View): ImageView? = runCatching {
        val f = itemView.javaClass.getDeclaredField("mIcon")
            .apply { isAccessible = true }
        f.get(itemView) as? ImageView
    }.getOrNull()

    /**
     * 把「选中/未选」两套图标绑到每个 item 上。
     *
     * ## 为什么必须自己做
     *
     * COUI 的 `setIcon(Drawable)` 内部只有 `mIcon.setImageDrawable(d)`，
     * 它只认识**一张**图，选中时不会换图（也不会染色）。
     * 用户给的是 sun1/sun2、set1/set 两套**描边粗细不同**的矢量，
     * 光靠 tint 把两张都染成同一个色，看上去就是「两个都没选中」。
     *
     * → 在 `setSelectedItemId` 之后手动按 drawableState 换 drawable。
     *
     * ## 什么时候换
     *
     * 1. 初始：按 selectedId 摆一遍；
     * 2. 点击：COUI 内部改完选中态后会 refreshDrawableState，
     *    我们挂一个 `OnItemSelectedListener` 里 post 一次同步（读到的已是新状态）。
     */
    private fun bindItemIcons(bar: View, items: List<Item>) {
        val menu = menuViewOf(bar) ?: return
        val ctx = bar.context
        runCatching {
            for (i in 0 until menu.childCount) {
                val child = menu.getChildAt(i)
                val icon = iconOf(child) ?: continue
                val item = items.getOrNull(i) ?: continue
                syncOne(ctx, icon, item, child)
            }
        }.onFailure { FkLog.w("bindItemIcons 失败: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * 单个 item 的图标与文字按当前 drawableState 摆正。
     *
     * ## ⚠️ 必须同时改 mIconResId，否则会被打回原形
     *
     * 反汇编 `COUIBottomFloatingToolbarItemView` 得到的调用链：
     * ```
     * refreshThemeAppearance()
     *   ├─ mMaskRippleDrawable.refresh(context)
     *   ├─ updateTextColor()
     *   ├─ rebindIconDrawableToCurrentTheme()
     *   │     └─ loadIconFromResourceId(mIconResId)     ← 用字段里的 id 重新加载图标
     *   └─ invalidate()
     * ```
     * 也就是说 COUI 记着 `mIconResId` 这个 int 字段，任何主题/状态刷新都会
     * 拿它把图标重新贴回去。**只调 setImageDrawable 不改字段，下次刷新就白改**。
     *
     * → 换图时必须同时把 [ICON_RES_ID_FIELD] 写成新的资源 id。
     */
    private fun syncOne(
        context: android.content.Context,
        icon: ImageView,
        item: Item,
        itemView: View,
    ) {
        val selectedNow = itemView.isSelected ||
            itemView.drawableState.any { it == STATE_SELECTED } ||
            (itemView.parent as? View)?.isSelected == true

        val res = if (selectedNow) item.iconResSelected else item.iconRes

        // ① 改 COUI 记着的那份 id —— 否则 refreshThemeAppearance 会把图标还原
        var idWritten = false
        runCatching {
            val f = itemView.javaClass.getDeclaredField(ICON_RES_ID_FIELD)
                .apply { isAccessible = true }
            f.setInt(itemView, res)
            idWritten = true
        }.onFailure { e: Throwable ->
            FkLog.w("${ICON_RES_ID_FIELD} 不可写: ${e.javaClass.simpleName}")
        }

        // ② 立即生效。必须用**本 App** 的 context 取图（宿主 context 取不到会返回 null）
        val app = appContext ?: return
        val d = runCatching { androidx.core.content.ContextCompat.getDrawable(app, res) }.getOrNull()
        if (d == null) {
            FkLog.w("取图标失败 res=0x${res.toString(16)}")
        } else {
            icon.setImageDrawable(d)
        }
        // COUI 的 mIcon 是 COUIFadeMaskImageView，遮罩随选中态开关
        runCatching {
            val m = icon.javaClass.getMethod("setShowMask", Boolean::class.javaPrimitiveType)
            m.invoke(icon, selectedNow)
        }

        // ③ 文字颜色
        runCatching {
            val f = itemView.javaClass.getDeclaredField("mTitle").apply { isAccessible = true }
            (f.get(itemView) as? android.widget.TextView)?.setTextColor(
                if (selectedNow) TITLE_SELECTED else TITLE_UNSELECTED
            )
        }.onFailure { e: Throwable ->
            FkLog.w("mTitle 着色失败: ${e.javaClass.simpleName}")
        }

        FkLog.d(
            "item ${item.title} -> ${if (selectedNow) "选中" else "未选"} " +
                "res=0x${res.toString(16)} idWritten=$idWritten"
        )
    }

    /**
     * 阴影留白（dp）。
     *
     * 胶囊外框 88dp 里，胶囊本身 56dp 上下居中 → 上下各只有 16dp 余量，
     * 而 COUI 焦散阴影的模糊半径约 24~32dp，需要 ≥ 28dp 才不会看出被切平的边。
     */
    private const val SHADOW_PAD_DP = 8

    /** COUI 记图标资源 id 的私有字段名 */
    private const val ICON_RES_ID_FIELD = "mIconResId"

    /**
     * 按当前状态刷新所有 item 的图标。
     *
     * 点击回调里 post 一次调用即可：那时 COUI 已把选中态改完。
     */
    fun syncIcons(bar: View, items: List<Item>) {
        val menu = menuViewOf(bar) ?: return
        val ctx = bar.context
        FkLog.d("syncIcons: menu.childCount=${menu.childCount} items=${items.size} menuClass=${menu.javaClass.name}")
        for (i in 0 until menu.childCount) {
            FkLog.d("  child[$i] class=${menu.getChildAt(i).javaClass.name} " +
                "selected=${menu.getChildAt(i).isSelected}")
        }
        for (i in 0 until menu.childCount) {
            val child = menu.getChildAt(i)
            val icon = iconOf(child) ?: continue
            val item = items.getOrNull(i) ?: continue
            syncOne(ctx, icon, item, child)
        }
    }

    /**
     * 外部状态变化时同步选中态。
     *
     * `MenuView.setSelectedItem` 开头是 `if (mSelectedItemId == id) return;`，
     * 所以重复设同一个 id 是无害的。
     */
    fun applySelection(bar: View, id: Int) {
        runCatching {
            bar.javaClass.getMethod("setSelectedItemId", Integer.TYPE).invoke(bar, id)
        }.onFailure { FkLog.w("applySelection($id) 失败") }
    }

    /** 底栏应预留的高度（px） */
    fun height(context: Context): Float =
        runCatching { Tk.get(context)?.barHeight() }.getOrNull() ?: 0f

    /** OnItemSelectedListener 的动态代理 */
    private class ItemListener(
        private val onSelect: (Int) -> Unit,
    ) : java.lang.reflect.InvocationHandler {
        override fun invoke(
            proxy: Any?,
            method: java.lang.reflect.Method,
            args: Array<out Any?>?,
        ): Any? = when (method.name) {
            // 签名：(View, COUIBottomFloatingNavigationBarItem, int)
            "onNavigationItemSelected" -> {
                val id = args?.firstNotNullOfOrNull { arg ->
                    arg?.let {
                        runCatching { it.javaClass.getMethod("getId").invoke(it) as Int }.getOrNull()
                    }
                }
                if (id != null) onSelect(id)
                Unit
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            "toString" -> "CouiBarListener"
            else -> null
        }
    }
}

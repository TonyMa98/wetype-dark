package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * COUI 浮动底部导航栏（手写实现，不依赖 COUI 运行时）。
 *
 * ## 设计依据
 *
 * 数值来自 `Color OS17/docs/COUI_控件清单.md` 第五章 5.1 与
 * `Color OS17/preview/index.html` 第一章「底部导航」：
 *
 * | 项 | 值 |
 * |---|---|
 * | 胶囊高 | 56dp |
 * | 单项宽（最大） | 70dp |
 * | 项间距 | 8dp |
 * | 胶囊内 padding | 12dp |
 * | 盒高（含上下留白） | 88dp |
 * | 离屏底 | 8dp（由调用方设置 bottomMargin） |
 * | 圆角 | 28dp（= 高的一半，纯 stadium） |
 * | 图标 | 24dp |
 * | 文字 | 10sp |
 *
 * ## 观感（重要）
 *
 * 参考包明确记录：本机 ColorOS 的底栏真实观感是
 * **「不透明白胶囊 + 大阴影 + 无描边」**，而不是磨砂玻璃。
 * 所以这里**不做 backdrop blur、不画 rim 描边、不做焦散/高光层**——
 * 那些是 COUI 材质开关全开时的效果，开了反而不像系统。
 *
 * 阴影用 elevation + outlineProvider 交给系统渲染（真·RenderThread 阴影），
 * 比自己 drawBitmap 的假阴影更接近 ColorOS 的观感，代码也少得多。
 *
 * ## 交互
 *
 * - 点击切换 tab，选中图标做一次放大回弹、文字淡入
 * - 按下时整条胶囊轻微下沉（scale 0.97），抬手回弹
 * - 切换时内容页转场由调用方负责（[onTabSelected]）
 *
 * 旧实现里的弹簧物理、拖拽拉伸、熔球、跟随手指的点光源高光已全部移除：
 * 收益远小于维护成本，且部分参数是逆向所得，真机上行为不稳定。
 */
class CouiFloatingBar(context: Context) : ViewGroup(context) {

    /**
     * 一项 tab：图标 + 文字。
     *
     * ColorOS 17 的选中态和未选态是**两套不同描边粗细的图标**（不是同一张图染色）：
     * 未选是细线稿，选中是实心/粗线稿。所以两个资源位都要。
     */
    class Item(
        val iconRes: Int,
        val label: String,
        val iconResSelected: Int = iconRes,
    )

    private val items = listOf(
        Item(R.drawable.ic_tab_sun, "应用", R.drawable.ic_tab_sun_selected),
        Item(R.drawable.ic_tab_set, "设置", R.drawable.ic_tab_set_selected),
    )

    var selected: Int = 0
        set(value) {
            val v = value.coerceIn(0, items.size - 1)
            if (field == v) return
            field = v
            refreshItems(animate = true)
        }

    var onTabSelected: ((Int) -> Unit)? = null

    // ---- 尺寸（px，构造时按 density 换算）----
    private val density = resources.displayMetrics.density
    private val capsuleHeight = CouiBarSpec.BarHeight * density
    private val itemMaxWidth = CouiBarSpec.ItemMaxWidth * density
    private val itemSpacing = CouiBarSpec.ItemSpacing * density
    private val innerPad = CouiBarSpec.ItemInnerPadding * density

    /** 胶囊本体：只负责一块圆角实色 + 系统阴影 */
    private val capsule = CapsuleView(context)

    private val tabs = ArrayList<LinearLayout>(items.size)
    private val icons = ArrayList<ImageView>(items.size)
    private val labels = ArrayList<TextView>(items.size)

    init {
        setWillNotDraw(false)
        clipToPadding = false

        capsule.elevation = CouiBarSpec.Elevation * density
        capsule.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = CouiBarSpec.CornerRadius * density
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        addView(capsule, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val iconPx = (CouiBarSpec.IconSize * density).toInt()
        val gapPx = (CouiBarSpec.IconTitleGapVertical * density).toInt()

        for (i in items.indices) {
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
            }
            val label = TextView(context).apply {
                text = items[i].label
                textSize = CouiBarSpec.LabelFontSize
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { select(i) }
                addView(icon)
                addView(label, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = gapPx })
            }
            // elevation 会让 View 被提到兄弟节点之上合成 —— capsule 设了 elevation，
            // 若 tab 不设，图标文字会被胶囊的实色底整个盖住（实测：底栏一片空白）。
            // 这里给 tab 一个更高的 Z，确保它们画在胶囊之后。
            tab.elevation = CouiBarSpec.Elevation * density + 1f
            tabs.add(tab)
            icons.add(icon)
            labels.add(label)
            addView(tab, LayoutParams(0, LayoutParams.MATCH_PARENT))
        }

        refreshItems(animate = false)
    }

    // ---------------------------------------------------------------- 布局

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = items.size
        // 宽度 = n×项宽 + (n-1)×间距 + 左右各 innerPad
        val wantW = (itemMaxWidth * n + itemSpacing * (n - 1) + innerPad * 2).toInt()
        val wantH = (CouiBarSpec.MinHeightWithoutPaddingVertical * density).toInt()

        val width = resolveSize(wantW, widthMeasureSpec)
        val height = resolveSize(wantH, heightMeasureSpec)
        setMeasuredDimension(width, height)

        // 胶囊在 88dp 盒子里垂直居中
        val capH = capsuleHeight.toInt()
        val capTop = (height - capH) / 2
        capsule.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(capH, MeasureSpec.EXACTLY)
        )
        capsule.layout(0, capTop, width, capTop + capH)

        val each = ((width - innerPad * 2 - itemSpacing * (n - 1)) / n).toInt()
        for (i in 0 until n) {
            val left = (innerPad + i * (each + itemSpacing)).toInt()
            tabs[i].measure(
                MeasureSpec.makeMeasureSpec(each, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(capH, MeasureSpec.EXACTLY)
            )
            tabs[i].layout(left, capTop, left + each, capTop + capH)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // 尺寸固定，布局在 onMeasure 里一次算完
    }

    // ---------------------------------------------------------------- 状态

    private fun select(index: Int) {
        if (index == selected) return
        selected = index
        onTabSelected?.invoke(index)
    }

    private fun refreshItems(animate: Boolean) {
        val labelSel = ContextCompat.getColor(context, R.color.settings_text_main)
        val labelNorm = ContextCompat.getColor(context, R.color.settings_text_sub)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        for (i in items.indices) {
            val on = i == selected
            val icon = icons[i]
            val label = labels[i]

            icon.setImageResource(if (on) items[i].iconResSelected else items[i].iconRes)
            label.setTextColor(if (on) labelSel else labelNorm)
            label.typeface = medium

            if (animate) {
                // 选中图标：从略小放大回弹，制造"浮起来"的手感
                icon.animate().cancel()
                icon.scaleX = if (on) 0.86f else 1f
                icon.scaleY = if (on) 0.86f else 1f
                icon.animate().scaleX(1f).scaleY(1f)
                    .setDuration(if (on) 280 else 160)
                    .setInterpolator(DecelerateInterpolator(1.8f))
                    .start()

                label.animate().cancel()
                label.alpha = if (on) 0.4f else 1f
                label.animate().alpha(1f).setDuration(if (on) 240 else 140).start()
            } else {
                icon.scaleX = 1f
                icon.scaleY = 1f
                label.alpha = 1f
            }
        }
    }

    // ---------------------------------------------------------------- 触摸

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                animate().scaleX(CouiBarSpec.PressScale).scaleY(CouiBarSpec.PressScale)
                    .setDuration(90)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().cancel()
                animate().scaleX(1f).scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(DecelerateInterpolator(2.2f))
                    .start()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 胶囊本体：一块圆角实色。
     *
     * 不做 backdrop blur（`setBackdropRenderEffect` 是隐藏 API，ColorOS 上开了反变半透明磨砂，
     * 和系统真实观感不一致）、不画描边、不做焦散/高光。
     */
    private class CapsuleView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.settings_capsule)
        }
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = CouiBarSpec.CornerRadius * resources.displayMetrics.density
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            path.reset()
            path.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.drawPath(path, fill)
        }
    }

    /** 尺寸与观感常量，单位 dp */
    internal object CouiBarSpec {
        const val BarHeight = 56f
        const val MinHeightWithoutPaddingVertical = 88f
        const val ItemMaxWidth = 70f
        const val ItemSpacing = 8f
        const val ItemInnerPadding = 12f
        const val IconSize = 24f
        const val IconTitleGapVertical = 2f
        const val LabelFontSize = 10f
        const val CornerRadius = 28f

        /** ColorOS 底栏的影子扩散明显比 Material 重，这个值需要真机对照调 */
        const val Elevation = 12f

        /** 按下时整条胶囊的缩放，参考 COUITouchMotionSpec.PressScaleMax */
        const val PressScale = 0.97f
    }
}

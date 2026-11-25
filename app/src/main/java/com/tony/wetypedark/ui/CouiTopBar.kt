package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * COUI 规范的顶部工具栏（`COUIToolbar` 的等价手写版）。
 *
 * 数值全部来自 `Color OS17/docs/COUI_控件清单.md` 第五章 5.4「系统设置二级页」，
 * 以及参考包 `preview/index.html` 第二章「顶部工具栏」的实测规格表：
 *
 * | 项 | 值 | 出处 |
 * |---|---|---|
 * | 栏高 | **52dp** | 实测 182px @3.5 |
 * | 返回按钮 | **40×40dp**，距左 **16dp**，距状态栏下沿 **6dp** | 实测 |
 * | 标题左起点 | **66dp**（= 按钮右沿 56dp + 10dp） | 实测 |
 * | 标题字号 | **20sp** | 实测 |
 * | 右侧按钮右边距 | **16dp**（overflow 9dp + offset 7dp） | 资源真值 |
 *
 * 用法：
 * ```kotlin
 * val bar = CouiTopBar(this, "下滑功能")
 * bar.setActions(listOf(R.drawable.ic_restart) to View.OnClickListener { ... })
 * ```
 *
 * 主页面（无返回）不画图标，标题直接落在 16dp —— 与页面内容左对齐（2026-11-03 用户要求改动，
 * 原实现沿用 COUI 的 66dp 图标槽位，主页没有图标时左边会空出一大块）。
 */
class CouiTopBar(
    context: Context,
    title: String,
    /** 是否显示返回箭头；主页传 false */
    showBack: Boolean = true,
    /** 返回回调，默认 finish() */
    onBack: (() -> Unit)? = null,
) : LinearLayout(context) {

    private val dp = { v: Float -> v * resources.displayMetrics.density }

    private val titleView: TextView
    private val actionRow: LinearLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 上边距由调用方负责避让状态栏；这里只管「距状态栏下沿 6dp」
        setPadding(0, dp(6f).toInt(), 0, 0)

        // ---- 返回按钮：40dp 盒子，左沿 16dp ----
        if (showBack) {
            val btn = ImageView(context).apply {
                setImageResource(R.drawable.coui_back_arrow)
                setColorFilter(ContextCompat.getColor(context, R.color.settings_text_main))
                val p = dp(10f).toInt()
                setPadding(p, p, p, p)
                contentDescription = "返回"
                isClickable = true
                isFocusable = true
                setOnClickListener { (onBack ?: { })() }
            }
            addView(btn, LayoutParams(dp(40f).toInt(), dp(40f).toInt()).apply {
                leftMargin = dp(16f).toInt()
            })
        } else {
            // 无返回时也占掉同样的左边距，让标题落点一致
            addView(View(context), LayoutParams(dp(16f).toInt(), 1))
        }

        // ---- 标题：左起点 66dp = 按钮右沿 56dp + 10dp ----
        titleView = TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.settings_text_main))
            // COUI 用 sans-serif-medium，不是 Bold —— 写 Bold 会 fallback 到默认字重
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            // 有返回时按钮右沿 = 16+40 = 56dp，标题再 +10dp = 66dp
            // 无返回时不留图标槽位，标题直接落在 16dp（与页面卡片左对齐）
            leftMargin = if (showBack) dp(10f).toInt() else 0
        })

        // ---- 右侧动作区：右边距 16dp ----
        actionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(actionRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(16f).toInt()
        })
    }

    /** 栏高固定 52dp —— 用 `CouiTopBar.height()` 取换算好的像素值 */
    fun applyBarHeight(): CouiTopBar = apply {
        layoutParams = layoutParams?.apply { height = dp(52f).toInt() }
    }

    /** 加一个右侧圆按钮（40dp 圆底 + 图标居中） */
    fun addAction(iconRes: Int, onClick: () -> Unit): CouiTopBar = apply {
        val btn = ImageView(context).apply {
            setImageResource(iconRes)
            val circle = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(ContextCompat.getColor(context, R.color.settings_accent))
            }
            background = circle
            val p = dp(9f).toInt()
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        actionRow.addView(btn, LayoutParams(dp(40f).toInt(), dp(40f).toInt()).apply {
            leftMargin = dp(8f).toInt()
        })
    }

    fun setTitle(text: String) {
        titleView.text = text
    }

    companion object {
        /** 栏高 52dp 换算成像素 */
        fun barHeight(context: Context): Int =
            (52f * context.resources.displayMetrics.density).toInt()
    }
}

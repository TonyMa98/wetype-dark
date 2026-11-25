package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 通用分段选择器（2 档及以上）：
 *
 *  - 浅灰胶囊轨道铺满可用宽度，圆角 = 高/2；
 *  - 选中档是一枚**蓝色胶囊**（左右各内缩 3dp，圆角同样 = 高/2），白字；
 *  - 未选中档是深色字，选中/未选中之间按滑块位置连续插值颜色（拖动时是渐变而非跳变）；
 *  - 换档带 KEYBOARD_TAP 触觉反馈。
 *
 * 手势规则（用户 2026-10-30 指定，保持原样）：
 *  1. DOWN 只记坐标，**不动选中态**；
 *  2. 纵向位移 > touchSlop → 判定为滚列表，本次手势作废（不切换），事件交还父容器；
 *  3. 横向位移 > touchSlop → 正常拖动，蓝胶囊跟手滑动；
 *  4. 位移始终 < touchSlop → UP 时按落点取档（纯轻点，无任何时间延迟）；
 *  5. 上下各 8dp 热区不响应（视觉尺寸不变），降低边缘误触。
 */
open class SegmentedControl(context: Context) : View(context) {

    var options: List<String> = emptyList()
        set(value) {
            field = value
            if (selection > maxIndex()) selection = maxIndex().coerceAtLeast(0)
            invalidate()
        }

    /** 当前选中档位（0..options.lastIndex） */
    var selection = 0
        protected set

    /** 换档回调（只在真正变化时触发） */
    var onSelectionChanged: ((Int) -> Unit)? = null

    // 深浅色跟随 AppTheme：底轨 / 未选中标签走 color 资源，选中态用 accent
    private val trackColor = context.getColor(R.color.settings_track)
    private val labelColor = context.getColor(R.color.settings_text_main)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = labelColor
    }
    private val argb = ArgbEvaluator()

    /** 0..max 连续位置（拖动跟手用；0.5 表示正好卡在第 1 档中间） */
    private var pos = 0f
    private var anim: ValueAnimator? = null
    private var dragging = false

    // ---- 手势判定 ----
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeInset = dp(8f)
    private var downX = 0f
    private var downY = 0f
    private var cancelled = false

    /** 拖动过程中上一次触发触觉的档位 */
    private var hapticAt = -1

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    /** 轨道高度跟随控件高度（上限 44dp）：调用方给 40dp 就画 40dp，不会上下溢出被裁 */
    private fun trackH(): Float =
        if (height <= 0) dp(44f) else minOf(dp(44f), height.toFloat())

    /** 蓝胶囊相对轨道的上下左右内缩 */
    private fun pillInset() = dp(3f)

    private fun maxIndex() = (options.size - 1).coerceAtLeast(0)

    /** 每档等分宽度 */
    private fun segW(): Float =
        (width.toFloat() / options.size.coerceAtLeast(1)).coerceAtLeast(1f)

    private fun trackTop(): Float = (height - trackH()) / 2f

    /** 蓝胶囊矩形：位置 p 时正好覆盖第 p 档（p 可以是小数，拖动时连续滑动） */
    private fun pillRect(p: Float): RectF {
        val w = segW()
        val inset = pillInset()
        val top = trackTop() + inset
        val bottom = trackTop() + trackH() - inset
        return RectF(p * w + inset, top, (p + 1f) * w - inset, bottom)
    }

    /** 外部设置选中档（不触发回调） */
    fun setSelection(i: Int, animate: Boolean) {
        selection = i.coerceIn(0, maxIndex())
        animateTo(selection.toFloat(), animate)
        invalidate()
    }

    private fun animateTo(target: Float, animate: Boolean) {
        anim?.cancel()
        if (!animate || !isAttachedToWindow) {
            pos = target
            invalidate()
            return
        }
        val from = pos
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                pos = from + (target - from) * (it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    /** 拖动：让蓝胶囊中心跟着手指走 */
    private fun follow(x: Float) {
        pos = (x / segW() - 0.5f).coerceIn(0f, maxIndex().toFloat())
        val nearest = pos.roundToInt().coerceIn(0, maxIndex())
        if (nearest != hapticAt) {
            hapticAt = nearest
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        invalidate()
    }

    /** x 落在哪一档（只算不改状态，供「抬手落档」用） */
    private fun indexAt(x: Float): Int =
        (x / segW() - 0.5f).roundToInt().coerceIn(0, maxIndex())

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val top = trackTop()
        val rr = trackH() / 2f

        // 底轨
        canvas.drawRoundRect(0f, top, w, top + trackH(), rr, rr, trackPaint)
        // 选中胶囊
        val pill = pillRect(pos)
        val pillR = pill.height() / 2f
        canvas.drawRoundRect(pill, pillR, pillR, thumbPaint)

        // 档位文字：按与选中位置的距离在「深色 ↔ 白色」之间插值
        val cy = top + trackH() / 2f
        val baseline = cy - (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
        val segW = segW()
        for (i in options.indices) {
            val t = (1f - abs(pos - i)).coerceIn(0f, 1f)
            labelPaint.color = argb.evaluate(t, labelColor, 0xFFFFFFFF.toInt()) as Int
            canvas.drawText(options[i], (i + 0.5f) * segW, baseline, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 5：边缘 8dp 不吃触摸，让列表滚动从边缘起手也能用
                if (event.y < edgeInset || event.y > height - edgeInset) return false
                anim?.cancel()
                downX = event.x
                downY = event.y
                dragging = false
                cancelled = false
                hapticAt = selection
            }

            MotionEvent.ACTION_MOVE -> {
                if (cancelled) return true
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (!dragging) {
                    // 2：纵向意图 → 本次作废，父容器继续接管（列表滚动）
                    if (dy > touchSlop && dy >= dx) {
                        cancelled = true
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                    // 3：横向意图 → 开始跟手拖动
                    if (dx > touchSlop) dragging = true
                }
                if (dragging) follow(event.x)
            }

            MotionEvent.ACTION_UP -> {
                if (cancelled) {
                    cancelled = false
                    dragging = false
                    return true
                }
                // 4：拖动按连续位置取整，轻点按落点取档
                val target = if (dragging) pos.roundToInt().coerceIn(0, maxIndex())
                else indexAt(event.x)
                dragging = false
                if (target != selection) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    selection = target
                    onSelectionChanged?.invoke(target)
                }
                animateTo(target.toFloat(), true)
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                // 被父容器（列表滚动）抢走：不落档，胶囊回原位
                cancelled = false
                dragging = false
                animateTo(selection.toFloat(), true)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val ACCENT = 0xFF0A6AFF.toInt()
    }
}

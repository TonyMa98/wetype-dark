package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

/**
 * iOS 风格开关：胶囊轨道 + 圆形滑块，选中变强调色。
 *
 * 尺寸由布局给定（推荐 46×28dp），轨道圆角恒为高/2。
 */
class ToggleSwitch(context: Context) : View(context) {

    var onCheckedChanged: ((Boolean) -> Unit)? = null

    var checked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateProgress(if (value) 1f else 0f)
        }

    private val accent = context.getColor(R.color.settings_accent)
    private val trackOff = context.getColor(R.color.settings_check_off)
    private val knobColor = 0xFFFFFFFF.toInt()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = knobColor }

    /** 0 = 关，1 = 开 */
    private var progress = 0f
    private var anim: ValueAnimator? = null
    private val rect = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun animateProgress(target: Float) {
        anim?.cancel()
        val from = progress
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                progress = from + (target - from) * (it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    /** 不带动画地同步状态（初始化 / 外部批量刷新用） */
    fun setCheckedSilently(value: Boolean) {
        anim?.cancel()
        checked = value
        progress = if (value) 1f else 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        progress = if (checked) 1f else 0f
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2f
        // 轨道颜色在「关灰」与「强调色」之间插值
        trackPaint.color = lerpColor(trackOff, accent, progress)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, trackPaint)
        // 滑块：直径 = 高 - 2*2dp，左右各留 2dp
        val pad = dp(2f)
        val kr = (h - pad * 2f) / 2f
        val cx = pad + kr + (w - pad * 2f - kr * 2f) * progress
        canvas.drawCircle(cx, h / 2f, kr, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                invalidate()
                if (event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    checked = !checked
                    onCheckedChanged?.invoke(checked)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = (from ushr 24 and 0xFF) + (((to ushr 24 and 0xFF) - (from ushr 24 and 0xFF)) * f).roundToInt()
        val r = (from ushr 16 and 0xFF) + (((to ushr 16 and 0xFF) - (from ushr 16 and 0xFF)) * f).roundToInt()
        val g = (from ushr 8 and 0xFF) + (((to ushr 8 and 0xFF) - (from ushr 8 and 0xFF)) * f).roundToInt()
        val b = (from and 0xFF) + (((to and 0xFF) - (from and 0xFF)) * f).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * 连续滑杆：细轨 + 已填充段 + 圆滑块。
 *
 * 回调分两段（照原设计的防抖约定）：[onValue] 拖动中连续回调（只更新预览），
 * [onFinished] 松手才回调（此时才落盘）。
 */
class RangeSlider(context: Context) : View(context) {

    var min = 0
    var max = 100
    var step = 1

    /** 当前值（整数，按 [step] 对齐） */
    var value = 0
        set(v) {
            val clamped = v.coerceIn(min, max)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    var onValue: ((Int) -> Unit)? = null
    var onFinished: ((Int) -> Unit)? = null

    private val accent = context.getColor(R.color.settings_accent)
    private val trackColor = context.getColor(R.color.settings_track)
    private val knobColor = 0xFFFFFFFF.toInt()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = knobColor }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private var dragging = false

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun trackTop() = height / 2f - dp(2f)
    private fun trackBottom() = height / 2f + dp(2f)
    private fun knobR() = dp(10f)

    /** 滑块左端到右端可用的横向范围 */
    private fun startX() = knobR()
    private fun endX() = width - knobR()

    private fun fraction(): Float {
        val span = (max - min).coerceAtLeast(1)
        return (value - min).toFloat() / span
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val sx = startX()
        val ex = endX()
        val x = sx + (ex - sx) * fraction()
        canvas.drawRoundRect(sx, trackTop(), ex, trackBottom(), dp(2f), dp(2f), trackPaint)
        if (x > sx) {
            canvas.drawRoundRect(sx, trackTop(), x, trackBottom(), dp(2f), dp(2f), fillPaint)
        }
        canvas.drawCircle(x, cy, knobR(), knobPaint)
        canvas.drawCircle(x, cy, knobR() - dp(1f), knobRing)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) updateFromX(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onFinished?.invoke(value)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromX(x: Float) {
        val sx = startX()
        val ex = endX()
        val f = ((x - sx) / (ex - sx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val raw = min + (max - min) * f
        val stepped = ((raw / step).roundToInt() * step).coerceIn(min, max)
        if (stepped != value) {
            value = stepped
            onValue?.invoke(stepped)
        }
    }
}

package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * 键盘预览：按当前布局画出真实键位，已绑定的键在字下方显示短标签。
 *
 * 点某个键 = 直接跳到该键的绑定编辑（[onKeyTap]），这也是原设计里
 * 「点选按键」的入口；空格 / 回车的下滑变体（`space_down`）在下方列表里单独一行。
 */
class GestureKeyboardPreview(context: Context) : View(context) {

    var layout: KeyLayout = KeyLayout.QWERTY
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var settings: GestureSettings? = null
        set(value) {
            field = value
            invalidate()
        }

    var onKeyTap: ((String) -> Unit)? = null

    private val keyBg = context.getColor(R.color.settings_tab_highlight)
    private val textColor = context.getColor(R.color.settings_text_main)
    private val accent = context.getColor(R.color.settings_accent)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = keyBg }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 0x1F) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()

    private var pressedKey: String? = null
    private var downX = 0f
    private var downY = 0f

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** 键位命中表：key → rect（每次 onDraw 重算，和绘制完全一致） */
    private val hits = HashMap<String, RectF>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = layout.rows.size
        val keyH = dp(42f)
        val gap = dp(6f)
        val h = (rows * keyH + (rows + 1) * gap).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        val rows = layout.rows
        val gap = dp(6f)
        val keyH = ((height - gap * (rows.size + 1)) / rows.size).coerceAtLeast(dp(24f))
        textPaint.textSize = dp(15f)
        labelPaint.textSize = dp(9f)

        var y = gap
        rows.forEachIndexed { rowIndex, keys ->
            val isLast = rowIndex == rows.lastIndex
            if (isLast) {
                // 最后一行：空格 + 回车，各占一半（留出左右 gap）
                val totalW = width - gap * 3
                val kw = (totalW - gap) / 2f
                var x = gap
                keys.forEach { key ->
                    drawKey(canvas, key, x, y, kw, keyH, gap)
                    x += kw + gap
                }
            } else {
                // 字母行：以最长行为基准算键宽，整行居中
                val maxLen = rows.maxOf { it.size }
                val kw = (width - gap * (maxLen + 1)) / maxLen
                val rowW = keys.size * kw + (keys.size - 1) * gap
                var x = (width - rowW) / 2f
                keys.forEach { key ->
                    drawKey(canvas, key, x, y, kw, keyH, gap)
                    x += kw + gap
                }
            }
            y += keyH + gap
        }
    }

    private fun drawKey(
        canvas: Canvas,
        key: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
    ) {
        rect.set(x, y, x + w, y + h)
        hits[key] = RectF(rect)
        bgPaint.color = if (key == pressedKey) withAlpha(accent, 0x24) else keyBg
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        if (key == pressedKey) canvas.drawRoundRect(rect, radius, radius, pressPaint)

        val action = settings?.action(layout, key) ?: GestureAction.None
        val cap = GestureKeys.capLabel(key)
        val hasLabel = action != GestureAction.None
        val cy = y + h / 2f
        if (hasLabel) {
            // 字 + 下方短标签
            textPaint.textSize = dp(14f)
            labelPaint.textSize = dp(9f)
            val capBaseline = cy - dp(3f) - (textPaint.fontMetrics.descent)
            canvas.drawText(cap, x + w / 2f, capBaseline, textPaint)
            canvas.drawText(action.short, x + w / 2f, cy + dp(10f), labelPaint)
        } else {
            textPaint.textSize = dp(14f)
            val baseline = cy - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
            canvas.drawText(cap, x + w / 2f, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressedKey = keyAt(event.x, event.y)
                invalidate()
                return pressedKey != null
            }
            MotionEvent.ACTION_MOVE -> {
                val now = keyAt(event.x, event.y)
                if (now != pressedKey) {
                    pressedKey = now
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val key = pressedKey
                pressedKey = null
                invalidate()
                if (key != null && keyAt(event.x, event.y) == key) {
                    performClick()
                    onKeyTap?.invoke(key)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
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

    private fun keyAt(x: Float, y: Float): String? =
        hits.entries.firstOrNull { it.value.contains(x, y) }?.key

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)
}

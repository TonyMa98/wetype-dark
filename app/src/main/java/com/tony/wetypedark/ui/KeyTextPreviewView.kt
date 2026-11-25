package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * 键盘预览：几何**逐键取自用户给的两个 SVG 模板**
 * （`/Users/tonyma/导出/九键.svg`、`/Users/tonyma/导出/26键.svg`，1272×1059 px @3.5x）。
 *
 * 模板里的颜色编码就是分组：
 * - 蓝色 = 可调，**只调上下**
 * - 黄色 = 不参与调整（[K.group] 为 -1）
 * - 绿色 = 标点区，**支持左右调**（只有九键左侧那一条）
 *
 * 所有坐标都已换算成 dp（`px / 3.5`），绘制时按预览宽度等比缩放，所以相对位置和真机一致。
 */
/**
 * 字重 +50（400 → 450）≈ 笔画 +1% em；真机侧由 Entry 给文字补这段描边，
 * 预览按同一比例补，两边才看起来是一档字重。
 */
private const val KEY_WEIGHT_RATIO = 0.01f

/**
 * 预览要用真机的字体，否则排版对不上。
 *
 * 真机按键文字已被模块在 `:hld` 侧统一换掉：宿主自绘键盘原来用
 * `Typeface.createFromAsset(assets, "fonts/WE-Regular.ttf")`（400 常规），
 * 现在返回系统字体 `sans-serif` 的 450 字重（常规 +50）。
 * 这个设置页跑在自己的进程里，直接按同样参数造一份，预览和键盘才一致。
 */
private object SystemKeyFont {

    @Volatile
    private var font: Typeface? = null

    fun get(): Typeface {
        font?.let { return it }
        synchronized(this) {
            font?.let { return it }
            val made = runCatching { Typeface.create(Typeface.SANS_SERIF, 400, false) }
                .getOrNull() ?: Typeface.SANS_SERIF
            font = made
            return made
        }
    }
}

class KeyTextPreview(context: Context) : View(context) {

    /**
     * 一个键。坐标与尺寸单位是 dp，取值来自 SVG 模板。
     *
     * [group] 为 -1 表示「黄色区域：不参与调整」。
     * [puncts] 非空表示竖排标点列（九键左侧绿色区域）。
     */
    private class K(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val text: String = "",
        val float: String? = null,
        val group: Int = -1,
        val puncts: List<String>? = null,
        /** 主文字字号（dp），取宿主布局 JSON 的 mainTextSize（1080 基准换算） */
        val ts: Float? = null,
        /** 角标字号（dp），取宿主布局 JSON 的 floatTextSize */
        val fs: Float? = null
    )

    /** 九键：17 个矩形，坐标取自 `九键.svg` */
    private val t9Keys = listOf(
        // 绿色：左侧标点列，跨 3 行，支持左右调（整列平移）
        K(4.0f, 50.9f, 60.9f, 151.4f, group = KeyTextTuning.GROUP_PUNCT,
            puncts = listOf("，", "。", "？", "！"), ts = T9_FUNC),
        K(70.3f, 50.9f, 70.9f, 47.7f, "@#", "1", KeyTextTuning.GROUP_T9_LETTER, ts = T9_ABC, fs = T9_FLOAT),
        K(146.3f, 50.9f, 70.9f, 47.7f, "ABC", "2", KeyTextTuning.GROUP_T9_LETTER, ts = T9_LETTER, fs = T9_FLOAT),
        K(222.3f, 50.9f, 70.9f, 47.7f, "DEF", "3", KeyTextTuning.GROUP_T9_LETTER, ts = T9_LETTER, fs = T9_FLOAT),
        K(298.3f, 50.9f, 60.9f, 47.7f, "⌫"),                       // 黄：不调
        K(70.3f, 102.3f, 70.9f, 47.7f, "GHI", "4", KeyTextTuning.GROUP_T9_LETTER, ts = T9_LETTER, fs = T9_FLOAT),
        K(146.3f, 102.3f, 70.9f, 47.7f, "JKL", "5", KeyTextTuning.GROUP_T9_LETTER, ts = T9_LETTER, fs = T9_FLOAT),
        K(222.3f, 102.3f, 70.9f, 47.7f, "MNO", "6", KeyTextTuning.GROUP_T9_LETTER, ts = T9_LETTER, fs = T9_FLOAT),
        K(298.3f, 102.3f, 60.9f, 47.7f, "换行", group = KeyTextTuning.GROUP_T9_LETTER, ts = T9_FUNC),
        K(70.3f, 154.6f, 70.9f, 47.7f, "PQRS", "7", KeyTextTuning.GROUP_T9_LETTER, ts = T9_LETTER, fs = T9_FLOAT),
        K(146.3f, 154.6f, 70.9f, 47.7f, "TUV", "8", KeyTextTuning.GROUP_T9_LETTER, ts = T9_LETTER, fs = T9_FLOAT),
        K(222.3f, 154.6f, 70.9f, 47.7f, "WXYZ", "9", KeyTextTuning.GROUP_T9_LETTER, ts = T9_LETTER, fs = T9_FLOAT),
        K(298.3f, 154.6f, 60.9f, 99.7f, "发送", group = KeyTextTuning.GROUP_T9_LETTER, ts = T9_FUNC),
        K(4.0f, 206.9f, 60.9f, 47.4f, "符号", group = KeyTextTuning.GROUP_T9_LETTER, ts = T9_FUNC),
        K(70.3f, 206.9f, 47.1f, 47.4f, "123", ts = T9_FUNC),       // 黄：不调
        K(122.9f, 206.9f, 117.7f, 47.4f, "", "0", KeyTextTuning.GROUP_T9_LETTER, fs = T9_FLOAT),
        K(246.0f, 206.9f, 47.1f, 47.4f, "中/英", ts = T9_FUNC)     // 黄：不调
    )

    /** 26 键：34 个矩形，坐标取自 `26键.svg`（没有绿色区域，全部只调上下） */
    private val qwertyKeys = buildList {
        val g = KeyTextTuning.GROUP_QWERTY_LETTER
        // 行 1：10 键
        "qwertyuiop".forEachIndexed { i, c ->
            add(K(4.3f + 36f * i, 50.9f, 30.9f, 44.6f, c.toString(), "${(i + 1) % 10}", g, ts = QW_LETTER, fs = QW_FLOAT))
        }
        // 行 2：9 键
        val floats2 = listOf("-", "/", "：", "；", "（", "）", "～", "“", "”")
        "asdfghjkl".forEachIndexed { i, c ->
            add(K(22f + 36f * i, 103.7f, 30.9f, 44.6f, c.toString(), floats2[i], g, ts = QW_LETTER, fs = QW_FLOAT))
        }
        // 行 3：⇧ + 7 键 + ⌫
        add(K(4.3f, 156.9f, 43.7f, 44.6f, "⇧"))                    // 黄：不调
        val floats3 = listOf("@", ".", "#", "、", "？", "！", "…")
        "zxcvbnm".forEachIndexed { i, c ->
            add(K(58f + 36f * i, 156.9f, 30.9f, 44.6f, c.toString(), floats3[i], g, ts = QW_LETTER, fs = QW_FLOAT))
        }
        add(K(316f, 156.9f, 43.4f, 44.6f, "⌫"))                    // 黄：不调
        // 行 4：123 + ，。 + 空格 + 中/英 + 换行
        add(K(4.3f, 210f, 67.1f, 44.6f, "123", ts = QW_FUNC))      // 黄：不调
        // dotComma 键：宿主用专门的绘制器，主字「，」偏上、角标「。」在下半（drawmethod/b.java）
        add(K(76.6f, 210f, 30.9f, 44.6f, "，", "。", g, ts = QW_LETTER, fs = QW_FUNC))
        add(K(112.6f, 210f, 130.3f, 44.6f, ""))                    // 黄：空格，不调
        add(K(247.4f, 210f, 35.7f, 44.6f, "中/英", ts = QW_FUNC))   // 黄：不调
        add(K(287.7f, 210f, 71.7f, 44.6f, "换行", group = g, ts = QW_FUNC))
    }

    private var dy = FloatArray(KeyTextTuning.GROUP_COUNT)
    private var dx = FloatArray(KeyTextTuning.GROUP_COUNT)

    private val density = resources.displayMetrics.density

    private val isDark = !resolveIsLightTheme(context)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isDark) 0x33FFFFFF else 0xFFFFFFFF.toInt()
    }

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.settings_text_main)
        textAlign = Paint.Align.CENTER
        typeface = SystemKeyFont.get()
        // 真机：宿主 F2 给文字 0.1px 描边，模块再按字号补 1%（字重 400→450）
        style = Paint.Style.FILL_AND_STROKE
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.settings_text_sub)
        textAlign = Paint.Align.RIGHT
        typeface = SystemKeyFont.get()
        // 宿主角标画笔是 FILL_AND_STROKE + 0.7px 描边（drawmethod/e.java 构造函数），
        // 少这一下字会偏细，跟真机看着不是同一款字重。
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 0.7f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.settings_accent)
        textAlign = Paint.Align.CENTER
        alpha = (0.4f * 255).toInt()
        typeface = SystemKeyFont.get()
    }

    private val rect = RectF()

    /** 0 = 九键预览，1 = 26 键预览 */
    var mode = 0
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    fun setValues(dy: FloatArray, dx: FloatArray) {
        this.dy = dy.copyOf()
        this.dx = dx.copyOf()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * PANEL_H / PANEL_W).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        // 整块面板完全透明：只画键和字，底色交给页面，看起来就是在页面上直接排版
        // dp → 预览像素：预览宽度对应整块面板宽度（363.4dp）
        val s = width / PANEL_W / density
        val keys = if (mode == 0) t9Keys else qwertyKeys
        for (key in keys) drawKey(canvas, key, s)
    }

    private fun drawKey(canvas: Canvas, key: K, s: Float) {
        // 黄色键（group = -1）不叠加任何偏移
        val offY = if (key.group >= 0) dy[key.group] else 0f
        val offX = if (key.group >= 0) dx[key.group] else 0f

        // 键面永远不动：动的是键里的**字符**（蓝色只上下、绿色标点可左右）
        rect.set(
            key.x * density * s,
            key.y * density * s,
            (key.x + key.w) * density * s,
            (key.y + key.h) * density * s
        )
        val r = CornerDp * density * s
        canvas.drawRoundRect(rect, r, r, keyPaint)

        val shiftX = offX * density * s
        val shiftY = offY * density * s

        key.puncts?.let { puncts ->
            val cellH = rect.height() / puncts.size
            letterPaint.textSize = fitTextSize("，", (key.ts ?: T9_FUNC) * density * s, rect.width())
            letterPaint.strokeWidth = 0.1f + letterPaint.textSize * KEY_WEIGHT_RATIO
            val fm = letterPaint.fontMetrics
            for ((i, p) in puncts.withIndex()) {
                val cy = rect.top + cellH * i + cellH / 2f
                val baseline = cy - (fm.descent - fm.ascent) / 2f + (fm.leading - fm.ascent) + shiftY
                canvas.drawText(p, rect.centerX() + shiftX, baseline, letterPaint)
            }
            return
        }

        // 「，」键（26 键第 4 行）在宿主里走 dotComma 绘制器，两条基线跟其它键都不同
        val isDotComma = mode == 1 && key.text == "，" && key.float != null

        if (key.text.isNotEmpty()) {
            letterPaint.textSize = fitTextSize(key.text, (key.ts ?: T9_LETTER) * density * s, rect.width())
            letterPaint.strokeWidth = 0.1f + letterPaint.textSize * KEY_WEIGHT_RATIO
            val fm = letterPaint.fontMetrics
            // 真机 drawmethod/e.java（floatType 绘制器）在「26 键且有角标」的键上，
            // 会把主文字整体下移 i10 px（默认 6，X1() 时大写 10 / 小写 8），给角标让位。
            // 预览原来没这一下，字母比真机偏高 —— 是「位置对不上」的一半原因。
            val downPx = if (mode == 1 && key.float != null && !isDotComma) 6f else 0f
            // dotComma：baseline = centerY - (descent-ascent)/3 + (leading-ascent)（b.java:53）
            val div = if (isDotComma) 3f else 2f
            val baseline = rect.centerY() - (fm.descent - fm.ascent) / div + (fm.leading - fm.ascent) +
                shiftY + downPx * s
            canvas.drawText(key.text, rect.centerX() + shiftX, baseline, letterPaint)
        }

        key.float?.let { f ->
            // 角标小数字和字母分开调：九键归「角标」组，26 键归「26 键角标」组
            // （真机侧 KeyTextTuning.groupForText 按字号判定，预览必须跟它一致）
            val g = if (mode == 0) KeyTextTuning.GROUP_NUMBER else KeyTextTuning.GROUP_QWERTY_NUMBER
            numberPaint.textSize = (key.fs ?: QW_FLOAT) * density * s
            val nfm = numberPaint.fontMetrics
            // 位置公式抄宿主 drawmethod/e.java:83（与 g.java:116 是同一表达式）：
            //   gravity == 2（九键）→ x = drawRect.right - floatTextMarginRight，Paint.Align.RIGHT
            //   gravity == 1（26 键）→ x = drawRect.centerX()，Paint.Align.CENTER
            //   y = drawRect.top + floatTextMarginTop + (fm.leading - fm.ascent)
            // 边距与字号同为 vWidth=1080 基准（8/14 → 2.69/4.71dp）。
            val nx: Float
            if (mode == 0) {
                numberPaint.textAlign = Paint.Align.RIGHT
                nx = rect.right - T9_FLOAT_MARGIN_RIGHT * density * s + dx[g] * density * s
            } else {
                numberPaint.textAlign = Paint.Align.CENTER
                nx = rect.centerX() + dx[g] * density * s
            }
            val ny = if (isDotComma) {
                // dotComma：baseline = centerY + (descent-ascent)/2 - (leading-ascent)（b.java:64）
                rect.centerY() + (nfm.descent - nfm.ascent) / 2f - (nfm.leading - nfm.ascent) +
                    dy[g] * density * s
            } else {
                rect.top + FLOAT_MARGIN_TOP * density * s +
                    (nfm.leading - nfm.ascent) + dy[g] * density * s
            }
            canvas.drawText(f, nx, ny, numberPaint)
        }

        // 手势标签示例：九键画在 JKL，26 键画在 G
        val labelOn = if (mode == 0) key.text == "JKL" else key.text == "g"
        if (labelOn) {
            labelPaint.textSize = 8f * density * s
            val lfm = labelPaint.fontMetrics
            val ly = rect.bottom - 2f * density * s - lfm.descent +
                dy[KeyTextTuning.GROUP_LABEL] * density * s
            canvas.drawText(
                "复制", rect.centerX() + dx[KeyTextTuning.GROUP_LABEL] * density * s, ly, labelPaint
            )
        }
    }

    /** 文字比键还宽时缩字号，避免溢出到相邻键（底行功能键很窄） */
    private fun fitTextSize(text: String, size: Float, maxWidth: Float): Float {
        val limit = maxWidth - 6f * density
        var s = size
        while (s > 5f * density && letterPaint.measureText(text) > limit) {
            s *= 0.92f
            letterPaint.textSize = s
        }
        return s
    }

    private companion object {
        /** 面板总宽（dp）：1272px / 3.5 */
        const val PANEL_W = 363.4f
        /** 面板总高（dp）：1059px / 3.5 */
        const val PANEL_H = 302.6f
        /** 键圆角（dp） */
        const val CornerDp = 6f
        // 以下字号全部来自宿主布局 JSON（1080 基准 px × 363.4 / 1080 = dp）
        /** 26 键字母 / `，。`（mainTextSize=60） */
        const val QW_LETTER = 20.2f
        /** 26 键功能键：换行 / 123 / 中英（mainTextSize=44） */
        const val QW_FUNC = 14.8f
        /** 26 键角标（floatTextSize=28） */
        const val QW_FLOAT = 9.4f
        /** 九键字母（mainTextSize=50） */
        const val T9_LETTER = 16.8f
        /** 九键 @#（mainTextSize=56） */
        const val T9_ABC = 18.8f
        /** 九键功能键与左侧标点列（mainTextSize=44） */
        const val T9_FUNC = 14.8f
        /** 九键角标（floatTextSize=26） */
        const val T9_FLOAT = 8.8f
        /** 角标上边距：floatTextMarginTop=8（1080 基准）→ 8/1080×363.4 ≈ 2.69dp，九键与 26 键相同 */
        const val FLOAT_MARGIN_TOP = 2.69f
        /** 九键角标右边距：floatTextMarginRight=14（1080 基准）→ 4.71dp；26 键是居中对齐，用不到 */
        const val T9_FLOAT_MARGIN_RIGHT = 4.71f
    }
}

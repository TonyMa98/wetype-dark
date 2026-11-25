package com.tony.wetypedark.ui

import com.tony.wetypedark.R

/**
 * 按键文字偏移的分组表（原工程 [KeyTextTuning] 的数据部分）。
 * 复刻版只保留设置页需要的常量与读写，不再包含 :hld 侧的绘制钩子。
 */
internal object KeyTextTuning {

    /** 九键键盘上的大写字母 */
    const val GROUP_T9_LETTER = 0

    /** 键帽角标小数字 */
    const val GROUP_NUMBER = 1

    /** 手势标签（剪切/复制/全选…） */
    const val GROUP_LABEL = 2

    /** 主面板右侧标点（可左右调） */
    const val GROUP_PUNCT = 3

    /** 26 键键盘上的字母 */
    const val GROUP_QWERTY_LETTER = 4

    /** 26 键面板上的角标小数字 */
    const val GROUP_QWERTY_NUMBER = 5

    /** 表情面板发送键的「发送」 */
    const val GROUP_EMOJI_SEND = 6

    /** S2 中文全键盘的发送/换行键 */
    const val GROUP_S2_ENTER = 7

    const val GROUP_COUNT = 8

    /** 落盘键名，下标必须与上面的分组常量一一对应 */
    val GROUP_NAMES = arrayOf(
        "t9", "num", "label", "punct", "qwerty", "qnum", "emojisend", "s2enter"
    )

    /** 纵向偏移，单位 dp，正数向下 */
    private val dy = FloatArray(GROUP_COUNT)

    /** 横向偏移，单位 dp，正数向右 */
    private val dx = FloatArray(GROUP_COUNT)

    fun load(json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            val root = org.json.JSONObject(json)
            for (i in 0 until GROUP_COUNT) {
                val arr = root.optJSONArray(GROUP_NAMES[i]) ?: continue
                dy[i] = arr.optDouble(0, 0.0).toFloat()
                dx[i] = arr.optDouble(1, 0.0).toFloat()
            }
        }
    }

    fun dyDp(group: Int): Float = dy.getOrElse(group) { 0f }

    fun dxDp(group: Int): Float = dx.getOrElse(group) { 0f }
}

package com.tony.wetypedark

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * 按键文字位置（宿主侧）。
 *
 * 分组与 App 侧 [com.tony.wetypedark.ui.KeyTextTuning] 的常量一一对应：
 * `t9 / num / label / punct / qwerty / qnum / emojisend / s2enter`，单位 dp。
 *
 * 生效方式：钩**真实 Canvas 运行时类**的 `drawText`（微信的 Canvas 是 RecordingCanvas，
 * 钩 Canvas 基类一次都不触发），只在「正在画某颗键」的窗口里按分组平移基线；
 * 九键左侧那条竖排标点列是 RecyclerView+TextView（走 drawTextRun），
 * 直接平移整块 View。
 */
object KeyTextHost {

    const val GROUP_T9_LETTER = 0
    const val GROUP_NUMBER = 1
    const val GROUP_LABEL = 2
    const val GROUP_PUNCT = 3
    const val GROUP_QWERTY_LETTER = 4
    const val GROUP_QWERTY_NUMBER = 5
    const val GROUP_EMOJI_SEND = 6
    const val GROUP_S2_ENTER = 7
    const val GROUP_COUNT = 8

    private val GROUP_NAMES = arrayOf("t9", "num", "label", "punct", "qwerty", "qnum", "emojisend", "s2enter")

    private val dy = FloatArray(GROUP_COUNT)
    private val dx = FloatArray(GROUP_COUNT)

    @Volatile private var density = 3.5f

    @Volatile private var keyboardT9 = false

    private val inKey = ThreadLocal<Boolean>()
    private val keyT9 = ThreadLocal<Boolean>()

    private val hookedCanvas = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var iface: XposedInterface? = null

    private val punctuation = "，。？！、；：\"“”‘’（）【】《》,.?!;:\"()[]<>/\\-_=+*&^%#@~`|·…—～"

    fun setIface(value: XposedInterface?) {
        iface = value
    }

    fun setDensity(value: Float) {
        if (value > 0f) density = value
    }

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

    fun dyDp(group: Int): Float = if (group in 0 until GROUP_COUNT) dy[group] else 0f
    fun dxDp(group: Int): Float = if (group in 0 until GROUP_COUNT) dx[group] else 0f

    /** 九键 / 26 键（九键标点列画在按键绘制之外，需要全局依据） */
    fun setKeyboardT9(v: Boolean) {
        keyboardT9 = v
    }

    /** 某颗键开始绘制：记下它是九键还是 26 键，并保证这块 Canvas 已挂钩 */
    fun enter(canvas: Canvas?, keyId: String?) {
        if (canvas != null) ensureCanvasHooked(canvas)
        val id = keyId.orEmpty()
        keyT9.set(id.startsWith("S1") || id.startsWith("S4")
            || id.contains("t9", true) || id.contains("nine", true))
        inKey.set(true)
    }

    fun exit() {
        inKey.remove()
        keyT9.remove()
    }

    /** 九键左侧标点列：整块 View 平移（触摸判定会跟着走） */
    fun applyPunctColumnOffset(keyboard: View) {
        val column = findPunctColumn(keyboard) ?: return
        val tx = dxDp(GROUP_PUNCT) * density
        val ty = dyDp(GROUP_PUNCT) * density
        if (column.translationX != tx) column.translationX = tx
        if (column.translationY != ty) column.translationY = ty
    }

    private fun findPunctColumn(view: View): View? {
        val name = view.javaClass.name
        if (name.contains("ImeSboAndSybKeysScrollView") || name.contains("SboAndSyb")) return view
        runCatching {
            val id = view.id
            if (id != View.NO_ID) {
                val entry = view.resources.getResourceEntryName(id)
                if (entry == "S1_recycleview") return view
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val hit = findPunctColumn(view.getChildAt(i))
                if (hit != null) return hit
            }
        }
        return null
    }

    // ------------------------------------------------------------ drawText 钩子

    private val drawTextHits = java.util.concurrent.atomic.AtomicInteger()

    /**
     * 微信的 Canvas 是 `RecordingCanvas`，但它自己**没声明** drawText（继承自 Canvas），
     * 所以要沿父类逐级往上找声明它的那一层再挂钩子。
     */
    private fun ensureCanvasHooked(canvas: Canvas) {
        var cls: Class<*>? = canvas.javaClass
        var hookedNow = 0
        while (cls != null) {
            val name = cls.name
            if (name == "android.graphics.Canvas") break
            if (hookedCanvas.add(name)) {
                hookedNow += hookDrawText(cls)
            }
            cls = cls.superclass
        }
        if (hookedNow == 0 && hookedCanvas.contains("android.graphics.Canvas")) return
        if (hookedCanvas.add("android.graphics.Canvas")) {
            runCatching {
                hookedNow += hookDrawText(Canvas::class.java)
            }
        }
        if (hookedNow > 0) {
            FkLogBridge.d("KeyTextHost: drawText hooks on ${canvas.javaClass.name} +$hookedNow")
        }
    }

    private fun hookDrawText(cls: Class<*>): Int {
        val i = iface ?: return 0
        var count = 0
        for (m in cls.declaredMethods) {
            if (m.name != "drawText") continue
            val types = m.parameterTypes
            val stringAt = types.indexOfFirst {
                it == CharSequence::class.java || it == String::class.java
            }
            if (stringAt < 0) continue
            val paintAt = types.indexOfFirst { it == android.graphics.Paint::class.java }
            if (paintAt < 0 || paintAt != types.size - 1 || paintAt < 2) continue
            val xAt = paintAt - 2
            val yAt = paintAt - 1
            if (types[xAt] != Float::class.javaPrimitiveType) continue
            if (types[yAt] != Float::class.javaPrimitiveType) continue
            runCatching {
                m.isAccessible = true
                i.hook(m).intercept { chain ->
                    shiftText(chain, stringAt, xAt, yAt)
                }
                count++
            }
        }
        return count
    }

    /** 只平移真正被分组命中的那一笔；不命中就原样 proceed */
    private fun shiftText(chain: io.github.libxposed.api.XposedInterface.Chain,
                          stringAt: Int, xAt: Int, yAt: Int): Any? {
        if (inKey.get() != true) return chain.proceed()
        val text = chain.getArg(stringAt)?.toString().orEmpty()
        val group = groupOf(text) ?: return chain.proceed()
        val shiftX = dxDp(group) * density
        val shiftY = dyDp(group) * density
        if (shiftX == 0f && shiftY == 0f) return chain.proceed()
        if (drawTextHits.getAndIncrement() < 6) {
            FkLogBridge.d("KeyTextHost: shift '$text' g=$group by $shiftX,$shiftY")
        }
        val args = chain.args.toTypedArray()
        args[xAt] = (chain.getArg(xAt) as Float) + shiftX
        args[yAt] = (chain.getArg(yAt) as Float) + shiftY
        return chain.proceed(args)
    }

    /** 按正在画的文字判分组：单字符数字 / 标点 / 其余算字母 */
    private fun groupOf(text: String): Int? {
        if (text.isEmpty()) return null
        val t9 = if (keyT9.get() == true) true else keyboardT9
        if (text.length == 1) {
            val c = text[0]
            if (c in '0'..'9') return if (t9) GROUP_NUMBER else GROUP_QWERTY_NUMBER
            if (punctuation.indexOf(c) >= 0) return GROUP_PUNCT
        }
        return if (t9) GROUP_T9_LETTER else GROUP_QWERTY_LETTER
    }
}

/** 宿主侧日志走 LSPosed + probe，避免引入 UI 包里的文件日志队列 */
internal object FkLogBridge {
    fun d(msg: String) {
        android.util.Log.i("wetype-dark", msg)
        runCatching {
            java.io.File("/data/user/0/com.tencent.wetype/files/wetype-dark-probe.log")
                .appendText(System.currentTimeMillis().toString() + " " + msg + "\n")
        }
    }

    fun w(msg: String) = d("W " + msg)

    fun w(msg: String, t: Throwable) = d("W " + msg + " :: " + t.javaClass.simpleName + ":" + t.message)

    fun e(msg: String) = d("E " + msg)

    fun e(msg: String, t: Throwable) = d("E " + msg + " :: " + t.javaClass.simpleName + ":" + t.message)
}

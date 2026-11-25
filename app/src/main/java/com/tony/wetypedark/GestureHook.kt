package com.tony.wetypedark

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Locale
import com.tony.wetypedark.ui.GestureAction
import com.tony.wetypedark.ui.GestureSettings
import com.tony.wetypedark.ui.KeyLayout

/**
 * 按键下滑手势的宿主侧实现。
 *
 * 结构与 WeType-Swipe（Apache-2.0）一致，而不是逐键挂 hook：
 *
 * 1. 只挂 **`View.dispatchTouchEvent`** 一个入口，键盘自己不是 `ViewGroup`，
 *    它的触摸会直接进 `dispatchTouchEvent`；
 * 2. 用类继承关系认出键盘（`selfdraw.o` / `selfdraw.n`，或 `selfdraw.` 包里
 *    同时声明 `getActionButton` 与 `getKeysLayoutInfo` 的 View），认出来之后
 *    缓存这个类，后续只做 `isInstance` 判断；
 * 3. 按键身份不靠 `getId()` 硬匹配，而是 `getActionButton()` → 按键对象 →
 *    `O()` 取 KeyData → `getMainText()` / `getSubText()` 反推 qwerty 字母或
 *    九键数字，`getId()` 只做兜底；
 * 4. 命中后把事件换成 `ACTION_CANCEL` 再 `proceed`，原生只收高亮、不上屏。
 *
 * 阈值与横向容差沿用 WeType-Swipe 的语义：横向上限
 * `max(阈值*2.5, dy*1.2)`（全键盘）/ `max(阈值*0.9, dy*0.75)`（九键）。
 */
internal object GestureHook {

    /** 微信输入法 4.x 的自绘键盘基类 */
    private const val KEYBOARD_BASE_4 =
        "com.tencent.wetype.plugin.hld.keyboard.selfdraw.o"

    /** 老版本（3.5.x）的自绘键盘基类 */
    private const val KEYBOARD_BASE_LEGACY =
        "com.tencent.wetype.plugin.hld.keyboard.selfdraw.n"

    private const val SELF_DRAW_PREFIX =
        "com.tencent.wetype.plugin.hld.keyboard.selfdraw."

    /** 九键阈值下限：九键键面大，20dp 以下容易误触 */
    private const val T9_MIN_THRESHOLD_DP = 20f

    private var installed = false
    private var keyboardBase: Class<*>? = null
    private var serviceRef: WeakReference<InputMethodService>? = null
    private var ifaceRef: XposedInterface? = null

    /** 当前选择区（来自 `onUpdateSelection`），文档/段落动作要用 */
    private var selectionStart = -1
    private var selectionEnd = -1

    private val methodCache = HashMap<String, Method?>()

    /** 一次按下的手势会话；`keyboard` 变了就作废 */
    private class Session(
        val keyboard: View,
        val downX: Float,
        val downY: Float,
        val key: String?,
        val display: String,
        val t9: Boolean,
        val action: GestureAction,
        val thresholdPx: Float,
    ) {
        var triggered = false
    }

    private var session: Session? = null

    // ---------------------------------------------------------------- 安装

    fun install(loader: ClassLoader, iface: XposedInterface): Boolean {
        if (installed) return true
        ifaceRef = iface

        val dispatch = runCatching {
            View::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        }.getOrNull()
        if (dispatch == null) {
            FkLogBridge.w("gesture: 找不到 View.dispatchTouchEvent")
            return false
        }

        runCatching {
            dispatch.isAccessible = true
            iface.hook(dispatch)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain -> interceptTouch(chain) }
            installed = true
            FkLogBridge.d("gesture: View.dispatchTouchEvent hook 已安装")
        }.onFailure {
            FkLogBridge.e("gesture: dispatchTouchEvent hook 失败", it)
        }

        // 选择区：文档/段落动作需要绝对下标
        runCatching {
            val onSelection = InputMethodService::class.java.getDeclaredMethod(
                "onUpdateSelection",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            onSelection.isAccessible = true
            iface.hook(onSelection)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        selectionStart = chain.getArg(2) as Int
                        selectionEnd = chain.getArg(3) as Int
                    }
                    result
                }
        }.onFailure { FkLogBridge.w("gesture: onUpdateSelection hook 跳过（${it.javaClass.simpleName}）") }

        return installed
    }

    fun onInputStart(service: InputMethodService) {
        serviceRef = WeakReference(service)
        selectionStart = -1
        selectionEnd = -1
        // 键盘是"唤起即画"的：等用户点一下才挂绘制 hook，标签就要等到第二次绘制才出现。
        // 所以窗口建好就扫一遍视图树，把键盘的绘制方法提前挂上。
        val decor = service.window?.window?.decorView as? ViewGroup ?: return
        // 键盘/符号面板的视图是分几帧建出来的，单次扫描会漏。
        // 这里按 0/150/400/800ms 扫四遍，谁先建好就先挂上。
        ARM_DELAYS_MS.forEach { delay ->
            decor.postDelayed({ armLabels(decor) }, delay)
        }
    }

    /** 上次安排重扫的时间，避免连点时空转。 */
    private var lastArmSchedule = 0L

    /** 按键之后补扫视图树：符号/数字/表情面板都是按需创建的。 */
    private fun scheduleArm(view: View) {
        val iface = ifaceRef ?: return
        val root = view.rootView as? ViewGroup ?: return
        val now = SystemClock.uptimeMillis()
        // 之前每次触摸(连 MOVE)都可能触发、且一次排两遍全树扫描；改成 800ms 节流、只扫一遍。
        if (now - lastArmSchedule < 800L) return
        lastArmSchedule = now
        root.postDelayed({ armLabels(root) }, 200L)
    }

    /** 唤起后重新扫描视图树的时点（毫秒）。之前 0/150/400/800 扫四遍太费，
     *  实测 0 和 300 两次足够（符号/表情面板是按需创建的，第二次能覆盖到）。*/
    private val ARM_DELAYS_MS = longArrayOf(0L, 300L)

    /**
     * 在视图树里找到**所有**键盘视图并挂上绘制 hook。
     *
     * 之前只挂第一个命中的视图，结果符号面板（`ImeSymbolKeyboardView`）这类
     * 独立子视图从来没被挂过。现在全树收集，每个都挂一遍（幂等）。
     */
    private fun armLabels(root: ViewGroup) {
        val iface = ifaceRef ?: return
        val views = ArrayList<View>()
        collectKeyboardViews(root, views)
        if (views.isEmpty()) {
            FkLogBridge.d("gesture: 还没找到键盘视图（稍后再扫）")
            return
        }
        if (keyboardBase == null) {
            keyboardBase = findKeyboardBase(views.first().javaClass)
            if (keyboardBase != null) {
                FkLogBridge.d("gesture: 键盘类已识别 ${views.first().javaClass.name}（唤起时）")
            }
        }
        FkLogBridge.d("gesture: 挂载 ${views.size} 个键盘视图 -> " + views.joinToString(",") { it.javaClass.simpleName })
        // 键盘类型同步给「按键文字位置」：九键左侧那条标点列画在按键绘制之外，
        // 分组判定需要一个不依赖「正在画某个键」的全局依据
        views.firstOrNull()?.let { first ->
            val name = first.javaClass.simpleName
            val t9 = name.contains("T9") || name.contains("Number")
            KeyTextHost.setKeyboardT9(t9)
            // 九键左侧标点列是 RecyclerView + TextView，文字走 drawTextRun（drawText 钩子抓不到），
            // 只能整块 View 平移；每次唤起都套一遍当前偏移量
            if (t9) runCatching { KeyTextHost.applyPunctColumnOffset(first) }
        }
        views.forEach { view ->
            view.postInvalidate()
        }
    }

    private fun collectKeyboardViews(view: View, output: MutableList<View>) {
        if (findKeyboardBase(view.javaClass) != null) output.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectKeyboardViews(view.getChildAt(i), output)
        }
    }

    private fun findKeyboardView(view: View): View? {
        if (findKeyboardBase(view.javaClass) != null) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val hit = findKeyboardView(view.getChildAt(i))
                if (hit != null) return hit
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 触摸

    private fun interceptTouch(chain: XposedInterface.Chain): Any? {
        val target = chain.getThisObject()
        val event = chain.getArg(0) as? MotionEvent
        if (target !is View || event == null) return chain.proceed()

        var base = keyboardBase
        if (base == null) {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return chain.proceed()
            base = findKeyboardBase(target.javaClass) ?: return chain.proceed()
            keyboardBase = base
            FkLogBridge.d("gesture: 键盘类已识别 ${base.name}")
        } else if (!base.isInstance(target)) {
            return chain.proceed()
        }

        // 符号面板这类视图是点「符号」之后才建出来的，唤起时的扫描扫不到。
        // 只在按下时补挂一次（原来每个 MOVE 事件都调 ensureHooked，白耗）；
        // 按下后再补扫一次视图树。
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scheduleArm(target)
        }

        val settings = currentSettings()
        if (!settings.enabled) return chain.proceed()
        if (isHandwriting(target)) {
            session = null
            return chain.proceed()
        }

        return handleKeyboardTouch(chain, target, event, settings)
    }

    private fun handleKeyboardTouch(
        chain: XposedInterface.Chain,
        keyboard: View,
        event: MotionEvent,
        settings: GestureSettings,
    ): Any? {
        val action = event.actionMasked

        if (event.pointerCount != 1) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) session = null
            return chain.proceed()
        }

        if (action == MotionEvent.ACTION_DOWN) {
            session = null
            val result = chain.proceed()

            val info = keyAfterDown(keyboard, event)
            val bound = info?.let { bindOf(settings, it) } ?: GestureAction.None
            val threshold = dp(keyboard, thresholdDpFor(settings, info?.t9 == true))
            // 诊断日志：无论有没有绑定都打一行，用来确认手指到底落在哪个键上。
            FkLogBridge.d(
                "gesture: DOWN x=${event.x.toInt()} y=${event.y.toInt()}" +
                    " kb=${keyboard.javaClass.simpleName}" +
                    " key=${info?.key} t9=${info?.t9} action=${bound.name}" +
                    " raw=${rawKeyData(keyboard, event)}",
            )
            if (bound == GestureAction.None) {
                session = null
            } else {
                session = Session(
                    keyboard = keyboard,
                    downX = event.x,
                    downY = event.y,
                    key = info?.key,
                    display = info?.display ?: "",
                    t9 = info?.t9 == true,
                    action = bound,
                    thresholdPx = threshold,
                )
            }
            return result
        }

        val current = session ?: return chain.proceed()
        if (current.keyboard !== keyboard) return chain.proceed()

        if (current.triggered) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) session = null
            return true
        }

        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            val dx = event.x - current.downX
            val dy = event.y - current.downY
            val horizontalLimit = if (current.t9) {
                maxOf(current.thresholdPx * 0.9f, dy * 0.75f)
            } else {
                maxOf(current.thresholdPx * 2.5f, dy * 1.2f)
            }

            if (dy >= current.thresholdPx && Math.abs(dx) <= horizontalLimit) {
                current.triggered = true
                proceedWithCancel(chain)

                if (settings.haptics) {
                    runCatching {
                        keyboard.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }

                val action2 = current.action
                val key = current.key
                FkLogBridge.d("gesture: 触发 key=$key action=${action2.name}")
                keyboard.post { runAction(keyboard.context, action2, key, current.t9) }

                if (action == MotionEvent.ACTION_UP) session = null
                return true
            }
        }

        val result = chain.proceed()
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) session = null
        return result
    }

    private fun proceedWithCancel(chain: XposedInterface.Chain) {
        val source = chain.getArg(0) as? MotionEvent ?: return
        val cancel = MotionEvent.obtain(source)
        try {
            cancel.action = MotionEvent.ACTION_CANCEL
            val args = chain.args.toTypedArray()
            args[0] = cancel
            chain.proceed(args)
        } finally {
            cancel.recycle()
        }
    }

    // ---------------------------------------------------------------- 键盘识别

    private fun findKeyboardBase(type: Class<*>?): Class<*>? {
        var current = type
        var depth = 0
        while (current != null && depth < 12) {
            val name = current.name
            if (name == KEYBOARD_BASE_4 || name == KEYBOARD_BASE_LEGACY) return current
            if (name.startsWith(SELF_DRAW_PREFIX) &&
                View::class.java.isAssignableFrom(current) &&
                declaresNoArg(current, "getActionButton") &&
                declaresNoArg(current, "getKeysLayoutInfo")
            ) {
                return current
            }
            current = current.superclass
            depth++
        }
        return null
    }

    private fun declaresNoArg(type: Class<*>, name: String): Boolean = runCatching {
        type.getDeclaredMethod(name)
        true
    }.getOrDefault(false)

    private fun isHandwriting(keyboard: View): Boolean {
        var current: View? = keyboard
        var depth = 0
        while (current != null && depth < 16) {
            val name = current.javaClass.name.lowercase(Locale.ROOT)
            if (name.contains("handwrite") || name.contains("hand_write")) return true
            val parent = current.parent
            current = parent as? View
            depth++
        }
        return false
    }

    // ---------------------------------------------------------------- 按键身份

    private class KeyInfo(val key: String, val display: String, val t9: Boolean)

    private fun keyAfterDown(keyboard: View, event: MotionEvent): KeyInfo? {
        var button = invoke(keyboard, "getActionButton")
        if (button == null) button = invoke(keyboard, "v1", event, false)
        if (button == null) button = invoke(keyboard, "v1", event, true)
        return keyFromButton(keyboard, button)
    }

    /** 诊断用：把手指下方按键的原始数据打出来（id / 主文字 / 次要文字）。 */
    private fun rawKeyData(keyboard: View, event: MotionEvent): String = try {
        var button = invoke(keyboard, "getActionButton")
        if (button == null) button = invoke(keyboard, "v1", event, false)
        if (button == null) button = invoke(keyboard, "v1", event, true)
        val keyData = button?.let { invoke(it, "O") }
        if (keyData == null) {
            "button=$button"
        } else {
            "id=${invoke(keyData, "getId")} main=${invoke(keyData, "getMainText")}" +
                " sub=${invoke(keyData, "getSubText")}"
        }
    } catch (t: Throwable) {
        "err=${t.javaClass.simpleName}"
    }

    private fun keyFromButton(keyboard: View, button: Any?): KeyInfo? {
        if (button == null) return null
        val keyData = invoke(button, "O")
        if (keyData != null) {
            val main = invoke(keyData, "getMainText")
            val secondary = invoke(keyData, "getSubText")
                ?: invoke(keyData, "getSecondaryText")
                ?: invoke(keyData, "getHintText")
                ?: invoke(keyData, "getAssistText")
            keyFromTexts(keyboard, button, keyData, main, secondary)?.let { return it }
            if (isT9Context(keyboard, button, keyData)) {
                numericT9Key(main)?.let { return KeyInfo(it, "九键 $it", true) }
            }
            keyFromId(invoke(keyData, "getId"), keyboard, button, keyData)?.let { return it }
        }
        return keyFromId(invoke(button, "K"), keyboard, button, keyData)
    }

    private fun keyFromTexts(
        keyboard: View,
        button: Any?,
        keyData: Any?,
        mainValue: Any?,
        secondaryValue: Any?,
    ): KeyInfo? {
        val main = normalizeText(mainValue)
        val secondary = normalizeText(secondaryValue)
        // 只有九键键盘才把字母串折成 2-9 的数字键。
        // 之前无条件折：26 键的 "q" 被折成 "7" 并标成 t9=true，
        // 结果全键盘的绑定永远查不到 —— 就是"全键盘打开手势没反应"的原因。
        if (isT9Context(keyboard, button, keyData)) {
            t9DigitFromLetters(main + secondary)?.let { return KeyInfo(it, "九键 $it", true) }
        }
        if (main.length == 1) {
            val c = main[0]
            if (c in 'a'..'z') return KeyInfo(c.toString(), c.toString().uppercase(Locale.ROOT), false)
        }
        return null
    }

    private fun keyFromId(
        value: Any?,
        keyboard: View,
        button: Any?,
        keyData: Any?,
    ): KeyInfo? {
        val raw = value?.toString()?.trim()?.lowercase(Locale.ROOT) ?: return null
        val index = raw.lastIndexOf("_key_")
        val tail = if (index >= 0) raw.substring(index + 5) else raw
        if (tail.length == 1) {
            val c = tail[0]
            if (c in 'a'..'z') return KeyInfo(c.toString(), c.toString().uppercase(Locale.ROOT), false)
        }
        if (tail.isNotEmpty()) {
            val last = tail.last()
            if (last in 'a'..'z' && (tail.endsWith("_$last") || tail.endsWith("key$last"))) {
                return KeyInfo(last.toString(), last.toString().uppercase(Locale.ROOT), false)
            }
        }
        val t9Hint = raw.contains("t9") || raw.contains("nine") ||
            isT9Context(keyboard, button, keyData)
        if (t9Hint) {
            for (i in raw.indices.reversed()) {
                val c = raw[i]
                if (c in '2'..'9') return KeyInfo(c.toString(), "九键 $c", true)
            }
        }
        return null
    }

    private fun isT9Context(vararg values: Any?): Boolean = values.any { v ->
        v != null && v.javaClass.name.lowercase(Locale.ROOT).let {
            it.contains("t9") || it.contains("nine")
        }
    }

    private fun numericT9Key(value: Any?): String? {
        val text = normalizeText(value)
        if (text.length == 1) {
            val c = text[0]
            if (c in '2'..'9') return c.toString()
        }
        return null
    }

    private fun t9DigitFromLetters(value: String): String? {
        if (value.isEmpty()) return null
        val groups = listOf("abc" to "2", "def" to "3", "ghi" to "4", "jkl" to "5",
            "mno" to "6", "pqrs" to "7", "tuv" to "8", "wxyz" to "9")
        groups.forEach { (letters, digit) -> if (lettersMatch(value, letters)) return digit }
        return null
    }

    /** 只比对字母，且必须按顺序完整覆盖一组（"abc" 命中，但 "abcd" 不命中） */
    private fun lettersMatch(value: String, expected: String): Boolean {
        var matched = 0
        value.forEach { c ->
            if (c !in 'a'..'z') return@forEach
            if (matched >= expected.length || c != expected[matched]) return false
            matched++
        }
        return matched == expected.length
    }

    private fun normalizeText(value: Any?): String {
        val text = (value?.toString() ?: "").trim().lowercase(Locale.ROOT)
        val out = StringBuilder(text.length)
        text.forEach { c -> if (c in 'a'..'z' || c in '0'..'9') out.append(c) }
        return out.toString()
    }

    // ---------------------------------------------------------------- 绑定

    private fun bindOf(settings: GestureSettings, info: KeyInfo): GestureAction {
        val layout = if (info.t9) KeyLayout.T9 else KeyLayout.QWERTY
        val direct = settings.action(layout, info.key)
        if (direct != GestureAction.None) return direct
        if (info.key == "space") return settings.action(layout, "space_down")
        return GestureAction.None
    }

    private fun thresholdDpFor(settings: GestureSettings, t9: Boolean): Float =
        if (t9) maxOf(settings.thresholdDp.toFloat(), T9_MIN_THRESHOLD_DP)
        else settings.thresholdDp.toFloat()

    private fun currentSettings(): GestureSettings = runCatching {
        GestureSettings.fromJson(HostConfig.gestureSettingsJson())
    }.getOrDefault(GestureSettings())

    private fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density

    // ---------------------------------------------------------------- 反射

    private fun invoke(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        return try {
            val types = arrayOfNulls<Class<*>>(args.size)
            for (i in args.indices) {
                types[i] = when (val arg = args[i]) {
                    is MotionEvent -> MotionEvent::class.java
                    is Boolean -> java.lang.Boolean.TYPE
                    is Int -> Integer.TYPE
                    is Float -> java.lang.Float.TYPE
                    else -> arg?.javaClass ?: Any::class.java
                }
            }
            @Suppress("UNCHECKED_CAST")
            val parameterTypes = types as Array<Class<*>>
            val cacheKey = target.javaClass.name + "#" + name +
                parameterTypes.joinToString("") { ":" + it.name }
            val method = if (methodCache.containsKey(cacheKey)) {
                methodCache[cacheKey]
            } else {
                val found = findMethod(target.javaClass, name, parameterTypes)
                methodCache[cacheKey] = found
                found
            } ?: return null
            method.invoke(target, *args)
        } catch (t: Throwable) {
            null
        }
    }

    private fun findMethod(type: Class<*>?, name: String, parameterTypes: Array<Class<*>>): Method? {
        var current = type
        while (current != null) {
            runCatching {
                val method = current.getDeclaredMethod(name, *parameterTypes)
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    // ---------------------------------------------------------------- 动作

    private fun runAction(context: Context, action: GestureAction, key: String?, t9: Boolean) {
        val service = serviceRef?.get()
        if (service == null) {
            FkLogBridge.w("gesture: 没有 InputMethodService 引用，动作 ${action.name} 跳过")
            return
        }
        runCatching { GestureActions.perform(service, action, key, t9, selectionStart, selectionEnd) }
            .onFailure { FkLogBridge.e("gesture: 动作 ${action.name} 执行失败", it) }
    }
}

/**
 * 动作执行器。
 *
 * 优先走 `InputConnection.performContextMenuAction`（编辑器自己实现），
 * 不行再退回 `InputMethodService.performContextMenuAction`，最后用按键事件兜底；
 * 文档/段落类动作靠 `onUpdateSelection` 记下的绝对下标算目标区间。
 */
internal object GestureActions {

    fun perform(
        service: InputMethodService,
        action: GestureAction,
        key: String?,
        t9: Boolean,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean {
        if (action == GestureAction.None) return false
        if (isPassword(service)) return false

        when (action) {
            GestureAction.NextInputMethod -> return service.switchToNextInputMethod(false)
            GestureAction.PreviousInputMethod -> return service.switchToPreviousInputMethod()
            GestureAction.ShowInputMethodPicker -> {
                val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager ?: return false
                imm.showInputMethodPicker()
                return true
            }
            // InputMethodManager 的版本要 IBinder 令牌，服务自己这个是无参的重载
            GestureAction.SwitchLanguage -> return service.switchToNextInputMethod(false)
            else -> Unit
        }

        val connection = service.currentInputConnection ?: return false
        runCatching { connection.finishComposingText() }

        return when (action) {
            GestureAction.SelectAll -> menu(service, connection, android.R.id.selectAll)
            GestureAction.Cut -> menu(service, connection, android.R.id.cut)
            GestureAction.Copy -> menu(service, connection, android.R.id.copy)
            GestureAction.Paste -> menu(service, connection, android.R.id.paste)
            GestureAction.Undo -> menu(service, connection, android.R.id.undo)
            GestureAction.Redo -> menu(service, connection, android.R.id.redo)
            GestureAction.CopyAll -> menu(service, connection, android.R.id.selectAll) &&
                menu(service, connection, android.R.id.copy)
            GestureAction.CutAll -> menu(service, connection, android.R.id.selectAll) &&
                menu(service, connection, android.R.id.cut)
            GestureAction.ParagraphStart -> moveTo(connection, selectionStart, -1)
            GestureAction.ParagraphEnd -> moveTo(connection, selectionStart, +1)
            GestureAction.SelectToParagraphStart -> selectTo(connection, selectionStart, selectionEnd, -1)
            GestureAction.SelectToParagraphEnd -> selectTo(connection, selectionStart, selectionEnd, +1)
            GestureAction.DocumentStart -> setSelection(connection, 0, 0)
            GestureAction.DocumentEnd -> {
                val end = documentEnd(connection)
                setSelection(connection, end, end)
            }
            GestureAction.SelectToDocumentStart -> setSelection(connection, 0, selectionEnd.coerceAtLeast(0))
            GestureAction.SelectToDocumentEnd -> setSelection(
                connection, selectionStart.coerceAtLeast(0), documentEnd(connection),
            )
            else -> {
                FkLogBridge.w("gesture: 动作 ${action.name} 还没有宿主入口")
                false
            }
        }
    }

    private fun menu(
        service: InputMethodService,
        connection: android.view.inputmethod.InputConnection,
        menuId: Int,
    ): Boolean {
        runCatching { if (connection.performContextMenuAction(menuId)) return true }
        runCatching {
            val method = service.javaClass.getMethod("performContextMenuAction", Int::class.javaPrimitiveType)
            val result = method.invoke(service, menuId)
            if (result !is Boolean || result) return true
        }
        return false
    }

    private fun moveTo(
        connection: android.view.inputmethod.InputConnection,
        cursor: Int,
        direction: Int,
    ): Boolean {
        if (cursor < 0) return false
        if (direction < 0) {
            val before = runCatching { connection.getTextBeforeCursor(4096, 0) }.getOrNull()
                ?: return false
            val offset = ParagraphNavigator.distanceToStart(before)
            return setSelection(connection, cursor - offset, cursor - offset)
        }
        val after = runCatching { connection.getTextAfterCursor(4096, 0) }.getOrNull() ?: return false
        val offset = ParagraphNavigator.distanceToEnd(after)
        return setSelection(connection, cursor + offset, cursor + offset)
    }

    private fun selectTo(
        connection: android.view.inputmethod.InputConnection,
        start: Int,
        end: Int,
        direction: Int,
    ): Boolean {
        if (start < 0 || end < 0) return false
        val left = minOf(start, end)
        val right = maxOf(start, end)
        if (direction < 0) {
            val before = runCatching { connection.getTextBeforeCursor(4096, 0) }.getOrNull()
                ?: return false
            return setSelection(connection, left - ParagraphNavigator.distanceToStart(before), right)
        }
        val after = runCatching { connection.getTextAfterCursor(4096, 0) }.getOrNull() ?: return false
        return setSelection(connection, left, right + ParagraphNavigator.distanceToEnd(after))
    }

    private fun setSelection(
        connection: android.view.inputmethod.InputConnection,
        start: Int,
        end: Int,
    ): Boolean = runCatching { connection.setSelection(start.coerceAtLeast(0), end.coerceAtLeast(0)) }
        .getOrDefault(false)

    private fun documentEnd(connection: android.view.inputmethod.InputConnection): Int {
        val before = runCatching { connection.getTextBeforeCursor(1 shl 20, 0) }.getOrNull()?.length ?: 0
        val after = runCatching { connection.getTextAfterCursor(1 shl 20, 0) }.getOrNull()?.length ?: 0
        return before + after
    }

    private fun isPassword(service: InputMethodService): Boolean = runCatching {
        val info = service.currentInputEditorInfo ?: return false
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        info.inputType and InputType.TYPE_MASK_VARIATION in intArrayOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
    }.getOrDefault(false)
}

/** 段落边界的纯函数（照 WeType-Swipe 的 ParagraphNavigator） */
internal object ParagraphNavigator {
    fun distanceToStart(before: CharSequence?): Int {
        if (before == null) return 0
        for (index in before.length - 1 downTo 0) {
            if (isLineBreak(before[index])) return before.length - index - 1
        }
        return before.length
    }

    fun distanceToEnd(after: CharSequence?): Int {
        if (after == null) return 0
        for (index in after.indices) {
            if (isLineBreak(after[index])) return index
        }
        return after.length
    }

    private fun isLineBreak(value: Char): Boolean =
        value == '\n' || value == '\r' || value == '\u2028' || value == '\u2029'
}

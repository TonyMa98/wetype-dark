package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import org.json.JSONObject

/**
 * 下滑手势的可绑定动作。
 *
 * 三项信息对应宿主的三处用法：
 *  - [full]  → 绑定行与选择弹层里显示的名字；
 *  - [short] → 键帽下方那行小字（最多 4 个汉字，所以不能直接用全名）；
 *  - [name]  → 落盘的稳定 id（改显示名不会让老配置失效）。
 */
enum class GestureAction(val full: String, val short: String) {
    None("未绑定", ""),
    SelectAll("全选", "全选"),
    Cut("剪切", "剪切"),
    Copy("复制", "复制"),
    Paste("粘贴", "粘贴"),
    ParagraphStart("段首", "段首"),
    ParagraphEnd("段尾", "段尾"),
    SelectToParagraphStart("选至段首", "选段首"),
    SelectToParagraphEnd("选至段尾", "选段尾"),
    OpenClipboard("剪贴板", "剪贴板"),
    OpenQuickPhrase("常用语", "常用语"),
    CopyAll("全复制", "全复制"),
    CutAll("全剪切", "全剪切"),
    Undo("撤销", "撤销"),
    Redo("重做", "重做"),
    DocumentStart("文首", "文首"),
    DocumentEnd("文尾", "文尾"),
    SelectToDocumentStart("选至文首", "选文首"),
    SelectToDocumentEnd("选至文尾", "选文尾"),
    OpenSettings("设置", "设置"),
    OpenFindWord("手写找字", "找字"),
    MoveCursor("滑移", "滑移"),
    MoveSelect("滑选", "滑选"),
    NextInputMethod("下一个输入法", "下个输入法"),
    PreviousInputMethod("上一个输入法", "上个输入法"),
    ShowInputMethodPicker("选择输入法", "选输入法"),
    SwitchLanguage("语言切换", "切换"),
    OpenEmoji("表情面板", "表情"),
    OpenTextEditPanel("文字编辑面板", "文字编辑");

    companion object {
        fun of(id: String?): GestureAction = values().firstOrNull { it.name == id } ?: None
    }
}

/** 键盘布局。两套布局**各自维护一张绑定表**，不能合并成一张扁平表，否则切布局会串。 */
enum class KeyLayout(val label: String) {
    QWERTY("全键盘"),
    T9("九键");

    /** 该布局下所有可绑定按键（含空格上滑 / 空格下滑 / 回车） */
    val keys: List<String>
        get() = when (this) {
            QWERTY -> ('a'..'z').map { it.toString() } + EXTRA_KEYS
            T9 -> (1..9).map { it.toString() } + EXTRA_KEYS
        }

    /** 键盘预览的分行（最后一行放空格与回车） */
    val rows: List<List<String>>
        get() = when (this) {
            QWERTY -> listOf(
                "qwertyuiop".map { it.toString() },
                "asdfghjkl".map { it.toString() },
                "zxcvbnm".map { it.toString() },
                listOf("space", "enter"),
            )
            T9 -> listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("space", "enter"),
            )
        }

    companion object {
        private val EXTRA_KEYS = listOf("space", "space_down", "enter")
    }
}

/** 按键 id 的展示文案 */
object GestureKeys {

    fun display(key: String): String = when (key) {
        "space" -> "空格"
        "space_down" -> "空格（下滑）"
        "enter" -> "回车"
        else -> key
    }

    /** 键帽上那行小字用的更短写法 */
    fun capLabel(key: String): String = when (key) {
        "space" -> "空格"
        "space_down" -> "空格↓"
        "enter" -> "回车"
        else -> key
    }

    /** 选择弹层的标题 */
    fun pickerTitle(key: String): String = when (key) {
        "space" -> "空格键上滑动作"
        "space_down" -> "空格键下滑动作"
        "enter" -> "回车键上滑动作"
        else -> "「$key」下滑动作"
    }
}

/**
 * 下滑手势的完整配置。
 *
 * 落盘是一整块 JSON（[toJson]），不拆成十几个 key —— 配置项彼此关联（比如
 * 显示标签 ↔ 自定义颜色、字号 ↔ 偏移），整体读写比逐项 merge 简单也不容易写坏。
 */
class GestureSettings {

    /** 启用按键手势（总闸） */
    var enabled: Boolean = true

    /** 密码框禁用：密码输入时保留原生按键行为 */
    var disableInPassword: Boolean = true

    /** 触感反馈：动作执行成功后振动 */
    var haptics: Boolean = true

    /** 在按键下方显示绑定动作 */
    var showLabel: Boolean = true

    /** 自定义标签颜色 */
    var customColor: Boolean = false

    var lightColor: Int = DEFAULT_COLOR
    var darkColor: Int = DEFAULT_COLOR

    /**
     * 下滑面板小字是否跟随全局「功能键文字色」(Appearance.Role.KEY_FN_TEXT)。
     * 默认 true：网页端调整该色时，下滑面板的 全选/复制/粘贴/剪切 等小字一起变。
     * 关掉则回落到 [customColor] 或宿主键帽小字色。
     */
    var useAppearanceColor: Boolean = true

    /** 下滑阈值（dp，10–48） */
    var thresholdDp: Int = 24

    /** 标签字号（sp，5–16） */
    var fontSizeSp: Int = 9

    /** 标签水平 / 垂直偏移（dp，-48–48） */
    var offsetXDp: Int = 0
    var offsetYDp: Int = 0

    /** layout.name → (key → action) */
    private val bindings = HashMap<String, HashMap<String, GestureAction>>()

    fun action(layout: KeyLayout, key: String): GestureAction =
        bindings[layout.name]?.get(key) ?: GestureAction.None

    fun setAction(layout: KeyLayout, key: String, action: GestureAction) {
        val map = bindings.getOrPut(layout.name) { HashMap() }
        if (action == GestureAction.None) map.remove(key) else map[key] = action
    }

    /** 该布局下已绑定的按键数（用于分组标题） */
    fun boundCount(layout: KeyLayout): Int = bindings[layout.name]?.size ?: 0

    fun toJson(): String {
        val root = JSONObject()
        root.put("v", SCHEMA)
        root.put("enabled", enabled)
        root.put("pw", disableInPassword)
        root.put("haptic", haptics)
        root.put("showLabel", showLabel)
        root.put("custom", customColor)
        root.put("light", lightColor)
        root.put("dark", darkColor)
        root.put("useAp", useAppearanceColor)
        root.put("thr", thresholdDp)
        root.put("fs", fontSizeSp)
        root.put("ox", offsetXDp)
        root.put("oy", offsetYDp)
        val bind = JSONObject()
        bindings.forEach { (layout, map) ->
            if (map.isEmpty()) return@forEach
            val one = JSONObject()
            map.forEach { (key, action) -> one.put(key, action.name) }
            bind.put(layout, one)
        }
        root.put("bind", bind)
        return root.toString()
    }

    companion object {
        /** 默认标签颜色：跟主题强调色一致 */
        const val DEFAULT_COLOR = 0xFF0A6AFF.toInt()

        /** 配置结构版本；v2 起标签默认开启 */
        private const val SCHEMA = 2

        fun fromJson(raw: String?): GestureSettings {
            val s = GestureSettings()
            if (raw.isNullOrBlank()) return s
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return s
            s.enabled = root.optBoolean("enabled", true)
            s.disableInPassword = root.optBoolean("pw", true)
            s.haptics = root.optBoolean("haptic", true)
            // v1 的配置默认没开标签，升级时补开一次
            s.showLabel = if (root.optInt("v", 1) < 2) true else root.optBoolean("showLabel", true)
            s.customColor = root.optBoolean("custom", false)
            s.lightColor = root.optInt("light", DEFAULT_COLOR)
            s.darkColor = root.optInt("dark", DEFAULT_COLOR)
            s.useAppearanceColor = root.optBoolean("useAp", true)
            s.thresholdDp = root.optInt("thr", 24)
            s.fontSizeSp = root.optInt("fs", 9)
            s.offsetXDp = root.optInt("ox", 0)
            s.offsetYDp = root.optInt("oy", 0)
            val bind = root.optJSONObject("bind")
            bind?.keys()?.forEach { layoutName ->
                val layout = KeyLayout.values().firstOrNull { it.name == layoutName }
                    ?: return@forEach
                val one = bind.optJSONObject(layoutName) ?: return@forEach
                val keys = one.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    s.setAction(layout, key, GestureAction.of(one.optString(key)))
                }
            }
            return s
        }
    }
}

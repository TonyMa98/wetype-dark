package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject

/**
 * 「颜色配置」二级页的数据模型（用户 2026-10-31 需求）。
 *
 * ## 为什么单独一个 object
 *
 * 配色分两处生效：
 *  - `:hld`（微信输入法）进程：按键底色/文字/角标/投影/描边 → 走 [ThemeColors]；
 *    功能按钮背景/图标、候选词文字、logo 着色 → 走 [ToolbarTint] / [IconOverlay]。
 *  - 设置页进程：`ColorConfigActivity` 负责读写。
 *
 * 两边都要拿到同一份配置，所以模型放在这里，只做「JSON ↔ 角色颜色」的转换，
 * 不碰任何 View。
 *
 * ## 存储格式（`ConfigStore.KEY_APPEARANCE`）
 *
 * ```json
 * {
 *   "light": { "key_fill": "99FFFFFF", "key_text": "D1000000" },
 *   "dark":  { "key_fill": "14FFFFFF" },
 *   "icons": { "voice": "<base64 png>" }
 * }
 * ```
 *
 * **没有键 = 不接管**（保持微信原生 / 模块既有默认值）。这样「恢复默认」只要删键，
 * 不用记一堆默认值，也不会因为以后改默认值而把用户的选择盖掉。
 */
object Appearance {

    /**
     * 可调角色。
     *
     * [id] 是 JSON 里的键，**改名等于配置失效**，只加不改。
     * [group] 决定二级页里分到哪张卡片。
     */
    enum class Role(
        val id: String,
        val label: String,
        val group: Group,
        /** 手机端二级页里不显示（比如描边只能调不透明度，不能改色） */
        val hideInApp: Boolean = false,
        /** 没配置时页面上显示的默认色（只影响展示，不写进配置） */
        val fallback: Int? = null,
    ) {
        // 输入法面板底色（2026-11-04 用户）：上下两端做垂直渐变，
        // 8 位 AARRGGBB 里的 A 就是透明度。只配一端 = 纯色；两端都不配 = 沿用磨砂默认。
        // 亮/暗各存一套（分别落在 light / dark 表里），网页端与手机端共用这两个 id。
        // 面板背景色（2026-11-13 用户）：纯色，一次盖住整块面板，
        // 配了它就无视上面两端渐变（上下端配置仍然保留、可随时回退用）。
        PANEL_BG("panel_bg", "面板背景色", Group.CANDIDATE),

        PANEL_TOP("panel_top", "面板底色 上端", Group.CANDIDATE, hideInApp = true),
        PANEL_BOTTOM("panel_bottom", "面板底色 下端", Group.CANDIDATE, hideInApp = true),

        KEY_FILL("key_fill", "按键底色", Group.KEY),
        /**
         * 【2026-11-19 用户需求】按键底色渐变。
         * 只有 [KEY_FILL] 和它**两端都配了**才走渐变；只配 key_fill = 纯色（跟以前完全一样）。
         * 渐变方向由数值项 [Number.KEY_FILL_GRAD_ANGLE] 控制，90° = 从上到下。
         */
        KEY_FILL_END("key_fill_end", "按键底色·渐变末端", Group.KEY),
        KEY_TEXT("key_text", "按键文字", Group.KEY),
        /** 下滑手势面板的功能小字（全选/复制/粘贴/剪切…）单独配色。
         *  不配则回落到 [KEY_TEXT]（全局按键文字色），保持「默认跟着走、可单拆」。 */
        KEY_FN_TEXT("key_fn_text", "下滑面板小字", Group.KEY),
        KEY_NUMBER("key_number", "角标小字", Group.KEY),
        KEY_SHADOW("key_shadow", "按键投影", Group.KEY),
        KEY_PRESSED("key_pressed", "按压态键面", Group.KEY),

        CANDIDATE_TEXT("candidate_text", "候选词文字", Group.CANDIDATE),
        CANDIDATE_SELECTED_BG("candidate_selected_bg", "候选词高亮背景", Group.CANDIDATE),
        // 2026-11-18 用户要求去掉：高亮文字统一跟随「主题色」(send_bg)，不再单独配置
        CANDIDATE_SELECTED_TEXT("candidate_selected_text", "候选词高亮文字", Group.CANDIDATE, hideInApp = true),
        CANDIDATE_ICON("candidate_icon", "候选区图标", Group.CANDIDATE, hideInApp = true),
        // 两个角色分别对应 docs/logo/ 下的两张 svg，一一对应，别再混
        LOGO_CIRCLE("logo_circle", "logo 圆底（logo_circle.svg）", Group.CANDIDATE),
        LOGO_GLYPH("logo_glyph", "logo 图形（logo_glyph.svg）", Group.CANDIDATE),

        FN_BG("fn_bg", "功能按钮背景", Group.CANDIDATE),
        FN_ICON("fn_icon", "功能按钮图标", Group.CANDIDATE),

        // 长按弹层（FloatView）：长按 JKL 之类按键时上方弹出的那条竖排/横排候选项
        POPUP_BG("popup_bg", "长按弹层底色", Group.CANDIDATE),
        POPUP_TEXT("popup_text", "长按弹层文字色", Group.CANDIDATE),

        // 图标配色：每个可换图标的按钮单独一个颜色（上传自定义图标后按这个色染色）
        ICON_VOICE("icon_tint_voice", "语音图标颜色", Group.KEY, hideInApp = true),
        ICON_KEYBOARD("icon_tint_keyboard", "切换输入法图标颜色", Group.KEY),
        ICON_CLIPBOARD("icon_tint_clipboard", "剪贴板图标颜色", Group.KEY, hideInApp = true),
        ICON_COLLAPSE("icon_tint_collapse", "收起键盘图标颜色", Group.KEY),
        ICON_BACKSPACE("icon_tint_backspace", "退格图标颜色", Group.KEY, hideInApp = true),

        // 主题色：一组两端色，做垂直渐变（发送键底色等）。默认 006AFF → 347CFF。
        THEME_TOP("theme_top", "主题色 起始", Group.KEY, fallback = 0xFF006AFF.toInt(), hideInApp = true),
        THEME_BOTTOM("theme_bottom", "主题色 结束", Group.KEY, fallback = 0xFF347CFF.toInt(), hideInApp = true),

        /**
         * 发送键（九键右下那一块）**有字时**的底色。不配就用主题色渐变。
         * 没字时这块键跟普通按键完全一样，不受这里影响。
         */
        SEND_BG("send_bg", "主题色", Group.KEY, fallback = 0xFF23C891.toInt()),
        /** 发送键有字时的文字色。不配 = 纯白 */
        SEND_TEXT("send_text", "发送键的字", Group.KEY, fallback = 0xFFFFFFFF.toInt()),
        /** 发送键**无字（输入框为空）时**补画的「发送」文字色。不配 = 80% 白 */
        SEND_TEXT_IDLE("send_text_idle", "发送键的字（空输入框）", Group.KEY, fallback = 0xCCFFFFFF.toInt(), hideInApp = true),

        // ---- 换行/回车键（enter）的灰态与禁用态 ----
        // enter 键其实是三态：可发送(绿)= 跟随主题色；普通/换行(灰)；禁用。
        // 灰态和禁用态共用同一个灰底，只是文字色不同，所以分开配。
        /** 换行键（灰态）的底色。不配 = 亮 #B7BCC4 / 暗 #414141 */
        ENTER_GREY_BG("enter_grey_bg", "换行键底色（灰）", Group.KEY),
        /** 换行键（灰态）的文字色。不配 = 亮 #E6000000 / 暗 #E6FFFFFF */
        ENTER_GREY_TEXT("enter_grey_text", "换行键文字（灰）", Group.KEY),
        /** 禁用态回车键的文字色。不配 = 亮 #33000000 / 暗 #33FFFFFF */
        ENTER_DISABLED_TEXT("enter_disabled_text", "禁用键文字", Group.KEY),
        /** 禁用态回车键的底色。不配 = #414141 */
        ENTER_DISABLED_BG("enter_disabled_bg", "禁用键底色", Group.KEY, fallback = 0xFF414141.toInt()),

        // ---- 找字 → 词频 子页的两块灰（2026-11-13 用户点名） ----
        /** 词频界面：左边那条字母列（j/k/l/空/首笔画）+ 右下角 ⌫ 的底色。不配 = 纯白 */
        FIND_FREQ_BG("find_freq_bg", "词频界面颜色", Group.KEY, fallback = 0xFFFFFFFF.toInt()),

        // ---- 返回键（return）图标色：无 mode，只有常态/按下 ----
        /** 返回键箭头（常态）。不配 = 纯白 */
        RETURN_ICON("return_icon", "返回键图标", Group.KEY, fallback = 0xFFFFFFFF.toInt(), hideInApp = true),
        /** 返回键箭头（按下）。不配 = 90% 白 */
        RETURN_ICON_PRESSED("return_icon_pressed", "返回键图标（按下）", Group.KEY, fallback = 0xE6FFFFFF.toInt(), hideInApp = true),
        ;

        companion object {
            fun byId(id: String): Role? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * 可调的数值项（不是颜色）。
     *
     * 存法：JSON 里 `"numbers": { "shadow_radius": 3.0 }`，
     * 与颜色一样「没有键 = 用模块默认值」。
     */
    enum class Number(
        val id: String,
        val label: String,
        val min: Float,
        val max: Float,
        val fallback: Float,
        val unit: String,
        /** 手机端二级页里不显示（自绘阴影功能删除后隐藏其参数项） */
        val hideInApp: Boolean = false,
    ) {
        /**
         * 按键圆角。**三套主题共用一个值**（亮色圆角 / 亮色直角 / 暗色圆角），
         * 因为「直角」那一套指的是**面板顶部**直角，不是按键。
         */
        KEY_CORNER("key_corner", "按键圆角", 0f, 24f, 9f, "dp"),
        SHADOW_RADIUS("shadow_radius", "按键投影模糊", 0f, 10f, 2f, "dp"),
        /** 投影往下偏移多少 —— 越大越像「浮起来」，月牙也越厚 */
        SHADOW_DY("shadow_dy", "按键投影距离", 0f, 24f, 8f, "dp"),
        /** 高光描边不透明度：100 = 内置三档渐变的原样，调小就整体变淡 */
        RIM_ALPHA("rim_alpha", "按键描边不透明度", 0f, 100f, 100f, "%"),
        /**
         * 「使用 ColorOS 原生」：按键底色上叠一层 COUI 轮廓光（边缘亮线 + 内晕）。
         * 0 = 关闭（走原来的白色三档高光）；>0 = 启用，数值同时当作强度百分比。
         * 亮/暗共用（COUI 自己的深浅差异写在下面的绘制参数里）。
         */
        KEY_COUI_EDGE("key_coui_edge", "ColorOS 原生描边", 0f, 100f, 0f, "%"),
        /**
         * 按键底色渐变端点（百分比位置，纵向：0 = 键顶，100 = 键底）。
         * 只做上下渐变；start/end 控制渐变从哪一行开始、到哪一行结束，
         * 范围外自动拉平到端点色。只有 key_fill + key_fill_end 两端都配了才生效。
         */
        KEY_FILL_GRAD_START("key_fill_grad_start", "渐变起始位置", 0f, 100f, 0f, "%"),
        KEY_FILL_GRAD_END("key_fill_grad_end", "渐变结束位置", 0f, 100f, 100f, "%"),
        ;

        /** true = 亮/暗共用同一个值（按键圆角）；false = 亮/暗各存一套 */
        var shared: Boolean = false
            internal set

        companion object {
            /** 手机端二级页里这张数值卡的标题（与网页第三组一致） */
            const val CARD_TITLE = "③ 按键属性"

            init {
                KEY_CORNER.shared = true
                KEY_COUI_EDGE.shared = true
            }

            fun byId(id: String): Number? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * 二级页里的分组（= 卡片）。
     *
     * 2026-11-14 起**完全对齐网页调色台的三组**：
     *   ① 候选栏（顶栏）  ② 按键区域  ③ 按键属性（数值，见 [Number]）
     * 原来那套「面板底色 / 主题色 / 按键 / 候选词与 logo / 功能按钮 / 长按弹层 / 图标配色」
     * 是上一版网页的结构，早就对不上了。
     */
    enum class Group(val label: String) {
        CANDIDATE("① 候选栏（顶栏）"),
        KEY("② 按键区域"),
    }

    /**
     * 可换图标的按钮。
     *
     * [resNames] 是微信输入法里这些按钮 `ImageView` 的 drawable 资源名
     * （`ToolbarTint.resName()` 读出来的真名），[IconOverlay] 按它匹配。
     * 一个按钮可能有多个资源名（亮/暗各一套），全列上。
     */
    enum class IconSlot(
        val id: String,
        val label: String,
        val resNames: List<String>,
        /** 这个图标单独的颜色（上传自定义图标后按它染色） */
        val tint: Role,
        /** 手机端二级页里不显示（网页上只提供「语音图标」一个上传口） */
        val hideInApp: Boolean = false,
    ) {
        VOICE(
            "voice", "语音图标",
            listOf(
                "icon_voice", "icon_voice_dark", "icon_voice_nor", "icon_voice_press",
                "icon_keys_voice", "icon_keys_voice_dark", "icon_keys_voice_draw",
                "icon_toolbar_voice_input", "icon_custom_toolbar_voice_input",
                "icon_voice_white", "icon_voice_gray", "icon_voice_gray_night",
            ),
            Role.ICON_VOICE,
        ),
        KEYBOARD(
            "keyboard", "切换输入法",
            listOf("icon_keyboard", "icon_keyboard_dark", "icon_switch"),
            Role.ICON_KEYBOARD,
            hideInApp = true,
        ),
        CLIPBOARD(
            "clipboard", "剪贴板",
            listOf("icon_clipboard", "icon_clipboard_dark"),
            Role.ICON_CLIPBOARD,
            hideInApp = true,
        ),
        COLLAPSE(
            "collapse", "收起键盘",
            listOf("icon_hide", "icon_hide_dark", "icon_keyboard_hide"),
            Role.ICON_COLLAPSE,
            hideInApp = true,
        ),
        BACKSPACE(
            "backspace", "退格键",
            listOf("icon_delete", "icon_delete_dark", "icon_backspace"),
            Role.ICON_BACKSPACE,
            hideInApp = true,
        ),
        ;

        companion object {
            fun byId(id: String): IconSlot? = entries.firstOrNull { it.id == id }
        }
    }

    // ------------------------------------------------------------ 解析后的缓存（:hld 进程）

    /** light/dark → (roleId → ARGB) */
    @Volatile
    private var lightColors: Map<String, Int> = emptyMap()

    @Volatile
    private var darkColors: Map<String, Int> = emptyMap()

    /** slotId → base64 png */
    @Volatile
    private var icons: Map<String, String> = emptyMap()

    /** numberId → 数值 */
    @Volatile
    private var numbers: Map<String, Float> = emptyMap()

    /** 配置版本号：每次 [load] 变化时 +1，供 [IconOverlay] 判断要不要重新解码 */
    /** 亮色专属数值（除按键圆角外的所有滑块） */
    private var lightNumbers: Map<String, Float> = emptyMap()

    /** 暗色专属数值 */
    private var darkNumbers: Map<String, Float> = emptyMap()

    @Volatile
    var revision: Int = 0
        private set

    /**
     * 这份 JSON 里到底有没有配色？
     *
     * 用来挡住「空表 / 写了一半的文件」。live 文件是网页那边流式写出来的，
     * FileObserver 经常在内容还不完整时就回调，那时解析出来 light 和 dark 都是
     * 空表。把它喂给 [load] 会把整份配色清空，键盘当场变回微信默认色。
     *
     * 判据故意宽松：颜色、图标、数值任意一处有内容就算有效。真要用「全部清空」
     * 的语义，走 [load] 传一份合法的空 JSON 即可，那条路不经过这里。
     */
    fun hasAnyColor(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        val (l, d, i) = parse(json)
        if (l.isNotEmpty() || d.isNotEmpty() || i.isNotEmpty()) return true
        return parseNumbers(json).isNotEmpty() ||
            parseNumbers(json, "numbers_light").isNotEmpty() ||
            parseNumbers(json, "numbers_dark").isNotEmpty()
    }

    /** `:hld` 侧调用：把 RemotePreferences 里的 JSON 灌进来；返回 true = 内容有变化 */
    /**
     * 把「实时预览文件」（live）**逐键**合并到应用内配置（base）上：
     * live 里定义的键覆盖 base，base 独有的键保留。
     *
     * 之前是整份替换（`live ?: base`），后果是只要 live 文件存在，
     * 应用内「颜色配置」改的圆角/投影等就全部被无视 —— live 文件里没有
     * numbers 时，用户怎么调数值都不会生效。
     */
    fun mergeJson(base: String?, live: String?): String {
        if (live.isNullOrBlank()) return base ?: "{}"
        val lb = runCatching { org.json.JSONObject(base ?: "{}") }.getOrDefault(org.json.JSONObject())
        val lo = runCatching { org.json.JSONObject(live) }.getOrNull() ?: return base ?: "{}"
        val out = org.json.JSONObject()
        val keys = LinkedHashSet<String>()
        lb.keys().forEach { keys.add(it) }
        lo.keys().forEach { keys.add(it) }
        for (k in keys) {
            val b = lb.optJSONObject(k)
            val l = lo.optJSONObject(k)
            if (b != null || l != null) {
                // 一层深度合并（light/dark/icons/numbers* 都是平面对象）
                val m = org.json.JSONObject()
                val ks = LinkedHashSet<String>()
                b?.keys()?.forEach { ks.add(it) }
                l?.keys()?.forEach { ks.add(it) }
                for (kk in ks) {
                    if (l != null && l.has(kk)) m.put(kk, l.get(kk))
                    else if (b != null) m.put(kk, b.get(kk))
                }
                out.put(k, m)
            } else if (lo.has(k)) {
                out.put(k, lo.get(k))
            } else {
                out.put(k, lb.get(k))
            }
        }
        return out.toString()
    }

    fun load(json: String?): Boolean {
        val (l, d, i) = parse(json)
        val n = parseNumbers(json)
        val ln = parseNumbers(json, "numbers_light")
        val dn = parseNumbers(json, "numbers_dark")
        if (l == lightColors && d == darkColors && i == icons && n == numbers &&
            ln == lightNumbers && dn == darkNumbers
        ) {
            return false
        }
        lightColors = l
        darkColors = d
        icons = i
        numbers = n
        lightNumbers = ln
        darkNumbers = dn
        revision++
        FkLog.d(
            "Appearance: light=${l.size} dark=${d.size} icons=${i.size} nums=${n.size}" +
                " nL=${ln.size} nD=${dn.size} rev=$revision",
        )
        return true
    }

    /**
     * 数值项；null = 用模块默认值。
     *
     * [shared] = true 的项（按键圆角）亮/暗共用一个值；其余项分亮色、暗色两套，
     * 读不到当前皮肤的专属值就退回旧的共用值（兼容老配置）。
     */
    fun number(n: Number, dark: Boolean = false): Float? {
        // 【第三步 2026-11-17】按键圆角强制成网页端默认值 9dp（与 tools/live-colors 的
        // key_corner 默认 9 一致）。所有消费点——getBgCorner hook、keyCornerPx()（描边/
        // 影子/填充）——都读这里，从而在出口层统一圆角，且描边与键面圆角同源。
        // 设为 null 即恢复配置驱动。
        if (n == Number.KEY_CORNER && FORCE_KEY_CORNER != null) return FORCE_KEY_CORNER
        if (n.shared) return numbers[n.id]
        return (if (dark) darkNumbers else lightNumbers)[n.id] ?: numbers[n.id]
    }

    /**
     * 【2026-11-17】按键底色调试强制开关。设为具体 ARGB 可强制所有普通键底色，
     * 现在设为 null = 恢复配置驱动，让网页调色台（tools/live-colors）的 key_fill 生效。
     * 发送键走独立 SEND_BG / 绿主题分支，不受 KEY_FILL 影响。
     * 需要再调试时改回 0xFF00FF86.toInt() 即可。
     */
    private val FORCE_KEY_FILL: Int? = null

    /**
     * 【2026-11-17】按键圆角调试强制开关。设为具体 dp 可强制所有按键圆角，
     * 现在设为 null = 恢复配置驱动，让网页调色台（tools/live-colors）的 key_corner 生效
     * （描边/影子/填充的 keyCornerPx() 与键面同源，都会跟着配置走）。
     * 需要再强制时改回 9f 即可。
     */
    private val FORCE_KEY_CORNER: Float? = null


    fun color(role: Role, dark: Boolean): Int? {
        if (role == Role.KEY_FILL && FORCE_KEY_FILL != null) return FORCE_KEY_FILL
        return configuredColor(role, dark)
    }

    /** 纯读用户配置，忽略 [FORCE_KEY_FILL] 等调试强制色。发送键专用路径用它，避免被调试色污染。 */
    fun configuredColor(role: Role, dark: Boolean): Int? {
        if (!dark) return lightColors[role.id]
        val d = darkColors[role.id]
        if (d != null) return d
        // 【2026-11-19 暗色主题色】「主题色」是整套配色里唯一按"调好一次、两个模式共用"设计
        // 的角色：暗色侧只配了投影/logo/候选等少数项，主题色没配就整块失效（用户报「暗色下
        // 主题色不生效」）。这里让它从亮色侧继承，其他角色不受影响（暗色仍按自己的配置走）。
        return if (role == Role.SEND_BG) lightColors[role.id] else null
    }

    /** 当前皮肤下所有覆盖（roleId → ARGB） */
    fun overrides(dark: Boolean): Map<String, Int> {
        val base = if (TEST_COLORS) TEST_PALETTE else if (dark) darkColors else lightColors
        if (TEST_COLORS || FORCE_KEY_FILL == null) return base
        // 必须返回非空 map：否则 ThemeColors.applyAppearance 在 o.isEmpty() 时早退，
        // 资源层 FILL_NAMES（white/grey 普通键）就拿不到强制色。
        return HashMap(base).also { it[Role.KEY_FILL.id] = FORCE_KEY_FILL!! }
    }

    /**
     * 【临时调试】把 10 个角色全部设成互不相同的鲜艳色，让用户一眼看出
     * 每个角色到底控制键盘上的哪一块。映射确认后把 [TEST_COLORS] 改回 false。
     *
     * 对应关系（用户按颜色回报）：
     * ```
     * 红 #FF0000  按键底色
     * 蓝 #0000FF  按键文字
     * 绿 #00FF00  角标小字
     * 品红 #FF00FF 按键投影
     * 青 #00FFFF  按键描边
     * 橙 #FF8000  按压态键面
     * 紫 #8000FF  候选词文字
     * 黄 #FFFF00  logo 着色
     * 棕 #8B4513  功能按钮背景
     * 黄绿 #BFFF00 功能按钮图标
     * ```
     */
    private const val TEST_COLORS = false

    private val TEST_PALETTE: Map<String, Int> = mapOf(
        Role.KEY_FILL.id to 0xFFFF0000.toInt(),
        Role.KEY_TEXT.id to 0xFF0000FF.toInt(),
        Role.KEY_NUMBER.id to 0xFF00FF00.toInt(),
        Role.KEY_SHADOW.id to 0xFFFF00FF.toInt(),
        Role.KEY_PRESSED.id to 0xFFFF8000.toInt(),
        Role.CANDIDATE_TEXT.id to 0xFF8000FF.toInt(),
        Role.CANDIDATE_ICON.id to 0xFFFFFF00.toInt(),
        Role.FN_BG.id to 0xFF8B4513.toInt(),
        Role.FN_ICON.id to 0xFFBFFF00.toInt(),
    )

    fun iconBase64(slot: IconSlot): String? = icons[slot.id]

    fun hasIcons(): Boolean = icons.isNotEmpty()

    /** 有没有任何一个图标单独配了颜色 */
    fun hasIconTints(): Boolean =
        IconSlot.entries.any { slot -> color(slot.tint, false) != null || color(slot.tint, true) != null }

    /** 这个槽位的图标颜色（当前皮肤下） */
    fun iconTint(slot: IconSlot, dark: Boolean): Int? = color(slot.tint, dark)

    // ------------------------------------------------------------ JSON 工具（两边共用）

    /** 解析成 (亮色表, 暗色表, 图标表)；任何一段坏掉只丢那一段 */
    fun parse(json: String?): Triple<Map<String, Int>, Map<String, Int>, Map<String, String>> {
        if (json.isNullOrBlank()) return Triple(emptyMap(), emptyMap(), emptyMap())
        val obj = runCatching { JSONObject(json) }.getOrNull()
            ?: return Triple(emptyMap(), emptyMap(), emptyMap())
        return Triple(
            readColors(obj.optJSONObject("light")),
            readColors(obj.optJSONObject("dark")),
            readIcons(obj.optJSONObject("icons")),
        )
    }

    /** 解析 `numbers` / `numbers_light` / `numbers_dark` 段（`ColorConfigActivity` 也用） */
    @JvmOverloads
    fun parseNumbers(json: String?, key: String = "numbers"): Map<String, Float> {
        if (json.isNullOrBlank()) return emptyMap()
        val o = runCatching { JSONObject(json).optJSONObject(key) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Float>()
        o.keys().forEach { k ->
            val v = o.optDouble(k, Double.NaN)
            if (!v.isNaN()) out[k] = v.toFloat()
        }
        return out
    }

    private fun readColors(o: JSONObject?): Map<String, Int> {
        if (o == null) return emptyMap()
        val out = HashMap<String, Int>()
        o.keys().forEach { k ->
            val raw = o.optString(k, "")
            val v = runCatching { raw.toLong(16).toInt() }.getOrNull()
            if (raw.isNotEmpty() && v != null) out[k] = v
        }
        return out
    }

    private fun readIcons(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = HashMap<String, String>()
        o.keys().forEach { k ->
            val v = o.optString(k, "")
            if (v.isNotEmpty()) out[k] = v
        }
        return out
    }

    /** 把一张图编码成 JSON 里存的 base64（设置页用） */
    fun encodeIcon(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** `:hld` 侧解码成 Bitmap；失败返回 null（不崩，按钮保持原图） */
    fun decodeIcon(slot: IconSlot): Bitmap? {
        val b64 = iconBase64(slot) ?: return null
        return runCatching {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}

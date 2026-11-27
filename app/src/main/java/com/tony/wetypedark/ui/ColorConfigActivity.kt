package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.itos.coui.backdrop.CouiBackdrop
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.chip.ChipTabRow
import com.itos.coui.chip.CouiChipTabItem
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.layout.Scaffold
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.slider.CouiSliderDefaults
import com.itos.coui.slider.Slider
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.rememberCouiTextStyle
import com.itos.coui.theme.CouiTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 「颜色配置」：两级结构。
 *
 * 一级是卡片列表（面板 / 候选栏 / 按键 / 发送键 / 按键高光描边 / 按键投影 / 功能键 / Logo / 顶部高光 / 主题色），
 * 点进某一项才是二级页，只显示这一项自己的颜色、圆角、模糊等参数。
 *
 * 圆角不再统一到一处：按键圆角在「按键」里，候选框圆角在「候选栏」里，面板圆角在「面板」里。
 *
 * 数据源：微信输入法目录里的 `appearance-live.json`（电脑调色台和 :hld 模块读的同一份）；
 * 面板背景模糊存在 `wetype-dark.json`（模块运行时的实时配置文件）。
 */
class ColorConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = !resolveIsLightTheme(this)
        enableEdgeToEdge()
        setContent {
            CouiTheme(darkTheme = dark) {
                ColorConfigPage(dark = dark, onBack = { finish() })
            }
        }
    }
}

// ------------------------------------------------------------------ 数据

private object LivePalette {
    private const val MEDIA =
        "/data/media/0/Android/data/com.tencent.wetype/files/appearance-live.json"
    private const val SD = "/sdcard/Android/data/com.tencent.wetype/files/appearance-live.json"

    /** 模块运行时的实时配置（面板模糊 / 面板色），和 serve.py 写的是同一个文件 */
    private const val RUNTIME = "/data/user/0/com.tencent.wetype/files/wetype-dark.json"

    fun read(context: Context): JSONObject? {
        runCatching { java.io.File(MEDIA).readText() }.getOrNull()?.let { raw ->
            runCatching { return JSONObject(raw) }
        }
        val raw = suCat(MEDIA) ?: suCat(SD) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    fun write(context: Context, obj: JSONObject) {
        val text = obj.toString()
        Thread { AppearanceLiveSync.sync(context, text) }.start()
    }

    fun readRuntime(context: Context): JSONObject? {
        val raw = runCatching { java.io.File(RUNTIME).readText() }.getOrNull() ?: suCat(RUNTIME)
        return raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun writeRuntime(context: Context, obj: JSONObject) {
        val tmp = java.io.File(context.cacheDir, "wetype-dark.json")
        runCatching { tmp.writeText(obj.toString()) }
        su("cp '${tmp.absolutePath}' '$RUNTIME' && chmod 644 '$RUNTIME'")
    }

    private fun suCat(path: String): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '$path'"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (p.exitValue() == 0 && out.isNotBlank()) out else null
    }.getOrNull()

    private fun su(cmd: String): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor()
        p.exitValue() == 0
    }.getOrDefault(false)
}

private val THEME_KEYS = listOf("light", "dark", "ultra")
private val THEME_NAMES = listOf("亮色", "暗色", "极暗")

/** 圆角的最终值在顶层也有副本，两处一起写 */
private val RADIUS_KEYS = setOf("key_radius_dp", "cand_radius_dp", "panel_radius_dp")

/** 主题配置只包含这三套模式 + 顶层圆角副本 */
private val PALETTE_COPY_KEYS = setOf("light", "dark", "ultra", "key_radius_dp", "cand_radius_dp", "panel_radius_dp")

private fun deepCopy(value: Any?): Any? = when (value) {
    is JSONObject -> JSONObject(value.toString())
    is JSONArray -> JSONArray(value.toString())
    else -> value
}

private fun paletteSnapshot(src: JSONObject): JSONObject {
    val out = JSONObject()
    PALETTE_COPY_KEYS.forEach { k ->
        val v = src.opt(k)
        if (v != null) out.put(k, deepCopy(v))
    }
    return out
}

private fun copyPaletteInto(dst: JSONObject, src: JSONObject) {
    PALETTE_COPY_KEYS.forEach { k ->
        val v = src.opt(k)
        if (v != null) dst.put(k, deepCopy(v))
    }
}

private fun containsJsonString(arr: JSONArray, value: String): Boolean =
    (0 until arr.length()).any { arr.optString(it, "") == value }

private fun themeNamesOf(obj: JSONObject?): List<String> {
    val profiles = obj?.optJSONObject("theme_profiles") ?: return listOf("默认")
    val order = obj.optJSONArray("theme_order")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { n -> n.isNotEmpty() } }
    } ?: profiles.keys().asSequence().toList()
    val names = order.filter { profiles.has(it) }
    return if (names.contains("默认")) listOf("默认") + names.filter { it != "默认" }
    else listOf("默认") + names
}

/** 面板模糊不在 appearance 里，走运行时配置，用这个伪 key 区分 */
private const val LIVE_BLUR_KEY = "@panel_blur"

private data class DetailColor(val label: String, val key: String)

private data class DetailNum(
    val label: String,
    val key: String,
    val min: Float,
    val max: Float,
    val steps: Int = 0,
    val unit: String = "",
    val isInt: Boolean = false,
)

private data class ItemDef(
    val id: String,
    val title: String,
    val summary: String,
    val iconRes: Int,
    val colors: List<DetailColor> = emptyList(),
    val nums: List<DetailNum> = emptyList(),
    /** 按键的纯色 / 渐变切换 + 渐变专属色标 */
    val gradient: Boolean = false,
    val gradColors: List<DetailColor> = emptyList(),
    val gradNums: List<DetailNum> = emptyList(),
)

private val CATALOG: List<ItemDef> = listOf(
    ItemDef(
        id = "panel",
        title = "面板",
        summary = "面板底色、圆角，以及\n背景模糊半径",
        iconRes = R.drawable.ic_row_window,
        colors = listOf(DetailColor("面板背景", "panel_bg")),
        nums = listOf(
            DetailNum("面板圆角", "panel_radius_dp", 0f, 40f, 39, " dp", true),
            DetailNum("模糊半径", LIVE_BLUR_KEY, 0f, 40f, 39, " dp", true),
            DetailNum("高光透明", "top_glow_alpha", 0f, 100f, 0, "%", true),
            DetailNum("高光高度", "top_glow_px", 0f, 120f, 119, " px", true),
            DetailNum("高光宽度", "top_glow_width", 0f, 8f, 15, " px", false),
            DetailNum("高光模糊", "top_glow_blur", 0f, 48f, 49, " px", false),
        ),
    ),
    ItemDef(
        id = "cand",
        title = "候选栏",
        summary = "候选词底色、圆角，\n都在这里调",
        iconRes = R.drawable.ic_cfg_cand,
        colors = listOf(DetailColor("候选框底色", "cand_bg")),
        nums = listOf(DetailNum("候选框圆角", "cand_radius_dp", 0f, 40f, 39, " dp", true)),
    ),
    ItemDef(
        id = "key",
        title = "按键",
        summary = "键面颜色、圆角，以及\n渐变和按下色",
        iconRes = R.drawable.ic_cfg_key,
        colors = listOf(
            DetailColor("按键颜色", "key_fill"),
            DetailColor("按键文字", "key_text"),
            DetailColor("小字颜色", "key_small_text"),
            DetailColor("按键暗下色", "key_press_bg"),
        ),
        nums = listOf(DetailNum("按键圆角", "key_radius_dp", 0f, 40f, 39, " dp", true)),
        gradient = true,
        gradColors = listOf(
            DetailColor("渐变起色", "key_grad_c1"),
            DetailColor("渐变止色", "key_grad_c2"),
        ),
        gradNums = listOf(
            DetailNum("渐变角度", "key_grad_angle", 0f, 360f, 71, "°", true),
            DetailNum("整体透明", "key_grad_alpha", 0f, 100f, 0, "%", true),
        ),
    ),
    ItemDef(
        id = "send",
        title = "发送键",
        summary = "七种状态色与禁用\n透明度都在这里",
        iconRes = R.drawable.ic_cfg_send,
        colors = listOf(
            DetailColor("可发送文字", "send_active_text"),
            DetailColor("灰色态底", "send_grey_bg"),
            DetailColor("灰色态字", "send_grey_text"),
            DetailColor("禁用态底", "send_disabled_bg"),
            DetailColor("禁用态字", "send_disabled_text"),
        ),
        nums = listOf(
            DetailNum("禁用水印", "send_disabled_alpha", 0f, 100f, 0, "%", true),
            DetailNum("遮罩透明", "send_mask_alpha", 0f, 100f, 0, "%", true),
        ),
    ),
    ItemDef(
        id = "rim",
        title = "按键高光描边",
        summary = "只贴在按键内侧的\n一圈渐变描边",
        iconRes = R.drawable.ic_cfg_rim,
        nums = listOf(
            DetailNum("描边宽度", "key_rim_px", 0f, 8f, 7, " px", true),
            DetailNum("描边透明", "key_rim_alpha", 0f, 100f, 0, "%", true),
            DetailNum("整体透明", "key_rim_alpha_all", 0f, 100f, 0, "%", true),
            DetailNum("描边方向角", "key_rim_angle", 0f, 360f, 71, "°", true),
            DetailNum("描边模糊", "key_rim_blur", 0f, 8f, 0, " dp", false),
        ),
    ),
    ItemDef(
        id = "shadow",
        title = "按键投影",
        summary = "按键外圈的投影颜色\n与模糊范围",
        iconRes = R.drawable.ic_cfg_shadow,
        colors = listOf(DetailColor("投影颜色", "key_shadow")),
        nums = listOf(
            DetailNum("投影透明", "key_shadow_alpha", 0f, 100f, 0, "%", true),
            DetailNum("模糊半径", "key_shadow_blur_dp", 0f, 24f, 23, " dp", true),
            DetailNum("Y 轴位移", "key_shadow_dy_dp", 0f, 24f, 47, " dp", false),
        ),
    ),
    ItemDef(
        id = "fn",
        title = "功能键",
        summary = "功能键的文字与图标\n颜色都能改",
        iconRes = R.drawable.ic_row_swipe,
        colors = listOf(
            DetailColor("功能键文字", "key_fn_text"),
            DetailColor("功能键图标", "fn_icon"),
            DetailColor("Logo 圆圈", "logo_circle"),
            DetailColor("Logo 字", "logo_glyph"),
        ),
    ),

)

// ------------------------------------------------------------------ 页面

@Composable
private fun ColorConfigPage(dark: Boolean, onBack: () -> Unit) {
    val colors = CouiPageDefaults.themeColors(dark)
    val context = LocalContext.current
    var palette by remember { mutableStateOf<JSONObject?>(null) }
    var live by remember { mutableStateOf<JSONObject?>(null) }
    var themeIndex by remember { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<DetailColor?>(null) }
    var activeTheme by remember { mutableStateOf("默认") }
    var createThemeOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = LivePalette.read(context)
        if (loaded != null) {
            val profiles = loaded.optJSONObject("theme_profiles")
            if (profiles == null) {
                // 第一次进来：把当前配置存成「默认」主题
                val created = JSONObject()
                created.put("默认", paletteSnapshot(loaded))
                loaded.put("theme_profiles", created)
                loaded.put("theme_order", JSONArray().put("默认"))
                loaded.put("active_theme", "默认")
                palette = JSONObject(loaded.toString())
                LivePalette.write(context, loaded)
            } else {
                activeTheme = loaded.optString("active_theme", "默认")
                    .takeIf { profiles.has(it) } ?: "默认"
                palette = loaded
            }
        }
        live = LivePalette.readRuntime(context)
    }

    val themeObj = palette?.optJSONObject(THEME_KEYS[themeIndex])
    val currentItem = CATALOG.firstOrNull { it.id == openId }
    val gradOn = themeObj?.optString("key_mode", "gradient") != "solid"

    fun push(obj: JSONObject) {
        // 改了哪套主题，就同步回它的存档
        obj.optJSONObject("theme_profiles")?.put(activeTheme, paletteSnapshot(obj))
        palette = JSONObject(obj.toString())
        LivePalette.write(context, obj)
    }

    /** 起止色改动同步到多色标的首尾；没有色标就补一组两点色标 */
    fun syncGradStops(t: JSONObject, key: String, hex: String) {
        val arr = t.optJSONArray("key_grad_stops") ?: JSONArray().also { a ->
            a.put(JSONObject().put("p", 0.0).put("c", t.optString("key_grad_c1", hex)))
            a.put(JSONObject().put("p", 1.0).put("c", t.optString("key_grad_c2", hex)))
            t.put("key_grad_stops", a)
        }
        if (arr.length() < 2) return
        val idx = if (key == "key_grad_c1") 0 else arr.length() - 1
        val item = arr.optJSONObject(idx)
        if (item != null) item.put("c", hex)
        else arr.put(idx, JSONObject().put("p", if (idx == 0) 0.0 else 1.0).put("c", hex))
    }

    fun putColor(spec: DetailColor, color: Color) {
        val obj = palette ?: return
        val t = obj.optJSONObject(THEME_KEYS[themeIndex]) ?: JSONObject().also {
            obj.put(THEME_KEYS[themeIndex], it)
        }
        val hex = hexArgb(color)
        t.put(spec.key, hex)
        // 可发送底色默认跟随主题色（两者在模块里就是同一个值 send_bg）；
        // 关掉跟随之后各写各的，不再互相覆盖。
        when (spec.key) {
            "accent" -> if (t.optBoolean("send_bg_follow", true)) t.put("send_bg", hex)
            "send_bg" -> t.put("send_bg_follow", false)
        }
        // 模块优先读多色标 key_grad_stops，起止色只在没有色标时生效；
        // 所以改起止色时必须把色标的首尾一起换掉，否则手机上看不到变化。
        if (spec.key == "key_grad_c1" || spec.key == "key_grad_c2") syncGradStops(t, spec.key, hex)
        push(obj)
    }

    fun putNum(spec: DetailNum, value: Float) {
        if (spec.key == LIVE_BLUR_KEY) {
            val obj = live?.let { JSONObject(it.toString()) } ?: JSONObject()
            obj.put("blurRadiusDp", value.toInt())
            // 面板色以 appearance 为准，别让运行时文件里旧的 panelColor 覆盖回去
            obj.remove("panelColor")
            live = obj
            LivePalette.writeRuntime(context, obj)
            return
        }
        val obj = palette ?: return
        val t = obj.optJSONObject(THEME_KEYS[themeIndex]) ?: JSONObject().also {
            obj.put(THEME_KEYS[themeIndex], it)
        }
        t.put(spec.key, if (spec.isInt) value.toInt() else value.toDouble())
        if (spec.key in RADIUS_KEYS) obj.put(spec.key, value.toInt())
        push(obj)
    }

    fun numOf(spec: DetailNum): Float {
        if (spec.key == LIVE_BLUR_KEY) {
            return (live?.optInt("blurRadiusDp", 24) ?: 24).toFloat()
        }
        val t = themeObj?.opt(spec.key)
        return when (t) {
            is Number -> t.toFloat()
            is String -> t.toFloatOrNull() ?: 0f
            else -> palette?.opt(spec.key)?.let { if (it is Number) it.toFloat() else 0f } ?: 0f
        }
    }

    fun setSendFollow(follow: Boolean) {
        val obj = palette ?: return
        val t = obj.optJSONObject(THEME_KEYS[themeIndex]) ?: JSONObject().also {
            obj.put(THEME_KEYS[themeIndex], it)
        }
        t.put("send_bg_follow", follow)
        if (follow) t.put("send_bg", t.optString("accent", "#FF4C8DFF"))
        push(obj)
    }

    fun setKeyMode(mode: String) {
        val obj = palette ?: return
        obj.optJSONObject(THEME_KEYS[themeIndex])?.put("key_mode", mode)
        push(obj)
    }

    fun switchTheme(name: String) {
        if (name == activeTheme) return
        val obj = palette ?: return
        val profile = obj.optJSONObject("theme_profiles")?.optJSONObject(name) ?: return
        copyPaletteInto(obj, profile)
        obj.put("active_theme", name)
        activeTheme = name
        push(obj)
    }

    fun createTheme(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        val obj = palette ?: return
        val profiles = obj.optJSONObject("theme_profiles")
            ?: JSONObject().also { obj.put("theme_profiles", it) }
        if (profiles.has(name)) return
        // 以当前正在用的这套为底，复制成一套新主题
        profiles.put(name, paletteSnapshot(obj))
        val order = obj.optJSONArray("theme_order") ?: JSONArray().also { obj.put("theme_order", it) }
        if (!containsJsonString(order, name)) order.put(name)
        obj.put("active_theme", name)
        activeTheme = name
        push(obj)
        createThemeOpen = false
    }

    val themeNames = themeNamesOf(palette)
    // 给 chip 栏一个不透明的兄弟源，否则 chip 采样不到背景会发黑
    val chipBackdrop = rememberCouiBackdrop(diagnosticName = "color-config-bg")

    BackHandler(enabled = openId != null) { openId = null }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.background,
        windowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(dark) {
                    if (openId != null) openId = null else onBack()
                }
                Text(
                    text = currentItem?.title ?: "颜色配置",
                    style = rememberCouiTextStyle(CouiTextKind.GroupTitle)
                        .copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.title,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .couiBackdrop(chipBackdrop)
                .background(colors.background)
        )
        AnimatedContent(
            targetState = currentItem,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            transitionSpec = {
                val forward = targetState != null
                (slideInHorizontally(tween(260)) { if (forward) it / 4 else -it / 4 } +
                    fadeIn(tween(200)))
                    .togetherWith(
                        slideOutHorizontally(tween(260)) { if (forward) -it / 6 else it / 6 } +
                            fadeOut(tween(150))
                    )
            },
            label = "color-detail",
        ) { item ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ThemeChipRow(
                    names = themeNames,
                    selected = activeTheme,
                    dark = dark,
                    backdrop = chipBackdrop,
                    onSelect = { switchTheme(it) },
                    onAdd = { createThemeOpen = true },
                )
                Spacer(Modifier.height(10.dp))
                ThemeSegment(
                    selected = themeIndex,
                    dark = dark,
                    onSelected = { themeIndex = it },
                )
            }

            if (item == null) {
                // ---------------- 一级：卡片列表（两列）
                Group {
                    ColorRow(
                        label = "主题色",
                        color = parseColor(themeObj?.optString("accent")),
                        dark = dark,
                        onClick = { editing = DetailColor("主题色", "accent") },
                    )
                }
                Spacer(Modifier.height(14.dp))
                CATALOG.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Max)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        pair.forEach { def ->
                            ItemCard(
                                item = def,
                                dark = dark,
                                modifier = Modifier.weight(1f),
                                onClick = { openId = def.id },
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = "每一项都直接写进 appearance-live.json —— 和电脑网页调色台是同一份配置，改完立刻生效。",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.summary,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                )
            } else {
                // ---------------- 二级：这一项自己的参数
                val sendFollow = themeObj?.optBoolean("send_bg_follow", true) ?: true
                if (item.id == "send") {
                    Group(title = "可发送底色") {
                        Item(
                            onClick = { setSendFollow(!sendFollow) },
                            title = {
                                Text(
                                    text = "跟随主题色",
                                    style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                                    color = colors.title,
                                )
                            },
                            summary = {
                                Text(
                                    text = if (sendFollow) "可发送底色就是主题色，改主题色这里会一起变"
                                    else "已单独设置，不再跟主题色走",
                                    style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                                    color = colors.summary,
                                )
                            },
                            trailing = {
                                Switch(checked = sendFollow, onCheckedChange = null, dark = dark)
                            },
                        )
                        if (!sendFollow) {
                            Divider(color = colors.divider)
                            ColorRow(
                                label = "自定义底色",
                                color = parseColor(themeObj?.optString("send_bg")),
                                dark = dark,
                                onClick = { editing = DetailColor("自定义底色", "send_bg") },
                            )
                        }
                    }
                }
                if (item.colors.isNotEmpty()) {
                    Group(title = "颜色") {
                        item.colors.forEachIndexed { index, spec ->
                            if (index > 0) Divider(color = colors.divider)
                            ColorRow(
                                label = spec.label,
                                color = parseColor(themeObj?.optString(spec.key)),
                                dark = dark,
                                onClick = { editing = spec },
                            )
                        }
                    }
                }

                if (item.gradient) {
                    Spacer(Modifier.height(10.dp))
                    Group(title = "渐变模式") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Segmented(
                                options = listOf("纯色", "渐变"),
                                selected = if (gradOn) 1 else 0,
                                dark = dark,
                                onSelected = { index ->
                                    setKeyMode(if (index == 1) "gradient" else "solid")
                                },
                            )
                            if (!gradOn) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "纯色模式只用上面的「按键颜色」；要改多色标渐变先切到渐变。",
                                    style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                                    color = colors.summary,
                                )
                            }
                        }
                    }
                    if (gradOn) {
                        Spacer(Modifier.height(10.dp))
                        Group(title = "渐变色标") {
                            item.gradColors.forEachIndexed { index, spec ->
                                if (index > 0) Divider(color = colors.divider)
                                ColorRow(
                                    label = spec.label,
                                    color = parseColor(themeObj?.optString(spec.key)),
                                    dark = dark,
                                    onClick = { editing = spec },
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Group(title = "渐变数值") {
                            item.gradNums.forEachIndexed { index, spec ->
                                if (index > 0) Divider(color = colors.divider)
                                SliderRow(
                                    spec = spec,
                                    value = numOf(spec),
                                    dark = dark,
                                    onCommit = { putNum(spec, it) },
                                )
                            }
                        }
                    }
                }

                if (item.nums.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Group(title = "数值") {
                        item.nums.forEachIndexed { index, spec ->
                            if (index > 0) Divider(color = colors.divider)
                            SliderRow(
                                spec = spec,
                                value = numOf(spec),
                                dark = dark,
                                onCommit = { putNum(spec, it) },
                            )
                        }
                    }
                }

                if (item.id == "key") {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "起色 / 止色会同步到色标首尾；中间几段色标仍在电脑调色台上编辑。",
                        style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                        color = colors.summary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        }
        }
    }

    if (createThemeOpen) {
        ThemeNameDialog(
            dark = dark,
            onDismiss = { createThemeOpen = false },
            onConfirm = { createTheme(it) },
        )
    }

    val target = editing
    if (target != null) {
        val initial = parseColor(themeObj?.optString(target.key)) ?: Color.White
        ColorEditorDialog(
            title = target.label,
            initial = initial,
            dark = dark,
            onDismiss = { editing = null },
            onConfirm = {
                putColor(target, it)
                editing = null
            },
        )
    }
}

// ------------------------------------------------------------------ 组件

@Composable
private fun ItemCard(
    item: ItemDef,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(CouiPageDefaults.shape)
            .background(colors.card)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Icon(
            painter = painterResource(item.iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = colors.title,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.title,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.summary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = colors.summary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BackButton(dark: Boolean, onClick: () -> Unit) {
    val colors = CouiPageDefaults.themeColors(dark)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CouiCapsuleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "‹", fontSize = 26.sp, color = colors.title)
    }
}

@Composable
private fun ThemeChipRow(
    names: List<String>,
    selected: String,
    dark: Boolean,
    backdrop: CouiBackdrop,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val addKey = "__coui_add_theme__"
    val items = names.map { CouiChipTabItem(key = it, label = it) } +
        CouiChipTabItem(key = addKey, label = "+")
    ChipTabRow(
        modifier = Modifier.fillMaxWidth(),
        items = items,
        selectedKey = selected,
        onSelectedKeyChange = { key ->
            if (key == addKey) onAdd()
            else (key as? String)?.let { if (it != selected) onSelect(it) }
        },
        backdrop = backdrop,
        dark = dark,
        itemSpacing = 8.dp,
    )
}

@Composable
private fun ThemeNameDialog(dark: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val colors = CouiPageDefaults.themeColors(dark)
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.card)
                .padding(18.dp),
        ) {
            Text(
                text = "新建主题",
                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                color = colors.title,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("主题名称", color = colors.summary) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.title,
                    unfocusedTextColor = colors.title,
                    cursorColor = colors.accent,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "取消",
                    color = colors.summary,
                    modifier = Modifier
                        .clip(CouiCapsuleShape)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Text(
                    text = "创建",
                    color = if (name.isBlank()) colors.summary else colors.accent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(CouiCapsuleShape)
                        .clickable(enabled = name.isNotBlank()) { onConfirm(name) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ThemeSegment(selected: Int, dark: Boolean, onSelected: (Int) -> Unit) {
    if (Build.VERSION.SDK_INT >= 34) {
        // 和「按键文字位置」页同一个 COUI 长胶囊分段控件
        SegmentedControl(
            modifier = Modifier.fillMaxWidth(),
            labels = THEME_NAMES,
            selectedIndex = selected,
            onSelected = onSelected,
            dark = dark,
        )
    } else {
        Segmented(options = THEME_NAMES, selected = selected, dark = dark, onSelected = onSelected)
    }
}

@Composable
private fun Segmented(
    options: List<String>,
    selected: Int,
    dark: Boolean,
    onSelected: (Int) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    val container = Color(if (dark) 0x1FFFFFFF else 0x0F000000)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(CouiCapsuleShape)
            .background(container)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, name ->
            val on = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(CouiCapsuleShape)
                    .background(if (on) colors.accent else Color.Transparent)
                    .clickable { onSelected(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name,
                    style = rememberCouiTextStyle(CouiTextKind.ItemValue).copy(fontSize = 13.sp),
                    color = if (on) Color.White else colors.title,
                )
            }
        }
    }
}

@Composable
private fun ColorRow(label: String, color: Color?, dark: Boolean, onClick: () -> Unit) {
    val colors = CouiPageDefaults.themeColors(dark)
    Item(
        onClick = onClick,
        title = {
            Text(
                text = label,
                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                color = colors.title,
            )
        },
        summary = {
            Text(
                text = if (color != null) hexArgb(color) else "跟随默认",
                style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                color = colors.summary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color ?: Color.Transparent)
                    .border(1.dp, colors.divider, RoundedCornerShape(6.dp)),
            )
        },
    )
}

@Composable
private fun SliderRow(spec: DetailNum, value: Float, dark: Boolean, onCommit: (Float) -> Unit) {
    val colors = CouiPageDefaults.themeColors(dark)
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = spec.label,
                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                color = colors.title,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = format(spec, local),
                style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                color = colors.summary,
            )
        }
        Slider(
            modifier = Modifier.fillMaxWidth(),
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = spec.min..spec.max,
            steps = spec.steps,
            colors = CouiSliderDefaults.colors(colors.accent, dark),
            dark = dark,
        )
    }
}

private fun format(spec: DetailNum, value: Float): String =
    if (spec.isInt) "${value.toInt()}${spec.unit}"
    else String.format(Locale.US, "%.1f%s", value, spec.unit)

/**
 * 取色器：HSV 方块（横轴饱和度、纵轴明度）+ 色相条 + 透明条，纯 Compose 绘制。
 */
@Composable
private fun ColorEditorDialog(
    title: String,
    initial: Color,
    dark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableFloatStateOf(initial.alpha) }

    val current = remember(hue, sat, value, alpha) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))).copy(alpha = alpha)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.card)
                .padding(18.dp),
        ) {
            Text(
                text = title,
                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                color = colors.title,
            )
            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.White,
                                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))),
                            )
                        )
                    )
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    .pointerInput(hue) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                applySv(offset, size.width.toFloat(), size.height.toFloat()) { s, v ->
                                    sat = s; value = v
                                }
                            },
                        ) { change, _ ->
                            applySv(change.position, size.width.toFloat(), size.height.toFloat()) { s, v ->
                                sat = s; value = v
                            }
                            change.consume()
                        }
                    }
                    .pointerInput(hue) {
                        detectTapGestures { offset ->
                            applySv(offset, size.width.toFloat(), size.height.toFloat()) { s, v ->
                                sat = s; value = v
                            }
                        }
                    },
            )
            Spacer(Modifier.height(14.dp))

            Slider(
                modifier = Modifier.fillMaxWidth(),
                value = hue,
                onValueChange = { hue = it },
                valueRange = 0f..360f,
                colors = CouiSliderDefaults.colors(
                    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))),
                    dark,
                ),
                dark = dark,
            )
            Slider(
                modifier = Modifier.fillMaxWidth(),
                value = alpha,
                onValueChange = { alpha = it },
                valueRange = 0f..1f,
                colors = CouiSliderDefaults.colors(current, dark),
                dark = dark,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(current)
                        .border(1.dp, colors.divider, RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = hexArgb(current).substring(2),
                    style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                    color = colors.summary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "取消",
                    style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                    color = colors.summary,
                    modifier = Modifier
                        .clip(CouiCapsuleShape)
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "确定",
                    style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                    color = colors.accent,
                    modifier = Modifier
                        .clip(CouiCapsuleShape)
                        .clickable { onConfirm(current) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun applySv(offset: Offset, w: Float, h: Float, set: (Float, Float) -> Unit) {
    if (w <= 1f || h <= 1f) return
    set((offset.x / w).coerceIn(0f, 1f), (1f - offset.y / h).coerceIn(0f, 1f))
}

// ------------------------------------------------------------------ 颜色工具

private fun hexArgb(color: Color): String = String.format(Locale.US, "#%08X", color.toArgb())

private fun parseColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val s = hex.trim().trimStart('#')
    return runCatching {
        when (s.length) {
            8 -> Color(s.substring(2).toLong(16) or (s.substring(0, 2).toLong(16) shl 24))
            6 -> Color(s.toLong(16) or 0xFF000000L)
            3 -> {
                val r = "" + s[0] + s[0]
                val g = "" + s[1] + s[1]
                val b = "" + s[2] + s[2]
                Color((r + g + b).toLong(16) or 0xFF000000L)
            }
            else -> null
        }
    }.getOrNull()
}

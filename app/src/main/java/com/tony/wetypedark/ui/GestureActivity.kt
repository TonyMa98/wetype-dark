package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.layout.Scaffold
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.slider.CouiSliderDefaults
import com.itos.coui.slider.Slider
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.rememberCouiTextStyle
import com.itos.coui.theme.CouiTheme

/**
 * 「下滑功能」二级页（2026-11-22 由 View 版迁到 Compose + COUI-Kit）。
 *
 * 结构不变：
 *   ① 布局切换（全键盘 / 九键）  ② 键盘预览（点按键直接绑定）
 *   ③ 六个开关  ④ 标签样式（两色 + 四个滑杆）  ⑤ 按键绑定表
 *
 * 落盘仍是 [GestureSettings].toJson() → RemotePreferences + [HostConfigSync]。
 */
class GestureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = !resolveIsLightTheme(this)
        enableEdgeToEdge()
        LsposedClient.get(this)
        setContent {
            CouiTheme(darkTheme = dark) {
                GesturePage(dark = dark, onBack = { finish() })
            }
        }
    }
}

// ------------------------------------------------------------------ 存储

private fun readGestureSettings(context: android.content.Context): Pair<GestureSettings, Boolean> {
    val prefs = LsposedClient.remote(context, ConfigStore.GROUP)
    val raw = runCatching {
        prefs?.getString(ConfigStore.KEY_GESTURE_SETTINGS, null)
    }.getOrNull()
    return GestureSettings.fromJson(raw) to (raw != null)
}

private fun writeGestureSettings(context: android.content.Context, s: GestureSettings) {
    val prefs = LsposedClient.remote(context, ConfigStore.GROUP)
    if (prefs == null) {
        Toast.makeText(context, "LSPosed 未连接，配置暂未保存", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        prefs.edit().putString(ConfigStore.KEY_GESTURE_SETTINGS, s.toJson()).commit()
        HostConfigSync.sync(context)
    }.onFailure { FkLog.e("gesture: save failed", it) }
}

// ------------------------------------------------------------------ 页面

private val LABEL_PALETTE = intArrayOf(
    0xFF0A6AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF3B30.toInt(),
    0xFFFF2D55.toInt(), 0xFFAF52DE.toInt(), 0xFF5AC8FA.toInt(), 0xFF00C7BE.toInt(),
    0xFF8E8E93.toInt(), 0xFF141416.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFD60A.toInt(),
)

@Composable
private fun GesturePage(dark: Boolean, onBack: () -> Unit) {
    val colors = CouiPageDefaults.themeColors(dark)
    val context = LocalContext.current
    val accent = colors.accent

    var settings by remember { mutableStateOf<GestureSettings?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    var layoutSel by remember { mutableStateOf(KeyLayout.QWERTY) }
    var pickerKey by remember { mutableStateOf<String?>(null) }
    var colorTarget by remember { mutableStateOf<Int?>(null) }   // 0 = 浅色，1 = 深色

    LaunchedEffect(Unit) {
        val (s, ok) = readGestureSettings(context)
        settings = s
        loaded = ok
        LsposedClient.whenBound {
            Handler(Looper.getMainLooper()).post {
                if (!loaded) {
                    val (fresh, ok2) = readGestureSettings(context)
                    settings = fresh
                    loaded = ok2
                }
            }
        }
    }

    BackHandler { onBack() }

    val s = settings ?: run {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = colors.background,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {}
        return
    }

    fun save() {
        writeGestureSettings(context, s)
        rev++
    }

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
                CapBack(dark) { onBack() }
                Text(
                    text = "下滑功能",
                    style = rememberCouiTextStyle(CouiTextKind.GroupTitle)
                        .copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.title,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            // ① 布局
            item(key = "layout") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val labels = KeyLayout.values().map { it.label }
                    if (Build.VERSION.SDK_INT >= 34) {
                        SegmentedControl(
                            modifier = Modifier.fillMaxWidth(),
                            labels = labels,
                            selectedIndex = layoutSel.ordinal,
                            onSelected = { i -> layoutSel = KeyLayout.values()[i] },
                            dark = dark,
                        )
                    } else {
                        PlainSegment(
                            labels = labels,
                            selected = layoutSel.ordinal,
                            dark = dark,
                            onSelected = { i -> layoutSel = KeyLayout.values()[i] },
                        )
                    }
                }
            }

            // ② 预览
            item(key = "preview") {
                Group(title = "键盘预览 · 点按键直接绑定") {
                    Box(Modifier.padding(10.dp)) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (s.enabled) 1f else 0.45f),
                            factory = { ctx ->
                                GestureKeyboardPreview(ctx).apply {
                                    layout = layoutSel
                                    settings = s
                                }
                            },
                            update = { view ->
                                view.layout = layoutSel
                                view.settings = s
                                view.onKeyTap = { key -> pickerKey = key }
                                view.invalidate()
                            },
                        )
                    }
                }
            }

            // ③ 开关
            item(key = "toggles") {
                Group(title = "手势") {
                    ToggleRow("启用按键手势", "滑动按键，执行绑定动作", s.enabled, dark) {
                        s.enabled = it
                        save()
                    }
                    Divider(color = colors.divider)
                    ToggleRow("密码框禁用", "密码输入时保留原按键行为", s.disableInPassword, dark) {
                        s.disableInPassword = it
                        save()
                    }
                    Divider(color = colors.divider)
                    ToggleRow("触感反馈", "动作执行成功后振动", s.haptics, dark) {
                        s.haptics = it
                        save()
                    }
                    Divider(color = colors.divider)
                    ToggleRow("显示底部标签", "在按键下方显示绑定动作", s.showLabel, dark) {
                        s.showLabel = it
                        save()
                    }
                    Divider(color = colors.divider)
                    ToggleRow(
                        "跟随全局文字色",
                        "下滑面板小字跟随「滑动面板小字」配色（网页可单独调）；关掉则用下面的自定义颜色",
                        s.useAppearanceColor, dark,
                    ) { s.useAppearanceColor = it; save() }
                    Divider(color = colors.divider)
                    ToggleRow("自定义颜色", "分别设置浅色与深色键盘的标签颜色", s.customColor, dark) {
                        s.customColor = it
                        save()
                    }
                }
            }

            // ④ 标签样式
            item(key = "labelStyle") {
                Group(title = "标签样式", modifier = Modifier.alpha(if (s.enabled) 1f else 0.45f)) {
                    ColorRow("浅色标签", s.lightColor, dark, onPick = { colorTarget = 0 }, onReset = {
                        s.lightColor = GestureSettings.DEFAULT_COLOR
                        save()
                    })
                    Divider(color = colors.divider)
                    ColorRow("深色标签", s.darkColor, dark, onPick = { colorTarget = 1 }, onReset = {
                        s.darkColor = GestureSettings.DEFAULT_COLOR
                        save()
                    })
                    Divider(color = colors.divider)
                    IntSliderRow("下滑阈值", s.thresholdDp, 10, 48, " dp", s.enabled, dark, accent) { v ->
                        s.thresholdDp = v
                        save()
                    }
                    Divider(color = colors.divider)
                    IntSliderRow("字号", s.fontSizeSp, 5, 16, " sp", s.enabled, dark, accent) { v ->
                        s.fontSizeSp = v
                        save()
                    }
                    Divider(color = colors.divider)
                    IntSliderRow("水平偏移", s.offsetXDp, -48, 48, " dp", s.enabled, dark, accent) { v ->
                        s.offsetXDp = v
                        save()
                    }
                    Divider(color = colors.divider)
                    IntSliderRow("垂直偏移", s.offsetYDp, -48, 48, " dp", s.enabled, dark, accent) { v ->
                        s.offsetYDp = v
                        save()
                    }
                }
            }

            // ⑤ 绑定表
            item(key = "bindings") {
                Group(
                    title = "按键标签 · ${layoutSel.label}",
                    modifier = Modifier.alpha(if (s.enabled) 1f else 0.45f),
                ) {
                    layoutSel.keys.forEachIndexed { index, key ->
                        if (index > 0) Divider(color = colors.divider)
                        val action = s.action(layoutSel, key)
                        Item(
                            onClick = { if (s.enabled) pickerKey = key },
                            title = {
                                Text(
                                    text = GestureKeys.display(key),
                                    style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                                    color = colors.title,
                                )
                            },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = action.full,
                                        style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                                        color = if (action == GestureAction.None) colors.summary else colors.accent,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        painter = painterResource(R.drawable.ic_chevron),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = colors.summary,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            item(key = "tip") {
                Text(
                    text = "改完立即写入 LSPosed 配置并同步进微信输入法目录，下次唤起键盘生效。",
                    style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                    color = colors.summary,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                )
            }
        }
    }

    // 动作选择弹层
    val key = pickerKey
    if (key != null) {
        ActionPickerDialog(
            title = GestureKeys.pickerTitle(key),
            current = s.action(layoutSel, key),
            dark = dark,
            onDismiss = { pickerKey = null },
            onPick = { action ->
                s.setAction(layoutSel, key, action)
                save()
                pickerKey = null
            },
        )
    }

    // 颜色弹层
    val target = colorTarget
    if (target != null) {
        val current = if (target == 0) s.lightColor else s.darkColor
        ColorPickerDialog(
            title = if (target == 0) "浅色标签" else "深色标签",
            current = current,
            dark = dark,
            onDismiss = { colorTarget = null },
            onPick = { c ->
                if (target == 0) s.lightColor = c else s.darkColor = c
                save()
                colorTarget = null
            },
        )
    }
}

// ------------------------------------------------------------------ 小部件

@Composable
private fun CapBack(dark: Boolean, onClick: () -> Unit) {
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
private fun PlainSegment(
    labels: List<String>,
    selected: Int,
    dark: Boolean,
    onSelected: (Int) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(CouiCapsuleShape)
            .background(Color(if (dark) 0x1FFFFFFF else 0x0F000000))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, name ->
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
private fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    dark: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Item(
        onClick = { onChange(!checked) },
        title = {
            Text(
                text = title,
                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                color = colors.title,
            )
        },
        summary = {
            Text(
                text = summary,
                style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                color = colors.summary,
            )
        },
        trailing = { Switch(checked = checked, onCheckedChange = null, dark = dark) },
    )
}

@Composable
private fun ColorRow(
    title: String,
    color: Int,
    dark: Boolean,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Item(
        onClick = onPick,
        title = {
            Text(
                text = title,
                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                color = colors.title,
            )
        },
        summary = {
            Text(
                text = String.format("#%06X", 0xFFFFFF and color),
                style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                color = colors.summary,
            )
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(color))
                        .border(1.dp, colors.divider, RoundedCornerShape(11.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "重置",
                    style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                    color = colors.accent,
                    modifier = Modifier
                        .clip(CouiCapsuleShape)
                        .clickable { onReset() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        },
    )
}

@Composable
private fun IntSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    unit: String,
    enabled: Boolean,
    dark: Boolean,
    accent: Color,
    onCommit: (Int) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                color = colors.title,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${local.toInt()}$unit",
                style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                color = colors.summary,
            )
        }
        Slider(
            modifier = Modifier.fillMaxWidth(),
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            colors = CouiSliderDefaults.colors(accent, dark, enabled),
            dark = dark,
            enabled = enabled,
        )
    }
}

// ------------------------------------------------------------------ 弹层

@Composable
private fun ActionPickerDialog(
    title: String,
    current: GestureAction,
    dark: Boolean,
    onDismiss: () -> Unit,
    onPick: (GestureAction) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Dialog(
        onDismissRequest = onDismiss,
        confirmAction = CouiDialogAction("取消", onDismiss),
        dark = dark,
        title = title,
        placement = CouiDialogPlacement.Bottom,
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            items(GestureAction.values().toList()) { action ->
                Item(
                    onClick = { onPick(action) },
                    title = {
                        Text(
                            text = action.full,
                            style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                            color = if (action == current) colors.accent else colors.title,
                        )
                    },
                    trailing = {
                        if (action == current) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CouiCapsuleShape)
                                    .background(colors.accent)
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ColorPickerDialog(
    title: String,
    current: Int,
    dark: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Dialog(
        onDismissRequest = onDismiss,
        confirmAction = CouiDialogAction("取消", onDismiss),
        dark = dark,
        title = title,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            LABEL_PALETTE.toList().chunked(6).forEach { row ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    row.forEach { c ->
                        val selected = c == current
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(44.dp)
                                .clip(CouiCapsuleShape)
                                .background(Color(c))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) colors.accent else colors.divider,
                                    shape = CouiCapsuleShape,
                                )
                                .clickable { onPick(c) },
                        )
                    }
                }
            }
        }
    }
}


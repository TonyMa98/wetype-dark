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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.layout.Scaffold
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.slider.CouiSliderDefaults
import com.itos.coui.slider.Slider
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.rememberCouiTextStyle
import com.itos.coui.theme.CouiTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 「按键文字位置」二级页（2026-11-22 由 View 版迁到 Compose + COUI-Kit）。
 *
 * 三个页签（九键 / 26 键 / 表情）各自展示要调的组，每组两个滑杆（纵向 + 横向），
 * 顶部一块按真机公式绘制的键盘预览，拖动时预览实时跟着动。
 */
class KeyTextTuningActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = !resolveIsLightTheme(this)
        enableEdgeToEdge()
        LsposedClient.get(this)
        setContent {
            CouiTheme(darkTheme = dark) {
                TuningPage(dark = dark, onBack = { finish() })
            }
        }
    }
}

// ------------------------------------------------------------------ 存储

private fun loadTuning(context: android.content.Context): Pair<FloatArray, FloatArray> {
    val dy = FloatArray(KeyTextTuning.GROUP_COUNT)
    val dx = FloatArray(KeyTextTuning.GROUP_COUNT)
    val prefs = LsposedClient.remote(context, ConfigStore.GROUP) ?: return dy to dx
    val json = runCatching {
        prefs.getString(ConfigStore.KEY_KEY_TEXT_TUNING, null)
    }.getOrNull() ?: return dy to dx
    runCatching {
        val root = JSONObject(json)
        for (i in 0 until KeyTextTuning.GROUP_COUNT) {
            val arr = root.optJSONArray(KeyTextTuning.GROUP_NAMES[i]) ?: continue
            dy[i] = arr.optDouble(0, 0.0).toFloat()
            dx[i] = arr.optDouble(1, 0.0).toFloat()
        }
    }
    return dy to dx
}

private fun saveTuning(context: android.content.Context, dy: FloatArray, dx: FloatArray) {
    if (LsposedClient.remote(context, ConfigStore.GROUP) == null) {
        Toast.makeText(context, "LSPosed 未连接，配置暂未保存", Toast.LENGTH_SHORT).show()
        return
    }
    val root = JSONObject()
    for (i in 0 until KeyTextTuning.GROUP_COUNT) {
        root.put(
            KeyTextTuning.GROUP_NAMES[i],
            JSONArray().put(dy[i].toDouble()).put(dx[i].toDouble())
        )
    }
    runCatching {
        LsposedClient.remote(context, ConfigStore.GROUP)?.edit()
            ?.putString(ConfigStore.KEY_KEY_TEXT_TUNING, root.toString())
            ?.commit()
        HostConfigSync.sync(context)
    }.onFailure { FkLog.e("keytext: save failed", it) }
}

// ------------------------------------------------------------------ 页面

private data class GroupSpec(
    val title: String,
    val group: Int,
    val horizontal: Boolean = false,
    val note: String? = null,
)

@Composable
private fun TuningPage(dark: Boolean, onBack: () -> Unit) {
    val colors = CouiPageDefaults.themeColors(dark)
    val context = LocalContext.current
    val accent = colors.accent

    var mode by remember { mutableIntStateOf(0) }
    var dy by remember { mutableStateOf(FloatArray(KeyTextTuning.GROUP_COUNT)) }
    var dx by remember { mutableStateOf(FloatArray(KeyTextTuning.GROUP_COUNT)) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (y, x) = loadTuning(context)
        dy = y
        dx = x
        loaded = LsposedClient.remote(context, ConfigStore.GROUP) != null
        LsposedClient.whenBound {
            Handler(Looper.getMainLooper()).post {
                if (!loaded) {
                    val (y2, x2) = loadTuning(context)
                    dy = y2
                    dx = x2
                    loaded = LsposedClient.remote(context, ConfigStore.GROUP) != null
                }
            }
        }
    }

    BackHandler { onBack() }

    fun commit(y: FloatArray, x: FloatArray) {
        dy = y.copyOf()
        dx = x.copyOf()
        saveTuning(context, dy, dx)
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
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CouiCapsuleShape)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "‹", fontSize = 26.sp, color = colors.title)
                }
                Text(
                    text = "按键文字位置",
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
            item(key = "mode") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val labels = listOf("九键", "26 键")
                    if (Build.VERSION.SDK_INT >= 34) {
                        SegmentedControl(
                            modifier = Modifier.fillMaxWidth(),
                            labels = labels,
                               selectedIndex = mode,
                            onSelected = { mode = it },
                            dark = dark,
                        )
                    } else {
                        PlainSegment(
                            labels = labels,
                            selected = mode,
                            dark = dark,
                            onSelected = { mode = it },
                        )
                    }
                }
            }

            // 预览（表情页不画键盘）
            if (mode != 2) {
                item(key = "preview") {
                    Column(Modifier.fillMaxWidth()) {
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { ctx -> KeyTextPreview(ctx).apply { this.mode = mode } },
                            update = { view ->
                                view.mode = mode
                                view.setValues(dy, dx)
                            },
                        )
                        Text(
                            text = if (mode == 0) {
                                "键位与键宽按 九键.svg 模板（蓝 = 调上下 / 绿 = 标点可左右 / 黄 = 不调）"
                            } else {
                                "键位与键宽按 26键.svg 模板（蓝 = 调上下 / 黄 = 不调；字母与角标分开调）"
                            },
                            style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                            color = colors.summary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            item(key = "tuning") {
                val specs = when (mode) {
                    0 -> listOf(
                        GroupSpec(
                            "九键按键文字（数字键 / 符号 / 重输 / 换行）",
                            KeyTextTuning.GROUP_T9_LETTER
                        ),
                        GroupSpec("左侧标点列（可左右）", KeyTextTuning.GROUP_PUNCT, horizontal = true),
                        GroupSpec("键帽角标小数字", KeyTextTuning.GROUP_NUMBER),
                        GroupSpec("手势标签（剪切/复制…）", KeyTextTuning.GROUP_LABEL),
                    )

                    1 -> listOf(
                        GroupSpec("26 键字母", KeyTextTuning.GROUP_QWERTY_LETTER),
                        GroupSpec("26 键角标小数字", KeyTextTuning.GROUP_QWERTY_NUMBER),
                        GroupSpec("发送键（enter，独立）", KeyTextTuning.GROUP_S2_ENTER),
                    )

                    else -> listOf(
                        GroupSpec(
                            "表情页发送按钮",
                            KeyTextTuning.GROUP_EMOJI_SEND,
                            note = "微调表情面板右下角「发送」两字的上下位置；0 = 自动墨迹居中",
                        )
                    )
                }
                Group(title = "偏移") {
                    specs.forEachIndexed { index, spec ->
                        if (index > 0) Divider(color = colors.divider)
                        TuningGroup(
                            spec = spec,
                            dy = dy[spec.group],
                            dx = dx[spec.group],
                            dark = dark,
                            accent = accent,
                            onChange = { y, x ->
                                val nd = dy.copyOf().also { it[spec.group] = y }
                                val nx = dx.copyOf().also { it[spec.group] = x }
                                commit(nd, nx)
                            },
                        )
                    }
                }
            }

            item(key = "reset") {
                Group {
                    Item(
                        onClick = {
                            commit(
                                FloatArray(KeyTextTuning.GROUP_COUNT),
                                FloatArray(KeyTextTuning.GROUP_COUNT),
                            )
                        },
                        title = {
                            Text(
                                text = "全部归零",
                                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                                color = colors.accent,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        },
                    )
                }
            }

            item(key = "tip") {
                Text(
                    text = "改完立即写入配置并同步到微信输入法目录；0.0dp 表示自动居中。",
                    style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                    color = colors.summary,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun TuningGroup(
    spec: GroupSpec,
    dy: Float,
    dx: Float,
    dark: Boolean,
    accent: Color,
    onChange: (Float, Float) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = spec.title,
                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                color = colors.title,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = fmt(dy) + if (spec.horizontal) "  /  " + fmt(dx) else "",
                style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                color = accent,
            )
        }
        OffsetSlider("纵向", dy, dark, accent) { v -> onChange(v, dx) }
        if (spec.horizontal) {
            OffsetSlider("横向", dx, dark, accent) { v -> onChange(dy, v) }
        }
        spec.note?.let {
            Text(
                text = it,
                style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                color = colors.summary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun OffsetSlider(hint: String, value: Float, dark: Boolean, accent: Color, onCommit: (Float) -> Unit) {
    val colors = CouiPageDefaults.themeColors(dark)
    var local by remember(value) { mutableStateOf((value * 10f).coerceIn(-60f, 60f)) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hint,
            style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
            color = colors.summary,
            modifier = Modifier.width(34.dp),
        )
        Slider(
            modifier = Modifier.weight(1f),
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local / 10f) },
            valueRange = -60f..60f,
            steps = 59,
            colors = CouiSliderDefaults.colors(accent, dark),
            dark = dark,
        )
    }
}

private fun fmt(v: Float): String =
    if (kotlin.math.abs(v) < 0.05f) "0.0dp" else String.format(Locale.US, "%+.1fdp", v)

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

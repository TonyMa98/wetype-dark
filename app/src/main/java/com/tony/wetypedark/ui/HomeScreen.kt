package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.graphics.drawable.Drawable
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.itos.coui.checkbox.Checkbox
import com.itos.coui.checkbox.CouiCheckboxDefaults
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.InputDialog
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.Item
import com.itos.coui.layout.Scaffold
import com.itos.coui.navigation.CouiNavigationItem
import com.itos.coui.floatingactionbutton.Fab
import com.itos.coui.floatingactionbutton.CouiFabDefaults
import com.itos.coui.floatingactionbutton.CouiFabColor
import com.itos.coui.navigation.NavigationBar
import com.itos.coui.radiobutton.CouiRadioButtonDefaults
import com.itos.coui.radiobutton.RadioButton
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.rememberCouiTextStyle
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.itos.coui.theme.CouiTheme
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.search.SearchBar
import com.itos.coui.search.rememberCouiSearchBarState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * wetype-dark 主界面（Compose + COUI-Kit，2026-11-22 移植进 1119）。
 *
 * 和 fork 版重做稿的差异，全部按 1119 功能正常版的既有改动对齐：
 *  - 逐应用模式保留 4 档：亮色圆角 / 亮色直角 / 深色 / 极暗（MODE_ULTRA_DARK = 3）
 *  - 系统平台分段是 ColorOS 17 / AOSP（不是 HyperOS），副标题沿用 1119 文案
 *  - 「背景高斯模糊」「屏蔽自动更新」分别用 1119 的 ic_row_blur / ic_row_sync_block 图标
 *  - 顶栏保留 1119 的 wetype-dark 大标题 + 珊瑚红重启圆钮
 *  - 底栏是 COUI 悬浮胶囊（浮在页面之上，不是贴底实心条）
 */
data class AppEntry(
    val label: String,
    val pkg: String,
    val icon: Drawable?,
    val isSystem: Boolean = false,
)

private val ModeLabels = listOf("亮色圆角", "亮色直角", "深色", "极暗")
private val PlatformLabels = listOf("ColorOS 17", "其他")

private val CoralRestart = Color(0xFFEE4235)

@Composable
fun HomeScreen(
    dark: Boolean,
    appsLoaded: Boolean,
    shownApps: List<AppEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    showSystem: Boolean,
    onShowSystemChange: (Boolean) -> Unit,
    modes: Map<String, Int>,
    tab: Int,
    onTabSelected: (Int) -> Unit,
    platform: Int,
    onPlatformChange: (Int) -> Unit,
    frosted: Boolean,
    onFrostedChange: (Boolean) -> Unit,
    updateBlock: Boolean,
    onUpdateBlockChange: (Boolean) -> Unit,
    liveUrl: String,
    onCopyLive: () -> Unit,
    onEditLive: () -> Unit,
    onOpenGesture: () -> Unit,
    onOpenKeyText: () -> Unit,
    onOpenColor: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRestart: () -> Unit,
    onModeChange: (String, Int?) -> Unit,
    paletteServerOn: Boolean,
    paletteServerUrl: String,
    onPaletteServerChange: (Boolean) -> Unit,
    onCopyPaletteServerUrl: () -> Unit,
    editHostVisible: Boolean,
    editHostValue: String,
    onEditHostValueChange: (String) -> Unit,
    onEditHostConfirm: () -> Unit,
    onEditHostDismiss: () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)

    CouiTheme(darkTheme = dark) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = colors.background,
            windowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Header(
                    dark = dark,
                    tab = tab,
                    showSystem = showSystem,
                    onShowSystemChange = onShowSystemChange,
                )
            },
            bottomBar = { TabBar(tab = tab, onTabSelected = onTabSelected, dark = dark, onRestart = onRestart) },
        ) { padding ->
            when (tab) {
                0 -> AppsPage(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding()),
                    dark = dark,
                    shown = shownApps,
                    appsLoaded = appsLoaded,
                    query = query,
                    onQueryChange = onQueryChange,
                    showSystem = showSystem,
                    onShowSystemChange = onShowSystemChange,
                    modes = modes,
                    onModeChange = onModeChange,
                )

                else -> SettingsPage(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding()),
                    dark = dark,
                    platform = platform,
                    onPlatformChange = onPlatformChange,
                    frosted = frosted,
                    onFrostedChange = onFrostedChange,
                    updateBlock = updateBlock,
                    onUpdateBlockChange = onUpdateBlockChange,
                    liveUrl = liveUrl,
                    onCopyLive = onCopyLive,
                    onEditLive = onEditLive,
                    onOpenGesture = onOpenGesture,
                    onOpenKeyText = onOpenKeyText,
                    onOpenColor = onOpenColor,
                    onExport = onExport,
                    onImport = onImport,
                    paletteServerOn = paletteServerOn,
                    paletteServerUrl = paletteServerUrl,
                    onPaletteServerChange = onPaletteServerChange,
                    onCopyPaletteServerUrl = onCopyPaletteServerUrl,
                )
            }
        }

        if (editHostVisible) {
            InputDialog(
                value = TextFieldValue(editHostValue),
                onValueChange = { onEditHostValueChange(it.text) },
                onDismissRequest = onEditHostDismiss,
                confirmAction = CouiDialogAction("保存", onEditHostConfirm),
                dismissAction = CouiDialogAction("取消", onEditHostDismiss),
                clearContentDescription = "清空地址",
                title = "电脑地址",
                message = "填电脑在局域网里的 IP，端口默认 8777",
                placeholder = "192.168.2.5",
                dark = dark,
                visible = true,
            )
        }
    }
}

// ------------------------------------------------------------------ 顶栏（1119 原样）

@Composable
private fun Header(
    dark: Boolean,
    tab: Int,
    showSystem: Boolean,
    onShowSystemChange: (Boolean) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "wetype-dark",
            color = colors.title,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 「系统应用」只属于「应用」页；设置页这一块整个不出现
        if (tab == 0) {
            Row(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { onShowSystemChange(!showSystem) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "系统应用",
                    style = rememberCouiTextStyle(CouiTextKind.ItemSummary)
                        .copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = Color.Black,
                )
                Spacer(Modifier.width(8.dp))
                RadioButton(
                    modifier = Modifier.size(24.dp),
                    selected = showSystem,
                    colors = CouiRadioButtonDefaults.colors(colors.accent, dark),
                )
            }
        }
        // 重启钮已移到应用页左下角的大圆按钮
    }
}

// ------------------------------------------------------------------ 底栏：悬浮胶囊

@Composable
private fun TabBar(
    tab: Int,
    onTabSelected: (Int) -> Unit,
    dark: Boolean,
    onRestart: () -> Unit,
) {
    if (Build.VERSION.SDK_INT >= 34) {
        // 导航胶囊是同尺寸 56dp 的圆角行；用 root 坐标量出胶囊行顶端，
        // 圆钮按同一条基线摆 → 与胶囊严格同行
        var boxTop by remember { mutableFloatStateOf(0f) }
        var navTop by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val startPx = with(density) { 18.dp.roundToPx() }
        val insetPx = with(density) { 16.dp.roundToPx() }
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { boxTop = it.positionInRoot().y },
        ) {
            NavigationBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onGloballyPositioned { navTop = it.positionInRoot().y },
                items = listOf(
                    CouiNavigationItem(
                        "应用",
                        ImageVector.vectorResource(R.drawable.ic_tab_sun),
                        ImageVector.vectorResource(R.drawable.ic_tab_sun_selected),
                    ),
                    CouiNavigationItem(
                        "设置",
                        ImageVector.vectorResource(R.drawable.ic_tab_set),
                        ImageVector.vectorResource(R.drawable.ic_tab_set_selected),
                    ),
                ),
                selectedIndex = tab.coerceIn(0, 1),
                lightColor = Color.White,
                onSelection = onTabSelected,
                dark = dark,
            )
            RestartFab(
                onRestart = onRestart,
                dark = dark,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(x = startPx, y = (navTop - boxTop).roundToInt() + insetPx)
                    },
            )
        }
    } else {
        val colors = CouiPageDefaults.themeColors(dark)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .height(56.dp)
                    .shadow(10.dp, CouiCapsuleShape)
                    .clip(CouiCapsuleShape)
                    .background(colors.card)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FallbackTab("应用", tab == 0, colors.title, colors.accent, Modifier.weight(1f)) { onTabSelected(0) }
                FallbackTab("设置", tab == 1, colors.title, colors.accent, Modifier.weight(1f)) { onTabSelected(1) }
            }
        }
    }
}

/** 左下角大圆按钮：直接用 COUI 官方 Fab + 录音红（CouiFabColor.Recorder）的材质与投影预设 */
@Composable
private fun RestartFab(onRestart: () -> Unit, dark: Boolean, modifier: Modifier = Modifier) {
    val color = CouiFabDefaults.containerColor(CouiFabColor.Recorder, dark)
    Fab(
        modifier = modifier.size(56.dp),
        onClick = onRestart,
        dark = dark,
        shape = CouiFabDefaults.circleShape,
        material = CouiFabDefaults.material(color, dark),
        // 只叠前景，不回填采样背景：否则圆外会露出一块方块
        foregroundComposition = true,
        contentColor = CouiFabDefaults.contentColor,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_restart),
            contentDescription = "重启微信输入法",
            modifier = Modifier.size(24.dp),
            tint = CouiFabDefaults.contentColor,
        )
    }
}

@Composable
private fun FallbackTab(
    label: String,
    selected: Boolean,
    title: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(CouiCapsuleShape)
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
            color = if (selected) accent else title,
        )
    }
}

// ------------------------------------------------------------------ 应用页

@Composable
private fun AppsPage(
    modifier: Modifier,
    dark: Boolean,
    shown: List<AppEntry>,
    appsLoaded: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    showSystem: Boolean,
    onShowSystemChange: (Boolean) -> Unit,
    modes: Map<String, Int>,
    onModeChange: (String, Int?) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)

    // 搜索框用 COUI（ColorOS 17）的常驻搜索胶囊：点击展开 → 缩窄 + 右侧分裂出取消按钮。
    // 它需要采样背景源，所以整页垫一层纯色底作为源，搜索胶囊在源之外（不能自采样）。
    // 和 COUI Demo 首页/搜索样例一样的接法：整页铺一层会被采样的背景源，
    // SearchBar 读它做材质（源必须和搜索胶囊是兄弟节点，不能自采样）
    val searchSupported = Build.VERSION.SDK_INT >= 34
    val backdrop = rememberCouiBackdrop()
    var searchExpanded by remember { mutableStateOf(false) }
    val searchState = rememberCouiSearchBarState(searchExpanded)
    // 进页面不抢焦点：否则一打开就弹键盘，把底栏顶上去
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // 连做两次：首帧后还有一次系统把焦点塞回输入框的动作
        repeat(2) {
            delay(250)
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    BackHandler(enabled = searchExpanded || query.isNotEmpty()) {
        if (searchExpanded) searchExpanded = false
        onQueryChange("")
    }

    Box(modifier = modifier) {
    if (searchSupported) {
        Box(
            Modifier
                .matchParentSize()
                .couiBackdrop(backdrop)
                .background(colors.background),
        )
    }
    val listState = rememberLazyListState()
    // 参考 COUI 搜索示例：内容一滚，顶部出现一层与背景同色的蒙层，把内容压在搜索框后面
    val scrimProgress by remember(listState) {
        derivedStateOf {
            when {
                searchExpanded -> 1f
                listState.firstVisibleItemIndex > 0 -> 1f
                else -> (listState.firstVisibleItemScrollOffset / 56f).coerceIn(0f, 1f)
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = if (searchSupported) 54.dp else 62.dp, bottom = 130.dp),
    ) {
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = if (!appsLoaded) "正在加载应用列表…" else "没有匹配的应用",
                    style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                    color = colors.summary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                )
            }
        }

        items(shown, key = { it.pkg }) { entry ->
            AppCard(
                entry = entry,
                dark = dark,
                mode = modes[entry.pkg],
                onToggle = {
                    // 未选中 → 勾选并给默认「亮色圆角」；已选中 → 取消勾选
                    onModeChange(entry.pkg, if (modes[entry.pkg] == null) 0 else null)
                },
                onModeChange = { mode -> onModeChange(entry.pkg, mode) },
            )
        }
    }
    if (scrimProgress > 0f) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(92.dp)
                .background(
                    Brush.verticalGradient(
                        0f to colors.background.copy(alpha = scrimProgress),
                        .55f to colors.background.copy(alpha = scrimProgress * .96f),
                        .78f to colors.background.copy(alpha = scrimProgress * .5f),
                        1f to colors.background.copy(alpha = 0f),
                    )
                ),
        )
    }

    // 搜索胶囊浮在列表之上最后绘制：投影溢出到下方卡片区域时不会被卡片盖掉/截断
    if (searchSupported) {
        SearchBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
                .focusProperties { canFocus = searchExpanded },
            state = searchState,
            query = query,
            onQueryChange = onQueryChange,
            onExpandedChange = {
                searchExpanded = it
                if (!it) onQueryChange("")
            },
            placeholder = "搜索应用",
            cancelText = "取消",
            clearDescription = "清空搜索",
            dark = dark,
            backdrop = backdrop,
        )
    } else {
        SearchField(
            dark = dark,
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp, start = 16.dp, end = 16.dp),
        )
    }

    }
}

@Composable
private fun SearchField(
    dark: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(CouiCapsuleShape)
            .background(colors.card)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.summary,
        )
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索应用",
                    style = rememberCouiTextStyle(CouiTextKind.Search),
                    color = colors.summary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = rememberCouiTextStyle(CouiTextKind.Search).copy(color = colors.title),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                painter = painterResource(R.drawable.ic_clear),
                contentDescription = "清空搜索",
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onQueryChange("") },
                tint = colors.summary,
            )
        }
    }
}

@Composable
private fun AppCard(
    entry: AppEntry,
    dark: Boolean,
    mode: Int?,
    onToggle: () -> Unit,
    onModeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    val checked = mode != null
    val bitmap = remember(entry.icon) {
        entry.icon?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }

    // 选中态只体现在四档分段里，卡片本身保持常态
    val cardBg = colors.card
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(CouiPageDefaults.shape)
            .background(cardBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                // 上下 16dp 与左右 16dp 一致
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .background(colors.card),
                    )
                }
            }
            // 图标和名称之间收窄到 12dp
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                    color = colors.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.pkg,
                    style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                    color = colors.summary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Checkbox(
                checked = checked,
                colors = CouiCheckboxDefaults.colors(colors.accent, dark),
            )
        }

        if (checked) {
            ModeSelector(
                dark = dark,
                selected = mode ?: 0,
                onSelected = onModeChange,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun ModeSelector(
    dark: Boolean,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 外形沿用 COUI 分段选择器（40dp 胶囊轨道 + 4dp 内缩），滑块换成蓝色，选中项白字
    BlueSegment(
        modifier = modifier,
        labels = ModeLabels,
        selected = selected.coerceIn(0, ModeLabels.lastIndex),
        onSelected = onSelected,
        dark = dark,
    )
}

@Composable
private fun BlueSegment(
    labels: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    val container = Color(if (dark) 0x1FFFFFFF else 0x0F000000)
    val pos = animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = tween(200, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "modeSegment",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CouiCapsuleShape)
            .background(container),
    ) {
        // 蓝色滑块：4dp 内缩，胶囊形，跟选中项滑动
        Canvas(Modifier.fillMaxSize()) {
            val inset = 4.dp.toPx()
            val segW = (size.width - inset * 2) / labels.size.coerceAtLeast(1)
            val left = inset + pos.value * segW
            drawRoundRect(
                color = colors.accent,
                topLeft = Offset(left, inset),
                size = Size(segW, size.height - inset * 2),
                cornerRadius = CornerRadius((size.height - inset * 2) / 2f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { index, label ->
                val on = index == selected
                val textColor = animateColorAsState(
                    targetValue = if (on) Color.White else colors.title,
                    animationSpec = tween(160),
                    label = "modeLabel",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = rememberCouiTextStyle(CouiTextKind.ItemValue)
                            .copy(fontSize = 13.sp, lineHeight = 16.sp),
                        color = textColor.value,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 设置页

@Composable
private fun SettingsPage(
    modifier: Modifier,
    dark: Boolean,
    platform: Int,
    onPlatformChange: (Int) -> Unit,
    frosted: Boolean,
    onFrostedChange: (Boolean) -> Unit,
    updateBlock: Boolean,
    onUpdateBlockChange: (Boolean) -> Unit,
    liveUrl: String,
    onCopyLive: () -> Unit,
    onEditLive: () -> Unit,
    onOpenGesture: () -> Unit,
    onOpenKeyText: () -> Unit,
    onOpenColor: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    paletteServerOn: Boolean,
    paletteServerUrl: String,
    onPaletteServerChange: (Boolean) -> Unit,
    onCopyPaletteServerUrl: () -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 0.dp, bottom = 120.dp),
    ) {
        // 「模糊」一张卡：上面是背景高斯模糊开关，开了才展开系统平台选择
        item(key = "blur") {
            Group {
                Item(
                    onClick = { onFrostedChange(!frosted) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_row_blur),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    summary = {
                        Text(
                            text = "关闭后键盘背景为纯色，不再模糊",
                            style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                            color = colors.summary,
                        )
                    },
                    title = {
                        Text(
                            text = "背景高斯模糊",
                            style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                            color = colors.title,
                        )
                    },
                    trailing = {
                        Switch(checked = frosted, onCheckedChange = null, dark = dark)
                    },
                )
                // 只有打开模糊，才出现 ColorOS 17 / 其他的平台选择
                if (frosted) {
                    Divider(color = colors.divider)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        if (Build.VERSION.SDK_INT >= 34) {
                            SegmentedControl(
                                modifier = Modifier.fillMaxWidth(),
                                labels = PlatformLabels,
                                selectedIndex = platform.coerceIn(0, PlatformLabels.lastIndex),
                                onSelected = onPlatformChange,
                                dark = dark,
                            )
                        } else {
                            FallbackSegment(
                                labels = PlatformLabels,
                                selected = platform.coerceIn(0, PlatformLabels.lastIndex),
                                onSelected = onPlatformChange,
                                dark = dark,
                            )
                        }
                    }
                }
            }
        }

        item(key = "main") {
            Group {
                NavigationRow(R.drawable.ic_row_swipe, "下滑功能", dark, onOpenGesture)
                Divider(color = colors.divider)
                NavigationRow(R.drawable.ic_row_textedit, "按键文字位置", dark, onOpenKeyText)
                Divider(color = colors.divider)
                NavigationRow(R.drawable.ic_row_colorbar, "颜色配置", dark, onOpenColor)
                Divider(color = colors.divider)
                Item(
                    onClick = { onPaletteServerChange(!paletteServerOn) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_row_window),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    title = {
                        Text(
                            text = "网页调色",
                            style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                            color = colors.title,
                        )
                    },
                    trailing = {
                        Switch(checked = paletteServerOn, onCheckedChange = null, dark = dark)
                    },
                )
                if (paletteServerOn) {
                    Divider(color = colors.divider)
                    Item(
                        onClick = onCopyPaletteServerUrl,
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_row_globe),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                        title = {
                            Text(
                                text = paletteServerUrl,
                                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                                color = colors.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailing = {
                            Text(
                                text = "复制",
                                style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                                color = colors.accent,
                            )
                        },
                    )
                }
                Divider(color = colors.divider)
                Item(
                    onClick = { onUpdateBlockChange(!updateBlock) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_row_block),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    title = {
                        Text(
                            text = "屏蔽微信输入法自动更新",
                            style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                            color = colors.title,
                        )
                    },
                    summary = {
                        Text(
                            text = "拦截升级弹窗、安装 APK 和安装会话",
                            style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                            color = colors.summary,
                        )
                    },
                    trailing = {
                        Switch(checked = updateBlock, onCheckedChange = null, dark = dark)
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        item(key = "io") {
            Group {
                NavigationRow(R.drawable.ic_row_export, "导出配置", dark, onExport)
                Divider(color = colors.divider)
                NavigationRow(R.drawable.ic_row_restore, "恢复配置", dark, onImport)
            }
        }
    }
}

@Composable
private fun SwitchRow(
    iconRes: Int,
    title: String,
    summary: String,
    checked: Boolean,
    dark: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Group {
        Item(
            onClick = { onCheckedChange(!checked) },
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
            icon = {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            trailing = {
                Switch(checked = checked, onCheckedChange = null, dark = dark)
            },
        )
    }
}

@Composable
private fun NavigationRow(iconRes: Int, label: String, dark: Boolean, onClick: () -> Unit) {
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
        icon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        },
        trailing = {
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.summary,
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveColorsRow(url: String, dark: Boolean, onCopy: () -> Unit, onEdit: () -> Unit) {
    val colors = CouiPageDefaults.themeColors(dark)
    Item(
        modifier = Modifier.combinedClickable(onClick = onCopy, onLongClick = onEdit),
        title = {
            Text(
                text = "网页调色",
                style = rememberCouiTextStyle(CouiTextKind.ItemTitle),
                color = colors.title,
            )
        },
        summary = {
            Text(
                text = url,
                style = rememberCouiTextStyle(CouiTextKind.ItemSummary),
                color = colors.summary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_row_window),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        },
        trailing = {
            Text(
                text = "复制",
                style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                color = colors.summary,
            )
        },
    )
}

@Composable
private fun FallbackSegment(
    labels: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CouiPageDefaults.themeColors(dark)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CouiCapsuleShape)
            .background(colors.card)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(CouiCapsuleShape)
                    .background(if (index == selected) colors.accent else Color.Transparent)
                    .clickable { onSelected(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = rememberCouiTextStyle(CouiTextKind.ItemValue),
                    color = if (index == selected) Color.White else colors.title,
                    maxLines = 1,
                )
            }
        }
    }
}

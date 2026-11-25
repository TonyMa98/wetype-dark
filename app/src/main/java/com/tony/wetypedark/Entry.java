package com.tony.wetypedark;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import java.lang.ref.WeakReference;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * wetype-dark / 面板磨砂：
 *
 * ① 面板底色 = 半透明色层；
 * ② 底下垫一张 ColorOS 窗口级背景模糊 Drawable
 *    （com.oplus.view.ViewRootManager#getBackgroundBlurDrawable，见
 *    docs/改代码前必读-踩坑与正确做法.md 第 1 节），两者一起贴到
 *    currentInputView（整块面板：候选栏 + 键盘区 + 底部手势条填充）。
 *
 * 铁律（来自同一份文档）：
 *  - 不要用 RenderEffect / Window.setBackgroundBlurRadius 造面板磨砂；
 *  - BackgroundBlurDrawable 绑在 ViewRootImpl 上，宿主每次唤起 IME 都会重建窗口，
 *    旧 Drawable 立即失效 → 必须按「ViewRootImpl 实例」判断失效并重新取，不能长期缓存；
 *  - 挂在哪个 View 就只糊哪个 View 的区域，所以必须挂在面板根，不能挂全屏 decor。
 */
public final class Entry extends XposedModule {

    private static final String TAG = "wetype-dark";
    private static final String PKG = "com.tencent.wetype";
    private static final String PROCESS = ":hld";
    private static final String IME_CLASS = "com.tencent.wetype.plugin.hld.WxHldService";
    private static final float RADIUS_DP = 24f;
    private static final boolean DEBUG = true;

    /** 自绘键盘包：整块键盘是 selfdraw.o，单键模型是 selfdraw.j */
    private static final String KEYBOARD_PKG = "com.tencent.wetype.plugin.hld.keyboard.selfdraw";

    /** 每个键盘实例只完整 dump 一次按键表 */
    private final Map<Object, Boolean> keyMapDumped = new WeakHashMap<>();
    /** 已主动捕获过的键盘（弱引用键，键盘销毁自动回收） */
    private static final java.util.Map<Object, Boolean> keysCaptured =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, Boolean>());
    /** 发送/回车键的 id：捕获时排除 */
    private static boolean isSendKeyId(String id) {
        return "enter".equals(id) || "return".equals(id) || "send".equals(id);
    }

    /** 命中矩形左边缘贴到 0（±8px）才算「左下角那颗」 */
    private static final int CORNER_LEFT_PX = 8;

    /**
     * 页面级的额外排除，只排除真正贴在左下角的那一颗 return：
     *  - 九键符号页（S5SymbolKeyboard）左下角 return
     *  - 数字面板（S4NumberKeyboard）左下角 return
     * 靠矩形判断，同一页面里其它位置的回车/返回键照常染色。
     */
    private static boolean isCornerReturnKey(String keyboardClass, String id,
                                             Method keyRect, Object button) {
        if (keyboardClass == null || id == null || keyRect == null || button == null) {
            return false;
        }
        String cls = keyboardClass;
        if (!cls.endsWith("S5SymbolKeyboard") && !cls.endsWith("S4NumberKeyboard")) {
            return false;
        }
        if (!"back".equals(id) && !"return".equals(id)) {
            return false;
        }
        try {
            Object r = keyRect.invoke(button);
            if (r instanceof android.graphics.Rect) {
                return ((android.graphics.Rect) r).left <= CORNER_LEFT_PX;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** 面板色默认值：模糊之上的半透明着色层，alpha 越低越透出背后的模糊 */
    private static final int DEFAULT_PANEL_COLOR = 0xC8E2E2E2;
    /** 模糊半径默认值（dp），80px 左右是文档实测值，24dp × 3.5 ≈ 84px */
    private static final int DEFAULT_BLUR_RADIUS_DP = 24;
    /** 模糊层自己是否要圆角：false = 交给画布 clip 的 squircle 决定形状 */
    private static final boolean BLUR_OWN_CORNER = true;
    /** 模糊层圆角半径的折算系数（1 / 1.33，抵消 ColorOS 平滑圆角的支撑区放大） */
    private static final float BLUR_RADIUS_SCALE = 1.0f;
    /** 模糊层的平滑圆角权重：2.0 = 不处理（普通圆角矩形，半径不会被放大） */
    private static final float PANEL_SMOOTH_WEIGHT = 2.0f;
    private boolean panelSmoothLogged;
    private boolean blurCornerLogged;

    /** 调色台写入的实时配置（不经过 RemotePreferences，改完 0.3s 内生效） */
    private static final String LIVE_FILE =
            "/data/user/0/com.tencent.wetype/files/wetype-dark.json";

    /** 候选词整体右移量（dp）：只平移自绘候选条，不动左右两端的 logo / 功能键 */
    private static final String MODULE_PKG = "com.tony.wetypedark";
    private static final float CANDIDATE_SHIFT_DP = 5f;
    /** 候选条原始宽度（减 dx 的基准，避免每次布局累减） */
    private int candidateBaseWidth = -1;
    private View candidateBaseWidthView;
    private int candidateBaseLeft = -1;
    private int candidateBaseMargin = -1;
    private boolean candidateWidthLogged;

    /** 网页调色的「按键底色」：0 = 不接管 */
    private volatile int keyFillColor = 0;
    /** 表情页删除键清理探针配额 */
    private final java.util.concurrent.atomic.AtomicInteger emojiDelHit =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 网页调色的「按键按下色」：0 = 不接管（保持宿主 pressMaskColor） */
    private volatile int keyPressColor = 0;
    /** 4.0.1 里 pressMaskColor 的混淆字段名：drawmethod.c 走 getter X()I，X() 读字段 O */
    private static final String PRESS_FIELD_NAME = "O";
    /** 按键底色走渐变（网页里选「渐变」时） */
    private volatile boolean keyGradientOn;
    /** 渐变起点 / 终点色（0 = 退回按键底色）与角度（0° 左→右，90° 上→下） */
    /** 渐变多色标（Photoshop 式）：位置 0..1 + 颜色；为空时回落到两点渐变 */
    private volatile int[] keyGradStopColors = null;
    private volatile float[] keyGradStopPos = null;
    private final java.util.Map<String, android.graphics.Shader> keyGradStopShaders =
            new java.util.HashMap<>();
    private volatile int keyGradC1 = 0;
    private volatile int keyGradC2 = 0;
    private volatile float keyGradAngle = 90f;
    /** 只在键盘自绘 draw() 期间接管 Canvas.drawRoundRect */
    private volatile boolean keyDrawWindow;
    private boolean keyGradHookInstalled;
    private boolean layerGuardInstalled;
    /** 布局变化后，下一帧画之前把三层底色重新种一遍（否则新重建的键盘 View 会有几帧露出自己的底色） */
    private final java.util.concurrent.atomic.AtomicBoolean layersDirty =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private volatile boolean layersBusy;
    private volatile View layersPanelRef;
    /** 我们种下去的 View 背景（面板背景链、键盘 root 实色层）：宿主重设时同步换回 */
    private final Map<View, Drawable> plantedBackgrounds =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.concurrent.atomic.AtomicInteger plantedRestoreCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private boolean keyGradLogged;
    private int keyGradDebugCount;
    private int keyGradApplyCount;
    private int keyGradSkipCount;
    private boolean canvasLogged;
    private boolean drawWindowLogged;
    /** 网页调色的「按键圆角」（px）：0 = 不接管；对包括发送键在内的全部按键生效 */
    private volatile int keyRadiusPx = 0;
    /** 亮色直角模式：强行把按键圆角压成 0（keyRadiusPx 此时不参与判定） */
    /** 亮色主题的「直角」：只把输入法面板顶部两个圆角压成直角，按键圆角不动 */
    private volatile boolean panelTopFlat = false;

    /** 拿面板的 Context 读安装包里内置的默认配置 */
    private volatile android.content.Context cfgContext;

    /* ---------- 逐应用模式（0亮色圆角 1亮色直角 2深色 3极暗） ---------- */
    /** 当前输入框所属应用包名（onStartInputView 的 EditorInfo 里取） */
    private volatile String editorPkg = "";
    /** 命中的应用模式；-1 = 该应用没勾选，跟随系统深浅色 */
    private volatile int activeAppMode = -1;
    /** 包名 → 模式 */
    private volatile java.util.Map<String, Integer> appModes = java.util.Collections.emptyMap();
    private long appModesMtime = Long.MIN_VALUE;
    private long appModesLength = -1;
    /** 按键文字/图标、候选词文字的目标色：0 = 不接管（跟随宿主自己的深浅色） */
    private volatile int keyTextColor = 0;
    private volatile int candTextColor = 0;

    /* ---------- 按键自绘投影（只画按键，不碰候选栏 / 不恢复宿主自带阴影） ---------- */
    /** 投影颜色（RGB，透明度由 keyShadowAlpha 控制）：0 = 关闭投影 */
    private volatile int keyShadowColor = 0xFF000000;
    /** 投影透明度 0~100 */
    private volatile int keyShadowAlpha = 20;
    /** 投影模糊半径（px） */
    private volatile float keyShadowBlurPx = 0f;
    /** 投影 Y 轴下移距离（px） */
    private volatile float keyShadowDyPx = 0f;
    /** 投影是否启用（配置里出现 key_shadow 才算接管） */
    private volatile boolean keyShadowEnabled = false;

    private final android.graphics.Paint keyShadowPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    /** 烘焙好的投影位图：按「尺寸+圆角+偏移+模糊+颜色」缓存，换主题/中英切换时参数一变就重建一副 */
    private static final class ShadowBmp {
        final android.graphics.Bitmap bmp;
        final int pad;
        ShadowBmp(android.graphics.Bitmap bmp, int pad) {
            this.bmp = bmp;
            this.pad = pad;
        }
    }

    private final java.util.HashMap<String, ShadowBmp> keyShadowCache = new java.util.HashMap<>();
    private boolean keyShadowCacheLogged = false;

    private ShadowBmp obtainShadowBitmap(int w, int h, float rad, float dy,
                                         float blur, int color) {
        String key = w + "|" + h + "|" + rad + "|" + dy + "|" + blur + "|" + color;
        ShadowBmp cached = keyShadowCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            int pad = (int) Math.ceil(blur * 2f + Math.abs(dy) + 2f);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w + pad * 2, h + pad * 2, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(color);
            if (blur > 0f) {
                p.setMaskFilter(new android.graphics.BlurMaskFilter(
                        blur, android.graphics.BlurMaskFilter.Blur.NORMAL));
            }
            android.graphics.RectF shadow = new android.graphics.RectF(
                    pad, pad, pad + w, pad + h);
            c.drawRoundRect(shadow, rad, rad, p);
            // 把按键本体挖掉：投影只留在按键外面
            android.graphics.Paint clear = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            clear.setStyle(android.graphics.Paint.Style.FILL);
            clear.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.CLEAR));
            android.graphics.RectF hole = new android.graphics.RectF(
                    pad, pad - dy, pad + w, pad - dy + h);
            c.drawRoundRect(hole, rad, rad, clear);
            ShadowBmp entry = new ShadowBmp(bmp, pad);
            if (keyShadowCache.size() > 24) {
                for (ShadowBmp old : keyShadowCache.values()) {
                    if (old.bmp != null && !old.bmp.isRecycled()) {
                        old.bmp.recycle();
                    }
                }
                keyShadowCache.clear();
            }
            keyShadowCache.put(key, entry);
            if (!keyShadowCacheLogged) {
                keyShadowCacheLogged = true;
                probe("key shadow bitmap baked " + w + "x" + h + " rad=" + rad
                        + " dy=" + dy + " blur=" + blur + " color=" + Integer.toHexString(color));
            }
            return entry;
        } catch (Throwable t) {
            probe("key shadow bitmap failed: " + t);
            return null;
        }
    }
    private float keyShadowBlurApplied = -1f;
    private java.lang.reflect.Field keyDrawRectField;
    private java.lang.reflect.Field keyCornerField;

    /* ---------- 下滑功能：键帽下方的小标签 ---------- */
    private final android.graphics.Paint gestureLabelPaint =
            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private volatile org.json.JSONObject gestureCfg;
    private volatile String gestureCfgRaw = "";
    private long gestureCfgAt = 0L;

    /** 动作 id → 键帽下方最多 4 个字的短名 */
    private static final java.util.Map<String, String> GESTURE_SHORT = new java.util.HashMap<>();

    static {
        for (com.tony.wetypedark.ui.GestureAction a
                : com.tony.wetypedark.ui.GestureAction.values()) {
            if (!a.getShort().isEmpty()) {
                GESTURE_SHORT.put(a.name(), a.getShort());
            }
        }
    }

    /** appearance-live.json 的 mtime/size，避免每帧解析 */
    private long appearanceMtime = Long.MIN_VALUE;
    /** 上一次成功应用的外观文件全文：用来判断内容是否真的变了（比 mtime+size 可靠） */
    private volatile String appearanceText = null;
    private long appearanceLength = -1;
    /** 当前皮肤是否深色（由面板配置决定） */
    private volatile boolean nightMode = false;
    /** 网页「小字颜色」：下滑手势小字（全选/复制/粘贴/剪切）的独立颜色，0 = 跟按键文字走 */
    private volatile int keyFnTextColor = 0;
    /** 网页「字符颜色」：亮/暗两套都生效的按键主字色（发送键除外），0 = 不接管 */
    private volatile int keyTextLiveColor = 0;
    /** 网页「字符颜色」是否配过：配成「全透明黑」时颜色本身是 0，不能靠颜色值判断 */
    private volatile boolean keyTextLiveSet = false;

    /** 按键上所有「墨色」（大字 / 标点 / 功能字 / 图标）的当前取值 */
    private int keyInk() {
        int ink = keyTextLiveSet
                ? (keyTextLiveColor == 0 ? 0x01000000 : keepInkAlpha(keyTextLiveColor))
                : keepInkAlpha(keyTextColor);
        return ink;
    }
    /** 网页「小字颜色」（新）：键帽右上角的角标小字（T9 数字 / 英文 / 标点），0 = 回落主字色 */
    private volatile int keySmallTextColor = 0;
    private volatile boolean keySmallTextSet = false;

    /**
     * 按键文字字体：宿主自绘键盘一律用 `fonts/WE-Regular.ttf`（400 常规），
     * 这里换成「我们面板那套系统字体」（sans-serif）+ 字重 450（比常规 +50）。
     *
     * 两条路一起走：
     *  ① 构造期拦截 `Typeface.createFromAsset`，新造的画笔直接拿到系统字体；
     *  ② 键盘第一次绘制时把已经建好的画笔（键盘实例里 drawerMap 里那几个绘制器）
     *    逐个改写字体和 wght —— 抽屉是在构造键盘时就建好的，早于 hook 安装，
     *    只靠 ① 对现存的键盘一笔都改不到。
     */
    private static final String KEY_FONT_ASSET = "fonts/WE-Regular.ttf";
    private static final int KEY_FONT_WEIGHT = 450;
    /**
     * 字重 +50 用描边补：weight 400 → 450 大约等于笔画 +1% em，
     * 宿主本来就是「FILL_AND_STROKE + 0.1px」这套，这里按字号乘 1% 追加上去。
     */
    private static final float KEY_WEIGHT_STROKE_RATIO = 0.01f;
    /** 宿主的 m1 工具类（F2 里给文字定 0.1/0.5 描边，是每颗键画字前的必经点） */
    private static final String HOST_UTILS_PKG = "com.tencent.wetype.plugin.hld.utils";
    private static android.graphics.Typeface keySystemTypeface;
    /** 宿主字体资源的调用点前缀（命中其一才替换） */
    private static final String[] KEY_FONT_CALLERS = {
            "com.tencent.wetype.plugin.hld.keyboard.selfdraw",
            "com.tencent.wetype.plugin.hld.oldsymbol",
    };
    /** 键盘实例里装着全部绘制器的容器（selfdraw.e，字段 drawerMap） */
    private static final String KEY_DRAWER_HOLDER =
            "com.tencent.wetype.plugin.hld.keyboard.selfdraw.e";
    /** 各个按键绘制器（主字 / 角标 / 图标 / dotComma / 空键占位） */
    private static final String KEY_DRAWER_PKG =
            "com.tencent.wetype.plugin.hld.keyboard.selfdraw.drawmethod";
    /** 每台键盘解析出来的「按键文字画笔」清单（弱引用，键盘销毁自动回收） */
    private final Map<Object, android.graphics.Paint[]> keyFontPaints =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<Object, android.graphics.Paint[]>());
    /** 绘制器类 → 里面的 Paint 字段 */
    private final Map<Class<?>, java.lang.reflect.Field[]> paintFieldsByClass =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger keyFontLog =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 按键文字画笔（按对象身份认，F2 里只给这些补字重，候选栏之类不动） */
    private final java.util.IdentityHashMap<android.graphics.Paint, Boolean> keyTextPaints =
            new java.util.IdentityHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger keyWeightLog =
            new java.util.concurrent.atomic.AtomicInteger();


    /** 缓存的系统字体（wght 450）；设备字体不支持可变字重时退回默认 sans-serif */
    private static synchronized android.graphics.Typeface systemKeyTypeface() {
        if (keySystemTypeface == null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    // 只用系统字体 400 那一档：ColorOS 的 fonts.xml 把 sans-serif 的
                    // 100~900 全指到同一个可变文件，走 wght 轴出来的反而更细/更窄，
                    // 所以字重不靠轴，靠下面 F2 的描边补（见 KEY_WEIGHT_STROKE_RATIO）。
                    keySystemTypeface = android.graphics.Typeface.create(
                            android.graphics.Typeface.SANS_SERIF, 400, false);
                }
            } catch (Throwable ignore) {
            }
            if (keySystemTypeface == null) {
                keySystemTypeface = android.graphics.Typeface.SANS_SERIF;
            }
        }
        return keySystemTypeface;
    }

    /** 当前调用是否来自「按键」的画笔构造（构造期调用，走栈判断开销可忽略） */
    private static boolean isKeyFontCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            for (String caller : KEY_FONT_CALLERS) {
                if (cls.startsWith(caller)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 把这台键盘上所有「按键文字画笔」换成系统字体 + wght 450。
     * 每台键盘实例解析一次（少数几个 Paint），之后每帧只比对，未生效才写一次。
     */
    private void applyKeyTextFont(Object keyboard) {
        android.graphics.Paint[] paints = keyFontPaints.get(keyboard);
        if (paints == null) {
            paints = collectKeyPaints(keyboard);
            if (paints.length == 0) {
                return; // 绘制器还没建好，下一帧再来
            }
            keyFontPaints.put(keyboard, paints);
            if (keyFontLog.getAndIncrement() < 3) {
                probe("key font paints=" + paints.length
                        + " keyboard=" + keyboard.getClass().getSimpleName());
            }
        }
        applyKeyFontToPaints(paints);
    }

    /**
     * 单个按键绘制器在画每一颗键之前都会被调用（drawmethod.*#b(Canvas, j)），
     * 在这里把它的画笔改掉最稳：不管抽屉是构造期还是什么时候建的，画之前一定经过这里。
     */
    private void patchDrawerKeyFont(Object drawer) {
        android.graphics.Paint[] paints = keyFontPaints.get(drawer);
        if (paints == null) {
            List<android.graphics.Paint> out = new ArrayList<>();
            collectDrawerPaints(drawer, out);
            paints = out.toArray(new android.graphics.Paint[0]);
            keyFontPaints.put(drawer, paints);
            if (keyFontLog.getAndIncrement() < 8) {
                // 只在前几颗绘制器上留一条痕，方便确认字体真的落到了 w450
                android.graphics.Typeface tf = paints.length > 0 ? paints[0].getTypeface() : null;
                probe("key font drawer=" + drawer.getClass().getSimpleName()
                        + " paints=" + paints.length + " w=" + (tf == null ? -1 : tf.getWeight()));
            }
        }
        applyKeyFontToPaints(paints);
    }

    /**
     * 按键自绘投影：在每颗键画自己之前，先按它的可视矩形画一层圆角阴影。
     *
     * 只在这里画，所以只影响按键：候选栏、面板、logo 都走别的绘制路径，不会被误伤。
     * 宿主自带阴影（j.K / j.L 两个字段）依然逐帧清零，这里**不恢复**它们。
     */
    private void drawKeyShadow(android.graphics.Canvas canvas, Object button) {
        if (!keyShadowEnabled || canvas == null || button == null) {
            return;
        }
        int alpha = keyShadowAlpha;
        if (alpha <= 0) {
            return;
        }
        try {
            if (keyDrawRectField == null) {
                keyDrawRectField = findField(button.getClass(), "l", android.graphics.Rect.class);
            }
            if (keyCornerField == null) {
                keyCornerField = findField(button.getClass(), "I", int.class);
            }
            android.graphics.Rect r = keyDrawRectField == null
                    ? null : (android.graphics.Rect) keyDrawRectField.get(button);
            if (r == null || r.width() <= 0 || r.height() <= 0) {
                return;
            }
            int corner = keyCornerField == null ? 0 : keyCornerField.getInt(button);
            float dy = keyShadowDyPx;

            int base = keyShadowColor;
            int a = (int) ((alpha / 100f) * ((base >>> 24) == 0 ? 255 : (base >>> 24))) ;
            keyShadowPaint.setColor((a << 24) | (base & 0x00FFFFFF));
            keyShadowPaint.setStyle(android.graphics.Paint.Style.FILL);
            if (keyShadowBlurPx != keyShadowBlurApplied) {
                keyShadowBlurApplied = keyShadowBlurPx;
                if (keyShadowBlurPx > 0f) {
                    keyShadowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(
                            keyShadowBlurPx, android.graphics.BlurMaskFilter.Blur.NORMAL));
                } else {
                    keyShadowPaint.setMaskFilter(null);
                }
            }
            float rad = corner <= 0 ? 0f : Math.min(corner, Math.min(r.width(), r.height()) / 2f);
            // 投影预先烘焙成位图：模糊只算一次，每帧只是 drawBitmap。
            // 位图里已经把按键本体的圆角矩形「挖空」，所以投影只会在按键外面。
            int a2 = (int) ((alpha / 100f)
                    * ((keyShadowColor >>> 24) == 0 ? 255 : (keyShadowColor >>> 24)));
            int color = (a2 << 24) | (keyShadowColor & 0x00FFFFFF);
            ShadowBmp sb = obtainShadowBitmap(r.width(), r.height(), rad, dy,
                    keyShadowBlurPx, color);
            if (sb != null) {
                canvas.drawBitmap(sb.bmp, r.left - sb.pad, r.top + dy - sb.pad, null);
            }
        } catch (Throwable t) {
            keyShadowEnabled = false;
            probe("key shadow draw failed: " + t);
        }
    }

    /** 在类及其父类里找一个指定名字/类型的字段 */
    private static java.lang.reflect.Field findField(Class<?> cls, String name, Class<?> type) {
        while (cls != null && cls != Object.class) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(name);
                if (f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (Throwable ignore) {
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** 网页调色里的按键文字色（0 = 没配，标签用主题默认色） */
    private volatile int keyTextPaletteColor = 0;
    /** HyperOS 降级只打一条探针 */
    private boolean hyperOsLogged = false;

    /**
     * 下滑功能：设置页那份 JSON（App 推到 wetype-dark-host.json，这里 300ms 缓存一次）。
     */
    private org.json.JSONObject gestureSettings() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - gestureCfgAt < 300L) {
            return gestureCfg;
        }
        gestureCfgAt = now;
        String raw = HostConfig.INSTANCE.gestureSettingsJson();
        if (raw == null || raw.equals(gestureCfgRaw)) {
            return gestureCfg;
        }
        gestureCfgRaw = raw;
        try {
            gestureCfg = new org.json.JSONObject(raw);
        } catch (Throwable t) {
            gestureCfg = null;
        }
        return gestureCfg;
    }

    /** 按键 id → 绑定表里的键名（字母 / 数字 / space / enter） */
    private static String gestureKeyOf(String id) {
        if (id == null) {
            return null;
        }
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("space")) {
            return "space";
        }
        if (lower.contains("enter")) {
            return "enter";
        }
        int idx = lower.lastIndexOf("_key_");
        String tail = idx >= 0 ? lower.substring(idx + 5) : lower;
        if (tail.length() == 1) {
            char c = tail.charAt(0);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                return String.valueOf(c);
            }
        }
        return null;
    }

    private static final java.util.HashMap<Class<?>, java.util.List<java.lang.reflect.Field>>
            keyTextFieldCache = new java.util.HashMap<>();

    /**
     * 取宿主这颗键上真正的字（不限长度，取最长的那个字符串字段，通常是 "ABC" 这样的键帽主字）。
     * 数字页 / 符号页会把 id 复用成 S1_key_1/2/3，靠这个字来区分「ABC 键」和「数字 2 键」。
     */
    private static String hostKeyText(Object button) {
        try {
            Class<?> cls = button.getClass();
            java.util.List<java.lang.reflect.Field> fs;
            synchronized (keyTextFieldCache) {
                fs = keyTextFieldCache.get(cls);
            }
            if (fs == null) {
                fs = collectKeyTextFields(cls);
            }
            String best = null;
            for (java.lang.reflect.Field f : fs) {
                try {
                    Object v = f.get(button);
                    if (!(v instanceof CharSequence)) {
                        continue;
                    }
                    String t = v.toString().trim();
                    // 只要像键帽字的（数字 / 字母 / 少量中文），别把 id 之类的长串当成键帽字
                    if (t.isEmpty() || t.length() > 4) {
                        continue;
                    }
                    if (best == null || t.length() > best.length()) {
                        best = t;
                    }
                } catch (Throwable ignore) {
                }
            }
            return best;
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 键上的图标 id（G()/H() 是 image / imagePressed 资源；0 = 这颗键没有图标） */
    private static int keyIconId(Object button) {
        try {
            Class<?> cls = button.getClass();
            java.lang.reflect.Method g = cls.getDeclaredMethod("G");
            g.setAccessible(true);
            Object v = g.invoke(button);
            if (v instanceof Integer && (Integer) v != 0) {
                return (Integer) v;
            }
            java.lang.reflect.Method h = cls.getDeclaredMethod("H");
            h.setAccessible(true);
            Object v2 = h.invoke(button);
            if (v2 instanceof Integer) {
                return (Integer) v2;
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private static java.util.List<java.lang.reflect.Field> collectKeyTextFields(Class<?> cls) {
        java.util.List<java.lang.reflect.Field> fs = new java.util.ArrayList<>();
        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            if (f.getType() == String.class || f.getType() == CharSequence.class) {
                try {
                    f.setAccessible(true);
                } catch (Throwable ignore) {
                    continue;
                }
                fs.add(f);
            }
        }
        synchronized (keyTextFieldCache) {
            keyTextFieldCache.put(cls, fs);
        }
        return fs;
    }

    /**
     * 取宿主这颗键上真正的字（单字符）。符号页会把 id 复用成 S1_key_1/2/3，
     * 所以不能只看 id 判断这是不是「数字 1/2/3 键」。
     */
    private static String hostKeyChar(Object button) {
        try {
            Class<?> cls = button.getClass();
            java.util.List<java.lang.reflect.Field> fs;
            synchronized (keyTextFieldCache) {
                fs = keyTextFieldCache.get(cls);
            }
            if (fs == null) {
                fs = new java.util.ArrayList<>();
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    if (f.getType() == String.class || f.getType() == CharSequence.class) {
                        try {
                            f.setAccessible(true);
                        } catch (Throwable ignore) {
                            continue;
                        }
                        fs.add(f);
                    }
                }
                synchronized (keyTextFieldCache) {
                    keyTextFieldCache.put(cls, fs);
                }
            }
            for (java.lang.reflect.Field f : fs) {
                try {
                    Object v = f.get(button);
                    if (v instanceof CharSequence) {
                        String t = v.toString().trim();
                        if (t.length() == 1) {
                            return t;
                        }
                    }
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /**
     * 把绑定的动作短名画在键帽下方（宿主所有键都画完之后调用，标签压在键面上）。
     *
     * 受 [com.tony.wetypedark.ui.GestureSettings] 里的 showLabel / 颜色 / 字号 / 偏移控制。
     */
    private void drawKeyGestureLabel(android.graphics.Canvas canvas, Object button,
                                     String keyId) {
        try {
            org.json.JSONObject s = gestureSettings();
            if (s == null || !s.optBoolean("showLabel", true)) {
                return;
            }
            String key = gestureKeyOf(keyId);
            if (key == null) {
                return;
            }
            // 数字页会复用同一个 key_N → 数字 2 的绑定，键帽上只有「2」，
            // 九键那颗才写着「ABC」。所以九键数字键必须要求键帽字里带对应的字母组，
            // 26 键要求键帽字就是那个字母；对不上就不画，免得数字页冒出全选/剪切/复制/粘贴。
            String actual = hostKeyText(button);
            if (actual == null) {
                return;
            }
            String letters = T9_LETTERS.get(key);
            if (letters != null) {
                if (!actual.toUpperCase().contains(letters)) {
                    return;
                }
            } else if (!actual.equalsIgnoreCase(key)) {
                return;
            }
            org.json.JSONObject bind = s.optJSONObject("bind");
            if (bind == null) {
                return;
            }
            // 九键 / 26 键各自一张表，双份都试（键 id 自己说明了布局）
            String actionName = "";
            org.json.JSONObject t9One = bind.optJSONObject("T9");
            org.json.JSONObject qOne = bind.optJSONObject("QWERTY");
            if (t9One != null && !t9One.optString(key, "").isEmpty()) {
                actionName = t9One.optString(key);
            } else if (qOne != null) {
                actionName = qOne.optString(key, "");
            }
            if (actionName.isEmpty()) {
                return;
            }
            String label = GESTURE_SHORT.get(actionName);
            if (label == null) {
                return;
            }
            android.graphics.Rect r = (android.graphics.Rect) keyDrawRectField.get(button);
            if (r == null || r.width() <= 0) {
                return;
            }
            float density = 3.5f;
            if (button instanceof View) {
                density = ((View) button).getResources().getDisplayMetrics().density;
            }
            int color;
            if (keyFnTextColor != 0) {
                // 网页「小字颜色」优先级最高，alpha 也按网页给的算
                color = keyFnTextColor;
            } else {
                if (s.optBoolean("custom", false)) {
                    color = nightMode ? s.optInt("dark", 0xFF0A6AFF) : s.optInt("light", 0xFF0A6AFF);
                } else {
                    // 不跟「字符颜色」走：用宿主自己的小字色（深色白、亮色黑）
                    color = nightMode ? 0xFFF0F0E9 : 0xFF141414;
                }
                color = (color & 0x00FFFFFF) | 0xB0000000;
            }
            float sizePx = Math.max(1, s.optInt("fs", 9)) * density;
            float ox = s.optInt("ox", 0) * density;
            float oy = s.optInt("oy", 0) * density;

            gestureLabelPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
            gestureLabelPaint.setColor(color);
            gestureLabelPaint.setTextSize(sizePx);
            gestureLabelPaint.setTypeface(systemKeyTypeface());

            float cx = r.centerX() + ox;
            float baseline = r.bottom - 6f * density + oy;
            canvas.drawText(label, cx, baseline, gestureLabelPaint);
            if (gestureLabelLog.getAndIncrement() < 4) {
                probe("gesture label " + keyId + " key=" + key + " -> " + label
                        + " at " + (int) cx + "," + (int) baseline);
            }
        } catch (Throwable t) {
            probe("gesture labels failed: " + t);
        }
    }

    /** 九键数字键 → 键帽上的字母组（2=ABC …），用来区分「九键那颗」和「数字页那颗」 */
    private static final java.util.HashMap<String, String> T9_LETTERS =
            new java.util.HashMap<String, String>();

    static {
        String[] groups = {"", "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"};
        for (int i = 2; i <= 9; i++) {
            T9_LETTERS.put(String.valueOf(i), groups[i]);
        }
    }

    private final java.util.Set<String> inkProbeSeen =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** 诊断：把每颗键的几种「墨色」取值打一行，用来找漏覆盖的键 */
    private void probeKeyInk(Object btn, String id) {
        try {
            if (btn == null || id == null || inkProbeSeen.size() >= 40 || !inkProbeSeen.add(id)) {
                return;
            }
            Class<?> cls = btn.getClass();
            String[] names = {"q", "s", "t", "w", "G"};
            StringBuilder sb = new StringBuilder("ink " + id);
            for (String nm : names) {
                try {
                    java.lang.reflect.Method m = cls.getDeclaredMethod(nm);
                    m.setAccessible(true);
                    Object v = m.invoke(btn);
                    sb.append(' ').append(nm).append('=')
                            .append(v instanceof Integer ? Integer.toHexString((Integer) v) : String.valueOf(v));
                } catch (Throwable ignore) {
                    sb.append(' ').append(nm).append("=?");
                }
            }
            try {
                java.lang.reflect.Method tm = cls.getDeclaredMethod("M");
                tm.setAccessible(true);
                sb.append(" text=").append(tm.invoke(btn));
            } catch (Throwable ignore) {
            }
            probe(sb.toString());
        } catch (Throwable ignore) {
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger gestureLabelLog =
            new java.util.concurrent.atomic.AtomicInteger();

    private void applyKeyFontToPaints(android.graphics.Paint[] paints) {
        android.graphics.Typeface tf = systemKeyTypeface();
        for (android.graphics.Paint paint : paints) {
            try {
                // 之前试过 wght 轴，ColorOS 上会落到更细的那一档，先清掉
                if (paint.getFontVariationSettings() != null) {
                    paint.setFontVariationSettings(null);
                }
                if (!tf.equals(paint.getTypeface())) {
                    paint.setTypeface(tf);
                }
                synchronized (keyTextPaints) {
                    keyTextPaints.put(paint, Boolean.TRUE);
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private boolean isKeyTextPaint(android.graphics.Paint paint) {
        synchronized (keyTextPaints) {
            return keyTextPaints.containsKey(paint);
        }
    }

    /** 扫键盘实例的字段，收集所有按键绘制器里的 Paint（含 drawerMap 里那批） */
    private android.graphics.Paint[] collectKeyPaints(Object keyboard) {
        List<android.graphics.Paint> out = new ArrayList<>();
        Class<?> cls = keyboard.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                String type = f.getType().getName();
                boolean holder = KEY_DRAWER_HOLDER.equals(type);
                boolean drawer = type.startsWith(KEY_DRAWER_PKG);
                if (!holder && !drawer) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                } catch (Throwable ignore) {
                    continue;
                }
                Object value = null;
                try {
                    value = f.get(keyboard);
                } catch (Throwable ignore) {
                }
                if (value == null) {
                    continue;
                }
                if (drawer) {
                    collectDrawerPaints(value, out);
                } else {
                    collectMapDrawerPaints(value, out);
                }
            }
            cls = cls.getSuperclass();
        }
        return out.toArray(new android.graphics.Paint[0]);
    }

    /** selfdraw.e：drawerMap（Map<String, drawmethod.a>）里装着全部按键绘制器 */
    private void collectMapDrawerPaints(Object holder, List<android.graphics.Paint> out) {
        for (java.lang.reflect.Field f : holder.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (!Map.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object raw = f.get(holder);
                if (!(raw instanceof Map)) {
                    continue;
                }
                for (Object drawer : ((Map<?, ?>) raw).values()) {
                    if (drawer != null) {
                        collectDrawerPaints(drawer, out);
                    }
                }
                return;
            } catch (Throwable ignore) {
            }
        }
    }

    /** 单个绘制器：取出它（含父类）里所有 Paint 字段 */
    private void collectDrawerPaints(Object drawer, List<android.graphics.Paint> out) {
        java.lang.reflect.Field[] fields = paintFieldsByClass.computeIfAbsent(
                drawer.getClass(), c -> {
                    List<java.lang.reflect.Field> list = new ArrayList<>();
                    Class<?> k = c;
                    while (k != null && k != Object.class) {
                        for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                                continue;
                            }
                            if (f.getType() != android.graphics.Paint.class) {
                                continue;
                            }
                            try {
                                f.setAccessible(true);
                                list.add(f);
                            } catch (Throwable ignore) {
                            }
                        }
                        k = k.getSuperclass();
                    }
                    return list.toArray(new java.lang.reflect.Field[0]);
                });
        for (java.lang.reflect.Field f : fields) {
            try {
                Object value = f.get(drawer);
                if (value instanceof android.graphics.Paint && !out.contains(value)) {
                    out.add((android.graphics.Paint) value);
                }
            } catch (Throwable ignore) {
            }
        }
    }

    /** 宿主键面原色：值等于它的 int 字段就是键面底色字段 */
    private static final int HOST_FILL_LIGHT = 0x99FFFFFF;
    private static final int HOST_FILL_DARK = 0x14FFFFFF;
    /** 网页调色写 live 文件的路径：先看内部存储真实路径，再看 /sdcard 视图 */
    private static final String[] APPEARANCE_LIVE_PATHS = {
            "/data/user/0/com.tencent.wetype/files/appearance-live.json",
            "/storage/emulated/0/Android/data/com.tencent.wetype/files/appearance-live.json",
    };
    /** 逐应用模式表（设置页写，格式 {"包名":0..3}，0亮色圆角 1亮色直角 2深色 3极暗） */
    private static final String[] APP_MODE_PATHS = {
            "/data/user/0/com.tencent.wetype/files/wetype-dark-apps.json",
            "/data/media/0/Android/data/com.tencent.wetype/files/wetype-dark-apps.json",
            "/storage/emulated/0/Android/data/com.tencent.wetype/files/wetype-dark-apps.json",
    };
    private final java.util.concurrent.atomic.AtomicInteger cornerDumpCount =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 键对象 → 圆角字段（selfdraw.j#I，即 getBgCorner 对应的 int 字段） */
    private final Map<Object, java.lang.reflect.Field> cornerFieldByKey =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, java.lang.reflect.Field>());

    private java.lang.reflect.Field bgCornerFieldOf(Object key) {
        java.lang.reflect.Field cached = cornerFieldByKey.get(key);
        if (cached != null) {
            return cached;
        }
        java.lang.reflect.Field found = null;
        try {
            Class<?> cls = key.getClass();
            while (cls != null && cls != Object.class && found == null) {
                try {
                    java.lang.reflect.Field f = cls.getDeclaredField("I");
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        found = f;
                    }
                } catch (Throwable ignore) {
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignore) {
        }
        if (found != null) {
            cornerFieldByKey.put(key, found);
        }
        return found;
    }

    // ===== 按键高光描边 =====
    private final android.graphics.Paint keyRimPaint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF keyRimRect = new android.graphics.RectF();
    private final Map<Class<?>, java.lang.reflect.Method> keyRimRectMethods =
            java.util.Collections.synchronizedMap(new HashMap<Class<?>, java.lang.reflect.Method>());
    private final Map<String, java.lang.reflect.Field> keyRimFields = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger enterStyleLog =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 发送键三态配色（0 = 不接管，沿用宿主 / 主题色） */
    private volatile int sendActiveBg = 0;
    private volatile int sendActiveText = 0;
    private volatile int sendGreyBg = 0;
    private volatile int sendGreyText = 0;
    private volatile int sendDisabledBg = 0;
    private volatile int sendDisabledText = 0;
    private volatile int sendDisabledAlphaPct = -1;
    private volatile int sendMaskAlphaPct = -1;
    private final Map<Class<?>, java.lang.reflect.Method> keyRimIdMethods =
            java.util.Collections.synchronizedMap(new HashMap<Class<?>, java.lang.reflect.Method>());
    private final java.util.concurrent.atomic.AtomicInteger keyRimFailCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger keyRimOkCount =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 按键高光描边宽度（px），0 = 关闭；由调色页 key_rim_px 下发 */
    private volatile float keyRimWidthPx = 2f;
    /** 按键高光描边的高斯模糊（px，0 = 不模糊）；模糊结果按按键轮廓裁剪，绝不糊出键外 */
    private volatile float keyRimBlurPx = 0f;
    /** 烘焙好的「模糊描边」位图：按尺寸 + 圆角 + 线宽 + 模糊 + 着色器缓存 */
    private static final class RimBmp {
        final android.graphics.Bitmap bmp;
        final int pad;
        RimBmp(android.graphics.Bitmap bmp, int pad) { this.bmp = bmp; this.pad = pad; }
    }
    private final java.util.HashMap<String, RimBmp> rimBlurCache = new java.util.HashMap<>();

    private RimBmp obtainRimBlurBitmap(int w, int h, float corner, float stroke, float blur,
                                       android.graphics.Shader shader, android.graphics.Path path) {
        String key = w + "x" + h + "|" + corner + "|" + stroke + "|" + blur
                + "|" + System.identityHashCode(shader);
        RimBmp cached = rimBlurCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            int pad = (int) Math.ceil(blur * 2f) + 3;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w + pad * 2, h + pad * 2, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            c.translate(pad, pad);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setShader(shader);
            p.setMaskFilter(new android.graphics.BlurMaskFilter(
                    blur, android.graphics.BlurMaskFilter.Blur.NORMAL));
            c.drawPath(path, p);
            RimBmp entry = new RimBmp(bmp, pad);
            if (rimBlurCache.size() > 32) {
                for (RimBmp old : rimBlurCache.values()) {
                    if (old.bmp != null && !old.bmp.isRecycled()) {
                        old.bmp.recycle();
                    }
                }
                rimBlurCache.clear();
            }
            rimBlurCache.put(key, entry);
            return entry;
        } catch (Throwable t) {
            probe("rim blur bitmap failed: " + t);
            return null;
        }
    }
    /** 按键高光描边的透明度（0–255），由调色页 key_rim_alpha（%）换算 */
    private volatile int keyRimAlpha = 255;
    /** 描边渐变（网页「按键高光描边」那块）：多色标 + 方向角，null = 用旧的白色三段 */
    private volatile int[] rimStopColors = null;
    private volatile float[] rimStopPos = null;
    private volatile float rimAngle = 90f;
    private final java.util.LinkedHashMap<String, android.graphics.Shader> rimShaders =
            new java.util.LinkedHashMap<String, android.graphics.Shader>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, android.graphics.Shader> e) {
                    return size() > 64;
                }
            };


    /** 取 selfdraw.j 的字段（缓存），拿不到返回 null */
    private java.lang.reflect.Field enterField(Class<?> cls, String name) {
        java.lang.reflect.Field f = keyRimFields.get(cls.getName() + "#" + name);
        if (f != null) {
            return f;
        }
        try {
            f = cls.getDeclaredField(name);
            f.setAccessible(true);
            keyRimFields.put(cls.getName() + "#" + name, f);
        } catch (Throwable ignore) {
            return null;
        }
        return f;
    }

    /**
     * 大发送键的「灰 / 禁用」态：底色换成主题色、字改纯白、去掉宿主自带描边与投影。
     * 只在绘制期间改，画完还原。返回旧值数组，null = 本次未改。
     */
    /** 发送键这一帧要用的文字色（只在它自己那一次绘制内有效） */
    private static final ThreadLocal<int[]> ENTER_TEXT = new ThreadLocal<>();
    /** 发送键这一帧的键面底色，用来盖掉宿主自带的描边 */
    private static final ThreadLocal<int[]> ENTER_BG = new ThreadLocal<>();

    /**
     * 发送键自带一圈 0.5dp 的描边（宿主用 ime_color_09 画），跟我们的高光描边叠在一起很难看。
     * 宿主这圈边是在它自己的绘制里画的，字段清零不一定拦得住，所以这里直接把它涂掉：
     * 沿键面同一条连续曲率路径、只在内侧 stroke 一圈键面底色，把宿主那圈边吃掉，
     * 再由 drawKeyRim 画我们唯一的那圈。
     */
    private void coverEnterBorder(android.graphics.Canvas canvas, Object button) {
        int[] bgv = ENTER_BG.get();
        if (canvas == null || button == null || bgv == null || bgv[0] == 0) {
            return;
        }
        try {
            Class<?> cls = button.getClass();
            java.lang.reflect.Method rectM = keyRimRectMethods.get(cls);
            if (rectM == null) {
                rectM = cls.getDeclaredMethod("t");
                rectM.setAccessible(true);
                keyRimRectMethods.put(cls, rectM);
            }
            android.graphics.Rect r = (android.graphics.Rect) rectM.invoke(button);
            if (r == null || r.width() < 8 || r.height() < 8) {
                return;
            }
            float radius = keyRadiusPx > 0f ? keyRadiusPx : 24f;
            android.graphics.Path path = keyBgPath(r.width(), r.height(), radius);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(10f);
            p.setColor(bgv[0]);
            int save = canvas.save();
            canvas.translate(r.left, r.top);
            canvas.clipPath(path);
            canvas.drawPath(path, p);
            canvas.restoreToCount(save);
        } catch (Throwable ignore) {
        }
    }

    private int[] applyEnterStyle(Object button) {
        if (button == null) {
            return null;
        }
        try {
            Class<?> cls = button.getClass();
            java.lang.reflect.Method idM = keyRimIdMethods.get(cls);
            if (idM == null) {
                idM = cls.getDeclaredMethod("K");     // ImeButton.getId()
                idM.setAccessible(true);
                keyRimIdMethods.put(cls, idM);
            }
            String id = String.valueOf(idM.invoke(button));
            if (!("enter".equals(id) || "return".equals(id) || "send".equals(id))) {
                return null;
            }
            java.lang.reflect.Field bg = enterField(cls, "J");        // bgColor
            if (bg == null) {
                return null;
            }
            int cur = bg.getInt(button);
            if (cur == 0) {
                return null;   // 透明键不接管
            }
            java.lang.reflect.Field border = enterField(cls, "M");    // borderColor
            java.lang.reflect.Field shadow = enterField(cls, "K");    // shadowColor
            java.lang.reflect.Field text = enterField(cls, "s");      // mainTextColor
            java.lang.reflect.Field textP = enterField(cls, "t");     // mainTextColorPressed
            java.lang.reflect.Field icon = enterField(cls, "G");      // iconColor
            java.lang.reflect.Field iconP = enterField(cls, "H");     // iconPressedColor
            java.lang.reflect.Field mask = enterField(cls, "O");      // pressMaskColor
            int textCur = text == null ? 0xFFFFFFFF : text.getInt(button);
            int[] old = new int[]{
                    cur,
                    border == null ? -1 : border.getInt(button),
                    shadow == null ? -1 : shadow.getInt(button),
                    textCur,
                    textP == null ? -1 : textP.getInt(button),
                    icon == null ? -1 : icon.getInt(button),
                    iconP == null ? -1 : iconP.getInt(button),
                    mask == null ? -1 : mask.getInt(button),
            };
            int accentColor = accentFromLive != 0 ? accentFromLive : accentFromPalette;
            boolean active = (cur & 0x00FFFFFF) == 0x0023C891
                    || (accentColor != 0 && cur == accentColor);
            boolean disabled = ((textCur >>> 24) & 0xFF) <= 0x50;
            int cfgBg = active ? sendActiveBg : disabled ? sendDisabledBg : sendGreyBg;
            int cfgText = active ? sendActiveText : disabled ? sendDisabledText : sendGreyText;
            int bgNew = cfgBg != 0 ? cfgBg : (accentColor != 0 ? accentColor : cur);
            bg.setInt(button, bgNew);
            int textNew = cfgText != 0 ? cfgText : 0xFFFFFFFF;
            if (disabled && sendDisabledAlphaPct >= 0) {
                textNew = (textNew & 0x00FFFFFF)
                        | (Math.round(255f * sendDisabledAlphaPct / 100f) << 24);
            }
            if (text != null) {
                text.setInt(button, textNew);
            }
            if (textP != null) {
                textP.setInt(button, textNew);
            }
            if (icon != null) {
                icon.setInt(button, (textNew & 0x00FFFFFF) | 0xFF000000);
            }
            if (iconP != null) {
                iconP.setInt(button, (textNew & 0x00FFFFFF) | 0xFF000000);
            }
            if (border != null) {
                border.setInt(button, 0);
            }
            if (shadow != null) {
                shadow.setInt(button, 0);
            }
            if (mask != null && sendMaskAlphaPct >= 0 && old[7] != 0) {
                int alpha = Math.round(255f * sendMaskAlphaPct / 100f);
                mask.setInt(button, (old[7] & 0x00FFFFFF) | (alpha << 24));
            }
            ENTER_TEXT.set(new int[]{textNew});
            ENTER_BG.set(new int[]{bgNew});
            if (enterStyleLog.getAndIncrement() < 5) {
                probe("enter style bg=0x" + Integer.toHexString(cur)
                        + " text=0x" + Integer.toHexString(textCur)
                        + " active=" + active + " disabled=" + disabled
                        + " -> bg=0x" + Integer.toHexString(bgNew)
                        + " text=0x" + Integer.toHexString(textNew));
            }
            return old;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private void restoreEnterStyle(Object button, int[] old) {
        ENTER_TEXT.remove();
        ENTER_BG.remove();
        if (old == null || button == null) {
            return;
        }
        try {
            Class<?> cls = button.getClass();
            String[] names = {"J", "M", "K", "s", "t", "G", "H", "O"};
            for (int i = 0; i < names.length; i++) {
                if (old[i] < 0) {
                    continue;
                }
                java.lang.reflect.Field f = enterField(cls, names[i]);
                if (f != null) {
                    f.setInt(button, old[i]);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * 按键高光描边：2px，垂直渐变「顶部纯白 → 中间透明 → 底部纯白」。
     * 只挂在各键盘绘制器 drawmethod.c/d/h 的 a(Canvas, j) 上，键画完补一圈，
     * 因此天然只落在按键上，碰不到候选栏、面板、装饰。
     */
    private void drawKeyRim(android.graphics.Canvas canvas, Object button) {
        if (canvas == null || button == null) {
            return;
        }
        try {
            Class<?> cls = button.getClass();
            java.lang.reflect.Method rectM = keyRimRectMethods.get(cls);
            if (rectM == null) {
                rectM = cls.getDeclaredMethod("t");   // ImeButton.getDrawRect()
                rectM.setAccessible(true);
                keyRimRectMethods.put(cls, rectM);
            }
            java.lang.reflect.Method idM = keyRimIdMethods.get(cls);
            if (idM == null) {
                idM = cls.getDeclaredMethod("K");     // ImeButton.getId()
                idM.setAccessible(true);
                keyRimIdMethods.put(cls, idM);
            }
            String id = String.valueOf(idM.invoke(button));
            // 候选栏之类的伪键不画；左侧那条标点长条栏（id 里带 recycle）是真实键面，
            // 必须一起描，否则只有右边按键有边、长条是光的
            if (id.contains("cand")) {
                return;
            }
            java.lang.reflect.Field bg = fillFieldOf(button);
            if (bg == null || bg.getInt(button) == 0) {
                return;   // 没有键面的透明键不描
            }
            android.graphics.Rect r = (android.graphics.Rect) rectM.invoke(button);
            if (r == null || r.width() < 8 || r.height() < 8) {
                return;
            }
            java.lang.reflect.Field cornerF = bgCornerFieldOf(button);
            int cornerPx = cornerF == null ? 0 : cornerF.getInt(button);
            float stroke = Math.max(0f, Math.min(6f, keyRimWidthPx));
            if (stroke <= 0f) {
                return;
            }
            // 描边必须跟键面走同一条连续曲率路径，否则键圆角改了描边还是圆弧，角上会错位
            float innerW = r.width() - stroke;
            float innerH = r.height() - stroke;
            if (innerW < 6f || innerH < 6f) {
                return;
            }
            // 左下角返回键仍然保持宿主自己画的键面（普通圆弧），描边跟着它的圆角走
            boolean hostOnly = "back".equals(id) && r.left <= CORNER_LEFT_PX;
            float corner = (!hostOnly && keyRadiusPx > 0f)
                    ? Math.max(0f, keyRadiusPx - stroke / 2f)
                    : Math.max(0f, cornerPx - stroke / 2f);
            corner = Math.min(corner, Math.min(innerW, innerH) / 2f);
            int rimA = Math.max(0, Math.min(255, keyRimAlpha));
            int rimWhite = (rimA << 24) | 0x00FFFFFF;
            int[] cols = rimStopColors;
            float[] pos = rimStopPos;
            if (cols != null && pos != null && cols.length >= 2) {
                // 网页配的渐变：透明度已经按「描边透明 / 三段色标各自的 a」算进 ARGB 了
                int[] scaled = new int[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    int a = (((cols[i] >>> 24) * rimA) / 255) & 0xFF;
                    scaled[i] = (a << 24) | (cols[i] & 0x00FFFFFF);
                }
                keyRimPaint.setShader(rimShader(Math.round(innerW), Math.round(innerH),
                        scaled, pos, rimAngle));
                keyRimPaint.setColor(0xFFFFFFFF);
            } else {
                keyRimPaint.setShader(rimShader(Math.round(innerW), Math.round(innerH),
                        new int[]{rimWhite, 0x00FFFFFF, rimWhite},
                        new float[]{0f, 0.5f, 1f}, 90f));
                keyRimPaint.setColor(rimWhite);
            }
            keyRimPaint.setStyle(android.graphics.Paint.Style.STROKE);
            keyRimPaint.setStrokeWidth(stroke);
            keyRimPaint.setColor(rimWhite);
            android.graphics.Path rimPath = keyBgPath(innerW, innerH, corner);
            if (keyRimBlurPx > 0.5f) {
                // 高斯模糊：描边先烘焙到一张软件位图里（模糊只算一次），
                // 再裁到「整颗键」的连续曲率轮廓里贴回去 —— 所以糊不出按键边界
                RimBmp rb = obtainRimBlurBitmap(Math.round(innerW), Math.round(innerH),
                        corner, stroke, keyRimBlurPx, keyRimPaint.getShader(), rimPath);
                if (rb != null) {
                    float fullCorner = (!hostOnly && keyRadiusPx > 0f)
                            ? keyRadiusPx : Math.max(0f, cornerPx);
                    fullCorner = Math.min(fullCorner, Math.min(r.width(), r.height()) / 2f);
                    int sc = canvas.save();
                    canvas.translate(r.left, r.top);
                    canvas.clipPath(keyBgPath(r.width(), r.height(), fullCorner));
                    canvas.drawBitmap(rb.bmp, stroke / 2f - rb.pad, stroke / 2f - rb.pad, null);
                    canvas.restoreToCount(sc);
                }
            } else {
                int rimSave = canvas.save();
                canvas.translate(r.left + stroke / 2f, r.top + stroke / 2f);
                canvas.drawPath(rimPath, keyRimPaint);
                canvas.restoreToCount(rimSave);
            }
            keyRimPaint.setShader(null);
            if (keyRimOkCount.getAndIncrement() < 6) {
                probe("key rim " + id + " " + r.toShortString() + " corner=" + corner
                        + " use=keyRadiusPx(" + keyRadiusPx + ")");
            }
        } catch (Throwable t) {
            if (keyRimFailCount.getAndIncrement() < 3) {
                probe("key rim failed: " + t);
            }
        }
    }

    /** 读 key_rim_stops / key_rim_angle / key_rim_alpha_all，缺省就用旧的白色三段 */
    private void parseRimGradient(org.json.JSONObject set) {
        if (set == null) {
            rimStopColors = null;
            rimStopPos = null;
            return;
        }
        float all = (float) set.optDouble("key_rim_alpha_all", 100d);
        all = Math.max(0f, Math.min(100f, all)) / 100f;
        rimAngle = (float) set.optDouble("key_rim_angle", 90d);
        org.json.JSONArray arr = set.optJSONArray("key_rim_stops");
        if (arr == null || arr.length() < 2) {
            rimStopColors = null;
            rimStopPos = null;
            return;
        }
        int n = arr.length();
        int[] cols = new int[n];
        float[] pos = new float[n];
        for (int i = 0; i < n; i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                return;
            }
            // 网页偶尔会写进 "##RRGGBB"（多一个井号），先归一化，否则颜色解析失败会退回白色三段
            String raw = o.optString("c", "").trim();
            while (raw.startsWith("##")) raw = raw.substring(1);
            int rgb = parseHexColor(raw);
            int a = (int) (Math.max(0, Math.min(100, o.optInt("a", 100)) / 100f * all * 255f));
            cols[i] = (a << 24) | (rgb & 0x00FFFFFF);
            pos[i] = (float) Math.max(0d,
                    Math.min(1d, o.optDouble("p", (double) i / (n - 1))));
        }
        pos[0] = 0f;
        pos[n - 1] = 1f;
        for (int i = 1; i < n - 1; i++) {
            pos[i] = Math.max(pos[i - 1] + 0.001f, Math.min(0.999f, pos[i]));
        }
        rimStopColors = cols;
        rimStopPos = pos;
        probe("rim gradient: stops=" + n + " angle=" + rimAngle + " all=" + (int) (all * 100) + "%");
    }

    /** 描边渐变着色器：按方向角在键面矩形里取两端点，尺寸变了就重建 */
    private android.graphics.Shader rimShader(int w, int h, int[] colors, float[] pos, float angle) {
        StringBuilder sb = new StringBuilder();
        for (int c : colors) sb.append(Integer.toHexString(c)).append(',');
        sb.append(Math.round(angle)).append('|').append(w).append('x').append(h);
        String key = sb.toString();
        android.graphics.Shader sh = rimShaders.get(key);
        if (sh != null) {
            return sh;
        }
        float rad = (float) (angle * Math.PI / 180.0);
        float ux = (float) Math.cos(rad);
        float uy = (float) Math.sin(rad);
        float half = Math.abs(ux) * w / 2f + Math.abs(uy) * h / 2f;
        sh = new android.graphics.LinearGradient(
                w / 2f - ux * half, h / 2f - uy * half,
                w / 2f + ux * half, h / 2f + uy * half,
                colors, pos, android.graphics.Shader.TileMode.CLAMP);
        rimShaders.put(key, sh);
        return sh;
    }

    /** 键对象 → 键面底色字段（每颗键只扫一次字段） */
    private final Map<Object, java.lang.reflect.Field> pressFieldByKey =
            java.util.Collections.synchronizedMap(new WeakHashMap<Object, java.lang.reflect.Field>());
    private final java.util.concurrent.atomic.AtomicInteger pressHitCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final Map<Object, java.lang.reflect.Field> fillFieldByKey =
            java.util.Collections.synchronizedMap(new WeakHashMap<Object, java.lang.reflect.Field>());
    private final java.util.concurrent.atomic.AtomicInteger fillDumpCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger fillHitCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final Map<Class<?>, java.lang.reflect.Field[]> intFieldsByClass =
            new java.util.concurrent.ConcurrentHashMap<>();

    private volatile int panelColor = DEFAULT_PANEL_COLOR;
    private volatile int blurRadiusDp = DEFAULT_BLUR_RADIUS_DP;
    /** 面板（背景）圆角，网页调色台的全局项，亮/暗/极暗共用一份 */
    private volatile int panelRadiusDp = (int) RADIUS_DP;
    /** 已挂到面板上的圆角，用于判断圆角改了要重建背景层 */
    private volatile float appliedPanelRadiusPx = -1f;
    /** 用户上传的自定义话筒图标（空格键）：原始 data URL 与解出来的位图 */
    private final java.util.Set<String> spaceProbeSeen =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    /** 长按气泡字母的横向压缩，抵消系统字体 + 字重描边带来的字形外扩 */
    private static final float BUBBLE_TEXT_SCALE_X = 0.9f;
    private volatile String micIconData = "";
    private volatile android.graphics.Bitmap micIconBitmap;
    private volatile android.graphics.Paint micIconPaint;
    private boolean installedMicHook;
    /** 宿主皮肤（1 白 / 2 暗）已经切到哪一档，-1 表示还没同步过 */
    private volatile int hostSkinApplied = -1;
    /** 上一次已知应用对应的逐应用模式，包名还没送到时沿用它，避免闪一下亮色 */
    private volatile int lastKnownMode = -1;
    private java.lang.reflect.Method hostSkinGetManager;
    private java.lang.reflect.Method hostSkinCurrent;
    private java.lang.reflect.Method hostSkinSet;
    private final Map<Class<?>, java.lang.reflect.Method> keyRectMethods =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String lastFnSig = "";
    /** 调色板（appearance-live.json）里是否给了这三项：给了就以它为最高优先级 */
    private volatile boolean logoFromPalette = false;
    private volatile boolean circleFromPalette = false;
    private volatile boolean fnIconFromPalette = false;
    private volatile int logoIconColor = 0;
    private volatile int logoCircleColor = 0;
    /** 候选栏右侧功能键里的图标颜色（未配置为 0） */
    private volatile int fnIconColor = 0;
    /** 网页调色的「主题色」：live 文件里的 accentColor（优先）与调色板 send_bg（兜底） */
    private volatile int accentFromLive = 0;
    private volatile int accentFromPalette = 0;
    private final java.util.concurrent.atomic.AtomicInteger accentHitCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 面板顶部高光：顶部纯白、向下渐变到全透明，只覆盖面板顶部 N px */
    private static final int DEFAULT_TOP_GLOW_ALPHA = 100;   // 百分比 0–100
    private static final int DEFAULT_TOP_GLOW_PX = 30;
    private static final float DEFAULT_TOP_GLOW_WIDTH = 2f;   // 可见线宽 px
    private volatile int topGlowAlpha = DEFAULT_TOP_GLOW_ALPHA;
    private volatile int topGlowHeightPx = DEFAULT_TOP_GLOW_PX;
    private volatile float topGlowWidthPx = DEFAULT_TOP_GLOW_WIDTH;
    private volatile float appliedGlowWidth = -1f;
    private int appliedGlowAlpha = -1;
    private int appliedGlowPx = -1;
    private boolean logoLogged = false;
    private final Map<View, String> logoApplied = new WeakHashMap<>();
    private final Map<View, Drawable> logoSource = new WeakHashMap<>();
    private boolean dumpRequested = false;
    /** 悬浮键盘第一次出现时自动 dump 一次视图树，方便定位「哪些层还在用宿主原生色」 */
    private boolean floatingDumped = false;
    /** 已绑过布局监听的候选条（宿主换根布局时会换 View，需要重新绑） */
    private View candidateShiftBound;
    /** 候选词右移只打一条探针，避免刷屏 */
    private boolean candidateShiftLogged;
    /** 网页调色的「高亮候选框」：0 = 不接管颜色；-1 = 不接管圆角 */
    private volatile int candBoxColor = 0;
    private volatile int candBoxRadiusPx = -1;
    private boolean candBoxLogged;
    private boolean dividerShiftLogged;
    private long liveMtime = Long.MIN_VALUE;
    private volatile String liveText = null;
    private long liveLength = -1;
    private int appliedColor = 0;
    private int appliedBlurDp = -1;
    private boolean appliedFrosted = true;

    private String processName = "";
    /** 当前正在绘制的键盘类名（表情页是 S11EmojiKeyboard），给无 keyboard 引用的绘制入口用 */
    private static volatile String currentKbSimpleName = "";
    private WeakReference<View> panelRef = new WeakReference<>(null);
    /** HyperOS 窗口级背景模糊要用 IME 的 Window，这里留一份 service 引用 */
    private WeakReference<Object> imeServiceRef = new WeakReference<>(null);
    private boolean imeHooked = false;
    private boolean watchdogStarted = false;
    private boolean keyboardHooked = false;
    /** 候选栏右侧功能键：item View → 键名（emoji / clipboard / collapse） */
    private final Map<View, String> fnKeys = new WeakHashMap<>();
    private final Map<View, Boolean> fnScanDone = new WeakHashMap<>();
    private final Map<View, Boolean> fnLogged = new WeakHashMap<>();
    /** 候选栏右侧那排功能键的图标 ImageView（宿主每次刷色时在这里换成配置色） */
    private final Map<View, Boolean> fnGlyphViews =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());
    private WeakReference<View> fnRvRef = new WeakReference<>(null);
    private boolean fnTintHookInstalled;
    private final java.util.concurrent.atomic.AtomicInteger fnTintHit =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final String PKG_NAME = "com.tencent.wetype";

    /** HookHandle 必须强引用，否则会被回收导致 hook 失效 */
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<View, Boolean> outlined = new WeakHashMap<>();
    private final Map<View, Drawable> releasedBackgrounds = new WeakHashMap<>();

    /** 当前的模糊 Drawable 只对「同一个 ViewRootImpl」有效；窗口重建后必须重新取 */
    private Object blurRoot;
    private Drawable blurDrawable;
    private boolean oplusBlurUnavailable = false;

    private static Method getViewRootImplMethod;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!PKG.equals(param.getPackageName())) {
            return;
        }
        if (processName == null || !processName.endsWith(PROCESS)) {
            return;
        }
        try {
            installAttachWatcher();
            installAccentHooks();
            log(Log.INFO, TAG, "attach watcher installed in " + processName);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install hooks failed", t);
        }
    }

    // ===== 底栏两颗图标（收起输入法 ⌄ / 切换输入法 ⌨）：SVG 自绘的路径数据 =====
    /** 用户给的两份 97×97 矢量图，path 直接抄自 SVG（视口 97×97） */
    private static final String PATH_CHEVRON =
            "M80.24,37.24 L53.36,64.12 Q51.61,65.88 49.12,65.88 Q46.64,65.88 44.88,64.12 "
                    + "L18,37.24 L22.24,33 L49.12,59.88 L76,33 Z";
    private static final String PATH_KEYBOARD =
            "M7,70 L7,28 Q7,21.79 11.39,17.39 Q15.79,13 22,13 L76,13 Q82.21,13 86.61,17.39 "
                    + "Q91,21.79 91,28 L91,70 Q91,76.21 86.61,80.61 Q82.21,85 76,85 L22,85 "
                    + "Q15.79,85 11.39,80.61 Q7,76.21 7,70 Z "
                    + "M13,70 Q13,73.73 15.64,76.36 Q18.27,79 22,79 L76,79 Q79.73,79 "
                    + "82.36,76.36 Q85,73.73 85,70 L85,28 Q85,24.27 82.36,21.64 Q79.73,19 "
                    + "76,19 L22,19 Q18.27,19 15.64,21.64 Q13,24.27 13,28 Z "
                    + "M29,32 L37,32 L37,40 L29,40 Z M53,32 L45,32 L45,40 L53,40 Z "
                    + "M61,32 L69,32 L69,40 L61,40 Z M29,46 L37,46 L37,54 L29,54 Z "
                    + "M45,46 L53,46 L53,54 L45,54 Z M69,46 L61,46 L61,54 L69,54 Z "
                    + "M69,61 L29,61 L29,69 L69,69 Z";

    private static android.graphics.Path pathFromData(String data) {
        try {
            Class<?> pp = Class.forName("android.util.PathParser");
            Method create = pp.getDeclaredMethod("createPathFromPathData", String.class);
            create.setAccessible(true);
            return (android.graphics.Path) create.invoke(null, data);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private void install(Executable executable, String id, XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle handle = hook(executable).setId(id).intercept(hooker);
        handles.add(handle);
    }

    /**
     * 键盘侧：①捕获所有按键 ②删掉按键底部投影。
     *
     * 微信输入法的键盘不是「一键一 View」，整块键盘是一个自绘 View：
     *   selfdraw.o.dispatchTouchEvent → o.onTouch(View, MotionEvent)
     *     ACTION_DOWN → o2() 里做命中测试，把当前键写进字段 actionButton
     * 所以一个 onTouch hook 就能覆盖 26 键 / 九键 / 符号 / 数字 / 表情 / 手写全部键盘。
     *
     * 投影（那条贴着键底的圆角矩形）由 drawmethod/c.e() 与 drawmethod/h.i() 画，
     * 两处都用「button.getShadowColor() != 0」当开关；把 ImeButton.Y() 钉成 0 即可
     * 同时让两条绘制路径都不画，键高、背景、边框、按压高亮都不受影响。
     */
    private void installKeyboardHooks(ClassLoader cl) throws Throwable {
        Class<?> oCls = Class.forName(KEYBOARD_PKG + ".o", false, cl);
        Class<?> jCls = Class.forName(KEYBOARD_PKG + ".j", false, cl);

        // ① 删投影：直接改字段，不 hook getter。
        //    宿主 4.0.1 里 getShadowColor()/getShadowHeight() 被 R8 内联成字段读取
        //    （j.K:int / j.L:float），hook 方法根本不会被调用，必须在绘制前后改字段。
        final Field fShadowColor = jCls.getDeclaredField("K");
        final Field fShadowHeight = jCls.getDeclaredField("L");
        fShadowColor.setAccessible(true);
        fShadowHeight.setAccessible(true);

        final Method draw = oCls.getDeclaredMethod("draw", android.graphics.Canvas.class);
        final Method keyIdMethod = jCls.getDeclaredMethod("K");   // ImeButton.getId()
        final Method keyRectMethod = jCls.getDeclaredMethod("F");  // ImeButton.getHitRect()
        final Method getActionButtonMethod = oCls.getDeclaredMethod("getActionButton");
        final Field mapField = findKeyMapField(oCls);
        install(draw, "wetype_dark_key_shadow_draw", chain -> {
            Object keyboard = chain.getThisObject();
            try {
                currentKbSimpleName = keyboard.getClass().getSimpleName();
            } catch (Throwable ignore) {
            }
            // 按键文字：系统字体 + wght 450（地面上的画笔在这里逐个改写）
            applyKeyTextFont(keyboard);
            refreshHostExtras(keyboard);
            List<Object> buttons = new ArrayList<>();
            int[] colors = new int[0];
            float[] heights = new float[0];
            try {
                Object raw = mapField == null ? null : mapField.get(keyboard);
                if (raw instanceof Map) {
                    for (Object b : ((Map<?, ?>) raw).values()) {
                        if (b != null) {
                            buttons.add(b);
                        }
                    }
                    colors = new int[buttons.size()];
                    heights = new float[buttons.size()];
                    for (int i = 0; i < buttons.size(); i++) {
                        colors[i] = (Integer) fShadowColor.get(buttons.get(i));
                        heights[i] = (Float) fShadowHeight.get(buttons.get(i));
                        fShadowColor.set(buttons.get(i), 0);
                        fShadowHeight.set(buttons.get(i), 0f);
                    }
                }
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "zero shadow failed", t);
            }
            captureAllKeys(keyboard, buttons, keyIdMethod, keyRectMethod, getActionButtonMethod);

            // 网页调色的「按键圆角」：所有按键（含发送/完成键）统一套用，画完还原
            java.lang.reflect.Field[] cornerFields = null;
            int[] cornerOld = null;
            int[] corner = new int[]{keyRadiusPx};
            if (corner[0] > 0 && !buttons.isEmpty()) {
                cornerFields = new java.lang.reflect.Field[buttons.size()];
                cornerOld = new int[buttons.size()];
                for (int i = 0; i < buttons.size(); i++) {
                    Object b = buttons.get(i);
                    try {
                        if (isPseudoContainer(String.valueOf(keyIdMethod.invoke(b))) ) {
                            continue;
                        }
                        java.lang.reflect.Field f = bgCornerFieldOf(b);
                        if (f == null) {
                            continue;
                        }
                        cornerFields[i] = f;
                        cornerOld[i] = f.getInt(b);
                        f.setInt(b, corner[0]);
                    } catch (Throwable ignore) {
                    }
                }
                if (cornerDumpCount.getAndIncrement() < 3) {
                    probe("corner patch radius=" + corner[0] + "px keys=" + buttons.size());
                }
            }

            // 网页调色的「按键底色」：绘制前把键面字段换成配置色，画完立刻还原
            java.lang.reflect.Field[] fillFields = null;
            int[] fillOld = null;
            int[] keyFill = new int[]{keyFillColor};
            if (keyFill[0] != 0 && !buttons.isEmpty()) {
                fillFields = new java.lang.reflect.Field[buttons.size()];
                fillOld = new int[buttons.size()];
                int hostFill = nightMode ? HOST_FILL_DARK : HOST_FILL_LIGHT;
                if (fillDumpCount.getAndIncrement() < 3 && buttons.size() > 0) {
                    try {
                        Object b0 = buttons.get(0);
                        java.lang.reflect.Field[] fs = intFieldsByClass.computeIfAbsent(
                                b0.getClass(), c -> {
                                    java.util.List<java.lang.reflect.Field> list = new ArrayList<>();
                                    Class<?> k = c;
                                    while (k != null && k != Object.class) {
                                        for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                                            if (f.getType() == int.class
                                                    && !java.lang.reflect.Modifier.isStatic(
                                                            f.getModifiers())) {
                                                try {
                                                    f.setAccessible(true);
                                                    list.add(f);
                                                } catch (Throwable ignore) {
                                                }
                                            }
                                        }
                                        k = k.getSuperclass();
                                    }
                                    return list.toArray(new java.lang.reflect.Field[0]);
                                });
                        StringBuilder fsb = new StringBuilder();
                        for (java.lang.reflect.Field f : fs) {
                            int v = 0;
                            try {
                                v = f.getInt(b0);
                            } catch (Throwable ignore) {
                            }
                            if (v != 0) {
                                fsb.append(f.getName()).append("=0x")
                                        .append(Integer.toHexString(v)).append(' ');
                            }
                        }
                        probe("key fields host=0x" + Integer.toHexString(hostFill)
                                + " cls=" + b0.getClass().getSimpleName() + " :: " + fsb);
                    } catch (Throwable ignore) {
                    }
                }
                for (int i = 0; i < buttons.size(); i++) {
                    Object b = buttons.get(i);
                    try {
                        String id = String.valueOf(keyIdMethod.invoke(b));
                        if (isSendKeyId(id) || isCornerReturnKey(
                                keyboard.getClass().getSimpleName(), id, keyRectMethod, b)) {
                            continue;
                        }
                        if (isPseudoContainer(id)) {
                            if (fillHitCount.getAndIncrement() < 8) {
                                probe("fill skip pseudo " + id);
                            }
                            continue;
                        }
                        java.lang.reflect.Field f = fillFieldOf(b);
                        if (f == null) {
                            continue;
                        }
                        int cur = f.getInt(b);
                        if (cur == 0 || cur == 0xFF23C891 || cur == 0xFF0086FF) {
                            continue;
                        }
                        fillFields[i] = f;
                        fillOld[i] = cur;
                        f.setInt(b, keyFill[0]);
                        if (fillHitCount.getAndIncrement() < 5) {
                            probe("fill patch " + id + " field=" + f.getName()
                                    + " 0x" + Integer.toHexString(cur)
                                    + " -> 0x" + Integer.toHexString(keyFill[0]));
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }
            // 「按键按下色」：绘制前把按下色字段换成配置色，画完还原；
            // 发送 / 完成键与左下角收起键不动。亮色 / 暗色 / 极暗各读自己主题段里的那份，
            // 主题段里没有 key_press_bg 就完全不接管（keyPressColor=0）。
            java.lang.reflect.Field[] pressFields = null;
            int[] pressOld = null;
            int pressColor = keyPressColor;
            if (pressColor != 0 && !buttons.isEmpty()) {
                pressFields = new java.lang.reflect.Field[buttons.size()];
                pressOld = new int[buttons.size()];
                for (int i = 0; i < buttons.size(); i++) {
                    Object b = buttons.get(i);
                    try {
                        String id = String.valueOf(keyIdMethod.invoke(b));
                        if (isSendKeyId(id) || isCornerReturnKey(
                                keyboard.getClass().getSimpleName(), id, keyRectMethod, b)) {
                            continue;
                        }
                        if (isPseudoContainer(id)) {
                            if (pressHitCount.getAndIncrement() < 8) {
                                probe("press skip pseudo " + id);
                            }
                            continue;
                        }
                        // 表情页删除键不接管按下色（这颗键本来就没有键面）
                        if (isEmojiDeleteKey(id)) {
                            continue;
                        }
                        java.lang.reflect.Field f = pressFieldOf(b);
                        if (f == null) {
                            continue;
                        }
                        int cur = f.getInt(b);
                        // 暗色皮肤下宿主把按下色留成 0（表示「没有按下态」），
                        // 显式配了按下色就照样写进去，0 也能被替换、画完再还原成 0
                        pressFields[i] = f;
                        pressOld[i] = cur;
                        f.setInt(b, pressColor);
                        if (pressHitCount.getAndIncrement() < 5) {
                            probe("press patch " + id + " field=" + f.getName()
                                    + " 0x" + Integer.toHexString(cur)
                                    + " -> 0x" + Integer.toHexString(pressColor));
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }

            try {
                keyDrawWindow = true;
                return chain.proceed();
            } finally {
                keyDrawWindow = false;
                if (cornerFields != null && cornerOld != null) {
                    for (int i = 0; i < buttons.size(); i++) {
                        java.lang.reflect.Field f = cornerFields[i];
                        if (f == null) {
                            continue;
                        }
                        try {
                            f.setInt(buttons.get(i), cornerOld[i]);
                        } catch (Throwable ignore) {
                        }
                    }
                }
                if (fillFields != null && fillOld != null) {
                    for (int i = 0; i < buttons.size(); i++) {
                        java.lang.reflect.Field f = fillFields[i];
                        if (f != null) {
                            try {
                                f.setInt(buttons.get(i), fillOld[i]);
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                }
                if (pressFields != null && pressOld != null) {
                    for (int i = 0; i < buttons.size(); i++) {
                        java.lang.reflect.Field f = pressFields[i];
                        if (f != null) {
                            try {
                                f.setInt(buttons.get(i), pressOld[i]);
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                }
                if (true) {
                    for (int i = 0; i < buttons.size(); i++) {
                        fShadowColor.set(buttons.get(i), colors[i]);
                        fShadowHeight.set(buttons.get(i), heights[i]);
                    }
                }
            }
        });


        // ② 捕获所有按键
        Method onTouch = oCls.getDeclaredMethod("onTouch", View.class, MotionEvent.class);
        Method getActionButton = getActionButtonMethod;
        Method keyId = keyIdMethod;
        Method keyRect = keyRectMethod;
        Field keyMap = mapField;

        install(onTouch, "wetype_dark_key_touch", chain -> {
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            MotionEvent ev = (MotionEvent) chain.getArg(1);
            if (ev == null) {
                return result;
            }
            int action = ev.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) {
                return result;
            }
            try {
                if (action == MotionEvent.ACTION_DOWN && keyMap != null
                        && !keyMapDumped.containsKey(self)) {
                    keyMapDumped.put(self, Boolean.TRUE);
                    dumpKeys(keyMap, self, keyId, keyRect);
                }
                Object button = getActionButton.invoke(self);
                if (button != null) {
                    String id = String.valueOf(keyId.invoke(button));
                    if (!isSendKeyId(id)) {
                        log(Log.DEBUG, TAG, "key " + (action == MotionEvent.ACTION_DOWN ? "down" : "up")
                                + " " + id + " " + keyRect.invoke(button));
                    }
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "key capture failed", t);
            }
            return result;
        });

        try {
            installKeyGradientHook(cl);
        } catch (Throwable t) {
            probe("key grad hook failed: " + t);
        }

        try {
            installCandidateBoxHook(cl);
            probe("cand box hook OK");
        } catch (Throwable t) {
            probe("cand box hook failed: " + t);
        }

        try {
            installFnIconTintHook();
        } catch (Throwable t) {
            probe("fn icon tint hook failed: " + t);
        }

        log(Log.INFO, TAG, "keyboard hooks ready: onTouch + shadow, keyMap=" + keyMap);

        // ③ 按键文字字体：每个绘制器画键前把画笔改成系统字体 + wght 450。
        //    画键的入口是 drawmethod.c#a(Canvas, j)（里面依次 e() 背景 → b() 文字 → f()/g() 高光），
        //    b/e/f/g/i/k/l 都继承 c 且不重写 a；只有 d（空键占位）和 h（符号）自己重写了 a。
        try {
            Class<?> keyCls = Class.forName(KEYBOARD_PKG + ".j", false, cl);
            final Method jIdMethod = keyCls.getDeclaredMethod("K");
            for (String simple : new String[]{"c", "d", "h"}) {
                try {
                    Class<?> drawerCls = Class.forName(KEY_DRAWER_PKG + "." + simple, false, cl);
                    Method drawKey = drawerCls.getDeclaredMethod(
                            "a", android.graphics.Canvas.class, keyCls);
                    install(drawKey, "wetype_dark_key_font_" + simple, chain -> {
                        Object canvasArg = chain.getArg(0);
                        Object btnArg = chain.getArg(1);
                        String keyIdStr = null;
                        try {
                            if (btnArg != null) {
                                keyIdStr = String.valueOf(jIdMethod.invoke(btnArg));
                            }
                        } catch (Throwable ignore) {
                        }
                        // 只给真按键画投影（画在键面之下）；表情页 / 符号页的容器伪键不画，
                        // 否则那些占位容器下面会拖出一条「凭空多出来」的阴影
                        if (canvasArg instanceof android.graphics.Canvas
                                && !isPseudoContainer(keyIdStr)
                                && !isEmojiDeleteKey(keyIdStr)) {
                            drawKeyShadow((android.graphics.Canvas) canvasArg, btnArg);
                        }
                        patchDrawerKeyFont(chain.getThisObject());
                        // 大发送键灰/禁用态：底 → 主题色，字 → 纯白，去掉宿主描边与投影
                        int[] enterGreyOld = applyEnterStyle(btnArg);
                        // 按键文字位置：记下这颗键是九键还是 26 键，并给这块 Canvas 挂 drawText
                        KeyTextHost.INSTANCE.enter((android.graphics.Canvas) canvasArg, keyIdStr);
                        // 表情页删除键：宿主 skin 给的白色键面（bgColor=J）、描边（M）、
                        // 投影（I）在这颗键绘制期间全部清 0，画完还原 → 只剩中间那个删除符号
                        java.lang.reflect.Field delBgF = null;
                        java.lang.reflect.Field delShadowF = null;
                        java.lang.reflect.Field delBorderF = null;
                        int delBgOld = 0;
                        int delShadowOld = 0;
                        int delBorderOld = 0;
                        if (isEmojiDeleteKey(keyIdStr) && btnArg != null) {
                            try {
                                delBgF = intFieldNamed(btnArg, "J");
                                delShadowF = intFieldNamed(btnArg, "I");
                                delBorderF = intFieldNamed(btnArg, "M");
                                if (delBgF != null) {
                                    delBgOld = delBgF.getInt(btnArg);
                                    delBgF.setInt(btnArg, 0);
                                }
                                if (delShadowF != null) {
                                    delShadowOld = delShadowF.getInt(btnArg);
                                    delShadowF.setInt(btnArg, 0);
                                }
                                if (delBorderF != null) {
                                    delBorderOld = delBorderF.getInt(btnArg);
                                    delBorderF.setInt(btnArg, 0);
                                }
                                if (emojiDelHit.getAndIncrement() < 3) {
                                    probe("emoji del clear J=0x"
                                            + Integer.toHexString(delBgOld) + " I=0x"
                                            + Integer.toHexString(delShadowOld) + " M=0x"
                                            + Integer.toHexString(delBorderOld)
                                            + " kb=" + currentKbSimpleName);
                                }
                            } catch (Throwable t) {
                                probe("emoji del clear fail " + t);
                            }
                        }
                        Object result;
                        try {
                            result = chain.proceed();
                        } finally {
                            if (delBgF != null) {
                                try {
                                    delBgF.setInt(btnArg, delBgOld);
                                } catch (Throwable ignore) {
                                }
                            }
                            if (delShadowF != null) {
                                try {
                                    delShadowF.setInt(btnArg, delShadowOld);
                                } catch (Throwable ignore) {
                                }
                            }
                            if (delBorderF != null) {
                                try {
                                    delBorderF.setInt(btnArg, delBorderOld);
                                } catch (Throwable ignore) {
                                }
                            }
                            KeyTextHost.INSTANCE.exit();
                            restoreEnterStyle(btnArg, enterGreyOld);
                        }
                        // 键画完补一圈高光描边（只此入口，因此只命中按键）
                        if (btnArg != null) {
                            if (micIconBitmap != null && "space".equals(keyIdStr)) {
                                // 九键的空格键是「纯图标键」（imgSrc 非 0），数字页那颗写的是
                                // 「空格」两个字、没有任何图标。只给前者补话筒，后者别长出话筒。
                                // 话筒只属于「九键 T9」和「26 键」的那颗空格：
                                // 数字页的空格是上屏空格（键帽写着「空格」），不该长话筒。
                                String kb = currentKbSimpleName;
                                boolean voiceKb = kb != null
                                        && (kb.contains("T9") || kb.contains("Qwerty"));
                                if (voiceKb) {
                                    drawMicIcon((android.graphics.Canvas) canvasArg, btnArg);
                                } else if (spaceProbeSeen.add(kb + "|" + keyIdStr)) {
                                    probe("space skip mic kb=" + kb);
                                }
                            }
                            coverEnterBorder((android.graphics.Canvas) canvasArg, btnArg);
                            if (!isEmojiDeleteKey(keyIdStr)) {
                                drawKeyRim((android.graphics.Canvas) canvasArg, btnArg);
                            }
                            // 下滑功能：把绑定的动作短名压在键帽下方
                            drawKeyGestureLabel((android.graphics.Canvas) canvasArg,
                                    btnArg, keyIdStr);
                        }
                        return result;
                    });
                    probe("key font hook ok: drawmethod." + simple + "#a");
                } catch (Throwable t) {
                    probe("key font hook FAIL drawmethod." + simple + "#a: " + t);
                }
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "key font hooks failed", t);
        }

        // ④ 按键文字字体：宿主所有按键画笔都在构造时
        //    Typeface.createFromAsset(assets, "fonts/WE-Regular.ttf")，一处拦掉即可全覆盖
        //    （主字 / 角标 / 功能键 / 千层饼式的 dotComma、符号页都在里面）。
        try {
            Method createFromAsset = android.graphics.Typeface.class.getDeclaredMethod(
                    "createFromAsset", android.content.res.AssetManager.class, String.class);
            install(createFromAsset, "wetype_dark_key_font", chain -> {
                Object path = chain.getArg(1);
                if (KEY_FONT_ASSET.equals(path) && isKeyFontCaller()) {
                    android.graphics.Typeface tf = systemKeyTypeface();
                    if (tf != null) {
                        if (keyFontLog.getAndIncrement() < 6) {
                            probe("key font asset hit -> sans-serif 400");
                        }
                        return tf;
                    }
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "key font hook installed: sans-serif 400 + stroke");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "key font hook failed", t);
        }

        // ⑤ 字重 +50：宿主每颗键画字前都会走 m1.F2(paint, bold) 定 0.1/0.5 描边，
        //    拦住它，给「按键文字画笔」按字号补 1% 描边（400 → 450 的笔画差）。
        try {
            Class<?> m1Cls = Class.forName(HOST_UTILS_PKG + ".m1", false, cl);
            Method f2 = m1Cls.getDeclaredMethod(
                    "F2", android.graphics.Paint.class, boolean.class);
            install(f2, "wetype_dark_key_text_weight", chain -> {
                chain.proceed();
                Object arg = chain.getArg(0);
                if (arg instanceof android.graphics.Paint) {
                    android.graphics.Paint paint = (android.graphics.Paint) arg;
                    if (isKeyTextPaint(paint)) {
                        float size = paint.getTextSize();
                        float before = paint.getStrokeWidth();
                        paint.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
                        paint.setStrokeWidth(before + size * KEY_WEIGHT_STROKE_RATIO);
                        if (keyWeightLog.getAndIncrement() < 4) {
                            probe("key weight f2 size=" + size + " stroke=" + before
                                    + " -> " + paint.getStrokeWidth());
                        }
                    }
                }
                return null;
            });
            log(Log.INFO, TAG, "key weight hook installed: +" + KEY_WEIGHT_STROKE_RATIO + "em");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "key weight hook failed", t);
        }

        // ⑥ 逐应用模式（深色 / 极暗）时的文字与图标换色：
        //    按键主字走 selfdraw.j#q()，角标走 #w()（即 getFloatTextColor），
        //    图标走 #G() / #I()（即 getIconColor / getIconPressedColor），
        //    候选词文字走 candidate.selfdraw.selfview.s#B0()。
        //    j 里的 getter 在字节码里是单字母名（jadx 只是按属性重命名显示），所以按字母名找。
        //    只在「该应用勾了模式」时生效（keyTextColor / candTextColor 为 0 时一律原样返回）。
        try {
            Class<?> btnCls = Class.forName(KEYBOARD_PKG + ".j", false, cl);
            install(btnCls.getDeclaredMethod("q"), "wetype_dark_key_text_color", chain -> {
                Object baseObj = chain.proceed();
                // 亮/暗都吃网页「字符颜色」；没配才走原来的逐应用文字色
                int eff = keyInk();
                if (eff == 0 || !(baseObj instanceof Integer)) {
                    return baseObj;
                }
                if (isSendButton(chain.getThisObject())) {
                    // 发送键不吃网页「字符颜色」，但灰态 / 禁用态的文字色要在这一帧生效：
                    // 它的字色是走这个 getter 的，光改字段会被这里顶回纯白
                    int[] enterOv = ENTER_TEXT.get();
                    if (enterOv != null && enterOv[0] != 0) {
                        return enterOv[0];
                    }
                    return 0xFFFFFFFF;
                }
                return inkAlpha(eff, (Integer) baseObj);
            });
            // 标点长条 / 123 这类键的墨色走 getMainTextColor（含按下态），一起接管
            for (String nm : new String[]{"s", "t"}) {
                try {
                    installIntGetter(btnCls, new String[]{nm}, "wetype_dark_key_main_color_" + nm);
                } catch (Throwable t) {
                    probe("main text color " + nm + " hook failed: " + t);
                }
            }
            // 键帽右上角角标小字（T9 数字 / 英文 / 标点）单独配色
            try {
                java.lang.reflect.Method floatM = btnCls.getDeclaredMethod("w");
                install(floatM, "wetype_dark_key_float_color", chain -> {
                    Object baseObj = chain.proceed();
                    // 角标小字只吃「小字颜色」；没配就保持宿主原色，绝不跟「字符颜色」
                    if (!keySmallTextSet && keySmallTextColor == 0 || !(baseObj instanceof Integer)) {
                        return baseObj;
                    }
                    return inkAlpha(keepInkAlpha(keySmallTextColor), (Integer) baseObj);
                });
            } catch (Throwable t) {
                probe("float text color hook failed: " + t);
            }
            // 删除键 X、空格话筒这类图标也跟着「字符颜色」，发送键除外
            for (String[] names : new String[][]{{"G", "getIconColor"}, {"I", "getIconPressedColor"}}) {
                final String tag = names[0];
                try {
                    java.lang.reflect.Method im = null;
                    for (String nm : names) {
                        try {
                            java.lang.reflect.Method mm = btnCls.getDeclaredMethod(nm);
                            if (mm.getReturnType() == int.class && mm.getParameterCount() == 0) {
                                im = mm;
                                break;
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                    if (im == null) {
                        probe("icon color getter not found: " + tag);
                    } else {
                        install(im, "wetype_dark_key_icon_color_" + tag, chain -> {
                            Object baseObj = chain.proceed();
                            int eff = keyInk();
                            if (eff == 0 || !(baseObj instanceof Integer)
                                    || isSendButton(chain.getThisObject())) {
                            return baseObj;
                            }
                            // 图标默认按满不透明上色；网页把「字符颜色」调透了，图标跟着一起透
                            return iconAlpha(eff);
                        });
                    }
                } catch (Throwable t) {
                    probe("icon color hook failed: " + t);
                }
            }
            // VoiceDrawer（空格上的麦克风）把图标 tint 写死成纯黑，不吃 getIconColor；
            // 在按键绘制窗口里把纯黑 tint 换成当前文字色即可覆盖这类图标。
            try {
                install(android.graphics.PorterDuffColorFilter.class.getDeclaredConstructor(
                        int.class, android.graphics.PorterDuff.Mode.class),
                        "wetype_dark_key_icon_black_tint", chain -> {
                            Object cObj = chain.getArg(0);
                            // 深色模式下，底栏「收起 / 切换输入法」这类图标被宿主 tint 成近黑色，
                            // 在深色面板上看不出来：键内绘制窗口，或深色模式下，把深灰/黑 tint 换成文字色
                            if (keyInk() != 0 && (keyDrawWindow || nightMode) && cObj instanceof Integer
                                    && isDarkNeutral((Integer) cObj)) {
                                Object[] args = chain.getArgs().toArray();
                                int inkTint = keyInk();
                                args[0] = (inkTint & 0x00FFFFFF) | (inkAlphaOf(inkTint) << 24);
                                return chain.proceed(args);
                            }
                            return chain.proceed();
                        });
            } catch (Throwable t) {
                probe("black tint hook failed: " + t);
            }
            // 注意：这里曾经挂过一个「深色模式兜底」的 Paint#setColor 全局钩子，
            // 把任何「深灰/近黑」的绘制色都换成字符颜色。它在深色模式下会把
            // 键面底色（#2C2C2C 这类深灰）也一起换成白色，
            // 结果就是「白键 + 白字 + 一圈白晕」，亮也不是暗也不是。
            // 底栏那两颗图标已经由 KeyButtonDrawable#draw 的 mIconPaint 精确接管，
            // 不需要这种全域改写，已移除。
                // Drawable.setTint / setTintList 路径：底栏图标如果是给 drawable 设深色 tint，
            // 深色模式下换成当前文字色
            try {
                install(android.graphics.drawable.Drawable.class.getDeclaredMethod("setTint", int.class),
                        "wetype_dark_drawable_tint", chain -> {
                            Object cObj = chain.getArg(0);
                            int ink = keyInk();
                            // 只接管底栏那两颗 KeyButtonDrawable，别把键面/候选条的深色 tint 也刷成白色
                            if (nightMode && ink != 0 && cObj instanceof Integer
                                    && isNavKeyDrawable(chain.getThisObject())
                                    && isDarkNeutral((Integer) cObj)) {
                                return chain.proceed(new Object[]{ink});
                            }
                            return chain.proceed();
                        });
                probe("drawable tint hook ok");
            } catch (Throwable t) {
                probe("drawable tint hook failed: " + t);
            }
            try {
                install(android.graphics.drawable.Drawable.class.getDeclaredMethod(
                                "setTintList", android.content.res.ColorStateList.class),
                        "wetype_dark_drawable_tint_list", chain -> {
                            Object csObj = chain.getArg(0);
                            int ink = keyInk();
                            if (nightMode && ink != 0
                                    && isNavKeyDrawable(chain.getThisObject())
                                    && csObj instanceof android.content.res.ColorStateList) {
                                android.content.res.ColorStateList cs =
                                        (android.content.res.ColorStateList) csObj;
                                int base = cs.getDefaultColor();
                                if (isDarkNeutral(base)) {
                                    return chain.proceed(new Object[]{
                                            android.content.res.ColorStateList.valueOf(ink)});
                                }
                            }
                            return chain.proceed();
                        });
                probe("drawable tint list hook ok");
            } catch (Throwable t) {
                probe("drawable tint list hook failed: " + t);
            }
            probe("key text/icon color hooks ok");

            // 空格键上的话筒：VoiceDrawer（drawmethod.l）内部有张自绘图标，
            // 用户上传了自己的话筒图就把它顶掉（i(Drawable,Canvas,Rect) 只画这一颗）
            installMicIconHook(cl);
            installBubbleTextHook();
        } catch (Throwable t) {
            probe("key text/icon color hooks failed: " + t);
        }
        try {
            Class<?> candCls = Class.forName(
                    "com.tencent.wetype.plugin.hld.candidate.selfdraw.selfview.s", false, cl);
            install(candCls.getDeclaredMethod("B0"), "wetype_dark_cand_text_color", chain -> {
                Object baseObj = chain.proceed();
                if (candTextColor == 0 || !(baseObj instanceof Integer)) {
                    return baseObj;
                }
                return withAlpha(candTextColor, (Integer) baseObj);
            });
            probe("cand text color hook ok");
        } catch (Throwable t) {
            probe("cand text color hook failed: " + t);
        }

    }

    /** 只认底栏那两颗系统 KeyButtonDrawable，其它 drawable 一律放行 */
    private static boolean isNavKeyDrawable(Object o) {
        return o instanceof Drawable
                && o.getClass().getName().contains("KeyButtonDrawable");
    }

    /** 判断一个颜色是不是「深灰/黑」这类在深色背景上会消失的中性色 */
    private static boolean isDarkNeutral(int c) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max <= 0x6E && (max - min) <= 0x1E;
    }

    /** 发送 / 完成键不参与文字换色（绿底白字是它自己的配色） */
    private boolean isSendButton(Object button) {
        if (button == null) {
            return false;
        }
        try {
            Class<?> cls = button.getClass();
            java.lang.reflect.Method idM = keyRimIdMethods.get(cls);
            if (idM == null) {
                idM = cls.getDeclaredMethod("K");
                idM.setAccessible(true);
                keyRimIdMethods.put(cls, idM);
            }
            return isSendKeyId(String.valueOf(idM.invoke(button)));
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 换色时保留宿主原来的 alpha（角标、按下态都是靠 alpha 分层的） */
    private static int withAlpha(int color, int base) {
        int alpha = (base >>> 24) & 0xFF;
        if (alpha == 0) {
            return base;
        }
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /**
     * 文字墨色落笔：网页给的透明度优先，网页没给（alpha＝255）才让宿主的 alpha 分层生效。
     * 之前一律走 withAlpha（保留宿主 alpha），宿主墨色都是 FF，所以网页把
     * 「字符颜色」拉到任意透明度都看不出变化。
     */
    private static int inkAlpha(int color, int base) {
        int hostA = (base >>> 24) & 0xFF;
        int inkA = (color >>> 24) & 0xFF;
        int a = inkA == 0xFF ? hostA : inkA;
        if (hostA == 0) {
            a = 0;                     // 宿主本来就是透明的一层，别把它点亮
        }
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** 0% 透明度存成 1/255：既看不出墨色，又不会被「颜色＝0」当成未配置 */
    private static int keepInkAlpha(int color) {
        if (color == 0) {
            return 0;
        }
        return ((color >>> 24) & 0xFF) == 0 ? ((color & 0x00FFFFFF) | 0x01000000) : color;
    }

    /** 话筒这类「跟字符颜色」的图标：透明度照抄「字符颜色」，0% 留 1/255 免得被当成透明层 */
    private static int inkAlphaOf(int ink) {
        int a = (ink >>> 24) & 0xFF;
        return a == 0 ? 1 : a;
    }

    /** 图标类墨色：网页给了透明度就用网页的，没给就钉满（宿主会给话筒这类设半透明） */
    private static int iconAlpha(int color) {
        int inkA = (color >>> 24) & 0xFF;
        return (color & 0x00FFFFFF) | ((inkA == 0xFF ? 0xFF : (inkA == 0 ? 1 : inkA)) << 24);
    }

    /** 按候选名找「无参返回 int」的 getter 并挂钩换色；找不到只记一条探针，不影响其它 hook */
    /**
     * 自定义话筒图标。
     *
     * 空格键那颗话筒由 VoiceDrawer = drawmethod.l 画：按 button 的 imgSrc 取图、
     * 算好 iconRect 后走私有 i(Drawable,Canvas,Rect) 落笔。只拦这一个方法：
     * 有用户上传的图就在这个 rect 里画掉它，没有就放行原生逻辑。
     */
    /** 空格键上话筒图的绘制：按「键高 × 比例」等比塞进键的中部 */
    private void drawMicIcon(android.graphics.Canvas canvas, Object button) {
        try {
            android.graphics.Rect rect = keyDrawRect(button);
            android.graphics.Bitmap bmp = micIconBitmap;
            if (rect == null || rect.width() <= 0 || rect.height() <= 0 || bmp == null) {
                return;
            }
            float h = rect.height() * 0.40f;
            float w = h * bmp.getWidth() / (float) bmp.getHeight();
            float cx = rect.centerX();
            float cy = rect.centerY();
            android.graphics.RectF dst = new android.graphics.RectF(
                    cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            if (micIconPaint == null) {
                micIconPaint = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                                | android.graphics.Paint.FILTER_BITMAP_FLAG);
            }
            // 话筒图按「字符颜色」满墨实色：RGB 钉成墨色，alpha 拉满
            int ink = keyInk();
            if (ink != 0) {
                float[] mat = new float[]{
                        0, 0, 0, 0, android.graphics.Color.red(ink),
                        0, 0, 0, 0, android.graphics.Color.green(ink),
                        0, 0, 0, 0, android.graphics.Color.blue(ink),
                        0, 0, 0, 1.0f, 0};
                micIconPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(mat));
                micIconPaint.setAlpha(inkAlphaOf(ink));
            }
            canvas.drawBitmap(bmp, null, dst, micIconPaint);
        } catch (Throwable ignore) {
        }
    }

    /** 按键在 Canvas 上的绘制矩形（宿主 j#t()，缓存在类上） */
    private android.graphics.Rect keyDrawRect(Object button) {
        if (button == null) {
            return null;
        }
        try {
            Class<?> cls = button.getClass();
            java.lang.reflect.Method m = keyRectMethods.get(cls);
            if (m == null) {
                m = cls.getDeclaredMethod("t");
                m.setAccessible(true);
                keyRectMethods.put(cls, m);
            }
            Object r = m.invoke(button);
            return r instanceof android.graphics.Rect ? (android.graphics.Rect) r : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** 当前想要的宿主皮肤：1 亮色 / 2 暗色（和宿主 ImeSkinManager 的取值一致） */
    private void syncHostSkin(View panel) {
        try {
            boolean dark = "dark".equals(themeForMode(activeAppMode))
                    || "ultra".equals(themeForMode(activeAppMode));
            int want = dark ? 2 : 1;
            Object mgr = hostSkinManager(panel.getClass().getClassLoader());
            if (mgr == null) {
                return;
            }
            // 不再用「上次设置成功的值」当缓存短路：
            // 系统切换深浅色时宿主会自己把皮肤刷回默认，缓存里那个值会把它挡住，
            // 于是我们只改了面板色、原生部件（工具栏/候选条）还是亮皮肤 —— 看着就是「混着来」。
            int cur = (Integer) hostSkinCurrent.invoke(mgr);
            if (cur == want) {
                hostSkinApplied = want;
                return;
            }
            hostSkinSet.invoke(mgr, want);
            hostSkinApplied = want;
            probe("host skin -> " + want + " (was " + cur + ") mode=" + activeAppMode
                    + " theme=" + themeForMode(activeAppMode) + " night=" + nightMode);
        } catch (Throwable t) {
            probe("host skin failed: " + t);
        }
    }

    /** 宿主的 ImeSkinManager（skin.e 单例）里的皮肤管理器；类还没加载就返回 null，下次再试 */
    private Object hostSkinManager(ClassLoader cl) throws Exception {
        if (hostSkinGetManager == null) {
            Class<?> skinCls = Class.forName(
                    "com.tencent.wetype.plugin.hld.skin.e", false, cl);
            java.lang.reflect.Method m = skinCls.getDeclaredMethod("c");
            m.setAccessible(true);
            hostSkinGetManager = m;
        }
        Object mgr = hostSkinGetManager.invoke(null);
        if (mgr == null) {
            return null;
        }
        if (hostSkinCurrent == null) {
            hostSkinCurrent = mgr.getClass().getDeclaredMethod("s");
            hostSkinCurrent.setAccessible(true);
        }
        if (hostSkinSet == null) {
            hostSkinSet = mgr.getClass().getDeclaredMethod("j", int.class);
            hostSkinSet.setAccessible(true);
        }
        return mgr;
    }

    /** 按键 id（宿主自绘按键用 K() 取） */
    private String keyIdOf(Object button) {
        if (button == null) {
            return "";
        }
        try {
            Class<?> cls = button.getClass();
            java.lang.reflect.Method idM = keyRimIdMethods.get(cls);
            if (idM == null) {
                idM = cls.getDeclaredMethod("K");
                idM.setAccessible(true);
                keyRimIdMethods.put(cls, idM);
            }
            return String.valueOf(idM.invoke(button));
        } catch (Throwable ignore) {
            return "";
        }
    }

    /**
     * 长按字母气泡：宿主在 selfdraw.o#onDraw 里直接画（不是按键绘制器那一套），
     * 气泡的裁剪框按原生 WE 字体的字形宽度算死。我们换成系统字体又加了字重描边，
     * 字形两侧外扩，最左/最右那点像素就出框被切。这里在气泡那次 drawText 前
     * 把画笔 textScaleX 压到 0.9，横向收窄到裁剪框里，键帽文字不受影响。
     */
    private final java.util.Set<String> bubbleCallerSeen =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private void installBubbleTextHook() {
        String[] sigs = {"string", "cs"};
        for (String sig : sigs) {
            try {
                java.lang.reflect.Method target = "string".equals(sig)
                        ? android.graphics.Canvas.class.getDeclaredMethod("drawText",
                        String.class, float.class, float.class, android.graphics.Paint.class)
                        : android.graphics.Canvas.class.getDeclaredMethod("drawText",
                        CharSequence.class, int.class, int.class, float.class, float.class,
                        android.graphics.Paint.class);
                install(target, "wetype_dark_bubble_text_" + sig, chain -> {
                    Object pObj = "string".equals(sig) ? chain.getArg(3) : chain.getArg(5);
                    if (pObj instanceof android.graphics.Paint && keyDrawWindow) {
                        android.graphics.Paint paint = (android.graphics.Paint) pObj;
                        // 只有大水号的字才可能是气泡那个字母，别的文字连栈都不查
                        if (paint.getTextSize() >= 36f && isBubbleCaller()) {
                            float old = paint.getTextScaleX();
                            paint.setTextScaleX(BUBBLE_TEXT_SCALE_X);
                            try {
                                return chain.proceed();
                            } finally {
                                paint.setTextScaleX(old);
                            }
                        }
                    }
                    return chain.proceed();
                });
                probe("bubble text hook ok: " + sig);
            } catch (Throwable t) {
                probe("bubble text hook failed " + sig + ": " + t);
            }
        }
    }

    /** 气泡文字是宿主 selfdraw.o#onDraw 直接画的；键帽文字走 drawmethod.*，不会命中 */
    private boolean isBubbleCaller() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (StackTraceElement el : st) {
                String cn = el.getClassName();
                if (cn.startsWith("com.tony.wetypedark") || cn.startsWith("android.")
                        || cn.startsWith("java.") || cn.startsWith("de.robv")
                        || cn.startsWith("com.tencent.wetype.plugin.hld.keyboard.selfdraw.drawmethod")) {
                    continue;
                }
                boolean hit = cn.endsWith(".keyboard.selfdraw.o") && "onDraw".equals(el.getMethodName());
                String key = cn + "#" + el.getMethodName();
                if (bubbleCallerSeen.size() < 12 && bubbleCallerSeen.add(key)) {
                    probe("bubble text caller=" + key + " hit=" + hit);
                }
                return hit;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private void installMicIconHook(ClassLoader cl) {
        try {
            Class<?> voiceCls = Class.forName(
                    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.drawmethod.l", false, cl);
            // 按键上的位图图标（话筒等）：宿主用半透明 Paint 落笔，这里在按键绘制窗口里补成满墨
            try {
                install(android.graphics.Canvas.class.getDeclaredMethod("drawBitmap",
                                android.graphics.Bitmap.class,
                                android.graphics.Rect.class,
                                android.graphics.RectF.class,
                                android.graphics.Paint.class),
                        "wetype_dark_bitmap_ink", chain -> {
                            int ink = keyInk();
                            Object pObj = chain.getArg(3);
                            if (ink != 0 && keyDrawWindow
                                    && pObj instanceof android.graphics.Paint) {
                                android.graphics.Paint paint = (android.graphics.Paint) pObj;
                                int want = inkAlphaOf(ink);
                                if (paint.getAlpha() != want) {
                                    paint.setAlpha(want);
                                }
                            }
                            return chain.proceed();
                        });
                probe("canvas bitmap ink hook ok");
            } catch (Throwable t) {
                probe("canvas bitmap ink hook failed: " + t);
            }
            // 空格话筒这类「切换图」宿主会 Drawable.setAlpha(63) 做成半透明，键内绘制一律拉满
            try {
                install(android.graphics.drawable.Drawable.class.getDeclaredMethod(
                                "setAlpha", int.class),
                        "wetype_dark_key_drawable_alpha", chain -> {
                            int inkNow = keyInk();
                            if (inkNow != 0 && keyDrawWindow) {
                                return chain.proceed(new Object[]{inkAlphaOf(inkNow)});
                            }
                            return chain.proceed();
                        });
                probe("key drawable alpha hook ok");
            } catch (Throwable t) {
                probe("key drawable alpha hook failed: " + t);
            }
            // 话筒 drawable 若自带半透明像素，这里把 RGB 钉成墨色、alpha 拉满
            try {
                install(voiceCls.getDeclaredMethod("i",
                                android.graphics.drawable.Drawable.class,
                                android.graphics.Canvas.class,
                                android.graphics.Rect.class),
                        "wetype_dark_mic_alpha", chain -> {
                            Object dObj = chain.getArg(0);
                            int ink = keyInk();
                            if (!(dObj instanceof android.graphics.drawable.Drawable) || ink == 0) {
                                return chain.proceed();
                            }
                            android.graphics.drawable.Drawable d =
                                    (android.graphics.drawable.Drawable) dObj;
                            d.mutate();
                            d.setAlpha(inkAlphaOf(ink));
                            float[] mat = new float[]{
                                    0, 0, 0, 0, android.graphics.Color.red(ink),
                                    0, 0, 0, 0, android.graphics.Color.green(ink),
                                    0, 0, 0, 0, android.graphics.Color.blue(ink),
                                    0, 0, 0, 1.0f, 0};
                            d.setColorFilter(new android.graphics.ColorMatrixColorFilter(mat));
                            return chain.proceed();
                        });
                probe("mic alpha hook installed");
            } catch (Throwable t) {
                probe("mic alpha hook failed: " + t);
            }
            install(voiceCls.getDeclaredMethod("i",
                            android.graphics.drawable.Drawable.class,
                            android.graphics.Canvas.class,
                            android.graphics.Rect.class),
                    "wetype_dark_mic_icon", chain -> {
                        android.graphics.Bitmap bmp = micIconBitmap;
                        if (bmp == null) {
                            return chain.proceed();
                        }
                        Object cObj = chain.getArg(1);
                        Object rObj = chain.getArg(2);
                        if (!(cObj instanceof android.graphics.Canvas)
                                || !(rObj instanceof android.graphics.Rect)) {
                            return chain.proceed();
                        }
                        android.graphics.Rect rect = (android.graphics.Rect) rObj;
                        if (rect.width() <= 0 || rect.height() <= 0) {
                            return chain.proceed();
                        }
                        android.graphics.Canvas canvas = (android.graphics.Canvas) cObj;
                        float scale = Math.min(rect.width() / (float) bmp.getWidth(),
                                rect.height() / (float) bmp.getHeight());
                        float w = bmp.getWidth() * scale;
                        float h = bmp.getHeight() * scale;
                        android.graphics.RectF dst = new android.graphics.RectF(
                                rect.centerX() - w / 2f, rect.centerY() - h / 2f,
                                rect.centerX() + w / 2f, rect.centerY() + h / 2f);
                        if (micIconPaint == null) {
                            micIconPaint = new android.graphics.Paint(
                                    android.graphics.Paint.ANTI_ALIAS_FLAG
                                            | android.graphics.Paint.FILTER_BITMAP_FLAG);
                        }
                        // 自绘话筒图一律按「字符颜色」满不透明染色（上传的图自带灰度/透明会显得发虚）
                        int ink = keyInk();
                        if (ink != 0) {
                            micIconPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(
                                    (ink & 0x00FFFFFF) | 0xFF000000,
                                    android.graphics.PorterDuff.Mode.SRC_IN));
                            micIconPaint.setAlpha(inkAlphaOf(ink));
                        } else {
                            micIconPaint.setColorFilter(null);
                        }
                        canvas.drawBitmap(bmp, null, dst, micIconPaint);
                        return null;
                    });
            installedMicHook = true;
            Class<?> btnClsForMic = Class.forName(
                    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.j", false, cl);
            try {
                Class<?> imgCls = Class.forName(
                        "com.tencent.wetype.plugin.hld.keyboard.selfdraw.drawmethod.g", false, cl);
                install(imgCls.getDeclaredMethod("j", btnClsForMic),
                        "wetype_dark_mic_hide", chain -> {
                            if (micIconBitmap != null
                                    && "space".equals(keyIdOf(chain.getArg(0)))) {
                                return null;
                            }
                            return chain.proceed();
                        });
                probe("mic hide hook installed");
            } catch (Throwable t) {
                probe("mic hide hook failed: " + t);
            }
            probe("mic icon hook installed");
        } catch (Throwable t) {
            probe("mic icon hook failed: " + t);
        }
    }

    private void installIntGetter(Class<?> cls, String[] names, String id) {
        java.lang.reflect.Method target = null;
        for (String name : names) {
            try {
                java.lang.reflect.Method m = cls.getDeclaredMethod(name);
                if (m.getReturnType() == int.class && m.getParameterCount() == 0) {
                    target = m;
                    break;
                }
            } catch (Throwable ignore) {
            }
        }
        if (target == null) {
            probe(id + " not found on " + cls.getSimpleName());
            return;
        }
        install(target, id, chain -> {
            Object baseObj = chain.proceed();
            int eff = keyInk();
            if (eff == 0 || !(baseObj instanceof Integer)) {
                return baseObj;
            }
            int base = (Integer) baseObj;
            return base == 0 ? baseObj : inkAlpha(eff, base);
        });
    }


    /**
     * 键盘的类不在 app 默认 ClassLoader 里（plugin.hld 是插件式加载），
     * 必须从真实的键盘 View 上取它自己的 ClassLoader，否则 hook 会挂到一份用不到的副本上。
     */
    private void ensureKeyboardHooks(View panel) {
        if (keyboardHooked || panel == null) {
            return;
        }
        View kb = findKeyboardView(panel, 0);
        if (kb == null) {
            return;
        }
        keyboardHooked = true;
        ClassLoader cl = kb.getClass().getClassLoader();
        probe("keyboard view=" + kb.getClass().getName() + " loader=" + cl);
        try {
            installKeyboardHooks(cl);
            installHostExtras(cl);
            installKeyboardAttachHook();
            probe("keyboard hooks OK");
        } catch (Throwable t) {
            probe("keyboard hooks failed: " + t);
            keyboardHooked = false;
        }
    }

    /**
     * 切页时宿主会 new 一个新键盘页（9键/26键/符号/数字/中英各一套），
     * 这一页在 inflate 时就带着宿主自己的皮肤底色；面板的 apply 又不会被切页触发，
     * 于是第一帧画出来的是宿主原生色，之后才追上我们的调色 —— 表现就是切页闪一下。
     * 这里在「页 attach、还没画第一帧」的时机清掉它自己的底、并把面板那几层重新让位。
     */
    private void installKeyboardAttachHook() {
        try {
            java.lang.reflect.Method m = android.view.View.class
                    .getDeclaredMethod("onAttachedToWindow");
            install(m, "wetype_dark_kb_attach", chain -> {
                Object r = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof View) {
                    cleanKeyboardPageOnAttach((View) self);
                }
                return r;
            });
            probe("kb attach hook ok");
        } catch (Throwable t) {
            probe("kb attach hook failed " + t);
        }
    }

    private void cleanKeyboardPageOnAttach(View v) {
        if (v == null) {
            return;
        }
        String cls = v.getClass().getName();
        if (!cls.startsWith(KEYBOARD_PKG + ".")) {
            return;
        }
        String sn = v.getClass().getSimpleName();
        if (!sn.endsWith("Keyboard") && !sn.endsWith("KeyboardView")) {
            return;
        }
        View panel = lastPanelRef == null ? null : lastPanelRef.get();
        if (panel == null) {
            return;
        }
        try {
            Drawable bg = v.getBackground();
            if (bg != null) {
                LAYER_BG_ALLOW.set(true);
                v.setBackground(null);
            }
            float density = v.getResources().getDisplayMetrics().density;
            int screenWidth = v.getResources().getDisplayMetrics().widthPixels;
            boolean fullWidth = panel.getWidth() >= screenWidth - Math.round(2f * density);
            releaseBackgroundLayers(panel, fullWidth, !fullWidth);
            probe("kb attach clean " + sn + " w=" + v.getWidth());
        } catch (Throwable ignore) {
        } finally {
            if (LAYER_BG_ALLOW.get() != null) {
                LAYER_BG_ALLOW.remove();
            }
        }
    }

    private View findKeyboardView(View v, int depth) {
        if (v == null || depth > 8) {
            return null;
        }
        Class<?> c = v.getClass();
        while (c != null) {
            if ((KEYBOARD_PKG + ".o").equals(c.getName())) {
                return v;
            }
            c = c.getSuperclass();
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findKeyboardView(g.getChildAt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 诊断：把 IME 面板的视图树写进 probe（含类名/尺寸，定位候选栏那一排） */
    private int walk(View v, int depth, StringBuilder sb, int count) {
        if (v == null || depth > 12 || count > 500) {
            return count;
        }
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            pad.append(' ');
        }
        sb.append(pad).append(v.getClass().getName())
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                .append(v.getVisibility() == View.VISIBLE ? "" : " hidden")
                .append(" @").append(v.getLeft()).append(',').append(v.getTop());
        try {
            if (v.getId() != View.NO_ID) {
                sb.append(" id=").append(v.getResources().getResourceEntryName(v.getId()));
            }
        } catch (Throwable ignore) {
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null) {
            sb.append(" desc=").append(cd);
        }
        if (v instanceof android.widget.TextView) {
            CharSequence tx = ((android.widget.TextView) v).getText();
            if (tx != null && tx.length() > 0) {
                sb.append(" text=").append(tx);
            }
        }
        if (v.getBackground() != null) {
            sb.append(" bg=").append(v.getBackground().getClass().getSimpleName());
        }
        sb.append('\n');
        count++;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                count = walk(g.getChildAt(i), depth + 1, sb, count);
            }
        }
        return count;
    }

    /**
     * 捕捉候选栏右侧那一排功能键（AI 表情 / 剪贴板 / 收起）。
     * 范围严格限定在 ImeCandidateView 自己的横向 RecyclerView：
     * 该 RV 宽 >800 且带 icon item（宽 100~200、内含 ConstraintLayout）。
     * 二级页面（符号、表情、数字等）的功能键在别的容器里，不会被匹配到。
     */
    private android.view.ViewGroup findFnRv(View panel) {
        View candidate = findByClassSuffix(panel, ".candidate.ImeCandidateView", 0);
        if (!(candidate instanceof android.view.ViewGroup)) {
            return null;
        }
        android.view.ViewGroup cv = (android.view.ViewGroup) candidate;
        android.view.ViewGroup rv = null;
        for (int i = 0; i < cv.getChildCount(); i++) {
            View c = cv.getChildAt(i);
            if (!(c instanceof android.view.ViewGroup)) {
                continue;
            }
            if (!"androidx.recyclerview.widget.RecyclerView".equals(c.getClass().getName())) {
                continue;
            }
            if (c.getWidth() < 800 || ((android.view.ViewGroup) c).getChildCount() == 0) {
                continue;
            }
            rv = (android.view.ViewGroup) c;
        }
        return rv;
    }

    /**
     * 底栏两颗图标：
     *   android:id/input_method_nav_back          收起输入法 ⌄
     *   android:id/input_method_nav_ime_switcher   切换输入法 ⌨
     * 都是输入法窗口里的 KeyButtonView(ImageView)，154×154 贴在屏幕最底。
     *
     * 原生图标照样画、照样点，只把「画它的那支笔」抢过来改：
     * 它们由私有类 KeyButtonDrawable 绘制，颜色只由内部 `mIconPaint` 决定 ——
     * `colorFilter` 才是真正生效的那个（明暗过渡动画每帧把它从 #89E3E3E3 插值到
     * #89B2B2B2 之类，深色面板上最后落在近黑），`alpha` 又常被压到 60~90%。
     * 所以直接改 drawable 的 tint / alpha / setColorFilter 都会被下一帧覆盖，
     * 必须在它 `draw()` 之前把 mIconPaint 的 colorFilter / color / alpha 写死。
     */
    private static final String NAV_ID_BACK = "input_method_nav_back";
    private static final String NAV_ID_SWITCH = "input_method_nav_ime_switcher";

    private final Map<android.graphics.drawable.Drawable, Boolean> navDrawables =
            new java.util.WeakHashMap<>();
    private final Map<android.graphics.drawable.Drawable, android.graphics.Paint> navPaints =
            new java.util.WeakHashMap<>();
    private boolean navPaintHookInstalled;
    /**
     * 当前平台是不是 HyperOS。HyperOS 下底栏按钮的取舍（收起 / 切换输入法 / 剪贴板）
     * 完全交给系统那套，我们只负责给图标染色，不动背景、不动 alpha、不动显隐。
     */
    private static volatile boolean navHyperOs = false;

    private void applyNavigationBarIcons(View panel) {
        if (panel == null) {
            return;
        }
        try {
            View root = panel.getRootView();
            if (root == null) {
                return;
            }
            navHyperOs = HostConfig.INSTANCE.isHyperOs();
            android.widget.ImageView back = findNavIconView(root, NAV_ID_BACK);
            android.widget.ImageView switcher = findNavIconView(root, NAV_ID_SWITCH);
            // HyperOS 的底栏按钮集合与 ColorOS 不同（可能有剪贴板、也可能不显示切换键），
            // 不按固定 id 写死，凡是底栏里 id 以 input_method_nav_ 开头的图标都收进来。
            java.util.List<android.widget.ImageView> extra =
                    new java.util.ArrayList<>(4);
            collectNavIcons(root, extra, 0);
            String scanSig = (back == null ? "-" : System.identityHashCode(back) + "")
                    + "|" + (switcher == null ? "-" : System.identityHashCode(switcher) + "")
                    + "|" + extra.size()
                    + "|" + (navHyperOs ? "H" : "C")
                    + "|" + (panelColor == 0 ? "0" : Integer.toHexString(panelColor))
                    + "|" + Integer.toHexString(navInkColor());
            if (!scanSig.equals(lastNavScanSig)) {
                lastNavScanSig = scanSig;
                probe("nav scan back=" + (back == null ? "null" : back.getClass().getSimpleName())
                        + " switch=" + (switcher == null ? "null" : switcher.getClass().getSimpleName())
                        + " extra=" + extra.size()
                        + " platform=" + (navHyperOs ? "HyperOS" : "ColorOS")
                        + " panel=#" + (panelColor == 0 ? "0" : Integer.toHexString(panelColor))
                        + " ink=#" + Integer.toHexString(navInkColor())
                        + " lum=" + panelLuminance());
                dumpNavCandidates(root);
            }
            registerNavIcon(back);
            registerNavIcon(switcher);
            for (android.widget.ImageView iv : extra) {
                registerNavIcon(iv);
            }
            if (back != null || switcher != null || !extra.isEmpty()) {
                installNavPaintHook();
            }
        } catch (Throwable t) {
            probe("nav icons failed: " + t);
        }
    }

    /** 收集底栏里 id 以 input_method_nav_ 开头的图标（HyperOS 可能多出剪贴板等按钮） */
    private void collectNavIcons(View v, java.util.List<android.widget.ImageView> out, int depth) {
        if (v == null || depth > 8 || out.size() >= 8) {
            return;
        }
        if (v instanceof android.widget.ImageView) {
            String name = "";
            try {
                int id = v.getId();
                if (id != View.NO_ID) {
                    name = v.getResources().getResourceEntryName(id);
                }
            } catch (Throwable ignore) {
            }
            // 只认底栏自己那几个按钮，别把候选栏、键盘上的图标一起染了
            if (name.startsWith("input_method_nav_")
                    && !NAV_ID_BACK.equals(name)
                    && !NAV_ID_SWITCH.equals(name)
                    && !name.contains("home_handle")) {
                out.add((android.widget.ImageView) v);
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectNavIcons(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    private volatile String appliedThemeId = null;
    /** 主题刚切换：这时候窗口还在，宿主不会自己重画，得我们主动推一把 */
    private volatile boolean themeJustChanged = false;

    private volatile String lastNavScanSig = "";
    private java.util.WeakHashMap<android.view.View, Boolean> navDumped =
            new java.util.WeakHashMap<>();

    /** 诊断：把 IME 窗口里「大块 ImageView」都列出来，看底栏那两颗到底是什么控件 */
    private void dumpNavCandidates(View root) {
        if (root == null || navDumped.put(root, Boolean.TRUE) != null) {
            return;
        }
        StringBuilder sb = new StringBuilder("nav tree:");
        try {
            walkNav(root, sb, 0);
        } catch (Throwable t) {
            sb.append(" err=").append(t.getClass().getSimpleName());
        }
        probe(sb.toString());
    }

    private void walkNav(View v, StringBuilder sb, int depth) {
        if (v == null || depth > 8 || sb.length() > 900) {
            return;
        }
        if (v instanceof android.widget.ImageView
                && v.getWidth() >= 60 && v.getHeight() >= 60) {
            String id = "-";
            try {
                int vid = v.getId();
                id = vid == 0 ? "-" : v.getResources().getResourceEntryName(vid);
            } catch (Throwable ignore) {
            }
            Drawable d = ((android.widget.ImageView) v).getDrawable();
            sb.append(" | ").append(id)
                    .append(' ').append(v.getClass().getSimpleName())
                    .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                    .append(" y=").append(v.getTop())
                    .append(" a=").append(v.getAlpha())
                    .append(" bg=").append(d == null ? "-" : d.getClass().getSimpleName());
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walkNav(g.getChildAt(i), sb, depth + 1);
            }
        }
    }

    private android.widget.ImageView findNavIconView(android.view.View v, String name) {
        try {
            int id = v.getResources().getIdentifier(name, "id", "android");
            if (id == 0) {
                return null;
            }
            View r = v.findViewById(id);
            return r instanceof android.widget.ImageView ? (android.widget.ImageView) r : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private void registerNavIcon(android.widget.ImageView iv) {
        if (iv == null) {
            return;
        }
        try {
            // HyperOS 的底栏按钮由系统决定显隐（只有一个输入法时切输入法键本来就是淡出的），
            // 强行拉满会让本该隐藏的按钮冒出来，这里只染色、不碰 alpha。
            if (!navHyperOs && iv.getAlpha() != 1f) {
                iv.setAlpha(1f);
            }
            android.graphics.drawable.Drawable d = iv.getDrawable();
            if (d == null) {
                d = iv.getBackground();
            }
            if (d != null) {
                navDrawables.put(d, Boolean.TRUE);
            }
        } catch (Throwable ignore) {
        }
    }

    private void installNavPaintHook() {
        if (navPaintHookInstalled) {
            return;
        }
        try {
            Class<?> cls = Class.forName("android.inputmethodservice.navigationbar.KeyButtonDrawable");
            Method draw = cls.getDeclaredMethod("draw", android.graphics.Canvas.class);
            navPaintHookInstalled = true;
            install(draw, "wetype_dark_navpaint", chain -> {
                Object self = chain.getThisObject();
                try {
                    if (self instanceof android.graphics.drawable.Drawable
                            && navDrawables.containsKey(self)) {
                        forceNavIconPaint((android.graphics.drawable.Drawable) self);
                    }
                } catch (Throwable ignore) {
                }
                try {
                    return chain.proceed();
                } catch (Throwable t) {
                    // 宿主这个 drawable 内部位图可能是 null，画到一半 NPE 会把输入法打死
                    return null;
                }
            });
            probe("nav paint hook installed on " + cls.getName());
        } catch (Throwable t) {
            probe("nav paint hook failed: " + t);
        }
    }

    /** 把 mIconPaint 的 colorFilter / color / alpha 钉成「字符颜色」 */
    private void forceNavIconPaint(android.graphics.drawable.Drawable dr) {
        int ink = navInkColor();
        if (ink == 0) {
            return;
        }
        android.graphics.Paint pt = navPaints.get(dr);
        if (pt == null) {
            try {
                Field f = dr.getClass().getDeclaredField("mIconPaint");
                f.setAccessible(true);
                pt = (android.graphics.Paint) f.get(dr);
            } catch (Throwable ignore) {
                pt = null;
            }
            if (pt == null) {
                return;
            }
            navPaints.put(dr, pt);
        }
        try {
            // 图标本体是白色位图/矢量，`paint.color` 对 drawBitmap 无效；
            // 真正能改色的是 colorFilter：SRC_IN 把白色整体换成我们的墨色。
            android.graphics.PorterDuffColorFilter want =
                    new android.graphics.PorterDuffColorFilter(
                            ink, android.graphics.PorterDuff.Mode.SRC_IN);
            android.graphics.ColorFilter cur = pt.getColorFilter();
            boolean same = cur instanceof android.graphics.PorterDuffColorFilter
                    && filtColorOf(cur) == ink;
            if (!same) {
                pt.setColorFilter(want);
                if (!navPaintAppliedLogged) {
                    navPaintAppliedLogged = true;
                    probe("nav paint applied ink=0x" + Integer.toHexString(ink)
                            + " dr=" + dr.getClass().getSimpleName());
                }
            }
            // ColorOS 原生图标常被压到 60~90% 透明，统一抬到 70%；
            // HyperOS 保持系统自己的 alpha，避免把本应淡出的按钮画实。
            if (!navHyperOs && pt.getAlpha() != NAV_ICON_ALPHA) {
                pt.setAlpha(NAV_ICON_ALPHA);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 底栏图标透明度 70% */
    private static final int NAV_ICON_ALPHA = 179;

    private boolean navPaintAppliedLogged;

    private static int filtColorOf(android.graphics.ColorFilter f) {
        try {
            Field mf = f.getClass().getDeclaredField("mColor");
            mf.setAccessible(true);
            return mf.getInt(f);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /**
     * 图标色：优先跟「字符颜色」走，但要保证跟面板底色有对比 ——
     * 深色面板配白字、浅色面板配黑字，字符颜色反过来的（比如亮色主题里
     * 字符色被配成白）就用兜底色，否则图标会糊在面板上看不见。
     */
    private int navInkColor() {
        int ink = keyInk();
        int fallback = panelLuminance() < 0.55f ? 0xFFD8D8D8 : 0xFF2C2C2E;
        if (ink == 0) {
            return fallback;
        }
        float lum = luminanceOf(ink);
        float panelLum = panelLuminance();
        if (Math.abs(lum - panelLum) < 0.22f) {
            return fallback;
        }
        return 0xFF000000 | (ink & 0xFFFFFF);
    }

    private float panelLuminance() {
        return luminanceOf(panelColor == 0 ? 0xFF1C1C1C : panelColor);
    }

    private static float luminanceOf(int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    /** 一次性把面板视图树打印到探针，用来定位底栏那两个图标是哪个 View 画的 */
    private boolean panelTreeDumped;
    private void dumpPanelTreeOnce(View panel) {
        if (panelTreeDumped) {
            return;
        }
        panelTreeDumped = true;
        panel.postDelayed(() -> {
            try {
                View root = panel.getRootView();
                if (!(root instanceof android.view.ViewGroup)) {
                    return;
                }
                java.util.List<String> lines = new ArrayList<>();
                walkForDump((android.view.ViewGroup) root, 0, lines);
                for (String line : lines) {
                    probe("tree " + line);
                }
            } catch (Throwable t) {
                probe("tree dump failed: " + t);
            }
        }, 700);
    }

    private void walkForDump(android.view.ViewGroup group, int depth, java.util.List<String> out) {
        if (out.size() > 80 || depth > 8) {
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String idName = "?";
            try {
                int id = child.getId();
                if (id != 0 && child.getResources() != null) {
                    idName = child.getResources().getResourceEntryName(id);
                }
            } catch (Throwable ignore) {
            }
            out.add(depth + " " + child.getClass().getSimpleName() + " #" + idName
                    + " " + child.getLeft() + "," + child.getTop()
                    + "-" + child.getRight() + "," + child.getBottom()
                    + " vis=" + child.getVisibility());
            if (child instanceof android.view.ViewGroup) {
                walkForDump((android.view.ViewGroup) child, depth + 1, out);
            }
        }
    }

    /**
     * 左侧标点长条（S1_recycleview → ImeSboAndSybKeysScrollView）里的 ? , ° ! 四格，
     * 它们是 RecyclerView 的子项、不走按键绘制，所以单独按「字符颜色」刷一遍文字色。
     */
    private void applySymbolStripColor(View panel) {
        int ink = keyInk();
        if (ink == 0) {
            return;
        }
        View strip = findByClassSuffix(panel, ".oldsymbol.ImeSboAndSybKeysScrollView", 0);
        if (strip instanceof android.view.ViewGroup) {
            tintSymbolCellChildren((android.view.ViewGroup) strip, ink);
        }
        // 符号页底部那一排「中 / 英 / 《》 / ¥€ / ÷= / ǎ / あ …」是 ImeSymbolTypeScrollView
        // 里的一串 ImageView 图标，不走按键绘制、也不走 TextView 那条路，
        // 以前完全没被接管，所以一直是宿主自己那层暗淡的灰。

    }

    /** 诊断：把树里类名带 Symbol 的视图列出来 */
    private void collectSymbolClasses(android.view.View v, StringBuilder sb, int depth) {
        if (v == null || depth > 20 || sb.length() > 600) {
            return;
        }
        String n = v.getClass().getSimpleName();
        if (n.toLowerCase(java.util.Locale.ROOT).contains("symbol")) {
            sb.append(" |").append(n).append('@').append(depth);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectSymbolClasses(g.getChildAt(i), sb, depth + 1);
            }
        }
    }

    private void tintSymbolCellChildren(android.view.ViewGroup group, int ink) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) child;
                int old = tv.getCurrentTextColor();
                int alpha = android.graphics.Color.alpha(old);
                if (alpha == 0) {
                    alpha = 0xFF;
                }
                int inkA = android.graphics.Color.alpha(ink);
                int target = ((inkA == 0xFF ? alpha : (inkA == 0 ? 1 : inkA)) << 24) | (ink & 0x00FFFFFF);
                if (tv.getCurrentTextColor() != target) {
                    tv.setTextColor(target);
                }
                continue;
            }
            if (child instanceof android.widget.ImageView) {
                ((android.widget.ImageView) child).setColorFilter(ink,
                        android.graphics.PorterDuff.Mode.SRC_IN);
                continue;
            }
            if (child instanceof android.view.ViewGroup) {
                tintSymbolCellChildren((android.view.ViewGroup) child, ink);
            }
        }
    }

    /**
     * 功能键上色：圆底跟随 logo 圆底色，图标用「功能键图标色」。
     * 只作用于候选栏右侧这一排 item，不碰二级页面。
     */
    private void applyFnKeyColors(View panel) {
        android.view.ViewGroup rv = findFnRv(panel);
        if (rv == null) {
            return;
        }
        fnRvRef = new WeakReference<>(rv);
        int circle = logoCircleColor;
        int icon = fnIconColor;
        if (circle == 0 && icon == 0) {
            return;
        }
        captureFunctionKeys(panel);
        String fnSig = Integer.toHexString(circle) + "/" + Integer.toHexString(icon)
                + "/" + rv.getChildCount();
        boolean fnChanged = !fnSig.equals(lastFnSig);
        if (fnChanged) {
            lastFnSig = fnSig;
            probe("fn apply circle=#" + Integer.toHexString(circle)
                    + " icon=#" + Integer.toHexString(icon) + " n=" + rv.getChildCount());
        }
        for (int i = 0; i < rv.getChildCount(); i++) {
            View item = rv.getChildAt(i);
            View disc = findDisc(item, 0);
            View glyph = findGlyph(item, 0);
            if (disc != null && fnChanged) {
                probe("fn[" + i + "] item=" + item.getWidth() + "x" + item.getHeight()
                        + " disc=" + disc.getClass().getSimpleName()
                        + " " + disc.getWidth() + "x" + disc.getHeight()
                        + " bg=" + (disc.getBackground() == null ? "null"
                                : disc.getBackground().getClass().getSimpleName())
                        + " color=" + hexOfDrawable(disc.getBackground()));
            } else {
                probe("fn[" + i + "] disc=null item=" + item.getClass().getSimpleName());
            }
            if (disc != null && circle != 0) {
                Drawable bg = disc.getBackground();
                if (bg instanceof android.graphics.drawable.GradientDrawable) {
                    Drawable m = bg.mutate();
                    ((android.graphics.drawable.GradientDrawable) m).setColor(circle);
                    disc.setBackground(m);
                }
            }
            if (glyph instanceof android.widget.ImageView && icon != 0) {
                fnGlyphViews.put(glyph, Boolean.TRUE);
                ((android.widget.ImageView) glyph).setColorFilter(
                        icon, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }
    }

    /**
     * 候选栏右侧功能键的图标色「钉死」：
     * 宿主每次触摸/状态变化都会自己再 setColorFilter 一遍（普通、按下、禁用各一套），
     * 我们的着色只是布局后补一次，于是会先闪一帧宿主的原色。
     * 这里直接 hook ImageView.setColorFilter，在这排图标上把颜色就地换成配置色，
     * 宿主刷多少次都直接是我们的色，不再有中间帧。
     */
    private void installFnIconTintHook() throws Throwable {
        if (fnTintHookInstalled) {
            return;
        }
        fnTintHookInstalled = true;
        try {
            Method m = android.widget.ImageView.class.getDeclaredMethod(
                    "setColorFilter", int.class, android.graphics.PorterDuff.Mode.class);
            install(m, "wetype_dark_fn_icon_tint", chain -> {
                Object self = chain.getThisObject();
                int c = fnIconColor;
                if (c == 0 || self == null) {
                    return chain.proceed();
                }
                if (self instanceof View && isFnGlyphView((View) self)) {
                    if (fnTintHit.getAndIncrement() < 3) {
                        int host = 0;
                        Object a0 = chain.getArg(0);
                        if (a0 instanceof Integer) {
                            host = (Integer) a0;
                        }
                        probe("fn icon tint host=0x" + Integer.toHexString(host)
                                + " -> 0x" + Integer.toHexString(c));
                    }
                    Object[] args = {c, chain.getArg(1)};
                    return chain.proceed(args);
                }
                return chain.proceed();
            });
            probe("fn icon tint hook ok");
        } catch (Throwable t) {
            probe("fn icon tint hook failed: " + t);
        }
    }

    /** 这个 ImageView 是不是候选栏右侧那排功能键里的图标 */
    private boolean isFnGlyphView(View v) {
        if (v == null) {
            return false;
        }
        if (fnGlyphViews.containsKey(v)) {
            return true;
        }
        View rv = fnRvRef.get();
        if (rv == null) {
            return false;
        }
        android.view.ViewParent p = v.getParent();
        int depth = 0;
        while (p instanceof View && depth < 5) {
            if (p == rv) {
                return true;
            }
            p = p.getParent();
            depth++;
        }
        return false;
    }

    /**
     * 候选词整体右移 5dp。
     * 候选词是 canvas 自绘的 SelfDrawScrollView（宽 1098 @4,0，右端 1102 才是功能键区），
     * 只平移这一条，左侧 logo / 右侧功能键都不动。
     * 宿主排版时会把 translationX 归零（刚弹出候选的前几帧会闪回原位），
     * 所以绑一次 OnLayoutChangeListener，每次布局结束都重新平移。
     */
    private void applyCandidateShift(View panel) {
        View strip = findByClassSuffix(panel,
                ".candidate.selfdraw.scrollview.SelfDrawScrollView", 0);
        if (strip == null) {
            return;
        }
        final int shift = Math.round(CANDIDATE_SHIFT_DP
                * strip.getResources().getDisplayMetrics().density);
        if (candidateShiftBound != strip) {
            candidateShiftBound = strip;
            candidateBaseWidth = -1;
            candidateBaseLeft = -1;
            strip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                applyCandidateShiftNow((View) v, shift, l);
            });
        }
        applyCandidateShiftNow(strip, shift, strip.getLeft());
    }

    private boolean candRowLogged;

    /** 列出和候选条同一横带、且水平方向和它重叠/相邻的大块 View（找竖线归属） */
    private static void bandViews(View v, View skip, int bandTop, int bandH,
                                  int depth, StringBuilder sb) {
        if (v == null || depth > 6) {
            return;
        }
        if (v != skip) {
            int w = v.getWidth(), h = v.getHeight();
            boolean sameBand = h > 60 && h < bandH + 40 && w > 40;
            if (sameBand) {
                String idn = v.getId() == View.NO_ID ? "-" : v.getResources().getResourceEntryName(v.getId());
                sb.append("\n  band ").append(v.getClass().getSimpleName()).append('#').append(idn)
                        .append(" L=").append(v.getLeft()).append(" R=").append(v.getRight())
                        .append(" w=").append(w).append(" h=").append(h)
                        .append(" tx=").append(v.getTranslationX());
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                bandViews(g.getChildAt(i), skip, bandTop, bandH, depth + 1, sb);
            }
        }
    }


    /** 临时探针：把候选条这一行的兄弟 View 几何打出来，确认竖线到底在哪 */
    private void logCandidateRow(View strip) {
        if (candRowLogged) {
            return;
        }
        try {
            if (!(strip.getParent() instanceof android.view.ViewGroup)) {
                return;
            }
            candRowLogged = true;
            android.view.ViewGroup p = (android.view.ViewGroup) strip.getParent();
            StringBuilder sb = new StringBuilder("CANDROW parent=")
                    .append(p.getClass().getSimpleName())
                    .append(' ').append(p.getWidth()).append('x').append(p.getHeight());
            for (int i = 0; i < p.getChildCount(); i++) {
                View c = p.getChildAt(i);
                String idn = c.getId() == View.NO_ID ? "-" : c.getResources().getResourceEntryName(c.getId());
                sb.append("\n  ").append(c.getClass().getSimpleName()).append('#').append(idn)
                        .append(" L=").append(c.getLeft()).append(" R=").append(c.getRight())
                        .append(" w=").append(c.getWidth())
                        .append(" tx=").append(c.getTranslationX());
            }
            sb.append("\n  strip L=").append(strip.getLeft()).append(" R=").append(strip.getRight())
                    .append(" w=").append(strip.getWidth());
            // 把「同一横带」上的兄弟层也列出来：竖线归功能键那一层，得看它的左边界到底在哪
            View panelView = strip;
            for (int d = 0; d < 8 && panelView.getParent() instanceof View; d++) {
                panelView = (View) panelView.getParent();
            }
            int stripTop = strip.getTop(), stripH = strip.getHeight();
            sb.append("\n band stripTop=").append(stripTop).append(" h=").append(stripH);
            bandViews(panelView, strip, stripTop, stripH, 0, sb);
            probe(sb.toString());
        } catch (Throwable ignore) {
        }
    }

    /**
     * 右移 dx、同时把候选条自身宽度减 dx：
     * 左边缘跟着内容一起右移，右边缘原地不动 —— 右侧那根竖线就不用动了。
     * 移动 5dp 就减 5dp，移动 10dp 就减 10dp。
     */
    private void applyCandidateShiftNow(View strip, int shift, int left) {
        android.view.ViewGroup.LayoutParams lp = strip.getLayoutParams();
        if (lp == null) {
            return;
        }
        if (candidateBaseWidth < 0 || candidateBaseWidthView != strip) {
            int w = strip.getWidth();
            if (w <= 0) {
                return;
            }
            candidateBaseWidth = w;
            candidateBaseLeft = left;
            candidateBaseWidthView = strip;
            candidateBaseMargin = (lp instanceof android.view.ViewGroup.MarginLayoutParams)
                    ? ((android.view.ViewGroup.MarginLayoutParams) lp).leftMargin : -1;
        }
        // 用「左边距 +shift、宽度 -shift」：布局右边缘原地不动（宿主画的那根竖线就不会动），
        // 内容整体右移 shift，多出来的部分被容器右边界裁掉 —— 裁切边就是容器右边界。
        if (candidateBaseMargin >= 0 && lp instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams mlp =
                    (android.view.ViewGroup.MarginLayoutParams) lp;
            int wantW = Math.max(0, candidateBaseWidth - shift);
            int wantM = candidateBaseMargin + shift;
            if (mlp.width != wantW || mlp.leftMargin != wantM) {
                mlp.width = wantW;
                mlp.leftMargin = wantM;
                strip.setLayoutParams(mlp);
                if (!candidateWidthLogged) {
                    candidateWidthLogged = true;
                    probe("candidate box width " + candidateBaseWidth + "->" + wantW
                            + " margin " + candidateBaseMargin + "->" + wantM
                            + " shiftPx=" + shift);
                }
            }
        } else {
            int target = Math.max(0, candidateBaseWidth - shift);
            if (lp.width != target) {
                lp.width = target;
                strip.setLayoutParams(lp);
            }
        }
        // ② 位移只补「还差多少」，不叠加：
        //    有的容器缩宽后左边缘自己就右移了，这时 translationX 必须是 0，
        //    否则就是「缩宽一次 + 平移一次」= 视觉上移了 2×shift（就是 10dp 的来源）。
        float tx = 0f;
        logCandidateRow(strip);
        // 候选条内容会画到 View 边界之外（这一行的父容器 clipChildren=false），
        // 宽度减了也裁不住，词就压到右边那根竖线上。这里把这一行改成按边界裁剪。
        if (strip.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup row = (android.view.ViewGroup) strip.getParent();
            if (!row.getClipChildren()) {
                row.setClipChildren(true);
            }
        }
        if (strip.getTranslationX() != tx) {
            strip.setTranslationX(tx);
            if (!candidateShiftLogged) {
                candidateShiftLogged = true;
                probe("candidate shift baseLeft=" + candidateBaseLeft + " left=" + left
                        + " tx=" + tx + " shiftPx=" + shift);
            }
        }
    }

    /**
     * 候选条所在容器里那根 1px 竖线（功能键区左侧的分隔线）：跟着候选词一起右移。
     * 只认「宽 ≤4px、高 ≥20px」的细线，不碰图标和圆底。
     */
    private void shiftCandidateDivider(ViewGroup parent, View skip, float dx, int depth) {
        if (parent == null || depth > 2) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == skip) {
                continue;
            }
            int w = c.getWidth();
            if (w > 0 && w <= 4 && c.getHeight() >= 20) {
                if (c.getTranslationX() != dx) {
                    c.setTranslationX(dx);
                }
                if (!dividerShiftLogged) {
                    dividerShiftLogged = true;
                    probe("cand divider shifted px=" + dx + " line=" + w + "x"
                            + c.getHeight() + " id=" + c.getId());
                }
                continue;
            }
            if (c instanceof ViewGroup) {
                shiftCandidateDivider((ViewGroup) c, skip, dx, depth + 1);
            }
        }
    }

    /* ---------------- 按键底色：自绘「连续曲率圆角」，纯色 / 渐变都走这里 ---------------- */
    private final android.graphics.Paint keyBgPaint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final java.util.Map<Long, android.graphics.Path> keyBgPaths =
            java.util.Collections.synchronizedMap(new java.util.HashMap<Long, android.graphics.Path>());
    private final java.util.Map<Long, android.graphics.Shader> keyBgShaders =
            java.util.Collections.synchronizedMap(new java.util.HashMap<Long, android.graphics.Shader>());
    private static final int KEY_BG_PATH_STEPS = 16;
    private final java.util.concurrent.atomic.AtomicInteger typeRowLog =
            new java.util.concurrent.atomic.AtomicInteger();
    private boolean symbolTypeRowHooked;
    private boolean symbolIconHooked;
    private final java.util.concurrent.atomic.AtomicInteger symbolRowFaceLog =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.WeakHashMap<android.view.View, Boolean> symbolRowMembers =
            new java.util.WeakHashMap<>();
    private final java.util.WeakHashMap<android.view.View, Integer> symbolRowApplied =
            new java.util.WeakHashMap<>();
    /** 最近一次拿到的键盘面板，给「符号页那排图标」做延迟补色用 */
    private java.lang.ref.WeakReference<android.view.View> lastPanelRef;
    private long lastSymbolRowScan;

    private final java.util.concurrent.atomic.AtomicInteger symbolTypeRowLog =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.WeakHashMap<android.view.View, Integer> symbolTypeRowPaint =
            new java.util.WeakHashMap<>();
    private int keyBgApplyCount;
    private int keyBgSkipCount;

    private android.graphics.Path keyBgPath(float w, float h, float r) {
        // 缓存 key 用的是取整后的尺寸，路径也必须按同样的整数尺寸构建：
        // 否则 246.6 和 247.4 会共用同一条路径，重建布局后键尺寸一变就有 1px 尺寸不符的帧
        int wi = Math.max(1, Math.round(w));
        int hi = Math.max(1, Math.round(h));
        long key = ((long) wi << 34)
                | ((long) hi << 12)
                | (long) Math.round(r * 4f);
        android.graphics.Path p = keyBgPaths.get(key);
        if (p == null) {
            if (keyBgPaths.size() > 64) {
                keyBgPaths.clear();
            }
            p = SquirclePath.buildFull(wi, hi, r, KEY_BG_PATH_STEPS);
            keyBgPaths.put(key, p);
        }
        return p;
    }

    /** 多色标渐变：位置数组 + 颜色数组，方向按角度，跨整个键面 */
    private android.graphics.Shader keyBgShaderStops(int[] colors, float[] pos,
                                                     float angle, float w, float h) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < colors.length; i++) {
            sb.append(Integer.toHexString(colors[i])).append('@')
                    .append(Math.round(pos[i] * 1000)).append(',');
        }
        sb.append('|').append(Math.round(angle)).append('|')
                .append(Math.round(w)).append('x').append(Math.round(h));
        String key = sb.toString();
        android.graphics.Shader sh = keyGradStopShaders.get(key);
        if (sh == null) {
            float rad = (float) (angle * Math.PI / 180.0);
            float ux = (float) Math.cos(rad);
            float uy = (float) Math.sin(rad);
            float half = Math.abs(ux) * w / 2f + Math.abs(uy) * h / 2f;
            float[] p = pos.clone();
            if (p.length >= 2) {
                p[0] = 0f;
                p[p.length - 1] = 1f;
                for (int i = 1; i < p.length - 1; i++) {
                    p[i] = Math.max(p[i - 1] + 0.001f, Math.min(1f - 0.001f, p[i]));
                }
            }
            sh = new android.graphics.LinearGradient(
                    w / 2f - ux * half, h / 2f - uy * half,
                    w / 2f + ux * half, h / 2f + uy * half,
                    colors, p, android.graphics.Shader.TileMode.CLAMP);
            if (keyGradStopShaders.size() > 48) {
                keyGradStopShaders.clear();
            }
            keyGradStopShaders.put(key, sh);
        }
        return sh;
    }

    private android.graphics.Shader keyBgShader(int c1, int c2, float angle, float w, float h) {
        long sig = ((long) c1 << 24) ^ ((long) c2 << 8)
                ^ ((long) (Math.round(angle) & 0xFF) << 32)
                ^ ((long) Math.round(w) << 40) ^ ((long) Math.round(h) << 48);
        android.graphics.Shader sh = keyBgShaders.get(sig);
        if (sh == null) {
            float rad = (float) (angle * Math.PI / 180.0);
            float ux = (float) Math.cos(rad);
            float uy = (float) Math.sin(rad);
            float half = Math.abs(ux) * w / 2f + Math.abs(uy) * h / 2f;
            sh = new android.graphics.LinearGradient(
                    w / 2f - ux * half, h / 2f - uy * half,
                    w / 2f + ux * half, h / 2f + uy * half,
                    c1, c2, android.graphics.Shader.TileMode.CLAMP);
            if (keyBgShaders.size() > 64) {
                keyBgShaders.clear();
            }
            keyBgShaders.put(sig, sh);
        }
        return sh;
    }

    /**
     * 用连续曲率圆角（clothoid 过渡 + 中间圆弧）画一层键面底色。
     * 坐标用「本地坐标」建路径，画的时候整体 translate 到键的位置，路径可以按尺寸缓存复用。
     */
    private void drawKeyBgSquircle(android.graphics.Canvas canvas, android.graphics.Rect rect,
                                   float radius, int color) {
        drawKeyBgSquircle(canvas, rect, radius, color, false);
    }

    private void drawKeyBgSquircle(android.graphics.Canvas canvas, android.graphics.Rect rect,
                                   float radius, int color, boolean forceSolid) {
        if (canvas == null || rect == null || rect.width() < 6 || rect.height() < 6) {
            return;
        }
        float w = rect.width();
        float h = rect.height();
        int[] stopColors = keyGradStopColors;
        float[] stopPos = keyGradStopPos;
        boolean multi = stopColors != null && stopPos != null && stopColors.length >= 2;
        boolean grad = !forceSolid && keyGradientOn
                && (multi || keyGradC1 != 0 || keyGradC2 != 0);
        android.graphics.Path path = keyBgPath(w, h, radius);
        if (grad && multi) {
            keyBgPaint.setShader(keyBgShaderStops(stopColors, stopPos, keyGradAngle, w, h));
            keyBgPaint.setColor(0xFFFFFFFF);
        } else if (grad) {
            int c1 = keyGradC1 != 0 ? keyGradC1 : color;
            int c2 = keyGradC2 != 0 ? keyGradC2 : color;
            keyBgPaint.setShader(keyBgShader(c1, c2, keyGradAngle, w, h));
            keyBgPaint.setColor(0xFFFFFFFF);
        } else {
            keyBgPaint.setShader(null);
            keyBgPaint.setColor(color);
        }
        keyBgPaint.setStyle(android.graphics.Paint.Style.FILL);
        int save = canvas.save();
        canvas.translate(rect.left, rect.top);
        canvas.drawPath(path, keyBgPaint);
        canvas.restoreToCount(save);
        keyBgPaint.setShader(null);
    }

    /**
     * 按键底色接管：宿主的键面是 Canvas.drawRoundRect（圆弧），这里换成自绘的连续曲率圆角。
     *
     * 只挂在真正画键面的两条方法上（已按 smali 核对：都读 button.i() 当底色、button.j() 当圆角）：
     *   drawmethod.c#e（常规键 / 逗号键 / 空格）、drawmethod.h#e（符号页）
     * 画之前把宿主的底色字段置 0（宿主 `if (bg == 0) skip`，于是圆角矩形不再画），
     * 我们自己画一层 squircle，画完立刻还原字段。发送 / 完成 / 回车键与左下角「返回」保持宿主原样。
     */
    /**
     * 符号页底部那排「中 / 英 / 《》 / ¥€ / ÷= / ≤≥ / ǎ / あ …」。
     *
     * 它不在按键体系里：dump 出来是
     * `com.tencent.wetype.plugin.hld.symbol.ImeSymbolTypeScrollView`
     * 里一串 97x97 的 ImageView（图标位图），既不经过 drawmethod/*.a(Canvas, KeyView)，
     * 也不在面板 apply 的那条链上，所以我们的按键钩子 / applySymbolStripColor 都摸不到它，
     * 颜色一直是宿主自己那层灰。
     *
     * 这里直接钩它自己的 onDraw / draw：每帧都跑，稳拿这排图标，
     * 按「字符颜色」刷 colorFilter（粉色描不出来的那条路也一起覆盖）。
     */
    /**
     * 符号页底部那排图标（中 / 英 / 《》 / ¥€ / ÷= / ≤≤ / ǎ / あ …）真正的兜底钩子。
     *
     * 这排是 `com.tencent.wetype.plugin.hld.symbol.ImeSymbolTypeScrollView` 里的图标 ImageView，
     * 既不走 drawmethod（不是 KeyView），又是在切到符号页时才 inflate 的，
     * 面板 apply 那一刻去树里找根本找不到。直接钩 ImageView#onDraw：
     * 每帧都会跑，命中「父链里有 ImeSymbolTypeScrollView」就按字符颜色刷 colorFilter。
     */
    private void installSymbolIconHook() {
        if (symbolIconHooked) {
            return;
        }
        try {
            Class<?> ivCls = Class.forName("android.widget.ImageView");
            Method m = ivCls.getDeclaredMethod("onDraw", android.graphics.Canvas.class);
            m.setAccessible(true);
            install(m, "wetype_dark_symbol_icon", chain -> {
                Object self = chain.getThisObject();
                Object canvasArg = chain.getArg(0);
                if (self instanceof android.widget.ImageView) {
                    android.widget.ImageView iv = (android.widget.ImageView) self;
                    if (isSymbolRowIcon(iv) && canvasArg instanceof android.graphics.Canvas) {
                        // 先按「按键」那套补一层键面 + 高光描边，再让宿主把图标画上去
                        paintSymbolRowKeyFace((android.graphics.Canvas) canvasArg, iv);
                    }
                    tintIfSymbolRowIcon(iv);
                }
                return chain.proceed();
            });
            symbolIconHooked = true;
            probe("symbol icon hook ok");
        } catch (Throwable t) {
            probe("symbol icon hook failed: " + t);
        }
    }

    /** 是不是「符号类型那排」里的图标（结果缓存，父链只爬一次） */
    private boolean isSymbolRowIcon(android.widget.ImageView iv) {
        Boolean inside = symbolRowMembers.get(iv);
        if (inside != null) {
            return inside;
        }
        boolean found = false;
        android.view.ViewParent p = iv.getParent();
        for (int i = 0; i < 12 && p instanceof android.view.View; i++) {
            if (((android.view.View) p).getClass().getName()
                    .endsWith("ImeSymbolTypeScrollView")) {
                found = true;
                break;
            }
            p = ((android.view.View) p).getParent();
        }
        symbolRowMembers.put(iv, found);
        if (found) {
            probe("symbol row icon found");
        }
        return found;
    }

    /**
     * 给符号页底部这排图标补上和普通按键一样的键面：
     * 同一个连续曲率路径、同一份键底色（含渐变）、同一条顶部高光描边、同一个圆角值。
     */
    private void paintSymbolRowKeyFace(android.graphics.Canvas canvas,
                                      android.widget.ImageView iv) {
        try {
            int w = iv.getWidth();
            int h = iv.getHeight();
            if (w < 24 || h < 24) {
                return;
            }
            int fill = keyFillColor;
            if (fill == 0) {
                return;   // 没配色就不铺，保持宿主原样
            }
            android.graphics.Rect r = new android.graphics.Rect(0, 0, w, h);
            float radius = keyRadiusPx > 0f ? keyRadiusPx : h * 0.18f;
            radius = Math.min(radius, Math.min(w, h) / 2f);
            // 用「键底色」实色铺：普通键画面上看到的就是这个色（渐变只叠了很低的透明度，
            // 单独铺在这排会偏灰），所以这里直接 forceSolid，跟其他键看起来完全一致。
            drawKeyBgSquircle(canvas, r, radius, fill, true);
            float stroke = Math.max(0f, Math.min(6f, keyRimWidthPx));
            if (stroke > 0f && keyRimAlpha > 0) {
                float innerW = w - stroke;
                float innerH = h - stroke;
                if (innerW > 6f && innerH > 6f) {
                    float corner = Math.max(0f, Math.min(radius - stroke / 2f,
                            Math.min(innerW, innerH) / 2f));
                    int rimA = Math.max(0, Math.min(255, keyRimAlpha));
                    int rimWhite = (rimA << 24) | 0x00FFFFFF;
                    keyRimPaint.setShader(new android.graphics.LinearGradient(
                            0f, 0f, 0f, innerH,
                            new int[]{rimWhite, 0x00FFFFFF, rimWhite},
                            new float[]{0f, 0.5f, 1f},
                            android.graphics.Shader.TileMode.CLAMP));
                    keyRimPaint.setStyle(android.graphics.Paint.Style.STROKE);
                    keyRimPaint.setStrokeWidth(stroke);
                    keyRimPaint.setColor(rimWhite);
                    android.graphics.Path rimPath = keyBgPath(innerW, innerH, corner);
                    int save = canvas.save();
                    canvas.translate(stroke / 2f, stroke / 2f);
                    canvas.drawPath(rimPath, keyRimPaint);
                    canvas.restoreToCount(save);
                    keyRimPaint.setShader(null);
                }
            }
            if (symbolRowFaceLog.getAndIncrement() < 2) {
                probe("symbol row key face " + w + "x" + h + " r=" + radius
                        + " fill=0x" + Integer.toHexString(fill));
            }
        } catch (Throwable ignore) {
        }
    }

    /** 这个 ImageView 是不是「符号类型那排」里的图标；是就按字符颜色刷一遍 */
    private void tintIfSymbolRowIcon(android.widget.ImageView iv) {
        try {
            Boolean inside = symbolRowMembers.get(iv);
            if (inside == null) {
                boolean found = false;
                android.view.ViewParent p = iv.getParent();
                for (int i = 0; i < 12 && p instanceof android.view.View; i++) {
                    if (((android.view.View) p).getClass().getName()
                            .endsWith("ImeSymbolTypeScrollView")) {
                        found = true;
                        break;
                    }
                    p = ((android.view.View) p).getParent();
                }
                inside = found;
                symbolRowMembers.put(iv, inside);
                if (found) {
                    probe("symbol row icon found, n=" + symbolRowMembers.size());
                }
            }
            if (!isSymbolRowIcon(iv)) {
                return;
            }
            int ink = keyInk();
            if (ink == 0) {
                return;
            }
            android.graphics.ColorFilter cur = iv.getColorFilter();
            if (cur instanceof android.graphics.PorterDuffColorFilter
                    && symbolRowApplied.get(iv) != null
                    && symbolRowApplied.get(iv) == ink) {
                return;
            }
            iv.setColorFilter(ink, android.graphics.PorterDuff.Mode.SRC_IN);
            symbolRowApplied.put(iv, ink);
        } catch (Throwable ignore) {
        }
    }

    private void installSymbolTypeRowHook(ClassLoader cl) throws Throwable {
        if (symbolTypeRowHooked) {
            return;
        }
        try {
            Class<?> rowCls = Class.forName(
                    "com.tencent.wetype.plugin.hld.symbol.ImeSymbolTypeScrollView", false, cl);
            int hooked = 0;
            String[] names = {"onDraw", "draw"};
            for (String name : names) {
                try {
                    Method m = rowCls.getDeclaredMethod(
                            name, android.graphics.Canvas.class);
                    m.setAccessible(true);
                    final String hid = "wetype_dark_symtype_" + name;
                    install(m, hid, chain -> {
                        Object self = chain.getThisObject();
                        tintSymbolTypeRow(self);
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable ignore) {
                }
            }
            symbolTypeRowHooked = hooked > 0;
            probe("symbol type row hooks=" + hooked);
        } catch (Throwable t) {
            probe("symbol type row hook failed: " + t);
        }
    }

    /** 把这排图标按「字符颜色」刷一遍（同一颗 View 只刷一次，配置变了才重刷） */
    private void tintSymbolTypeRow(Object self) {
        try {
            int ink = keyInk();
            if (ink == 0 || !(self instanceof android.view.ViewGroup)) {
                return;
            }
            android.view.ViewGroup g = (android.view.ViewGroup) self;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View child = g.getChildAt(i);
                Object was = symbolTypeRowPaint.get(child);
                if (was != null && was.equals(ink)) {
                    continue;
                }
                if (child instanceof android.widget.ImageView) {
                    ((android.widget.ImageView) child).setColorFilter(
                            ink, android.graphics.PorterDuff.Mode.SRC_IN);
                } else if (child instanceof android.widget.TextView) {
                    ((android.widget.TextView) child).setTextColor(ink);
                } else if (child instanceof android.view.ViewGroup) {
                    tintSymbolCellChildren((android.view.ViewGroup) child, ink);
                }
                symbolTypeRowPaint.put(child, ink);
            }
            if (symbolTypeRowLog.getAndIncrement() < 2) {
                probe("symbol type row tinted n=" + g.getChildCount()
                        + " ink=0x" + Integer.toHexString(ink));
            }
        } catch (Throwable ignore) {
        }
    }

        private final java.util.concurrent.atomic.AtomicInteger keyClassLog = new java.util.concurrent.atomic.AtomicInteger();

private final java.util.concurrent.atomic.AtomicInteger leftDbg = new java.util.concurrent.atomic.AtomicInteger();

private void installKeyGradientHook(ClassLoader cl) throws Throwable {
        if (keyGradHookInstalled) {
            return;
        }
        Class<?> jCls = Class.forName(KEYBOARD_PKG + ".j", false, cl);
        final Method getRect = jCls.getDeclaredMethod("t");   // ImeButton.getDrawRect(): Rect
        getRect.setAccessible(true);
        final Method getId = jCls.getDeclaredMethod("K");     // ImeButton.getKeyId(): String
        getId.setAccessible(true);

        // drawmethod 包下一共 a..l 12 个类，每个键盘可能用不同的一套；
        // 之前只挂了 c/h，符号页（ImeSymbolKeyboardView / S5_key_*）走的那套没被挂上，
        // 所以同一页里一部分键有我们的键面、一部分还是宿主原色。
        // 实测只有 c#e / h#e 声明了（Canvas, j），hook 数一直是 2，符号页那套根本不走 e。
        // 所有键盘的每个键最终都会经过 drawmethod.c#a（每键绘制的总入口），
        // 所以改挂 c#a：先把键面画上去，再让宿主在它上面画图标/文字，宿主底色已被压成 alpha=1。
        String[][] targets = {
                {"c", "e"},
                {"h", "e"},
        };
        int hooked = 0;
        for (String[] t : targets) {
            try {
                Class<?> cls = Class.forName(KEYBOARD_PKG + ".drawmethod." + t[0], false, cl);
                Method target = null;
                for (Method m : cls.getDeclaredMethods()) {
                    if (!m.getName().equals(t[1])) {
                        continue;
                    }
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 2 && pts[0] == android.graphics.Canvas.class
                            && pts[1] == jCls && m.getReturnType() == void.class) {
                        target = m;
                        break;
                    }
                }
                if (target == null) {
                    probe("key bg no method " + t[0] + "#" + t[1]);
                    continue;
                }
                final String id = "wetype_dark_key_bg_" + t[0] + "_" + t[1];
                install(target, id, chain -> {
                    Object button = chain.getArg(1);
                    Object canvasArg = chain.getArg(0);
                    if (button == null || !(canvasArg instanceof android.graphics.Canvas)) {
                        return chain.proceed();
                    }
                    String keyId = null;
                    try {
                        keyId = String.valueOf(getId.invoke(button));
                    } catch (Throwable ignore) {
                    }
                    // 发送 / 完成 / 回车键也走我们自己的连续曲率键面，
                    // 但颜色用宿主已经算好的那一个（主题色由 accent 通道负责，这里不改色）
                    boolean sendKey = isSendKeyId(keyId);
                    android.graphics.Rect rect = null;
                    try {
                        rect = (android.graphics.Rect) getRect.invoke(button);
                    } catch (Throwable ignore) {
                    }
                    if (rect == null || rect.width() < 6 || rect.height() < 6) {
                        return chain.proceed();
                    }
                    // 左下角贴边的那颗（数字面板 / 符号页的 return，以及返回键）保持宿主原样，
                    // 它本来是强调色，被我们一起染成键色后就看不出是功能键了。
                    if ("back".equals(keyId) && rect.left <= CORNER_LEFT_PX) {
                        return chain.proceed();
                    }

                    // 表情页那颗删除键：什么都不铺，键面 / 描边 / 投影全留空，只留删除符号
                    if (isEmojiDeleteKey(keyId)) {
                        if (keyBgApplyCount < 4) {
                            keyBgApplyCount++;
                            probe("key skip emoji delete via=" + id);
                        }
                        return chain.proceed();
                    }
                    // 九键 / 数字页左侧那条标点长条（S1_ / S4_recycleview，约 213x527）也是一颗
                    // 「伪键」：它既是容器名、又高过宽度 2 倍、还越过 420px 尺寸线，三处规则都会
                    // 把它挡掉，于是它始终保留宿主默认底色，调按键颜色时这条长条不变色。
                    // 先把它单独挑出来，让它跟普通键一样吃按键底色 / 渐变。
                    // 左列这根 213x527 的 recycleview「伪键」必须跟着按键配色走（它里面 4 个小格
                    // 是独立绘制的，不铺的话这一条永远是宿主原色，和右边键不同色）。
                    boolean narrowStrip = keyId != null && keyId.contains("recycleview")
                            && rect.width() <= 300 && rect.height() <= 760;
                    // 容器型「伪键」不给铺底色：符号页 / 九键左侧那种一条占半屏的容器
                    // （recycleview 之类，213x527）如果被我们画成键面，就是一个大白块框住一片按键
                    if (!narrowStrip && keyId != null && (isPseudoContainer(keyId)
                            || keyId.contains("cand") || keyId.contains("panel"))) {
                        probe("key skip id=" + keyId + " rect=" + rect.width() + "x"
                                + rect.height() + " why=name via=" + id);
                        return chain.proceed();
                    }
                    if (!narrowStrip && rect.height() > 2f * rect.width()) {
                        probe("key skip id=" + keyId + " rect=" + rect.width() + "x"
                                + rect.height() + " why=ratio via=" + id);
                        return chain.proceed();
                    }
                    // 回车/发送键是 213x347（跨两行），真键里最高的就是它；
                    // 比这还大的（1272x650 之类）才是容器/整行底，不能铺键面
                    if (!narrowStrip && (rect.width() > 720 || rect.height() > 420)) {
                        probe("key skip id=" + keyId + " rect=" + rect.width() + "x"
                                + rect.height() + " why=size via=" + id);
                        return chain.proceed();
                    }
                    if (keyId != null && keyId.startsWith("S5_key_2") && keyClassLog.getAndIncrement() < 4) {
                        try {
                            StringBuilder sb = new StringBuilder("s5key fields " + keyId
                                    + " cls=" + button.getClass().getName() + " :");
                            Class<?> k = button.getClass();
                            while (k != null && k != Object.class) {
                                for (java.lang.reflect.Field fd : k.getDeclaredFields()) {
                                    if (fd.getType() == int.class) {
                                        fd.setAccessible(true);
                                        sb.append(' ').append(fd.getName()).append("=0x")
                                                .append(Integer.toHexString(fd.getInt(button)));
                                    }
                                }
                                k = k.getSuperclass();
                            }
                            probe(sb.toString());
                        } catch (Throwable ignore) {
                        }
                    }
                    java.lang.reflect.Field bgField = fillFieldOf(button);
                    if (bgField == null) {
                        return chain.proceed();
                    }
                    int hostColor;
                    try {
                        hostColor = bgField.getInt(button);
                    } catch (Throwable t2) {
                        return chain.proceed();
                    }
                    float radius = keyRadiusPx;
                    if (radius <= 0f) {
                        java.lang.reflect.Field cf = bgCornerFieldOf(button);
                        try {
                            radius = cf == null ? 0f : cf.getInt(button);
                        } catch (Throwable ignore) {
                            radius = 0f;
                        }
                    }
                    int color = sendKey
                            ? hostColor
                            : accent(keyFillColor != 0 ? keyFillColor : hostColor);
                    // 宿主自带 1px 描边（borderColor）与键底投影（shadowColor）一起关掉，避免键边出现灰细线
                    java.lang.reflect.Field borderField = intFieldNamed(button, "M");
                    java.lang.reflect.Field shadowField = intFieldNamed(button, "K");
                    int hostBorder = 0;
                    int hostShadow = 0;
                    try {
                        if (borderField != null) {
                            hostBorder = borderField.getInt(button);
                            borderField.setInt(button, 0);
                        }
                    } catch (Throwable ignore) {
                    }
                    try {
                        if (shadowField != null) {
                            hostShadow = shadowField.getInt(button);
                            shadowField.setInt(button, 0);
                        }
                    } catch (Throwable ignore) {
                    }
                    // 键面一律只由我们画：宿主的 Canvas.drawRoundRect 是普通圆弧圆角，
                    // 一旦让它露出来，键的高光描边（连续曲率）就会和键面轮廓对不上。
                    // 所以不管纯色还是渐变，都把宿主底色压成 alpha=1 的同色（不是 0，
                    // 0 在宿主里是「这个键没有底色」的信号，重建布局那一帧会少画一层），
                    // 然后自己铺一层连续曲率圆角的键面。
                    try {
                        bgField.setInt(button, (color & 0x00FFFFFF) | 0x01000000);
                    } catch (Throwable ignore) {
                    }
                    try {
                        // 必须画在宿主之后：宿主画键面时会把 canvas 裁到它自己的圆角矩形上，
                        // 我们先画的话四个角会被那道裁剪切掉，而描边在裁剪外，角上就露一条缝。
                        Object drawn = chain.proceed();
                        drawKeyBgSquircle((android.graphics.Canvas) canvasArg, rect, radius, color, sendKey);
                        if (narrowStrip || keyBgApplyCount < 6 || rect.width() > 300 || rect.height() > 200) {
                            keyBgApplyCount++;
                            probe("key bg squircle id=" + keyId
                                    + " rect=" + rect.width() + "x" + rect.height()
                                    + " r=" + radius + " color=0x" + Integer.toHexString(color)
                                    + " grad=" + keyGradientOn + " via=" + id);
                        }
                        return drawn;
                    } finally {
                        try {
                            bgField.setInt(button, hostColor);
                        } catch (Throwable ignore) {
                        }
                        try {
                            if (borderField != null) {
                                borderField.setInt(button, hostBorder);
                            }
                        } catch (Throwable ignore) {
                        }
                        try {
                            if (shadowField != null) {
                                shadowField.setInt(button, hostShadow);
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                });
                hooked++;
            } catch (Throwable t2) {
                probe("key bg " + t[0] + " failed: " + t2);
            }
        }
        probe("key bg hooks=" + hooked);
        keyGradHookInstalled = true;
    }

        private static java.lang.reflect.Field[] paintFieldsOf(Class<?> cls) {
        java.util.List<java.lang.reflect.Field> list = new java.util.ArrayList<>();
        Class<?> k = cls;
        while (k != null && k != Object.class) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (f.getType() == android.graphics.Paint.class
                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        list.add(f);
                    } catch (Throwable ignore) {
                    }
                }
            }
            k = k.getSuperclass();
        }
        return list.toArray(new java.lang.reflect.Field[0]);
    }

    private static Class<?> primitiveOrClass(String name) throws ClassNotFoundException {
        switch (name) {
            case "float": return float.class;
            case "int": return int.class;
            case "boolean": return boolean.class;
            default: return Class.forName(name);
        }
    }

    /**
     * 高亮候选框：首位（选中）候选词背后那层圆角底，颜色和圆角都可以单独配。
     *
     * 宿主在 selfdraw.selfview.e#b0(Context, Canvas, Rect) 里调 private i() 画这层底：
     * 颜色来自 k()（选中态读 selectedBackgroundColorAttr，否则读 backgroundColorAttr /
     * backGroundColor），圆角读 cornerSize 字段。
     * 做法：绘制前后临时改这几个字段再还原，颜色设成配置色、圆角设成配置 px；
     * 只动 isSelected 的那一颗，普通候选不动。
     */
    private void installCandidateBoxHook(ClassLoader cl) throws Throwable {
        Class<?> eCls = Class.forName(
                "com.tencent.wetype.plugin.hld.candidate.selfdraw.selfview.e", false, cl);
        // 混淆后的真实字段名（jadx 的 cornerSize / isSelected 是 Kotlin 元数据里的名字）
        final Field fCorner = eCls.getDeclaredField("r");     // cornerSize
        final Field fSelected = eCls.getDeclaredField("w");   // isSelected
        final Field fSelAttr = eCls.getDeclaredField("x");    // selectedBackgroundColorAttr
        final Field fBgAttr = eCls.getDeclaredField("t");     // backgroundColorAttr
        final Field fPressAttr = eCls.getDeclaredField("v");  // pressedBackgroundColorAttr
        final Field fBgColor = eCls.getDeclaredField("s");    // backGroundColor
        for (Field f : new Field[]{fCorner, fSelected, fSelAttr, fBgAttr, fPressAttr, fBgColor}) {
            f.setAccessible(true);
        }
        XposedInterface.Hooker hooker = chain -> {
            int color = candBoxColor;
            int radius = candBoxRadiusPx;
            Object self = chain.getThisObject();
            if (self == null || (color == 0 && radius < 0)) {
                return chain.proceed();
            }
            if (!fSelected.getBoolean(self)) {
                return chain.proceed();
            }
            int oldCorner = fCorner.getInt(self);
            Object oldSel = fSelAttr.get(self);
            Object oldBg = fBgAttr.get(self);
            Object oldPress = fPressAttr.get(self);
            int oldColor = fBgColor.getInt(self);
            try {
                if (radius >= 0) {
                    fCorner.setInt(self, radius);
                }
                if (color != 0) {
                    fSelAttr.set(self, null);
                    fBgAttr.set(self, null);
                    fPressAttr.set(self, null);
                    fBgColor.setInt(self, color);
                }
                if (!candBoxLogged) {
                    candBoxLogged = true;
                    probe("cand box hit color=0x" + Integer.toHexString(color)
                            + " radius=" + radius + "px host=0x" + Integer.toHexString(oldColor)
                            + "/" + oldCorner);
                }
                return chain.proceed();
            } finally {
                try {
                    fCorner.setInt(self, oldCorner);
                    fSelAttr.set(self, oldSel);
                    fBgAttr.set(self, oldBg);
                    fPressAttr.set(self, oldPress);
                    fBgColor.setInt(self, oldColor);
                } catch (Throwable ignore) {
                }
            }
        };
        // b0 是「画底 → 画字 → 画图标」的入口；i 是里面单独画底的那一步。
        // 两个都挂上：R8 若把 i 内联进 b0，至少 b0 还能拦到。
        try {
            install(eCls.getDeclaredMethod("b0", android.content.Context.class,
                    android.graphics.Canvas.class, android.graphics.Rect.class),
                    "wetype_dark_cand_box_b0", hooker);
        } catch (Throwable t) {
            probe("cand box b0 hook failed: " + t);
        }
        try {
            install(eCls.getDeclaredMethod("i", android.content.Context.class,
                    android.graphics.Canvas.class, android.graphics.Rect.class),
                    "wetype_dark_cand_box_i", hooker);
        } catch (Throwable t) {
            probe("cand box i hook failed: " + t);
        }
    }

    /** 诊断：把背景 drawable 的实色 / 圆角打出来（GradientDrawable 有效，其余返回类名） */
    private String hexOfDrawable(Drawable bg) {
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            android.graphics.drawable.GradientDrawable gd =
                    (android.graphics.drawable.GradientDrawable) bg;
            StringBuilder sb = new StringBuilder();
            android.content.res.ColorStateList cs = gd.getColor();
            sb.append(cs == null ? "null" : "#" + Integer.toHexString(cs.getDefaultColor()));
            sb.append(" r=").append((int) gd.getCornerRadius());
            sb.append(" a=").append(gd.getAlpha());
            return sb.toString();
        }
        if (bg == null) {
            return "null";
        }
        return bg.getClass().getSimpleName() + " a=" + bg.getAlpha();
    }

    /** item 里那个圆底盘：圆角半径极大的 GradientDrawable */
    private View findDisc(View v, int depth) {
        if (depth > 5 || !(v instanceof android.view.ViewGroup)) {
            return null;
        }
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            Drawable bg = c.getBackground();
            if (bg instanceof android.graphics.drawable.GradientDrawable) {
                android.graphics.drawable.GradientDrawable gd =
                        (android.graphics.drawable.GradientDrawable) bg;
                if (gd.getCornerRadius() >= 30f && c.getWidth() > 0) {
                    return c;
                }
            }
            View deep = findDisc(c, depth + 1);
            if (deep != null) {
                return deep;
            }
        }
        return null;
    }

    /** item 里那颗图标：圆盘内部第一个可见 ImageView */
    private View findGlyph(View v, int depth) {
        if (depth > 6 || !(v instanceof android.view.ViewGroup)) {
            return null;
        }
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof android.widget.ImageView && c.getVisibility() == View.VISIBLE
                    && c.getWidth() > 0 && c.getHeight() > 0) {
                return c;
            }
            View deep = findGlyph(c, depth + 1);
            if (deep != null) {
                return deep;
            }
        }
        return null;
    }

    private boolean captureFunctionKeys(View panel) {
        android.view.ViewGroup rv = findFnRv(panel);
        if (rv == null) {
            return false;
        }
        int n = rv.getChildCount();

        StringBuilder sb = new StringBuilder("fn keys captured n=" + n
                + " rv=" + rv.getWidth() + "x" + rv.getHeight());
        fnKeys.clear();
        for (int i = 0; i < n; i++) {
            View item = rv.getChildAt(i);
            // 从右往左：收起 / 剪贴板 / AI 表情
            int fromRight = rv.getWidth() - item.getLeft();
            String id = fromRight <= 175 ? "collapse" : (fromRight <= 330 ? "clipboard" : "emoji");
            fnKeys.put(item, id);
            sb.append(" [").append(i).append("]").append(id)
                    .append('@').append(item.getLeft())
                    .append('-').append(item.getLeft() + item.getWidth())
                    .append(' ').append(item.getWidth()).append('x').append(item.getHeight());
        }
        if (fnLogged.put(rv, Boolean.TRUE) == null) {
            probe(sb.toString());
            log(Log.INFO, TAG, sb.toString());
        }
        return true;
    }

    /** 同 findByClassSuffix，但翻到 20 层（符号页那排图标埋在第 12 层左右） */
    private View findDeepByClassSuffix(View v, String suffix, int depth) {
        if (v == null || depth > 20) {
            return null;
        }
        if (v.getClass().getName().endsWith(suffix)) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findDeepByClassSuffix(g.getChildAt(i), suffix, depth + 1);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private View findByClassSuffix(View v, String suffix, int depth) {
        if (v == null || depth > 8) {
            return null;
        }
        if (v.getClass().getName().endsWith(suffix)) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findByClassSuffix(g.getChildAt(i), suffix, depth + 1);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    /** 诊断：候选栏里有几个 RecyclerView，哪个是右侧功能键那排 */
    /** 诊断用：直接落盘（这台机器上 logcat 会被系统日志冲掉） */
    private static void probe(String msg) {
        try {
            java.io.File f = new java.io.File(
                    "/data/user/0/com.tencent.wetype/files/wetype-dark-probe.log");
            java.io.FileOutputStream os = new java.io.FileOutputStream(f, true);
            os.write((System.currentTimeMillis() + " " + msg + "\n").getBytes());
            os.close();
        } catch (Throwable ignore) {
        }
    }

    /**
     * 主动捕获一份键表：绘制时就把所有 key 记下来（发送 / 回车键除外）。
     * 这样不依赖用户先点一次，键盘一画出来就能拿到完整列表。
     */
    /**
     * 找这颗键的键面底色字段。
     *
     * 宿主原色不固定（亮色实测 J=0xFFB7BCC4，发送键 R=0xFF23C891 等），
     * 所以不能按颜色值认：直接认字段 `J`（4.0.1 里就是 bgColor），拿不到再退到名字带 bg 的字段。
     */
    private java.lang.reflect.Field fillFieldOf(Object key) {
        java.lang.reflect.Field cached = fillFieldByKey.get(key);
        if (cached != null) {
            return cached;
        }
        java.lang.reflect.Field[] fields = intFieldsByClass.computeIfAbsent(
                key.getClass(), c -> {
                    java.util.List<java.lang.reflect.Field> list = new ArrayList<>();
                    Class<?> k = c;
                    while (k != null && k != Object.class) {
                        for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                            if (f.getType() == int.class
                                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                                try {
                                    f.setAccessible(true);
                                    list.add(f);
                                } catch (Throwable ignore) {
                                }
                            }
                        }
                        k = k.getSuperclass();
                    }
                    return list.toArray(new java.lang.reflect.Field[0]);
                });
        java.lang.reflect.Field bg = null;
        java.lang.reflect.Field nameBg = null;
        for (java.lang.reflect.Field f : fields) {
            if ("J".equals(f.getName())) {
                bg = f;
            } else if (nameBg == null && f.getName().toLowerCase().contains("bg")) {
                nameBg = f;
            }
        }
        java.lang.reflect.Field chosen = bg != null ? bg : nameBg;
        if (chosen != null) {
            fillFieldByKey.put(key, chosen);
        }
        return chosen;
    }

    /** 按 dex 字段名取按键的某个 int 字段（键面接管用：K=shadowColor、M=borderColor） */
    private java.lang.reflect.Field intFieldNamed(Object key, String dexName) {
        java.lang.reflect.Field[] fields = intFieldsByClass.computeIfAbsent(
                key.getClass(), c -> {
                    java.util.List<java.lang.reflect.Field> list = new ArrayList<>();
                    Class<?> k = c;
                    while (k != null && k != Object.class) {
                        for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                            if (f.getType() == int.class
                                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                                try {
                                    f.setAccessible(true);
                                    list.add(f);
                                } catch (Throwable ignore) {
                                }
                            }
                        }
                        k = k.getSuperclass();
                    }
                    return list.toArray(new java.lang.reflect.Field[0]);
                });
        for (java.lang.reflect.Field f : fields) {
            if (dexName.equals(f.getName())) {
                return f;
            }
        }
        return null;
    }

    /** 找按键的 pressMaskColor 字段（4.0.1 = O），找不到就返回 null，不接管按下色 */
    private java.lang.reflect.Field pressFieldOf(Object key) {
        java.lang.reflect.Field cached = pressFieldByKey.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> k = key.getClass();
            while (k != null && k != Object.class) {
                try {
                    java.lang.reflect.Field f = k.getDeclaredField(PRESS_FIELD_NAME);
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        pressFieldByKey.put(key, f);
                        return f;
                    }
                } catch (NoSuchFieldException ignore) {
                }
                k = k.getSuperclass();
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private float cachedDensity = 0f;

    private float densityOf() {
        if (cachedDensity <= 0f) {
            try {
                cachedDensity = android.content.res.Resources.getSystem()
                        .getDisplayMetrics().density;
            } catch (Throwable ignore) {
                cachedDensity = 3.5f;
            }
        }
        return cachedDensity;
    }

    /** 安装包内置的默认主题配置（assets/default-appearance.json），读到后落一份到 live 路径 */
    /**
     * 读安装包里内置的默认配置。
     * 注意：这里拿到的 Context 是微信输入法的，它自己的 assets 里没有我们的文件
     * （在 2.3 上就是 FileNotFoundException → 内嵌默认永远读不到）。
     * 所以先走宿主 assets，失败再从模块自己的 APK（按包名取 sourceDir）里解 zip。
     */
    private String readBundledAsset(android.content.Context ctx) {
        try {
            java.io.InputStream is = ctx.getAssets().open("default-appearance.json");
            return readAll(is);
        } catch (Throwable ignore) {
        }
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(MODULE_PKG, 0);
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(ai.sourceDir);
            try {
                java.util.zip.ZipEntry e = zf.getEntry("assets/default-appearance.json");
                if (e != null) {
                    java.io.InputStream is = zf.getInputStream(e);
                    return readAll(is);
                }
            } finally {
                try {
                    zf.close();
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            probe("bundled asset from apk failed: " + t);
        }
        return null;
    }

    private static String readAll(java.io.InputStream is) throws java.io.IOException {
        try {
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            try {
                is.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 这份 live 配置是不是我们这版写的（有 ultra 段、亮色段里有 key_fill） */
    private static boolean isOurAppearance(String text) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(text);
            if (!o.has("ultra")) {
                return false;
            }
            org.json.JSONObject light = o.optJSONObject("light");
            return light != null && light.has("key_fill");
        } catch (Throwable t) {
            return false;
        }
    }

    private String bundledAppearance() {
        return bundledAppearance(false);
    }

    private String bundledAppearance(boolean replaceLive) {
        try {
            android.content.Context ctx = cfgContext;
            if (ctx == null) {
                return null;
            }
            String text = readBundledAsset(ctx);
            if (text.isEmpty()) {
                return null;
            }
            try {
                java.io.File out = new java.io.File(APPEARANCE_LIVE_PATHS[1]);
                if (replaceLive && out.isFile() && out.length() > 0) {
                    // 旧格式（app2 那一套 light/dark/numbers/icons）挡在这里，我们的默认配置永远写不进去；
                    // 只在「格式不认识」时覆盖，并留一份 .bak 方便回退。
                    try {
                        java.io.File bak = new java.io.File(out.getPath() + ".bak");
                        if (!bak.isFile()) {
                            java.nio.file.Files.copy(out.toPath(), bak.toPath());
                        }
                    } catch (Throwable ignore) {
                    }
                    probe("legacy appearance replaced by bundled default");
                }
                if (replaceLive || !out.isFile() || out.length() == 0) {
                    java.io.File dir = out.getParentFile();
                    if (dir != null && !dir.isDirectory()) {
                        dir.mkdirs();
                    }
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                        fo.write(text.getBytes("UTF-8"));
                    }
                    probe("default appearance written");
                }
            } catch (Throwable ignore) {
            }
            probe("using bundled default appearance");
            return text;
        } catch (Throwable t) {
            probe("bundled appearance failed: " + t);
            return null;
        }
    }

    /** 解析 appearance-live.json 里的按键底色（hex，可带 # / 6 位 / 8 位）。返回是否读到新内容 */
    private boolean refreshAppearance() {
        try {
            refreshAppModes();
            int mode = resolveAppMode();
            boolean modeChanged = mode != activeAppMode;
            activeAppMode = mode;
            // 主题（亮/暗/极暗）是否真的换了：应用自己没换、只是跟随时段变了也要算
            String curTheme = themeForMode(mode);
            boolean themeChanged = !curTheme.equals(appliedThemeId);
            if (themeChanged) {
                appliedThemeId = curTheme;
                themeJustChanged = true;
                probe("theme -> " + curTheme);
            }

            java.io.File f = null;
            // 两份候选（输入法私有目录 / 外部存储）都在时，挑最后修改的那份：
            // 以前固定取第一个，只要私有目录里残留过一份旧配置，就会把调色台写进外部存储的新配置
            // 永久挡在外面，表现就是「网页上怎么调手机都不变」。
            for (String path : APPEARANCE_LIVE_PATHS) {
                java.io.File candidate = new java.io.File(path);
                if (candidate.isFile() && candidate.length() > 0
                        && (f == null || candidate.lastModified() > f.lastModified())) {
                    f = candidate;
                }
            }
            String text;
            long mtime;
            long length;
            if (f != null) {
                mtime = f.lastModified();
                length = f.length();
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line);
                    }
                }
                text = sb.toString();
                if (!isOurAppearance(text)) {
                    // 这台机器上残留的是旧模块写的配置（light/dark/numbers/icons），
                    // 我们认不出那一套字段 → 大量项回落默认，表现就是「很多配置不生效」。
                    String bundled = bundledAppearance(true);
                    if (bundled != null && isOurAppearance(bundled)) {
                        text = bundled;
                        mtime = -1L;
                        length = bundled.length();
                    }
                }
            } else {
                // 装完还没人调过色：用安装包里内置的那套默认配置，并落一份到 live 路径
                text = bundledAppearance();
                if (text == null) {
                    return false;
                }
                mtime = -1L;
                length = text.length();
            }
            // 用「文件内容」判断有没有变，而不是 mtime+size：
            // 调色台连点几下、或者只改了一两位色值（长度相同）时，mtime 在秒级精度下可能完全不变，
            // 那种情况会被当成「没改」而一直不生效，看起来就是「处理得不及时 / 要重启输入法才对」。
            appearanceMtime = mtime;
            appearanceLength = length;
            if (text.equals(appearanceText) && !modeChanged && !themeChanged) {
                return false;
            }
            appearanceText = text;
            org.json.JSONObject root = new org.json.JSONObject(text);
            // 勾了逐应用模式：按模式选调色板的段；没勾：跟随系统深浅色。
            // 每段都以 light 打底再覆盖，避免某段只写了几项时整组失效。
            boolean explicit = mode >= 0;
            String themeId = themeForMode(mode);
            org.json.JSONObject set = paletteSet(root, themeId);
            String raw = set == null ? null : set.optString("key_fill", "");
            int parsed = parseHexColor(raw);
            keyFillColor = parsed;

            // 按键按下色：读当前主题段里那份（亮色 / 暗色 / 极暗各写各的）
            String pressRaw = set == null ? null : set.optString("key_press_bg", "");
            keyPressColor = parseHexColor(pressRaw);

            // 圆角属于「形状」参数：网页把它们写在调色板根节点，亮/暗/极暗共用一份；
            // 没有根节点值时退回当前主题段的旧写法，保证老配置仍能读。
            int radiusDp = intFrom(root, set, "key_radius_dp", -1);
            keyRadiusPx = radiusDp < 0 ? 0 : Math.round(Math.max(0, radiusDp) * densityOf());
            int panelRadiusDpValue = intFrom(root, set, "panel_radius_dp", (int) RADIUS_DP);
            panelRadiusDp = Math.max(0, panelRadiusDpValue);

            // 自定义话筒图标：调色台塞进来的 data URL，空串表示用宿主原生那枚
            String micRaw = root == null ? "" : root.optString("mic_icon", "");
            if (!micRaw.equals(micIconData)) {
                micIconData = micRaw;
                micIconBitmap = decodeImageData(micRaw);
                probe("mic icon " + (micIconBitmap == null ? "cleared"
                        : micIconBitmap.getWidth() + "x" + micIconBitmap.getHeight()));
            }
            // 亮色直角：把「输入法面板顶部两个圆角」压成直角（系统暗色下不会有亮色主题，所以也不压平）
            panelTopFlat = mode == 1 && !nightMode;

            // 按键底色：纯色 / 渐变
            String keyMode = set == null ? "" : set.optString("key_mode", "");
            keyGradientOn = "gradient".equalsIgnoreCase(keyMode);
            keyGradC1 = parseHexColor(set == null ? null : set.optString("key_grad_c1", ""));
            keyGradC2 = parseHexColor(set == null ? null : set.optString("key_grad_c2", ""));
            keyGradAngle = (float) (set == null ? 90 : set.optDouble("key_grad_angle", 90));
            // 网页「整体透明」：0–100，按比例乘到每个色标的 alpha 上，色标之间相对透明保持不变
            int keyGradAlphaPct = set == null ? 100 : set.optInt("key_grad_alpha", 100);
            keyGradAlphaPct = Math.max(0, Math.min(100, keyGradAlphaPct));
            keyGradC1 = scaleColorAlpha(keyGradC1, keyGradAlphaPct);
            keyGradC2 = scaleColorAlpha(keyGradC2, keyGradAlphaPct);
            // 多色标（key_grad_stops:[{"p":0,"c":"#FFFFFFFF"}, …]），没有就用 c1/c2 两点
            org.json.JSONArray gstops = set == null ? null : set.optJSONArray("key_grad_stops");
            if (gstops != null && gstops.length() >= 2) {
                int n = Math.min(8, gstops.length());
                int[] cs = new int[n];
                float[] ps = new float[n];
                for (int i = 0; i < n; i++) {
                    org.json.JSONObject o = gstops.optJSONObject(i);
                    ps[i] = Math.max(0f, Math.min(1f,
                            (float) (o == null ? i / (float) (n - 1) : o.optDouble("p", i / (float) (n - 1)))));
                    cs[i] = o == null ? 0 : parseHexColor(o.optString("c", ""));
                }
                // 按位置排序（宿主 Shader 要求位置单调递增）
                for (int i = 1; i < n; i++) {
                    for (int j = i; j > 0 && ps[j] < ps[j - 1]; j--) {
                        float tp = ps[j]; ps[j] = ps[j - 1]; ps[j - 1] = tp;
                        int tc = cs[j]; cs[j] = cs[j - 1]; cs[j - 1] = tc;
                    }
                }
                for (int i = 0; i < n; i++) {
                    cs[i] = scaleColorAlpha(cs[i], keyGradAlphaPct);
                }
                keyGradStopColors = cs;
                keyGradStopPos = ps;
            } else {
                keyGradStopColors = null;
                keyGradStopPos = null;
            }

            // 按键文字色：下滑标签没单独配色时跟它走
            String textRaw = set == null ? null : set.optString("key_text", "");
            keyTextPaletteColor = parseHexColor(textRaw);

            String panelRaw = set == null ? null : set.optString("panel_bg", "");
            int panelParsed = parseHexColor(panelRaw);
            if (panelParsed != 0) {
                panelColor = panelParsed;
            }

            // 主题色：网页「强调色」写入 send_bg（旧字段名 accent 兜底）
            String accentRaw = set == null ? null : set.optString("send_bg", "");
            if (accentRaw == null || accentRaw.isEmpty()) {
                accentRaw = set == null ? null : set.optString("accent", "");
            }
            accentFromPalette = parseHexColor(accentRaw);
            accentHitCount.set(0);

            // logo 图标 / 圆底 / 功能键图标：无论「逐应用」还是「跟随系统深浅色」，
            // 都必须按当前选中的那一段（亮/暗/极暗）重新读一遍。
            // 之前这三项被写在 explicit 分支里，跟随时根本不读，
            // 结果一直沿用上一次逐应用模式留下的值：暗色段的 #14FFFFFF（8% 白）
            // 落到亮色面板上就等于「圆底没颜色」。
            logoFromPalette = false;
            circleFromPalette = false;
            fnIconFromPalette = false;
            int glyph = parseHexColor(set == null ? null : set.optString("logo_glyph", ""));
            if (glyph != 0) {
                logoIconColor = glyph;
                logoFromPalette = true;
            }
            int circle = parseHexColor(set == null ? null : set.optString("logo_circle", ""));
            if (circle != 0) {
                logoCircleColor = circle;
                circleFromPalette = true;
            }
            int fnIcon = parseHexColor(set == null ? null : set.optString("fn_icon", ""));
            if (fnIcon != 0) {
                fnIconColor = fnIcon;
                fnIconFromPalette = true;
            }

            // 逐应用模式：这一段自己的文字颜色，整份覆盖 live 里的全局值
            if (explicit) {
                keyTextColor = parseHexColor(set == null ? null : set.optString("key_text", ""));
                candTextColor = keyTextColor;
                // 主题色也以模式那一段为准（live 里的全局 accent 会让位）
                accentFromLive = 0;
            } else {
                keyTextColor = 0;
                candTextColor = 0;
            }

            // 面板顶部高光：透明度（%）+ 覆盖像素数
            int glowAlpha = set == null ? -1 : set.optInt("top_glow_alpha", -1);            if (glowAlpha >= 0) {
                topGlowAlpha = Math.min(100, Math.max(0, glowAlpha));
            }
            int glowPx = set == null ? -1 : set.optInt("top_glow_px", -1);
            if (glowPx >= 0) {
                topGlowHeightPx = Math.min(120, Math.max(0, glowPx));
            }
            float glowWidth = set == null ? -1f : (float) set.optDouble("top_glow_width", -1d);
            if (glowWidth >= 0f) {
                topGlowWidthPx = Math.min(16f, glowWidth);
            }

            // 下滑手势小字颜色（全选 / 剪切 / 复制 / 粘贴…）
            keyFnTextColor = parseHexColor(set == null ? null : set.optString("key_fn_text", ""));
            // 按键主字（大字）与右上角角标小字：亮/暗都生效
            String keyTextRaw = set == null ? null : set.optString("key_text", "");
            keyTextLiveColor = parseHexColor(keyTextRaw);
            keyTextLiveSet = isHexSpec(keyTextRaw);
            String smallRaw = set == null ? null : set.optString("key_small_text", "");
            keySmallTextColor = parseHexColor(smallRaw);
            keySmallTextSet = isHexSpec(smallRaw);

            // 发送键三态配色
            sendActiveBg = parseHexColor(set == null ? null : set.optString("send_active_bg", ""));
            sendActiveText = parseHexColor(set == null ? null : set.optString("send_active_text", ""));
            sendGreyBg = parseHexColor(set == null ? null : set.optString("send_grey_bg", ""));
            sendGreyText = parseHexColor(set == null ? null : set.optString("send_grey_text", ""));
            sendDisabledBg = parseHexColor(set == null ? null : set.optString("send_disabled_bg", ""));
            sendDisabledText = parseHexColor(set == null ? null : set.optString("send_disabled_text", ""));
            sendDisabledAlphaPct = set == null ? -1 : set.optInt("send_disabled_alpha", -1);
            sendMaskAlphaPct = set == null ? -1 : set.optInt("send_mask_alpha", -1);

            // 按键高光描边宽度（px，0 = 关闭）
            float rimPx = set == null ? -1f : (float) set.optDouble("key_rim_px", -1d);
            if (rimPx >= 0f) {
                keyRimWidthPx = Math.min(6f, rimPx);
            }
            // 描边透明度（%）
            int rimAlphaPct = set == null ? -1 : set.optInt("key_rim_alpha", -1);
            if (rimAlphaPct >= 0) {
                keyRimAlpha = Math.round(Math.min(100, rimAlphaPct) * 255f / 100f);
            }
            // 描边高斯模糊（dp → px）：模糊后按按键轮廓裁剪，不会糊到键外
            float rimBlurDp = set == null ? 0f : (float) set.optDouble("key_rim_blur", 0d);
            keyRimBlurPx = Math.max(0f, Math.min(16f, rimBlurDp)) * densityOf();

            // 高亮候选框：底色 + 圆角，独立一项调
            String candRaw = set == null ? null : set.optString("cand_bg", "");
            candBoxColor = parseHexColor(candRaw);
            int candRadiusDp = intFrom(root, set, "cand_radius_dp", -1);
            candBoxRadiusPx = candRadiusDp < 0
                    ? -1 : Math.round(Math.max(0, candRadiusDp) * densityOf());

            // 按键投影：四个属性都由网页控制，缺 key_shadow 视为不接管
            String shadowRaw = set == null ? null : set.optString("key_shadow", "");
            int shadowParsed = parseHexColor(shadowRaw);
            keyShadowEnabled = shadowParsed != 0;
            if (shadowParsed != 0) {
                keyShadowColor = shadowParsed;
            }
            keyShadowAlpha = set == null ? 20 : Math.min(100, Math.max(0, set.optInt("key_shadow_alpha", 20)));
            // 描边渐变：色标 + 方向角（网页「按键高光描边」下发）
            parseRimGradient(set);

            float shadowBlurDp = set == null ? 6f : (float) set.optDouble("key_shadow_blur_dp", 6f);
            float shadowDyDp = set == null ? 3f : (float) set.optDouble("key_shadow_dy_dp", 3f);
            keyShadowBlurPx = Math.max(0f, shadowBlurDp) * densityOf();
            keyShadowDyPx = shadowDyDp * densityOf();

            appearanceMtime = mtime;
            appearanceLength = length;
            probe("appearance key_fill=" + (raw == null || raw.isEmpty() ? "none" : raw)
                    + " -> 0x" + Integer.toHexString(keyFillColor)
                    + " key_mode=" + (keyMode.isEmpty() ? "solid" : keyMode)
                    + " grad=0x" + Integer.toHexString(keyGradC1)
                    + "/0x" + Integer.toHexString(keyGradC2)
                    + "@" + keyGradAngle
                    + " panel_bg=" + (panelRaw == null || panelRaw.isEmpty() ? "none" : panelRaw)
                    + " -> 0x" + Integer.toHexString(panelColor)
                    + " accent=" + (accentRaw == null || accentRaw.isEmpty() ? "none" : accentRaw)
                    + " -> 0x" + Integer.toHexString(accentFromPalette)
                    + " cand_bg=" + (candRaw == null || candRaw.isEmpty() ? "none" : candRaw)
                    + " -> 0x" + Integer.toHexString(candBoxColor)
                    + " cand_radius=" + candRadiusDp + "dp/" + candBoxRadiusPx + "px"
                    + " shadow=" + (keyShadowEnabled ? shadowRaw : "off")
                    + " a=" + keyShadowAlpha + "% blur=" + keyShadowBlurPx + "px dy=" + keyShadowDyPx + "px"
                    + " app=" + editorPkg + " mode=" + mode + " theme=" + themeId
                    + " text=0x" + Integer.toHexString(keyTextColor)
                    + " flatTop=" + panelTopFlat
                    + " kr=" + keyRadiusPx + "px pr=" + panelRadiusDp + "dp cr=" + candBoxRadiusPx + "px");
            return true;
        } catch (Throwable t) {
            probe("appearance refresh failed: " + t);
            return false;
        }
    }

    /** 读取设置页下发的逐应用模式表，返回是否读到了新内容 */
    private boolean refreshAppModes() {
        try {
            // 三处候选路径可能只有部分写入成功，取“最新修改的那一份”，
            // 否则内部目录里的旧文件会一直挡住后写入的镜像文件。
            java.io.File f = null;
            long newest = Long.MIN_VALUE;
            for (String path : APP_MODE_PATHS) {
                java.io.File candidate = new java.io.File(path);
                if (!candidate.isFile()) {
                    continue;
                }
                long t = candidate.lastModified();
                if (f == null || t > newest) {
                    f = candidate;
                    newest = t;
                }
            }
            if (f == null) {
                if (!appModes.isEmpty()) {
                    appModes = java.util.Collections.emptyMap();
                    appModesMtime = Long.MIN_VALUE;
                    appModesLength = -1;
                    return true;
                }
                return false;
            }
            long mtime = f.lastModified();
            long length = f.length();
            if (mtime == appModesMtime && length == appModesLength) {
                return false;
            }
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            org.json.JSONObject root = new org.json.JSONObject(sb.toString());
            java.util.HashMap<String, Integer> map = new java.util.HashMap<>();
            java.util.Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String k = it.next();
                int v = root.optInt(k, -1);
                if (v >= 0 && v <= 3) {
                    map.put(k, v);
                }
            }
            appModes = map;
            appModesMtime = mtime;
            appModesLength = length;
            probe("app modes loaded n=" + map.size() + " editor=" + editorPkg
                    + " -> mode=" + resolveAppMode());
            return true;
        } catch (Throwable t) {
            probe("app modes read failed: " + t);
            return false;
        }
    }

    /** 当前输入框所属应用命中的模式；-1 = 没勾选 */
    private int resolveAppMode() {
        String pkg = editorPkg;
        if (pkg == null || pkg.isEmpty()) {
            return -1;
        }
        Integer m = appModes.get(pkg);
        return m == null ? -1 : m;
    }

    /**
     * 模式 → 调色板段。
     *
     * 规则：
     *  - 勾了极暗（3）的应用永远是极暗，跟系统深浅色无关；
     *  - 系统切到暗色模式时，**不再区分亮色/暗色**，所有应用统一走暗色；
     *  - 系统亮色时，才按每个应用自己的设置分亮色（圆角/直角）、深色。
     */
    private String themeForMode(int mode) {
        if (mode == 3) {
            return "ultra";
        }
        if (nightMode) {
            return "dark";
        }
        if (mode == 2) {
            return "dark";
        }
        return "light";
    }

    /** light 打底、指定主题段覆盖（段里缺失的键沿用 light） */
    /**
     * 容器型伪键：表情页的 s11_title_board / s11_content_recyclerview / s11_type_recyclerview、
     * 符号页的 s5_content_pager / s5_type_recyclerview 之类。它们不是按键，只是一块占位容器，
     * 一旦被涂成键面颜色就是「凭空多出来的一大块」。
     * 九键左侧的 S1_recycleview 例外——那是标点竖栏，看着是一整条但真的是按键。
     */
    private static boolean isPseudoContainer(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        String low = id.toLowerCase();
        if (low.contains("s1_recycleview")) {
            return false;
        }
        return low.contains("recyclerview") || low.contains("recycle")
                || low.contains("content_pager") || low.contains("_board")
                || low.contains("title_board") || low.contains("container")
                || low.contains("type_pager");
    }

    /**
     * 表情页那颗删除键：只留删除符号，白色键面 / 描边 / 投影全部去掉。
     * 靠当前键盘类名区分（表情页是 S11EmojiKeyboard），字母页 / 符号页的 delete 照旧。
     */
    private static boolean isEmojiDeleteKey(String id) {
        if (id == null || !"delete".equals(id)) {
            return false;
        }
        String kb = currentKbSimpleName;
        return kb != null && kb.toLowerCase().contains("emoji");
    }

    private static org.json.JSONObject paletteSet(org.json.JSONObject root, String theme) {
        org.json.JSONObject merged = new org.json.JSONObject();
        try {
            org.json.JSONObject light = root.optJSONObject("light");
            org.json.JSONObject seg = root.optJSONObject(theme);
            java.util.Iterator<String> it;
            if (light != null) {
                it = light.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    merged.put(k, light.opt(k));
                }
            }
            if (seg != null && seg != light) {
                it = seg.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    merged.put(k, seg.opt(k));
                }
            }
            if (light == null && seg != null) {
                it = seg.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    merged.put(k, seg.opt(k));
                }
            }
        } catch (Throwable ignore) {
        }
        return merged;
    }

    /**
     * 读「全局项」整数：先看调色板根节点（网页把形状参数写在根上，一张值管三个主题），
     * 没有再退回当前主题段。
     */
    /** data:image/png;base64,xxx → Bitmap；解析失败返回 null（退回原生图标） */
    private static android.graphics.Bitmap decodeImageData(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        try {
            byte[] bytes = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intFrom(org.json.JSONObject root, org.json.JSONObject set,
                               String key, int def) {
        try {
            if (root != null && root.has(key)) {
                return (int) Math.round(root.optDouble(key, def));
            }
        } catch (Throwable ignore) {
        }
        try {
            if (set != null && set.has(key)) {
                return (int) Math.round(set.optDouble(key, def));
            }
        } catch (Throwable ignore) {
        }
        return def;
    }

    private static boolean isHexSpec(String raw) {
        if (raw == null) {
            return false;
        }
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6 && hex.length() != 8) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            char ch = hex.charAt(i);
            boolean ok = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 把颜色的 alpha 按 0–100 的百分比缩放，RGB 不变 */
    private static int scaleColorAlpha(int color, int pct) {
        if (color == 0) {
            return color;
        }
        int a = android.graphics.Color.alpha(color);
        int na = Math.round(a * Math.max(0, Math.min(100, pct)) / 100f);
        return (color & 0x00FFFFFF) | (na << 24);
    }

    private static int parseHexColor(String raw) {
        if (raw == null) {
            return 0;
        }
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() == 6) {
            hex = "FF" + hex;
        }
        if (hex.length() != 8) {
            return 0;
        }
        try {
            return (int) Long.parseLong(hex, 16);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static String keyRectOf(Object b) {
        try {
            java.lang.reflect.Method r = b.getClass().getMethod("F");
            Object rect = r.invoke(b);
            return String.valueOf(rect);
        } catch (Throwable ignore) {
            return "?";
        }
    }

    private void captureAllKeys(Object keyboard, List<Object> buttons,
                                Method keyId, Method keyRect, Method getActionButton) {
        if (keyboard == null || keyId == null) {
            return;
        }
        if (keysCaptured.containsKey(keyboard)) {
            return;
        }
        keysCaptured.put(keyboard, Boolean.TRUE);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        int skippedSend = 0;
        StringBuilder skippedIds = new StringBuilder();
        String actionId = null;
        try {
            if (getActionButton != null) {
                Object action = getActionButton.invoke(keyboard);
                if (action != null) {
                    actionId = String.valueOf(keyId.invoke(action));
                    if (isSendKeyId(actionId)) {
                        actionId = null;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        String kbCls = keyboard.getClass().getSimpleName();
        for (Object b : buttons) {
            try {
                String id = String.valueOf(keyId.invoke(b));
                if (isSendKeyId(id) || isCornerReturnKey(kbCls, id, keyRect, b)) {
                    skippedSend++;
                    if (skippedIds.length() < 200) {
                        skippedIds.append(id).append(isCornerReturnKey(kbCls, id, keyRect, b)
                                ? "@corner" : "").append(keyRectOf(b)).append(' ');
                    }
                    continue;
                }
                count++;
                if (sb.length() < 1500) {
                    sb.append(id).append(keyRectOf(b)).append(' ');
                }
            } catch (Throwable ignore) {
            }
        }
        String msg = "captured keys=" + count
                + " (skipped=" + skippedSend + ": " + skippedIds + ")"
                + (actionId == null ? "" : " actionPending=" + actionId)
                + " keyboard=" + keyboard.getClass().getSimpleName()
                + " : " + sb;
        probe(msg);
        log(Log.INFO, TAG, msg);
    }

    /** 一次把该键盘的全部键列出来（id + 命中矩形），用于确认「所有按键」都被覆盖 */
    private void dumpKeys(Field keyMap, Object keyboard, Method keyId, Method keyRect) {
        try {
            Object raw = keyMap.get(keyboard);
            if (!(raw instanceof Map)) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Object button : ((Map<?, ?>) raw).values()) {
                if (button == null) {
                    continue;
                }
                String id = String.valueOf(keyId.invoke(button));
                if (isSendKeyId(id) || isCornerReturnKey(
                        keyboard.getClass().getSimpleName(), id, keyRect, button)) {
                    continue;
                }
                count++;
                if (sb.length() < 1200) {
                    sb.append(id).append(keyRect.invoke(button)).append(' ');
                }
            }
            log(Log.INFO, TAG, "keyboard " + keyboard.getClass().getSimpleName()
                    + " keys=" + count + " : " + sb);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "dump keys failed", t);
        }
    }

    /** imeButtonMap 在 dex 里被混淆成 P；拿不到就退化成「第一个非静态 Map 字段」 */
    private Field findKeyMapField(Class<?> oCls) {
        for (String name : new String[]{"P", "imeButtonMap"}) {
            try {
                Field f = oCls.getDeclaredField(name);
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (Throwable ignore) {
            }
        }
        for (Field f : oCls.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType())
                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    /** 用 Application 的 attachBaseContext 作为锚点，轮询 ActivityThread 的服务表拿到 IME 实例 */
    /* ============================================================
       主题色：#23C891 全局捕捉
       ============================================================
       APK 里这个品牌绿一共 71 处：
         · res/values/colors.xml 16 项（ime_color_14_* 五档 alpha + hd/he/hf + l_/la/lb）
         · res/drawable 52 个（vector fillColor / gradient 端点）
         · 代码 3 处（selfdraw.j 发送键 #FF23C891、guide.i 引导气泡 #1F/#3323C891、
           voice.r 音波 #23C891）
       做法：在 :hld 进程里按「RGB 精确匹配」改颜色 int，alpha 原样保留，
       于是 5% / 10% / 20% / 30% / 50% / 100% 六档透明度会一起跟着主题色走。
    */

    private static final int HOST_ACCENT_RGB = 0x23C891;
    /**
     * 候选栏首个高亮候选词的字色：亮色 #16AC66 / 深色 #00E38D（ime_color_19），
     * 它不走品牌绿，跟着主题色一起走。
     */
    private static final int[] HOST_ACCENT_RGBS = {
            HOST_ACCENT_RGB, 0x16AC66, 0x00E38D,
    };

    private int accent(int color) {
        int accent = accentFromLive != 0 ? accentFromLive : accentFromPalette;
        if (accent == 0) {
            return color;
        }
        int rgb = color & 0x00FFFFFF;
        boolean host = false;
        for (int h : HOST_ACCENT_RGBS) {
            if (rgb == h) {
                host = true;
                break;
            }
        }
        if (!host) {
            return color;
        }
        int mapped = (color & 0xFF000000) | (accent & 0x00FFFFFF);
        if (mapped != color && accentHitCount.getAndIncrement() < 5) {
            probe("accent 0x" + Integer.toHexString(color)
                    + " -> 0x" + Integer.toHexString(mapped));
        }
        return mapped;
    }

    private static java.lang.reflect.Method cslGetStatesMethod;

    private android.content.res.ColorStateList accentStateList(android.content.res.ColorStateList src) {
        if (src == null || (accentFromLive == 0 && accentFromPalette == 0)) {
            return src;
        }
        try {
            // getStates() 是 @hide（getColorForState / 构造器是公开 API），只能反射拿
            if (cslGetStatesMethod == null) {
                java.lang.reflect.Method m = android.content.res.ColorStateList.class
                        .getMethod("getStates");
                m.setAccessible(true);
                cslGetStatesMethod = m;
            }
            int[][] states = (int[][]) cslGetStatesMethod.invoke(src);
            if (states == null || states.length == 0) {
                return src;
            }
            int[] colors = new int[states.length];
            boolean changed = false;
            for (int i = 0; i < states.length; i++) {
                int old = src.getColorForState(states[i], 0);
                colors[i] = accent(old);
                if (colors[i] != old) {
                    changed = true;
                }
            }
            if (!changed) {
                return src;
            }
            return new android.content.res.ColorStateList(states, colors);
        } catch (Throwable t) {
            if (accentHitCount.getAndIncrement() < 3) {
                probe("accent state list rebuild failed: " + t);
            }
            return src;
        }
    }

    /** 第 index 个入参是颜色 int：替换后继续调用 */
    private XposedInterface.Hooker accentArgHook(final int index) {
        return chain -> {
            Object raw = chain.getArg(index);
            if (!(raw instanceof Integer)) {
                return chain.proceed();
            }
            int old = (Integer) raw;
            int mapped = accent(old);
            if (mapped == old) {
                return chain.proceed();
            }
            Object[] args = chain.getArgs().toArray();
            args[index] = mapped;
            return chain.proceed(args);
        };
    }

    /** 第 index 个入参是 ColorStateList：重建后继续调用 */
    private XposedInterface.Hooker accentStateListHook(final int index) {
        return chain -> {
            Object raw = chain.getArg(index);
            if (!(raw instanceof android.content.res.ColorStateList)) {
                return chain.proceed();
            }
            android.content.res.ColorStateList mapped =
                    accentStateList((android.content.res.ColorStateList) raw);
            if (mapped == raw) {
                return chain.proceed();
            }
            Object[] args = chain.getArgs().toArray();
            args[index] = mapped;
            return chain.proceed(args);
        };
    }

    /** 返回值是颜色：原样调用后替换返回值（TypedArray.getColor / Color.parseColor 等） */
    private XposedInterface.Hooker accentReturnHook() {
        return chain -> {
            Object result = chain.proceed();
            if (!(result instanceof Integer)) {
                return result;
            }
            int old = (Integer) result;
            int mapped = accent(old);
            return mapped == old ? result : mapped;
        };
    }

    private void hookAccentExecutable(Executable ex, String id, XposedInterface.Hooker hooker) {
        try {
            install(ex, id, hooker);
        } catch (Throwable t) {
            probe("accent hook " + id + " failed: " + t);
        }
    }

    private void hookAccentMethod(String owner, String name, Class<?>[] sig, String id,
                                  XposedInterface.Hooker hooker) {
        try {
            Class<?> cls = Class.forName(owner);
            hookAccentExecutable(cls.getDeclaredMethod(name, sig), id, hooker);
        } catch (Throwable t) {
            probe("accent hook " + id + " failed: " + t);
        }
    }

    private void installAccentHooks() {
        Class<?>[] intSig = new Class<?>[]{int.class};

        hookAccentMethod("android.graphics.Paint", "setColor", intSig,
                "wetype_dark_accent_paint", accentArgHook(0));
        // Paint.setARGB(a, r, g, b)
        hookAccentMethod("android.graphics.Paint", "setARGB",
                new Class<?>[]{int.class, int.class, int.class, int.class},
                "wetype_dark_accent_argb", chain -> {
                    int a = (Integer) chain.getArg(0);
                    int r = (Integer) chain.getArg(1);
                    int g = (Integer) chain.getArg(2);
                    int b = (Integer) chain.getArg(3);
                    int color = ((a & 0xFF) << 24) | ((r & 0xFF) << 16)
                            | ((g & 0xFF) << 8) | (b & 0xFF);
                    int mapped = accent(color);
                    if (mapped == color) {
                        return chain.proceed();
                    }
                    return chain.proceed(new Object[]{
                            (mapped >>> 24) & 0xFF, (mapped >> 16) & 0xFF,
                            (mapped >> 8) & 0xFF, mapped & 0xFF});
                });

        hookAccentMethod("android.graphics.Canvas", "drawColor", intSig,
                "wetype_dark_accent_canvas", accentArgHook(0));
        hookAccentMethod("android.graphics.Canvas", "drawColor",
                new Class<?>[]{int.class, android.graphics.PorterDuff.Mode.class},
                "wetype_dark_accent_canvas_mode", accentArgHook(0));

        // ColorFilter：ImageView.setColorFilter / Drawable.setColorFilter 都走这两个构造器
        try {
            Class<?> pd = Class.forName("android.graphics.PorterDuffColorFilter");
            hookAccentExecutable(pd.getDeclaredConstructor(
                    int.class, android.graphics.PorterDuff.Mode.class),
                    "wetype_dark_accent_pd_filter", accentArgHook(0));
        } catch (Throwable t) {
            probe("accent hook pd filter failed: " + t);
        }
        try {
            Class<?> bm = Class.forName("android.graphics.BlendMode");
            Class<?> bf = Class.forName("android.graphics.BlendModeColorFilter");
            hookAccentExecutable(bf.getDeclaredConstructor(int.class, bm),
                    "wetype_dark_accent_blend_filter", accentArgHook(0));
        } catch (Throwable t) {
            probe("accent hook blend filter failed: " + t);
        }

        hookAccentMethod("android.graphics.drawable.GradientDrawable", "setColor", intSig,
                "wetype_dark_accent_gradient", accentArgHook(0));
        hookAccentMethod("android.graphics.drawable.GradientDrawable", "setColor",
                new Class<?>[]{android.content.res.ColorStateList.class},
                "wetype_dark_accent_gradient_csl", accentStateListHook(0));
        hookAccentMethod("android.graphics.drawable.Drawable", "setTint", intSig,
                "wetype_dark_accent_tint", accentArgHook(0));
        hookAccentMethod("android.graphics.drawable.Drawable", "setTintList",
                new Class<?>[]{android.content.res.ColorStateList.class},
                "wetype_dark_accent_tint_list", accentStateListHook(0));

        hookAccentMethod("android.content.res.ColorStateList", "valueOf", intSig,
                "wetype_dark_accent_csl_valueof", accentReturnHook());
        try {
            Class<?> csl = Class.forName("android.content.res.ColorStateList");
            hookAccentExecutable(csl.getDeclaredMethod("of", int.class),
                    "wetype_dark_accent_csl_of", accentReturnHook());
        } catch (Throwable ignore) {
        }

        hookAccentMethod("android.widget.TextView", "setTextColor", intSig,
                "wetype_dark_accent_text", accentArgHook(0));
        hookAccentMethod("android.widget.TextView", "setTextColor",
                new Class<?>[]{android.content.res.ColorStateList.class},
                "wetype_dark_accent_text_csl", accentStateListHook(0));

        // XML 取色：colors.xml 那 16 项、vector 的 fillColor 都从这里过
        hookAccentMethod("android.content.res.Resources", "getColor", intSig,
                "wetype_dark_accent_res_color", accentReturnHook());
        hookAccentMethod("android.content.res.Resources", "getColor",
                new Class<?>[]{int.class, android.content.res.Resources.Theme.class},
                "wetype_dark_accent_res_color_theme", accentReturnHook());
        hookAccentMethod("android.content.res.TypedArray", "getColor",
                new Class<?>[]{int.class, int.class},
                "wetype_dark_accent_typed_color", accentReturnHook());

        // 代码里的字面量 #FF23C891 / #1F23C891 / #3323C891
        hookAccentMethod("android.graphics.Color", "parseColor",
                new Class<?>[]{String.class},
                "wetype_dark_accent_parse", accentReturnHook());

        try {
            Class<?> cd = Class.forName("android.graphics.drawable.ColorDrawable");
            hookAccentExecutable(cd.getDeclaredConstructor(int.class),
                    "wetype_dark_accent_colordrawable", accentArgHook(0));
        } catch (Throwable t) {
            probe("accent hook colordrawable failed: " + t);
        }

        probe("accent hooks installed (host accent #23C891, 71 处)");
    }

    /**
     * 宿主侧扩展：下滑手势、屏蔽自动更新、按键文字位置。
     * 都在 :hld 里装，跟键盘 hook 用同一个 classloader。
     */
    private void installHostExtras(ClassLoader cl) {
        try {
            KeyTextHost.INSTANCE.setIface(this);
            GestureHook.INSTANCE.install(cl, this);
            UpdateBlock.INSTANCE.install(this, cl);
            probe("host extras installed: gesture + update block + key text");
        } catch (Throwable t) {
            probe("host extras failed: " + t);
        }
    }

    /** 每帧刷一次：按键文字位置的分组依据、密度、九键标点列平移 */
    private void refreshHostExtras(Object keyboard) {
        try {
            if (keyboard instanceof View) {
                float density = ((View) keyboard).getResources().getDisplayMetrics().density;
                KeyTextHost.INSTANCE.setDensity(density);
                boolean t9 = keyboard.getClass().getSimpleName().contains("T9");
                KeyTextHost.INSTANCE.setKeyboardT9(t9);
                if (t9) {
                    KeyTextHost.INSTANCE.applyPunctColumnOffset((View) keyboard);
                }
            }
            KeyTextHost.INSTANCE.load(HostConfig.INSTANCE.keyTextTuningJson());
        } catch (Throwable ignore) {
        }
    }

    private void notifyImeStarted(Object svc) {
        if (svc instanceof android.inputmethodservice.InputMethodService) {
            try {
                GestureHook.INSTANCE.onInputStart(
                        (android.inputmethodservice.InputMethodService) svc);
            } catch (Throwable t) {
                probe("gesture onInputStart failed: " + t);
            }
        }
    }

    private void installAttachWatcher() throws Throwable {
        Method attach = android.content.ContextWrapper.class
                .getDeclaredMethod("attachBaseContext", Context.class);
        install(attach, "wetype_dark_attach", chain -> {
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            if (self != null && "com.tencent.wetype.HldApplication".equals(self.getClass().getName())) {
                log(Log.INFO, TAG, "HldApplication attached, start polling IME service");
                startPolling();
            }
            return result;
        });

        // Service.onCreate 一定会在 IME 服务创建时回调（WxHldService 里调用了 super.onCreate）
        Method serviceOnCreate = android.app.Service.class.getDeclaredMethod("onCreate");
        install(serviceOnCreate, "wetype_dark_service_oncreate", chain -> {
            Object result = chain.proceed();
            Object self = chain.getThisObject();
            if (self != null && IME_CLASS.equals(self.getClass().getName())) {
                onImeInstance(self, "Service.onCreate");
            }
            return result;
        });
    }

    private void startPolling() {
        try {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            Runnable[] task = new Runnable[1];
            final int[] tries = {0};
            task[0] = new Runnable() {
                @Override
                public void run() {
                    try {
                        Object svc = findImeService();
                        if (svc != null) {
                            onImeInstance(svc, "poll");
                            if (imeHooked) {
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "poll failed", t);
                        return;
                    }
                    if (++tries[0] < 1200) {
                        handler.postDelayed(task[0], tries[0] < 200 ? 50 : 500);
                    }
                }
            };
            handler.postDelayed(task[0], 30);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "start polling failed", t);
        }
    }

    private Object findImeService() throws Throwable {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method current = at.getDeclaredMethod("currentActivityThread");
        current.setAccessible(true);
        Object thread = current.invoke(null);
        if (thread == null) {
            return null;
        }
        Field f = at.getDeclaredField("mServices");
        f.setAccessible(true);
        Object map = f.get(thread);
        if (!(map instanceof java.util.Map)) {
            return null;
        }
        for (Object v : ((java.util.Map<?, ?>) map).values()) {
            if (v != null && IME_CLASS.equals(v.getClass().getName())) {
                return v;
            }
        }
        return null;
    }

    private void onImeInstance(Object svc, String from) {
        if (svc == null || !IME_CLASS.equals(svc.getClass().getName())) {
            return;
        }
        log(Log.INFO, TAG, "IME instance via " + from + " cl=" + svc.getClass().getClassLoader());
        if (svc instanceof android.inputmethodservice.InputMethodService) {
            imeServiceRef = new WeakReference<>(svc);
        }
        if (!imeHooked) {
            imeHooked = true;
            try {
                hookRealIme(svc.getClass());
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook real IME failed", t);
            }
        }
        View panel = readPanel(svc.getClass(), svc);
        if (panel != null) {
            panelRef = new WeakReference<>(panel);
            applyToPanel(panel, from);
        }
    }

    private void hookRealIme(Class<?> service) throws Throwable {
        log(Log.INFO, TAG, "real IME class=" + service.getName()
                + " cl=" + service.getClassLoader());

        Method onCreate = service.getDeclaredMethod("onCreate");
        install(onCreate, "wetype_dark_on_create", chain -> {
            Object result = chain.proceed();
            try {
                View panel = readPanel(service, chain.getThisObject());
                if (panel != null) {
                    panelRef = new WeakReference<>(panel);
                    applyToPanel(panel, "onCreate");
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "onCreate apply failed", t);
            }
            return result;
        });

        Method onCreateInputView = service.getDeclaredMethod("onCreateInputView");
        install(onCreateInputView, "wetype_dark_on_create_input_view", chain -> {
            Object result = chain.proceed();
            if (result instanceof View) {
                View panel = (View) result;
                panelRef = new WeakReference<>(panel);
                applyToPanel(panel, "onCreateInputView");
            }
            return result;
        });

        // q1() 会给 currentInputView 重设底部填色背景（C2405f），必须在其之后重铺我们的模糊层
        Method q1 = service.getDeclaredMethod("q1");
        install(q1, "wetype_dark_q1", chain -> {
            Object result = chain.proceed();
            View panel = panelRef.get();
            if (panel != null) {
                applyToPanel(panel, "q1");
            }
            return result;
        });

        Method l2 = service.getDeclaredMethod("L2", View.class);
        install(l2, "wetype_dark_l2", chain -> {
            Object result = chain.proceed();
            View panel = panelRef.get();
            if (panel != null) {
                applyToPanel(panel, "L2");
            }
            return result;
        });

        Method onWindowShown = service.getDeclaredMethod("onWindowShown");
        install(onWindowShown, "wetype_dark_on_window_shown", chain -> {
            Object result = chain.proceed();
            ensurePanel(service, chain.getThisObject(), "onWindowShown");
            notifyImeStarted(chain.getThisObject());
            return result;
        });

        Method onStartInputView = service.getDeclaredMethod("onStartInputView",
                android.view.inputmethod.EditorInfo.class, boolean.class);
        install(onStartInputView, "wetype_dark_on_start_input_view", chain -> {
            // 先记下这次是给哪个应用打字，逐应用模式要靠它选主题
            Object ei = chain.getArg(0);
            if (ei instanceof android.view.inputmethod.EditorInfo) {
                String pkg = ((android.view.inputmethod.EditorInfo) ei).packageName;
                editorPkg = pkg == null ? "" : pkg;
            }
            Object result = chain.proceed();
            ensurePanel(service, chain.getThisObject(), "onStartInputView");
            // 键盘面板往往先建出来再收到 EditorInfo，包名刚记下就重跑一遍外观，
            // 否则逐应用模式会先按空包名铺成亮色、之后再也不刷新
            View panel = panelRef.get();
            if (panel != null) {
                applyToPanel(panel, "onStartInputView");
            }
            return result;
        });

        log(Log.INFO, TAG, "real IME hooked, handles=" + handles.size());
    }

    private void ensurePanel(Class<?> service, Object instance, String from) {
        View panel = panelRef.get();
        if (panel == null) {
            panel = readPanel(service, instance);
            if (panel != null) {
                panelRef = new WeakReference<>(panel);
            }
        }
        if (panel != null) {
            applyToPanel(panel, from);
        }
    }

    private View readPanel(Class<?> service, Object instance) {
        try {
            Field f = service.getDeclaredField("currentInputView");
            f.setAccessible(true);
            return (View) f.get(instance);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "read currentInputView failed", t);
            return null;
        }
    }

    private String lastNightSig;

    /**
     * 系统是不是暗色模式。
     *
     * 不能只看面板自己的 Configuration：微信输入法会把输入法窗口的 uiMode 强行设成浅色
     * （它自己那套「跟随系统 / 浅色 / 深色」开关），面板配置永远报 light，于是系统暗色下
     * 键盘还是一片白。这里按「面板配置 → 系统资源」逐级兜底，取到真值后再用于主题判定。
     */
    private boolean isSystemNight(android.content.Context ctx,
                                  android.content.res.Configuration cfg) {
        boolean p = (cfg.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean s = false;
        try {
            android.content.res.Configuration sc =
                    android.content.res.Resources.getSystem().getConfiguration();
            s = (sc.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignore) {
        }
        int dm = -1;
        int un = -1;
        try {
            dm = android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), "dark_mode_state", -1);
        } catch (Throwable ignore) {
        }
        try {
            un = android.provider.Settings.Secure.getInt(
                    ctx.getContentResolver(), "ui_night_mode", -1);
        } catch (Throwable ignore) {
        }
        // ColorOS 的「设置-显示-暗色模式」最终落在这个 settings 上：1=暗色
        boolean su = false;
        boolean sys = false;
        try {
            android.content.res.Resources r = ctx.getPackageManager()
                    .getResourcesForApplication("com.android.systemui");
            su = (r.getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignore) {
        }
        try {
            android.content.res.Resources r = ctx.getPackageManager()
                    .getResourcesForApplication("android");
            sys = (r.getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignore) {
        }
        boolean dark = p || s || su || sys;
        String sig = "night panel=" + p + " res=" + s + " su=" + su + " andr=" + sys
                + " g.dark=" + dm + " s.night=" + un + " -> " + dark;
        if (!sig.equals(lastNightSig)) {
            lastNightSig = sig;
            probe(sig);
        }
        return dark;
    }

    private void applyToPanel(View panel, String from) {
        if (panel != null) {
            cfgContext = panel.getContext();
            lastPanelRef = new java.lang.ref.WeakReference<>(panel);
            ensureReloadReceiver(cfgContext);
        }
        if (panel == null) {
            return;
        }
        try {
            refreshLiveConfig();
            try {
                nightMode = isSystemNight(panel.getContext(),
                        panel.getResources().getConfiguration());
            } catch (Throwable ignore) {
            }
            if (editorPkg.isEmpty() && activeAppMode >= 0 && lastKnownMode >= 0) {
                activeAppMode = lastKnownMode;
            } else if (!editorPkg.isEmpty() && activeAppMode >= 0) {
                lastKnownMode = activeAppMode;
            }
            boolean appearanceChanged = refreshAppearance();
            // 逐应用模式选的是「深色 / 极暗」时，把微信输入法自己的皮肤也切成暗色皮肤（SKIN_DARK），
            // 这样工具栏、候选条这些我们没逐个上色的部件会走宿主原生暗色资源，而不是只在浅色皮肤上刷颜色。
            syncHostSkin(panel);
            if (appearanceChanged) {
                // 按键底色 / 圆角是在 draw 里改字段后画完还原的，外观文件一变就让键盘重画一帧
                View redraw = findKeyboardView(panel, 0);
                if (redraw != null) {
                    redraw.invalidate();
                }
                // 逐应用模式会换候选词文字色，候选条也要重画
                panel.invalidate();
            }

            float density = panel.getResources().getDisplayMetrics().density;
            // 亮色直角：只压面板顶部两个圆角，按键圆角照旧
            float radius = panelTopFlat ? 0f : panelRadiusDp * density;

            int screenWidth = panel.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = panel.getResources().getDisplayMetrics().heightPixels;
            boolean fullWidth = panel.getWidth() >= screenWidth - Math.round(2f * density);
            int panelHeight = panel.getHeight();
            boolean fullHeight = panelHeight > 0
                    && panelHeight < screenHeight - Math.round(4f * density);

            // 悬浮判据不能只看「没占满宽」：横屏非悬浮键盘本身就只有 2631 宽（屏 2772），
            // 以前一律当成悬浮，于是面板 alpha 归零 → 横屏键盘没有背景。
            // 悬浮键盘宽度约 90% 屏宽，横屏键盘约 95%，用 93% 分界。
            boolean floating = panel.getWidth() > 0
                    && panel.getWidth() <= Math.round(screenWidth * 0.93f);
            installLayerBackgroundGuard();
            ensureLayerWatchers(panel);
            layersDirty.set(true);

            // 面板自己裁圆角（悬浮键盘不裁）；只影响面板这一层，子级布局不动
            if (!floating) {
                ensureOutline(panel, radius);
            } else if (panel.getWidth() > 0) {
                clearOutline(panel);
            }

            if (dumpRequested && panel.getWidth() > 0 && panel.getHeight() > 0) {
                dumpRequested = false;
                StringBuilder tree = new StringBuilder("VIEW TREE\n");
                walk(panel, 0, tree, 0);
                probe(tree.toString());
                log(Log.INFO, TAG, "view tree dumped");
            }
            // 悬浮/分体键盘的判据就是「宽度没占满屏」：横屏全键盘也是不满高，但它是满宽的

            if (floating && !floatingDumped) {
                floatingDumped = true;
                dumpRequested = true;
                probe("floating keyboard detected " + panel.getWidth() + "x" + panel.getHeight());
            }
            applyPanelLook(panel, fullWidth, fullHeight, radius, floating);
            startWatchdog();

            ensureKeyboardHooks(panel);
            if (panel.getWidth() > 0 && !fnScanDone.containsKey(panel)) {
                fnScanDone.put(panel, Boolean.TRUE);
                final View fnPanel = panel;
                panel.postDelayed(() -> captureFunctionKeys(fnPanel), 700);
                panel.postDelayed(() -> captureFunctionKeys(fnPanel), 1600);
                panel.postDelayed(() -> captureFunctionKeys(fnPanel), 2600);
            }

            if (DEBUG && !"watchdog".equals(from)) {
                log(Log.INFO, TAG, from + " panel=" + panel.getClass().getName()
                        + " size=" + panel.getWidth() + "x" + panel.getHeight()
                        + " blur=" + (blurDrawable != null)
                        + " bg=" + (panel.getBackground() == null ? "null"
                        : panel.getBackground().getClass().getSimpleName()));
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "apply panel background failed", t);
        }
    }

    /**
     * 给面板铺「模糊 + 半透明色层」。
     *
     * 模糊与色层只挂在 currentInputView 这一层自己的 background 上，
     * 再让面板里那几个「底色容器」（候选栏 / 键盘区 / 上下延伸 / 底部填色）
     * 把自己的实色背景让出来，使这一层透出来。其余组件一律不动：
     *  - 不递归清背景，按键、工具栏、候选词、浮层保持原样；
     *  - 不改窗口、导航栏、其它窗口级属性；
     *  - 面板被量成全屏（手写全屏/横屏）时只是不铺模糊，绝不改挂到别的 View 上。
     */
    private void applyPanelLook(View panel, boolean fullWidth, boolean fullHeight,
                                float radius, boolean floating) {
        View blurHost = panel;

        Drawable bg = blurHost.getBackground();
        boolean ours = bg instanceof TopRoundDrawable
                && ((TopRoundDrawable) bg).getInner() instanceof PanelLayer;

        Object vri = viewRootImpl(blurHost);
        boolean sameWindow = vri != null && vri == blurRoot;
        boolean frosted = HostConfig.INSTANCE.frostedEnabled();
        if (!frosted && HostConfig.INSTANCE.isHyperOs()) {
            clearMiuiPanelBlur(blurHost);
        }
        int color = panelColor;
        if (floating) {
            // 悬浮键盘：仍用我们这套配色，但整体 alpha 归零、取消模糊，
            // 只让按键自己画。以前复用面板半透明色，悬浮时又会露出宿主原生底色（绿色皮肤）。
            color = 0x00000000;
        } else if (panelTopFlat || !frosted) {
            // 亮色直角 / 关闭背景模糊：面板 100% 不透明
            color = 0xFF000000 | (color & 0xFFFFFF);
        }
        int blurDp = blurRadiusDp;
        boolean colorChanged = color != appliedColor;
        boolean radiusChanged = blurDp != appliedBlurDp;
        boolean frostedChanged = frosted != appliedFrosted;
        // 面板圆角改了也要重建：TopRoundDrawable / 模糊层都按旧圆角裁过
        boolean cornerChanged = Math.abs(radius - appliedPanelRadiusPx) > 0.5f;

        if (!(ours && sameWindow) || colorChanged || radiusChanged || cornerChanged
                || frostedChanged) {
            Drawable blur = null;
            // 亮色直角：去掉模糊层，只留实色
            if (!floating && fullHeight && !panelTopFlat && frosted) {
                if (sameWindow && blurDrawable != null && !radiusChanged && !cornerChanged) {
                    blur = blurDrawable;
                } else {
                    blur = createPanelBlurDrawable(blurHost, radius, vri);
                    if (blur != null) {
                        blurRoot = vri;
                        blurDrawable = blur;
                    } else {
                        blurRoot = null;
                        blurDrawable = null;
                    }
                }
            }
            Drawable tint = new ColorDrawable(color);
            // 顶层再加一条「顶部高光」：纯白 → 透明，只画面板顶部 N px
            TopGlowDrawable glow = new TopGlowDrawable();
            appliedGlowAlpha = -1;
            appliedGlowPx = -1;
            applyTopGlow(glow, radius);
            Drawable[] layers;
            if (floating) {
                layers = new Drawable[]{tint};
            } else if (blur != null) {
                layers = new Drawable[]{blur, tint, glow};
            } else {
                layers = new Drawable[]{tint, glow};
            }
            Drawable content = new PanelLayer(layers, blur);
            plantBackground(blurHost, new TopRoundDrawable(content, radius));
            appliedColor = color;
            appliedBlurDp = blurDp;
            appliedFrosted = frosted;
            appliedPanelRadiusPx = radius;
            log(Log.INFO, TAG, "panel bg attached host=" + blurHost.getClass().getSimpleName()
                    + " size=" + blurHost.getWidth() + "x" + blurHost.getHeight()
                    + " color=" + Integer.toHexString(color) + " blurDp=" + blurDp
                    + " blur=" + (blur != null));
        } else {
            ((TopRoundDrawable) bg).setRadius(radius);
        }

        // 高光参数（透明度 / 像素数）改了就地更新，不重建整条背景
        updateTopGlow(bg, radius);

        // 只让「背景层」自己的实色让位，露出面板这一层；
        // 不递归、不碰按键 / 工具栏 / 候选词 / 浮层这些真正的组件。
        releaseBackgroundLayers(panel, fullWidth, floating);

        // 左上角 logo（图标 + 所在圆底）的配色，来自调色台
        applyLogoColors(panel);
        applyFnKeyColors(panel);
        applySymbolStripColor(panel);
        applyNavigationBarIcons(panel);
        applyCandidateShift(panel);

        // 切换应用时输入法窗口是「复用」的，宿主不会自己重画：
        // 底栏那两颗 KeyButtonView 的 mIconPaint 是每帧 draw 时才被我们改写的，
        // 窗口不重画它就一直停在上一套颜色（表现就是暗色应用切到亮色应用不变色，
        // 收起再弹起才有一次 layout，于是又对了）。这里主动 invalidate 一次整窗口。
        if (themeJustChanged) {
            themeJustChanged = false;
            repaintForTheme(panel);
        }
    }

    /**
     * 主题切换后只把「底栏那两颗图标」自己那一小块重画一遍。
     * 不能 invalidate 整个窗口根：输入法窗口是半透明的，整窗重画会让面板的
     * 模糊层 / 实色层再合成一次，看起来就是「黑的又叠在白的一层上」。
     */
    private void repaintForTheme(View panel) {
        try {
            View root = panel == null ? null : panel.getRootView();
            android.widget.ImageView back = root == null ? null
                    : findNavIconView(root, NAV_ID_BACK);
            android.widget.ImageView switcher = root == null ? null
                    : findNavIconView(root, NAV_ID_SWITCH);
            int n = 0;
            java.util.List<android.widget.ImageView> repaint =
                    new java.util.ArrayList<>(6);
            if (back != null) {
                repaint.add(back);
            }
            if (switcher != null) {
                repaint.add(switcher);
            }
            if (root != null) {
                collectNavIcons(root, repaint, 0);
            }
            for (android.widget.ImageView iv : repaint) {
                if (iv == null) {
                    continue;
                }
                iv.invalidate();
                Drawable d = iv.getDrawable();
                if (d != null) {
                    d.invalidateSelf();
                }
                n++;
            }
            probe("theme repaint nav views n=" + n
                    + " ink=#" + Integer.toHexString(navInkColor()));
        } catch (Throwable t) {
            probe("theme repaint failed " + t.getClass().getSimpleName());
        }
        lastNavScanSig = "";
        try {
            java.util.ArrayList<Drawable> ds = new java.util.ArrayList<>(navDrawables.keySet());
            for (Drawable d : ds) {
                forceNavIconPaint(d);
            }
            probe("theme repaint nav drawables n=" + ds.size()
                    + " ink=#" + Integer.toHexString(navInkColor()));
        } catch (Throwable ignore) {
        }
        try {
            applyLogoColors(panel);
            applyFnKeyColors(panel);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 布局一变（切中英重建键盘视图）就在「下一帧画之前」把这几层重新处理一遍：
     * 候选栏/键盘容器/底栏该透明的透明，键盘 root 该铺实色的铺实色。
     * 放在 OnDrawListener 里是为了赶在绘制之前完成，不会出现「重建后那几帧还是原色」。
     */
    private void ensureLayerWatchers(View panel) {
        if (panel == null || layersPanelRef == panel) {
            return;
        }
        layersPanelRef = panel;
        try {
            panel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (l != ol || t != ot || r != or || b != ob) {
                    layersDirty.set(true);
                }
            });
            panel.getViewTreeObserver().addOnDrawListener(() -> {
                if (!layersDirty.get() || layersBusy) {
                    return;
                }
                layersBusy = true;
                try {
                    View p = layersPanelRef;
                    if (p == null || p.getWidth() <= 0) {
                        return;
                    }
                    float density = p.getResources().getDisplayMetrics().density;
                    int sw = p.getResources().getDisplayMetrics().widthPixels;
                    boolean full = p.getWidth() >= sw - Math.round(2f * density);
                    releaseBackgroundLayers(p, full, !full);
                    layersDirty.set(false);
                } catch (Throwable ignore) {
                } finally {
                    layersBusy = false;
                }
            });
            probe("layer watchers attached");
        } catch (Throwable t) {
            probe("layer watchers failed: " + t);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean layerDumpDone =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void dumpPanelLayersOnce(View panel) {
        if (!layerDumpDone.compareAndSet(false, true)) {
            return;
        }
        try {
            int n = panel instanceof android.view.ViewGroup
                    ? ((android.view.ViewGroup) panel).getChildCount() : -1;
            probe("panel tree: cls=" + panel.getClass().getSimpleName() + " children=" + n
                    + " bg=" + (panel.getBackground() == null ? "-"
                    : panel.getBackground().getClass().getSimpleName()));
            StringBuilder sb = new StringBuilder("panel kids:");
            walkLayers(panel, 0, sb);
            probe(sb.toString());
        } catch (Throwable t) {
            probe("panel tree failed: " + t);
        }
    }

    private void walkLayers(View v, int depth, StringBuilder sb) {
        if (v == null || depth > 3 || sb.length() > 900) {
            return;
        }
        String idn = "?";
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                idn = v.getResources().getResourceEntryName(id);
            }
        } catch (Throwable ignore) {
        }
        Drawable bg = v.getBackground();
        sb.append(" | ").append(depth).append(':').append(v.getClass().getSimpleName())
                .append('#').append(idn).append(" bg=")
                .append(bg == null ? "-" : bg.getClass().getSimpleName());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walkLayers(g.getChildAt(i), depth + 1, sb);
            }
        }
    }

    /** 把「透明度 / 像素数」落到高光 Drawable 上（百分比 → alpha） */
    private void applyTopGlow(TopGlowDrawable glow, float radius) {
        int alpha = Math.round(Math.max(0, Math.min(100, topGlowAlpha)) / 100f * 255f);
        int px = Math.max(0, Math.min(120, topGlowHeightPx));
        glow.setParams(alpha, px, radius, topGlowWidthPx);
        appliedGlowAlpha = topGlowAlpha;
        appliedGlowPx = topGlowHeightPx;
        appliedGlowWidth = topGlowWidthPx;
    }

    /** 调色台改了高光参数：就地更新，不重建背景链 */
    private void updateTopGlow(Drawable bg, float radius) {
        if (appliedGlowAlpha == topGlowAlpha && appliedGlowPx == topGlowHeightPx
                && appliedGlowWidth == topGlowWidthPx) {
            return;
        }
        TopGlowDrawable glow = findGlowLayer(bg);
        if (glow == null) {
            return;
        }
        applyTopGlow(glow, radius);
        probe("top glow alpha=" + topGlowAlpha + "% px=" + topGlowHeightPx
                + " w=" + topGlowWidthPx);
    }

    private static TopGlowDrawable findGlowLayer(Drawable bg) {
        if (!(bg instanceof TopRoundDrawable)) {
            return null;
        }
        Drawable inner = ((TopRoundDrawable) bg).getInner();
        if (!(inner instanceof LayerDrawable)) {
            return null;
        }
        LayerDrawable layers = (LayerDrawable) inner;
        for (int i = 0; i < layers.getNumberOfLayers(); i++) {
            Drawable d = layers.getDrawable(i);
            if (d instanceof TopGlowDrawable) {
                return (TopGlowDrawable) d;
            }
        }
        return null;
    }

    /**
     * 左上角 logo：
     *  - logo_iv 图标用 SRC_IN 色滤染色（保留图形透明区）；
     *  - logo_container_rl 的圆底给原背景 drawable 打 tint（保留形状/圆角），没有背景时补一个圆形实色。
     * 只碰这两个 id，其余组件不参与。
     */
    private void applyLogoColors(View panel) {
        if (DEBUG && !logoLogged && panel.getWidth() > 0) {
            logoLogged = true;
            for (String id : new String[]{"logo_container_rl", "logo_iv", "logo_ll"}) {
                for (View v : findViewsById(panel, id)) {
                    String extra = "";
                    if (v instanceof android.widget.ImageView) {
                        Drawable d = ((android.widget.ImageView) v).getDrawable();
                        extra = " drawable=" + (d == null ? "null" : d.getClass().getName());
                        if (d instanceof LayerDrawable) {
                            extra += " layers=" + ((LayerDrawable) d).getNumberOfLayers();
                            for (int i = 0; i < ((LayerDrawable) d).getNumberOfLayers(); i++) {
                                Drawable layer = ((LayerDrawable) d).getDrawable(i);
                                extra += " [" + i + "]" + (layer == null ? "null"
                                        : layer.getClass().getSimpleName());
                            }
                        }
                    }
                    log(Log.INFO, TAG, "logo probe " + id + " cls=" + v.getClass().getSimpleName()
                            + " size=" + v.getWidth() + "x" + v.getHeight()
                            + " bg=" + (v.getBackground() == null ? "null"
                            : v.getBackground().getClass().getSimpleName()) + extra);
                }
            }
        }
        int icon = logoIconColor;
        int circle = logoCircleColor;
        if (icon == 0 && circle == 0) {
            return;
        }
        for (View v : findViewsById(panel, "logo_iv")) {
            if (!(v instanceof android.widget.ImageView)) {
                continue;
            }
            android.widget.ImageView iv = (android.widget.ImageView) v;
            if (iv.getWidth() <= 0 || iv.getHeight() <= 0) {
                continue;
            }
            Drawable cur = iv.getDrawable();
            Drawable src = logoSource.get(iv);
            if (cur != null && !(cur instanceof LogoTwoToneDrawable)) {
                src = cur;
                logoSource.put(iv, cur);
            }
            if (src == null) {
                continue;
            }
            String[] keyOut = new String[1];
            Drawable twoTone = buildTwoTone(iv, src, icon, circle, keyOut);
            if (twoTone == null) {
                continue;
            }
            if (keyOut[0].equals(logoApplied.get(iv))) {
                continue;
            }
            iv.setImageDrawable(twoTone);
            logoApplied.put(iv, keyOut[0]);
            log(Log.INFO, TAG, "logo two-tone applied " + keyOut[0]);
        }
    }

    /**
     * logo 是整体一张矢量图（底圆 + 品牌图形），单色 tint 会把两层糊成一片。
     * 这里给它做「两色调位图重映射」：
     *   1. 把原图按控件尺寸渲染成 bitmap；
     *   2. 从圆心附近 0.86R 画一圈采样点，取出出现次数最多的颜色 —— 这就是**底圆的颜色**
     *      （底圆一定铺满外圈，而图形被它包在里面，所以外圈采样不会被图形污染）；
     *   3. 再在剩下的不透明像素里取出现次数最多的颜色 —— 这是**图形的颜色**；
     *   4. 逐像素按「离哪个参考色更近」归到圆底或图形，分别上 circleColor / iconColor。
     *
     * 全程只拿这张图跟自己比，没有「白=底圆 / 亮=图形」这种亮度假设，
     * 所以亮色、深色、极暗模式都成立（极暗模式下圆底和图形本身都会变暗，依旧能分开）。
     */
    private Drawable buildTwoTone(android.widget.ImageView iv, Drawable src, int iconColor,
                                  int circleColor, String[] keyOut) {
        try {
            int w = iv.getWidth();
            int h = iv.getHeight();
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            Drawable d = src;
            if (src.getConstantState() != null) {
                d = src.getConstantState().newDrawable().mutate();
            }
            d.setBounds(0, 0, w, h);
            d.clearColorFilter();
            d.draw(canvas);

            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);

            int disc = sampleDiscColor(px, w, h);
            int glyph = sampleGlyphColor(px, disc);
            if (glyph == 0) {
                log(Log.WARN, TAG, "logo single-tone, skip (disc=#" + Integer.toHexString(disc) + ")");
                bmp.recycle();
                return null;
            }
            if (keyOut != null) {
                keyOut[0] = "t:" + Integer.toHexString(iconColor) + ":" + Integer.toHexString(circleColor)
                        + ":" + w + "x" + h
                        + ":" + Integer.toHexString(disc) + ":" + Integer.toHexString(glyph)
                        + ":" + System.identityHashCode(src);
            }

            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                int a = (p >>> 24) & 0xFF;
                if (a == 0) {
                    continue;
                }
                boolean isDisc = dist2(p, disc) <= dist2(p, glyph);
                int target = isDisc
                        ? (circleColor != 0 ? circleColor : p)
                        : (iconColor != 0 ? iconColor : p);
                int baseA = (isDisc ? ((disc >>> 24) & 0xFF) : ((glyph >>> 24) & 0xFF));
                int cov = baseA > 0 ? Math.min(255, a * 255 / baseA) : 255;
                int ta = (target >>> 24) & 0xFF;
                int outA = Math.min(255, cov * ta / 255);
                px[i] = (outA << 24) | (target & 0x00FFFFFF);
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h);
            return new LogoTwoToneDrawable(iv.getResources(), bmp);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "logo two-tone failed: " + t);
            return null;
        }
    }

    /** 外圈环采样：圆底一定铺满边框内一圈，图形很少能占到那里，所以取众数即为圆底色 */
    private int sampleDiscColor(int[] px, int w, int h) {
        int cx = w / 2;
        int cy = h / 2;
        double r = Math.min(w, h) / 2.0 * 0.86;
        int[] ring = new int[36];
        int n = 0;
        for (int i = 0; i < 36; i++) {
            double ang = Math.PI * 2 * i / 36;
            int x = (int) Math.round(cx + Math.cos(ang) * r);
            int y = (int) Math.round(cy + Math.sin(ang) * r);
            if (x < 0 || y < 0 || x >= w || y >= h) {
                continue;
            }
            int p = px[y * w + x];
            if (((p >>> 24) & 0xFF) < 8) {
                continue;
            }
            ring[n++] = p;
        }
        int ringColor = dominant(ring, n, -1, 0);
        if (ringColor != 0) {
            return ringColor;
        }
        // 兜底：环采样全落空（控件尺寸异常/透明度问题）时，用全体不透明像素的众数
        return dominant(px, px.length, -1, 0);
    }

    /** 图形色：在不透明像素里，离圆底色足够远的那些颜色的众数 */
    private int sampleGlyphColor(int[] px, int disc) {
        int n = 0;
        int[] bucket = new int[px.length];
        int far = 0;
        int farColor = 0;
        int farBest = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            if (((p >>> 24) & 0xFF) < 8) {
                continue;
            }
            int d = dist2(p, disc);
            if (d > 8100) {
                bucket[n++] = p;
            }
            if (d > farBest) {
                farBest = d;
                farColor = p;
            }
        }
        int c = dominant(bucket, n, -1, 0);
        if (c != 0) {
            return c;
        }
        // 只有一种颜色（单色 logo）就放弃，不去乱猜
        return farBest > 8100 ? farColor : 0;
    }

    /** 众数（每通道 5 bit 量化，抗抗锯齿噪点），返回 0 表示没有候选 */
    private int dominant(int[] colors, int n, int unused, int ignore) {
        if (n <= 0) {
            return 0;
        }
        int[] count = new int[1 << 15];
        int[] rep = new int[1 << 15];
        int bestCount = 0;
        int bestColor = 0;
        for (int i = 0; i < n; i++) {
            int p = colors[i];
            if (((p >>> 24) & 0xFF) == 0) {
                continue;
            }
            int idx = quantKey(p);
            if (count[idx] == 0) {
                rep[idx] = p;
            }
            count[idx]++;
            if (count[idx] > bestCount) {
                bestCount = count[idx];
                bestColor = rep[idx];
            }
        }
        return bestColor;
    }

    /** 每通道保留 5 bit，作为众数分桶键 */
    private int quantKey(int p) {
        return (((p >> 16) & 0xF8) << 7) | (((p >> 8) & 0xF8) << 2) | ((p & 0xF8) >> 3);
    }

    /** 两个颜色在 RGB 空间的平方距离（忽略 alpha，alpha 只影响透明度不参与分类） */
    private int dist2(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    /** 标记类：区分「我们自己画的两色调 logo」和宿主原来的矢量图 */
    static final class LogoTwoToneDrawable extends android.graphics.drawable.BitmapDrawable {
        LogoTwoToneDrawable(android.content.res.Resources res, android.graphics.Bitmap bmp) {
            super(res, bmp);
            setAntiAlias(true);
        }
    }

    /** 按资源 id 名收集面板里所有同 id 的 View（候选栏 / 剪贴板面板可能各有一个 logo） */
    private java.util.List<View> findViewsById(View root, String name) {
        java.util.List<View> out = new java.util.ArrayList<>();
        int id = pluginId(root, name);
        if (id != -1) {
            collectById(root, id, out, 0);
        }
        return out;
    }

    private void collectById(View view, int id, java.util.List<View> out, int depth) {
        if (view == null || depth > 8) {
            return;
        }
        if (view.getId() == id) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectById(group.getChildAt(i), id, out, depth + 1);
            }
        }
    }

    /** 纯实色（不做任何透明度处理） */
    static final class SolidColorDrawable extends ColorDrawable {
        final int color;

        SolidColorDrawable(int color) {
            super(color);
            this.color = color;
        }
    }

    /**
     * 面板里承载底色的几个容器（候选栏、键盘区、上下延伸、底部填色）。
     * 只按资源 id 精确命中这些「背景层」，其它一律不碰。
     */
    private static final String[] BG_LAYER_IDS = {
            "candidate_container",
            "keyboard_container_rl",
            "upper_view",
            "below_view",
            "input_below_view",
            "input_center_container",
            "input_center_view",
            "input_up_view",
            "input_lower_view",
            "input_upper_view",
            "input_left_view",
            "input_left_view2",
    };

    /** 面板三段底色（候选栏 / 面板栏 / 底栏）统一成一块时，这几层 View 的自带底色要一直按掉 */
    private static final java.util.Set<String> BG_LAYER_NAMES =
            new java.util.HashSet<>(java.util.Arrays.asList(BG_LAYER_IDS));

    /** 我们自己要改这几层的底色时临时放行，否则守卫会把我们自己的调用也拦掉 */
    private static final ThreadLocal<Boolean> LAYER_BG_ALLOW = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicInteger layerGuardSkips =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 底栏（收起 / 切输入法 两颗图标那一条）：切页时宿主会把它的原生底色重新设回去，闪的就是这一条 */
    private static boolean isBottomBarView(View v) {
        String sn = v.getClass().getSimpleName();
        return sn.contains("NavigationBar");
    }

    /** 递归找面板里的底栏，把它的底色让出来（限深 6 层，只碰类名含 NavigationBar 的） */
    private static int clearBottomBars(View v, int depth) {
        // HyperOS：底栏底色、分隔、按钮排布都是系统原生的，动了就会把按钮画错位/裁掉，
        // 平台适配只做图标染色，底栏这一层整个不动。
        if (navHyperOs) {
            return 0;
        }
        if (v == null || depth > 6) {
            return 0;
        }
        int n = 0;
        if (isBottomBarView(v) && v.getBackground() != null) {
            LAYER_BG_ALLOW.set(true);
            try {
                v.setBackground(null);
                n++;
            } finally {
                LAYER_BG_ALLOW.remove();
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += clearBottomBars(g.getChildAt(i), depth + 1);
            }
        }
        return n;
    }

    private static boolean isPanelLayerView(View v) {
        if (v == null) {
            return false;
        }
        if (isBottomBarView(v)) {
            // HyperOS 的底栏背景归系统，连宿主重设背景也不要拦，否则按钮所在那块会被自己的底色盖掉
            return !navHyperOs;
        }
        // 每个键盘页自己也会带一层原生皮肤底（日志里 id=a3i cls=S4/S5/S3...Keyboard val=GradientDrawable），
        // 切页/中英切换时宿主把它重新设回来，就会先露一帧原生色。这一层也要按掉。
        String cn = v.getClass().getName();
        if (cn.startsWith(KEYBOARD_PKG + ".")) {
            String sn = v.getClass().getSimpleName();
            if (sn.endsWith("Keyboard") || sn.endsWith("KeyboardView")) {
                return true;
            }
        }
        try {
            int id = v.getId();
            if (id == View.NO_ID) {
                return false;
            }
            return BG_LAYER_NAMES.contains(v.getResources().getResourceEntryName(id));
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * 三段底色统一：只靠「进面板时 setBackground(null)」是一次性的，
     * 切中英重建布局时宿主会把候选栏 / 底栏自己的底色重新设回去，
     * 那几帧就看到三段各自的原色 → 颜色抖动。
     * 这里把这几个 View 的 setBackground / setBackgroundColor / setBackgroundResource 直接拦掉。
     */
    private boolean floatTreeProbeInstalled = false;

    /** 临时诊断：把悬浮弹窗（float / 含表情页）的视图树 dump 出来 */
    private void installFloatTreeProbe() {
        if (floatTreeProbeInstalled) {
            return;
        }
        floatTreeProbeInstalled = true;
        try {
            Class<?> c = Class.forName("android.view.WindowManagerGlobal");
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!"addView".equals(m.getName()) || m.getParameterCount() < 3) {
                    continue;
                }
                install(m, "wetype_dark_wmg_addview_probe", chain -> {
                    Object res = chain.proceed();
                    try {
                        Object o = chain.getArg(0);
                        if (o instanceof View) {
                            View v = (View) o;
                            String cn = v.getClass().getName();
                            probe("WMG ADD " + cn + " " + v.getWidth() + "x"
                                    + v.getHeight() + " emoji=" + containsEmoji(v, 0));
                            if (cn.contains("float") || containsEmoji(v, 0)) {
                                StringBuilder sb = new StringBuilder("FLOAT ROOT ")
                                        .append(cn).append('\n');
                                walk(v, 0, sb, 0);
                                probe(sb.toString());
                            } else if (cn.contains("PopupDecorView")) {
                                final int[] tick = {0};
                                final Runnable[] self = new Runnable[1];
                                self[0] = () -> {
                                    try {
                                        boolean em = containsEmoji(v, 0);
                                        if (em) {
                                            applyFloatEmojiFill(v, 0);
                                            StringBuilder sb = new StringBuilder("FLOAT EMOJI TREE t=")
                                                    .append(tick[0]).append(' ')
                                                    .append(v.getWidth()).append('x')
                                                    .append(v.getHeight()).append('\n');
                                            walk(v, 0, sb, 0);
                                            probe(sb.toString());
                                        }
                                    } catch (Throwable ignore) {
                                    }
                                    tick[0]++;
                                    if (tick[0] < 25) {
                                        v.postDelayed(self[0], 1200L);
                                    }
                                };
                                v.postDelayed(self[0], 700L);
                            }
                        }
                    } catch (Throwable ignore) {
                    }
                    return res;
                });
            }
            probe("float tree probe installed (WMG.addView)");
        } catch (Throwable t) {
            probe("float tree probe failed " + t);
        }
    }

    /**
     * 悬浮弹窗（float.e 那棵树）从来没走过我们的面板铺色流程：
     *  - ImeRadiusLinearLayout = 面板容器，宿主给的是 ColorDrawable 浅灰白 → 换成我们的面板色（带圆角）
     *  - ImeCandidateView = 候选条 → 换成候选条配置色
     * 其余按键 / 图标自己画，不动。
     */
    /** 读 GradientDrawable 的实色（宿主那几块功能栏用的都是它） */
    private Integer floatGradColor(Drawable d) {
        try {
            java.lang.reflect.Field f = d.getClass().getDeclaredField("mSolidColor");
            f.setAccessible(true);
            int c = (Integer) f.get(d);
            if ((c >>> 24) != 0) {
                return c;
            }
        } catch (Throwable ignore) {
        }
        try {
            java.lang.reflect.Field f = d.getClass().getDeclaredField("mFillPaint");
            f.setAccessible(true);
            android.graphics.Paint p = (android.graphics.Paint) f.get(d);
            return p.getColor();
        } catch (Throwable ignore) {
        }
        return null;
    }

    private int styleFloatPopup(View v, int depth, int parentWidth) {
        if (v == null || depth > 12) {
            return 0;
        }
        int n = 0;
        int w = v.getWidth();
        String cls = v.getClass().getSimpleName();
        try {
            float dn = v.getResources().getDisplayMetrics().density;
            int radius = Math.round(panelRadiusDp * dn);
            // 浮窗最上面盖着两块满尺寸 ImageView（宿主九宫格底图），它们把面板色全遮住了：
            // 实测给面板刷纯红，只有 21px 边框能看见红 → 这两张图必须让位
            if (v instanceof android.widget.ImageView && parentWidth > 0
                    && w >= parentWidth * 95 / 100 && v.getHeight() >= 40) {
                ((android.widget.ImageView) v).setImageAlpha(0);
                probe("float bg image view hidden " + w + "x" + v.getHeight());
            }
            if (cls.contains("ImeRadiusLinearLayout") && w > 0) {
                int opaque = 0xFF000000 | (panelColor & 0xFFFFFF);
                android.graphics.drawable.GradientDrawable gd =
                        new android.graphics.drawable.GradientDrawable();
                gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                gd.setColor(opaque);
                gd.setCornerRadius(radius);
                LAYER_BG_ALLOW.set(true);
                plantBackground(v, gd);
                LAYER_BG_ALLOW.remove();
                probe("float panel painted w=" + w + " h=" + v.getHeight()
                        + " color=#" + Integer.toHexString(opaque) + " r=" + radius);
                n++;
            } else if (cls.contains("ImeCandidateView") && w > 0) {
                // 非悬浮面板里候选条是自己让位、露出面板色的；悬浮这棵树上也得一样，
                // 不能单独刷一层候选条色，否则候选条和面板两块颜色对不上。
                int pc = 0xFF000000 | (panelColor & 0xFFFFFF);
                LAYER_BG_ALLOW.set(true);
                plantBackground(v, new SolidColorDrawable(pc));
                LAYER_BG_ALLOW.remove();
                probe("float cand painted panel color");
                n++;
            } else if (parentWidth > 0 && w >= parentWidth * 85 / 100 && v.getHeight() >= 40) {
                // 功能栏 / 键区那两块满宽实色（宿主浅灰白）：同样让位，露出面板这一层
                Drawable bgd = v.getBackground();
                int c = 0;
                if (bgd instanceof ColorDrawable) {
                    c = ((ColorDrawable) bgd).getColor();
                } else if (bgd != null && bgd.getClass().getName().contains("GradientDrawable")) {
                    Integer gc = floatGradColor(bgd);
                    if (gc != null) {
                        c = gc;
                    }
                }
                int expected = 0xFF000000 | (panelColor & 0xFFFFFF);
                int a = c >>> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (c != 0 && c != expected && a >= 0xF0 && r > 0xE0 && g > 0xE0 && b > 0xE0) {
                    LAYER_BG_ALLOW.set(true);
                    plantBackground(v, new SolidColorDrawable(expected));
                    LAYER_BG_ALLOW.remove();
                    probe("float row painted #" + Integer.toHexString(c) + "->#"
                            + Integer.toHexString(expected) + " " + w + "x"
                            + v.getHeight());
                    n++;
                }
            }
        } catch (Throwable t) {
            probe("float paint fail " + t);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += styleFloatPopup(g.getChildAt(i), depth + 1, w);
            }
        }
        return n;
    }

    /** 悬浮弹窗里的表情页：和普通模式一样补一层我方实色（宿主不给底色） */
    private int applyFloatEmojiFill(View v, int depth) {
        if (v == null || depth > 12) {
            return 0;
        }
        int n = 0;
        String cls = v.getClass().getSimpleName();
        if (cls.contains("Emoji")) {
            int opaque = 0xFF000000 | (panelColor & 0xFFFFFF);
            Drawable bg = v.getBackground();
            if (!(bg instanceof SolidColorDrawable) || ((SolidColorDrawable) bg).color != opaque) {
                LAYER_BG_ALLOW.set(true);
                plantBackground(v, new SolidColorDrawable(opaque));
                LAYER_BG_ALLOW.remove();
                probe("float emoji fill " + cls + " " + v.getWidth() + "x" + v.getHeight());
                n++;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += applyFloatEmojiFill(g.getChildAt(i), depth + 1);
            }
        }
        return n;
    }

    private boolean containsEmoji(View v, int depth) {
        if (v == null || depth > 12) {
            return false;
        }
        if (v.getClass().getSimpleName().contains("Emoji")) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsEmoji(g.getChildAt(i), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void installLayerBackgroundGuard() {
        if (layerGuardInstalled) {
            return;
        }
        layerGuardInstalled = true;
        installFloatTreeProbe();

        // 宿主会在这几个入口重设背景；统一在 setBackground 里判一次：
        //   ① 我们自己种过的 View（面板本体、键盘 root 的实色层）→ 立刻把我们那张塞回去；
        //   ② 三段底色那几层 → 直接丢掉，只留面板那一块统一色。
        for (String nm : new String[]{"setBackground", "setBackgroundDrawable"}) {
            try {
                java.lang.reflect.Method m = android.view.View.class.getDeclaredMethod(
                        nm, android.graphics.drawable.Drawable.class);
                install(m, "wetype_dark_layer_bg_" + nm, chain -> {
                    if (LAYER_BG_ALLOW.get() != null) {
                        return chain.proceed();
                    }
                    Object vObj = chain.getThisObject();
                    if (!(vObj instanceof View)) {
                        return chain.proceed();
                    }
                    View v = (View) vObj;
                    Object arg = chain.getArg(0);
                    Drawable mine = plantedBackgrounds.get(v);
                    if (mine != null) {
                        if (arg == mine) {
                            return chain.proceed();
                        }
                        // 关键：直接丢掉宿主这次调用，不要「再 setBackground 一次把我们那张塞回去」——
                        // 重新 setBackground 会让窗口模糊层重新注册，重新注册本身就会丢掉一帧模糊，
                        // 表现出来还是「闪一下」。我们那张本来就还挂在上面，丢掉就够了。
                        int n = plantedRestoreCount.incrementAndGet();
                        if (n == 1 || n == 20 || n == 100) {
                            probe("planted bg dropped n=" + n + " cls="
                                    + v.getClass().getSimpleName());
                        }
                        return null;
                    }
                    if (isPanelLayerView(v)) {
                        int n = layerGuardSkips.incrementAndGet();
                        if (n == 1 || n == 10 || n == 100 || n == 1000) {
                            probe("layer bg dropped n=" + n + " cls=" + v.getClass().getSimpleName());
                        }
                        return null;
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                probe("layer bg guard " + nm + " failed: " + t);
            }
        }
        for (String nm : new String[]{"setBackgroundColor", "setBackgroundResource"}) {
            try {
                java.lang.reflect.Method m = android.view.View.class.getDeclaredMethod(nm, int.class);
                install(m, "wetype_dark_layer_bg_int_" + nm, chain -> {
                    if (LAYER_BG_ALLOW.get() != null) {
                        return chain.proceed();
                    }
                    Object vObj = chain.getThisObject();
                    if (!(vObj instanceof View)) {
                        return chain.proceed();
                    }
                    View v = (View) vObj;
                    int arg = chain.getArg(0) instanceof Integer ? (Integer) chain.getArg(0) : 0;
                    Drawable mine = plantedBackgrounds.get(v);
                    if (mine != null) {
                        plantedRestoreCount.incrementAndGet();
                        return null;
                    }
                    if (isPanelLayerView(v)) {
                        int n = layerGuardSkips.incrementAndGet();
                        if (n == 1 || n == 10 || n == 100 || n == 1000) {
                            probe("layer bg dropped n=" + n + " cls=" + v.getClass().getSimpleName());
                        }
                        return null;
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                probe("layer bg guard " + nm + " failed: " + t);
            }
        }
        for (String nm : new String[]{"setBackgroundColor", "setBackgroundResource"}) {
            try {
                java.lang.reflect.Method m = android.view.View.class.getDeclaredMethod(nm, int.class);
                install(m, "wetype_dark_layer_bg_int_" + nm, chain -> {
                    if (LAYER_BG_ALLOW.get() != null) {
                        return chain.proceed();
                    }
                    Object vObj = chain.getThisObject();
                    if (!(vObj instanceof View)) {
                        return chain.proceed();
                    }
                    View v = (View) vObj;
                    Drawable mine = plantedBackgrounds.get(v);
                    if (mine != null) {
                        plantedRestoreCount.incrementAndGet();
                        return null;
                    }
                    if (isPanelLayerView(v)) {
                        layerGuardSkips.incrementAndGet();
                        return null;
                    }
                    return chain.proceed();
                });
            } catch (Throwable t) {
                probe("layer bg guard " + nm + " failed: " + t);
            }
        }
        probe("layer bg guard installed");
    }

    /** 我们主动种下去的背景：登记后宿主每次重设都会被同步换回来 */
    private void plantBackground(View v, Drawable d) {
        if (v == null || d == null) {
            return;
        }
        plantedBackgrounds.put(v, d);
        LAYER_BG_ALLOW.set(true);
        try {
            v.setBackground(d);
        } finally {
            LAYER_BG_ALLOW.remove();
        }
    }

    private static final Map<String, Integer> pluginIdCache = new HashMap<>();

    private int pluginId(View view, String name) {
        Integer cached = pluginIdCache.get(name);
        if (cached != null) {
            return cached;
        }
        int id = -1;
        try {
            Class<?> ids = Class.forName("com.tencent.wetype.plugin.hld.s", false,
                    view.getClass().getClassLoader());
            id = ids.getField(name).getInt(null);
        } catch (Throwable ignore) {
        }
        pluginIdCache.put(name, id);
        return id;
    }

    private View idView(View root, String name) {
        int id = pluginId(root, name);
        return id == -1 ? null : root.findViewById(id);
    }

    private void releaseBackgroundLayers(View panel, boolean fullWidth, boolean floating) {
        if (floating) {
            // 悬浮键盘：把宿主那几层底色也按掉，否则它们会盖在我们 alpha=0 的面板上，
            // 露出微信输入法自己的绿色皮肤。底栏（NavigationBar）不碰，避免多出一条长横条。
            LAYER_BG_ALLOW.set(true);
            try {
                for (String name : BG_LAYER_IDS) {
                    View v = idView(panel, name);
                    if (v == null || v == panel || v.getBackground() == null) {
                        continue;
                    }
                    releasedBackgrounds.put(v, v.getBackground());
                    v.setBackground(null);
                }
                View keyboard = idView(panel, "keyboard_container_rl");
                if (keyboard != null) {
                    handleKeyboardRoots(keyboard, 0, true);
                }
                // 注意：不能往上清父容器/DecorView 的背景。悬浮键盘的 IME 窗口本身是
                // 全屏 surface，清掉它就整屏发黑（实测过）。只清面板内部那几层。
            } finally {
                LAYER_BG_ALLOW.remove();
            }
            return;
        }
        if (!fullWidth) {
            // 非全宽又非悬浮（横屏全屏等）：把让出去的底色还回去，保证别的形态和宿主原样一致
            restoreBackgroundLayers();
            return;
        }
        int released = 0;
        StringBuilder names = new StringBuilder();
        LAYER_BG_ALLOW.set(true);
        try {
        for (String name : BG_LAYER_IDS) {
            View v = idView(panel, name);
            if (v == null || v == panel || v.getBackground() == null) {
                continue;
            }
            releasedBackgrounds.put(v, v.getBackground());
            v.setBackground(null);
            released++;
            if (names.length() < 200) {
                names.append(name).append(' ');
            }
        }
        // 键盘区真正的底色挂在每个键盘 View 的 root 上（宿主磨砂也是改这一层）
        View keyboard = idView(panel, "keyboard_container_rl");
        if (keyboard != null) {
            int n = handleKeyboardRoots(keyboard, 0, false);
            released += n;
            if (n > 0) {
                names.append("keyboard_root x").append(n).append(' ');
            }
        }
        int nav = clearBottomBars(panel, 0);
        released += nav;
        if (nav > 0) {
            names.append("bottom_bar x").append(nav).append(' ');
        }
        } finally {
            LAYER_BG_ALLOW.remove();
        }
        if (DEBUG && released > 0) {
            log(Log.INFO, TAG, "released background layers n=" + released + " : " + names);
        }
    }

    /**
     * 键盘容器里的每个键盘 View 只有一层 root 底色：
     *  - 普通键盘：让位（露出面板模糊层）；
     *  - 表情 / 剪贴板 / 常用语键盘（宿主本来就没给这类面板底色）：直接铺一层和面板同色的实色（alpha FF）。
     * 最多下探 4 层，绝不扩大范围。
     */
    private int handleKeyboardRoots(View view, int depth, boolean floating) {
        if (view == null || depth > 4) {
            return 0;
        }
        int count = 0;
        String cls = view.getClass().getSimpleName();
        if (cls.endsWith("Keyboard")) {
            View root = idView(view, "root");
            if (root != null) {
                if (floating) {
                    if (needsSolidBackground(cls)) {
                        // 悬浮键盘的 表情 / 剪贴板 / 常用语 页：面板层 alpha 归零了，
                        // 宿主自己也不给底色，这里必须补一层我方实色，否则整页透明
                        LAYER_BG_ALLOW.set(true);
                        int opaqueF = 0xFF000000 | (panelColor & 0xFFFFFF);
                        Drawable bgF = root.getBackground();
                        if (!(bgF instanceof SolidColorDrawable)
                                || ((SolidColorDrawable) bgF).color != opaqueF) {
                            if (bgF != null && !(bgF instanceof SolidColorDrawable)) {
                                releasedBackgrounds.put(root, bgF);
                            }
                            plantBackground(root, new SolidColorDrawable(opaqueF));
                        }
                        LAYER_BG_ALLOW.remove();
                        probe("float solid page fill " + cls);
                    } else if (root.getBackground() != null) {
                        releasedBackgrounds.put(root, root.getBackground());
                        root.setBackground(null);
                        count++;
                    }
                } else if (needsSolidBackground(cls)) {
                    LAYER_BG_ALLOW.set(true);
                    int opaque = 0xFF000000 | (panelColor & 0xFFFFFF);
                    Drawable bg = root.getBackground();
                    if (!(bg instanceof SolidColorDrawable) || ((SolidColorDrawable) bg).color != opaque) {
                        if (bg != null && !(bg instanceof SolidColorDrawable)) {
                            releasedBackgrounds.put(root, bg);
                        }
                        plantBackground(root, new SolidColorDrawable(opaque));
                    }
                } else if (root.getBackground() != null) {
                    releasedBackgrounds.put(root, root.getBackground());
                    LAYER_BG_ALLOW.set(true);
                    root.setBackground(null);
                    count++;
                }
                LAYER_BG_ALLOW.remove();
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += handleKeyboardRoots(group.getChildAt(i), depth + 1, floating);
            }
        }
        return count;
    }

    /** 这些面板宿主不画底色，需要我们自己补同色实底 */
    private boolean needsSolidBackground(String keyboardClass) {
        return keyboardClass.contains("Emoji")
                || keyboardClass.contains("Clipboard")
                || keyboardClass.contains("CustomPhrase");
    }

    /** 把让出去的底色还回去（只在悬浮/非全宽等需要复原的形态用） */
    private void restoreBackgroundLayers() {
        LAYER_BG_ALLOW.set(true);
        try {
        for (Map.Entry<View, Drawable> e : releasedBackgrounds.entrySet()) {
            View v = e.getKey();
            Drawable bg = e.getValue();
            plantedBackgrounds.remove(v);
            if (v != null && bg != null) {
                Drawable cur = v.getBackground();
                if (cur == null || cur instanceof SolidColorDrawable) {
                    v.setBackground(bg);
                }
            }
        }
        } finally {
            LAYER_BG_ALLOW.remove();
        }
        releasedBackgrounds.clear();
    }

    /**
     * ColorOS 官方背景模糊 Drawable：
     * ViewRootManager(view) = view.getViewRootImpl().createBackgroundBlurDrawable()
     * 拿到的是 AOSP BackgroundBlurDrawable（窗口级 backdrop blur）。
     * 每次窗口重建都必须重新取，不能长期缓存。
     */
    private final java.util.concurrent.atomic.AtomicInteger blurCreateCount =
            new java.util.concurrent.atomic.AtomicInteger();

    private boolean miuiBlurLogged;

    /**
     * HyperOS（澎湃 OS）原生面板背景模糊。
     *
     * 小米这一套不是 Oplus 的 Drawable，也不是 AOSP 的窗口背景模糊：
     * 每个 View 自带背景模糊（View#setMiBackgroundBlurRadius / setMiBackgroundBlurEnhanceFlag /
     * disableMiBackgroundContainBelow），SystemUI 自己也这么用（MiBlurCompat + ShadeBlendBlurController）。
     * 半径 clamp 0..400 px，模糊跟着 View 的背景轮廓走，正好配我们的 TopRoundDrawable 圆角。
     */
    /** 记录已经下过的参数，避免每 200ms 重铺面板就重复调用一遍 */
    private WeakReference<View> miuiBlurTargetRef = new WeakReference<>(null);
    private int miuiBlurAppliedRadius = -1;

    private void applyMiuiPanelBlur(View target, float radiusPx) {
        try {
            int r = Math.round(radiusPx);
            r = Math.max(0, Math.min(400, r));
            if (miuiBlurTargetRef.get() == target && miuiBlurAppliedRadius == r) {
                return;
            }
            miuiBlurTargetRef = new WeakReference<>(target);
            miuiBlurAppliedRadius = r;
            // 1) 跨窗口模糊白名单：ViewRootImpl 只在 sIsEnabledPassWindowBlurFilter 为真、
            //    且 mPassWindowBlurFilterData 含本包名时才允许 setPassWindowBlurEnabled(true)。
            Object vri = viewRootImpl(target);
            Class<?> vriCls = Class.forName("android.view.ViewRootImpl");
            try {
                java.lang.reflect.Field f = vriCls
                        .getDeclaredField("sIsEnabledPassWindowBlurFilter");
                f.setAccessible(true);
                f.setBoolean(null, true);
            } catch (Throwable ignore) {
            }
            if (vri != null) {
                try {
                    java.lang.reflect.Field d = vriCls
                            .getDeclaredField("mPassWindowBlurFilterData");
                    d.setAccessible(true);
                    d.set(vri, "com.tencent.wetype");
                } catch (Throwable ignore) {
                }
            }
            // 2) 窗口模糊类型（ViewRootImpl.setMiBlurWinType）：0=不参与 1=窗口级模糊
            Object winType = null;
            try {
                winType = android.view.View.class.getMethod("setMiBlurWinType", int.class)
                        .invoke(target, 1);
            } catch (Throwable ignore) {
            }
            // 3) 打开模糊模式（0=关 1=开）
            boolean modeOk = false;
            try {
                android.view.View.class.getMethod("setMiBackgroundBlurMode", int.class)
                        .invoke(target, 1);
                modeOk = true;
            } catch (Throwable ignore) {
            }
            // 3) 允许模糊窗口背后的 App（否则只糊本窗口内部，什么都看不到）
            Object passOk = null;
            try {
                passOk = android.view.View.class.getMethod("setPassWindowBlurEnabled", boolean.class)
                        .invoke(target, true);
            } catch (Throwable ignore) {
            }
            // 4) 半径 / 增强位 / 允许糊下方内容
            applyWindowSurfaceBlur(target, r);
            android.view.View.class.getMethod("setMiBackgroundBlurRadius", int.class)
                    .invoke(target, r);
            try {
                android.view.View.class.getMethod("setMiBackgroundBlurEnhanceFlag",
                        int.class, int.class).invoke(target, 0x200, 0xF00);
            } catch (Throwable ignore) {
            }
            try {
                android.view.View.class.getMethod("disableMiBackgroundContainBelow", boolean.class)
                        .invoke(target, false);
            } catch (Throwable ignore) {
            }
            // miuix 的 MiuiBlurUtils 里还有两步我们之前漏了：
            // setMiViewBlurMode(BG_MODE_ENABLE=1) 让这个 View 成为模糊承载者，
            // setMiBackgroundBlurType(TYPE_GAUSS=0) 选高斯（0=高斯 1=Kawase 2=渐变）
            try {
                android.view.View.class.getMethod("setMiViewBlurMode", int.class)
                        .invoke(target, 1);
            } catch (Throwable ignore) {
            }
            try {
                android.view.View.class.getMethod("setMiBackgroundBlurType", int.class)
                        .invoke(target, 0);
            } catch (Throwable ignore) {
            }
            if (!miuiBlurLogged) {
                miuiBlurLogged = true;
                probe("miui view blur r=" + r + "px host="
                        + target.getClass().getSimpleName()
                        + " mode=" + modeOk + " pass=" + passOk + " winType=" + winType
                        + " vri=" + (vri != null));
            }
        } catch (Throwable t) {
            if (!miuiBlurLogged) {
                miuiBlurLogged = true;
                probe("miui view blur failed: " + t);
            }
        }
    }

    /**
     * 兜底：直接对输入法窗口的 SurfaceControl 下模糊。
     * 小米那套 View 级模糊要靠 WMS/PassBlur 把窗口“peekThrough”接上，输入法窗口拿不到，
     * 这里绕开窗口管理，直接给窗口图层设背景模糊半径（SurfaceFlinger 支持 cross-window blur）。
     */
    /** 开关：SF 区域模糊会让输入法窗口反复消失，先停用，回到只走 View 级调用 */
    private static final boolean SF_BLUR_ENABLED = false;

    private void applyWindowSurfaceBlur(View target, int radiusPx) {
        if (!SF_BLUR_ENABLED) {
            return;
        }
        try {
            Object vri = viewRootImpl(target);
            if (vri == null) {
                return;
            }
            Class<?> vriCls = Class.forName("android.view.ViewRootImpl");
            Object sc = null;
            // mSurfaceControl 才是 SurfaceControl；mSurface 是老的 Surface 包装
            for (String name : new String[]{"mSurfaceControl", "mSurface"}) {
                try {
                    java.lang.reflect.Field f = vriCls.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(vri);
                    if (v instanceof android.view.SurfaceControl) {
                        sc = v;
                        break;
                    }
                } catch (Throwable ignore) {
                }
            }
            if (sc == null) {
                return;
            }
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object t = txCls.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method set = txCls.getMethod("setBackgroundBlurRadius",
                    scCls, int.class);
            // 整层背景模糊会连输入法窗口上方留白一起糊（看起来像全屏），必须归 0，
            // 只按面板矩形走 blurRegions
            set.invoke(t, sc, 0);
            // 按面板矩形下发 blurRegions（AOSP 13+ 的区域模糊，SF 直接采样下层窗口）
            try {
                int[] loc = new int[2];
                target.getLocationInWindow(loc);
                float[] region = new float[]{loc[0], loc[1],
                        loc[0] + target.getWidth(), loc[1] + target.getHeight(), radiusPx};
                float[][] regions = new float[][]{region};
                boolean ok = false;
                // 澎湃自己加的 blend 版：多带 include/exclude 图层列表（PassBlur 走的就是这条）
                try {
                    java.lang.reflect.Method blend = txCls.getMethod("setBlurRegions",
                            scCls, float[][].class, java.util.List.class, java.util.List.class);
                    blend.invoke(t, sc, regions,
                            new java.util.ArrayList<Object>(), new java.util.ArrayList<Object>());
                    ok = true;
                } catch (Throwable ignore) {
                }
                if (!ok) {
                    java.lang.reflect.Method setRegions = txCls.getMethod("setBlurRegions",
                            scCls, float[][].class);
                    setRegions.invoke(t, sc, regions);
                }
            } catch (Throwable ignore) {
            }
            txCls.getMethod("apply").invoke(t);
            if (!miuiBlurLogged) {
                miuiBlurLogged = true;
                probe("sf region blur r=" + radiusPx + " rect=" + target.getWidth()
                        + "x" + target.getHeight());
            }
        } catch (Throwable t) {
            if (!miuiBlurLogged) {
                miuiBlurLogged = true;
                probe("sf window blur failed: " + t);
            }
        }
    }

    /** 关掉小米原生背景模糊（半径 0 + 关闭增强位） */
    private void clearMiuiPanelBlur(View target) {
        try {
            android.view.View.class.getMethod("setMiBackgroundBlurRadius", int.class)
                    .invoke(target, 0);
            android.view.View.class.getMethod("setMiBackgroundBlurMode", int.class)
                    .invoke(target, 0);
            android.view.View.class.getMethod("setPassWindowBlurEnabled", boolean.class)
                    .invoke(target, false);
            try {
                android.view.View.class.getMethod("setMiBackgroundBlurEnhanceFlag",
                        int.class, int.class).invoke(target, 0x100, 0xF00);
            } catch (Throwable ignore) {
            }
        } catch (Throwable ignore) {
        }
    }

    private Drawable createPanelBlurDrawable(View target, float cornerPx, Object vri) {
        int bn = blurCreateCount.incrementAndGet();
        if (bn <= 3 || bn % 20 == 0) {
            probe("blur drawable #" + bn);
        }
        if (oplusBlurUnavailable || vri == null) {
            return null;
        }
        // 系统平台 = HyperOS（小米 / 红米）：没有 com.oplus.*，改用 AOSP 窗口级背景模糊
        // （Window#setBackgroundBlurRadius + 带圆角 Outline 的 window background，
        //  先置 0 再设背景再设半径，见《改代码前必读》第 1 节的三步重建法）。
        if (HostConfig.INSTANCE.isHyperOs()) {
            float density = target.getResources().getDisplayMetrics().density;
            applyMiuiPanelBlur(target, blurRadiusDp * density);
            if (!hyperOsLogged) {
                hyperOsLogged = true;
                probe("platform=HyperOS -> MIUI View background blur");
            }
            return null;
        }
        try {
            int blurRadius = Math.round(blurRadiusDp * target.getResources().getDisplayMetrics().density);
            Class<?> vrmCls = Class.forName("com.oplus.view.ViewRootManager");
            Object vrm = vrmCls.getConstructor(View.class).newInstance(target);
            Drawable blur = (Drawable) vrmCls.getMethod("getBackgroundBlurDrawable").invoke(vrm);
            if (blur == null) {
                log(Log.WARN, TAG, "getBackgroundBlurDrawable=null (viewRootImpl null?)");
                return null;
            }
            // setBlurParams 必须先行，否则可能零模糊
            try {
                Class<?> paramCls = Class.forName("com.oplus.graphics.OplusBlurParam");
                Object param = paramCls.getConstructor().newInstance();
                paramCls.getMethod("setBlurType", int.class).invoke(param, 2);
                // 模糊区域只认「4 个半径 + 平滑参数」，不认任意 Path，
                // 而且画布 clip 管不到它。平滑圆角（G2）会把传入半径再放大 1.33 倍当支撑区，
                // 反而比颜色层大一圈，所以这里关掉平滑（type 0 / weight 2.0），让它就是普通圆角矩形。
                try {
                    paramCls.getMethod("setSmoothCornerType", int.class).invoke(param, 0);
                    paramCls.getMethod("setSmoothCornerWeight", float.class)
                            .invoke(param, PANEL_SMOOTH_WEIGHT);
                    if (!panelSmoothLogged) {
                        panelSmoothLogged = true;
                        probe("panel blur smoothCorner type=0 weight=" + PANEL_SMOOTH_WEIGHT);
                    }
                } catch (Throwable t) {
                    if (!panelSmoothLogged) {
                        panelSmoothLogged = true;
                        probe("panel blur smoothCorner NA " + t);
                    }
                }
                vrmCls.getMethod("setBlurParams", paramCls).invoke(vrm, param);
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "setBlurParams NA " + t.getClass().getSimpleName());
            }
            try {
                vrmCls.getMethod("setBlurRadius", int.class).invoke(vrm, blurRadius);
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "setBlurRadius NA " + t.getClass().getSimpleName());
            }
            // 模糊层自己不做圆角：让画布 clip 的 squircle 决定形状，
            // 这样模糊层和上面的实色层是同一个圆角（试：0 圆角 + 依赖 clipPath）
            try {
                // ColorOS 的平滑圆角会把传进去的半径再放大 ≈1.33 倍当「支撑区」，
                // 这里先按 1/1.33 折回去，模糊层的圆角外沿才和我们自绘的 1.33r 连续曲率重合
                float blurCorner = BLUR_OWN_CORNER ? cornerPx * BLUR_RADIUS_SCALE : 0f;
                if (!blurCornerLogged) {
                    blurCornerLogged = true;
                    probe("blur corner px=" + cornerPx + " eff=" + blurCorner
                            + " scale=" + BLUR_RADIUS_SCALE);
                }
                vrmCls.getMethod("setCornerRadius", float.class, float.class,
                        float.class, float.class).invoke(vrm, blurCorner, blurCorner, 0f, 0f);
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "setCornerRadius(FFFF) NA " + t.getClass().getSimpleName());
            }
            blur.setAlpha(255);
            log(Log.DEBUG, TAG, "blur drawable ready cls=" + blur.getClass().getSimpleName()
                    + " radius=" + blurRadius + " corner=" + cornerPx);
            return blur;
        } catch (Throwable t) {
            oplusBlurUnavailable = true;
            log(Log.WARN, TAG, "oplus blur unavailable: " + t);
            return null;
        }
    }

    private Object viewRootImpl(View view) {
        try {
            if (getViewRootImplMethod == null) {
                Method m = View.class.getMethod("getViewRootImpl");
                m.setAccessible(true);
                getViewRootImplMethod = m;
            }
            return getViewRootImplMethod.invoke(view);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读取调色台写入的实时配置：{ "panelColor":"#AARRGGBB", "blurRadiusDp":24 }
     * 只比对 mtime / 文件大小，避免每 300ms 都解析一次。
     */
    private void refreshLiveConfig() {
        try {
            java.io.File f = new java.io.File(LIVE_FILE);
            if (!f.isFile()) {
                return;
            }
            long mtime = f.lastModified();
            long length = f.length();

            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            String text = sb.toString();
            liveMtime = mtime;
            liveLength = length;
            // 同样按内容判断：只改了色值（长度不变）时 mtime 秒级精度可能没变化，
            // 会被当成「没改」，表现就是调了不生效、非得重启输入法
            if (text.equals(liveText)) {
                return;
            }
            liveText = text;
            org.json.JSONObject json = new org.json.JSONObject(text);

            String color = json.optString("panelColor", null);
            if (color != null) {
                String hex = color.trim();
                if (hex.startsWith("#")) {
                    hex = hex.substring(1);
                }
                if (hex.length() == 6) {
                    hex = "FF" + hex;
                }
                if (hex.length() == 8) {
                    panelColor = (int) Long.parseLong(hex, 16);
                }
            }
            int blur = json.optInt("blurRadiusDp", blurRadiusDp);
            if (blur >= 0 && blur <= 60) {
                blurRadiusDp = blur;
            }

            int icon = parseColor(json.optString("logoIconColor", null));
            if (icon != 0 && !logoFromPalette) {
                logoIconColor = icon;
            }
            int circle = parseColor(json.optString("logoCircleColor", null));
            if (circle != 0 && !circleFromPalette) {
                logoCircleColor = circle;
            }
            if (json.optBoolean("dump", false)) {
                dumpRequested = true;
            }
            int fnIcon = parseColor(json.optString("fnIconColor", null));
            if (fnIcon != 0 && !fnIconFromPalette) {
                fnIconColor = fnIcon;
            }
            int accent = parseColor(json.optString("accentColor", null));
            accentFromLive = accent;
            accentHitCount.set(0);

            int glowAlpha = json.optInt("topGlowAlpha", -1);
            if (glowAlpha >= 0) {
                topGlowAlpha = Math.min(100, Math.max(0, glowAlpha));
            }
            int glowPx = json.optInt("topGlowPx", -1);
            if (glowPx >= 0) {
                topGlowHeightPx = Math.min(120, Math.max(0, glowPx));
            }
            if (json.has("topGlowWidth")) {
                topGlowWidthPx = Math.min(16f, Math.max(0f, (float) json.optDouble("topGlowWidth", DEFAULT_TOP_GLOW_WIDTH)));
            }

            liveMtime = mtime;
            liveLength = length;
            log(Log.INFO, TAG, "live config: color=#" + Integer.toHexString(panelColor)
                    + " blurDp=" + blurRadiusDp
                    + " logoIcon=#" + Integer.toHexString(logoIconColor)
                    + " logoCircle=#" + Integer.toHexString(logoCircleColor));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "live config read failed: " + t);
        }
    }

    /** #AARRGGBB / #RRGGBB → int；解析失败返回 0（表示沿用宿主原色） */
    private int parseColor(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            String hex = raw.trim();
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            if (hex.length() == 6) {
                hex = "FF" + hex;
            }
            if (hex.length() != 8) {
                return 0;
            }
            return (int) Long.parseLong(hex, 16);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 调色台写盘后广播一下，键盘还开着也能立刻重读配置。
     * 之前只靠 watchdog 每 180ms 轮询，面板不可见或 watchdog 没起来时就只能靠
     * 收起 / 重启键盘才生效，表现就是「改了不实时」。
     */
    static final String ACTION_PALETTE_RELOAD = "com.tony.wetypedark.action.PALETTE_RELOAD";
    private volatile boolean reloadReceiverRegistered = false;

    private void ensureReloadReceiver(android.content.Context context) {
        if (reloadReceiverRegistered || context == null) {
            return;
        }
        reloadReceiverRegistered = true;
        try {
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent intent) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        View target = lastPanelRef == null ? null : lastPanelRef.get();
                        if (target == null) {
                            target = panelRef.get();
                        }
                        if (target == null) {
                            return;
                        }
                        try {
                            refreshAppearance();
                            applyToPanel(target, "reload-broadcast");
                            target.invalidate();
                        } catch (Throwable ignore) {
                        }
                    });
                }
            };
            android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_PALETTE_RELOAD);
            android.content.Context app = context.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            probe("reload receiver installed");
        } catch (Throwable t) {
            probe("reload receiver failed: " + t);
        }
    }

    /** 轻量守护：宿主换肤/重建时会重设背景，面板可见期间持续校验 */
    private void startWatchdog() {        if (watchdogStarted) {
            return;
        }
        watchdogStarted = true;
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable[] task = new Runnable[1];
        task[0] = new Runnable() {
            @Override
            public void run() {
                View panel = panelRef.get();
                if (panel == null) {
                    watchdogStarted = false;
                    return;
                }
                if (panel.isAttachedToWindow()) {
                    try {
                        applyToPanel(panel, "watchdog");
                    } catch (Throwable ignore) {
                    }
                }
                handler.postDelayed(task[0], 180);
            }
        };
        handler.postDelayed(task[0], 180);
    }

    /** 当前面板上圆角（px），轮廓回调每次读它，改了就 invalidateOutline 立即生效 */
    private volatile float outlineRadiusPx = -1f;

    /** 面板自己的上圆角轮廓（只裁面板这一层，不参与任何子级逻辑计算） */
    private void ensureOutline(View panel, final float radius) {
        if (outlined.get(panel) == null) {
            panel.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    float r = outlineRadiusPx;
                    if (w <= 0 || h <= 0) {
                        outline.setEmpty();
                        return;
                    }
                    if (r <= 0.5f) {
                        // 直角
                        outline.setRect(0, 0, w, h);
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        outline.setPath(SquirclePath.buildTop(w, h, r));
                    } else {
                        float rr = Math.min(r, Math.min(w, h) / 2f);
                        outline.setRoundRect(0, 0, w, h, rr);
                    }
                    outline.setAlpha(1f);
                }
            });
            panel.setClipToOutline(true);
            outlined.put(panel, Boolean.TRUE);
        }
        if (Math.abs(outlineRadiusPx - radius) > 0.5f) {
            outlineRadiusPx = radius;
            panel.invalidateOutline();
        }
    }

    private void clearOutline(View panel) {
        if (outlined.remove(panel) != null) {
            panel.setClipToOutline(false);
            panel.setOutlineProvider(null);
        }
    }

    /** 标记类：panel.background = TopRoundDrawable(PanelLayer[blur, tint]) */
    static final class PanelLayer extends LayerDrawable {
        final Drawable blur;

        PanelLayer(Drawable[] layers, Drawable blur) {
            super(layers);
            this.blur = blur;
        }
    }
}

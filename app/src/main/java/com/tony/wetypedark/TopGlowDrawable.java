package com.tony.wetypedark;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.Log;

/**
 * 面板顶部「高光描边」：沿面板上边缘画一条细描边，描边自身的颜色是竖向渐变。
 *
 *  - 描边路径 = 面板的 squircle 顶边（左右两个圆角 + 直线段），与外层
 *    TopRoundDrawable 的裁切轮廓同源，所以线正好压在面板内沿上；
 *  - 颜色竖向渐变：y = 0 处是纯白（alpha 由调色台给），到 y = N px 处
 *    变成同一个白的 alpha 0；
 *  - 只保留 y ∈ [0, N] 这一段：直线段是纯白，圆角往下走会自然淡出，
 *    N 以下的描边完全不画。
 *
 *  模糊：离线画到一张小 Bitmap 上，用 BlurMaskFilter 做真高斯模糊，再整块贴回。
 *  （硬件加速 Canvas 会直接忽略 MaskFilter，所以必须先落到软件 Bitmap。）
 *  外沿溢出由外层 TopRoundDrawable 的 clipPath 裁掉、向下溢出由 clipRect 裁掉，
 *  所以模糊只会往面板内侧柔和散开，不会糊到面板外面。
 */
public final class TopGlowDrawable extends Drawable {

    /** 默认可见线宽（真机 px） */
    private static final float DEFAULT_STROKE_PX = 2f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF layerRect = new RectF();

    private int topAlpha = 0;
    private int heightPx = 0;
    private float radius = 0f;
    /** 可见线宽（px）：描边有一半落在轮廓外侧被裁掉，所以实际 strokeWidth = 2 倍 */
    private float strokePx = DEFAULT_STROKE_PX;
    /** 模糊半径（px）：0 表示不模糊（走原来的矢量绘制） */
    private float blurPx = 0f;

    private Shader shader;
    private int shaderTop = Integer.MIN_VALUE;
    private int shaderBottom = Integer.MIN_VALUE;
    private int shaderAlpha = Integer.MIN_VALUE;

    private int cachedW = 0;
    private int cachedH = 0;
    private float cachedRadius = -1f;

    /** 模糊结果缓存 */
    private Bitmap blurCache;
    private int cacheW;
    private int cacheH;
    private int cacheTop;
    private int cacheAlpha;
    private float cacheBlur;
    private float cacheStroke;
    private float cacheRadius;

    public void setParams(int topAlpha, int heightPx, float radius, float strokePx) {
        setParams(topAlpha, heightPx, radius, strokePx, blurPx);
    }

    public void setParams(int topAlpha, int heightPx, float radius, float strokePx, float blurPx) {
        int alpha = Math.max(0, Math.min(255, topAlpha));
        int h = Math.max(0, heightPx);
        float w = Math.max(0f, Math.min(16f, strokePx));
        float b = Math.max(0f, Math.min(48f, blurPx));
        boolean changed = this.topAlpha != alpha || this.heightPx != h
                || this.radius != radius || this.strokePx != w || this.blurPx != b;
        this.topAlpha = alpha;
        this.heightPx = h;
        this.radius = radius;
        this.strokePx = w;
        this.blurPx = b;
        if (changed) {
            Log.i("wetype-dark", "glow params alpha=" + alpha + " px=" + h
                    + " w=" + w + " blur=" + b);
            invalidateSelf();
        }
    }

    public int getTopAlpha() {
        return topAlpha;
    }

    public int getGlowHeightPx() {
        return heightPx;
    }

    public float getStrokePx() {
        return strokePx;
    }

    public float getBlurPx() {
        return blurPx;
    }

    private void buildPath(Rect b) {
        if (b.width() == cachedW && b.height() == cachedH && cachedRadius == radius) {
            return;
        }
        cachedW = b.width();
        cachedH = b.height();
        cachedRadius = radius;
        path.set(SquirclePath.buildTop(b.width(), b.height(), radius));
        path.offset(b.left, b.top);
    }

    private void ensureShader(int top, int bottom) {
        if (shader != null && shaderTop == top && shaderBottom == bottom
                && shaderAlpha == topAlpha) {
            return;
        }
        int solid = (topAlpha << 24) | 0x00FFFFFF;
        shader = new LinearGradient(0f, top, 0f, bottom, solid, 0x00FFFFFF,
                Shader.TileMode.CLAMP);
        shaderTop = top;
        shaderBottom = bottom;
        shaderAlpha = topAlpha;
    }

    /** 把「渐变描边 + 高斯模糊」画进一张小 Bitmap 并缓存 */
    private void ensureBlurCache(Rect b, int bottom) {
        int pad = (int) Math.ceil(blurPx * 1.3f) + (int) Math.ceil(strokePx) + 4;
        int top = b.top - pad;
        int bw = b.width();
        int bh = (bottom - b.top) + pad * 2;
        if (bw <= 0 || bh <= 0) {
            blurCache = null;
            return;
        }
        if (blurCache != null && !blurCache.isRecycled()
                && cacheW == bw && cacheH == bh && cacheTop == top
                && cacheAlpha == topAlpha && cacheBlur == blurPx
                && cacheStroke == strokePx && cacheRadius == radius) {
            return;
        }
        try {
            if (blurCache != null) {
                blurCache.recycle();
            }
            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.translate(-b.left, -top);
            c.clipRect(b.left, b.top, b.right, bottom);
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setStyle(Paint.Style.STROKE);
            bp.setShader(shader);
            // 几何高斯：从外到内叠 8 层同一条描边，每层更宽、整体更淡，
            // 权重按 exp(-2.2t²) 衰减，视觉上就是一条向下散开的柔光。
            // 不依赖 BlurMaskFilter（它在部分硬件 Canvas 上不出效果）。
            int steps = 8;
            for (int i = steps; i >= 1; i--) {
                float tt = i / (float) steps;                 // 1 = 最外层
                float grow = blurPx * tt * 1.2f;              // 向面板内侧外扩的半宽
                int layerAlpha = (int) (255f * 0.85f * (float) Math.exp(-2.2f * tt * tt));
                bp.setStrokeWidth(strokePx * 2f + grow * 2f);
                layerRect.set(b.left, b.top, b.right, bottom);
                int ls = c.saveLayerAlpha(layerRect, Math.max(1, layerAlpha));
                c.drawPath(path, bp);
                c.restoreToCount(ls);
            }
            // 最里面那条：保持原线宽、原透明度，线本身还在
            bp.setStrokeWidth(strokePx * 2f);
            c.drawPath(path, bp);
            blurCache = bmp;
            cacheW = bw;
            cacheH = bh;
            cacheTop = top;
            cacheAlpha = topAlpha;
            cacheBlur = blurPx;
            cacheStroke = strokePx;
            cacheRadius = radius;
        } catch (Throwable t) {
            blurCache = null;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || topAlpha <= 0 || heightPx <= 0 || strokePx <= 0f) {
            return;
        }
        int h = Math.min(heightPx, b.height());
        int bottom = b.top + h;

        ensureShader(b.top, bottom);
        buildPath(b);

        int save = canvas.save();
        // 描边只保留顶部 N px：圆角下半段自然被裁掉
        canvas.clipRect(b.left, b.top, b.right, bottom);

        if (blurPx <= 0.01f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokePx * 2f);
            paint.setShader(shader);
            canvas.drawPath(path, paint);
        } else {
            ensureBlurCache(b, bottom);
            if (blurCache != null && !blurCache.isRecycled()) {
                canvas.drawBitmap(blurCache, b.left, cacheTop, bitmapPaint);
            }
        }
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        // alpha 由 setParams 控制
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

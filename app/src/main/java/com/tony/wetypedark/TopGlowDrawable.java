package com.tony.wetypedark;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 面板顶部「高光描边」：沿面板上边缘画一条细描边，描边自身的颜色是竖向渐变。
 *
 *  - 描边路径 = 面板的 squircle 顶边（左右两个圆角 + 直线段），与外层
 *    TopRoundDrawable 的裁切轮廓同源，所以线正好压在面板内沿上；
 *  - 颜色竖向渐变：y = 0 处是纯白（alpha 由调色台给），到 y = N px 处
 *    变成同一个白的 alpha 0；
 *  - 只保留 y ∈ [0, N] 这一段：直线段是纯白，圆角往下走会自然淡出，
 *    N 以下的描边完全不画。
 */
public final class TopGlowDrawable extends Drawable {

    /** 默认可见线宽（真机 px） */
    private static final float DEFAULT_STROKE_PX = 2f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int topAlpha = 0;
    private int heightPx = 0;
    private float radius = 0f;
    /** 可见线宽（px）：描边有一半落在轮廓外侧被裁掉，所以实际 strokeWidth = 2 倍 */
    private float strokePx = DEFAULT_STROKE_PX;

    private Shader shader;
    private int shaderTop = Integer.MIN_VALUE;
    private int shaderBottom = Integer.MIN_VALUE;
    private int shaderAlpha = Integer.MIN_VALUE;

    private int cachedW = 0;
    private int cachedH = 0;
    private float cachedRadius = -1f;

    public void setParams(int topAlpha, int heightPx, float radius, float strokePx) {
        int alpha = Math.max(0, Math.min(255, topAlpha));
        int h = Math.max(0, heightPx);
        float w = Math.max(0f, Math.min(16f, strokePx));
        boolean changed = this.topAlpha != alpha || this.heightPx != h
                || this.radius != radius || this.strokePx != w;
        this.topAlpha = alpha;
        this.heightPx = h;
        this.radius = radius;
        this.strokePx = w;
        if (changed) {
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

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || topAlpha <= 0 || heightPx <= 0 || strokePx <= 0f) {
            return;
        }
        int h = Math.min(heightPx, b.height());
        int bottom = b.top + h;

        if (shader == null || shaderTop != b.top || shaderBottom != bottom
                || shaderAlpha != topAlpha) {
            int solid = (topAlpha << 24) | 0x00FFFFFF;
            shader = new LinearGradient(0f, b.top, 0f, bottom, solid, 0x00FFFFFF,
                    Shader.TileMode.CLAMP);
            shaderTop = b.top;
            shaderBottom = bottom;
            shaderAlpha = topAlpha;
        }

        buildPath(b);

        int save = canvas.save();
        // 描边只保留顶部 N px：圆角下半段自然被裁掉
        canvas.clipRect(b.left, b.top, b.right, bottom);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokePx * 2f);
        paint.setShader(shader);
        canvas.drawPath(path, paint);
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

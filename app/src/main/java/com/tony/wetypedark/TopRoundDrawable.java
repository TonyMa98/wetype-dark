package com.tony.wetypedark;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 只包装输入法面板原有的背景 Drawable，不改色、不改透明度，
 * 仅把绘制区域裁成「顶部两角圆角、底部直角」。
 * 原 Drawable 的 callback 会被透传为原来的 View，
 * 保证 BottomInsetColorDrawable 读取 paddingBottom 的逻辑不变。
 */
public final class TopRoundDrawable extends Drawable implements Drawable.Callback {

    private final Drawable inner;
    private final Path path = new Path();
    private float radius;
    private int cachedW = 0;
    private int cachedH = 0;
    private float cachedRadius = -1f;

    public TopRoundDrawable(Drawable inner, float radius) {
        this.inner = inner;
        this.radius = radius;
        inner.setCallback(this);
    }

    public void setRadius(float radius) {
        if (this.radius != radius) {
            this.radius = radius;
            cachedW = 0;
            cachedH = 0;
            invalidateSelf();
        }
    }

    public Drawable getInner() {
        return inner;
    }

    private void buildPath() {
        Rect b = getBounds();
        if (b.isEmpty()) {
            path.reset();
            cachedW = 0;
            cachedH = 0;
            return;
        }
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
        if (getBounds().isEmpty()) {
            return;
        }
        buildPath();
        int save = canvas.save();
        canvas.clipPath(path);
        inner.setBounds(getBounds());
        inner.draw(canvas);
        canvas.restoreToCount(save);
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        inner.setBounds(left, top, right, bottom);
        path.reset();
    }

    @Override
    public void setAlpha(int alpha) {
        inner.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        inner.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return inner.getOpacity();
    }

    @Override
    public boolean isStateful() {
        return inner.isStateful();
    }

    @Override
    public boolean setState(int[] stateSet) {
        return inner.setState(stateSet);
    }

    @Override
    public void jumpToCurrentState() {
        inner.jumpToCurrentState();
    }

    @Override
    public boolean getPadding(Rect padding) {
        return inner.getPadding(padding);
    }

    @Override
    public int getIntrinsicWidth() {
        return inner.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return inner.getIntrinsicHeight();
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        unscheduleSelf(what);
    }
}

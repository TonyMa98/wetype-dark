package com.tony.wetypedark;

import android.graphics.Path;

/**
 * 顶部连续曲率圆角路径：用两段 clothoid（曲率 0↔1/r 线性过渡）+ 中间圆弧，
 * 让圆角与直边交界处的曲率连续变化，而不是圆弧那种曲率突变。
 * 只有顶部两角是连续曲率圆角，底部保持直角。
 */
public final class SquirclePath {

    /**
     * 过渡段转角（弧度）。越大越「苹果味」，0.30 ≈ 17°。
     * 取 0.30 是因为曲率过渡后沿直边延伸 ≈ 1.33r，正好对上 ColorOS 模糊层
     * （OplusSmoothRoundedManager.reverseNoWeightRadius 里那个 1.33 系数），
     * 这样模糊层和实色层是同一个圆角形状。
     */
    private static final float THETA = 0.30f;
    private static final int STEPS = 96;

    private SquirclePath() {
    }

    public static Path buildTop(float width, float height, float radius) {
        Path path = new Path();
        if (width <= 0 || height <= 0) {
            return path;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r <= 0.5f) {
            path.addRect(0, 0, width, height, Path.Direction.CW);
            return path;
        }

        float s0 = 2f * r * THETA;
        float arc = r * (float) (Math.PI / 2 - 2 * THETA);
        float total = 2f * s0 + arc;
        float k = 1f / r;

        int n = STEPS;
        float ds = total / n;
        float[] xs = new float[n + 1];
        float[] ys = new float[n + 1];

        float x = r + s0;
        float y = 0f;
        float phi = (float) Math.PI;
        xs[0] = x;
        ys[0] = y;

        for (int i = 1; i <= n; i++) {
            float sm = (i - 0.5f) * ds;
            float kap;
            if (sm < s0) {
                kap = -k * (sm / s0);
            } else if (sm < s0 + arc) {
                kap = -k;
            } else {
                float t = (sm - s0 - arc) / s0;
                kap = -k * (1f - Math.min(t, 1f));
            }
            x += (float) Math.cos(phi) * ds;
            y += (float) Math.sin(phi) * ds;
            phi += kap * ds;
            xs[i] = x;
            ys[i] = y;
        }

        float ex = xs[n];
        float ey = ys[n];
        float extent = r + s0 - ex;
        float scaleY = ey > 0.01f ? extent / ey : 1f;

        // 归一化：起点 (extent, 0)，终点 (0, extent)
        float[] px = new float[n + 1];
        float[] py = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            px[i] = xs[i] - ex;
            py[i] = ys[i] * scaleY;
        }

        // 关键：把曲线沿直边的「支撑长度」缩回配置半径本身。
        // 连续曲率的曲线几何上要比同样半径的圆弧多伸出 ≈33%，不缩回来的话
        // 同样的 24dp 会肉眼看起来比之前大一圈。
        float fit = extent > 0.01f ? r / extent : 1f;
        for (int i = 0; i <= n; i++) {
            px[i] *= fit;
            py[i] *= fit;
        }
        float a = Math.min(r, Math.min(width / 2f, height));
        if (a < r) {
            float scale = a / r;
            for (int i = 0; i <= n; i++) {
                px[i] *= scale;
                py[i] *= scale;
            }
        }

        path.moveTo(a, 0f);
        path.lineTo(width - a, 0f);

        // 右上角（左上角镜像）
        for (int i = 1; i <= n; i++) {
            path.lineTo(width - px[i], py[i]);
        }
        path.lineTo(width, a);

        path.lineTo(width, height);
        path.lineTo(0f, height);
        path.lineTo(0f, a);

        // 左上角（反向）
        for (int i = n - 1; i >= 0; i--) {
            path.lineTo(px[i], py[i]);
        }
        path.close();
        return path;
    }

    /**
     * 四个角都是连续曲率圆角的整块路径（按键那种小圆角用的，采样段少、够轻）。
     *
     * @param steps 每段圆角的采样数，按键 16 段足够，面板用 96 也不卡
     */
    public static Path buildFull(float width, float height, float radius, int steps) {
        Path path = new Path();
        if (width <= 0 || height <= 0) {
            return path;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r <= 0.5f) {
            path.addRect(0, 0, width, height, Path.Direction.CW);
            return path;
        }

        float s0 = 2f * r * THETA;
        float arc = r * (float) (Math.PI / 2 - 2 * THETA);
        float total = 2f * s0 + arc;
        float k = 1f / r;

        int n = Math.max(4, Math.min(steps, 120));
        float ds = total / n;
        float[] xs = new float[n + 1];
        float[] ys = new float[n + 1];

        float x = r + s0;
        float y = 0f;
        float phi = (float) Math.PI;
        xs[0] = x;
        ys[0] = y;
        for (int i = 1; i <= n; i++) {
            float sm = (i - 0.5f) * ds;
            float kap;
            if (sm < s0) {
                kap = -k * (sm / s0);
            } else if (sm < s0 + arc) {
                kap = -k;
            } else {
                float t = (sm - s0 - arc) / s0;
                kap = -k * (1f - Math.min(t, 1f));
            }
            x += (float) Math.cos(phi) * ds;
            y += (float) Math.sin(phi) * ds;
            phi += kap * ds;
            xs[i] = x;
            ys[i] = y;
        }

        float ex = xs[n];
        float ey = ys[n];
        float extent = r + s0 - ex;
        float scaleY = ey > 0.01f ? extent / ey : 1f;

        float[] px = new float[n + 1];
        float[] py = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            px[i] = xs[i] - ex;
            py[i] = ys[i] * scaleY;
        }
        // 支撑长度缩回配置半径（连续曲率多伸 33%，不缩会显得圆角更大）
        float fit = extent > 0.01f ? r / extent : 1f;
        for (int i = 0; i <= n; i++) {
            px[i] *= fit;
            py[i] *= fit;
        }
        // 圆角比键还大时等比收一点，别把整块吃掉
        float a = Math.min(r, Math.min(width / 2f, height / 2f));
        if (a < r) {
            float scale = a / r;
            for (int i = 0; i <= n; i++) {
                px[i] *= scale;
                py[i] *= scale;
            }
        }

        path.moveTo(a, 0f);
        path.lineTo(width - a, 0f);
        for (int i = 1; i <= n; i++) {                     // 右上
            path.lineTo(width - px[i], py[i]);
        }
        path.lineTo(width, height - a);
        for (int i = 1; i <= n; i++) {                     // 右下（x 走切点、y 走曲率，和右上要换轴）
            path.lineTo(width - py[i], height - px[i]);
        }
        path.lineTo(a, height);
        for (int i = 1; i <= n; i++) {                     // 左下
            path.lineTo(px[i], height - py[i]);
        }
        path.lineTo(0f, a);
        for (int i = n - 1; i >= 0; i--) {                 // 左上
            path.lineTo(px[i], py[i]);
        }
        path.close();
        return path;
    }
}

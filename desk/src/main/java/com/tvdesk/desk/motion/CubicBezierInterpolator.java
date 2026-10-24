package com.tvdesk.desk.motion;

import android.view.animation.Interpolator;

/**
 * 自己实现的 cubic-bezier，避免 {@code android.view.animation.PathInterpolator}
 * 的 API 21 限制（本项目 minSdk 19）。
 *
 * <p>桌面所有焦点动画统一走这条曲线：{@code cubic-bezier(0.2, 0, 0, 1)}，
 * 起步快、收尾稳，是 Apple TV 遥控器手感的关键。
 */
public class CubicBezierInterpolator implements Interpolator {

    /** Apple TV 档默认曲线 */
    public static final CubicBezierInterpolator EASE_OUT_STRONG =
            new CubicBezierInterpolator(0.2f, 0f, 0f, 1f);

    private final float x1;
    private final float y1;
    private final float x2;
    private final float y2;

    public CubicBezierInterpolator(float x1, float y1, float x2, float y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    @Override
    public float getInterpolation(float input) {
        if (input <= 0f) return 0f;
        if (input >= 1f) return 1f;
        return sampleY(solveT(input));
    }

    /** 三分量贝塞尔在参数 t 处的 x */
    private float sampleX(float t) {
        float mt = 1f - t;
        return 3f * mt * mt * t * x1 + 3f * mt * t * t * x2 + t * t * t;
    }

    /** 三分量贝塞尔在参数 t 处的 y */
    private float sampleY(float t) {
        float mt = 1f - t;
        return 3f * mt * mt * t * y1 + 3f * mt * t * t * y2 + t * t * t;
    }

    /** 给定 x，牛顿迭代求 t */
    private float solveT(float x) {
        float t = x;
        for (int i = 0; i < 8; i++) {
            float currentX = sampleX(t) - x;
            if (Math.abs(currentX) < 1e-5f) break;
            float dx = derivativeX(t);
            if (Math.abs(dx) < 1e-6f) break;
            t -= currentX / dx;
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;
        }
        return t;
    }

    private float derivativeX(float t) {
        float mt = 1f - t;
        return 3f * mt * mt * x1
                + 6f * mt * t * (x2 - x1)
                + 3f * t * t * (1f - x2);
    }
}

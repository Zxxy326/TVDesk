package com.tvdesk.desk.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 圆形唱片封面<b>外围的一圈动效</b>。
 *
 * <p>取代原来那台黑胶机（{@code bg_vinyl_turntable} + {@code bg_vinyl_disc}）：
 * 盘不要了，封面直接做成大圆，外面套三层光：
 * <ul>
 *   <li>静态细环 —— 给封面一个边界，不然大圆浮在深色背景上会糊；</li>
 *   <li>旋转渐变弧 —— 8 秒一圈，低频（bass）越强转得越亮，是这层的主要动效；</li>
 *   <li>呼吸光环 —— 外扩的一圈柔光，随 bass 一起一伏。</li>
 * </ul>
 *
 * <p>自转由内部 {@link ValueAnimator} 驱动（不是页面 tick），暂停/离开页面时停，
 * 避免不可见时还在空转耗电。频谱读 {@link SpectrumTap}，没有采样时就是匀速呼吸，
 * 不会崩也不会停。
 */
public class DiscHaloView extends View {

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    /** 酒红偏粉，和播放页那间「房间」底色是一套 */
    private int accent = 0xFFC98A92;

    private float phase;
    private float density = 1f;
    private SweepGradient arcShader;
    private ValueAnimator spin;

    public DiscHaloView(Context context) {
        this(context, null);
    }

    public DiscHaloView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.2f));
        ringPaint.setColor(0x33FFFFFF);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(3f));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(14f));

        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void setAccent(int color) {
        accent = color;
        arcShader = null;
        invalidate();
    }

    /** 开始自转（跟着播放状态走，不是一直转） */
    public void startSpin() {
        if (spin != null && spin.isRunning()) {
            return;
        }
        spin = ValueAnimator.ofFloat(0f, 1f);
        spin.setDuration(8000L);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                phase = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        spin.start();
    }

    public void stopSpin() {
        if (spin != null) {
            spin.cancel();
            spin = null;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpin();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        buildShader(w, h);
    }

    private void buildShader(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        arcShader = new SweepGradient(cx, cy,
                new int[]{0x00000000, withAlpha(accent, 30), withAlpha(accent, 210), 0x00000000},
                new float[]{0f, 0.55f, 0.90f, 1f});
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        // 留 24dp 余量：呼吸光晕最外沿要落在控件边界内，不然会被父容器裁掉一圈
        float baseR = Math.min(w, h) / 2f - dp(24f);
        float bass = clamp(SpectrumTap.bass);
        float mid = clamp(SpectrumTap.mid);

        // 1) 呼吸外环：越响越亮、越往外扩
        float glowR = baseR + dp(2f) + dp(10f) * bass;
        glowPaint.setColor(withAlpha(accent, (int) (48 + 90 * bass)));
        canvas.drawCircle(cx, cy, glowR, glowPaint);

        // 2) 静态细环
        canvas.drawCircle(cx, cy, baseR, ringPaint);

        // 3) 旋转渐变弧：一圈 8 秒，bass 越强弧越亮
        if (arcShader == null) {
            buildShader(w, h);
        }
        arcPaint.setShader(arcShader);
        arcPaint.setAlpha((int) (150 + 105 * bass));
        oval.set(cx - baseR, cy - baseR, cx + baseR, cy + baseR);
        canvas.save();
        canvas.rotate(phase * 360f, cx, cy);
        canvas.drawArc(oval, -90f, 300f, false, arcPaint);
        canvas.restore();

        // 4) 三颗沿环慢转的小点（反向，o 比弧慢，形成层次）
        float dotR = baseR + dp(1f);
        if (mid > 0.02f) {
            dotPaint.setColor(withAlpha(accent, (int) (90 + 120 * mid)));
        } else {
            dotPaint.setColor(withAlpha(accent, 110));
        }
        for (int i = 0; i < 3; i++) {
            double ang = Math.toRadians(-phase * 360f * 0.55f + i * 120f);
            float dx = cx + (float) Math.cos(ang) * dotR;
            float dy = cy + (float) Math.sin(ang) * dotR;
            canvas.drawCircle(dx, dy, dp(2.2f), dotPaint);
        }
    }

    private static float clamp(float v) {
        if (v < 0f) {
            return 0f;
        }
        return Math.min(v, 1f);
    }

    private static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private float dp(float v) {
        return v * density;
    }
}

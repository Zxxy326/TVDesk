package com.tvdesk.desk.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

/**
 * 动感涟漪 —— 对照 MuYin 的 {@code WaveLyricsEffect} 的 Java 实现。
 *
 * <p>参数照搬（960dp 画布 → 我们用屏宽的百分比，所以不用换算）：
 * <ul>
 *   <li>6 个环槽，5 个常开 + 第 6 个只在低频冲过 0.55 时补一刀；</li>
 *   <li>带宽 band = w × 0.075 × (1 + 0.6×mid)；最大半径 maxR = w × 0.40；</li>
 *   <li>相位速度 speed = 0.18 + 0.26×bass + 0.42×mid + 0.22×咬字脉冲（每秒圈数）；</li>
 *   <li>亮度 loud = 0.60 + 0.55×bass + 0.30×mid；静音也有 0.60 保底，不会死掉；</li>
 *   <li>中心一颗「当前歌词首字」的巨大水印，透明度 0.20。</li>
 * </ul>
 *
 * <p>歌词是整行居中 + 从左往右的“已唱”遮罩（LRC 只有行级时间，没有逐字时间轴，
 * MuYin 在这种情况也是按行时长匀速扫过）。
 */
public class RippleStageView extends View {

    private static final int RING_SLOTS = 6;
    private static final int RING_BASE = 5;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lyricPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sungPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int accent = 0xFF6E8BFF;
    private String lyric = "";
    private float progress;
    private float phase;
    private float pulse;
    private long lastFrame;

    public RippleStageView(Context context) {
        this(context, null);
    }

    public RippleStageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        markPaint.setFakeBoldText(true);
        lyricPaint.setColor(Color.WHITE);
        lyricPaint.setFakeBoldText(true);
        sungPaint.setColor(Color.WHITE);
        sungPaint.setFakeBoldText(true);
    }

    public void setAccent(int color) {
        accent = color;
        invalidate();
    }

    public void setLyric(String text, float done) {
        if (!TextUtils.equals(text, lyric)) {
            lyric = text == null ? "" : text;
            invalidate();
        }
        progress = Math.max(0f, Math.min(1f, done));
    }

    /** 每帧从页面喂进来（和 MuYin 的 withFrameNanos 同一个位置） */
    public void tick(float wordPulse) {
        pulse = wordPulse;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0f : (now - lastFrame) / 1000f;
        lastFrame = now;
        float speed = 0.18f + 0.26f * SpectrumTap.bass + 0.42f * SpectrumTap.mid + 0.22f * pulse;
        phase = (phase + dt * Math.min(0.85f, speed)) % 1f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        bgPaint.setShader(new LinearGradient(0, 0, 0, h,
                blend(accent, 0xFF080B14, 0.58f), blend(accent, 0xFF080B14, 0.68f),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, bgPaint);

        float band = w * 0.075f * (1f + 0.60f * SpectrumTap.mid);
        float maxR = w * 0.40f;
        float loud = 0.60f + 0.55f * SpectrumTap.bass + 0.30f * SpectrumTap.mid;
        float extra = Math.max(0f, Math.min(1f, (SpectrumTap.bass - 0.55f) / 0.35f));
        float[] centers = {-0.02f * w, 1.02f * w};
        for (float cx : centers) {
            for (int i = 0; i < RING_SLOTS; i++) {
                float offset = i < RING_BASE ? i / (float) RING_BASE : 0.1f;
                float gate = i < RING_BASE ? 1f : extra;
                if (gate <= 0.01f) {
                    continue;
                }
                float p = (phase + offset) % 1f;
                float radius = maxR * p;
                float fadeIn = Math.min(1f, p / 0.22f);
                float fadeOut = 1f - Math.max(0f, Math.min(1f, (p - 0.62f) / 0.38f));
                float alpha = 0.78f * loud * gate * fadeIn * fadeIn * fadeOut * fadeOut;
                if (alpha <= 0.004f) {
                    continue;
                }
                float var = (i % 2 == 0 ? 1f : 0.78f) * (0.80f + 0.40f * SpectrumTap.high);
                float outer = radius + band * 0.5f;
                if (outer <= 1f) {
                    continue;
                }
                ringPaint.setShader(new RadialGradient(cx, h * 0.5f, outer,
                        new int[]{0x00000000,
                                withAlpha(blend(accent, Color.WHITE, 0.12f), 0),
                                withAlpha(blend(accent, Color.WHITE, 0.12f), (int) (alpha * 100 * var)),
                                withAlpha(blend(accent, Color.WHITE, 0.46f), (int) (alpha * 230 * var)),
                                withAlpha(blend(accent, Color.WHITE, 0.12f), (int) (alpha * 90 * var)),
                                0x00000000},
                        new float[]{0f, 0.62f, 0.72f, 0.80f, 0.90f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, h * 0.5f, outer, ringPaint);
            }
        }

        // 中心水印：当前歌词的第一个字
        String mark = lyric.isEmpty() ? "" : lyric.substring(0, 1);
        if (!mark.isEmpty()) {
            markPaint.setColor(withAlpha(accent, 51));
            markPaint.setTextSize(h * 0.92f);
            float tw = markPaint.measureText(mark);
            canvas.drawText(mark, (w - tw) / 2f, h * 0.5f + h * 0.32f, markPaint);
        }

        // 歌词：整行居中，左边已唱部分用纯白盖一层
        if (!lyric.isEmpty()) {
            lyricPaint.setTextSize(h * 0.075f);
            sungPaint.setTextSize(h * 0.075f);
            float tw = lyricPaint.measureText(lyric);
            float x = (w - tw) / 2f;
            float y = h * 0.5f;
            lyricPaint.setColor(0x99FFFFFF);
            canvas.drawText(lyric, x, y, lyricPaint);
            canvas.save();
            canvas.clipRect(x, 0, x + tw * progress, h);
            canvas.drawText(lyric, x, y, sungPaint);
            canvas.restore();
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return Color.rgb((int) (ar + (br - ar) * t), (int) (ag + (bg - ag) * t),
                (int) (ab + (bb - ab) * t));
    }
}

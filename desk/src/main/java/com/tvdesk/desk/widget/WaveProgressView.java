package com.tvdesk.desk.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 播放进度条。名字还叫 Wave，是为了不再动布局和调用方；
 * 画法已经换成普通直条——电视盒子的 GPU 很弱，整条波浪每帧重算 path 会掉帧，
 * 而进度条本来只需要回答「播到哪儿了」。
 *
 * <p>接口和 {@link android.widget.ProgressBar} 的 {@link #setMax(int)} /
 * {@link #setProgress(int)} 一致，调用方按千分比给值即可。
 */
public class WaveProgressView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint donePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int max = 1000;
    private int progress;
    private float stroke;
    private float knobRadius;

    public WaveProgressView(Context context) {
        this(context, null);
    }

    public WaveProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        stroke = 4 * density;
        knobRadius = 5 * density;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(0x33FFFFFF);
        donePaint.setStyle(Paint.Style.STROKE);
        donePaint.setStrokeWidth(stroke);
        donePaint.setStrokeCap(Paint.Cap.ROUND);
        donePaint.setColor(0xFFFFFFFF);
        knobPaint.setStyle(Paint.Style.FILL);
        knobPaint.setColor(0xFFFFFFFF);
    }

    public void setMax(int value) {
        if (value > 0 && value != max) {
            max = value;
            invalidate();
        }
    }

    /** 换配色：B 站播放页用品牌粉走进度，音乐页保持默认的白。圆点一直是白的。 */
    public void setColors(int trackColor, int progressColor) {
        trackPaint.setColor(trackColor);
        donePaint.setColor(progressColor);
        invalidate();
    }

    /** 同样的值不重画：进度是 0.3 秒对一次的，一半的对次其实没动 */
    public void setProgress(int value) {
        int clamped = value < 0 ? 0 : (value > max ? max : value);
        if (clamped != progress) {
            progress = clamped;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(knobRadius * 2 + stroke);
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        if (width <= 0) {
            return;
        }
        float cy = getHeight() / 2f;
        float half = stroke / 2f;
        rect.set(half, cy - half, width - half, cy + half);
        canvas.drawRoundRect(rect, half, half, trackPaint);

        float ratio = max <= 0 ? 0f : (float) progress / max;
        ratio = Math.max(0f, Math.min(1f, ratio));
        if (ratio <= 0f) {
            return;
        }
        float endX = half + (width - stroke) * ratio;
        canvas.drawLine(half, cy, endX, cy, donePaint);
        canvas.drawCircle(endX, cy, knobRadius, knobPaint);
    }
}

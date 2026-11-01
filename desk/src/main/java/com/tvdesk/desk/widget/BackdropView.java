package com.tvdesk.desk.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 桌面氛围背景。
 *
 * <p>用 Canvas 画三层光：垂直底色 + 右上暖光 + 左下冷光。
 * XML 的 radial gradient 在 oval bounds 上会留硬边，自绘可以自由控制半径和衰减。
 *
 * <p>接入真实数据后，Hero 海报图会盖在这层之上；这层负责兜底和渐变过渡。
 */
public class BackdropView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient base;
    private RadialGradient warm;
    private RadialGradient cool;

    /**
     * 画好的背景位图。
     *
     * <p>三层全屏渐变如果每帧现画，GPU 每帧要把两百多万像素的径向渐变重新算一遍 ——
     * 实测滚动时 GPU 单帧 20~25ms，一半耗在这层静态背景上。这层内容只有尺寸变了才会变，
     * 所以画一次存成位图，之后每帧只贴一张图。
     */
    private android.graphics.Bitmap cache;

    public BackdropView(Context context) {
        super(context);
    }

    public BackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BackdropView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) return;
        // 尺寸变了，渐变也要重建，旧位图作废
        if (cache != null) {
            cache.recycle();
            cache = null;
        }

        base = new LinearGradient(
                0f, 0f, 0f, h,
                new int[]{0xFF1B1526, 0xFF0E0C14, 0xFF050508},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP);

        // 暖光中心放在屏幕右上外侧，衰减半径给足，避免出现可见边界
        warm = new RadialGradient(
                w * 0.86f, h * 0.12f, Math.max(w, h) * 0.92f,
                new int[]{0x5CFF5A2A, 0x2EFF5A2A, 0x00FF5A2A},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP);

        cool = new RadialGradient(
                w * 0.05f, h * 1.02f, Math.max(w, h) * 0.72f,
                new int[]{0x453B7AFF, 0x1A3B7AFF, 0x003B7AFF},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (base == null || w == 0 || h == 0) {
            return;
        }
        if (cache == null || cache.getWidth() != w || cache.getHeight() != h) {
            cache = raster(w, h);
        }
        if (cache != null) {
            canvas.drawBitmap(cache, 0, 0, null);
            return;
        }
        drawLayers(canvas, w, h);
    }

    /** 把三层渐变画进一张位图 */
    private android.graphics.Bitmap raster(int w, int h) {
        try {
            android.graphics.Bitmap bitmap =
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            drawLayers(new Canvas(bitmap), w, h);
            return bitmap;
        } catch (Throwable th) {
            return null;
        }
    }

    private void drawLayers(Canvas canvas, int w, int h) {
        paint.setShader(base);
        canvas.drawRect(0, 0, w, h, paint);

        paint.setShader(warm);
        canvas.drawRect(0, 0, w, h, paint);

        paint.setShader(cool);
        canvas.drawRect(0, 0, w, h, paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (cache != null) {
            cache.recycle();
            cache = null;
        }
        super.onDetachedFromWindow();
    }
}

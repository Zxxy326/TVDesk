package com.tvdesk.desk.widget;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 全局「磨砂背景」快照。
 *
 * <h3>为什么是这张图</h3>
 * 玻璃面板要显示「它背后那块背景」。真做实时模糊（RenderEffect）要求 Android 12+，
 * 而这台电视是 Android 9，GPU 也吃不下 —— 所以换成便宜的做法：
 * 把背景层按 1/6 尺寸画一张小图，用的时候再拉伸回来。
 * 缩小再放大的插值本身就是最便宜的模糊，和音乐页、B 站页的背景图是同一个手法
 * （像素播放器/Aerial TV 的 {@code Wallpapers.frosted} 也是这么做的）。
 *
 * <h3>谁负责更新</h3>
 * 桌面在自己背景变化（换壁纸 / Hero 换片）之后调 {@link #capture(View)}。
 * 面板在 {@link GlassDrawable} 里按自己在窗口里的位置去这张图上取样。
 */
public final class FrostedBackdrop {

    /** 缩小倍数：6 够糊，又不至于糊成一团纯色 */
    private static final int DOWNSCALE = 6;

    private static Bitmap frosted;
    private static int screenW;
    private static int screenH;

    private FrostedBackdrop() {
    }

    /**
     * 把背景层拍成一张磨砂小图。
     *
     * @param layers 从下往上叠的背景层（氛围光 → Hero 海报）。
     *               不要传整页，否则会把文字和图标一起拍进去。
     *               任何一层宽高为 0（还没量出来）就跳过它，别把整张图判死
     */
    public static void capture(View... layers) {
        if (layers == null || layers.length == 0) {
            return;
        }
        // 以第一张有尺寸的层为准
        int w = 0;
        int h = 0;
        for (View v : layers) {
            if (v != null && v.getWidth() > 0 && v.getHeight() > 0) {
                w = v.getWidth();
                h = v.getHeight();
                break;
            }
        }
        if (w <= 0 || h <= 0) {
            return;
        }
        try {
            int sw = Math.max(1, w / DOWNSCALE);
            int sh = Math.max(1, h / DOWNSCALE);
            Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(small);
            canvas.scale(sw / (float) w, sh / (float) h);
            for (View v : layers) {
                if (v == null || v.getWidth() <= 0 || v.getVisibility() != View.VISIBLE) {
                    continue;
                }
                canvas.save();
                // 各层尺寸可能不一样（Hero 是 match_parent，氛围光是整屏），按各自比例缩放
                canvas.scale(v.getWidth() / (float) w, v.getHeight() / (float) h);
                v.draw(canvas);
                canvas.restore();
            }
            frosted = small;
            screenW = w;
            screenH = h;
        } catch (Throwable ignored) {
            // 拍不下来就退回纯色玻璃，不该因此崩页面
        }
    }

    /** 磨砂图；没拍过返回 null（面板会退回纯色玻璃） */
    public static Bitmap get() {
        return frosted;
    }

    /** 拍这张图时的背景尺寸，取样时要按它换算 */
    public static int width() {
        return screenW;
    }

    public static int height() {
        return screenH;
    }

    /** 清掉（退出桌面时不需要了，省内存） */
    public static void clear() {
        frosted = null;
        screenW = 0;
        screenH = 0;
    }

    /** 调试用：把当前磨砂图填满一张画布 */
    public static void fill(Canvas canvas, Paint paint) {
        Bitmap bmp = frosted;
        if (bmp == null || canvas == null) {
            return;
        }
        canvas.drawBitmap(bmp, null,
                new android.graphics.Rect(0, 0, canvas.getWidth(), canvas.getHeight()), paint);
    }
}

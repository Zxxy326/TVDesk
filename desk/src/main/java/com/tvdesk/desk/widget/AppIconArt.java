package com.tvdesk.desk.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * 应用图标画成「瓷砖」。
 *
 * <p>移植自 Aerial TV 的 {@code apps/TileArt.kt} + {@code ui/components/SmoothCorners.kt}。
 * 那个项目不直接铺应用自带的 drawable，而是把图标重新合成到一块瓷砖上：
 * <ul>
 *   <li>图标本来就是满幅方图（大多数国产 TV 应用）→ 原样铺满，只把四角裁成连续圆角；</li>
 *   <li>图标是透明底的 logo（老式 legacy 图标）→ 先照它的主色铺一块上下渐变的底板，
 *       再把 logo 按「面积」缩到安全区内居中，这样一屏里圆的和方的 logo 视觉重量一样；</li>
 *   <li>四角用 iOS 那种连续圆角（超椭圆），不是普通圆弧 —— 普通圆弧和直边的交界处
 *       有一道肉眼可见的「折角」，铺满一屏时特别明显。</li>
 * </ul>
 *
 * <p>瓷砖上再补两笔：上沿一道白高光、一圈 1px 内描边。这是为了和桌面顶栏那套玻璃语言对齐 ——
 * 不然玻璃胶囊旁边排一片纯平面的图标，看着像两个时代的界面。
 *
 * <p>成品按包名缓存：图标解码 + 合成一次要几十毫秒，滚动时每帧重做会卡。
 */
public final class AppIconArt {

    /** 统一画成这个边长的方图，显示时由 ImageView 缩放 */
    private static final int TILE = 192;

    /** 圆角占边长的比例。iOS 图标是 22.37%，用同一档，一排图标看上去才像同一套 */
    private static final float CORNER_RATIO = 0.2237f;

    /** 透明底图标在瓷砖上的占位（面积意义上的「直径」），取自 Aerial TV 的 LOGO 常量 */
    private static final float LOGO = 0.5f;

    /** 连续圆角的平滑度：0 是普通圆弧，0.6 是 Figma 说的 iOS 那一档 */
    private static final float SMOOTHING = 0.6f;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(96) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private AppIconArt() {
    }

    /**
     * 取一块画好的瓷砖。
     *
     * @param key  缓存键（包名；包更新后要换 key，否则会拿到旧图）
     * @param icon 应用图标（越清晰越好，调用方给最密那档）
     * @return 瓷砖；图标为空时返回 null，调用方照旧显示占位
     */
    public static Drawable tile(Context context, String key, Drawable icon) {
        if (icon == null) {
            return null;
        }
        Bitmap cached = key == null ? null : CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            return new BitmapDrawable(context.getResources(), cached);
        }
        Bitmap tile;
        try {
            tile = render(icon);
        } catch (Throwable th) {
            // 合成失败就用原图，不该因为一个怪图标整页空掉
            return icon;
        }
        if (tile == null) {
            return icon;
        }
        if (key != null) {
            CACHE.put(key, tile);
        }
        return new BitmapDrawable(context.getResources(), tile);
    }

    /** 清掉某个包的缓存（卸载/更新后调用） */
    public static void forget(String key) {
        if (key != null) {
            CACHE.remove(key);
        }
    }

    private static Bitmap render(Drawable icon) {
        Bitmap out = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        int[] sample = sample(icon, 24);
        boolean fullBleed = isFullBleed(sample, 24);

        if (fullBleed) {
            // 满幅图标：原样铺满整块瓷砖
            icon.setBounds(0, 0, TILE, TILE);
            icon.draw(canvas);
        } else {
            int[] plate = plateColors(sample, 24);
            Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            platePaint.setShader(new LinearGradient(0f, 0f, 0f, TILE,
                    plate[0], plate[1], Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, TILE, TILE, platePaint);

            // logo 按面积缩到安全区内居中；给一点外边距，聚焦放大时也不会顶到圆角
            int size = Math.round(TILE * LOGO + TILE * (1f - LOGO) * 0.42f);
            int left = (TILE - size) / 2;
            icon.setBounds(left, left, left + size, left + size);
            icon.draw(canvas);
        }

        applySquircle(out);
        addGlassEdge(out);
        return out;
    }

    /** 把四角裁成连续圆角：先铺一层圆角遮罩，再用 DST_IN 把外面挖掉 */
    private static void applySquircle(Bitmap out) {
        Bitmap mask = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(mask);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        maskCanvas.drawPath(squircle(0f, 0f, TILE, TILE, TILE * CORNER_RATIO), paint);

        Canvas canvas = new Canvas(out);
        Paint xfer = new Paint(Paint.ANTI_ALIAS_FLAG);
        xfer.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(mask, 0f, 0f, xfer);
        mask.recycle();
    }

    /** 上沿高光 + 内描边，和顶栏胶囊同一套光 */
    private static void addGlassEdge(Bitmap out) {
        Canvas canvas = new Canvas(out);
        Path shape = squircle(0f, 0f, TILE, TILE, TILE * CORNER_RATIO);

        int save = canvas.save();
        canvas.clipPath(shape);
        Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        gloss.setShader(new LinearGradient(0f, 0f, 0f, TILE * 0.5f,
                0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, TILE, TILE * 0.5f, gloss);
        canvas.restoreToCount(save);

        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f);
        edge.setColor(0x33FFFFFF);
        canvas.drawPath(shape, edge);
    }

    // ---------------------------------------------------------------- 判定

    /** 图标是不是「满幅方图」：四个角和主体一样实心就说明它自带底板 */
    private static boolean isFullBleed(int[] px, int n) {
        int corner = 0;
        int[][] corners = {{0, 0}, {n - 1, 0}, {0, n - 1}, {n - 1, n - 1}};
        for (int[] c : corners) {
            int color = px[c[1] * n + c[0]];
            if ((color >>> 24) > 200) {
                corner++;
            }
        }
        if (corner < 3) {
            return false;
        }
        int solid = 0;
        for (int color : px) {
            if ((color >>> 24) > 200) {
                solid++;
            }
        }
        // 实心面积要够大：一个圆标只有 79%，一个圆角方标约 96%
        return solid > px.length * 0.9f;
    }

    /**
     * 透明底图标要铺什么颜色的底板。
     *
     * <p>取图标里实心像素的平均色，再把饱和度和亮度夹到中间档 ——
     * 直接用平均色的话，白底黑字的 logo 会得到一块灰板，深色 logo 会得到一块近黑的板。
     * 夹完就都是「同一个 app 的色调，但够亮能压住白 logo」。
     */
    private static int[] plateColors(int[] px, int n) {
        long r = 0;
        long g = 0;
        long b = 0;
        int count = 0;
        for (int color : px) {
            if ((color >>> 24) < 200) {
                continue;
            }
            r += (color >> 16) & 0xFF;
            g += (color >> 8) & 0xFF;
            b += color & 0xFF;
            count++;
        }
        if (count == 0) {
            return new int[]{0xFF3A3A46, 0xFF26262E};
        }
        float[] hsl = rgbToHsl(r / count, g / count, b / count);
        float s = Math.max(0.45f, Math.min(0.85f, hsl[1]));
        float l = Math.max(0.40f, Math.min(0.56f, hsl[2]));
        return new int[]{hslToRgb(hsl[0], s, Math.min(1f, l + 0.08f)),
                hslToRgb(hsl[0], s, Math.max(0f, l - 0.08f))};
    }

    /** 把 drawable 画成 n×n 的像素采样；画不出来就给一张全透明 */
    private static int[] sample(Drawable icon, int n) {
        int[] px = new int[n * n];
        try {
            Bitmap small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(small);
            icon.setBounds(0, 0, n, n);
            icon.draw(canvas);
            small.getPixels(px, 0, n, 0, 0, n, n);
            small.recycle();
        } catch (Throwable ignored) {
            // 有的 drawable 画到软件 canvas 上会抛，采不到就当透明处理
        }
        return px;
    }

    // ---------------------------------------------------------------- 连续圆角

    /**
     * 连续圆角路径（超椭圆）。
     *
     * <p>直边进圆弧的那一点，普通圆角是「直线突然变成圆弧」，曲率突变，眼睛能看到一折；
     * 连续圆角在两个 cubic 过渡里把曲率从 0 慢慢升上去，所以看着是顺的。
     * 数学来自 Aerial TV 的 SmoothCorner（Figma 的 corner smoothing 算法）。
     * 这个形状完全落在同半径的普通圆角矩形内，所以外面还能照常按普通圆角裁剪。
     */
    static Path squircle(float l, float t, float r, float b, float radius) {
        Path path = new Path();
        Corner top = new Corner(radius, SMOOTHING, Math.min(r - l, b - t) / 2f);
        Corner bot = top;
        path.moveTo(l + top.p, t);
        top.addTo(path, r, t, 1f, 0f, 0f, 1f);
        bot.addTo(path, r, b, 0f, 1f, -1f, 0f);
        bot.addTo(path, l, b, -1f, 0f, 0f, -1f);
        top.addTo(path, l, t, 0f, -1f, 1f, 0f);
        path.close();
        return path;
    }

    /** 一个角的几何参数与绘制 */
    private static final class Corner {
        private final float p;
        private final float r;
        private final float a;
        private final float b;
        private final float c;
        private final float d;
        private final float arc;

        Corner(float radius, float smoothing, float budget) {
            float s = smoothing;
            // 空间不够时先放弃平滑，再放弃半径
            if (radius > 0f && (1f + s) * radius > budget) {
                s = Math.max(0f, Math.min(s, budget / radius - 1f));
            }
            r = Math.max(0f, Math.min(radius, budget));
            p = Math.min((1f + s) * r, budget);
            arc = (float) (Math.PI / 2 * (1f - s));
            double arcLen = Math.sin(arc / 2.0) * r * Math.sqrt(2f);
            double alpha = (Math.PI / 2 - arc) / 2;
            double p3p4 = r * Math.tan(alpha / 2);
            double beta = Math.PI / 4 * s;
            c = (float) (p3p4 * Math.cos(beta));
            d = (float) (c * Math.tan(beta));
            b = (float) ((p - arcLen - c - d) / 3f);
            a = 2f * b;
        }

        /**
         * 加一个角。
         *
         * @param vx 角点 x
         * @param vy 角点 y
         * @param ux 入边方向
         * @param uy
         * @param vx2 出边方向
         * @param vy2
         */
        void addTo(Path path, float vx, float vy, float ux, float uy, float vx2, float vy2) {
            if (r <= 0f) {
                path.lineTo(vx, vy);
                return;
            }
            path.lineTo(vx + ux * -p, vy + uy * -p);
            cubic(path, vx, vy, ux, uy, vx2, vy2,
                    -p + a, 0f, -p + a + b, 0f, -p + a + b + c, d);

            float f0 = (float) (-Math.PI / 4 - arc / 2);
            float f1 = (float) (-Math.PI / 4 + arc / 2);
            float k = (float) (4f / 3f * Math.tan(arc / 4) * r);
            float e0x = (float) (-r + r * Math.cos(f0));
            float e0y = (float) (r + r * Math.sin(f0));
            float e1x = (float) (-r + r * Math.cos(f1));
            float e1y = (float) (r + r * Math.sin(f1));
            cubic(path, vx, vy, ux, uy, vx2, vy2,
                    e0x - k * (float) Math.sin(f0), e0y + k * (float) Math.cos(f0),
                    e1x + k * (float) Math.sin(f1), e1y - k * (float) Math.cos(f1),
                    e1x, e1y);

            cubic(path, vx, vy, ux, uy, vx2, vy2,
                    0f, p - a - b, 0f, p - a, 0f, p);
        }

        private static void cubic(Path path, float vx, float vy, float ux, float uy,
                float vx2, float vy2, float x1, float y1, float x2, float y2, float x3, float y3) {
            path.cubicTo(vx + ux * x1 + vx2 * y1, vy + uy * x1 + vy2 * y1,
                    vx + ux * x2 + vx2 * y2, vy + uy * x2 + vy2 * y2,
                    vx + ux * x3 + vx2 * y3, vy + uy * x3 + vy2 * y3);
        }
    }

    // ---------------------------------------------------------------- 颜色

    private static float[] rgbToHsl(float r, float g, float b) {
        r /= 255f;
        g /= 255f;
        b /= 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f;
        float h;
        float s;
        if (max == min) {
            h = 0f;
            s = 0f;
        } else {
            float delta = max - min;
            s = l > 0.5f ? delta / (2f - max - min) : delta / (max + min);
            if (max == r) {
                h = (g - b) / delta + (g < b ? 6f : 0f);
            } else if (max == g) {
                h = (b - r) / delta + 2f;
            } else {
                h = (r - g) / delta + 4f;
            }
            h /= 6f;
        }
        return new float[]{h, s, l};
    }

    private static int hslToRgb(float h, float s, float l) {
        float r;
        float g;
        float b;
        if (s == 0f) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
            float p = 2f * l - q;
            r = hue(p, q, h + 1f / 3f);
            g = hue(p, q, h);
            b = hue(p, q, h - 1f / 3f);
        }
        return 0xFF000000
                | (Math.round(r * 255f) << 16)
                | (Math.round(g * 255f) << 8)
                | Math.round(b * 255f);
    }

    private static float hue(float p, float q, float t) {
        float value = t;
        if (value < 0f) {
            value += 1f;
        }
        if (value > 1f) {
            value -= 1f;
        }
        if (value < 1f / 6f) {
            return p + (q - p) * 6f * value;
        }
        if (value < 1f / 2f) {
            return q;
        }
        if (value < 2f / 3f) {
            return p + (q - p) * (2f / 3f - value) * 6f;
        }
        return p;
    }
}

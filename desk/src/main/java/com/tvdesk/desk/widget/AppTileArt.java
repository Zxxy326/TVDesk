package com.tvdesk.desk.widget;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;

import androidx.core.graphics.ColorUtils;

/**
 * 应用「磁贴」画成 Aerial TV 的样子（16:9 横板）。
 *
 * <p>忠实移植自 Aerial TV 的 {@code apps/TileArt.kt}（TileArtFactory）。那条流水线把
 * 一个应用的图标 / 横幅重新合成为一块 tvOS 风格的横板磁贴：
 * <ul>
 *   <li><b>横幅（banner）</b>：应用专门给电视做的图，直接盖满磁贴；</li>
 *   <li><b>自适应图标（API 26+）</b>：底图层盖满磁贴，前景 logo 单独一层；「统一」模式下
 *       logo 转白、底板取 app 主色拉成渐变，跟 tvOS 画自家应用一个手法；</li>
 *   <li><b>板式图标</b>（国产应用最常见的「圆角方板 + logo」）：板色沿上下边取中位数，
 *       拉通整张磁贴做上下渐变，logo 按「与板色的色距」抠出来、边缘羽化融进板色；</li>
 *   <li><b>老式透明底图标</b>：按主色铺渐变，logo 转白居中；</li>
 *   <li>logo 大小按<b>面积</b>折算（等价边长 = 磁贴高的 0.5），宽 logo 和圆标视觉重量一致。</li>
 * </ul>
 *
 * <p>磁贴尺寸固定 352×198（147dp×82.8dp 在 1080p 下的像素，Aerial 的取值），
 * 显示时由视图缩放；四角按 Aerial 的圆角（12dp / 82.8dp 高）做连续圆角遮罩。
 * 成品按「包名@更新时间」缓存——合成一次几十毫秒，滚动时不能重做。
 */
public final class AppTileArt {

    private static final int W = 352;
    private static final int H = 198;

    /** logo 的面积等价边长，占磁贴高的比例（TileArtFactory.LOGO） */
    private static final float LOGO = 0.5f;

    /** logo 采样分辨率 */
    private static final int SAMPLE = 256;

    private static final int GRAPHITE_TOP = 0xFF3A3A3C;
    private static final int GRAPHITE_BOTTOM = 0xFF242426;

    /** 磁贴圆角：12dp / 82.8dp 高（AppTile.Corner）折算到这张图上 */
    private static final float CORNER_RADIUS = H * (12f / 82.8f);
    private static final float SMOOTHING = 0.6f;

    /**
     * 磁贴缓存。
     *
     * <p><b>容量单位是 {@link #sizeOf} 的返回值（字节），不是「几张」。</b>
     * 之前这里写 80 —— 等于整个缓存只有 80 字节，一块横幅磁贴就 1.1MB，
     * 于是每张卡每次绑定都重新解码图标、重新合成（几十毫秒），翻页时就抖。
     * 32MB 够放三十来张 704×396 的成品。
     */
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private AppTileArt() {
    }

    /**
     * 取一块画好的磁贴。
     *
     * @param key    缓存键（包名@更新时间）
     * @param banner 应用的电视横幅（没有就传 null）
     * @param icon   应用图标
     */
    public static Bitmap tile(String key, Drawable banner, Drawable icon) {
        Bitmap cached = key == null ? null : CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Bitmap art;
        try {
            art = render(banner, icon);
        } catch (Throwable th) {
            art = colorTile(GRAPHITE_TOP, GRAPHITE_BOTTOM);
        }
        if (key != null) {
            CACHE.put(key, art);
        }
        return art;
    }

    /** 清掉某个包的缓存（卸载 / 更新后调用） */
    public static void forget(String key) {
        if (key != null) {
            CACHE.remove(key);
        }
    }

    // ---------------------------------------------------------------- 入口分发

    private static Bitmap render(Drawable banner, Drawable icon) {
        if (banner != null) {
            int iw = banner.getIntrinsicWidth();
            int ih = banner.getIntrinsicHeight();
            // 「横幅」得真的是横的：方的横幅是把方图标再声明一遍，盖满会把 logo 撑出边
            if (iw > 0 && ih > 0 && iw >= ih * 4 / 3) {
                return mask(fromBanner(banner));
            }
        }
        if (icon == null) {
            return mask(colorTile(GRAPHITE_TOP, GRAPHITE_BOTTOM));
        }
        return mask(fromIcon(icon));
    }

    private static Bitmap fromIcon(Drawable icon) {
        if (Build.VERSION.SDK_INT >= 26 && icon instanceof AdaptiveIconDrawable
                && ((AdaptiveIconDrawable) icon).getForeground() != null) {
            Bitmap art = adaptive((AdaptiveIconDrawable) icon);
            if (art != null) {
                return art;
            }
        }
        Bitmap art = plate(icon);
        if (art != null) {
            return art;
        }
        return legacy(icon);
    }

    // ---------------------------------------------------------------- 横幅

    private static Bitmap fromBanner(Drawable banner) {
        Bitmap src = banner instanceof BitmapDrawable ? ((BitmapDrawable) banner).getBitmap() : null;
        // 原图尺寸：位图用自己的像素，其余（LayerDrawable / AdaptiveIconDrawable…）
        // 用 intrinsic —— 之前这里退回磁贴尺寸，于是横幅被当成「和磁贴一样大」
        int sw = src != null ? src.getWidth() : banner.getIntrinsicWidth();
        int sh = src != null ? src.getHeight() : banner.getIntrinsicHeight();
        if (sw <= 0 || sh <= 0) {
            sw = W;
            sh = H;
        }
        // 画布跟着横幅走（最多两倍磁贴宽），这样一屏只重采样一次就够清晰
        int w = Math.min(sw, 2 * W);
        int h = w * 9 / 16;
        Bitmap back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(back);
        canvas.drawColor(GRAPHITE_BOTTOM);
        cover(canvas, banner, w, h, sw, sh);
        return back;
    }

    /** [iw]×[ih] 是 d 的原始尺寸：位图用自己的像素，其他按声明的 intrinsic */
    private static void cover(Canvas canvas, Drawable d, int w, int h, int iw, int ih) {
        float scale = Math.max(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * scale);
        int dh = Math.round(ih * scale);
        int l = (w - dw) / 2;
        int t = (h - dh) / 2;
        d.setBounds(l, t, l + dw, t + dh);
        d.draw(canvas);
    }

    // ---------------------------------------------------------------- 自适应图标

    private static Bitmap adaptive(AdaptiveIconDrawable icon) {
        Drawable fore = icon.getForeground();
        int[] fg = raster(fore, SAMPLE);
        RectF logo = visible(fg, SAMPLE);
        // logo 占满整个安全区（>0.7）说明前景把图层当整图用，两层拆开就不成样子了
        if (logo == null || Math.max(logo.width(), logo.height()) > 0.7f) {
            return null;
        }
        Drawable bg = icon.getBackground();
        int[] bgPx = bg != null ? raster(bg, 32) : null;
        if (bgPx != null && !isFlat(bgPx)) {
            return null;
        }
        Placed placed = placeLogo(fore, new LogoFinder() {
            @Override
            public RectF find(int[] px, int n) {
                return visible(px, n);
            }
        });
        if (placed == null) {
            return null;
        }
        int color = GRAPHITE_BOTTOM;
        if (bgPx != null) {
            int mid = bgPx[bgPx.length / 2];
            if (Color.alpha(mid) > 200) {
                color = mid;
            }
        }
        if (multiColor(placed.layer, placed.logo, color)) {
            // 多色 logo：转白会糊成一坨，保留颜色。底图 = 石墨色 + 底图层盖满
            Bitmap back = layer(new CanvasHook() {
                @Override
                public void draw(Canvas canvas) {
                    canvas.drawColor(GRAPHITE_BOTTOM);
                    if (bg != null) {
                        // 底图层是方的：当宽度盖满高 16:9 的磁贴
                        bg.setBounds(0, (H - W) / 2, W, (H - W) / 2 + W);
                        bg.draw(canvas);
                    }
                }
            });
            return compose(back, placed.layer);
        }
        return unify(placed.layer, placed.logo, color, color);
    }

    // ---------------------------------------------------------------- 板式图标

    private static Bitmap plate(Drawable icon) {
        final int[] px = raster(icon, SAMPLE);
        final Rect box = solidBox(px, SAMPLE);
        if (box == null) {
            return null;
        }
        final int l = box.left;
        final int t = box.top;
        final int r = box.right - 1;
        final int b = box.bottom - 1;
        final int w = box.width();
        final int h = box.height();
        // 方板（宽高差 <6%）至少占画布一半，且 90% 以上实心：圆角方板约 96%，圆标只有 79%
        if (w < SAMPLE / 2 || Math.abs(w - h) > w * 0.06f) {
            return null;
        }
        int filled = 0;
        for (int y = t; y <= b; y++) {
            for (int x = l; x <= r; x++) {
                if (solid(px[y * SAMPLE + x])) {
                    filled++;
                }
            }
        }
        if (filled < w * h * 0.9f) {
            return null;
        }
        // 板色沿上下边取（避开圆角），用中位数：角上的角标（WPS 的「TV」）和贴边的 logo 不带偏它
        final int edge = Math.round(h * 0.04f);
        final int depth = Math.round(h * 0.06f);
        Integer topColor = bandColor(px, SAMPLE, l, r, t + edge, t + edge + depth);
        if (topColor == null) {
            return null;
        }
        Integer bottomColor = bandColor(px, SAMPLE, l, r, b - edge - depth, b - edge);
        if (bottomColor == null) {
            return null;
        }
        final int top = topColor;
        final int bottom = bottomColor;
        // 两种颜色（白字红底）是画，不是可以拉通的板
        if (distance(top, bottom) > 150) {
            return null;
        }
        final Rect plateBox = box;
        Placed placed = placeLogo(icon, new LogoFinder() {
            @Override
            public RectF find(int[] sample, int n) {
                Rect p = solidBox(sample, n);
                if (p == null) {
                    return null;
                }
                // 板内缩一圈，找「和板色拉开了距离」的实心像素 = logo
                int inset = Math.round(p.width() * 0.08f);
                Rect within = new Rect(p.left + inset, p.top + inset, p.right - inset, p.bottom - inset);
                RectF found = bounds(sample, n, within, new PixelTest() {
                    @Override
                    public boolean test(int i, int c) {
                        if (!solid(c)) {
                            return false;
                        }
                        float yFrac = (i / n - plateBox.top) / (float) plateBox.height();
                        return distance(c, ColorUtils.blendARGB(top, bottom, yFrac)) > 72;
                    }
                });
                if (found != null) {
                    return found;
                }
                // 整板就是一个 logo（板色一直铺到边）：按整板算
                return new RectF(p.left / (float) n, p.top / (float) n,
                        p.right / (float) n, p.bottom / (float) n);
            }
        });
        if (placed == null) {
            return null;
        }
        // 多色 logo（抖音的彩色音符、天翼云盘的云）转白会糊成一坨，
        // 这里跟 Aerial 一样走「保留原色」：板色上下拉通做底，
        // 原 logo 叠上去、边缘羽化融进板色 —— 而不是统一成白标
        int plateColor = ColorUtils.blendARGB(top, bottom, 0.5f);
        if (multiColor(placed.layer, placed.logo, plateColor)) {
            // 板色和磁贴底色是同一个色系但不是一个值（还有圆角和一层斜光），
            // 只羽化的话板上那圈边会在磁贴上留成一圈「残留背景」。
            // 按「离板色的距离」把板整块抠掉，保留 logo 自己的颜色
            cutOut(placed.layer, placed.logo, top, bottom, false);
            final int keepTop = top;
            final int keepBottom = bottom;
            Bitmap back = layer(new CanvasHook() {
                @Override
                public void draw(Canvas canvas) {
                    Paint paint = new Paint();
                    paint.setShader(new LinearGradient(0f, 0f, 0f, (float) H,
                            keepTop, keepBottom, Shader.TileMode.CLAMP));
                    canvas.drawRect(0f, 0f, (float) W, (float) H, paint);
                }
            });
            return compose(back, placed.layer);
        }
        return unify(placed.layer, placed.logo, top, bottom);
    }

    // ---------------------------------------------------------------- 老式图标

    private static Bitmap legacy(Drawable icon) {
        final int sample = 192;
        Bitmap small = Bitmap.createBitmap(sample, sample, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, sample, sample);
        icon.draw(new Canvas(small));
        boolean square = opaqueCorners(small);
        int tint = dominant(small);
        small.recycle();
        int[] grad = tileFromTint(tint);
        // 透明底的纯形状 logo：转白放在自己的色调上，跟 tvOS 画系统应用一致
        if (!square) {
            Placed placed = placeLogo(icon, new LogoFinder() {
                @Override
                public RectF find(int[] px, int n) {
                    return visible(px, n);
                }
            });
            if (placed != null && !multiColor(placed.layer, placed.logo, 0)) {
                whiten(placed.layer);
                return compose(colorTile(grad[0], grad[1]), placed.layer);
            }
        }
        // 满幅方图且上下边各是一种颜色（很多音乐 / 视频应用这么画图标）：
        // 把边色沿上下拉通整张磁贴，中间的画按原样淡出融进边色 ——
        // 这样磁贴被画填满，而不是一张小图孤零零坐在中央（Aerial 的 edgeColors 路）
        if (square) {
            int[] edge = edgeColors(icon);
            if (edge != null) {
                Placed carried = placeLogo(icon, new LogoFinder() {
                    @Override
                    public RectF find(int[] px, int n) {
                        return visible(px, n);
                    }
                }, false, 1.25f);
                if (carried != null) {
                    float side = carried.logo.width();
                    feather(carried.layer,
                            new RectF(carried.logo.left + side * 0.18f, carried.logo.top + side * 0.18f,
                                    carried.logo.right - side * 0.18f, carried.logo.bottom - side * 0.18f),
                            0f, side * 0.18f);
                    final int eTop = edge[0];
                    final int eBottom = edge[1];
                    final float gTop = carried.logo.top;
                    final float gBottom = carried.logo.bottom;
                    Bitmap back = layer(new CanvasHook() {
                        @Override
                        public void draw(Canvas canvas) {
                            Paint paint = new Paint();
                            paint.setShader(new LinearGradient(0f, gTop, 0f, gBottom,
                                    eTop, eBottom, Shader.TileMode.CLAMP));
                            canvas.drawRect(0f, 0f, (float) W, (float) H, paint);
                        }
                    });
                    return compose(back, carried.layer);
                }
            }
        }
        // 其余方图：整幅按原样放上去，边角随磁贴圆掉
        Placed placed = placeLogo(icon, new LogoFinder() {
            @Override
            public RectF find(int[] px, int n) {
                return visible(px, n);
            }
        }, square, square ? 1.25f : 1f);
        if (placed == null) {
            return colorTile(grad[0], grad[1]);
        }
        return compose(colorTile(grad[0], grad[1]), placed.layer);
    }

    /**
     * 满幅方图的上下边各是什么颜色：边内侧两条带的中位数，两带都得基本纯色，
     * 且两色不能差太远（黑上橙下那种拉通会变成一条怪条纹）。
     * 移植自 Aerial 的 {@code TileArtFactory.edgeColors}。
     */
    private static int[] edgeColors(Drawable icon) {
        final int n = 96;
        int[] px = raster(icon, n);
        Rect box = solidBox(px, n);
        if (box == null) {
            return null;
        }
        int w = box.width();
        int h = box.height();
        if (w < n / 2 || Math.abs(w - h) > w * 0.06f) {
            return null;
        }
        int filled = 0;
        for (int y = box.top; y < box.bottom; y++) {
            for (int x = box.left; x < box.right; x++) {
                if (solid(px[y * n + x])) {
                    filled++;
                }
            }
        }
        if (filled < w * h * 0.88f) {
            return null;
        }
        // 贴边一点取带：画的主体的边常逼近图标边缘
        Integer top = bandColor(px, n, box.left, box.right,
                box.top + h * 2 / 100, box.top + h * 5 / 100);
        if (top == null) {
            return null;
        }
        Integer bottom = bandColor(px, n, box.left, box.right,
                box.bottom - 1 - h * 5 / 100, box.bottom - 1 - h * 2 / 100);
        if (bottom == null) {
            return null;
        }
        if (distance(top, bottom) > 260) {
            return null;
        }
        return new int[]{top, bottom};
    }

    // ---------------------------------------------------------------- 统一观感

    /**
     * Aerial 的「统一」观感：logo 转白，放在 app 主色的渐变上。板色有色就用板色；
     * 白、黑、灰的板没有「色」可取，改用 logo 自己的颜色；灰 logo 配灰板用石墨色。
     */
    private static Bitmap unify(Bitmap fore, RectF logo, int top, int bottom) {
        int plate = ColorUtils.blendARGB(top, bottom, 0.5f);
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(plate, hsl);
        boolean colored = hsl[1] > 0.25f && hsl[2] > 0.18f && hsl[2] < 0.85f;
        int tint = colored ? 0 : logoTint(fore, logo, plate);
        cutOut(fore, logo, top, bottom);
        int a;
        int b;
        if (colored) {
            int[] grad = tileFromPlate(top, bottom);
            a = grad[0];
            b = grad[1];
        } else {
            int[] grad = tileFromTint(tint);
            a = grad[0];
            b = grad[1];
        }
        return compose(colorTile(a, b), fore);
    }

    /**
     * 只留与板色（沿 logo 从 [top] 渐变到 [bottom]）拉得开色距的像素，转白，
     * alpha 按色距渐变，边缘就融进了板色。
     */
    private static void cutOut(Bitmap layer, RectF logo, int top, int bottom) {
        cutOut(layer, logo, top, bottom, true);
    }

    /**
     * @param white true = 剩下的像素转白（Aerial 的「统一」观感）；
     *              false = 保留 logo 自己的颜色（多色 logo 不能转白）
     */
    private static void cutOut(Bitmap layer, RectF logo, int top, int bottom, boolean white) {
        int[] px = new int[W * H];
        layer.getPixels(px, 0, W, 0, 0, W, H);
        for (int y = 0; y < H; y++) {
            float frac = (y - logo.top) / logo.height();
            int plate = ColorUtils.blendARGB(top, bottom, clamp01(frac));
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                int c = px[i];
                if (Color.alpha(c) == 0) {
                    continue;
                }
                // 起点抬到 150：板的斜光、圆角、内侧亮块离板色只有 30~100，
                // 起抠点低了它们会被当 logo 留下来（磁贴上一圈「残留背景」）；
                // 真正的 logo 离板色都在 200 以上（白标 300 上下），不受影响
                float keep = clamp01((distance(c, plate) - 150) / 80f);
                int a = Math.round(Color.alpha(c) * keep);
                px[i] = (a << 24) | (white ? 0xFFFFFF : (c & 0xFFFFFF));
            }
        }
        layer.setPixels(px, 0, W, 0, 0, W, H);
    }

    /** 整层所有可见像素转白，alpha 不动 */
    private static void whiten(Bitmap layer) {
        int[] px = new int[W * H];
        layer.getPixels(px, 0, W, 0, 0, W, H);
        for (int i = 0; i < px.length; i++) {
            px[i] = (px[i] & 0xFF000000) | 0xFFFFFF;
        }
        layer.setPixels(px, 0, W, 0, 0, W, H);
    }

    /** 让板的边缘和圆角融进板色：keep 框以外按距离淡出 */
    private static void feather(Bitmap layer, RectF keep) {
        feather(layer, keep, H * 0.04f, H * 0.22f);
    }

    private static void feather(Bitmap layer, RectF keep, float margin, float fade) {
        Canvas canvas = new Canvas(layer);
        canvas.drawRect(0f, 0f, W, (float) H, mask(keep.left, keep.right, margin, fade, true));
        canvas.drawRect(0f, 0f, W, (float) H, mask(keep.top, keep.bottom, margin, fade, false));
    }

    private static Paint mask(float from, float to, float margin, float fade, boolean horizontal) {
        float span = to - from + 2 * (margin + fade);
        float f = fade / span;
        float start = from - margin - fade;
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(
                horizontal ? start : 0f, horizontal ? 0f : start,
                horizontal ? start + span : 0f, horizontal ? 0f : start + span,
                new int[]{0, Color.BLACK, Color.BLACK, 0},
                new float[]{0f, f, 1f - f, 1f}, Shader.TileMode.CLAMP));
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        return paint;
    }

    // ---------------------------------------------------------------- 配色

    /** Aerial 的磁贴底色：斜向渐变 + 左上角一道柔光 */
    private static Bitmap colorTile(int top, int bottom) {
        return layer(new CanvasHook() {
            @Override
            public void draw(Canvas canvas) {
                Paint paint = new Paint();
                paint.setShader(new LinearGradient(0f, 0f, W * 0.4f, (float) H, top, bottom, Shader.TileMode.CLAMP));
                canvas.drawRect(0f, 0f, (float) W, (float) H, paint);
                Paint light = new Paint();
                light.setShader(new RadialGradient(W * 0.25f, -H * 0.2f, W * 0.8f,
                        0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                canvas.drawRect(0f, 0f, (float) W, (float) H, light);
            }
        });
    }

    /** 板色直接当磁贴色：拉平，别哪块特别淡哪块特别深 */
    private static int[] tileFromPlate(int top, int bottom) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(ColorUtils.blendARGB(top, bottom, 0.5f), hsl);
        float s = clamp(hsl[1], 0.45f, 0.85f);
        float l = clamp(hsl[2], 0.4f, 0.56f);
        return new int[]{ColorUtils.HSLToColor(new float[]{hsl[0], s, l + 0.08f}),
                ColorUtils.HSLToColor(new float[]{hsl[0], s, l - 0.08f})};
    }

    /** logo 没有自己的底色时：用它的色相，比 logo 深一档，白 logo 才站得住 */
    private static int[] tileFromTint(int tint) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(tint, hsl);
        if (hsl[1] < 0.18f) {
            return new int[]{0xFF6E6E74, 0xFF3A3A3C};
        }
        float s = clamp(hsl[1], 0.5f, 0.85f);
        return new int[]{ColorUtils.HSLToColor(new float[]{hsl[0], s, 0.56f}),
                ColorUtils.HSLToColor(new float[]{hsl[0], s, 0.4f})};
    }

    /**
     * logo 是不是好几种颜色（Geekbench 的彩条、星标气泡）：转白会糊成一个形状的
     * 就得保留原色。色相分 12 桶，另加「偏亮」「偏暗」两桶给没饱和度的颜色。
     */
    private static boolean multiColor(Bitmap layer, RectF logo, int plate) {
        int l = (int) logo.left;
        int t = (int) logo.top;
        int r = (int) logo.right;
        int b = (int) logo.bottom;
        l = Math.max(0, Math.min(l, W - 1));
        t = Math.max(0, Math.min(t, H - 1));
        r = Math.max(l + 1, Math.min(r, W));
        b = Math.max(t + 1, Math.min(b, H));
        int[] px = new int[(r - l) * (b - t)];
        layer.getPixels(px, 0, r - l, l, t, r - l, b - t);
        int[] bins = new int[14];
        float[] hsv = new float[3];
        Integer plateBin = plate != 0 ? binOf(plate, hsv) : null;
        if (plateBin != null && plateBin >= 12) {
            plateBin = null;
        }
        int count = 0;
        for (int c : px) {
            if (Color.alpha(c) < 200 || (plate != 0 && distance(c, plate) < 96)) {
                continue;
            }
            int bin = binOf(c, hsv);
            if (plateBin != null && (bin == plateBin || bin == (plateBin + 1) % 12 || bin == (plateBin + 11) % 12)) {
                continue;
            }
            bins[bin]++;
            count++;
        }
        if (count == 0) {
            return false;
        }
        // 相邻色相算一色（红渐到橙）
        int groups = 0;
        for (int i = 0; i < 12; i++) {
            int v = bins[i];
            if (v > 0 && v >= bins[(i + 11) % 12] && v >= bins[(i + 1) % 12]) {
                groups++;
            }
        }
        if (bins[12] > count * 0.08f) {
            groups++;
        }
        if (bins[13] > count * 0.08f) {
            groups++;
        }
        return groups >= 2;
    }

    private static int binOf(int c, float[] hsv) {
        Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.25f || hsv[2] < 0.2f) {
            return hsv[2] > 0.6f ? 12 : 13;
        }
        return (int) ((hsv[0] + 15f) / 30f) % 12;
    }

    /** 有饱和度像素的加权平均色：人描述这块板时会说的那个颜色 */
    private static int logoTint(Bitmap layer, RectF logo, int plate) {
        int l = Math.max(0, Math.min((int) logo.left, W - 1));
        int t = Math.max(0, Math.min((int) logo.top, H - 1));
        int r = Math.max(l + 1, Math.min((int) logo.right, W));
        int b = Math.max(t + 1, Math.min((int) logo.bottom, H));
        int[] px = new int[(r - l) * (b - t)];
        layer.getPixels(px, 0, r - l, l, t, r - l, b - t);
        double rr = 0;
        double gg = 0;
        double bb = 0;
        double total = 0;
        float[] hsv = new float[3];
        for (int c : px) {
            if (Color.alpha(c) < 200 || distance(c, plate) < 96) {
                continue;
            }
            Color.colorToHSV(c, hsv);
            double wgt = hsv[1] * hsv[2] + 0.02;
            rr += Color.red(c) * wgt;
            gg += Color.green(c) * wgt;
            bb += Color.blue(c) * wgt;
            total += wgt;
        }
        if (total == 0) {
            return GRAPHITE_TOP;
        }
        return Color.rgb((int) Math.round(rr / total), (int) Math.round(gg / total), (int) Math.round(bb / total));
    }

    // ---------------------------------------------------------------- logo 摆放

    private static class Placed {
        final Bitmap layer;
        final RectF logo;

        Placed(Bitmap layer, RectF logo) {
            this.layer = layer;
            this.logo = logo;
        }
    }

    private interface LogoFinder {
        RectF find(int[] px, int n);
    }

    private interface PixelTest {
        boolean test(int i, int c);
    }

    private interface CanvasHook {
        void draw(Canvas canvas);
    }

    /**
     * 把 [d] 按落位尺寸重画一遍再量：dp 尺寸的 layer-list 不随 bounds 缩放，
     * 得按「实际画出来的」定位。返回磁贴大小的 logo 层和 logo 在磁贴上的框。
     */
    private static Placed placeLogo(Drawable d, LogoFinder finder) {
        return placeLogo(d, finder, false, 1f);
    }

    private static Placed placeLogo(Drawable d, final LogoFinder finder, final boolean round, float scale) {
        RectF first = finder.find(raster(d, SAMPLE), SAMPLE);
        if (first == null) {
            return null;
        }
        int size = Math.round(iconOnTile(first, scale).width());
        size = Math.max(16, Math.min(size, 4 * H));
        Bitmap icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(icon));
        final int[] px = new int[size * size];
        icon.getPixels(px, 0, size, 0, 0, size, size);
        RectF logo = finder.find(px, size);
        if (logo == null) {
            logo = first;
        }
        final RectF at = iconOnTile(logo, scale);
        final Bitmap iconBitmap = icon;
        final boolean isRound = round;
        Bitmap out = layer(new CanvasHook() {
            @Override
            public void draw(Canvas canvas) {
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                if (isRound) {
                    // 满幅方图当圆角画上去（比例沿用 iOS 图标的 22.5%）
                    float rr = at.width() * 0.225f;
                    paint.setShader(new BitmapShaderHolder(iconBitmap, at).shader);
                    canvas.drawRoundRect(at, rr, rr, paint);
                } else {
                    canvas.drawBitmap(iconBitmap, null, at, paint);
                }
            }
        });
        iconBitmap.recycle();
        float box = at.width();
        RectF onTile = new RectF(at.left + box * logo.left, at.top + box * logo.top,
                at.left + box * logo.right, at.top + box * logo.bottom);
        return new Placed(out, onTile);
    }

    /** 把图标位图按 at 框贴上去的 shader（局部类只是为了少写一层参数） */
    private static final class BitmapShaderHolder {
        final Shader shader;

        BitmapShaderHolder(Bitmap icon, RectF at) {
            int w = icon.getWidth();
            int h = icon.getHeight();
            android.graphics.BitmapShader shader =
                    new android.graphics.BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            matrix.setScale(at.width() / w, at.height() / h);
            matrix.postTranslate(at.left, at.top);
            shader.setLocalMatrix(matrix);
            this.shader = shader;
        }
    }

    /**
     * logo 在磁贴上的落位：按面积折算居中。宽 logo 和圆标占同样的视觉重量，
     * 且全部留在安全区内（聚焦放大也不会顶到边）。
     */
    private static RectF iconOnTile(RectF logo, float scale) {
        float aspect = logo.width() / logo.height();
        float h = (float) Math.min(
                scale * LOGO * H / Math.sqrt(aspect),
                Math.min(0.66f * H, 0.62f * W / aspect));
        float box = h / logo.height();
        float left = W / 2f - box * logo.centerX();
        float top = H / 2f - box * logo.centerY();
        return new RectF(left, top, left + box, top + box);
    }

    // ---------------------------------------------------------------- 采样工具

    private static Bitmap layer(CanvasHook hook) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        hook.draw(new Canvas(out));
        return out;
    }

    /** back 叠上 fore，按磁贴圆角裁掉四角 */
    private static Bitmap compose(Bitmap back, Bitmap fore) {
        Bitmap out = back.copy(Bitmap.Config.ARGB_8888, true);
        new Canvas(out).drawBitmap(fore, 0f, 0f, null);
        if (out != back) {
            back.recycle();
        }
        return mask(out);
    }

    /** 磁贴四角：连续圆角遮罩（Aerial 的 Corner 12dp 档） */
    private static Bitmap mask(Bitmap in) {
        // 磁贴位图不一定就是 W×H（banner 会按原图尺寸出图，最多 2W），
        // 圆角遮罩必须按实际尺寸挖，否则只有左上角被圆掉、其余变直角
        int w = in.getWidth();
        int h = in.getHeight();
        float radius = h * (12f / 82.8f);
        Bitmap maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas maskCanvas = new Canvas(maskBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        maskCanvas.drawPath(squircle(0f, 0f, w, h, radius), paint);

        Canvas canvas = new Canvas(in);
        Paint xfer = new Paint(Paint.ANTI_ALIAS_FLAG);
        xfer.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(maskBitmap, 0f, 0f, xfer);
        maskBitmap.recycle();
        return in;
    }

    private static int[] raster(Drawable d, int n) {
        int[] px = new int[n * n];
        try {
            Bitmap small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(small);
            d.setBounds(0, 0, n, n);
            d.draw(canvas);
            small.getPixels(px, 0, n, 0, 0, n, n);
            small.recycle();
        } catch (Throwable ignored) {
            // 有的 drawable 画不上软件画布，采不到就当全透明
        }
        return px;
    }

    /** 实心像素的包围框（像素坐标）；一个实心像素都没有返回 null */
    private static Rect solidBox(int[] px, int n) {
        int l = n;
        int t = n;
        int r = -1;
        int b = -1;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (solid(px[y * n + x])) {
                    if (x < l) {
                        l = x;
                    }
                    if (x > r) {
                        r = x;
                    }
                    if (y < t) {
                        t = y;
                    }
                    if (y > b) {
                        b = y;
                    }
                }
            }
        }
        return r < 0 ? null : new Rect(l, t, r + 1, b + 1);
    }

    /** [test] 挑中的像素的包围框，按采样比例折成 0..1 */
    private static RectF bounds(int[] px, int n, Rect within, PixelTest test) {
        int l = n;
        int t = n;
        int r = -1;
        int b = -1;
        for (int y = within.top; y < within.bottom; y++) {
            for (int x = within.left; x < within.right; x++) {
                int i = y * n + x;
                if (test.test(i, px[i])) {
                    l = Math.min(l, x);
                    r = Math.max(r, x);
                    t = Math.min(t, y);
                    b = Math.max(b, y);
                }
            }
        }
        if (r < 0) {
            return null;
        }
        return new RectF(l / (float) n, t / (float) n, (r + 1) / (float) n, (b + 1) / (float) n);
    }

    private static RectF visible(int[] px, int n) {
        return bounds(px, n, new Rect(0, 0, n, n), new PixelTest() {
            @Override
            public boolean test(int i, int c) {
                return Color.alpha(c) > 24;
            }
        });
    }

    private static boolean solid(int c) {
        return Color.alpha(c) > 200;
    }

    /** 一层是不是（几乎）纯色 */
    private static boolean isFlat(int[] px) {
        int first = px[px.length / 2];
        int off = 0;
        for (int c : px) {
            if (distance(c, first) > 24 || Math.abs(Color.alpha(c) - Color.alpha(first)) > 24) {
                off++;
            }
        }
        return off < px.length / 20;
    }

    private static int distance(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    /**
     * [y0, y1] 这条横带（左右各避开 1/6 宽）的中位数颜色；带上 60% 以内都是这个色才认，
     * 否则这条边是画（照片铺到边），没有板色可取。
     */
    private static Integer bandColor(int[] px, int n, int l, int r, int y0, int y1) {
        int x0 = l + (r - l) / 6;
        int x1 = r - (r - l) / 6;
        java.util.List<Integer> rs = new java.util.ArrayList<>();
        java.util.List<Integer> gs = new java.util.ArrayList<>();
        java.util.List<Integer> bs = new java.util.ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int c = px[y * n + x];
                rs.add((c >> 16) & 0xFF);
                gs.add((c >> 8) & 0xFF);
                bs.add(c & 0xFF);
            }
        }
        if (rs.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(rs);
        java.util.Collections.sort(gs);
        java.util.Collections.sort(bs);
        int median = Color.rgb(rs.get(rs.size() / 2), gs.get(gs.size() / 2), bs.get(bs.size() / 2));
        int near = 0;
        int total = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                total++;
                if (distance(px[y * n + x], median) <= 48) {
                    near++;
                }
            }
        }
        return near < total * 0.6f ? null : median;
    }

    /** 四角都是实心的方图（国产应用期待启动器蒙圆角的那种） */
    private static boolean opaqueCorners(Bitmap b) {
        int i = Math.round(b.getWidth() * 0.03f);
        int e = b.getWidth() - 1 - i;
        return Color.alpha(b.getPixel(i, i)) > 200
                && Color.alpha(b.getPixel(e, i)) > 200
                && Color.alpha(b.getPixel(i, e)) > 200
                && Color.alpha(b.getPixel(e, e)) > 200;
    }

    /** 饱和度加权的平均色：人看到这块板会说出的那个颜色 */
    private static int dominant(Bitmap b) {
        Bitmap small = Bitmap.createScaledBitmap(b, 24, 14, true);
        double r = 0;
        double g = 0;
        double bl = 0;
        double total = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int c = small.getPixel(x, y);
                if (Color.alpha(c) < 128) {
                    continue;
                }
                Color.colorToHSV(c, hsv);
                double wgt = hsv[1] * hsv[2] * hsv[2] + 0.04;
                r += Color.red(c) * wgt;
                g += Color.green(c) * wgt;
                bl += Color.blue(c) * wgt;
                total += wgt;
            }
        }
        if (small != b) {
            small.recycle();
        }
        if (total == 0) {
            return GRAPHITE_TOP;
        }
        return Color.rgb((int) Math.round(r / total), (int) Math.round(g / total), (int) Math.round(bl / total));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---------------------------------------------------------------- 连续圆角

    /** 连续圆角路径（超椭圆），移植自 SmoothCorners（经此前 AppIconArt 的 Java 版） */
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
}

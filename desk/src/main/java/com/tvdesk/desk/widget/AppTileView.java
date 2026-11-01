package com.tvdesk.desk.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Aerial TV 式的应用磁贴：一块 16:9 的横板 + 正下方一行名字。
 *
 * <p>视觉规格全部照搬 Aerial 的 {@code AppTile.kt} / {@code HomeScreen.kt}：
 * <ul>
 *   <li>磁贴本体 16:9（宽由网格列宽决定，高 = 宽 × 9/16）；</li>
 *   <li>聚焦放大 1.15 倍（{@link com.tvdesk.desk.motion.FocusScaler}），放大时整块磁贴
 *       连同外框一起缩放，跟 Aerial 的做法一致；</li>
 *   <li>聚焦时磁贴外圈画一道白环（间隙 5dp、线宽 3dp），tvOS 画焦点的方式；</li>
 *   <li>抬起的磁贴在下方投一道软阴影（Aerial 是烘焙好的阴影图，这里用多层递减的
 *       圆角描边近似，电视上看不出差别）；</li>
 *   <li>名字在磁贴正下方居中一行，超出省略；非聚焦时淡一档，聚焦提亮。</li>
 * </ul>
 *
 * <p>GridView 的行高是统一的，所以这个视图把「磁贴 + 名字」整体量好：
 * 宽来自列宽，高 = 磁贴高 + 名字区。
 */
public class AppTileView extends FrameLayout {

    /** 聚焦放大倍数（AppTile.FOCUSED_SCALE） */
    public static final float FOCUSED_SCALE = 1.15f;

    /** 磁贴圆角（AppTile.Corner） */
    private static final float CORNER_DP = 12f;
    /** 白环离磁贴的间隙（AppTile.RingGap） */
    private static final float RING_GAP_DP = 5f;
    /** 白环线宽（AppTile.RingWidth） */
    private static final float RING_WIDTH_DP = 3f;
    /** 阴影下坠与模糊（AppTile.ShadowDrop / ShadowBlur） */
    private static final float SHADOW_DROP_DP = 12f;
    private static final float SHADOW_BLUR_DP = 14f;
    /** 名字区高度（Aerial 行距 = 磁贴高 + 50dp：这 32dp + 网格行距 18dp） */
    private static final float LABEL_ZONE_DP = 32f;

    private final ImageView tileView;
    private final TextView nameView;
    private final Paint ringPaint;
    private final Paint shadowPaint;
    private final RectF shadowRect = new RectF();
    private final float density;

    private int tileHeight;
    /** 当前贴着的磁贴位图，用来跳过重复 setImageBitmap */
    private android.graphics.Bitmap currentTile;

    public AppTileView(Context context) {
        this(context, null);
    }

    public AppTileView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        tileView = new ImageView(context);
        tileView.setScaleType(ImageView.ScaleType.FIT_XY);
        tileView.setClickable(false);
        addView(tileView);

        nameView = new TextView(context);
        nameView.setTextSize(12f);
        nameView.setTextColor(Color.parseColor("#EBEBF0"));
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameView.setGravity(android.view.Gravity.CENTER);
        // 名字坐在墙纸上，一道软阴影保证亮图上也读得清
        nameView.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        addView(nameView);

        ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.WHITE);
        ringPaint.setStrokeWidth(RING_WIDTH_DP * density);

        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setStyle(Paint.Style.STROKE);
        shadowPaint.setColor(0x66000000);

        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    /**
     * 磁贴图（AppTileArt 合成好的 16:9 位图）。
     *
     * <p>同一块位图重复设置会让 ImageView 重新上传一次纹理（704×396 ≈ 1.1MB），
     * 滚动时 GridView 每回收-复用一次就重设一遍 —— 翻页卡顿有一半来自这里。
     * 缓存命中时拿到的就是同一个对象，直接比引用。
     */
    public void setTile(android.graphics.Bitmap bitmap) {
        if (bitmap == currentTile) {
            return;
        }
        currentTile = bitmap;
        tileView.setImageBitmap(bitmap);
    }

    public void setName(CharSequence text) {
        CharSequence old = nameView.getText();
        if (old != null && old.toString().contentEquals(text == null ? "" : text)) {
            return;
        }
        nameView.setText(text);
    }

    /** 聚焦时名字提亮（Aerial：非聚焦 0.7，聚焦 1.0） */
    public void setNameBright(boolean bright) {
        nameView.setAlpha(bright ? 1f : 0.72f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // GridView 给的宽度就是列宽（精确值）；高度按 16:9 自己算，行高全墙一致
        int w = MeasureSpec.getSize(widthMeasureSpec);
        tileHeight = Math.round(w * 9f / 16f);
        int labelZone = Math.round(LABEL_ZONE_DP * density);
        int wSpec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int tileSpec = MeasureSpec.makeMeasureSpec(tileHeight, MeasureSpec.EXACTLY);
        tileView.measure(wSpec, tileSpec);
        nameView.measure(wSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(w, tileHeight + labelZone);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int nameTop = tileHeight + Math.round(dp(6));
        tileView.layout(0, 0, w, tileHeight);
        nameView.layout(0, nameTop, w, Math.max(nameTop + nameView.getMeasuredHeight(), getHeight()));
    }

    @Override
    public void draw(Canvas canvas) {
        if (hasFocus()) {
            drawShadow(canvas);
        }
        super.draw(canvas);
        if (hasFocus()) {
            drawRing(canvas);
        }
    }

    /** 抬起的磁贴投下的软阴影：多层递减圆角描边（Aerial 是烘焙的阴影位图，近似即可） */
    private void drawShadow(Canvas canvas) {
        float corner = CORNER_DP * density;
        float drop = SHADOW_DROP_DP * density;
        float blur = SHADOW_BLUR_DP * density;
        int layers = 8;
        for (int i = layers; i >= 1; i--) {
            float expand = blur * i / (float) layers;
            shadowPaint.setStrokeWidth(blur / (float) layers * 1.6f);
            shadowPaint.setAlpha(Math.max(4, 22 - i * 2));
            // 复用同一个矩形：每帧 new 8 个 RectF 是白给的 GC 压力
            shadowRect.set(0, 0, getWidth(), tileHeight);
            shadowRect.inset(-expand * 0.4f, -expand * 0.4f);
            shadowRect.offset(0, drop * (0.4f + 0.6f * i / layers));
            canvas.drawRoundRect(shadowRect, corner + expand * 0.4f, corner + expand * 0.4f, shadowPaint);
        }
    }

    /** tvOS 式焦点白环：磁贴外扩 RingGap，线宽 RingWidth */
    private void drawRing(Canvas canvas) {
        float corner = CORNER_DP * density;
        float gap = RING_GAP_DP * density;
        RectF rect = new RectF(-gap, -gap, getWidth() + gap, tileHeight + gap);
        Path path = AppTileArt.squircle(rect.left, rect.top, rect.right, rect.bottom, corner + gap);
        canvas.drawPath(path, ringPaint);
    }

    private float dp(float value) {
        return value * density;
    }
}

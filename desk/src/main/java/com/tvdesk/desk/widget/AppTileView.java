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

    /**
     * 焦点放大和白环都交给临界阻尼弹簧。
     *
     * <p>以前是 {@code FocusScaler} 的 180ms ease：曲线到点就停，中途改目标只能重启，
     * 连按方向键时一串半截动画接着走，看着像掉帧。弹簧改目标就行，
     * 速度和位置都连续，连按是「顺着当前速度滑过去」。
     *
     * <p>白环单独一根、刚度更高（{@link #RING_STIFFNESS}）：Aerial 那边也是让环
     * 出现得比放大慢一点、消失得比缩小快一点，左右移动时才不会同时看到两个框。
     */
    private final com.tvdesk.desk.motion.SpringValue scaleSpring =
            new com.tvdesk.desk.motion.SpringValue(com.tvdesk.desk.motion.SpringValue.STIFF_FOCUS);
    private final com.tvdesk.desk.motion.SpringValue ringSpring =
            new com.tvdesk.desk.motion.SpringValue(RING_STIFFNESS);
    private boolean animating;
    private long lastFrameNanos;

    private static final float RING_STIFFNESS = 900f;

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
        // 系统那层焦点高亮按外接矩形画，会盖住圆角和白环，自己画就够了（API 26 起才有）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setDefaultFocusHighlightEnabled(false);
        }
        scaleSpring.snapTo(1f);
        ringSpring.snapTo(0f);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        scaleSpring.setTarget(gainFocus ? FOCUSED_SCALE : 1f);
        ringSpring.setTarget(gainFocus ? 1f : 0f);
        // 抬一层，免得放大的那块被下一行的卡压住（墙的间距不够吃满 15% 的溢出）
        setTranslationZ(gainFocus ? 8f : 0f);
        startAnimating();
    }

    /**
     * 把缩放/描边状态对齐到「当前有没有焦点」。
     *
     * <p>墙会复用子视图（换排序、换栏目时按 key 拿回同一个对象），复用过来时
     * 它可能还停在上一张卡的动画中间态 —— 放大到一半、白环没退。实测表现是：
     * 搬动一张卡之后，第一张卡比别的大一圈，名字还被下一行的磁贴盖住。
     * 每次绑定都对齐一次，卡片的静态样子和它的焦点状态永远是同一个。
     */
    public void syncFocusState() {
        boolean focused = hasFocus();
        scaleSpring.snapTo(focused ? FOCUSED_SCALE : 1f);
        ringSpring.snapTo(focused ? 1f : 0f);
        setScaleX(scaleSpring.value());
        setScaleY(scaleSpring.value());
        setTranslationZ(focused ? 8f : 0f);
        invalidate();
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            float dt = lastFrameNanos == 0 ? 0.016f : (now - lastFrameNanos) / 1_000_000_000f;
            lastFrameNanos = now;

            boolean scaleAlive = scaleSpring.advance(dt);
            setScaleX(scaleSpring.value());
            setScaleY(scaleSpring.value());
            boolean ringAlive = ringSpring.advance(dt);
            if (ringAlive || ringSpring.value() > 0.001f) {
                invalidate();
            }
            if (scaleAlive || ringAlive) {
                postOnAnimation(this);
            } else {
                animating = false;
                lastFrameNanos = 0;
            }
        }
    };

    private void startAnimating() {
        if (animating) {
            return;
        }
        animating = true;
        lastFrameNanos = 0;
        postOnAnimation(frame);
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
        float ring = ringSpring.value();
        if (ring > 0.001f) {
            drawShadow(canvas, ring);
        }
        super.draw(canvas);
        if (ring > 0.001f) {
            drawRing(canvas, ring);
        }
    }

    /** 抬起的磁贴投下的软阴影：多层递减圆角描边（Aerial 是烘焙的阴影位图，近似即可） */
    private void drawShadow(Canvas canvas, float strength) {
        float corner = CORNER_DP * density;
        float drop = SHADOW_DROP_DP * density;
        float blur = SHADOW_BLUR_DP * density;
        int layers = 8;
        for (int i = layers; i >= 1; i--) {
            float expand = blur * i / (float) layers;
            shadowPaint.setStrokeWidth(blur / (float) layers * 1.6f);
            shadowPaint.setAlpha((int) (Math.max(4, 22 - i * 2) * strength));
            // 复用同一个矩形：每帧 new 8 个 RectF 是白给的 GC 压力
            shadowRect.set(0, 0, getWidth(), tileHeight);
            shadowRect.inset(-expand * 0.4f, -expand * 0.4f);
            shadowRect.offset(0, drop * (0.4f + 0.6f * i / layers));
            canvas.drawRoundRect(shadowRect, corner + expand * 0.4f, corner + expand * 0.4f, shadowPaint);
        }
    }

    /**
     * tvOS 式焦点白环：磁贴外扩 RingGap，线宽 RingWidth。
     *
     * <p>{@code strength} 是透明度弹簧：环从磁贴边缘「长出来」（间隙从 0 撑到 RingGap），
     * 消失时比缩小更快地淡掉，左右移动的路上不会出现两个框。
     */
    private void drawRing(Canvas canvas, float strength) {
        float corner = CORNER_DP * density;
        float gap = RING_GAP_DP * density * strength;
        RectF rect = new RectF(-gap, -gap, getWidth() + gap, tileHeight + gap);
        Path path = AppTileArt.squircle(rect.left, rect.top, rect.right, rect.bottom, corner + gap);
        ringPaint.setAlpha((int) (255 * Math.min(1f, strength)));
        canvas.drawPath(path, ringPaint);
    }

    private float dp(float value) {
        return value * density;
    }
}

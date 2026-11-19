package com.tvdesk.desk.widget;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import org.xmlpull.v1.XmlPullParser;

import com.tvdesk.desk.R;

/**
 * 静态「玻璃」底板 —— 和 {@link GlassDrawable} 同一套参数，只是不取样背后的画面。
 *
 * <h3>为什么还要一个静态版</h3>
 * {@link GlassDrawable} 靠「自己在窗口里什么位置」去磨砂图上取色，所以它只对「和快照同一个
 * 窗口」的控件成立。弹窗是另一个窗口（坐标从对话框自己算起），取样会取到错的地方；
 * XML 里写死的背景又没法在布局阶段拿到宿主 View。这类地方就用这个静态版：
 *
 * <ul>
 *   <li>底色 {@link #FLAT_GLASS} = Aerial 的 {@code Palette.Glass} #E6202022；</li>
 *   <li>上沿高光：白 0.10，到面板中线（50%）淡完；</li>
 *   <li>描边：1.2dp，左上→右下四段渐变 0.55 / 0.10 / 0.06 / 0.30；聚焦时加粗到 2dp 并整体提亮。</li>
 * </ul>
 *
 * <p>这三条和 {@code LiquidGlass.kt} 的 {@code glassEdge()} 一一对应，所以模块页的顶栏、
 * 侧栏、菜单和桌面的胶囊看起来是同一块玻璃——差别只在桌面那颗能透出背后的画面。
 *
 * <p>用法（XML 直接当背景用）：
 * <pre>{@code
 * <com.tvdesk.desk.widget.GlassSurface
 *     xmlns:app="http://schemas.android.com/apk/res-auto"
 *     app:glassRadius="@dimen/radius_panel" />
 * }</pre>
 */
public class GlassSurface extends Drawable {

    /** Aerial 的 Palette.Glass（「Follow App」模式下的玻璃底色） */
    private static final int FLAT_GLASS = 0xE6202022;

    private float radiusPx;
    /** dp→px 的换算，和 GlassDrawable 一样按宿主的 density 走 */
    private float density;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean focused;

    /**
     * 无参构造：**XML 里必须走这一条**。
     *
     * <p>{@code DrawableInflater} 是靠 {@code clazz.getConstructor()}（无参）反射实例化的，
     * 只提供 {@code (Context, AttributeSet)} 会抛 {@code NoSuchMethodException}，
     * 而且异常是在加载布局时抛的——整个桌面会起不来（实测过一次）。
     * 属性随后由 {@link #inflate} 补上。
     */
    public GlassSurface() {
        this.density = Resources.getSystem().getDisplayMetrics().density;
        // 先按「胶囊」取一个安全值：万一 inflate 没被调用，圆角也不会变成方块；
        // draw() 里还会按 min(w,h)/2 夹一次
        this.radiusPx = 100f * density;
        fillPaint.setColor(FLAT_GLASS);
        rimPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs,
                        android.content.res.Resources.Theme theme) {
        this.density = r.getDisplayMetrics().density;
        TypedArray a = r.obtainAttributes(attrs, R.styleable.GlassSurface);
        try {
            radiusPx = a.getDimension(R.styleable.GlassSurface_glassRadius, 32f * density);
        } finally {
            a.recycle();
        }
        fillPaint.setColor(FLAT_GLASS);
        rimPaint.setStyle(Paint.Style.STROKE);
    }

    /** 代码里用：直接给像素半径 */
    public GlassSurface(Context context, float radiusPx) {
        this.radiusPx = radiusPx;
        this.density = context.getResources().getDisplayMetrics().density;
        fillPaint.setColor(FLAT_GLASS);
        rimPaint.setStyle(Paint.Style.STROKE);
    }

    /** XML 里用：半径走 {@code app:glassRadius} */
    public GlassSurface(Context context, AttributeSet attrs) {
        this.radiusPx = readRadius(context, attrs);
        this.density = context.getResources().getDisplayMetrics().density;
        fillPaint.setColor(FLAT_GLASS);
        rimPaint.setStyle(Paint.Style.STROKE);
    }

    private static float readRadius(Context context, AttributeSet attrs) {
        if (attrs == null) {
            return 32f * context.getResources().getDisplayMetrics().density;
        }
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.GlassSurface);
        try {
            return a.getDimension(R.styleable.GlassSurface_glassRadius,
                    32f * context.getResources().getDisplayMetrics().density);
        } finally {
            a.recycle();
        }
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean now = false;
        for (int s : state) {
            if (s == android.R.attr.state_focused) {
                now = true;
                break;
            }
        }
        if (now != focused) {
            focused = now;
            invalidateSelf();
            return true;
        }
        return false;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        float r = Math.min(radiusPx, Math.min(bounds.width(), bounds.height()) / 2f);
        rect.set(bounds);
        canvas.drawRoundRect(rect, r, r, fillPaint);

        // 上沿高光：光从上面下来，玻璃上沿先亮，到中线淡完
        sheenPaint.setShader(new LinearGradient(0f, bounds.top, 0f, bounds.bottom,
                new int[]{0x1AFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, r, r, sheenPaint);
        sheenPaint.setShader(null);

        // 受光的边：左上最亮，侧面很淡，远端角落再亮一点
        // Aerial 的 glassEdge：描边 1.2dp
        float rim = 1.2f * density;
        rimPaint.setStrokeWidth(focused ? rim * 2f : rim);
        rimPaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right,
                bounds.bottom,
                focused
                        ? new int[]{0xFFFFFFFF, 0x99FFFFFF, 0x77FFFFFF, 0xE6FFFFFF}
                        : new int[]{0x8CFFFFFF, 0x1AFFFFFF, 0x0FFFFFFF, 0x4DFFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        float inset = rimPaint.getStrokeWidth() / 2f;
        rect.set(bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset);
        canvas.drawRoundRect(rect, Math.max(0f, r - inset), Math.max(0f, r - inset), rimPaint);
        rimPaint.setShader(null);
    }

    @Override
    public void setAlpha(int alpha) {
        // 透明度由填色和描边自己控制
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

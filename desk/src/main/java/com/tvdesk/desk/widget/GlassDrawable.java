package com.tvdesk.desk.widget;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;

/**
 * 「液态玻璃」底板 —— 移植自 Aerial TV 的 {@code LiquidGlass}。
 *
 * <h3>它画什么</h3>
 * <ol>
 *   <li><b>背后的磨砂背景</b>：从 {@link FrostedBackdrop} 里按自己在窗口中的位置取样，
 *       再用色矩阵压到半亮（白字压在雪山上也读得清）；</li>
 *   <li><b>受光的边</b>：顶部内侧一道白高光、一圈白描边（左上最亮，侧面很淡，远端角落再亮一点）；</li>
 *   <li><b>焦点态</b>：描边变亮变粗 —— 原来 {@code bg_pill} 用 selector 做的那件事。</li>
 * </ol>
 *
 * <h3>为什么做成 Drawable 而不是自定义 View</h3>
 * 桌面上有一堆胶囊（模块切换器、页签、时钟、提示条…），它们的布局各不相同（有横有竖、
 * 里面还套着东西）。做成 View 就得一个个改布局结构；做成 Drawable 只要把
 * {@code android:background} 换掉，布局一行不动，而且 Drawable 能通过
 * {@link #getCallback()} 拿到宿主 View，从而知道自己该从磨砂图的哪个位置取样。
 *
 * <p>不是实时模糊：取的是背景的静态快照，面板移动/背景变化后要重新
 * {@link FrostedBackdrop#capture(View)}（桌面在换图时已经这么做了）。
 */
public class GlassDrawable extends Drawable {

    /**
     * 玻璃没取到背景时的底色。
     *
     * <p>不用纯黑：纯黑块贴在彩色海报上像一块补丁（用户原话「纯黑色背景」）。
     * 换成半透明的中性深灰 —— 透出一点背后的颜色，又压得住白字。
     */
    private static final int FLAT_GLASS = 0xB3222028;
    /** 焦点态描边 */
    private static final int RIM_FOCUSED = 0xCCFFFFFF;
    /** 常态描边 */
    private static final int RIM_NORMAL = 0x33FFFFFF;

    /**
     * 压暗矩阵。
     *
     * <p>基准是 Aerial TV 的 PanelTint（0.48 亮度 + 14 偏移）—— 但那套是按
     * 「明亮壁纸」调的（他们的壁纸是航拍风景，雪地、白墙都很亮）。我们这边的背景
     * 是深色电影海报，同一组参数压完就发黑、整块像补丁。
     * 所以往上抬到 0.58 / 20：暗背景里还能透出海报颜色，白字照样读得清。
     */
    private static final ColorFilter TINT = new ColorMatrixColorFilter(new ColorMatrix(
            new float[]{
                    0.58f, 0f, 0f, 0f, 20f,
                    0f, 0.58f, 0f, 0f, 20f,
                    0f, 0f, 0.58f, 0f, 20f,
                    0f, 0f, 0f, 1f, 0f,
            }));

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix shaderMatrix = new Matrix();

    private final float radiusPx;
    /** 底部两角要不要圆（从底部升起的面板只圆上面两角） */
    private final boolean roundBottom;
    private boolean focused;
    /** 上一次成功取到的窗口位置：布局还没落定的那一帧拿它顶着，别画成纯色块 */
    private final int[] lastLoc = new int[2];

    public GlassDrawable(float radiusPx) {
        this(radiusPx, true);
    }

    public GlassDrawable(float radiusPx, boolean roundBottom) {
        this.radiusPx = radiusPx;
        this.roundBottom = roundBottom;
        rimPaint.setStyle(Paint.Style.STROKE);
        flatPaint.setColor(FLAT_GLASS);
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

        // 1) 背后的磨砂背景（没有就退回纯色玻璃）
        Bitmap frosted = FrostedBackdrop.get();
        boolean sampled = false;
        if (frosted != null && !frosted.isRecycled() && FrostedBackdrop.width() > 0) {
            View host = hostView();
            if (host != null) {
                int[] loc = new int[2];
                host.getLocationInWindow(loc);
                if (loc[0] == 0 && loc[1] == 0) {
                    // 还没走完第一帧布局，拿不到位置。
                    // 以前这里退回纯色玻璃 —— 但位置定下来之后 View 不会自己重画，
                    // 那块底色就永远留在屏幕上了（顶栏胶囊「藏过再显示」时最明显）。
                    // 有上一次取到的位置就沿用（顶栏这几颗位置固定，错不到哪去），
                    // 一次都没有才退回纯色。
                    if (lastLoc[0] != 0 || lastLoc[1] != 0) {
                        loc[0] = lastLoc[0];
                        loc[1] = lastLoc[1];
                    } else {
                        loc = null;
                    }
                } else {
                    lastLoc[0] = loc[0];
                    lastLoc[1] = loc[1];
                }
                if (loc != null) {
                    BitmapShader shader = new BitmapShader(frosted,
                            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shaderMatrix.reset();
                    shaderMatrix.setScale(
                            FrostedBackdrop.width() / (float) frosted.getWidth(),
                            FrostedBackdrop.height() / (float) frosted.getHeight());
                    shaderMatrix.postTranslate(-loc[0], -loc[1]);
                    shader.setLocalMatrix(shaderMatrix);
                    fillPaint.setShader(shader);
                    fillPaint.setColorFilter(TINT);
                    canvas.drawRoundRect(rect, r, r, fillPaint);
                    fillPaint.setShader(null);
                    fillPaint.setColorFilter(null);
                    sampled = true;
                }
            }
        }
        if (!sampled) {
            canvas.drawRoundRect(rect, r, r, flatPaint);
        }

        // 2) 顶部内侧高光：光从上方打下来，玻璃上沿先亮
        sheenPaint.setShader(new LinearGradient(0f, bounds.top, 0f, bounds.bottom,
                new int[]{0x1AFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, r, r, sheenPaint);
        sheenPaint.setShader(null);

        // 3) 受光的边：焦点态更亮更粗
        rimPaint.setStrokeWidth(focused ? dp(2.5f) : dp(1f));
        rimPaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right,
                bounds.bottom,
                focused
                        ? new int[]{0xFFFFFFFF, 0x88FFFFFF, 0x66FFFFFF, 0xCCFFFFFF}
                        : new int[]{RIM_NORMAL, 0x14FFFFFF, 0x0DFFFFFF, 0x44FFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        float inset = rimPaint.getStrokeWidth() / 2f;
        rect.set(bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset);
        canvas.drawRoundRect(rect, Math.max(0f, r - inset), Math.max(0f, r - inset), rimPaint);
        rimPaint.setShader(null);
    }

    private View hostView() {
        Callback callback = getCallback();
        return callback instanceof View ? (View) callback : null;
    }

    private float dp(float value) {
        View host = hostView();
        float density = host == null
                ? 1f : host.getResources().getDisplayMetrics().density;
        return value * density;
    }

    @Override
    public void setAlpha(int alpha) {
        // 玻璃的透明度由取样与描边自己控制，外部 alpha 不再叠加
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** 底部圆角开关（给从底部升起的面板用） */
    public boolean roundsBottom() {
        return roundBottom;
    }
}

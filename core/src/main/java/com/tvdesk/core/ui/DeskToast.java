package com.tvdesk.core.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 统一的底部提示条（Toast）。
 *
 * <h3>为什么不直接用系统 Toast</h3>
 * 这台电视上系统 Toast 是「直角 + 浅灰实底」—— 电视离三米，一块硬邦邦的白方块突然压在画面上，
 * 比它说的那句话还抢眼。而且原生 Toast 的样式由系统定，改不了。
 *
 * <p>App 的 targetSdk 还是 28，{@link Toast#setView} 仍然有效，所以这里给 Toast 换一个自己画的视图：
 * 圆角 + 半透明深色磨砂底 + 一圈很淡的描边，和桌面上的胶囊、设置行是同一套视觉语言。
 *
 * <h3>用法</h3>
 * 和系统写法一模一样，只是把 {@code Toast.makeText} 换成 {@link #makeText}：
 * <pre>
 * DeskToast.makeText(context, "已收藏", Toast.LENGTH_SHORT).show();
 * </pre>
 * 这样调用方不用改结构，也方便以后统一调整位置/时长。
 */
public final class DeskToast {

    /** 离屏幕底边的距离：避开电视的过扫区（safe_bottom），也不压住播放条 */
    private static final int BOTTOM_OFFSET_DP = 96;
    /** 文字最大宽度：长句折成两行，别拉成通栏 */
    private static final float MAX_WIDTH_RATIO = 0.62f;

    private DeskToast() {
    }

    /**
     * 和 {@code Toast.makeText} 同签名，返回的 Toast 已经换好视图，调用方照常 {@code .show()}。
     */
    public static Toast makeText(Context context, CharSequence text, int duration) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        Toast toast = new Toast(app);
        toast.setDuration(duration);
        toast.setGravity(Gravity.BOTTOM, 0, dp(app, BOTTOM_OFFSET_DP));
        try {
            toast.setView(buildView(app, text));
        } catch (Throwable ignored) {
            // 个别 ROM 上 setView 被忽略：保持系统样式总比崩了好
        }
        return toast;
    }

    /** 文案来自资源 id 的那一半调用点 */
    public static Toast makeText(Context context, int resId, int duration) {
        CharSequence text = context == null ? "" : context.getString(resId);
        return makeText(context, text, duration);
    }

    /** 一行式的快捷方法，给新代码用 */
    public static void show(Context context, CharSequence text) {
        makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    private static TextView buildView(Context context, CharSequence text) {
        TextView view = new TextView(context);
        view.setText(text == null ? "" : text);
        view.setTextColor(0xFFF4F6FA);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        view.setGravity(Gravity.CENTER);
        view.setLineSpacing(dp(context, 3), 1f);
        view.setMaxLines(2);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setPadding(dp(context, 26), dp(context, 13), dp(context, 26), dp(context, 13));

        // 磨砂底：从上到下略微加深一点点，圆角 20dp，一圈 12% 的白描边。
        // 不用纯色是因为纯色在半透明时看着发灰，带一点纵向渐变更像玻璃。
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xE622232B, 0xD9181921});
        bg.setCornerRadius(dp(context, 20));
        bg.setStroke(Math.max(1, dp(context, 1)), 0x22FFFFFF);
        view.setBackground(bg);

        if (android.os.Build.VERSION.SDK_INT >= 21) {
            view.setElevation(dp(context, 10));
        }
        int max = (int) (context.getResources().getDisplayMetrics().widthPixels * MAX_WIDTH_RATIO);
        view.setMaxWidth(Math.max(max, dp(context, 240)));
        view.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

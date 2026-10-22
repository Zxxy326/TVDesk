package com.tvdesk.desk.motion;

import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 焦点缩放。桌面所有可聚焦元素共用一套参数，保证手感一致。
 *
 * <p>参数取 Apple TV 档：放大 1.08x、180ms、{@code cubic-bezier(0.2,0,0,1)}。
 * 失焦回落用 140ms，比进入更快，避免「按完还黏着」。
 *
 * <p>连发压缩（{@link #DURATION_REPEAT}）由 {@link KeyRepeatHandler} 驱动：遥控器按住方向键时
 * 每 ~50ms 来一次事件，如果每次都排 180ms 动画会积压成「按了没反应然后猛跳」。
 * 连发期间改用 80ms 并取消当前动画，从当前值续上，绝不排队。
 */
public final class FocusScaler {

    public static final float SCALE_FOCUSED = 1.08f;

    public static final long DURATION_FOCUS = 180L;
    public static final long DURATION_UNFOCUS = 140L;
    /** 连发期间使用，由 {@link KeyRepeatHandler} 触发 */
    public static final long DURATION_REPEAT = 80L;

    /**
     * 桌面里所有可聚焦元素共用的「是否正在连发」。
     *
     * <p>写成常量而不是让调用方传参：连发是个**全局的输入状态**，
     * 同一个时刻不可能出现「这个元素在连发、那个元素不在连发」。
     * 让每个调用点各自判断，只会让某处漏判、手感变得不一致。
     */
    private static final KeyRepeatHandler REPEAT = KeyRepeatHandler.get();

    private FocusScaler() {
    }

    /** 挂上默认缩放行为 */
    public static void attach(View view) {
        attach(view, SCALE_FOCUSED, (View.OnFocusChangeListener) null);
    }

    public static void attach(final View view, final float focusedScale) {
        attach(view, focusedScale, (View.OnFocusChangeListener) null);
    }

    /**
     * 挂缩放行为，同时允许调用方附加一个焦点变化监听。
     *
     * <p>{@link View#setOnFocusChangeListener} 是<b>覆盖式</b>的（View 内部只存一个字段），
     * 两个调用方各自 set 一次，后挂的会把先挂的挤掉。所以附加监听必须在这里合并成
     * 一个监听器，不能由调用方挂完再回来 {@link #attach}。
     *
     * <p>桌面海报行用它做「焦点到哪张，Hero 背景换成哪张」。
     *
     * @param extra 可空的附加监听；缩放照常生效，两者互不可见
     */
    public static void attach(final View view, final float focusedScale,
            @Nullable final View.OnFocusChangeListener extra) {
        disableSystemFocusHighlight(view);
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                toScale(v, hasFocus ? focusedScale : 1f, durationFor(hasFocus));
                // 抬升层级，避免放大后被相邻元素压住
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    v.setTranslationZ(hasFocus ? 12f : 0f);
                }
                if (extra != null) {
                    extra.onFocusChange(v, hasFocus);
                }
            }
        });
    }

    /**
     * 关掉系统自带的焦点高亮。
     *
     * <p>API 26 起，可聚焦的 View 拿到焦点时会被叠一层半透明白色矩形（default focus
     * highlight）。它按 View 的**外接矩形**画，而卡片根节点是「图框 + 标题 + 说明」的
     * 竖排 LinearLayout，于是白色一直铺到说明行底下，卡片下半截看起来发灰。
     *
     * <p>桌面所有元素本来就自带焦点态（{@code bg_poster} 白描边 + 缩放 + 抬层级），
     * 这层高亮纯属多余，直接在 {@link #attach} 里统一关掉，而不是每个界面各自处理。
     */
    private static void disableSystemFocusHighlight(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setDefaultFocusHighlightEnabled(false);
        }
    }

    /**
     * 选时长。
     *
     * <p>连发期间**进出都用 {@link #DURATION_REPEAT}**：焦点右移时，旧元素要缩回去、
     * 新元素要涨出来，两者是同一件事的两半。只压进入不压缩回，旧元素会以 180ms 慢慢缩，
     * 新元素已经涨完了它还占着放大态，视觉上像两个元素叠在一起。
     */
    private static long durationFor(boolean hasFocus) {
        if (REPEAT.isRepeating()) {
            return DURATION_REPEAT;
        }
        return hasFocus ? DURATION_FOCUS : DURATION_UNFOCUS;
    }

    /**
     * 缩放到目标值。
     *
     * <p>先 cancel 再重设目标：ViewPropertyAnimator 会从当前实际值续上，
     * 这样连发时不会回弹到起点。
     */
    public static void toScale(View view, float target, long duration) {
        view.animate().cancel();
        view.animate()
                .scaleX(target)
                .scaleY(target)
                .setDuration(duration)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG)
                .start();
    }
}

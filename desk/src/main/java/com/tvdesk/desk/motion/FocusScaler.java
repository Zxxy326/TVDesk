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
        unclipAncestors(view);
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
     * 把子树里所有「可聚焦但没开 inTouchMode」的控件补成两样都行。
     *
     * <p>为什么需要：窗口一旦进了 touch mode（触摸板、空中飞鼠、系统弹窗点过一下），
     * 只有 {@code focusableInTouchMode} 的控件才配接收焦点。XML 里只写
     * {@code android:focusable="true"} 的那些（Hero 主按钮、海报行标题…）从此
     * 方向键再也碰不到，看起来就是「往下按没反应」。遥控器纯按键的用户永远碰不到这个坑，
     * 一旦用过鼠标就整台电视都点不动了。
     */
    public static void makeFocusableInTouchMode(View root) {
        if (root == null) {
            return;
        }
        if (root.isFocusable() && !root.isFocusableInTouchMode()) {
            root.setFocusableInTouchMode(true);
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                makeFocusableInTouchMode(group.getChildAt(i));
            }
        }
    }

    /**
     * 把「从自己到页面根」这条父链上的裁剪全部打开，让放大出来的那一圈有地方画。
     *
     * <p>为什么必须在 attach 里自动做，而不是靠 XML 逐层写 {@code clipChildren="false"}：
     * 缩放是 1.08，左右各溢出 4% 宽度。48dp 高、240dp 宽的胶囊按钮，左右会各多出
     * 近 10dp —— 这正是「按钮左边圆角被削平一块」的来源。它不像系统焦点高亮那样
     * 换个弹窗就没了，而是**每新增一个页面就得记得写一遍**，漏一层就复发
     * （详情页、B 站详情页、音乐页都曾各漏一次）。
     *
     * <p>遇到滚动容器就停：AbsListView / RecyclerView / ScrollView 的裁剪是它们
     * 正常工作的一部分，关掉会让滚动内容溢出到列表外，比裁边难受得多。
     * 滚动容器里的卡片靠 item 自身留内边距，不能靠这条路。
     */
    private static void unclipAncestors(View view) {
        android.view.ViewParent parent = view.getParent();
        int guard = 0;
        while (parent instanceof android.view.ViewGroup && guard++ < 12) {
            android.view.ViewGroup group = (android.view.ViewGroup) parent;
            if (isScrollContainer(group)) {
                return;
            }
            group.setClipChildren(false);
            group.setClipToPadding(false);
            parent = group.getParent();
        }
    }

    /** 这些容器的裁剪不能动：滚动时靠它把出界的内容挡在视口外 */
    private static boolean isScrollContainer(android.view.ViewGroup group) {
        return group instanceof android.widget.AbsListView
                || group instanceof androidx.recyclerview.widget.RecyclerView
                || group instanceof android.widget.ScrollView
                || group instanceof android.widget.HorizontalScrollView;
    }

    /**
     * 递归关掉整棵子树里所有控件的系统焦点高亮。
     *
     * <p>弹窗这种「一次 show 出一堆焦点行、行样式在 XML/drawable 里定好」的地方，
     * 逐个 {@link #attach} 很啰嗦（还得连带缩放），但方形高亮一样会盖在圆角上：
     * 白框按 2dp 圆角画，系统那层按外接矩形画，直角边会在四个角上「露出来」，
     * 看上去像按钮被裁了一角。弹窗只需关掉这一层，缩放交给各自的 bg 选择器。
     */
    public static void disableSystemHighlightTree(android.view.View root) {
        if (root == null) {
            return;
        }
        disableSystemFocusHighlight(root);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableSystemHighlightTree(group.getChildAt(i));
            }
        }
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

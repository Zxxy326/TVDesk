package com.github.tvbox.osc.ui.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.HorizontalScrollView;

/**
 * 只跟着焦点滚、不抢方向键的横向容器。
 *
 * <p>播放控制条那一排按钮（下一集 / 上一集 / 倍速 / 字幕…）原本套在
 * {@link HorizontalScrollView} 里，问题就出在这儿：它把 {@code dispatchKeyEvent} 覆写成
 * 「先给孩子，孩子不要我就自己滚」，于是左右键在按钮之间<b>永远走不动</b> ——
 * {@code arrowScroll} 把这一下当成「横向滚动」消费掉，返回 true，
 * 系统的焦点导航（要整条链路都返回 false 才会走）根本没机会执行。
 * 用户看到的就是：底栏弹出来了、第一颗按钮亮着，左右键却一格都挪不动。
 *
 * <p>所以这里把 {@code arrowScroll} 关掉。滚动不用它操心：
 * 焦点落到屏幕外的按钮上时，{@code ViewGroup.requestChildFocus} 会回调
 * {@code requestChildRectangleOnScreen}，系统自己会把这个孩子滚进可视区。
 */
public class FocusRowScroll extends HorizontalScrollView {

    public FocusRowScroll(Context context) {
        super(context);
    }

    public FocusRowScroll(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FocusRowScroll(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean arrowScroll(int direction) {
        return false;
    }
}

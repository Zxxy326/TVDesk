package com.tvdesk.desk.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.GridView;

/**
 * 自己翻行的卡片墙。
 *
 * <p>{@code GridView} 每次 {@code layoutChildren} 都优先回到内部的「选中项」，而这个值在首帧
 * 被设成 0。于是只要有一张封面加载完触发一次 {@code requestLayout}，哪怕墙已经平滑滚到第十行，
 * 也会整面弹回顶部，焦点还留在屏幕外——用户看到的就是「闪一下过去」。
 *
 * <p>所以翻页拆成两步：动画期间先按住布局请求，别让它中途重排；滚完把选中项锚到当前首行
 * （连偏移一起记），之后无论谁再触发布局，都只会复现这个位置。
 */
public class PageGridView extends GridView {

    private boolean holding;
    /** 连按时只认最后一轮翻页的落位回调 */
    private int token;

    public PageGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 平滑翻 {@code rows} 行（负值向上），动画结束自动锚定位置 */
    public void pageBy(int rows, int rowHeight, int durationMs) {
        final int mine = ++token;
        holding = true;
        smoothScrollBy(rows * rowHeight, durationMs);
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mine == token) {
                    settle();
                }
            }
        }, durationMs + 60);
    }

    /** 把「选中项」钉在当前首行和当前偏移上，之后的布局不会再把墙拉回第 0 行 */
    private void settle() {
        View first = getChildAt(0);
        if (first != null) {
            setSelectionFromTop(getFirstVisiblePosition(), first.getTop() - getListPaddingTop());
        }
        holding = false;
    }

    @Override
    public void requestLayout() {
        if (!holding) {
            super.requestLayout();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        holding = false;
        super.onDetachedFromWindow();
    }
}

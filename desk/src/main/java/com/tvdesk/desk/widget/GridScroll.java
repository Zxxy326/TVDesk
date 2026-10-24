package com.tvdesk.desk.widget;

import android.view.View;
import android.widget.GridView;

/**
 * 卡片墙整行翻页。
 *
 * <p>{@code GridView.setSelection} 是瞬移：目标行还没挂载时，一次按键直接换一屏，
 * 观感是「闪一下过去」。这里改成按真实行高平滑滚动，只滚到目标行刚好完整露出来为止，
 * 一次按键最多一行，顺序和瀑布流一致。滚动期间不碰焦点，滚完由调用方补焦。
 *
 * <p>返回值给调用方做 {@code postDelayed} 的等待时长：动画结束再加一帧布局，
 * 目标行的子 View 才停稳，此时 requestFocus 才不会静默失败。
 */
public final class GridScroll {

    /** 翻一行的时长。短了仍像闪，长了连按会跟不上手 */
    private static final int PAGE_MS = 220;

    private GridScroll() {
    }

    /**
     * 卡片是否整张都在视口里。
     *
     * <p>{@code getChildAt} 返回非空只说明这一行已经挂载，不代表看得见：
     * GridView 会多铺一排到视口外，此时直接 requestFocus 也能成功，
     * 结果就是焦点落到屏幕外、墙却不滚——用户看到的是「闪一下就没了」。
     */
    public static boolean isFullyVisible(GridView grid, View child) {
        if (grid == null || child == null) {
            return false;
        }
        int top = grid.getPaddingTop();
        int bottom = grid.getHeight() - grid.getPaddingBottom();
        return child.getTop() >= top && child.getBottom() <= bottom;
    }

    /**
     * 把包含 {@code position} 的那一行滚到视口里，尽量只滚到它刚露全。
     *
     * <p>不用 {@code smoothScrollToPositionFromTop}：GridView 对未挂载的行只有估算高度，
     * 配上较大的 top padding 会滚不到目标，或在补焦时被重排拉回来。这里按已挂载行的
     * 真实行高算位移；目标行还没挂载（焦点刚从别处回来）时退化成整行差。
     */
    public static long smoothToRow(GridView grid, int position) {
        if (grid == null || position < 0 || position >= grid.getCount()) {
            return 0;
        }
        int columns = Math.max(1, grid.getNumColumns());
        int row = position / columns;
        View first = grid.getChildAt(0);
        if (first == null || first.getHeight() <= 0) {
            // 还没布局过，没有可参照的行；先落到目标位置，下一帧再补焦
            grid.setSelection(position);
            return 80;
        }
        int pitch = first.getHeight() + grid.getVerticalSpacing();
        View child = grid.getChildAt(position - grid.getFirstVisiblePosition());
        int rows;
        if (child != null) {
            int top = grid.getPaddingTop();
            int bottom = grid.getHeight() - grid.getPaddingBottom();
            if (child.getBottom() > bottom) {
                rows = (child.getBottom() - bottom + pitch - 1) / pitch;
            } else if (child.getTop() < top) {
                rows = -((top - child.getTop() + pitch - 1) / pitch);
            } else {
                rows = 0;
            }
        } else {
            rows = row - grid.getFirstVisiblePosition() / columns;
        }
        if (rows == 0) {
            return 60;
        }
        if (grid instanceof PageGridView) {
            ((PageGridView) grid).pageBy(rows, pitch, PAGE_MS);
        } else {
            grid.smoothScrollBy(rows * pitch, PAGE_MS);
        }
        return PAGE_MS + 80;
    }
}

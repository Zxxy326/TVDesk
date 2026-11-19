package com.tvdesk.desk.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/**
 * 一行海报（横向滚动）。桌面首页的「今日热门」是这么排的，音乐/哔哩哔哩的模块首页
 * 现在也照这个来：Hero 大图 + 顶部胶囊 + 信息控制区 + 底部一行海报。
 *
 * <h3>为什么不复用 {@link TileWall}</h3>
 * 墙是「纵向滚 + 按行翻页」的（{@code scrollTo(0, y)}），一行做成横向要改的是它的滚动、
 * 对焦、测量三套逻辑，风险比新写一个大。这里只做「一行」，其余照墙的实现方式：
 * 复用同一个 {@link TileWall.Adapter}，方法名也刻意对齐（setAdapter / notifyDataSetChanged /
 * focusPosition / getPositionForView / setEdgeListener / setScrollListener），
 * 页面里把类型换掉就能编译过，行为从「墙」变成「行」。
 *
 * <h3>宽度怎么定</h3>
 * 一屏放几张贴图由 {@link #setNumColumns(int)} 决定（页面照旧传自己的列数），
 * 卡片宽度 = (可用宽 - 间距 × (列数-1)) / 列数，所以换行不换排版。
 */
public class TileRow extends HorizontalScrollView {

    private final LinearLayout content;
    private TileWall.Adapter adapter;
    private TileWall.OnEdgeListener edgeListener;
    private TileWall.ScrollListener scrollListener;

    private int columns = 6;
    private int hSpace;
    /** 固定卡片宽（px）；<=0 表示按列数均分 */
    private int fixedItemWidth;
    private OnItemFocus onItemFocus;

    /** 卡片获得焦点（页面用它换 Hero 大图和信息） */
    public interface OnItemFocus {
        void onItemFocus(int index);
    }

    public void setOnItemFocus(OnItemFocus listener) {
        this.onItemFocus = listener;
    }

    public TileRow(Context context) {
        this(context, null);
    }

    public TileRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        // 焦点放大的一圈不能被自己裁掉（和桌面海报行同一处理）
        setClipChildren(false);
        setClipToPadding(false);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---------------------------------------------------------------- 页面用的接口（对齐 TileWall）

    public void setAdapter(TileWall.Adapter adapter) {
        this.adapter = adapter;
        rebind();
    }

    public TileWall.Adapter getAdapter() {
        return adapter;
    }

    /** 一行放几张。只用来算卡片宽度，不影响「一行」这件事 */
    public void setNumColumns(int columns) {
        this.columns = Math.max(1, columns);
        requestLayout();
    }

    public int getNumColumns() {
        return columns;
    }

    /** 固定每张卡宽度（dp）。首页那排海报用这个，卡片大小才和设计稿一致 */
    public void setItemWidthDp(float widthDp) {
        this.fixedItemWidth = Math.round(widthDp * getResources().getDisplayMetrics().density);
        requestLayout();
    }

    public void setSpacingDp(float horizontalDp, float verticalDp) {
        this.hSpace = Math.round(horizontalDp * getResources().getDisplayMetrics().density);
        requestLayout();
    }

    public void setEdgeListener(TileWall.OnEdgeListener listener) {
        this.edgeListener = listener;
    }

    /**
     * 滚到接近末尾时报一次（参数照 TileWall 的语义：只有一行，所以 first/last 都是 0）。
     * 分页加载靠它——B 站推荐流一页 20 条，走到倒数第三张就去要下一页。
     */
    public void setScrollListener(TileWall.ScrollListener listener) {
        this.scrollListener = listener;
    }

    public void notifyDataSetChanged() {
        rebind();
    }

    public void scrollToTop() {
        scrollTo(0, 0);
    }

    public int getCount() {
        return content.getChildCount();
    }

    public int getPositionForView(View view) {
        View cur = view;
        while (cur != null && cur.getParent() != content) {
            if (!(cur.getParent() instanceof View)) {
                return -1;
            }
            cur = (View) cur.getParent();
        }
        return cur == null ? -1 : content.indexOfChild(cur);
    }

    /** 把焦点落到第 index 张卡上（切栏目/回焦点时用） */
    public boolean focusPosition(int index) {
        if (index < 0 || index >= content.getChildCount()) {
            return false;
        }
        View child = content.getChildAt(index);
        if (!child.isFocusable()) {
            return false;
        }
        child.requestFocus();
        smoothScrollToChild(child);
        return true;
    }

    public int focusedPosition() {
        View focused = findFocus();
        return focused == null ? -1 : getPositionForView(focused);
    }

    // ---------------------------------------------------------------- 内部

    private void rebind() {
        content.removeAllViews();
        if (adapter == null) {
            return;
        }
        int count = adapter.getCount();
        for (int i = 0; i < count; i++) {
            final int index = i;
            View item = adapter.getView(i, null, content);
            if (item == null) {
                continue;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    itemWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = hSpace;
            // 卡片宽度由这一行统一给：item 自己的布局里写死宽高会在这排不齐
            item.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        smoothScrollToChild(v);
                        maybeLoadMore(index);
                        if (onItemFocus != null) {
                            onItemFocus.onItemFocus(index);
                        }
                    }
                }
            });
            // 卡片是复用的 TileWall 适配器，里面会调 RevealAnim.staggerIn（alpha 0 → 1）。
            // 单行里几十张卡一起错峰淡入反而糊，而且离屏那几张会一直停在半透明，
            // 所以这里直接把卡片摆到终态，入场交给整行做
            item.setAlpha(1f);
            item.setTranslationY(0f);
            content.addView(item, lp);
        }
        content.requestLayout();

    }

    private int itemWidth() {
        if (fixedItemWidth > 0) {
            return fixedItemWidth;
        }
        int avail = getWidth() - getPaddingLeft() - getPaddingRight()
                - hSpace * (columns - 1);
        if (avail <= 0) {
            // 还没量出宽度：先给个和屏幕同比例的估值，量完 onSizeChanged 会重建
            avail = getResources().getDisplayMetrics().widthPixels
                    - getPaddingLeft() - getPaddingRight() - hSpace * (columns - 1);
        }
        return Math.max(1, avail / Math.max(1, columns));
    }

    private void maybeLoadMore(int index) {
        if (scrollListener == null || adapter == null) {
            return;
        }
        if (index >= adapter.getCount() - 3) {
            scrollListener.onScroll(0, 0, 1);
        }
    }

    private void smoothScrollToChild(View child) {
        int target = child.getLeft() - (getWidth() - child.getWidth()) / 2;
        int max = Math.max(0, content.getWidth() - getWidth()
                + getPaddingLeft() + getPaddingRight());
        target = Math.max(0, Math.min(max, target));
        smoothScrollTo(target, 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw && adapter != null) {
            // 宽度变了，卡片宽度跟着变。
            // 必须延后一帧：onSizeChanged 是在**布局过程中**回调的，这时重建子 View，
            // 父容器这一轮布局已经算完了，新加进来的卡片不会被测量 —— 表现就是
            // 「行占着位置、卡片全是 0×0、屏幕上什么都看不到」。（实测过）
            post(new Runnable() {
                @Override
                public void run() {
                    rebind();
                }
            });
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && edgeListener != null) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                View focused = findFocus();
                int pos = focused == null ? -1 : getPositionForView(focused);
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0
                        && edgeListener.onEdge(code, 0)) {
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && pos == getCount() - 1
                        && edgeListener.onEdge(code, pos)) {
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }
}

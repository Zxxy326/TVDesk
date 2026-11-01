package com.tvdesk.desk.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
 *
 * <p>另一件事是<b>焦点</b>。列表一重排（翻页加载下一批、换栏目、封面陆续到达），
 * 带焦点的那张卡会被回收，于是构建窗口时一句 {@code restoreDefaultFocus} 就把焦点
 * 交给页面里第一个可聚焦控件 —— 左上角那颗模块胶囊。用户看到的是「翻着翻着跳一下左上角」。
 * 页面层原来靠一个 32ms 的延时补焦，中间那一两帧该跳还是照跳。
 * 这里改成在 <b>onLayout 里、这一帧画出来之前</b>就把焦点还给原来的位置，用户看不到过渡。
 * 所有用这个控件的墙（B 站 / 音乐 / 应用）一起受益。
 */
public class PageGridView extends GridView implements ViewTreeObserver.OnGlobalFocusChangeListener {

    private boolean holding;
    /** 连按时只认最后一轮翻页的落位回调 */
    private int token;

    /** 用户在墙里最后站的那一张（列表下标）；-1 表示用户不在墙里 */
    private int keepPosition = -1;
    /** 正在 onLayout 里补焦点：避免 requestFocus 再触发一次布局、递归进来 */
    private boolean restoring;
    /**
     * 用户是不是<b>主动</b>把焦点带出了墙。
     *
     * <p>翻页加载下一批时，带焦点的卡会被回收，系统顺手把焦点交给窗口里
     * 第一个可聚焦控件（左上角那颗模块胶囊）—— 这是被动的，不算用户走了。
     * 这个标记只在收到<b>用户按键</b>时才置真，用来区分这两种情况。
     */
    private boolean userLeftWall;

    /**
     * 到边之后左右键就地吃掉。
     *
     * <p>GridView 不管边界：第一列再按左，几何寻焦在墙外找不到更近的卡，就一路摸到
     * 屏幕左上角的模块胶囊 / 页签上。整页铺满的墙（应用、B 站、音乐、信号源）都该打开，
     * 用户的预期是「已经在最左边了，按左不动」。
     *
     * <p>默认关：详情页的选集格、搜索页的结果格旁边就有可去的控件
     * （线路条 / 键盘），吃掉左键会把人困住。
     */
    private boolean keepFocusAtEdges;

    public PageGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // 墙本身不参与焦点搜索，焦点只落在墙里的卡上。
        //
        // <p>AbsListView 构造时把自己设成了可聚焦，于是它自己也成了焦点候选 ——
        // 而且它的矩形比里面的卡更靠外（左边距更近），几何搜索常常是它赢。
        // 搜索页里从键盘往右按，焦点就落在结果墙这个「空壳」上：
        // 什么都不高亮、按确定也没反应，看起来就是焦点丢了。
        // 把容器摘掉之后，方向键会直接落到（或越过）卡上。
        // 墙的补焦、翻页走的是 child.requestFocus()，不依赖容器可聚焦。
        setFocusable(false);
        setFocusableInTouchMode(false);
        // 卡片聚焦时要放大（FocusScaler），放大后的边会超出格子边界。
        // 网格这一层不裁，也顺手关掉 padding 裁剪 —— 顶行卡片放大向上溢出的部分
        // 正好落在 padding 区域里，默认 clipToPadding 会把它切平。
        // 祖先层的裁剪由各布局自己关（见 favorites_dual / category_wall 的 XML）。
        setClipChildren(false);
        setClipToPadding(false);
    }

    /**
     * 焦点是不是已经贴在墙的左右边界上（左键在最左列、右键在最右列/最后一张）。
     *
     * <p>页面层拿到方向键时用它判断要不要就地吃掉 —— 墙自己拦不到这些键：
     * 按键是顺着焦点链走的，墙在到边时已经被跳过了。
     */
    public static boolean atHorizontalEdge(GridView grid, int keyCode) {
        if (grid == null) {
            return false;
        }
        View focused = grid.findFocus();
        if (focused == null) {
            return false;
        }
        int position = grid.getPositionForView(focused);
        if (position < 0) {
            return false;
        }
        int columns = Math.max(1, grid.getNumColumns());
        int column = position % columns;
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            return column == 0;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            return column == columns - 1 || position == grid.getCount() - 1;
        }
        return false;
    }

    /** 到边之后吃掉左右键（见 {@link #keepFocusAtEdges}） */
    public void setKeepFocusAtEdges(boolean keep) {
        keepFocusAtEdges = keep;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (keepFocusAtEdges && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                View focused = findFocus();
                int position = focused == null ? -1 : getPositionForView(focused);
                if (position >= 0) {
                    int columns = Math.max(1, getNumColumns());
                    int column = position % columns;
                    if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT && column == 0) {
                        return true;
                    }
                    if (key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            && (column == columns - 1 || position == getCount() - 1)) {
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
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
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnGlobalFocusChangeListener(this);
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(this);
    }

    // ---------------------------------------------------------------- 焦点

    @Override
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        if (newFocus == null) {
            // 聚焦的卡被回收了：位置先留着，等 onLayout 补回去。
            // 这里不能清，否则「丢失 → 重排 → 恢复」这一段就断了
            return;
        }
        int position = positionOf(newFocus);
        if (position >= 0) {
            keepPosition = position;
            userLeftWall = false;
            return;
        }
        if (newFocus == this) {
            return;
        }
        // 焦点去了墙外。分两种情况，处置完全相反：
        //
        // <p><b>用户自己走出去</b>：焦点从还挂在墙上的那张卡，被方向键带到了
        // 顶栏 / 页签这类控件（oldFocus 还挂在墙上就是证据）。这时必须把
        // 记住的位置清掉 —— 否则用户在顶栏上按左右换页签，每换一次内容重排一次，
        // 每次重排 {@link #restoreFocus()} 都会把焦点从页签上拽回墙里，
        // 顶栏根本没法逛。这正是「顶栏左右移动时焦点自己跳进内容里」的根源。
        //
        // <p><b>系统把焦点甩出来</b>：往下翻加载下一批时，带焦点的卡被回收，
        // 焦点被系统交给窗口里第一个可聚焦控件。oldFocus 已经不在墙上了 ——
        // 这不是用户走的，位置留着，而且<b>这一帧之内就得把焦点按回墙里</b>：
        // 平滑翻行是一帧一帧走的，等 onLayout 再补，用户已经看见焦点
        // 「跳到左上角闪一下再回来」了。这里立刻抓住墙里离原位置最近的可见卡，
        // 等滚动停稳后页面自己的补焦再把焦点放到该去的那格。
        // （以前这里一刀切把 keepPosition 清成 -1，翻页时焦点就会跳去左上角。）
        if (positionOf(oldFocus) >= 0) {
            userLeftWall = true;
            keepPosition = -1;
            return;
        }
        if (keepPosition >= 0 && isShown() && getChildCount() > 0) {
            View near = nearestAttachedChild(keepPosition);
            if (near != null && near.requestFocus()) {
                keepPosition = positionOf(near);
            }
        }
    }

    /** 挂载着的卡里离 {@code target} 位置最近的那张；墙是空的返回 null */
    private View nearestAttachedChild(int target) {
        int first = getFirstVisiblePosition();
        View best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < getChildCount(); i++) {
            int position = first + i;
            int distance = Math.abs(position - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = getChildAt(i);
            }
        }
        return best;
    }

    /** newFocus 在墙里的下标；不在墙里返回 -1 */
    private int positionOf(View view) {
        View node = view;
        while (node != null) {
            ViewGroup parent = node.getParent() instanceof ViewGroup
                    ? (ViewGroup) node.getParent() : null;
            if (parent == this) {
                int index = indexOfChild(node);
                return index < 0 ? -1 : getFirstVisiblePosition() + index;
            }
            node = parent;
        }
        return -1;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        restoreFocus();
    }

    /**
     * 重排之后把焦点放回用户原来站的那一张。
     *
     * <p>只在「墙里已经没有控件持有焦点」时动手 —— 用户自己按到别处去了就不用管。
     */
    private void restoreFocus() {
        if (restoring || keepPosition < 0 || hasFocus()) {
            return;
        }
        View child = getChildAt(keepPosition - getFirstVisiblePosition());
        if (child == null || !child.isShown()) {
            // 目标那张卡这一帧还没挂上来（列表正在重排）：下一帧再试。
            // 直接放弃的话，焦点就一直停在左上角那颗模块胶囊上了 ——
            // 用户看到的就是「翻着翻着跳一下左上角」。
            if (!retryScheduled) {
                retryScheduled = true;
                post(new Runnable() {
                    @Override
                    public void run() {
                        retryScheduled = false;
                        if (!hasFocus()) {
                            restoreFocus();
                        }
                    }
                });
            }
            return;
        }
        restoring = true;
        try {
            child.requestFocus();
        } finally {
            restoring = false;
        }
    }

    /** 补焦重试已经排上了，别重复排 */
    private boolean retryScheduled;

    /**
     * 告诉墙：用户是自己按了键把焦点带走的。
     *
     * <p>页面在处理方向键、决定把焦点送去页签或别的控件时调它。
     * 不调的话，墙重排时会把焦点抢回列表里 —— 用户明明按了「上」想去页签，
     * 又被拽回列表，这一层交互就乱了。
     */
    public void markUserLeft() {
        userLeftWall = true;
        keepPosition = -1;
    }

    /** 用户重新走进墙里（切回这一页、点进内容），把标记撤掉 */
    public void markUserEntered() {
        userLeftWall = false;
    }
}

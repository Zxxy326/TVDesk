package com.tvdesk.desk.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import com.tvdesk.desk.motion.FocusNav;
import com.tvdesk.desk.motion.SpringValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 不回收的磁贴墙（应用页试点）。
 *
 * <h3>为什么不用 GridView</h3>
 * {@code GridView} 只挂载视口附近的子 View，滚出去就回收。带焦点的那张卡一被回收，
 * 焦点就没主了 —— 系统按「窗口里第一个可聚焦控件」补，也就是左上角那颗模块胶囊，
 * 用户看到的是「翻着翻着跳一下左上角，再自己下来」。以前靠 {@code onLayout} 里抢先补焦、
 * 32ms 重试、{@code markUserLeft} 这一串补丁压着，翻页快一点还是会露馅。
 *
 * <p>Aerial 那边没有这个问题，不是因为它的焦点代码写得巧，而是它的磁贴<b>根本不回收</b>：
 * 每个磁贴按绝对位置摆着，滚动只是整体位移。应用也就几十个，全量挂上完全负担得起。
 * 这个类照那个思路做，顺带把焦点也交给 {@link FocusNav} 显式算 —— 于是
 * 「到边」「翻页落点」「重建后回到原处」都不用再猜。
 *
 * <h3>布局</h3>
 * 列宽 = (内容宽 - 横向间距) / 列数；行高取子 View 量出来的最大高度（16:9 磁贴 + 名字区）。
 * 滚动用 {@code scrollTo}，由弹簧驱动；翻页只是把目标换成「±N 行」。
 */
public class TileWall extends ViewGroup implements FocusNav.Host {

    public interface Adapter {
        int getCount();

        /** 第 index 张卡；{@code convert} 是要复用的旧视图（可能为 null） */
        View getView(int index, View convertView, ViewGroup parent);

        /** 稳定标识（应用页用包名）：重建后按它把焦点找回来 */
        String keyAt(int index);
    }

    /** 一行走完了，页面要把焦点交给上面的顶栏 */
    public interface OnEdgeListener {
        /** @return true 表示页面接管了（例如往上交给页签）；false 表示原地不动 */
        boolean onEdge(int keyCode, int index);
    }

    /**
     * 滚动位置变化（按行报）。
     *
     * <p>分页加载用它：B 站的关注流一页 20 条，滚到离底部两行时去要下一页。
     * GridView 时代靠 {@code AbsListView.OnScrollListener}，这里墙自己算。
     */
    public interface ScrollListener {
        void onScroll(int firstRow, int lastRow, int totalRows);
    }

    private static final int NUDGE_DP = 9;

    private Adapter adapter;
    private OnEdgeListener edgeListener;
    private ScrollListener scrollListener;

    private int columns = 5;
    private int hSpace;
    private int vSpace;

    private int colWidth;
    private int rowHeight;

    private final FocusNav nav = new FocusNav(this);
    private final Map<String, View> viewByKey = new HashMap<>();
    private final List<String> keys = new ArrayList<>();

    private final SpringValue scrollSpring = new SpringValue(SpringValue.STIFF_SCROLL);
    private final SpringValue nudgeSpring = new SpringValue(SpringValue.STIFF_FOCUS);
    private final SpringValue nudgeY = new SpringValue(SpringValue.STIFF_FOCUS);
    private boolean animating;

    public TileWall(Context context) {
        super(context);
        init();
    }

    public TileWall(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        // 焦点一律由 FocusNav 算，系统不要自己按几何关系猜
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        float d = getResources().getDisplayMetrics().density;
        hSpace = Math.round(28 * d);
        vSpace = Math.round(18 * d);
        nudgeSpring.snapTo(0f);
        nudgeY.snapTo(0f);
        scrollSpring.snapTo(0f);
    }

    // ---------------------------------------------------------------- 配置

    public void setAdapter(Adapter adapter) {
        this.adapter = adapter;
        rebuild();
    }

    public Adapter getAdapter() {
        return adapter;
    }

    public void setNumColumns(int columns) {
        this.columns = Math.max(1, columns);
        requestLayout();
    }

    public int getNumColumns() {
        return columns;
    }

    /** 横/纵间距（dp）。各页的排版参数沿用原来 GridView 里那份，不另起一套 */
    public void setSpacingDp(float horizontalDp, float verticalDp) {
        float d = getResources().getDisplayMetrics().density;
        hSpace = Math.round(horizontalDp * d);
        vSpace = Math.round(verticalDp * d);
        requestLayout();
    }

    public void setEdgeListener(OnEdgeListener listener) {
        edgeListener = listener;
    }

    public void setScrollListener(ScrollListener listener) {
        scrollListener = listener;
    }

    /** 上次报给页面的行号，避免每帧都回调 */
    private int lastReportedRow = -1;

    private void reportScroll() {
        if (scrollListener == null || rowHeight <= 0 || columns <= 0) {
            return;
        }
        int totalRows = (int) Math.ceil(getChildCount() / (float) columns);
        int firstRow = getScrollY() / (rowHeight + vSpace);
        int lastRow = (getScrollY() + Math.max(0, getHeight() - getPaddingTop())) / (rowHeight + vSpace);
        lastRow = Math.min(lastRow, Math.max(0, totalRows - 1));
        if (firstRow == lastReportedRow && totalRows > 0) {
            return;
        }
        lastReportedRow = firstRow;
        scrollListener.onScroll(firstRow, lastRow, totalRows);
    }

    public int getCount() {
        return adapter == null ? 0 : adapter.getCount();
    }

    /** 全量挂载，所以「第一个可见位置」永远是 0 —— 与 GridView 的同名方法对齐，方便老代码过渡 */
    public int getFirstVisiblePosition() {
        return 0;
    }

    public int getPositionForView(View view) {
        View node = view;
        while (node != null) {
            if (node.getParent() == this) {
                return indexOfChild(node);
            }
            node = node.getParent() instanceof View ? (View) node.getParent() : null;
        }
        return -1;
    }

    // ---------------------------------------------------------------- 重建

    /** 数据变了：复用同 key 的旧视图，顺序对齐后把焦点按 key 找回来 */
    public void notifyDataSetChanged() {
        rebuild();
    }

    private void rebuild() {
        if (adapter == null) {
            removeAllViews();
            keys.clear();
            viewByKey.clear();
            return;
        }
        // 重排前记下「现在焦点在哪张卡」：换栏目/换排序之后要回到同一张
        final String wasFocused = nav.currentKey();

        int count = adapter.getCount();
        List<View> desired = new ArrayList<>(count);
        List<String> desiredKeys = new ArrayList<>(count);
        Map<String, View> next = new HashMap<>();

        for (int i = 0; i < count; i++) {
            String key = adapter.keyAt(i);
            View reuse = key == null ? null : viewByKey.get(key);
            View view = adapter.getView(i, reuse, this);
            desired.add(view);
            desiredKeys.add(key);
            if (key != null) {
                next.put(key, view);
            }
        }

        // 顺序对齐：只有内容真的换了位置才 detach/attach（应用排序、切栏目时才会发生）
        boolean sameOrder = desired.size() == getChildCount();
        if (sameOrder) {
            for (int i = 0; i < desired.size(); i++) {
                if (getChildAt(i) != desired.get(i)) {
                    sameOrder = false;
                    break;
                }
            }
        }
        if (!sameOrder) {
            removeAllViewsInLayout();
            for (int i = 0; i < desired.size(); i++) {
                View view = desired.get(i);
                if (view.getParent() != null) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                addViewInLayout(view, i, new LayoutParams(LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT));
            }
            requestLayout();
        }

        viewByKey.clear();
        viewByKey.putAll(next);
        keys.clear();
        keys.addAll(desiredKeys);
        nav.reset();

        // 重建把子视图 detach/attach 过一遍，焦点会掉；按 key 立刻补回来。
        // （以前这一步靠页面在 32ms 后重试，中间那两帧焦点已经闪到左上角胶囊上了。）
        if (wasFocused != null) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (findFocus() == null) {
                        nav.focusKey(wasFocused);
                    }
                    // 焦点就算还在卡上，滚动位置也可能是上一屏留下的（比如从底部移完卡），
                    // 这里按当前落点再对齐一次，别让第一行压到顶栏的压暗罩底下。
                    int current = nav.currentIndex();
                    if (current >= 0 && current < getCount()) {
                        ensureRowVisible(current);
                    }
                }
            });
        }
    }

    // ---------------------------------------------------------------- 测量与布局

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        colWidth = Math.max(1, (available - hSpace * (columns - 1)) / columns);

        int count = getChildCount();
        int childSpecW = MeasureSpec.makeMeasureSpec(colWidth, MeasureSpec.EXACTLY);
        int maxRow = 0;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            child.measure(childSpecW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            maxRow = Math.max(maxRow, child.getMeasuredHeight());
        }
        rowHeight = maxRow;
        setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int count = getChildCount();
        int pitch = rowHeight + vSpace;
        int x0 = getPaddingLeft();
        int y0 = getPaddingTop();
        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int col = i % columns;
            int left = x0 + col * (colWidth + hSpace);
            int top = y0 + row * pitch;
            getChildAt(i).layout(left, top, left + colWidth, top + rowHeight);
        }
        // 滚动范围可能变了（换栏目后条目变少）：把目标夹回去，别停在空白上
        clampScrollTarget();
        reportScroll();
    }

    /**
     * 内容总高。
     *
     * <p>取最后一张卡的 bottom 而不是「行数 × 行高」：最后一行未必排满，
     * 而且卡片高度会随名字行/字号变，用行数算出来的数比实际少，滚到底会停早。
     */
    private int contentHeight() {
        int count = getChildCount();
        if (count == 0) {
            return 0;
        }
        View last = getChildAt(count - 1);
        return last.getBottom() + getPaddingBottom();
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - getHeight());
    }

    private void clampScrollTarget() {
        float target = Math.max(0f, Math.min(scrollSpring.target(), maxScroll()));
        scrollSpring.setTarget(target);
        if (scrollSpring.isAtRest()) {
            scrollTo(0, Math.round(target));
        } else {
            startAnimating();
        }
    }

    // ---------------------------------------------------------------- 焦点

    @Override
    public int count() {
        return getCount();
    }

    @Override
    public int columns() {
        return columns;
    }

    @Override
    public View viewAt(int index) {
        return index >= 0 && index < getChildCount() ? getChildAt(index) : null;
    }

    @Override
    public String keyAt(int index) {
        return index >= 0 && index < keys.size() ? keys.get(index) : null;
    }

    @Override
    public void onFocusMoved(int from, int to) {
        ensureRowVisible(to);
    }

    @Override
    public int onEdge(int keyCode, int index) {
        if (edgeListener != null && edgeListener.onEdge(keyCode, index)) {
            return FocusNav.EDGE_HANDLED;
        }
        // 左右到头 / 上下到头：给一次轻微的回弹，让用户知道「到底了」而不是按键没响应
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            nudge(-1f);
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            nudge(1f);
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            nudgeVertical(1f);
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            nudgeVertical(-1f);
        }
        return FocusNav.EDGE_SWALLOW;
    }

    /** 把焦点放到第 index 张卡上（必要时先滚过去） */
    public boolean focusPosition(int index) {
        if (index < 0 || index >= getCount()) {
            return false;
        }
        ensureRowVisible(index);
        return nav.focusIndex(index);
    }

    /** 按 key 找回焦点（重建、从别处回来时用） */
    public boolean focusKey(String key) {
        Integer index = indexOfKey(key);
        if (index == null) {
            return false;
        }
        return focusPosition(index);
    }

    public String focusedKey() {
        return nav.currentKey();
    }

    public int focusedPosition() {
        return nav.currentIndex();
    }

    private Integer indexOfKey(String key) {
        if (key == null) {
            return null;
        }
        for (int i = 0; i < keys.size(); i++) {
            if (key.equals(keys.get(i))) {
                return i;
            }
        }
        return null;
    }

    /**
     * 让焦点那一行停在视口顶部（照 Aerial 的 {@code scrollFor(row)}）。
     *
     * <p>以前写的是「最小滚动」——只在行被切掉时才滚，结果按一下下去，上一行露半截、
     * 屏幕上下都不整齐。改成整行对齐之后，每次换行都是完整的一排在动，
     * 和翻一页是同一个观感（差别只在滚多少）。
     */
    private void ensureRowVisible(int index) {
        if (rowHeight <= 0 || getHeight() <= 0) {
            return;
        }
        int row = index / columns;
        // 墙自身的 paddingTop 已经挪到布局的 margin 上（见 activity_apps.xml）：
        // 这样「第 row 行贴顶」就是 scrollY = row × 行距，不会在顶部露出上一行的半截。
        int top = row * (rowHeight + vSpace);
        float target = Math.max(0f, Math.min(top, maxScroll()));
        if (Math.abs(target - scrollSpring.target()) > 0.5f) {
            scrollSpring.setTarget(target);
            startAnimating();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                int result = nav.handleKeyDown(key);
                if (result == FocusNav.PASS) {
                    // 到边且页面要接管（例如向上交给页签）：放行，让页面的 dispatchKeyEvent 接住
                    return false;
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------- 翻页

    /** 回到顶部（换栏目、刷新列表后用） */
    public void scrollToTop() {
        scrollSpring.snapTo(getScrollY());
        scrollSpring.setTarget(0f);
        startAnimating();
    }

    /** 平滑翻 {@code rows} 行（负值向上）。以当前实际位置为基准，和「自动跟焦」互不叠加 */
    public void pageBy(int rows) {
        if (rowHeight <= 0) {
            return;
        }
        float target = getScrollY() + rows * (rowHeight + vSpace);
        scrollSpring.snapTo(getScrollY());
        scrollSpring.setTarget(Math.max(0f, Math.min(target, maxScroll())));
        startAnimating();
    }

    /** 翻一页时焦点跟着走：落在下一页同一列的位置 */
    public boolean pageByAndFocus(int rows) {
        int current = nav.currentIndex();
        if (current < 0) {
            current = 0;
        }
        int step = rows * columns;
        int next = Math.max(0, Math.min(getCount() - 1, current + step));
        if (next == current) {
            nudgeVertical(rows > 0 ? 1f : -1f);
            return false;
        }
        boolean ok = nav.focusIndex(next);
        pageBy(rows);
        return ok;
    }

    // ---------------------------------------------------------------- 动画

    private void nudge(float direction) {
        float distance = NUDGE_DP * getResources().getDisplayMetrics().density;
        nudgeSpring.setTarget(direction * distance);
        startAnimating();
    }

    private void nudgeVertical(float direction) {
        float distance = NUDGE_DP * getResources().getDisplayMetrics().density;
        nudgeY.setTarget(direction * distance);
        startAnimating();
    }

    private long lastFrameNanos;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            float dt = lastFrameNanos == 0 ? 0.016f : (now - lastFrameNanos) / 1_000_000_000f;
            lastFrameNanos = now;

            boolean scrollAlive = scrollSpring.advance(dt);
            if (getScrollY() != Math.round(scrollSpring.value())) {
                scrollTo(0, Math.round(scrollSpring.value()));
                reportScroll();
            }
            boolean nudgeAlive = nudgeSpring.advance(dt);
            boolean nudgeYAlive = nudgeY.advance(dt);
            if (nudgeAlive || nudgeYAlive
                    || Math.abs(nudgeSpring.value()) > 0.01f
                    || Math.abs(nudgeY.value()) > 0.01f) {
                invalidate();
            }
            if (scrollAlive || nudgeAlive || nudgeYAlive) {
                postOnAnimation(this);
            } else {
                animating = false;
                lastFrameNanos = 0;
                nudgeSpring.snapTo(0f);
                nudgeY.snapTo(0f);
                invalidate();
            }
        }
    };

    private void startAnimating() {
        if (animating) {
            return;
        }
        animating = true;
        lastFrameNanos = 0;
        postOnAnimation(frame);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        float nudgeX = nudgeSpring.value();
        float nudgeOffY = nudgeY.value();
        if (Math.abs(nudgeX) > 0.01f || Math.abs(nudgeOffY) > 0.01f) {
            canvas.save();
            canvas.translate(nudgeX, nudgeOffY);
            super.dispatchDraw(canvas);
            canvas.restore();
        } else {
            super.dispatchDraw(canvas);
        }
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        // 卡片一挂上就先量一遍，行高要等测量才有
        requestLayout();
    }

    /**
     * 把「从自己到页面根」这条链上的裁剪全打开。
     *
     * <p>磁贴聚焦放大 1.15 倍，左右各溢出约 7% 列宽、上下各溢出十几个像素，
     * 默认的 {@code clipChildren} 会把白环和放大的边切平 —— 看着就是「焦点框缺一角」。
     * 以前靠 {@code FocusScaler.attach} 顺手做，现在墙自己接管焦点，这一步也由墙负责。
     * 遇到滚动容器就停（它们的裁剪是正常工作的一部分）。
     */
    private void unclipAncestors() {
        android.view.ViewParent parent = getParent();
        int guard = 0;
        while (parent instanceof ViewGroup && guard++ < 12) {
            ViewGroup group = (ViewGroup) parent;
            if (group instanceof android.widget.HorizontalScrollView
                    || group instanceof android.widget.ScrollView
                    || group instanceof android.widget.AbsListView
                    || group instanceof androidx.recyclerview.widget.RecyclerView) {
                return;
            }
            group.setClipChildren(false);
            group.setClipToPadding(false);
            parent = group.getParent();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        unclipAncestors();
    }
}

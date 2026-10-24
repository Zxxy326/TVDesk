package com.tvdesk.desk;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.LruCache;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.util.ImgUtil;
import com.tvdesk.core.feed.HomeFeed;
import com.tvdesk.core.feed.SourceFeed;
import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.motion.RevealAnim;
import com.tvdesk.desk.widget.GridScroll;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 桌面内嵌海报墙。
 *
 * <p>以前「影视」是一个独立的模块页，有自己的标题、类目栏和搜索入口；桌面顶栏
 * 已经有一模一样的一套类目，点进去等于把同一件事再做一遍。现在类目直接接管桌面
 * 内容区：顶栏选什么类目，这里就铺什么，窗口、顶栏、背景都没换过。
 *
 * <p>{@link #show(MovieSort.SortData)} 传 {@code null} 时铺首页豆瓣榜单，
 * 对应桌面「今日热门电视剧」那一行的「查看全部」。
 *
 * <h3>焦点</h3>
 * GridView 只挂载视口附近的卡片，下一排往往还没生成；这时按 DOWN，系统找不到目标
 * 会把焦点甩给页面上别的控件。所以上下走位在这里自己按位置算，UP 到第一排时交还
 * 顶栏（{@link Host#onNavigateUp()}）。
 */
public class CategoryWall extends FrameLayout {

    public interface Host {
        void openVideo(Movie.Video vod);

        /** 焦点从第一排再往上，交还当前类目所在的顶栏项 */
        void onNavigateUp();

        /** 长按某张海报。收藏那一墙靠它移掉条目；返回 true 表示这一下已经处理完 */
        boolean onItemLongPress(Movie.Video vod);
    }

    private static final int PREFETCH_DISTANCE = 6;

    /** 首屏骨架的数量。铺满两排即可，不用跟着真实条数走 */
    private static final int SKELETON_COUNT = 12;

    /**
     * 首屏缓存。换回看过的类目时直接铺，不再等源站——
     * 「顶栏切一下就转圈」的根源是每次都重新拉第一页。
     */
    private static final int CACHE_SIZE = 16;

    /**
     * 数据没回来之前先不动画面；超过这个时间才给出反馈。
     *
     * <p>源快的时候（命中缓存、本地源）几十毫秒就回来了，如果立刻铺骨架，
     * 用户看到的是「闪一下」；延迟到 160ms 后再决定要不要提示。
     */
    private static final long SLOW_LOADING_HINT_MS = 160L;

    private final List<Movie.Video> items = new ArrayList<>();
    private final Set<String> seenKeys = new HashSet<>();
    private final PosterAdapter adapter = new PosterAdapter();
    private final LruCache<String, List<Movie.Video>> firstPageCache = new LruCache<>(CACHE_SIZE);

    private GridView grid;
    private TextView emptyView;
    /** 本地清单（收藏）为空时没有「重试」这回事，别给空态挂上可聚焦的重试提示 */
    private boolean emptyRetryable = true;
    private TextView footer;

    private Host host;
    /** null 表示首页豆瓣榜单 */
    private MovieSort.SortData sort;
    private int contentSeq;
    private int loadedPage;
    private boolean loadingMore;
    private boolean endReached;
    private int lastFocusPos;

    /** 第一页还没回来。期间墙里的旧内容不可点、不可聚焦进入 */
    private boolean loadingFirstPage;
    /** 墙里没有旧内容可留时，用骨架撑住画面 */
    private boolean skeletonMode;

    /** 首屏数据到达后的入场窗口：窗口内新绑定的卡片才做错峰浮入 */
    private boolean revealPending;
    private final SparseBooleanArray revealDone = new SparseBooleanArray();
    private final Runnable endReveal = new Runnable() {
        @Override
        public void run() {
            revealPending = false;
        }
    };

    /**
     * 首屏慢了才出现的加载反馈。
     *
     * <p>不弹转圈：有旧内容就把旧内容压暗，等新数据回来直接换；
     * 墙里还是空的才铺骨架。两种情况都不会出现一块独立盖在页面上的加载层。
     */
    private final Runnable slowLoadingHint = new Runnable() {
        @Override
        public void run() {
            if (!loadingFirstPage || gone()) {
                return;
            }
            if (!items.isEmpty()) {
                grid.setAlpha(0.45f);
                grid.setVisibility(View.VISIBLE);
                return;
            }
            skeletonMode = true;
            grid.setAlpha(1f);
            grid.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
        }
    };

    public CategoryWall(Context context) {
        this(context, null);
    }

    public CategoryWall(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.view_category_wall, this, true);

        grid = findViewById(R.id.wall_grid);
        emptyView = findViewById(R.id.wall_empty);
        footer = findViewById(R.id.wall_footer);

        grid.setAdapter(adapter);
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                                 int totalItemCount) {
                if (totalItemCount > 0
                        && firstVisibleItem + visibleItemCount >= totalItemCount - 4) {
                    loadMore();
                }
            }
        });
        footer.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!loadingMore && !endReached) {
                    loadMore();
                }
            }
        });
        emptyView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (emptyRetryable) {
                    loadFirstPage();
                }
            }
        });
    }

    public void setHost(Host host) {
        this.host = host;
    }

    /** 换内容。sort 为 null 铺首页榜单；看过的类目直接走首屏缓存 */
    public void show(MovieSort.SortData sort) {
        this.sort = sort;
        emptyRetryable = true;
        loadFirstPage();
    }

    /**
     * 铺一份本地清单（收藏这种不用问源站的列表）。
     *
     * <p>不走 {@link #show}：那条路最终要落到首页榜单或源站的类目接口，而这里的数据
     * 已经在内存里。sort 顺手置空——本地清单没有下一页，预取必须关掉。
     *
     * @param hint 底部常驻提示。本地清单没有「下一页」，那条状态位正好空着，
     *             用它讲清楚怎么把条目移掉
     */
    public void showList(List<Movie.Video> list, String emptyText, String hint) {
        this.sort = null;
        contentSeq++;
        removeCallbacks(endReveal);
        grid.removeCallbacks(slowLoadingHint);
        loadingFirstPage = false;
        skeletonMode = false;
        loadingMore = false;
        endReached = true;
        loadedPage = 1;
        lastFocusPos = 0;
        hideFooter();
        emptyRetryable = false;
        emptyView.setVisibility(View.GONE);
        applyItems(list, emptyText, true);
        if (!items.isEmpty() && hint != null) {
            showFooterHint(hint);
        }
    }

    public boolean hasContent() {
        return !items.isEmpty() && !loadingFirstPage;
    }

    /** 换源后旧列表全部作废，连缓存一起清 */
    public void clearCache() {
        firstPageCache.evictAll();
        clearContent();
    }

    /** 回首页时把墙清空，下次进来不会先闪一眼上个类目的海报 */
    public void clearContent() {
        contentSeq++;
        removeCallbacks(endReveal);
        grid.removeCallbacks(slowLoadingHint);
        items.clear();
        seenKeys.clear();
        revealPending = false;
        revealDone.clear();
        loadingFirstPage = false;
        skeletonMode = false;
        loadingMore = false;
        endReached = false;
        loadedPage = 0;
        lastFocusPos = 0;
        adapter.notifyDataSetChanged();
        hideFooter();
        emptyView.setVisibility(View.GONE);
        grid.animate().cancel();
        grid.setAlpha(1f);
        grid.setVisibility(View.INVISIBLE);
    }

    // ---------------------------------------------------------------- 数据

    private void loadFirstPage() {
        final int mySeq = ++contentSeq;
        removeCallbacks(endReveal);
        grid.removeCallbacks(slowLoadingHint);
        revealPending = false;
        revealDone.clear();
        loadedPage = 0;
        loadingMore = false;
        endReached = false;
        lastFocusPos = 0;
        hideFooter();
        emptyView.setVisibility(View.GONE);

        final String key = cacheKey(sort);
        List<Movie.Video> cached = firstPageCache.get(key);
        if (cached != null && !cached.isEmpty()) {
            // 看过的类目不再问源站：直接铺，切换看起来就是即时的
            loadingFirstPage = false;
            skeletonMode = false;
            applyItems(cached, null, false);
            return;
        }

        loadingFirstPage = true;
        skeletonMode = false;
        // 有上一屏就先留着（不打空），没有旧内容时等 160ms 再决定铺不铺骨架
        grid.setAlpha(1f);
        grid.setVisibility(items.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        grid.postDelayed(slowLoadingHint, SLOW_LOADING_HINT_MS);

        if (sort == null) {
            HomeFeed.load(getContext(), new HomeFeed.Callback() {
                @Override
                public void onResult(List<Movie.Video> list, boolean fromCache) {
                    if (mySeq != contentSeq || gone()) {
                        return;
                    }
                    loadedPage = 1;
                    endReached = true;
                    applyItems(list, "首页加载失败了。检查网络，或换个类目试试。", true);
                    cacheFirstPage(key);
                }

                @Override
                public void onError(Throwable th) {
                    if (mySeq != contentSeq || gone()) {
                        return;
                    }
                    showEmpty("首页加载失败了。检查网络，或换个类目试试。");
                }
            });
            return;
        }

        SourceFeed.loadList(sort, 1, new SourceFeed.ListCallback() {
            @Override
            public void onList(List<Movie.Video> videos) {
                if (mySeq != contentSeq || gone()) {
                    return;
                }
                loadedPage = 1;
                applyItems(videos, "这个类目下暂时没取到内容。换个类目试试。", true);
                cacheFirstPage(key);
            }

            @Override
            public void onError(Throwable th) {
                if (mySeq != contentSeq || gone()) {
                    return;
                }
                showEmpty("这个类目加载失败了。源站可能不稳定，稍后再试。");
            }
        });
    }

    private String cacheKey(MovieSort.SortData sort) {
        return sort == null ? "home" : sort.id;
    }

    private void cacheFirstPage(String key) {
        if (!items.isEmpty()) {
            firstPageCache.put(key, new ArrayList<>(items));
        }
    }

    private void applyItems(List<Movie.Video> list, String emptyText, boolean animate) {
        loadingFirstPage = false;
        skeletonMode = false;
        grid.removeCallbacks(slowLoadingHint);
        items.clear();
        seenKeys.clear();
        if (list != null) {
            for (Movie.Video video : list) {
                appendUnique(video);
            }
        }
        if (items.isEmpty()) {
            showEmpty(emptyText);
            return;
        }
        grid.animate().cancel();
        grid.setAlpha(1f);
        emptyView.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        grid.setSelection(0);
        revealPending = animate;
        revealDone.clear();
        removeCallbacks(endReveal);
        if (animate) {
            postDelayed(endReveal, 900L);
        }
        adapter.notifyDataSetChanged();
    }

    /**
     * 预取下一页。焦点走到倒数第 {@link #PREFETCH_DISTANCE} 张、滚到接近底部都会触发。
     */
    private void loadMore() {
        if (sort == null || loadingFirstPage || loadingMore || endReached
                || gone() || items.isEmpty()) {
            return;
        }
        loadingMore = true;
        final int mySeq = contentSeq;
        final int page = loadedPage + 1;
        showFooterLoading();
        SourceFeed.loadList(sort, page, new SourceFeed.ListCallback() {
            @Override
            public void onList(List<Movie.Video> videos) {
                if (mySeq != contentSeq || gone()) {
                    return;
                }
                loadingMore = false;
                int before = items.size();
                if (videos != null) {
                    for (Movie.Video video : videos) {
                        appendUnique(video);
                    }
                }
                if (items.size() == before) {
                    // 空页，或者源把最后一页又吐了一遍（很常见）：都算到底
                    endReached = true;
                    showFooterEnd();
                    return;
                }
                loadedPage = page;
                // 翻页是追加，不是换页：别再触发一轮入场动画
                revealPending = false;
                removeCallbacks(endReveal);
                View focusedBefore = findFocus();
                boolean posterFocused = focusedBefore != null
                        && grid.getPositionForView(focusedBefore) >= 0;
                adapter.notifyDataSetChanged();
                // 追加数据会重排。原本站在海报上就拉回原处；用户自己去了顶栏就别抢
                if (posterFocused) {
                    grid.post(new Runnable() {
                        @Override
                        public void run() {
                            View now = findFocus();
                            if (now == null || grid.getPositionForView(now) < 0) {
                                focusAttempt(Math.max(0,
                                        Math.min(lastFocusPos, items.size() - 1)), 1);
                            }
                        }
                    });
                }
                hideFooter();
            }

            @Override
            public void onError(Throwable th) {
                if (mySeq != contentSeq || gone()) {
                    return;
                }
                loadingMore = false;
                showFooterRetry();
            }
        });
    }

    /** 源分页常有重叠，不去重会看到同一张海报出现两次 */
    private void appendUnique(Movie.Video video) {
        if (video == null || video.name == null) {
            return;
        }
        String key = (video.id == null || video.id.isEmpty())
                ? "name:" + video.name : "id:" + video.id;
        if (!seenKeys.add(key)) {
            return;
        }
        items.add(video);
    }

    private boolean gone() {
        Context context = getContext();
        return !(context instanceof Activity)
                || ((Activity) context).isFinishing()
                || ((Activity) context).isDestroyed();
    }

    // ---------------------------------------------------------------- 状态

    /**
     * 空态。上一屏的旧内容必须在这里清掉——类目已经换了，
     * 留着旧海报会让人以为这就是新类目的内容。
     */
    private void showEmpty(String text) {
        loadingFirstPage = false;
        skeletonMode = false;
        grid.removeCallbacks(slowLoadingHint);
        items.clear();
        seenKeys.clear();
        adapter.notifyDataSetChanged();
        grid.animate().cancel();
        grid.setAlpha(1f);
        grid.setVisibility(View.INVISIBLE);
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setFocusable(emptyRetryable);
        emptyView.setClickable(emptyRetryable);
        emptyView.setText(emptyRetryable
                ? (text == null ? "没取到内容" : text) + "\n按「确定」重试"
                : (text == null ? "这里还是空的" : text));
    }

    private final Runnable hideFooterTask = new Runnable() {
        @Override
        public void run() {
            footer.setVisibility(View.GONE);
        }
    };

    private void showFooterLoading() {
        footer.removeCallbacks(hideFooterTask);
        footer.setText("正在加载下一页…");
        footer.setFocusable(false);
        footer.setClickable(false);
        footer.setVisibility(View.VISIBLE);
    }

    private void showFooterEnd() {
        footer.removeCallbacks(hideFooterTask);
        footer.setText("已经到底了");
        footer.setFocusable(false);
        footer.setClickable(false);
        footer.setVisibility(View.VISIBLE);
        footer.postDelayed(hideFooterTask, 1800L);
    }

    private void showFooterRetry() {
        footer.removeCallbacks(hideFooterTask);
        footer.setText("下一页加载失败，按确定重试");
        footer.setFocusable(true);
        footer.setFocusableInTouchMode(true);
        footer.setClickable(true);
        footer.setVisibility(View.VISIBLE);
    }

    /** 常驻提示。收藏墙用它说「长按能移掉」——没有这一步这墙就成了只进不出的地方 */
    private void showFooterHint(String text) {
        footer.removeCallbacks(hideFooterTask);
        footer.setText(text);
        footer.setFocusable(false);
        footer.setClickable(false);
        footer.setVisibility(View.VISIBLE);
    }

    private void hideFooter() {
        footer.removeCallbacks(hideFooterTask);
        footer.setVisibility(View.GONE);
        footer.setFocusable(false);
        footer.setClickable(false);
    }

    // ---------------------------------------------------------------- 焦点

    /**
     * 顶栏按 DOWN 进来时，回到上次停留的位置（首次就是第一张）。
     * 内容没拉到则把焦点交给「重试」，不让用户卡在顶栏上没出路。
     */
    public boolean focusLast() {
        if (gone()) {
            return false;
        }
        if (loadingFirstPage) {
            // 旧内容还没换掉，先别把焦点放进去。键吃掉，焦点留在顶栏，
            // 加载完再按一次 DOWN 就能进墙
            return true;
        }
        if (items.isEmpty()) {
            // 不可重试的空态没有焦点可给，返回 false 让调用方把按键交还给上层
            return emptyView.getVisibility() == View.VISIBLE && emptyView.requestFocus();
        }
        focusAttempt(Math.max(0, Math.min(lastFocusPos, items.size() - 1)), 2, true);
        return true;
    }

    private void focusPos(int target) {
        if (gone() || items.isEmpty()) {
            return;
        }
        lastFocusPos = target;
        focusAttempt(target, 2, true);
    }

    /** 内容刚回来时子项可能还没铺出来，直接 requestFocus 会静默失败，补一拍再试 */
    private void focusAttempt(final int target, final int retries) {
        focusAttempt(target, retries, false);
    }

    /**
     * @param force 用户明确按了方向键/确定：当前焦点在顶栏也要抢过来；
     *              数据回来时的自动补焦不能抢，否则用户刚移到顶栏就会被拽回墙里
     */
    private void focusAttempt(final int target, final int retries, final boolean force) {
        if (gone() || items.isEmpty() || (!force && !canTakeFocus())) {
            return;
        }
        View child = grid.getChildAt(target - grid.getFirstVisiblePosition());
        if (!GridScroll.isFullyVisible(grid, child)) {
            // 目标行没挂载或被视口裁掉：平滑翻一行过去，别用 setSelection 瞬移整屏
            long wait = GridScroll.smoothToRow(grid, target);
            retryFocus(target, retries, force, wait);
            return;
        }
        boolean ok = child.hasFocus() || child.requestFocus();
        if (!ok) {
            retryFocus(target, retries, force, 80);
        }
    }

    private void retryFocus(final int target, final int retries, final boolean force, long delay) {
        if (retries <= 0) {
            return;
        }
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                focusAttempt(target, retries - 1, force);
            }
        }, delay);
    }

    private boolean canTakeFocus() {
        if (!hasWindowFocus()) {
            return false;
        }
        Context context = getContext();
        if (!(context instanceof Activity)) {
            return true;
        }
        View focused = ((Activity) context).getCurrentFocus();
        return focused == null || focused == this || isDescendant(focused);
    }

    private boolean isDescendant(View view) {
        while (view != null) {
            if (view == this) {
                return true;
            }
            android.view.ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (moveVertical(keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 上下走位自己算，必须在系统寻焦之前接管：等 ViewRootImpl 找不到下一排时，
     * 它会把焦点交给别的控件。
     */
    private boolean moveVertical(boolean down) {
        View focused = findFocus();
        if (loadingFirstPage) {
            // 还在换数据，别让焦点在旧卡片之间乱跑
            return focused != null;
        }
        if (focused == null) {
            if (down && !items.isEmpty()) {
                focusPos(Math.max(0, Math.min(lastFocusPos, items.size() - 1)));
                return true;
            }
            return false;
        }
        if (focused == grid) {
            if (down) {
                focusPos(0);
            } else if (host != null) {
                host.onNavigateUp();
            }
            return true;
        }
        int position = grid.getPositionForView(focused);
        if (position < 0) {
            return false;
        }
        int columns = Math.max(1, grid.getNumColumns());
        if (down) {
            int next = position + columns;
            if (next < items.size()) {
                focusPos(next);
            } else if (!endReached) {
                loadMore();
            }
            // 到底或正在翻页都把键吃掉，别让系统再去找别的焦点
            return true;
        }
        int previous = position - columns;
        if (previous >= 0) {
            focusPos(previous);
            return true;
        }
        if (host != null) {
            host.onNavigateUp();
        }
        return true;
    }

    // ---------------------------------------------------------------- 列表

    private final class PosterAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return skeletonMode ? SKELETON_COUNT : items.size();
        }

        @Override
        public Object getItem(int position) {
            return skeletonMode ? null : items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return skeletonMode ? 1 : 0;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            if (skeletonMode) {
                return convertView != null ? convertView : createSkeleton();
            }
            View root = convertView;
            if (root == null || root.findViewById(R.id.poster_img) == null) {
                // 类型分开回收后一般不会拿到骨架，但旧版本布局可能没有这个 id
                root = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_tv_poster, parent, false);
            }
            Movie.Video video = items.get(position);
            ImageView img = root.findViewById(R.id.poster_img);
            TextView name = root.findViewById(R.id.poster_name);
            TextView note = root.findViewById(R.id.poster_note);

            name.setText(video.name);
            String noteText = video.note != null ? video.note : "";
            if (noteText.isEmpty() && video.type != null) {
                noteText = video.type;
            }
            note.setText(noteText);
            note.setVisibility(noteText.isEmpty() ? View.GONE : View.VISIBLE);
            loadImage(img, video.pic);
            PosterCorners.clip(img,
                    getResources().getDimensionPixelSize(R.dimen.poster_radius) - dp(3));

            // GridView 的 item 默认不可聚焦，遥控器会整片跳过
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            FocusScaler.attach(root, 1.08f,
                    new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus) {
                        return;
                    }
                    lastFocusPos = position;
                    if (position >= items.size() - PREFETCH_DISTANCE) {
                        loadMore();
                    }
                }
            });
            root.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (loadingFirstPage) {
                        return;
                    }
                    if (host != null && position < items.size()) {
                        host.openVideo(items.get(position));
                    }
                }
            });

            root.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (loadingFirstPage || host == null || position >= items.size()) {
                        return false;
                    }
                    return host.onItemLongPress(items.get(position));
                }
            });

            if (revealPending) {
                if (!revealDone.get(position)) {
                    revealDone.put(position, true);
                    RevealAnim.staggerIn(root, position, dp(12));
                }
            } else {
                RevealAnim.reset(root);
            }
            return root;
        }
    }

    /** 骨架卡片：尺寸和真海报一致，不可聚焦，也不挂点击 */
    private View createSkeleton() {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View poster = new View(getContext());
        GradientDrawable posterBg = new GradientDrawable();
        posterBg.setColor(0x14FFFFFF);
        posterBg.setCornerRadius(getResources().getDimensionPixelSize(R.dimen.poster_radius));
        poster.setBackground(posterBg);
        root.addView(poster, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(258)));

        GradientDrawable lineBg = new GradientDrawable();
        lineBg.setColor(0x0DFFFFFF);
        lineBg.setCornerRadius(getResources().getDimensionPixelSize(R.dimen.radius_badge));
        View name = new View(getContext());
        name.setBackground(lineBg);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(dp(72), dp(14));
        nameLp.topMargin = dp(12);
        nameLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(name, nameLp);
        return root;
    }

    /** TVBox 的图址带 @User-Agent=... 后缀，必须过 ImgUtil，否则整串当 URL 会 403 */
    private void loadImage(ImageView target, String url) {
        if (target == null || url == null || url.isEmpty()) {
            return;
        }
        try {
            Object model = ImgUtil.getImageModel(url);
            if (model == null) {
                return;
            }
            Drawable fallback = AppCompatResources.getDrawable(getContext(), R.drawable.bg_poster);
            if (fallback == null) {
                Glide.with(getContext()).load(model).centerCrop().into(target);
            } else {
                Glide.with(getContext()).load(model)
                        .placeholder(fallback)
                        .error(fallback)
                        .centerCrop()
                        .into(target);
            }
        } catch (Throwable th) {
            android.util.Log.w("CategoryWall", "load image failed: " + url, th);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

package com.tvdesk.desk;

import com.tvdesk.core.ui.DeskToast;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.content.res.AppCompatResources;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.util.ImgUtil;
import com.tvdesk.core.feed.DetailFeed;
import com.tvdesk.desk.motion.FocusScaler;

import java.util.ArrayList;
import java.util.List;

/**
 * 内嵌详情页。
 *
 * <p>数据走 {@link DetailFeed}（详情接口 → 线路/选集），播放交给宿主：
 * 宿主把 TVBox 的 PlayFragment 盖成一层覆盖层，仍然在 DeskActivity 里，不换窗口。
 * 所以「桌面 → 影视 → 海报 → 详情 → 播放」全程一个 Activity、一个焦点树。
 *
 * <p>线路切换沿用类目横排的语言：焦点落到哪条线路就切哪条，不用再按确定；
 * 按确定把焦点送进选集。
 */
public class DetailPage extends FrameLayout implements ModulePage {

    private static final String TAG = "DetailPage";

    public interface Host {
        void play(VodInfo info);

        void search(String keyword);

        /**
         * 详情页里收藏状态变了。
         *
         * <p>收藏清单是两份入口共用的一份数据：首页那颗 ♥ 和这一页的按钮。
         * 宿主收到之后要把首页的心形和收藏墙刷一遍，否则用户从详情收完退回收藏页，
         * 看到的还是「还没有收藏」。
         */
        void favoriteChanged();
    }

    private final Host host;
    private Movie.Video seed;
    private VodInfo info;
    private String activeFlag;
    private boolean loading;
    private boolean released;
    private int seq;
    /**
     * 详情一到位就直接起播，不等用户再按一次。
     *
     * <p>首页那颗「开始播放」要的就是这个：从桌面按确认到画面出来，中间那次「选集」
     * 点击是纯等待。消费一次就复位，避免从别的页面切回来时又自动播一遍。
     */
    private boolean autoPlay;

    private ImageView backdrop;
    private ImageView poster;
    private TextView crumb;
    private TextView title;
    private TextView meta;
    private TextView desc;
    private ImageView playButton;
    private TextView favButton;
    private TextView searchButton;
    private TextView retryButton;
    private TextView lineLabel;
    private LinearLayout lines;
    private TextView episodeLabel;
    private GridView episodes;
    /** 选集上方的分段条（1-30 / 31-60 …），集数超过一屏才显示 */
    private android.view.View chunkScroll;
    private android.widget.LinearLayout chunks;
    /** 当前显示的是第几段（第一集的索引），分段翻页用 */
    private int chunkStart;
    /**
     * 用户自己翻过段。
     *
     * <p>置真之后 {@link #renderEpisodes()} 不再拿「正在播的那一集」把段拉回去 ——
     * 否则用户翻到 193-199 想看看后面几集，随便一个刷新（返回详情、封面到齐）
     * 就把网格弹回正在播的那一段，光标还停在原来的格位上，按确定播的是另一集。
     * 用户点了某一集或换了线路时，这个标记会清掉，段重新跟着播放进度走。
     */
    private boolean chunkPinned;
    /** 光标停在选集的第几格（滚动后会丢焦点，用它兜底算下一步） */
    private int episodeCursor = -1;
    private ProgressBar loadingView;
    private TextView errorView;

    private final List<VodInfo.VodSeries> series = new ArrayList<>();
    /** 只装「当前这一段」的集数：一屏 32 格，翻页靠换数据而不是滚动 */
    private final EpisodeAdapter adapter = new EpisodeAdapter();

    public DetailPage(Context context, Movie.Video seed, Host host) {
        super(context);
        this.seed = seed;
        this.host = host;
        LayoutInflater.from(context).inflate(R.layout.page_detail, this, true);

        backdrop = findViewById(R.id.detail_bg);
        poster = findViewById(R.id.detail_poster);
        PosterCorners.clip(poster,
                getResources().getDimensionPixelSize(R.dimen.poster_radius) - dp(3));
        crumb = findViewById(R.id.detail_crumb);
        title = findViewById(R.id.detail_title);
        meta = findViewById(R.id.detail_meta);
        desc = findViewById(R.id.detail_desc);
        playButton = findViewById(R.id.detail_play);
        favButton = findViewById(R.id.detail_fav);
        searchButton = findViewById(R.id.detail_search);
        retryButton = findViewById(R.id.detail_retry);
        lineLabel = findViewById(R.id.detail_line_label);
        lines = findViewById(R.id.detail_lines);
        episodeLabel = findViewById(R.id.detail_episode_label);
        episodes = findViewById(R.id.detail_episodes);
        chunkScroll = findViewById(R.id.detail_chunk_scroll);
        chunks = findViewById(R.id.detail_episode_chunks);
        loadingView = findViewById(R.id.detail_loading);
        errorView = findViewById(R.id.detail_error);

        episodes.setAdapter(adapter);
        // 记住「光标在第几格」：滚动之后原来的子 View 会被回收，
        // findFocus() 变成 null，只剩这条记录还能算出下一步往哪走。
        episodes.getViewTreeObserver().addOnGlobalFocusChangeListener(
                new ViewTreeObserver.OnGlobalFocusChangeListener() {
                    @Override
                    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        if (episodes != null && newFocus != null
                                && episodes.indexOfChild(newFocus) >= 0) {
                            episodeCursor = episodes.getFirstVisiblePosition()
                                    + episodes.indexOfChild(newFocus);
                        }
                    }
                });

        FocusScaler.attach(playButton, 1.06f);
        FocusScaler.attach(favButton, 1.06f);
        FocusScaler.attach(searchButton, 1.06f);
        FocusScaler.attach(retryButton, 1.06f);

        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playCurrent();
            }
        });
        favButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFavorite();
            }
        });
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (DetailPage.this.host != null) {
                    DetailPage.this.host.search(searchKeyword());
                }
            }
        });
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });

        showLoadingState();
    }

    /** 详情加载完直接起播，见 {@link #autoPlay}。必须在 {@code pushPage} 之前调 */
    public void playWhenReady() {
        autoPlay = true;
    }

    // ---------------------------------------------------------------- 收藏

    /** 当前这部片是不是已收藏（只用来决定按钮长什么样） */
    private boolean favoriteLiked;

    /**
     * 收藏 / 取消收藏。
     *
     * <p>存的和首页那颗星是同一份清单（{@link VodFavorites}），所以首页收的这里看得见，
     * 这里收的首页也立刻变亮 —— 两个入口只是同一个动作的两处按钮。
     */
    private void toggleFavorite() {
        Movie.Video vod = favoriteTarget();
        if (vod == null) {
            return;
        }
        favoriteLiked = VodFavorites.toggle(getContext(), vod);
        applyFavoriteState();
        if (host != null) {
            host.favoriteChanged();
        }
        DeskToast.makeText(getContext(),
                favoriteLiked ? "已收藏：" + vod.name : "已取消收藏：" + vod.name,
                Toast.LENGTH_SHORT).show();
    }

    /** 进页面 / 详情到位 / 从别处回来时，把按钮刷成清单里的真实状态 */
    private void refreshFavoriteState() {
        Movie.Video vod = favoriteTarget();
        favoriteLiked = vod != null && VodFavorites.contains(getContext(), vod);
        applyFavoriteState();
    }

    private void applyFavoriteState() {
        if (favButton == null) {
            return;
        }
        favButton.setText(favoriteLiked ? "★ 已收藏" : "收藏");
        favButton.setTextColor(favoriteLiked ? 0xFFFFD166 : 0xFFF2F5FA);
        favButton.setContentDescription(favoriteLiked ? "取消收藏" : "收藏");
    }

    /**
     * 收藏要存的那条数据。
     *
     * <p>优先用详情里的那份：它带着真正的 {@code sourceKey} 和 {@code id}，
     * 收藏页点进去能直接回到详情，而不是再按片名搜一遍。
     */
    private Movie.Video favoriteTarget() {
        if (info != null && !TextUtils.isEmpty(info.name)) {
            Movie.Video vod = new Movie.Video();
            vod.id = info.id;
            vod.name = info.name;
            vod.pic = info.pic;
            vod.type = info.type;
            vod.area = info.area;
            vod.year = info.year;
            vod.note = info.note;
            vod.des = info.des;
            vod.sourceKey = info.sourceKey;
            return vod;
        }
        if (seed != null && !TextUtils.isEmpty(seed.name)) {
            return seed;
        }
        return null;
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onEnter() {
        if (info == null) {
            if (!loading) {
                load();
            }
            return;
        }
        // 回到这一页时（尤其从播放器退回来），分段条跟着「正在看的那一集」走：
        // 用户可能在播放器里用选集面板翻到了别的段，页面上不该还停在旧的那一段。
        chunkPinned = false;
        refreshSelection();
        refreshFavoriteState();
        // 从播放器退回来时落在「正在看的那一集」上：以前一律给「开始播放」，
        // 看着像是又要从头开一遍。找不到那一格（选集还没铺完、段落对不上）才退回默认落点。
        if (!focusPlayingEpisode()) {
            focusFirst();
        }
    }

    /** @return 焦点真的落到当前那一集上了 */
    private boolean focusPlayingEpisode() {
        if (info == null || series.isEmpty() || episodes.getChildCount() == 0) {
            return false;
        }
        int position = info.playIndex - chunkStart;
        if (position < 0 || position >= series.size()) {
            return false;
        }
        int child = position - episodes.getFirstVisiblePosition();
        if (child < 0 || child >= episodes.getChildCount()) {
            return false;
        }
        View item = episodes.getChildAt(child);
        return item != null && item.requestFocus();
    }

    @Override
    public void onRelease() {
        released = true;
        seq++;
    }

    @Override
    public boolean handleKeyDown(int keyCode) {
        // BACK 交给宿主弹栈（详情页在页面栈中间，不能一次退到桌面）。
        // 这一页本身没有浮层：选集和线路都在页面上摊开，播放器是宿主的覆盖层
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            return Back.pass("影视详情·退出详情页");
        }
        return false;
    }

    private boolean gone() {
        Context c = getContext();
        return released || !(c instanceof Activity)
                || ((Activity) c).isFinishing() || ((Activity) c).isDestroyed();
    }

    // ---------------------------------------------------------------- 加载

    private void load() {
        if (seed == null) {
            return;
        }
        final int mySeq = ++seq;
        loading = true;
        showLoadingState();
        DetailFeed.loadDetail(seed, new DetailFeed.DetailCallback() {
            @Override
            public void onDetail(VodInfo detail) {
                if (mySeq != seq || gone()) {
                    return;
                }
                loading = false;
                info = detail;
                if (TextUtils.isEmpty(seed.sourceKey)) {
                    seed.sourceKey = detail.sourceKey;
                }
                render();
            }

            @Override
            public void onError(String message) {
                if (mySeq != seq || gone()) {
                    return;
                }
                loading = false;
                showErrorState(message);
            }
        });
    }

    private void render() {
        if (info == null) {
            return;
        }
        loadingView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        meta.setVisibility(View.VISIBLE);
        desc.setVisibility(View.VISIBLE);
        playButton.setVisibility(View.VISIBLE);
        favButton.setVisibility(View.VISIBLE);
        searchButton.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.GONE);
        refreshFavoriteState();
        lineLabel.setVisibility(View.VISIBLE);
        lines.setVisibility(View.VISIBLE);
        episodeLabel.setVisibility(View.VISIBLE);
        episodes.setVisibility(View.VISIBLE);

        String name = !TextUtils.isEmpty(info.name) ? info.name : safe(seed.name);
        updateEpisodeLabel();
        title.setText(name);
        meta.setText(buildMeta());
        String text = stripHtml(info.des);
        desc.setText(text.isEmpty() ? "暂无简介" : text);

        SourceBean bean = ApiConfig.get().getSource(info.sourceKey);
        String sourceName = bean == null || TextUtils.isEmpty(bean.getName()) ? "" : bean.getName();
        crumb.setText(sourceName.isEmpty() ? "影视 / 详情" : "影视 / " + sourceName + " / 详情");

        loadImage(poster, info.pic, null);
        String bg = !TextUtils.isEmpty(seed.picSlide) ? seed.picSlide : info.pic;
        loadImage(backdrop, bg, null);

        activeFlag = TextUtils.isEmpty(info.playFlag) && info.seriesFlags != null && !info.seriesFlags.isEmpty()
                ? info.seriesFlags.get(0).name : info.playFlag;
        if (TextUtils.isEmpty(activeFlag) && info.seriesMap != null && !info.seriesMap.isEmpty()) {
            activeFlag = info.seriesMap.keySet().iterator().next();
        }
        applyResume();
        info.playFlag = activeFlag;
        buildLines();
        bindSeries();
        focusFirst();
        if (autoPlay) {
            autoPlay = false;
            // 没有任何一集可选时不能假装成功：留在详情页让用户自己搜/换源
            if (!series.isEmpty()) {
                playCurrent();
            }
        }
    }

    private String buildMeta() {
        List<String> parts = new ArrayList<>();
        if (info.year > 0) {
            parts.add(String.valueOf(info.year));
        }
        add(parts, info.area);
        add(parts, info.type);
        add(parts, info.note);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append(" · ");
            }
            builder.append(parts.get(i));
        }
        return builder.toString();
    }

    private static void add(List<String> parts, String value) {
        if (!TextUtils.isEmpty(value)) {
            parts.add(value.trim());
        }
    }

    private void buildLines() {
        lines.removeAllViews();
        if (info.seriesFlags == null) {
            return;
        }
        for (final VodInfo.VodSeriesFlag flag : info.seriesFlags) {
            if (flag == null || TextUtils.isEmpty(flag.name)
                    || info.seriesMap == null || !info.seriesMap.containsKey(flag.name)) {
                continue;
            }
            TextView chip = new TextView(getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            lp.rightMargin = dp(10);
            chip.setLayoutParams(lp);
            chip.setBackgroundResource(R.drawable.bg_module_item);
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dp(18), 0, dp(18), 0);
            chip.setText(flag.name);
            chip.setTextSize(14);
            chip.setTextColor(AppCompatResources.getColorStateList(getContext(), R.color.nav_item_text));
            chip.setSelected(flag.name.equals(activeFlag));
            FocusScaler.attach(chip, 1.06f, new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        switchLine(flag.name);
                    }
                    v.setSelected(flag.name.equals(activeFlag));
                }
            });
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchLine(flag.name);
                    focusFirstEpisode();
                }
            });
            lines.addView(chip);
        }
    }

    private void switchLine(String flag) {
        if (info == null || flag == null || flag.equals(activeFlag)) {
            return;
        }
        activeFlag = flag;
        info.playFlag = flag;
        info.playIndex = 0;
        chunkPinned = false;
        for (int i = 0; i < lines.getChildCount(); i++) {
            View child = lines.getChildAt(i);
            child.setSelected(flag.contentEquals(
                    child instanceof TextView ? ((TextView) child).getText() : ""));
        }
        bindSeries();
    }

    private void bindSeries() {
        series.clear();
        if (info != null && info.seriesMap != null && activeFlag != null) {
            List<VodInfo.VodSeries> list = info.seriesMap.get(activeFlag);
            if (list != null) {
                series.addAll(list);
            }
        }
        if (info != null && info.playIndex >= series.size()) {
            info.playIndex = Math.max(0, series.size() - 1);
        }
        // 先按全片最长的名字定列数，再据此算「一段放几集」——
        // 两者必须一起算，否则一段的集数和一屏的容量对不上，又会出现要滚动的情况。
        int longest = 0;
        for (VodInfo.VodSeries item : series) {
            String label = item == null ? "" : episodeLabel(item, 0);
            if (label.length() > longest) {
                longest = label.length();
            }
        }
        episodeColumns = columnsForLength(longest);
        chunkSize = Math.max(episodeColumns, episodeColumns * MAX_EPISODE_ROWS);
        episodeLabel.setText("选集" + (series.isEmpty() ? "" : " · 共 " + series.size() + " 集"));
        renderEpisodes();
    }

    private void refreshSelection() {
        if (info == null) {
            return;
        }
        if (!TextUtils.isEmpty(info.playFlag) && !info.playFlag.equals(activeFlag)
                && info.seriesMap != null && info.seriesMap.containsKey(info.playFlag)) {
            activeFlag = info.playFlag;
            bindSeries();
            for (int i = 0; i < lines.getChildCount(); i++) {
                View child = lines.getChildAt(i);
                child.setSelected(activeFlag.contentEquals(
                        child instanceof TextView ? ((TextView) child).getText() : ""));
            }
        } else {
            renderEpisodes();
        }
    }

    // ---------------------------------------------------------------- 播放

    private void playCurrent() {
        if (info == null || series.isEmpty() || info.seriesMap == null) {
            return;
        }
        if (TextUtils.isEmpty(info.playFlag) || info.seriesMap.get(info.playFlag) == null) {
            info.playFlag = activeFlag;
        }
        if (host != null) {
            // 记一笔播放历史。
            //
            // <p>记在这里而不是播放器的入口：首页推的是豆瓣条目（没有源站 id），
            // 要先在详情页里换成源站的片子才谈得上「播了这一集」。
            // 在播放入口记的话，豆瓣那一路就永远记不上。
            android.util.Log.i(TAG, "开始播放：flag=" + info.playFlag
                    + " index=" + info.playIndex + " note=" + info.playNote);
            recordHistory();
            host.play(info);
        }
    }

    /** 把当前这部片子记进本地播放历史 */
    private void recordHistory() {
        if (info == null || TextUtils.isEmpty(info.name)) {
            return;
        }
        Movie.Video video = new Movie.Video();
        video.id = info.id;
        video.name = info.name;
        video.pic = info.pic;
        video.picSlide = info.pic;
        video.type = info.type;
        video.area = info.area;
        video.year = info.year;
        video.note = info.note;
        video.des = info.des;
        video.sourceKey = info.sourceKey;
        VodHistory.record(getContext(), video);
        recordResumePoint();
    }

    // ---------------------------------------------------------------- 断点续播

    /**
     * 上次看到哪条线路的第几集。
     *
     * <p>「看过的片子」由 {@link VodHistory} 记（只有片名/海报，够海报墙用），
     * 但「看到第几集」这件事只有内核那份历史（{@code vodRecord} 表）记全：
     * 它存的是整份 {@link VodInfo}，{@code playFlag}／{@code playIndex} 都在里面。
     * 详情页以前只写 {@code VodHistory}，没碰过那份表，于是每次从「观看历史」
     * 点回来都停在第 1 集 —— 用户说的「接着上次看」缺的就是这一步。
     *
     * <p>一集之内的秒数不在这里：播放器按 {@code 源+id+线路+集数+集名} 自己把
     * 进度存在缓存里，只要集数对得上，起播就会自动 seek 到上次退出的位置。
     */
    private String resumeFlag;
    private int resumeIndex = -1;
    /** 上次看到的那一集的**名字**：换过线路/源站重排过集数时，只有名字还认得出来 */
    private String resumeNote = "";
    /** 续播点是「按片名兜底」找到的（另一个源），集数不能直接信 */
    private boolean resumeByName;
    /** 真的读到过续播记录（区别于「默认第 1 集」） */
    private boolean hasResumePoint;

    /** 读回上次的线路和集数，读到就记下来，{@link #applyResume()} 里用 */
    private void readResumePoint() {
        resumeFlag = null;
        resumeIndex = -1;
        resumeNote = "";
        resumeByName = false;
        hasResumePoint = false;
        if (info == null || info.seriesMap == null || info.seriesMap.isEmpty()) {
            return;
        }
        String sourceKey = !TextUtils.isEmpty(info.sourceKey) ? info.sourceKey
                : (seed == null ? "" : seed.sourceKey);
        if (TextUtils.isEmpty(sourceKey) || TextUtils.isEmpty(info.id)) {
            return;
        }
        try {
            VodInfo record = RoomDataManger.getVodInfo(sourceKey, info.id);
            if (record != null) {
                if (!TextUtils.isEmpty(record.playFlag)
                        && info.seriesMap.containsKey(record.playFlag)) {
                    resumeFlag = record.playFlag;
                }
                resumeIndex = record.playIndex;
                resumeNote = record.playNote == null ? "" : record.playNote;
                hasResumePoint = true;
            }
        } catch (Throwable th) {
            android.util.Log.w(TAG, "读播放进度失败：" + th.getMessage());
        }
        // 内核那张表读不到、或读出来是第 0 集时，用 prefs 里那份兜底。
        // 实机上出现过「表里有记录但 id 对不上」的情况，用户看到的就是「又从第一集开始」。
        // prefs 那份还带「片名」这一层键：源站换了 id（详情页地址变了）也能找回来。
        // 两份是同时写的，prefs 总在内核表之后 —— 不一致时以 prefs 为准。
        ResumeStore.Point point = ResumeStore.read(getContext(), sourceKey, info.id, info.name);
        if (point != null && (point.index > 0 || !TextUtils.isEmpty(point.episode))) {
            resumeIndex = point.index;
            resumeNote = point.episode == null ? "" : point.episode;
            resumeByName = point.byName;
            hasResumePoint = true;
            android.util.Log.i(TAG, "读续播点（本地）：第 " + point.index + " 集 " + point.episode
                    + (point.byName ? "（按片名兜底）" : ""));
        }
        android.util.Log.i(TAG, "读续播点：source=" + sourceKey + " id=" + info.id
                + " flag=" + resumeFlag + " index=" + resumeIndex
                + " note=" + resumeNote + " 有记录=" + hasResumePoint);
    }

    /**
     * 把上次的线路/集数盖到这次拉到的详情上。
     *
     * <p>集数不能直接按数字用：同一条片子在不同线路上的排序可能不一样
     * （实测同一部剧，A 线第 39 条是 E20、B 线第 21 条才是 E20）。
     * 只按数字盖的话，用户「看的是第 22 集，回来却高亮成别的集」。
     * 所以先按集名找，找不到才退回按数字夹。
     */
    private void applyResume() {
        readResumePoint();
        if (!TextUtils.isEmpty(resumeFlag)) {
            activeFlag = resumeFlag;
        }
        if (info.seriesMap != null && activeFlag != null) {
            List<VodInfo.VodSeries> list = info.seriesMap.get(activeFlag);
            if (list != null && !list.isEmpty() && resumeIndex >= 0) {
                int byName = indexOfEpisode(list, resumeNote);
                if (byName >= 0) {
                    info.playIndex = byName;
                } else if (!resumeByName) {
                    info.playIndex = Math.max(0, Math.min(resumeIndex, list.size() - 1));
                }
                // 按片名兜底找到、但这条线路里没有同名的集：不猜，留在第一集。
                // 猜错的代价比「从头开始」大得多 —— 用户会以为选集全乱了。
            }
        }
    }

    /** 按集名找下标；找不到（源站改过名）返回 -1 */
    private static int indexOfEpisode(List<VodInfo.VodSeries> list, String name) {
        if (list == null || TextUtils.isEmpty(name)) {
            return -1;
        }
        String target = name.trim();
        for (int i = 0; i < list.size(); i++) {
            VodInfo.VodSeries item = list.get(i);
            if (item != null && item.name != null && target.equals(item.name.trim())) {
                return i;
            }
        }
        return -1;
    }

    /** 把「这条线路、这一集」写回内核的历史表，下次打开详情页才有得读 */
    private void recordResumePoint() {
        if (info == null || info.seriesMap == null || TextUtils.isEmpty(info.id)) {
            return;
        }
        String sourceKey = !TextUtils.isEmpty(info.sourceKey) ? info.sourceKey
                : (seed == null ? "" : seed.sourceKey);
        if (TextUtils.isEmpty(sourceKey)) {
            return;
        }
        try {
            List<VodInfo.VodSeries> list = info.seriesMap.get(info.playFlag);
            info.playNote = list != null && info.playIndex >= 0 && info.playIndex < list.size()
                    ? list.get(info.playIndex).name : "";
            RoomDataManger.insertVodRecord(sourceKey, info);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "写播放进度失败：" + th.getMessage());
        }
        // 再存一份到 prefs：内核表在实机上出现过写不进/读不出的情况
        ResumeStore.save(getContext(), sourceKey, info.id, info.name, info.playIndex, info.playNote);
        hasResumePoint = true;
        android.util.Log.i(TAG, "记续播点：" + info.name + " 第 " + info.playIndex + " 集 " + info.playNote);
    }

    private String searchKeyword() {
        if (info != null && !TextUtils.isEmpty(info.name)) {
            return info.name;
        }
        return seed == null ? "" : safe(seed.name);
    }

    // ---------------------------------------------------------------- 状态

    private void showLoadingState() {
        loadingView.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        title.setVisibility(View.GONE);
        meta.setVisibility(View.GONE);
        desc.setVisibility(View.GONE);
        playButton.setVisibility(View.GONE);
        favButton.setVisibility(View.GONE);
        searchButton.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        lineLabel.setVisibility(View.GONE);
        lines.setVisibility(View.GONE);
        episodeLabel.setVisibility(View.GONE);
        episodes.setVisibility(View.GONE);
        crumb.setText("影视 / 详情");
        if (seed != null) {
            loadImage(poster, !TextUtils.isEmpty(seed.pic) ? seed.pic : seed.picSlide, null);
            loadImage(backdrop, !TextUtils.isEmpty(seed.picSlide) ? seed.picSlide : seed.pic, null);
            crumb.setText("影视 / " + safe(seed.name));
        }
    }

    private void showErrorState(String message) {
        loadingView.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        errorView.setText(TextUtils.isEmpty(message)
                ? "详情加载失败，按「重试」再来一次" : message + "\n按「重试」再来一次");
        title.setVisibility(View.VISIBLE);
        title.setText(seed == null ? "" : safe(seed.name));
        meta.setVisibility(View.GONE);
        desc.setVisibility(View.GONE);
        playButton.setVisibility(View.GONE);
        favButton.setVisibility(View.GONE);
        searchButton.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
        lineLabel.setVisibility(View.GONE);
        lines.setVisibility(View.GONE);
        episodeLabel.setVisibility(View.GONE);
        episodes.setVisibility(View.GONE);
        retryButton.post(new Runnable() {
            @Override
            public void run() {
                retryButton.requestFocus();
            }
        });
    }

    private void focusFirst() {
        post(new Runnable() {
            @Override
            public void run() {
                if (gone() || info == null) {
                    return;
                }
                Activity activity = (Activity) getContext();
                View focused = activity.getCurrentFocus();
                if (focused != null && isDescendant(focused)) {
                    return;
                }
                playButton.requestFocus();
            }
        });
    }

    private void focusFirstEpisode() {
        if (series.isEmpty()) {
            return;
        }
        episodes.post(new Runnable() {
            @Override
            public void run() {
                View child = episodes.getChildAt(0);
                if (child != null) {
                    child.requestFocus();
                }
            }
        });
    }

    private boolean isDescendant(View v) {
        while (v != null) {
            if (v == this) {
                return true;
            }
            android.view.ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    // ---------------------------------------------------------------- 选集

    /**
     * 刷新选集。
     *
     * <p>网格里只有「当前这一段」的集数（正好一屏 32 格），
     * 翻页靠换 adapter 的数据，不靠滚动 —— 见布局里那段注释，
     * 这台电视上滚动方案要么崩要么重影。
     */
    /** 选集标题：条数 + 上次看到第几集（让「记住了」这件事看得见） */
    private void updateEpisodeLabel() {
        if (episodeLabel == null) {
            return;
        }
        StringBuilder text = new StringBuilder("选集");
        if (!series.isEmpty()) {
            text.append(" · 共 ").append(series.size()).append(" 集");
        }
        // 只有真的读到过续播记录才写「上次看到第几集」。
        // 以前这里只看 playIndex 是否在范围内，而新剧的 playIndex 默认就是 0，
        // 于是每一部没看过的剧都标着「上次看到第 1 集」—— 用户以为记录全乱了。
        if (hasResumePoint && info != null
                && info.playIndex >= 0 && info.playIndex < series.size()) {
            text.append(" · 上次看到第 ").append(info.playIndex + 1).append(" 集");
        }
        episodeLabel.setText(text.toString());
    }

    private void renderEpisodes() {
        if (episodes == null) {
            return;
        }
        // 段起点跟着播放进度走：换集之后回到的那一段才是用户想看的那一段。
        // 但用户手动翻过段之后就不跟了，见 chunkPinned 的说明
        if (!chunkPinned && info != null && info.playIndex >= 0 && info.playIndex < series.size()) {
            chunkStart = (info.playIndex / chunkSize) * chunkSize;
        }
        if (chunkStart >= series.size()) {
            chunkStart = Math.max(0, ((series.size() - 1) / chunkSize) * chunkSize);
        }
        applyEpisodeColumns();
        adapter.notifyDataSetChanged();
        renderChunks();
        updateEpisodeLabel();
        android.util.Log.i(TAG, "renderEpisodes: chunkStart=" + chunkStart
                + " playIndex=" + (info == null ? -1 : info.playIndex)
                + " 列数=" + episodes.getNumColumns() + " 段内=" + adapter.getCount());
        // 网格高度按「实际几行」量一次。写死 204dp 在长名字（列数变少、行数变多）
        // 或者字号被系统放大时会切掉最后一行 —— 用户看到的就是「最下面一栏按不下去」。
        fitEpisodeGrid();
    }

    /**
     * 网格高度按内容自动贴合（最多 4 行，多出来的在网格内滚动）。
     *
     * <p>为什么必须量：这段的列数是按片名长度算的（2–8 列），32 集在 2 列时是 16 行、
     * 在 8 列时是 4 行。布局里写死 204dp 只在「8 列 4 行」这一种情况下刚好；
     * 一旦是长文件名（网盘源那种），最后一行会被切掉一半 —— 焦点移过去看不见，
     * 用户就会觉得「按不下去、选不完」。
     */
    private void fitEpisodeGrid() {
        if (episodes == null) {
            return;
        }
        episodes.post(new Runnable() {
            @Override
            public void run() {
                if (episodes == null || episodes.getChildCount() == 0) {
                    return;
                }
                // 行高按选及格子的固定高度算，不去量子 View：
                // 第一次 post 时子 View 还没走完布局，量到的是布局前的旧值（实测 46px，
                // 实际 60px），算出来的高度仍然差一行 —— 上一版就是这么把第 4 行切一半的。
                int cellHeight = dp(40);
                int columns = Math.max(1, episodes.getNumColumns());
                int totalRows = (episodes.getCount() + columns - 1) / columns;
                int rows = Math.min(totalRows, MAX_EPISODE_ROWS);
                int height = episodes.getPaddingTop() + episodes.getPaddingBottom()
                        + rows * cellHeight
                        + Math.max(0, rows - 1) * episodes.getVerticalSpacing();
                ViewGroup.LayoutParams lp = episodes.getLayoutParams();
                if (lp != null && lp.height != height) {
                    lp.height = height;
                    episodes.setLayoutParams(lp);
                }
                applyEpisodeSelection();
            }
        });
    }

    /**
     * 选集网格最多显示几行（再多就在网格里滚动）。
     *
     * <p>为什么是 3 不是 4：详情页给选集区的垂直空间只有 241px（实测），
     * 一行 40dp 加 8dp 间距要 72px，4 行要 300px —— 超出的部分会被父布局裁掉，
     * 第 4 行只剩一条边，焦点移过去等于看不见。3 行正好 228px，稳稳放下。
     */
    /**
     * 按「上次看到第几集」刷一遍格子的选中态。
     *
     * <p>格子的选中态是在 {@code getView} 里按 {@code info.playIndex} 设的，
     * 而详情数据回来时 playIndex 可能还是 0 —— 续播点要等 {@code applyResume()} 才盖上去。
     * 于是第一格被标成「上次看的」，用户看到的就是「没记住」。
     * 这里在布局完成后再对齐一次，不依赖时序。
     */
    private void applyEpisodeSelection() {
        if (episodes == null || info == null) {
            return;
        }
        int first = episodes.getFirstVisiblePosition();
        for (int i = 0; i < episodes.getChildCount(); i++) {
            View child = episodes.getChildAt(i);
            if (child != null) {
                child.setActivated(chunkStart + first + i == info.playIndex);
            }
        }
    }

    private static final int MAX_EPISODE_ROWS = 3;

    /**
     * 选集网格上的方向键。
     *
     * <p>焦点停在最后一行再按「下」，以前什么都不会发生 —— 用户以为选集卡住了。
     * 这里把它接到「下一段」：换一段数据、焦点落到新的一段第一格，行为跟直接点分段条一致。
     * 第一行按「上」同理回到上一段（没有上一段就把它交还给上面的控件）。
     */
    private boolean onEpisodeKey(int keyCode, int position) {
        if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN
                && keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP) {
            return false;
        }
        if (position < 0) {
            return false;
        }
        int columns = Math.max(1, episodes.getNumColumns());
        int count = episodes.getCount();
        android.util.Log.i(TAG, "选及格按键 key=" + keyCode + " pos=" + position
                + " 列=" + columns + " 共=" + count);
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            int target = position + columns;
            if (target >= count) {
                return jumpChunk(1);
            }
            moveEpisodeFocus(target);
            return true;
        }
        int target = position - columns;
        if (target < 0) {
            return jumpChunk(-1);
        }
        moveEpisodeFocus(target);
        return true;
    }

    /**
     * 把焦点挪到第 target 格 —— 先让网格把这一格滚进视口，再补焦点。
     *
     * <p>为什么不能交给系统：GridView 只给「视口内的那几行」创建子 View，
     * 视口外的格子根本不存在，几何寻焦找不到落点，按「下」就没反应。
     * 所以这里先 {@code setSelection} 让它滚过去（这一步会创建子 View），
     * 下一帧再把焦点交给它。
     *
     * <p>网格高度是按「最多 4 行」量的，长片名（2 列）时 30 集要 15 行，
     * 全靠这个滚动才能一集一集走到底。
     */
    private void moveEpisodeFocus(final int target) {
        if (episodes == null) {
            return;
        }
        // 目标就在视口里：直接给焦点，别动不动 setSelection（那样每次按「下」
        // 都会把这一格顶到第一行，滚动很跳）
        episodeCursor = target;
        View visible = episodes.getChildAt(target - episodes.getFirstVisiblePosition());
        if (visible != null && visible.isShown()) {
            visible.requestFocus();
            return;
        }
        // 视口外：先滚过去（这一下才会创建对应的子 View），下一帧再补焦
        episodes.setSelection(target);
        episodes.postDelayed(new Runnable() {
            @Override
            public void run() {
                focusEpisodeAt(target, 4);
            }
        }, 16L);
    }

    /** 按「绝对下标」补焦点：先换算成可见子 View 的下标 */
    private void focusEpisodeAt(final int target, final int retries) {
        if (episodes == null || gone()) {
            return;
        }
        View child = episodes.getChildAt(target - episodes.getFirstVisiblePosition());
        if (child != null && child.isShown()) {
            child.requestFocus();
            return;
        }
        if (retries > 0) {
            episodes.postDelayed(new Runnable() {
                @Override
                public void run() {
                    focusEpisodeAt(target, retries - 1);
                }
            }, 16L);
        }
    }

    /** 换到上/下一段；没有那一段就返回 false（把按键还回去） */
    private boolean jumpChunk(int direction) {
        int next = chunkStart + direction * chunkSize;
        if (next < 0 || next >= series.size()) {
            return false;
        }
        chunkStart = next;
        chunkPinned = true;
        adapter.notifyDataSetChanged();
        updateChunkSelection();
        fitEpisodeGrid();
        focusEpisode(0, 4);
        return true;
    }

    /**
     * 选集按钮的列数按这一段里最长的名字自适应。
     *
     * <p>格子改成了「原名显示」，名字长短就看源的了——短的两三个字
     * （「第 3 集」「预告」），长的十几二十字（网盘源的「4.9 GB 1080P
     * 国语中字.mp4」）。列数写死 8 的话，长名字在 90dp 的格子里全被截成
     * 「4.9 GB…」，选集等于没写。
     *
     * <p>这里量这一段里最长的名字，按长度挑列数：名字越长列越少、
     * 每颗按钮越宽。8 列正好一屏 32 格是短名字的福利；长名字时一段
     * 一屏放不下 32 颗，网格自己滚动，段标签照样翻。
     */
    private void applyEpisodeColumns() {
        if (episodes == null || series.isEmpty()) {
            return;
        }
        // 列数在 bindSeries() 里按全片最长的名字算好了，这里只管套上去。
        // 段与段一致，才不会出现「这一段的格子和上一段一样宽窄不一」。
        if (episodes.getNumColumns() != episodeColumns) {
            episodes.setNumColumns(episodeColumns);
        }
    }

    /**
     * 片名多长用几列。
     *
     * <p>名字越长列越少、每颗按钮越宽。8 列是短名字（「第 3 集」）的福利；
     * 网盘源那种「[5.2GB]JJC.S01E10.mkv【叫金钗】」只能给 2 列，
     * 再多就截断在体积前缀上，用户根本看不到是第几集。
     */
    private int columnsForLength(int longest) {
        if (longest <= 4) {
            return 8;
        }
        if (longest <= 7) {
            return 6;
        }
        if (longest <= 12) {
            return 5;
        }
        if (longest <= 17) {
            return 4;
        }
        if (longest <= 24) {
            return 3;
        }
        return 2;
    }

    /**
     * 每段多少集。
     *
     * <p>取 32 —— 一屏正好 8 列 × 4 行 = 32 格。以前按 30 分，每段末尾都空两个格，
     * 一屏里看着像「少了两集」；而且段标签写「31-60」，第 31 集的格子却在第一列第二行，
     * 跟标签对不上。按 32 分之后，段号、格位、集数三者完全重合。
     */
    private static final int CHUNK_SIZE = 32;

    /**
     * 当前这一段实际放多少集：列数 × 可见行数。
     *
     * <p>以前的算法是「固定 32 集一段，一屏放不下就在网格里滚」。可这台的详情页
     * 只给选集区 3 行的高度，而列数又是按片名长度自适应的（长文件名只有 2 列）——
     * 32 集要排 16 行，靠滚动才能看全，而 GridView 在这里根本不跟焦点滚，
     * 用户按到底就卡住了。
     *
     * <p>改成「一段正好一屏」：列数 × 3 行，多余的一律不进这一段。
     * 翻段用上面的分段条，或者在最后一行继续按「下」——两侧都能翻，永远不需要滚动。
     */
    private int chunkSize = CHUNK_SIZE;

    /** 这一部片子的选集列数（按全片最长的名字算，段与段之间保持一致） */
    private int episodeColumns = 8;

    /**
     * 选集上方的分段条。
     *
     * <p>199 集的剧靠方向键一路按下去太费劲，这里按 32 集切一段，
     * 点一下就把网格换成那一段（焦点落在该段第一集）。
     * 集数不到一屏就不显示 —— 20 集的剧顶上一排「1-30」只是噪音。
     */
    private void renderChunks() {
        if (chunks == null || chunkScroll == null) {
            return;
        }
        chunks.removeAllViews();
        int total = series.size();
        if (total <= chunkSize) {
            chunkScroll.setVisibility(View.GONE);
            return;
        }
        chunkScroll.setVisibility(View.VISIBLE);
        for (int start = 0; start < total; start += chunkSize) {
            final int first = start;
            final int end = Math.min(start + chunkSize, total);
            TextView chip = new TextView(getContext());
            // 段标签用位置区间：格子里显示的是原始名称，没有统一的「集号」可指，
            // 写「1-32」就是「这段里是列表的第 1 到 32 项」
            chip.setText((first + 1) + "-" + end);
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTextColor(androidx.appcompat.content.res.AppCompatResources
                    .getColorStateList(getContext(), R.color.episode_text));
            chip.setBackgroundResource(R.drawable.bg_episode_chip);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            // 当前这一段高亮，让用户知道自己停在哪
            chip.setSelected(first == chunkStart);
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setFocusableInTouchMode(true);
            FocusScaler.attach(chip, 1.03f);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chunkStart = first;
                    chunkPinned = true;
                    adapter.notifyDataSetChanged();
                    // 只改高亮，不要 removeAllViews 重建整排。
                    // 以前这里调的是 renderChunks()，它一上来就把所有分段按钮删掉 ——
                    // 被删的正好是刚按下、还带着焦点的那一颗。焦点没了，系统顺手把它交给
                    // 页面里第一个可聚焦控件（「播放」按钮），用户按一下分段，光标直接飞走；
                    // 这时候再按确定，播的是「上一次点的集」，看起来就是「选了 197，播的是别的集」。
                    updateChunkSelection();
                    // 焦点落进这一段的第一集（网格刚换数据，要等一帧才有子 View）
                    focusEpisode(0, 4);
                }
            });
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            lp.rightMargin = dp(8);
            chunks.addView(chip, lp);
        }
    }

    /** 只改分段条的高亮，不动视图（删视图会把焦点一起删掉） */
    private void updateChunkSelection() {
        if (chunks == null) {
            return;
        }
        for (int i = 0; i < chunks.getChildCount(); i++) {
            View child = chunks.getChildAt(i);
            int first = i * chunkSize;
            child.setSelected(first == chunkStart);
        }
    }

    /**
     * 把焦点送进网格的第 {@code position} 格（段内下标）。
     *
     * <p>换段之后 adapter 的数据变了，但子 View 要等下一次 layout 才重建，
     * 所以这里带重试：拿到子 View 就挂焦，拿不到就等一帧再来。
     */
    private void focusEpisode(final int position, final int retries) {
        if (episodes == null || gone()) {
            return;
        }
        episodes.post(new Runnable() {
            @Override
            public void run() {
                if (gone()) {
                    return;
                }
                View child = episodes.getChildAt(position);
                if (child == null || !child.isShown()) {
                    if (retries > 0) {
                        focusEpisode(position, retries - 1);
                    }
                    return;
                }
                child.requestFocus();
            }
        });
    }

    /** 一屏一页：只装当前这一段 */
    private final class EpisodeAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            if (series.isEmpty()) {
                return 0;
            }
            int from = Math.min(chunkStart, series.size() - 1);
            return Math.min(chunkSize, series.size() - from);
        }

        @Override
        public Object getItem(int position) {
            return series.get(chunkStart + position);
        }

        @Override
        public long getItemId(int position) {
            return chunkStart + position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView item = convertView instanceof TextView ? (TextView) convertView
                    : (TextView) LayoutInflater.from(getContext())
                    .inflate(R.layout.item_episode, parent, false);
            final int index = chunkStart + position;
            if (index >= series.size()) {
                return item;
            }
            final VodInfo.VodSeries vodSeries = series.get(index);
            String label = episodeLabel(vodSeries, index);
            item.setText(label);
            // 名字越长字号越小，配合自适应列宽把名字尽量显示全；
            // 再长也不缩到看不清，截断交给省略号
            float textSizeSp;
            int len = label.length();
            if (len <= 7) {
                textSizeSp = 14f;
            } else if (len <= 12) {
                textSizeSp = 12f;
            } else {
                textSizeSp = 10f;
            }
            item.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp);
            item.setActivated(info != null && index == info.playIndex);
            // 选集以前只靠背景区分焦点，系统在 API 26+ 又叠了一层方形默认高亮，
            // 圆角胶囊外面套个直角方框，看着就像被裁了一刀。挂上缩放后由 FocusScaler
            // 统一关掉系统高亮，焦点语言和其他按钮一致。
            FocusScaler.attach(item, 1.03f);
            /*
             * 方向键挂在「每一格」上，不是网格上。
             * 按键在 Android 里只派发给带焦点的那个子 View，父容器的 OnKeyListener
             * 收不到（网格那一版挂了等于没挂，按到底什么都不会发生）。
             */
            final int keyIndex = index;
            item.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
                    if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    int at = series.indexOf(vodSeries);
                    return onEpisodeKey(keyCode, at >= 0 ? at - chunkStart : keyIndex - chunkStart);
                }
            });
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (info == null) {
                        return;
                    }
                    // 按「这一格绑定的那一集」定位，而不是拿 getView 时的下标直接用：
                    // 段起点（chunkStart）在重排、刷新、切换线路时都会变，
                    // 现算出来的下标可能已经指向另一集 —— 表现就是「选第 197 集，播的是别的集」。
                    int at = series.indexOf(vodSeries);
                    if (at < 0 || at >= series.size()) {
                        at = index;
                    }
                    info.playIndex = at;
                    chunkPinned = false;
                    adapter.notifyDataSetChanged();
                    playCurrent();
                }
            });
            return item;
        }
    }

    // ---------------------------------------------------------------- 图片/工具

    /**
     * 选集按钮上写什么。网盘类源给的是文件名（「4.9 GB 1080P 国语中字.mp4」），
     * 十来个字在 80dp 的格子里全被截成同一截「4.9 GB…」，选集等于没写。
     * 认得出是文件名的就退回序号；真正的「第 3 集」这类短标签照原样显示。
     */
    /**
     * 这一格显示的文字：源里给什么就显示什么。
     *
     * <p>以前试过两版：拿「位置 + 1」当集号，结果源里两套编号混排时
     * 界面写的号和播出来的集对不上；后来又解析文件名里的集号，可源给的
     * 名字五花八门（体积前缀、扩展名、音轨标注），解析总有漏网的，
     * 显示出来的号还是可能和名字对不上。
     *
     * <p>最后回到最简单的做法：<b>原名显示，不动源的数据</b>。
     * 名字长就让按钮长（列数按名字长度自适应，见 {@link #applyEpisodeColumns()}），
     * 名字是空的才退回「位置 + 1」占位。
     */
    private static String episodeLabel(VodInfo.VodSeries vodSeries, int position) {
        String name = vodSeries == null ? "" : vodSeries.name;
        if (TextUtils.isEmpty(name)) {
            return String.valueOf(position + 1);
        }
        return name.trim();
    }

    private void loadImage(ImageView target, String url, Drawable fallback) {
        if (target == null || TextUtils.isEmpty(url)) {
            return;
        }
        try {
            Object model = ImgUtil.getImageModel(url);
            if (model == null) {
                return;
            }
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
            android.util.Log.w(TAG, "load image failed: " + url, th);
        }
    }

    private static String stripHtml(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        return text.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

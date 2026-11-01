package com.tvdesk.desk;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

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

        FocusScaler.attach(playButton, 1.06f);
        FocusScaler.attach(searchButton, 1.06f);
        FocusScaler.attach(retryButton, 1.06f);

        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playCurrent();
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

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onEnter() {
        if (info == null) {
            if (!loading) {
                load();
            }
            return;
        }
        refreshSelection();
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
        searchButton.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.GONE);
        lineLabel.setVisibility(View.VISIBLE);
        lines.setVisibility(View.VISIBLE);
        episodeLabel.setVisibility(View.VISIBLE);
        episodes.setVisibility(View.VISIBLE);

        String name = !TextUtils.isEmpty(info.name) ? info.name : safe(seed.name);
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

    /** 读回上次的线路和集数，读到就记下来，{@link #applyResume()} 里用 */
    private void readResumePoint() {
        resumeFlag = null;
        resumeIndex = -1;
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
            if (record == null) {
                return;
            }
            if (!TextUtils.isEmpty(record.playFlag) && info.seriesMap.containsKey(record.playFlag)) {
                resumeFlag = record.playFlag;
            }
            resumeIndex = record.playIndex;
        } catch (Throwable th) {
            android.util.Log.w(TAG, "读播放进度失败：" + th.getMessage());
        }
    }

    /**
     * 把上次的线路/集数盖到这次拉到的详情上。
     *
     * <p>夹到列表长度以内：源站删过集、换了线路之后集数会变，
     * 越界的话 {@code series.get()} 会直接崩在这里。
     */
    private void applyResume() {
        readResumePoint();
        if (!TextUtils.isEmpty(resumeFlag)) {
            activeFlag = resumeFlag;
        }
        if (info.seriesMap != null && activeFlag != null) {
            List<VodInfo.VodSeries> list = info.seriesMap.get(activeFlag);
            if (list != null && !list.isEmpty() && resumeIndex >= 0) {
                info.playIndex = Math.max(0, Math.min(resumeIndex, list.size() - 1));
            }
        }
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
    private void renderEpisodes() {
        if (episodes == null) {
            return;
        }
        // 段起点跟着播放进度走：换集之后回到的那一段才是用户想看的那一段。
        // 但用户手动翻过段之后就不跟了，见 chunkPinned 的说明
        if (!chunkPinned && info != null && info.playIndex >= 0 && info.playIndex < series.size()) {
            chunkStart = (info.playIndex / CHUNK_SIZE) * CHUNK_SIZE;
        }
        if (chunkStart >= series.size()) {
            chunkStart = Math.max(0, ((series.size() - 1) / CHUNK_SIZE) * CHUNK_SIZE);
        }
        applyEpisodeColumns();
        adapter.notifyDataSetChanged();
        renderChunks();
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
        int longest = 0;
        int to = Math.min(chunkStart + CHUNK_SIZE, series.size());
        for (int i = chunkStart; i < to; i++) {
            String name = series.get(i) == null ? "" : series.get(i).name;
            if (name != null && name.length() > longest) {
                longest = name.length();
            }
        }
        int columns;
        if (longest <= 4) {
            columns = 8;      // 「第 3 集」「预告」这个量级：一屏 32 格
        } else if (longest <= 7) {
            columns = 6;      // 「S01E03」「1080P 国语」
        } else if (longest <= 12) {
            columns = 5;      // 「4.9 GB 1080P.mp4」
        } else if (longest <= 17) {
            columns = 4;      // 「[3.2GB]125.mp4」
        } else if (longest <= 24) {
            columns = 3;      // 「[2.3GB]E67.mp4【牧S记】」
        } else {
            columns = 2;      // 更长的文件名：一行两颗，名字尽量显示全。
            // 集数藏在名字中段（[338.1MB]01.mp4【斗罗大陆2】），列数再多
            // 就截断在体积前缀上，用户根本看不到是第几集 —— 2 列是下限，
            // 再长也是它，截断交给省略号
        }
        if (episodes.getNumColumns() != columns) {
            episodes.setNumColumns(columns);
        }
    }

    /**
     * 每段多少集。
     *
     * <p>取 32 —— 一屏正好 8 列 × 4 行 = 32 格。以前按 30 分，每段末尾都空两个格，
     * 一屏里看着像「少了两集」；而且段标签写「31-60」，第 31 集的格子却在第一列第二行，
     * 跟标签对不上。按 32 分之后，段号、格位、集数三者完全重合。
     */
    private static final int CHUNK_SIZE = 32;
    /** 超过一屏（32 集）才做分段条 */
    private static final int CHUNK_THRESHOLD = CHUNK_SIZE;

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
        if (total <= CHUNK_THRESHOLD) {
            chunkScroll.setVisibility(View.GONE);
            return;
        }
        chunkScroll.setVisibility(View.VISIBLE);
        for (int start = 0; start < total; start += CHUNK_SIZE) {
            final int first = start;
            final int end = Math.min(start + CHUNK_SIZE, total);
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
            int first = i * CHUNK_SIZE;
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
            return Math.min(CHUNK_SIZE, series.size() - from);
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
            item.setSelected(info != null && index == info.playIndex);
            // 选集以前只靠背景区分焦点，系统在 API 26+ 又叠了一层方形默认高亮，
            // 圆角胶囊外面套个直角方框，看着就像被裁了一刀。挂上缩放后由 FocusScaler
            // 统一关掉系统高亮，焦点语言和其他按钮一致。
            FocusScaler.attach(item, 1.03f);
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

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
    private ProgressBar loadingView;
    private TextView errorView;

    private final List<VodInfo.VodSeries> series = new ArrayList<>();
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
        focusFirst();
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
        adapter.notifyDataSetChanged();
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
            adapter.notifyDataSetChanged();
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
            host.play(info);
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
        episodes.setSelection(0);
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

    private final class EpisodeAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return series.size();
        }

        @Override
        public Object getItem(int position) {
            return series.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            TextView item = convertView instanceof TextView ? (TextView) convertView
                    : (TextView) LayoutInflater.from(getContext())
                    .inflate(R.layout.item_episode, parent, false);
            VodInfo.VodSeries vodSeries = series.get(position);
            item.setText(episodeLabel(vodSeries, position));
            item.setSelected(info != null && position == info.playIndex);
            // 选集以前只靠背景区分焦点，系统在 API 26+ 又叠了一层方形默认高亮，
            // 圆角胶囊外面套个直角方框，看着就像被裁了一刀。挂上缩放后由 FocusScaler
            // 统一关掉系统高亮，焦点语言和其他按钮一致。
            FocusScaler.attach(item, 1.03f);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (info == null || position >= series.size()) {
                        return;
                    }
                    info.playIndex = position;
                    notifyDataSetChanged();
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
    private static String episodeLabel(VodInfo.VodSeries vodSeries, int position) {
        String name = vodSeries == null ? "" : vodSeries.name;
        if (TextUtils.isEmpty(name) || looksLikeFileName(name)) {
            return String.valueOf(position + 1);
        }
        return name;
    }

    private static boolean looksLikeFileName(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".ts")
                || lower.endsWith(".avi") || lower.endsWith(".m3u8") || lower.endsWith(".rmvb")) {
            return true;
        }
        return lower.contains(" gb") || lower.contains(" mb") || name.length() > 14;
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

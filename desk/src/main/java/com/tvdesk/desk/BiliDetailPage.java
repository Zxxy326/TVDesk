package com.tvdesk.desk;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.tvdesk.core.bili.BiliClient;
import com.tvdesk.core.bili.model.BiliDetail;
import com.tvdesk.core.bili.model.BiliEpisode;
import com.tvdesk.desk.motion.FocusScaler;

import java.util.ArrayList;
import java.util.List;

/**
 * B 站稿件详情页（叠在模块页之上的一层，见 {@code DeskActivity} 的页面栈）。
 *
 * <p>它只负责「这一条视频是什么 + 有哪几 P + 从哪一 P 开始播」，
 * 取播放地址和播放本身在 {@link BiliPlayerPage}——取地址那一步要签 wbi、
 * 还要两次请求（先要整片 mp4，没有再要 DASH），把这段时间花在播放器页的
 * loading 上比卡在详情页清楚。
 *
 * <p>分 P 用影视详情页同款的方块网格：一个 BV 号从 1 P 到几百 P 都有，
 * 卡片墙放不下也数不清第几 P。只有一 P 时整块「选集」收起来。
 */
public class BiliDetailPage extends FrameLayout implements ModulePage {

    private static final String TAG = "BiliDetailPage";

    public interface Host {
        /** 交一集给播放器。宿主压 {@link BiliPlayerPage} */
        void playBili(BiliDetail detail, BiliEpisode episode);

        /** 三连要登录：宿主弹扫码窗，登录成功后回调（回调里会接着把三连发出去） */
        void openBiliLogin(Runnable afterLogin);
    }

    private final Host host;
    private final String bvid;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BiliDetail detail;
    private boolean loading;
    private boolean released;
    private int seq;
    /** 焦点/点击选中的那一 P，没选就是第一 P */
    private int playIndex;

    private ImageView bg;
    private TextView crumb;
    private TextView tag;
    private TextView title;
    private TextView meta;
    private TextView desc;
    private View playButton;
    private TextView playLabel;
    private View tripleButton;
    private TextView tripleLabel;
    private TextView onlineView;
    private View shareButton;
    private TextView retryButton;
    private TextView episodeLabel;
    private GridView episodes;
    private TextView errorView;
    private ProgressBar loadingView;

    private boolean tripling;
    private int onlineSeq;

    private final List<BiliEpisode> items = new ArrayList<>();
    private final EpisodeAdapter adapter = new EpisodeAdapter();

    public BiliDetailPage(Context context, Host host, String bvid) {
        super(context);
        this.host = host;
        this.bvid = bvid;
        LayoutInflater.from(context).inflate(R.layout.page_bili_detail, this, true);

        bg = findViewById(R.id.bili_detail_bg);
        crumb = findViewById(R.id.bili_detail_crumb);
        tag = findViewById(R.id.bili_detail_tag);
        title = findViewById(R.id.bili_detail_title);
        meta = findViewById(R.id.bili_detail_meta);
        desc = findViewById(R.id.bili_detail_desc);
        playButton = findViewById(R.id.bili_detail_play);
        playLabel = findViewById(R.id.bili_detail_play_label);
        tripleButton = findViewById(R.id.bili_detail_triple);
        tripleLabel = findViewById(R.id.bili_detail_triple_label);
        onlineView = findViewById(R.id.bili_detail_online);
        shareButton = findViewById(R.id.bili_detail_share);
        retryButton = findViewById(R.id.bili_detail_retry);
        episodeLabel = findViewById(R.id.bili_detail_episode_label);
        episodes = findViewById(R.id.bili_detail_episodes);
        errorView = findViewById(R.id.bili_detail_error);
        loadingView = findViewById(R.id.bili_detail_loading);

        FocusScaler.attach(playButton, 1.06f);
        FocusScaler.attach(tripleButton, 1.06f);
        FocusScaler.attach(shareButton, 1.06f);
        FocusScaler.attach(retryButton, 1.06f);
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playCurrent();
            }
        });
        tripleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triple();
            }
        });
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        // 操作和选集是一条竖线：从播放往上是选集，从选集最下面一行往下回播放
        playButton.setNextFocusRightId(R.id.bili_detail_triple);
        tripleButton.setNextFocusLeftId(R.id.bili_detail_play);
        tripleButton.setNextFocusRightId(R.id.bili_detail_share);
        shareButton.setNextFocusLeftId(R.id.bili_detail_triple);
        // GridView 本身不聚焦，nextFocusUp 指过去会落空；手动把焦点交给选集里的当前 P
        playButton.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                return event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_DPAD_UP && focusEpisode(playIndex);
            }
        });
        episodes.setAdapter(adapter);

        title.setText(TextUtils.isEmpty(bvid) ? "视频" : bvid);
        load();
    }

    @Override
    public void onEnter() {
        released = false;
        post(new Runnable() {
            @Override
            public void run() {
                playButton.requestFocus();
            }
        });
        // 从播放器退回来时人数已经变了，重新报一次
        refreshOnline();
    }

    @Override
    public boolean handleKeyDown(int keyCode) {
        // BACK 交回宿主弹栈：本页在页面栈中间，不能一次退到桌面
        return false;
    }

    @Override
    public void onRelease() {
        released = true;
        seq++;
        onlineSeq++;
    }

    private boolean gone() {
        Context context = getContext();
        return released || !(context instanceof Activity)
                || ((Activity) context).isFinishing() || ((Activity) context).isDestroyed();
    }

    // ---------------------------------------------------------------- 加载

    private void load() {
        if (TextUtils.isEmpty(bvid)) {
            return;
        }
        final int mine = ++seq;
        loading = true;
        errorView.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        loadingView.setVisibility(View.VISIBLE);
        meta.setText("正在取视频信息…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                BiliDetail result = null;
                String error = null;
                try {
                    result = BiliClient.get(getContext()).view(bvid);
                } catch (Exception e) {
                    error = TextUtils.isEmpty(e.getMessage()) ? "这条视频的信息没取到" : e.getMessage();
                }
                final BiliDetail value = result;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != seq || gone()) {
                            return;
                        }
                        loading = false;
                        loadingView.setVisibility(View.GONE);
                        if (value == null || TextUtils.isEmpty(value.title)) {
                            showError(failure);
                            return;
                        }
                        detail = value;
                        playIndex = 0;
                        bind();
                    }
                });
            }
        }).start();
    }

    private void showError(String error) {
        meta.setText("");
        errorView.setText(TextUtils.isEmpty(error) ? "这条视频的信息没取到" : error);
        errorView.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
    }

    private void bind() {
        title.setText(detail.title);
        meta.setText(buildMeta());
        desc.setText(detail.desc);
        desc.setVisibility(TextUtils.isEmpty(detail.desc) ? View.GONE : View.VISIBLE);
        // 稿件标签在 view/detail 的 Tags 里，取前两个做一枚胶囊
        String tagText = TextUtils.join(" · ", detail.tags.size() > 2
                ? detail.tags.subList(0, 2) : detail.tags);
        tag.setText(tagText);
        tag.setVisibility(TextUtils.isEmpty(tagText) ? View.GONE : View.VISIBLE);
        // 整屏铺底：参考稿里封面就是页面本身，不再缩成一张小卡
        loadImage(bg, detail.cover, 1f);

        items.clear();
        items.addAll(detail.episodes);
        boolean multi = items.size() > 1;
        crumb.setText("哔哩哔哩 / " + (multi ? "合集" : "视频"));
        episodeLabel.setVisibility(multi ? View.VISIBLE : View.GONE);
        episodes.setVisibility(multi ? View.VISIBLE : View.GONE);
        if (multi) {
            episodeLabel.setText("选集（" + items.size() + " P）");
        }
        adapter.notifyDataSetChanged();
        tripleLabel.setText(tripling ? "三连中…" : "一键三连");
        tripleButton.setEnabled(!tripling);
        tripleButton.setAlpha(tripling ? 0.6f : 1f);
        playLabel.setText(multi ? "播放 P1" : "开始播放");
        playButton.setEnabled(true);
        playButton.setAlpha(1f);
        refreshOnline();
    }

    /** 「UP · 时间 · 播放/弹幕/评论」一行说完 */
    private String buildMeta() {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(detail.up)) {
            sb.append("UP ").append(detail.up);
        }
        if (detail.pubdate > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date(detail.pubdate * 1000L)));
        }
        appendStat(sb, detail.views, "播放");
        appendStat(sb, detail.danmaku, "弹幕");
        appendStat(sb, detail.replies, "评论");
        appendStat(sb, detail.favorites, "收藏");
        if (detail.durationSec > 0) {
            sb.append(" · 全长 ").append(BiliClient.humanTime(detail.durationSec));
        }
        return sb.toString();
    }

    private static void appendStat(StringBuilder sb, long value, String label) {
        if (value <= 0) {
            return;
        }
        sb.append(sb.length() == 0 ? "" : " · ")
                .append(BiliClient.humanCount(value)).append(' ').append(label);
    }

    private void loadImage(ImageView target, String url, float alpha) {
        if (target == null) {
            return;
        }
        target.animate().alpha(alpha).setDuration(320).start();
        if (TextUtils.isEmpty(url)) {
            Glide.with(getContext()).clear(target);
            return;
        }
        try {
            Glide.with(getContext()).load(url).centerCrop().into(target);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "图片加载失败：" + url, th);
        }
    }

    // ---------------------------------------------------------------- 播放

    /**
     * TV 上没有系统分享面板，分享的实际落点就是「把链接弄到手机上」：
     * 复制到剪贴板，用户用手机端输入/扫码都能接上。
     */
    private void share() {
        if (detail == null) {
            return;
        }
        String url = "https://www.bilibili.com/video/" + detail.bvid;
        if (items.size() > 1 && playIndex >= 0 && playIndex < items.size()) {
            url += "?p=" + (playIndex + 1);
        }
        try {
            ClipboardManager cm = (ClipboardManager) getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("B 站视频链接", url));
            }
            toast("链接已复制，可在手机上打开");
        } catch (Throwable th) {
            android.util.Log.w(TAG, "复制分享链接失败", th);
            toast("复制链接失败");
        }
    }

    private void toast(String text) {
        Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 三连与在线

    /** 点赞 + 投两币 + 收藏。没登录就先弹扫码窗，登录成功后自动接着发 */
    private void triple() {
        if (loading || detail == null || tripling) {
            return;
        }
        final BiliClient client = BiliClient.get(getContext());
        if (!client.loggedIn()) {
            toast("扫码登录后会自动三连");
            if (host != null) {
                host.openBiliLogin(new Runnable() {
                    @Override
                    public void run() {
                        if (!gone()) {
                            triple();
                        }
                    }
                });
            }
            return;
        }
        tripling = true;
        tripleButton.setEnabled(false);
        tripleButton.setAlpha(0.6f);
        tripleLabel.setText("三连中…");
        final String bvid = detail.bvid;
        final long aid = detail.aid;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BiliClient.TripleResult result = client.triple(bvid, aid);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        tripling = false;
                        if (gone()) {
                            return;
                        }
                        tripleButton.setEnabled(true);
                        tripleButton.setAlpha(1f);
                        if (result.complete()) {
                            tripleLabel.setText("已三连");
                            toast("三连完成 · 点赞 / 投币 / 收藏");
                        } else {
                            tripleLabel.setText("一键三连");
                            toast(TextUtils.isEmpty(result.error)
                                    ? "三连没连全，再试一次" : "三连未完成 · " + result.error);
                        }
                    }
                });
            }
        }).start();
    }

    /** 人数是「此刻」，只报给界面不落库；换 P 或从播放器回来都重新拉一次 */
    private void refreshOnline() {
        if (detail == null || released) {
            return;
        }
        long cid = detail.cid;
        if (playIndex >= 0 && playIndex < items.size()
                && items.get(playIndex).cid > 0) {
            cid = items.get(playIndex).cid;
        }
        final long useCid = cid;
        final String bvid = detail.bvid;
        final int mine = ++onlineSeq;
        final Context appContext = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                long count = -1;
                try {
                    count = BiliClient.get(appContext).onlineCount(bvid, useCid);
                } catch (Exception ignore) {
                    // 人数拿不到不影响详情页，别弹错
                }
                final long value = count;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mine != onlineSeq || gone() || value <= 0) {
                            return;
                        }
                        onlineView.setText("● " + BiliClient.humanCount(value) + " 人在看");
                        onlineView.setVisibility(View.VISIBLE);
                    }
                });
            }
        }).start();
    }

    private void playCurrent() {
        if (loading || detail == null || items.isEmpty() || host == null) {
            return;
        }
        int index = playIndex >= 0 && playIndex < items.size() ? playIndex : 0;
        host.playBili(detail, items.get(index));
    }

    /** 把焦点送到选集里指定的一格；选集没展开时返回 false，交回系统找下一个焦点 */
    private boolean focusEpisode(final int index) {
        if (episodes.getVisibility() != View.VISIBLE || items.isEmpty()) {
            return false;
        }
        final int target = Math.max(0, Math.min(index, items.size() - 1));
        episodes.setSelection(target);
        episodes.post(new Runnable() {
            @Override
            public void run() {
                View child = episodes.getChildAt(target - episodes.getFirstVisiblePosition());
                if (child != null) {
                    child.requestFocus();
                }
            }
        });
        return true;
    }

    private final class EpisodeAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
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
            BiliEpisode episode = items.get(position);
            // 分 P 名经常是「P1 正片」这种长串，方块放不下就只报第几 P
            String label = episode.index > 0 ? "P" + episode.index : String.valueOf(position + 1);
            item.setText(label);
            // GridView 会把 setSelected 清掉，当前 P 直接用底片区分
            item.setBackgroundResource(position == playIndex
                    ? R.drawable.bg_episode_current : R.drawable.bg_episode_chip);
            FocusScaler.attach(item, 1.03f);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (position >= items.size()) {
                        return;
                    }
                    playIndex = position;
                    playLabel.setText("播放 " + label);
                    adapter.notifyDataSetChanged();
                    playCurrent();
                }
            });
            return item;
        }
    }
}

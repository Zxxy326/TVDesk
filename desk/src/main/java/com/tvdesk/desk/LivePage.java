package com.tvdesk.desk;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.tvdesk.core.live.LiveCatalog;
import com.tvdesk.core.live.LiveChannel;
import com.tvdesk.core.live.LiveRepository;
import com.tvdesk.core.ui.DeskToast;
import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.widget.TileWall;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播（IPTV）模块页。
 *
 * <h3>两态一页</h3>
 * 列表态（分组顶栏 + 频道墙）和播放态（全屏画面 + 台标信息条）在同一个页里切换，
 * 不压二级页：换台要快，一跳 Activity 就是几百毫秒的黑屏，而且回来焦点还要重建。
 *
 * <h3>数据从哪来</h3>
 * 见 {@link LiveRepository}：主源 vbskycn（IPv4/IPv6 两份清单）、兜底 Guovin。
 * 进页面先显示磁盘缓存（有过就是秒开），缓存过了 6 小时在后台静默刷新，
 * 顶栏右侧那颗「同步」可以随时强制拉一次。上游仓库更新不需要我们适配——
 * 拉到的就是最新一份，格式仍是 m3u。
 *
 * <h3>换台与换线</h3>
 * 上下键换台（同一分组内循环），左键回上一组；一个台挂多条地址时
 * 播放失败自动试下一条（{@link LiveChannel#urls}），全失败才提示。
 *
 * <h3>返回键</h3>
 * 播放态 → 回列表（停在刚才那个台）；列表态 → 交给宿主关模块页。
 * 严格一层一层退，和 {@link ModulePage} 里那张表一致。
 */
public class LivePage extends FrameLayout implements ModulePage {

    private static final String TAG = "LivePage";

    /** 频道墙列数。台标格子小，一屏放得下更多；7 列在 1080p 上每格约 140dp */
    private static final int COLUMNS = 7;
    /** 信息条停留多久自动收起 */
    private static final long OSD_HIDE_MS = 4000L;

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0 Safari/537.36";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable onExit;

    // ---- 列表态
    private LinearLayout listLayer;
    private LinearLayout groupsRow;
    private HorizontalScrollView groupsScroll;
    private TileWall wall;
    private TextView statusView;
    private TextView emptyView;
    /** 顶栏那颗「同步」 */
    private TextView syncChip;
    private LinearLayout headerRow;

    // ---- 播放态
    private FrameLayout playerLayer;
    private PlayerView playerView;
    private TextView osdTitle;
    private TextView osdSub;
    private LinearLayout osdBox;
    private ExoPlayer player;

    private LiveCatalog catalog;
    private String group = "";
    private List<LiveChannel> channels = new ArrayList<>();
    private int wallIndex;
    private boolean playing;
    private int playIndex = -1;
    /** 当前台正在试第几条地址 */
    private int urlTry;
    private boolean loading;
    private boolean released;
    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            osdBox.setVisibility(View.GONE);
        }
    };

    public LivePage(Context context, Runnable onExit) {
        super(context);
        this.onExit = onExit;
        setBackgroundColor(Color.parseColor("#0B0B0C"));
        setFocusable(false);
        buildListLayer();
        buildPlayerLayer();
        showList();
    }

    // ---------------------------------------------------------------- 界面搭建

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void buildListLayer() {
        listLayer = new LinearLayout(getContext());
        listLayer.setOrientation(LinearLayout.VERTICAL);
        listLayer.setBackgroundColor(Color.parseColor("#0B0B0C"));
        addView(listLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 顶栏：左边「直播 ▾」当回桌面的出口，中间分组，右边同步状态
        headerRow = new LinearLayout(getContext());
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(48);
        headerRow.setPadding(pad, dp(18), pad, dp(6));

        TextView exit = chip("直播 ▾", false);
        exit.setContentDescription("返回桌面");
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback();
                if (onExit != null) {
                    onExit.run();
                }
            }
        });
        headerRow.addView(exit);

        groupsScroll = new HorizontalScrollView(getContext());
        groupsScroll.setHorizontalScrollBarEnabled(false);
        groupsScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        scrollParams.leftMargin = dp(14);
        groupsScroll.setLayoutParams(scrollParams);
        groupsRow = new LinearLayout(getContext());
        groupsRow.setOrientation(LinearLayout.HORIZONTAL);
        groupsRow.setGravity(Gravity.CENTER_VERTICAL);
        groupsScroll.addView(groupsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headerRow.addView(groupsScroll);

        statusView = new TextView(getContext());
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setTextColor(0xFF8A8A90);
        statusView.setSingleLine(true);
        statusView.setPadding(dp(12), 0, dp(6), 0);
        headerRow.addView(statusView);

        syncChip = chip("⟳ 同步", false);
        syncChip.setContentDescription("立即同步直播源");
        syncChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh(true);
            }
        });
        headerRow.addView(syncChip);

        listLayer.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 频道墙
        wall = new TileWall(getContext());
        wall.setNumColumns(COLUMNS);
        wall.setSpacingDp(8f, 8f);
        wall.setAdapter(new TileWall.Adapter() {
            @Override
            public int getCount() {
                return channels.size();
            }

            @Override
            public View getView(int index, View convertView, ViewGroup parent) {
                return channelTile(index);
            }

            @Override
            public String keyAt(int index) {
                return index >= 0 && index < channels.size() ? channels.get(index).key() : "";
            }
        });
        wall.setEdgeListener(new TileWall.OnEdgeListener() {
            @Override
            public boolean onEdge(int keyCode, int index) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    // 上到顶就交给分组顶栏，落点守在「当前分组」那颗上
                    focusGroupChip(group);
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams wallParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wallParams.topMargin = dp(10);
        wallParams.leftMargin = pad;
        wallParams.rightMargin = pad;
        wallParams.bottomMargin = dp(24);
        listLayer.addView(wall, wallParams);

        emptyView = new TextView(getContext());
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        emptyView.setTextColor(0xFFBDBDC4);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        listLayer.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildPlayerLayer() {
        playerLayer = new FrameLayout(getContext());
        playerLayer.setBackgroundColor(Color.BLACK);
        playerLayer.setVisibility(View.GONE);
        addView(playerLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        playerView = new PlayerView(getContext());
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerLayer.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 信息条：台号 + 台名 + 分组，压在左下角，4 秒后自己收起来
        osdBox = new LinearLayout(getContext());
        osdBox.setOrientation(LinearLayout.VERTICAL);
        osdBox.setBackgroundColor(0xCC101014);
        osdBox.setPadding(dp(22), dp(14), dp(22), dp(14));
        // 次行可能只有分组名，先给一个最小宽度，免得「2  CCTV5+」这种宽度算早了被截
        osdBox.setMinimumWidth(dp(260));
        FrameLayout.LayoutParams osdParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        osdParams.gravity = Gravity.BOTTOM | Gravity.START;
        osdParams.leftMargin = dp(48);
        osdParams.bottomMargin = dp(42);
        osdBox.setLayoutParams(osdParams);

        osdTitle = new TextView(getContext());
        osdTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        osdTitle.setTextColor(Color.WHITE);
        osdTitle.setSingleLine(true);
        osdBox.addView(osdTitle);

        osdSub = new TextView(getContext());
        osdSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        osdSub.setTextColor(0xFFB9B9C0);
        osdSub.setSingleLine(true);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(4);
        osdBox.addView(osdSub, subParams);
        osdBox.setVisibility(View.GONE);
        playerLayer.addView(osdBox);
    }

    /** 顶栏胶囊。{@code active} 表示当前选中的分组 */
    private TextView chip(String text, boolean active) {
        TextView item = new TextView(getContext());
        item.setId(View.generateViewId());
        item.setText(text);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        item.setTextColor(AppCompatResources.getColorStateList(getContext(), R.color.nav_item_text));
        item.setBackgroundResource(R.drawable.bg_nav_item);
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        item.setFocusable(true);
        item.setClickable(true);
        item.setFocusableInTouchMode(true);
        item.setPadding(getResources().getDimensionPixelSize(R.dimen.nav_item_pad_h), 0,
                getResources().getDimensionPixelSize(R.dimen.nav_item_pad_h), 0);
        item.setSelected(active);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(2);
        item.setLayoutParams(lp);
        FocusScaler.attach(item, 1.06f);
        return item;
    }

    /** 一张频道卡：台标 + 名字。台标取不到就退回名字首字，不留黑块 */
    private View channelTile(final int index) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setFocusableInTouchMode(true);
        tile.setId(View.generateViewId());

        FrameLayout frame = new FrameLayout(getContext());
        frame.setBackgroundResource(R.drawable.bg_poster);
        frame.setPadding(dp(3), dp(3), dp(3), dp(3));
        frame.setDuplicateParentStateEnabled(true);

        final LiveChannel channel = channels.get(index);
        // 没台标时放一个首字当占位。注意别把整串台名放进来：格子下面已经有一行台名了，
        // 上面再重复一遍会看起来像「每个台出现了两次」（实测截图就是这个观感）
        final TextView initial = new TextView(getContext());
        initial.setText(TextUtils.isEmpty(channel.name) ? "台" : channel.name.substring(0, 1));
        initial.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        initial.setTextColor(0xFF6E6E76);
        initial.setGravity(Gravity.CENTER);
        frame.addView(initial, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!TextUtils.isEmpty(channel.logo)) {
            ImageView logo = new ImageView(getContext());
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setPadding(dp(12), dp(12), dp(12), dp(12));
            frame.addView(logo, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Glide.with(getContext())
                    .load(channel.logo)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .into(logo);
        }
        tile.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

        TextView name = new TextView(getContext());
        name.setText(channel.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        // 和应用卡片标题同一个档位：卡片底色偏深，纯白会晃眼
        name.setTextColor(0xFFE6E9F0);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dp(5);
        tile.addView(name, nameParams);

        FocusScaler.attach(tile, 1.05f);
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play(index);
            }
        });
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    wallIndex = index;
                }
            }
        });
        return tile;
    }


    // ---------------------------------------------------------------- 数据

    @Override
    public void onEnter() {
        LiveCatalog cache = LiveRepository.cached(getContext());
        if (cache != null) {
            apply(cache, true);
            if (!LiveRepository.fresh(cache)) {
                refresh(false);
            }
        } else {
            empty("正在同步直播源…");
            refresh(false);
        }
        // 模块页从 GONE 变回 VISIBLE 时焦点已经掉了，系统会补给它找到的第一个可聚焦控件——
        // 也就是左上那颗「直播 ▾」。进直播是要看台的，落点应该是上次那个台
        if (!channels.isEmpty()) {
            wall.post(new Runnable() {
                @Override
                public void run() {
                    wall.focusPosition(Math.max(0, Math.min(wallIndex, channels.size() - 1)));
                }
            });
        }
    }

    private void refresh(final boolean force) {
        if (loading) {
            return;
        }
        loading = true;
        updateStatus(force ? "正在同步…" : null);
        new Thread(new Runnable() {
            @Override
            public void run() {
                LiveCatalog result = null;
                String error = null;
                try {
                    result = LiveRepository.load(getContext(), force);
                } catch (Throwable th) {
                    error = th.getMessage();
                }
                final LiveCatalog value = result;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (released) {
                            return;
                        }
                        if (value == null) {
                            if (channels.isEmpty()) {
                                empty(TextUtils.isEmpty(failure)
                                        ? "直播源取不到 · 按「⟳ 同步」重试" : failure
                                        + " · 按「⟳ 同步」重试");
                            } else {
                                DeskToast.show(getContext(), "同步失败，继续用上一次的目录");
                            }
                            updateStatus(null);
                            return;
                        }
                        boolean same = catalog != null && value.updatedAt == catalog.updatedAt;
                        apply(value, true);
                        if (force || !same) {
                            DeskToast.show(getContext(), "直播源已更新 · "
                                    + value.channelCount() + " 个频道");
                        }
                    }
                });
            }
        }, "live-sync").start();
    }

    /** 目录到位：重建分组、按上次的分组恢复、刷新墙面 */
    private void apply(LiveCatalog value, boolean focusWall) {
        catalog = value;
        List<String> groups = value.groupNames();
        if (groups.isEmpty()) {
            empty("直播源里没有可用的频道");
            return;
        }
        String group0 = group;
        if (TextUtils.isEmpty(group0) || !groups.contains(group0)) {
            group0 = pickGroup(groups, DeskPrefs.liveGroup(getContext()));
        }
        group = group0;
        emptyView.setVisibility(View.GONE);
        buildGroups();
        showGroup(group, focusWall);
        updateStatus(null);
    }

    /**
     * 选默认分组：优先用户上次看的，其次「央视频道」这类最常看的，
     * 都没有就用第一个。源里的分组名并不统一（央视/央视频道/CCTV），所以按包含关系认。
     */
    private String pickGroup(List<String> groups, String remembered) {
        if (!TextUtils.isEmpty(remembered) && groups.contains(remembered)) {
            return remembered;
        }
        String[] preferred = {"央视频道", "央视", "卫视频道", "卫视", "CCTV"};
        for (String want : preferred) {
            for (String candidate : groups) {
                if (candidate.contains(want)) {
                    return candidate;
                }
            }
        }
        return groups.get(0);
    }

    private void buildGroups() {
        groupsRow.removeAllViews();
        for (final String name : catalog.groupNames()) {
            TextView item = chip(name, name.equals(group));
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showGroup(name, true);
                }
            });
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus) {
                        return;
                    }
                    groupsScroll.smoothScrollTo(Math.max(0, v.getLeft() - dp(60)), 0);
                    // 焦点落到哪个分组就切到哪个分组：遥控器上左右移一下就该看到内容，
                    // 还要再按一次「确定」才生效会让人以为没响应。和首页顶栏、应用页同一个口径。
                    // 这里不把焦点送进墙面（false），用户是在浏览分组，不是选了就走
                    if (!name.equals(group)) {
                        showGroup(name, false);
                    }
                }
            });
            groupsRow.addView(item);
        }
        // 分组之间显式连左右：和 AppPage 顶栏一个道理，几何寻焦会按距离挑，
        // 分组名长短不一时会跳格
        for (int i = 0; i < groupsRow.getChildCount(); i++) {
            View current = groupsRow.getChildAt(i);
            current.setNextFocusRightId(i + 1 < groupsRow.getChildCount()
                    ? groupsRow.getChildAt(i + 1).getId() : current.getId());
            current.setNextFocusLeftId(i > 0 ? groupsRow.getChildAt(i - 1).getId() : current.getId());
        }
    }

    /** 切分组：换墙面数据，焦点按需要送进第一张 */
    private void showGroup(String name, boolean focusWall) {
        group = name;
        channels = catalog.group(name);
        for (int i = 0; i < groupsRow.getChildCount(); i++) {
            View child = groupsRow.getChildAt(i);
            child.setSelected(i < catalog.groupNames().size()
                    && name.equals(catalog.groupNames().get(i)));
        }
        DeskPrefs.setLiveGroup(getContext(), name);
        wall.notifyDataSetChanged();
        if (channels.isEmpty()) {
            empty("这个分组没有频道");
            return;
        }
        emptyView.setVisibility(View.GONE);
        // 换分组后优先落在上次看过的那个台上（同分组内才有意义）
        int target = indexOfRemembered();
        wallIndex = Math.max(0, Math.min(target, channels.size() - 1));
        if (focusWall) {
            wall.post(new Runnable() {
                @Override
                public void run() {
                    wall.focusPosition(wallIndex);
                }
            });
        }
    }

    private int indexOfRemembered() {
        String key = DeskPrefs.liveChannel(getContext());
        if (TextUtils.isEmpty(key)) {
            return 0;
        }
        for (int i = 0; i < channels.size(); i++) {
            if (key.equals(channels.get(i).key())) {
                return i;
            }
        }
        return 0;
    }

    private void focusGroupChip(String name) {
        int index = catalog == null ? -1 : catalog.groupNames().indexOf(name);
        if (index >= 0 && index < groupsRow.getChildCount()) {
            groupsRow.getChildAt(index).requestFocus();
        } else if (groupsRow.getChildCount() > 0) {
            groupsRow.getChildAt(0).requestFocus();
        }
    }

    private void updateStatus(String override) {
        if (statusView == null) {
            return;
        }
        if (!TextUtils.isEmpty(override)) {
            statusView.setText(override);
            return;
        }
        if (catalog == null) {
            statusView.setText("");
            return;
        }
        String text = LiveRepository.ago(catalog.updatedAt) + " · " + catalog.channelCount() + " 台";
        statusView.setText(text);
    }

    private void empty(String message) {
        emptyView.setText(message);
        emptyView.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- 播放

    private void ensurePlayer() {
        if (player != null) {
            return;
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(12000);
        DefaultDataSource.Factory dataSource = new DefaultDataSource.Factory(getContext(), http);
        player = new ExoPlayer.Builder(getContext())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource))
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                onStreamError(error);
            }
        });
        playerView.setPlayer(player);
    }

    /** 点一张台卡：进播放态 */
    private void play(int index) {
        if (index < 0 || index >= channels.size()) {
            return;
        }
        playIndex = index;
        urlTry = 0;
        LiveChannel channel = channels.get(index);
        DeskPrefs.setLiveChannel(getContext(), channel.key());
        enterPlayer();
        startUrl(channel, 0);
    }

    private void enterPlayer() {
        playing = true;
        playerLayer.setVisibility(View.VISIBLE);
        listLayer.setVisibility(View.GONE);
        showOsd();
    }

    private void startUrl(LiveChannel channel, int tryIndex) {
        if (channel.urls.isEmpty()) {
            DeskToast.show(getContext(), channel.name + " 没有可用地址");
            return;
        }
        if (tryIndex >= channel.urls.size()) {
            DeskToast.show(getContext(), channel.name + " 的地址都播不了，换一个台试试");
            return;
        }
        urlTry = tryIndex;
        ensurePlayer();
        String url = channel.urls.get(tryIndex);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);
        updateOsd(channel, tryIndex);
    }

    /** 换台：在当前分组里循环 */
    private void stepChannel(int delta) {
        if (channels.isEmpty()) {
            return;
        }
        int next = playIndex + delta;
        if (next < 0) {
            next = channels.size() - 1;
        } else if (next >= channels.size()) {
            next = 0;
        }
        playIndex = next;
        urlTry = 0;
        LiveChannel channel = channels.get(next);
        DeskPrefs.setLiveChannel(getContext(), channel.key());
        startUrl(channel, 0);
        showOsd();
    }

    /**
     * 起播失败：这个台还有下一条地址就接着试。
     *
     * <p>直播源里同一个台常挂好几个 CDN，某一条 404/超时很常见，直接判「播不了」
     * 会让用户以为整个源坏了。只有全部试完才提示，而且不退列表——用户可能想接着换台。
     */
    private void onStreamError(PlaybackException error) {
        if (!playing || playIndex < 0 || playIndex >= channels.size()) {
            return;
        }
        LiveChannel channel = channels.get(playIndex);
        int next = urlTry + 1;
        android.util.Log.i(TAG, "播放失败 " + channel.name + " 第 " + (urlTry + 1) + " 条："
                + error.getErrorCodeName());
        if (next < channel.urls.size()) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    startUrl(channels.get(Math.max(0, Math.min(playIndex, channels.size() - 1))),
                            urlTry + 1);
                }
            });
        } else {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!released) {
                        DeskToast.show(getContext(), channel.name + " 暂时播不了");
                        updateOsd(channel, 0);
                    }
                }
            });
        }
    }

    private void updateOsd(LiveChannel channel, int tryIndex) {
        if (channel == null) {
            return;
        }
        int number = playIndex + 1;
        osdTitle.setText(number + "  " + channel.name);
        String line = group;
        if (channel.urls.size() > 1) {
            line += " · 线路 " + (tryIndex + 1) + "/" + channel.urls.size();
        }
        osdSub.setText(line);
        // 文字是后填的：不主动要一次布局，WRAP_CONTENT 的父容器可能还按空文本的宽度摆着
        osdBox.requestLayout();
    }

    private void showOsd() {
        osdBox.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOsd);
        ui.postDelayed(hideOsd, OSD_HIDE_MS);
    }

    /** 回列表态：停画面，焦点落回刚才那个台 */
    private void showList() {
        playing = false;
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        playerLayer.setVisibility(View.GONE);
        listLayer.setVisibility(View.VISIBLE);
        final int target = wallIndex;
        wall.post(new Runnable() {
            @Override
            public void run() {
                if (channels.size() > 0) {
                    wall.focusPosition(Math.max(0, Math.min(target, channels.size() - 1)));
                }
            }
        });
    }

    // ---------------------------------------------------------------- 模块契约

    @Override
    public boolean handleKeyDown(int keyCode) {
        if (playing) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                showList();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                stepChannel(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                stepChannel(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                // 确定 = 回到频道列表挑台，画面先停着自己
                showList();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_INFO) {
                if (osdBox.getVisibility() == View.VISIBLE) {
                    osdBox.setVisibility(View.GONE);
                } else {
                    showOsd();
                }
                return true;
            }
            return true;
        }
        // 列表态的分组顶栏左右自己算，一格一格走
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (moveChipFocus(keyCode)) {
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            View focused = findFocus();
            if (focused != null && groupsRow.indexOfChild(focused) >= 0) {
                wall.focusPosition(wallIndex);
                return true;
            }
        }
        return false;
    }

    /**
     * 分组顶栏 / 底部按钮之间的左右移动。
     *
     * <p>显式逐格：这两个容器里胶囊宽度差得大（「CCTV」和「上海地方频道」），
     * 交给几何寻焦会按中心距离挑，短名字旁边按一下可能跳过一颗。
     */
    private boolean moveChipFocus(int keyCode) {
        View focused = findFocus();
        if (focused == null) {
            return false;
        }
        LinearLayout row = null;
        if (groupsRow.indexOfChild(focused) >= 0) {
            row = groupsRow;
        } else if (headerRow.indexOfChild(focused) >= 0) {
            row = headerRow;
        }
        if (row == null) {
            return false;
        }
        int index = row.indexOfChild(focused);
        int next = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? index + 1 : index - 1;
        if (next < 0 || next >= row.getChildCount()) {
            return true;
        }
        row.getChildAt(next).requestFocus();
        return true;
    }

    @Override
    public void onLeave() {
        // 切走就停：直播是持续占带宽的，后台继续放既费流量也费解码器
        if (playing) {
            showList();
        }
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    public boolean hasActivePlayback() {
        return playing;
    }

    @Override
    public void stopPlayback() {
        if (playing) {
            showList();
        }
    }

    @Override
    public void onRelease() {
        released = true;
        ui.removeCallbacks(hideOsd);
        if (player != null) {
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
    }
}

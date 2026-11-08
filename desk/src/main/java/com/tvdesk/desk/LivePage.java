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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.tvdesk.core.live.Epg;
import com.tvdesk.core.live.EpgRepository;
import com.tvdesk.core.live.EpgSchedule;
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
 * <h3>一进来就播</h3>
 * 直播的心智是「一打开就在放」，不是「先看列表再挑」。所以进页面直接起播上次退出的那个台
 * （没有记录就播第一台），选台退居成一个<b>按菜单键调出来的左侧面板</b>——
 * 和影视播放页的「选集」是同一个交互，只是面板放在左边，右边留给画面。
 *
 * <h3>三个层次，各管一段</h3>
 * <ul>
 *   <li>播放态：上下键换台（全频道顺序，跨分组不打断），菜单/确定调面板，
 *       左右键不动（直播没有进度可拖）；</li>
 *   <li>面板态：顶部一行分类（默认「全部频道」，可横移切换），下面是该分类的频道，
 *       选中即换台并收起面板；</li>
 *   <li>返回：面板收起 → 再按一次退出模块（宿主接管）。</li>
 * </ul>
 *
 * <h3>换线与失败</h3>
 * 一个台常挂多条地址（不同 CDN），播放失败按顺序试下一条，全失败才提示。
 */
public class LivePage extends FrameLayout implements ModulePage {

    private static final String TAG = "LivePage";

    /** 「全部频道」这个假分类的键：真实分组名不会叫这个 */
    private static final String ALL = "全部频道";
    /** 面板里的频道列数：面板占屏宽 46%，3 列刚好放得下「CCTV第一剧场」这种长名 */
    private static final int PANEL_COLUMNS = 3;
    /** 信息条停留多久自动收起 */
    private static final long OSD_HIDE_MS = 4000L;
    /** 一个台全线路失败后，最多自动往后跳几个台 */
    private static final int MAX_AUTO_SKIP = 5;

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0 Safari/537.36";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable onExit;

    // ---- 播放层
    private FrameLayout playerLayer;
    private PlayerView playerView;
    private LinearLayout osdBox;
    private TextView osdTitle;
    private TextView osdSub;
    private TextView osdProgram;
    private TextView hintView;
    private ExoPlayer player;

    // ---- 频道面板（菜单键）
    private FrameLayout panelLayer;
    private LinearLayout catRow;
    private HorizontalScrollView catScroll;
    private TileWall panelWall;
    private TextView panelTitle;

    // ---- 数据
    private LiveCatalog catalog;
    /** 节目单；拿不到就是 null，界面上少一行字，不影响播放 */
    private Epg epg;
    /** 全频道顺序，上下键换台走这一份 */
    private List<LiveChannel> playlist = new ArrayList<>();
    /** 面板当前分类下的频道 */
    private List<LiveChannel> panelList = new ArrayList<>();
    private String category = ALL;
    private int playIndex = -1;
    private int panelIndex;
    private int urlTry;
    private boolean playing;
    private boolean loading;
    private boolean released;
    private boolean panelOpen;
    /** 连续「所有地址都播不了」的台数：用来决定「自动换台」还要不要继续 */
    private int failStreak;

    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            osdBox.setVisibility(View.GONE);
        }
    };

    public LivePage(Context context, Runnable onExit) {
        super(context);
        this.onExit = onExit;
        setBackgroundColor(Color.BLACK);
        setFocusable(false);
        buildPlayerLayer();
        buildPanelLayer();
    }

    // ---------------------------------------------------------------- 界面

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void buildPlayerLayer() {
        playerLayer = new FrameLayout(getContext());
        playerLayer.setBackgroundColor(Color.BLACK);
        addView(playerLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        playerView = new PlayerView(getContext());
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerLayer.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        osdBox = new LinearLayout(getContext());
        osdBox.setOrientation(LinearLayout.VERTICAL);
        osdBox.setBackgroundColor(0xCC101014);
        osdBox.setPadding(dp(22), dp(14), dp(22), dp(14));
        osdBox.setMinimumWidth(dp(300));
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

        osdProgram = new TextView(getContext());
        osdProgram.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        osdProgram.setTextColor(0xFFD8D8DE);
        osdProgram.setSingleLine(true);
        LinearLayout.LayoutParams progParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progParams.topMargin = dp(3);
        osdBox.addView(osdProgram, progParams);
        osdProgram.setVisibility(View.GONE);
        osdBox.setVisibility(View.GONE);
        playerLayer.addView(osdBox);

        // 没画面时的话术（正在同步 / 取不到源）：压在正中，起播后自动消失
        hintView = new TextView(getContext());
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hintView.setTextColor(0xFFBDBDC4);
        hintView.setGravity(Gravity.CENTER);
        playerLayer.addView(hintView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 起播后画面要能点出面板（触屏机型），遥控器走 MENU
        playerLayer.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                openPanel();
            }
        });
    }

    private void buildPanelLayer() {
        panelLayer = new FrameLayout(getContext());
        panelLayer.setVisibility(View.GONE);
        addView(panelLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 面板外压暗一层：点一下也收起（触屏），遥控器走 BACK
        View dim = new View(getContext());
        dim.setBackgroundColor(0x88000000);
        dim.setClickable(true);
        dim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanel();
            }
        });
        panelLayer.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_module_menu);
        panel.setPadding(dp(26), dp(22), dp(26), dp(18));
        // 左边一整条：占屏宽 46%，右边留出画面（能一边看一边挑）
        int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.46f);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.MATCH_PARENT);
        panelParams.gravity = Gravity.START;
        panelLayer.addView(panel, panelParams);

        panelTitle = new TextView(getContext());
        panelTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        panelTitle.setTextColor(Color.WHITE);
        panelTitle.setSingleLine(true);
        panelTitle.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(panelTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        catScroll = new HorizontalScrollView(getContext());
        catScroll.setHorizontalScrollBarEnabled(false);
        catScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(10);
        catScroll.setLayoutParams(scrollParams);
        catRow = new LinearLayout(getContext());
        catRow.setOrientation(LinearLayout.HORIZONTAL);
        catRow.setGravity(Gravity.CENTER_VERTICAL);
        catScroll.addView(catRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(catScroll);

        panelWall = new TileWall(getContext());
        panelWall.setNumColumns(PANEL_COLUMNS);
        panelWall.setSpacingDp(6f, 6f);
        panelWall.setAdapter(new TileWall.Adapter() {
            @Override
            public int getCount() {
                return panelList.size();
            }

            @Override
            public View getView(int index, View convertView, ViewGroup parent) {
                return panelTile(index);
            }

            @Override
            public String keyAt(int index) {
                return index >= 0 && index < panelList.size() ? panelList.get(index).key() : "";
            }
        });
        panelWall.setEdgeListener(new TileWall.OnEdgeListener() {
            @Override
            public boolean onEdge(int keyCode, int index) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    focusCategory(category);
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams wallParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wallParams.topMargin = dp(12);
        panel.addView(panelWall, wallParams);
    }

    /** 分类胶囊（「全部频道」+ 每个分组） */
    private TextView chip(String text, boolean active) {
        TextView item = new TextView(getContext());
        item.setId(View.generateViewId());
        item.setText(text);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
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
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lp.rightMargin = dp(2);
        item.setLayoutParams(lp);
        FocusScaler.attach(item, 1.06f);
        return item;
    }

    /**
     * 面板里的一张频道卡。
     *
     * <p>观感对齐影视播放页的选集格子：一行文字、深底、聚焦放大一点。
     * 427 个台如果都带台标，滚动和内存都不划算——台标留给画面上的信息条。
     */
    private View panelTile(final int index) {
        final LiveChannel channel = panelList.get(index);
        // 两行：台名 + 正在播的节目。节目单拿不到就只显示台名（第二行留空、高度不变，
        // 免得列表在有无 EPG 时跳来跳去）
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setId(View.generateViewId());
        tile.setBackgroundResource(R.drawable.bg_nav_item);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setFocusableInTouchMode(true);
        tile.setPadding(dp(10), dp(5), dp(10), dp(5));
        tile.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        boolean current = playIndex >= 0 && playIndex < playlist.size()
                && playlist.get(playIndex).key().equals(channel.key());
        tile.setSelected(current);
        FocusScaler.attach(tile, 1.04f);

        TextView name = new TextView(getContext());
        name.setText(channel.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(AppCompatResources.getColorStateList(getContext(), R.color.nav_item_text));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String program = nowProgramTitle(channel);
        if (program.length() > 0) {
            TextView sub = new TextView(getContext());
            sub.setText(program);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            sub.setTextColor(0xFF8E8E98);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(sub, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        tile.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                playChannel(channel);
                closePanel();
            }
        });
        tile.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    panelIndex = index;
                }
            }
        });
        return tile;
    }

    // ---------------------------------------------------------------- 数据

    @Override
    public void onEnter() {
        epg = EpgRepository.cached(getContext());
        loadEpg(false);
        LiveCatalog cache = LiveRepository.cached(getContext());
        if (cache != null) {
            apply(cache);
            if (!LiveRepository.fresh(cache)) {
                refresh(false);
            }
        } else {
            hint("正在同步直播源…");
            refresh(false);
        }
    }

    /** 节目单：缓存先用着，后台按需刷新；失败什么都不做 */
    private void loadEpg(final boolean force) {
        if (!force && EpgRepository.fresh(epg)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Epg loaded = EpgRepository.load(getContext(), force);
                if (loaded == null) {
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released) {
                            return;
                        }
                        epg = loaded;
                        // 画面上的信息条和面板里的「正在播」都要按新节目单重画一遍
                        if (playIndex >= 0 && playIndex < playlist.size()) {
                            updateOsd(playlist.get(playIndex), urlTry);
                        }
                        if (panelOpen) {
                            panelWall.notifyDataSetChanged();
                        }
                    }
                });
            }
        }, "live-epg").start();
    }

    private void refresh(final boolean force) {
        if (loading) {
            return;
        }
        loading = true;
        if (playlist.isEmpty()) {
            hint("正在同步直播源…");
        }
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
                            if (playlist.isEmpty()) {
                                hint(TextUtils.isEmpty(failure) ? "直播源取不到 · 按菜单键重试"
                                        : failure + " · 按菜单键重试");
                            } else if (force) {
                                DeskToast.show(getContext(), "同步失败，继续用上一次的目录");
                            }
                            return;
                        }
                        boolean same = catalog != null && value.updatedAt == catalog.updatedAt;
                        apply(value);
                        if (force && !same) {
                            DeskToast.show(getContext(), "直播源已更新 · "
                                    + value.channelCount() + " 个频道");
                        }
                    }
                });
            }
        }, "live-sync").start();
    }

    /**
     * 目录到位：摊平成「全部频道」，再按上次看的台起播。
     *
     * <p>只在「还没在播」时起播：后台同步换了一份目录，不能把用户正在看的台打断。
     */
    private void apply(LiveCatalog value) {
        catalog = value;
        String keep = currentKey();
        playlist = LiveRepository.flatten(value);
        if (playlist.isEmpty()) {
            hint("直播源里没有可用的频道");
            return;
        }
        if (!playing) {
            int target = indexOf(TextUtils.isEmpty(keep)
                    ? DeskPrefs.liveChannel(getContext()) : keep);
            if (target < 0) {
                target = 0;
            }
            playIndex = target;
            startChannel(playlist.get(target), 0);
        }
        hint(null);
    }

    /** 当前正在播的台名归一化键；没在播返回空 */
    private String currentKey() {
        if (playIndex >= 0 && playIndex < playlist.size()) {
            return playlist.get(playIndex).key();
        }
        return "";
    }

    private int indexOf(String key) {
        if (TextUtils.isEmpty(key)) {
            return -1;
        }
        for (int i = 0; i < playlist.size(); i++) {
            if (key.equals(playlist.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    private void hint(String message) {
        if (TextUtils.isEmpty(message)) {
            hintView.setVisibility(View.GONE);
            return;
        }
        hintView.setText(message);
        hintView.setVisibility(View.VISIBLE);
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

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    // 真的出画面了才算「这个台可用」，把连败计数清掉
                    failStreak = 0;
                } else if (state == Player.STATE_ENDED) {
                    // 直播源里不少是有限时长的 m3u8（循环宣传片、录播），放完给的是 ENDED
                    // 而不是错误。不接这一条的话画面就停在最后一帧不动了
                    onStreamEnded();
                }
            }
        });
        playerView.setPlayer(player);
    }

    /** 选中一个台：换过去（面板由调用方决定要不要收） */
    private void playChannel(LiveChannel channel) {
        if (channel == null) {
            return;
        }
        int index = indexOf(channel.key());
        if (index < 0) {
            playlist.add(channel);
            index = playlist.size() - 1;
        }
        playIndex = index;
        startChannel(channel, 0);
    }

    private void startChannel(LiveChannel channel, int tryIndex) {
        if (channel == null) {
            return;
        }
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
        playing = true;
        hint(null);
        player.setMediaItem(MediaItem.fromUri(channel.urls.get(tryIndex)));
        player.prepare();
        player.setPlayWhenReady(true);
        DeskPrefs.setLiveChannel(getContext(), channel.key());
        updateOsd(channel, tryIndex);
        showOsd();
    }

    /** 换台：全频道顺序循环 */
    private void stepChannel(int delta) {
        if (playlist.isEmpty()) {
            return;
        }
        int next = playIndex + delta;
        if (next < 0) {
            next = playlist.size() - 1;
        } else if (next >= playlist.size()) {
            next = 0;
        }
        playIndex = next;
        startChannel(playlist.get(next), 0);
    }

    /**
     * 起播失败：这个台还有下一条地址就接着试。
     *
     * <p>直播源里同一个台常挂好几个 CDN，某一条 404/超时很常见，直接判「播不了」
     * 会让用户以为整个源坏了。只有全部试完才提示。
     */
    private void onStreamError(PlaybackException error) {
        if (playIndex < 0 || playIndex >= playlist.size()) {
            return;
        }
        // 直播窗口滑走了（暂停久了、切台回来、缓冲太慢）：这不是「地址坏了」，
        // 重新对齐到当前窗口就能继续放。当成失败处理会把好好的台换掉
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
            android.util.Log.i(TAG, "直播窗口已滑走，重新对齐：" + playlist.get(playIndex).name);
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        android.util.Log.i(TAG, "播放失败 " + playlist.get(playIndex).name
                + " 第 " + (urlTry + 1) + " 条：" + error.getErrorCodeName());
        advanceAfterFailure();
    }

    /** 流自己放完了（有限时长的 m3u8）：同样按「这条地址不行」处理 */
    private void onStreamEnded() {
        if (playIndex < 0 || playIndex >= playlist.size()) {
            return;
        }
        android.util.Log.i(TAG, "播放结束 " + playlist.get(playIndex).name
                + " 第 " + (urlTry + 1) + " 条，换下一路");
        advanceAfterFailure();
    }

    /**
     * 当前台这条路不行：先换同台的下一条地址，全都不行再自动往后跳几个台。
     */
    private void advanceAfterFailure() {
        if (released) {
            return;
        }
        final LiveChannel channel = playlist.get(playIndex);
        final int next = urlTry + 1;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (next < channel.urls.size()) {
                    startChannel(channel, next);
                    return;
                }
                // 所有地址都不行。源里的台会失效，用户记住的那一台隔几天挂掉很常见，
                // 所以先自动往后跳几台试试；连跳几个都不行才认为是大面积故障，停下来提示。
                failStreak++;
                if (panelOpen) {
                    // 面板正开着（用户在挑台），别用整屏大字打断；换个轻提示就够
                    DeskToast.show(getContext(), "「" + channel.name + "」播不了");
                    return;
                }
                if (failStreak <= MAX_AUTO_SKIP) {
                    hint("「" + channel.name + "」播不了，自动换台…");
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!released && !panelOpen) {
                                stepChannel(1);
                            }
                        }
                    }, 900L);
                } else {
                    hint("多个频道都播不了 · 按菜单键换个台，或同步直播源");
                }
            }
        });
    }

    private void updateOsd(LiveChannel channel, int tryIndex) {
        if (channel == null) {
            return;
        }
        osdTitle.setText((playIndex + 1) + "  " + channel.name);
        String line = channel.group;
        if (channel.urls.size() > 1) {
            line += " · 线路 " + (tryIndex + 1) + "/" + channel.urls.size();
        }
        osdSub.setText(line);
        // 第三行：节目单。公共 EPG 常常滞后，此刻没有节目覆盖就不显示，
        // 而不是把上一条硬挂在这儿（那会让人以为电视播的是别的节目）
        EpgSchedule.Program now = nowProgram(channel);
        if (now != null) {
            osdProgram.setText(clock(now.startMs) + "-" + clock(now.stopMs) + "  " + now.title);
            osdProgram.setVisibility(View.VISIBLE);
        } else {
            osdProgram.setVisibility(View.GONE);
        }
        osdBox.requestLayout();
    }

    /** 这个频道此刻在播的节目；没有节目单就 null */
    private EpgSchedule.Program nowProgram(LiveChannel channel) {
        if (epg == null) {
            return null;
        }
        EpgSchedule schedule = epg.scheduleOf(channel);
        return schedule == null ? null : schedule.now(System.currentTimeMillis());
    }

    private String nowProgramTitle(LiveChannel channel) {
        EpgSchedule.Program now = nowProgram(channel);
        return now == null ? "" : now.title;
    }

    private static String clock(long timeMs) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(timeMs);
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = calendar.get(java.util.Calendar.MINUTE);
        return (hour < 10 ? "0" : "") + hour + ":" + (minute < 10 ? "0" : "") + minute;
    }

    private void showOsd() {
        osdBox.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOsd);
        ui.postDelayed(hideOsd, OSD_HIDE_MS);
    }

    // ---------------------------------------------------------------- 频道面板

    private void openPanel() {
        if (panelOpen) {
            return;
        }
        if (playlist.isEmpty()) {
            // 还没有数据：菜单键当「重试」用，比让用户退出去再进来强
            refresh(true);
            return;
        }
        panelOpen = true;
        panelLayer.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOsd);
        osdBox.setVisibility(View.GONE);
        hint(null);
        panelTitle.setText("频道 · 共 " + playlist.size() + " 台");
        buildCategories();
        // 焦点先落在分类行：菜单键是「我要换台」，第一步就是选类别；
        // 想看当前台在哪一行，按一下「下」就进列表（列表里那台已经是选中态）
        showCategory(category, false);
        focusCategory(category);
    }

    private void closePanel() {
        if (!panelOpen) {
            return;
        }
        panelOpen = false;
        panelLayer.setVisibility(View.GONE);
        showOsd();
    }

    private List<String> categoryNames() {
        List<String> names = new ArrayList<>();
        names.add(ALL);
        if (catalog != null) {
            names.addAll(catalog.groupNames());
        }
        return names;
    }

    private void buildCategories() {
        catRow.removeAllViews();
        for (final String name : categoryNames()) {
            TextView item = chip(name, name.equals(category));
            item.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showCategory(name, true);
                }
            });
            item.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus) {
                        return;
                    }
                    catScroll.smoothScrollTo(Math.max(0, v.getLeft() - dp(40)), 0);
                    // 焦点落到哪个分类就切哪个分类，遥控器上移一下就能看到内容
                    if (!name.equals(category)) {
                        showCategory(name, false);
                    }
                }
            });
            catRow.addView(item);
        }
        // 分类之间显式连左右：名字长短不一时几何寻焦会跳格
        for (int i = 0; i < catRow.getChildCount(); i++) {
            View current = catRow.getChildAt(i);
            current.setNextFocusRightId(i + 1 < catRow.getChildCount()
                    ? catRow.getChildAt(i + 1).getId() : current.getId());
            current.setNextFocusLeftId(i > 0 ? catRow.getChildAt(i - 1).getId() : current.getId());
        }
    }

    /** 切分类：换面板里的列表；{@code focusWall} 为真时把焦点送进频道区 */
    private void showCategory(String name, boolean focusWall) {
        category = name;
        if (catalog != null && !ALL.equals(name)) {
            panelList = catalog.group(name);
        } else {
            panelList = playlist;
        }
        List<String> names = categoryNames();
        for (int i = 0; i < catRow.getChildCount() && i < names.size(); i++) {
            catRow.getChildAt(i).setSelected(names.get(i).equals(name));
        }
        panelWall.notifyDataSetChanged();
        // 落点优先给「正在播的那台」，一打开面板就停在当前台上
        int target = 0;
        if (playIndex >= 0 && playIndex < playlist.size()) {
            String key = playlist.get(playIndex).key();
            for (int i = 0; i < panelList.size(); i++) {
                if (key.equals(panelList.get(i).key())) {
                    target = i;
                    break;
                }
            }
        }
        panelIndex = Math.max(0, Math.min(target, Math.max(0, panelList.size() - 1)));
        if (focusWall) {
            final int index = panelIndex;
            panelWall.post(new Runnable() {
                @Override
                public void run() {
                    panelWall.focusPosition(index);
                }
            });
        }
    }

    private void focusCategory(String name) {
        int index = categoryNames().indexOf(name);
        if (index >= 0 && index < catRow.getChildCount()) {
            catRow.getChildAt(index).requestFocus();
        } else if (catRow.getChildCount() > 0) {
            catRow.getChildAt(0).requestFocus();
        }
    }

    /** 分类行里的左右逐格移动（面板打开时生效） */
    private boolean moveCategoryFocus(int keyCode) {
        View focused = findFocus();
        if (focused == null || catRow.indexOfChild(focused) < 0) {
            return false;
        }
        int index = catRow.indexOfChild(focused);
        int next = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? index + 1 : index - 1;
        if (next < 0 || next >= catRow.getChildCount()) {
            return true;
        }
        catRow.getChildAt(next).requestFocus();
        return true;
    }

    // ---------------------------------------------------------------- 模块契约

    @Override
    public boolean handleKeyDown(int keyCode) {
        if (panelOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
                closePanel();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return moveCategoryFocus(keyCode);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                View focused = findFocus();
                if (focused != null && catRow.indexOfChild(focused) >= 0) {
                    panelWall.focusPosition(panelIndex);
                    return true;
                }
            }
            return false;
        }
        // 播放态
        if (keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER) {
            openPanel();
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
        if (keyCode == KeyEvent.KEYCODE_INFO) {
            if (osdBox.getVisibility() == View.VISIBLE) {
                osdBox.setVisibility(View.GONE);
            } else {
                showOsd();
            }
            return true;
        }
        return false;
    }

    @Override
    public void onLeave() {
        closePanel();
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        playing = false;
    }

    /**
     * 直播没有「后台继续放」的意义：退出模块就该停。
     *
     * <p>返回 true 会让宿主弹「保持播放吗」，对直播是多余的一问——
     * 用户按返回就是想回桌面，不是想把电视声留在后台。
     */
    @Override
    public boolean hasActivePlayback() {
        return false;
    }

    @Override
    public void stopPlayback() {
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        playing = false;
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

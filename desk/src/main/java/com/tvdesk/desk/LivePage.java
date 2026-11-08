package com.tvdesk.desk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
import com.google.android.exoplayer2.video.VideoSize;
import com.tvdesk.core.live.Epg;
import com.tvdesk.core.live.EpgApi;
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
 * 直播的心智是「一打开就在放」，不是「先看列表再挑」。进页面直接起播上次退出的那个台
 * （没有记录就播第一台），选台退居成一个<b>按菜单键调出来的左侧面板</b>。
 *
 * <h3>面板：两列竖排</h3>
 * 左列是分类（全部频道 + 各分组），右列是该分类下的频道。顶栏那种「横着一长条分类」
 * 在窄面板里放不下，也不符合「先选类别、再选台」的顺序。整个面板只占屏宽约四分之一，
 * 半透明压在画面上——挑台时还能看到正在播什么。
 *
 * <h3>清晰度</h3>
 * 源按画质分层（见 {@code LiveSources}）：IPv6 单播优先，公网 IPv4 低码率殿后。
 * 一个台常挂多条地址，播放时按顺序试，播不动自动换下一条。
 *
 * <h3>节目单</h3>
 * 两层：整份 XMLTV（覆盖面板里所有台，可能滞后）+ 按台查询接口（只查正在看的这一台，
 * 更容易是当天的）。界面优先用按台查到的；查不到、或只有占位文案，就退回整份的。
 */
public class LivePage extends FrameLayout implements ModulePage {

    private static final String TAG = "LivePage";

    /** 「全部频道」这个假分类的键：真实分组名不会叫这个 */
    private static final String ALL = "全部频道";
    /** 信息条停留多久自动收起 */
    private static final long OSD_HIDE_MS = 4000L;
    /** 一个台全线路失败后，最多自动往后跳几个台 */
    private static final int MAX_AUTO_SKIP = 5;
    /**
     * 一个台最多试几条地址。
     *
     * <p>有的源给同一个台挂 8 条线路，全挂的时候一条条试下来要一分多钟，
     * 用户看到的就是「卡住不动」。试到 3 条还不通，基本可以认定这个台不行了。
     */
    private static final int MAX_URL_TRIES = 5;
    /** 面板占屏宽的比例。原来 46% 太占画面，砍到一半左右 */
    private static final float PANEL_WIDTH_RATIO = 0.24f;

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0 Safari/537.36";

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- 播放层
    private PlayerView playerView;
    private LinearLayout osdBox;
    private TextView osdTitle;
    private TextView osdSub;
    private TextView osdProgram;
    private TextView hintView;
    private ExoPlayer player;

    // ---- 频道面板（菜单键）
    private FrameLayout panelLayer;
    private TileWall catWall;
    private TileWall channelWall;
    private TextView panelTitle;

    // ---- 数据
    private LiveCatalog catalog;
    private Epg epg;
    /** 全频道顺序，上下键换台走这一份 */
    private List<LiveChannel> playlist = new ArrayList<>();
    /** 面板当前分类下的频道 */
    private List<LiveChannel> panelList = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private String category = ALL;
    private int playIndex = -1;
    private int catIndex;
    private int chIndex;
    private int urlTry;
    private int videoWidth;
    private int videoHeight;
    private boolean playing;
    private boolean loading;
    private boolean released;
    private boolean panelOpen;
    /** 连续「所有地址都播不了」的台数：决定自动换台还要不要继续 */
    private int failStreak;
    /**
     * 本次会话里播不动的地址。
     *
     * <p>源里同一批地址会反复出现（同一个台挂好几条、不同台共用 CDN），
     * 记住之后换台就不用再等一次超时。只记内存、不做持久化——网络环境变了
     * 可能就好了，下次开应用重新试。
     */
    private final java.util.Set<String> deadUrls = new java.util.LinkedHashSet<>();
    /**
     * 本次会话里「连挂三次」的主机。
     *
     * <p>源里同一台 CDN 常常同时服务几十个台，某台 CDN 挂了之后，如果只按地址记，
     * 每个台都要各自挂一次才学会。按主机记能一次性跳过整批。
     */
    private final java.util.Set<String> badHosts = new java.util.LinkedHashSet<>();
    private final java.util.Map<String, Integer> hostFails = new java.util.HashMap<>();
    /** 当前台的尝试顺序（下标列表，第一条是上次成功的那条） */
    private final List<Integer> urlOrder = new ArrayList<>();
    /** 当前台试到第几次 */
    private int attempt;

    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            osdBox.setVisibility(View.GONE);
        }
    };

    public LivePage(Context context, Runnable onExit) {
        super(context);
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
        FrameLayout playerLayer = new FrameLayout(getContext());
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

        // 起播后点画面也能调出面板（触屏机型），遥控器走 MENU
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

        // 压暗层比原来淡：面板本身就半透明，再压太黑会把画面全盖住
        View dim = new View(getContext());
        dim.setBackgroundColor(0x4D000000);
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
        // 半透明底 + 圆角：面板窄了之后，不透明会像一块黑板贴在画面左边
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99101014);
        bg.setCornerRadius(dp(16));
        panel.setBackground(bg);
        panel.setPadding(dp(9), dp(12), dp(7), dp(16));
        int width = (int) (getResources().getDisplayMetrics().widthPixels * PANEL_WIDTH_RATIO);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.MATCH_PARENT);
        panelParams.gravity = Gravity.START;
        panelParams.topMargin = dp(10);
        panelParams.bottomMargin = dp(10);
        panelParams.leftMargin = dp(8);
        panelLayer.addView(panel, panelParams);

        panelTitle = new TextView(getContext());
        panelTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        panelTitle.setTextColor(0xFF9A9AA4);
        panelTitle.setSingleLine(true);
        panelTitle.setEllipsize(TextUtils.TruncateAt.END);
        panelTitle.setPadding(dp(8), 0, dp(8), dp(6));
        panel.addView(panelTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));

        // 两列：左分类、右频道
        FrameLayout columnsWrap = new FrameLayout(getContext());
        panel.addView(columnsWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout columns = new LinearLayout(getContext());
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columnsWrap.addView(columns, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        catWall = new TileWall(getContext());
        catWall.setNumColumns(1);
        catWall.setSpacingDp(4f, 4f);
        catWall.setAdapter(new TileWall.Adapter() {
            @Override
            public int getCount() {
                return categories.size();
            }

            @Override
            public View getView(int index, View convertView, ViewGroup parent) {
                return categoryChip(index);
            }

            @Override
            public String keyAt(int index) {
                return index >= 0 && index < categories.size() ? categories.get(index) : "";
            }
        });
        catWall.setEdgeListener(new TileWall.OnEdgeListener() {
            @Override
            public boolean onEdge(int keyCode, int index) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // 分类往右就是频道列，落点是「当前台」而不是第一行
                    focusChannel();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams catParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.34f);
        columns.addView(catWall, catParams);

        channelWall = new TileWall(getContext());
        channelWall.setNumColumns(1);
        channelWall.setSpacingDp(4f, 4f);
        channelWall.setAdapter(new TileWall.Adapter() {
            @Override
            public int getCount() {
                return panelList.size();
            }

            @Override
            public View getView(int index, View convertView, ViewGroup parent) {
                return channelTile(index);
            }

            @Override
            public String keyAt(int index) {
                return index >= 0 && index < panelList.size() ? panelList.get(index).key() : "";
            }
        });
        channelWall.setEdgeListener(new TileWall.OnEdgeListener() {
            @Override
            public boolean onEdge(int keyCode, int index) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    catWall.focusPosition(catIndex);
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams chParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.66f);
        chParams.leftMargin = dp(4);
        columns.addView(channelWall, chParams);

        // 底部渐隐：列表滚到一半时，最后一行会被切成半个字，看起来像 bug。
        // 盖一层「透明 → 面板底色」的渐变，把裁切线化掉，同时暗示下面还有内容。
        View fade = new View(getContext());
        GradientDrawable fadeBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00101014, 0xE6101014});
        fade.setBackground(fadeBg);
        fade.setFocusable(false);
        fade.setClickable(false);
        FrameLayout.LayoutParams fadeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        fadeParams.gravity = Gravity.BOTTOM;
        columnsWrap.addView(fade, fadeParams);
    }

    /** 左列：分类。选中态显示当前所在分类 */
    private View categoryChip(final int index) {
        final String name = categories.get(index);
        TextView chip = new TextView(getContext());
        chip.setId(View.generateViewId());
        chip.setText(name);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        chip.setTextColor(AppCompatResources.getColorStateList(getContext(), R.color.nav_item_text));
        chip.setBackgroundResource(R.drawable.bg_nav_item);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setFocusableInTouchMode(true);
        chip.setPadding(dp(8), 0, dp(4), 0);
        chip.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        chip.setSelected(name.equals(category));
        chip.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    return;
                }
                catIndex = index;
                if (!name.equals(category)) {
                    showCategory(name);
                }
            }
        });
        chip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showCategory(name);
            }
        });
        return chip;
    }

    /** 右列：频道。两行（台名 + 正在播） */
    private View channelTile(final int index) {
        final LiveChannel channel = panelList.get(index);
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setId(View.generateViewId());
        tile.setBackgroundResource(R.drawable.bg_nav_item);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setFocusableInTouchMode(true);
        tile.setPadding(dp(8), dp(4), dp(6), dp(4));
        tile.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        boolean current = playIndex >= 0 && playIndex < playlist.size()
                && playlist.get(playIndex).key().equals(channel.key());
        tile.setSelected(current);

        TextView name = new TextView(getContext());
        name.setText(channel.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTextColor(AppCompatResources.getColorStateList(getContext(), R.color.nav_item_text));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String program = nowProgramTitle(channel);
        if (program.length() > 0) {
            TextView sub = new TextView(getContext());
            sub.setText(program);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
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
                    chIndex = index;
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

    /**
     * 按台查节目单：只查正在看的这一台。
     *
     * <p>整份 XMLTV 覆盖全但可能滞后，按台查更容易是当天的；两者都有时优先按台的。
     */
    private void loadEpgApiIfNeeded() {
        if (playIndex < 0 || playIndex >= playlist.size()) {
            return;
        }
        final LiveChannel channel = playlist.get(playIndex);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final EpgSchedule schedule = EpgApi.fetch(channel);
                if (schedule == null || schedule.items.isEmpty()) {
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released) {
                            return;
                        }
                        apiSchedule = schedule;
                        apiScheduleKey = channel.key();
                        if (playIndex >= 0 && playIndex < playlist.size()) {
                            updateOsd(playlist.get(playIndex), urlTry);
                        }
                        if (panelOpen) {
                            channelWall.notifyDataSetChanged();
                        }
                    }
                });
            }
        }, "live-epg-api").start();
    }

    /** 按台查到的节目单（只对应这一台） */
    private EpgSchedule apiSchedule;
    private String apiScheduleKey = "";

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
                        if (playIndex >= 0 && playIndex < playlist.size()) {
                            updateOsd(playlist.get(playIndex), urlTry);
                        }
                        if (panelOpen) {
                            channelWall.notifyDataSetChanged();
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
                // 直播换台等不起：连不上 5 秒就换下一条线路
                .setConnectTimeoutMs(5000)
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
                if (state == Player.STATE_ENDED) {
                    onStreamEnded();
                } else if (state == Player.STATE_READY) {
                    // 真的出画面了才算这个台可用
                    failStreak = 0;
                    // 记住这条线路：下次换到这台直接从它开始，不再从第 1 条试
                    if (playIndex >= 0 && playIndex < playlist.size()) {
                        LiveChannel channel = playlist.get(playIndex);
                        if (urlTry >= 0 && urlTry < channel.urls.size()) {
                            DeskPrefs.setLivePick(getContext(), channel.key(),
                                    channel.urls.get(urlTry));
                        }
                    }
                    // 按台查节目单放在这里：跳台失败时不必为每个失败的台各打一次接口
                    loadEpgApiIfNeeded();
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                videoWidth = size.width;
                videoHeight = size.height;
                if (playIndex >= 0 && playIndex < playlist.size()) {
                    updateOsd(playlist.get(playIndex), urlTry);
                }
            }
        });
        playerView.setPlayer(player);
    }

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
        apiSchedule = null;
        apiScheduleKey = "";
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
        // 第 0 次尝试时排一次顺序：上次播成功的那条放最前，
        // 其余按源里的原顺序，本次会话已确认播不动的直接跳过
        if (tryIndex == 0) {
            urlOrder.clear();
            String pick = DeskPrefs.livePick(getContext(), channel.key());
            if (pick.length() > 0) {
                int found = channel.urls.indexOf(pick);
                if (found >= 0 && !deadUrls.contains(pick)) {
                    urlOrder.add(found);
                }
            }
            for (int i = 0; i < channel.urls.size(); i++) {
                String url = channel.urls.get(i);
                if (urlOrder.contains(i) || deadUrls.contains(url)
                        || badHosts.contains(hostOf(url))) {
                    continue;
                }
                urlOrder.add(i);
            }
            if (urlOrder.isEmpty()) {
                // 全是坏主机：清一次名单，总得试一条，不然这个台永远播不了
                badHosts.clear();
                hostFails.clear();
                for (int i = 0; i < channel.urls.size(); i++) {
                    urlOrder.add(i);
                }
            }
        }
        attempt = tryIndex;
        if (tryIndex >= urlOrder.size() || tryIndex >= MAX_URL_TRIES) {
            // 这个台在这个网络上没得试了：交给上层自动换台
            onChannelExhausted(channel);
            return;
        }
        tryIndex = urlOrder.get(tryIndex);
        urlTry = tryIndex;
        playing = true;
        hint(null);
        DeskPrefs.setLiveChannel(getContext(), channel.key());
        updateOsd(channel, tryIndex);
        showOsd();
        // 直接交给播放器，不做「先探后播」：
        // 试过一轮，不少台用 HTTP 探活是失败的（要 Referer、只认特定 Range、或者
        // 首发响应很慢），但 ExoPlayer 反而能放起来。多这一层只会把能放的台判死。
        playNow(channel.urls.get(tryIndex));
    }

    /** 探测通过，真正交给播放器 */
    private void playNow(String url) {
        ensurePlayer();
        hint(null);
        videoWidth = 0;
        videoHeight = 0;
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);
        if (playIndex >= 0 && playIndex < playlist.size()) {
            updateOsd(playlist.get(playIndex), urlTry);
        }
    }



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
        apiSchedule = null;
        apiScheduleKey = "";
        startChannel(playlist.get(next), 0);
    }

    private void onStreamError(PlaybackException error) {
        if (playIndex < 0 || playIndex >= playlist.size()) {
            return;
        }
        // 直播窗口滑走了（暂停久了、缓冲太慢）：重新对齐就能继续，不是地址坏了
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
            android.util.Log.i(TAG, "直播窗口已滑走，重新对齐：" + playlist.get(playIndex).name);
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        android.util.Log.i(TAG, "播放失败 " + playlist.get(playIndex).name
                + " 第 " + (urlTry + 1) + "/" + playlist.get(playIndex).urls.size()
                + " 条 " + hostOf(playlist.get(playIndex).urls.get(urlTry))
                + "：" + error.getErrorCodeName());
        rememberDead(playlist.get(playIndex), urlTry);
        advanceAfterFailure();
    }

    /** 流自己放完了（有限时长的 m3u8）：同样按「这条地址不行」处理 */
    private void onStreamEnded() {
        if (playIndex < 0 || playIndex >= playlist.size()) {
            return;
        }
        android.util.Log.i(TAG, "播放结束 " + playlist.get(playIndex).name
                + " 第 " + (urlTry + 1) + " 条，换下一路");
        rememberDead(playlist.get(playIndex), urlTry);
        advanceAfterFailure();
    }

    /** 日志里只留主机名：地址常带签名，打全了没意义 */
    private static String hostOf(String url) {
        try {
            java.net.URL parsed = new java.net.URL(url);
            return parsed.getHost();
        } catch (Throwable th) {
            return "?";
        }
    }

    private void rememberDead(LiveChannel channel, int index) {
        if (channel == null || index < 0 || index >= channel.urls.size()) {
            return;
        }
        String url = channel.urls.get(index);
        deadUrls.add(url);
        String host = hostOf(url);
        Integer fails = hostFails.get(host);
        int count = (fails == null ? 0 : fails) + 1;
        hostFails.put(host, count);
        if (count >= 3) {
            badHosts.add(host);
            android.util.Log.i(TAG, "主机连续失败 " + count + " 次，本次会话内跳过：" + host);
        }
        // 上限兜一下：一次会话记住几百条足够，别无限涨
        while (deadUrls.size() > 400) {
            java.util.Iterator<String> it = deadUrls.iterator();
            it.next();
            it.remove();
        }
    }

    /** 这个台没有可试的地址了：面板开着就提示，否则交给自动跳台 */
    private void onChannelExhausted(LiveChannel channel) {
        if (panelOpen) {
            DeskToast.show(getContext(), "「" + channel.name + "」播不了");
            return;
        }
        failStreak++;
        if (failStreak <= MAX_AUTO_SKIP) {
            hint("「" + channel.name + "」播不了，自动换台…");
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!released && !panelOpen) {
                        stepChannel(1);
                    }
                }
            }, 600L);
        } else {
            hint("多个频道都播不了 · 按菜单键换个台，或同步直播源");
        }
    }

    private void advanceAfterFailure() {
        if (released) {
            return;
        }
        final LiveChannel channel = playlist.get(playIndex);
        final int next = attempt + 1;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (next < channel.urls.size()) {
                    startChannel(channel, next);
                    return;
                }
                if (panelOpen) {
                    DeskToast.show(getContext(), "「" + channel.name + "」播不了");
                    return;
                }
                if (failStreak < MAX_AUTO_SKIP) {
                    failStreak++;
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
        StringBuilder line = new StringBuilder(channel.group);
        if (videoWidth > 0 && videoHeight > 0) {
            // 画面实际分辨率比「台名里写着 HD」可信：低码率源常把 720p 标成 1080p
            line.append(" · ").append(quality());
        }
        if (channel.urls.size() > 1) {
            line.append(" · 线路 ").append(tryIndex + 1).append('/').append(channel.urls.size());
        }
        osdSub.setText(line);
        EpgSchedule.Program now = nowProgram(channel);
        if (now != null) {
            osdProgram.setText(clock(now.startMs) + "-" + clock(now.stopMs) + "  " + now.title);
            osdProgram.setVisibility(View.VISIBLE);
        } else {
            osdProgram.setVisibility(View.GONE);
        }
        osdBox.requestLayout();
    }

    /** 按短边认档位：竖屏/横屏都适用，1920x1080 与 1080x1920 都是 1080P */
    private String quality() {
        int shortSide = Math.min(videoWidth, videoHeight);
        if (shortSide >= 2160) {
            return "4K";
        }
        if (shortSide >= 1080) {
            return "1080P";
        }
        if (shortSide >= 720) {
            return "720P";
        }
        if (shortSide >= 576) {
            return "576P";
        }
        return shortSide + "P";
    }

    private EpgSchedule.Program nowProgram(LiveChannel channel) {
        long at = System.currentTimeMillis();
        // 按台查到的优先（更容易是当天的）
        if (apiSchedule != null && channel.key().equals(apiScheduleKey)) {
            EpgSchedule.Program now = apiSchedule.now(at);
            if (now != null) {
                return now;
            }
        }
        if (epg == null) {
            return null;
        }
        EpgSchedule schedule = epg.scheduleOf(channel);
        return schedule == null ? null : schedule.now(at);
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
            refresh(true);
            return;
        }
        panelOpen = true;
        panelLayer.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOsd);
        osdBox.setVisibility(View.GONE);
        hint(null);
        panelTitle.setText("共 " + playlist.size() + " 台");
        buildCategories();
        showCategory(category);
        // 焦点先落在分类列：菜单键是「要换台」，第一步是选类别
        catWall.post(new Runnable() {
            @Override
            public void run() {
                catWall.focusPosition(Math.max(0, categories.indexOf(category)));
            }
        });
    }

    private void closePanel() {
        if (!panelOpen) {
            return;
        }
        panelOpen = false;
        panelLayer.setVisibility(View.GONE);
        showOsd();
    }

    private void buildCategories() {
        categories.clear();
        categories.add(ALL);
        if (catalog != null) {
            categories.addAll(catalog.rankedGroupNames());
        }
        if (!categories.contains(category)) {
            category = ALL;
        }
        catWall.notifyDataSetChanged();
    }

    private void showCategory(String name) {
        category = name;
        if (catalog != null && !ALL.equals(name)) {
            panelList = catalog.group(name);
        } else {
            panelList = playlist;
        }
        catWall.notifyDataSetChanged();
        channelWall.notifyDataSetChanged();
        // 落点优先给「正在播的那台」
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
        chIndex = Math.max(0, Math.min(target, Math.max(0, panelList.size() - 1)));
    }

    /** 从分类列跳到频道列，落在当前台上 */
    private void focusChannel() {
        final int index = chIndex;
        channelWall.post(new Runnable() {
            @Override
            public void run() {
                channelWall.focusPosition(index);
            }
        });
    }

    // ---------------------------------------------------------------- 模块契约

    @Override
    public boolean handleKeyDown(int keyCode) {
        if (panelOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
                closePanel();
                return true;
            }
            // 左右跨界由两个墙的 edgeListener 处理，这里不再插手
            return false;
        }
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
     * <p>返回 true 会让宿主弹「保持播放吗」，对直播是多余的一问。
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

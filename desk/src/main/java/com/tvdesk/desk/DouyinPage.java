package com.tvdesk.desk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.tvdesk.core.douyin.DouyinClient;
import com.tvdesk.core.douyin.DouyinException;
import com.tvdesk.core.douyin.model.DyComment;
import com.tvdesk.core.douyin.model.DyItem;
import com.tvdesk.core.douyin.model.DyProfile;
import com.tvdesk.desk.motion.PressFx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抖音模块页：竖屏信息流，一次只看一条。
 *
 * <h3>为什么不是卡片墙</h3>
 * 技术方案 §6.2 定的就是这个形态：短视频的「标题＋封面」信息量远小于影视海报，
 * 铺成墙只会得到一屏看不清的小图。这里保留的是同一套焦点语言（半透明圆按钮、
 * 白字主标题、胶囊出口），只是把网格换成一屏一条。
 *
 * <h3>按键一次切一条，不做惯性</h3>
 * 遥控器没有滚动惯性。下＝下一条、上＝上一条（和刷短视频时手往上推的方向一致），
 * **按住连发的自动重复一律忽略**（{@code repeatCount > 0}），否则一按就跳三条——
 * 这是当初设计时点名要避免的事。快速连按两下仍然是两条，因为它俩是不同的按下事件。
 *
 * <h3>播放时只留视频</h3>
 * 画面一开就把模块胶囊、档位、作者文案和右侧互动栏淡出，{@value #CHROME_IDLE_MS} 毫秒内
 * 没有任何操作就让它们消失；任意按键（包括换条）都会先把它们唤回来。
 * 暂停、缓冲、评论抽屉打开时都不收——那是用户正在读信息的时候。
 *
 * <p>方向键照 myDV 那套：左/右立刻快退/快进 5 秒（按住连发），上/下换条；
 * 确定键短按播放/暂停，长按（{@value #CENTER_LONG_PRESS_MS} 毫秒）开搜索；
 * 菜单键摊开功能抽屉（评论/接着刷/搜索/刷新/收藏/倍速/账号），和 myDV 的菜单一致。
 * 宿主以前把菜单键全局截走，页面里的分支收不到——现在模块页先拿，页里不认才轮到全局菜单。
 *
 * <p>点赞/收藏/评论不占方向键：右侧那几颗按钮点一下就是；评论另开菜单键，
 * 收藏给带 (i) 键遥控器的 {@code KEYCODE_INFO}。这三个都会写用户账号，不给方向键
 * 叠长按，手慢一点就会误触发。
 *
 * <h3>数据从哪来</h3>
 * 匿名 {@code tab/feed} 服务端只给 2 条（{@code count} 提上去也没用），所以真正的刷流感来自
 * {@code aweme/related}：每条能续 10 条推荐。列表快见底时后台预取下一批
 * （{@link #ensureMore}），用户察觉不到取数。这两条路都<b>不需要签名、不需要 cookie</b>。
 * 评论要 {@code a_bogus}，由 {@code DouyinSigner} 在本机算（进页时预热，见 {@link #onEnter}）；
 * 抽屉一屏装不下 20 条，而上下键归换条管，所以右键在抽屉里是「翻页」。
 *
 * <h3>搜索是另一种刷流</h3>
 * 关键词同样不在电视上敲：借 cookie 那套局域网表单（{@link DouyinSearchDialog}），
 * 手机扫开敲字提交。进入搜索模式后，结果列表顶替推荐流，推荐那一批先存一份
 * （{@link #feedBackup}），按 BACK 退出搜索时原样接回去——用户退出来应该还停在他刚才看的那条，
 * 而不是被重新推一批不相干的。
 *
 * <h3>登录只影响两件事</h3>
 * 点赞/收藏（要 cookie 里的 {@code UIFID}）和搜索。缺 cookie 时按点赞不是转圈后报失败，
 * 而是直接把「去导 cookie」这一步摊出来，见 {@link Host#openDouyinLogin(String)}。
 *
 * <p>过期比缺失更麻烦：用户以为自己登录着。所以进页时顺手拿 {@code profile/self} 探一次
 * （{@link #probeCookie()}）——认得出来就报个「已登录：昵称」，不认得就直接把弹窗递到面前。
 * 探不出来（网络、风控）时<b>什么都不说</b>，把一次超时说成「你 cookie 过期了」，
 * 用户会去重导一份本来好好的 cookie。
 */
public class DouyinPage extends FrameLayout implements ModulePage {

    private static final String TAG = "DouyinPage";

    /** 换条动画：滑出去短、滑进来按方案给的 200ms */
    private static final int SLIDE_OUT_MS = 120;
    private static final int SLIDE_IN_MS = 200;
    private static final int SLIDE_DISTANCE_DP = 56;
    /** 剩这么几条就预取下一批。太小会来不及，太大推荐会一直贴着第一条走 */
    private static final int PREFETCH_AHEAD = 3;
    /** 回传给服务端去重的历史 id 条数，再多对这个接口没意义 */
    private static final int SEEN_KEEP = 40;
    /** 搜索一页条数。给不满就认为到底了，抖音这个接口的 has_more 不可靠 */
    private static final int SEARCH_PAGE = 15;
    /** 确定键按这么久算长按（开搜索），不到就算短按（播放/暂停） */
    private static final int CENTER_LONG_PRESS_MS = 380;
    /** 进度条唤出后左右键每按一次走 5 秒（和 myDV 的操作一致） */
    private static final int SEEK_STEP_MS = 5000;
    /** 进度条自己收起来的时间；动一下重新计时 */
    private static final long PROGRESS_STAY_MS = 4000L;
    /** 推荐面板最多列几条：六条刚好一屏，再多就得滚，而上下键另有用途 */
    private static final int REC_ROWS = 6;

    public interface Host {

        /**
         * 把用户带去导 cookie。
         *
         * @param why 为什么现在要打开它（没登录 / 接口原文），显示在弹窗第一行；
         *            {@code null} 表示用户自己按了「登录」，弹窗按常规提示
         */
        void openDouyinLogin(String why);

        /**
         * 打开「搜索」弹窗：本机局域网关键词表单，扫码或直接在本机输入都行。
         *
         * <p>页自己不建弹窗——它手上只有 Context，Dialog 要的是宿主 Activity，
         * 而且宿主得在 {@code onDestroy} 里替它把这个带着局域网服务的窗口关掉。
         *
         * @param listener 关键词到手时回调（在 UI 线程），弹窗同时关掉
         */
        void openDouyinSearch(DouyinSearchDialog.Listener listener);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Host host;
    private final Runnable backAction;
    private final DouyinClient client;

    private final List<DyItem> items = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final List<String> seenOrder = new ArrayList<>();
    /** 抽屉当前这批评论（一次取 20 条，分页渲染） */
    private final List<DyComment> commentRows = new ArrayList<>();
    private int commentPage;

    private int index = -1;
    private boolean started;
    private boolean released;
    /** 切走前是在放的，切回来才接回去；用户自己按过暂停的别替他按播放 */
    private boolean resumeOnEnter;
    private boolean loadingFeed;
    private boolean loadingMore;
    private boolean commentsOpen;
    /** 换条动画进行中，期间再按键会被吃掉——宁可漏一次按键，也不要两个动画互相打断 */
    private boolean sliding;

    // ---- 搜索模式：items 被结果顶替，推荐流那一批先存起来
    /** 当前列表是搜索结果而不是推荐流。它决定见底时该续什么 */
    private boolean searching;
    private String searchKeyword = "";
    /** 已取到第几条，下一页的 offset */
    private int searchCursor;
    /** 上一页没给满一页：到底了，别再发请求 */
    private boolean searchEnded;
    private boolean loadingSearch;
    /** 进搜索前那一屏推荐（连同看到第几条），退出时原样接回去 */
    private final List<DyItem> feedBackup = new ArrayList<>();
    private int feedIndex = -1;
    /** 确定键按下去了，还没决定是短按还是长按 */
    private boolean centerDown;
    /** 按键说明只在第一次出内容时报一次 */
    private boolean keysHintShown;
    /** 登录态探针一个页实例只发一次；重导 cookie 后重新放行 */
    private boolean cookieProbed;
    /** 播放中把作者/互动栏/档位这些 UI 收起来，只留视频。任意按键唤回 */
    private boolean chromeHidden;
    /** 收起前给用户留的阅读时间：三条文案刚出来就被藏掉等于没写 */
    private static final long CHROME_IDLE_MS = 3000L;
    private boolean keepPlayingOnce;

    private View stage;
    private PlayerView playerView;
    private ImageView coverView;
    private ImageView backdropView;
    private ImageView avatarView;
    private ProgressBar loadingView;
    private TextView statusView;
    private TextView authorView;
    private TextView descView;
    private TextView musicView;
    private TextView qualityView;
    private ImageView diggView;
    private TextView diggCountView;
    private TextView commentCountView;
    private ImageView collectView;
    private TextView collectCountView;
    private LinearLayout commentPanel;
    private android.widget.ScrollView commentScroll;
    private LinearLayout commentList;
    private TextView commentHint;
    private View switcherView;
    private View infoView;
    private View railView;
    /** 左右键快退快进时短暂摊出的进度条 */
    private View progressPanel;
    private TextView progressTime;
    private View progressFill;
    private boolean progressShown;
    /** 菜单「接着刷」摊出的推荐面板 */
    private LinearLayout recPanel;
    private LinearLayout recList;
    private boolean recOpen;
    /** 面板里高亮的是 items 的哪一条 */
    private int recIndex;
    /** 菜单键唤出的功能抽屉（照 myDV：评论/接着刷/搜索/倍速/账号） */
    private LinearLayout menuPanel;
    private LinearLayout menuList;
    private TextView menuTitle;
    private TextView menuHint;
    private boolean menuOpen;
    /** 0 主菜单，1 倍速选择 */
    private int menuPage;
    private int menuIndex;
    private float playbackSpeed = 1f;
    private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final String[] SPEED_LABELS = {"0.75x", "1.0x", "1.25x", "1.5x", "2.0x"};
    private long lastSeekAt;

    private SimpleExoPlayer player;

    public DouyinPage(Context context, Host host, Runnable backToDesk) {
        super(context);
        this.host = host;
        this.backAction = backToDesk;
        this.client = DouyinClient.get(context);
        LayoutInflater.from(context).inflate(R.layout.page_douyin, this, true);
        bind();
    }

    private void bind() {
        // 本页自己拿焦点：互动栏上的按钮全部 focusable=false，方向键才能稳定地用来换条
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        stage = findViewById(R.id.dy_stage);
        playerView = findViewById(R.id.dy_player);
        coverView = findViewById(R.id.dy_cover);
        backdropView = findViewById(R.id.dy_backdrop);
        avatarView = findViewById(R.id.dy_avatar);
        loadingView = findViewById(R.id.dy_loading);
        statusView = findViewById(R.id.dy_status);
        authorView = findViewById(R.id.dy_author);
        descView = findViewById(R.id.dy_desc);
        musicView = findViewById(R.id.dy_music);
        qualityView = findViewById(R.id.dy_quality);
        diggView = findViewById(R.id.dy_digg);
        diggCountView = findViewById(R.id.dy_digg_count);
        commentCountView = findViewById(R.id.dy_comment_count);
        collectView = findViewById(R.id.dy_collect);
        collectCountView = findViewById(R.id.dy_collect_count);
        commentPanel = findViewById(R.id.dy_comments);
        commentScroll = findViewById(R.id.dy_comments_scroll);
        commentList = findViewById(R.id.dy_comments_list);
        commentHint = findViewById(R.id.dy_comments_hint);
        switcherView = findViewById(R.id.dy_switcher);
        infoView = findViewById(R.id.dy_info);
        railView = findViewById(R.id.dy_rail);
        progressPanel = findViewById(R.id.dy_progress);
        progressTime = findViewById(R.id.dy_progress_time);
        progressFill = findViewById(R.id.dy_progress_fill);
        recPanel = findViewById(R.id.dy_rec);
        recList = findViewById(R.id.dy_rec_list);
        menuPanel = findViewById(R.id.dy_menu);
        menuList = findViewById(R.id.dy_menu_list);
        menuTitle = findViewById(R.id.dy_menu_title);
        menuHint = findViewById(R.id.dy_menu_hint);

        findViewById(R.id.dy_switcher).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (backAction != null) {
                    backAction.run();
                }
            }
        });
        diggView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // 先跳再发请求：电视上网络往返要几百毫秒，只让图标等着，
                // 用户会以为没按到，接着按第二下就把刚点上的赞取消了
                PressFx.heart(v);
                digg();
            }
        });
        collectView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PressFx.heart(v);
                collect();
            }
        });
        findViewById(R.id.dy_comment).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PressFx.heart(v);
                toggleComments();
            }
        });
        findViewById(R.id.dy_login).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                host.openDouyinLogin(null);
            }
        });
        findViewById(R.id.dy_search).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                requestSearch();
            }
        });
        stage.setTranslationY(0f);
        stage.setAlpha(1f);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onEnter() {
        released = false;
        requestFocus();
        showChrome();
        if (started) {
            if (resumeOnEnter && player != null) {
                resumeOnEnter = false;
                player.setPlayWhenReady(true);
            }
            return;
        }
        started = true;
        createPlayer();
        // 签名环境趁取流这段时间先备好：首次取签要加载三个脚本，
        // 留到用户按菜单键开评论那一下，就变成「按了等一下」
        client.prepareSigner();
        loadFeed();
        probeCookie();
    }

    /**
     * 切走或退后台：默认停声。
     *
     * <p>本页是缓存页，切版块只会 {@code setVisibility(GONE)}，View 看不见但 ExoPlayer
     * 不知道自己该停，于是「切到音乐页，抖音还在响」。真机上用户第一反应是找不到声音从哪来。
     *
     * <p>要保留续播的（比如就想放着当背景音）走 {@code 设置 → 离页后续播}。
     */
    @Override
    public void onLeave() {
        // 页都走了，那个「再等 380ms 才决定要不要弹搜索窗」的计时器必须一起摘掉
        ui.removeCallbacks(centerLongPress);
        ui.removeCallbacks(hideChrome);
        centerDown = false;
        if (released || player == null || !player.getPlayWhenReady()) {
            return;
        }
        // 回桌面前宿主问过「继续/停止」，选继续就放行这一次
        if (keepPlayingOnce) {
            keepPlayingOnce = false;
            return;
        }
        if (DeskPrefs.keepPlayingOnLeave(getContext())) {
            return;
        }
        player.setPlayWhenReady(false);
        resumeOnEnter = true;
    }

    // ---------------------------------------------------------------- 播放时收起浮层

    /** 正在出声才算「沉浸」：暂停、缓冲、评论抽屉打开时都把浮层留在脸上 */
    private boolean chromeShouldStay() {
        return released || commentsOpen || menuOpen || recOpen || player == null
                || !player.getPlayWhenReady()
                || player.getPlaybackState() != Player.STATE_READY;
    }

    private void showChrome() {
        ui.removeCallbacks(hideChrome);
        if (chromeHidden) {
            chromeHidden = false;
            setChromeVisible(true);
        }
        scheduleChromeHide();
    }

    private void scheduleChromeHide() {
        ui.removeCallbacks(hideChrome);
        if (chromeShouldStay()) {
            return;
        }
        ui.postDelayed(hideChrome, CHROME_IDLE_MS);
    }

    private final Runnable hideChrome = new Runnable() {
        @Override
        public void run() {
            if (chromeShouldStay()) {
                return;
            }
            chromeHidden = true;
            setChromeVisible(false);
        }
    };

    private void setChromeVisible(final boolean visible) {
        View[] chrome = {switcherView, qualityView, infoView, railView};
        for (final View v : chrome) {
            if (v == null) {
                continue;
            }
            v.animate().cancel();
            if (visible) {
                // 档位还没拿到时文案是空的，别在右上角留一个空壳
                if (v == qualityView && TextUtils.isEmpty(qualityView.getText())) {
                    v.setVisibility(View.INVISIBLE);
                    continue;
                }
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(180).start();
            } else {
                v.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        // 淡出途中用户又按了键，showChrome 已经把 chromeHidden 翻回去
                        if (chromeHidden) {
                            v.setVisibility(View.INVISIBLE);
                        }
                    }
                }).start();
            }
        }
        // 状态提示是按键/出错才出的反馈，不属于常驻浮层，不跟着藏
    }

    @Override
    public boolean hasActivePlayback() {
        try {
            return player != null && player.isPlaying();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void stopPlayback() {
        if (player == null) {
            return;
        }
        try {
            player.setPlayWhenReady(false);
        } catch (Throwable ignored) {
            return;
        }
        resumeOnEnter = false;
    }

    @Override
    public void setKeepPlayingOnce(boolean keep) {
        keepPlayingOnce = keep;
    }

    /**
     * cookie 变了（导入或清除）之后由宿主叫一声。
     *
     * <p>不重新取流：匿名推荐流和登录后的推荐流是同一个接口，手里这一批照样能播，
     * 重取只会把用户看到的位置冲掉。点赞/收藏是发请求时才现查 cookie 的，所以这里只报状态。
     */
    public void onCookieChanged() {
        if (released) {
            return;
        }
        String missing = client.missingCookieHint();
        if (TextUtils.isEmpty(missing)) {
            say("已登录 · 点赞能用了");
            // 新导的那份到底认不认，别等用户按了点赞才知道
            cookieProbed = false;
            probeCookie();
        } else {
            say("未登录 · " + missing);
        }
    }

    /**
     * 进页时探一次登录态：cookie 过期这件事，本来只有等用户按了点赞才会暴露，
     * 而那时他已经以为自己登录过了。
     *
     * <p>只认两种结论：接口点名「缺登录态」（{@code needsLogin}）或者 200 但资料是空的，
     * 才算不认了，直接把用户带进登录弹窗——那是全站唯一有「清除登录」的地方，
     * 而且它自己就能取消，不算把人堵在门口。
     *
     * <p>其它失败（网络、风控、签名没算出来）一律<b>不吭声</b>：把一次超时说成「你 cookie 过期了」，
     * 用户会照着去重导一份本来好好的 cookie，比不提示更糟。
     */
    private void probeCookie() {
        if (cookieProbed || !client.hasLoginCookie()) {
            return;
        }
        cookieProbed = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String nick = null;
                boolean dead = false;
                try {
                    DyProfile profile = client.selfProfile();
                    if (profile.valid()) {
                        nick = profile.user;
                    } else {
                        dead = true;
                    }
                } catch (DouyinException e) {
                    dead = e.needsLogin;
                    if (!dead) {
                        Log.w(TAG, "登录态没探出来，不当作过期", e);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "登录态没探出来，不当作过期", e);
                }
                final String name = nick;
                final boolean expired = dead;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released) {
                            return;
                        }
                        if (name != null) {
                            say("已登录：" + name + " · 菜单评论 (i)收藏");
                        } else if (expired) {
                            host.openDouyinLogin("这份 cookie 抖音已经不认了");
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    public void onRelease() {
        released = true;
        // 长按计时器不能比页活得久：它一响就会弹出一个没有主人的搜索窗
        ui.removeCallbacks(centerLongPress);
        ui.removeCallbacks(hideChrome);
        ui.removeCallbacks(progressTick);
        ui.removeCallbacks(hideProgress);
        centerDown = false;
        progressShown = false;
        recOpen = false;
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        playerView.setPlayer(null);
    }

    // ---------------------------------------------------------------- 按键

    @Override
    public boolean handleKeyDown(int keyCode) {
        return handleKeyDown(keyCode, null);
    }

    /**
     * @param event 原始事件。只有它带得上「这是不是按住连发」的信息，
     *              换条必须按次响应，所以连发要丢掉。
     */
    @Override
    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        boolean burst = event != null && event.getRepeatCount() > 0;
        // 任意按键都先把收起来的浮层唤回来；隐藏期间按方向键照样执行动作，
        // 不做「第一下只负责唤回」——那会让换条看起来像丢了半拍
        showChrome();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!burst) {
                    if (menuOpen) {
                        moveMenu(1);
                    } else if (recOpen) {
                        moveRec(1);
                    } else {
                        step(1);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (!burst) {
                    if (menuOpen) {
                        moveMenu(-1);
                    } else if (recOpen) {
                        moveRec(-1);
                    } else {
                        step(-1);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 照 myDV：左/右直接快退快进，不再先摊一层面板。
                // 抽屉开着时左右只负责收起来（抽屉里上下移动，没有横向层级）
                if (menuOpen) {
                    if (!burst) {
                        closeMenu();
                    }
                    return true;
                }
                if (recOpen) {
                    if (!burst) {
                        closeRec();
                    }
                    return true;
                }
                if (commentsOpen) {
                    if (!burst) {
                        showCommentPage(Math.max(0, commentPage - 1));
                    }
                    return true;
                }
                if (!burst || seekReady(event)) {
                    seekBy(-SEEK_STEP_MS, event);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 评论抽屉开着时右键仍是翻页；其余情况就是快进
                if (commentsOpen) {
                    if (!burst) {
                        showCommentPage(commentPage + 1);
                    }
                    return true;
                }
                if (menuOpen) {
                    if (!burst) {
                        closeMenu();
                    }
                    return true;
                }
                if (recOpen) {
                    if (!burst) {
                        closeRec();
                    }
                    return true;
                }
                if (!burst || seekReady(event)) {
                    seekBy(SEEK_STEP_MS, event);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 短按播放/暂停、长按开搜索。判定时长在抬起那一下（见 handleKeyUp），
                // 这里只挂计时；一条都没有时按确定是「重试取数」，按提示语办事
                if (menuOpen) {
                    if (!burst) {
                        activateMenu();
                    }
                    return true;
                }
                if (recOpen) {
                    if (!burst) {
                        jumpRec();
                    }
                    return true;
                }
                pressAndHold(burst);
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                // 带独立搜索键的遥控器走这条快路，不用等长按
                if (!burst) {
                    requestSearch();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                // 菜单＝本页的功能抽屉（myDV 同款）。以前宿主先把这个键吃掉，
                // 页面里这一段永远收不到；现在 DeskActivity 先问模块页
                if (!burst) {
                    toggleMenu();
                }
                return true;
            case KeyEvent.KEYCODE_INFO:
                // 收藏给带 (i) 键的遥控器一个入口；触屏用户直接点右侧那颗星
                if (!burst) {
                    collect();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                // 层级表顺序：L2 本页浮层（功能抽屉、推荐列表、进度条、评论）→ 搜索模式 → 交回宿主
                if (menuOpen) {
                    closeMenu();
                    return Back.consume("抖音·收起功能抽屉");
                }
                if (recOpen) {
                    closeRec();
                    return Back.consume("抖音·收起推荐列表");
                }
                if (progressShown) {
                    hideProgress();
                    return Back.consume("抖音·收起进度条");
                }
                if (commentsOpen) {
                    closeComments();
                    return Back.consume("抖音·收起评论");
                }
                if (searching) {
                    exitSearch();
                    return Back.consume("抖音·退出搜索模式");
                }
                return Back.pass("抖音·页内已无层级");
            default:
                return false;
        }
    }

    /**
     * 确定键抬起：长按已经生效（弹窗在面前了）就只当收个尾，否则这一下算短按。
     *
     * <p>为什么非要等到抬起才动播放器：按下时还不知道用户要按多久，
     * 立刻播放/暂停的话，想长按开搜索的人每次都会先把视频按停。
     *
     * <p>抬起时再判一次时长是补漏：按下到 380 毫秒之间主线程要是被取数占住了，
     * 计时器会排到抬起之后，那一下就会被当成短按——用户明明按住不放，看到的却是暂停。
     */
    @Override
    public boolean handleKeyUp(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) {
            return false;
        }
        ui.removeCallbacks(centerLongPress);
        if (!centerDown) {
            return true;
        }
        if (event.getEventTime() - event.getDownTime() >= CENTER_LONG_PRESS_MS
                || event.isLongPress()) {
            centerDown = false;
            requestSearch();
            return true;
        }
        centerDown = false;
        togglePlay();
        return true;
    }

    private void pressAndHold(boolean burst) {
        if (items.isEmpty()) {
            centerDown = false;
            ui.removeCallbacks(centerLongPress);
            retryCurrent();
            return;
        }
        if (burst) {
            return;
        }
        centerDown = true;
        ui.removeCallbacks(centerLongPress);
        ui.postDelayed(centerLongPress, CENTER_LONG_PRESS_MS);
    }

    private final Runnable centerLongPress = new Runnable() {
        @Override
        public void run() {
            if (released || !centerDown) {
                return;
            }
            centerDown = false;
            requestSearch();
        }
    };

    /** 空列表时按确定的出口：搜索模式重取当前关键词，否则重取推荐流 */
    private void retryCurrent() {
        if (searching) {
            searchCursor = 0;
            searchEnded = false;
            loadSearch(true);
        } else {
            loadFeed();
        }
    }

    // ---------------------------------------------------------------- 换条

    private void step(int delta) {
        if (items.isEmpty() || sliding) {
            return;
        }
        int target = index + delta;
        if (target < 0) {
            say("这已经是第一条了");
            return;
        }
        if (target >= items.size()) {
            if (searching) {
                // 搜索模式见底了就取下一页；取完了就明说取完了，
                // 这里绝不能退回 loadFeed——那会把用户刚搜到的结果冲掉
                if (searchEnded) {
                    say("「" + searchKeyword + "」就这些了 · 按返回回到推荐流");
                } else if (loadingSearch) {
                    say("正在取下一条…");
                } else {
                    loadSearch(false);
                }
                return;
            }
            // 见底了。相关推荐一般早就续上了；真没续上（接口挂/去重去空了）就现取一批新推荐，
            // 停在原地只说一句「没有了」，用户会以为这个功能到头了
            if (loadingMore || loadingFeed) {
                say("正在取下一条…");
            } else {
                say("正在取新的推荐…");
                loadFeed();
            }
            return;
        }
        show(target, delta > 0);
        ensureMore();
    }

    /**
     * 切到第 position 条。
     *
     * @param forward 向前刷（下一条）时新卡从下面上来，回看上一条时反过来，
     *                方向要和按键意图一致，否则看着像「跳回去又弹回来」
     */
    private void show(final int position, final boolean forward) {
        index = position;
        final DyItem item = items.get(position);
        remember(item.id);
        float distance = SLIDE_DISTANCE_DP * getResources().getDisplayMetrics().density;
        final float enterFrom = forward ? distance : -distance;
        sliding = true;
        stage.animate()
                .translationY(forward ? -distance : distance)
                .alpha(0f)
                .setDuration(SLIDE_OUT_MS)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (released) {
                            return;
                        }
                        bind(item);
                        play(item);
                        stage.setTranslationY(enterFrom);
                        stage.animate()
                                .translationY(0f)
                                .alpha(1f)
                                .setDuration(SLIDE_IN_MS)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        sliding = false;
                                    }
                                })
                                .start();
                    }
                })
                .start();
    }

    private void bind(DyItem item) {
        authorView.setText(TextUtils.isEmpty(item.author) ? "抖音" : "@" + item.author);
        descView.setText(item.desc);
        descView.setVisibility(TextUtils.isEmpty(item.desc) ? View.GONE : View.VISIBLE);
        String music = item.musicTitle;
        if (TextUtils.isEmpty(music)) {
            music = item.mixName;
        }
        musicView.setText(TextUtils.isEmpty(music) ? "" : "♪ " + music);
        musicView.setVisibility(TextUtils.isEmpty(music) ? View.GONE : View.VISIBLE);
        diggCountView.setText(human(item.digg));
        commentCountView.setText(human(item.comment));
        collectCountView.setText(human(item.collect));
        // 点赞态用图标+颜色说，不用「已赞」两个字：圆里塞四个汉字在电视上读不出来。
        // 描边 = 没赞，实心 = 赞过了；染色只是再压一层，光靠描边变红在电视上不够明显。
        diggView.setImageResource(item.diggStatus == 1
                ? R.drawable.ic_dy_digg_on : R.drawable.ic_dy_digg);
        if (item.diggStatus == 1) {
            diggView.setColorFilter(getResources().getColor(R.color.dy_digg_on));
        } else {
            diggView.clearColorFilter();
        }
        collectView.setImageResource(item.collectStatus == 1
                ? R.drawable.ic_dy_collect_on : R.drawable.ic_dy_collect);
        if (item.collectStatus == 1) {
            collectView.setColorFilter(getResources().getColor(R.color.dy_collect_on));
        } else {
            collectView.clearColorFilter();
        }
        String quality = qualityLabel(item);
        qualityView.setText(quality);
        qualityView.setVisibility(TextUtils.isEmpty(quality) || chromeHidden
                ? View.INVISIBLE : View.VISIBLE);

        if (!TextUtils.isEmpty(item.avatar)) {
            Glide.with(getContext()).load(item.avatar).circleCrop().into(avatarView);
        }
        if (!TextUtils.isEmpty(item.cover)) {
            // 封面正放一份在中间（和画面同尺寸），再放大一份铺满整屏当氛围底
            Glide.with(getContext()).load(item.cover).into(coverView);
            Glide.with(getContext()).load(item.cover).centerCrop().into(backdropView);
            backdropView.animate().alpha(0.45f).setDuration(240).start();
        }
        coverView.setAlpha(1f);
        if (commentsOpen) {
            closeComments();
        }
        // 换条时上一张卡的操作浮层不该带到新卡片上
        if (progressShown) {
            hideProgress();
        }
        if (recOpen) {
            closeRec();
        }
    }

    /** 档位名形如 normal_1080_0 / adapt_lowest_1440_1，只取分辨率那一段，认不出就不显示 */
    private static String qualityLabel(DyItem item) {
        String gear = item.gearName;
        if (TextUtils.isEmpty(gear)) {
            return item.isVertical() ? "竖屏" : "";
        }
        String lower = gear.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("2160") || lower.contains("4k")) {
            return "4K";
        }
        if (lower.contains("1440") || lower.contains("2k")) {
            return "2K";
        }
        if (lower.contains("1080")) {
            return "1080P";
        }
        if (lower.contains("720")) {
            return "720P";
        }
        if (lower.contains("540")) {
            return "540P";
        }
        if (lower.contains("480")) {
            return "480P";
        }
        if (lower.contains("360")) {
            return "360P";
        }
        return item.isVertical() ? "竖屏" : "";
    }

    private static String human(long count) {
        if (count >= 100000000L) {
            return String.format(java.util.Locale.CHINA, "%.1f亿", count / 100000000f);
        }
        if (count >= 10000L) {
            return String.format(java.util.Locale.CHINA, "%.1f万", count / 10000f);
        }
        return String.valueOf(count);
    }

    private void say(String message) {
        statusView.setText(message);
        statusView.setAlpha(1f);
        ui.removeCallbacks(hideStatus);
        ui.postDelayed(hideStatus, 1800L);
    }

    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            statusView.animate().alpha(0f).setDuration(260).start();
        }
    };

    // ---------------------------------------------------------------- 取数

    private void loadFeed() {
        if (loadingFeed) {
            return;
        }
        loadingFeed = true;
        loadingView.setVisibility(View.VISIBLE);
        say("正在取推荐流…");
        // filterGids 要「,」拼接的已看 id；String.join 是 API 26，minSdk 19 用不了
        StringBuilder filter = new StringBuilder();
        for (String id : seenOrder) {
            if (filter.length() > 0) {
                filter.append(',');
            }
            filter.append(id);
        }
        final String seenIds = filter.toString();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<DyItem> result = null;
                String error = null;
                try {
                    result = client.feed(15, seenIds);
                } catch (Exception e) {
                    error = message(e);
                }
                final List<DyItem> value = result;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loadingFeed = false;
                        if (released) {
                            return;
                        }
                        loadingView.setVisibility(View.GONE);
                        if (value == null || value.isEmpty()) {
                            // 匿名推荐流偶尔被限流；说清楚是它没给，而不是我们坏了，并留下重试的口
                            say((failure == null ? "推荐流暂时是空的" : failure) + " · 按确定重试");
                            return;
                        }
                        append(value);
                        show(0, true);
                        ensureMore();
                        if (!keysHintShown) {
                            keysHintShown = true;
                            // 第一次进来把按键摊出来一次就够：这一页没有常驻图例，
                            // 而「长按确定能搜索」这件事没人猜得到
                            say("上/下换条 · 左/右快退快进 · 短按确定暂停 · 长按搜索 · 菜单更多");
                        }
                    }
                });
            }
        }).start();
    }

    /** 列表快见底时，用最后一条去要 10 条相关推荐 */
    private void ensureMore() {
        // 搜索模式不预取推荐：那会往结果里混进不相干的一条，用户以为是搜索结果
        if (searching || loadingMore || items.isEmpty()
                || index < items.size() - PREFETCH_AHEAD) {
            return;
        }
        loadingMore = true;
        final String tail = items.get(items.size() - 1).id;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<DyItem> result = null;
                try {
                    result = client.related(tail);
                } catch (Exception e) {
                    Log.w(TAG, "相关推荐失败", e);
                }
                final List<DyItem> value = result;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loadingMore = false;
                        if (released) {
                            return;
                        }
                        if (value == null || value.isEmpty()) {
                            // 相关推荐续不上（风控、或去重后正好全空）就退回推荐流：
                            // 匿名 feed 一次哪怕只给 2 条，也比让用户刷到见底强
                            loadFeed();
                            return;
                        }
                        append(value);
                    }
                });
            }
        }).start();
    }

    private void append(List<DyItem> incoming) {
        for (DyItem item : incoming) {
            if (seen.add(item.id)) {
                items.add(item);
                seenOrder.add(item.id);
            }
        }
        while (seenOrder.size() > SEEN_KEEP) {
            seenOrder.remove(0);
        }
    }

    private void remember(String id) {
        if (!TextUtils.isEmpty(id) && !seenOrder.contains(id)) {
            seenOrder.add(id);
            while (seenOrder.size() > SEEN_KEEP) {
                seenOrder.remove(0);
            }
        }
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * 开搜索。
     *
     * <p>搜索接口要 cookie（{@code general/search/single} 是签名＋凭据都要的那一类），
     * 缺了就先把「去导 cookie」摊出来而不是转圈等 403——和点赞同一个理由。
     */
    private void requestSearch() {
        if (!client.hasLoginCookie()) {
            host.openDouyinLogin("搜索要登录 cookie");
            return;
        }
        host.openDouyinSearch(new DouyinSearchDialog.Listener() {
            @Override
            public void onSearch(String keyword) {
                startSearch(keyword);
            }
        });
    }

    /**
     * 换成搜索结果列表。
     *
     * <p>推荐流那一批连同「看到第几条」整份存进 {@link #feedBackup}：退出搜索时接回去，
     * 用户回到的是他刚才那条，而不是一屏新的推荐——那会让人以为搜索把页刷挂了。
     */
    private void startSearch(String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.length() == 0) {
            return;
        }
        if (!searching) {
            feedBackup.clear();
            feedBackup.addAll(items);
            feedIndex = index;
            searching = true;
        }
        searchKeyword = trimmed;
        searchCursor = 0;
        searchEnded = false;
        loadingSearch = false;
        loadingMore = false;
        items.clear();
        index = -1;
        // 去重集跟着列表换：推荐流看过的 id 不该把同一条的搜索结果吞掉
        seen.clear();
        seenOrder.clear();
        if (player != null) {
            player.stop();
        }
        if (commentsOpen) {
            closeComments();
        }
        loadSearch(true);
    }

    /** 退掉搜索，把推荐流那一屏原样接回去 */
    private void exitSearch() {
        if (!searching) {
            return;
        }
        searching = false;
        loadingSearch = false;
        items.clear();
        items.addAll(feedBackup);
        feedBackup.clear();
        seen.clear();
        seenOrder.clear();
        for (DyItem item : items) {
            seen.add(item.id);
        }
        if (items.isEmpty()) {
            feedIndex = -1;
            loadFeed();
            return;
        }
        int target = Math.max(0, Math.min(feedIndex, items.size() - 1));
        feedIndex = -1;
        show(target, false);
        say("回到推荐流");
    }

    /**
     * @param firstPage true=换关键词取第一页（回来直接切到第一条），
     *                  false=用户刷到底了续下一页（回来接上往下走一条）
     */
    private void loadSearch(final boolean firstPage) {
        if (loadingSearch || searchEnded) {
            return;
        }
        loadingSearch = true;
        loadingView.setVisibility(View.VISIBLE);
        say("正在搜「" + searchKeyword + "」…");
        final int offset = searchCursor;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<DyItem> result = null;
                String error = null;
                try {
                    result = client.search(searchKeyword, offset, SEARCH_PAGE);
                } catch (Exception e) {
                    error = message(e);
                }
                final List<DyItem> value = result;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loadingSearch = false;
                        // 关键词换快了，或者已经退出搜索：这一批不要了
                        if (released || !searching) {
                            return;
                        }
                        loadingView.setVisibility(View.GONE);
                        int got = value == null ? 0 : value.size();
                        searchCursor += got;
                        if (got < SEARCH_PAGE) {
                            searchEnded = true;
                        }
                        if (value != null) {
                            append(value);
                        }
                        if (items.isEmpty()) {
                            say((failure == null ? "没搜到「" + searchKeyword + "」" : failure)
                                    + " · 按返回回到推荐流");
                            return;
                        }
                        if (firstPage) {
                            show(0, true);
                        } else if (index + 1 < items.size()) {
                            // 那一下上键用户是按了的，取到了就得真的往下走一条
                            show(index + 1, true);
                        }
                        say("搜索：" + searchKeyword + " · 共 " + items.size() + " 条 · 按返回退出");
                    }
                });
            }
        }).start();
    }

    /**
     * 失败原因给人看的那一句。
     *
     * <p>接口原文优先：用户手上已经有一份 cookie 却还失败，提示「这个操作要登录 cookie」
     * 等于把「你这份已经过期」这个真正的信息藏掉了，他会去翻浏览器找怎么登录，而不是先清除。
     */
    private static String message(Exception e) {
        if (e instanceof DouyinException && !TextUtils.isEmpty(e.getMessage())) {
            return e.getMessage();
        }
        if (e instanceof DouyinException) {
            return ((DouyinException) e).needsLogin
                    ? "这个操作要登录 cookie" : "抖音接口不通";
        }
        return TextUtils.isEmpty(e.getMessage()) ? "抖音接口不通" : e.getMessage();
    }

    // ---------------------------------------------------------------- 播放

    private void createPlayer() {
        Map<String, String> headers = client.playHeaders();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(DouyinClient.userAgent())
                .setDefaultRequestProperties(headers)
                // douyinvod 的 CDN 会在 http/https 之间跳，不让跳就取不到流
                .setAllowCrossProtocolRedirects(true);
        DataSource.Factory factory = new DefaultDataSource.Factory(getContext(), http);
        player = new SimpleExoPlayer.Builder(getContext())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(factory))
                // 短视频只有几十秒，缓冲 50 秒纯粹是浪费内存和带宽；起播门槛也压到 0.8 秒。
                // 上下换条时这两项直接决定「按下键到画面出来」的手感
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(8000, 20000, 800, 1500)
                        .build())
                .build();
        playerView.setPlayer(player);
        player.setPlayWhenReady(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (released) {
                    return;
                }
                if (state == Player.STATE_READY) {
                    loadingView.setVisibility(View.GONE);
                    coverView.animate().alpha(0f).setDuration(180).start();
                    scheduleChromeHide();
                } else if (state == Player.STATE_BUFFERING) {
                    loadingView.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_ENDED) {
                    // 短视频放完就自动往下刷，这是这个形态的基本期待
                    step(1);
                }
            }

            @Override
            public void onPlayerError(com.google.android.exoplayer2.PlaybackException error) {
                if (released) {
                    return;
                }
                loadingView.setVisibility(View.GONE);
                // 地址带时效，签名过期是这个接口最常见的失败原因
                say("这条放不出来 · 按下键换一条");
                Log.w(TAG, "playback error", error);
            }
        });
    }

    private void play(DyItem item) {
        if (player == null || TextUtils.isEmpty(item.playUrl)) {
            say("这条没有可播的地址");
            return;
        }
        loadingView.setVisibility(View.VISIBLE);
        player.setMediaItem(MediaItem.fromUri(item.playUrl));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void togglePlay() {
        if (player == null) {
            return;
        }
        // 暂停时浮层必须露出来：用户停下就是想看清作者和文案，这时藏 UI 等于帮倒忙
        showChrome();
        if (player.getPlayWhenReady()) {
            player.setPlayWhenReady(false);
            say("已暂停 · 按确定继续");
        } else {
            player.setPlayWhenReady(true);
            say("播放中");
            scheduleChromeHide();
        }
    }

    // ---------------------------------------------------------------- 进度条

    /**
     * 按住连发时用 220ms 的节流：遥控器连发间隔大约 50ms，不节流的话按住右键
     * 半秒就直接拖到片尾。
     */
    private boolean seekReady(KeyEvent event) {
        if (event == null) {
            return false;
        }
        long now = event.getEventTime();
        if (now - lastSeekAt < 220) {
            return false;
        }
        lastSeekAt = now;
        return true;
    }

    private void showProgress() {
        if (player == null) {
            return;
        }
        progressShown = true;
        lastSeekAt = android.os.SystemClock.uptimeMillis();
        progressPanel.setVisibility(View.VISIBLE);
        progressPanel.animate().alpha(1f).setDuration(140).start();
        updateProgress();
        ui.removeCallbacks(progressTick);
        ui.post(progressTick);
        resetProgressTimer();
    }

    private void resetProgressTimer() {
        ui.removeCallbacks(hideProgress);
        ui.postDelayed(hideProgress, PROGRESS_STAY_MS);
    }

    private void hideProgress() {
        progressShown = false;
        ui.removeCallbacks(progressTick);
        ui.removeCallbacks(hideProgress);
        progressPanel.animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (!progressShown) {
                    progressPanel.setVisibility(View.GONE);
                }
            }
        }).start();
    }

    private final Runnable hideProgress = new Runnable() {
        @Override
        public void run() {
            if (progressShown) {
                hideProgress();
            }
        }
    };

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (!progressShown || released) {
                return;
            }
            updateProgress();
            ui.postDelayed(this, 500L);
        }
    };

    private void updateProgress() {
        if (player == null) {
            return;
        }
        long position = Math.max(0L, player.getCurrentPosition());
        long duration = Math.max(0L, player.getDuration());
        progressTime.setText(clock(position) + " / " + clock(duration));
        float fraction = duration > 0 ? Math.min(1f, position / (float) duration) : 0f;
        View parent = (View) progressFill.getParent();
        int width = parent == null ? 0 : parent.getWidth();
        android.view.ViewGroup.LayoutParams lp = progressFill.getLayoutParams();
        lp.width = Math.round(width * fraction);
        progressFill.setLayoutParams(lp);
    }

    private void seekBy(int delta, KeyEvent event) {
        if (player == null) {
            return;
        }
        // 一下左右键就动位置，进度条只是顺手把结果报出来（myDV 同款）
        if (!progressShown) {
            showProgress();
        } else {
            resetProgressTimer();
        }
        lastSeekAt = event == null
                ? android.os.SystemClock.uptimeMillis() : event.getEventTime();
        long duration = player.getDuration();
        long target = Math.max(0L, player.getCurrentPosition() + delta);
        if (duration > 0 && target > duration) {
            target = duration;
        }
        player.seekTo(target);
        updateProgress();
        resetProgressTimer();
        say((delta > 0 ? "快进 " : "快退 ") + Math.abs(delta) / 1000 + " 秒");
    }

    private static String clock(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long hours = total / 3600L;
        long minutes = total % 3600L / 60L;
        long seconds = total % 60L;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    // ---------------------------------------------------------------- 功能抽屉

    /**
     * 菜单键：右侧摊出一列功能，和 myDV 的菜单一个位置、一套操作。
     *
     * <p>上下选、确定进、左右或返回收起。抽屉里的操作都会把抽屉先关掉——
     * 用户选的是「去评论」而不是「在抽屉里看评论」，留在原地会挡住要去的页面。
     */
    private void toggleMenu() {
        if (menuOpen) {
            closeMenu();
        } else {
            openMenu();
        }
    }

    private void openMenu() {
        if (commentsOpen) {
            closeComments();
        }
        if (progressShown) {
            hideProgress();
        }
        if (recOpen) {
            closeRec();
        }
        menuOpen = true;
        menuPage = 0;
        menuIndex = 0;
        buildMenuRows();
        menuPanel.setVisibility(View.VISIBLE);
        menuPanel.setAlpha(0f);
        menuPanel.setTranslationX(dp(16));
        menuPanel.animate().alpha(1f).translationX(0f).setDuration(180).start();
        ui.removeCallbacks(hideChrome);
    }

    private void closeMenu() {
        if (!menuOpen) {
            return;
        }
        menuOpen = false;
        menuPanel.animate().alpha(0f).translationX(dp(16)).setDuration(140)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!menuOpen) {
                            menuPanel.setVisibility(View.GONE);
                        }
                    }
                }).start();
        scheduleChromeHide();
    }

    private void buildMenuRows() {
        menuList.removeAllViews();
        final String[] labels;
        final int[] icons;
        if (menuPage == 1) {
            labels = SPEED_LABELS;
            icons = null;
            menuTitle.setText("倍速播放");
            menuHint.setText("上/下 选择 · 确定应用 · 返回上级");
        } else {
            DyItem item = current();
            String comments = "查看评论";
            if (item != null && item.comment > 0) {
                comments += " · " + item.comment;
            }
            String collect = item != null && item.collectStatus == 1 ? "取消收藏" : "收藏";
            labels = new String[]{comments, "接着刷", "搜索", "刷新推荐",
                    collect, "倍速播放 " + speedLabel(), "账号与登录"};
            icons = new int[]{R.drawable.ic_dy_comment, R.drawable.ic_next, R.drawable.ic_search,
                    R.drawable.ic_mode_sequence,
                    item != null && item.collectStatus == 1
                            ? R.drawable.ic_dy_collect_on : R.drawable.ic_dy_collect,
                    R.drawable.ic_play, R.drawable.ic_dy_login};
            menuTitle.setText("抖音");
            menuHint.setText("上/下 选择 · 确定 · 返回关闭");
        }
        menuIndex = Math.max(0, Math.min(menuIndex, labels.length - 1));
        for (int i = 0; i < labels.length; i++) {
            final int position = i;
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_module_item);
            row.setPadding(dp(10), dp(7), dp(6), dp(7));
            row.setMinimumHeight(dp(50));
            row.setSelected(i == menuIndex);

            if (icons != null) {
                ImageView icon = new ImageView(getContext());
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
                iconLp.rightMargin = dp(12);
                icon.setLayoutParams(iconLp);
                icon.setImageResource(icons[i]);
                row.addView(icon);
            }

            TextView label = new TextView(getContext());
            label.setTextSize(15);
            label.setTextColor(getResources().getColor(R.color.text_primary));
            label.setText(labels[i]);
            label.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);

            if (menuPage == 0) {
                ImageView chevron = new ImageView(getContext());
                LinearLayout.LayoutParams chevronLp =
                        new LinearLayout.LayoutParams(dp(14), dp(14));
                chevron.setLayoutParams(chevronLp);
                chevron.setImageResource(R.drawable.ic_chevron_right);
                row.addView(chevron);
            }
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    menuIndex = position;
                    activateMenu();
                }
            });
            menuList.addView(row);
        }
    }

    private void moveMenu(int delta) {
        int count = menuPage == 1 ? SPEED_LABELS.length : 7;
        menuIndex = ((menuIndex + delta) % count + count) % count;
        for (int i = 0; i < menuList.getChildCount(); i++) {
            menuList.getChildAt(i).setSelected(i == menuIndex);
        }
    }

    private void activateMenu() {
        if (menuPage == 1) {
            applySpeed(SPEEDS[Math.max(0, Math.min(menuIndex, SPEEDS.length - 1))]);
            menuPage = 0;
            menuIndex = 5;
            buildMenuRows();
            return;
        }
        switch (menuIndex) {
            case 0:
                closeMenu();
                toggleComments();
                break;
            case 1:
                closeMenu();
                showRec();
                break;
            case 2:
                closeMenu();
                requestSearch();
                break;
            case 3:
                closeMenu();
                refreshFeed();
                break;
            case 4:
                closeMenu();
                collect();
                break;
            case 5:
                menuPage = 1;
                menuIndex = speedIndex();
                buildMenuRows();
                break;
            case 6:
                closeMenu();
                host.openDouyinLogin(null);
                break;
            default:
                break;
        }
    }

    private DyItem current() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    private int speedIndex() {
        for (int i = 0; i < SPEEDS.length; i++) {
            if (SPEEDS[i] == playbackSpeed) {
                return i;
            }
        }
        return 1;
    }

    private String speedLabel() {
        return speedLabel(playbackSpeed);
    }

    private static String speedLabel(float speed) {
        if (speed == Math.round(speed)) {
            return String.format(java.util.Locale.US, "%.1fx", speed);
        }
        return String.format(java.util.Locale.US, "%sx", speed);
    }

    private void applySpeed(float speed) {
        playbackSpeed = speed;
        if (player != null) {
            player.setPlaybackParameters(new PlaybackParameters(speed));
        }
        say("倍速 " + speedLabel(speed));
    }

    /**
     * 菜单「刷新推荐」：换一批。
     *
     * <p>保留 {@link #seen}：刚看过的不该刷新后立刻又推回来。搜索模式下先退出来，
     * 否则新推荐会被当成搜索结果混进列表。
     */
    private void refreshFeed() {
        if (loadingFeed) {
            say("正在取推荐流…");
            return;
        }
        if (searching) {
            searching = false;
            loadingSearch = false;
            searchEnded = false;
            feedBackup.clear();
        }
        items.clear();
        index = -1;
        if (player != null) {
            player.stop();
        }
        loadFeed();
    }

    // ---------------------------------------------------------------- 推荐面板

    /** 菜单「接着刷」：把当前这条之后的内容摊成一列，遥控器上下选、确定直接跳过去 */
    private void showRec() {
        if (items.isEmpty() || index < 0) {
            return;
        }
        recOpen = true;
        // 默认高亮下一条；只有一条时高亮当前这条，面板仍然给用户一个可见的确认
        recIndex = Math.min(index + 1, items.size() - 1);
        buildRecRows();
        recPanel.setVisibility(View.VISIBLE);
        recPanel.setAlpha(0f);
        recPanel.setTranslationX(-dp(16));
        recPanel.animate().alpha(1f).translationX(0f).setDuration(180).start();
        // 面板开着时别让总浮层自己收：用户在读列表
        ui.removeCallbacks(hideChrome);
    }

    private void closeRec() {
        if (!recOpen) {
            return;
        }
        recOpen = false;
        recPanel.animate().alpha(0f).translationX(-dp(16)).setDuration(140)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!recOpen) {
                            recPanel.setVisibility(View.GONE);
                        }
                    }
                }).start();
        scheduleChromeHide();
    }

    private void buildRecRows() {
        recList.removeAllViews();
        int start = index;
        int end = Math.min(items.size() - 1, start + REC_ROWS);
        for (int i = start; i <= end; i++) {
            final int position = i;
            final DyItem item = items.get(i);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_module_item);
            row.setPadding(dp(8), dp(7), dp(8), dp(7));
            row.setMinimumHeight(dp(52));
            row.setSelected(i == recIndex);

            ImageView thumb = new ImageView(getContext());
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(40), dp(52));
            thumbLp.rightMargin = dp(10);
            thumb.setLayoutParams(thumbLp);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (!TextUtils.isEmpty(item.cover)) {
                Glide.with(getContext()).load(item.cover).into(thumb);
            }
            row.addView(thumb);

            LinearLayout textBox = new LinearLayout(getContext());
            textBox.setOrientation(LinearLayout.VERTICAL);
            textBox.setLayoutParams(new LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = new TextView(getContext());
            title.setText(TextUtils.isEmpty(item.author) ? "抖音" : "@" + item.author);
            title.setTextColor(getResources().getColor(R.color.text_primary));
            title.setTextSize(13);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textBox.addView(title);

            TextView desc = new TextView(getContext());
            desc.setText(item.desc);
            desc.setTextColor(getResources().getColor(R.color.text_tertiary));
            desc.setTextSize(11);
            desc.setSingleLine(true);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            textBox.addView(desc);

            row.addView(textBox);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            row.setLayoutParams(lp);
            row.setTag(position);
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    recIndex = position;
                    jumpRec();
                }
            });
            recList.addView(row);
        }
    }

    private void updateRecSelection() {
        for (int i = 0; i < recList.getChildCount(); i++) {
            View child = recList.getChildAt(i);
            Object tag = child.getTag();
            child.setSelected(tag instanceof Integer && (Integer) tag == recIndex);
        }
    }

    private void moveRec(int delta) {
        if (!recOpen) {
            return;
        }
        int start = Math.max(0, index);
        int end = Math.min(items.size() - 1, start + REC_ROWS);
        int next = Math.max(start, Math.min(end, recIndex + delta));
        if (next == recIndex) {
            return;
        }
        recIndex = next;
        updateRecSelection();
    }

    private void jumpRec() {
        int target = recIndex;
        closeRec();
        if (target == index) {
            say("正在播这条");
            return;
        }
        show(target, target > index);
        ensureMore();
    }

    // ---------------------------------------------------------------- 互动

    /**
     * 点赞。
     *
     * <p>没导 cookie 时不去发请求（必 403），而是直接把用户带到「怎么导」那一步——
     * 按了没反应、或者只弹个「失败」，是这个功能最容易劝退人的两种写法。
     *
     * <p>已经有 cookie 却被服务端拒（过期、粘错、只粘了 {@code document.cookie}）时
     * 也进那个弹窗。这一条是必须的：整个页面里只有那个弹窗有「清除登录」，
     * 坏 cookie 不给回头路的话，用户会永远卡在「这个操作要登录 cookie」这句提示上。
     */
    private void digg() {
        commit(true);
    }

    /** 收藏 / 取消收藏。和点赞是同一条流程，只是换了个接口和一行文案 */
    private void collect() {
        commit(false);
    }

    /**
     * 发一次互动（点赞或收藏），成功后就地改这份 {@link DyItem} 的计数和状态。
     *
     * <p>两个接口合并成一个方法是值得的：它们的前置（要 cookie）、失败处理（进登录弹窗）、
     * 回投 UI 的判断（还是这一条吗）完全一样，分开写等于把「坏 cookie 必须给回头路」
     * 这条规矩抄两遍，将来只改一处就漏一处。
     *
     * @param digg {@code true} 点赞，{@code false} 收藏
     */
    private void commit(final boolean digg) {
        if (items.isEmpty() || index < 0) {
            return;
        }
        if (!client.hasLoginCookie()) {
            host.openDouyinLogin(null);
            return;
        }
        final DyItem item = items.get(index);
        final boolean on = digg ? item.diggStatus != 1 : item.collectStatus != 1;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (digg) {
                        client.digg(item.id, on);
                        item.diggStatus = on ? 1 : 0;
                        item.digg += on ? 1 : -1;
                    } else {
                        client.collect(item.id, on);
                        item.collectStatus = on ? 1 : 0;
                        item.collect += on ? 1 : -1;
                    }
                    final String done = digg
                            ? (on ? "已点赞" : "已取消点赞")
                            : (on ? "已收藏" : "已取消收藏");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (released || items.isEmpty() || index < 0
                                    || items.get(index) != item) {
                                return;
                            }
                            bind(item);
                            say(done);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, (digg ? "点赞" : "收藏") + "失败", e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (released) {
                                return;
                            }
                            // 互动失败一律进弹窗：那是全站唯一能「清除登录」的地方，
                            // 坏 cookie（过期、只粘了 document.cookie）不给回头路就是死局
                            host.openDouyinLogin(message(e));
                        }
                    });
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- 评论

    private void toggleComments() {
        if (commentsOpen) {
            // 抽屉开着时右键是「翻下一页」：上下键被换条占着，列表又注定比一屏长，
            // 不给翻页就等于只让用户看前几条。关掉走 BACK，见 handleKeyDown
            showCommentPage(commentPage + 1);
            return;
        }
        if (items.isEmpty() || index < 0) {
            return;
        }
        commentsOpen = true;
        // 抽屉开着时不再自动收浮层，不然关掉抽屉时背后空一片
        ui.removeCallbacks(hideChrome);
        commentPanel.setVisibility(View.VISIBLE);
        commentList.removeAllViews();
        commentRows.clear();
        commentHint.setText("加载中…");
        final DyItem item = items.get(index);
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<DyComment> result = null;
                String error = null;
                try {
                    result = client.comments(item.id, 0);
                } catch (Exception e) {
                    error = message(e);
                }
                final List<DyComment> value = result;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || !commentsOpen || items.isEmpty() || items.get(index) != item) {
                            return;
                        }
                        if (value == null || value.isEmpty()) {
                            // 带上出口：抽屉是按键打开的，用户得知道怎么关掉
                            commentHint.setText((failure == null ? "还没有评论" : failure)
                                    + " · 按返回关闭");
                            return;
                        }
                        commentRows.addAll(value);
                        showCommentPage(0);
                    }
                });
            }
        }).start();
    }

    /**
     * 渲染抽屉的某一页。
     *
     * <p>「一页」按可视高度算，不按条数：抖音评论长短差得离谱（一行到八行都有），
     * 写死条数一定会在某一屏上把最后一条切成半截，看着像坏了。
     *
     * <p>页码越界就绕回第一页：刷到底再按一次右键回到开头，比停在最后一屏「没反应」好读，
     * 也比把抽屉关掉更符合「右键 = 评论区的下一页」这个直觉。
     */
    private void showCommentPage(int page) {
        if (commentRows.isEmpty()) {
            return;
        }
        commentPage = page;
        if (commentRows.size() != commentList.getChildCount()) {
            commentList.removeAllViews();
            for (DyComment comment : commentRows) {
                TextView row = new TextView(getContext());
                row.setTextSize(13);
                row.setTextColor(getResources().getColor(R.color.text_secondary));
                row.setPadding(0, 0, 0, dp(12));
                row.setText(comment.user + "：" + comment.text);
                commentList.addView(row);
            }
        }
        // 刚 addView 完还没有高度，量不到可视区，只能等这一帧排完再算
        commentList.post(new Runnable() {
            @Override
            public void run() {
                if (released || !commentsOpen) {
                    return;
                }
                int viewport = commentScroll.getHeight() - commentScroll.getPaddingTop()
                        - commentScroll.getPaddingBottom();
                if (viewport <= 0) {
                    viewport = dp(480);
                }
                int content = Math.max(commentList.getHeight(), viewport);
                int pages = (content + viewport - 1) / viewport;
                commentPage = ((commentPage % pages) + pages) % pages;
                // 按整屏滚会把边界那条评论切成半截（看着像渲染坏了），
                // 所以对齐到「这一屏第一条的顶边」；最后一屏再往前没内容了，就贴到末尾
                int offset = commentPage * viewport;
                for (int i = 0; i < commentList.getChildCount(); i++) {
                    View child = commentList.getChildAt(i);
                    if (child.getTop() >= offset) {
                        offset = child.getTop();
                        break;
                    }
                }
                offset = Math.min(offset, Math.max(0, content - viewport));
                commentScroll.scrollTo(0, offset);
                commentHint.setText("共 " + commentRows.size() + " 条 · 第 " + (commentPage + 1)
                        + "/" + pages + " 屏 · 右键翻页 · 按返回关闭");
            }
        });
    }

    private void closeComments() {
        commentsOpen = false;
        commentPage = 0;
        commentRows.clear();
        commentPanel.setVisibility(View.GONE);
        scheduleChromeHide();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

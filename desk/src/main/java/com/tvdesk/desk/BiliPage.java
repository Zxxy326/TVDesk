package com.tvdesk.desk;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.tvdesk.core.bili.BiliClient;
import com.tvdesk.core.bili.model.BiliAccount;
import com.tvdesk.core.bili.model.BiliCard;
import com.tvdesk.core.bili.model.BiliStream;
import com.tvdesk.core.bili.model.LiveArea;
import com.tvdesk.core.bili.model.LiveRoom;
import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.motion.RevealAnim;
import com.tvdesk.desk.widget.GridScroll;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.widget.AbsListView;
import java.util.Map;

/**
 * 哔哩哔哩模块页（内嵌在桌面的 module_host 里）。
 *
 * <h3>六个栏目</h3>
 * 推荐 / 热门 / 排行榜 / 直播 / 我的 / 搜索。全部直连 B 站 web 接口
 * （{@link BiliClient}），没有自建后端——榜、推荐、搜索、播放地址、弹幕都能免登录拿到，
 * 只有「历史 / 收藏 / 关注」要用户自己的 cookie。
 *
 * <h3>为什么要一层「子页签」</h3>
 * 排行榜按分区切（十几个 fid）、推荐要「换一批」。它们放不进顶栏胶囊（分区名连排远超一屏宽），
 * 所以内容区标题下面再加一条横向可滚的页签（{@link #subScroll}），只在用到的栏目露出来。
 *
 * <h3>数据怎么管</h3>
 * 每个视图一份 {@link Feed}，用字符串 key 寻址（{@code "rank:4"}、{@code "live:129"}、
 * {@code "fav:98765"}、{@code "search:海阔天空"}…）。规则是「先占住 loading，后台取，
 * 结果落回 Feed，再重建行」——这样不管请求什么时候回来，界面都由同一套 {@link #rebuildRows()}
 * 画出来，切走再切回来也不会重复打接口（Feed 已经 loaded）。
 *
 * <h3>焦点</h3>
 * 和音乐页一个套路：左右走几何（同一行页签），上下在「页签 ↔ 子页签 ↔ 卡片墙」之间手动路由，
 * 卡片墙的上下按「下标 ± 列数」自己算（GridView 只挂载视口附近的卡片，
 * 下一排还没生成时系统找不到目标，会把焦点甩到看不见的地方）。
 */
public class BiliPage extends FrameLayout implements ModulePage {

    private static final String TAG = "BiliPage";

    /**
     * 宿主 DeskActivity 实现的出口。
     *
     * <p>详情页和播放器页要占满整个窗口、也要管 BACK 键，交给 Activity 压栈比在本页里
     * 再套一层容器干净（音乐页那套「页面里盖页面」会把焦点树搞乱）。
     * 登录也在这里：二维码弹窗是 Activity 级的事，不该由一个 View 去拿 WindowManager。
     */
    public interface Host {
        void openBiliVideo(BiliCard card);

        void openBiliLive(LiveRoom room);

        void openBiliLogin();
    }

    /** 栏目。顺序就是 SEC_* 的下标 */
    private static final String[] SECTIONS =
            {"推荐", "关注", "热门", "排行榜", "直播", "我的", "搜索"};

    private static final int SEC_RECOMMEND = 0;
    /** 已登录用户的关注动态（只收视频投稿） */
    private static final int SEC_FOLLOW = 1;
    private static final int SEC_POPULAR = 2;
    private static final int SEC_RANK = 3;
    private static final int SEC_LIVE = 4;
    private static final int SEC_MINE = 5;
    private static final int SEC_SEARCH = 6;

    /** 「我的」里的入口卡 */
    private static final int ENTRY_ACCOUNT = 0;
    private static final int ENTRY_LOGIN = 1;
    private static final int ENTRY_COOKIE = 2;
    private static final int ENTRY_HISTORY = 3;
    private static final int ENTRY_FAV = 4;
    private static final int ENTRY_LOGOUT = 5;

    /** 一次铺几张卡。一屏 6 列 2 排，40 张够滚两三排，再多首屏更慢 */
    private static final int PAGE_SIZE = 40;

    /** 搜索防抖。和音乐页同一个理由：不防抖的话「海阔天空」四个字要发四次请求 */
    private static final long SEARCH_DEBOUNCE_MS = 500L;

    /** 整页背景的目标不透明度。B 站封面都是亮图，压得比音乐页更暗一档才不抢文字 */
    private static final float BG_ALPHA = 0.5f;

    /** 一张卡片对应的东西。四种来源里只有一个非空 */
    static class Row {
        BiliCard card;
        LiveArea area;
        LiveRoom room;
        int entry = -1;
        /** 账号行/入口行的文案，卡片复用同一条绑定 */
        String title = "";
        String note = "";
        String badge = "";
        String cover = "";
    }

    /**
     * 一个视图的数据槽。
     *
     * <p>{@code cards/areas/rooms} 三选一，由 key 决定装哪种——省掉为每种列表再封一层类型。
     */
    private static final class Feed {
        final List<BiliCard> cards = new ArrayList<>();
        final List<LiveArea> areas = new ArrayList<>();
        final List<LiveRoom> rooms = new ArrayList<>();
        String error;
        boolean loading;
        boolean loaded;

        /**
         * 分页状态（跟着这份视图走）。
         *
         * <p>{@code nextPage} 用于页码式接口（热门 / 直播 / 收藏夹 / 搜索），
         * {@code nextOffset} 用于游标式接口（关注动态）。{@code hasMore} 为 false
         * 表示这个视图已经到底——榜单、分区目录、收藏夹列表这类一次给全的也是它。
         */
        int nextPage = 1;
        String nextOffset = "";
        boolean hasMore = true;
        boolean loadingMore;

        void clear() {
            cards.clear();
            areas.clear();
            rooms.clear();
            nextPage = 1;
            nextOffset = "";
            hasMore = true;
            loadingMore = false;
        }

        boolean isEmpty() {
            return cards.isEmpty() && areas.isEmpty() && rooms.isEmpty();
        }
    }

    private final Map<String, Feed> feeds = new HashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 登录态一变，旧请求的结果就该整包丢掉：回调里比对它 */
    private int epoch;
    private boolean released;
    /**
     * 这一趟进来还没把焦点送进内容。
     *
     * <p>「首屏数据到达就把焦点从页签送进卡片墙」只在<b>刚进页面</b>时成立。以前没有这个闸，
     * 每换一个栏目、数据一到就把焦点从页签拽走——用户正在用左右键挑栏目，手一停焦点已经
     * 掉进列表里了，遥控器上的体感是「想切到直播，结果人钻进卡片」。
     */
    private boolean autoFocusPending;
    /**
     * 用户最后落在卡片墙的第几张。
     *
     * <p>卡片墙一重排（换栏目、加载更多、内容高度变了）就会把带焦点的那个 View 回收掉，
     * 系统随即把焦点交给窗口里第一个可聚焦控件——左上角那颗「哔哩哔哩▾」。
     * 用户看到的就是「翻页翻到左上角去了」。记着原来的位置，重排后放回去。
     */
    private int lastContentPosition = -1;
    /**
     * 钻进了一层（直播分区 / 收藏夹），等这层的内容到了要把焦点送进去。
     *
     * <p>钻进那一刻内容还在路上，{@link #focusGrid} 无处可放；等数据到了再补一次。
     */
    private boolean pendingDrillFocus;

    private final List<Row> rows = new ArrayList<>();

    private final CardAdapter adapter = new CardAdapter();

    private int section = SEC_RECOMMEND;

    /** 排行榜当前分区（fid） */
    private int rankRid = 0;
    /** 推荐的「换一批」游标：只用来换 feed key，接口那边每次都是从头要新的一批 */
    private int recommendIndex = 1;
    /** 直播：非空表示已经钻进某个二级分区 */
    private LiveArea liveArea;
    /**
     * 钻到当前分区之前待着的那一层。
     *
     * <p>直播分区是两级：一级（网游）→ 二级（英雄联盟）→ 直播间。
     * 返回要一级一级退：从直播间退到「英雄联盟」那一层，再退才回到分区列表。
     * 没有这个字段，一次返回就跨两层回去了。
     */
    private LiveArea liveAreaParent;
    /** 「我的」：非空表示钻进某个收藏夹 */
    private BiliCard favFolder;
    /**
     * 钻进 {@link #liveArea}/{@link #favFolder} 之前那张卡的位置（{@link #rows} 下标）。
     *
     * <p>层级表 L3 的焦点口径：退层回到你来处的那张卡，不是列表第一张。
     * 少了它，从第 20 张卡进收藏夹、按返回会掉到左上角第一张，等于白翻页。
     */
    private int drillFromPos = 0;
    private String searchQuery = "";

    private BiliAccount account;
    private String accountError;
    private boolean accountLoading;

    private boolean menuOpen;
    /** 已经贴上去的背景地址，同一张不重复加载，避免焦点在卡片间移动时闪 */
    private String shownBgUrl;

    private final Runnable searchTask = new Runnable() {
        @Override
        public void run() {
            runSearch();
        }
    };

    private LinearLayout switcher;
    private LinearLayout switcherMenu;
    private LinearLayout navItems;
    private HorizontalScrollView subScroll;
    private LinearLayout subNav;
    private com.tvdesk.desk.widget.TileWall grid;
    private TextView title;
    private TextView subtitle;
    private TextView emptyView;
    private EditText searchInput;
    private ImageView bgImage;
    private ImageView headCover;
    private ProgressBar loadingView;

    /** 回桌面，由宿主 DeskActivity 传入 */
    private final Runnable onClose;
    private final Host host;
    /** 单独跑调试时没有宿主，允许 host/onClose 为 null */
    public BiliPage(Context context) {
        this(context, null, null);
    }

    public BiliPage(Context context, Host host, Runnable onClose) {
        super(context);
        this.host = host;
        this.onClose = onClose;
        LayoutInflater.from(context).inflate(R.layout.activity_bili, this, true);

        switcher = findViewById(R.id.bili_switcher);
        switcherMenu = findViewById(R.id.bili_menu);
        navItems = findViewById(R.id.bili_nav_items);
        subScroll = findViewById(R.id.bili_sub_scroll);
        subNav = findViewById(R.id.bili_sub_nav);
        grid = findViewById(R.id.bili_grid);
        title = findViewById(R.id.bili_title);
        subtitle = findViewById(R.id.bili_subtitle);
        emptyView = findViewById(R.id.bili_empty);
        searchInput = findViewById(R.id.bili_search);
        bgImage = findViewById(R.id.bili_bg);
        headCover = findViewById(R.id.bili_head_cover);
        loadingView = findViewById(R.id.bili_loading);

        buildSwitcher();
        buildSwitcherMenu();
        buildNav();
        buildSubNav();
        setupGrid();
        wireSearch();

        // 设备票要先有，否则第一个签名接口容易被回 -352
        BiliClient.prime(getContext());
        selectSection(SEC_RECOMMEND, false);
    }

    // ---------------------------------------------------------------- 顶栏

    private void buildSwitcher() {
        FocusScaler.attach(switcher, FocusScaler.SCALE_FOCUSED);
        switcher.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMenuOpen(!menuOpen);
            }
        });
    }

    /**
     * 胶囊下拉：本页的全局操作。
     *
     * <p>「播放设置」和播放器控制条上那颗「设置」是同一份 {@link BiliSettingsDialog}、
     * 同一份 {@link DeskPrefs}——在这里改的清晰度/倍速，下次进播放器就是那个值。
     * 「返回桌面」是本页唯一常驻出口：本页没有桌面顶栏。
     *
     * <p>不放「登录」快捷方式：登录在「我的」里，是内容的一部分而不是全局操作。
     */
    private void buildSwitcherMenu() {
        switcherMenu.removeAllViews();
        addMenuEntry("播放设置", new Runnable() {
            @Override
            public void run() {
                setMenuOpen(false);
                openPlaybackSettings();
            }
        });
        addMenuEntry("返回桌面", new Runnable() {
            @Override
            public void run() {
                closeToDesk();
            }
        });
    }

    /** 首页这边拿不到服务端清晰度列表，弹窗会用 1080P/720P/480P/360P 那组通用档位 */
    private void openPlaybackSettings() {
        if (!(getContext() instanceof Activity)) {
            return;
        }
        BiliSettingsDialog dialog = new BiliSettingsDialog((Activity) getContext(), null,
                DeskPrefs.biliQuality(getContext()), false, new BiliSettingsDialog.Listener() {
                    @Override
                    public void onQuality(int qn) {
                        DeskPrefs.setBiliQuality(getContext(), qn);
                    }

                    @Override
                    public void onSpeed(float speed) {
                        DeskPrefs.setBiliSpeed(getContext(), speed);
                    }

                    @Override
                    public void onResize(int mode) {
                        DeskPrefs.setBiliResize(getContext(), mode);
                    }

                    @Override
                    public void onDanmaku(boolean on) {
                        DeskPrefs.setBiliDanmaku(getContext(), on);
                    }

                    @Override
                    public void onSponsorBlock(boolean on) {
                        DeskPrefs.setBiliSponsorBlock(getContext(), on);
                    }

                    @Override
                    public void onPlayMode(int mode) {
                        DeskPrefs.setBiliPlayMode(getContext(), mode);
                    }

                    @Override
                    public void onDanmakuScale(float scale) {
                        DeskPrefs.setBiliDanmakuScale(getContext(), scale);
                    }

                    @Override
                    public void onDanmakuArea(int lines) {
                        DeskPrefs.setBiliDanmakuArea(getContext(), lines);
                    }

                    @Override
                    public void onDanmakuSpeed(float speed) {
                        DeskPrefs.setBiliDanmakuSpeed(getContext(), speed);
                    }

                    @Override
                    public void onDanmakuAlpha(float alpha) {
                        DeskPrefs.setBiliDanmakuAlpha(getContext(), alpha);
                    }
                });
        // 弹窗关掉时焦点原本停在菜单项上，菜单已经收起来了，不收回来会丢在桌面外
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                switcher.requestFocus();
            }
        });
        dialog.show();
    }

    private void addMenuEntry(String label, final Runnable action) {
        TextView item = new TextView(getContext());
        item.setText(label);
        item.setTextColor(getResources().getColor(R.color.text_primary));
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), 0, dp(16), 0);
        item.setBackgroundResource(R.drawable.bg_module_item);
        item.setFocusable(true);
        item.setClickable(true);
        item.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.bottomMargin = dp(2);
        item.setLayoutParams(lp);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        switcherMenu.addView(item);
    }

    private void setMenuOpen(boolean open) {
        menuOpen = open;
        switcherMenu.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open && switcherMenu.getChildCount() > 0) {
            switcherMenu.getChildAt(0).requestFocus();
        }
    }

    private void buildNav() {
        navItems.removeAllViews();
        for (int i = 0; i < SECTIONS.length; i++) {
            final int index = i;
            TextView item = new TextView(getContext());
            item.setId(View.generateViewId());
            item.setText(SECTIONS[i]);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setTextColor(AppCompatResources.getColorStateList(getContext(),
                    R.color.nav_item_text));
            item.setBackgroundResource(R.drawable.bg_nav_item);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setFocusable(true);
            item.setClickable(true);
            item.setFocusableInTouchMode(true);
            item.setPadding(getResources().getDimensionPixelSize(R.dimen.nav_item_pad_h), 0,
                    getResources().getDimensionPixelSize(R.dimen.nav_item_pad_h), 0);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            if (index > 0) {
                lp.leftMargin = dp(2);
            }
            item.setLayoutParams(lp);

            // 1.06：主界面胶囊/导航的统一档位
            FocusScaler.attach(item, 1.06f);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectSection(index, true);
                }
            });
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus || menuOpen) {
                        return;
                    }
                    if (index == section && !hasDrill()) {
                        return;
                    }
                    selectSection(index, false);
                }
            });
            // 左右键在页签上只能落到相邻页签。
            //
            // 光连 nextFocusRightId 不够：页签是横排的，下方卡片墙也在「右边」，
            // 内容一重建（切栏目、换分区）几何搜索就会先挑中卡片——遥控器上的体感
            // 是「想切到直播，人却跳进列表里」。这里直接拦按键，落到哪一格自己说了算。
            item.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return false;
                    }
                    int target = -1;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        target = index + 1;
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        target = index - 1;
                    }
                    if (target < 0 || target >= navItems.getChildCount()) {
                        return false;
                    }
                    navItems.getChildAt(target).requestFocus();
                    return true;
                }
            });
            navItems.addView(item);
        }
        // 页签之间显式连一条左右链。
        //
        // 不连的话，从「热门」按右键会直接跳到下面的卡片墙：页签是横向排列的，
        // 右边那个页签和下方第一张卡片都算「在右边」，系统的焦点搜索按几何距离挑，
        // 挑中卡片——遥控器上的体感就是「想切页签，结果人跳到内容里去了」。
        for (int i = 0; i + 1 < navItems.getChildCount(); i++) {
            View current = navItems.getChildAt(i);
            View next = navItems.getChildAt(i + 1);
            current.setNextFocusRightId(next.getId());
            next.setNextFocusLeftId(current.getId());
        }
        updateChipSelection();
    }

    /** 有没有「钻进一层」——决定焦点路过页签时要不要强制回顶层 */
    private boolean hasDrill() {
        return liveArea != null || favFolder != null
                || (section == SEC_SEARCH && !TextUtils.isEmpty(searchQuery));
    }

    private void updateChipSelection() {
        for (int i = 0; i < navItems.getChildCount(); i++) {
            navItems.getChildAt(i).setSelected(i == section);
        }
    }

    // ---------------------------------------------------------------- 子页签

    /**
     * 子页签按栏目重建：排行榜给分区，推荐给「换一批」，其余收起来。
     *
     * <p>分区表来自 {@link BiliClient#rankTabs()}（实测可用的 fid），不在这儿写死第二份——
     * B 站改分区号时只改 core 一处。
     */
    private void buildSubNav() {
        subNav.removeAllViews();
        if (section == SEC_RANK) {
            for (BiliClient.RankTab tab : BiliClient.rankTabs()) {
                addSubChip(tab.name, String.valueOf(tab.rid));
            }
        } else if (section == SEC_RECOMMEND) {
            addSubChip("换一批", "");
        } else if (section == SEC_FOLLOW) {
            // 关注流会一直有新动态：给一颗「刷新」，原地从头要一遍
            addSubChip("刷新", "");
        }
        subScroll.setVisibility(subNav.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void addSubChip(String label, final String value) {
        TextView item = new TextView(getContext());
        item.setText(label);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        item.setTextColor(AppCompatResources.getColorStateList(getContext(),
                R.color.nav_item_text));
        item.setBackgroundResource(R.drawable.bg_nav_item);
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        item.setFocusable(true);
        item.setClickable(true);
        item.setFocusableInTouchMode(true);
        int padH = dp(14);
        item.setPadding(padH, 0, padH, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        item.setLayoutParams(lp);
        FocusScaler.attach(item, 1.06f);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSubChip(value);
            }
        });
        subNav.addView(item);
    }

    private void onSubChip(String value) {
        if (section == SEC_RANK) {
            int rid = parseInt(value, 0);
            if (rid == rankRid) {
                focusGrid(0, 2);
                return;
            }
            rankRid = rid;
            rebuildRows();
            focusGrid(0, 2);
            return;
        }
        if (section == SEC_FOLLOW) {
            // 刷新：整份 feed 丢掉（页码/游标状态随之归零），从第一页重新要；
            // 顺带把网格滚回顶部 —— 否则数据变短时旧的滚动位置会停在空白处
            feeds.remove(key(SEC_FOLLOW, ""));
            rebuildRows();
            grid.scrollToTop();
            return;
        }
        if (section == SEC_RECOMMEND) {
            // 换一批：换一个 feed key，让手机推荐接口重新要一屏。新接口每次 idx=0
            // 给的都是新的一批，游标只负责把旧的那屏数据丢掉
            recommendIndex++;
            feeds.remove(key(SEC_RECOMMEND, ""));
            rebuildRows();
            focusGrid(0, 2);
        }
    }

    private void updateSubSelection() {
        if (section != SEC_RANK) {
            return;
        }
        int hit = 0;
        for (int i = 0; i < subNav.getChildCount(); i++) {
            boolean selected = i < BiliClient.rankTabs().size()
                    && BiliClient.rankTabs().get(i).rid == rankRid;
            subNav.getChildAt(i).setSelected(selected);
            if (selected) {
                hit = i;
            }
        }
        // 焦点路过切分区时，选中的那颗可能在视口外——滚进来，否则用户不知道自己在哪个区
        final int index = hit;
        subScroll.post(new Runnable() {
            @Override
            public void run() {
                View child = index < subNav.getChildCount() ? subNav.getChildAt(index) : null;
                if (child != null) {
                    subScroll.smoothScrollTo(Math.max(0, child.getLeft() - dp(40)), 0);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 栏目切换

    /**
     * @param focusContent true 表示用户按确认进来的，切完把焦点交给内容；
     *                     false 表示焦点路过，保持停在页签上
     */
    private void selectSection(int target, boolean focusContent) {
        if (target < 0 || target >= SECTIONS.length) {
            return;
        }
        section = target;
        liveArea = null;
        liveAreaParent = null;
        favFolder = null;
        updateChipSelection();
        buildSubNav();
        updateSubSelection();
        rebuildRows();
        if (focusContent) {
            if (section == SEC_SEARCH && TextUtils.isEmpty(searchQuery)) {
                focusSearch();
            } else if (!rows.isEmpty()) {
                // 隔一拍再进列表：按下确定这一下，键的 UP 可能还没走完，
                // 焦点刚落到第一张卡上就被同一次按键点开 ——
                // 表现就是「按确定想进栏目，结果直接放起了第一条」
                navItems.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!released) {
                            focusGrid(0, 2);
                        }
                    }
                }, 120);
            } else {
                focusChip();
            }
        }
    }

    /** 当前栏目该读哪个 feed key */
    private String key(int section, String suffix) {
        switch (section) {
            case SEC_RECOMMEND:
                return "recommend:" + recommendIndex;
            case SEC_FOLLOW:
                return "follow";
            case SEC_POPULAR:
                return "popular";
            case SEC_RANK:
                return "rank:" + rankRid;
            case SEC_LIVE:
                return liveArea == null ? "live" : "live:" + liveArea.id;
            case SEC_MINE:
                return favFolder == null ? "mine" : "fav:" + favFolder.favId;
            case SEC_SEARCH:
                return "search:" + suffix;
            default:
                return "history";
        }
    }

    private Feed feed(String key) {
        Feed feed = feeds.get(key);
        if (feed == null) {
            feed = new Feed();
            feeds.put(key, feed);
        }
        return feed;
    }

    // ---------------------------------------------------------------- 组行

    /**
     * 把 feed 里的数据铺成卡片行，顺带决定标题、副标题、整页背景和加载状态。
     *
     * <p>它是唯一画界面的入口：所有请求回来都只改 feed 再调这里，
     * 所以「空 / 加载中 / 出错 / 有内容」四种状态天然一致。
     */
    private void rebuildRows() {
        rows.clear();
        title.setText(SECTIONS[section]);
        String headCoverUrl = null;

        switch (section) {
            case SEC_RECOMMEND:
                appendCards(feed(key(section, "")));
                subtitle.setText("手机端推荐流 · 点「换一批」再来一轮");
                break;
            case SEC_FOLLOW:
                appendCards(feed(key(section, "")));
                subtitle.setText(BiliClient.get(getContext()).loggedIn()
                        ? "关注的 UP 主最近发的视频" : "登录后能看到关注 UP 主的更新 · 去「我的」里登录");
                break;
            case SEC_POPULAR:
                appendCards(feed(key(section, "")));
                subtitle.setText("全站综合热门，不分区");
                break;
            case SEC_RANK:
                appendCards(feed(key(section, "")));
                subtitle.setText(rankSubtitle());
                headCoverUrl = coverOfFirst();
                break;
            case SEC_LIVE:
                if (liveArea == null) {
                    title.setText("直播分区");
                    appendAreas(feed("live"));
                    subtitle.setText("选一个分区看正在播的直播间");
                } else {
                    title.setText(liveArea.name);
                    Feed sub = feed("live:" + liveArea.id);
                    if (!sub.areas.isEmpty()) {
                        // 一级分区：列子分区
                        appendAreas(sub);
                        subtitle.setText("选一个子分区看正在播的直播间");
                    } else {
                        // 二级分区：列直播间
                        appendRooms(sub);
                        subtitle.setText("按返回回分区列表");
                    }
                    headCoverUrl = liveArea.cover;
                }
                break;
            case SEC_MINE:
                if (favFolder == null) {
                    appendMine();
                    subtitle.setText(accountReady()
                            ? account.nickname + " · 按返回停在原处" : "登录后能看历史与收藏");
                    headCoverUrl = accountReady() ? account.avatar : null;
                } else {
                    title.setText(favFolder.title);
                    appendCards(feed("fav:" + favFolder.favId));
                    subtitle.setText(favFolder.badge + " · 按返回回收藏夹列表");
                    headCoverUrl = favFolder.cover;
                }
                break;
            case SEC_SEARCH:
                searchInput.setVisibility(View.VISIBLE);
                if (TextUtils.isEmpty(searchQuery)) {
                    subtitle.setText("输入关键词后自动搜，支持视频名、UP 主名、BV 号");
                } else {
                    appendCards(feed("search:" + searchQuery));
                    subtitle.setText("“" + searchQuery + "”的搜索结果");
                }
                break;
            default:
                break;
        }
        if (section != SEC_SEARCH) {
            searchInput.setVisibility(View.GONE);
        }

        String status = statusLine();
        if (!TextUtils.isEmpty(status)) {
            subtitle.setText(status);
        }
        // 所有栏目统一的分页提示：滚到底会不会再有、现在一共多少条
        Feed current = feeds.get(key(section, searchQuery));
        if (current != null && !current.cards.isEmpty() && !current.loading
                && subtitle.getVisibility() == View.VISIBLE) {
            subtitle.setText(subtitle.getText() + " · 已加载 " + current.cards.size() + " 条 · "
                    + (current.loadingMore ? "正在加载更多…"
                    : current.hasMore ? "下翻自动加载更多" : "已经到底了"));
        }
        loadingView.setVisibility(rows.isEmpty() && isBusy() ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setText(rows.isEmpty() && !isBusy() ? "这里暂时没有内容" : "");

        bindHeadCover(headCoverUrl);
        grid.notifyDataSetChanged();
        updateSubSelection();
        showBackground(sectionCover(), true);
    }

    private String sectionCover() {
        for (Row row : rows) {
            if (!TextUtils.isEmpty(row.cover)) {
                return row.cover;
            }
        }
        return null;
    }

    private String coverOfFirst() {
        for (Row row : rows) {
            if (!TextUtils.isEmpty(row.cover)) {
                return row.cover;
            }
        }
        return null;
    }

    private String rankSubtitle() {
        for (BiliClient.RankTab tab : BiliClient.rankTabs()) {
            if (tab.rid == rankRid) {
                return tab.name + "分区榜 · 近三日最热，按综合热度排";
            }
        }
        return "排行榜";
    }

    private void appendCards(Feed feed) {
        ensure(feed);
        for (BiliCard card : feed.cards) {
            Row row = new Row();
            row.card = card;
            row.title = card.title;
            row.cover = card.cover;
            row.badge = card.badge;
            row.note = cardNote(card);
            rows.add(row);
        }
    }

    private void appendAreas(Feed feed) {
        ensure(feed);
        for (LiveArea area : feed.areas) {
            Row row = new Row();
            row.area = area;
            row.title = area.name;
            row.cover = area.cover;
            row.note = area.children.isEmpty()
                    ? "进入看正在播的直播间" : area.children.size() + " 个子分区";
            rows.add(row);
        }
    }

    private void appendRooms(Feed feed) {
        ensure(feed);
        for (LiveRoom room : feed.rooms) {
            Row row = new Row();
            row.room = room;
            row.title = room.title;
            row.cover = room.cover;
            row.note = TextUtils.isEmpty(room.anchor) ? room.areaName
                    : room.anchor + " · " + room.areaName;
            row.badge = BiliClient.humanCount(room.watchCount);
            rows.add(row);
        }
    }

    /** 「我的」的入口卡。没登录时前两张就是登录入口 */
    private void appendMine() {
        ensureAccount();
        Row accountRow = new Row();
        accountRow.entry = ENTRY_ACCOUNT;
        if (accountReady()) {
            accountRow.title = account.nickname;
            accountRow.note = "Lv" + account.level + (account.vip ? " · 大会员" : "")
                    + (account.fans > 0 ? " · " + account.fans + " 粉丝" : "");
            accountRow.cover = account.avatar;
            accountRow.badge = "UID " + account.mid;
        } else {
            accountRow.title = accountLoading ? "正在确认登录态…" : "未登录";
            accountRow.note = "登录后可同步观看历史和收藏夹";
            accountRow.badge = "";
        }
        rows.add(accountRow);

        if (accountReady()) {
            rows.add(entryRow(ENTRY_HISTORY, "观看历史", "接着上次没看完的地方看", "历史"));
            rows.add(entryRow(ENTRY_FAV, "我的收藏", "自己的收藏夹，点进去看里面的视频", "收藏"));
            rows.add(entryRow(ENTRY_LOGOUT, "退出登录", "只清掉本机保存的 cookie", ""));
        } else {
            rows.add(entryRow(ENTRY_LOGIN, "扫码登录", "用手机 B 站 App 扫二维码", "登录"));
            rows.add(entryRow(ENTRY_COOKIE, "粘贴 Cookie 登录",
                    "扫码不方便时，从浏览器复制 cookie 粘进来", "登录"));
        }

        if (accountReady()) {
            appendCards(feed("fav"));
        }
    }

    private Row entryRow(int entry, String title, String note, String badge) {
        Row row = new Row();
        row.entry = entry;
        row.title = title;
        row.note = note;
        row.badge = badge;
        return row;
    }

    private boolean accountReady() {
        return account != null && account.loggedIn;
    }

    private static String cardNote(BiliCard card) {
        String up = TextUtils.isEmpty(card.up) ? "" : card.up;
        String views = card.views > 0 ? BiliClient.humanCount(card.views) + " 播放" : "";
        if (TextUtils.isEmpty(up)) {
            return views;
        }
        return TextUtils.isEmpty(views) ? up : up + " · " + views;
    }

    // ---------------------------------------------------------------- 请求

    /** feed 空且没在飞就发一次请求；已 loaded 的空结果不再重试 */
    private void ensure(Feed feed) {
        if (feed.loading || feed.loaded || !feed.isEmpty()) {
            return;
        }
        load(feed);
    }

    private void load(final Feed feed) {
        feed.loading = true;
        feed.error = null;
        final int mine = epoch;
        final String key = currentRequestKey(feed);
        grid.notifyDataSetChanged();
        loadingView.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    fetchInto(feed, key, false);
                } catch (Exception e) {
                    error = message(e);
                    feed.error = error;
                }
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || mine != epoch) {
                            return;
                        }
                        feed.loading = false;
                        feed.loaded = true;
                        if (failure != null) {
                            feed.error = failure;
                        }
                        rebuildRows();
                        // 钻进一层（分区/收藏夹）之后，这层内容到齐了才把焦点送进去
                        if (pendingDrillFocus) {
                            pendingDrillFocus = false;
                            if (!rows.isEmpty()) {
                                focusGrid(0, 2);
                            }
                        }
                        // 首屏数据到达时焦点还停在页签上，就把它送进内容。
                        // 只在刚进页面这一趟做：换栏目时焦点得留在页签上，
                        // 否则用户正在左右挑栏目，数据一到就被拽进列表。
                        if (autoFocusPending && !rows.isEmpty() && navItems.findFocus() != null) {
                            autoFocusPending = false;
                            focusGrid(0, 2);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 滚到接近底部时接下一页（所有栏目共用一套）。
     *
     * <p>每份视图自己带着分页状态：页码式的往下加一页，游标式的带上一页的 offset，
     * 一次给全的（榜单 / 分区目录 / 收藏夹列表 / 推荐一批）{@code hasMore} 是 false，
     * 这里直接不动——所以「到底了」和「本来就没有下一页」是同一条判断。
     */
    private void loadMoreCurrent() {
        Feed feed = feeds.get(key(section, searchQuery));
        if (feed == null) {
            return;
        }
        loadMore(feed);
    }

    private void loadMore(final Feed feed) {
        if (feed.loading || feed.loadingMore || !feed.loaded || !feed.hasMore) {
            return;
        }
        if (feed.cards.isEmpty() && feed.rooms.isEmpty()) {
            // 没有列表可接（空态、分区目录）就别发请求
            return;
        }
        feed.loadingMore = true;
        final int mine = epoch;
        final String key = currentRequestKey(feed);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    fetchInto(feed, key, true);
                } catch (Exception e) {
                    error = message(e);
                }
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        feed.loadingMore = false;
                        if (released || mine != epoch) {
                            return;
                        }
                        if (failure != null) {
                            feed.error = "加载更多失败：" + failure;
                        } else {
                            feed.error = null;
                            dedupe(feed);
                        }
                        rebuildRows();
                    }
                });
            }
        }).start();
    }

    /** 翻页偶尔会回带已经显示过的那条：按 bvid/aid 去重，后到的丢掉 */
    private static void dedupe(Feed feed) {
        Set<String> seen = new HashSet<>();
        for (int i = feed.cards.size() - 1; i >= 0; i--) {
            String key = cardKey(feed.cards.get(i));
            if (!seen.add(key)) {
                feed.cards.remove(i);
            }
        }
    }

    /** 翻页去重用的键 */
    private static String cardKey(BiliCard card) {
        if (!TextUtils.isEmpty(card.bvid)) {
            return card.bvid;
        }
        return "aid:" + card.aid;
    }

    /**
     * feed 的 key 反查。
     *
     * <p>后台线程只拿得到一个字符串，得靠它重新解析出「取哪个接口、什么参数」——
     * 用 key 而不是闭包，是为了让「同一个视图重复请求」天然合并（feeds 按 key 去重）。
     */
    private String currentRequestKey(Feed feed) {
        for (Map.Entry<String, Feed> entry : feeds.entrySet()) {
            if (entry.getValue() == feed) {
                return entry.getKey();
            }
        }
        return key(section, searchQuery);
    }

    /**
     * 取一页数据填进 feed。
     *
     * <p>每个分支自己更新 {@code feed.nextPage / nextOffset / hasMore}：
     * 页码式的（热门、直播、收藏夹、搜索）看这一页是不是满的，
     * 游标式的（关注动态）直接用接口给的 has_more；
     * 一次给全的（推荐一批、榜单、分区目录、收藏夹列表、历史）标记到底，
     * 滚到底不再请求。
     */
    private void fetchInto(Feed feed, String key, boolean append) throws Exception {
        BiliClient client = BiliClient.get(getContext());
        if (key.startsWith("recommend:")) {
            feed.cards.addAll(client.recommend(parseInt(key.substring(10), 1), PAGE_SIZE));
            // 推荐流是一次一批的快照，没有「下一页」，要新内容用「换一批」
            feed.hasMore = false;
        } else if (key.equals("follow")) {
            BiliClient.FollowPage page = client.followedFeed(PAGE_SIZE, feed.nextOffset);
            feed.cards.addAll(page.cards);
            feed.nextOffset = page.offset;
            feed.hasMore = page.hasMore;
        } else if (key.startsWith("popular")) {
            List<BiliCard> more = client.popular(feed.nextPage, PAGE_SIZE);
            feed.cards.addAll(more);
            feed.hasMore = more.size() >= PAGE_SIZE;
            feed.nextPage++;
        } else if (key.startsWith("rank:")) {
            feed.cards.addAll(client.ranking(parseInt(key.substring(5), 0)));
            feed.hasMore = false;
        } else if (key.equals("live")) {
            feed.areas.addAll(client.liveAreas());
            feed.hasMore = false;
        } else if (key.startsWith("live:")) {
            int areaId = parseInt(key.substring(5), 0);
            LiveArea area = findArea(areaId);
            if (area != null && !area.children.isEmpty()) {
                // 一级分区：这一层该列的是它下面的子分区（英雄联盟、CS2…），
                // 拿一级 id 去查直播间接口是查不到的，结果就是「点进去一片空白」
                feed.areas.addAll(area.children);
                feed.hasMore = false;
            } else {
                List<LiveRoom> more = client.liveRooms(parentOfArea(areaId), areaId,
                        "online", feed.nextPage);
                feed.rooms.addAll(more);
                feed.hasMore = !more.isEmpty();
                feed.nextPage++;
            }
        } else if (key.equals("fav")) {
            feed.cards.addAll(client.favFolders());
            feed.hasMore = false;
        } else if (key.startsWith("fav:")) {
            List<BiliCard> more = client.favItems(parseLong(key.substring(4)), feed.nextPage);
            feed.cards.addAll(more);
            feed.hasMore = more.size() >= 20;
            feed.nextPage++;
        } else if (key.startsWith("search:")) {
            List<BiliCard> more = client.search(key.substring(7), feed.nextPage, PAGE_SIZE);
            feed.cards.addAll(more);
            feed.hasMore = more.size() >= PAGE_SIZE;
            feed.nextPage++;
        } else if (key.equals("history")) {
            feed.cards.addAll(client.history(PAGE_SIZE));
            feed.hasMore = false;
        } else {
            throw new IllegalStateException("没有这个视图：" + key);
        }
    }

    /** 直播二级分区要一级 id，从「分区目录」那份 feed 里现找 */
    /** 在直播分区表里找一级或二级分区 */
    private LiveArea findArea(int areaId) {
        Feed areas = feeds.get("live");
        if (areas == null) {
            return null;
        }
        for (LiveArea area : areas.areas) {
            if (area.id == areaId) {
                return area;
            }
            for (LiveArea child : area.children) {
                if (child.id == areaId) {
                    return child;
                }
            }
        }
        return null;
    }

    private int parentOfArea(int areaId) {
        Feed areas = feeds.get("live");
        if (areas == null) {
            return 0;
        }
        for (LiveArea area : areas.areas) {
            for (LiveArea child : area.children) {
                if (child.id == areaId) {
                    return area.id;
                }
            }
        }
        return 0;
    }

    private void ensureAccount() {
        if (account != null || accountLoading) {
            return;
        }
        accountLoading = true;
        final int mine = epoch;
        new Thread(new Runnable() {
            @Override
            public void run() {
                BiliAccount info = null;
                String error = null;
                try {
                    info = BiliClient.get(getContext()).nav();
                } catch (Exception e) {
                    error = message(e);
                }
                final BiliAccount result = info;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || mine != epoch) {
                            return;
                        }
                        accountLoading = false;
                        account = result;
                        accountError = failure;
                        if (section == SEC_MINE) {
                            rebuildRows();
                        }
                    }
                });
            }
        }).start();
    }

    /** 登录态变了：丢掉所有已缓存视图，重画 */
    public void onLoginChanged() {
        epoch++;
        account = null;
        accountLoading = false;
        accountError = null;
        feeds.clear();
        if (!released) {
            rebuildRows();
        }
    }

    private boolean isBusy() {
        if (accountLoading) {
            return true;
        }
        for (Feed feed : feeds.values()) {
            if (feed.loading) {
                return true;
            }
        }
        return false;
    }

    /** 出错就把原因顶到副标题上，而不是只留一个「暂无内容」 */
    private String statusLine() {
        List<String> errors = new ArrayList<>();
        if (!TextUtils.isEmpty(accountError)) {
            errors.add(accountError);
        }
        Feed current = feeds.get(key(section, searchQuery));
        if (current != null && !TextUtils.isEmpty(current.error)) {
            errors.add(current.error);
        }
        return errors.isEmpty() ? "" : TextUtils.join("；", errors);
    }

    private static String message(Exception e) {
        String text = e == null ? null : e.getMessage();
        return TextUtils.isEmpty(text) ? "接口没响应，检查网络" : text;
    }

    // ---------------------------------------------------------------- 交互

    private void activate(Row row) {
        int pos = Math.max(0, rows.indexOf(row));
        if (row.entry >= 0) {
            activateEntry(row.entry, pos);
        } else if (row.room != null) {
            if (host != null) {
                hideIme();
                host.openBiliLive(row.room);
            }
        } else if (row.area != null) {
            // 现在待着的这一层就是下一层的上级
            liveAreaParent = liveArea;
            liveArea = row.area;
            drillFromPos = pos;
            pendingDrillFocus = true;
            rebuildRows();
            focusGrid(0, 2);
        } else if (row.card != null && row.card.favId > 0) {
            favFolder = row.card;
            drillFromPos = pos;
            pendingDrillFocus = true;
            rebuildRows();
            focusGrid(0, 2);
        } else if (row.card != null) {
            if (host != null) {
                hideIme();
                host.openBiliVideo(row.card);
            }
        }
    }

    private void activateEntry(int entry, int fromPos) {
        switch (entry) {
            case ENTRY_LOGIN:
            case ENTRY_COOKIE:
                if (host != null) {
                    host.openBiliLogin();
                }
                break;
            case ENTRY_HISTORY: {
                // 历史是一张「只有一批数据」的列表，直接把它当卡片墙铺开，
                // 标题改掉；BACK 时 favFolder/liveArea 都是空的，所以退到「我的」入口
                final Feed feed = feed("history");
                drillFromPos = fromPos;
                loadFeedDirectly(feed, "history");
                title.setText("观看历史");
                break;
            }
            case ENTRY_LOGOUT:
                BiliClient.get(getContext()).logout();
                onLoginChanged();
                break;
            case ENTRY_FAV:
                selectSection(SEC_MINE, false);
                break;
            default:
                break;
        }
    }

    /** 从入口卡直接拉一个视图（不走栏目 key 那套） */
    private void loadFeedDirectly(final Feed feed, final String requestKey) {
        if (!feed.isEmpty() || feed.loading) {
            return;
        }
        feed.loading = true;
        final int mine = epoch;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    fetchInto(feed, requestKey, false);
                } catch (Exception e) {
                    error = message(e);
                }
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || mine != epoch) {
                            return;
                        }
                        feed.loading = false;
                        feed.loaded = true;
                        feed.error = failure;
                        favFolder = null;
                        rebuildHistoryView();
                    }
                });
            }
        }).start();
    }

    private void rebuildHistoryView() {
        rows.clear();
        title.setText("观看历史");
        subtitle.setText("接着上次没看完的地方看 · 按返回回「我的」");
        appendCards(feed("history"));
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setText(feed("history").error);
        grid.notifyDataSetChanged();
        focusGrid(0, 2);
    }

    // ---------------------------------------------------------------- 搜索

    private void wireSearch() {
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(searchTask);
                ui.postDelayed(searchTask, SEARCH_DEBOUNCE_MS);
            }
        });
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    ui.removeCallbacks(searchTask);
                    runSearch();
                    return true;
                }
                return false;
            }
        });
        searchInput.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    hideIme();
                    if (!rows.isEmpty()) {
                        focusGrid(0, 2);
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    hideIme();
                    focusChip();
                    return true;
                }
                return false;
            }
        });
    }

    private void runSearch() {
        String keyword = searchInput.getText().toString().trim();
        if (TextUtils.equals(keyword, searchQuery)) {
            return;
        }
        searchQuery = keyword;
        if (section != SEC_SEARCH) {
            return;
        }
        rebuildRows();
    }

    private void focusSearch() {
        if (searchInput.getVisibility() != View.VISIBLE) {
            return;
        }
        searchInput.requestFocus();
        searchInput.setSelection(searchInput.getText().length());
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------- 焦点

    private void setupGrid() {
        grid.setAdapter(adapter);
        grid.setNumColumns(GRID_COLUMNS);
        grid.setSpacingDp(20f, 24f);
        // 到边：往上交回页签，其余回弹（以前是 atHorizontalEdge 逐键判，现在由墙自己算）
        grid.setEdgeListener(new com.tvdesk.desk.widget.TileWall.OnEdgeListener() {
            @Override
            public boolean onEdge(int keyCode, int index) {
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP) {
                    return false;
                }
                if (subScroll.getVisibility() == View.VISIBLE && subNav.getChildCount() > 0) {
                    subNav.getChildAt(0).requestFocus();
                } else {
                    focusChip();
                }
                return true;
            }
        });
        // 滚到离底部还有两行时就去接下一页（关注动态一页只有 20 条，热门/直播同理）
        grid.setScrollListener(new com.tvdesk.desk.widget.TileWall.ScrollListener() {
            @Override
            public void onScroll(int firstRow, int lastRow, int totalRows) {
                if (totalRows > 0 && lastRow >= totalRows - 2) {
                    loadMoreCurrent();
                }
            }
        });
    }

    /** B 站卡片墙 6 列（沿用原来 GridView 的排版） */
    private static final int GRID_COLUMNS = 6;

    private void focusChip() {
        if (section < navItems.getChildCount()) {
            navItems.getChildAt(section).requestFocus();
        }
    }

    /** 把焦点放到第 position 张卡上（滚动与补焦由墙一次做完） */
    private void focusGrid(final int position, final int retries) {
        if (gone() || adapter.getCount() == 0) {
            // 内容还没到：留在原地，别把人甩到页签上。
            // 点了分区却「人自己跑回页签」比「暂时没动」更让人以为点错了
            return;
        }
        int target = Math.max(0, Math.min(position, adapter.getCount() - 1));
        if (grid.focusPosition(target)) {
            lastContentPosition = target;
        }
    }

    /**
     * 卡片墙的上下走位交给 {@code TileWall}：全量挂载 + 显式焦点图，
     * 不再需要「下一排还没挂载 → 几何寻焦猜错 → 页面拦一下」这一套。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && grid.getVisibility() == View.VISIBLE
                && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            // 顶栏页签这一层到边：最左/最右一颗别再往外跑
            View focused = findFocus();
            if (focused != null && navItems != null
                    && navItems.indexOfChild(focused) >= 0) {
                int index = navItems.indexOfChild(focused);
                if (index >= 0) {
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && index == 0) {
                        return true;
                    }
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                            && index == navItems.getChildCount() - 1) {
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean moveGridVertical(boolean down) {
        View focused = findFocus();
        if (focused == null) {
            return false;
        }
        int position = grid.getPositionForView(focused);
        if (position < 0) {
            return false;
        }
        int columns = Math.max(1, grid.getNumColumns());
        if (down) {
            int next = position + columns;
            if (next < adapter.getCount()) {
                focusGrid(next, 4);
            }
            return true;
        }
        int previous = position - columns;
        if (previous >= 0) {
            focusGrid(previous, 4);
            return true;
        }
        // 已经在第一行：往上一层是子页签（换一批 / 选分区），再往上是栏目页签。
        // 注意这里给的是页签那一排，不是左上角那颗模块切换器——后者是再往上一层的事。
        if (subScroll.getVisibility() == View.VISIBLE && subNav.getChildCount() > 0) {
            subNav.getChildAt(0).requestFocus();
        } else {
            focusChip();
        }
        return true;
    }

    @Override
    public void onEnter() {
        released = false;
        autoFocusPending = true;
        post(new Runnable() {
            @Override
            public void run() {
                focusContentIfIdle();
            }
        });
        postDelayed(new Runnable() {
            @Override
            public void run() {
                focusContentIfIdle();
            }
        }, 300);
    }

    /** 焦点还空着或停在页签上，就把它挪进内容；用户已经自己按到别处时不抢 */
    private void focusContentIfIdle() {
        if (gone()) {
            return;
        }
        View focused = currentFocus();
        // 只在焦点真的空着、或者还停在左上角那颗模块切换器上时才送进内容。
        // 焦点已经在页签上（用户正在左右挑栏目）或在内容里（正在浏览）都不抢：
        // 抢一次就等于把用户从「挑栏目」改成「看内容」，遥控器上像是自己乱跳。
        if (focused != null && switcher.findFocus() == null) {
            return;
        }
        if (section == SEC_SEARCH && TextUtils.isEmpty(searchQuery)) {
            focusChip();
        } else if (!rows.isEmpty()) {
            focusGrid(0, 2);
        } else {
            focusChip();
        }
    }

    private View currentFocus() {
        Context context = getContext();
        if (context instanceof Activity) {
            return ((Activity) context).getCurrentFocus();
        }
        return findFocus();
    }

    /** 宿主不在了。所有异步回调动界面之前先问这个 */
    private boolean gone() {
        Context context = getContext();
        return !(context instanceof Activity)
                || ((Activity) context).isFinishing() || ((Activity) context).isDestroyed();
    }

    // ---------------------------------------------------------------- 背景

    /**
     * 整页背景。用当前视图的封面解码成一张小图再拉满屏——
     * 放大插值本身就是最便宜的模糊，不用引 blur 库也不吃 GPU（和音乐页同一个手法）。
     */
    private void showBackground(String url, boolean force) {
        if (bgImage == null) {
            return;
        }
        if (TextUtils.isEmpty(url)) {
            if (!force) {
                return;
            }
            shownBgUrl = null;
            bgImage.animate().alpha(0f).setDuration(320).start();
            return;
        }
        if (TextUtils.equals(url, shownBgUrl)) {
            return;
        }
        shownBgUrl = url;
        try {
            Glide.with(getContext())
                    .load(url)
                    .override(200, 112)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(420))
                    .into(bgImage);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "背景图加载失败：" + url, th);
            return;
        }
        bgImage.animate().alpha(BG_ALPHA).setDuration(420).start();
    }

    /** 标题左边的方图，给「现在看的是哪个分区/哪张收藏夹」一个锚点 */
    private void bindHeadCover(String url) {
        if (headCover == null) {
            return;
        }
        if (TextUtils.isEmpty(url)) {
            Glide.with(getContext()).clear(headCover);
            headCover.setImageDrawable(null);
            headCover.setVisibility(View.GONE);
            return;
        }
        headCover.setVisibility(View.VISIBLE);
        try {
            Glide.with(getContext()).load(url).centerCrop().into(headCover);
        } catch (Throwable th) {
            headCover.setImageDrawable(null);
        }
    }

    // ---------------------------------------------------------------- ModulePage

    @Override
    public boolean handleKeyDown(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BACK && menuOpen) {
            setMenuOpen(false);
            switcher.requestFocus();
            return Back.consume("哔哩哔哩·收起胶囊菜单");
        }
        // 焦点兜底：任何方向键先把它救回页签，否则遥控器看起来像失灵
        if (findFocus() == null && keyCode >= KeyEvent.KEYCODE_DPAD_UP
                && keyCode <= KeyEvent.KEYCODE_DPAD_CENTER) {
            focusChip();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && navItems.findFocus() != null) {
            if (subScroll.getVisibility() == View.VISIBLE && subNav.getChildCount() > 0) {
                subNav.getChildAt(0).requestFocus();
            } else if (section == SEC_SEARCH && TextUtils.isEmpty(searchQuery)) {
                focusSearch();
            } else if (!rows.isEmpty()) {
                focusGrid(0, 2);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && subNav.hasFocus()) {
            focusChip();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 层级表顺序：L2 胶囊菜单（在上面那个 if）→ L3 页内子视图 → L4 输入态 → 交回宿主
            if (favFolder != null) {
                favFolder = null;
                rebuildRows();
                focusGrid(drillFromPos, 2);
                return Back.consume("哔哩哔哩·退出收藏夹");
            }
            if (liveArea != null) {
                // 逐级退：二级分区退回一级分区，一级分区才退回分区列表
                LiveArea parent = liveAreaParent;
                liveAreaParent = null;
                liveArea = parent;
                rebuildRows();
                focusGrid(drillFromPos, 2);
                return Back.consume("哔哩哔哩·退出"
                        + (parent == null ? "直播分区" : parent.name));
            }
            if (section == SEC_MINE && isHistoryView()) {
                selectSection(SEC_MINE, false);
                focusGrid(drillFromPos, 2);
                return Back.consume("哔哩哔哩·退出观看历史");
            }
            if (section == SEC_SEARCH && !TextUtils.isEmpty(searchQuery)) {
                searchQuery = "";
                searchInput.setText("");
                hideIme();
                rebuildRows();
                focusSearch();
                return Back.consume("哔哩哔哩·清空搜索词");
            }
            // 顶栏是内容的上一层：焦点不在页签行上时，返回只把焦点收回来，
            // 再按一次才退出模块（统一走 BackTop，见那个类的注释）
            if (BackTop.toTopBar("哔哩哔哩", navItems, new Runnable() {
                @Override
                public void run() {
                    focusChip();
                }
            })) {
                return true;
            }
            return Back.pass("哔哩哔哩·页内已无层级");
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            setMenuOpen(!menuOpen);
            return true;
        }
        return false;
    }

    /** 「我的」里正在看的是不是历史列表（历史不是栏目，是一次性的钻入视图） */
    private boolean isHistoryView() {
        for (Row row : rows) {
            if (row.card != null && "历史".equals(row.card.section)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onRelease() {
        ui.removeCallbacks(searchTask);
        // 页实例被宿主缓存复用，这里只断回调，不清数据：
        // 切走再切回来不该重新打一遍接口，但回来时要能保证焦点不落空
        released = true;
    }

    // ---------------------------------------------------------------- 卡片

    /**
     * 一张卡的稳定标识。
     *
     * <p>视频用 bvid、直播间用 roomId、分区用 id、入口用编号 —— 重排/翻页之后
     * 下标会变，这些不会，墙靠它把焦点找回来。
     */
    private static String keyOf(Row row) {
        if (row == null) {
            return null;
        }
        if (row.card != null) {
            if (!TextUtils.isEmpty(row.card.bvid)) {
                return "c:" + row.card.bvid;
            }
            if (row.card.aid > 0) {
                return "c:" + row.card.aid;
            }
            if (row.card.favId > 0) {
                return "c:" + row.card.favId;
            }
            return "c:" + row.card.title;
        }
        if (row.room != null) {
            return "r:" + row.room.roomId;
        }
        if (row.area != null) {
            return "a:" + row.area.id;
        }
        if (row.entry >= 0) {
            return "e:" + row.entry;
        }
        return "t:" + row.title;
    }

    private final class CardAdapter implements com.tvdesk.desk.widget.TileWall.Adapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        /**
         * 卡片的稳定标识。
         *
         * <p>视频用 bvid、直播间用 roomId、分区用 id、入口用编号 —— 重排/翻页之后
         * 下标会变，这些不会，{@code TileWall} 靠它把焦点找回来。
         */
        @Override
        public String keyAt(int index) {
            return index >= 0 && index < rows.size() ? keyOf(rows.get(index)) : null;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View root = convertView;
            final boolean fresh = root == null;
            if (root == null) {
                root = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_bili_card, parent, false);
            }
            final Row row = rows.get(position);
            TextView name = root.findViewById(R.id.bili_card_name);
            TextView note = root.findViewById(R.id.bili_card_note);
            TextView badge = root.findViewById(R.id.bili_card_badge);
            ImageView img = root.findViewById(R.id.bili_card_img);

            name.setText(row.title);
            note.setText(row.note);
            note.setVisibility(TextUtils.isEmpty(row.note) ? View.GONE : View.VISIBLE);
            badge.setText(row.badge);
            badge.setVisibility(TextUtils.isEmpty(row.badge) ? View.GONE : View.VISIBLE);

            bindCover(img, row.cover);
            PosterCorners.clip(img,
                    getResources().getDimensionPixelSize(R.dimen.poster_radius) - dp(3));

            // GridView 的 item 默认不可聚焦，得自己开
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            final String cover = row.cover;
            FocusScaler.attach(root, FocusScaler.SCALE_FOCUSED,
                    new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        showBackground(cover, false);
                    }
                }
            });
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activate(row);
                }
            });

            if (!fresh || root.isFocused()) {
                RevealAnim.reset(root);
            } else {
                RevealAnim.staggerIn(root, position, dp(10));
            }
            return root;
        }
    }

    /**
     * 封面。B 站的图全在 {@code *.hdslb.com}，不带 Referer 也能取，
     * 但地址一半是 {@code //i0.hdslb.com/...} 这种省掉协议的写法——已经在 core 补成 https 了。
     */
    private void bindCover(ImageView view, String url) {
        if (view == null) {
            return;
        }
        if (TextUtils.isEmpty(url)) {
            Glide.with(getContext()).clear(view);
            view.setImageDrawable(null);
            return;
        }
        try {
            Glide.with(getContext()).load(url).centerCrop().into(view);
        } catch (Throwable th) {
            view.setImageDrawable(null);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void closeToDesk() {
        setMenuOpen(false);
        if (onClose != null) {
            onClose.run();
        }
    }

    /** 宿主在压入详情页之前调用，让本页先把 IME 收掉 */
    public void prepareToCover() {
        hideIme();
        setMenuOpen(false);
    }
}

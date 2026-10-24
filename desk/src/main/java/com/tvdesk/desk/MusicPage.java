package com.tvdesk.desk;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
import android.view.animation.LinearInterpolator;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.github.catvod.music.LxManager;
import com.github.catvod.music.LxRuntime;
import com.github.catvod.music.LxSource;
import com.github.catvod.music.NeteaseClient;
import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.motion.PressFx;
import com.tvdesk.desk.motion.RevealAnim;
import com.tvdesk.desk.widget.GridScroll;
import com.tvdesk.desk.widget.LyricView;
import com.tvdesk.desk.widget.WaveProgressView;

import androidx.appcompat.content.res.AppCompatResources;

import org.json.JSONObject;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 音乐模块页（内嵌在桌面的 module_host 里，不再是独立 Activity）。
 *
 * <h3>自己的顶栏</h3>
 * 音乐页不再借桌面那条顶栏——桌面的页签是内容源的类目（电影/电视剧…），和音乐没关系。
 * 现在它自带一条，视觉语言照搬桌面（bg_pill 胶囊 + bg_nav_item 页签 + FocusScaler 1.06）：
 * <ul>
 *   <li>左上「音乐 ▾」：点开是回桌面的入口（音乐页没有桌面顶栏了，这是唯一常驻出口）；</li>
 *   <li>居中五个栏目：每日推荐 / 排行榜 / 推荐歌单 / 我的 / 搜索。</li>
 * </ul>
 *
 * <h3>在线和本地各占一半</h3>
 * 元数据和播放链接是两条完全不同的路，所以这一个页面里有两套数据源：
 * <ul>
 *   <li><b>元数据</b>（榜单、推荐歌单、账号歌单、搜索）走 {@link NeteaseClient}——直连网易云
 *       的 {@code /weapi/*}。音源脚本的契约里没有这些（脚本只声明了 {@code musicUrl} 一个动作，
 *       实测也是），所以这部分搬不了，只能自己实现。</li>
 *   <li><b>播放链接</b>走音源脚本——在线曲目只带一个网易云 id，播之前要由
 *       {@link LxRuntime} 把 id 换成能播的直链（这一步几十秒，见 {@link #playOnline}）。</li>
 * </ul>
 * 五个栏目的分工：
 * <ul>
 *   <li>每日推荐：登录后取网易云的每日推荐；没登录／取不到就回落本地（按日期做种挑 20 首，当天顺序不变）；</li>
 *   <li>排行榜：第一行永远是「本地播放榜」（自己的记账，没网也看得到），下面是网易云的榜单目录；</li>
 *   <li>推荐歌单：网易云的推荐歌单；</li>
 *   <li>我的：账号（登录／我喜欢的音乐／我的歌单）+ 本地歌曲 / 歌手 / 专辑 / 文件夹 / 音源；</li>
 *   <li>搜索：网易云和本地曲库一起搜，在线结果排前面。</li>
 * </ul>
 * 登录用 {@link NeteaseLoginDialog}（WebView 扫码，或粘 cookie）——无头登录接口全被风控挡死了，
 * 具体原因记在 {@link NeteaseClient} 的类注释里。
 *
 * <h3>焦点</h3>
 * 左右在页签之间走几何搜索（同一行）；上下在「页签 ↔ 列表」之间手动路由，
 * 不靠几何：列表空的时候按 DOWN 会掉到播放条上，用户就回不去页签了。
 * 列表行也是可聚焦的（{@code setItemsCanFocus(true)}）——items can focus 之后 ListView
 * 不再派发 onItemClick，点击必须在 getView 里自己挂到行上，这是这套组合最容易踩的坑。
 */
public class MusicPage extends FrameLayout implements ModulePage {

    private static final String TAG = "MusicPage";

    /** 顶部栏栏目。顺序就是 SEC_* 常量的下标 */
    private static final String[] SECTIONS = {"每日推荐", "排行榜", "推荐歌单", "我的", "搜索"};

    private static final int SEC_RECOMMEND = 0;
    private static final int SEC_RANK = 1;
    private static final int SEC_PLAYLIST = 2;
    private static final int SEC_MINE = 3;
    private static final int SEC_SEARCH = 4;

    /** 「我的」里的曲库视图。顺序就是 MINE_* 常量的下标 */
    private static final String[] MINE_TABS = {"本地歌曲", "歌手", "专辑", "文件夹", "音源"};

    private static final int MINE_SONG = 0;
    private static final int MINE_ARTIST = 1;
    private static final int MINE_ALBUM = 2;
    private static final int MINE_FOLDER = 3;
    private static final int MINE_SOURCE = 4;

    /**
     * 「我的」里另外两个入口。不往 {@link #MINE_TABS} 里塞：那个数组是本地曲库的分组方式
     * （下标就是分组算法要用的 keyTab），这两个是在线数据，混进去会把两边都搞乱。
     */
    private static final int MINE_LIKED = 5;
    private static final int MINE_MY_PLAYLISTS = 6;

    /** 排行榜第一行的「本地播放榜」。它不是分组、也不是在线歌单，用一个哨兵值占住 drillKey */
    private static final String LOCAL_RANK_KEY = "__local_rank__";

    /** 网易云那边唯一认的平台 id。音源脚本里的平台 key 就用它 */
    private static final String WY = "wy";

    private static final int REQ_AUDIO = 1001;

    /** Android 13 起读音频改成了这个权限；targetSdk 还是 28，两个都申请最省事 */
    private static final String PERM_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO";

    /** 每日推荐挑几首 */
    private static final int DAILY_COUNT = 20;
    /** 排行榜最多铺几首 */
    private static final int RANK_LIMIT = 50;
    /** 推荐歌单里一张专辑至少要有几首才算「一张」 */
    private static final int PLAYLIST_MIN_TRACKS = 2;

    private static final String PREFS = "music_stats";
    private static final String KEY_PLAYS = "plays";

    /** 在线曲目一次取几首。榜单动辄两百首，铺满就行，不做翻页 */
    private static final int ONLINE_TRACK_LIMIT = 300;
    /** 在线搜索取几首 */
    private static final int SEARCH_LIMIT = 40;
    /** 榜单 / 推荐歌单取几个 */
    private static final int ONLINE_LIST_LIMIT = 40;
    /**
     * 搜索的防抖：输入框每敲一个字就会走一遍 rebuildRows，
     * 不防抖的话「海阔天空」四个字要发四次请求，前三次全是废的。
     * 500ms 是「手停了才发」和「按完马上看到」之间的折中。
     */
    private static final long SEARCH_DEBOUNCE_MS = 500;

    /** 一首歌 */
    static class Track {
        long id;
        String title;
        String artist;
        String album;
        String path;
        /** Android 10+ 的目录字段。DATA 在分区存储下可能拿不到，文件夹分组靠它兜底 */
        String relativePath;
        long duration;
        /**
         * 网易云歌曲 id。非空表示这是一首在线曲目：它既没有 MediaStore id 也没有本地文件，
         * 播放前得先让音源脚本把 id 换成直链（见 {@link #playOnline}）。
         */
        String onlineId;
        /** 在线曲目的专辑封面；本地歌曲为空（MediaStore 那点缩略图不值得为它多查一次） */
        String coverUrl;

        /** 是不是在线曲目 */
        boolean isOnline() {
            return !TextUtils.isEmpty(onlineId);
        }

        /** 分组键：歌手 / 专辑 / 文件夹 */
        String keyOf(int mineTab) {
            if (mineTab == MINE_ARTIST) {
                return TextUtils.isEmpty(artist) ? "未知歌手" : artist;
            }
            if (mineTab == MINE_ALBUM) {
                return TextUtils.isEmpty(album) ? "未知专辑" : album;
            }
            if (mineTab == MINE_FOLDER) {
                String folder = folderName(relativePath);
                if (!TextUtils.isEmpty(folder)) {
                    return folder;
                }
                if (TextUtils.isEmpty(path)) {
                    return "未知目录";
                }
                File parent = new File(path).getParentFile();
                return parent == null ? "未知目录" : parent.getName();
            }
            return "";
        }

        /** "Music/周杰伦/范特西/" → "范特西" */
        private static String folderName(String relative) {
            if (TextUtils.isEmpty(relative)) {
                return "";
            }
            String trimmed = relative;
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            int slash = trimmed.lastIndexOf('/');
            return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        }
    }

    /** 列表里的一行：要么是一首歌，要么是一个分组，要么是一条音源/平台，要么是「我的」入口 */
    static class Row {
        String title;
        String sub;
        String tail;
        String key;      // 分组键；null 表示不是分组
        Track track;     // 歌曲行才有
        /** 音源行才有 */
        com.github.catvod.music.LxSource source;
        /** 平台行才有 */
        com.github.catvod.music.LxSource.Platform platform;
        /** 「我的」入口行才有，值是 MINE_* */
        int mineTarget = -1;
        /** 音源页顶部的「扫码导入」入口行 */
        boolean importSource;
        /** 在线歌单/榜单行才有 */
        NeteaseClient.Playlist playlist;
        /** 「我的」里账号那一行才有 */
        boolean accountRow;
        /** 这一行的封面（专辑图 / 歌单封面 / 头像）。空表示没有，行上留占位底 */
        String coverUrl;
        /** 没有封面时的占位字符（分组行按歌手/专辑/文件夹给不同字） */
        String glyph;
    }

    private final List<Track> allTracks = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Track> queue = new ArrayList<>();

    /** 已导入的 LX 音源脚本 */
    private final List<com.github.catvod.music.LxSource> sources = new ArrayList<>();
    /** 正在测试的音源文件名，避免重复点确定起多个 runtime */
    private String testingSource;

    /**
     * 已经初始化过的音源运行时，按文件名缓存。
     *
     * <p>混淆过的脚本首跑要 15~20 秒（解字符串表），如果每次取链接都新建 runtime
     * 重新执行一遍，点一个平台就要等 20 秒，完全没法用。所以测完不销毁，
     * 后续取链接直接复用——那一步只需要几百毫秒。
     */
    private final java.util.Map<String, com.github.catvod.music.LxRuntime> runtimes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 本地播放次数。key 见 {@link #statKey(Track)} */
    private final Map<String, Integer> plays = new LinkedHashMap<>();

    /** 当前栏目（SEC_*） */
    private int section = SEC_RECOMMEND;
    /** 「我的」子视图：-1 表示停在入口列表 */
    private int mineTab = -1;
    /** 非空表示已经钻进某个歌单 / 歌手 / 专辑 / 文件夹 / 音源，列表只剩它下面的内容 */
    private String drillKey;
    private String drillLabel;
    /**
     * 钻进下一层（在线歌单 / 子列表 / 「我的」子页）之前焦点停在哪一行。
     *
     * <p>层级表 L3 的焦点口径：退层回到你来处的那一行，不是列表第一行。
     * 由 {@link #rememberDrillFrom()} 在钻入的那一刻从焦点自己算出来。
     */
    private int drillFromIndex;
    /**
     * 下一次 {@link #rebuildRows()} 出内容时要把焦点放进第几行；-1 表示没有待办。
     *
     * <p>钻进去的那一层数据是异步的：按下确定时列表还是空的，当场要焦点要不到，
     * 等歌单真回来了又没人补——遥控器就掉在模块胶囊上。所以把「一会儿要把焦点放这儿」
     * 记下来，交给数据到达时的那次重建。
     */
    private int pendingContentFocus = -1;
    /** 搜索框里的词 */
    private String searchQuery = "";

    private boolean menuOpen;

    // ---------------------------------------------------------------- 在线状态
    //
    // 在线数据一律「先发请求、结果落在字段里、再重建列表」，各栏目只是把这些字段铺成行。
    // 好处是不管请求什么时候回来，界面都是同一套代码画出来的；代价是每个栏目都要有
    // 「空 / 加载中 / 出错」三种状态，下面这些字段就是为此存在的。

    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * 会话代号。登录态一变，之前发出去的请求结果就都是上一份会话的了——
     * 各自的回调里拿它比对，对不上就整包丢掉，不然会把上一个账号的推荐刷上来。
     */
    private int onlineEpoch;

    /** 榜单目录 */
    private List<NeteaseClient.Playlist> onlineCharts;
    private String chartsError;
    private boolean chartsLoading;

    /** 推荐歌单 */
    private List<NeteaseClient.Playlist> onlinePlaylists;
    private String playlistsError;
    private boolean playlistsLoading;

    /**
     * 钻进某张在线歌单。榜单 / 推荐歌单 / 我的歌单 / 我喜欢的音乐共用这一组状态——
     * 它们在网易云那边本来就是同一种东西，没必要各写一套。
     */
    private String onlinePlaylistId;
    private String onlinePlaylistName;
    /** 这张歌单的封面。整页背景和标题左边的方图都用它 */
    private String onlinePlaylistCover;
    private List<Track> onlinePlaylistTracks;
    private String onlinePlaylistError;
    private boolean onlinePlaylistLoading;

    /** 每日推荐（要登录才有） */
    private List<Track> onlineDaily;
    private String dailyError;
    private boolean dailyLoading;

    /** 账号 + 账号歌单 */
    private NeteaseClient.Account account;
    private List<NeteaseClient.Playlist> myPlaylists;
    private String accountError;
    private String myPlaylistsError;
    private boolean accountLoading;
    /** 「我喜欢的音乐」在网易云那边也是一张歌单，从账号歌单里认出来的那张 */
    private String likedPlaylistId;
    /** playlists 里读不到 specialType 时的兜底名字 */
    private static final String LIKED_NAME = "我喜欢的音乐";

    /** 搜索 */
    private List<Track> onlineSearch;
    private String onlineSearchFor;
    private String searchError;
    private boolean searchLoading;
    private String searchPendingFor;
    private String searchDoneFor;
    private final Runnable searchTask = new Runnable() {
        @Override
        public void run() {
            runSearch();
        }
    };

    /** 记住哪个音源能出网易云的链接，省得每次播放都去翻一遍平台列表 */
    private String wySourceFile;
    /** {@link #findWySource} 顺手记下网易云的平台描述——音质要从它声明的列表里挑 */
    private LxSource.Platform wyPlatform;

    /**
     * 正在后台取播放链接的那一首。
     *
     * <p>它同时兼任两件事：给播放条一个「正在取链接…」的由头（否则几十秒里界面毫无反应，
     * 用户会以为遥控器坏了），以及当作一把锁——同一时刻只放一个取链接任务进来。
     */
    private Track resolvingTrack;

    private LinearLayout switcher;
    private LinearLayout switcherMenu;
    private LinearLayout navItems;
    private ListView list;
    /**
     * 内容卡片墙。除了「音源管理」，所有版块都铺成和影视页一样的海报卡片：
     * 每日推荐 / 榜单目录 / 榜单曲目 / 推荐歌单 / 歌单曲目 / 我的 / 本地分组 / 搜索。
     */
    private GridView grid;
    /** 当前视图是一串歌时露出来的「播放全部」 */
    private TextView playAllBtn;
    private TextView title;
    private TextView subtitle;
    private TextView emptyView;
    private EditText searchInput;
    private View bar;
    private TextView nowTitle;
    private TextView nowMeta;
    private ImageView nowCover;
    private TextView nowNote;
    private ImageView likeBtn;
    private ImageView modeBtn;
    private ImageView toggleBtn;
    /** 整页的内容背景，跟着栏目/焦点走 */
    private ImageView bgImage;
    /** 标题左边的封面，给「现在看的是哪一张」一个锚点 */
    private ImageView headCover;
    /** 当前已经贴上去的背景图地址。同一张不重复加载，避免焦点在行间移动时闪 */
    private String shownBgUrl;

    // ---- 沉浸播放页（盖在本页上面的一层，见 view_music_stage.xml）----
    private View stage;
    private ImageView stageBg;
    private View stageTurntable;
    private View stageDisc;
    private ImageView stageCover;
    private TextView stageNote;
    private LyricView stageLyric;
    private TextView stageTitle;
    private TextView stageMeta;
    private TextView stagePos;
    private TextView stageDur;
    private WaveProgressView stageProgress;
    private ImageView stageToggle;
    private ImageView stageLike;
    private ImageView stageMode;
    private ImageView expandBtn;
    private boolean stageOpen;
    /** 播放页底部的进度条 + 五颗按钮。播放时空闲整块淡出，只剩盘和词 */
    private View stageControls;
    private boolean stageChromeHidden;
    /** 胶片盘的旋转。暂停时 pause 而不是 cancel：cancel 会把盘角弹回零点，看着像卡了一下 */
    private ValueAnimator discSpin;
    /** 歌词请求在路上的那一首，兼作「同一首不重复发」的锁 */
    private Track lyricFor;
    /** LRC 原文缓存，键见 {@link #lyricKey} */
    private final Map<String, String> lyricCache = new LinkedHashMap<>();
    /** 本地歌要先搜出网易云 id 才有歌词，这一步的对应关系也记住 */
    private final Map<String, String> lyricIdCache = new LinkedHashMap<>();

    /** 播放页开着时每 0.3 秒对一次进度：进度条、时间、歌词都靠它推 */
    private final Runnable stageTick = new Runnable() {
        @Override
        public void run() {
            if (!stageOpen) {
                return;
            }
            syncStageProgress();
            ui.postDelayed(this, 300);
        }
    };

    /** 控制区收起的空闲时长。暂停时不计时，不然用户刚想按暂停，按钮先没了 */
    private static final long STAGE_CHROME_IDLE_MS = 6000L;

    private final Runnable stageChromeHide = new Runnable() {
        @Override
        public void run() {
            if (!stageOpen || stageChromeHidden) {
                return;
            }
            boolean playing = false;
            try {
                playing = player != null && player.isPlaying();
            } catch (Throwable ignored) {
            }
            if (playing) {
                hideStageControls();
            }
        }
    };

    /** 右上「正在播放」胶囊：长列表里直达播放条 / 播放页的入口 */
    private View nowChip;
    private TextView nowChipText;
    /** 宿主弹窗里选了「继续播放」：下一次 onLeave 不停声（一次性） */
    private boolean keepPlayingOnce;
    /** 上一次 onLeave 是自己掐断的，回来时接回去 */
    private boolean pausedOnLeave;

    private MediaPlayer player;
    private int queueIndex = -1;
    private Track nowPlaying;

    // ---- 播放模式：顺序 / 随机 / 单首循环 ----
    private static final int MODE_ORDER = 0;
    private static final int MODE_SHUFFLE = 1;
    private static final int MODE_ONE = 2;

    private int playMode = MODE_ORDER;

    /**
     * 随机模式走过的下标。随机没有「上一首」可言——直接退 queueIndex-1 会回到
     * 随机之前的那一首，而不是用户刚听过的那一首，所以走过的路要自己记着。
     */
    private final List<Integer> past = new ArrayList<>();

    private final Random random = new Random();

    /**
     * 已收藏的网易云 id。只在登录后去取，取到之前 likeBtn 一直是空心。
     *
     * <p>本地歌不在这个集合的语义里：收藏是网易云的功能，本地曲库没有「喜欢」这一说，
     * 所以本地歌只有在搜出对应的网易云 id 之后才可能点亮（见 {@link #neteaseIdOf}）。
     */
    private final Set<String> liked = new HashSet<>();
    private boolean likedLoaded;

    /** 当前视图铺卡片（true）还是列表行（false，只有音源管理） */
    private boolean cardMode;

    private final TrackAdapter adapter = new TrackAdapter();
    private final CardAdapter cardAdapter = new CardAdapter();

    /** 中文按拼音排，不然「周杰伦」会排到「A-Lin」后面 */
    private final Collator collator = Collator.getInstance(Locale.CHINA);

    /** 回桌面。由宿主 DeskActivity 传入（closeModulePage + 把焦点还给切换器） */
    private final Runnable onClose;

    /** 单独跑 MusicActivity 调试时没有宿主可回，允许为 null */
    public MusicPage(Context context) {
        this(context, null);
    }

    public MusicPage(Context context, Runnable onClose) {
        super(context);
        this.onClose = onClose;
        LayoutInflater.from(context).inflate(R.layout.activity_music, this, true);

        switcher = findViewById(R.id.music_switcher);
        switcherMenu = findViewById(R.id.music_menu);
        navItems = findViewById(R.id.music_nav_items);
        list = findViewById(R.id.music_list);
        grid = findViewById(R.id.music_grid);
        playAllBtn = findViewById(R.id.music_play_all);
        title = findViewById(R.id.music_title);
        subtitle = findViewById(R.id.music_subtitle);
        emptyView = findViewById(R.id.music_empty);
        searchInput = findViewById(R.id.music_search);
        bar = findViewById(R.id.music_bar);
        nowTitle = findViewById(R.id.music_now_title);
        nowMeta = findViewById(R.id.music_now_meta);
        nowCover = findViewById(R.id.music_now_cover);
        nowNote = findViewById(R.id.music_now_note);
        likeBtn = findViewById(R.id.music_like);
        modeBtn = findViewById(R.id.music_mode);
        toggleBtn = findViewById(R.id.music_toggle);
        bgImage = findViewById(R.id.music_bg);
        headCover = findViewById(R.id.music_head_cover);
        nowChip = findViewById(R.id.music_now_chip);
        nowChipText = findViewById(R.id.music_now_chip_text);

        playMode = DeskPrefs.musicPlayMode(getContext());

        loadPlays();
        // 音源列表要在播放之前就位：在线曲目取链接时得知道哪个脚本能出网易云的链接，
        // 等用户点到播放才去扫目录就来不及了（那一下要几十秒）
        preloadSources();
        buildSwitcher();
        buildSwitcherMenu();
        buildNav();
        setupList();
        setupBar();
        setupNowChip();
        setupStage();
        setupPlayAll();
        wireSearch();
        selectSection(SEC_RECOMMEND, -1, false);
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
     * 胶囊下拉。目前两项：回桌面、音源管理。
     *
     * <p>回桌面必须在这里——音乐页没有桌面顶栏了，这是唯一常驻的出口。
     * 音源是从「我的」里提出来的快捷方式：它是这个页面唯一需要外部文件（脚本）的操作，
     * 埋在两级菜单里不好找。
     */
    private void buildSwitcherMenu() {
        switcherMenu.removeAllViews();
        addMenuEntry("返回桌面", new Runnable() {
            @Override
            public void run() {
                closeToDesk();
            }
        });
        addMenuEntry("音源", new Runnable() {
            @Override
            public void run() {
                setMenuOpen(false);
                selectSection(SEC_MINE, MINE_SOURCE, true);
            }
        });
        addMenuEntry("扫码导入音源", new Runnable() {
            @Override
            public void run() {
                setMenuOpen(false);
                enterMineTab(MINE_SOURCE);
                openSourceImport();
            }
        });
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

    /** 关页回桌面。onClose 为空（单独调试）时什么也不做 */
    private void closeToDesk() {
        setMenuOpen(false);
        if (onClose != null) {
            onClose.run();
        }
    }

    private void buildNav() {
        navItems.removeAllViews();
        for (int i = 0; i < SECTIONS.length; i++) {
            final int index = i;
            TextView item = new TextView(getContext());
            item.setText(SECTIONS[i]);
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

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            if (index > 0) {
                lp.leftMargin = dp(2);
            }
            item.setLayoutParams(lp);

            // 1.06：主界面胶囊/导航的统一档位，别自用 1.04
            FocusScaler.attach(item, 1.06f);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectSection(index, -1, true);
                }
            });
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus || menuOpen) {
                        return;
                    }
                    if (index == section && drillKey == null && mineTab < 0) {
                        return;
                    }
                    selectSection(index, -1, false);
                }
            });

            navItems.addView(item);
        }
        updateChipSelection();
    }

    private void updateChipSelection() {
        for (int i = 0; i < navItems.getChildCount(); i++) {
            navItems.getChildAt(i).setSelected(i == section);
        }
    }

    /**
     * 切栏目。
     *
     * @param mineTarget 「我的」的子视图（MINE_*），-1 表示停在入口列表；只在 section==SEC_MINE 时有意义
     * @param focusList  true 表示切完把焦点交给列表（用户按确定进来的），
     *                   false 表示保持焦点在页签上（焦点路过触发的切换）
     */
    private void selectSection(int mineTargetSection, int mineTarget, boolean focusList) {
        if (mineTargetSection < 0 || mineTargetSection >= SECTIONS.length) {
            return;
        }
        section = mineTargetSection;
        mineTab = (section == SEC_MINE) ? mineTarget : -1;
        drillKey = null;
        drillLabel = null;
        // 切栏目就是回上一层：在线歌单也跟着退掉，否则从「排行榜」切到「推荐歌单」
        // 会直接看到上一张榜单的歌，标题还写着它的名字
        onlinePlaylistId = null;
        onlinePlaylistName = null;
        onlinePlaylistCover = null;
        onlinePlaylistTracks = null;
        onlinePlaylistError = null;
        // 上次失败过就允许再试一次
        resetSectionErrors(section);
        updateChipSelection();
        rebuildRows();
        if (focusList) {
            if (section == SEC_SEARCH && rows.isEmpty()) {
                focusSearch();
            } else if (adapter.getCount() > 0) {
                focusList();
            } else {
                focusChip();
            }
        }
    }

    private void focusChip() {
        if (section < navItems.getChildCount()) {
            navItems.getChildAt(section).requestFocus();
        }
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
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    /** 输入框里即时过滤本地曲库，不用再按「搜索」 */
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
                searchQuery = s.toString();
                if (section == SEC_SEARCH) {
                    rebuildRows();
                }
            }
        });
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE) {
                    hideIme();
                    if (adapter.getCount() > 0) {
                        focusList();
                    }
                    return true;
                }
                return false;
            }
        });
        // EditText 会自己吃掉上下键（当成光标移动），焦点出不去。单行输入框里
        // 上下键没有编辑含义，截走交给宿主的路由更稳
        searchInput.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN && keyCode != KeyEvent.KEYCODE_DPAD_UP) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        hideIme();
                        if (adapter.getCount() > 0) {
                            focusList();
                        }
                    } else {
                        hideIme();
                        focusChip();
                    }
                }
                return true;
            }
        });
    }

    @Override
    public void onEnter() {
        requestAudioPermission();
        // 上次离开时是自己掐断的（开关没开），回来接上；用户按过暂停的不会走到这里
        if (pausedOnLeave && player != null) {
            pausedOnLeave = false;
            try {
                player.start();
                updateBar();
            } catch (Throwable ignored) {
            }
        }
        // 进页就直接站在内容上：焦点优先给列表第一行，页签按 UP 回来即可。
        // 曲库是异步扫的，所以补一拍——第二拍只在焦点还没动过的情况下才抢。
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

    /**
     * 焦点还空着、或者还停在页签上，就把它挪到内容列表。
     *
     * <p>用户已经自己按到别处（比如 300ms 内就按了 DOWN）时不抢——
     * 半路把焦点抢走比不抢更难受。
     */
    private void focusContentIfIdle() {
        if (gone()) {
            return;
        }
        View focused = currentFocus();
        if (focused != null && navItems.findFocus() == null && switcher.findFocus() == null) {
            return;
        }
        if (section == SEC_SEARCH && TextUtils.isEmpty(searchQuery)) {
            focusChip();
        } else if (adapter.getCount() > 0) {
            focusList();
        } else {
            focusChip();
        }
    }

    private View currentFocus() {
        Context c = getContext();
        if (c instanceof Activity) {
            return ((Activity) c).getCurrentFocus();
        }
        return findFocus();
    }

    /** 宿主（DeskActivity/MusicActivity）不在了。所有 UI 回调先问这个再动界面 */
    private boolean gone() {
        Context c = getContext();
        return !(c instanceof Activity)
                || ((Activity) c).isFinishing() || ((Activity) c).isDestroyed();
    }

    private void setupList() {
        // items can focus 之后 ListView 不派发 onItemClick，点击在 getView 里挂
        list.setItemsCanFocus(true);
        list.setSelector(new ColorDrawable(0));
        list.setAdapter(adapter);

        // 卡片墙同影视页：GridView 自己不参与焦点，焦点落在卡片根上
        grid.setAdapter(cardAdapter);
        grid.setSelector(new ColorDrawable(0));
        grid.setFocusable(false);
    }

    // ---------------------------------------------------------------- 播放次数

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 记账用的键。有 MediaStore id 就用 id（换名字也不丢），否则退回「歌名|歌手」 */
    private static String statKey(Track t) {
        if (t.id > 0) {
            return "id:" + t.id;
        }
        String raw = safe(t.title) + "|" + safe(t.artist);
        return raw.replace('\t', ' ').replace('\n', ' ');
    }

    private void loadPlays() {
        plays.clear();
        String raw = prefs().getString(KEY_PLAYS, "");
        for (String line : raw.split("\n")) {
            int sep = line.lastIndexOf('\t');
            if (sep <= 0) {
                continue;
            }
            try {
                plays.put(line.substring(0, sep), Integer.parseInt(line.substring(sep + 1)));
            } catch (Throwable ignored) {
            }
        }
    }

    private void savePlays() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : plays.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(entry.getKey()).append('\t').append(entry.getValue());
        }
        prefs().edit().putString(KEY_PLAYS, sb.toString()).apply();
    }

    private void countPlay(Track track) {
        if (track == null || track.id <= 0) {
            // 在线试听（音源探测）不计入本地排行榜
            return;
        }
        String key = statKey(track);
        Integer old = plays.get(key);
        plays.put(key, old == null ? 1 : old + 1);
        savePlays();
    }

    private int playCount(Track track) {
        Integer value = plays.get(statKey(track));
        return value == null ? 0 : value;
    }

    // ---------------------------------------------------------------- 数据

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            loadTracks();
            return;
        }
        boolean granted = getContext().checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            loadTracks();
            return;
        }
        Context c = getContext();
        if (!(c instanceof Activity)) {
            showEmpty("宿主不是 Activity，无法申请存储权限。");
            return;
        }
        List<String> wanted = new ArrayList<>();
        wanted.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(PERM_READ_MEDIA_AUDIO);
        }
        ((Activity) c).requestPermissions(wanted.toArray(new String[0]), REQ_AUDIO);
    }

    /** 宿主收到权限结果后转进来 */
    public void onPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQ_AUDIO) {
            return;
        }
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
            }
        }
        if (granted) {
            loadTracks();
        } else {
            showEmpty("没有读取本地文件的权限，扫不到音乐。\n在系统设置 → 应用 → TVDesk → 权限里打开后重新进来。");
        }
    }

    /**
     * 扫本地音频。
     *
     * <p>放在子线程：上千首的曲库查一次要几百毫秒，冷启动时在主线程做会直接掉帧。
     * MediaStore 的 IS_MUSIC 已经帮我们滤掉了铃声、通知音和录音。
     */
    private void loadTracks() {
        showEmpty("正在扫描本地音乐…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Track> found = new ArrayList<>();
                Cursor cursor = null;
                try {
                    boolean hasRelativePath = Build.VERSION.SDK_INT >= 29;
                    List<String> columns = new ArrayList<>();
                    columns.add(MediaStore.Audio.Media._ID);
                    columns.add(MediaStore.Audio.Media.TITLE);
                    columns.add(MediaStore.Audio.Media.ARTIST);
                    columns.add(MediaStore.Audio.Media.ALBUM);
                    columns.add(MediaStore.Audio.Media.DATA);
                    columns.add(MediaStore.Audio.Media.DURATION);
                    if (hasRelativePath) {
                        columns.add(MediaStore.Audio.Media.RELATIVE_PATH);
                    }
                    String[] projection = columns.toArray(new String[0]);
                    String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0";
                    cursor = getContext().getContentResolver().query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            projection, selection, null, null);
                    while (cursor != null && cursor.moveToNext()) {
                        Track t = new Track();
                        t.id = cursor.getLong(0);
                        t.title = cursor.getString(1);
                        t.artist = cursor.getString(2);
                        t.album = cursor.getString(3);
                        t.path = cursor.getString(4);
                        t.duration = cursor.getLong(5);
                        if (hasRelativePath) {
                            t.relativePath = cursor.getString(6);
                        }
                        if (TextUtils.isEmpty(t.title) && TextUtils.isEmpty(t.path)) {
                            continue;
                        }
                        found.add(t);
                    }
                } catch (Throwable th) {
                    android.util.Log.w(TAG, "扫描本地音乐失败", th);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone()) {
                            return;
                        }
                        allTracks.clear();
                        allTracks.addAll(found);
                        android.util.Log.i(TAG, "本地音乐 " + allTracks.size() + " 首");
                        rebuildRows();
                    }
                });
            }
        }, "music-scan").start();
    }

    // ---------------------------------------------------------------- 列表装配

    private void rebuildRows() {
        // 先记下焦点当前停在内容里的第几行：notifyDataSetChanged 会让这一行连同别的
        // 一起被回收，焦点静默掉到页面第一个可聚焦的控件（模块胶囊）上。在线子视图的
        // 数据是异步回来的，回来时没人补焦——用户看到的就是「歌单点开了，遥控器没了」。
        final int keepFocusFrom = contentFocusIndex();
        rows.clear();
        boolean searchVisible = section == SEC_SEARCH;
        searchInput.setVisibility(searchVisible ? View.VISIBLE : View.GONE);
        if (!searchVisible && searchInput.hasFocus()) {
            hideIme();
        }
        // 每个分支自己决定空态文案；先把上一条清掉，否则切栏目时会留着上一个栏目的空态
        emptyView.setVisibility(View.GONE);
        countPlaysForRank();
        // 退登录 / cookie 过期之后可能还停在某个在线子视图上，先退回「我的」入口，
        // 不然会显示一个空列表，用户不知道发生了什么
        if (section == SEC_MINE && !netease().loggedIn()
                && (mineTab == MINE_LIKED || mineTab == MINE_MY_PLAYLISTS)) {
            mineTab = -1;
        }
        ensureOnlineData();

        if (section == SEC_RECOMMEND) {
            buildRecommendRows();
        } else if (section == SEC_RANK) {
            buildRankRows();
        } else if (section == SEC_PLAYLIST) {
            buildPlaylistSection();
        } else if (section == SEC_MINE) {
            buildMineSection();
        } else {
            buildSearchRows();
        }

        // 内容一律铺卡片；只有「音源」是设置型列表，保留行式
        cardMode = !(section == SEC_MINE && mineTab == MINE_SOURCE);
        notifyContentChanged();
        applyContentMode();
        if (rows.isEmpty()) {
            // 分支没给具体解释就兜一句通用的，别把空列表留成一片黑
            if (emptyView.getVisibility() != View.VISIBLE) {
                showEmpty("这里还没有内容。");
            }
        } else {
            emptyView.setVisibility(View.GONE);
        }
        // 重建后焦点可能落在已移除的行/卡片上，交回页签更稳
        if (rows.isEmpty() && (list.hasFocus() || grid.hasFocus() || playAllBtn.hasFocus())) {
            focusChip();
        } else if (playAllBtn.getVisibility() != View.VISIBLE && playAllBtn.hasFocus()) {
            focusChip();
        }
        if (pendingContentFocus >= 0) {
            // 刚钻进来：新层说了算，别去追旧层那一行（那个下标在新列表里没有意义）
            if (!rows.isEmpty()) {
                int want = pendingContentFocus;
                pendingContentFocus = -1;
                focusList(want, 3);
            }
        } else if (keepFocusFrom >= 0) {
            keepContentFocus(keepFocusFrom, 3);
        }
        syncBackground();
    }

    /** 焦点在内容里是第几行；不在内容里（页签、胶囊、播放条）返回 -1 */
    private int contentFocusIndex() {
        View focused = findFocus();
        if (focused == null) {
            return -1;
        }
        if (grid.hasFocus()) {
            return grid.getPositionForView(focused);
        }
        if (list.hasFocus()) {
            return list.getPositionForView(focused);
        }
        return -1;
    }

    /** 重排吃掉焦点后把它放回原来那一行 */
    private void keepContentFocus(final int position, final int retries) {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gone() || list.hasFocus() || grid.hasFocus() || retries <= 0) {
                    return;
                }
                focusList(position, retries - 1);
            }
        }, 40);
    }

    /** ListView 和卡片墙各一份适配器，数据是同一份 rows，两个都得刷新 */
    private void notifyContentChanged() {
        adapter.notifyDataSetChanged();
        cardAdapter.notifyDataSetChanged();
    }

    private void applyContentMode() {
        boolean hasRows = !rows.isEmpty();
        grid.setVisibility(cardMode && hasRows ? View.VISIBLE : View.GONE);
        list.setVisibility(!cardMode && hasRows ? View.VISIBLE : View.GONE);
        playAllBtn.setVisibility(hasTrackRows() ? View.VISIBLE : View.GONE);
    }

    /** 当前视图是不是一串可以直接开播的歌 */
    private boolean hasTrackRows() {
        for (Row row : rows) {
            if (row.track != null) {
                return true;
            }
        }
        return false;
    }

    private void buildRecommendRows() {
        title.setText("每日推荐");
        boolean logged = netease().loggedIn();

        if (logged && dailyLoading) {
            buildTrackRows(new ArrayList<Track>(), false);
            subtitle.setText("正在取网易云今日推荐…");
            showEmpty("正在从网易云取今天的推荐…");
            return;
        }
        if (logged && onlineDaily != null && !onlineDaily.isEmpty()) {
            buildTrackRows(onlineDaily, false);
            subtitle.setText("网易云每日推荐 · " + rows.size() + " 首 · 按「确定」播放");
            return;
        }

        // 没登录 / 取失败 / 取回来是空的：三种情况都回落本地曲库。
        // 不在没登录时干等着：本地这一份是白送的，先把列表填满，原因写在副标题里。
        String note;
        if (!logged) {
            note = "没登录网易云，下面是按今天日期从本地曲库挑的";
        } else if (TextUtils.isEmpty(dailyError)) {
            note = "网易云今天没给出推荐，下面是本地曲库挑的";
        } else {
            note = "网易云取推荐失败（" + dailyError + "），下面是本地曲库挑的";
        }
        List<Track> picks = dailyPicks();
        if (picks.isEmpty()) {
            buildTrackRows(new ArrayList<Track>(), false);
            subtitle.setText("");
            showEmpty(allTracks.isEmpty()
                    ? "没有找到本地音乐。\n往设备里放几首歌，或者插上 U 盘再进来。\n（在线推荐要在「我的」里登录网易云）"
                    : "本地曲库还不够挑出一份歌单。");
            return;
        }
        buildTrackRows(picks, false);
        subtitle.setText(note + " · " + picks.size() + " 首");
    }

    /**
     * 每日推荐：按日期做种子（年×10000+月×100+日），把本地曲库洗一遍取前 20 首。
     *
     * <p>同一天内反复进出顺序完全一致——每天变、当天不变，才像一份「今日歌单」，
     * 而不是每次点进来都重新掷骰子。
     */
    private List<Track> dailyPicks() {
        List<Track> pool = new ArrayList<>(allTracks);
        if (pool.isEmpty()) {
            return pool;
        }
        Calendar now = Calendar.getInstance();
        long seed = now.get(Calendar.YEAR) * 10000L
                + (now.get(Calendar.MONTH) + 1) * 100L
                + now.get(Calendar.DAY_OF_MONTH);
        Collections.shuffle(pool, new Random(seed));
        int count = Math.min(DAILY_COUNT, pool.size());
        return new ArrayList<>(pool.subList(0, count));
    }

    /** 排行榜的数据来源在 rebuildRows 里算好，这里只是按它铺行 */
    private List<Track> rankedTracks;

    private void buildRankRows() {
        // 钻进本地播放榜
        if (TextUtils.equals(drillKey, LOCAL_RANK_KEY)) {
            List<Track> ranked = rankedTracks == null ? new ArrayList<Track>() : rankedTracks;
            buildTrackRows(ranked, true);
            title.setText("本地播放榜");
            if (ranked.isEmpty()) {
                subtitle.setText("");
                showEmpty("还没有播放记录。\n在「我的」里挑几首歌听过之后，这里会按播放次数排出来。");
                return;
            }
            subtitle.setText("按本地播放次数排的 " + ranked.size() + " 首 · 按「返回」回上级");
            return;
        }
        // 钻进某个网易云榜单
        if (onlinePlaylistId != null) {
            buildOnlinePlaylistRows();
            return;
        }

        title.setText("排行榜");
        // 本地播放榜永远排第一行：它是这个页面自己的记账，断网也在，
        // 而且用户点开「排行榜」时想看的经常就是自己听得最多的那几首
        Row local = new Row();
        local.title = "本地播放榜";
        local.key = LOCAL_RANK_KEY;
        local.sub = "按你这台设备上的播放次数";
        local.tail = rankedTracks == null || rankedTracks.isEmpty()
                ? "" : rankedTracks.size() + " 首";
        rows.add(local);

        if (chartsLoading) {
            subtitle.setText("正在拉取网易云榜单…");
            return;
        }
        if (chartsError != null) {
            subtitle.setText("网易云榜单取不到：" + chartsError + " · 再进一次这个栏目可以重试");
            return;
        }
        if (onlineCharts == null || onlineCharts.isEmpty()) {
            subtitle.setText("按「确定」看本地播放榜");
            return;
        }
        for (NeteaseClient.Playlist playlist : onlineCharts) {
            Row row = new Row();
            row.title = playlist.name;
            row.playlist = playlist;
            row.coverUrl = playlist.coverUrl;
            row.sub = TextUtils.isEmpty(playlist.updateFrequency)
                    ? "网易云榜单" : ("网易云 · " + playlist.updateFrequency);
            row.tail = playlist.trackCount > 0 ? playlist.trackCount + " 首" : "";
            rows.add(row);
        }
        subtitle.setText("网易云榜单 " + onlineCharts.size() + " 个 · 按「确定」看曲目");
    }

    private void countPlaysForRank() {
        if (section != SEC_RANK) {
            return;
        }
        List<Track> ranked = new ArrayList<>();
        for (Track t : allTracks) {
            if (playCount(t) > 0) {
                ranked.add(t);
            }
        }
        Collections.sort(ranked, new Comparator<Track>() {
            @Override
            public int compare(Track a, Track b) {
                int diff = playCount(b) - playCount(a);
                return diff != 0 ? diff : collator.compare(safe(a.title), safe(b.title));
            }
        });
        if (ranked.size() > RANK_LIMIT) {
            ranked = new ArrayList<>(ranked.subList(0, RANK_LIMIT));
        }
        rankedTracks = ranked;
    }

    private void buildPlaylistSection() {
        // 钻进一张在线歌单
        if (onlinePlaylistId != null) {
            buildOnlinePlaylistRows();
            return;
        }
        // 钻进一张本地专辑（列表末尾兜底的那些行）
        if (drillKey != null) {
            List<Track> tracks = filterByKey(MINE_ALBUM, drillKey);
            buildTrackRows(tracks, true);
            title.setText(drillLabel == null ? "推荐歌单" : drillLabel);
            subtitle.setText(tracks.isEmpty() ? "" : tracks.size() + " 首 · 按「返回」回上级");
            return;
        }

        title.setText("推荐歌单");
        if (playlistsLoading) {
            subtitle.setText("正在拉取网易云推荐歌单…");
        } else if (playlistsError != null) {
            subtitle.setText("网易云推荐歌单取不到：" + playlistsError + " · 再进一次这个栏目可以重试");
        } else if (onlinePlaylists != null && !onlinePlaylists.isEmpty()) {
            for (NeteaseClient.Playlist playlist : onlinePlaylists) {
                Row row = new Row();
                row.title = playlist.name;
                row.playlist = playlist;
            row.coverUrl = playlist.coverUrl;
                row.sub = TextUtils.isEmpty(playlist.creator)
                        ? "网易云推荐" : ("网易云 · by " + playlist.creator);
                row.tail = playlist.trackCount > 0 ? playlist.trackCount + " 首" : "";
                rows.add(row);
            }
            subtitle.setText("网易云推荐 " + onlinePlaylists.size() + " 张 · 按「确定」看曲目");
        } else {
            subtitle.setText("网易云暂时没给推荐，下面这些是本地的");
        }

        // 本地专辑垫在后面：这个栏目在线取不到东西的时候也得有内容，
        // 不然断一次网整个栏目就是一片黑。够一整张的才列（一首一张不叫歌单）。
        Map<String, Integer> counter = countGroups(MINE_ALBUM);
        List<String> albumKeys = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counter.entrySet()) {
            if (entry.getValue() >= PLAYLIST_MIN_TRACKS) {
                albumKeys.add(entry.getKey());
            }
        }
        Collections.sort(albumKeys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return collator.compare(a, b);
            }
        });
        for (String key : albumKeys) {
            Row row = new Row();
            row.title = key;
            row.key = key;
            row.sub = "本地专辑";
            row.tail = counter.get(key) + " 首";
            rows.add(row);
        }

        if (rows.isEmpty()) {
            subtitle.setText("");
            showEmpty(allTracks.isEmpty()
                    ? "没有找到本地音乐。\n往设备里放几首歌，或者插上 U 盘再进来。"
                    : "本地曲库里还没有够 " + PLAYLIST_MIN_TRACKS + " 首的专辑。\n再往同一个专辑里放几首就会出现。");
        }
    }

    private void buildMineSection() {
        if (mineTab < 0) {
            buildMineEntries();
            return;
        }
        if (mineTab == MINE_SOURCE) {
            buildSourceRows();
            return;
        }
        if (onlinePlaylistId != null) {
            buildOnlinePlaylistRows();
            return;
        }
        if (mineTab == MINE_LIKED || mineTab == MINE_MY_PLAYLISTS) {
            buildMineOnline();
            return;
        }
        if (drillKey != null) {
            buildTrackRows(filterByKey(mineTab, drillKey), true);
            title.setText(drillLabel == null ? MINE_TABS[mineTab] : drillLabel);
            subtitle.setText(MINE_TABS[mineTab] + " · " + rows.size() + " 首 · 按「返回」回上级");
            return;
        }
        if (mineTab == MINE_SONG) {
            buildTrackRows(allTracks, true);
            title.setText("本地歌曲");
            subtitle.setText(rows.isEmpty() ? "" : rows.size() + " 首 · 按「确定」播放");
            return;
        }
        buildGroupRows(mineTab);
        title.setText(MINE_TABS[mineTab]);
        subtitle.setText(rows.isEmpty() ? "" : rows.size() + " 个 · 按「确定」进去");
    }

    /**
     * 「我的」的入口列表。
     *
     * <p>账号行永远排第一：登录态决定了下面两个在线入口在不在，而且这是全页面唯一能找到
     * 登录入口的地方——藏到胶囊菜单里的话，没登录的用户根本不知道这里能登。
     */
    private void buildMineEntries() {
        title.setText("我的");

        Row accountRow = new Row();
        accountRow.accountRow = true;
        if (netease().loggedIn()) {
            String nickname = account == null ? "" : account.nickname;
            accountRow.title = TextUtils.isEmpty(nickname) ? "网易云账号" : nickname;
            accountRow.sub = "网易云 · 已登录"
                    + (accountLoading ? "（正在读取账号…）" : "");
            accountRow.tail = "按「确定」管理";
        } else {
            accountRow.title = "登录网易云";
            accountRow.sub = "登录后才能拿每日推荐、我喜欢的音乐和账号歌单";
            accountRow.tail = "扫码或粘 cookie";
        }
        accountRow.coverUrl = account == null ? "" : account.avatarUrl;
        rows.add(accountRow);

        if (netease().loggedIn()) {
            Row liked = new Row();
            liked.title = LIKED_NAME;
            liked.mineTarget = MINE_LIKED;
            liked.coverUrl = playlistCoverById(likedPlaylistId);
            liked.sub = "网易云 · 我喜欢的音乐";
            liked.tail = TextUtils.isEmpty(likedPlaylistId) ? "读取中…" : "按「确定」打开";
            rows.add(liked);

            Row likedGroup = new Row();
            likedGroup.title = "我的歌单";
            likedGroup.mineTarget = MINE_MY_PLAYLISTS;
            likedGroup.sub = "网易云 · 账号歌单";
            likedGroup.tail = myPlaylists == null ? "读取中…" : (myPlaylists.size() + " 张");
            rows.add(likedGroup);
        }

        for (int i = 0; i < MINE_TABS.length; i++) {
            Row row = new Row();
            row.title = MINE_TABS[i];
            row.mineTarget = i;
            if (i == MINE_SONG) {
                row.sub = "本地曲库";
                row.tail = allTracks.isEmpty() ? "" : allTracks.size() + " 首";
            } else if (i == MINE_ARTIST) {
                row.sub = "按歌手分组";
            } else if (i == MINE_ALBUM) {
                row.sub = "按专辑分组";
            } else if (i == MINE_FOLDER) {
                row.sub = "按文件夹分组";
            } else if (i == MINE_SOURCE) {
                row.sub = "LX 脚本";
                row.tail = sources.isEmpty() ? "" : sources.size() + " 个";
            }
            rows.add(row);
        }
        subtitle.setText(mineSummary());
    }

    private String mineSummary() {
        List<String> parts = new ArrayList<>();
        if (netease().loggedIn()) {
            parts.add("已登录网易云");
        }
        if (!allTracks.isEmpty()) {
            parts.add("本地 " + allTracks.size() + " 首");
        }
        return TextUtils.join(" · ", parts);
    }

    /** 「我喜欢的音乐」和「我的歌单」。两者数据来源相同（账号歌单），一起铺 */
    private void buildMineOnline() {
        title.setText(mineTab == MINE_LIKED ? LIKED_NAME : "我的歌单");
        if (accountLoading || myPlaylists == null) {
            if (myPlaylistsError != null) {
                subtitle.setText("读取失败：" + myPlaylistsError + " · 按「返回」回上级，再进来可以重试");
                showEmpty("账号歌单没读到：\n" + myPlaylistsError);
                return;
            }
            subtitle.setText("正在读取账号歌单…");
            showEmpty("正在读取你的网易云歌单…");
            return;
        }
        for (NeteaseClient.Playlist playlist : myPlaylists) {
            Row row = new Row();
            row.title = playlist.name;
            row.playlist = playlist;
            row.coverUrl = playlist.coverUrl;
            row.sub = playlist.trackCount > 0 ? playlist.trackCount + " 首" : "";
            row.tail = TextUtils.equals(playlist.id, likedPlaylistId) ? "我喜欢的音乐" : "";
            rows.add(row);
        }
        if (rows.isEmpty()) {
            subtitle.setText("");
            showEmpty("账号里还没有歌单。");
            return;
        }
        subtitle.setText(rows.size() + " 张 · 按「确定」看曲目");
    }

    /** 打开一张在线歌单。榜单 / 推荐歌单 / 我的歌单走的都是这里 */
    private void openPlaylist(NeteaseClient.Playlist playlist) {
        if (playlist == null || TextUtils.isEmpty(playlist.id)) {
            return;
        }
        onlinePlaylistId = playlist.id;
        onlinePlaylistName = playlist.name;
        onlinePlaylistCover = playlist.coverUrl;
        onlinePlaylistTracks = null;
        onlinePlaylistError = null;
        rememberDrillFrom();
        rebuildRows();
        focusList();
    }

    /** 打开「我喜欢的音乐」。它在网易云那边也是一张歌单，id 从账号歌单里认出来 */
    private void openLiked() {
        if (TextUtils.isEmpty(likedPlaylistId)) {
            toast(myPlaylistsError != null
                    ? "账号歌单没读到：" + myPlaylistsError
                    : "还在读取账号歌单，稍等一下再按");
            return;
        }
        onlinePlaylistId = likedPlaylistId;
        onlinePlaylistName = LIKED_NAME;
        onlinePlaylistCover = playlistCoverById(likedPlaylistId);
        onlinePlaylistTracks = null;
        onlinePlaylistError = null;
        // 得同时把 mineTab 定在「我喜欢的音乐」上：buildMineSection 先看 mineTab，
        // 停在 -1 的话只认「我的」入口列表，重刷一遍还是那八张卡片，按下去像没反应。
        enterMineTab(MINE_LIKED);
    }

    /**
     * 一张在线歌单的曲目。
     *
     * <p>榜单、推荐歌单、我的歌单、我喜欢的音乐全走这里——它们在网易云那边本来就是
     * 同一种东西（一个 id + 一串歌），没必要各写一遍。
     */
    private void buildOnlinePlaylistRows() {
        title.setText(TextUtils.isEmpty(onlinePlaylistName) ? "在线歌单" : onlinePlaylistName);
        if (onlinePlaylistLoading) {
            subtitle.setText("正在取曲目…");
            showEmpty("正在从网易云取这张歌单…");
            return;
        }
        if (onlinePlaylistError != null) {
            subtitle.setText("取失败：" + onlinePlaylistError + " · 按「返回」回上级，再进来可以重试");
            showEmpty("这张歌单没取到：\n" + onlinePlaylistError);
            return;
        }
        List<Track> tracks = onlinePlaylistTracks == null
                ? new ArrayList<Track>() : onlinePlaylistTracks;
        buildTrackRows(tracks, false);
        if (tracks.isEmpty()) {
            subtitle.setText("");
            showEmpty("这张歌单里没有取到歌曲。");
            return;
        }
        subtitle.setText(tracks.size() + " 首 · 按「确定」播放 · 按「返回」回上级");
    }

    private void buildSearchRows() {
        title.setText("搜索");
        String query = safe(searchQuery).trim();
        List<Track> hits = new ArrayList<>();
        int localCount = 0;
        if (!query.isEmpty()) {
            // 在线结果排前面：会来搜一首歌，多半就是因为本地没有
            if (onlineSearch != null && onlineSearchFor != null
                    && query.equalsIgnoreCase(onlineSearchFor)) {
                hits.addAll(onlineSearch);
            }
            // 本地结果跟在后面。同名同歌手的不再重复出现——两边都有的歌只列一次，
            // 列两次用户会以为曲库重了
            Set<String> seen = new HashSet<>();
            for (Track t : hits) {
                seen.add(dedupeKey(t));
            }
            String lower = query.toLowerCase(Locale.ROOT);
            for (Track t : allTracks) {
                if (!matchesQuery(t, lower)) {
                    continue;
                }
                if (seen.add(dedupeKey(t))) {
                    hits.add(t);
                    localCount++;
                }
            }
        }
        // 顺序有意义（在线在前、本地在后），不能排
        buildTrackRows(hits, false);

        if (query.isEmpty()) {
            subtitle.setText("搜歌名 / 歌手 / 专辑，网易云和本地一起搜");
            emptyView.setVisibility(View.GONE);
            return;
        }
        if (searchLoading) {
            subtitle.setText("正在搜网易云…" + (localCount > 0 ? "（本地已有 " + localCount + " 首）" : ""));
            return;
        }
        if (hits.isEmpty()) {
            String why = searchError == null ? "" : "\n（网易云搜索失败：" + searchError + "）";
            subtitle.setText("没有匹配「" + query + "」的歌");
            showEmpty("没搜到「" + query + "」。\n换个词试试。" + why);
            return;
        }
        int onlineCount = hits.size() - localCount;
        StringBuilder note = new StringBuilder();
        if (onlineCount > 0) {
            note.append("网易云 ").append(onlineCount).append(" 首");
        }
        if (localCount > 0) {
            if (note.length() > 0) {
                note.append(" · ");
            }
            note.append("本地 ").append(localCount).append(" 首");
        }
        if (searchError != null) {
            note.append("（网易云这次没搜成：").append(searchError).append("）");
        }
        subtitle.setText(note + " · 按「确定」播放");
    }

    private static boolean matchesQuery(Track t, String lowerQuery) {
        return safe(t.title).toLowerCase(Locale.ROOT).contains(lowerQuery)
                || safe(t.artist).toLowerCase(Locale.ROOT).contains(lowerQuery)
                || safe(t.album).toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    /** 去重用的键。在线那首和本地那首是同一首歌的话，只留在线的那一份 */
    private static String dedupeKey(Track t) {
        return safe(t.title).toLowerCase(Locale.ROOT) + "|" + safe(t.artist).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- 在线取数

    private NeteaseClient netease() {
        return NeteaseClient.get(getContext());
    }

    /**
     * 网易云的请求都是阻塞的，一律丢到后台线程。
     *
     * <p>没做成线程池：请求都是用户操作触发的，一分钟撑死几个，开个线程就完事。
     * 用池的话还得管生命周期——{@code onRelease} 之后这个页面可能被复用，
     * 池被关掉就再也发不出请求了，这种坑不值得为几个线程去踩。
     */
    private void runAsync(Runnable task) {
        new Thread(task, "netease").start();
    }

    /**
     * 该发请求的地方就顺手把请求发出去。
     *
     * <p>放在 rebuildRows 里而不是各个 buildXxx 里：buildXxx 只负责「把手上有的数据铺成行」，
     * 一旦它也负责发请求，就会出现「重建期间又触发一次重建」这种绕不出来的循环。
     * 这里只按状态判断「要不要发」，发完的回调再反过来叫一次 rebuildRows。
     */
    private void ensureOnlineData() {
        if (section == SEC_RECOMMEND) {
            if (netease().loggedIn()) {
                ensureDaily();
            }
        } else if (section == SEC_RANK && onlinePlaylistId != null) {
            ensureOnlinePlaylistTracks();
        } else if (section == SEC_RANK) {
            ensureCharts();
        } else if (section == SEC_PLAYLIST && onlinePlaylistId != null) {
            ensureOnlinePlaylistTracks();
        } else if (section == SEC_PLAYLIST) {
            ensurePlaylists();
        } else if (section == SEC_MINE && onlinePlaylistId != null) {
            ensureOnlinePlaylistTracks();
        } else if (section == SEC_MINE) {
            if (netease().loggedIn() && (mineTab < 0 || mineTab == MINE_MY_PLAYLISTS)) {
                ensureAccount();
            }
        } else if (section == SEC_SEARCH) {
            ensureSearch();
        }
    }

    /** 用户重新进栏目时把上次的失败清掉：他再点进来，多半就是想让它是好的 */
    private void resetSectionErrors(int target) {
        if (target == SEC_RECOMMEND) {
            dailyError = null;
        } else if (target == SEC_RANK) {
            chartsError = null;
            if (onlineCharts != null && onlineCharts.isEmpty()) {
                onlineCharts = null;
            }
        } else if (target == SEC_PLAYLIST) {
            playlistsError = null;
            if (onlinePlaylists != null && onlinePlaylists.isEmpty()) {
                onlinePlaylists = null;
            }
        } else if (target == SEC_MINE) {
            accountError = null;
            myPlaylistsError = null;
        }
    }

    private void ensureCharts() {
        if (onlineCharts != null || chartsLoading || chartsError != null) {
            return;
        }
        chartsLoading = true;
        final int epoch = onlineEpoch;
        runAsync(new Runnable() {
            @Override
            public void run() {
                List<NeteaseClient.Playlist> found = null;
                String error = null;
                try {
                    found = netease().toplists();
                } catch (Throwable th) {
                    error = describe(th);
                }
                final List<NeteaseClient.Playlist> data = found;
                final String failure = error;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (epoch != onlineEpoch || gone()) {
                            return;
                        }
                        chartsLoading = false;
                        if (failure != null) {
                            chartsError = failure;
                        } else {
                            onlineCharts = data == null
                                    ? new ArrayList<NeteaseClient.Playlist>() : data;
                        }
                        if (section == SEC_RANK && onlinePlaylistId == null) {
                            rebuildRows();
                        }
                    }
                });
            }
        });
    }

    private void ensurePlaylists() {
        if (onlinePlaylists != null || playlistsLoading || playlistsError != null) {
            return;
        }
        playlistsLoading = true;
        final int epoch = onlineEpoch;
        runAsync(new Runnable() {
            @Override
            public void run() {
                List<NeteaseClient.Playlist> found = null;
                String error = null;
                try {
                    found = netease().recommendedPlaylists(ONLINE_LIST_LIMIT);
                } catch (Throwable th) {
                    error = describe(th);
                }
                final List<NeteaseClient.Playlist> data = found;
                final String failure = error;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (epoch != onlineEpoch || gone()) {
                            return;
                        }
                        playlistsLoading = false;
                        if (failure != null) {
                            playlistsError = failure;
                        } else {
                            onlinePlaylists = data == null
                                    ? new ArrayList<NeteaseClient.Playlist>() : data;
                        }
                        if (section == SEC_PLAYLIST && onlinePlaylistId == null) {
                            rebuildRows();
                        }
                    }
                });
            }
        });
    }

    /** 一张在线歌单的曲目。榜单、推荐歌单、我的歌单都走这里 */
    private void ensureOnlinePlaylistTracks() {
        final String id = onlinePlaylistId;
        if (TextUtils.isEmpty(id) || onlinePlaylistTracks != null
                || onlinePlaylistLoading || onlinePlaylistError != null) {
            return;
        }
        onlinePlaylistLoading = true;
        final int epoch = onlineEpoch;
        runAsync(new Runnable() {
            @Override
            public void run() {
                List<Track> found = null;
                String error = null;
                try {
                    found = toTracks(netease().playlistSongs(id, ONLINE_TRACK_LIMIT));
                } catch (Throwable th) {
                    error = describe(th);
                }
                final List<Track> data = found;
                final String failure = error;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (epoch != onlineEpoch || gone()) {
                            return;
                        }
                        // 用户可能已经退出这张歌单、换去看别的了，结果就不该再贴上去
                        if (!TextUtils.equals(id, onlinePlaylistId)) {
                            return;
                        }
                        onlinePlaylistLoading = false;
                        if (failure != null) {
                            onlinePlaylistError = failure;
                        } else {
                            onlinePlaylistTracks = data == null ? new ArrayList<Track>() : data;
                        }
                        rebuildRows();
                    }
                });
            }
        });
    }

    private void ensureDaily() {
        if (onlineDaily != null || dailyLoading || dailyError != null) {
            return;
        }
        dailyLoading = true;
        final int epoch = onlineEpoch;
        runAsync(new Runnable() {
            @Override
            public void run() {
                List<Track> found = null;
                String error = null;
                try {
                    found = toTracks(netease().dailySongs());
                } catch (Throwable th) {
                    error = describe(th);
                }
                final List<Track> data = found;
                final String failure = error;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (epoch != onlineEpoch || gone()) {
                            return;
                        }
                        dailyLoading = false;
                        if (failure != null) {
                            dailyError = failure;
                        } else {
                            onlineDaily = data == null ? new ArrayList<Track>() : data;
                        }
                        if (section == SEC_RECOMMEND) {
                            rebuildRows();
                        }
                    }
                });
            }
        });
    }

    /**
     * 账号信息 + 账号歌单。
     *
     * <p>两个请求串在一个任务里：歌单要用账号的 uid 去取，分开做就得处理「账号回来了
     * 歌单还没发」这种中间态。串起来只有一个 loading、一套错误，界面上也只有一个说法。
     */
    private void ensureAccount() {
        if (accountLoading) {
            return;
        }
        if (account != null && myPlaylists != null) {
            return;
        }
        if (accountError != null || myPlaylistsError != null) {
            return;
        }
        accountLoading = true;
        final int epoch = onlineEpoch;
        runAsync(new Runnable() {
            @Override
            public void run() {
                NeteaseClient.Account found = null;
                List<NeteaseClient.Playlist> lists = null;
                String accountFailure = null;
                String listFailure = null;
                try {
                    found = netease().account();
                    if (found == null) {
                        accountFailure = "登录态已失效，重新登录一下";
                    }
                } catch (Throwable th) {
                    accountFailure = describe(th);
                }
                if (found != null) {
                    try {
                        lists = netease().userPlaylists(found.userId);
                    } catch (Throwable th) {
                        listFailure = describe(th);
                    }
                }
                final NeteaseClient.Account acc = found;
                final List<NeteaseClient.Playlist> playlists = lists;
                final String accFailure = accountFailure;
                final String listErr = listFailure;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (epoch != onlineEpoch || gone()) {
                            return;
                        }
                        accountLoading = false;
                        account = acc;
                        accountError = accFailure;
                        if (playlists != null) {
                            myPlaylists = playlists;
                            myPlaylistsError = null;
                            likedPlaylistId = findLikedId(playlists);
                        } else if (listErr != null) {
                            myPlaylistsError = listErr;
                        }
                        if (section == SEC_MINE) {
                            rebuildRows();
                        }
                    }
                });
            }
        });
    }

    /** 「我喜欢的音乐」在账号歌单里是特殊歌单（specialType=5）；读不到就按名字兜底 */
    private static String findLikedId(List<NeteaseClient.Playlist> playlists) {
        for (NeteaseClient.Playlist playlist : playlists) {
            if (playlist.specialType == 5) {
                return playlist.id;
            }
        }
        for (NeteaseClient.Playlist playlist : playlists) {
            if (LIKED_NAME.equals(playlist.name)) {
                return playlist.id;
            }
        }
        return null;
    }

    // ---- 搜索：输入框每敲一个字都会走到这里，所以要防抖 ----

    private void ensureSearch() {
        String query = safe(searchQuery).trim();
        if (query.isEmpty()) {
            searchPendingFor = null;
            return;
        }
        if (query.equalsIgnoreCase(searchDoneFor)) {
            return;
        }
        if (query.equalsIgnoreCase(searchPendingFor)) {
            return;
        }
        searchPendingFor = query;
        ui.removeCallbacks(searchTask);
        ui.postDelayed(searchTask, SEARCH_DEBOUNCE_MS);
    }

    private void runSearch() {
        final String query = searchPendingFor;
        if (TextUtils.isEmpty(query)) {
            return;
        }
        searchLoading = true;
        searchError = null;
        final int epoch = onlineEpoch;
        // 先把「正在搜」刷出来，不然防抖那半秒里界面一动不动
        rebuildIfSearching(query);
        runAsync(new Runnable() {
            @Override
            public void run() {
                List<Track> found = null;
                String error = null;
                try {
                    found = toTracks(netease().search(query, SEARCH_LIMIT));
                } catch (Throwable th) {
                    error = describe(th);
                }
                final List<Track> data = found;
                final String failure = error;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (epoch != onlineEpoch || gone()) {
                            return;
                        }
                        searchLoading = false;
                        onlineSearch = data == null ? new ArrayList<Track>() : data;
                        onlineSearchFor = query;
                        searchError = failure;
                        searchDoneFor = query;
                        rebuildIfSearching(query);
                    }
                });
            }
        });
    }

    /** 只有用户还停在这个词上才重建：他已经改了词，旧结果就没必要再刷上去 */
    private void rebuildIfSearching(String query) {
        if (section == SEC_SEARCH && query.equalsIgnoreCase(safe(searchQuery).trim())) {
            rebuildRows();
        }
    }

    private static List<Track> toTracks(List<NeteaseClient.Song> songs) {
        List<Track> out = new ArrayList<>();
        if (songs == null) {
            return out;
        }
        for (NeteaseClient.Song song : songs) {
            if (song == null || TextUtils.isEmpty(song.id)) {
                continue;
            }
            Track t = new Track();
            t.onlineId = song.id;
            t.title = song.name;
            t.artist = song.artist;
            t.album = song.album;
            t.duration = song.durationMs;
            t.coverUrl = song.picUrl;
            out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------------------- 登录

    /** 账号行。只开界面，不写任何东西——真正的登录态变更在弹窗回调里 */
    private void openAccountDialog() {
        Context context = getContext();
        if (!(context instanceof Activity)) {
            toast("宿主不是 Activity，打不开登录界面");
            return;
        }
        boolean logged = netease().loggedIn();
        String name = account == null ? "" : account.nickname;
        if (logged && TextUtils.isEmpty(name)) {
            name = "已登录";
        }
        new NeteaseLoginDialog((Activity) context, logged ? name : "",
                new NeteaseLoginDialog.Listener() {
                    @Override
                    public void onSubmit(String manualCookie, String webCookie) {
                        applyCookie(manualCookie, webCookie);
                    }

                    @Override
                    public void onLogout() {
                        netease().logout();
                        // 红心是上一个账号的，留着会让本地下歌一排全是已收藏
                        liked.clear();
                        likedLoaded = false;
                        updateBar();
                        clearOnlineData();
                        rebuildRows();
                        toast("已退出网易云");
                    }
                }).show();
    }

    /**
     * 把 cookie 收下来。
     *
     * <p>用户手粘的那串优先：WebView 里读到的可能是扫码之前就残留的旧 cookie，
     * 而他既然动手粘了，说明他要的就是这串。
     */
    private void applyCookie(String manualCookie, String webCookie) {
        String chosen = TextUtils.isEmpty(manualCookie) ? webCookie : manualCookie;
        if (netease().setCookie(chosen) == 0) {
            toast("这串里没有能认出来的 cookie");
            return;
        }
        if (!netease().loggedIn()) {
            // 认出来了 cookie，但没有 MUSIC_U——那只是设备指纹之类的，不算登录
            toast("没读到 MUSIC_U，登录没成功");
            return;
        }
        clearOnlineData();
        rebuildRows();
        liked.clear();
        likedLoaded = false;
        loadLiked();
        updateBar();
        toast("已导入登录态，正在读取账号…");
    }

    /**
     * 登录态一变，手上这些「上一个会话的数据」就全都不能要了。
     *
     * <p>{@link #onlineEpoch} 一起加一，好让还在路上的请求结果回来时认出自己已经过期。
     */
    private void clearOnlineData() {
        onlineEpoch++;
        onlineCharts = null;
        chartsError = null;
        onlinePlaylists = null;
        playlistsError = null;
        onlinePlaylistId = null;
        onlinePlaylistName = null;
        onlinePlaylistCover = null;
        onlinePlaylistTracks = null;
        onlinePlaylistError = null;
        onlineDaily = null;
        dailyError = null;
        onlineSearch = null;
        onlineSearchFor = null;
        searchError = null;
        searchDoneFor = null;
        searchPendingFor = null;
        account = null;
        myPlaylists = null;
        accountError = null;
        myPlaylistsError = null;
        likedPlaylistId = null;
        wySourceFile = null;
        ui.removeCallbacks(searchTask);
    }

    private static String describe(Throwable th) {
        if (th == null) {
            return "未知错误";
        }
        String message = th.getMessage();
        return TextUtils.isEmpty(message) ? th.getClass().getSimpleName() : message;
    }

    // ---------------------------------------------------------------- 音源

    /**
     * 扫描音源目录，并把 Download 里能认出来的脚本顺带导进来。
     *
     * <p>自动导入是刻意的：电视上没有「打开文件」这种交互，用户能做的就是把文件 push
     * 到某个目录。既然已经放进来了，还要求他再点一次「导入」纯属多余。
     */
    private void loadSources() {
        title.setText("音源");
        subtitle.setText("正在扫描…");
        showEmpty("正在扫描音源脚本…");
        scanSources(false);
    }

    /**
     * 只把音源列表扫进内存，不动界面。
     *
     * <p>在线播放要靠它：得先知道哪个脚本声明了网易云平台，才能拿它去换播放链接。
     * 用户可能一次都没进过「音源」页，所以这件事在进音乐页时就得做掉。
     */
    private void preloadSources() {
        scanSources(true);
    }

    private void scanSources(final boolean quiet) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Context ctx = getContext();
                List<File> discovered = com.github.catvod.music.LxManager.discover(ctx);
                for (File f : discovered) {
                    com.github.catvod.music.LxManager.importFrom(ctx, f);
                }
                final List<com.github.catvod.music.LxSource> found =
                        com.github.catvod.music.LxManager.scan(ctx);
                // 把上次的测试结果贴回去，不然每次进来都显示「未测试」
                com.github.catvod.music.LxManager.applyState(ctx, found);
                final int imported = discovered.size();
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone()) {
                            return;
                        }
                        // 播放线程可能正在读这份列表（找网易云平台），换内容要加锁
                        synchronized (sources) {
                            sources.clear();
                            sources.addAll(found);
                        }
                        if (quiet) {
                            return;
                        }
                        if (imported > 0) {
                            toast("自动导入了 " + imported + " 个音源");
                        }
                        rebuildRows();
                    }
                });
            }
        }, "lx-scan").start();
    }

    /**
     * 对某个平台做一次「取播放链接」探测。
     *
     * <p>各平台的 musicInfo 字段不一样（QQ/网易云用 songmid，酷我用 musicId，
     * 酷狗用 hash，咪咕用 copyrightId），这里用一组公开的固定样本去试——
     * 目的不是拿到能放的歌，而是验证「脚本 → lx.request → HTTP → 回调」这条链真的通了。
     * 通了之后接上真正的搜索接口就是纯 UI 活。
     */
    private void probePlatform(final com.github.catvod.music.LxSource source,
                               final com.github.catvod.music.LxSource.Platform platform) {
        if (source == null || platform == null) {
            return;
        }
        if (TextUtils.equals(testingSource, "probe:" + platform.id)) {
            return;
        }
        testingSource = "probe:" + platform.id;
        platform.probeResult = "探测中…";
        rebuildRows();
        toast("正在向 " + platform.name + " 取链接…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                File file = new File(com.github.catvod.music.LxManager.dir(getContext()), source.fileName);
                String script = com.github.catvod.music.LxManager.readAll(file);
                com.github.catvod.music.LxRuntime runtime = runtimeFor(source);
                String outcome;
                try {
                    com.github.catvod.music.LxRuntime.Result init = runtime.test(script, source);
                    if (!init.ok) {
                        outcome = "初始化失败：" + init.error;
                    } else {
                        String quality = platform.qualities.isEmpty() ? "128k" : platform.qualities.get(0);
                        com.github.catvod.music.LxRuntime.UrlResult r =
                                runtime.resolveUrl(platform.id, probeInfo(platform.id), quality);
                        if (r.ok) {
                            outcome = "已取到链接（" + quality + "），开始试听";
                            android.util.Log.i(TAG, "probe " + platform.id + " -> " + r.url);
                            // 直接复用本地播放器：Track.id<=0 时 play() 走 setDataSource(path)，
                            // MediaPlayer 原生支持 http 流（cleartext 已在网络配置里放开）
                            Track online = new Track();
                            online.id = 0;
                            online.path = r.url;
                            online.title = "试听 · " + platform.name + " " + quality;
                            online.artist = source.displayName();
                            online.album = "在线音乐";
                            play(online);
                        } else {
                            outcome = r.error;
                            android.util.Log.i(TAG, "probe " + platform.id + " -> " + r.display());
                        }
                    }
                } catch (Throwable th) {
                    outcome = "异常：" + th;
                }
                final String text = outcome;
                post(new Runnable() {
                    @Override
                    public void run() {
                        testingSource = null;
                        if (gone()) {
                            return;
                        }
                        platform.probeResult = text;
                        rebuildRows();
                        toast(platform.name + "：" + text);
                    }
                });
            }
        }, "lx-probe").start();
    }

    /** 各平台的探测样本。字段名按 LX 官方源的习惯写的 */
    private static String probeInfo(String platformId) {
        switch (platformId) {
            case "tx":
            case "qq":
                return "{\"songmid\":\"0039MnYb0qxYhV\",\"strMediaMid\":\"0039MnYb0qxYhV\",\"albumMid\":\"\",\"songId\":0}";
            case "wy":
                return "{\"songmid\":\"186016\"}";
            case "kw":
                // 2026-10 用酷我搜索 API 复核过：海阔天空(G.E.M.) MUSIC_321946135
                return "{\"musicId\":\"321946135\",\"songId\":\"321946135\"}";
            case "kg":
                // 同上：酷狗搜索 API 拿的海阔天空(乐与怒) hash
                return "{\"hash\":\"c41e80a18d1448fa47086372999c7f43\"}";
            case "mg":
                return "{\"copyrightId\":\"60084600227\"}";
            default:
                return "{}";
        }
    }

    private void buildSourceRows() {
        // 钻进某个音源看它的平台列表
        if (drillKey != null) {
            com.github.catvod.music.LxSource target = null;
            for (com.github.catvod.music.LxSource s : sources) {
                if (TextUtils.equals(drillKey, s.fileName)) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                drillKey = null;
                buildSourceRows();
                return;
            }
            title.setText(target.displayName());
            subtitle.setText(target.platformSummary()
                    + (target.hasRequestHandler ? " · 取链接接口已注册" : " · 未注册取链接接口")
                    + " · 按「返回」回音源列表");
            for (com.github.catvod.music.LxSource.Platform p : target.platforms) {
                Row row = new Row();
                row.title = p.name;
                row.platform = p;
                row.source = target;
                row.sub = p.actions.isEmpty() ? "" : "动作：" + TextUtils.join(" / ", p.actions);
                StringBuilder q = new StringBuilder();
                for (String quality : p.qualities) {
                    if (q.length() > 0) {
                        q.append(" · ");
                    }
                    q.append(com.github.catvod.music.LxSource.qualityName(quality));
                }
                row.tail = TextUtils.isEmpty(p.probeResult) ? q.toString() : p.probeResult;
                rows.add(row);
            }
            return;
        }

        title.setText("音源");
        // 导入入口永远排第一行：电视上没有「打开文件」，扫码是唯一对遥控器友好的方式
        Row importRow = new Row();
        importRow.title = "扫码导入音源";
        importRow.importSource = true;
        importRow.glyph = "扫";
        if (sources.isEmpty()) {
            subtitle.setText("还没有音源 · 先用手机扫码导入一个");
            importRow.sub = "手机扫码上传 LX 音源 .js";
            importRow.tail = "推荐";
        } else {
            subtitle.setText(sources.size() + " 个 · 按「确定」测试");
            importRow.sub = "手机扫码上传 LX 音源 .js";
        }
        rows.add(importRow);
        for (com.github.catvod.music.LxSource s : sources) {
            Row row = new Row();
            row.title = s.displayName();
            row.source = s;
            String meta = s.displayMeta();
            String desc = s.description == null ? "" : s.description.trim();
            row.sub = TextUtils.isEmpty(meta) ? desc : (TextUtils.isEmpty(desc) ? meta : meta + " · " + desc);
            row.tail = s.status + (TextUtils.isEmpty(s.platformSummary()) ? "" : " · " + s.platformSummary());
            rows.add(row);
        }
    }

    /**
     * 跑一次音源初始化，拿到它支持哪些平台。
     *
     * <p>这是唯一真正执行用户脚本的地方，所以放在独立线程 + 独立 runtime，
     * 跑完立刻销毁——脚本里有什么死循环也只影响这一次测试。
     */
    private void testSource(final com.github.catvod.music.LxSource source) {
        if (source == null) {
            return;
        }
        if (TextUtils.equals(testingSource, source.fileName)) {
            return;
        }
        testingSource = source.fileName;
        source.status = "测试中…";
        source.detail = "";
        rebuildRows();
        toast("正在执行 " + source.displayName() + " …");

        new Thread(new Runnable() {
            @Override
            public void run() {
                File file = new File(com.github.catvod.music.LxManager.dir(getContext()), source.fileName);
                String script = com.github.catvod.music.LxManager.readAll(file);
                com.github.catvod.music.LxRuntime runtime = runtimeFor(source);
                com.github.catvod.music.LxRuntime.Result result;
                try {
                    result = runtime.test(script, source);
                    // 初始化通过就顺手对第一个平台取一次链接。
                    // 「能加载」和「能取到歌」是两件事，只测前者的话用户以为好了，
                    // 真去听歌才发现全是空——一次测完，两个结论一起给。
                    if (result.ok && result.hasRequestHandler && !result.platforms.isEmpty()) {
                        com.github.catvod.music.LxSource.Platform first = result.platforms.get(0);
                        String quality = first.qualities.isEmpty() ? "128k" : first.qualities.get(0);
                        com.github.catvod.music.LxRuntime.UrlResult probe =
                                runtime.resolveUrl(first.id, probeInfo(first.id), quality);
                        first.probeResult = probe.ok ? ("已取到链接（" + quality + "）") : probe.error;
                        android.util.Log.i(TAG, "自动探测 " + first.id + " -> " + probe.display());
                    }
                } catch (Throwable th) {
                    result = new com.github.catvod.music.LxRuntime.Result();
                    result.error = String.valueOf(th);
                }
                final com.github.catvod.music.LxRuntime.Result r = result;
                post(new Runnable() {
                    @Override
                    public void run() {
                        testingSource = null;
                        if (gone()) {
                            return;
                        }
                        source.platforms.clear();
                        source.platforms.addAll(r.platforms);
                        source.hasRequestHandler = r.hasRequestHandler;
                        source.status = r.ok
                                ? com.github.catvod.music.LxSource.STATUS_OK
                                : com.github.catvod.music.LxSource.STATUS_FAIL;
                        source.detail = r.ok ? r.summary() : r.error;
                        com.github.catvod.music.LxManager.saveState(getContext(), sources);
                        android.util.Log.i(TAG, "测试完成 ok=" + r.ok + " 平台=" + r.platforms.size()
                                + " handler=" + r.hasRequestHandler + " err=" + r.error);
                        rebuildRows();
                        if (r.ok) {
                            // 测试成功直接钻进去，用户按下确定就想看到「它到底支持什么」
                            drillKey = source.fileName;
                            drillLabel = source.displayName();
                            rebuildRows();
                            focusList();
                        } else {
                            toast("测试失败：" + r.error);
                            for (String line : r.logs) {
                                android.util.Log.i(TAG, "源日志 " + source.fileName + " | " + line);
                            }
                        }
                    }
                });
            }
        }, "lx-test").start();
    }

    // ---------------------------------------------------------------- 内容背景

    /** 背景图压到这个透明度：再亮就压不住白字，再暗就白贴了 */
    private static final float BG_ALPHA = 0.62f;

    /**
     * 贴整页背景。
     *
     * <p>{@code force=false} 是「焦点路过某一行」发起的调用：那一行没有封面时什么都不做，
     * 让上一张留着，否则用户每按一下下键背景就闪一下黑。
     *
     * <p>图按 160×90 解码再拉满屏——放大插值本身就是模糊，不用第三方 blur 库。
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
            bindHeadCover(null);
            return;
        }
        if (TextUtils.equals(url, shownBgUrl)) {
            bindHeadCover(url);
            return;
        }
        shownBgUrl = url;
        try {
            Glide.with(getContext())
                    .load(url)
                    .override(160, 90)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(420))
                    .into(bgImage);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "背景图加载失败：" + url, th);
            return;
        }
        bgImage.animate().alpha(BG_ALPHA).setDuration(420).start();
        bindHeadCover(url);
    }

    /** 标题左边那块方图。没有封面就收起来，标题会自己顶到左边 */
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
            Glide.with(getContext())
                    .load(url)
                    .transform(new CenterCrop(), new RoundedCorners(dp(10)))
                    .into(headCover);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "标题封面加载失败：" + url, th);
        }
    }

    /**
     * 按当前列表挑一张「这个视图的代表封面」：钻进歌单就是那张歌单的封面，
     * 否则取列表里第一张有封面的行。
     *
     * <p>整页背景必须跟着栏目走，不能只跟着焦点走——用户刚切进来还没按过键，
     * 那时候就该已经是这个栏目的颜色了。
     */
    private void syncBackground() {
        String cover = "";
        if (onlinePlaylistId != null && !TextUtils.isEmpty(onlinePlaylistCover)) {
            cover = onlinePlaylistCover;
        } else {
            for (Row row : rows) {
                if (!TextUtils.isEmpty(row.coverUrl)) {
                    cover = row.coverUrl;
                    break;
                }
            }
        }
        showBackground(cover, true);
    }

    /** 列表行的小封面。ListView 会复用，没封面时必须显式清掉上一张 */
    private void bindRowCover(ImageView view, String url) {
        if (view == null) {
            return;
        }
        if (TextUtils.isEmpty(url)) {
            Glide.with(getContext()).clear(view);
            view.setImageDrawable(null);
            return;
        }
        try {
            Glide.with(getContext())
                    .load(url)
                    .transform(new CenterCrop(), new RoundedCorners(dp(8)))
                    .into(view);
        } catch (Throwable th) {
            view.setImageDrawable(null);
        }
    }

    /** 从账号歌单里找一张的封面。「我喜欢的音乐」入口和打开它时都要用 */
    private String playlistCoverById(String id) {
        if (TextUtils.isEmpty(id) || myPlaylists == null) {
            return "";
        }
        for (NeteaseClient.Playlist playlist : myPlaylists) {
            if (playlist != null && TextUtils.equals(id, playlist.id)) {
                return playlist.coverUrl == null ? "" : playlist.coverUrl;
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- 行装配

    private void buildTrackRows(List<Track> source) {
        buildTrackRows(source, true);
    }

    /**
     * 把一串曲子铺成列表行，同时把它记成播放队列（下一首/上一首就按这个顺序走）。
     *
     * @param sort true 按歌名排（本地曲库用，拼音序）；false 保持原顺序。
     *             在线列表的顺序本身就是信息——榜单第 1 行必须是第 1 名，
     *             排一下就只剩一堆按拼音排的歌名，名次没了
     */
    private void buildTrackRows(List<Track> source, boolean sort) {
        List<Track> ordered = new ArrayList<>(source);
        if (sort) {
            Collections.sort(ordered, new Comparator<Track>() {
                @Override
                public int compare(Track a, Track b) {
                    return collator.compare(safe(a.title), safe(b.title));
                }
            });
        }
        queue.clear();
        queue.addAll(ordered);
        for (Track t : ordered) {
            Row row = new Row();
            row.title = TextUtils.isEmpty(t.title) ? fileName(t.path) : t.title;
            String sub = join(safe(t.artist), safe(t.album));
            if (t.isOnline()) {
                // 标出来是在线曲目：它点下去要几十秒才出声（见 playOnline），
                // 用户得先知道这一点，不然会以为遥控器没反应
                sub = TextUtils.isEmpty(sub) ? "网易云" : ("网易云 · " + sub);
            }
            row.sub = sub;
            row.tail = formatDuration(t.duration);
            row.track = t;
            row.coverUrl = t.coverUrl;
            rows.add(row);
        }
    }

    /** 按某个分组维度数一遍本地曲库。歌手/专辑/文件夹都走它 */
    private Map<String, Integer> countGroups(int keyTab) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (Track t : allTracks) {
            String key = t.keyOf(keyTab);
            Integer old = counter.get(key);
            counter.put(key, old == null ? 1 : old + 1);
        }
        return counter;
    }

    private void buildGroupRows(int keyTab) {
        queue.clear();
        Map<String, Integer> counter = countGroups(keyTab);
        List<String> keys = new ArrayList<>(counter.keySet());
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return collator.compare(a, b);
            }
        });
        for (String key : keys) {
            Row row = new Row();
            row.title = key;
            row.key = key;
            row.glyph = keyTab == MINE_ARTIST ? "人" : (keyTab == MINE_ALBUM ? "专" : "夹");
            row.tail = counter.get(key) + " 首";
            rows.add(row);
        }
    }

    private List<Track> filterByKey(int keyTab, String key) {
        List<Track> out = new ArrayList<>();
        for (Track t : allTracks) {
            if (TextUtils.equals(key, t.keyOf(keyTab))) {
                out.add(t);
            }
        }
        return out;
    }

    private void drillInto(Row row) {
        rememberDrillFrom();
        drillKey = row.key;
        drillLabel = row.title;
        rebuildRows();
        // 钻进子列表后把焦点放到第一行，用户不用再按一次右键
        focusList();
    }

    /**
     * 记下「钻进下一层之前焦点停在哪一行」。
     *
     * <p>音乐页的钻入入口有好几个（在线歌单、按歌手/专辑分组、我的子页），
     * 但都是「焦点停在那一行上按了确定」，所以统一从焦点自己算，
     * 不用每个调用点往下传下标——传漏一个就只有那一层退不回位。
     */
    private void rememberDrillFrom() {
        View focused = findFocus();
        int position = focused == null ? -1
                : (cardMode ? grid.getPositionForView(focused) : list.getPositionForView(focused));
        drillFromIndex = Math.max(0, position);
        // 钻进去的新层默认从第一行开始接焦点（异步到达时由 rebuildRows 兑现）
        pendingContentFocus = 0;
    }

    /** 把焦点交给列表第一行（钻入时用） */
    private void focusList() {
        focusList(0);
    }

    /**
     * 把焦点交给第 position 行（退层时用，见 {@link #rememberDrillFrom()}）。
     *
     * <p>两个坑叠在一起：
     * 一是 notifyDataSetChanged 只标脏，ListView 要到下一帧才真正布局出子 View，
     * 此时立刻 requestFocus 会因为没有可聚焦的 child 而失败；
     * 二是 itemsCanFocus=true 之后 ListView 自己不参与焦点，requestFocus 到它身上
     * 是空操作。所以必须延后，并且直接 focus 到目标行的子 View。
     */
    private void focusList(final int position) {
        focusList(position, 2);
    }

    private void focusList(final int position, final int retries) {
        if (gone() || adapter.getCount() == 0) {
            return;
        }
        final int target = Math.max(0, Math.min(position, adapter.getCount() - 1));
        if (cardMode) {
            focusGrid(target, retries);
            return;
        }
        list.setSelection(target);
        View first = list.getChildAt(target - list.getFirstVisiblePosition());
        if (first != null && first.requestFocus()) {
            confirmListFocus(target, retries - 1);
            return;
        }
        if (retries <= 0) {
            return;
        }
        // 还没布局出子 View，等一帧再来
        list.postDelayed(new Runnable() {
            @Override
            public void run() {
                focusList(target, retries - 1);
            }
        }, 60);
    }

    /**
     * 下一帧验一次焦点还在不在。
     *
     * <p>{@code notifyDataSetChanged} 只把列表标脏，真正的重排在下一帧；重排会把刚挂上焦
     * 的那一行回收掉，焦点静默落回页面里第一个可聚焦的控件（模块胶囊）。用户看到的是
     * 「按了返回，结果还得重新按方向键找位置」。丢了就照原样再来一次。
     */
    private void confirmListFocus(final int position, final int retries) {
        if (retries <= 0) {
            return;
        }
        list.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gone() || list.hasFocus()) {
                    return;
                }
                focusList(position, retries);
            }
        }, 32);
    }

    /**
     * 把焦点放到第 position 张卡片上。
     *
     * <p>GridView 只挂载视口附近的卡片，目标行还没生成时先平滑翻一行过去；
     * 滚完再补焦——动画期间请求焦点会被随后的重排清掉。
     */
    private void focusGrid(final int position, final int retries) {
        if (gone() || cardAdapter.getCount() == 0) {
            return;
        }
        View child = grid.getChildAt(position - grid.getFirstVisiblePosition());
        if (!GridScroll.isFullyVisible(grid, child)) {
            if (retries <= 0) {
                return;
            }
            long wait = GridScroll.smoothToRow(grid, position);
            postFocus(position, retries - 1, wait);
            return;
        }
        if (child.requestFocus()) {
            confirmGridFocus(position, retries);
            return;
        }
        if (retries <= 0) {
            return;
        }
        postFocus(position, retries - 1, 60);
    }

    /** 同 {@link #confirmListFocus(int, int)}：卡片墙重排会把刚挂上焦的卡片回收掉 */
    private void confirmGridFocus(final int position, final int retries) {
        if (retries <= 0) {
            return;
        }
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gone() || grid.hasFocus()) {
                    return;
                }
                focusGrid(position, retries - 1);
            }
        }, 32);
    }

    private void postFocus(final int position, final int retries, long delay) {
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                focusGrid(position, retries);
            }
        }, delay);
    }

    /**
     * 卡片墙的上下走位自己算。
     *
     * <p>和影视页同一个理由：GridView 只挂载视口附近的卡片，下一排还没生成时
     * 系统找不到目标，会把焦点甩给播放条或页签。这里按「当前下标 ± 列数」算，
     * 目标不在视口里就先 setSelection 再补焦。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (stageOpen && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                // 松手必须收掉连发，否则「点一下」会变成一直快进
                stopHoldSeek();
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && stageOpen) {
            if (stageChromeHidden) {
                // BACK 仍然交给宿主收起这一层；其余键先把控制区唤回来。
                // 这一键不能再往下发：唤回之前焦点挂在整层上，默认寻焦会按几何把
                // 它挪到旁边的按钮（实测按右键落到「下一首」，下一键就把歌切了）。
                // 左右键的含义在这里直接执行，和亮着控制区时按同一个键等价；
                // 确定键只负责唤回，免得黑着屏按一下就被当成暂停。
                if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) {
                    showStageControls(true);
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                        startHoldSeek(-1, event.getRepeatCount());
                        return true;
                    }
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        startHoldSeek(1, event.getRepeatCount());
                        return true;
                    }
                    if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                            || event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                            || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                        return true;
                    }
                }
            } else {
                scheduleStageChromeHide();
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && !stageOpen && cardMode && grid.getVisibility() == View.VISIBLE) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (moveGridVertical(keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    return true;
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
            if (next < cardAdapter.getCount()) {
                focusGrid(next, 2);
            } else if (bar.getVisibility() == View.VISIBLE) {
                toggleBtn.requestFocus();
            }
            // 到底或正在滚动都把键吃掉，别让焦点再乱跑
            return true;
        }
        int previous = position - columns;
        if (previous >= 0) {
            focusGrid(previous, 2);
            return true;
        }
        if (playAllBtn.getVisibility() == View.VISIBLE) {
            playAllBtn.requestFocus();
        } else {
            focusChip();
        }
        return true;
    }

    // ---------------------------------------------------------------- 列表

    private class TrackAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_music_row, parent, false);
            }
            final Row row = rows.get(position);
            TextView rowTitle = view.findViewById(R.id.music_row_title);
            TextView rowSub = view.findViewById(R.id.music_row_sub);
            TextView rowTail = view.findViewById(R.id.music_row_tail);

            bindRowCover((ImageView) view.findViewById(R.id.music_row_cover), row.coverUrl);
            // 焦点走到哪一行，整页背景就跟到哪一行。捕获的是这一次绑定的地址，
            // 不是 row 对象——ListView 复用时 row 已经换成别的位置了
            final String rowCover = row.coverUrl;
            view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        showBackground(rowCover, false);
                    }
                }
            });

            rowTitle.setText(row.title);
            rowSub.setText(row.sub == null ? "" : row.sub);
            rowSub.setVisibility(TextUtils.isEmpty(row.sub) ? View.GONE : View.VISIBLE);

            // 正在放的那首把时长换成「▶ 播放中」：bg_setting_row 没有 selected 态，
            // 光 setSelected 是看不出来的，得在文字上给个明确的标记
            boolean playing = row.track != null && row.track == nowPlaying;
            rowTail.setText(playing ? "▶ 播放中" : (row.tail == null ? "" : row.tail));

            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activateRow(row);
                }
            });
            return view;
        }
    }

    /**
     * 卡片墙的适配器。数据源和行列表是同一份 {@link #rows}，所以点击语义、
     * 播放队列下标、整页背景都能直接复用。
     */
    private final class CardAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View root = convertView;
            final boolean fresh = root == null;
            if (root == null) {
                root = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_music_card, parent, false);
            }
            final Row row = rows.get(position);
            TextView name = root.findViewById(R.id.music_card_name);
            TextView note = root.findViewById(R.id.music_card_note);
            ImageView img = root.findViewById(R.id.music_card_img);
            TextView placeholder = root.findViewById(R.id.music_card_ph);

            name.setText(row.title);
            String noteText = cardNote(row);
            if (row.track != null && row.track == nowPlaying) {
                noteText = "▶ 播放中";
            }
            note.setText(noteText);
            note.setVisibility(TextUtils.isEmpty(noteText) ? View.GONE : View.VISIBLE);

            bindCardCover(img, placeholder, row.coverUrl, glyphFor(row));
            PosterCorners.clip(img,
                    getResources().getDimensionPixelSize(R.dimen.poster_radius) - dp(3));

            // GridView 的 item 默认不可聚焦
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            final String rowCover = row.coverUrl;
            FocusScaler.attach(root, FocusScaler.SCALE_FOCUSED,
                    new View.OnFocusChangeListener() {
                        @Override
                        public void onFocusChange(View v, boolean hasFocus) {
                            if (hasFocus) {
                                showBackground(rowCover, false);
                            }
                        }
                    });
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activateRow(row);
                }
            });

            // 入场和影视页同一节奏：错峰淡入 + 轻微上浮。
            // 复用的卡片（以及正在焦点上的那张）不重放，否则布局一来整墙都会闪一下。
            if (!fresh || root.isFocused()) {
                RevealAnim.reset(root);
            } else {
                RevealAnim.staggerIn(root, position, dp(10));
            }
            return root;
        }
    }

    /** 卡片/行共用的点击语义。顺序不能反：平台行也带着 source（探测要用），
     * 先判 source 的话点平台行会又去跑一次音源测试。
     */
    private void activateRow(Row row) {
        if (row.importSource) {
            openSourceImport();
        } else if (row.platform != null) {
            probePlatform(row.source, row.platform);
        } else if (row.source != null) {
            testSource(row.source);
        } else if (row.accountRow) {
            openAccountDialog();
        } else if (row.playlist != null) {
            openPlaylist(row.playlist);
        } else if (row.mineTarget == MINE_LIKED) {
            openLiked();
        } else if (row.mineTarget >= 0) {
            enterMineTab(row.mineTarget);
        } else if (row.key != null) {
            drillInto(row);
        } else if (row.track != null) {
            playAt(rows.indexOf(row));
        }
    }

    /** 扫码导入：起局域网接收页，手机上传的 .js 落进音源目录 */
    private void openSourceImport() {
        android.content.Context context = getContext();
        if (!(context instanceof android.app.Activity)) {
            return;
        }
        MusicSourceDialog dialog = new MusicSourceDialog((android.app.Activity) context,
                new MusicSourceDialog.Listener() {
                    @Override
                    public void onSourceImported() {
                        scanSources(false);
                    }
                });
        dialog.show();
    }

    /** 卡片的第二行。歌曲是「歌手 · 专辑 · 时长」，歌单是「来源 · 曲目数」 */
    private static String cardNote(Row row) {
        String sub = safe(row.sub).trim();
        String tail = safe(row.tail).trim();
        if (row.accountRow || row.mineTarget >= 0) {
            return sub.isEmpty() ? tail : sub;
        }
        if (sub.isEmpty()) {
            return tail;
        }
        return tail.isEmpty() ? sub : (sub + " · " + tail);
    }

    /** 没有封面时的占位字符 */
    private static String glyphFor(Row row) {
        if (!TextUtils.isEmpty(row.glyph)) {
            return row.glyph;
        }
        if (row.track != null) {
            return "♪";
        }
        if (row.accountRow) {
            return "云";
        }
        if (row.mineTarget == MINE_LIKED) {
            return "♥";
        }
        if (row.mineTarget == MINE_MY_PLAYLISTS) {
            return "≡";
        }
        if (row.mineTarget == MINE_SONG) {
            return "♪";
        }
        if (row.mineTarget == MINE_ARTIST) {
            return "人";
        }
        if (row.mineTarget == MINE_ALBUM) {
            return "专";
        }
        if (row.mineTarget == MINE_FOLDER) {
            return "夹";
        }
        if (row.mineTarget == MINE_SOURCE || row.source != null) {
            return "源";
        }
        if (row.importSource) {
            return "扫";
        }
        if (row.platform != null) {
            return "台";
        }
        if (TextUtils.equals(row.key, LOCAL_RANK_KEY)) {
            return "榜";
        }
        if (row.key != null) {
            return "专";
        }
        if (row.playlist != null) {
            return "歌";
        }
        return "♪";
    }

    /** 卡片封面：有图就铺图，没图就显示占位字符（占位符在 ImageView 下层） */
    private void bindCardCover(ImageView view, TextView placeholder, String url, String glyph) {
        if (placeholder != null) {
            placeholder.setText(TextUtils.isEmpty(glyph) ? "♪" : glyph);
        }
        if (view == null) {
            return;
        }
        if (TextUtils.isEmpty(url)) {
            Glide.with(getContext()).clear(view);
            view.setImageDrawable(null);
            return;
        }
        try {
            Glide.with(getContext())
                    .load(url)
                    .centerCrop()
                    .into(view);
        } catch (Throwable th) {
            view.setImageDrawable(null);
        }
    }

    /** 「我的」里选一个子视图：进去之后 BACK 回入口列表 */
    private void enterMineTab(int mineTarget) {
        rememberDrillFrom();
        section = SEC_MINE;
        mineTab = mineTarget;
        drillKey = null;
        drillLabel = null;
        updateChipSelection();
        if (mineTarget == MINE_SOURCE) {
            loadSources();
        } else {
            rebuildRows();
            focusList();
        }
    }

    // ---------------------------------------------------------------- 播放

    private void setupBar() {
        // 容器本身不吃焦点（XML 里 focusable=false + afterDescendants），
        // 否则从列表按 DOWN 会停在容器上，RIGHT 又进不去按钮——遥控器会直接卡死在这一层。
        bar.setOnClickListener(null);
        View prev = findViewById(R.id.music_prev);
        View next = findViewById(R.id.music_next);
        expandBtn = findViewById(R.id.music_expand);
        FocusScaler.attach(prev, 1.06f);
        FocusScaler.attach(next, 1.06f);
        FocusScaler.attach(toggleBtn, 1.06f);
        FocusScaler.attach(expandBtn, 1.06f);
        FocusScaler.attach(likeBtn, 1.06f);
        FocusScaler.attach(modeBtn, 1.06f);

        // 左边那一块（圆封面 + 曲名）不参与焦点，整块点下去就是「展开播放页」：
        // 电视上想看清在放什么，点开比在这 84dp 里猜要直接。
        nowCover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openStage();
            }
        });
        nowTitle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openStage();
            }
        });

        // 六个按钮横向互串，UP 统一回列表——否则 UP 会去抢上面那一整块列表容器
        modeBtn.setNextFocusRightId(R.id.music_prev);
        prev.setNextFocusLeftId(R.id.music_mode);
        prev.setNextFocusRightId(R.id.music_toggle);
        toggleBtn.setNextFocusLeftId(R.id.music_prev);
        toggleBtn.setNextFocusRightId(R.id.music_next);
        next.setNextFocusLeftId(R.id.music_toggle);
        next.setNextFocusRightId(R.id.music_like);
        likeBtn.setNextFocusLeftId(R.id.music_next);
        likeBtn.setNextFocusRightId(R.id.music_expand);
        expandBtn.setNextFocusLeftId(R.id.music_like);
        for (View v : new View[] {modeBtn, prev, toggleBtn, next, likeBtn, expandBtn}) {
            v.setNextFocusUpId(R.id.music_list);
        }
        // 列表按到底就落到「暂停」上，和遥控器习惯一致。
        // 卡片墙走的是 dispatchKeyEvent 里的 moveGridVertical，它同样在最后一行往下落到暂停。
        list.setNextFocusDownId(R.id.music_toggle);

        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playPrev();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playNext();
            }
        });
        toggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        likeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PressFx.heart(v);
                toggleLike();
            }
        });
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PressFx.squish(v);
                cyclePlayMode();
            }
        });
        expandBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openStage();
            }
        });
        renderPlayMode();
    }

    /**
     * 右上「正在播放」胶囊。
     *
     * <p>播放条压在列表下面，遥控器从列表按 DOWN 要一行一行走到歌单末尾才够得着；
     * 这个胶囊把两条路都缩短：确定键直接展开播放页，DOWN 直接落到播放条的暂停键。
     * 它和页签在同一行，从最后一颗页签按 RIGHT 就能过来，不用背快捷键。
     */
    private void setupNowChip() {
        if (nowChip == null) {
            return;
        }
        FocusScaler.attach(nowChip, 1.06f);
        nowChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (nowPlaying == null && resolvingTrack == null) {
                    toast("还没有在播的歌，先在列表里选一首");
                    return;
                }
                openStage();
            }
        });
        nowChip.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (bar.getVisibility() == View.VISIBLE) {
                        toggleBtn.requestFocus();
                    } else if (adapter.getCount() > 0) {
                        focusList();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    focusChip();
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * 「播放全部」。只出现在真有一串歌的视图上：每日推荐、榜单曲目、歌单曲目、
     * 我喜欢的音乐、本地歌曲/分组曲目、搜索结果。
     *
     * <p>它不另存一份队列——直接把当前 rows 里的歌按屏幕顺序装进 queue，
     * 所以下一首/上一首就是「这个歌单的顺序」，和逐张点卡片的行为一致。
     */
    private void setupPlayAll() {
        FocusScaler.attach(playAllBtn, 1.06f);
        playAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playAllTracks();
            }
        });
        playAllBtn.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusList();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    focusChip();
                    return true;
                }
                return false;
            }
        });
    }

    private void playAllTracks() {
        List<Track> tracks = new ArrayList<>();
        for (Row row : rows) {
            if (row.track != null) {
                tracks.add(row.track);
            }
        }
        if (tracks.isEmpty()) {
            return;
        }
        queue.clear();
        queue.addAll(tracks);
        queueIndex = 0;
        past.clear();
        toast("播放全部 · " + tracks.size() + " 首");
        play(queue.get(0));
    }

    private void playAt(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return;
        }
        Row row = rows.get(rowIndex);
        if (row.track == null) {
            return;
        }
        queueIndex = indexInQueue(row.track);
        // 手动点歌就是重新起一条路，之前随机走过的那串不该还能往后退
        past.clear();
        play(row.track);
    }

    private int indexInQueue(Track track) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i) == track) {
                return i;
            }
        }
        return -1;
    }

    private void play(Track track) {
        if (track == null) {
            return;
        }
        // 第一次开播才去取红心列表：没在放歌的时候，播放条上那颗心用不着状态
        if (!likedLoaded && netease().loggedIn()) {
            loadLiked();
        }
        // 歌词和播放链接是两条独立的网络请求，一起发出去。在线歌换链接要几十秒，
        // 等播起来了再取词的话，用户展开播放页看到的就是好几秒的空白。
        loadLyric(track);
        if (track.isOnline()) {
            // 在线曲目手里只有一个 id，直链得先让音源脚本去换
            playOnline(track);
            return;
        }
        if (track.id <= 0 && TextUtils.isEmpty(track.path)) {
            toast("这首歌没有可用的文件地址");
            return;
        }
        if (!startPlayer(track)) {
            toast("这首歌打不开，可能文件已经不在原位置了");
        }
    }

    /**
     * 把一首「已经有可播地址」的曲子交给 MediaPlayer。
     *
     * @return 是否成功开播
     */
    private boolean startPlayer(final Track track) {
        try {
            if (player == null) {
                player = new MediaPlayer();
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        advanceQueue(true);
                    }
                });
                player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        // 在线直链会过期、本地文件会被删——两种失败都不抛异常，只走这里。
                        // 不接住的话默认行为是当作「播完了」去切下一首，于是一路往下滚，
                        // 用户看到的是「点了一整张歌单，一首都没响」。所以吃下来，说清楚。
                        android.util.Log.w(TAG, "MediaPlayer 报错 what=" + what + " extra=" + extra);
                        nowPlaying = null;
                        updateBar();
                        notifyContentChanged();
                        toast("这一首放不出来，可能链接已经过期或文件不在了");
                        return true;
                    }
                });
            }
            player.reset();
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mp.start();
                    updateBar();
                }
            });
            if (track.id > 0) {
                // 优先用 content:// 播，不直接用文件路径：Android 10 起分区存储会让 DATA 拿到的
                // 路径对别的应用不可读，MediaPlayer 会报 Permission denied。MediaStore 的 id
                // 是稳的，拼成 content URI 交给 MediaPlayer 由系统代理读取。
                player.setDataSource(getContext(), ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id));
            } else {
                player.setDataSource(track.path);
            }
            player.prepareAsync();
            nowPlaying = track;
            countPlay(track);
            updateBar();
            notifyContentChanged();
            return true;
        } catch (Throwable th) {
            android.util.Log.w(TAG, "播放失败 id=" + track.id + " path=" + track.path, th);
            return false;
        }
    }

    /**
     * 在线曲目播放：先把 id 换成直链，再交给播放器。
     *
     * <p>换链接这一步是几十秒的重活（墨澜源实测一次 ~55 秒，全程是混淆脚本在 quickjs 里跑
     * 加密，省不掉），所以这里有三条硬要求：
     * <ul>
     *   <li><b>必须在后台线程</b>，并且界面要立刻给个说法（播放条写「正在取播放链接…」），
     *       否则几十秒里按什么都没反应，用户会以为遥控器坏了；</li>
     *   <li><b>同一时刻只允许一个</b>（{@link #resolvingTrack} 兼任锁）：连点五首歌就会
     *       并发起五个几十秒的任务，全都跑完再一起回来，机器直接卡住；</li>
     *   <li><b>回来时要确认用户要的还是这一首</b>：等链接的半分钟里他完全可能已经换了歌。</li>
     * </ul>
     */
    private void playOnline(final Track track) {
        if (resolvingTrack != null) {
            toast("上一首还在取播放链接，稍等一下");
            return;
        }
        resolvingTrack = track;
        updateBar();
        toast("正在取「" + shorten(track.title) + "」的播放链接，要等几十秒");

        runAsync(new Runnable() {
            @Override
            public void run() {
                String url = null;
                String error = null;
                try {
                    LxSource source = findWySource();
                    if (source == null) {
                        error = sources.isEmpty()
                                ? "还没有音源脚本：把 LX 规范的 .js 放到 "
                                + LxManager.dir(getContext()).getAbsolutePath() + " 再试"
                                : "这些音源都没有声明网易云平台";
                    } else {
                        String quality = pickQuality(wyPlatform == null ? null : wyPlatform.qualities);
                        LxRuntime runtime = runtimeFor(source);
                        String script = LxManager.readAll(
                                new File(LxManager.dir(getContext()), source.fileName));
                        LxRuntime.Result init = runtime.test(script, source);
                        if (!init.ok) {
                            error = "音源初始化失败：" + init.error;
                        } else {
                            LxRuntime.UrlResult result = runtime.resolveUrl(WY, musicInfo(track), quality);
                            if (result.ok) {
                                url = result.url;
                            } else {
                                error = result.error;
                            }
                        }
                    }
                } catch (Throwable th) {
                    error = describe(th);
                }
                final String resolved = url;
                final String failure = error;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone()) {
                            return;
                        }
                        // 只认「还是这一首」的结果：这半分钟里用户可能已经点了别的歌
                        if (resolvingTrack != track) {
                            return;
                        }
                        resolvingTrack = null;
                        if (resolved == null) {
                            updateBar();
                            toast("没取到播放链接：" + failure);
                            return;
                        }
                        android.util.Log.i(TAG, "在线链接 " + track.onlineId + " -> " + resolved);
                        track.path = resolved;
                        if (!startPlayer(track)) {
                            toast("取到的链接放不出来，可能已经失效了");
                        }
                    }
                });
            }
        });
    }

    /**
     * 找能出网易云链接的音源脚本。
     *
     * <p>「哪个脚本支持网易云」要先跑一遍脚本才拿得到（平台列表是它 send('inited') 时报上来的），
     * 而跑脚本是重活（墨澜源首跑 ~20 秒）。所以只在真的要播在线歌时才付这个成本，
     * 而且结论会落到 {@link #wySourceFile} 和脚本自己的 {@code platforms} 里，
     * 之后进「音源」页也能直接看到它支持什么。
     */
    private LxSource findWySource() {
        wyPlatform = null;
        if (wySourceFile != null) {
            LxSource cached = sourceByFile(wySourceFile);
            if (cached != null) {
                wyPlatform = platformOf(cached.platforms, WY);
                if (wyPlatform != null) {
                    return cached;
                }
            }
        }
        for (LxSource source : sourceSnapshot()) {
            // 平台列表可能已经从上次的测试结果里贴回来了（applyState / publishPlatforms），
            // 有就直接用，省掉一次十几秒的脚本执行
            LxSource.Platform platform;
            synchronized (source.platforms) {
                platform = platformOf(source.platforms, WY);
            }
            if (platform == null) {
                LxRuntime runtime = runtimeFor(source);
                String script = LxManager.readAll(new File(LxManager.dir(getContext()), source.fileName));
                LxRuntime.Result result = runtime.test(script, source);
                if (!result.ok) {
                    continue;
                }
                platform = platformOf(result.platforms, WY);
                if (platform == null) {
                    continue;
                }
                publishPlatforms(source, result);
            }
            wySourceFile = source.fileName;
            wyPlatform = platform;
            return source;
        }
        return null;
    }

    /**
     * 音源列表的快照。
     *
     * <p>扫描是在后台线程里跑、结论回到主线程一次性贴进来的（{@code clear + addAll}），
     * 播放线程正好在这中间读就会看到半截列表甚至撞上并发修改。所以两边都用同一把锁。
     */
    private List<LxSource> sourceSnapshot() {
        synchronized (sources) {
            return new ArrayList<>(sources);
        }
    }

    private LxSource sourceByFile(String fileName) {
        for (LxSource source : sourceSnapshot()) {
            if (TextUtils.equals(fileName, source.fileName)) {
                return source;
            }
        }
        return null;
    }

    /** 把刚测出来的平台列表贴回界面上的那份（音源页显示的就是它） */
    private void publishPlatforms(final LxSource source, final LxRuntime.Result result) {
        post(new Runnable() {
            @Override
            public void run() {
                if (gone()) {
                    return;
                }
                // 播放线程会读这份列表（找网易云的平台），换内容要加锁
                synchronized (source.platforms) {
                    source.platforms.clear();
                    source.platforms.addAll(result.platforms);
                }
                source.hasRequestHandler = result.hasRequestHandler;
                source.status = LxSource.STATUS_OK;
                source.detail = result.summary();
                LxManager.saveState(getContext(), sources);
            }
        });
    }

    private static LxSource.Platform platformOf(List<LxSource.Platform> platforms, String id) {
        for (LxSource.Platform platform : platforms) {
            if (TextUtils.equals(id, platform.id)) {
                return platform;
            }
        }
        return null;
    }

    /** 音质：优先 320k（听感够、又不至于像 flac 那样容易取不到），没有就用它声明的第一个 */
    private static String pickQuality(List<String> qualities) {
        if (qualities == null || qualities.isEmpty()) {
            return "320k";
        }
        for (String quality : qualities) {
            if ("320k".equals(quality)) {
                return quality;
            }
        }
        return qualities.get(0);
    }

    /**
     * 交给音源脚本的歌曲信息。
     *
     * <p>字段塞得比需要的多，是被逼的：LX 各家的 wy 平台读的键不一样——老脚本找
     * {@code songmid}，新的找 {@code id}。只给一个的话换个源就取不到链接，
     * 而多给几个对脚本没有任何副作用（它只读自己要的那个）。
     */
    private static String musicInfo(Track track) {
        try {
            JSONObject info = new JSONObject();
            info.put("id", track.onlineId);
            info.put("songmid", track.onlineId);
            info.put("songId", track.onlineId);
            info.put("name", safe(track.title));
            info.put("singer", safe(track.artist));
            info.put("albumName", safe(track.album));
            return info.toString();
        } catch (Throwable th) {
            return "{\"id\":\"" + track.onlineId + "\"}";
        }
    }

    /** 提示语里用，太长会把 Toast 撑满半屏 */
    private static String shorten(String text) {
        String value = safe(text);
        return value.length() > 12 ? value.substring(0, 12) + "…" : value;
    }

    private void togglePlay() {
        if (player == null || nowPlaying == null) {
            // 还没播过：从列表第一首开始，比什么都不发生要好
            if (!queue.isEmpty()) {
                queueIndex = 0;
                past.clear();
                play(queue.get(0));
            }
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
            } else {
                player.start();
            }
            updateBar();
        } catch (Throwable th) {
            android.util.Log.w(TAG, "切换播放状态失败", th);
        }
    }

    private void playNext() {
        advanceQueue(false);
    }

    /**
     * 往下走一首。
     *
     * @param auto 是不是「播完了自动切」。只有自动时才轮到单首循环生效——
     *             用户明确按了下一首，那就该换歌，再给他重播一遍是耍人。
     */
    private void advanceQueue(boolean auto) {
        if (queue.isEmpty()) {
            return;
        }
        if (auto && playMode == MODE_ONE && queueIndex >= 0) {
            // 重播当前这首：重新走 play() 会再发一次取链接，在线歌等于白等几十秒。
            // 本地和在线这时候播放器里都已经有货了，回到零起点继续放就行。
            if (rewindCurrent()) {
                return;
            }
        }
        int next = queueIndex + 1;
        if (playMode == MODE_SHUFFLE && queue.size() > 1) {
            next = randomIndex();
        } else if (next >= queue.size()) {
            next = 0;
        }
        if (queueIndex >= 0 && queueIndex < queue.size()) {
            past.add(queueIndex);
            // 历史栈只留一条来路就够了，电视上没人会连着倒回去十几首
            if (past.size() > 30) {
                past.remove(0);
            }
        }
        queueIndex = next;
        play(queue.get(next));
    }

    /** 队列里随机挑一首，尽量不挑到现在这首 */
    private int randomIndex() {
        int candidate = random.nextInt(queue.size());
        int guard = 0;
        while (candidate == queueIndex && guard++ < 8) {
            candidate = random.nextInt(queue.size());
        }
        return candidate;
    }

    /**
     * 单曲循环：把当前这首退回开头继续放。
     *
     * @return 是否真的重播了。播放器还没准备好（取链接在路上、或上一次失败）时返回 false，
     *         让调用方退回正常的切歌逻辑，否则界面会停在一个不再前进的进度上。
     */
    private boolean rewindCurrent() {
        if (player == null || nowPlaying == null) {
            return false;
        }
        try {
            player.seekTo(0);
            player.start();
            updateBar();
            return true;
        } catch (Throwable th) {
            android.util.Log.w(TAG, "单曲重播失败", th);
            return false;
        }
    }

    private void playPrev() {
        if (queue.isEmpty()) {
            return;
        }
        if (!past.isEmpty()) {
            queueIndex = past.remove(past.size() - 1);
            play(queue.get(queueIndex));
            return;
        }
        int prev = queueIndex - 1;
        if (prev < 0) {
            prev = queue.size() - 1;
        }
        queueIndex = prev;
        play(queue.get(prev));
    }

    // ---------------------------------------------------------------- 播放模式 / 收藏

    /** 顺序 → 随机 → 单首循环 → 顺序。两处按钮共用，点一下就切 */
    private void cyclePlayMode() {
        playMode = (playMode + 1) % 3;
        DeskPrefs.setMusicPlayMode(getContext(), playMode);
        renderPlayMode();
        toast(modeName(playMode));
    }

    private String modeName(int mode) {
        if (mode == MODE_SHUFFLE) {
            return "列表随机";
        }
        return mode == MODE_ONE ? "单首循环" : "列表顺序";
    }

    private int modeIcon(int mode) {
        if (mode == MODE_SHUFFLE) {
            return R.drawable.ic_mode_shuffle;
        }
        return mode == MODE_ONE ? R.drawable.ic_mode_one : R.drawable.ic_mode_order;
    }

    /** 图标 + 焦点提示语一起刷；播放条和播放页那两份显示走同一个出口 */
    private void renderPlayMode() {
        if (modeBtn != null) {
            modeBtn.setImageResource(modeIcon(playMode));
            modeBtn.setContentDescription("播放方式：" + modeName(playMode));
        }
        if (stageMode != null) {
            stageMode.setImageResource(modeIcon(playMode));
            stageMode.setContentDescription("播放方式：" + modeName(playMode));
        }
    }

    /**
     * 收藏/取消收藏当前这首。
     *
     * <p>界面先动、请求后发：电视上这个往返要几百毫秒，反过来做的话按下去看不到任何反应，
     * 用户会以为没按到而再按一次，第二下刚好把刚点上的收藏又取消掉。请求失败再把图标改回去。
     */
    private void toggleLike() {
        final Track track = nowPlaying != null ? nowPlaying : resolvingTrack;
        if (track == null) {
            toast("还没有在播的歌");
            return;
        }
        if (!netease().loggedIn()) {
            toast("收藏要先登录网易云");
            return;
        }
        if (!likedLoaded) {
            loadLiked();
        }
        if (track.isOnline()) {
            applyLike(track.onlineId);
            return;
        }
        // 本地歌得先搜出对得上的网易云 id，那是个网络请求，不能在主线程等
        runAsync(new Runnable() {
            @Override
            public void run() {
                String found;
                try {
                    found = resolveLocalSongId(track);
                } catch (Throwable th) {
                    found = "";
                }
                final String id = found == null ? "" : found;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone() || nowPlaying != track) {
                            return;
                        }
                        applyLike(id);
                    }
                });
            }
        });
    }

    private void applyLike(String songId) {
        if (TextUtils.isEmpty(songId)) {
            toast("这首歌对不上网易云的曲库，收藏不了");
            return;
        }
        final String id = songId;
        final boolean on = !liked.contains(id);
        setLiked(id, on);
        setLikeShown(on);
        final NeteaseClient client = netease();
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    client.like(id, on);
                    client.markLiked(id, on);
                } catch (Throwable th) {
                    android.util.Log.w(TAG, "收藏失败 id=" + id, th);
                    final String why = th instanceof NeteaseClient.NeteaseException
                            ? th.getMessage() : "网络不给力";
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (gone()) {
                                return;
                            }
                            setLiked(id, !on);
                            setLikeShown(isLikedNow());
                            toast("收藏没改成：" + why);
                        }
                    });
                }
            }
        });
    }

    private void setLiked(String id, boolean on) {
        if (on) {
            liked.add(id);
        } else {
            liked.remove(id);
        }
    }

    /** 当前这首对应的网易云 id；本地歌复用歌词那套「歌名 + 歌手」的猜测结果 */
    private String neteaseIdOf(Track track) {
        if (track.isOnline()) {
            return track.onlineId;
        }
        return peekLocalSongId(track);
    }

    /**
     * 只查缓存的「本地歌 → 网易云 id」。
     *
     * <p>渲染收藏状态时每 0.3 秒都会走一遍，这里绝不能顺手发一次搜索请求。
     */
    private String peekLocalSongId(Track track) {
        String cached = lyricIdCache.get(safe(track.title) + "|" + safe(track.artist));
        return cached == null ? "" : cached;
    }

    private void setLikeShown(boolean on) {
        int icon = on ? R.drawable.ic_like_on : R.drawable.ic_like;
        String desc = on ? "取消收藏" : "收藏";
        if (likeBtn != null) {
            likeBtn.setImageResource(icon);
            likeBtn.setContentDescription(desc);
        }
        if (stageLike != null) {
            stageLike.setImageResource(icon);
            stageLike.setContentDescription(desc);
        }
    }

    /** 把已收藏的 id 取回来。只在登录后调，失败就当没收藏 */
    private void loadLiked() {
        likedLoaded = true;
        final NeteaseClient client = netease();
        runAsync(new Runnable() {
            @Override
            public void run() {
                final Set<String> ids;
                try {
                    ids = client.likedIds();
                } catch (Throwable th) {
                    android.util.Log.w(TAG, "收藏列表取不到", th);
                    return;
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone()) {
                            return;
                        }
                        // 换内容要在主线程做：liked 是普通 HashSet，
                        // 后台线程直接 clear/addAll 会跟渲染红心时的 contains 撞上。
                        liked.clear();
                        liked.addAll(ids);
                        updateBar();
                    }
                });
            }
        });
    }

    /** 当前这首是不是已收藏。没登录就没有收藏这回事 */
    private boolean isLikedNow() {
        Track track = nowPlaying;
        if (track == null || liked.isEmpty()) {
            return false;
        }
        String id = track.isOnline() ? track.onlineId : peekLocalSongId(track);
        return !TextUtils.isEmpty(id) && liked.contains(id);
    }

    private void updateBar() {
        boolean resolving = resolvingTrack != null;
        Track shown = nowPlaying != null ? nowPlaying : resolvingTrack;
        if (shown == null) {
            bar.setVisibility(View.GONE);
            if (nowChipText != null) {
                nowChipText.setText("正在播放");
            }
            // 播放页不跟着退：用户是在播放页里切歌时碰上取链接失败/文件不在的，
            // 界面一退，他连「刚才那首没放出来」都来不及看清，只会以为按错了键。
            if (stageOpen) {
                stageMeta.setText("这首歌放不出来 · 按上一首 / 下一首继续");
                applyPlaying(false);
            }
            return;
        }
        bar.setVisibility(View.VISIBLE);
        if (nowChipText != null) {
            nowChipText.setText(TextUtils.isEmpty(shown.title) ? fileName(shown.path) : shown.title);
        }
        nowTitle.setText(TextUtils.isEmpty(shown.title) ? fileName(shown.path) : shown.title);
        // 取链接那几十秒里必须有个说法，否则用户看到的是一首「正在播」但听不到声音的歌
        nowMeta.setText(resolving && nowPlaying == null
                ? "正在取播放链接…" : join(safe(shown.artist), safe(shown.album)));
        renderBarCover(shown);
        setLikeShown(isLikedNow());

        boolean playing = false;
        try {
            playing = !resolving && player != null && player.isPlaying();
        } catch (Throwable ignored) {
        }
        toggleBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        toggleBtn.setContentDescription(playing ? "暂停" : "播放");
        // 播放页开着时它和播放条是同一样东西的两份显示，必须一起改。
        // 图标的唯一出口在这里：手动暂停、切歌、自动播完都走 updateBar，
        // 别处再设一次就会打架。
        if (stageOpen) {
            renderStageHead(shown);
            applyPlaying(playing);
        }
    }

    /**
     * 播放条左边的圆形封面。
     *
     * <p>没有封面时只把图清空，底下那颗圆和音符还在——位置不能跳，否则歌名会跟着左右挪，
     * 看着像界面在抖。
     */
    private void renderBarCover(Track shown) {
        boolean hasCover = !TextUtils.isEmpty(shown.coverUrl);
        nowNote.setVisibility(hasCover ? View.GONE : View.VISIBLE);
        if (!hasCover) {
            Glide.with(getContext()).clear(nowCover);
            nowCover.setImageDrawable(null);
            return;
        }
        try {
            Glide.with(getContext())
                    .load(shown.coverUrl)
                    .placeholder(R.drawable.bg_music_bar_cover)
                    .transform(new CenterCrop(), new CircleCrop())
                    .into(nowCover);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "播放条封面加载失败：" + shown.coverUrl, th);
        }
    }

    // ---------------------------------------------------------------- 沉浸播放页

    /**
     * 装配沉浸播放页。
     *
     * <p>它不是一个 ModulePage，只是本页上面的一个遮罩层：做成独立页的话，
     * 退出时要把焦点还回列表原来那一行，就得再记一份行位置，纯属自找麻烦。
     */
    private void setupStage() {
        stage = findViewById(R.id.music_stage);
        stageBg = findViewById(R.id.stage_bg);
        stageTurntable = findViewById(R.id.stage_turntable);
        stageDisc = findViewById(R.id.stage_disc);
        stageCover = findViewById(R.id.stage_cover);
        stageNote = findViewById(R.id.stage_note);
        stageLyric = findViewById(R.id.stage_lyric);
        stageTitle = findViewById(R.id.stage_title);
        stageMeta = findViewById(R.id.stage_meta);
        stagePos = findViewById(R.id.stage_pos);
        stageDur = findViewById(R.id.stage_dur);
        stageProgress = findViewById(R.id.stage_progress);
        stageToggle = findViewById(R.id.stage_toggle);
        stageLike = findViewById(R.id.stage_like);
        stageMode = findViewById(R.id.stage_mode);
        stageControls = findViewById(R.id.stage_controls);
        ImageView stagePrev = findViewById(R.id.stage_prev);
        ImageView stageNext = findViewById(R.id.stage_next);

        // 控制区收起时焦点得有个去处，否则系统会把它甩到页签或播放条上。
        // 这一层自己不是可点控件，拿到焦点也不会有焦点框。
        stage.setFocusable(true);
        stage.setFocusableInTouchMode(true);

        FocusScaler.attach(stagePrev, 1.06f);
        FocusScaler.attach(stageToggle, 1.06f);
        FocusScaler.attach(stageNext, 1.06f);
        FocusScaler.attach(stageLike, 1.06f);
        FocusScaler.attach(stageMode, 1.06f);
        // 电视遥控器用不到 touch 模式，但模拟器/触屏盒子点一下屏幕就会进 touch 模式，
        // 这时普通 focusable 的 ImageView 收不到 requestFocus，控制区淡出后就唤不回来。
        stagePrev.setFocusableInTouchMode(true);
        stageToggle.setFocusableInTouchMode(true);
        stageNext.setFocusableInTouchMode(true);
        stageLike.setFocusableInTouchMode(true);
        stageMode.setFocusableInTouchMode(true);
        stagePrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playPrev();
            }
        });
        stageNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playNext();
            }
        });
        stageToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        stageLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PressFx.heart(v);
                toggleLike();
            }
        });
        stageMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PressFx.squish(v);
                cyclePlayMode();
            }
        });

        // 焦点只在这五个按钮之间转。这一层没有列表可去，UP/DOWN 要是能把焦点
        // 丢到封面或歌词上，遥控器就再也按不回来了。
        // 排法是「左右两列 + 中间传输键」：上下键从任意一颗都落到方式（上）/收藏（下），
        // 左右键沿一排走。暂停那颗的左右被快退快进占了（见下面的 OnKeyListener），
        // 所以它只能靠上下离开。
        stageMode.setNextFocusRightId(R.id.stage_prev);
        stageMode.setNextFocusUpId(R.id.stage_mode);
        stageMode.setNextFocusDownId(R.id.stage_mode);
        stagePrev.setNextFocusLeftId(R.id.stage_mode);
        stagePrev.setNextFocusRightId(R.id.stage_toggle);
        stagePrev.setNextFocusUpId(R.id.stage_mode);
        stagePrev.setNextFocusDownId(R.id.stage_like);
        stageToggle.setNextFocusUpId(R.id.stage_mode);
        stageToggle.setNextFocusDownId(R.id.stage_like);
        stageNext.setNextFocusLeftId(R.id.stage_toggle);
        stageNext.setNextFocusRightId(R.id.stage_like);
        stageNext.setNextFocusUpId(R.id.stage_mode);
        stageNext.setNextFocusDownId(R.id.stage_like);
        stageLike.setNextFocusLeftId(R.id.stage_next);
        stageLike.setNextFocusUpId(R.id.stage_mode);
        stageLike.setNextFocusDownId(R.id.stage_like);

        // 「暂停」这颗上的左右是快退/快进：这一层就三个按钮，左右键落不到别处，
        // 空转不如给个播放器人人都按的动作。OnKeyListener 比焦点搜索先跑，抢得到。
        stageToggle.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    startHoldSeek(-1, event.getRepeatCount());
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    startHoldSeek(1, event.getRepeatCount());
                    return true;
                }
                return false;
            }
        });
    }

    private void openStage() {
        if (stageOpen) {
            return;
        }
        Track shown = nowPlaying != null ? nowPlaying : resolvingTrack;
        if (shown == null) {
            return;
        }
        stageOpen = true;
        stage.setVisibility(View.VISIBLE);
        stage.animate().cancel();
        stage.setAlpha(0f);
        stage.animate().alpha(1f).setDuration(240).start();
        // 文字、封面、播放图标都归 updateBar 管，这里借它一次刷齐
        updateBar();
        renderStageHead(shown);
        loadLyric(shown);
        syncStageProgress();
        ui.removeCallbacks(stageTick);
        ui.postDelayed(stageTick, 300);
        // 刚展开时控制区必须齐整；上一次退出时可能是淡出到一半被关掉的
        stageChromeHidden = false;
        stageControls.animate().cancel();
        stageControls.setAlpha(1f);
        stageControls.setVisibility(View.VISIBLE);
        shiftStageHead(false);
        stageToggle.requestFocus();
        scheduleStageChromeHide();
    }

    private void closeStage() {
        if (!stageOpen) {
            return;
        }
        stageOpen = false;
        stopHoldSeek();
        ui.removeCallbacks(stageTick);
        ui.removeCallbacks(stageChromeHide);
        stageChromeHidden = false;
        stageControls.animate().cancel();
        stageControls.setAlpha(1f);
        stageControls.setVisibility(View.VISIBLE);
        shiftStageHead(false);
        stopSpin();
        stage.animate().cancel();
        stage.setVisibility(View.GONE);
        // 焦点回原来进来的那颗，别丢回列表顶部——用户是按了展开才进来的。
        // requestFocus 可能因为设备处在触摸模式而失败（触屏上这一排不是触摸可聚焦的），
        // 那时退回列表，总之不能让焦点悬空。
        if (bar.getVisibility() == View.VISIBLE && expandBtn != null && expandBtn.requestFocus()) {
            return;
        }
        focusList();
    }

    /**
     * 收起进度条和五颗按钮。
     *
     * <p>焦点先挪到播放页根上：留在即将隐藏的按钮里，方向键会找不到候选直接失灵。
     * 用 INVISIBLE 而不是 GONE——控制区一 GONE，下面的曲名会往上顶，
     * 每次淡出画面都抖一下。
     */
    private void hideStageControls() {
        if (!stageOpen || stageChromeHidden || stageControls == null) {
            return;
        }
        stageChromeHidden = true;
        stage.requestFocus();
        stageControls.animate().cancel();
        stageControls.animate().alpha(0f).setDuration(320).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (stageChromeHidden) {
                    stageControls.setVisibility(View.INVISIBLE);
                }
            }
        }).start();
        // 控制区淡出的同时，曲名/歌手整体下移，落进腾出来的位置：
        // 只淡出一半、上面那两行却悬在半空，画面重心会飘。用 INVISIBLE 保留布局高度，
        // 所以位移量要自己补上（见 hide 的注释）。
        shiftStageHead(true);
    }

    /**
     * 沉浸态（控制区收起）时把曲名/歌手往下挪一档，唱机一起同步下沉。
     *
     * <p>位移量取控制区的实测高度；刚展开还没 layout 时给一个和字号/按钮尺寸相符的估值。
     * 唱机只挪曲名的三分之一：这台机器本来就顶出了可用高度，挪满了盘沿会压到曲名上。
     */
    private void shiftStageHead(final boolean down) {
        if (stageTitle == null || stageMeta == null) {
            return;
        }
        float shift = 0f;
        if (down) {
            shift = stageControls != null && stageControls.getHeight() > 0
                    ? stageControls.getHeight() : dp(118);
        }
        stageTitle.animate().translationY(shift).setDuration(320).start();
        stageMeta.animate().translationY(shift).setDuration(320).start();
        if (stageTurntable != null) {
            stageTurntable.animate().translationY(down ? shift / 3f : 0f)
                    .setDuration(320).start();
        }
    }

    /** 唤回控制区。focusToggle 为真时焦点回暂停键——用户刚按了键，下一步多半要操作 */
    private void showStageControls(boolean focusToggle) {
        if (stageControls == null) {
            return;
        }
        boolean wasHidden = stageChromeHidden;
        stageChromeHidden = false;
        stageControls.animate().cancel();
        stageControls.setVisibility(View.VISIBLE);
        if (wasHidden) {
            shiftStageHead(false);
            stageControls.setAlpha(0f);
            stageControls.animate().alpha(1f).setDuration(220).start();
        } else {
            stageControls.setAlpha(1f);
        }
        if (focusToggle) {
            stageToggle.requestFocus();
        }
        scheduleStageChromeHide();
    }

    private void scheduleStageChromeHide() {
        ui.removeCallbacks(stageChromeHide);
        if (stageOpen) {
            ui.postDelayed(stageChromeHide, STAGE_CHROME_IDLE_MS);
        }
    }

    /** 进度条、时间、歌词位置。只有播放页开着才需要，所以由 {@link #stageTick} 驱动 */
    private void syncStageProgress() {
        int position = 0;
        int duration = 0;
        if (player != null && nowPlaying != null) {
            try {
                position = player.getCurrentPosition();
                duration = player.getDuration();
            } catch (Throwable ignored) {
            }
        }
        stageProgress.setProgress(duration > 0 ? (int) (position * 1000L / duration) : 0);
        stagePos.setText(formatDuration(position));
        stageDur.setText(formatDuration(duration));
        stageLyric.setPosition(position);
    }

    /** 曲名、歌手、封面、背景。换歌和刚展开时各刷一次，不跟着进度走 */
    private void renderStageHead(Track shown) {
        stageTitle.setText(TextUtils.isEmpty(shown.title) ? fileName(shown.path) : shown.title);
        stageMeta.setText(join(safe(shown.artist), safe(shown.album)));
        boolean hasCover = !TextUtils.isEmpty(shown.coverUrl);
        stageCover.setVisibility(hasCover ? View.VISIBLE : View.GONE);
        stageNote.setVisibility(hasCover ? View.GONE : View.VISIBLE);
        if (hasCover) {
            try {
                Glide.with(getContext())
                        .load(shown.coverUrl)
                        .transform(new CenterCrop(), new CircleCrop())
                        .into(stageCover);
            } catch (Throwable th) {
                android.util.Log.w(TAG, "播放页封面加载失败：" + shown.coverUrl, th);
            }
            try {
                // 背景照整页那套：解码成一张小图再拉满屏，放大插值就是最便宜的模糊
                Glide.with(getContext())
                        .load(shown.coverUrl)
                        .override(120, 120)
                        .centerCrop()
                        .transition(DrawableTransitionOptions.withCrossFade(420))
                        .into(stageBg);
            } catch (Throwable ignored) {
            }
        } else {
            Glide.with(getContext()).clear(stageCover);
            Glide.with(getContext()).clear(stageBg);
            stageCover.setImageDrawable(null);
            stageBg.setImageDrawable(null);
        }
    }

    /** 播放/暂停切换时图标和盘的转动一起改，两处按钮共用这一个出口 */
    private void applyPlaying(boolean playing) {
        stageToggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        stageToggle.setContentDescription(playing ? "暂停" : "播放");
        if (playing) {
            startSpin();
            scheduleStageChromeHide();
        } else {
            if (discSpin != null) {
                discSpin.pause();
            }
            // 暂停时控制区必须露着。用户可能就是回来按播放的，
            // 这时要是还藏着，他得先按一下唤醒、再按一下播放
            showStageControls(false);
        }
    }

    /** 一圈 18 秒：再快就转成电风扇，慢过这个数又看不出在动 */
    private void startSpin() {
        if (discSpin != null) {
            if (discSpin.isPaused()) {
                discSpin.resume();
            }
            return;
        }
        discSpin = ValueAnimator.ofFloat(0f, 360f);
        discSpin.setDuration(18000L);
        discSpin.setRepeatCount(ValueAnimator.INFINITE);
        discSpin.setInterpolator(new LinearInterpolator());
        discSpin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (stageDisc != null) {
                    stageDisc.setRotation((Float) animation.getAnimatedValue());
                }
            }
        });
        discSpin.start();
    }

    private void stopSpin() {
        if (discSpin != null) {
            discSpin.cancel();
            discSpin = null;
        }
        if (stageDisc != null) {
            stageDisc.setRotation(0f);
        }
    }

    /** 按住左右键连续快退/快进：第一下立刻走 15 秒，之后按节拍加速 */
    private static final long HOLD_SEEK_DELAY = 400L;
    private static final long HOLD_SEEK_INTERVAL = 260L;
    private int holdSeekDirection;
    private int holdSeekTicks;

    private void startHoldSeek(int direction, int repeatCount) {
        if (repeatCount != 0 || player == null || nowPlaying == null) {
            return;
        }
        holdSeekDirection = direction;
        holdSeekTicks = 0;
        seekBy(direction * 15000);
        ui.removeCallbacks(holdSeekTask);
        ui.postDelayed(holdSeekTask, HOLD_SEEK_DELAY);
    }

    private void stopHoldSeek() {
        holdSeekDirection = 0;
        ui.removeCallbacks(holdSeekTask);
    }

    private final Runnable holdSeekTask = new Runnable() {
        @Override
        public void run() {
            if (holdSeekDirection == 0 || player == null || nowPlaying == null) {
                return;
            }
            holdSeekTicks++;
            int step = holdSeekTicks < 8 ? 15000
                    : holdSeekTicks < 20 ? 30000 : 60000;
            seekBy(holdSeekDirection * step);
            // 连发时控制区不能自己淡出去，否则用户看不到进度在哪儿
            if (stageOpen) {
                showStageControls(false);
            }
            ui.postDelayed(this, HOLD_SEEK_INTERVAL);
        }
    };

    /** 快退/快进。seek 到末尾会直接触发 onCompletion 切下一首，这里不用特殊处理 */
    private void seekBy(int deltaMs) {
        if (player == null || nowPlaying == null) {
            return;
        }
        try {
            int duration = player.getDuration();
            int target = player.getCurrentPosition() + deltaMs;
            if (target < 0) {
                target = 0;
            }
            if (duration > 0 && target > duration - 1000) {
                target = Math.max(0, duration - 1000);
            }
            player.seekTo(target);
            syncStageProgress();
        } catch (Throwable th) {
            android.util.Log.w(TAG, "跳转失败", th);
        }
    }

    // ---------------------------------------------------------------- 歌词

    /** 缓存键：在线歌本来就有网易云 id，本地歌只能拿「歌名+歌手」当身份 */
    private static String lyricKey(Track track) {
        return track.isOnline()
                ? "n:" + track.onlineId
                : "l:" + safe(track.title) + "|" + safe(track.artist);
    }

    /**
     * 取当前这首歌的歌词。
     *
     * <p>在线曲目手上就有网易云 id，直接查；本地歌没有，得先用「歌名 歌手」搜一条对上台。
     * 两种都要走网络，所以一律后台线程，回来时确认「用户要的还是这首」——和取播放链接同一套做法。
     * 开播就取，不等用户展开播放页：展开的那一下必须立刻有词，不能又让他等。
     */
    private void loadLyric(final Track track) {
        if (track == null) {
            return;
        }
        final String key = lyricKey(track);
        String cached = lyricCache.get(key);
        if (cached != null) {
            applyLyric(track, cached);
            return;
        }
        if (lyricFor == track) {
            return;
        }
        lyricFor = track;
        stageLyric.setPlaceholder(track.isOnline() ? "正在取歌词…" : "正在匹配歌词…");
        runAsync(new Runnable() {
            @Override
            public void run() {
                String lrc = "";
                try {
                    String songId = track.isOnline()
                            ? track.onlineId : resolveLocalSongId(track);
                    if (!TextUtils.isEmpty(songId)) {
                        lrc = netease().lyric(songId);
                    }
                } catch (Throwable th) {
                    android.util.Log.w(TAG, "歌词取不到：" + key, th);
                }
                final String done = lrc == null ? "" : lrc;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (gone()) {
                            return;
                        }
                        if (lyricFor != track) {
                            return;
                        }
                        lyricFor = null;
                        lyricCache.put(key, done);
                        applyLyric(track, done);
                    }
                });
            }
        });
    }

    private void applyLyric(Track track, String lrc) {
        // 缓存命中时也可能已经换了歌，别把上一首的词刷到这一首上面
        if (nowPlaying != track) {
            return;
        }
        stageLyric.setSource(lrc);
        if (TextUtils.isEmpty(lrc)) {
            stageLyric.setPlaceholder("这首歌没有官方歌词");
        }
    }

    /**
     * 给本地歌找一个网易云 id。
     *
     * <p>只按歌名匹配会串到别人的翻唱上，所以先要「歌名相同且歌手对得上」，
     * 找不到才退到歌名相同，再退到第一条结果。错配最多是歌词差几句，
     * 但完全不猜的话本地歌就一行词都没有。
     */
    private String resolveLocalSongId(Track track) {
        String key = safe(track.title) + "|" + safe(track.artist);
        String known = lyricIdCache.get(key);
        if (known != null) {
            return known;
        }
        String keyword = TextUtils.isEmpty(track.artist)
                ? track.title : track.title + " " + track.artist;
        String found = "";
        try {
            List<NeteaseClient.Song> songs = netease().search(keyword, 8);
            NeteaseClient.Song byName = null;
            for (NeteaseClient.Song song : songs) {
                if (TextUtils.isEmpty(song.name) || !song.name.equalsIgnoreCase(track.title)) {
                    continue;
                }
                if (byName == null) {
                    byName = song;
                }
                if (!TextUtils.isEmpty(track.artist) && song.artist != null
                        && song.artist.contains(track.artist)) {
                    byName = song;
                    break;
                }
            }
            if (byName != null) {
                found = byName.id;
            } else if (!songs.isEmpty()) {
                found = songs.get(0).id;
            }
        } catch (Throwable th) {
            android.util.Log.w(TAG, "本地歌匹配网易云 id 失败：" + key, th);
        }
        lyricIdCache.put(key, found);
        return found;
    }

    // ---------------------------------------------------------------- 按键

    /**
     * 宿主转发按键进来。返回 false 的 BACK 由宿主关页回桌面。
     */
    @Override
    public boolean handleKeyDown(int keyCode) {
        // 层级表顺序：L2 浮层（沉浸播放页、胶囊菜单）→ L3 页内子视图 → L4 输入态 → 交回宿主
        // 沉浸播放页开着时，BACK 只收这一层，不收整个音乐模块
        if (keyCode == KeyEvent.KEYCODE_BACK && stageOpen) {
            closeStage();
            return Back.consume("音乐·收起沉浸播放页");
        }
        // 胶囊下拉开着时先让 BACK 收菜单
        if (keyCode == KeyEvent.KEYCODE_BACK && menuOpen) {
            setMenuOpen(false);
            switcher.requestFocus();
            return Back.consume("音乐·收起胶囊菜单");
        }
        // 焦点兜底：重建列表、播放结束切歌这些时机都可能把焦点弄丢，
        // 一旦丢了遥控器就完全没反应。任何方向键先把它救回页签上。
        // 播放页开着时不能走这条：那会儿焦点在播放页的三个按钮上，
        // 一旦丢了要回的是播放条，不是页签后面的列表。
        if (!stageOpen && findFocus() == null && keyCode >= KeyEvent.KEYCODE_DPAD_UP
                && keyCode <= KeyEvent.KEYCODE_DPAD_CENTER) {
            focusChip();
            return true;
        }
        // 上下在「页签 ↔ 列表/搜索框」之间手动路由。
        // 不能靠几何搜索：列表空的时候 DOWN 会越过列表掉到播放条上，
        // 用户就再也回不去页签了。播放页开着时这一套全部不适用，那层自己管自己的焦点。
        if (!stageOpen && keyCode == KeyEvent.KEYCODE_DPAD_DOWN && navItems.findFocus() != null) {
            if (section == SEC_SEARCH) {
                focusSearch();
            } else if (adapter.getCount() > 0) {
                focusList();
            }
            return true;
        }
        if (!stageOpen && keyCode == KeyEvent.KEYCODE_DPAD_UP
                && (list.hasFocus() || grid.hasFocus() || searchInput.hasFocus()
                || playAllBtn.hasFocus())) {
            hideIme();
            focusChip();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 数据还在路上就按了返回：那条「焦点放进新层」的预约作废，否则会抢走下面的回位
            pendingContentFocus = -1;
            // 钻进某张在线歌单/榜单的，返回一次回它的目录
            if (onlinePlaylistId != null) {
                onlinePlaylistId = null;
                onlinePlaylistName = null;
                onlinePlaylistCover = null;
                onlinePlaylistTracks = null;
                onlinePlaylistError = null;
                // 「我喜欢的音乐」自己就是一层，它上面没有目录，回到「我的」入口
                if (mineTab == MINE_LIKED) {
                    mineTab = -1;
                }
                rebuildRows();
                focusList(drillFromIndex);
                return Back.consume("音乐·退出在线歌单");
            }
            // 先进过某个歌单/歌手/专辑/文件夹/音源的，返回一次退到上级列表
            if (drillKey != null) {
                drillKey = null;
                drillLabel = null;
                rebuildRows();
                focusList(drillFromIndex);
                return Back.consume("音乐·退出分组列表");
            }
            // 「我的」的子视图：返回一次回入口列表
            if (mineTab >= 0) {
                mineTab = -1;
                rebuildRows();
                focusList(drillFromIndex);
                return Back.consume("音乐·退出「我的」子页");
            }
            // 搜索框里还留着词：返回一次清掉，别直接把人弹出模块
            if (section == SEC_SEARCH && !TextUtils.isEmpty(searchQuery)) {
                searchQuery = "";
                searchInput.setText("");
                hideIme();
                rebuildRows();
                focusSearch();
                return Back.consume("音乐·清空搜索词");
            }
            // 没层级可退了，交给宿主关页回桌面。
            // 注意这里不再「先回页签再退出」：页签和列表是同一个内嵌页里的两块，
            // 不是两级页面，多要一次 BACK 只会让人以为遥控器失灵。
            return Back.pass("音乐·页内已无层级");
        }
        if (keyCode == KeyEvent.KEYCODE_MENU && !stageOpen) {
            setMenuOpen(!menuOpen);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            togglePlay();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            playNext();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            playPrev();
            return true;
        }
        return false;
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
            player.pause();
        } catch (Throwable ignored) {
        }
        pausedOnLeave = false;
        updateBar();
    }

    @Override
    public void setKeepPlayingOnce(boolean keep) {
        keepPlayingOnce = keep;
    }

    /**
     * 切走 / 退后台。
     *
     * <p>以前这里什么都不做，用户按 BACK 回桌面后音乐还在响，又没有任何提示——
     * 宿主现在会在回桌面/换模块前问一次（见 DeskActivity 的保持播放弹窗），
     * 这里的开关只兜两种情况：应用真的被压到后台（弹窗来不及弹），或者用户
     * 在设置里明确选了「离页后续播」。
     */
    @Override
    public void onLeave() {
        ui.removeCallbacks(stageChromeHide);
        boolean playing = false;
        try {
            playing = player != null && player.isPlaying();
        } catch (Throwable ignored) {
        }
        if (!playing) {
            return;
        }
        if (keepPlayingOnce) {
            keepPlayingOnce = false;
            return;
        }
        if (DeskPrefs.keepPlayingOnLeave(getContext())) {
            return;
        }
        try {
            player.pause();
        } catch (Throwable ignored) {
            return;
        }
        pausedOnLeave = true;
        updateBar();
    }

    @Override
    public void onRelease() {
        // 防抖里那个待发的搜索：页面都撤了还发出去，回来时会往一个没人看的界面上刷
        ui.removeCallbacks(searchTask);
        ui.removeCallbacks(stageTick);
        stageOpen = false;
        stopSpin();
        lyricFor = null;
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        // 音源运行时各自带一个 quickjs ctx 和一条线程，不回收会一直占着
        for (com.github.catvod.music.LxRuntime runtime : runtimes.values()) {
            try {
                runtime.close();
            } catch (Throwable ignored) {
            }
        }
        runtimes.clear();
        // 背景图请求挂在 view 上，页面撤了还留着就是白占一份解码后的 bitmap
        if (bgImage != null) {
            Glide.with(getContext()).clear(bgImage);
        }
        if (headCover != null) {
            Glide.with(getContext()).clear(headCover);
        }
        if (stageBg != null) {
            Glide.with(getContext()).clear(stageBg);
        }
        if (stageCover != null) {
            Glide.with(getContext()).clear(stageCover);
        }
    }

    /** 取（或建）某个音源的运行时。已初始化的直接复用，不再重跑脚本 */
    private com.github.catvod.music.LxRuntime runtimeFor(com.github.catvod.music.LxSource source) {
        com.github.catvod.music.LxRuntime runtime = runtimes.get(source.fileName);
        if (runtime != null) {
            return runtime;
        }
        com.github.catvod.music.LxRuntime fresh = new com.github.catvod.music.LxRuntime();
        com.github.catvod.music.LxRuntime existing = runtimes.putIfAbsent(source.fileName, fresh);
        return existing != null ? existing : fresh;
    }

    // ---------------------------------------------------------------- 小工具

    private void showEmpty(String hint) {
        emptyView.setText(hint);
        emptyView.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
    }

    private void toast(String text) {
        Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String join(String a, String b) {
        if (TextUtils.isEmpty(a)) {
            return b == null ? "" : b;
        }
        if (TextUtils.isEmpty(b)) {
            return a;
        }
        return a + " · " + b;
    }

    private static String fileName(String path) {
        if (TextUtils.isEmpty(path)) {
            return "未知曲目";
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) {
            return "";
        }
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" + seconds : String.valueOf(seconds));
    }
}
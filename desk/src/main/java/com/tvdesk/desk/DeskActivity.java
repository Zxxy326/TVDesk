package com.tvdesk.desk;

import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.ImgUtil;
import com.tvdesk.core.bili.model.BiliCard;
import com.tvdesk.core.bili.model.BiliDetail;
import com.tvdesk.core.bili.model.BiliEpisode;
import com.tvdesk.core.bili.model.LiveRoom;
import com.tvdesk.core.feed.HomeFeed;
import com.tvdesk.core.feed.SourceConfig;
import com.tvdesk.core.feed.SourceFeed;
import com.tvdesk.desk.motion.CubicBezierInterpolator;
import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.motion.KeyRepeatHandler;
import com.tvdesk.desk.motion.PressFx;
import com.tvdesk.desk.motion.RevealAnim;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * TVDesk 桌面。
 *
 * <p>结构：左上模块切换器 / 顶部页面导航 / Hero 主视觉 / 底部横向海报行。
 *
 * <p>顶栏不做自己的一套信息架构，直接取配置源自带的类目：
 * 「首页 + 电影 / 电视剧 / 动漫 / 综艺 … + 搜索」。换源之后顶栏跟着变。
 * 首页单独用豆瓣新片榜兜底，类目页走源本身的分页接口。
 *
 * <p>密度适配在基类 {@link DeskAdaptActivity} 里，不在本类。
 *
 * <p>「设置」不再跳 TVBox 的设置页，改跳本模块的 {@link SettingsActivity}
 * （见 {@link #MODULE_SETTING}）。
 */
public class DeskActivity extends DeskAdaptActivity {

    /**
     * 模块切换器展开后的入口。
     *
     * <p>第一项必须是「桌面」——它就是当前这个界面本身。少了它，用户点进任何模块之后
     * 就没有回桌面的入口了。
     *
     * <p>没有「影视」：类目和海报墙已经长在桌面顶栏/内容区里（见 {@link CategoryWall}），
     * 再留一个整页入口就是把同一件事做两遍。
     */
    private static final String[] MODULES = {
            "桌面", "音乐", "抖音", "哔哩哔哩", "应用", "信号源", "设置"
    };

    /**
     * 「设置」跳的是 TVDesk 自己的设置页 {@link SettingsActivity}，不是 TVBox 的设置页。
     *
     * <p>它必须能点进去——桌面上「还没配置内容源」的引导语就指向这里
     * （设置 → 配置地址）。这是用户配源的唯一入口，断了 App 就没法用。
     *
     * <p>原先是跳 {@code com.github.tvbox.osc.ui.activity.SettingActivity}。改掉的原因：
     * 那是 TVBox 的整页设置（播放器、弹幕、界面、直播… 几十项），而桌面只用得到其中
     * 「配置地址」一项——把用户扔进一个满是陌生选项的页面里去找一个输入框，找不到是正常的。
     */
    private static final int MODULE_SETTING = 6;

    /**
     * 「抖音」内嵌为模块页（{@link DouyinPage}）：竖屏信息流，一屏一条。
     *
     * <p>缓存实例是因为列表和播放器状态都值钱——切去别的应用再看回来，
     * 重取一次推荐流就是回到第一条。
     */
    private static final int MODULE_DOUYIN = 2;

    /**
     * 「音乐」内嵌为桌面的模块页（{@link MusicPage}），不跳 Activity。
     *
     * <p>页实例缓存在 {@link #musicPage}：切走再切回，播放不中断、
     * 音源 quickjs runtime（首次初始化 ~19 秒）不重建。
     * 保留 {@link MusicActivity} 只是为了 adb 直连调试。
     */
    private static final int MODULE_MUSIC = 1;

    /**
     * 「哔哩哔哩」同样内嵌为模块页（{@link BiliPage}），不跳 Activity。
     *
     * <p>缓存的理由和音乐页一致，但更硬：B 站首屏要签 wbi、取 buvid，再拉推荐，
     * 重建一次就是站着看两三秒空白。详情页和播放器页不缓存——它们是「看完就走」的，
     * 留着只会让BACK多按几次。
     */
    private static final int MODULE_BILI = 3;

    /**
     * 「应用」内嵌为模块页（{@link AppPage}）：本地已安装应用的卡片墙。
     *
     * <p>缓存实例是因为扫描本身慢——读上百个 APK 图标是磁盘 IO，
     * 切走再回来重扫一次会白等。页内部把「离开过应用信息/卸载页」记成脏标记，
     * 回来时才重扫，兼顾了不重扫和不看陈旧列表。
     */
    private static final int MODULE_APPS = 4;

    /**
     * 「信号源」内嵌为模块页（{@link SignalPage}）：内容源、直播源、电视输入源放一页管。
     *
     * <p>缓存实例是因为电视输入那一栏要问系统的 Live TV 框架（{@code TvInputManager}），
     * 在部分盒子上那是一次 binder 调用，切走再回来重问一遍没意义。
     *
     * <p>在这一页换了内容源之后，桌面靠 {@link #syncSourceIfChanged()} 重扫——
     * 内嵌页不会触发桌面 {@code onResume}，所以「关掉模块页回桌面」那一刻也补一次比对。
     */
    private static final int MODULE_SIGNAL = 5;

    /**
     * 顶栏最多显示几个类目，超出的收进「更多」。
     *
     * <p>真正决定放几个的是 {@link #navBudget()} 按实测宽度算出来的预算——
     * 类目名长度完全由源决定，「电影」和「电视剧榜单」能差一倍，
     * 按个数收口一定会翻车（王二小的类目名把顶栏撑爆、首页被「桌面」压住就是这么来的）。
     * 这个常量只是防御性上限，防止某个源塞几百个类目把循环拖长。
     */
    private static final int MAX_CATEGORY_TABS = 16;

    /**
     * 类目拉空时的重试策略。
     *
     * <p>源站不稳定或爬虫 jar 刚下载完还没热身时，第一次拉类目常常是空的。
     * 不重试的话顶栏就只剩「首页」，用户会以为类目功能坏了。
     */
    private static final int CATEGORY_RETRY_LIMIT = 2;
    private static final long CATEGORY_RETRY_DELAY = 4000L;

    /**
     * 顶栏提示条上的三句话。
     *
     * <p>三种状态的共同表现都是「顶栏没内容」，但对用户来说是三件完全不同的事：
     * 还没填地址 / 地址那头没响应 / 这个源里没有内容。混成一句「加载失败」等于什么都没说——
     * 用户既不知道该去点哪里，也不知道要不要重试。
     */
    private static final String HINT_NOT_CONFIGURED =
            "还没配置内容源 · 走「桌面 ▾」→ 设置 → 配置地址";
    private static final String HINT_SOURCE_DOWN =
            "配置源没响应 · 检查网络，或在「设置 → 配置地址」换个源";
    private static final String HINT_NO_CATEGORY =
            "这个源给不出可用类目 · 在「设置 → 配置地址」换个源试试";

    /**
     * 换源之后正在重扫时的提示。
     *
     * <p>只在「用户刚操作过」时挂（换源、清空源）。冷启动不挂：那时候顶栏短暂只有「首页」
     * 是正常的，闪一条提示反而吵。
     */
    private static final String HINT_LOADING = "正在加载配置源…";

    /** 首页海报行最多铺多少张 */
    private static final int ROW_LIMIT = 30;

    private static final int ACTION_HOME = 0;
    private static final int ACTION_CATEGORY = 1;
    private static final int ACTION_MORE = 2;
    /** 顶栏的「收藏」：本地清单，不经源站 */
    private static final int ACTION_FAVORITES = 3;

    /**
     * 焦点落到页签上就切内容——AppleTV 就是这么做的，没有「先聚焦再按确认」这一步。
     *
     * <p>但不能真的零延迟：连续右键扫过 5 个页签会连发 5 次列表请求，源站多半会限流，
     * 而且用户根本没想停在中间那几个。所以视觉立刻跟上（选中态 + 标题），
     * 只有数据请求延后 {@value #TAB_SWITCH_DEBOUNCE} ms，连按只发最后那一次。
     */
    private static final long TAB_SWITCH_DEBOUNCE = 220L;

    /**
     * 类目请求超过这么久还没回来，就把「正在加载」换成「源站响应慢」。
     *
     * <p>这个源实测 5~20 秒都有可能。不说明的话，
     * 用户看到的是一条「正在加载…」挂十几秒，分不清是在等还是已经卡死了。
     */
    private static final long SLOW_HINT_MS = 8000L;

    /** 加载态铺几张空卡片。一屏大约能看到 7 张，6 张正好铺满且不溢出 */
    private static final int SKELETON_COUNT = 6;

    /**
     * Hero 背景的解码上限，就是屏幕的物理像素（1920×1080）。
     *
     * <p>之前这里是 {@code override(160, 90)}——故意把图压到 160×90 再交给 ImageView
     * 拉满屏，靠双线性插值做出「柔化」的效果。代价是 12 倍放大，整屏就是一坨马赛克，
     * 这也是「海报太模糊」的根因。现在按屏幕真实分辨率解码，1:1 显示不再放大。
     *
     * <p>上限也不能再往上抬：这是全屏图，ARGB_8888 下 1920×1080 就是 8MB 一张，
     * 电视盒子内存本来就紧，再大就有被系统回收的风险。
     */
    private static final int HERO_MAX_W = 1920;
    private static final int HERO_MAX_H = 1080;

    /** 有真横图时的不透明度：铺满整屏且本身清晰，可以亮一点 */
    private static final float HERO_ALPHA_SLIDE = 0.8f;
    /** 退回竖版海报时压暗一档：放大 4~6 倍的糊图压暗后反而像氛围底，文字也更好读 */
    private static final float HERO_ALPHA_POSTER = 0.5f;

    /** 右上角时钟的格式。电视上不显示秒：秒一跳，眼睛就会跟着数 */
    private static final java.text.SimpleDateFormat CLOCK =
            new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);

    /** 海报行距顶距离：有内容时按设计稿，空状态时上移，避免整屏上半截全空 */
    private static final int ROW_TOP_NORMAL = 404;
    private static final int ROW_TOP_EMPTY = 250;

    /**
     * 桌面停在首页、这么久没有任何按键，就进沉浸轮播。
     *
     * <p>20 秒：电视的用法是「坐下—拿遥控器—看清楚再按」，十几秒的空档多半是人在找遥控器
     * 或者在犹豫，这时候切走只会打乱；再长（1 分钟）又显得迟钝。做屏保看的时间比看内容长，
     * 那才是沉浸态该出场的时机。
     */
    private static final long IDLE_IMMERSIVE_MS = 20000L;

    /** 沉浸态里每过这么久换下一条 */
    private static final long HERO_ROTATE_MS = 8000L;

    /** 分页点最多画几颗，超出的条目用窗口表示，见 {@link #rebuildHeroDots} */
    private static final int HERO_DOT_MAX = 10;

    /** 沉浸态在原有背景不透明度上再乘的系数 */
    private static final float IMMERSIVE_BG_DIM = 0.72f;

    /**
     * 沉浸态把信息块整体往下挪的距离（dp）。
     *
     * <p>正常态 Hero 只能待在上方：下面 404dp 起是海报行。沉浸态把那一行收掉了，
     * 信息块就该落到参考图上那个位置——左下角，上面整屏留给画面。
     *
     * <p>270dp 是按 720dp 高的版面量的：正常态那块整体上移过 40dp（见
     * activity_desk.xml 里 hero_block 的 marginTop），这里补回同样的距离，
     * 沉浸态标题顶仍然落在 382dp 上下；三行简介排完块底停在 575dp。
     * 150dp 那版落在正中偏上，看着像盖在画面中间而不是压住左下角。
     */
    private static final int IMMERSIVE_LIFT = 270;

    /**
     * 圆点单独少抬 40dp。
     *
     * <p>沉浸态按钮行是收起来的，信息块比正常态矮一截；圆点如果跟着块一起抬 270，
     * 就会和简介拉开 60 多 dp，像浮在半空。少抬 40dp 后圆点顶停在 602dp，
     * 简介底下留 27dp，正好接住。
     */
    private static final int IMMERSIVE_DOTS_LIFT = 230;

    /**
     * 播放器 Fragment 的类名。
     *
     * <p>它在 app 模块里，而 desk 不能编译期依赖 app（依赖方向是 app → desk → core），
     * 所以只能用反射创建。反射只碰它的公开方法（setData / 按键转发），
     * 播放器内部的解码、代理、字幕、弹幕全部照原样复用。
     */
    private static final String CLS_PLAY_FRAGMENT =
            "com.github.tvbox.osc.ui.fragment.PlayFragment";
    private static final String PLAYER_TAG = "desk_player";

    /** 兜底海报：标题 + 角标。数据拿不到时用它撑住布局 */
    private static final String[][] POSTERS = {
            {"暗影蜘蛛侠", "豆瓣热播"},
            {"怪奇物语", "豆瓣热播"},
            {"漫长的季节", "豆瓣热播"},
            {"三体", "豆瓣热播"},
            {"繁花", "豆瓣热播"},
            {"怒呛人生", "豆瓣热播"},
            {"最后生还者", "豆瓣热播"},
            {"黑镜", "豆瓣热播"},
    };

    private static final int[] POSTER_COLORS = {
            0xFF43325C, 0xFF2B4257, 0xFF5A382C, 0xFF2F4A34,
            0xFF47325C, 0xFF5A4A2E, 0xFF2A4F49, 0xFF522E47
    };

    private ImageView heroBg;
    private TextView heroTitle;
    private TextView heroTag;
    private TextView heroMeta;
    private TextView heroRate;
    private TextView heroDesc;
    private View heroBlock;
    private View heroActions;
    private LinearLayout heroDots;
    private ImageView heroLikeIcon;
    private TextView deskClock;

    private LinearLayout moduleSwitcher;
    private TextView moduleTitle;
    private LinearLayout moduleMenu;
    /** 内嵌模块页的宿主容器。音乐/哔哩哔哩/应用/信号源整页挂这里，不跳 Activity */
    private android.widget.FrameLayout moduleHost;
    /** 当前打开的模块页；null 表示在桌面 */
    private ModulePage activePage;
    /** 音乐页实例缓存——切走再切回时播放器不中断、音源 runtime 不重建 */
    private MusicPage musicPage;
    /** 抖音信息流实例缓存：列表和播放进度切回来还要在 */
    private DouyinPage douyinPage;
    /** 抖音 cookie 弹窗。带本机推送服务，同一时刻只留一个实例 */
    private DouyinLoginDialog douyinLogin;
    private DouyinSearchDialog douyinSearch;
    /** 「回桌面还要不要继续放」的确认。同一时刻只留一个 */
    private KeepPlayingDialog keepPlayingPrompt;
    /** B 站卡片墙实例缓存，理由同 {@link #musicPage} */
    private BiliPage biliPage;
    /** 应用卡片墙实例缓存，理由同 {@link #biliPage}（重的是包目录扫描，不是网络） */
    private AppPage appPage;
    /** 信号源页实例缓存，理由同 {@link #appPage}（重的是问系统电视输入框架那一步） */
    private SignalPage signalPage;
    /** 还没开工模块的占位页，按模块下标缓存 */
    private final android.util.SparseArray<PlaceholderPage> placeholders =
            new android.util.SparseArray<>();
    private boolean moduleOpen;

    /**
     * 模块内的页面栈。栈底可能是模块页，上面可以叠搜索结果和详情页。
     *
     * <p>这些页都挂在同一个 {@link #moduleHost} 里。BACK 先弹栈，栈空才回桌面——
     * 所以「搜索 → 详情 → 播放」全程只有 DeskActivity 一个窗口。
     */
    private final List<ModulePage> pageStack = new ArrayList<>();

    /**
     * 每一层的「来处」：打开这一层之前焦点停在哪个控件上，与 {@link #pageStack} 同下标。
     *
     * <p>退层要回到打开它的地方，这是统一层级（见 {@link ModulePage} 的表）里唯一管焦点的一条：
     * 其余各层只是收起浮层，这一条决定「返回之后我站在哪」。不做的话，从 Hero 点进详情、
     * 按返回会掉到左上角的模块切换器上，用户得再按三四下才回到刚才看的东西。
     *
     * <p>存的是 View 引用，列表重建／页被藏掉之后它就是游离的，
     * {@link #restoreReturnFocus} 会先验还在不在窗口树上，验不过就退回默认落点。
     */
    private final List<View> pageReturnFocus = new ArrayList<>();

    /** 模块页真正关闭时要还的焦点。可能隔着「保持播放」弹窗，所以单独存一份 */
    private View pendingReturnFocus;

    /** 播放覆盖层。TVBox 的 PlayFragment 动态加进容器，不换 Activity */
    private android.widget.FrameLayout playerHost;
    private Fragment playFragment;
    private boolean playerOpen;
    /** 上一次 BACK 按下的时间：遥控器连发/误连按时只退一层，不能跳级 */
    private long lastBackDownAt;
    private static final long BACK_DEBOUNCE_MS = 250L;
    /** 播放器公开方法，首次播放时反射取好，之后每次按键复用 */
    private Method playerSetData;
    private Method playerDispatchKey;
    private Method playerKeyDown;
    private Method playerKeyUp;
    private Method playerBack;

    /** 详情页宿主：数据由页面自己拉，播放交给本 Activity 的覆盖层 */
    private final DetailPage.Host detailHost = new DetailPage.Host() {
        @Override
        public void play(VodInfo info) {
            playVod(info);
        }

        @Override
        public void search(String keyword) {
            openSearch(keyword, true);
        }
    };

    /** 搜索结果点击 → 内嵌详情，仍是同一个窗口 */
    private final SearchPage.Host searchHost = new SearchPage.Host() {
        @Override
        public void openVideo(Movie.Video vod) {
            DeskActivity.this.openVideo(vod);
        }
    };

    /**
     * 抖音页宿主。
     *
     * <p>两条出口都是「弹一个本机局域网表单」：抖音没有给第三方用的扫码接口，
     * 登录只能把电脑上已有的 cookie 搬过来（{@link DouyinLoginDialog}），
     * 关键词也只能借手机键盘敲（{@link DouyinSearchDialog}）。
     * 返回桌面不走 Host，用下面构造页时传进去的 Runnable——和音乐/应用/信号源同一套写法。
     */
    private final DouyinPage.Host douyinHost = new DouyinPage.Host() {
        @Override
        public void openDouyinLogin(String why) {
            showDouyinLogin(why);
        }

        @Override
        public void openDouyinSearch(DouyinSearchDialog.Listener listener) {
            douyinSearch = new DouyinSearchDialog(DeskActivity.this, listener);
            douyinSearch.show();
        }
    };

    /**
     * B 站卡片墙宿主。
     *
     * <p>三条出口都是「压一层」而不是开 Activity：B 站的稿件详情、播放、直播间
     * 共用这一个窗口，BACK 一层层退，和影视那条链路一样。
     */
    private final BiliPage.Host biliHost = new BiliPage.Host() {
        @Override
        public void openBiliVideo(BiliCard card) {
            if (card == null || TextUtils.isEmpty(card.bvid)) {
                return;
            }
            openBiliDetail(card.bvid);
        }

        @Override
        public void openBiliLive(LiveRoom room) {
            if (room == null) {
                return;
            }
            pushPage(new BiliPlayerPage(DeskActivity.this, room));
        }

        @Override
        public void openBiliLogin() {
            showBiliLogin();
        }
    };

    /** B 站详情页点播放 → 压播放器页。详情页留在栈里，退出来还在原位置 */
    private final BiliDetailPage.Host biliDetailHost = new BiliDetailPage.Host() {
        @Override
        public void playBili(BiliDetail detail, BiliEpisode episode) {
            if (detail == null || episode == null) {
                return;
            }
            pushPage(new BiliPlayerPage(DeskActivity.this, detail, episode));
        }

        @Override
        public void openBiliLogin(Runnable afterLogin) {
            showBiliLogin(afterLogin);
        }
    };

    private LinearLayout rowPosters;
    private HorizontalScrollView rowScroll;
    private View rowSection;
    private TextView rowTitle;
    private TextView rowEmpty;
    private LinearLayout topNav;
    /** 顶栏下面的提示条，四种文案共用，见 {@link #showHint(String)} */
    private TextView sourceHint;

    /** 首页长按弹出的「请选择首页数据源」。同一时刻只留一个，重复长按不叠窗口 */
    private HomeSourceDialog homeSourceDialog;

    /** 数据源弹窗底部的二级入口：换配置线路 / 改配置地址 */
    private SourceSwitchDialog sourceSwitchDialog;

    /**
     * 遥控器 OK 长按。
     *
     * <p>{@link View.OnLongClickListener} 只跟触摸事件走，方向键按住不放不会触发它——
     * 首页海报「长按换源」因此一直按不出来。遥控器的长按只能在窗口层自己计时。
     *
     * <p>只在桌面（没开模块页）和播放器里生效：模块页有自己的长按约定
     * （抖音长按确定开搜索），在这里再压一层只会互相抢事件。
     */
    private static final long OK_LONG_PRESS_MS = 450L;
    private final Handler okHandler = new Handler(Looper.getMainLooper());
    private boolean okDown;
    private boolean okLongFired;
    private final Runnable okLongPress = new Runnable() {
        @Override
        public void run() {
            if (!okDown) {
                return;
            }
            okLongFired = true;
            okDown = false;
            onOkLongPress();
        }
    };

    /** 类目/首页「查看全部」的内嵌海报墙 */
    private CategoryWall categoryWall;
    /** true 表示内容区现在是海报墙，false 是 Hero + 海报行 */
    private boolean wallMode;
    /** 海报墙铺的是本地收藏清单：长按只能在这里生效，类目墙按了不该掉条目 */
    private boolean favoritesMode;

    /**
     * 顶栏装不下的类目。
     *
     * <p>{@code allCategories} 是源里给的全部类目；{@code overflowSort} 记录用户
     * 从「更多」里选中的那个（它不在顶栏里，顶部高亮停在「更多」上）。
     */
    private final List<MovieSort.SortData> allCategories = new ArrayList<>();
    private MovieSort.SortData overflowSort;
    private ScrollView categoryMenuScroll;
    private LinearLayout categoryMenuList;
    private boolean categoryMenuOpen;

    /**
     * 爬虫 jar 迟到时的重扫。
     *
     * <p>「配置 + jar」有整体超时：超时后桌面会按「无 jar」先把顶栏铺出来，但那一次扫描
     * 是残的——type 3 站点全靠 jar，一个都捞不到，只剩几个 CMS 站。jar 后来又挂上时
     * 必须重扫一遍，否则用户看到的就是「顶栏缺了一大截」，还以为这个源就这样。
     */
    private final SourceFeed.JarMountedListener jarMounted = new SourceFeed.JarMountedListener() {
        @Override
        public void onJarMounted() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            // 这是补扫不是重试，重试次数重新给满
            categoryRetryCount = 0;
            topNav.removeCallbacks(categoryRetry);
            android.util.Log.i("DeskActivity", "jar 迟到挂上，重扫顶栏类目");
            loadCategories();
        }
    };

    /**
     * 量顶栏宽度的「尺子」：一个不上屏的 TextView，字号/内边距和真项完全一致，
     * 只用来在排版前算出「这个词有多宽」。见 {@link #measureNavLabel(String)}。
     */
    private TextView navRuler;

    /** 顶栏条目，和 {@link #navActions} / {@link #navSorts} 一一对应 */
    private final List<TextView> navItems = new ArrayList<>();
    private final List<Integer> navActions = new ArrayList<>();
    private final List<MovieSort.SortData> navSorts = new ArrayList<>();

    /**
     * 顶栏上<b>真实类目</b>的个数（不含「首页 / 更多 / 搜索」）。
     *
     * <p>不能用 {@code navSorts.isEmpty()} 判断「有没有类目」——这个列表是按顶栏下标对齐的，
     * 首页和「更多」这两项也要占位（值是 null），所以渲染完它永远非空。
     * 早先的重试守卫正是踩了这个坑：条件恒为假，重试一次都没跑，日志里却能看到
     * 「第 N 次重试」（那句在 onCategories 里），查起来很费劲。
     */
    private int navCategoryCount = 0;

    private boolean menuOpen;
    private int currentModule = 0;
    private int currentTab = 0;

    /** 已经拉过内容的页签，用来避免同一页签重复请求（点确认、顶栏重建都会回到同一项） */
    private int loadedTab = -1;

    /** 防抖等待中的页签，-1 表示没有待办 */
    private int pendingTab = -1;

    /**
     * 上次生效的配置源地址，用来发现「用户去设置里换了源」。
     *
     * <p>在 {@code onCreate} 里就取好初值 —— 否则第一次 {@code onResume} 会误判成
     * 「源变了」而重扫一遍，白等一次配置加载。
     */
    private String lastApiUrl = "";

    private final Runnable tabSwitch = new Runnable() {
        @Override
        public void run() {
            if (pendingTab < 0) {
                return;
            }
            int index = pendingTab;
            pendingTab = -1;
            switchTab(index);
        }
    };

    /** 每次切页签自增，用来丢弃迟到的回调 */
    private int contentSeq = 0;

    /** 类目重试已经用掉几次 */
    private int categoryRetryCount = 0;

    /** 当前页签的条目，Hero 与按钮都指向 {@link #heroItem} */
    private final List<Movie.Video> items = new ArrayList<>();
    private Movie.Video heroItem;
    /** Hero 停在 {@link #items} 的第几条：沉浸轮播就是推着这个下标往前走 */
    private int heroIndex = -1;
    /** 条目自己没有 type 时徽章上的文案（首页那批豆瓣是「电视剧」） */
    private String heroFallbackTag = "";

    /**
     * 沉浸轮播是否开着。
     *
     * <p开着的时候桌面上只剩背景、Hero 信息块、圆点和右上角时钟：顶栏和海报行都收起来。
     * 收到的是「这一屏没有任何可聚焦的东西」，所以任意按键都先用来退出，不当成操作。
     */
    private boolean immersive;

    /** 进沉浸态时提示条是否开着，退出时按它还原 */
    private boolean immersiveHintWasVisible;

    /** 一段时间没按键 → 进沉浸轮播。每次按键都会重新掐这个表 */
    private final Runnable idleToImmersive = new Runnable() {
        @Override
        public void run() {
            enterImmersive();
        }
    };

    /** 沉浸态里换下一条 */
    private final Runnable heroRotate = new Runnable() {
        @Override
        public void run() {
            if (!immersive || !rotateHero()) {
                return;
            }
            heroBlock.postDelayed(this, HERO_ROTATE_MS);
        }
    };

    /**
     * 右上角时钟：每整分刷一次。
     *
     * <p>不写成「每 60 秒 postDelayed(60000)」：那样第一次刷新的时刻会跟着进页面的时间漂，
     * 显示的分钟可能停在 12:51 长达 119 秒。这里第一次对齐到下一个整分，之后每次 60 秒。
     */
    private final Runnable tickClock = new Runnable() {
        @Override
        public void run() {
            if (deskClock == null || isFinishing() || isDestroyed()) {
                return;
            }
            long now = System.currentTimeMillis();
            deskClock.setText(CLOCK.format(new java.util.Date(now)));
            long toNextMinute = 60000L - now % 60000L;
            deskClock.postDelayed(this, toNextMinute);
        }
    };

    /** 正在加载的行标题，慢响应提示和还原都用它 */
    private String loadingTitle;

    /**
     * 正在加载的页签下标。
     *
     * <p>用户可能在 8 秒内换到别的页签（那个页签已经加载过，{@code switchTab} 会提前返回，
     * 不会走到 {@link #cancelRowLoading}）。这时上一个页签的延时提示还在队列里，
     * 不校验的话会把「A 源站响应慢」写到 B 的标题上。
     */
    private int loadingTab = -1;

    /**
     * 「加载中」挂太久时的补充说明。
     *
     * <p>只改行标题的文案不动布局：用户已经看到骨架和「加载中」，这里只是告诉他
     * 「不是卡死，是源站慢」，不用重新排版，也不会闪。
     */
    private final Runnable slowHint = new Runnable() {
        @Override
        public void run() {
            if (loadingTitle == null || rowTitle == null || loadingTab != currentTab) {
                return;
            }
            rowTitle.setText(loadingTitle + " · 源站响应慢，仍在加载…");
        }
    };

    /**
     * 密度适配已经上提到 {@link DeskAdaptActivity}。
     *
     * <p>原先这里是本类自己写的 {@code getResources()} 覆写。挪上去的原因是设置页也要它，
     * 而「忘了写」的后果是静默的——density 不转换，所有 dp 放大 1/3，不报任何错，
     * 只是海报行被屏幕底部切掉 60%（实测）。这种坑靠每个新页面
     * 各自记得写一遍是防不住的，所以放基类。
     *
     * <p>这里只留本页自己的尺寸探针，给换电视时做比对：海报宽按设计稿是 160dp，
     * 1920px 宽的屏上应该解析成 240px。不是 240 就说明适配没生效。
     */
    @Override
    protected String adaptProbe(Resources res) {
        return "poster_width 解析为 " + res.getDimensionPixelSize(R.dimen.poster_width) + "px";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_desk);
        suppressForeignOverlays();

        // 进程被杀后系统会尝试恢复播放器 Fragment。播放状态（VodInfo）在进程里，
        // 恢复不出来，留着只会得到一个空播放器或者一次容器找不到的崩溃。
        Fragment stale = getSupportFragmentManager().findFragmentByTag(PLAYER_TAG);
        if (stale != null) {
            getSupportFragmentManager().beginTransaction()
                    .remove(stale).commitNowAllowingStateLoss();
        }

        bindViews();
        buildModuleMenu();
        // 先把当前源记下来。onResume 靠「和上次记的比」来判断用户是不是去设置里换了源，
        // 初值不留好，第一次进来就会被误判成「源变了」，白扫一遍配置。
        lastApiUrl = SourceFeed.currentApiUrl();
        // 必须在 buildTopNav 之前挂上：整体超时后 jar 才挂上时，通知会走这个回调。
        SourceFeed.setJarMountedListener(jarMounted);
        buildTopNav();
        setupFocus();
        setupActions();
        armIdleTimer();
        selectTab(0);
    }

    // ---------------------------------------------------------------- 视图装配

    private void bindViews() {
        moduleSwitcher = findViewById(R.id.module_switcher);
        moduleTitle = findViewById(R.id.module_title);
        moduleMenu = findViewById(R.id.module_menu);
        moduleHost = findViewById(R.id.module_host);
        playerHost = findViewById(R.id.desk_player_host);
        heroDots = findViewById(R.id.hero_dots);
        rowPosters = findViewById(R.id.row_posters);
        rowScroll = findViewById(R.id.row_scroll);
        rowSection = findViewById(R.id.row_section);
        rowTitle = findViewById(R.id.row_title);
        rowEmpty = findViewById(R.id.row_empty);
        topNav = findViewById(R.id.top_nav);
        sourceHint = findViewById(R.id.source_hint);
        categoryWall = findViewById(R.id.category_wall);
        categoryMenuScroll = findViewById(R.id.category_menu_scroll);
        categoryMenuList = findViewById(R.id.category_menu_list);

        categoryWall.setHost(new CategoryWall.Host() {
            @Override
            public void openVideo(Movie.Video vod) {
                DeskActivity.this.openVideo(vod);
            }

            @Override
            public void onNavigateUp() {
                focusActiveNav();
            }

            @Override
            public boolean onItemLongPress(Movie.Video vod) {
                if (!favoritesMode || vod == null) {
                    return false;
                }
                boolean liked = VodFavorites.toggle(DeskActivity.this, vod);
                android.util.Log.i("DeskActivity", (liked ? "收藏 " : "取消收藏 ") + vod.name
                        + "，清单共 " + VodFavorites.list(DeskActivity.this).size() + " 条");
                renderFavorites();
                return true;
            }
        });

        heroBlock = findViewById(R.id.hero_block);
        heroActions = findViewById(R.id.hero_actions);
        heroBg = findViewById(R.id.hero_bg);
        heroTitle = findViewById(R.id.hero_title);
        heroTag = findViewById(R.id.hero_tag);
        heroMeta = findViewById(R.id.hero_meta);
        heroRate = findViewById(R.id.hero_rate);
        heroDesc = findViewById(R.id.hero_desc);
        heroLikeIcon = findViewById(R.id.btn_like_icon);
        deskClock = findViewById(R.id.desk_clock);
    }

    /**
     * 拦掉第三方爬虫往窗口里塞的启动进度条。
     *
     * <p>配置源里的爬虫 jar 会在进程启动时挂一条“正在读取… / 正在等待 Go 服务响应”的
     * 进度条。它的日志写的是 {@code attached activity=..., root=android.widget.FrameLayout}，
     * 而窗口里叫 FrameLayout 的东西不止 {@code android.R.id.content} 一个——原先只盯
     * content，挂在外面那层容器上的时候就漏了，用户照样能看到顶部那条。
     *
     * <p>所以从 content 往上，每一层都盯住：只放行「通往桌面根布局的那一个孩子」，
     * 其余一律隐藏并移除。到 DecorView 就停——那上面还挂着系统自己的状态栏背景和
     * 文本选择 ActionMode，不能一起清。本窗口的播放器、详情、搜索都在 activity_desk
     * 内部，对话框/Toast 是独立窗口，都不会误伤。
     */
    private void suppressForeignOverlays() {
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) {
            return;
        }
        final View deskRoot = content.getChildAt(0);

        watchHierarchy(content, deskRoot, true);
        dropForeignChildren(content, deskRoot);

        View pathChild = content;
        ViewParent parent = content.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup host = (ViewGroup) parent;
            if (host.getClass().getName().endsWith("DecorView")) {
                break;
            }
            watchHierarchy(host, pathChild, false);
            pathChild = host;
            parent = host.getParent();
        }
    }

    /**
     * @param strict true 表示这一层只允许桌面根布局。内容层是独占的；
     *               外层窗口容器可能还有系统的 ActionMode，不能见谁都删。
     */
    private void watchHierarchy(final ViewGroup host, final View allowed, final boolean strict) {
        host.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                if (child == allowed) {
                    return;
                }
                if (!strict && isSystemOverlay(child)) {
                    return;
                }
                dropForeign(host, child);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
    }

    /** 窗口容器里系统自己会用的东西：文本选择的 ActionMode 和它的占位 Stub */
    private boolean isSystemOverlay(View child) {
        if (child instanceof android.view.ViewStub) {
            return true;
        }
        String cls = child.getClass().getName();
        if (cls.contains("ActionMode") || cls.contains("ActionBarContextView")) {
            return true;
        }
        int id = child.getId();
        if (id != View.NO_ID) {
            try {
                String name = getResources().getResourceEntryName(id);
                if (name != null && name.startsWith("action_mode")) {
                    return true;
                }
            } catch (Resources.NotFoundException ignored) {
            }
        }
        return false;
    }

    private void dropForeignChildren(ViewGroup host, View allowed) {
        for (int i = host.getChildCount() - 1; i >= 0; i--) {
            View child = host.getChildAt(i);
            if (child != allowed) {
                dropForeign(host, child);
            }
        }
    }

    /**
     * 必须同步移除，不能 post：爬虫每次更新提示文案都会重新挂一遍，
     * post 到下一帧等于每次都让它露一帧，看起来就是顶部闪了一条。
     */
    private void dropForeign(ViewGroup host, View child) {
        android.util.Log.i("DeskActivity", "移除第三方浮层："
                + child.getClass().getName());
        child.setVisibility(View.GONE);
        host.removeView(child);
    }

    /**
     * 重画 Hero 下面的分页点。
     *
     * <p>以前是四颗写死的装饰点。现在跟着 {@link #items} 走：沉浸轮播换到哪一条，
     * 点就亮到第几颗——用户得能在「这是轮播」和「轮播走到哪」之间看出关系。
     *
     * <p>条目多于 {@link #HERO_DOT_MAX} 时画的是窗口而不是全量：豆瓣首页一次给 25 条，
     * 全画出来是一串铺到屏幕中间的珠子，反而看不出停在哪一条。窗口以当前条为中心，
     * 走到两头时贴边收口，所以中间那段每按一次点就整体挪一格。
     */
    private void rebuildHeroDots() {
        int total = items.size();
        heroDots.removeAllViews();
        if (total <= 1) {
            // 只有一条时没有「轮播」可言，一颗点等于噪音
            heroDots.setVisibility(View.GONE);
            return;
        }
        heroDots.setVisibility(View.VISIBLE);
        int count = Math.min(total, HERO_DOT_MAX);
        // 窗口左端：当前条尽量落在中间，走到两头就贴边
        int start = Math.max(0, Math.min(heroIndex - count / 2, total - count));
        for (int i = 0; i < count; i++) {
            int position = start + i;
            boolean active = position == heroIndex;
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(active ? 8 : 6), dp(active ? 8 : 6));
            lp.rightMargin = dp(6);
            // 竖向差 2dp，不加居中的话亮的那颗会往下坠一颗像素的距离
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(active ? R.drawable.dot_active : R.drawable.dot_normal);
            heroDots.addView(dot);
        }
    }

    // ---------------------------------------------------------------- 顶栏

    /**
     * 顶栏内容来自配置源的 class 列表。
     *
     * <p>先把「首页」画出来，再去拉类目——拉类目要过爬虫，慢的时候顶栏不能是空的。
     * 拉类目之前必须先确保配置解析完了，否则源是空的，类目自然也空。
     */
    private void buildTopNav() {
        renderTopNav(null);
        SourceFeed.ensureConfig(this, new SourceFeed.ConfigCallback() {
            @Override
            public void onReady() {
                if (isFinishing()) {
                    return;
                }
                loadCategories();
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (SourceFeed.NOT_CONFIGURED.equals(message)) {
                    // 用户压根没填地址。这不是故障，是首次安装的正常状态，
                    // 得给他一条怎么配的路，而不是干等两轮重试再留个空顶栏。
                    renderTopNav(null);
                    showSourceMissing();
                    return;
                }
                // 源站拉不动：整体超时（jar 那头没动静），或者重试次数用完了。
                // 顶栏收敛回「首页 + 搜索」，功能不残，但必须给出反馈——
                // 不说的话用户看到的就是一个残缺的顶栏，会以为是软件坏了，
                // 而且他会一直等下去（这正是超时改造要解决的问题）。
                android.util.Log.w("DeskActivity", "config load failed: " + message);
                renderTopNav(null);
                showHint(HINT_SOURCE_DOWN);
            }
        });
    }

    /** 类目重试。Activity 销毁时要摘掉，否则会往已销毁的界面里塞视图 */
    private final Runnable categoryRetry = new Runnable() {
        @Override
        public void run() {
            // 等到这会儿顶栏已经有类目了（比如 jar 刚好挂上、上一轮重试拿到了），就别再扫。
            // 判断必须用 navCategoryCount：navSorts 因占位永远非空，见它的注释。
            if (!isFinishing() && !isDestroyed() && navCategoryCount == 0) {
                loadCategories();
            }
        }
    };

    private void loadCategories() {
        SourceFeed.loadCategories(new SourceFeed.CategoryCallback() {
            @Override
            public void onCategories(List<MovieSort.SortData> categories) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                renderTopNav(categories);
                if (!categories.isEmpty()) {
                    // 类目拿到了，说明源是通的，提示条该收了
                    hideSourceMissing();
                    return;
                }
                if (categoryRetryCount < CATEGORY_RETRY_LIMIT) {
                    categoryRetryCount++;
                    android.util.Log.i("DeskActivity", "类目为空，第 " + categoryRetryCount + " 次重试");
                    topNav.postDelayed(categoryRetry, CATEGORY_RETRY_DELAY);
                    return;
                }
                // 重试也用完了还是空。源是通的（配置拿到了），但一个类目都给不出来——
                // 常见于把公告站/搜索站选成了首页源，或主 jar 没挂上导致 type 3 站点集体失效。
                // 这不是「没配源」，所以不能沿用那句引导语，得指向「换个源」。
                android.util.Log.w("DeskActivity", "类目重试已用完，仍无可用类目");
                showHint(HINT_NO_CATEGORY);
            }

            @Override
            public void onError(Throwable th) {
                android.util.Log.w("DeskActivity", "load categories failed", th);
            }
        });
    }

    private void renderTopNav(List<MovieSort.SortData> categories) {
        boolean hadFocus = topNav.findFocus() != null;
        topNav.removeAllViews();
        navItems.clear();
        navActions.clear();
        navSorts.clear();
        navCategoryCount = 0;
        allCategories.clear();
        if (categories != null) {
            for (MovieSort.SortData sort : categories) {
                if (sort != null && sort.name != null) {
                    allCategories.add(sort);
                }
            }
        }
        hideCategoryMenu();

        // 先按实测宽度算出能放几个类目。
        // 顶栏是居中排布的，左边固定挂着「桌面」切换器，项数多了会向两边溢出去压到它，
        // 所以不能按个数收口——类目名长度完全由源决定，4 个字的「热门电影」
        // 和 2 个字的「电影」差一倍。
        // 「首页」和「收藏」不参与这个预算：它们是本地的两颗固定页签，挤掉的是类目。
        int remaining = navBudget() - measureNavLabel("首页") - measureNavLabel("收藏")
                - searchItemWidth();
        int moreWidth = measureNavLabel("更多") + navItemGap();
        int total = categories == null ? 0 : categories.size();
        int shown = 0;
        for (int i = 0; i < total && i < MAX_CATEGORY_TABS; i++) {
            int itemWidth = measureNavLabel(categories.get(i).name) + navItemGap();
            // 后面还有类目就得给「更多」留位置，否则最后一项会把「更多」挤出去
            int reserve = (i + 1 < total) ? moreWidth : 0;
            if (itemWidth + reserve > remaining) {
                break;
            }
            remaining -= itemWidth;
            shown++;
        }
        // 「装得下但一个都没排上」才是真的没类目。这里记的是最终上屏的个数，
        // 重试守卫和提示条都看它。
        navCategoryCount = shown;

        addNavItem("首页", ACTION_HOME, null);
        // 紧跟首页：Hero 那颗 ♥ 收进去的东西得有一页能看到，否则收藏按下去就是半句话
        addNavItem("收藏", ACTION_FAVORITES, null);
        for (int i = 0; i < shown; i++) {
            MovieSort.SortData sort = categories.get(i);
            addNavItem(sort.name, ACTION_CATEGORY, sort);
        }
        if (shown < total) {
            // 装不下的交给「更多」，进 TVBox 原生首页
            addNavItem("更多", ACTION_MORE, null);
        }
        addSearchItem();
        buildCategoryMenu();

        if (categories == null) {
            overflowSort = null;
        }
        if (currentTab >= navItems.size()) {
            currentTab = 0;
        }
        // 类目序号变了（首次拉到、或重试后换了一批），「已加载」的判断作废
        if (categories != null) {
            loadedTab = -1;
        }
        highlightTab(currentTab);
        if (hadFocus && !navItems.isEmpty()) {
            navItems.get(currentTab).requestFocus();
        }
    }

    /**
     * 顶栏可用的宽度（像素）。
     *
     * <p>顶栏居中，「桌面」切换器钉在左边，所以两侧要留出同样的空档才不会被压住：
     * 可用宽度 = 屏宽 − 2 ×（切换器右边缘 + 间隙）− 顶栏自身左右 padding。
     */
    private int navBudget() {
        int screen = getResources().getDisplayMetrics().widthPixels;
        int chipEnd = getResources().getDimensionPixelSize(R.dimen.screen_pad);
        View switcher = findViewById(R.id.module_switcher);
        if (switcher != null && switcher.getWidth() > 0) {
            chipEnd = switcher.getLeft() + switcher.getWidth();
        } else {
            // 还没走完布局，按切换器的典型宽度估一个，宁可估宽一点
            chipEnd += dp(90);
        }
        int budget = screen - (chipEnd + dp(16)) * 2 - dp(6) * 2;
        return Math.max(dp(320), budget);
    }

    /** 顶栏项之间的左间距，和 {@link #addNavItem} 里的 LayoutParams 保持一致 */
    private int navItemGap() {
        return dp(2);
    }

    private int searchItemWidth() {
        return dp(40) + dp(4);
    }

    /**
     * 量一个导航项的实际宽度。
     *
     * <p>用一把「尺子」TextView，样式和真项完全一致，量完不留痕迹。
     * 不能拿 {@code Paint.measureText} 估——那样漏掉左右 padding，
     * 22dp × 2 的 padding 在长类目名上正好是决定放不放得下的那部分。
     */
    private int measureNavLabel(String label) {
        if (navRuler == null) {
            navRuler = new TextView(this);
            // 必须给 LayoutParams：TextView.setText 内部的 checkForRelayout 会直接读
            // getLayoutParams().width，没设就 NPE（这里踩过一次）
            navRuler.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
            navRuler.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            navRuler.setSingleLine(true);
            navRuler.setPadding(getResources().getDimensionPixelSize(R.dimen.nav_item_pad_h), 0,
                    getResources().getDimensionPixelSize(R.dimen.nav_item_pad_h), 0);
        }
        navRuler.setText(label == null ? "" : label);
        navRuler.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(dp(32), View.MeasureSpec.EXACTLY));
        return navRuler.getMeasuredWidth();
    }

    private void addNavItem(String label, int action, MovieSort.SortData sort) {
        final int index = navItems.size();

        TextView item = new TextView(this);
        item.setText(label);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        item.setTextColor(AppCompatResources.getColorStateList(this, R.color.nav_item_text));
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

        FocusScaler.attach(item, 1.06f);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(index);
            }
        });
        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    return;
                }
                // 「更多」不是页签，是个跳转入口，只在按确认时才走
                if (navActions.get(index) == ACTION_MORE) {
                    return;
                }
                // 已经停在这一项上，别重复拉
                if (index == currentTab && loadedTab == index) {
                    return;
                }
                markTab(index);
                topNav.removeCallbacks(tabSwitch);
                pendingTab = index;
                topNav.postDelayed(tabSwitch, TAB_SWITCH_DEBOUNCE);
            }
        });

        navItems.add(item);
        navActions.add(action);
        navSorts.add(sort);
        topNav.addView(item);
    }

    private void addSearchItem() {
        FrameLayout search = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(32));
        lp.leftMargin = dp(4);
        search.setLayoutParams(lp);
        search.setBackgroundResource(R.drawable.bg_nav_item);
        search.setFocusable(true);
        search.setClickable(true);
        search.setFocusableInTouchMode(true);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_search);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(20), dp(20));
        iconLp.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconLp);
        search.addView(icon);

        FocusScaler.attach(search, 1.06f);
        search.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSearch(null, false);
            }
        });

        topNav.addView(search);
    }

    // ---------------------------------------------------------------- 类目下拉

    /**
     * 顶栏装不下的类目收进「更多」。
     *
     * <p>以前「更多」是整页跳影视模块；现在它只展开一个类目列表，选中后仍然在
     * 同一屏换海报墙，顶栏和窗口都不动。
     */
    private void buildCategoryMenu() {
        if (categoryMenuList == null) {
            return;
        }
        categoryMenuList.removeAllViews();
        if (allCategories.isEmpty()) {
            hideCategoryMenu();
            return;
        }
        for (int i = 0; i < allCategories.size(); i++) {
            final MovieSort.SortData sort = allCategories.get(i);
            TextView item = new TextView(this);
            item.setText(sort.name);
            item.setTextColor(getResources().getColor(R.color.text_primary));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), 0, dp(16), 0);
            item.setBackgroundResource(R.drawable.bg_module_item);
            item.setFocusable(true);
            item.setClickable(true);
            item.setFocusableInTouchMode(true);
            item.setSelected(isActiveSort(sort));
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chooseCategory(sort);
                }
            });
            categoryMenuList.addView(item);
        }
        // 类目多的时候限高，超出的靠焦点自动滚动
        ViewGroup.LayoutParams lp = categoryMenuScroll.getLayoutParams();
        if (lp != null) {
            lp.height = Math.min(dp(44) * allCategories.size() + dp(12), dp(420));
            categoryMenuScroll.setLayoutParams(lp);
        }
        categoryMenuScroll.setVisibility(View.GONE);
    }

    private void openCategoryMenu() {
        if (allCategories.isEmpty()) {
            return;
        }
        setMenuOpen(false);
        if (categoryMenuList.getChildCount() == 0) {
            buildCategoryMenu();
        }
        int focusIndex = 0;
        for (int i = 0; i < allCategories.size(); i++) {
            if (isActiveSort(allCategories.get(i))) {
                focusIndex = i;
                break;
            }
        }
        categoryMenuOpen = true;
        categoryMenuScroll.setVisibility(View.VISIBLE);
        if (focusIndex < categoryMenuList.getChildCount()) {
            categoryMenuList.getChildAt(focusIndex).requestFocus();
        }
    }

    private void hideCategoryMenu() {
        categoryMenuOpen = false;
        if (categoryMenuScroll != null) {
            categoryMenuScroll.setVisibility(View.GONE);
        }
    }

    private void closeCategoryMenu() {
        if (!categoryMenuOpen) {
            return;
        }
        hideCategoryMenu();
        focusActiveNav();
    }

    private void chooseCategory(MovieSort.SortData sort) {
        closeCategoryMenu();
        int index = -1;
        for (int i = 0; i < navSorts.size(); i++) {
            if (navSorts.get(i) == sort) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            overflowSort = null;
            selectTab(index);
            if (index < navItems.size()) {
                navItems.get(index).requestFocus();
            }
            return;
        }

        int more = navActions.indexOf(ACTION_MORE);
        overflowSort = sort;
        currentTab = Math.max(0, more);
        loadedTab = currentTab;
        topNav.removeCallbacks(tabSwitch);
        pendingTab = -1;
        highlightTab(currentTab);
        renderCategory(sort);
        if (more >= 0 && more < navItems.size()) {
            navItems.get(more).requestFocus();
        }
    }

    /** 这个类目现在是不是内容区正在铺的那一个 */
    private boolean isActiveSort(MovieSort.SortData sort) {
        for (int i = 0; i < navSorts.size(); i++) {
            if (navSorts.get(i) == sort) {
                return i == currentTab;
            }
        }
        return sort == overflowSort;
    }

    /** 海报墙按 UP 离开时，把焦点还给当前类目所在的顶栏项 */
    private void focusActiveNav() {
        int index = currentTab;
        if (index < 0 || index >= navItems.size()) {
            index = 0;
        }
        if (index < navItems.size()) {
            navItems.get(index).requestFocus();
        }
    }

    private void highlightTab(int index) {
        for (int i = 0; i < navItems.size(); i++) {
            navItems.get(i).setSelected(i == index);
        }
    }

    /** 点按（或后台切换）路径：取消防抖，立刻切 */
    private void selectTab(int index) {
        if (index < 0 || index >= navItems.size()) {
            return;
        }
        if (navActions.get(index) == ACTION_MORE) {
            // 「更多」不是页签，展开类目列表；选中后仍在本屏换海报墙
            openCategoryMenu();
            return;
        }
        topNav.removeCallbacks(tabSwitch);
        pendingTab = -1;
        markTab(index);
        if (loadedTab == index) {
            // 已经在这一页，再按一次确定就走进内容，不用再按一下 DOWN
            if (wallMode) {
                categoryWall.focusLast();
            }
            return;
        }
        switchTab(index);
    }

    /**
     * 只动选中态和标题，不发请求。
     *
     * <p>焦点一落到页签上就先跑这个，用户能看到即时反馈；
     * 数据晚 {@value #TAB_SWITCH_DEBOUNCE} ms 才拉，连按右键时省掉中间那些请求。
     */
    private void markTab(int index) {
        currentTab = index;
        highlightTab(index);
        if (navActions.get(index) == ACTION_CATEGORY && navSorts.get(index) != null) {
            overflowSort = null;
            rowTitle.setText(navSorts.get(index).name);
        } else if (navActions.get(index) == ACTION_HOME) {
            overflowSort = null;
            rowTitle.setText("今日热门电视剧");
        } else if (navActions.get(index) == ACTION_FAVORITES) {
            overflowSort = null;
            rowTitle.setText("收藏");
        } else if (overflowSort != null) {
            rowTitle.setText(overflowSort.name);
        }
    }

    /** 真正去拉内容 */
    private void switchTab(int index) {
        if (index < 0 || index >= navItems.size()) {
            return;
        }
        // 触摸点页签时不会触发焦点变化，菜单得跟着切换关掉
        setMenuOpen(false);
        hideCategoryMenu();
        loadedTab = index;
        MovieSort.SortData sort = navSorts.get(index);
        if (navActions.get(index) == ACTION_CATEGORY) {
            renderCategory(navSorts.get(index));
        } else if (navActions.get(index) == ACTION_FAVORITES) {
            renderFavorites();
        } else if (navActions.get(index) == ACTION_MORE) {
            if (overflowSort != null) {
                renderCategory(overflowSort);
            }
        } else {
            renderHome();
        }
    }

    // ---------------------------------------------------------------- 内容

    /** 首页：豆瓣新片榜 */
    private void renderHome() {
        final int seq = ++contentSeq;
        // 重新拉首页说明用户（或系统）刚动过这一屏，沉浸态该结束
        exitImmersive(false);
        wallMode = false;
        favoritesMode = false;
        applyContentMode();
        rowTitle.setText("今日热门电视剧");
        heroDots.setVisibility(View.VISIBLE);
        rowSection.setVisibility(View.VISIBLE);
        showRowLoading("今日热门电视剧");

        HomeFeed.load(this, new HomeFeed.Callback() {
            @Override
            public void onResult(List<Movie.Video> list, boolean fromCache) {
                if (seq != contentSeq) {
                    return;
                }
                if (list == null || list.isEmpty()) {
                    applyPlaceholders();
                    // 这屏没内容，切回来时应该重试
                    loadedTab = -1;
                    return;
                }
                applyItems(list, "电视剧");
            }

            @Override
            public void onError(Throwable th) {
                if (seq != contentSeq) {
                    return;
                }
                android.util.Log.w("DeskActivity", "home feed failed", th);
                applyPlaceholders();
                loadedTab = -1;
            }
        });
    }

    /**
     * 类目页：不再单开影视页面，直接在桌面内容区铺海报墙。
     *
     * <p>这里只负责把内容区切过去；分页、焦点、空态都在 {@link CategoryWall} 里。
     */
    private void renderCategory(final MovieSort.SortData sort) {
        if (sort == null) {
            return;
        }
        // 作废还在飞的首页请求，别让它回来把墙顶掉
        contentSeq++;
        cancelRowLoading();
        wallMode = true;
        favoritesMode = false;
        applyContentMode();
        rowTitle.setText(sort.name);
        categoryWall.show(sort);
    }

    /**
     * 首页海报行的「查看全部」。
     *
     * <p>以前这个入口跳影视页；现在同一批豆瓣条目直接换成海报墙，BACK 回 Hero。
     * 顶部仍停在「首页」，不额外造一个页签。
     */
    private void showHomeWall() {
        contentSeq++;
        cancelRowLoading();
        wallMode = true;
        favoritesMode = false;
        applyContentMode();
        currentTab = 0;
        highlightTab(0);
        loadedTab = 0;
        categoryWall.show(null);
        // 行标题马上要被藏起来，先把焦点交给顶栏，别让它掉到空气里
        focusActiveNav();
    }

    /** 从海报墙按 BACK：回到 Hero 首页，焦点落在「首页」上 */
    private void returnHome() {
        currentTab = 0;
        loadedTab = 0;
        overflowSort = null;
        hideCategoryMenu();
        highlightTab(0);
        renderHome();
        // 墙已经藏起来了，顺手清空：下次再进类目不该先闪一眼上一个类目的海报
        categoryWall.clearContent();
        focusActiveNav();
    }

    /** 内容区二选一：Hero + 海报行，或者整屏海报墙 */
    private void applyContentMode() {
        boolean wall = wallMode && !moduleOpen;
        categoryWall.setVisibility(wall ? View.VISIBLE : View.GONE);
        int showcase = wall ? View.GONE : View.VISIBLE;
        heroBlock.setVisibility(showcase);
        heroDots.setVisibility(showcase);
        rowSection.setVisibility(showcase);
        heroBg.setVisibility(wall ? View.INVISIBLE : View.VISIBLE);
    }

    /** 统一落数据：Hero 用第一条，海报行铺全部 */
    private void applyItems(List<Movie.Video> list, String fallbackTag) {
        cancelRowLoading();
        // 补拉横图回来时要判断「还是不是同一屏」，这里把当前代次钉住
        final int seq = contentSeq;
        items.clear();
        if (list != null) {
            for (int i = 0; i < list.size() && i < ROW_LIMIT; i++) {
                if (list.get(i) != null) {
                    items.add(list.get(i));
                }
            }
        }
        if (items.isEmpty()) {
            applyPlaceholders();
            return;
        }

        setRowTop(ROW_TOP_NORMAL);
        setActionButtons(true);
        rowEmpty.setVisibility(View.GONE);
        rowScroll.setVisibility(View.VISIBLE);
        heroBlock.setVisibility(View.VISIBLE);
        heroFallbackTag = safe(fallbackTag);
        showHero(pickHeroIndex(items));
        // 内容刚落定，重新掐沉浸表：不重掐的话上一屏攒下的空闲时间会算到这一屏头上，
        // 用户刚看清首页，顶栏就消失了
        armIdleTimer();

        refillPosterRow(seq, new Runnable() {
            @Override
            public void run() {
                rowPosters.removeAllViews();
                for (int i = 0; i < items.size(); i++) {
                    View card = createPoster(items.get(i), i);
                    rowPosters.addView(card);
                    RevealAnim.staggerIn(card, i, dp(14));
                }
                attachPosterFocus();
            }
        });
    }

    /**
     * 把 Hero 信息块落到 {@link #items} 的第 index 条。
     *
     * <p>首次落数据和沉浸轮播换条都走这里，「Hero 该显示什么」只有一份代码。
     *
     * <p>片名不加书名号/引号：参考图上是裸的标题，而且海报行卡片本来也不带，
     * 只有 Hero 带的话两处写法就不一致了。
     *
     * @return 下标有效、页面还在；越界返回 false，轮播据此停下
     */
    private boolean showHero(int index) {
        if (index < 0 || index >= items.size() || isFinishing() || isDestroyed()) {
            return false;
        }
        heroIndex = index;
        heroItem = items.get(index);
        // 补拉横图回来时要判断「还是不是同一屏」
        final int seq = contentSeq;

        heroTitle.setText(safe(heroItem.name));
        heroTag.setText(firstNonEmpty(heroItem.type, heroFallbackTag));
        heroTag.setVisibility(TextUtils.isEmpty(heroTag.getText()) ? View.GONE : View.VISIBLE);

        // 元信息拼成一行：年份 · 地区 · 语言。源里缺哪项就少哪项，不留孤立的点，
        // 全缺时整段收起来——空 TextView 也占 10dp 的行距
        String meta = buildHeroMeta(heroItem);
        heroMeta.setText(meta);
        heroMeta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);

        // 只有「8.7 分」这种才是评分；「已收藏」「更新至第10集」不是
        String rate = ratingOf(heroItem.note);
        heroRate.setText(rate.isEmpty() ? "" : "\u2605 " + rate);
        heroRate.setVisibility(rate.isEmpty() ? View.GONE : View.VISIBLE);

        // 简介给三行。豆瓣榜单的列表接口不给 des，但条目带 subject id，可以按 id
        // 单独回查（HomeFeed.loadIntro）；源站条目列表里没 des 的仍按原来的说明走。
        heroDesc.setMinLines(0);
        String desc = TextUtils.isEmpty(heroItem.des) ? "" : stripHtml(heroItem.des);
        if (!desc.isEmpty()) {
            heroDesc.setText(desc);
        } else if (HomeFeed.subjectIdOf(heroItem).isEmpty()) {
            heroDesc.setText("这条没有简介 · 按 ⓘ 看详情");
        } else {
            loadHeroIntro(heroItem);
        }

        renderLikeState();
        loadHeroBackdrop(heroItem.picSlide, heroItem.pic);
        requestHeroSlide(heroItem, seq);
        rebuildHeroDots();
        prefetchNextHeroIntro();
        return true;
    }

    /**
     * 豆瓣条目的简介落到 Hero 时补问一次。
     *
     * <p>回包只认「还是当前这一条」：轮播 8 秒换一条，网络慢的时候回包可能晚于切条，
     * 不检查就会把上一条的简介写在下一条标题下面。
     */
    private void loadHeroIntro(final Movie.Video vod) {
        heroDesc.setText("简介加载中…");
        // 加载态先占住三行。回包到了直接从三行换成三行，下面的按钮不会往下跳一截
        heroDesc.setMinLines(3);
        HomeFeed.loadIntro(HomeFeed.subjectIdOf(vod), new HomeFeed.IntroCallback() {
            @Override
            public void onIntro(String intro) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (intro != null && !intro.isEmpty()) {
                    // 写回条目：同一进程里再轮回到这一条不用重新提问
                    vod.des = intro;
                }
                if (heroItem != vod) {
                    // 已经轮播切走，这块 TextView 归下一条管，别再动它的高度
                    return;
                }
                heroDesc.setMinLines(0);
                if (intro == null) {
                    heroDesc.setText("简介暂时取不到");
                } else if (intro.isEmpty()) {
                    heroDesc.setText("这条没有简介 · 按 ⓘ 看详情");
                } else {
                    heroDesc.setText(stripHtml(intro));
                }
            }
        });
    }

    /** 沉浸轮播 8 秒一条，提前把下一条的简介取回来，切过去就不用等 */
    private void prefetchNextHeroIntro() {
        if (!immersive || items.size() < 2) {
            return;
        }
        int next = nextHeroIndex();
        if (next < 0 || next >= items.size()) {
            return;
        }
        Movie.Video vod = items.get(next);
        if (vod == null || !TextUtils.isEmpty(vod.des)) {
            return;
        }
        String subjectId = HomeFeed.subjectIdOf(vod);
        if (!subjectId.isEmpty()) {
            HomeFeed.prefetchIntro(subjectId);
        }
    }

    /** 元信息那行：把源里给得出的字段拼成一句，缺的跳过 */
    private String buildHeroMeta(Movie.Video vod) {
        if (vod == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (vod.year > 0) {
            builder.append(vod.year);
        }
        appendMeta(builder, vod.area);
        appendMeta(builder, vod.lang);
        return builder.toString();
    }

    private void appendMeta(StringBuilder builder, String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" \u00b7 ");
        }
        builder.append(value.trim());
    }

    /** 收藏那颗心：实心/描边只由本地清单决定，不做乐观态——这一步没有网络请求可失败 */
    private void renderLikeState() {
        if (heroLikeIcon == null) {
            return;
        }
        boolean liked = heroItem != null && VodFavorites.contains(this, heroItem);
        heroLikeIcon.setImageResource(liked ? R.drawable.ic_like_on : R.drawable.ic_like);
    }

    /** 轮播的下一个下标：走到尾巴回第一条，这样沉浸态可以一直放着 */
    private int nextHeroIndex() {
        if (items.isEmpty()) {
            return -1;
        }
        return (heroIndex + 1) % items.size();
    }

    /**
     * 海报行换内容：旧卡片（多半是骨架）先快速淡出，再把新卡片逐张浮进来。
     *
     * <p>直接 removeAllViews + addView 会让整行在一帧里硬切，切换类目时尤其明显。
     * 延迟期间用户可能又切了页签，所以用 {@code seq} 把这次交接钉在当前内容代次上。
     */
    private void refillPosterRow(final int seq, final Runnable fill) {
        rowPosters.animate().cancel();
        if (rowPosters.getChildCount() == 0 || !rowPosters.isShown()) {
            rowPosters.setAlpha(1f);
            fill.run();
            return;
        }
        rowPosters.animate().alpha(0f)
                .setDuration(90L)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        rowPosters.setAlpha(1f);
                        if (seq != contentSeq) {
                            return;
                        }
                        fill.run();
                    }
                })
                .start();
    }

    /**
     * 异步补拉 Hero 的横版剧照。
     *
     * <p>列表接口一般只给竖版海报，横图要去详情接口拿。这里不等它——竖图先显示出来，
     * 横图到了再替换，省掉一整轮请求的等待。
     *
     * <p>{@code seq} 是内容代次：用户可能在补拉期间切了类目，那时回来的图属于上一屏，
     * 直接丢掉，否则会把新内容的背景换成旧片子的剧照。
     *
     * <p>豆瓣首页那批条目没有 {@code id}/{@code sourceKey}（不可播放，点了会走搜索），
     * {@code SourceFeed.loadBackdrop} 会直接回调 null，不会有网络开销。
     */
    private void requestHeroSlide(final Movie.Video vod, final int seq) {
        SourceFeed.loadBackdrop(vod, new SourceFeed.BackdropCallback() {
            @Override
            public void onBackdrop(String url) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (seq != contentSeq || heroItem != vod || TextUtils.isEmpty(url)) {
                    return;
                }
                loadHeroBackdrop(url, null);
            }
        });
    }

    /**
     * 选 Hero 条目的下标。
     *
     * <p>分三档取，能高清就高清：
     * <ol>
     *   <li>有横版剧照 {@code picSlide} 的——铺满整屏不用裁，最清晰</li>
     *   <li>有竖版海报 {@code pic} 的——会裁掉上下大半，但胜过一块空背景</li>
     *   <li>第一条——有些源的头几条没有图，Hero 会是一块纯色背景，看着像没加载出来</li>
     * </ol>
     *
     * <p>返回下标而不是条目本身：沉浸轮播要从这一条接着往后走，只拿到 {@code Video}
     * 就得再遍历一次去找它排第几。
     */
    private int pickHeroIndex(List<Movie.Video> list) {
        int withPoster = -1;
        for (int i = 0; i < list.size(); i++) {
            Movie.Video vod = list.get(i);
            if (vod == null) {
                continue;
            }
            if (!TextUtils.isEmpty(vod.picSlide)) {
                return i;
            }
            if (withPoster < 0 && !TextUtils.isEmpty(vod.pic)) {
                withPoster = i;
            }
        }
        return withPoster >= 0 ? withPoster : 0;
    }

    /** 数据拿不到时的兜底，保证布局不塌 */
    private void applyPlaceholders() {
        cancelRowLoading();
        items.clear();
        setRowTop(ROW_TOP_NORMAL);
        // Hero 上那三颗按钮是给 heroItem 用的，这里是假数据，点了没有对应条目可开
        setActionButtons(false);
        rowEmpty.setVisibility(View.GONE);
        rowScroll.setVisibility(View.VISIBLE);
        heroBlock.setVisibility(View.VISIBLE);
        heroItem = null;
        heroIndex = -1;
        heroDots.removeAllViews();

        final int seq = contentSeq;
        refillPosterRow(seq, new Runnable() {
            @Override
            public void run() {
                rowPosters.removeAllViews();
                for (int i = 0; i < POSTERS.length; i++) {
                    Movie.Video vod = new Movie.Video();
                    vod.name = POSTERS[i][0];
                    vod.note = POSTERS[i][1];
                    View card = createPoster(vod, i);
                    rowPosters.addView(card);
                    RevealAnim.staggerIn(card, i, dp(14));
                }
                attachPosterFocus();
            }
        });
    }

    private void showEmpty(String hint) {
        cancelRowLoading();
        items.clear();
        heroItem = null;
        heroIndex = -1;
        heroDots.removeAllViews();
        heroBlock.setVisibility(View.GONE);
        // 清掉上一页的背景图，否则空状态会挂着别人的海报，看着像没切过来
        heroBg.setImageDrawable(null);
        rowPosters.animate().cancel();
        rowPosters.setAlpha(1f);
        rowPosters.removeAllViews();
        rowScroll.setVisibility(View.GONE);
        // 空状态没有 Hero 撑着，行标题要上移，否则整屏上半截是空的
        setRowTop(ROW_TOP_EMPTY);
        rowEmpty.setText(hint);
        rowEmpty.setVisibility(View.VISIBLE);
    }

    /**
     * 内容还没回来时的加载态。
     *
     * <p>之前这里是「什么都不动」——用户切了页签，标题变了、海报还是上一屏的，
     * 也没有任何提示，只能干等（这个源 5~20 秒都有可能）。看着像切不过去，
     * 而且旧海报还能聚焦、能点进详情，等于把上一个类目的内容当成本类目的。
     *
     * <p>现在把海报行换成几张空卡片（骨架），行标题后面挂「加载中」。比一片黑
     * 更能说明「这里马上会有东西」，也顺手断掉了「点进上一屏内容」这条路。
     * 骨架不可聚焦，所以不会变成焦点陷阱。
     */
    private void showRowLoading(String title) {
        cancelRowLoading();
        items.clear();
        heroItem = null;
        heroBlock.setVisibility(View.GONE);
        heroBg.setImageDrawable(null);
        setActionButtons(false);

        setRowTop(ROW_TOP_NORMAL);
        rowEmpty.setVisibility(View.GONE);
        rowScroll.setVisibility(View.VISIBLE);
        rowPosters.animate().cancel();
        rowPosters.setAlpha(1f);
        rowPosters.removeAllViews();
        for (int i = 0; i < SKELETON_COUNT; i++) {
            View card = createSkeleton();
            rowPosters.addView(card);
            RevealAnim.staggerIn(card, i, dp(10));
        }

        loadingTitle = title;
        loadingTab = currentTab;
        rowTitle.setText(title + " · 加载中…");
        rowTitle.removeCallbacks(slowHint);
        rowTitle.postDelayed(slowHint, SLOW_HINT_MS);
    }

    /** 内容回来了（成功或失败都算），撤掉加载态和那条迟到的「源站慢」提示 */
    private void cancelRowLoading() {
        rowTitle.removeCallbacks(slowHint);
        loadingTab = -1;
        if (loadingTitle != null) {
            rowTitle.setText(loadingTitle);
            loadingTitle = null;
        }
    }

    /** 加载态的空卡片。尺寸/圆角/间距和真海报一致，只是没有内容 */
    private View createSkeleton() {
        View card = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                getResources().getDimensionPixelSize(R.dimen.poster_width),
                getResources().getDimensionPixelSize(R.dimen.poster_height));
        lp.rightMargin = getResources().getDimensionPixelSize(R.dimen.poster_gap);
        card.setLayoutParams(lp);
        // 不能用 bg_poster：它的常态是 #00000000 全透明，垫在深色背景上等于没画
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(posterRadiusPx());
        card.setBackground(bg);
        return card;
    }

    private void setRowTop(int dpValue) {
        ViewGroup.LayoutParams params = rowSection.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            if (margins.topMargin != dp(dpValue)) {
                margins.topMargin = dp(dpValue);
                rowSection.setLayoutParams(margins);
            }
        }
    }

    /** 播放三按钮只在有内容时才有意义：没有 heroItem，按下去没有对象可开 */
    private void setActionButtons(boolean visible) {
        heroActions.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * 沉浸轮播是给人看的，不是给人按的：三颗按钮在沉浸态里按不动（任何按键都先用来
     * 退出沉浸态），留着只是块噪声。退出时再淡回来。
     */
    private void fadeActionButtons(boolean show) {
        heroActions.animate().cancel();
        heroActions.setAlpha(1f);
        if (show) {
            heroActions.setAlpha(0f);
            heroActions.setVisibility(View.VISIBLE);
            heroActions.animate().alpha(1f)
                    .setDuration(220L)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG)
                    .start();
            return;
        }
        heroActions.animate().alpha(0f)
                .setDuration(200L)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!immersive) {
                            return;
                        }
                        heroActions.setAlpha(1f);
                        heroActions.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void attachPosterFocus() {
        for (int i = 0; i < rowPosters.getChildCount(); i++) {
            final View card = rowPosters.getChildAt(i);
            // 行尾状态卡不是海报，tag 里没有条目，跳过
            if (!(card.getTag() instanceof Movie.Video)) {
                continue;
            }
            final Movie.Video vod = (Movie.Video) card.getTag();
            FocusScaler.attach(card, FocusScaler.SCALE_FOCUSED,
                    new View.OnFocusChangeListener() {
                        @Override
                        public void onFocusChange(View v, boolean hasFocus) {
                            if (hasFocus) {
                                onPosterFocused(card, vod);
                            }
                        }
                    });
        }
    }

    /**
     * 焦点落到哪张海报，Hero 整块就跟到哪一条：背景、片名、标签、简介、点赞一起换。
     *
     * <p>以前只换背景，是怕文字跟着焦点跳让人看不清当前页在放什么；但用户拿到的观感是
     * 「背景变了、名字和简介没变」，像卡住了。现在和自动轮播走同一个 {@link #showHero(int)}，
     * 两边不会再出现两种表现。
     */
    private void onPosterFocused(final View card, final Movie.Video vod) {
        if (vod == null || heroBg == null) {
            return;
        }
        int index = rowPosters.indexOfChild(card);
        if (index < 0 || index >= items.size()) {
            return;
        }
        // 自动轮播的计时从这一次按键重新算，别下一秒又被它切走
        armIdleTimer();
        if (showHero(index)) {
            android.util.Log.d("DeskActivity", "hero <- " + vod.name);
        }
    }

    /** 给 ImageView 裁圆角。海报卡片本来就该是圆角的，图片直着铺会溢出卡片的圆角背景 */
    private void roundCorners(final ImageView art) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        final float radius = dp(posterRadiusPx()) - dp(3);
        art.setClipToOutline(true);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
    }

    private int posterRadiusPx() {
        return getResources().getDimensionPixelSize(R.dimen.poster_radius);
    }

    private View createPoster(Movie.Video vod, int index) {
        int color = POSTER_COLORS[index % POSTER_COLORS.length];
        String title = (vod == null || vod.name == null) ? "" : vod.name;
        String badge = (vod == null || vod.note == null) ? "" : vod.note;

        FrameLayout card = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                getResources().getDimensionPixelSize(R.dimen.poster_width),
                getResources().getDimensionPixelSize(R.dimen.poster_height));
        lp.rightMargin = getResources().getDimensionPixelSize(R.dimen.poster_gap);
        card.setLayoutParams(lp);
        card.setBackgroundResource(R.drawable.bg_poster);
        card.setFocusable(true);
        card.setClickable(true);
        card.setFocusableInTouchMode(true);
        // 挂上条目本身：attachPosterFocus 靠它做焦点联动（背景跟着海报走）
        card.setTag(vod);

        // 海报图。加载中/失败都退回同色渐变，避免出现白块
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final GradientDrawable fallback = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{color, darken(color)});
        fallback.setCornerRadius(posterRadiusPx() - dp(3));
        art.setBackground(fallback);
        FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        artLp.setMargins(dp(3), dp(3), dp(3), dp(3));
        art.setLayoutParams(artLp);
        if (vod != null) {
            loadImage(art, vod.pic, fallback);
        }
        card.addView(art);
        roundCorners(art);

        // 底部压暗，保证标题可读
        View scrim = new View(this);
        GradientDrawable scrimBg = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000});
        scrimBg.setCornerRadius(posterRadiusPx() - dp(3));
        scrim.setBackground(scrimBg);
        FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(86));
        scrimLp.gravity = Gravity.BOTTOM;
        scrimLp.setMargins(dp(3), 0, dp(3), dp(3));
        scrim.setLayoutParams(scrimLp);
        card.addView(scrim);

        // 左上角标（评分/来源）
        if (!badge.isEmpty()) {
            TextView badgeView = new TextView(this);
            badgeView.setText(badge);
            badgeView.setTextColor(0xCCFFFFFF);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            badgeView.setBackgroundResource(R.drawable.bg_tag_outline);
            badgeView.setPadding(dp(6), dp(1), dp(6), dp(1));
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.gravity = Gravity.TOP | Gravity.START;
            badgeLp.setMargins(dp(10), dp(10), 0, 0);
            badgeView.setLayoutParams(badgeLp);
            card.addView(badgeView);
        }

        // 剧名
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setMaxLines(2);
        name.setShadowLayer(6f, 0f, 1f, 0xCC000000);
        name.setPadding(dp(10), 0, dp(10), dp(10));
        FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.gravity = Gravity.BOTTOM;
        name.setLayoutParams(nameLp);
        card.addView(name);

        final Movie.Video target = vod;
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openVideo(target);
            }
        });
        // 长按海报 = 换首页数据源。用户看着不对的内容时，换源入口应该就在眼前，
        // 而不是退出去找设置；返回 true 把这次长按从普通点击里截走。
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showHomeSourceSwitch();
                return true;
            }
        });

        return card;
    }

    // ---------------------------------------------------------------- 模块菜单

    private void buildModuleMenu() {
        for (int i = 0; i < MODULES.length; i++) {
            final int index = i;
            final String name = MODULES[i];

            TextView item = new TextView(this);
            item.setText(name);
            item.setTextColor(getResources().getColor(R.color.text_primary));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), 0, dp(16), 0);
            item.setBackgroundResource(R.drawable.bg_module_item);
            item.setFocusable(true);
            item.setClickable(true);
            item.setFocusableInTouchMode(true);
            item.setSelected(index == currentModule);
            if (index == MODULE_SETTING) {
                // 跳出桌面的项（设置）给个箭头，和其余「切换模块」区分开
                item.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0);
                item.setCompoundDrawablePadding(dp(8));
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            // 「桌面」和其他模块之间加一道分隔
            lp.topMargin = index == 1 ? dp(6) : 0;
            lp.bottomMargin = dp(2);
            item.setLayoutParams(lp);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectModule(index);
                }
            });

            moduleMenu.addView(item);
        }
    }

    /**
     * 切换模块。音乐/哔哩哔哩/应用/信号源都走内嵌模块页（{@link #openModulePage}），
     * 只有设置是独立 Activity——它是一整页表单，内嵌进来没有收益。
     */
    private void selectModule(int index) {
        android.util.Log.i("DeskActivity", "selectModule index=" + index
                + " name=" + (index >= 0 && index < MODULES.length ? MODULES[index] : "?"));
        if (index == MODULE_SETTING) {
            // 设置是跳出去的页面，进来再退回来还是桌面本身，所以不动 currentModule，
            // 否则菜单里「桌面」会莫名其妙取消选中
            setMenuOpen(false);
            launchActivity(SettingsActivity.class.getName(), null);
            return;
        }
        currentModule = index;
        moduleTitle.setText(MODULES[index]);

        for (int i = 0; i < moduleMenu.getChildCount(); i++) {
            moduleMenu.getChildAt(i).setSelected(i == index);
        }

        setMenuOpen(false);
        if (index == 0) {
            // 焦点落点由 closeModulePageNow 统一还（回到打开模块时那个控件），这里不再抢
            closeModulePage();
            return;
        }
        openModulePage(index);
    }

    // ---------------------------------------------------------------- 模块页宿主

    /**
     * 打开内嵌模块页。页面盖在桌面内容之上（各页自带不透明背景），
     * 桌面的顶栏和 Hero 暂时隐藏——模块页有自己的导航，两套叠在一起只会抢焦点。
     */
    private void openModulePage(int index) {
        // 用户可能从模块页里按 MENU 直接换模块（菜单压在模块页上），
        // 先把上一位打开着的子页面收掉，免得它们留在宿主里既不可见也不释放
        if (playerOpen) {
            releasePlayer();
        }
        clearPages();

        ModulePage page;
        if (index == MODULE_MUSIC) {
            if (musicPage == null) {
                // 音乐页自带顶栏（左上「音乐 ▾」→ 返回桌面），回调就是它的回桌面出口
                musicPage = new MusicPage(this, new Runnable() {
                    @Override
                    public void run() {
                        closeModulePage();
                    }
                });
                moduleHost.addView(musicPage, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            page = musicPage;
        } else if (index == MODULE_DOUYIN) {
            if (douyinPage == null) {
                douyinPage = new DouyinPage(this, douyinHost, new Runnable() {
                    @Override
                    public void run() {
                        closeModulePage();
                    }
                });
                moduleHost.addView(douyinPage, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            page = douyinPage;
        } else if (index == MODULE_BILI) {
            if (biliPage == null) {
                biliPage = new BiliPage(this, biliHost, new Runnable() {
                    @Override
                    public void run() {
                        closeModulePage();
                    }
                });
                moduleHost.addView(biliPage, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            page = biliPage;
        } else if (index == MODULE_APPS) {
            if (appPage == null) {
                appPage = new AppPage(this, new Runnable() {
                    @Override
                    public void run() {
                        closeModulePage();
                    }
                });
                moduleHost.addView(appPage, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            page = appPage;
        } else if (index == MODULE_SIGNAL) {
            if (signalPage == null) {
                signalPage = new SignalPage(this, new Runnable() {
                    @Override
                    public void run() {
                        closeModulePage();
                    }
                });
                moduleHost.addView(signalPage, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            page = signalPage;
        } else {
            PlaceholderPage holder = placeholders.get(index);
            if (holder == null) {
                holder = new PlaceholderPage(this, MODULES[index], new Runnable() {
                    @Override
                    public void run() {
                        closeModulePage();
                    }
                });
                placeholders.put(index, holder);
                moduleHost.addView(holder, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            page = holder;
        }
        pageStack.add(page);
        pageReturnFocus.add(getCurrentFocus());
        moduleOpen = true;
        activePage = page;
        showOnly((View) page);
        page.onEnter();

        final View pageView = (View) page;
        pageView.post(new Runnable() {
            @Override
            public void run() {
                // 藏顶栏那一帧之后，焦点可能还留在桌面那颗切换器上（GONE 不自动清焦点）。
                // 它是 clickable 的，确定键会在它那里被 performClick 吃掉、再也不往 Activity 传——
                // 模块页里「短按确定暂停、长按确定搜索」这类约定当场全废，而且看起来像没反应。
                // 网格类页面（音乐/B 站/应用）自己会把焦点放到卡片上，那时 hasFocus 为真，不动它。
                if (!pageView.hasFocus()) {
                    moduleSwitcher.clearFocus();
                    pageView.requestFocus();
                }
            }
        });
    }

    /**
     * 关掉模块页回桌面。
     *
     * <p>还在出声的页（音乐 / 抖音）先问一句「继续还是停止」：页被藏起来之后
     * 播放器本身不知道，不问就退，用户会在桌面上听着找不到来源的声音。
     * 没在放的时候不弹，不凭空多一道门槛。
     */
    private void closeModulePage() {
        closeModulePage(null);
    }

    /**
     * @param back 内容页弹到栈空时带下来的「来处」。它已经从 {@link #pageReturnFocus} 弹出，
     *             只能由调用方给；null 时用模块页自己那一条（打开模块前的焦点）
     */
    private void closeModulePage(View back) {
        pendingReturnFocus = back != null ? back
                : (pageReturnFocus.isEmpty() ? null : pageReturnFocus.get(0));
        final ModulePage page = activePage;
        if (keepPlayingPrompt != null) {
            // 弹窗还开着时又按了返回：别再叠一个，用户先回答这一张
            return;
        }
        if (page == null || !page.hasActivePlayback()) {
            closeModulePageNow();
            return;
        }
        keepPlayingPrompt = new KeepPlayingDialog(this, new KeepPlayingDialog.Listener() {
            @Override
            public void onKeepPlaying() {
                leaveAfterPrompt(page, true);
            }

            @Override
            public void onStopPlaying() {
                leaveAfterPrompt(page, false);
            }

            @Override
            public void onCancel() {
                keepPlayingPrompt = null;
                pendingReturnFocus = null;
                // 弹窗窗格关了，焦点要还给这一页，否则遥控器第一下像没反应
                View view = (View) activePage;
                if (view != null && !view.hasFocus()) {
                    view.requestFocus();
                }
            }
        });
        keepPlayingPrompt.show();
    }

    private void leaveAfterPrompt(ModulePage page, boolean keep) {
        keepPlayingPrompt = null;
        // 弹窗开着期间页面可能已经被别处关掉（比如系统 HOME 再回来），别误关新页
        if (activePage != page) {
            return;
        }
        if (keep) {
            page.setKeepPlayingOnce(true);
        } else {
            page.stopPlayback();
        }
        // 焦点落点交给 closeModulePageNow 统一处理（回到打开模块时那个控件）
        closeModulePageNow();
    }

    private void closeModulePageNow() {
        View back = pendingReturnFocus;
        pendingReturnFocus = null;
        Back.consume("宿主·关页回桌面（" + moduleTitle.getText() + "）");
        if (playerOpen) {
            releasePlayer();
        }
        clearPages();
        moduleOpen = false;
        activePage = null;
        moduleHost.setVisibility(View.GONE);
        setDeskChromeVisible(true);
        currentModule = 0;
        moduleTitle.setText(MODULES[0]);
        for (int i = 0; i < moduleMenu.getChildCount(); i++) {
            moduleMenu.getChildAt(i).setSelected(i == 0);
        }
        // 退层要回到来处：从哪颗按钮进的模块，回来还停在那颗。
        // 来处已经不在树上（换过源、海报墙整个重建）才退回默认落点。
        if (!restoreReturnFocus(back)) {
            if (wallMode && categoryWall.hasContent()) {
                categoryWall.focusLast();
            } else {
                moduleSwitcher.requestFocus();
            }
        }
        // 信号源页里换过源的话，到这一步才重扫：桌面顶栏刚恢复可见，
        // 「正在加载配置源…」那条提示才看得见（在本页盖着的时候重扫等于没提示）。
        syncSourceIfChanged();
    }

    /**
     * 把焦点还给「打开这一层之前的那个控件」。
     *
     * @return 还得回去才 true。目标被回收、被藏起来（GONE 的页抢不到焦点）或本来就不可聚焦时
     *         返回 false，让调用方退回自己的默认落点——宁可回到切换器，也不要焦点凭空消失
     */
    private boolean restoreReturnFocus(View target) {
        return target != null && target.isAttachedToWindow()
                && target.getVisibility() == View.VISIBLE
                && target.isEnabled() && target.isFocusable() && target.requestFocus();
    }

    /**
     * 清空页面栈。
     *
     * <p>缓存的模块页（音乐/抖音/哔哩哔哩/应用/信号源/占位）只隐藏不释放——下次切回来进度还要在。
     * 但「只隐藏」不等于「可以接着响」，所以先给每一页发一次 {@link ModulePage#onLeave()}。
     * 搜索页和详情页是一次性的，移出宿主并 {@link ModulePage#onRelease()}。
     */
    private void clearPages() {
        for (int i = pageStack.size() - 1; i >= 0; i--) {
            ModulePage page = pageStack.get(i);
            page.onLeave();
            if (isCachedPage(page)) {
                ((View) page).setVisibility(View.GONE);
                continue;
            }
            moduleHost.removeView((View) page);
            page.onRelease();
        }
        pageStack.clear();
        pageReturnFocus.clear();
    }

    private boolean isCachedPage(ModulePage page) {
        return page == musicPage || page == douyinPage || page == biliPage || page == appPage
                || page == signalPage
                || page instanceof PlaceholderPage;
    }

    /** 宿主里只显示目标页，其余页实例保留（切回来进度原样还在） */
    private void showOnly(View target) {
        for (int i = 0; i < moduleHost.getChildCount(); i++) {
            View child = moduleHost.getChildAt(i);
            child.setVisibility(child == target ? View.VISIBLE : View.GONE);
        }
        moduleHost.setVisibility(View.VISIBLE);
        setDeskChromeVisible(false);
    }

    /**
     * 压一个内容页进栈（搜索结果 / 详情）。
     *
     * <p>栈底可能是影视模块页，也可能没有——从桌面 Hero 直接点开的详情，
     * 栈底就是详情自己，BACK 一次直接回桌面。
     */
    private void pushPage(ModulePage page) {
        if (!(page instanceof View)) {
            return;
        }
        View view = (View) page;
        if (view.getParent() == null) {
            moduleHost.addView(view, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pageStack.add(page);
        pageReturnFocus.add(getCurrentFocus());
        activePage = page;
        moduleOpen = true;
        showOnly(view);
        page.onEnter();
    }

    /** BACK 退一层：先释放栈顶，再回到下一层（栈空则整页关掉回桌面） */
    private void popPage() {
        if (pageStack.isEmpty()) {
            closeModulePage();
            return;
        }
        ModulePage page = pageStack.remove(pageStack.size() - 1);
        View back = pageReturnFocus.isEmpty() ? null
                : pageReturnFocus.remove(pageReturnFocus.size() - 1);
        moduleHost.removeView((View) page);
        page.onRelease();
        if (pageStack.isEmpty()) {
            // 栈底本来就是内容页（从桌面 Hero 直接点开的详情）：来处随关页一起交下去
            closeModulePage(back);
            return;
        }
        Back.consume("宿主·退出内容页「" + page.getClass().getSimpleName() + "」");
        ModulePage top = pageStack.get(pageStack.size() - 1);
        activePage = top;
        showOnly((View) top);
        top.onEnter();
        // onEnter 会把它自己的默认落点铺一遍，来处要盖在它上面：
        // 用户从哪张卡点进去的，退回来就停在那张卡
        restoreReturnFocus(back);
    }

    /**
     * 打开一个条目。
     *
     * <p>带 sourceKey + id 的（来自配置源）进内嵌详情页；
     * 只有名字的（豆瓣推荐）定位不到具体源和 id，退到内嵌搜索。
     */
    private void openVideo(Movie.Video vod) {
        openVideo(vod, false);
    }

    /**
     * @param autoPlay 详情一到位就直接起播（那颗「开始播放」），不再让用户按第二次确认
     */
    private void openVideo(Movie.Video vod, boolean autoPlay) {
        if (vod == null || TextUtils.isEmpty(vod.name)) {
            return;
        }
        boolean hasSource = !TextUtils.isEmpty(vod.sourceKey) && !TextUtils.isEmpty(vod.id);
        android.util.Log.i("DeskActivity", "openVideo: name=" + vod.name
                + " id=" + vod.id + " sourceKey=" + vod.sourceKey + " hasSource=" + hasSource);
        if (!hasSource) {
            // 豆瓣那批条目没有源站 id，起播只能靠片名去搜——搜索页本身没有「自动选第一条播」
            // 这一步，所以 autoPlay 到这里只能退回详情页，让用户自己挑。
            openSearch(vod.name, true);
            return;
        }
        DetailPage page = new DetailPage(this, vod, detailHost);
        if (autoPlay) {
            page.playWhenReady();
        }
        pushPage(page);
    }

    /** 打开内嵌搜索页；keyword 非空时预填，searchImmediately 表示进来就搜 */
    private void openSearch(String keyword, boolean searchImmediately) {
        SearchPage page = new SearchPage(this, searchHost);
        pushPage(page);
        page.prefill(keyword, searchImmediately);
    }

    /** B 站稿件详情。数据由页面自己按 bvid 拉 */
    private void openBiliDetail(String bvid) {
        pushPage(new BiliDetailPage(this, biliDetailHost, bvid));
    }

    private static boolean isOkKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /**
     * OK 按满 {@link #OK_LONG_PRESS_MS} 之后干什么。
     *
     * <p>播放中最直接的需求是「这条线卡了，换一条」——把源里给的线路（含网盘线路）
     * 摊出来，选完接着当前集放。桌面上换的是<b>首页数据源</b>（本条配置里 sites 的某一项），
     * 和电视端 TVBox 的「请选择首页数据源」同一层意思；
     * 焦点下如果本来就有长按动作（海报、收藏墙），先让它自己处理。
     */
    private void onOkLongPress() {
        if (playerOpen) {
            showLineSwitch();
            return;
        }
        if (moduleOpen) {
            return;
        }
        View focused = getCurrentFocus();
        if (focused != null && focused.performLongClick()) {
            return;
        }
        showHomeSourceSwitch();
    }

    /** 播放中换线路：只改 VodInfo.playFlag，重播由 {@link #playVod(VodInfo)} 负责 */
    private void showLineSwitch() {
        final VodInfo info = App.getInstance().getVodInfo();
        if (info == null) {
            toastDesk("当前没有在播的视频");
            return;
        }
        List<String> flags = LineSwitchDialog.flagsOf(info);
        if (flags.size() < 2) {
            toastDesk(flags.isEmpty()
                    ? "这个源没给可选线路"
                    : "这个视频只有「" + flags.get(0) + "」一条线路");
            return;
        }
        new LineSwitchDialog(this, info, new LineSwitchDialog.Listener() {
            @Override
            public void onPick(String flag) {
                info.playFlag = flag;
                List<VodInfo.VodSeries> list = info.seriesMap == null ? null : info.seriesMap.get(flag);
                if (list != null && !list.isEmpty() && info.playIndex >= list.size()) {
                    info.playIndex = list.size() - 1;
                }
                playVod(info);
                toastDesk("已切换线路：" + flag);
            }
        }).show();
    }

    /**
     * 首页长按：换<b>本条配置里的数据源</b>。
     *
     * <p>这一层改的是 {@code HawkConfig.HOME_API}（哪个站点当首页），配置地址本身不变，
     * 所以不能走 {@link SourceConfig#apply}——那会把整条配置重新拉一遍。
     * 换完只需作废「上次验证过的站点」再重扫类目：同一份配置、同一个 jar，没理由重下。
     *
     * <p>焦点不还给原来那张海报——重扫后海报墙会整个重建，那张卡已经不在树上了。
     * 交给左上角切换器，用户下一按一定落在活着的控件上。
     */
    private void showHomeSourceSwitch() {
        if (homeSourceDialog != null && homeSourceDialog.isShowing()) {
            return;
        }
        List<SourceBean> sites = switchableSites();
        if (sites.isEmpty()) {
            // 配置还没解析出来（或真的一条站点都没有）：给一条能走的路，不弹空窗
            toastDesk("这份配置还没有数据源，先换线路或配置地址");
            showSourceSwitch();
            return;
        }
        homeSourceDialog = new HomeSourceDialog(this, sites, new HomeSourceDialog.Listener() {
            @Override
            public void onPick(SourceBean bean) {
                String key = bean.getKey();
                SourceBean old = ApiConfig.get().getHomeSourceBean();
                if (old != null && key.equals(old.getKey())) {
                    toastDesk("已经在用这个数据源");
                    return;
                }
                ApiConfig.get().setSourceBean(bean);
                SourceFeed.invalidateResolved();
                onSourceChanged();
                toastDesk("已切换数据源：" + bean.getName() + " · 正在重扫首页");
                focusDeskAfterSourceChange();
            }

            @Override
            public void onOpenLines() {
                showSourceSwitch();
            }
        });
        homeSourceDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                homeSourceDialog = null;
            }
        });
        homeSourceDialog.show();
    }

    /**
     * 可切换的数据源：配置里 sites 的每一项，按配置顺序。
     *
     * <p>只滤掉不能出内容的类型：-1 是 TVBox 的推送入口，0/1/3 分别是 XML / JSON / 爬虫。
     * 搜索站、豆瓣壳都留着——参考稿同样把它们列出来，用户可能就是想切过去看一眼，
     * 由弹窗里的提示条解释「切完没有类目」比在这里替他决定更诚实。
     */
    private List<SourceBean> switchableSites() {
        List<SourceBean> result = new ArrayList<>();
        try {
            List<SourceBean> all = ApiConfig.get().getSwitchSourceBeanList();
            if (all == null) {
                return result;
            }
            for (SourceBean bean : all) {
                if (bean == null || TextUtils.isEmpty(bean.getKey())) {
                    continue;
                }
                int type = bean.getType();
                if (type != 0 && type != 1 && type != 3) {
                    continue;
                }
                result.add(bean);
            }
        } catch (Throwable th) {
            android.util.Log.w("DeskActivity", "读取数据源列表失败", th);
        }
        return result;
    }

    /**
     * 二级入口：换配置线路 / 配置地址 / 刷新当前源 / 信号源页。
     *
     * <p>弹窗只回传用户选了什么，写配置仍然只走 {@link SourceConfig}。
     * 换完必须当场触发重扫：弹窗盖在桌面上，不会走 {@code onResume}，
     * 没有别人替我们调 {@link #syncSourceIfChanged()}。
     */
    private void showSourceSwitch() {
        if (sourceSwitchDialog != null && sourceSwitchDialog.isShowing()) {
            return;
        }
        sourceSwitchDialog = new SourceSwitchDialog(this, SourceConfig.current(),
                SourceConfig.lines(), SourceConfig.history(), new SourceSwitchDialog.Listener() {
            @Override
            public void onPick(String value) {
                boolean changed = SourceConfig.apply(value);
                if (!changed) {
                    toastDesk("已经在用这个源");
                    return;
                }
                syncSourceIfChanged();
                toastDesk("已切换源 · 正在重新加载");
                focusDeskAfterSourceChange();
            }

            @Override
            public void onRefresh() {
                SourceFeed.invalidateConfig();
                onSourceChanged();
                toastDesk("正在重新加载当前源");
                focusDeskAfterSourceChange();
            }

            @Override
            public void onConfigure() {
                showSourceConfig();
            }

            @Override
            public void onOpenSignal() {
                selectModule(MODULE_SIGNAL);
            }
        });
        sourceSwitchDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                sourceSwitchDialog = null;
            }
        });
        sourceSwitchDialog.show();
    }

    /** 首页换源弹窗里的「配置地址…」：复用设置页那个输入弹窗，不另写一份 */
    private void showSourceConfig() {
        new SourceDialog(this, SourceConfig.current(), SourceConfig.history(),
                new SourceDialog.Listener() {
                    @Override
                    public void onApply(String url) {
                        boolean changed = SourceConfig.apply(url);
                        if (!changed) {
                            toastDesk("地址没变");
                            return;
                        }
                        syncSourceIfChanged();
                        toastDesk("已应用 · 正在重新加载");
                        focusDeskAfterSourceChange();
                    }

                    @Override
                    public void onClear() {
                        SourceConfig.clear();
                        syncSourceIfChanged();
                        toastDesk("已清空内容源 · 桌面退回豆瓣榜单");
                        focusDeskAfterSourceChange();
                    }
                }).show();
    }

    /** 换源会重建海报和顶栏，等这一帧落定再把焦点放到切换器上 */
    private void focusDeskAfterSourceChange() {
        moduleSwitcher.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!moduleSwitcher.requestFocus()) {
                    ensureFocus();
                }
            }
        }, 80);
    }

    private void toastDesk(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** B 站登录。二维码由本机 zxing 画，cookie 由 BiliClient 自己落盘 */
    private void showBiliLogin() {
        showBiliLogin(null);
    }

    /** @param afterLogin 登录成功后的续作（详情页三连靠它，登录完不用再按一次） */
    private void showBiliLogin(final Runnable afterLogin) {
        new BiliLoginDialog(this, new BiliLoginDialog.Listener() {
            @Override
            public void onLoggedIn() {
                if (biliPage != null) {
                    biliPage.onLoginChanged();
                }
                if (afterLogin != null) {
                    afterLogin.run();
                }
            }
        }).show();
    }

    /**
     * 抖音 cookie 导入。
     *
     * <p>和 B 站那个的差别：B 站是真的扫码授权，这里只是「扫开本机那个局域网表单页」。
     * 弹窗开着的时候本机服务才开着，所以重复按「登录」不重开一个，只是回到已有的那个。
     *
     * @param why 为什么打开（没登录 / 哪个接口怎么拒的），显示在弹窗第一行；可为 null
     */
    private void showDouyinLogin(String why) {
        if (douyinLogin != null && douyinLogin.isShowing()) {
            return;
        }
        douyinLogin = new DouyinLoginDialog(this, why, new DouyinLoginDialog.Listener() {
            @Override
            public void onCookieChanged() {
                if (douyinPage != null) {
                    douyinPage.onCookieChanged();
                }
            }
        });
        douyinLogin.show();
    }

    // ---------------------------------------------------------------- 播放覆盖层
    /**
     * 起播。
     *
     * <p>PlayFragment 在 app 模块，desk 不能编译期依赖它，所以反射创建。
     * 播放器盖在 {@code desk_player_host} 这层上，仍然属于 DeskActivity——
     * 窗口、任务栈、返回路径都没有变化。
     */
    private void playVod(VodInfo info) {
        if (info == null || TextUtils.isEmpty(info.sourceKey) || info.seriesMap == null) {
            return;
        }
        if (playerOpen) {
            closePlayer();
        }
        Fragment fragment = newPlayFragment();
        if (fragment == null || playerHost == null) {
            android.widget.Toast.makeText(this, "播放器没就绪", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        App.getInstance().setVodInfo(info);
        playFragment = fragment;
        playerOpen = true;
        playerHost.setVisibility(View.VISIBLE);
        playerHost.bringToFront();
        try {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.desk_player_host, fragment, PLAYER_TAG)
                    .commitNowAllowingStateLoss();
            bindPlayerMethods(fragment);
            // 不藏模块页的话，方向键的焦点搜索仍可能落到被播放器盖住的卡片上
            moduleHost.setVisibility(View.GONE);
            Bundle bundle = new Bundle();
            bundle.putString("sourceKey", info.sourceKey);
            if (playerSetData == null) {
                throw new NoSuchMethodException("PlayFragment.setData");
            }
            playerSetData.invoke(fragment, bundle);
            playerHost.requestFocus();
        } catch (Throwable th) {
            android.util.Log.w("DeskActivity", "start player failed", th);
            closePlayer();
        }
    }

    private Fragment newPlayFragment() {
        try {
            return (Fragment) Class.forName(CLS_PLAY_FRAGMENT).newInstance();
        } catch (Throwable th) {
            android.util.Log.w("DeskActivity", "create PlayFragment failed", th);
            return null;
        }
    }

    private void bindPlayerMethods(Fragment fragment) {
        Class<?> cls = fragment.getClass();
        playerSetData = methodOf(cls, "setData", Bundle.class);
        playerDispatchKey = methodOf(cls, "dispatchKeyEvent", KeyEvent.class);
        playerKeyDown = methodOf(cls, "onKeyDown", int.class, KeyEvent.class);
        playerKeyUp = methodOf(cls, "onKeyUp", int.class, KeyEvent.class);
        playerBack = methodOf(cls, "onBackPressed");
    }

    private static Method methodOf(Class<?> cls, String name, Class<?>... types) {
        try {
            return cls.getMethod(name, types);
        } catch (Throwable th) {
            android.util.Log.w("DeskActivity", "method " + name + " not found", th);
            return null;
        }
    }

    /** 调用播放器方法；不存在、抛异常都按「没处理」算，不能因为反射失败卡死遥控器 */
    private boolean playerCall(Method method, Object... args) {
        if (method == null || playFragment == null) {
            return false;
        }
        try {
            Object result = method.invoke(playFragment, args);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable th) {
            android.util.Log.w("DeskActivity", "player call failed", th);
            return false;
        }
    }

    /** 关掉播放器，回到打开它的那一页 */
    private void closePlayer() {
        releasePlayer();
        if (moduleOpen && activePage != null) {
            showOnly((View) activePage);
            activePage.onEnter();
        }
    }

    private void releasePlayer() {
        playerOpen = false;
        Fragment fragment = playFragment;
        playFragment = null;
        if (fragment != null) {
            try {
                getSupportFragmentManager().beginTransaction()
                        .remove(fragment).commitNowAllowingStateLoss();
            } catch (Throwable th) {
                android.util.Log.w("DeskActivity", "remove player failed", th);
            }
        }
        if (playerHost != null) {
            playerHost.setVisibility(View.GONE);
        }
        App.getInstance().setVodInfo(null);
    }

    /**
     * 模块页打开时把桌面的可聚焦控件藏起来。
     *
     * <p>不藏的话焦点几何搜索会「穿透」：比如音乐页左侧导航按 UP，
     * 会落到被盖住的 Hero 按钮上——用户看不见焦点在哪，遥控器就像失灵了。
     * source_hint 的显隐由 showHint 控制，恢复时按记录还原。
     */
    private boolean hintWasVisible;

    private void setDeskChromeVisible(boolean visible) {
        // 藏起来不等于交不出焦点：切换器 GONE 之后焦点可以照样留在它身上，
        // 而它是 clickable 的，确定键会在它那里被 performClick 吃掉（弹出模块菜单），
        // 压在下面的模块页永远收不到这一下——「短按暂停/长按搜索」就是这么没的。
        moduleSwitcher.setFocusable(visible);
        if (!visible) {
            moduleSwitcher.clearFocus();
        }
        moduleSwitcher.setVisibility(visible ? View.VISIBLE : View.GONE);
        topNav.setVisibility(visible ? View.VISIBLE : View.GONE);
        // 时钟跟着桌面 chrome 走：模块页有自己的版面，右上角再挂一颗胶囊就重复了。
        // 它不可聚焦，藏它不涉及「焦点留在看不见的内容上」那一层
        deskClock.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            // Hero 还是海报墙由当前内容模式决定，不能一律铺回 Hero
            applyContentMode();
            if (hintWasVisible) {
                sourceHint.setVisibility(View.VISIBLE);
            }
        } else {
            heroBlock.setVisibility(View.GONE);
            heroDots.setVisibility(View.GONE);
            rowSection.setVisibility(View.GONE);
            categoryWall.setVisibility(View.GONE);
            heroBg.setVisibility(View.GONE);
            hintWasVisible = sourceHint.getVisibility() == View.VISIBLE;
            sourceHint.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- 沉浸轮播

    /**
     * 重新掐「多久没按键就进沉浸态」这一表。
     *
     * <p>每次按键、每次换内容之后都要调。不重新掐的话，用户在类目墙里逛了半分钟回首页，
     * 表已经走完，一进首页顶栏就凭空消失。
     */
    private void armIdleTimer() {
        heroBlock.removeCallbacks(idleToImmersive);
        if (!canGoImmersive()) {
            return;
        }
        heroBlock.postDelayed(idleToImmersive, IDLE_IMMERSIVE_MS);
    }

    /**
     * 现在适不适合自己进沉浸态。
     *
     * <p>只在首页、真的落了 Hero 内容时轮播。类目墙、模块页、弹窗、播放器各有各的空闲
     * 逻辑（B 站播放器那套是「N 秒收起控制条」），桌面在这上面再换背景只会跟它们打架。
     */
    private boolean canGoImmersive() {
        return !immersive && !wallMode && !moduleOpen && !menuOpen && !categoryMenuOpen
                && !playerOpen && currentTab == 0 && heroItem != null && !items.isEmpty()
                && !isFinishing() && !isDestroyed();
    }

    private void enterImmersive() {
        if (!canGoImmersive()) {
            return;
        }
        immersive = true;
        android.util.Log.i("DeskActivity", "空闲进沉浸轮播，共 " + items.size() + " 条");
        // 单独记提示条的显隐：setDeskChromeVisible 也用 hintWasVisible，两条路不能互相覆盖
        immersiveHintWasVisible = sourceHint.getVisibility() == View.VISIBLE;
        sourceHint.setVisibility(View.GONE);
        // 焦点先收回来。被藏起来的可聚焦 View 照样可能持有焦点，那时候按确定就是
        // 按在看不见的内容上（音乐页的悬浮条踩过这个坑，见 M4 §11）
        View focused = getCurrentFocus();
        if (focused != null) {
            focused.clearFocus();
        }
        moduleSwitcher.setFocusable(false);
        moduleSwitcher.setVisibility(View.GONE);
        topNav.setVisibility(View.GONE);
        rowSection.setVisibility(View.GONE);
        fadeActionButtons(false);
        // 背景重新按 Hero 那条来：海报行开着时它跟着焦点走（见 onPosterFocused），
        // 沉浸态没有焦点可跟，留着会让画面和左边那几行字说的是两部片子。
        // 这一步顺带把沉浸态的压暗系数乘上去。
        loadHeroBackdrop(heroItem.picSlide, heroItem.pic);
        // 信息块落到左下角：行区域收掉了，Hero 上面那 150dp 空档该还给画面。
        // 走 translationY 而不是改 topMargin——后者要整屏重新布局，沉浸态正是最不该
        // 在动画中间插一次 measure 的时候
        heroBlock.animate().cancel();
        heroBlock.setAlpha(1f);
        heroBlock.animate().translationY(dp(IMMERSIVE_LIFT))
                .setDuration(320L)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG)
                .start();
        heroDots.animate().translationY(dp(IMMERSIVE_DOTS_LIFT))
                .setDuration(320L)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG)
                .start();
        heroBlock.removeCallbacks(heroRotate);
        heroBlock.postDelayed(heroRotate, HERO_ROTATE_MS);
        prefetchNextHeroIntro();
    }

    /**
     * 退出沉浸态，把桌面摆回正常样子。
     *
     * @param refocus 要不要把焦点交回顶栏。按键退出要（不然焦点悬空，下一键不知道落在哪）；
     *                点按钮退出不要（那一下本来就该给按钮，抢焦点会让点击像丢了半拍）
     */
    private void exitImmersive(boolean refocus) {
        if (!immersive) {
            return;
        }
        immersive = false;
        heroBlock.removeCallbacks(heroRotate);
        heroBlock.animate().cancel();
        heroBlock.setAlpha(1f);
        heroBlock.animate().translationY(0f)
                .setDuration(260L)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG)
                .start();
        heroDots.animate().translationY(0f)
                .setDuration(260L)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG)
                .start();
        moduleSwitcher.setVisibility(View.VISIBLE);
        moduleSwitcher.setFocusable(true);
        topNav.setVisibility(View.VISIBLE);
        // Hero / 圆点 / 海报行归位交给 applyContentMode：它按当前内容模式摆，
        // 正在看海报墙的情况不能一律铺回 Hero
        applyContentMode();
        if (heroItem != null) {
            fadeActionButtons(true);
            // 把沉浸态乘上去的那一档压暗退回来。同一个 URL，Glide 走内存缓存，不会重新下载
            loadHeroBackdrop(heroItem.picSlide, heroItem.pic);
        }
        if (immersiveHintWasVisible) {
            sourceHint.setVisibility(View.VISIBLE);
        }
        immersiveHintWasVisible = false;
        if (refocus) {
            focusActiveNav();
        }
        armIdleTimer();
    }

    /**
     * 换下一条：信息块先淡出，落新内容后再淡回来。
     *
     * <p>整屏背景不由这里管——它由 Glide 的 crossFade 处理（见 {@link #loadHeroBackdrop}）：
     * 图片到位的时机不确定，视图上再排一段 alpha 只会切在半路。
     *
     * @return 真的换了；只剩一条或内容被顶掉时返回 false，轮播就此停下不再空转
     */
    private boolean rotateHero() {
        if (items.size() < 2) {
            return false;
        }
        final int next = nextHeroIndex();
        if (next == heroIndex) {
            return false;
        }
        heroBlock.animate().cancel();
        heroBlock.animate().alpha(0f)
                .setDuration(160L)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        // 淡出这 160ms 里用户可能已经按键退出了，别把内容换了还不回来
                        if (!immersive) {
                            heroBlock.setAlpha(1f);
                            return;
                        }
                        showHero(next);
                        heroBlock.setAlpha(0f);
                        heroBlock.animate().alpha(1f)
                                .setDuration(260L)
                                .setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG)
                                .start();
                    }
                })
                .start();
        return true;
    }

    // ---------------------------------------------------------------- 收藏

    /**
     * 把当前 Hero 收进/移出本地收藏。
     *
     * <p>只写本机：收藏是用户自己的清单，没有账号也没有云端（这台工程不依赖后端）。
     * 心形的实心/描边就是唯一反馈——电视上弹 Toast 只会挡住刚看清的那行片名。
     */
    private void toggleFavorite() {
        if (heroItem == null) {
            return;
        }
        boolean liked = VodFavorites.toggle(this, heroItem);
        renderLikeState();
        android.util.Log.i("DeskActivity", (liked ? "收藏 " : "取消收藏 ")
                + heroItem.name + "，清单共 " + VodFavorites.list(this).size() + " 条");
    }

    /**
     * 收藏页：本地清单铺海报墙。
     *
     * <p>没有收藏也要进这一页，而不是按了没反应：墙的空态会写清楚去哪儿收的，
     * 用户才知道这颗心是真的存下来了。
     */
    private void renderFavorites() {
        contentSeq++;
        cancelRowLoading();
        wallMode = true;
        favoritesMode = true;
        applyContentMode();
        rowTitle.setText("收藏");
        java.util.List<Movie.Video> favorites = VodFavorites.list(this);
        // \uFE0E 强制文本呈现：不加的话 ♥ 会被 emoji 字体接管，画成一颗红心，
        // 和满屏 tabler 描边图标不是一个语言
        categoryWall.showList(favorites,
                "还没有收藏。\n回首页选中想看的片子，按 ♥\uFE0E 键收进来。",
                "长按海报取消收藏");
    }

    // ---------------------------------------------------------------- 焦点与交互

    private void setupFocus() {
        FocusScaler.attach(moduleSwitcher, FocusScaler.SCALE_FOCUSED);

        FocusScaler.attach(findViewById(R.id.btn_play), 1.06f);
        FocusScaler.attach(findViewById(R.id.btn_like), 1.10f);
        FocusScaler.attach(findViewById(R.id.btn_detail), 1.10f);
        FocusScaler.attach(findViewById(R.id.row_header), 1.02f);

        // onCreate 阶段 View 还没 layout，直接 requestFocus 会失败
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            moduleSwitcher.setFocusedByDefault(true);
        }
        moduleSwitcher.post(new Runnable() {
            @Override
            public void run() {
                moduleSwitcher.requestFocus();
            }
        });
    }

    private void setupActions() {
        moduleSwitcher.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMenuOpen(!menuOpen);
            }
        });

        // 触摸用户可能直接点这条提示。它在电视上也是可聚焦的（setClickable 即意味着可聚焦），
        // 往下按一格再确认一样进得来——这和一屏的行动指引本来就是同一件事，不用刻意做成不可聚焦。
        // 点它直接进设置页的「配置地址」，是「还没配置内容源」那句话的直达入口。
        sourceHint.setClickable(true);
        sourceHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchActivity(SettingsActivity.class.getName(), null);
            }
        });

        // 三颗按钮都直接作用在当前 Hero 条目上。沉浸态下它们照样能点（触摸用户没有
        // 「先按键唤回顶栏」这一步），所以先退出沉浸，别让桌面停在只剩一颗心的状态。
        findViewById(R.id.btn_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitImmersive(false);
                // 「开始播放」和「ⓘ」的差别是有没有替用户多点那一下：
                // 详情页拿到结果就直接起播，见 DetailPage#playWhenReady
                openVideo(heroItem, true);
            }
        });
        findViewById(R.id.btn_like).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitImmersive(false);
                PressFx.heart(v);
                toggleFavorite();
            }
        });
        findViewById(R.id.btn_detail).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitImmersive(false);
                openVideo(heroItem, false);
            }
        });
        findViewById(R.id.row_header).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 一行只铺 30 条，点标题把这批条目换成整屏海报墙
                showHomeWall();
            }
        });
    }

    private void launchActivity(String className, Bundle bundle) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(getPackageName(), className));
        if (bundle != null) {
            intent.putExtras(bundle);
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            android.util.Log.w("DeskActivity", "launch " + className + " failed", e);
            android.widget.Toast.makeText(this, "该页面还没就绪", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void setMenuOpen(boolean open) {
        menuOpen = open;
        if (open) {
            hideCategoryMenu();
        }
        moduleMenu.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open && moduleMenu.getChildCount() > 0) {
            // 焦点落在当前模块上，而不是永远回到第一项
            int index = Math.max(0, Math.min(currentModule, moduleMenu.getChildCount() - 1));
            moduleMenu.getChildAt(index).requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 长按 OK。必须在播放器转发之前处理：播放器拿到键只看单次，
        // 等它处理完再判断，长按早被当成一次短按消费掉了
        if (isOkKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    okDown = true;
                    okLongFired = false;
                    if (playerOpen || !moduleOpen) {
                        okHandler.postDelayed(okLongPress, OK_LONG_PRESS_MS);
                    }
                } else if (okLongFired) {
                    // 长按已经生效：后面每一帧连发都不再当短按
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                okHandler.removeCallbacks(okLongPress);
                okDown = false;
                if (okLongFired) {
                    okLongFired = false;
                    return true;
                }
            }
        }
        // 返回键一次物理按下只算一层：遥控器连发（长按）和 250ms 内的误连按都吞掉。
        // 不拦的话，播放器 → 详情 → 列表会在半秒里连退三层，看起来像「跳着来」。
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            long now = event.getEventTime();
            if (event.getRepeatCount() > 0 || now - lastBackDownAt < BACK_DEBOUNCE_MS) {
                return true;
            }
            lastBackDownAt = now;
        }
        // 必须在 super 之前喂：焦点移动是 super.dispatchKeyEvent 里同步做的，
        // 而且移动的那一刻 FocusScaler 就会来问「是不是在连发」。放到 super 之后，
        // 拿到的永远是上一个事件的状态，压缩会整整慢一拍。
        KeyRepeatHandler repeat = KeyRepeatHandler.get();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            repeat.onKeyDown(event);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            repeat.onKeyUp(event);
        }
        // 播放器只认宿主动喂进来的按键（它不在窗口的焦点树里），和 DetailActivity 同一套转发
        if (playerOpen && playerCall(playerDispatchKey, event)) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // 沉浸轮播里任何键都先当「把桌面唤回来」用，这一键本身不算操作。
            // 电视的交互是按了才有 UI；把第一键同时当成点击，等于让人误开看不见焦点的东西
            if (immersive) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    Back.consume("桌面·退出沉浸轮播");
                }
                exitImmersive(true);
                return true;
            }
            // 每次按键重新掐表：不重掐的话，用户操作到第 19 秒时表已经快走完，
            // 停手看海报的工夫顶栏就凭空消失了
            armIdleTimer();
        }
        // 海报墙模式下，DOWN 从顶栏直接进墙。交给几何寻焦会落到墙里第一排以外的位置，
        // 甚至掉到被藏起来的 Hero 按钮上。
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                && wallMode && !moduleOpen && !menuOpen && !categoryMenuOpen) {
            View focused = getCurrentFocus();
            if (focused != null && focused.getParent() == topNav && categoryWall.focusLast()) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 播放层最优先：它盖住整个窗口，按键不能再落到下面的桌面或模块页
        if (playerOpen) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 先给播放器（它可能只是收起控制条），它不要才是退出播放
                if (playerCall(playerBack)) {
                    return true;
                }
                closePlayer();
                Back.consume("影视·退出播放");
                return true;
            }
            if (playerCall(playerKeyDown, keyCode, event)) {
                return true;
            }
            // 没消化也不往下传，否则方向键会把焦点移到被播放器盖住的卡片上
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK && categoryMenuOpen) {
            closeCategoryMenu();
            Back.consume("桌面·收起类目菜单");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && menuOpen) {
            setMenuOpen(false);
            Back.consume("桌面·收起模块菜单");
            // 模块页开着时切换器是隐藏的，焦点还给模块页自己
            if (moduleOpen && activePage != null) {
                activePage.onEnter();
            } else {
                moduleSwitcher.requestFocus();
            }
            return true;
        }
        // 模块页先拿菜单键：抖音/B 站都有自己的功能抽屉（和参考 App 一致），
        // 页面不认才轮到下面开全局菜单。以前顺序反了，页里的 MENU 分支永远收不到
        if (keyCode == KeyEvent.KEYCODE_MENU && moduleOpen && activePage != null
                && activePage.handleKeyDown(keyCode, event)) {
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            setMenuOpen(!menuOpen);
            return true;
        }
        // 模块页/内容页打开时按键先给页面（焦点移动在 dispatchKeyEvent 里已经走完，
        // 到这里的是页面没消化的：方向键穿透、BACK、媒体键）
        if (moduleOpen && activePage != null) {
            if (activePage.handleKeyDown(keyCode, event)) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (pageStack.size() > 1) {
                    // 详情/搜索是叠在模块页上的，先退这一层
                    popPage();
                } else {
                    // 焦点落点在 closeModulePageNow 里统一还，不再固定抓切换器
                    closeModulePage();
                }
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 海报墙不是单独一页，BACK 只退回 Hero 首页，不退出桌面
            if (wallMode) {
                returnHome();
                Back.consume("桌面·从海报墙回首页");
                return true;
            }
            // 在任何子模块里，BACK 都先回桌面
            if (currentModule != 0) {
                selectModule(0);
                Back.consume("桌面·回首页");
                return true;
            }
            // 桌面是栈底，BACK 不退出（本 App 还接管着系统 HOME，退出得靠 HOME 键或菜单）
            Back.consume("桌面·已是栈底，不退");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (playerOpen) {
            if (playerCall(playerKeyUp, keyCode, event)) {
                return true;
            }
            return true;
        }
        // 抬起也先给模块页：「短按确定＝播放/暂停、长按＝搜索」这类约定
        // 只有按下和抬起配对才看得出按了多久，光靠 onKeyDown 做不到
        if (moduleOpen && activePage != null && activePage.handleKeyUp(keyCode, event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncSourceIfChanged();
        // 去设置页、系统卸载页、或按 HOME 退到后台再回来：开着的那页要自己决定
        // 是接着放还是重来，跟 onLeave 配成一对。
        if (moduleOpen && activePage != null) {
            activePage.onEnter();
        }
        // 时钟：先摘掉可能残留的续排链再立即写一次。tickClock 每次都把自己往后排，
        // 直接 run() 第二次就变成两条链各刷各的（一次 onResume 多一条）
        if (deskClock != null) {
            deskClock.removeCallbacks(tickClock);
            tickClock.run();
        }
        // 回到前台按「人回来了」处理：沉浸态结束，桌面回到可操作的样子，焦点由 ensureFocus 收尾
        exitImmersive(false);
        armIdleTimer();
        ensureFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 窗口看不见了就别再自己转。时钟、空闲表、轮播都是排在本机 Handler 上的
        // Runnable，压在后台继续刷只是白耗电——撤掉沉浸态的初衷就是省功耗
        if (deskClock != null) {
            deskClock.removeCallbacks(tickClock);
        }
        heroBlock.removeCallbacks(idleToImmersive);
        heroBlock.removeCallbacks(heroRotate);
        // 长按计时器不能比窗口活得久：一响就会去开一个没人看着的弹窗
        okHandler.removeCallbacks(okLongPress);
        okDown = false;
        okLongFired = false;
    }

    /**
     * 整个应用退到后台。
     *
     * <p>缓存页只是被窗口挡住，播放器可不知道自己已经看不见了——不在这里说一声，
     * 按 HOME 之后抖音的声音会一直响。用 {@code onStop} 而不是 {@code onPause}：
     * 弹个对话框、临时失焦这种「还在看」的情况不该把播放掐掉。
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (activePage != null) {
            activePage.onLeave();
        }
    }

    /**
     * 源被别处改过就重扫。
     *
     * <p>用户可能刚从自建的设置页（SettingsActivity → 配置地址）回来，那里会把源写到
     * TVBox 的 api_url。源变了就得重扫，否则顶栏还挂着上一个源的类目，看着像设置没生效。
     * 「应用之后不用重启 App」这件事就是靠这里兜的 —— 写配置的地方只写，不负责触发加载。
     *
     * <p>两个调用点：{@code onResume}（跳出到设置页再回来），和 {@code closeModulePage}
     * （在信号源页里就地换了源——内嵌页不会走这里，回来时不补一次就还是旧源的类目）。
     */
    private void syncSourceIfChanged() {
        String url = SourceFeed.currentApiUrl();
        if (!url.equals(lastApiUrl)) {
            lastApiUrl = url;
            SourceFeed.invalidateConfig();
            onSourceChanged();
        }
    }

    /**
     * 回到桌面时保证有东西持有焦点。
     *
     * <p>从设置页、或从 TVBox 的任意页面回来时，桌面上经常一个控件都没有焦点——
     * 那些页面的窗口把焦点拿走之后，回来时系统不一定还给我们（实测小米电视上就不会还）。
     * 这时候遥控器的第一下是「没反应」的：按方向键不动、按确定不响应，
     * 用户看到的就是一个死了的桌面，得先按一下别的键把它叫醒。
     *
     * <p>所以回到前台就补一次。已经有人拿着焦点就不动，不抢用户的位置。
     */
    private void ensureFocus() {
        moduleSwitcher.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                // 模块页开着的时候，桌面 chrome 是 GONE 的——切换器拿不到焦点，
                // 硬抢只会把音乐页的焦点打掉。这时候由模块页自己管焦点。
                if (moduleOpen) {
                    return;
                }
                if (getCurrentFocus() == null) {
                    if (wallMode && categoryWall.hasContent()) {
                        categoryWall.focusLast();
                    } else {
                        moduleSwitcher.requestFocus();
                    }
                }
            }
        });
    }

    /**
     * 配置源变化后的处理。
     *
     * <p>源可能被换掉，也可能被清空。两种情况都得重新走一遍「拉配置 + 挂 jar + 取类目」——
     * {@link SourceFeed#invalidateConfig()} 只把缓存作废，它自己不会去加载。
     */
    private void onSourceChanged() {
        // 旧源的类目、内容、以及已经用掉的重试次数，全部作废。
        // 重试次数属于「这一次加载」，换了源就该重新给满，否则换到第二个源时已经没有重试机会了。
        categoryRetryCount = 0;
        loadedTab = -1;
        pendingTab = -1;
        currentTab = 0;
        overflowSort = null;
        wallMode = false;
        hideCategoryMenu();
        topNav.removeCallbacks(tabSwitch);
        topNav.removeCallbacks(categoryRetry);
        // 还在飞的列表请求结果按旧源处理，作废掉
        contentSeq++;
        // 首屏缓存是旧源的列表，换源后必须一起清
        categoryWall.clearCache();

        if (TextUtils.isEmpty(lastApiUrl)) {
            // 清空了地址：继续挂着上个源的类目和海报会让人以为设置没生效，退回引导
            renderTopNav(null);
            renderHome();
            showSourceMissing();
            return;
        }

        // 换了源：先挂上「正在加载」，再重扫配置和类目。
        // 这一步可能耗到 25s（配置 + jar 的整体超时），期间顶栏和别人「源挂了」时一样
        // 只有「首页」——不说明的话用户分不清是在加载还是已经失败了。
        showHint(HINT_LOADING);
        buildTopNav();
        // 当前页签的内容也是上个源的，重新拉一遍；buildTopNav 里已把 currentTab 收敛到「首页」
        selectTab(currentTab);
    }

    /**
     * 顶栏下面的提示条。同一时刻只挂一条，后写的覆盖先写的。
     *
     * <p>纯文本 + 一个可点区域：点它就直接进「设置 → 配置地址」。
     * 不做成对话框，是因为首页本身是有内容的（豆瓣榜单），弹窗会挡住一个本来能用的页面。
     */
    private void showHint(String text) {
        if (sourceHint == null) {
            return;
        }
        sourceHint.setText(text);
        sourceHint.setVisibility(View.VISIBLE);
    }

    /**
     * 没配内容源时的引导。首次安装、或用户清空了地址，都走这里。
     *
     * <p>只在顶栏下面挂一条提示，<b>不清空页面</b>——首页走豆瓣榜单，没配源也能看，
     * 把整屏换成一句提示等于把这个能用的页面藏起来。
     */
    private void showSourceMissing() {
        showHint(HINT_NOT_CONFIGURED);
    }

    private void hideSourceMissing() {
        if (sourceHint != null && sourceHint.getVisibility() != View.GONE) {
            sourceHint.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        // 页面栈里一次性的详情/搜索随宿主销毁；缓存的模块页在下面单独释放
        for (ModulePage page : pageStack) {
            if (!isCachedPage(page)) {
                page.onRelease();
            }
        }
        pageStack.clear();
        pageReturnFocus.clear();
        // 模块页的重资源（音乐播放器、quickjs runtime、在飞的内容请求）随宿主一起回收
        if (musicPage != null) {
            musicPage.onRelease();
        }
        if (douyinPage != null) {
            douyinPage.onRelease();
        }
        // cookie 弹窗带着本机那个局域网服务，宿主没了就必须关掉
        if (douyinLogin != null) {
            douyinLogin.dismiss();
        }
        // 搜索弹窗挂的是同一个服务的另一条路径，同理
        if (douyinSearch != null) {
            douyinSearch.dismiss();
        }
        if (biliPage != null) {
            biliPage.onRelease();
        }
        if (appPage != null) {
            appPage.onRelease();
        }
        if (signalPage != null) {
            signalPage.onRelease();
        }
        for (int i = 0; i < placeholders.size(); i++) {
            placeholders.valueAt(i).onRelease();
        }
        // 迟到的 jar 通知可能落在一个已经销毁的界面上
        SourceFeed.setJarMountedListener(null);
        // 类目重试可能还挂在消息队列里，销毁后必须摘掉
        topNav.removeCallbacks(categoryRetry);
        topNav.removeCallbacks(tabSwitch);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 音乐页内嵌后权限申请落在桌面上，转给页面处理
        if (musicPage != null) {
            musicPage.onPermissionsResult(requestCode, grantResults);
        }
    }

    // ---------------------------------------------------------------- 工具

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonEmpty(String first, String second) {
        return TextUtils.isEmpty(safe(first)) ? safe(second) : first;
    }

    /** 源里的简介常带 <p> 之类标签，Hero 上只留纯文本 */
    private static String stripHtml(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String plain = text.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
        return plain.length() > 120 ? plain.substring(0, 120) + "…" : plain;
    }

    /** 从 note 里取评分，取不到返回空串 */
    private static String ratingOf(String note) {
        String value = safe(note).replace("分", "").trim();
        if (value.isEmpty()) {
            return "";
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                return "";
            }
        }
        return value;
    }

    /**
     * Hero 背景。
     *
     * <p>优先用横版剧照 {@code picSlide}——CMS 的 {@code vod_pic_slide}，本身就是 16:9，
     * 铺满整屏几乎不用裁。拿不到才退回竖版海报，那时 centerCrop 会先裁掉上下大半，
     * 只剩中间一条，观感差一截，但至少不是马赛克。
     *
     * <p>按屏幕真实分辨率解码（见 {@link #HERO_MAX_W}），不再人为缩小。
     */
    private void loadHeroBackdrop(String slideUrl, String posterUrl) {
        if (heroBg == null) {
            return;
        }
        boolean hasSlide = !TextUtils.isEmpty(slideUrl);
        String url = hasSlide ? slideUrl : posterUrl;
        if (TextUtils.isEmpty(url)) {
            heroBg.setImageDrawable(null);
            return;
        }
        // 竖版海报（约 300×450）铺满 1920×1080 要放大 4~6 倍，细节是补不回来的。
        // 与其让一块糊掉的大脸抢戏，不如压暗一档当氛围底，文字对比也更好读。
        float base = hasSlide ? HERO_ALPHA_SLIDE : HERO_ALPHA_POSTER;
        // 沉浸态再压一档：这时候没有顶栏和海报行分走视线，整屏就这一张图，
        // 源图越艳越会把左边那几行字吃掉（参考图上信息块压得住，背景是暗的）
        if (immersive) {
            base *= IMMERSIVE_BG_DIM;
        }
        heroBg.setAlpha(base);
        try {
            Object model = ImgUtil.getImageModel(url);
            if (model == null) {
                heroBg.setImageDrawable(null);
                return;
            }
            Glide.with(this)
                    .load(model)
                    .override(HERO_MAX_W, HERO_MAX_H)
                    .centerCrop()
                    // 沉浸轮播每 8 秒换一次整屏背景，硬切的话眼角余光里像闪了一下。
                    // 交给 Glide 做新旧图交叉淡入：图片到位的时机本来就不确定，
                    // 视图上的 alpha 动画只会切在半路，这里才是能对齐的那一层。
                    .transition(DrawableTransitionOptions.withCrossFade(320))
                    .into(heroBg);
        } catch (Throwable th) {
            android.util.Log.w("DeskActivity", "load hero failed: " + url, th);
        }
    }

    /**
     * 加载图片。
     *
     * <p>TVBox 的图片地址带 {@code @User-Agent=xxx@Referer=xxx} 后缀，
     * 必须过 {@link ImgUtil#getImageModel(String)} 拆成带请求头的 GlideUrl，
     * 否则直接把整串当 URL 会 403 / 404。
     *
     * <p>这里不用 {@code RequestOptions}：Glide 4 的 {@code placeholder(null)} 在部分
     * 版本上会抛空指针，用链式 API 只在有兜底图时才设置更稳。
     */
    private void loadImage(ImageView target, String url) {
        loadImage(target, url, null);
    }

    private void loadImage(ImageView target, String url, android.graphics.drawable.Drawable fallback) {
        if (target == null) {
            return;
        }
        if (TextUtils.isEmpty(url)) {
            if (fallback != null) {
                target.setImageDrawable(fallback);
            }
            return;
        }
        try {
            Object model = ImgUtil.getImageModel(url);
            if (model == null) {
                if (fallback != null) {
                    target.setImageDrawable(fallback);
                }
                return;
            }
            if (fallback == null) {
                Glide.with(this).load(model).centerCrop().into(target);
            } else {
                Glide.with(this).load(model)
                        .placeholder(fallback)
                        .error(fallback)
                        .centerCrop()
                        .into(target);
            }
        } catch (Throwable th) {
            android.util.Log.w("DeskActivity", "load image failed: " + url, th);
        }
    }

    /** 把颜色压暗，用于渐变的下半段 */
    private static int darken(int color) {
        float factor = 0.45f;
        int r = (int) (Color.red(color) * factor);
        int g = (int) (Color.green(color) * factor);
        int b = (int) (Color.blue(color) * factor);
        return Color.rgb(r, g, b);
    }
}

package com.tvdesk.core.feed;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.HawkConfig;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 从配置源里取「顶栏类目」和「某类目的内容」。
 *
 * <p>桌面顶栏不做自己的一套信息架构，直接用源自带的 class 列表
 * （电影 / 电视剧 / 动漫 / 综艺 …）。换源之后顶栏跟着变，不用改代码。
 *
 * <p>源分三种，处理方式和 TVBox 的 SourceViewModel 保持一致：
 * <ul>
 *   <li>type 3：爬虫 jar，走 {@link Spider#homeContent(boolean)} / {@link Spider#categoryContent}</li>
 *   <li>type 0：CMS XML，GET {@code api} 后按 XML 解析</li>
 *   <li>type 1：CMS JSON，GET {@code api} 后按 JSON 解析</li>
 * </ul>
 *
 * <p>不复用 SourceViewModel，是因为它在 app 模块里，desk 依赖它会造成模块循环。
 */
public final class SourceFeed {

    private static final String TAG = "SourceFeed";

    /** 爬虫调用可能阻塞，统一放到单线程里排队，避免并发打爆源站 */
    private static final ExecutorService SPIDER_POOL = Executors.newSingleThreadExecutor();

    /** 单个请求的上限 */
    private static final int TIMEOUT_SECONDS = 20;

    /**
     * 找可用源时最多试几个、最多花多久。
     *
     * <p>配置里常有公告站、搜索站、以及 jar 加载不起来的站点，排在前面会挡住真正的站点。
     * 逐个试能自动绕过去，但必须有上限——每个失败都可能要等一次 jar 下载或超时。
     * 时间预算比数量更靠谱：坏源失败得快，就多试几个；有源在慢慢下载，就早点收手。
     */
    private static final int MAX_CANDIDATES = 40;
    private static final long RESOLVE_BUDGET_MS = 30000L;

    /**
     * 时间预算生效前至少先试几个。
     *
     * <p>前面几个往往是一瞬间就失败的（主 jar 没挂上，爬虫直接返回空），
     * 如果一超时就收手，会连真正能用的源都试不到。
     */
    private static final int MIN_CANDIDATES = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 真正能用的源，{@link #loadList} 跟它走 */
    private static SourceBean resolvedSource;

    /**
     * 当前正在用的内容源。
     *
     * <p>详情/搜索要优先问这个源：用户刚在上面翻到片子，先问它命中率最高，
     * 也避免搜出同一个片子的十几个镜像站。
     */
    public static SourceBean currentSource() {
        if (resolvedSource != null) {
            return resolvedSource;
        }
        try {
            return ApiConfig.get().getHomeSourceBean();
        } catch (Throwable th) {
            return null;
        }
    }

    private SourceFeed() {
    }

    public interface CategoryCallback {
        void onCategories(List<MovieSort.SortData> categories);

        void onError(Throwable th);
    }

    public interface ListCallback {
        void onList(List<Movie.Video> videos);

        void onError(Throwable th);
    }

    public interface ConfigCallback {
        void onReady();

        void onError(String message);
    }

    /**
     * 爬虫 jar 迟到时的通知。
     *
     * <p>「配置 + jar」有整体超时（见 {@link #CONFIG_TOTAL_TIMEOUT_MS}）。超时后桌面会按
     * 「无 jar」先把顶栏铺出来，但如果 jar 后来又挂上了，之前那次扫描其实是残的
     * （type 3 站点全军覆没，只捞到几个 CMS 站）。这个回调就是让桌面自动重扫一次，
     * 用户不用退出桌面再进来。
     */
    public interface JarMountedListener {
        void onJarMounted();
    }

    // ---------------------------------------------------------------- 配置

    /** 配置只加载一次，多个调用方排队等同一个结果 */
    private static boolean configLoading;
    private static boolean configReady;
    private static int configAttempt;
    private static final List<ConfigCallback> PENDING_CONFIG = new ArrayList<>();

    /**
     * 配置拉取失败后的重试间隔（毫秒），用完才把失败抛给调用方。
     *
     * <p>配置源是单点，网络抖一下整个顶栏就只剩「首页」了，用户会以为功能坏了。
     * 重试两次的成本远低于一次误降级。
     */
    private static final long[] CONFIG_RETRY_DELAYS = {2000L, 6000L};

    /**
     * 没配源时 {@link ConfigCallback#onError} 收到的标记。
     *
     * <p>调用方拿它区分「用户还没填地址」和「源站拉不动」——
     * 前者要引导去设置，后者只需要提示稍后再试。
     */
    public static final String NOT_CONFIGURED = "not_configured";

    /**
     * 配置源整体超时后 {@link ConfigCallback#onError} 收到的标记。
     *
     * <p>和 {@link #NOT_CONFIGURED} 分开，是因为两者要给用户看的话完全不同：
     * 一个是「你还得去填地址」，一个是「地址填了但那头没动静」。
     */
    public static final String MSG_TIMEOUT = "config_timeout";

    /**
     * 「拉配置 + 下载并挂载 jar」的整体时间上限。
     *
     * <p>为什么必须有这个上限：jar 下载用 OkGo 的 {@code readTimeout} 是<b>单次 IO</b>
     * 的超时，不是整个请求的超时。源站如果是「连得上、但只肯慢慢吐字节」，
     * 每次读都在 20s 内返回一丁点数据，这个循环可以一直转下去。
     * 更糟的是 {@code ApiConfig.loadJar} 内部还有一层重试，失败一次会把整个 jar
     * 重下一遍，最坏情况叠加到一分钟以上。
     *
     * <p>表现出来就是：用户换了个源，桌面停在只有「首页」的状态，转很久也没有任何提示，
     * 只能按返回键退出重进——他没法知道是在加载还是已经死了。
     *
     * <p>25s 的取法：正常源（配置几百 KB + jar 几百 KB）实测 6s 内完成，
     * 慢的也就十几秒；超过 25s 基本可以判定为这一轮没戏了。
     */
    private static final long CONFIG_TOTAL_TIMEOUT_MS = 25000L;

    /**
     * 本轮「配置 + jar」的截止时刻，0 表示当前没有在跑的轮次。
     *
     * <p>存截止时刻而不是「还剩多久」，是为了让重试不顺延总预算——
     * 否则「重试两次」这个设计会把 25s 变成 25s × 3。
     */
    private static volatile long configDeadline;

    /** 站点列表（class 列表）已经解析出来了。jar 挂没挂上不影响这一项。 */
    private static volatile boolean configParsed;

    /**
     * 本轮已经因为超时按「无 jar」继续过了。
     *
     * <p>jar 后脚才挂上时要靠它决定要不要通知桌面重扫——只在「曾经超时」时才需要，
     * 正常路径下 jar 挂上时桌面还在等第一次结果，重扫纯属白跑。
     */
    private static volatile boolean timedOut;

    private static volatile JarMountedListener jarMountedListener;

    /**
     * 当前配置源地址。用来发现「用户去设置里换了源」这件事。
     *
     * <p>读的是 TVBox 自己的 {@code HawkConfig.API_URL}，也就是「设置 → 配置地址」里那一项。
     * Hawk 在进程启动早期可能还没 init，这里兜住异常，返回空串表示「还没配」。
     */
    public static String currentApiUrl() {
        try {
            String url = Hawk.get(HawkConfig.API_URL, "");
            return url == null ? "" : url.trim();
        } catch (Throwable th) {
            return "";
        }
    }

    /**
     * 把已加载的配置作废，下次 {@link #ensureConfig} 会重新走「拉配置 + 挂 jar」。
     *
     * <p>用户能在设置里换源，换完回桌面必须重扫——否则桌面上挂的还是上一个源的
     * 类和爬虫 jar，看着像换了没用。
     */
    public static void invalidateConfig() {
        configReady = false;
        configLoading = false;
        configAttempt = 0;
        synchronized (PENDING_CONFIG) {
            PENDING_CONFIG.clear();
        }
        resolvedSource = null;
        // 本轮的截止时刻和超时标记一并作废：换了源就是新的一轮，总预算要重新给满
        MAIN.removeCallbacks(CONFIG_WATCHDOG);
        configDeadline = 0L;
        configParsed = false;
        timedOut = false;
        synchronized (SLIDE_LOCK) {
            SLIDE_CACHE.evictAll();
        }
        android.util.Log.i(TAG, "配置已作废，下次进桌面重新加载");
    }

    /** 注册 jar 迟到通知。桌面在 {@code onCreate} 注册、{@code onDestroy} 传 null 注销。 */
    public static void setJarMountedListener(JarMountedListener listener) {
        jarMountedListener = listener;
    }

    private static void notifyJarMounted() {
        final JarMountedListener listener = jarMountedListener;
        if (listener == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                listener.onJarMounted();
            }
        });
    }

    /**
     * 武装本轮的整体超时。
     *
     * <p>可以重复调用：本轮已经算好截止时刻就不重置，只按剩余时间重新排一次。
     * 重试路径会再调一次，这样「重试两次」不会把总预算翻倍。
     */
    private static void armWatchdog() {
        if (configDeadline == 0L) {
            configDeadline = System.currentTimeMillis() + CONFIG_TOTAL_TIMEOUT_MS;
            timedOut = false;
        }
        long remain = configDeadline - System.currentTimeMillis();
        if (remain < 1L) {
            remain = 1L;
        }
        MAIN.removeCallbacks(CONFIG_WATCHDOG);
        MAIN.postDelayed(CONFIG_WATCHDOG, remain);
    }

    /**
     * 整体超时。跑在主线程。
     *
     * <p>两种收场方式，区别在于「配置解析出来没有」：
     * <ul>
     *   <li>解析出来了 → 按「无 jar」继续。配置里的 CMS 站（type 0/1）不依赖 jar，
     *       顶栏能出类目总比一直空着强。等 jar 真挂上了还有一次自动重扫。</li>
     *   <li>没解析出来 → 没有站点列表可用，只能如实报超时，让桌面提示用户。</li>
     * </ul>
     */
    private static final Runnable CONFIG_WATCHDOG = new Runnable() {
        @Override
        public void run() {
            if (configReady) {
                return;
            }
            // 重试的空窗期（configLoading 已放开、延迟任务还没跑）不算「没人管」，
            // 只要还有人等在回调里就得继续盯着
            boolean waited = false;
            synchronized (PENDING_CONFIG) {
                waited = !PENDING_CONFIG.isEmpty();
            }
            if (!configLoading && !waited) {
                return;
            }
            long remain = configDeadline - System.currentTimeMillis();
            if (remain > 0L) {
                // 还没到点。可能是重试空窗期把自己叫醒了，睡到点上再说。
                MAIN.postDelayed(this, remain);
                return;
            }
            timedOut = true;
            if (configParsed) {
                android.util.Log.w(TAG, "配置+jar 超过 " + (CONFIG_TOTAL_TIMEOUT_MS / 1000)
                        + "s，先按「无 jar」继续");
                flushConfig(true, null);
                return;
            }
            android.util.Log.w(TAG, "配置加载超过 " + (CONFIG_TOTAL_TIMEOUT_MS / 1000)
                    + "s 仍未解析出站点列表，放弃本轮");
            flushConfig(false, MSG_TIMEOUT);
        }
    };

    /**
     * 确保「配置解析完 + 爬虫 jar 已加载」。回调在主线程。
     *
     * <p>TVBox 里这两步都是 HomeActivity 做的：先 {@code loadConfig()} 解析站点列表，
     * 再 {@code loadJar()} 下载并挂载爬虫 jar。桌面不是它的子页面，两步都得自己补——
     * 少了第二步，每个源拿到的都是 {@code SpiderNull}，{@code homeContent} 一律返回空串，
     * 表现出来就是「顶栏一个类目都没有」。
     *
     * <p>jar 的 DexClassLoader 只存在于内存里，进程重启后必须重新挂载，
     * 所以这里每个进程只跑一次，而不是「文件在就跳过」。
     */
    public static void ensureConfig(android.app.Activity activity, final ConfigCallback callback) {
        if (configReady) {
            callback.onReady();
            return;
        }

        // 根本没配源（首次安装就是这种状态）。没什么可拉的，也不值得重试——
        // 直接把「未配置」交给调用方去引导用户，别让用户干等两轮重试（2s + 6s）。
        if (currentApiUrl().isEmpty()) {
            callback.onError(NOT_CONFIGURED);
            return;
        }

        synchronized (PENDING_CONFIG) {
            PENDING_CONFIG.add(callback);
        }
        if (configLoading) {
            return;
        }
        // 上一轮重试次数用完了，这次进桌面重新给一组
        if (configAttempt >= CONFIG_RETRY_DELAYS.length) {
            configAttempt = 0;
        }
        configLoading = true;
        // jar 下载没有总时长上限（readTimeout 是单次 IO 的），必须自己盯一个总预算，
        // 否则源站「连得上但不肯吐数据」时桌面会无限期停在只有「首页」。
        armWatchdog();
        loadConfigThenJar(activity);
    }

    private static void loadConfigThenJar(android.app.Activity activity) {
        try {
            // activity 在 loadConfig 里只是透传，重试时传 null 是安全的
            ApiConfig.get().loadConfig(true, new ApiConfig.LoadConfigCallback() {
                @Override
                public void success() {
                    configAttempt = 0;
                    // 站点列表到手了。后面 jar 卡住也只是「少了一批 type 3 源」，
                    // 不至于全盘皆输——超时时靠这个标记决定是降级继续还是直接报错。
                    configParsed = true;
                    loadJarThenReady();
                }

                @Override
                public void error(String msg) {
                    retryOrFail(msg);
                }

                @Override
                public void notice(String msg) {
                    // 加载过程中的提示，桌面不需要
                }
            }, activity);
        } catch (Throwable th) {
            retryOrFail(th.getMessage());
        }
    }

    /** 还有重试次数就延迟再来一次，用完才把失败交给调用方 */
    private static void retryOrFail(final String message) {
        configLoading = false;
        if (configAttempt < CONFIG_RETRY_DELAYS.length) {
            final long delay = CONFIG_RETRY_DELAYS[configAttempt];
            configAttempt++;
            android.util.Log.i(TAG, "配置加载失败，" + (delay / 1000) + "s 后重试："
                    + (message == null ? "" : message.replace('\n', ' ')));
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    synchronized (PENDING_CONFIG) {
                        if (configReady || configLoading || PENDING_CONFIG.isEmpty()) {
                            return;
                        }
                    }
                    configLoading = true;
                    // 重排一次哨兵：延迟任务执行前哨兵可能已经被自己叫醒过，
                    // armWatchdog 不重置截止时刻，所以总预算不会被这次重试顺延。
                    armWatchdog();
                    loadConfigThenJar(null);
                }
            }, delay);
            return;
        }
        android.util.Log.w(TAG, "配置加载重试次数已用完："
                + (message == null ? "" : message.replace('\n', ' ')));
        flushConfig(false, message);
    }

    private static void loadJarThenReady() {
        String spider;
        try {
            spider = ApiConfig.get().getSpider();
        } catch (Throwable th) {
            flushConfig(false, th.getMessage());
            return;
        }
        if (TextUtils.isEmpty(spider)) {
            // 纯 CMS 源没有 jar，配置解析完就能用
            flushConfig(true, null);
            return;
        }
        android.util.Log.i(TAG, "加载爬虫 jar: " + spider);
        try {
            ApiConfig.get().loadJar(true, spider, new ApiConfig.LoadConfigCallback() {
                @Override
                public void success() {
                    android.util.Log.i(TAG, "爬虫 jar 已挂载");
                    flushConfig(true, null);
                    if (timedOut) {
                        // 这一轮已经因为超时按「无 jar」收过场了，桌面当时拿到的是残缺结果
                        // （type 3 站点一个都没捞到）。jar 现在补上了，让它自动重扫一遍，
                        // 别逼用户退出桌面再进来。
                        android.util.Log.i(TAG, "jar 晚于超时挂上，通知桌面重扫");
                        notifyJarMounted();
                    }
                }

                @Override
                public void error(String msg) {
                    // jar 加载不起来不等于整份配置废了：配置里的站点列表已经解析好了，
                    // 自带独立 jar 的站点仍然能用。降级成警告，继续往下找可用的源。
                    android.util.Log.w(TAG, "主爬虫 jar 加载失败，继续尝试其它站点: " + msg);
                    flushConfig(true, null);
                }

                @Override
                public void notice(String msg) {
                }
            });
        } catch (Throwable th) {
            flushConfig(false, th.getMessage());
        }
    }

    private static void flushConfig(final boolean ready, final String message) {
        // 本轮结束，超时哨兵该撤了。timedOut 留着不清——jar 迟到时还要靠它判断要不要重扫。
        MAIN.removeCallbacks(CONFIG_WATCHDOG);
        configDeadline = 0L;
        // 超时哨兵在主线程、jar 回调在 IO 线程，两边可能同时收场。
        // 取+清必须是原子的，否则回调会被发两遍（onError 和 onReady 各来一次）。
        final List<ConfigCallback> callbacks;
        synchronized (PENDING_CONFIG) {
            callbacks = new ArrayList<>(PENDING_CONFIG);
            PENDING_CONFIG.clear();
        }
        configLoading = false;
        if (ready) {
            configReady = true;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (ConfigCallback cb : callbacks) {
                    if (ready) {
                        cb.onReady();
                    } else {
                        cb.onError(message);
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------- 类目

    /**
     * 取顶栏类目。回调在主线程。
     *
     * <p>先试设置里选的首页源；它没有类目（公告站、搜索站都是这种）就往后试别的站点，
     * 直到有一个能给出 class 列表。不修改用户的 HOME_API 设置，只在桌面内部记住结果。
     */
    public static void loadCategories(final CategoryCallback callback) {
        fallbackSource = null;
        fallbackCategories = new ArrayList<>();
        final List<SourceBean> candidates = candidateSources();
        if (candidates.isEmpty()) {
            android.util.Log.i(TAG, "loadCategories: 配置里没有可用站点");
            postCategories(callback, new ArrayList<MovieSort.SortData>());
            return;
        }
        tryCategories(candidates, 0, System.currentTimeMillis() + RESOLVE_BUDGET_MS, callback);
    }

    /**
     * 所有候选都没通过内容探测时的退路：第一个能出类目的源。
     *
     * <p>有类目总比顶栏空着强——用户至少能看见分类，点进去失败也能自己发现
     * 是源的问题，而不是以为桌面坏了。
     */
    private static SourceBean fallbackSource;
    private static List<MovieSort.SortData> fallbackCategories = new ArrayList<>();

    private static void rememberFallback(SourceBean bean, List<MovieSort.SortData> categories) {
        if (fallbackSource == null) {
            fallbackSource = bean;
            fallbackCategories = categories;
        }
    }

    private static void postFallbackOrEmpty(final CategoryCallback callback) {
        if (fallbackSource != null) {
            android.util.Log.i(TAG, "没有源通过内容探测，退回：" + fallbackSource.getName());
            resolvedSource = fallbackSource;
            postCategories(callback, fallbackCategories);
            return;
        }
        postCategories(callback, new ArrayList<MovieSort.SortData>());
    }

    private static void tryCategories(final List<SourceBean> candidates, final int index,
                                      final long deadline, final CategoryCallback callback) {
        if (index >= candidates.size()) {
            postFallbackOrEmpty(callback);
            return;
        }
        if (index >= MIN_CANDIDATES && System.currentTimeMillis() > deadline) {
            android.util.Log.i(TAG, "找源超时，试了 " + index + " 个");
            postFallbackOrEmpty(callback);
            return;
        }
        final SourceBean bean = candidates.get(index);
        fetchCategories(bean, new CategoryCallback() {
            @Override
            public void onCategories(List<MovieSort.SortData> categories) {
                if (categories.isEmpty()) {
                    android.util.Log.i(TAG, "源无类目，跳过：" + bean.getName());
                    tryCategories(candidates, index + 1, deadline, callback);
                    return;
                }
                rememberFallback(bean, categories);
                // 有类目不等于有内容：配置中心、网盘站也能列出十几个类目，
                // 但里面是「网页版配置中心」这种条目。真拉一条列表验证过再用。
                probePlayable(bean, categories, new ProbeCallback() {
                    @Override
                    public void onProbe(boolean playable) {
                        if (playable) {
                            resolvedSource = bean;
                            android.util.Log.i(TAG, "用源：" + bean.getName() + " 类目 " + categories.size() + " 个");
                            postCategories(callback, categories);
                            return;
                        }
                        android.util.Log.i(TAG, "内容不可播，跳过：" + bean.getName());
                        tryCategories(candidates, index + 1, deadline, callback);
                    }
                });
            }

            @Override
            public void onError(Throwable th) {
                android.util.Log.i(TAG, "源取类目失败，跳过：" + bean.getName() + " " + th);
                tryCategories(candidates, index + 1, deadline, callback);
            }
        });
    }

    private interface ProbeCallback {
        void onProbe(boolean playable);
    }

    /**
     * 拉一条真实列表，判断这个源能不能给出可播内容。
     *
     * <p>只看第一个类目的第一页，够用了：配置中心/网盘站的内容条目是「图标 + 短 id」，
     * 图标是内嵌的 base64，影视源的海报一定是网络地址；豆瓣壳的 id 是 {@code msearch:}。
     *
     * <p>判据刻意留宽：宁可放过一个可疑的源，也不要因为某个源列表没带海报就把
     * 所有候选都否掉——那样顶栏会直接空掉。
     */
    private static void probePlayable(final SourceBean bean, final List<MovieSort.SortData> categories,
                                      final ProbeCallback callback) {
        if (bean.getType() != 3 || categories.isEmpty()) {
            postProbe(callback, true);
            return;
        }
        final MovieSort.SortData sort = categories.get(0);
        SPIDER_POOL.execute(new Runnable() {
            @Override
            public void run() {
                boolean playable;
                try {
                    String json = callWithTimeout(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider spider = ApiConfig.get().getCSP(bean);
                            return spider == null ? null
                                    : spider.categoryContent(sort.id, "1", true, sort.filterSelect);
                        }
                    });
                    playable = looksPlayable(parseJson(json));
                } catch (Throwable th) {
                    playable = false;
                }
                postProbe(callback, playable);
            }
        });
    }

    private static void postProbe(final ProbeCallback callback, final boolean playable) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onProbe(playable);
            }
        });
    }

    private static boolean looksPlayable(AbsXml absXml) {
        if (absXml == null || absXml.movie == null || absXml.movie.videoList == null
                || absXml.movie.videoList.isEmpty()) {
            return false;
        }
        boolean hasPic = false;
        boolean hasNetworkPic = false;
        for (Movie.Video vod : absXml.movie.videoList) {
            if (vod == null || TextUtils.isEmpty(vod.name)) {
                continue;
            }
            // 豆瓣条目：点进去没有可播线路
            if (vod.id != null && vod.id.startsWith("msearch:")) {
                return false;
            }
            if (!TextUtils.isEmpty(vod.pic)) {
                hasPic = true;
                if (vod.pic.startsWith("http")) {
                    hasNetworkPic = true;
                }
            }
        }
        // 有图但一张网络图都没有 → 配置中心那类内嵌图标的站点
        return !hasPic || hasNetworkPic;
    }

    /**
     * 候选源：首页源排第一，后面按配置顺序补，最多 {@link #MAX_CANDIDATES} 个。
     *
     * <p>跳过 type -1（推送）和名字以「搜」结尾的站点——它们本来就不提供内容。
     *
     * <p>豆瓣壳站点（见 {@link #isMetaOnly}）排在最后：它们能给出类目，但内容
     * 点进去播不了，只适合兜底，不适合当顶栏的数据源。
     */
    private static List<SourceBean> candidateSources() {
        List<SourceBean> content = new ArrayList<>();
        List<SourceBean> metaOnly = new ArrayList<>();
        try {
            // 上次已经验证过能出类目的源排第一。不然每次回桌面都要把前面
            // 一堆没内容的站点重扫一遍，等爬虫 jar 挂载，顶栏要空十几秒。
            if (isUsable(resolvedSource)) {
                addCandidate(content, metaOnly, resolvedSource);
            }
            SourceBean home = ApiConfig.get().getHomeSourceBean();
            if (isUsable(home)) {
                addCandidate(content, metaOnly, home);
            }
            List<SourceBean> all = ApiConfig.get().getSourceBeanList();
            if (all != null) {
                for (SourceBean bean : all) {
                    if (content.size() >= MAX_CANDIDATES) {
                        break;
                    }
                    if (!isUsable(bean)) {
                        continue;
                    }
                    addCandidate(content, metaOnly, bean);
                }
            }
        } catch (Throwable th) {
            android.util.Log.w(TAG, "candidateSources failed", th);
        }
        // 真内容源在前，豆瓣壳垫底：配置里如果只有豆瓣站，顶栏也不至于空着
        List<SourceBean> result = new ArrayList<>(content);
        result.addAll(metaOnly);
        android.util.Log.i(TAG, "候选源：内容源 " + content.size() + " 个，豆瓣壳 " + metaOnly.size() + " 个");
        return result;
    }

    private static void addCandidate(List<SourceBean> content, List<SourceBean> metaOnly, SourceBean bean) {
        List<SourceBean> target = isMetaOnly(bean) ? metaOnly : content;
        if (!target.contains(bean)) {
            target.add(bean);
        }
    }

    /**
     * 判断一个站点是不是「豆瓣壳」。
     *
     * <p>这类站点（配置里常见 {@code csp_Douban}/{@code csp_NewDouBanGuard} 这类
     * 爬虫类）只提供豆瓣元数据：片名、海报、评分齐全，但内容 id 一律是
     * {@code msearch:xxx}，进详情页没有可播线路。它们的价值是给首页推荐流供图，
     * 当顶栏类目源就是「海报能看、点进去没数据」。
     *
     * <p>判据取爬虫类名和站点 key，两者都是配置作者显式写的，比猜站点名可靠。
     */
    private static boolean isMetaOnly(SourceBean bean) {
        if (bean == null) {
            return false;
        }
        String api = bean.getApi();
        if (api != null && api.toLowerCase(Locale.ROOT).contains("douban")) {
            return true;
        }
        String key = bean.getKey();
        return key != null && key.toLowerCase(Locale.ROOT).startsWith("douban");
    }

    private static boolean isUsable(SourceBean bean) {
        if (bean == null || TextUtils.isEmpty(bean.getKey())) {
            return false;
        }
        int type = bean.getType();
        if (type != 0 && type != 1 && type != 3) {
            return false;
        }
        String name = bean.getName();
        return !(name != null && name.length() <= 3 && name.endsWith("搜"));
    }

    private static void fetchCategories(final SourceBean bean, final CategoryCallback callback) {
        final int type = bean.getType();
        android.util.Log.i(TAG, "fetchCategories: " + bean.getName() + " type=" + type);

        if (type == 3) {
            SPIDER_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    String json;
                    try {
                        json = callWithTimeout(new Callable<String>() {
                            @Override
                            public String call() throws Exception {
                                Spider spider = ApiConfig.get().getCSP(bean);
                                return spider == null ? null : spider.homeContent(true);
                            }
                        });
                    } catch (Throwable th) {
                        postError(callback, th);
                        return;
                    }
                    postCategories(callback, categoriesOf(parseSortJson(json)));
                }
            });
            return;
        }

        if (TextUtils.isEmpty(bean.getApi())) {
            postCategories(callback, new ArrayList<MovieSort.SortData>());
            return;
        }

        OkGo.<String>get(bean.getApi())
                .tag(bean.getKey() + "_desk_sort")
                .execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body() == null ? "" : response.body().string();
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        AbsSortXml sortXml = type == 0
                                ? parseSortXml(response.body())
                                : parseSortJson(response.body());
                        postCategories(callback, categoriesOf(sortXml));
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        postError(callback, response.getException());
                    }
                });
    }

    // ---------------------------------------------------------------- 内容

    /**
     * 取某个类目的内容列表。回调在主线程。
     *
     * @param sort 类目，来自 {@link #loadCategories}
     * @param page 从 1 开始
     */
    public static void loadList(final MovieSort.SortData sort, final int page,
                                final ListCallback callback) {
        if (sort == null || TextUtils.isEmpty(sort.id)) {
            postList(callback, new ArrayList<Movie.Video>());
            return;
        }
        final SourceBean bean = resolvedSource != null
                ? resolvedSource : ApiConfig.get().getHomeSourceBean();
        if (bean == null || TextUtils.isEmpty(bean.getKey())) {
            postList(callback, new ArrayList<Movie.Video>());
            return;
        }
        final int type = bean.getType();

        if (type == 3) {
            SPIDER_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    String json;
                    try {
                        json = callWithTimeout(new Callable<String>() {
                            @Override
                            public String call() throws Exception {
                                Spider spider = ApiConfig.get().getCSP(bean);
                                if (spider == null) {
                                    return null;
                                }
                                return spider.categoryContent(sort.id, String.valueOf(page),
                                        true, sort.filterSelect);
                            }
                        });
                    } catch (Throwable th) {
                        postError(callback, th);
                        return;
                    }
                    android.util.Log.i(TAG, "categoryContent spider sort=" + sort.id
                            + " json=" + (json == null ? "null" : json.length() + " chars: "
                            + json.substring(0, Math.min(120, json.length()))));
                    postList(callback, videosOf(parseJson(json), bean.getKey()));
                }
            });
            return;
        }

        if (TextUtils.isEmpty(bean.getApi())) {
            postList(callback, new ArrayList<Movie.Video>());
            return;
        }

        OkGo.<String>get(bean.getApi())
                .tag(bean.getKey() + "_desk_list")
                .params("ac", type == 0 ? "videolist" : "detail")
                .params("t", sort.id)
                .params("pg", page)
                .execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body() == null ? "" : response.body().string();
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        List<Movie.Video> videos = type == 0
                                ? videosOf(parseXml(response.body()), bean.getKey())
                                : videosOf(parseJson(response.body()), bean.getKey());
                        postList(callback, videos);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        postError(callback, response.getException());
                    }
                });
    }

    // ---------------------------------------------------------------- 高清背景

    /**
     * 横版剧照缓存。
     *
     * <p>值可能是空串——那是「问过了，这个片子没有横版剧照」的标记。
     * 用空串而不是缺 key，是为了不再对着同一个 id 反复打空请求。
     */
    private static final LruCache<String, String> SLIDE_CACHE = new LruCache<>(64);

    /** 缓存会被主线程（读）和爬虫线程（写）同时碰到，统一走这两个方法加锁 */
    private static final Object SLIDE_LOCK = new Object();

    private static String peekSlide(String key) {
        synchronized (SLIDE_LOCK) {
            return SLIDE_CACHE.get(key);
        }
    }

    private static void putSlide(String key, String url) {
        synchronized (SLIDE_LOCK) {
            SLIDE_CACHE.put(key, url == null ? "" : url);
        }
    }

    public interface BackdropCallback {
        /** @param url 横版剧照地址；没有则为 null */
        void onBackdrop(String url);
    }

    /**
     * 取一条片目的高清背景图。
     *
     * <p>列表接口（{@code ac=videolist}）通常只返回竖版海报 {@code vod_pic}，
     * 而桌面 Hero 要铺满 1920×1080，拿竖图裁成 16:9 再放大必然发虚。
     * 源站的详情接口（{@code ac=detail&ids=}）里一般另有一张 {@code vod_pic_slide}——
     * 本来就是横版剧照，比例接近 16:9，铺满不用裁剪。
     *
     * <p>所以这里走「列表先用竖图顶上，随后补拉横图替换」的路子：
     * 首屏不用等详情请求，横图到了再换，用户感知不到延迟。
     * 结果进 {@link #SLIDE_CACHE}，来回切类目不会重复请求。
     *
     * <p>回调在主线程。
     */
    public static void loadBackdrop(final Movie.Video vod, final BackdropCallback callback) {
        if (vod == null || TextUtils.isEmpty(vod.id) || TextUtils.isEmpty(vod.sourceKey)) {
            postBackdrop(callback, null);
            return;
        }
        // 列表接口已经带了横图，直接用
        if (!TextUtils.isEmpty(vod.picSlide)) {
            postBackdrop(callback, vod.picSlide);
            return;
        }

        final String cacheKey = vod.sourceKey + "|" + vod.id;
        String cached = peekSlide(cacheKey);
        if (cached != null) {
            postBackdrop(callback, cached.isEmpty() ? null : cached);
            return;
        }

        final SourceBean bean = ApiConfig.get().getSource(vod.sourceKey);
        if (bean == null) {
            postBackdrop(callback, null);
            return;
        }
        final int type = bean.getType();
        android.util.Log.i(TAG, "列表未带横图，去详情补拉：" + vod.name);

        if (type == 3) {
            SPIDER_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    String json = null;
                    try {
                        json = callWithTimeout(new Callable<String>() {
                            @Override
                            public String call() {
                                Spider spider = ApiConfig.get().getCSP(bean);
                                if (spider == null) {
                                    return null;
                                }
                                List<String> ids = new ArrayList<>(1);
                                ids.add(vod.id);
                                return spider.detailContent(ids);
                            }
                        });
                    } catch (Throwable th) {
                        android.util.Log.w(TAG, "补拉横图失败 " + vod.name, th);
                    }
                    finishSlide(cacheKey, slideOf(parseJson(json)), callback);
                }
            });
            return;
        }

        if (TextUtils.isEmpty(bean.getApi())) {
            postBackdrop(callback, null);
            return;
        }

        OkGo.<String>get(bean.getApi())
                .tag(bean.getKey() + "_desk_slide")
                .params("ac", "detail")
                .params("ids", vod.id)
                .execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body() == null ? "" : response.body().string();
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        AbsXml absXml = type == 0
                                ? parseXml(response.body())
                                : parseJson(response.body());
                        finishSlide(cacheKey, slideOf(absXml), callback);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        finishSlide(cacheKey, null, callback);
                    }
                });
    }

    private static void finishSlide(String cacheKey, String slide, BackdropCallback callback) {
        putSlide(cacheKey, slide);
        postBackdrop(callback, slide);
    }

    private static String slideOf(AbsXml absXml) {
        if (absXml == null || absXml.movie == null || absXml.movie.videoList == null) {
            return null;
        }
        for (Movie.Video vod : absXml.movie.videoList) {
            if (vod != null && !TextUtils.isEmpty(vod.picSlide)) {
                return vod.picSlide;
            }
        }
        return null;
    }

    private static void postBackdrop(final BackdropCallback cb, final String url) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onBackdrop(url);
            }
        });
    }

    // ---------------------------------------------------------------- 解析

    private static AbsSortXml parseSortJson(String json) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            AbsSortJson sortJson = new Gson().fromJson(
                    JsonParser.parseString(json).getAsJsonObject(),
                    new TypeToken<AbsSortJson>() {
                    }.getType());
            return sortJson == null ? null : sortJson.toAbsSortXml();
        } catch (Throwable th) {
            return null;
        }
    }

    private static AbsSortXml parseSortXml(String xml) {
        if (TextUtils.isEmpty(xml)) {
            return null;
        }
        try {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            return (AbsSortXml) xstream.fromXML(xml);
        } catch (Throwable th) {
            return null;
        }
    }

    static AbsXml parseJson(String json) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            AbsJson absJson = new Gson().fromJson(json, AbsJson.class);
            if (absJson == null || absJson.list == null) {
                return null;
            }
            return absJson.toAbsXml();
        } catch (Throwable th) {
            return null;
        }
    }

    static AbsXml parseXml(String xml) {
        if (TextUtils.isEmpty(xml)) {
            return null;
        }
        try {
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            return (AbsXml) xstream.fromXML(xml);
        } catch (Throwable th) {
            return null;
        }
    }

    private static List<MovieSort.SortData> categoriesOf(AbsSortXml sortXml) {
        List<MovieSort.SortData> result = new ArrayList<>();
        if (sortXml == null || sortXml.classes == null || sortXml.classes.sortList == null) {
            return result;
        }
        for (MovieSort.SortData sort : sortXml.classes.sortList) {
            if (sort == null || TextUtils.isEmpty(sort.id) || TextUtils.isEmpty(sort.name)) {
                continue;
            }
            sort.name = sort.name.trim();
            result.add(sort);
        }
        return result;
    }

    static List<Movie.Video> videosOf(AbsXml absXml, String sourceKey) {
        List<Movie.Video> result = new ArrayList<>();
        if (absXml == null || absXml.movie == null || absXml.movie.videoList == null) {
            return result;
        }
        for (Movie.Video vod : absXml.movie.videoList) {
            if (vod == null || TextUtils.isEmpty(vod.name)) {
                continue;
            }
            // 补上 sourceKey，桌面点海报才能直接进详情页而不是退到搜索
            if (TextUtils.isEmpty(vod.sourceKey)) {
                vod.sourceKey = sourceKey;
            }
            result.add(vod);
        }
        return result;
    }

    // ---------------------------------------------------------------- 线程

    static String callWithTimeout(Callable<String> task) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(task);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } finally {
            executor.shutdown();
        }
    }

    private static void postCategories(final CategoryCallback cb, final List<MovieSort.SortData> list) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onCategories(list);
            }
        });
    }

    private static void postList(final ListCallback cb, final List<Movie.Video> list) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onList(list);
            }
        });
    }

    private static void postError(final Object cb, final Throwable th) {
        final Throwable error = th != null ? th : new RuntimeException("source request failed");
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (cb instanceof CategoryCallback) {
                    ((CategoryCallback) cb).onError(error);
                } else if (cb instanceof ListCallback) {
                    ((ListCallback) cb).onError(error);
                }
            }
        });
    }
}

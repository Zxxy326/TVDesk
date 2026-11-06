package com.tvdesk.core.bg;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.tvdesk.core.feed.DetailFeed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 影视条目的背景视频：从片源里要一集的可播地址。
 *
 * <p>和 B 站那条路径的差别在于**没有现成的播放地址**。B 站热门卡片自带 bvid，
 * 一次 playurl 就有流；影视条目只有 {@code sourceKey + id}，必须先过详情接口
 * 拿到剧集列表，再按片源的类型决定要不要让爬虫解析一遍。
 *
 * <p>这整条链路是「尽力而为」：B 站的脚本大部分能直接播，影视源那边慢的、要解析的、
 * 网盘推送的都可能拿不到地址。拿不到就返回 null，调用方继续显示静态图——
 * 背景墙不该因为某条影视取不到流就停下来。
 */
public final class MovieBackdropStream {

    private static final String TAG = "MovieBackdrop";

    /** 解析放到独立线程：详情回调可能在主线程，爬虫调用绝不能占主线程 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    /**
     * 已经解析出来的地址。
     *
     * <p>影视这条链路的瓶颈是「详情 + 解析」要好几秒，而一条背景只停 8 秒——
     * 等到轮播到它再开始解析，基本都来不及。所以切走前就先解析下一条（见
     * {@link #prefetch}），轮到时直接命中。LRU 24 条够放两轮。
     */
    private static final Map<String, String> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 24;
                }
            });

    /** 正在解析的 key，避免同一条被预取和即时请求各解一遍 */
    private static final Set<String> PENDING = Collections.synchronizedSet(new HashSet<String>());

    /**
     * 每个片源连续失败几次了。
     *
     * <p>网盘类的源会返回 {@code 127.0.0.1} 的本地代理地址，未登录时上游一律 403。
     * 这种源源源不断地失败，每条都要白等 6 秒的首帧超时。连续失败 {@link #MAX_FAILS}
     * 次之后这个源就不再尝试，直到进程重启（换源会换 sourceKey，自然重新开始）。
     */
    private static final Map<String, Integer> FAILS =
            Collections.synchronizedMap(new LinkedHashMap<String, Integer>());
    private static final int MAX_FAILS = 3;

    /** 一条背景最多试几个源：再多也来不及在十秒里出画面 */
    private static final int MAX_SOURCES_PER_ITEM = 4;

    /** 单次详情请求最多等多久 */
    private static final int DETAIL_TIMEOUT_SEC = 12;

    /**
     * 影视背景连续失败几次就不再试。
     *
     * <p>这套配置里 91 个源全是 type=3 的网盘聚合，搜索一次要十几秒、多半还回 HTML
     * 错误页。连着几条都拿不到就说明「当前配置给不了直连流」，再试下去只是白耗流量
     * 和线程；进程重启或换配置（重启生效）才会重新开始。
     */
    private static final int GIVE_UP_AFTER = 3;

    /** 单个源最多等多久：网盘源的搜索经常十几秒不回，等不起 */
    private static final int SOURCE_TIMEOUT_SEC = 8;

    /** 试源单独一组线程：卡住的搜索不能把预取队列堵死 */
    private static final ExecutorService SOURCE_POOL = Executors.newFixedThreadPool(3);
    private static int consecutiveFails = 0;

    public interface Callback {
        /** @param url 可播地址；null 表示这条拿不到 */
        void onUrl(String url);
    }

    private MovieBackdropStream() {
    }

    /**
     * 缓存键。
     *
     * <p>豆瓣条目没有源站 id，只能用片名做键——否则它们全都会落到同一个 ":" 上，
     * 后一条把前一条的地址覆盖掉，播出来就是「标题是这部、画面是那部」。
     */
    private static String keyOf(Movie.Video vod) {
        if (vod == null) {
            return "";
        }
        String source = vod.sourceKey == null ? "" : vod.sourceKey.trim();
        String id = vod.id == null ? "" : vod.id.trim();
        if (source.isEmpty() || id.isEmpty()) {
            String name = vod.name == null ? "" : vod.name.trim();
            return name.isEmpty() ? "" : "name:" + name;
        }
        return source + ":" + id;
    }

    /** 这个源是不是已经连续失败太多次了 */
    private static boolean tooManyFails(String sourceKey) {
        if (TextUtils.isEmpty(sourceKey)) {
            return false;
        }
        Integer count = FAILS.get(sourceKey);
        return count != null && count >= MAX_FAILS;
    }

    /**
     * 播放阶段失败时由调用方回报。
     *
     * <p>解析成功但播放报错（403 / 格式不支持），同样说明这个源给不了背景流。
     */
    public static void markFailed(Movie.Video vod) {
        if (vod == null || TextUtils.isEmpty(vod.sourceKey)) {
            return;
        }
        Integer count = FAILS.get(vod.sourceKey);
        int next = count == null ? 1 : count + 1;
        FAILS.put(vod.sourceKey, next);
        if (next == MAX_FAILS) {
            Log.i(TAG, "片源 " + vod.sourceKey + " 连续 " + next + " 次拿不到背景流，后续跳过");
        }
    }

    /** 已经解析好的地址；没有返回 null */
    public static String cached(Movie.Video vod) {
        return CACHE.get(keyOf(vod));
    }

    /**
     * 提前解析一条。轮播切到下一条之前调用，轮到它时就能直接起播。
     *
     * <p>失败不重试：一条背景最多试一次，拿不到就只看静态图，不做重试风暴。
     */
    public static void prefetch(Context context, Movie.Video vod) {
        final String key = keyOf(vod);
        if (key.isEmpty() || CACHE.containsKey(key) || !PENDING.add(key)) {
            return;
        }
        resolve(context, vod, new Callback() {
            @Override
            public void onUrl(String url) {
                PENDING.remove(key);
                if (!TextUtils.isEmpty(url)) {
                    CACHE.put(key, url);
                }
            }
        });
    }

    public static void resolve(Context context, final Movie.Video vod, final Callback callback) {
        if (vod == null) {
            callback.onUrl(null);
            return;
        }
        String ready = cached(vod);
        if (!TextUtils.isEmpty(ready)) {
            callback.onUrl(ready);
            return;
        }
        if (TextUtils.isEmpty(vod.name) && TextUtils.isEmpty(vod.id)) {
            callback.onUrl(null);
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                String url = null;
                try {
                    url = resolveBlocking(vod);
                } catch (Throwable th) {
                    Log.i(TAG, "取背景流失败：" + th.getMessage());
                }
                synchronized (MovieBackdropStream.class) {
                    if (TextUtils.isEmpty(url)) {
                        consecutiveFails++;
                        if (consecutiveFails == GIVE_UP_AFTER) {
                            Log.i(TAG, "连续 " + GIVE_UP_AFTER + " 条拿不到直连流，"
                                    + "本次运行不再尝试影视背景视频（换配置后重启生效）");
                        }
                    } else {
                        consecutiveFails = 0;
                    }
                }
                callback.onUrl(url);
            }
        });
    }

    /**
     * 找一个能**直连播放**的地址。
     *
     * <p>先按条目自己的源试。如果拿到的是 {@code 127.0.0.1}（TVBox 的本地转发服务），
     * 说明这个源是网盘类，未登录时上游一律 403 —— 拿到也播不了，于是按片名去配置里
     * 其它源挨个试，谁给得出真实 http 直链就用谁。这样就**不用换内容源**。
     *
     * <p>为什么要排除 127.0.0.1：实测网盘源的地址全是本地代理，播放直接
     * {@code ERROR_CODE_IO_BAD_HTTP_STATUS}（上游 403），白等 6 秒首帧超时。
     */
    private static String resolveBlocking(Movie.Video vod) {
        if (consecutiveFails >= GIVE_UP_AFTER) {
            return null;
        }
        String name = vod.name == null ? "" : vod.name.trim();
        if (!TextUtils.isEmpty(vod.sourceKey) && !TextUtils.isEmpty(vod.id)
                && !tooManyFails(vod.sourceKey)) {
            String url = fromSource(vod.sourceKey, vod.id);
            if (isDirect(url)) {
                return url;
            }
            if (!TextUtils.isEmpty(url)) {
                Log.i(TAG, "源 " + vod.sourceKey + " 只给本地代理，换源试：" + name);
            }
        }
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        List<SourceBean> sources = ApiConfig.get().getSearchSourceBeanList();
        Log.i(TAG, "按片名找直连流：" + name + "，候选 " + (sources == null ? 0 : sources.size()) + " 个源");
        if (sources == null) {
            return null;
        }
        int tried = 0;
        for (SourceBean bean : sources) {
            if (bean == null || tooManyFails(bean.getKey())) {
                continue;
            }
            // 不过滤 type：现在这套配置 98 个源全是 type=3，过滤掉就没源可问了。
            // 网盘源大多给的是本地代理，但也有个别直接给 CDN 直链，挨个试才知道
            if (tried >= MAX_SOURCES_PER_ITEM) {
                break;
            }
            tried++;
            String url = trySource(bean, name);
            if (isDirect(url)) {
                Log.i(TAG, "用源 " + bean.getKey() + " 取到直连背景流：" + name);
                return url;
            }
        }
        return null;
    }

    /**
     * 在一个源里「搜索 + 详情」一次，带超时。
     *
     * <p>spider 的搜索是同步阻塞且不可中断的，直接调会把线程池占满：实测某个源
     * 18 秒才失败（还回的是 HTML 错误页）。这里丢进小线程池 + 8 秒上限，
     * 超时就放弃这个源，后台那次请求自己跑完就算了。
     */
    private static String trySource(final SourceBean bean, final String want) {
        Future<String> future = SOURCE_POOL.submit(new Callable<String>() {
            @Override
            public String call() {
                String id = searchId(bean, want);
                if (TextUtils.isEmpty(id)) {
                    return null;
                }
                return fromSource(bean.getKey(), id);
            }
        });
        try {
            return future.get(SOURCE_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Throwable th) {
            future.cancel(true);
            Log.i(TAG, "源 " + bean.getKey() + " 超时/失败，跳过");
            return null;
        }
    }

    /**
     * 在一个源里按片名找条目 id。
     *
     * <p>片名严格相等才认：搜「庆余年」很容易回「庆余年2」「庆余年 特别版」，
     * 拿错一条的结果是「标题是这部、画面是那部」。
     */
    private static String searchId(SourceBean bean, String want) {
        try {
            Spider spider = ApiConfig.get().getCSP(bean);
            if (spider == null) {
                return null;
            }
            String json = spider.searchContent(want, false);
            if (TextUtils.isEmpty(json)) {
                return null;
            }
            JSONArray list = new JSONObject(json).optJSONArray("list");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                if (want.equals(item.optString("vod_name", "").trim())) {
                    return item.optString("vod_id", item.optString("id", ""));
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "搜索失败 " + bean.getKey() + "：" + th.getMessage());
        }
        return null;
    }

    private static String fromSource(String sourceKey, String id) {
        VodInfo info = loadDetailBlocking(sourceKey, id);
        if (info == null) {
            return null;
        }
        return firstEpisode(info);
    }

    /**
     * 同步等详情回包。
     *
     * <p>这里已经在线程池里了，但 {@code DetailFeed} 只有回调式接口，所以用 CountDownLatch
     * 把它拧成同步——多源挨个试的场景下，回调套回调会写成五层。
     */
    private static VodInfo loadDetailBlocking(final String sourceKey, final String id) {
        final CountDownLatch latch = new CountDownLatch(1);
        final VodInfo[] holder = new VodInfo[1];
        DetailFeed.loadDetail(sourceKey, id, new DetailFeed.DetailCallback() {
            @Override
            public void onDetail(VodInfo info) {
                holder[0] = info;
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                latch.countDown();
            }
        });
        try {
            latch.await(DETAIL_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return holder[0];
    }

    /** 本地转发 / 内网地址都不算「直连」 */
    private static boolean isDirect(String url) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            return false;
        }
        String host = hostOf(url);
        return !(host.equals("127.0.0.1") || host.equals("localhost")
                || host.startsWith("192.168.") || host.startsWith("10.")
                || host.startsWith("172.16."));
    }

    private static String hostOf(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 第一条线路的第一集。
     *
     * <p>为什么挑第一集而不是随机一集：片源的剧集顺序不一定按集数排（有的源倒序、
     * 有的把预告放在最前），拿「列表第 0 条」至少和用户点开详情后看到的第一个一致，
     * 不会出现「背景在播第 37 集」这种事。
     */
    private static String firstEpisode(VodInfo info) {
        if (info.seriesMap == null || info.seriesMap.isEmpty()) {
            return null;
        }
        String flag = null;
        VodInfo.VodSeries first = null;
        for (Map.Entry<String, List<VodInfo.VodSeries>> entry : info.seriesMap.entrySet()) {
            List<VodInfo.VodSeries> list = entry.getValue();
            if (list != null && !list.isEmpty() && !TextUtils.isEmpty(list.get(0).url)) {
                flag = entry.getKey();
                first = list.get(0);
                break;
            }
        }
        if (first == null) {
            return null;
        }
        String url = first.url.trim();
        if (url.startsWith("http")) {
            // 已经是直链（常见的 m3u8 / mp4）
            return url;
        }
        return parse(info.sourceKey, flag, url);
    }

    /**
     * 让爬虫解析一次。
     *
     * <p>TVBox 的约定：{@code playerContent(flag, id, vipFlags)} 返回一段 JSON，
     * 里面 {@code url} 才是真正的播放地址，有时还带 {@code header}。
     * 背景只要画面，不合并音轨也不带自定义头，所以这里只取 url。
     */
    private static String parse(String sourceKey, String flag, String id) {
        try {
            SourceBean bean = ApiConfig.get().getSource(sourceKey);
            if (bean == null) {
                return null;
            }
            Spider spider = ApiConfig.get().getCSP(bean);
            if (spider == null) {
                return null;
            }
            String json = spider.playerContent(flag, id, ApiConfig.get().getVipParseFlags());
            if (TextUtils.isEmpty(json)) {
                return null;
            }
            String url = new JSONObject(json).optString("url", "");
            return url.startsWith("http") ? url : null;
        } catch (Throwable th) {
            // 网盘类的源会在这里抛（它们要的是推送，不是直链），当成「这条没有视频」
            Log.i(TAG, "解析失败：" + th.getMessage());
            return null;
        }
    }
}

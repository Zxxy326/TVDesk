package com.tvdesk.core.feed;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 详情与搜索的数据层。
 *
 * <p>影视页内嵌进桌面之后，详情页和搜索页也在同一个窗口里，不能再去用 app 模块的
 * {@code SourceViewModel}（desk 依赖不到 app）。这里把同一条链路的解析搬过来：
 * 详情拿 {@code Movie.Video} 再转成播放器认的 {@link VodInfo}，搜索按源并发、
 * 有结果就先回一批。
 *
 * <h3>选集为什么要自己拆</h3>
 * CMS 的详情接口给的是 {@code vod_play_url="第1集$http://... #第2集$..."}。
 * XML 源经 XStream 能拆出 {@code beanList}，但 JSON 源（{@code AbsJson}）只填了
 * {@code urls} 原文，{@code VodInfo.setVideo} 又只认 {@code beanList}——
 * 直接用它会出现「详情页有线路、点播放没选集」。所以这里统一按 {@code #}/{@code $}
 * 自己拆一遍，两种源走同一条路。
 */
public final class DetailFeed {

    private static final String TAG = "DetailFeed";

    /** 爬虫调用放到独立线程：详情/搜索不该把 SourceFeed 的类目列表请求堵住 */
    private static final ExecutorService DETAIL_POOL = Executors.newSingleThreadExecutor();

    /**
     * 搜索单独一组线程。详情和搜索共用一个线程池时，搜索刚发起就点结果，
     * 详情会排在这批搜索后面，页面干转圈好几分钟。
     */
    private static final ExecutorService SEARCH_POOL = Executors.newFixedThreadPool(4);

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 聚合搜索的总预算。超时后已到的结果照常显示，只是不再等剩下的源 */
    private static final long SEARCH_BUDGET_MS = 15000L;

    /** 一次最多问几个搜索源，太多会把预算都耗在排队的慢源上 */
    private static final int MAX_SEARCH_SOURCES = 18;

    private static final AtomicInteger SEARCH_SEQ = new AtomicInteger();

    private DetailFeed() {
    }

    public interface DetailCallback {
        void onDetail(VodInfo info);

        void onError(String message);
    }

    public interface SearchCallback {
        /**
         * 某个源返回了一批结果。
         *
         * @param videos 已经按 id 去重、过滤过豆瓣壳条目的列表，可能为空（空批次不回调）
         */
        void onBatch(String sourceKey, String sourceName, List<Movie.Video> videos);

        /** 全部源都回了，或总预算用完。只调一次 */
        void onDone();
    }

    // ---------------------------------------------------------------- 详情

    public static void loadDetail(Movie.Video seed, DetailCallback callback) {
        if (seed == null || TextUtils.isEmpty(seed.sourceKey) || TextUtils.isEmpty(seed.id)) {
            postDetailError(callback, "这条内容没有可用的片源");
            return;
        }
        loadDetail(seed.sourceKey, seed.id, callback);
    }

    public static void loadDetail(final String sourceKey, final String id, final DetailCallback callback) {
        final SourceBean bean = ApiConfig.get().getSource(sourceKey);
        if (bean == null) {
            postDetailError(callback, "片源已不在配置里，换一个源试试");
            return;
        }
        if (TextUtils.isEmpty(id)) {
            postDetailError(callback, "缺少内容 id");
            return;
        }

        final int type = bean.getType();
        if (type == 3) {
            DETAIL_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    String json = null;
                    try {
                        json = SourceFeed.callWithTimeout(new Callable<String>() {
                            @Override
                            public String call() {
                                Spider spider = ApiConfig.get().getCSP(bean);
                                if (spider == null) {
                                    return null;
                                }
                                List<String> ids = new ArrayList<>(1);
                                ids.add(id);
                                return spider.detailContent(ids);
                            }
                        });
                    } catch (Throwable th) {
                        android.util.Log.w(TAG, "detail spider failed: " + bean.getName(), th);
                    }
                    finishDetail(SourceFeed.parseJson(json), sourceKey, callback);
                }
            });
            return;
        }

        if (TextUtils.isEmpty(bean.getApi())) {
            postDetailError(callback, "这个片源没有配置接口地址");
            return;
        }

        com.lzy.okgo.request.GetRequest<String> request = OkGo.<String>get(bean.getApi())
                .tag("desk_detail")
                .params("ac", type == 0 ? "videolist" : "detail")
                .params("ids", id);
        String extend = bean.getExt();
        if (!TextUtils.isEmpty(extend)) {
            request.params("extend", extend.trim());
        }
        request.execute(new AbsCallback<String>() {
            @Override
            public String convertResponse(okhttp3.Response response) throws Throwable {
                return response.body() == null ? "" : response.body().string();
            }

            @Override
            public void onSuccess(Response<String> response) {
                AbsXml absXml = type == 0
                        ? SourceFeed.parseXml(response.body())
                        : SourceFeed.parseJson(response.body());
                finishDetail(absXml, sourceKey, callback);
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
                postDetailError(callback, "详情加载失败：" + describe(response.getException()));
            }
        });
    }

    private static void finishDetail(AbsXml absXml, String sourceKey, DetailCallback callback) {
        List<Movie.Video> videos = SourceFeed.videosOf(absXml, sourceKey);
        if (videos.isEmpty()) {
            postDetailError(callback, "这个源没返回详情，可能片子已下架");
            return;
        }
        VodInfo info = toVodInfo(videos.get(0), sourceKey);
        if (info == null || info.seriesMap == null || info.seriesMap.isEmpty()) {
            postDetailError(callback, "取到了详情，但没有可播放的线路");
            return;
        }
        postDetail(callback, info);
    }

    /**
     * {@link Movie.Video} → {@link VodInfo}，顺带把线路原文拆成选集。
     *
     * @return 没有线路时返回 null
     */
    static VodInfo toVodInfo(Movie.Video video, String sourceKey) {
        if (video == null) {
            return null;
        }
        VodInfo info = new VodInfo();
        info.last = video.last;
        info.id = video.id;
        info.tid = video.tid;
        info.name = video.name;
        info.type = video.type;
        info.pic = video.pic;
        info.lang = video.lang;
        info.area = video.area;
        info.year = video.year;
        info.state = video.state;
        info.note = video.note;
        info.actor = video.actor;
        info.director = video.director;
        info.des = video.des;
        info.sourceKey = TextUtils.isEmpty(video.sourceKey) ? sourceKey : video.sourceKey;

        LinkedHashMap<String, List<VodInfo.VodSeries>> seriesMap = new LinkedHashMap<>();
        ArrayList<VodInfo.VodSeriesFlag> flags = new ArrayList<>();
        if (video.urlBean != null && video.urlBean.infoList != null) {
            for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                if (urlInfo == null || TextUtils.isEmpty(urlInfo.flag)) {
                    continue;
                }
                String flag = urlInfo.flag.trim();
                if (seriesMap.containsKey(flag)) {
                    continue;
                }
                List<VodInfo.VodSeries> series = new ArrayList<>();
                if (urlInfo.beanList != null && !urlInfo.beanList.isEmpty()) {
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean bean : urlInfo.beanList) {
                        if (bean != null && !TextUtils.isEmpty(bean.url)) {
                            series.add(new VodInfo.VodSeries(bean.name, bean.url));
                        }
                    }
                } else {
                    parseSeries(urlInfo.urls, series);
                }
                if (series.isEmpty()) {
                    continue;
                }
                seriesMap.put(flag, series);
                flags.add(new VodInfo.VodSeriesFlag(flag));
            }
        }
        if (seriesMap.isEmpty()) {
            return null;
        }

        info.seriesMap = seriesMap;
        info.seriesFlags = flags;
        info.playFlag = flags.get(0).name;
        info.playIndex = 0;
        return info;
    }

    /** 把 {@code 第1集$url#第2集$url} 拆成选集；没有名字的按序号补 */
    private static void parseSeries(String urls, List<VodInfo.VodSeries> out) {
        if (TextUtils.isEmpty(urls)) {
            return;
        }
        String[] episodes = urls.split("#");
        for (int i = 0; i < episodes.length; i++) {
            String episode = episodes[i];
            if (TextUtils.isEmpty(episode)) {
                continue;
            }
            String[] parts = episode.split("\\$", 2);
            String name;
            String url;
            if (parts.length >= 2) {
                name = parts[0];
                url = parts[1];
            } else {
                name = String.valueOf(i + 1);
                url = parts[0];
            }
            name = name == null ? "" : name.trim();
            url = url == null ? "" : url.trim();
            if (url.isEmpty()) {
                continue;
            }
            if (name.isEmpty()) {
                name = String.valueOf(i + 1);
            }
            out.add(new VodInfo.VodSeries(name, url));
        }
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * 按片名跨源搜索。结果按源分批回调，不保证源之间的顺序。
     *
     * @return 本次搜索的序号，调用方不需要使用；{@link #cancelSearch()} 会作废所有在飞的搜索
     */
    public static int search(final String keyword, final SearchCallback callback) {
        final int seq = SEARCH_SEQ.incrementAndGet();
        if (TextUtils.isEmpty(keyword)) {
            postSearchDone(callback, seq);
            return seq;
        }
        final String wd = keyword.trim();
        final List<SourceBean> sources = searchSources();
        if (sources.isEmpty()) {
            postSearchDone(callback, seq);
            return seq;
        }

        final AtomicInteger remaining = new AtomicInteger(sources.size());
        final AtomicBoolean done = new AtomicBoolean(false);
        final Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (done.compareAndSet(false, true)) {
                    callback.onDone();
                }
            }
        };
        // 总预算：慢源不该把整页拖住。已到的结果照常显示
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (seq != SEARCH_SEQ.get()) {
                    return;
                }
                finish.run();
            }
        }, SEARCH_BUDGET_MS);

        for (SourceBean bean : sources) {
            searchOne(bean, wd, seq, callback, new Runnable() {
                @Override
                public void run() {
                    if (seq != SEARCH_SEQ.get()) {
                        return;
                    }
                    if (remaining.decrementAndGet() <= 0) {
                        MAIN.post(finish);
                    }
                }
            });
        }
        return seq;
    }

    /** 作废所有在飞的搜索（翻页/关页时调） */
    public static void cancelSearch() {
        SEARCH_SEQ.incrementAndGet();
    }

    private static List<SourceBean> searchSources() {
        List<SourceBean> result = new ArrayList<>();
        SourceBean current = SourceFeed.currentSource();
        if (isSearchable(current)) {
            result.add(current);
        }
        List<SourceBean> all = null;
        try {
            all = ApiConfig.get().getSearchSourceBeanList();
            if (all == null || all.isEmpty()) {
                all = ApiConfig.get().getSourceBeanList();
            }
        } catch (Throwable th) {
            android.util.Log.w(TAG, "searchSources failed", th);
        }
        if (all != null) {
            for (SourceBean bean : all) {
                if (result.size() >= MAX_SEARCH_SOURCES) {
                    break;
                }
                if (bean == null || result.contains(bean) || !isSearchable(bean)) {
                    continue;
                }
                result.add(bean);
            }
        }
        return result;
    }

    private static boolean isSearchable(SourceBean bean) {
        if (bean == null || TextUtils.isEmpty(bean.getKey())) {
            return false;
        }
        int type = bean.getType();
        if (type != 0 && type != 1 && type != 3 && type != 4) {
            return false;
        }
        if (type != 3 && TextUtils.isEmpty(bean.getApi())) {
            return false;
        }
        // 豆瓣壳只会返回 msearch:xxx 这类不能播的条目，问了也白问
        String api = bean.getApi();
        String key = bean.getKey();
        if ((api != null && api.toLowerCase().contains("douban"))
                || key.toLowerCase().startsWith("douban")) {
            return false;
        }
        return true;
    }

    private static void searchOne(final SourceBean bean, final String wd, final int seq,
                                  final SearchCallback callback, final Runnable done) {
        final int type = bean.getType();
        if (type == 3) {
            SEARCH_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    // 排队期间已经换了关键词或关页，别把线程耗在过期的源上
                    if (seq != SEARCH_SEQ.get()) {
                        done.run();
                        return;
                    }
                    String json = null;
                    try {
                        json = SourceFeed.callWithTimeout(new Callable<String>() {
                            @Override
                            public String call() throws Exception {
                                Spider spider = ApiConfig.get().getCSP(bean);
                                return spider == null ? null : spider.searchContent(wd, false);
                            }
                        });
                    } catch (Throwable th) {
                        android.util.Log.w(TAG, "search spider failed: " + bean.getName(), th);
                    }
                    postBatch(callback, seq, bean, SourceFeed.videosOf(SourceFeed.parseJson(json), bean.getKey()));
                    done.run();
                }
            });
            return;
        }

        com.lzy.okgo.request.GetRequest<String> request = OkGo.<String>get(bean.getApi())
                .tag("desk_search_" + bean.getKey())
                .params("wd", wd);
        if (type == 1) {
            request.params("ac", "detail");
        } else if (type == 4) {
            request.params("ac", "detail").params("quick", "false");
            String extend = bean.getExt();
            if (!TextUtils.isEmpty(extend)) {
                request.params("extend", extend.trim());
            }
        }
        request.execute(new AbsCallback<String>() {
            @Override
            public String convertResponse(okhttp3.Response response) throws Throwable {
                return response.body() == null ? "" : response.body().string();
            }

            @Override
            public void onSuccess(Response<String> response) {
                AbsXml absXml = type == 0
                        ? SourceFeed.parseXml(response.body())
                        : SourceFeed.parseJson(response.body());
                postBatch(callback, seq, bean, SourceFeed.videosOf(absXml, bean.getKey()));
                done.run();
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
                done.run();
            }
        });
    }

    private static void postBatch(final SearchCallback callback, final int seq,
                                  final SourceBean bean, List<Movie.Video> videos) {
        final List<Movie.Video> filtered = new ArrayList<>();
        if (videos != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (Movie.Video vod : videos) {
                if (vod == null || TextUtils.isEmpty(vod.name) || TextUtils.isEmpty(vod.id)) {
                    continue;
                }
                // 豆瓣壳条目点进去没有线路
                if (vod.id.startsWith("msearch:")) {
                    continue;
                }
                if (seen.add(vod.id)) {
                    filtered.add(vod);
                }
            }
        }
        if (filtered.isEmpty()) {
            return;
        }
        final String sourceKey = bean.getKey();
        final String sourceName = TextUtils.isEmpty(bean.getName()) ? sourceKey : bean.getName();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (seq != SEARCH_SEQ.get()) {
                    return;
                }
                callback.onBatch(sourceKey, sourceName, filtered);
            }
        });
    }

    // ---------------------------------------------------------------- 回调

    private static void postDetail(final DetailCallback callback, final VodInfo info) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onDetail(info);
            }
        });
    }

    private static void postDetailError(final DetailCallback callback, final String message) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message == null ? "详情加载失败" : message);
            }
        });
    }

    private static void postSearchDone(final SearchCallback callback, final int seq) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (seq == SEARCH_SEQ.get()) {
                    callback.onDone();
                }
            }
        });
    }

    private static String describe(Throwable th) {
        if (th == null) {
            return "网络错误";
        }
        String message = th.getMessage();
        return TextUtils.isEmpty(message) ? th.getClass().getSimpleName() : message;
    }
}

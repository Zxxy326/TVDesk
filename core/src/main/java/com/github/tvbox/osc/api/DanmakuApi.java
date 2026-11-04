package com.github.tvbox.osc.api;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.bean.DanmuSearchResult;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.catvod.crawler.js.Trans;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class DanmakuApi {
    private static final String TAG = DanmakuApi.class.getSimpleName();
//    private static final String BUILTIN_API = "https://saas-oa.shyeguang.cn";
    private static final String BUILTIN_API = "https://logvardanmu.konfan.cn/87654321";
    private static final String USE_DEFAULT_KEY = "danmu_api_use_default";
    private static final long BUILTIN_TIMEOUT = TimeUnit.SECONDS.toMillis(15);
    private static final int BUILTIN_MAX_RETRY = 1;
    private static final int EPISODE_QUERY_NUMBER = 0;
    private static final int EPISODE_QUERY_EMPTY = 1;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final AtomicInteger searchSeq = new AtomicInteger();

    /**
     * 弹幕接口专用客户端：超时自己定，DNS 走系统解析。
     *
     * <p>为什么单独拿出来：配置里带 DoH 时，全站请求都会先问 DoH 服务器
     * （见 {@code OkDns}）。视频站需要它绕污染，但弹幕接口（logvardanmu）在国内直连就好，
     * 走 DoH 反而每换一个域名就要多等一次解析 —— 实机上表现是「弹幕时有时无，
     * 有时要等十几秒」，日志里全是 timeout。这里只把 DNS 换成系统解析，其余不动。
     */
    private static volatile okhttp3.OkHttpClient danmuClient;

    private static okhttp3.OkHttpClient danmuClient() {
        okhttp3.OkHttpClient client = danmuClient;
        if (client == null) {
            synchronized (DanmakuApi.class) {
                client = danmuClient;
                if (client == null) {
                    client = OkHttp.client(BUILTIN_TIMEOUT).newBuilder()
                            .dns(okhttp3.Dns.SYSTEM)
                            .build();
                    danmuClient = client;
                }
            }
        }
        return client;
    }

    public interface SearchCallback {
        void onFound(String url);

        default void onNotFound() {
        }
    }

    public interface SearchListCallback {
        void onSuccess(List<DanmuSearchResult> results);

        void onError(String message);
    }

    public interface SearchResultCallback {
        void onSuccess(String danmu);

        void onError(String message);
    }

    public static boolean canSearch() {
        return DanmuHelper.isOpen() && !TextUtils.isEmpty(getApiUrl());
    }

    public static boolean hasCustomApi() {
        String custom = Hawk.get(HawkConfig.DANMU_API, "");
        return !isUseDefault() && !TextUtils.isEmpty(custom) && !TextUtils.isEmpty(custom.trim());
    }

    public static String getDisplayApiUrl() {
        if (isUseDefault()) return "";
        String custom = Hawk.get(HawkConfig.DANMU_API, "");
        if (!TextUtils.isEmpty(custom)) return custom.trim();
        String config = ApiConfig.get().getDanmaku().trim();
        return TextUtils.isEmpty(config) ? "" : config;
    }

    public static boolean isUseDefault() {
        return Hawk.get(USE_DEFAULT_KEY, false);
    }

    public static void setUseDefault(boolean useDefault) {
        Hawk.put(USE_DEFAULT_KEY, useDefault);
        if (useDefault) Hawk.put(HawkConfig.DANMU_API, "");
    }

    public static void setCustomApi(String api) {
        Hawk.put(USE_DEFAULT_KEY, false);
        Hawk.put(HawkConfig.DANMU_API, api);
    }

    public static void search(String name, String episode, SearchCallback callback) {
        String apiUrl = getApiUrl();
        // 排查「没有弹幕」时先看这一行：实际用的是哪个接口（自定义 / 内置）
        LOG.i("echo-danmaku search api: " + safeLog(apiUrl)
                + ", name: " + safeLog(name) + ", episode: " + safeLog(episode));
        if (TextUtils.isEmpty(apiUrl) || callback == null) return;
        try {
            OkHttp.cancel(TAG);
            int seq = searchSeq.incrementAndGet();
//            LOG.i("echo-danmaku search title: " + safeLog(name) + ", episode: " + safeLog(episode));
            if (!hasPlaceholder(apiUrl) && !isDanmakuSearchApi(apiUrl)) {
                searchBuiltin(apiUrl, name, episode, callback, 0, seq);
                return;
            }
            newCall(apiUrl, name, episode).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    if (!isCurrentSearch(seq)) return;
                    LOG.e("echo-danmaku search error: " + e.getMessage());
                    notifyNotFound(callback, seq);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    if (!isCurrentSearch(seq)) return;
                    try {
                        String body = response.body() == null ? "" : response.body().string();
                        String url = parseUrl(body);
                        if (!TextUtils.isEmpty(url) && isCurrentSearch(seq)) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (isCurrentSearch(seq)) callback.onFound(url);
                                }
                            });
                        } else {
                            notifyNotFound(callback, seq);
                        }
                    } catch (Throwable th) {
                        LOG.e("echo-danmaku search parse error: " + th.getMessage());
                        notifyNotFound(callback, seq);
                    }
                }
            });
        } catch (Throwable th) {
            LOG.e("echo-danmaku search start error: " + th.getMessage());
            notifyNotFound(callback, searchSeq.get());
        }
    }

    public static void searchList(String name, String episode, SearchListCallback callback) {
        if (callback == null) return;
        OkHttp.cancel(TAG);
        int seq = searchSeq.incrementAndGet();
        String apiUrl = getApiUrl();
        if (TextUtils.isEmpty(apiUrl)) {
            notifySearchListError(callback, seq, "弹幕搜索接口为空");
            return;
        }
        if (!hasPlaceholder(apiUrl) && !isDanmakuSearchApi(apiUrl)) {
            searchBuiltinList(apiUrl, name, callback, seq);
            return;
        }
        try {
            newCall(apiUrl, name, episode).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    notifySearchListError(callback, seq, getErrorMessage(e));
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
                        String body = response.body() == null ? "" : response.body().string();
                        notifySearchListSuccess(callback, seq, parseSearchList(body));
                    } catch (Throwable th) {
                        notifySearchListError(callback, seq, getErrorMessage(th));
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Throwable th) {
            notifySearchListError(callback, seq, getErrorMessage(th));
        }
    }

    public static void loadSearchResult(DanmuSearchResult result, SearchResultCallback callback) {
        if (callback == null) return;
        OkHttp.cancel(TAG);
        int seq = searchSeq.incrementAndGet();
        if (result == null || TextUtils.isEmpty(result.getUrl())) {
            notifySearchResultError(callback, seq, "弹幕地址为空");
            return;
        }
        if (!result.isBuiltIn()) {
            notifySearchResultSuccess(callback, seq, result.getUrl());
            return;
        }
        OkHttp.newCall(danmuClient(), result.getUrl(), TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                notifySearchResultError(callback, seq, getErrorMessage(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
                    String body = response.body() == null ? "" : response.body().string();
                    String danmu = commentJsonToXml(body);
                    if (TextUtils.isEmpty(danmu)) throw new IOException("未获取到弹幕内容");
                    notifySearchResultSuccess(callback, seq, danmu);
                } catch (Throwable th) {
                    notifySearchResultError(callback, seq, getErrorMessage(th));
                } finally {
                    response.close();
                }
            }
        });
    }

    public static void cancel() {
        searchSeq.incrementAndGet();
        OkHttp.cancel(TAG);
    }

    private static void searchBuiltinList(String apiUrl, String name, SearchListCallback callback, int seq) {
        String baseUrl = normalizeBaseUrl(apiUrl);
        String searchUrl = baseUrl + "/api/v2/search/episodes?anime=" + encode(Trans.t2s(name == null ? "" : name));
        OkHttp.newCall(danmuClient(), searchUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                notifySearchListError(callback, seq, getErrorMessage(e));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
                    String body = response.body() == null ? "" : response.body().string();
                    notifySearchListSuccess(callback, seq, parseBuiltinSearchList(body, baseUrl));
                } catch (Throwable th) {
                    notifySearchListError(callback, seq, getErrorMessage(th));
                } finally {
                    response.close();
                }
            }
        });
    }

    private static void searchBuiltin(String apiUrl, String name, String episode, SearchCallback callback, int retry, int seq) {
        searchBuiltin(apiUrl, name, episode, callback, retry, seq, EPISODE_QUERY_NUMBER);
    }

    private static void searchBuiltin(String apiUrl, String name, String episode, SearchCallback callback, int retry, int seq, int queryMode) {
        final String baseUrl = normalizeBaseUrl(apiUrl);
        final String simpleName = Trans.t2s(name == null ? "" : name);
        final String simpleEpisode = Trans.t2s(episode == null ? "" : episode);
        final String episodeQuery = getEpisodeQuery(simpleEpisode, queryMode);
        final String searchUrl = baseUrl + "/api/v2/search/episodes?anime=" + encode(simpleName)
                + (TextUtils.isEmpty(episodeQuery) ? "" : "&episode=" + encode(episodeQuery));
        // 这一行是排查「没有弹幕」的第一现场：集名解出来的集数对不对，一眼就能看出来
        LOG.i("echo-danmaku builtin search: " + searchUrl + ", retry=" + retry + ", mode=" + queryMode);
        OkHttp.newCall(danmuClient(), searchUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!isCurrentSearch(seq)) return;
                if (retry < BUILTIN_MAX_RETRY) {
                    LOG.e("echo-danmaku builtin search error: " + e.getMessage() + ", retry later");
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (isCurrentSearch(seq)) searchBuiltin(apiUrl, name, episode, callback, retry + 1, seq, queryMode);
                        }
                    }, 1500L * (retry + 1));
                } else {
                    LOG.e("echo-danmaku builtin search error: " + e.getMessage());
                    searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback, seq, true);
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (!isCurrentSearch(seq)) return;
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    // 这个公共弹幕服务在限流/抖动时会返回一张 HTML 错误页而不是 JSON。
                    // 把它当成「网络抖了一下」重试，比直接判「这部片没弹幕」体验好得多。
                    if (!looksLikeJson(body)) {
                        LOG.e("echo-danmaku builtin search: 返回的不是 JSON（多半被限流）");
                        if (retry < BUILTIN_MAX_RETRY) {
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (isCurrentSearch(seq)) {
                                        searchBuiltin(apiUrl, name, episode, callback, retry + 1, seq, queryMode);
                                    }
                                }
                            }, 1200L * (retry + 1));
                        } else {
                            notifyNotFound(callback, seq);
                        }
                        return;
                    }
                    EpisodeMatch episodeMatch = findEpisodeFromSearchEpisodes(body, simpleEpisode);
                    if (episodeMatch != null && !TextUtils.isEmpty(episodeMatch.id)) {
                        loadBuiltinComment(baseUrl, simpleName, simpleEpisode, episodeMatch, callback, seq);
                        return;
                    }
                    if (isSearchEpisodesMovieResult(body)) {
                        LOG.i("echo-danmaku builtin movie result not matched, skip anime fallback");
                        notifyNotFound(callback, seq);
                        return;
                    }
                    if (tryNextEpisodeQuery(apiUrl, name, episode, callback, seq, queryMode)) return;
                    LOG.i("echo-danmaku builtin episode not matched, title: " + safeLog(simpleName) + ", episode: " + safeLog(simpleEpisode));
                    searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback, seq, true);
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin episode parse error: " + th.getMessage());
                    if (tryNextEpisodeQuery(apiUrl, name, episode, callback, seq, queryMode)) return;
                    searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback, seq, true);
                }
            }
        });
    }

    private static boolean tryNextEpisodeQuery(String apiUrl, String name, String episode, SearchCallback callback, int seq, int queryMode) {
        int nextMode = getNextEpisodeQueryMode(Trans.t2s(episode == null ? "" : episode), queryMode);
        if (nextMode < 0) return false;
//        LOG.i("echo-danmaku builtin retry episodes query mode: " + queryMode + " -> " + nextMode);
        searchBuiltin(apiUrl, name, episode, callback, 0, seq, nextMode);
        return true;
    }

    private static void searchBuiltinAnime(String baseUrl, String name, String episode, SearchCallback callback, int seq, boolean notifyOnEmpty) {
        final String searchUrl = baseUrl + "/api/v2/search/anime?keyword=" + encode(name);
        LOG.i("echo-danmaku builtin search anime: " + searchUrl);
        OkHttp.newCall(danmuClient(), searchUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!isCurrentSearch(seq)) return;
                LOG.e("echo-danmaku builtin anime error: " + e.getMessage());
                if (notifyOnEmpty) notifyNotFound(callback, seq);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (!isCurrentSearch(seq)) return;
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    String animeId = findAnimeId(body);
                    if (TextUtils.isEmpty(animeId)) {
                        if (notifyOnEmpty) notifyNotFound(callback, seq);
                        return;
                    }
                    loadBuiltinBangumi(baseUrl, animeId, episode, callback, seq);
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin anime parse error: " + th.getMessage());
                    if (notifyOnEmpty) notifyNotFound(callback, seq);
                }
            }
        });
    }

    private static void loadBuiltinBangumi(String baseUrl, String animeId, String episode, SearchCallback callback, int seq) {
        final String bangumiUrl = baseUrl + "/api/v2/bangumi/" + animeId;
//        LOG.i("echo-danmaku builtin bangumi: " + bangumiUrl);
        OkHttp.newCall(danmuClient(), bangumiUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!isCurrentSearch(seq)) return;
                LOG.e("echo-danmaku builtin bangumi error: " + e.getMessage());
                notifyNotFound(callback, seq);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (!isCurrentSearch(seq)) return;
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    EpisodeMatch episodeMatch = findEpisode(body, episode);
                    if (episodeMatch != null && !TextUtils.isEmpty(episodeMatch.id)) {
                        loadBuiltinComment(baseUrl, "", episode, episodeMatch, callback, seq);
                    } else {
//                        LOG.i("echo-danmaku builtin bangumi episode not matched, episode: " + safeLog(episode));
                        notifyNotFound(callback, seq);
                    }
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin bangumi parse error: " + th.getMessage());
                    notifyNotFound(callback, seq);
                }
            }
        });
    }

    private static void loadBuiltinComment(String baseUrl, String title, String episode, EpisodeMatch episodeMatch, SearchCallback callback, int seq) {
        final String commentUrl = baseUrl + "/api/v2/comment/" + episodeMatch.id + "?format=json";
        LOG.i("echo-danmaku builtin load title: " + safeLog(title)
                + ", request episode: " + safeLog(episode)
                + ", matched episode: " + safeLog(episodeMatch.title)
                + ", matched number: " + episodeMatch.number
                + ", episodeId: " + episodeMatch.id);
        LOG.i("echo-danmaku builtin comment: " + commentUrl);
        OkHttp.newCall(danmuClient(), commentUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (!isCurrentSearch(seq)) return;
                LOG.e("echo-danmaku builtin comment error: " + e.getMessage());
                notifyNotFound(callback, seq);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (!isCurrentSearch(seq)) return;
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    final String xml = commentJsonToXml(body);
                    if (TextUtils.isEmpty(xml) || !isCurrentSearch(seq)) {
                        notifyNotFound(callback, seq);
                        return;
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isCurrentSearch(seq)) callback.onFound(xml);
                        }
                    });
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin comment parse error: " + th.getMessage());
                    notifyNotFound(callback, seq);
                }
            }
        });
    }

    private static void notifyNotFound(SearchCallback callback, int seq) {
        if (callback == null || !isCurrentSearch(seq)) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrentSearch(seq)) callback.onNotFound();
            }
        });
    }

    private static boolean isCurrentSearch(int seq) {
        return seq == searchSeq.get();
    }

    /** 响应体像不像 JSON（公共接口被限流时会直接甩一张 HTML 错误页回来） */
    private static boolean looksLikeJson(String body) {
        if (TextUtils.isEmpty(body)) return false;
        String text = body.trim();
        return text.startsWith("{") || text.startsWith("[");
    }

    private static Call newCall(String apiUrl, String name, String episode) {
        name = Trans.t2s(name == null ? "" : name);
        episode = Trans.t2s(episode == null ? "" : episode);
        if (hasPlaceholder(apiUrl)) {
            return OkHttp.newCall(apiUrl.replace("{name}", name).replace("{episode}", episode), TAG);
        }
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("name", name);
        params.put("episode", episode);
        return OkHttp.newCall(apiUrl, OkHttp.toBody(params), TAG);
    }

    private static String getApiUrl() {
        if (isUseDefault()) return BUILTIN_API;
        String custom = Hawk.get(HawkConfig.DANMU_API, "");
        if (!TextUtils.isEmpty(custom)) return custom.trim();
        String config = ApiConfig.get().getDanmaku().trim();
        if (!TextUtils.isEmpty(config)) return config;
        return BUILTIN_API;
    }

    private static boolean hasPlaceholder(String apiUrl) {
        return !TextUtils.isEmpty(apiUrl) && (apiUrl.contains("{name}") || apiUrl.contains("{episode}"));
    }

    private static boolean isDanmakuSearchApi(String apiUrl) {
        String url = apiUrl == null ? "" : apiUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.endsWith("/danmaku");
    }

    private static String normalizeBaseUrl(String apiUrl) {
        String url = apiUrl == null ? "" : apiUrl.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/87654321")) url = url.substring(0, url.length() - "/87654321".length());
        return url;
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text == null ? "" : text, "UTF-8");
        } catch (Throwable th) {
            return text == null ? "" : text;
        }
    }

    private static String getEpisodeQuery(String episode, int queryMode) {
        if (queryMode == EPISODE_QUERY_EMPTY) return "";
        int number = extractNumber(episode);
        return number > 0 ? String.valueOf(number) : "";
    }

    private static int getNextEpisodeQueryMode(String episode, int queryMode) {
        if (queryMode == EPISODE_QUERY_NUMBER && !TextUtils.isEmpty(getEpisodeQuery(episode, EPISODE_QUERY_NUMBER))) {
            return EPISODE_QUERY_EMPTY;
        }
        return -1;
    }

    private static String findAnimeId(String body) throws Exception {
        JSONObject object = new JSONObject(body);
        JSONArray array = object.optJSONArray("animes");
        if (array == null) array = object.optJSONArray("anime");
        if (array == null) array = object.optJSONArray("data");
        if (array == null || array.length() <= 0) return "";
        JSONObject item = array.optJSONObject(0);
        if (item == null) return "";
        String id = item.optString("animeId", "");
        if (TextUtils.isEmpty(id)) id = item.optString("id", "");
        return id;
    }

    private static EpisodeMatch findEpisode(String body, String episode) throws Exception {
        return findEpisode(body, episode, true);
    }

    private static EpisodeMatch findEpisode(String body, String episode, boolean allowMovieFallback) throws Exception {
        JSONObject object = new JSONObject(body);
        // 一个「按集搜索」的响应里通常挂着好几条同名番剧（腾讯、360、爱奇艺…），
        // 每条的集数表是独立的一份。以前只看第一条，结果目标集在第二条里就永远匹配不到 ——
        // 表现就是「这部片有时候有弹幕、有时候没有」，全看源站先给哪家。
        List<EpisodeList> lists = findEpisodeLists(object);
        if (lists.isEmpty()) return null;
        int targetNumber = extractNumber(episode);
        EpisodeMatch first = null;
        EpisodeMatch firstMandarin = null;
        EpisodeMatch firstMovie = null;
        EpisodeMatch firstMovieMandarin = null;
        for (EpisodeList list : lists) {
            JSONArray episodes = list.episodes;
            if (episodes == null) continue;
            for (int i = 0; i < episodes.length(); i++) {
                JSONObject item = episodes.optJSONObject(i);
                if (item == null) continue;
                String id = firstString(item, "episodeId", "id");
                if (TextUtils.isEmpty(id)) continue;
                String title = firstString(item, "episodeTitle", "title", "name");
                int number = parseEpisodeNumber(firstString(item, "episodeNumber", "number", "sort"));
                EpisodeMatch match = new EpisodeMatch(id, title, number);
                if (first == null) first = match;
                if (firstMandarin == null && isMandarinTitle(title)) firstMandarin = match;
                if (list.isMovie) {
                    if (firstMovie == null) firstMovie = match;
                    if (firstMovieMandarin == null && isMandarinTitle(title)) firstMovieMandarin = match;
                    continue;
                }
                if (!TextUtils.isEmpty(episode) && !TextUtils.isEmpty(title) && title.contains(episode)) {
                    return match;
                }
                if (targetNumber > 0 && number == targetNumber) return match;
                if (targetNumber > 0 && extractNumber(title) == targetNumber) return match;
            }
        }
        if (TextUtils.isEmpty(episode)) return first;
        if (allowMovieFallback) {
            if (firstMovie != null) return firstMovieMandarin == null ? firstMovie : firstMovieMandarin;
            if (lists.get(0).isMovie) return firstMandarin == null ? first : firstMandarin;
        }
        return null;
    }

    private static EpisodeMatch findEpisodeFromSearchEpisodes(String body, String episode) throws Exception {
        EpisodeMatch match = findEpisode(body, episode, false);
        if (match != null) return match;
        EpisodeMatch movieFallback = findMovieFallback(new JSONObject(body), episode);
        if (movieFallback != null) {
            LOG.i("echo-danmaku episodes movie fallback episode: " + safeLog(movieFallback.title) + ", episodeId: " + movieFallback.id);
        }
        return movieFallback;
    }

    private static JSONArray findEpisodes(JSONObject object) {
        EpisodeList episodeList = findEpisodeList(object);
        return episodeList == null ? null : episodeList.episodes;
    }

    private static EpisodeList findEpisodeList(JSONObject object) {
        List<EpisodeList> lists = findEpisodeLists(object);
        return lists.isEmpty() ? null : lists.get(0);
    }

    /**
     * 把响应里<b>所有</b>集数表都取出来。
     *
     * <p>按集搜索的响应是「多条同名番剧，各自一段 episodes」：
     *
     * <pre>
     * {"animes":[
     *   {"animeTitle":"…from tencent","episodes":[{"episodeTitle":"…_21"}]},
     *   {"animeTitle":"…from 360","episodes":[{"episodeTitle":"…第22集"}]}
     * ]}
     * </pre>
     *
     * <p>以前只取第一条，目标集只要落在后面几家就永远匹配不上，用户看到的就是
     * 「这部片有时有弹幕、有时没有」——取决于源站这次先返回哪家。
     */
    private static List<EpisodeList> findEpisodeLists(JSONObject object) {
        List<EpisodeList> out = new ArrayList<>();
        JSONArray array = object.optJSONArray("episodes");
        if (array != null) {
            out.add(new EpisodeList(array, isMovieType(object)));
            return out;
        }
        JSONObject bangumi = object.optJSONObject("bangumi");
        if (bangumi != null) {
            array = bangumi.optJSONArray("episodes");
            if (array != null) {
                out.add(new EpisodeList(array, isMovieType(bangumi)));
                return out;
            }
        }
        JSONArray animes = object.optJSONArray("animes");
        if (animes == null) animes = object.optJSONArray("anime");
        if (animes == null) animes = object.optJSONArray("data");
        if (animes != null) {
            for (int i = 0; i < animes.length(); i++) {
                JSONObject item = animes.optJSONObject(i);
                if (item == null) continue;
                array = item.optJSONArray("episodes");
                if (array != null && array.length() > 0) {
                    out.add(new EpisodeList(array, isMovieType(item)));
                }
            }
        }
        return out;
    }

    private static boolean isMovieType(JSONObject object) {
        if (object == null) return false;
        if (isMovieTypeText(object.optString("type", ""))) return true;
        if (isMovieTypeText(object.optString("typeDescription", ""))) return true;
        JSONObject bangumi = object.optJSONObject("bangumi");
        if (bangumi != null && isMovieTypeText(bangumi.optString("type", ""))) return true;
        JSONArray animes = object.optJSONArray("animes");
        if (animes == null) animes = object.optJSONArray("anime");
        if (animes == null) animes = object.optJSONArray("data");
        if (animes == null) return false;
        for (int i = 0; i < animes.length(); i++) {
            JSONObject item = animes.optJSONObject(i);
            if (item != null && isMovieTypeText(item.optString("type", ""))) return true;
        }
        return false;
    }

    private static boolean isMovieTypeText(String type) {
        return "\u7535\u5f71".equals(type);
    }

    private static boolean isMandarinTitle(String title) {
        return !TextUtils.isEmpty(title) && (title.contains("\u56fd\u8bed") || title.contains("\u666e\u901a\u8bdd"));
    }

    private static boolean isCantoneseTitle(String title) {
        return !TextUtils.isEmpty(title) && title.contains("\u7ca4\u8bed");
    }

    private static boolean prefersCantonese(String episode) {
        return isCantoneseTitle(episode);
    }

    private static boolean isPreferredLanguageTitle(String title, boolean preferCantonese) {
        return preferCantonese ? isCantoneseTitle(title) : isMandarinTitle(title);
    }

    private static boolean isSearchEpisodesMovieResult(String body) {
        try {
            JSONObject object = new JSONObject(body);
            JSONArray animes = object.optJSONArray("animes");
            if (animes == null) animes = object.optJSONArray("anime");
            if (animes == null) animes = object.optJSONArray("data");
            if (animes == null) return false;
            for (int i = 0; i < animes.length(); i++) {
                JSONObject anime = animes.optJSONObject(i);
                if (anime != null && isMovieType(anime) && anime.optJSONArray("episodes") != null) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static EpisodeMatch findMovieFallback(JSONObject object, String episode) {
        JSONArray animes = object.optJSONArray("animes");
        if (animes == null) animes = object.optJSONArray("anime");
        if (animes == null) animes = object.optJSONArray("data");
        if (animes == null) return null;
        boolean preferCantonese = prefersCantonese(episode);
        EpisodeMatch firstMovie = null;
        for (int i = 0; i < animes.length(); i++) {
            JSONObject anime = animes.optJSONObject(i);
            if (anime == null || !isMovieType(anime)) continue;
            JSONArray episodes = anime.optJSONArray("episodes");
            if (episodes == null) continue;
            for (int j = 0; j < episodes.length(); j++) {
                JSONObject item = episodes.optJSONObject(j);
                if (item == null) continue;
                String id = firstString(item, "episodeId", "id");
                if (TextUtils.isEmpty(id)) continue;
                String title = firstString(item, "episodeTitle", "title", "name");
                EpisodeMatch match = new EpisodeMatch(id, title, parseEpisodeNumber(firstString(item, "episodeNumber", "number", "sort")));
                if (firstMovie == null) firstMovie = match;
                if (isPreferredLanguageTitle(title, preferCantonese)) return match;
            }
        }
        return firstMovie;
    }

    private static int parseEpisodeNumber(String value) {
        try {
            if (TextUtils.isEmpty(value)) return -1;
            return (int) Float.parseFloat(value);
        } catch (Throwable th) {
            return extractNumber(value);
        }
    }

    /**
     * 体积 / 分辨率 / 编码这些「不是集数」的数字，先摘掉再找集数。
     *
     * <p>边界刻意写成 {@code (?![a-z0-9])} 而不是 {@code \b}：Java 的 {@code \b} 是
     * Unicode 感知的，「4K官方」「1080p国语」这种中文紧贴英文的写法里 K/p 后面
     * 仍算「词内」，{@code \b} 不成立，于是 4K / 1080p 摘不掉 —— 兜底取最后一个数字时
     * 就取到了 4，拿「第 4 集」去搜一部电影，自然搜不到弹幕。
     */
    private static final java.util.regex.Pattern P_NOISE = java.util.regex.Pattern.compile(
            // 文件扩展名要先摘：不然 ".mp4" 里的 4 会被后面的兜底当成第 4 集
            "(?i)\\.(mp4|mkv|avi|rmvb|rm|mov|flv|wmv|m2ts|m4v|webm|mpg|mpeg|ts)(?![a-z0-9])"
                    + "|\\d+(\\.\\d+)?\\s*(gb|mb|tb|kb)(?![a-z0-9])"
                    + "|\\d+(\\.\\d+)?\\s*[gm](?![a-z0-9])"
                    + "|\\d{3,4}\\s*[pi](?![a-z0-9])"
                    + "|\\d{3,4}\\s*[x×]\\s*\\d{3,4}"
                    + "|(?<![a-z0-9])(4k|8k|uhd|hdr10?|dolby|bluray|blu-?ray|web-?dl"
                    + "|remux|hevc|avc|h\\.?26[45])(?![a-z0-9])");

    /** 年份也当噪声：电影名里的 2026 不是集数 */
    private static final java.util.regex.Pattern P_YEAR =
            java.util.regex.Pattern.compile("(?:19|20)\\d{2}");

    /** S01E22 / S1.E22 */
    private static final java.util.regex.Pattern P_SXXEXX =
            java.util.regex.Pattern.compile("(?i)s\\d{1,2}\\s*[._-]?\\s*e\\s*(\\d{1,4})(?!\\d)");

    /** E22 / EP22 / E.22 / E_22（前面不能紧贴字母，避免把 EXTENDED 之类当成 E） */
    private static final java.util.regex.Pattern P_EP =
            java.util.regex.Pattern.compile("(?i)(?:^|[^a-z])(?:ep|e)\\s*[._-]?\\s*(\\d{1,4})(?!\\d)");

    /** 第22集 / 第22话 / 第22期 */
    private static final java.util.regex.Pattern P_CN =
            java.util.regex.Pattern.compile("第\\s*(\\d{1,4})\\s*[集话話期]");

    /** 22集 / 22话 */
    private static final java.util.regex.Pattern P_CN2 =
            java.util.regex.Pattern.compile("(\\d{1,4})\\s*[集话話期]");

    /** [22] / （22）这类方括号里只有一个数的情况 */
    private static final java.util.regex.Pattern P_BRACKET =
            java.util.regex.Pattern.compile("[\\[\\(（【]\\s*(\\d{1,4})\\s*[\\]\\)）】]");

    /** 兜底用：所有独立数字 */
    private static final java.util.regex.Pattern P_NUM =
            java.util.regex.Pattern.compile("(?<!\\d)(\\d{1,4})(?!\\d)");

    /**
     * 从「集名」里取出集数。
     *
     * <h3>为什么不能把数字全拼起来</h3>
     * 网盘源的集名就是一整条文件名，例如 {@code [5.0GB]E22.mkv【尚公-组】}。
     * 老实现把所有数字拼成一个整数，这里会拼出 <b>5022</b> ——
     * 拿 5022 去问弹幕接口当然一条都搜不到，用户看到的就是「这部片没有弹幕」。
     *
     * <p>所以按「明确写法 → 兜底」的顺序找：S01E22、E22、第22集、[22]，
     * 都不中才取最后一个独立数字（文件名通常以集数结尾），年份和体积/分辨率先摘掉。
     *
     * @return 集数；认不出来返回 -1（电影、预告这类没有集数的条目走另一条路）
     */
    private static int extractNumber(String text) {
        if (TextUtils.isEmpty(text)) return -1;
        String cleaned = P_NOISE.matcher(text).replaceAll(" ");
        Integer found = firstNumber(P_SXXEXX, cleaned);
        if (found != null) return found;
        found = firstNumber(P_EP, cleaned);
        if (found != null) return found;
        found = firstNumber(P_CN, cleaned);
        if (found != null) return found;
        found = firstNumber(P_CN2, cleaned);
        if (found != null) return found;
        found = firstNumber(P_BRACKET, cleaned);
        if (found != null) return found;
        // 兜底：去掉年份后取最后一个数
        String rest = P_YEAR.matcher(cleaned).replaceAll(" ");
        java.util.regex.Matcher matcher = P_NUM.matcher(rest);
        int last = -1;
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value >= 0 && value <= MAX_EPISODE) {
                    last = value;
                }
            } catch (Throwable ignored) {
            }
        }
        return last;
    }

    /** 集数上限：再大就不是集数了（年份、文件大小残留、id 之类），宁可认不出也别乱猜 */
    private static final int MAX_EPISODE = 999;

    private static Integer firstNumber(java.util.regex.Pattern pattern, String text) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value >= 0 && value <= MAX_EPISODE) {
                    return value;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String commentJsonToXml(String body) throws Exception {
        JSONObject object = new JSONObject(body);
        JSONArray comments = object.optJSONArray("comments");
        if (comments == null) comments = object.optJSONArray("data");
        if (comments == null || comments.length() <= 0) return "";
        StringBuilder builder = new StringBuilder();
        builder.append("<i>");
        for (int i = 0; i < comments.length(); i++) {
            JSONObject item = comments.optJSONObject(i);
            if (item == null) continue;
            String param = item.optString("p", "");
            String text = item.optString("m", "");
            if (TextUtils.isEmpty(text)) text = item.optString("text", "");
            if (TextUtils.isEmpty(param) || TextUtils.isEmpty(text)) continue;
            builder.append("<d p=\"").append(escapeXml(normalizeDanmakuParam(param))).append("\">")
                    .append(escapeXml(text)).append("</d>");
        }
        builder.append("</i>");
        String xml = builder.toString();
        LOG.i("echo-danmaku builtin xml length: " + xml.length());
        return xml;
    }

    private static String normalizeDanmakuParam(String param) {
        String[] values = param.split(",");
        if (values.length < 4) return param;
        String time = values[0];
        String type = values[1];
        String size = values[2];
        String color = values[3];
        if (isColorValue(size)) {
            color = size;
            size = "25";
        } else if (!isColorValue(color)) {
            color = "16777215";
        }
        return time + "," + type + "," + size + "," + normalizeColor(color);
    }

    private static boolean isColorValue(String value) {
        try {
            if (TextUtils.isEmpty(value)) return false;
            String text = value.trim();
            if (text.startsWith("#")) return true;
            if (text.startsWith("0x") || text.startsWith("0X")) return true;
            long color = Long.parseLong(text);
            return color >= 0 && color <= 0x00ffffffL;
        } catch (Throwable th) {
            return false;
        }
    }

    private static String normalizeColor(String color) {
        if (TextUtils.isEmpty(color)) return "16777215";
        String text = color.trim();
        try {
            if (text.startsWith("#")) return String.valueOf(Long.parseLong(text.substring(1), 16));
            if (text.startsWith("0x") || text.startsWith("0X")) return String.valueOf(Long.parseLong(text.substring(2), 16));
        } catch (Throwable ignored) {
        }
        return text;
    }

    private static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static String safeLog(String text) {
        return TextUtils.isEmpty(text) ? "" : text;
    }

    private static List<DanmuSearchResult> parseSearchList(String body) throws Exception {
        List<DanmuSearchResult> results = new ArrayList<>();
        if (TextUtils.isEmpty(body)) return results;
        String text = body.trim();
        if (text.startsWith("[")) {
            appendSearchResults(results, new JSONArray(text));
            return results;
        }
        if (!text.startsWith("{")) return results;
        JSONObject object = new JSONObject(text);
        JSONArray items = object.optJSONArray("data");
        if (items == null) items = object.optJSONArray("list");
        if (items == null) items = object.optJSONArray("results");
        if (items != null) {
            appendSearchResults(results, items);
            return results;
        }
        String url = object.optString("url", "").trim();
        if (!TextUtils.isEmpty(url)) {
            results.add(new DanmuSearchResult(firstString(object, "name", "title"), url, false));
        }
        return results;
    }

    private static void appendSearchResults(List<DanmuSearchResult> results, JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", "").trim();
            if (TextUtils.isEmpty(url)) continue;
            String name = firstString(item, "name", "title", "episode", "vod_name");
            results.add(new DanmuSearchResult(name, url, false));
        }
    }

    private static List<DanmuSearchResult> parseBuiltinSearchList(String body, String baseUrl) throws Exception {
        List<DanmuSearchResult> results = new ArrayList<>();
        JSONObject object = new JSONObject(body);
        appendBuiltinResults(results, baseUrl, object, "");
        JSONObject bangumi = object.optJSONObject("bangumi");
        if (bangumi != null) appendBuiltinResults(results, baseUrl, bangumi, firstString(bangumi, "animeTitle", "title", "name"));
        appendBuiltinAnimeResults(results, baseUrl, object.optJSONArray("animes"));
        appendBuiltinAnimeResults(results, baseUrl, object.optJSONArray("anime"));
        appendBuiltinAnimeResults(results, baseUrl, object.optJSONArray("data"));
        return results;
    }

    private static void appendBuiltinAnimeResults(List<DanmuSearchResult> results, String baseUrl, JSONArray animes) {
        if (animes == null) return;
        for (int i = 0; i < animes.length(); i++) {
            JSONObject anime = animes.optJSONObject(i);
            if (anime == null) continue;
            appendBuiltinResults(results, baseUrl, anime, firstString(anime, "animeTitle", "title", "name"));
            JSONObject bangumi = anime.optJSONObject("bangumi");
            if (bangumi != null) {
                appendBuiltinResults(results, baseUrl, bangumi, firstString(anime, "animeTitle", "title", "name"));
            }
        }
    }

    private static void appendBuiltinResults(List<DanmuSearchResult> results, String baseUrl, JSONObject object, String animeName) {
        JSONArray episodes = object.optJSONArray("episodes");
        if (episodes == null) return;
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject episode = episodes.optJSONObject(i);
            if (episode == null) continue;
            String id = firstString(episode, "episodeId", "id");
            if (TextUtils.isEmpty(id)) continue;
            String episodeName = firstString(episode, "episodeTitle", "title", "name");
            String name = TextUtils.isEmpty(animeName) ? episodeName : animeName + " " + episodeName;
            if (TextUtils.isEmpty(name)) name = id;
            String url = baseUrl + "/api/v2/comment/" + id + "?format=json";
            results.add(new DanmuSearchResult(name, url, true));
        }
    }

    private static void notifySearchListSuccess(SearchListCallback callback, int seq, List<DanmuSearchResult> results) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrentSearch(seq)) callback.onSuccess(results);
            }
        });
    }

    private static void notifySearchListError(SearchListCallback callback, int seq, String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrentSearch(seq)) callback.onError(message);
            }
        });
    }

    private static void notifySearchResultSuccess(SearchResultCallback callback, int seq, String danmu) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrentSearch(seq)) callback.onSuccess(danmu);
            }
        });
    }

    private static void notifySearchResultError(SearchResultCallback callback, int seq, String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (isCurrentSearch(seq)) callback.onError(message);
            }
        });
    }

    private static String getErrorMessage(Throwable th) {
        String message = th == null ? "" : th.getMessage();
        return TextUtils.isEmpty(message) ? "弹幕搜索失败" : message;
    }

    private static class EpisodeList {
        final JSONArray episodes;
        final boolean isMovie;

        EpisodeList(JSONArray episodes, boolean isMovie) {
            this.episodes = episodes;
            this.isMovie = isMovie;
        }
    }

    private static class EpisodeMatch {
        final String id;
        final String title;
        final int number;

        EpisodeMatch(String id, String title, int number) {
            this.id = id;
            this.title = title;
            this.number = number;
        }
    }

    private static String escapeXml(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace(">", "&gt;")
                .replace("<", "&lt;");
    }

    private static String parseUrl(String body) throws Exception {
        if (TextUtils.isEmpty(body)) return "";
        String text = body.trim();
        if (text.startsWith("[")) {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String url = object.optString("url", "").trim();
                if (!TextUtils.isEmpty(url)) return url;
            }
        } else if (text.startsWith("{")) {
            return new JSONObject(text).optString("url", "").trim();
        } else if (text.startsWith("http") || text.startsWith("file")) {
            return text;
        }
        return "";
    }
}

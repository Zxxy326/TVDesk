package com.tvdesk.core.feed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.UA;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页推荐流。
 *
 * <p>从 UserFragment 里抽出来的数据逻辑：豆瓣「新片榜」+ 当天缓存。
 * 桌面拿 {@link Movie.Video} 列表自己渲染，不再复用 TVBox 的 Fragment 布局
 * （那套布局带方块按钮和它自己的海报行，和桌面的 Hero 会打架）。
 *
 * <p>缓存 key 沿用 TVBox 原有的 {@code home_hot_day} / {@code home_hot}，
 * 这样两边的缓存互相复用，不会各拉一份。
 */
public final class HomeFeed {

    private static final String CACHE_DAY = "home_hot_day";
    private static final String CACHE_JSON = "home_hot";

    /**
     * 豆瓣条目的 id 前缀。
     *
     * <p>榜单条目本身没有可播源，id 只用来回查简介和避免海报墙把同名条目认成同一条。
     * {@code DeskActivity.openVideo} 要求 sourceKey 与 id 同时存在才进详情页，
     * 这里只有 id，点开仍然是按片名搜索。
     */
    private static final String ID_PREFIX = "douban:";

    /**
     * 简介回查。
     *
     * <p>豆瓣的搜索/榜单接口只给标题、评分、封面，简介要按 subject id 单独取：
     * {@code /rexxar/api/v2/movie/<id>}，剧集会被 301 到 {@code /tv/<id>}，
     * OkHttp 自动跟随。接口不需要登录也不需要 apikey，但缺 Referer 会直接 400。
     *
     * <p>缓存只放内存：简介跟片名一样不常变，但进硬盘要另开一张表，
     * 而首页榜单本身每天都会换一批，跨天复用的价值不高。
     */
    private static final String INTRO_API = "https://m.douban.com/rexxar/api/v2/movie/";
    private static final String INTRO_UA =
            "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final String INTRO_REFERER = "https://m.douban.com/";
    private static final int INTRO_CACHE_MAX = 40;

    private static final LinkedHashMap<String, String> INTRO_CACHE =
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > INTRO_CACHE_MAX;
                }
            };
    /** 正在飞的请求；同一 id 的多个回调挂在这里，别为同一条简介发两遍 */
    private static final Map<String, List<IntroCallback>> INTRO_WAITING = new HashMap<>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private HomeFeed() {
    }

    public interface IntroCallback {
        /**
         * @param intro 简介；{@code null} 表示这次没取到（网络/解析失败），
         *              空串表示豆瓣确实没给简介。调用方据此给不同兜底文案。
         */
        void onIntro(String intro);
    }

    public interface Callback {
        /**
         * @param items     推荐列表，可能为空
         * @param fromCache 是否命中当天缓存
         */
        void onResult(List<Movie.Video> items, boolean fromCache);

        void onError(Throwable th);
    }

    /**
     * 加载首页推荐。回调在主线程。
     *
     * <p>先看当天缓存，命中就直接返回；没有才走网络。
     */
    public static void load(final Context context, final Callback callback) {
        final String today = todayKey();

        String cachedDay = Hawk.get(CACHE_DAY, "");
        if (today.equals(cachedDay)) {
            String json = Hawk.get(CACHE_JSON, "");
            if (!TextUtils.isEmpty(json)) {
                List<Movie.Video> items = parse(json);
                if (!items.isEmpty()) {
                    postResult(callback, items, true);
                    return;
                }
            }
        }

        try {
            OkGo.<String>get(doubanUrl())
                    .headers("User-Agent", UA.randomOne())
                    .execute(new AbsCallback<String>() {
                        @Override
                        public void onSuccess(Response<String> response) {
                            String body = response.body();
                            Hawk.put(CACHE_DAY, today);
                            Hawk.put(CACHE_JSON, body);
                            postResult(callback, parse(body), false);
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            Throwable th = response.getException();
                            postError(callback, th != null ? th : new RuntimeException("home feed failed"));
                        }

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            return response.body() == null ? "" : response.body().string();
                        }
                    });
        } catch (Throwable th) {
            postError(callback, th);
        }
    }

    private static String doubanUrl() {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        return "https://movie.douban.com/j/new_search_subjects"
                + "?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
    }

    private static String todayKey() {
        Calendar cal = Calendar.getInstance();
        return String.format("%d%d%d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DATE));
    }

    /** 解析豆瓣返回，最多取 25 条 */
    private static List<Movie.Video> parse(String json) {
        List<Movie.Video> result = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return result;
        }
        try {
            JsonObject root = new Gson().fromJson(json, JsonObject.class);
            if (root == null || !root.has("data")) {
                return result;
            }
            JsonArray array = root.getAsJsonArray("data");
            int limit = Math.min(array.size(), 25);
            for (int i = 0; i < limit; i++) {
                JsonElement ele = array.get(i);
                if (!ele.isJsonObject()) {
                    continue;
                }
                JsonObject obj = ele.getAsJsonObject();
                Movie.Video vod = new Movie.Video();
                vod.name = optString(obj, "title");
                String subjectId = optString(obj, "id");
                if (!TextUtils.isEmpty(subjectId)) {
                    vod.id = ID_PREFIX + subjectId;
                }
                String rate = optString(obj, "rate");
                vod.note = TextUtils.isEmpty(rate) ? "" : rate + " 分";

                // 豆瓣图床要带 Referer，否则 403
                String agent = "@User-Agent=" + UA.randomOne() + "@Referer=https://www.douban.com/";
                String cover = optString(obj, "cover");
                // 卡片只要约 240×357px，m 档（540×810）足够，再大只是白费流量
                vod.pic = variant(cover, "m") + agent;
                // Hero 铺满整屏。l 档（1080×1619）是接口能给到的最清晰的一档
                vod.picSlide = variant(cover, "l") + agent;

                if (!TextUtils.isEmpty(vod.name)) {
                    result.add(vod);
                }
            }
        } catch (Throwable ignored) {
            // 解析失败返回已解析到的部分
        }
        return result;
    }

    /**
     * 换豆瓣图床的尺寸档位。
     *
     * <p>接口默认返回 {@code s_ratio_poster}——竖版海报小图，实测 270×405、29KB。
     * 桌面海报卡片在 1080p 上是 240×357px，小图勉强够；但 Hero 要铺满整个屏幕，
     * 270 宽拉到 1920 就是 7 倍放大，糊得没法看。
     *
     * <p>同一张图豆瓣备了三档，URL 里只有尺寸段不同，直接替换即可：
     * <pre>
     *   s_ratio_poster   270×405    29KB
     *   m_ratio_poster   540×810    94KB
     *   l_ratio_poster  1080×1619  341KB
     * </pre>
     * 更清晰的 {@code raw_ratio_poster} 实测 404，拿不到，所以 l 就是上限。
     *
     * @param size 目标档位，{@code s} / {@code m} / {@code l}
     */
    private static String variant(String cover, String size) {
        if (TextUtils.isEmpty(cover)) {
            return "";
        }
        cover = stripHeaders(cover);
        // 主形态：https://img1.doubanio.com/view/photo/s_ratio_poster/public/pXXXX.jpg
        String out = cover.replaceAll("/(s|m|l|raw)_(ratio_poster|ratio)/", "/" + size + "_$2/");
        if (!out.equals(cover)) {
            return out;
        }
        // 少数接口给的是 https://img1.doubanio.com/view/subject/s/public/sXXXX.jpg
        return cover.replaceAll("/view/(subject|celebrity)/(s|m|l|raw)/", "/view/$1/" + size + "/");
    }

    /**
     * 去掉地址上可能已经挂着的 {@code @User-Agent=..@Referer=..} 尾巴。
     *
     * <p>缓存里存的是豆瓣原始响应（{@code UserFragment} 也是原样存的），本不该有这段。
     * 但万一某个写入路径存了处理过的串，再拼一次就变成两段请求头，
     * {@code ImgUtil} 解析出来是重复的 Referer，图直接挂掉——比糊更糟。
     */
    private static String stripHeaders(String cover) {
        int cut = cover.indexOf('@');
        return cut < 0 ? cover : cover.substring(0, cut);
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(key).getAsString();
        } catch (Throwable th) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 简介

    /** 从条目里取豆瓣 subject id；不是豆瓣条目返回空串 */
    public static String subjectIdOf(Movie.Video video) {
        if (video == null || TextUtils.isEmpty(video.id) || !video.id.startsWith(ID_PREFIX)) {
            return "";
        }
        return video.id.substring(ID_PREFIX.length()).trim();
    }

    /**
     * 取一条豆瓣简介。命中缓存就直接回调，同一 id 正在飞就排队等那一份。
     *
     * <p>回调在主线程；{@code callback} 可以为 null（只预热，不要结果）。
     */
    public static void loadIntro(String subjectId, IntroCallback callback) {
        final String id = subjectId == null ? "" : subjectId.trim();
        if (id.isEmpty()) {
            if (callback != null) {
                callback.onIntro(null);
            }
            return;
        }
        String cached = INTRO_CACHE.get(id);
        if (cached != null) {
            if (callback != null) {
                callback.onIntro(cached);
            }
            return;
        }
        List<IntroCallback> waiting = INTRO_WAITING.get(id);
        if (waiting != null) {
            if (callback != null) {
                waiting.add(callback);
            }
            return;
        }
        waiting = new ArrayList<>();
        if (callback != null) {
            waiting.add(callback);
        }
        INTRO_WAITING.put(id, waiting);
        fetchIntro(id);
    }

    /** 预热下一条，不关心结果 */
    public static void prefetchIntro(String subjectId) {
        loadIntro(subjectId, null);
    }

    private static void fetchIntro(final String subjectId) {
        try {
            OkGo.<String>get(INTRO_API + subjectId)
                    .headers("User-Agent", INTRO_UA)
                    .headers("Referer", INTRO_REFERER)
                    .headers("Accept", "application/json")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            return response.body() == null ? "" : response.body().string();
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String intro = parseIntro(response.body());
                            if (intro == null) {
                                // 回包不是能认的 JSON（被拦或接口改版），当失败，不写缓存
                                finishIntro(subjectId, null, false);
                            } else {
                                finishIntro(subjectId, intro, true);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            finishIntro(subjectId, null, false);
                        }
                    });
        } catch (Throwable th) {
            finishIntro(subjectId, null, false);
        }
    }

    /**
     * @return 简介正文；豆瓣没给时是空串；回包不是 JSON 时返回 null
     */
    private static String parseIntro(String json) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            JsonObject root = new Gson().fromJson(json, JsonObject.class);
            if (root == null || (!root.has("intro") && !root.has("title"))) {
                return null;
            }
            return normalizeIntro(optString(root, "intro"));
        } catch (Throwable th) {
            return null;
        }
    }

    /**
     * 简介原文带换行和富文本痕迹。Hero 只给三行，换行会让第一段独占版面，
     * 先压成一行再交给 TextView 自己截断。
     */
    private static String normalizeIntro(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        return raw.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void finishIntro(final String subjectId, final String intro, final boolean cache) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (cache) {
                    INTRO_CACHE.put(subjectId, intro == null ? "" : intro);
                }
                List<IntroCallback> waiting = INTRO_WAITING.remove(subjectId);
                if (waiting == null) {
                    return;
                }
                for (IntroCallback callback : waiting) {
                    callback.onIntro(intro);
                }
            }
        });
    }

    private static void postResult(final Callback cb, final List<Movie.Video> items, final boolean fromCache) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(items, fromCache);
            }
        });
    }

    private static void postError(final Callback cb, final Throwable th) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(th);
            }
        });
    }
}

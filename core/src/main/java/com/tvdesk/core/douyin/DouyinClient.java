package com.tvdesk.core.douyin;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.tvdesk.core.douyin.model.DyComment;
import com.tvdesk.core.douyin.model.DyItem;
import com.tvdesk.core.douyin.model.DyProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 抖音 Web 端接口（免登录只读优先）。
 *
 * <h3>为什么走 web 接口</h3>
 * App 接口（{@code aweme.snssdk.com}）要 {@code X-Argus}/{@code X-Gorgon} 一组 native 签名，
 * 秘钥在 APK 里按版本轮换，TV 上没法自造设备指纹。Web 接口这条路的门槛低得多：
 * 一个桌面 Chrome 的 UA + 按接口分好的 {@code Referer} + 一个 {@code ttwid} 设备票。
 * 实测（2026-10）：
 * <b>推荐流 {@code tab/feed} 和相关推荐 {@code aweme/related} 完全免签可用</b>，
 * 详情、搜索、用户页要登录 cookie 里的 {@code UIFID}，只补 {@code a_bogus} 签名过不去。
 *
 * <h3>刷流的策略</h3>
 * 匿名 {@code tab/feed} 一次只回 2 条（把 {@code count} 提到 15 也一样，是服务端的匿名配额）。
 * 所以信息流不是「一页页翻 feed」，而是 feed 起头 + 每条不断问 {@code related} 续——
 * 一次 10 条，够刷满一屏。{@link #next()} 就是干这个的。
 *
 * <h3>风控</h3>
 * 抖音的拒绝有两种完全不同的表现，都归到 {@link DouyinException}：
 * HTTP 403 且 body 里有 {@code Uifid}，或 HTTP 200 但 {@code status_code=2483}。
 * 前者是缺登录 cookie，硬重试没有意义；后者是签名／cookie 一起缺。
 *
 * <h3>线程</h3>
 * 所有方法都是阻塞的，调用方负责放后台线程（和 {@code BiliClient}、{@code NeteaseClient} 一致）。
 */
public class DouyinClient {

    private static final String TAG = "DouyinClient";

    private static final String API = "https://www.douyin.com";
    private static final String TTWID_API = "https://ttwid.bytedance.com/ttwid/union/register/";

    /**
     * 桌面 Chrome 的 UA。web 接口只认浏览器；电视 UA 会被当异常客户端直接挡。
     * 参数表里的 {@code browser_version} 必须和这里的版本号一致，否则风控对不上。
     */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";

    /**
     * 平板 Chrome 的 UA。推荐流按「平板模式」去要——和网页版桌面推荐相比，
     * pad 频道的候选池更偏长视频和横屏内容，正好适合电视大屏。
     *
     * <p>不带 {@code Mobile} 标记（那是手机），UA 里的 Android/Chrome 版本必须和
     * {@link #PAD_BASE} 里的 {@code browser_version}/{@code os_version} 对齐，
     * 否则风控会按「客户端自述」和「参数自述」不一致来挡。
     */
    private static final String PAD_UA = "Mozilla/5.0 (Linux; Android 13; SM-X700) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String PREFS = "douyin";
    private static final String KEY_COOKIE = "cookie";
    private static final String KEY_TTWID = "ttwid";

    /** 每个请求都带的固定 header（缺一项就可能被判定为非浏览器） */
    private static final Map<String, String> HEADERS = new LinkedHashMap<>();

    /** 平板口径的 header，只给推荐流/相关推荐用 */
    private static final Map<String, String> PAD_HEADERS = new LinkedHashMap<>();

    /** 所有 {@code /aweme/v1|v2/web/…} 共用的 25 个基础参数 */
    private static final Map<String, String> BASE = new LinkedHashMap<>();

    /** 平板口径的基础参数：在 {@link #BASE} 上覆盖平台相关的几项 */
    private static final Map<String, String> PAD_BASE = new LinkedHashMap<>();

    /** 接口 → Referer。{@code sec-fetch-site: same-origin} 之下 Referer 不对会被拦 */
    private static final Map<String, String> REFERER = new LinkedHashMap<>();

    /**
     * 要 {@code a_bogus} 才能拿到内容的接口。
     *
     * <p>判定依据是实测：推荐流和相关推荐不在这里（免签就能出内容），
     * 评论在没有签名时给的是「HTTP 200 + 空 body」，点赞给的是 {@code bd-tt-error-code}。
     * 签名器算不出来时这几个接口照原样发请求——顶多回到「空响应」那条提示，
     * 不该让整条链路连不上。
     */
    private static final java.util.Set<String> SIGNED = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "/aweme/v1/web/comment/list/",
                    "/aweme/v1/web/aweme/detail/",
                    "/aweme/v1/web/general/search/single/",
                    "/aweme/v1/web/search/item/",
                    "/aweme/v1/web/commit/item/digg/",
                    "/aweme/v1/web/aweme/collect/",
                    "/aweme/v1/web/user/profile/self/"));

    /** 路径签名形如 {@code douyinvod.com/<32 hex>/<8 hex>/}，是播放地址里的最优解 */
    private static final Pattern PATH_SIGNED =
            Pattern.compile("douyinvod\\.com/[0-9a-f]{32}/[0-9a-f]{8}/");

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    static {
        HEADERS.put("accept", "application/json, text/plain, */*");
        HEADERS.put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8");
        HEADERS.put("cache-control", "no-cache");
        HEADERS.put("pragma", "no-cache");
        HEADERS.put("priority", "u=1, i");
        HEADERS.put("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\"");
        HEADERS.put("sec-ch-ua-mobile", "?0");
        HEADERS.put("sec-ch-ua-platform", "\"Windows\"");
        HEADERS.put("sec-fetch-dest", "empty");
        HEADERS.put("sec-fetch-mode", "cors");
        HEADERS.put("sec-fetch-site", "same-origin");
        HEADERS.put("user-agent", UA);

        BASE.put("device_platform", "webapp");
        BASE.put("aid", "6383");
        BASE.put("channel", "channel_pc_web");
        BASE.put("update_version_code", "170400");
        BASE.put("pc_client_type", "1");
        BASE.put("pc_libra_divert", "Windows");
        BASE.put("support_h265", "1");
        BASE.put("support_dash", "1");
        BASE.put("version_code", "170400");
        BASE.put("version_name", "17.4.0");
        BASE.put("cookie_enabled", "true");
        BASE.put("screen_width", "2560");
        BASE.put("screen_height", "1440");
        BASE.put("browser_language", "zh-CN");
        BASE.put("browser_platform", "Win32");
        BASE.put("browser_name", "Chrome");
        BASE.put("browser_version", "147.0.0.0");
        BASE.put("browser_online", "true");
        BASE.put("engine_name", "Blink");
        BASE.put("engine_version", "147.0.0.0");
        BASE.put("os_name", "Windows");
        BASE.put("os_version", "10");
        BASE.put("platform", "PC");
        BASE.put("downlink", "10");
        BASE.put("effective_type", "4g");
        BASE.put("round_trip_time", "50");

        REFERER.put("/aweme/v1/web/tab/feed/", API + "/?recommend=1");
        REFERER.put("/aweme/v1/web/general/search/single/", API + "/search/");
        REFERER.put("/aweme/v1/web/search/item/", API + "/search/");
        REFERER.put("/aweme/v1/web/hot/search/list/", API + "/search/");
        REFERER.put("/aweme/v1/web/aweme/detail/", API + "/");
        REFERER.put("/aweme/v1/web/user/profile/self/", API + "/user/self");

        PAD_HEADERS.putAll(HEADERS);
        PAD_HEADERS.put("sec-ch-ua",
                "\"Not-A.Brand\";v=\"99\", \"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"");
        PAD_HEADERS.put("sec-ch-ua-mobile", "?0");
        PAD_HEADERS.put("sec-ch-ua-platform", "\"Android\"");
        PAD_HEADERS.put("user-agent", PAD_UA);

        PAD_BASE.putAll(BASE);
        PAD_BASE.put("channel", "channel_pad_web");
        PAD_BASE.put("pc_client_type", "2");
        PAD_BASE.put("pc_libra_divert", "Android");
        PAD_BASE.put("screen_width", "2560");
        PAD_BASE.put("screen_height", "1600");
        PAD_BASE.put("browser_platform", "Tablet");
        PAD_BASE.put("browser_version", "124.0.0.0");
        PAD_BASE.put("engine_version", "124.0.0.0");
        PAD_BASE.put("os_name", "Android");
        PAD_BASE.put("os_version", "13");
        PAD_BASE.put("platform", "Tablet");
    }

    private static volatile DouyinClient instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final OkHttpClient http;
    /** 导入的登录 cookie（{@code k=v; k=v}），没登录就是空 */
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String ttwid = "";

    private DouyinClient(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        http = new OkHttpClient.Builder()
                // 和 B 站同口径：电视网络差时把等待压短，失败早点摊开，别让人对着转圈
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        restore();
    }

    public static DouyinClient get(Context context) {
        if (instance == null) {
            synchronized (DouyinClient.class) {
                if (instance == null) {
                    instance = new DouyinClient(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // ---------------------------------------------------------------- 凭据

    private void restore() {
        ttwid = prefs.getString(KEY_TTWID, "");
        cookies.clear();
        parseCookie(prefs.getString(KEY_COOKIE, ""));
    }

    private void parseCookie(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        // 按 ; 和换行两种分隔都切一遍：从 devtools 表格里逐行复制出来的 cookie 是换行分隔的
        for (String part : raw.split("[;\r\n]+")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq > 0) {
                cookies.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
            }
        }
    }

    public String rawCookie() {
        return prefs.getString(KEY_COOKIE, "");
    }

    /**
     * 导入浏览器导出的整串 cookie。
     *
     * <p>必须是<b>从浏览器插件（Cookie-Editor 之类）导出</b>的那份，不能用 {@code document.cookie} 取——
     * {@code sessionid}、{@code UIFID} 这些关键项是 HttpOnly，脚本读不到。
     */
    public void setCookie(String raw) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_COOKIE, raw == null ? "" : raw);
        editor.apply();
        cookies.clear();
        parseCookie(raw);
    }

    public void clearCookie() {
        setCookie("");
    }

    /**
     * 能不能用详情／搜索／用户页。
     *
     * <p>只看 {@code UIFID}：实测 403 的原文就是 {@code Uifid Not Found}，
     * 而 {@code sessionid} 单独存在并不足以放行。
     */
    public boolean hasLoginCookie() {
        return !TextUtils.isEmpty(cookies.get("UIFID")) || !TextUtils.isEmpty(cookies.get("sessionid"));
    }

    /** 缺哪一项，给 UI 提示用；齐全返回空串 */
    public String missingCookieHint() {
        return hasLoginCookie() ? "" : "UIFID（要浏览器插件导出，document.cookie 取不到）";
    }

    /**
     * 拿设备票 {@code ttwid}。这个接口不要签名、不要 cookie，一次拿到长期复用。
     *
     * @return 是否可用；失败不抛错，因为拿不到 ttwid 也只是退化成「完全匿名」请求
     */
    public boolean ensureTtwid() {
        if (!TextUtils.isEmpty(ttwid)) {
            return true;
        }
        String body = "{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,"
                + "\"service\":\"www.ixigua.com\",\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},"
                + "\"cbUrlProtocol\":\"https\",\"union\":true}";
        Request request = new Request.Builder()
                .url(TTWID_API)
                .post(RequestBody.create(JSON, body))
                .header("user-agent", UA)
                .header("content-type", "application/json")
                .header("origin", API)
                .header("referer", API + "/")
                .build();
        try (Response response = http.newCall(request).execute()) {
            String picked = null;
            for (String setCookie : response.headers("Set-Cookie")) {
                String first = setCookie.split(";")[0];
                int eq = first.indexOf('=');
                if (eq > 0 && "ttwid".equals(first.substring(0, eq).trim())) {
                    picked = first.substring(eq + 1).trim();
                }
            }
            if (picked == null) {
                Log.w(TAG, "ttwid 注册失败 http=" + response.code());
                return false;
            }
            ttwid = picked;
            prefs.edit().putString(KEY_TTWID, ttwid).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ttwid 注册异常", e);
            return false;
        }
    }

    // ---------------------------------------------------------------- 接口

    /**
     * 推荐流。按平板频道要（{@link #PAD_BASE}）。匿名只回 2 条（服务端配额），
     * 所以这个方法只用来起头，续流走 {@link #related}。
     *
     * @param seenIds 已经看过的作品 id，逗号分隔回传给服务端去重
     */
    public List<DyItem> feed(int count, String seenIds) throws IOException {
        Map<String, String> params = padBase();
        params.put("filterGids", seenIds == null ? "" : seenIds);
        params.put("tag_id", "");
        params.put("share_aweme_id", "");
        params.put("live_insert_type", "");
        params.put("count", String.valueOf(count));
        params.put("refresh_index", "1");
        params.put("video_type_select", "1");
        params.put("pull_type", "0");
        params.put("view_count", "0");
        params.put("plug_block", "0");
        params.put("aweme_pc_rec_raw_data", "{\"is_client\":false,\"is_dash_user\":true,"
                + "\"related_recommend\":false,\"is_xigua_user\":false,\"seo_info\":\"\"}");
        return items(getJson("/aweme/v1/web/tab/feed/", params, true));
    }

    /** 相关推荐：一次 10 条，实测免签可用，是竖屏刷流的主力；同样走平板口径 */
    public List<DyItem> related(String awemeId) throws IOException {
        if (TextUtils.isEmpty(awemeId)) {
            return new ArrayList<>();
        }
        Map<String, String> params = padBase();
        params.put("aweme_id", awemeId);
        params.put("offset", "0");
        params.put("count", "10");
        params.put("relate_source", "related");
        return items(getJson("/aweme/v1/web/aweme/related/", params, true));
    }

    /** 作品详情。需要登录 cookie，缺了抛 {@link DouyinException} 且 {@code needsLogin=true} */
    public DyItem detail(String awemeId) throws IOException {
        Map<String, String> params = base();
        params.put("aweme_id", awemeId);
        params.put("aid", "6383");
        JSONObject json = getJson("/aweme/v1/web/aweme/detail/", params);
        JSONObject detail = json.optJSONObject("aweme_detail");
        return detail == null ? null : parse(detail);
    }

    /**
     * 当前 cookie 对应哪个账号——全站最便宜的登录态探针。
     *
     * <p>它值一次 GET 的理由：cookie 过期这件事，本来只有等用户按了点赞才知道。
     *
     * <p>注意这个接口<b>不区分</b>「没登录」和「登录了但资料为空」：没登录时它照样回 200，
     * 只是 {@code user} 是空的。所以 {@link DyProfile#valid()} 为 false 才是「cookie 不认了」，
     * 而抛出来的其它错误（网络、风控、签名）一律只是「没探出来」，
     * UI 不许拿它当过期用——否则一句假消息会把好 cookie 的用户赶去重导一遍。
     */
    public DyProfile selfProfile() throws IOException {
        Map<String, String> params = base();
        JSONObject json = getJson("/aweme/v1/web/user/profile/self/", params);
        DyProfile profile = new DyProfile();
        JSONObject user = json.optJSONObject("user");
        if (user == null) {
            // 有的版本把资料放在 user_info / data 下，两个都认一下
            user = json.optJSONObject("user_info");
        }
        if (user != null) {
            profile.uid = user.optString("uid", "");
            profile.user = user.optString("nickname", "");
            profile.avatar = firstUrl(user.optJSONObject("avatar_thumb"));
        }
        return profile;
    }

    /** 搜索。需要登录 cookie + a_bogus，当前只有导了 cookie 的设备能用 */    public List<DyItem> search(String keyword, int offset, int count) throws IOException {
        Map<String, String> params = base();
        params.put("search_channel", "aweme_video_web");
        params.put("enable_history", "1");
        params.put("sort_type", "0");
        params.put("publish_time", "0");
        params.put("filter_duration", "0");
        params.put("search_range", "0");
        params.put("keyword", keyword);
        params.put("search_source", "normal_search");
        params.put("query_correct_type", "1");
        params.put("is_filter_search", "1");
        params.put("from_group_id", "");
        params.put("offset", String.valueOf(offset));
        params.put("count", String.valueOf(count));
        params.put("need_filter_settings", "1");
        params.put("list_type", "single");
        JSONObject json = getJson("/aweme/v1/web/general/search/single/", params);
        JSONArray list = json.optJSONArray("data");
        List<DyItem> out = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject one = list.optJSONObject(i);
                // 搜索结果里混着用户、直播卡片，只留 aweme 类型
                if (one != null && one.has("aweme_id")) {
                    DyItem item = parse(one);
                    if (item != null && !TextUtils.isEmpty(item.id)) {
                        out.add(item);
                    }
                }
            }
        }
        return out;
    }

    /**
     * 一级评论。<b>要 {@code a_bogus}，不要登录 cookie</b>。
     *
     * <p>2026-10-21 实测（同一个真实 aweme_id，只带 ttwid）：不带签名时 HTTP 200 但
     * <b>响应体长度 0</b>；同一串参数加上 {@code a_bogus} 就是 {@code status_code=0} + 20 条
     * （total 一千多）。所以这一项的门槛是签名而不是凭据，由 {@link DouyinSigner} 在本机算。
     * 签名器起不来时这里拿到的还是那条「空响应」异常，界面按原样说一句就行。
     */
    public List<DyComment> comments(String awemeId, long cursor) throws IOException {
        Map<String, String> params = base();
        params.put("aweme_id", awemeId);
        params.put("cursor", String.valueOf(cursor));
        params.put("count", "20");
        params.put("item_type", "0");
        JSONObject json = getJson("/aweme/v1/web/comment/list/", params);
        List<DyComment> out = new ArrayList<>();
        JSONArray list = json.optJSONArray("comments");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject one = list.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                DyComment comment = new DyComment();
                comment.cid = one.optString("cid", "");
                comment.text = one.optString("text", "");
                comment.digg = one.optLong("digg_count");
                comment.createdAt = one.optLong("create_time") * 1000L;
                JSONObject user = one.optJSONObject("user");
                if (user != null) {
                    comment.user = user.optString("nickname", "");
                    comment.avatar = firstUrl(user.optJSONObject("avatar_thumb"));
                }
                out.add(comment);
            }
        }
        return out;
    }

    /**
     * 点赞 / 取消点赞。
     *
     * <p>这是本客户端里唯一写操作的接口，也是唯一<b>明确要登录态</b>的：
     * 实测没有 {@code UIFID} 时它直接 403，Web 端还额外要 {@code __ac_signature}。
     * 所以它按「尽力而为」设计——本地界面先改状态、这里失败再改回去，
     * 不导 cookie 的用户照样能看完视频，只是点赞按钮会提示要登录。
     *
     * @param on {@code true} 点赞，{@code false} 取消
     */
    public void digg(String awemeId, boolean on) throws IOException {
        if (TextUtils.isEmpty(awemeId)) {
            throw new DouyinException("缺少作品 ID", false);
        }
        Map<String, String> params = base();
        params.put("aweme_id", awemeId);
        // type=1 是作品点赞；request_id 是服务端用来去重的，随便给一个即可
        params.put("type", on ? "1" : "0");
        params.put("item_type", "0");
        params.put("from_user_page", "0");
        params.put("request_id", String.valueOf(System.currentTimeMillis()));
        postJson("/aweme/v1/web/commit/item/digg/", params, API + "/video/" + awemeId + "/");
    }

    /**
     * 收藏 / 取消收藏。
     *
     * <p>和 {@link #digg} 同一类：要登录态，参数里只有 {@code action} 一位在动（1 收、0 取消）。
     * referer 用站内首页而不是作品页——照浏览器实际发的那份来，头对不上一次就被判成爬虫。
     *
     * @param on {@code true} 收藏，{@code false} 取消
     */
    public void collect(String awemeId, boolean on) throws IOException {
        if (TextUtils.isEmpty(awemeId)) {
            throw new DouyinException("缺少作品 ID", false);
        }
        Map<String, String> params = base();
        params.put("aweme_id", awemeId);
        params.put("aweme_type", "0");
        params.put("action", on ? "1" : "0");
        postJson("/aweme/v1/web/aweme/collect/", params, API + "/");
    }

    // ---------------------------------------------------------------- 播放地址

    /**
     * 从一批候选地址里按优先级挑一个。
     *
     * <p>顺序是：带路径签名的直连 → {@code /aweme/v1/play/} 中转 → 带 {@code signature=} 的
     * douyinvod → 列表首个。前两类寿命长，第三类经常几小时就失效。
     */
    static String bestUrl(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return "";
        }
        for (String url : urls) {
            if (url != null && PATH_SIGNED.matcher(url).find()) {
                return url;
            }
        }
        for (String url : urls) {
            if (url != null && url.contains("douyin.com/aweme/v1/play")) {
                return url;
            }
        }
        for (String url : urls) {
            if (url != null && url.contains("signature=") && url.contains("douyinvod.com")) {
                return url;
            }
        }
        String first = urls.get(0);
        return first == null ? "" : first;
    }

    /**
     * 按「编解码偏好 + 档位」挑一条码流，再按 {@link #bestUrl} 选地址。
     *
     * <p>两层级联的兜底：档位（1080/720/540）匹配不到就忽略档位再试一次，
     * 还匹配不到就退回 {@code play_addr}／{@code play_addr_265}。
     * TV 上码率列表经常一半是重复档位，所以取同档里 {@code bit_rate} 最大的那条。
     */
    private String resolvePlayUrl(JSONObject video) {
        int codec = prefs.getInt("codec_mode", 0);
        String gear = prefs.getString("gear_name", "");
        JSONArray bitRates = video.optJSONArray("bit_rate");
        String url = fromBitRates(bitRates, gear, codec, true);
        if (TextUtils.isEmpty(url)) {
            url = fromBitRates(bitRates, gear, codec, false);
        }
        if (TextUtils.isEmpty(url)) {
            JSONObject addr = codec == 2 ? video.optJSONObject("play_addr_265") : video.optJSONObject("play_addr");
            if (addr == null) {
                addr = video.optJSONObject("play_addr");
            }
            url = bestUrl(urlList(addr));
        }
        return url == null ? "" : url;
    }

    /**
     * @param strictGear 为 false 时忽略档位，只按编解码挑，用来做「没有 1080 就退一档」
     */
    private String fromBitRates(JSONArray bitRates, String gear, int codec, boolean strictGear) {
        if (bitRates == null || bitRates.length() == 0) {
            return "";
        }
        List<String> best = null;
        long bestBit = -1;
        int bestRank = Integer.MIN_VALUE;
        String bestGear = "";
        for (int i = 0; i < bitRates.length(); i++) {
            JSONObject one = bitRates.optJSONObject(i);
            if (one == null) {
                continue;
            }
            int h265 = one.optInt("is_h265");
            // codec_mode：0 任意 / 1 只要 H264（老盒子兼容） / 2 只要 H265（省带宽）
            if (codec == 1 && h265 == 1 || codec == 2 && h265 == 0) {
                continue;
            }
            String gearName = one.optString("gear_name", "");
            if (strictGear && !TextUtils.isEmpty(gear) && !gearName.startsWith(gear)) {
                continue;
            }
            long bit = one.optLong("bit_rate");
            int rank = resolutionRank(gearName);
            List<String> urls = urlList(one.optJSONObject("play_addr"));
            // 先比分辨率再比码率：2K 的低码率版本仍然比 1080P 的高码率版本清楚，
            // 默认档位要的是「最高清晰度」而不是「最大文件」
            if (!urls.isEmpty()
                    && (rank > bestRank || rank == bestRank && bit > bestBit)) {
                bestRank = rank;
                bestBit = bit;
                best = urls;
                bestGear = gearName;
            }
        }
        if (best == null) {
            return "";
        }
        lastGear = bestGear;
        return bestUrl(best);
    }

    /** 上一次 {@link #resolvePlayUrl} 选中的档位名，回填到卡片上给清晰度角标用 */
    private String lastGear = "";

    /**
     * 档位名里的分辨率，数字越大越清楚。
     *
     * <p>抖音的 gear_name 形如 {@code normal_1080_0}、{@code adapt_lowest_1440_1}，
     * 中间那一段就是分辨率。认不出返回 -1，让它排在所有能认出的档位后面。
     */
    static int resolutionRank(String gearName) {
        if (TextUtils.isEmpty(gearName)) {
            return -1;
        }
        String lower = gearName.toLowerCase(Locale.ROOT);
        if (lower.contains("2160") || lower.contains("4k")) {
            return 5;
        }
        if (lower.contains("1440") || lower.contains("2k")) {
            return 4;
        }
        if (lower.contains("1080")) {
            return 3;
        }
        if (lower.contains("720")) {
            return 2;
        }
        if (lower.contains("540")) {
            return 1;
        }
        if (lower.contains("480")) {
            return 1;
        }
        if (lower.contains("360")) {
            return 0;
        }
        return -1;
    }

    private static List<String> urlList(JSONObject addr) {
        List<String> out = new ArrayList<>();
        JSONArray array = addr == null ? null : addr.optJSONArray("url_list");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i, "");
                if (!TextUtils.isEmpty(url)) {
                    out.add(url.replace("\\u002F", "/"));
                }
            }
        }
        return out;
    }

    private static String firstUrl(JSONObject obj) {
        List<String> urls = urlList(obj);
        return urls.isEmpty() ? "" : urls.get(0);
    }

    // ---------------------------------------------------------------- 解析

    private Map<String, String> base() {
        Map<String, String> params = new LinkedHashMap<>(BASE);
        return params;
    }

    /** 平板口径的参数，只给推荐流/相关推荐用；签名接口必须留在桌面口径上 */
    private Map<String, String> padBase() {
        return new LinkedHashMap<>(PAD_BASE);
    }

    private List<DyItem> items(JSONObject json) {
        List<DyItem> out = new ArrayList<>();
        JSONArray list = json.optJSONArray("aweme_list");
        if (list == null) {
            return out;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject one = list.optJSONObject(i);
            if (one == null) {
                continue;
            }
            DyItem item = parse(one);
            // 直播卡片（aweme_type=2）没有 play_addr，首版直接跳过，避免竖屏里出现黑屏
            if (item != null && !TextUtils.isEmpty(item.id) && !TextUtils.isEmpty(item.playUrl)) {
                out.add(item);
            }
        }
        return out;
    }

    private DyItem parse(JSONObject aweme) {
        DyItem item = new DyItem();
        item.id = aweme.optString("aweme_id", "");
        item.desc = aweme.optString("desc", "");
        item.awemeType = aweme.optInt("aweme_type");
        item.diggStatus = aweme.optInt("digg_status");
        item.collectStatus = aweme.optInt("collect_status");

        JSONObject statistics = aweme.optJSONObject("statistics");
        if (statistics != null) {
            item.digg = statistics.optLong("digg_count");
            item.comment = statistics.optLong("comment_count");
            item.share = statistics.optLong("share_count");
            item.collect = statistics.optLong("collect_count");
        }
        JSONObject author = aweme.optJSONObject("author");
        if (author != null) {
            item.author = author.optString("nickname", "");
            item.authorUid = author.optString("sec_uid", "");
            JSONObject avatarObj = author.optJSONObject("avatar_medium");
            if (avatarObj == null) {
                avatarObj = author.optJSONObject("avatar_thumb");
            }
            item.avatar = firstUrl(avatarObj);
        }
        JSONObject mix = aweme.optJSONObject("mix_info");
        if (mix != null) {
            item.mixName = mix.optString("mix_name", "");
        }
        JSONObject music = aweme.optJSONObject("music");
        if (music != null) {
            item.musicTitle = music.optString("title", "");
        }

        JSONObject video = aweme.optJSONObject("video");
        if (video == null) {
            return item;
        }
        item.cover = firstUrl(video.optJSONObject("cover"));
        if (TextUtils.isEmpty(item.cover)) {
            item.cover = firstUrl(video.optJSONObject("origin_cover"));
        }
        item.durationMs = video.optLong("duration");
        item.width = video.optInt("width");
        item.height = video.optInt("height");
        item.playUrl = resolvePlayUrl(video);
        item.gearName = lastGear;
        return item;
    }

    // ---------------------------------------------------------------- 传输

    private JSONObject getJson(String path, Map<String, String> params) throws IOException {
        return getJson(path, params, false);
    }

    /**
     * @param pad {@code true} 时整套 header/参数按平板口径发（推荐流、相关推荐），
     *            其它接口一律走桌面口径——签名接口换口径会直接算不对签名
     */
    private JSONObject getJson(String path, Map<String, String> params, boolean pad)
            throws IOException {
        String referer = REFERER.get(path);
        if (referer == null) {
            // 其余接口都在作品页里发起，Referer 就是那条视频自己的地址
            String awemeId = params.get("aweme_id");
            referer = TextUtils.isEmpty(awemeId) ? API + "/" : API + "/video/" + awemeId + "/";
        }
        ensureTtwid();
        Request.Builder builder = new Request.Builder()
                .url(API + path + "?" + signedQuery(path, params))
                .get();
        decorate(builder, referer, pad);
        return send(builder.build());
    }

    /** GET 之外还要 POST 的接口（点赞）走这条路：查询串照旧，只是多个空请求体 */
    private JSONObject postJson(String path, Map<String, String> params, String referer) throws IOException {
        ensureTtwid();
        Request.Builder builder = new Request.Builder()
                .url(API + path + "?" + signedQuery(path, params))
                .post(RequestBody.create(JSON, ""));
        decorate(builder, referer);
        return send(builder.build());
    }

    /**
     * 拼查询串，需要签名的接口顺手把 {@code a_bogus} 补上。
     *
     * <p>顺序很重要：签名算的是<b>没有 a_bogus 的那一份原文</b>，所以先 {@code query(params)} 再签，
     * 签完才把结果编码后接上去。反过来（先塞进 params 再签）签的是自己不签的对象，服务端一定验不过。
     *
     * <p>签名器给不出来就原样返回：这时接口会回到「空响应」那条提示，
     * 而不是整条链路连请求都发不出去。
     */
    private String signedQuery(String path, Map<String, String> params) {
        String query = query(params);
        if (!SIGNED.contains(path)) {
            return query;
        }
        String bogus = DouyinSigner.get(app).aBogus(query);
        if (TextUtils.isEmpty(bogus)) {
            return query;
        }
        // a_bogus 的字符集是 A-Za-z0-9+/=，其中 + 和 / 必须编码：
        // 不编码的话 + 到服务端被解成空格，签名当场失效
        try {
            return query + "&a_bogus=" + java.net.URLEncoder.encode(bogus, "UTF-8");
        } catch (Exception e) {
            return query + "&a_bogus=" + bogus;
        }
    }

    private void decorate(Request.Builder builder, String referer) {
        decorate(builder, referer, false);
    }

    private void decorate(Request.Builder builder, String referer, boolean pad) {
        Map<String, String> headers = pad ? PAD_HEADERS : HEADERS;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        builder.header("referer", referer);
        String cookie = cookieHeader();
        if (!TextUtils.isEmpty(cookie)) {
            builder.header("cookie", cookie);
        }
    }

    private JSONObject send(Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (text.length() == 0 && response.code() == 200) {
                // 缺签名的接口（评论就是）不给 JSON，也不给错误页，就是一个空 body
                throw new DouyinException("抖音返回了空响应，这个接口要网页签名", false);
            }
            String blocked = response.header("bd-tt-error-code");
            if (!TextUtils.isEmpty(blocked) && !"0".equals(blocked)) {
                // 8 是「你带着 cookie 来了，但这份不认」：实测点赞、收藏、我的资料三个要登录态的
                // 接口喂一份假 UIFID 全都回这个码，而匿名刷流从来不会。归到 needsLogin，
                // UI 才有地方把「重新导一份」这条路递给用户，而不是干巴巴一句「错误码 8」。
                boolean login = "8".equals(blocked);
                throw new DouyinException(login
                        ? "抖音不认这份 cookie（错误码 8）"
                        : "抖音返回错误码 " + blocked, login);
            }
            if (response.code() == 403 || text.contains("Uifid Not Found")) {
                throw new DouyinException("这个接口要登录 cookie（UIFID）", true);
            }
            if (!response.isSuccessful()) {
                throw new DouyinException("抖音接口 HTTP " + response.code(), false);
            }
            JSONObject json = new JSONObject(text);
            int statusCode = json.optInt("status_code");
            if (statusCode == 2483) {
                throw new DouyinException("抖音风控：需要登录 cookie 与请求签名", true);
            }
            if (statusCode != 0 && json.opt("aweme_list") == null && json.opt("comments") == null) {
                String message = json.optString("status_msg", "");
                if (TextUtils.isEmpty(message)) {
                    message = "接口返回 status_code=" + statusCode;
                }
                throw new DouyinException(message, false);
            }
            return json;
        } catch (org.json.JSONException e) {
            // 被风控时抖音经常回 HTML 验证页而不是 JSON
            throw new DouyinException("抖音返回的不是 JSON，可能被风控", false);
        }
    }

    /**
     * 拼查询串。
     *
     * <p>故意<b>不做 URL 编码</b>直接交给 OkHttp 去规范化：签名要的是「服务端看到的那一份原文」，
     * 提前编码会让以后接 {@code a_bogus} 时对不上。
     */
    private static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(value);
        }
        return sb.toString();
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(ttwid)) {
            sb.append("ttwid=").append(ttwid);
        }
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if ("ttwid".equals(entry.getKey())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 播放头

    public static String userAgent() {
        return UA;
    }

    /**
     * 播 {@code douyinvod.com} 的直连地址时要带的头。
     *
     * <p>CDN 会校验 {@code Referer} 和 UA，缺了直接回 403；导了 cookie 的话也要一起带上，
     * 否则同一批地址在有 cookie 的设备能放、在播放器里放不了。
     */
    public Map<String, String> playHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", API + "/");
        String cookie = cookieHeader();
        if (!TextUtils.isEmpty(cookie)) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    // ---------------------------------------------------------------- 设置

    /**
     * 提前把签名环境备好。
     *
     * <p>给 UI 在进页面时调用：首次取签要加载三个脚本，赶在用户按到评论/点赞之前做完，
     * 那一下才不用等。异步且不抛，备不好真正用时还会再试一次。
     */
    public void prepareSigner() {
        DouyinSigner.get(app).prepare();
    }

    /** 0 任意编解码 / 1 只要 H264 / 2 只要 H265 */
    public void setCodecMode(int mode) {
        prefs.edit().putInt("codec_mode", mode).apply();
    }

    /** 档位偏好，形如 {@code 1080}／{@code 720}／{@code 540}，空串表示自动 */
    public void setGearName(String gear) {
        prefs.edit().putString("gear_name", gear == null ? "" : gear).apply();
    }
}

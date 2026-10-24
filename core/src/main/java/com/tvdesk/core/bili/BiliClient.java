package com.tvdesk.core.bili;

import android.content.Context;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.tvdesk.core.bili.model.BiliAccount;
import com.tvdesk.core.bili.model.BiliCard;
import com.tvdesk.core.bili.model.BiliDetail;
import com.tvdesk.core.bili.model.BiliEpisode;
import com.tvdesk.core.bili.model.BiliStream;
import com.tvdesk.core.bili.model.LiveArea;
import com.tvdesk.core.bili.model.LiveRoom;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import okhttp3.CacheControl;
import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 哔哩哔哩 Web 端接口。
 *
 * <h3>为什么走 Web 接口而不是 App 接口</h3>
 * App 接口（{@code app.bilibili.com}）要 {@code appkey+secret} 签名，秘钥是官方客户端里
 * 逆向出来的、按版本轮换；TV 端也没有账号体系可以配合。Web 接口只需要：
 * 一个浏览器 UA、{@code Referer}、一对 {@code buvid3/buvid4} 设备票，
 * 加上少数接口的 wbi 签名（{@link BiliSigner}，纯 Java）。
 * 播放地址和搜索都在这一套里，实测（2026-10）免登录可用。
 *
 * <h3>线程</h3>
 * 所有方法都是阻塞的，调用方负责放后台线程（和 {@code NeteaseClient} 一致）。
 * UI 层统一用 {@code new Thread(...)} + {@code Handler.post}。
 *
 * <h3>风控</h3>
 * B 站对异常流量的表现是 -352（HTTP 状态正常但 body 为空/返回 HTML）。
 * 这里做三件事压低风险：请求间自然限速（同一栏目连点靠 UI 层的 {@code loading} 标志挡住）、
 * 固定一套设备票（{@link #ensureBuvid}）、以及所有请求带同一个 UA。
 */
public class BiliClient {

    private static final String TAG = "BiliClient";

    private static final String API = "https://api.bilibili.com";
    private static final String LIVE = "https://api.live.bilibili.com";
    private static final String PASSPORT = "https://passport.bilibili.com";
    private static final String COMMENT = "https://comment.bilibili.com";

    /**
     * 桌面版 Chrome 的 UA。
     *
     * <p>不用 Android TV 的 UA：web 接口认浏览器，电视 UA 会被当成异常客户端。
     */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * 手机端 Chrome 的 UA。
     *
     * <p>「推荐」用的是 {@code app.bilibili.com/x/v2/feed/index}——手机 App 的首页推荐。
     * 这个接口不校验 appkey 签名（2026-10 实测），但认移动端 UA 和手机尺寸的参数；
     * 用桌面 UA 去要，回来的还是网页版那套热门，失去「手机推荐」的意义。
     */
    private static final String PHONE_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final String APP_FEED = "https://app.bilibili.com/x/v2/feed/index";

    private static final String COOKIE_FILE = "bili_cookie.txt";

    /** 会话票：登录成功后这些里至少有 SESSDATA，认「已登录」只看它 */
    private static final String[] KEEP_COOKIES = {
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid",
            "buvid3", "buvid4", "b_nut", "fingerprint"
    };

    private static volatile BiliClient instance;

    /**
     * 推荐流一批最大只给 10 条，首屏 40 张要拼 4 批。串行时首屏是 4 个完整往返，
     * 电视上网络稍差就是三四秒空白；这四批互不依赖，并发发出去只等最慢的一批。
     */
    private static final ExecutorService BATCH_POOL = Executors.newFixedThreadPool(4,
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "bili-recommend");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    /** 详情缓存：从播放器退回列表、再点进同一支视频，没必要把同一份 view/detail 再要一遍 */
    private static final long VIEW_TTL_MS = 60 * 1000L;
    /** 取流缓存：地址自带约 2 小时时效，这里只留 5 分钟，够覆盖「详情 → 播放」这一次来回 */
    private static final long STREAM_TTL_MS = 5 * 60 * 1000L;

    private final File cookieFile;
    private final OkHttpClient http;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final ExecutorService prefetchPool = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "bili-prefetch");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private final Object cacheLock = new Object();
    private BiliDetail viewCache;
    private String viewCacheBvid = "";
    private long viewCacheAt;
    private BiliStream streamCache;
    private String streamCacheKey = "";
    private long streamCacheAt;

    /** nav 拿到的 wbi 两把 key + 取到的时间，2 小时内复用（官方前端也是这么缓存的） */
    private String imgKey = "";
    private String subKey = "";
    private long wbiKeyAt;
    /** nav 同时带回登录态，避免每次判断登录都发一个请求 */
    private BiliAccount account;

    private BiliClient(Context context) {
        File dir = context.getFilesDir();
        cookieFile = new File(dir == null ? new File(".") : dir, COOKIE_FILE);
        http = new OkHttpClient.Builder()
                // 电视盒子网络差时，默认 15s 连接 / 25s 读取会让「没网」变成半分钟的转圈；
                // 缩短到 8s/15s，失败早点摊开给重试按钮，比一直等着体面
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                // 自己管 cookie（要落盘、要只留白名单里的几项），不用 CookieJar 的默认实现
                .build();
        loadCookie();
    }

    public static BiliClient get(Context context) {
        if (instance == null) {
            synchronized (BiliClient.class) {
                if (instance == null) {
                    instance = new BiliClient(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // ---------------------------------------------------------------- 登录态

    public boolean loggedIn() {
        return !TextUtils.isEmpty(cookies.get("SESSDATA"));
    }

    /**
     * 账号信息（头像、昵称、等级、大会员）。
     *
     * <p>{@code nav} 在未登录时 code 是 -101 但仍带 {@code data.wbi_img}，
     * 所以这个方法不抛错，只用 {@code data.isLogin} 判断——它同时也是 wbi key 的来源。
     */
    public BiliAccount nav() throws IOException {
        JSONObject json = getJson(API + "/x/web-interface/nav", true);
        JSONObject data = json.optJSONObject("data");
        BiliAccount info = new BiliAccount();
        info.loggedIn = data != null && data.optBoolean("isLogin");
        if (data != null) {
            info.mid = data.optLong("mid");
            info.nickname = data.optString("uname", "");
            info.avatar = normalize(data.optString("face", ""));
            info.level = data.optInt("level");
            info.vip = data.optJSONObject("vipStatus") != null
                    || data.optJSONObject("vip") != null;
            info.fans = data.optInt("fans");
            JSONObject wbi = data.optJSONObject("wbi_img");
            if (wbi != null) {
                imgKey = wbi.optString("img_url", "");
                subKey = wbi.optString("sub_url", "");
                wbiKeyAt = System.currentTimeMillis();
            }
        }
        account = info;
        return info;
    }

    /** 缓存的账号信息；没拉过就先拉一次 */
    public BiliAccount account() throws IOException {
        if (account == null) {
            return nav();
        }
        return account;
    }

    /**
     * 导入一段 cookie（用户从浏览器复制的整串 {@code k=v; k=v}）。
     *
     * <p>和扫码登录并存：扫码是主路，粘贴 cookie 给「遥控器没法扫码」的设备兜底。
     */
    public void setCookie(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        for (String part : raw.split(";")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = item.substring(0, eq).trim();
            String value = item.substring(eq + 1).trim();
            if (isKept(name) && !TextUtils.isEmpty(value)) {
                cookies.put(name, value);
            }
        }
        account = null;
        saveCookie();
    }

    public void logout() {
        cookies.remove("SESSDATA");
        cookies.remove("bili_jct");
        cookies.remove("DedeUserID");
        cookies.remove("DedeUserID__ckMd5");
        cookies.remove("sid");
        account = null;
        saveCookie();
    }

    public String csrf() {
        String jct = cookies.get("bili_jct");
        return jct == null ? "" : jct;
    }

    public String cookieHeader() {
        // 判据是「有没有 buvid3」而不是「cookies 空不空」：普通响应里漏下一个
        // b_nut 之类就会让 cookies 非空，于是永远不去申请设备票，推荐接口一直回空
        if (needsBuvid()) {
            ensureBuvid();
        }
        StringBuilder sb = new StringBuilder();
        for (String name : KEEP_COOKIES) {
            String value = cookies.get(name);
            if (!TextUtils.isEmpty(value)) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(name).append('=').append(value);
            }
        }
        return sb.toString();
    }

    private static boolean isKept(String name) {
        for (String kept : KEEP_COOKIES) {
            if (kept.equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 扫码登录

    /** 二维码内容（原样是一个 H5 登录 URL，本地画成二维码给手机扫） */
    public static class Qr {
        public String url = "";
        public String key = "";
    }

    /** 轮询结果。状态码就是 B 站那几个：0 成功 / 86090 已扫待确认 / 86101 未扫 / 86038 过期 */
    public static class QrResult {
        public int code;
        public String message = "";
        public boolean success;
    }

    public Qr qrcodeGenerate() throws IOException {
        JSONObject json = getJson(PASSPORT
                + "/x/passport-login/web/qrcode/generate?source=main-fe-header", true);
        Qr qr = new Qr();
        JSONObject data = json.optJSONObject("data");
        if (data != null) {
            qr.url = data.optString("url", "");
            qr.key = data.optString("qrcode_key", "");
        }
        if (TextUtils.isEmpty(qr.key)) {
            throw new IOException("二维码接口没给 qrcode_key");
        }
        return qr;
    }

    /**
     * 轮询扫码状态。成功时登录 cookie 在 <b>响应头</b> 里（Set-Cookie），不在 body，
     * 所以这里必须直接读 header 并手动并进会话——{@link #getJson} 那条路会把
     * Set-Cookie 丢掉（OkHttp 只在有 CookieJar 时才落 cookie）。
     */
    public QrResult qrcodePoll(String key) throws IOException {
        Request request = new Request.Builder()
                .url(PASSPORT + "/x/passport-login/web/qrcode/poll?source=main-fe-header&qrcode_key="
                        + BiliSigner.encode(key))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Cookie", cookieHeader())
                .get()
                .build();
        QrResult result = new QrResult();
        Response response = http.newCall(request).execute();
        try {
            for (String raw : response.headers().values("Set-Cookie")) {
                Cookie cookie = Cookie.parse(response.request().url(), raw);
                if (cookie != null && isKept(cookie.name())) {
                    cookies.put(cookie.name(), cookie.value());
                }
            }
            String body = response.body() == null ? "{}" : response.body().string();
            JSONObject json = new JSONObject(body);
            JSONObject data = json.optJSONObject("data");
            result.code = data != null ? data.optInt("code") : json.optInt("code");
            result.message = data != null ? data.optString("message", "") : json.optString("message", "");
            result.success = result.code == 0;
            if (result.success) {
                account = null;
                saveCookie();
            }
        } catch (Exception e) {
            throw new IOException("扫码状态解析失败：" + e.getMessage());
        } finally {
            closeQuietly(response);
        }
        return result;
    }

    // ---------------------------------------------------------------- 视频列表

    /** 综合热门。免登录，分页 */
    public List<BiliCard> popular(int page, int pageSize) throws IOException {
        JSONObject json = getJson(API + "/x/web-interface/popular?pn=" + Math.max(1, page)
                + "&ps=" + pageSize, true);
        return parseCards(json.optJSONObject("data") == null
                ? null : json.optJSONObject("data").optJSONArray("list"), "热门");
    }

    /**
     * 首页推荐——手机 App 的推荐流（{@code x/v2/feed/index}）。
     *
     * <p>为什么不继续用网页版 {@code index/top/rcmd}：那个接口给的是「全站热榜 + 少量个性化」，
     * 一屏里经常一半是几百万播放的老视频，和手机上刷到的推荐完全不是一回事。
     * 手机 App 的这个接口（2026-10 实测）不校验签名，一次 10 条，{@code idx=0}
     * 每次都会给新的一批，正好对上「换一批」这个按钮的语义。
     *
     * <p>{@code index} 参数保留只是为了不改 UI 层的调用点：新接口没有「第几批」的概念，
     * 每次换一批都从 {@code idx=0} 重新要。四批并发发出去，按 aid 去重后拼成一屏。
     * 手机接口的卡片<b>没有 bvid</b>，只有 aid，这里用 {@link #av2bv} 本地转出来——
     * 详情页、播放器只认 bvid。
     */
    public List<BiliCard> recommend(int index, int pageSize) throws IOException {
        final int ps = 10;
        final int want = Math.max(1, pageSize);
        final int batches = Math.min(6, (want + ps - 1) / ps);
        List<BiliCard> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        List<Future<List<BiliCard>>> futures = new ArrayList<>(batches);
        for (int i = 0; i < batches; i++) {
            futures.add(BATCH_POOL.submit(new Callable<List<BiliCard>>() {
                @Override
                public List<BiliCard> call() throws IOException {
                    String url = APP_FEED
                            + "?mobi_app=android&platform=android&build=72302000"
                            + "&device=phone&idx=0&fnval=4048&fnver=0&fourk=1"
                            + "&actionKey=appkey&c_locale=zh_CN&s_locale=zh_CN"
                            + "&channel=xiaomi&network=wifi&login_event=0";
                    JSONObject json = getAppJson(url, true);
                    JSONObject data = json.optJSONObject("data");
                    return parsePhoneCards(data == null ? null : data.optJSONArray("items"));
                }
            }));
        }

        for (int i = 0; i < batches && cards.size() < want; i++) {
            List<BiliCard> batch;
            try {
                batch = futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                Log.w(TAG, "phone recommend batch " + i + " failed: "
                        + (cause == null ? e.getMessage() : cause.getMessage()));
                continue;
            }
            for (BiliCard card : batch) {
                String key = card.aid > 0 ? "av" + card.aid : card.bvid;
                if (TextUtils.isEmpty(key) || !seen.add(key)) {
                    continue;
                }
                cards.add(card);
                if (cards.size() >= want) {
                    break;
                }
            }
        }
        if (cards.isEmpty()) {
            // 手机接口偶尔也会给空列表（风控或灰度），退回网页热门顶上，不让首屏开天窗
            Log.w(TAG, "phone recommend empty, fallback to popular");
            return popular(1, want);
        }
        return cards;
    }

    /**
     * 排行榜。{@code rid} 是分区的 tid，0 表示全站。
     *
     * <p>选它而不是「分区最新投稿」接口：分区列表那几条（{@code dynamic/region}、
     * {@code newlist}）要么已下线要么要 wbi，ranking/v2 免签名且字段和 popular 同一套。
     */
    public List<BiliCard> ranking(int rid) throws IOException {
        String url = API + "/x/web-interface/ranking/v2?rid=" + Math.max(0, rid) + "&type=all";
        JSONObject json = getJson(url, true);
        JSONObject data = json.optJSONObject("data");
        return parseCards(data == null ? null : data.optJSONArray("list"), "排行榜");
    }

    /**
     * 相关推荐。单集视频的「列表循环」拿它当播放列表。
     *
     * <p>响应里 {@code data} 直接是数组（不是 {@code {list:…}}），字段和 popular 同一套。
     */
    public List<BiliCard> related(String bvid) throws IOException {
        if (TextUtils.isEmpty(bvid)) {
            return new ArrayList<>();
        }
        JSONObject json = getJson(API + "/x/web-interface/archive/related?bvid="
                + BiliSigner.encode(bvid), true);
        return parseCards(json.optJSONArray("data"), "相关推荐");
    }

    /** 排行榜的分区页签 */
    public static class RankTab {
        public final String name;
        public final int rid;

        RankTab(String name, int rid) {
            this.name = name;
            this.rid = rid;
        }
    }

    /**
     * 排行榜页签。
     *
     * <p>这份表是实测（2026-10）挑出来的：{@code ranking/v2} 认的是主分区 fid，
     * 老的 tid（17/18/35/71/167/209/228…）现在一律回 -400，写进去只会点开一片空白。
     * 名字取自各主分区下最常用那条子分区的 {@code tname}。
     */
    public static List<RankTab> rankTabs() {
        List<RankTab> tabs = new ArrayList<>();
        tabs.add(new RankTab("全站", 0));
        tabs.add(new RankTab("动画", 1));
        tabs.add(new RankTab("音乐", 3));
        tabs.add(new RankTab("游戏", 4));
        tabs.add(new RankTab("影视", 23));
        tabs.add(new RankTab("电视剧", 11));
        tabs.add(new RankTab("娱乐", 5));
        tabs.add(new RankTab("知识", 36));
        tabs.add(new RankTab("科技", 188));
        tabs.add(new RankTab("鬼畜", 119));
        tabs.add(new RankTab("舞蹈", 129));
        tabs.add(new RankTab("生活", 160));
        tabs.add(new RankTab("汽车", 223));
        tabs.add(new RankTab("运动", 234));
        tabs.add(new RankTab("时尚", 155));
        tabs.add(new RankTab("动物圈", 217));
        return tabs;
    }

    /** 搜索结果。要 wbi 签名，缺签名回 -412 */
    public List<BiliCard> search(String keyword, int page, int pageSize) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", String.valueOf(Math.max(1, page)));
        params.put("page_size", String.valueOf(pageSize));
        params.put("web_location", "333.788");
        JSONObject json = getSigned(API + "/x/web-interface/search/type", params, true);
        JSONArray list = json.optJSONObject("data") == null
                ? null : json.optJSONObject("data").optJSONArray("result");
        return parseSearch(list);
    }

    /** 观看历史（要登录） */
    public List<BiliCard> history(int pageSize) throws IOException {
        String url = API + "/x/web-interface/history/cursor?max=0&view_at=0&business=archive&ps="
                + pageSize;
        JSONObject json = getJson(url, true);
        JSONObject data = json.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("list");
        List<BiliCard> cards = new ArrayList<>();
        if (list == null) {
            return cards;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            BiliCard card = new BiliCard();
            card.bvid = item.optString("bvid", "");
            card.aid = item.optLong("aid");
            card.title = item.optString("title", "");
            card.cover = normalize(item.optString("cover", item.optString("pic", "")));
            card.up = item.optString("name", item.optString("author_name", ""));
            card.durationSec = item.optLong("duration");
            card.progressSec = item.optLong("progress");
            card.badge = "看到 " + humanTime(item.optLong("progress"));
            card.section = "历史";
            cards.add(card);
        }
        return cards;
    }

    /** 我的收藏夹列表（要登录） */
    public List<BiliCard> favFolders() throws IOException {
        BiliAccount info = account();
        String url = API + "/x/v3/fav/folder/created/list-all?up_mid=" + info.mid;
        JSONObject json = getJson(url, true);
        JSONObject data = json.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("list");
        List<BiliCard> folders = new ArrayList<>();
        if (list == null) {
            return folders;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            BiliCard card = new BiliCard();
            card.favId = item.optLong("id");
            card.title = item.optString("title", "");
            card.cover = normalize(item.optString("cover", ""));
            card.badge = item.optInt("media_count") + " 个视频";
            card.section = "收藏";
            folders.add(card);
        }
        return folders;
    }

    /** 收藏夹里的视频（要登录） */
    public List<BiliCard> favItems(long mediaId, int page) throws IOException {
        String url = API + "/x/v3/fav/resource/list?media_id=" + mediaId
                + "&pn=" + Math.max(1, page) + "&ps=20&order=mtime&type=0&platform=web";
        JSONObject json = getJson(url, true);
        JSONObject data = json.optJSONObject("data");
        return parseCards(data == null ? null : data.optJSONArray("medias"), "收藏");
    }

    // ---------------------------------------------------------------- 一键三连

    /** 三连里每一项的结果。已经点过赞/投满币也算这一项达成，只有真的失败才记 error */
    public static final class TripleResult {
        public boolean liked;
        public boolean coined;
        public boolean favorited;
        /** 第一处失败原因；三项都成功时为空 */
        public String error = "";

        public boolean complete() {
            return liked && coined && favorited;
        }
    }

    /**
     * 一键三连：点赞 + 投两枚币 + 收藏到默认收藏夹。
     *
     * <p>三项各发一条请求，互不短路：投币到上限、已经在收藏夹里都不算失败，
     * 只有真正的网络/登录错误才写进 {@link TripleResult#error}。
     */
    public TripleResult triple(String bvid, long aid) {
        TripleResult result = new TripleResult();
        StringBuilder firstError = new StringBuilder();
        try {
            result.liked = like(bvid);
        } catch (IOException e) {
            appendFirst(firstError, "点赞", e);
        }
        try {
            result.coined = coin(bvid, 2);
        } catch (IOException e) {
            appendFirst(firstError, "投币", e);
        }
        try {
            if (aid <= 0) {
                throw new IOException("没拿到稿件号");
            }
            result.favorited = favorite(aid, defaultFavId());
        } catch (IOException e) {
            appendFirst(firstError, "收藏", e);
        }
        result.error = firstError.toString();
        return result;
    }

    private static void appendFirst(StringBuilder out, String label, IOException e) {
        if (out.length() > 0) {
            return;
        }
        String message = TextUtils.isEmpty(e.getMessage()) ? "接口没响应" : e.getMessage();
        out.append(label).append("失败：").append(message);
    }

    private boolean like(String bvid) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("bvid", bvid);
        form.put("like", "1");
        form.put("csrf", csrfOrThrow());
        JSONObject json = postForm(API + "/x/web-interface/archive/like", form);
        int code = json.optInt("code", -1);
        // 65006 = 已经点过赞，三连的目的已经达到
        if (code == 0 || code == 65006) {
            return true;
        }
        throw new IOException(biliError(code, json, "点赞没成功"));
    }

    private boolean coin(String bvid, int multiply) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("bvid", bvid);
        form.put("multiply", String.valueOf(Math.max(1, Math.min(2, multiply))));
        form.put("select_like", "1");
        form.put("csrf", csrfOrThrow());
        JSONObject json = postForm(API + "/x/web-interface/coin/add", form);
        int code = json.optInt("code", -1);
        // -808 = 本片已投满两枚，当作达成
        if (code == 0 || code == -808) {
            return true;
        }
        throw new IOException(biliError(code, json, "投币没成功"));
    }

    private boolean favorite(long aid, long folderId) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("rid", String.valueOf(aid));
        form.put("type", "2");
        form.put("add_media_ids", String.valueOf(folderId));
        form.put("del_media_ids", "");
        form.put("csrf", csrfOrThrow());
        JSONObject json = postForm(API + "/x/v3/fav/resource/deal", form);
        int code = json.optInt("code", -1);
        if (code == 0) {
            return true;
        }
        throw new IOException(biliError(code, json, "收藏没成功"));
    }

    /** 默认收藏夹 id。attr=1 是默认夹；接口没给 attr 时退回列表第一个 */
    public long defaultFavId() throws IOException {
        BiliAccount info = account();
        if (info == null || info.mid <= 0) {
            throw new IOException("没拿到账号信息");
        }
        JSONObject json = getJson(API + "/x/v3/fav/folder/created/list-all?up_mid="
                + info.mid, true);
        JSONObject data = json.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("list");
        long first = 0;
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                long id = item.optLong("id");
                if (id <= 0) {
                    continue;
                }
                if (first <= 0) {
                    first = id;
                }
                if (item.optInt("attr") == 1) {
                    return id;
                }
            }
        }
        if (first > 0) {
            return first;
        }
        throw new IOException("没有可用的收藏夹");
    }

    private String csrfOrThrow() throws IOException {
        if (!loggedIn()) {
            throw new IOException("登录已过期，重新扫码登录后再试");
        }
        String token = csrf();
        if (TextUtils.isEmpty(token)) {
            throw new IOException("登录信息里没有 bili_jct，重新登录一次");
        }
        return token;
    }

    private static String biliError(int code, JSONObject json, String fallback) {
        String message = json.optString("message", "");
        if (code == -101 || code == -111) {
            return "登录已过期，重新扫码登录后再试";
        }
        return TextUtils.isEmpty(message) ? fallback + "（" + code + "）" : message;
    }

    /** 当前有多少人正在看这一 P。接口免登录；取不到返回 -1，界面就不显示 */
    public long onlineCount(String bvid, long cid) throws IOException {
        if (TextUtils.isEmpty(bvid) || cid <= 0) {
            return -1;
        }
        JSONObject json = getJson(API + "/x/player/online/total?bvid="
                + BiliSigner.encode(bvid) + "&cid=" + cid, true);
        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            return -1;
        }
        JSONObject show = data.optJSONObject("show_switch");
        if (show != null && !show.optBoolean("count", true)) {
            return -1;
        }
        String count = data.optString("count", "").trim();
        try {
            return Long.parseLong(count);
        } catch (NumberFormatException ignore) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 详情与播放

    public BiliDetail view(String bvid) throws IOException {
        long now = System.currentTimeMillis();
        synchronized (cacheLock) {
            if (viewCache != null && bvid.equals(viewCacheBvid) && now - viewCacheAt < VIEW_TTL_MS) {
                return viewCache;
            }
        }
        // view/detail 的稿件字段在 data.View 里，按 data.title 读会得到空标题
        // （2026-10 实测）。用它而不是 view，是为了顺带拿到 Tags——view 的 tname 已经空了。
        JSONObject json = getJson(API + "/x/web-interface/view/detail?bvid="
                + BiliSigner.encode(bvid), true);
        JSONObject root = json.optJSONObject("data");
        JSONObject data = root == null ? null : root.optJSONObject("View");
        BiliDetail detail = new BiliDetail();
        detail.bvid = bvid;
        if (data == null) {
            return detail;
        }
        detail.aid = data.optLong("aid");
        detail.cid = data.optLong("cid");
        detail.title = data.optString("title", "");
        detail.desc = plain(data.optString("desc", ""));
        detail.cover = normalize(data.optString("pic", ""));
        detail.pubdate = data.optLong("pubdate");
        detail.durationSec = data.optLong("duration");
        JSONObject owner = data.optJSONObject("owner");
        if (owner != null) {
            detail.up = owner.optString("name", "");
            detail.upMid = owner.optLong("mid");
            detail.upFace = normalize(owner.optString("face", ""));
        }
        JSONObject stat = data.optJSONObject("stat");
        if (stat != null) {
            detail.views = stat.optLong("view");
            detail.danmaku = stat.optLong("danmaku");
            detail.replies = stat.optLong("reply");
            detail.favorites = stat.optLong("favorite");
            detail.likes = stat.optLong("like");
        }
        JSONArray parts = data.optJSONArray("pages");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) {
                    continue;
                }
                BiliEpisode episode = new BiliEpisode();
                episode.cid = part.optLong("cid");
                episode.index = part.optInt("page", i + 1);
                episode.part = part.optString("part", "第 " + (i + 1) + " 集");
                episode.durationSec = part.optLong("duration");
                detail.episodes.add(episode);
            }
        }
        // 多集合集（影视/课程）在 ugc_season 里，pages 有时是空的，这里补上
        JSONObject season = data.optJSONObject("ugc_season");
        JSONArray sections = season == null ? null : season.optJSONArray("sections");
        if (detail.episodes.isEmpty() && sections != null) {
            for (int s = 0; s < sections.length(); s++) {
                JSONArray episodes = sections.optJSONObject(s) == null
                        ? null : sections.optJSONObject(s).optJSONArray("episodes");
                if (episodes == null) {
                    continue;
                }
                for (int e = 0; e < episodes.length(); e++) {
                    JSONObject item = episodes.optJSONObject(e);
                    JSONObject archive = item == null ? null : item.optJSONObject("archive");
                    if (archive == null) {
                        continue;
                    }
                    BiliEpisode episode = new BiliEpisode();
                    episode.cid = archive.optLong("cid");
                    episode.bvid = archive.optString("bvid", bvid);
                    episode.index = detail.episodes.size() + 1;
                    episode.part = archive.optString("title", "第 " + episode.index + " 集");
                    episode.durationSec = archive.optLong("duration");
                    detail.episodes.add(episode);
                }
            }
        }
        if (detail.episodes.isEmpty() && detail.cid > 0) {
            BiliEpisode episode = new BiliEpisode();
            episode.cid = detail.cid;
            episode.bvid = bvid;
            episode.index = 1;
            episode.part = detail.title;
            episode.durationSec = detail.durationSec;
            detail.episodes.add(episode);
        }
        JSONArray tags = root.optJSONArray("Tags");
        if (tags != null) {
            for (int i = 0; i < tags.length() && detail.tags.size() < 4; i++) {
                JSONObject item = tags.optJSONObject(i);
                String name = item == null ? "" : item.optString("tag_name", "");
                if (!TextUtils.isEmpty(name)) {
                    detail.tags.add(name);
                }
            }
        }
        synchronized (cacheLock) {
            viewCache = detail;
            viewCacheBvid = bvid;
            viewCacheAt = System.currentTimeMillis();
        }
        return detail;
    }

    /**
     * 取播放地址。DASH 优先，拿不到再退 mp4 整片。
     *
     * <p>未登录想拿到 1080P 有两个前提（实测，缺一不可）：
     * <ul>
     *   <li>{@code try_look=1}：让服务端按「试看」给高清晰度，否则最高只到 480P；</li>
     *   <li>五个 {@code dm_img_*} 参数：网页端播放器每次请求都会带，缺了服务端
     *       仍按低清给。值本身不校验，随便给合法 base64/JSON 即可。</li>
     * </ul>
     * 走 DASH（{@code fnval=4048}）才能拿到 1080P；mp4（{@code fnval=1}）那条是
     * 给不支持 DASH 的客户端的退路，未登录封顶 720P，所以只作兜底。
     *
     * <p>{@code playurl} 挂在 {@code /wbi/} 路由下，必须签名。
     */
    public BiliStream playUrl(String bvid, long cid, int qn) throws IOException {
        String cacheKey = bvid + ":" + cid + ":" + qn;
        synchronized (cacheLock) {
            if (streamCache != null && cacheKey.equals(streamCacheKey)
                    && System.currentTimeMillis() - streamCacheAt < STREAM_TTL_MS) {
                return streamCache;
            }
        }
        Map<String, String> params = playParams(bvid, cid, qn);
        params.put("fnval", "4048");
        JSONObject json = getSigned(API + "/x/player/wbi/playurl", params, true);
        BiliStream stream = parseStream(json.optJSONObject("data"), BiliStream.DASH, qn);
        if (stream != null && stream.playable()) {
            stream.requestedQn = qn;
            rememberStream(cacheKey, stream);
            return stream;
        }

        Map<String, String> mp4 = playParams(bvid, cid, qn);
        mp4.put("fnval", "1");
        mp4.put("platform", "html5");
        mp4.put("high_quality", "1");
        JSONObject mp4Json = getSigned(API + "/x/player/wbi/playurl", mp4, true);
        stream = parseStream(mp4Json.optJSONObject("data"), BiliStream.MP4, qn);
        if (stream == null || !stream.playable()) {
            throw new IOException("这个视频没有可播的流（可能需要大会员或已被下架）");
        }
        stream.requestedQn = qn;
        rememberStream(cacheKey, stream);
        return stream;
    }

    /**
     * 详情页拿到 cid 后先把首 P 的地址要回来。用户从「看到详情」到「按下播放」
     * 至少隔一次遥控器移动，这段时间足够把取流那个往返跑完，播放器就不用再空等。
     */
    public void prefetchStream(final String bvid, final long cid, final int qn) {
        if (TextUtils.isEmpty(bvid) || cid <= 0 || qn <= 0) {
            return;
        }
        prefetchPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    playUrl(bvid, cid, qn);
                } catch (Exception e) {
                    // 预取失败不吭声：真按播放时还会再要一次，错误在那里报
                    Log.i(TAG, "预取播放地址失败: " + e.getMessage());
                }
            }
        });
    }

    private void rememberStream(String key, BiliStream stream) {
        synchronized (cacheLock) {
            streamCache = stream;
            streamCacheKey = key;
            streamCacheAt = System.currentTimeMillis();
        }
    }

    private Map<String, String> playParams(String bvid, long cid, int qn) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("bvid", bvid);
        params.put("cid", String.valueOf(cid));
        params.put("qn", String.valueOf(qn));
        params.put("fnver", "0");
        params.put("fourk", "1");
        params.put("web_location", "1315873");
        // 网页播放器的指纹参数，缺了未登录会被压到 480P
        params.put("dm_img_list", "[]");
        params.put("dm_img_str", "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ");
        params.put("dm_cover_img_str", "QU5HTEUgKEludGVsLCBJbnRlbChSKSBVSEQgR3JhcGhpY3MgNjMwICgweDAwMDAzRTlCKSBEaXJlY3QzRDExIHZzXzVfMCBwc181XzApLCBvciBzaW1pbGFy");
        params.put("dm_img_inter", "{\"ds\":[],\"wh\":[0,0,0],\"of\":[0,0,0]}");
        if (!loggedIn()) {
            params.put("try_look", "1");
        }
        return params;
    }

    /** mp4 的 durl 和 DASH 的 dash.video/audio 都长在这里；qn 是用户请求的档位 */
    private BiliStream parseStream(JSONObject data, int kind, int qn) {
        if (data == null) {
            return null;
        }
        BiliStream stream = new BiliStream();
        stream.kind = kind;
        stream.quality = data.optInt("quality");
        stream.timelength = data.optLong("timelength");
        JSONArray formats = data.optJSONArray("support_formats");
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                JSONObject item = formats.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                BiliStream.Quality quality = new BiliStream.Quality();
                quality.qn = item.optInt("quality");
                quality.desc = item.optString("new_description", item.optString("display_desc", ""));
                stream.qualities.add(quality);
            }
        }
        JSONArray durl = data.optJSONArray("durl");
        if (durl != null) {
            for (int i = 0; i < durl.length(); i++) {
                JSONObject item = durl.optJSONObject(i);
                String url = item == null ? "" : item.optString("url", "");
                if (!TextUtils.isEmpty(url)) {
                    stream.urls.add(url);
                }
            }
        }
        JSONObject dash = data.optJSONObject("dash");
        if (dash != null) {
            pickBest(dash.optJSONArray("video"), stream, true, qn);
            pickBest(dash.optJSONArray("audio"), stream, false, 0);
            stream.dashVideoDuration = dash.optLong("duration");
            // data.quality 是服务端「档位」，未登录时经常写 64 而 dash 里实际有 80
            if (stream.dashVideoId > 0) {
                stream.quality = stream.dashVideoId;
            }
            refineQualities(dash.optJSONArray("video"), stream);
        }
        return stream;
    }

    /** support_formats 里会列出账号没拿到的档位，这里只留 dash 真给了流的 */
    private void refineQualities(JSONArray videos, BiliStream stream) {
        Set<Integer> ids = new HashSet<>();
        if (videos != null) {
            for (int i = 0; i < videos.length(); i++) {
                JSONObject item = videos.optJSONObject(i);
                if (item != null && !TextUtils.isEmpty(item.optString("base_url", ""))) {
                    ids.add(item.optInt("id"));
                }
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        List<BiliStream.Quality> kept = new ArrayList<>();
        Set<Integer> described = new HashSet<>();
        for (BiliStream.Quality quality : stream.qualities) {
            if (ids.contains(quality.qn)) {
                kept.add(quality);
                described.add(quality.qn);
            }
        }
        for (int id : ids) {
            if (!described.contains(id)) {
                BiliStream.Quality quality = new BiliStream.Quality();
                quality.qn = id;
                quality.desc = BiliStream.nameOf(id);
                kept.add(quality);
            }
        }
        java.util.Collections.sort(kept, (a, b) -> Integer.compare(b.qn, a.qn));
        stream.qualities.clear();
        stream.qualities.addAll(kept);
    }

    /**
     * 视频档位：请求档位 > 编码兼容性（AVC 硬解最稳）> 码率更低。
     *
     * <p>服务端即使给了 1080P 的流，也不代表用户就要 1080P；这里先挑出「不高于
     * 请求档位的最高档」，请求的那档不存在时退回最低档，再在同档里选 AVC。
     * {@code qn <= 0} 表示不限，取最高档。
     */
    private void pickBest(JSONArray list, BiliStream stream, boolean video, int qn) {
        if (list == null) {
            return;
        }
        int targetId = 0;
        if (video) {
            int highest = 0;
            int lowest = Integer.MAX_VALUE;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null || TextUtils.isEmpty(item.optString("base_url", ""))) {
                    continue;
                }
                int id = item.optInt("id");
                if (id <= 0) {
                    continue;
                }
                highest = Math.max(highest, id);
                lowest = Math.min(lowest, id);
                if (qn <= 0 || id <= qn) {
                    targetId = Math.max(targetId, id);
                }
            }
            if (qn <= 0) {
                targetId = highest;
            } else if (targetId == 0) {
                targetId = lowest == Integer.MAX_VALUE ? 0 : lowest;
            }
        }
        JSONObject best = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null || TextUtils.isEmpty(item.optString("base_url", ""))) {
                continue;
            }
            if (video && item.optInt("id") != targetId) {
                continue;
            }
            if (best == null) {
                best = item;
                continue;
            }
            if (video) {
                int codec = codecRank(item.optInt("codecid"));
                int bestCodec = codecRank(best.optInt("codecid"));
                if (codec != bestCodec) {
                    if (codec < bestCodec) {
                        best = item;
                    }
                    continue;
                }
                if (item.optLong("bandwidth") < best.optLong("bandwidth")) {
                    best = item;
                }
            } else {
                boolean aac = isAac(item);
                boolean bestAac = isAac(best);
                if (aac != bestAac) {
                    if (aac) {
                        best = item;
                    }
                    continue;
                }
                if (item.optLong("bandwidth") > best.optLong("bandwidth")) {
                    best = item;
                }
            }
        }
        if (best == null) {
            return;
        }
        String url = best.optString("base_url", "");
        List<String> backups = new ArrayList<>();
        JSONArray backup = best.optJSONArray("backup_url");
        if (backup != null) {
            for (int i = 0; i < backup.length(); i++) {
                String value = backup.optString(i, "");
                if (!TextUtils.isEmpty(value)) {
                    backups.add(value);
                }
            }
        }
        if (video) {
            stream.dashVideo = url;
            stream.dashVideoBackups.addAll(backups);
            stream.dashVideoId = best.optInt("id");
        } else {
            stream.dashAudio = url;
            stream.dashAudioBackups.addAll(backups);
        }
    }

    /** codecid：7=AVC 12=HEVC 13=AV1，越小越容易被电视盒子硬解 */
    private int codecRank(int codecid) {
        switch (codecid) {
            case 7:
                return 0;
            case 12:
                return 1;
            case 13:
                return 2;
            default:
                return 3;
        }
    }

    /** 杜比（30250）/ FLAC（30251）很多盒子解不了，优先 AAC */
    private boolean isAac(JSONObject item) {
        String codecs = item.optString("codecs", "");
        return codecs.startsWith("mp4a") || item.optInt("id") == 30216
                || item.optInt("id") == 30232 || item.optInt("id") == 30280;
    }

    /**
     * 弹幕原文（{@code <d p="时间,模式,字号,颜色,…">文本</d>} 那套 XML）。
     *
     * <p>不在这里解析成结构：core 里 TVBox 的弹幕解析器
     * {@code com.github.tvbox.osc.player.danmu.Parser} 认的就是这个格式（B 站自己也是这个格式），
     * 直接把 XML 给它，省一份模型类和一套重复的分段/缩放逻辑。
     *
     * <p>走老的 {@code comment.bilibili.com/{cid}.xml}。新接口 {@code /x/v2/dm/web/seg.so}
     * 是 protobuf 分段流，要引 protobuf 运行时；xml 这条仍在（一次给整条视频、上限 6000 条），
     * 对电视场景完全够用。
     */
    public String danmakuXml(long cid) {
        if (cid <= 0) {
            return "";
        }
        Request request = new Request.Builder()
                .url(COMMENT + "/" + cid + ".xml")
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .get()
                .build();
        Response response = null;
        try {
            response = http.newCall(request).execute();
            if (response.body() == null) {
                return "";
            }
            // 自己读字节再解：这个接口不保证回 Content-Encoding，但正文确实是压过的
            // （2026-10 实测：拿到的是 zlib，不是 gzip），所以按字节嗅探
            byte[] raw = response.body().bytes();
            return new String(decodeBody(raw, response.header("Content-Encoding")), "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "danmaku failed cid=" + cid, e);
            return "";
        } finally {
            closeQuietly(response);
        }
    }

    /**
     * 播放要带的请求头。B 站的流地址校验来源，直接丢给系统播放器会 403。
     *
     * <p>UA 必须是桌面浏览器那一套（CDN 按它决定给不给高清）；Cookie 里带着 buvid
     * 设备票，未登录时也有一串，别省。
     */
    public Map<String, String> playHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", "https://www.bilibili.com/");
        headers.put("Origin", "https://www.bilibili.com");
        String cookie = cookieHeader();
        if (!TextUtils.isEmpty(cookie)) {
            headers.put("Cookie", cookie);
        }
        return headers;
    }

    public static String userAgent() {
        return UA;
    }

    // ---------------------------------------------------------------- 空降助手

    /** 空降助手（B 站版 SponsorBlock）的社区服务。接口和 PiliPlus 用的是同一个 */
    private static final String SPONSOR = "https://www.bsbsb.top";
    private static final String SPONSOR_EXT_VERSION = "1.0.0";

    /** 一条被观众标记的片段。时间单位是秒，和接口原文一致 */
    public static final class SponsorSegment {
        public String category = "";
        public String actionType = "";
        public String uuid = "";
        public double start;
        public double end;
        public double videoDuration;
        public int votes;
    }

    /**
     * 拉某支稿件的空降片段。
     *
     * <p>接口回的是一个裸 JSON 数组（不是 {@code {code,data}} 那套）。404 / 服务异常都按
     * 「没有片段」处理——空降助手是锦上添花，不能因为它让正片播不出来。
     */
    public List<SponsorSegment> sponsorSegments(String bvid, long cid) {
        List<SponsorSegment> result = new ArrayList<>();
        if (TextUtils.isEmpty(bvid)) {
            return result;
        }
        String url = SPONSOR + "/api/skipSegments?videoID=" + bvid
                + (cid > 0 ? "&cid=" + cid : "");
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Origin", "https://www.bilibili.com")
                .header("x-ext-version", SPONSOR_EXT_VERSION)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .get()
                .build();
        Response response = null;
        try {
            response = http.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return result;
            }
            String body = response.body().string();
            JSONArray array = new JSONArray(body);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                JSONArray segment = item.optJSONArray("segment");
                if (segment == null || segment.length() < 2) {
                    continue;
                }
                SponsorSegment value = new SponsorSegment();
                value.category = item.optString("category", "");
                value.actionType = item.optString("actionType", "");
                value.uuid = item.optString("UUID", "");
                value.start = segment.optDouble(0, -1);
                value.end = segment.optDouble(1, -1);
                value.videoDuration = item.optDouble("videoDuration", 0);
                value.votes = item.optInt("votes", 0);
                if (value.start >= 0 && value.end >= value.start) {
                    result.add(value);
                }
            }
        } catch (Throwable th) {
            // JSON 数组解析失败多半是 404 页面或临时故障，按「无片段」处理
            Log.w(TAG, "sponsor segments failed bvid=" + bvid + " cid=" + cid, th);
        } finally {
            closeQuietly(response);
        }
        return result;
    }

    // ---------------------------------------------------------------- 直播

    /** 直播分区（两级：一级里面套 children） */
    public List<LiveArea> liveAreas() throws IOException {
        JSONObject json = getJson(LIVE + "/room/v1/Area/getList", true);
        JSONArray list = json.optJSONArray("data");
        List<LiveArea> areas = new ArrayList<>();
        if (list == null) {
            return areas;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            LiveArea area = new LiveArea();
            area.id = item.optInt("id");
            area.name = item.optString("name", "");
            area.cover = normalize(item.optString("cover", ""));
            JSONArray children = item.optJSONArray("children");
            if (children != null) {
                for (int c = 0; c < children.length(); c++) {
                    JSONObject child = children.optJSONObject(c);
                    if (child == null) {
                        continue;
                    }
                    LiveArea sub = new LiveArea();
                    sub.id = child.optInt("id");
                    sub.name = child.optString("name", "");
                    sub.parentId = area.id;
                    sub.cover = normalize(child.optString("cover", ""));
                    area.children.add(sub);
                }
            }
            areas.add(area);
        }
        return areas;
    }

    /**
     * 某个（二级）分区下的直播间。
     *
     * @param parentId 一级分区 id；未知时传 0，接口只认 area_id 时也接受
     */
    public List<LiveRoom> liveRooms(int parentId, int areaId, String sort, int page)
            throws IOException {
        String url = LIVE + "/xlive/web-interface/v1/second/getList?platform=web"
                + "&parent_area_id=" + parentId
                + "&area_id=" + areaId
                + "&sort_type=" + (TextUtils.isEmpty(sort) ? "online" : sort)
                + "&page=" + Math.max(1, page);
        JSONObject json = getJson(url, true);
        JSONObject data = json.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("list");
        List<LiveRoom> rooms = new ArrayList<>();
        if (list == null) {
            return rooms;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            LiveRoom room = new LiveRoom();
            room.roomId = item.optLong("roomid", item.optLong("room_id"));
            room.id = room.roomId;
            room.title = item.optString("title", "");
            room.cover = normalize(item.optString("cover", item.optString("user_cover", "")));
            room.watchCount = item.optLong("popularity", item.optLong("watched_show"));
            JSONObject anchor = item.optJSONObject("anchor");
            if (anchor != null) {
                room.anchor = anchor.optString("name", "");
                room.anchorFace = normalize(anchor.optString("face", ""));
                room.uid = anchor.optLong("uid");
            }
            room.areaName = item.optString("area_name", "");
            rooms.add(room);
        }
        return rooms;
    }

    /** 直播间信息 + 播放地址。{@code platform=h5} 给 HLS（老电视更稳），{@code web} 给 FLV */
    public LiveRoom liveRoom(long roomId, String platform) throws IOException {
        JSONObject info = getJson(LIVE + "/room/v1/Room/get_info?room_id=" + roomId, true);
        LiveRoom room = new LiveRoom();
        room.id = roomId;
        room.roomId = roomId;
        JSONObject data = info.optJSONObject("data");
        if (data != null) {
            room.title = data.optString("title", "");
            room.cover = normalize(data.optString("cover", ""));
            room.uid = data.optLong("uid");
            room.liveStatus = data.optInt("live_status");
        }
        JSONObject play = getJson(LIVE + "/room/v1/Room/playUrl?cid=" + roomId
                + "&platform=" + (TextUtils.isEmpty(platform) ? "h5" : platform)
                + "&quality=4", true);
        JSONObject playData = play.optJSONObject("data");
        if (playData != null) {
            room.currentQn = playData.optInt("current_qn");
            JSONArray desc = playData.optJSONArray("quality_description");
            if (desc != null) {
                for (int i = 0; i < desc.length(); i++) {
                    JSONObject item = desc.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    BiliStream.Quality quality = new BiliStream.Quality();
                    quality.qn = item.optInt("qn");
                    quality.desc = item.optString("desc", "");
                    room.qualities.add(quality);
                }
            }
            JSONArray durl = playData.optJSONArray("durl");
            if (durl != null) {
                for (int i = 0; i < durl.length(); i++) {
                    JSONObject item = durl.optJSONObject(i);
                    String url = item == null ? "" : item.optString("url", "");
                    if (!TextUtils.isEmpty(url)) {
                        room.urls.add(url);
                    }
                }
            }
        }
        if (room.urls.isEmpty()) {
            throw new IOException(room.liveStatus == 1 ? "这个直播间取不到播放地址" : "主播还没开播");
        }
        return room;
    }

    // ---------------------------------------------------------------- HTTP

    private JSONObject getJson(String url, boolean withCookie) throws IOException {
        String body = request(url, withCookie);
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("接口返回不是 JSON（可能被风控拦了）。"
                    + body.substring(0, Math.min(60, body.length())));
        }
    }

    /**
     * 手机 App 接口的 GET。
     *
     * <p>和 web 接口分开走：UA 换成手机 Chrome、Referer 换成 m 站。web 那套桌面 UA 配
     * {@code app.bilibili.com} 会被当成异常客户端，回来的不是推荐流。
     */
    private JSONObject getAppJson(String url, boolean withCookie) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", PHONE_UA)
                .header("Referer", "https://m.bilibili.com/")
                .header("Accept", "application/json, text/plain, */*")
                .get();
        if (withCookie) {
            String cookie = cookieHeader();
            if (!TextUtils.isEmpty(cookie)) {
                builder.header("Cookie", cookie);
            }
        }
        Response response = null;
        try {
            response = http.newCall(builder.build()).execute();
            if (response.body() == null) {
                throw new IOException("接口没有响应体");
            }
            harvestCookies(response);
            String body = response.body().string();
            try {
                return new JSONObject(body);
            } catch (Exception e) {
                throw new IOException("推荐接口返回不是 JSON（可能被风控拦了）。"
                        + body.substring(0, Math.min(60, body.length())));
            }
        } finally {
            closeQuietly(response);
        }
    }

    /** 带 wbi 签名的 GET。签名 key 从 nav 拿，2 小时内复用 */
    private JSONObject getSigned(String base, Map<String, String> params, boolean withCookie)
            throws IOException {
        String mixin = mixinKey();
        String query = BiliSigner.signedQuery(params, mixin, System.currentTimeMillis() / 1000L);
        return getJson(base + "?" + query, withCookie);
    }

    private String mixinKey() throws IOException {
        long age = System.currentTimeMillis() - wbiKeyAt;
        if (TextUtils.isEmpty(imgKey) || age > 2 * 60 * 60 * 1000L) {
            nav();
        }
        if (TextUtils.isEmpty(imgKey)) {
            throw new IOException("拿不到 wbi 密钥（nav 接口没响应）");
        }
        return BiliSigner.mixinKey(imgKey, subKey);
    }

    private String request(String url, boolean withCookie) throws IOException {
        // 不带 Origin：浏览器在同站导航的 GET 上不会发这个头，带上反而让
        // index/top/rcmd 这类接口回 -400（2026-10 实测）。
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .get();
        if (withCookie) {
            String cookie = cookieHeader();
            if (!TextUtils.isEmpty(cookie)) {
                builder.header("Cookie", cookie);
            }
        }
        Response response = null;
        try {
            response = http.newCall(builder.build()).execute();
            if (response.body() == null) {
                throw new IOException("接口没有响应体");
            }
            harvestCookies(response);
            return response.body().string();
        } finally {
            closeQuietly(response);
        }
    }

    /** 写接口统一走表单 POST：带 Origin，和 Web 端点赞/投币/收藏的请求一致 */
    private JSONObject postForm(String url, Map<String, String> form) throws IOException {
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            body.add(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Origin", "https://www.bilibili.com")
                .post(body.build());
        String cookie = cookieHeader();
        if (!TextUtils.isEmpty(cookie)) {
            builder.header("Cookie", cookie);
        }
        Response response = null;
        try {
            response = http.newCall(builder.build()).execute();
            if (response.body() == null) {
                throw new IOException("接口没有响应体");
            }
            harvestCookies(response);
            String text = response.body().string();
            try {
                return new JSONObject(text);
            } catch (Exception e) {
                throw new IOException("接口返回不是 JSON（可能被风控拦了）。"
                        + text.substring(0, Math.min(60, text.length())));
            }
        } finally {
            closeQuietly(response);
        }
    }

    /** 服务端在普通请求里也可能下发 buvid3 之类，拿到就并进来 */
    private void harvestCookies(Response response) {
        for (String raw : response.headers().values("Set-Cookie")) {
            Cookie cookie = Cookie.parse(response.request().url(), raw);
            if (cookie != null && isKept(cookie.name()) && !TextUtils.isEmpty(cookie.value())
                    && !cookie.value().equals(cookies.get(cookie.name()))) {
                cookies.put(cookie.name(), cookie.value());
                saveCookie();
            }
        }
    }

    /**
     * 设备票。没有 buvid3/buvid4 时，搜索和 playurl 很容易被回 -352。
     * {@code /x/frontend/finger/spi} 是公开发放接口，取到后落盘复用。
     *
     * <p>主线程调到这里就转后台去取，本次先空手返回。不能同步等：
     * {@link #cookieHeader()} 会调它，而 {@code playHeaders()} 在播放器建
     * DataSource 时被主线程调用——那一下就是 NetworkOnMainThreadException
     * （2026-10 实测：桌面第一次进 B 站模块时 {@link #prime(Context)} 在主线程取
     * buvid，日志里整条按键分发栈都是它的）。少一个 buvid 只是首请求容易被风控，
     * 比卡住主线程划算。
     */
    public void ensureBuvid() {
        if (!needsBuvid()) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (buvidFetching) {
                return;
            }
            buvidFetching = true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        fetchBuvid();
                    } finally {
                        buvidFetching = false;
                    }
                }
            }).start();
            return;
        }
        fetchBuvid();
    }

    /** 判据只看 buvid3：服务端在普通响应里也可能顺带下发它，那就不必再申请一次 */
    private boolean needsBuvid() {
        return TextUtils.isEmpty(cookies.get("buvid3"));
    }

    private volatile boolean buvidFetching;

    /** synchronized：后台多个首屏线程同时缺票时，排队等一次请求，别各要一份 */
    private synchronized void fetchBuvid() {
        if (!needsBuvid()) {
            return;
        }
        try {
            Request request = new Request.Builder()
                    .url(API + "/x/frontend/finger/spi")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com/")
                    .get()
                    .build();
            Response response = http.newCall(request).execute();
            String body = response.body() == null ? "{}" : response.body().string();
            closeQuietly(response);
            JSONObject data = new JSONObject(body).optJSONObject("data");
            if (data != null) {
                String b3 = data.optString("b_3", "");
                String b4 = data.optString("b_4", "");
                if (!TextUtils.isEmpty(b3)) {
                    cookies.put("buvid3", b3);
                }
                if (!TextUtils.isEmpty(b4)) {
                    cookies.put("buvid4", b4);
                }
                cookies.put("b_nut", String.valueOf(System.currentTimeMillis() / 1000L));
                saveCookie();
            }
        } catch (Exception e) {
            Log.w(TAG, "buvid fetch failed", e);
        }
    }

    // ---------------------------------------------------------------- 解析小工具

    /** popular / ranking / fav 的 medias 共用这一套字段 */
    private List<BiliCard> parseCards(JSONArray list, String section) {
        List<BiliCard> cards = new ArrayList<>();
        if (list == null) {
            return cards;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            BiliCard card = new BiliCard();
            card.section = section;
            card.bvid = item.optString("bvid", "");
            card.aid = item.optLong("aid");
            card.cid = item.optLong("cid");
            card.title = plain(item.optString("title", ""));
            card.cover = normalize(firstNonEmpty(item.optString("pic", ""),
                    item.optString("cover", "")));
            JSONObject owner = item.optJSONObject("owner");
            if (owner != null) {
                card.up = owner.optString("name", "");
                card.upMid = owner.optLong("mid");
            } else {
                card.up = item.optString("author", item.optString("upper_name", ""));
            }
            card.durationSec = item.optLong("duration");
            if (card.durationSec == 0 && item.has("duration") && !(item.opt("duration")
                    instanceof Number)) {
                card.durationSec = parseClock(item.optString("duration", ""));
            }
            JSONObject stat = item.optJSONObject("stat");
            if (stat != null) {
                card.views = stat.optLong("view");
                card.danmaku = stat.optLong("danmaku");
            } else {
                card.views = item.optLong("play");
                card.danmaku = item.optLong("video_review");
            }
            card.badge = firstNonEmpty(item.optString("tname", ""),
                    item.optString("reason", ""));
            if (TextUtils.isEmpty(card.bvid) && card.aid > 0) {
                // 老接口只给 aid，详情页认 aid 也能查
                card.bvid = "";
            }
            cards.add(card);
        }
        return cards;
    }

    /**
     * 手机推荐卡片的解析。
     *
     * <p>字段和网页列表不是一套：标题、封面在顶层，作者/分区在 {@code args}，
     * aid/cid/时长在 {@code player_args}；播放量和弹幕量没有结构化数字，只藏在封面角标
     * {@code cover_left_text_1/2} 里（形如「24万」）。卡片也<b>没有 bvid</b>，
     * 本地用 {@link #av2bv} 转出来，详情和播放器只认 bvid。
     */
    private List<BiliCard> parsePhoneCards(JSONArray items) {
        List<BiliCard> cards = new ArrayList<>();
        if (items == null) {
            return cards;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String goto_ = item.optString("goto", item.optString("card_goto", ""));
            if (!TextUtils.isEmpty(goto_) && !"av".equals(goto_)) {
                // 直播、番剧、广告卡没有 aid，点进去也没法播，直接跳过
                continue;
            }
            JSONObject player = item.optJSONObject("player_args");
            JSONObject args = item.optJSONObject("args");
            long aid = player == null ? 0 : player.optLong("aid");
            if (aid <= 0) {
                aid = item.optLong("param");
            }
            if (aid <= 0 && args != null) {
                aid = args.optLong("aid");
            }
            if (aid <= 0) {
                continue;
            }
            BiliCard card = new BiliCard();
            card.section = "推荐";
            card.aid = aid;
            card.bvid = av2bv(aid);
            card.title = plain(item.optString("title", ""));
            card.cover = normalize(item.optString("cover", ""));
            if (player != null) {
                card.cid = player.optLong("cid");
                card.durationSec = player.optLong("duration");
            }
            if (args != null) {
                card.up = args.optString("up_name", "");
                card.upMid = args.optLong("up_id");
                card.badge = args.optString("tname", "");
            }
            card.views = parseCount(item.optString("cover_left_text_1", ""));
            card.danmaku = parseCount(item.optString("cover_left_text_2", ""));
            cards.add(card);
        }
        return cards;
    }

    /** 搜索结果的字段名和列表接口不一样，而且 title 里带 {@code <em>} 高亮标签 */
    private List<BiliCard> parseSearch(JSONArray list) {
        List<BiliCard> cards = new ArrayList<>();
        if (list == null) {
            return cards;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            BiliCard card = new BiliCard();
            card.section = "搜索";
            card.bvid = item.optString("bvid", "");
            card.aid = item.optLong("aid");
            card.title = plain(item.optString("title", ""));
            card.cover = normalize(item.optString("picurl", ""));
            card.up = item.optString("author", "");
            card.upMid = item.optLong("mid");
            card.durationSec = parseClock(item.optString("duration", ""));
            card.views = item.optLong("play");
            card.danmaku = item.optLong("video_review");
            JSONArray tags = item.optJSONArray("new_rec_tags");
            card.badge = tags == null || tags.length() == 0 ? ""
                    : plain(((JSONObject) tags.opt(0)).optString("name", ""));
            cards.add(card);
        }
        return cards;
    }

    /** "4:37" / "1:02:11" → 秒 */
    private static long parseClock(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        long total = 0;
        for (String part : value.split(":")) {
            try {
                total = total * 60 + Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return total;
    }

    /** 封面角标「24万」「1.2亿」「363」→ 数字；认不出就当 0 */
    private static long parseCount(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        String value = text.trim();
        int end = 0;
        while (end < value.length()
                && (Character.isDigit(value.charAt(end)) || value.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            double base = Double.parseDouble(value.substring(0, end));
            if (value.indexOf('亿') >= 0) {
                return (long) (base * 100000000L);
            }
            if (value.indexOf('万') >= 0 || value.indexOf('w') >= 0
                    || value.indexOf('W') >= 0) {
                return (long) (base * 10000L);
            }
            return (long) base;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * aid → bvid。手机推荐卡片只给数字 aid，详情/播放全走 bvid，只能本地转。
     *
     * <p>算法是 B 站 2022 年换的那套新 BV：{@code (MAX_AID | aid) ^ XOR} 按 58 进制
     * 逐位取表，再按固定顺序错位。aid 小于 {@code 1<<51}，9 位刚好装下。
     * 实测 117472606816526 → BV1Whnc6YEoi，与官方接口一致。
     */
    public static String av2bv(long aid) {
        final long XOR = 23442827791579L;
        final long MAX_AID = 1L << 51;
        final String table = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf";
        final int[] order = {8, 7, 0, 5, 1, 3, 2, 4, 6};
        char[] out = new char[9];
        long tmp = (MAX_AID | aid) ^ XOR;
        for (int i = 0; i < 9; i++) {
            out[order[i]] = table.charAt((int) (tmp % 58));
            tmp /= 58;
        }
        return "BV1" + new String(out);
    }

    /** 列表接口里的标题会带 HTML 标签和换行，卡片上一律先洗成一行 */
    private static String plain(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String clean = text.replaceAll("<[^>]+>", "").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("\n", " ").trim();
        return clean.length() > 60 ? clean.substring(0, 60) + "…" : clean;
    }

    /** B 站的图地址一半是 {@code //i0.hdslb.com/...} 开头，直接给 Glide 会当成本地路径 */
    private static String normalize(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        // 手机推荐接口的封面是 http，Android 9+ 默认禁明文，Glide 会直接加载失败
        if (url.startsWith("http://")) {
            return "https://" + url.substring(7);
        }
        return url;
    }

    private static String firstNonEmpty(String first, String second) {
        return TextUtils.isEmpty(first) ? second : first;
    }

    /** 秒 → 「1:02:11」/「4:37」 */
    public static String humanTime(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    /** 播放量这类统计值的中文缩写，卡片角标用 */
    public static String humanCount(long value) {
        if (value >= 100000000L) {
            return String.format(java.util.Locale.US, "%.1f亿", value / 100000000.0);
        }
        if (value >= 10000L) {
            return String.format(java.util.Locale.US, "%.1f万", value / 10000.0);
        }
        return String.valueOf(value);
    }

    /**
     * 解压响应体。gzip 看 magic，zlib/raw deflate 看标头再试解。
     *
     * <p>弹幕接口实测回的是 zlib（0x78 开头），只认 gzip 会把一坨二进制当 XML 交出去，
     * 解析器安静地给出 0 条，界面上就是「弹幕一条也没有」而没有任何报错。
     */
    private static byte[] decodeBody(byte[] data, String encoding) {
        if (data == null || data.length < 2) {
            return data;
        }
        if ((data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            java.util.zip.GZIPInputStream in = null;
            try {
                in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data));
                return pump(in, data);
            } catch (Exception e) {
                return data;
            } finally {
                closeQuietlyStream(in);
            }
        }
        boolean deflate = "deflate".equalsIgnoreCase(encoding == null ? "" : encoding)
                || (data[0] & 0xFF) == 0x78;
        if (!deflate) {
            return data;
        }
        // 先按带 zlib 头解；不行再按 raw deflate 解，两种 B 站都出现过
        byte[] zlib = inflate(data, false);
        return zlib == data ? inflate(data, true) : zlib;
    }

    private static byte[] inflate(byte[] data, boolean noHeader) {
        java.util.zip.Inflater inflater = noHeader
                ? new java.util.zip.Inflater(true) : new java.util.zip.Inflater();
        java.util.zip.InflaterInputStream in = null;
        try {
            in = new java.util.zip.InflaterInputStream(
                    new java.io.ByteArrayInputStream(data), inflater);
            return pump(in, data);
        } catch (Exception e) {
            return data;
        } finally {
            inflater.end();
            closeQuietlyStream(in);
        }
    }

    private static byte[] pump(java.io.InputStream in, byte[] fallback) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void closeQuietlyStream(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(Response response) {
        if (response != null && response.body() != null) {
            try {
                response.body().close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- cookie 持久化

    private void loadCookie() {
        if (cookieFile == null || !cookieFile.exists()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(cookieFile), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    cookies.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "load cookie failed", e);
        } finally {
            closeQuietly(reader);
        }
    }

    private void saveCookie() {
        if (cookieFile == null) {
            return;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(cookieFile);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            out.write(sb.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "save cookie failed", e);
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(FileOutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 首次使用（没有 cookie 文件）时先把设备票申请好，免得第一个请求就被风控 */
    public static void prime(Context context) {
        BiliClient client = get(context);
        if (client.cookies.isEmpty()) {
            client.ensureBuvid();
        }
    }
}

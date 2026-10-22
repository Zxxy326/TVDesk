package com.github.catvod.music;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 网易云 Web 接口客户端。
 *
 * <h3>它和音源脚本的分工</h3>
 * 音源脚本（{@link LxRuntime}）只负责一件事：<b>把歌曲 id 换成能播的链接</b>。
 * 平台的元数据——榜单、推荐歌单、账号歌单、搜索——不在音源脚本的契约里
 * （脚本 {@code send('inited')} 声明的 actions 只有 musicUrl，实测也是），
 * 所以这部分只能直连官方接口，也就是这个类。
 *
 * <h3>为什么能用</h3>
 * 官方 {@code /weapi/*} 明文接口仍然可用，只要按 {@link NeteaseCrypto} 加密并带上
 * 一套设备指纹 cookie。实测（2026-10）以下接口免登录即可用：
 * <pre>
 *   /api/toplist                 榜单目录（64 个）
 *   /api/personalized/playlist   推荐歌单
 *   /api/v6/playlist/detail      歌单/榜单曲目
 *   /api/cloudsearch/pc          搜索歌曲
 *   /api/song/lyric              歌词（LRC 原文）
 * </pre>
 * 需要登录 cookie 的：{@code /api/v3/discovery/recommend/songs}（每日推荐）、
 * {@code /api/user/playlist}（账号歌单）、{@code /api/w/nuser/account/get}（账号信息）。
 *
 * <h3>登录为什么是「粘 cookie」而不是扫码</h3>
 * 扫码登录的 {@code /api/w/login/qr/unikey} 已经被官方下掉（weapi/eapi 两条路由都回
 * 「接口未找到」）；手机号登录虽然路由还在，但发验证码这一步被风控拦死
 * （{@code /api/sms/captcha/sent} 回 -462，要求先去 {@code st.music.163.com/encrypt-pages}
 * 过人机校验）。无头客户端两头都走不通，只剩「把别处登录好的 cookie 搬进来」这一条。
 * 所以 {@link #setCookie} 是这个页面唯一的登录入口。
 *
 * <h3>线程</h3>
 * 所有方法都是阻塞的，调用方负责放到后台线程（月抛一个同步 OkHttp 调用即可）。
 */
public class NeteaseClient {

    private static final String TAG = "Netease";

    private static final String BASE = "https://music.163.com";

    /**
     * 用 PC 版 Chrome 的 UA。
     * 换成 App 的 UA 反而要额外的 eapi 头部与 token，weapi 路由认的就是浏览器这一套。
     */
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0";

    private static final String COOKIE_FILE = "netease_cookie.txt";

    /** 未登录时也要装成一台普通 PC，否则容易被判成爬虫（-462） */
    private static final String OSVER = "Microsoft-Windows-10-Professional-build-19045-64bit";
    private static final String APPVER = "3.1.17.204416";

    private static final MediaType FORM =
            MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");

    private static volatile NeteaseClient instance;

    /** Set-Cookie 拿到过新东西，等这次请求收尾时一起落盘 */
    private boolean cookieDirty;

    /** 一首歌 */
    public static class Song {
        public String id = "";
        public String name = "";
        public String artist = "";
        public String album = "";
        public String picUrl = "";
        public long durationMs;
    }

    /** 一张歌单（榜单也是歌单） */
    public static class Playlist {
        public String id = "";
        public String name = "";
        public String coverUrl = "";
        public String creator = "";
        public int trackCount;
        /** 榜单才有：更新频率（「每周四更新」…） */
        public String updateFrequency = "";
        /**
         * 账号歌单才有：特殊歌单的类型，5 就是「我喜欢的音乐」。
         * 官方没在文档里写这个数，但它比按名字匹配稳——名字会被用户改，类型不会。
         */
        public int specialType;
    }

    /** 登录后的账号信息 */
    public static class Account {
        public long userId;
        public String nickname = "";
        public String avatarUrl = "";
    }

    /** 接口返回非 200 时抛这个，message 直接给用户看 */
    public static class NeteaseException extends Exception {
        public NeteaseException(String message) {
            super(message);
        }
    }

    private final File cookieFile;
    private final OkHttpClient http;

    /** 会话 cookie。登录态就在 MUSIC_U 里 */
    private final Map<String, String> cookies = new LinkedHashMap<>();

    /** 红心列表的进程内缓存；null 表示还没拉过，改收藏时顺手维护它 */
    private Set<String> likedIds;

    /** 设备指纹，进程内固定：每次请求都换反而更像异常流量 */
    private final String deviceId = NeteaseCrypto.randomHex(32);
    private final String nuid = NeteaseCrypto.randomHex(32);
    private final String wnmcid = NeteaseCrypto.randomHex(6) + "." + System.currentTimeMillis() + ".01.0";
    private final String realIp = "112.64." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);

    private NeteaseClient(Context context) {
        File dir = context.getFilesDir();
        cookieFile = new File(dir == null ? new File(".") : dir, COOKIE_FILE);
        http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        loadCookie();
    }

    /** 全局单例：会话（cookie）跟着进程走一份，多个页面读同一份登录态 */
    public static NeteaseClient get(Context context) {
        if (instance == null) {
            synchronized (NeteaseClient.class) {
                if (instance == null) {
                    instance = new NeteaseClient(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // ---------------------------------------------------------------- 登录态

    /** 有 MUSIC_U 就算登录。这是网易云的会话票，其它 cookie（__csrf 之类）都可由它带出来 */
    public boolean loggedIn() {
        String musicU = cookies.get("MUSIC_U");
        return !TextUtils.isEmpty(musicU);
    }

    /**
     * 导入一段 cookie（用户从别处复制来的整串 {@code k=v; k=v}）。
     * 解析出的键值会并入当前会话并落盘。
     *
     * @return 认出来的键数量；0 表示这串里没有可用的 cookie
     */
    public int setCookie(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return 0;
        }
        int count = 0;
        for (String part : raw.split("[;\\n]")) {
            String piece = part.trim();
            int eq = piece.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = piece.substring(0, eq).trim();
            String value = piece.substring(eq + 1).trim();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            cookies.put(key, value);
            count++;
        }
        if (count > 0) {
            saveCookie();
            // 换 cookie 等于换账号，红心列表必须重来，否则新账号一进来就满屏「已收藏」
            likedIds = null;
        }
        return count;
    }

    public void logout() {
        cookies.clear();
        // 红心是上一个账号的列表，留着会让新账号一进来就满屏「已收藏」
        likedIds = null;
        if (cookieFile.exists() && !cookieFile.delete()) {
            Log.w(TAG, "cookie 文件删不掉：" + cookieFile);
        }
    }

    private void loadCookie() {
        if (!cookieFile.isFile()) {
            return;
        }
        setCookieQuiet(readFile(cookieFile));
    }

    private void setCookieQuiet(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        for (String part : raw.split("[;\\n]")) {
            String piece = part.trim();
            int eq = piece.indexOf('=');
            if (eq > 0) {
                cookies.put(piece.substring(0, eq).trim(), piece.substring(eq + 1).trim());
            }
        }
    }

    private void saveCookie() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(cookieFile);
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "cookie 落盘失败", e);
        } finally {
            close(out);
        }
    }

    // ---------------------------------------------------------------- 接口

    /** 当前登录账号。未登录返回 null */
    public Account account() throws Exception {
        if (!loggedIn()) {
            return null;
        }
        JSONObject profile = request("/api/w/nuser/account/get", params()).optJSONObject("profile");
        if (profile == null) {
            return null;
        }
        Account a = new Account();
        a.userId = profile.optLong("userId");
        a.nickname = profile.optString("nickname", "");
        a.avatarUrl = normalize(profile.optString("avatarUrl", ""));
        return a;
    }

    /** 搜歌 */
    public List<Song> search(String keyword, int limit) throws Exception {
        Map<String, Object> p = params();
        p.put("s", keyword);
        p.put("type", 1);
        p.put("limit", limit);
        p.put("offset", 0);
        p.put("total", true);
        JSONObject result = request("/api/cloudsearch/pc", p).optJSONObject("result");
        return parseSongs(arrayOf(result == null ? null : result.optJSONArray("songs")));
    }

    /** 推荐歌单（官方「推荐歌单」位，免登录） */
    public List<Playlist> recommendedPlaylists(int limit) throws Exception {
        Map<String, Object> p = params();
        p.put("limit", limit);
        p.put("offset", 0);
        p.put("total", true);
        p.put("n", 1000);
        return parsePlaylists(arrayOf(request("/api/personalized/playlist", p).optJSONArray("result")));
    }

    /** 榜单目录：飙升榜、新歌榜、热歌榜… 共 60+ 个 */
    public List<Playlist> toplists() throws Exception {
        return parsePlaylists(arrayOf(request("/api/toplist", params()).optJSONArray("list")));
    }

    /**
     * 取一张歌单的歌。
     *
     * <p>{@code /api/v6/playlist/detail} 对大歌单只回 trackIds 不回 tracks，
     * 这时退到 {@code /api/v6/playlist/track/all} 分页取。
     */
    public List<Song> playlistSongs(String playlistId, int limit) throws Exception {
        Map<String, Object> p = params();
        p.put("id", playlistId);
        p.put("n", 100000);
        p.put("s", 8);
        JSONObject playlist = request("/api/v6/playlist/detail", p).optJSONObject("playlist");
        List<Song> songs = parseSongs(arrayOf(playlist == null ? null : playlist.optJSONArray("tracks")));
        if (!songs.isEmpty()) {
            return songs;
        }
        Map<String, Object> all = params();
        all.put("id", playlistId);
        all.put("limit", limit);
        all.put("offset", 0);
        all.put("s", 8);
        return parseSongs(arrayOf(request("/api/v6/playlist/track/all", all).optJSONArray("songs")));
    }

    /** 每日推荐（需要登录） */
    public List<Song> dailySongs() throws Exception {
        JSONObject data = request("/api/v3/discovery/recommend/songs", params()).optJSONObject("data");
        JSONArray arr = arrayOf(data == null ? null : data.optJSONArray("dailySongs"));
        List<Song> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject entry = arr.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            // 两种结构：{song:{...},reason:..} 或者干脆就是歌对象本身
            JSONObject song = entry.optJSONObject("song");
            if (song == null) {
                song = entry.optJSONObject("track");
            }
            if (song == null) {
                song = entry;
            }
            Song parsed = parseSong(song);
            if (!TextUtils.isEmpty(parsed.id)) {
                out.add(parsed);
            }
        }
        return out;
    }

    /** 账号歌单（含「我喜欢的音乐」，需要登录）。返回列表里 specialType==5 的那张就是红心歌单 */
    public List<Playlist> userPlaylists(long uid) throws Exception {
        Map<String, Object> p = params();
        p.put("uid", String.valueOf(uid));
        p.put("limit", 1000);
        p.put("offset", 0);
        p.put("includeVideo", false);
        // uid 同时挂 query 与 body：官方两个位置都会读，只给一处时偶发空列表
        return parsePlaylists(arrayOf(
                request("/api/user/playlist?uid=" + uid, p).optJSONArray("playlist")));
    }

    /**
     * 歌词 LRC 原文。没有歌词的歌官方回 {@code lrc.lyric == ""}，不是报错，
     * 所以空串是正常结果，调用方别当成失败。
     */
    public String lyric(String songId) throws Exception {
        Map<String, Object> p = params();
        p.put("id", songId);
        // 三个版本号一律 -1：给了正数就变成「只要某个翻译版本」，原文反而空
        p.put("tv", -1);
        p.put("lv", -1);
        p.put("kv", -1);
        p.put("rv", -1);
        JSONObject lrc = request("/api/song/lyric", p).optJSONObject("lrc");
        return lrc == null ? "" : lrc.optString("lyric", "");
    }

    // ---------------------------------------------------------------- 红心

    /**
     * 收藏 / 取消收藏（网易云的「红心」）。
     *
     * <p>要登录：没登录时 {@link #request} 会拿到 301，翻成「需要登录网易云」这句人话，
     * 调用方直接拿去提示就行，不用再判一次。
     */
    public void like(String songId, boolean on) throws Exception {
        if (TextUtils.isEmpty(songId)) {
            throw new NeteaseException("这首歌没有网易云 id，收藏不了");
        }
        // 红心只有明文 /api/song/like 这一条路由；weapi 下的 /song/like/update
        // 是客户端私有接口，Web 端早就回 404 了（之前收藏一直失败就是撞在这）。
        Map<String, String> form = new LinkedHashMap<>();
        form.put("trackId", songId);
        form.put("like", String.valueOf(on));
        form.put("timestamp", String.valueOf(System.currentTimeMillis()));
        requestApi("/api/song/like", form);
    }

    /**
     * 已经红心的歌曲 id 集合，用来决定播放页那颗心是实心还是描边。
     *
     * <p>结果缓在进程里（{@link #likedIds}）：这是一整份列表，每换一首歌去拉一次太浪费。
     * 未登录返回空集，不抛错——没登录时「没红心」就是事实，不该打断播放。
     */
    public Set<String> likedIds() {
        if (likedIds != null) {
            return likedIds;
        }
        Set<String> ids = new HashSet<>();
        if (loggedIn()) {
            try {
                JSONObject resp = request("/api/song/like/get", params());
                JSONArray arr = arrayOf(resp.optJSONArray("ids"));
                Log.i(TAG, "红心列表 " + arr.length() + " 首");
                for (int i = 0; i < arr.length(); i++) {
                    ids.add(String.valueOf(arr.optLong(i)));
                }
            } catch (Exception e) {
                Log.w(TAG, "红心列表没取到，先当没有", e);
                // 取不到就这次不缓存，下次点收藏还会再试一遍
                return ids;
            }
            likedIds = ids;
        }
        return ids;
    }

    /** 本地改状态后同步那份缓存，省一次全量重拉 */
    public void markLiked(String songId, boolean on) {
        if (likedIds == null || TextUtils.isEmpty(songId)) {
            return;
        }
        if (on) {
            likedIds.add(songId);
        } else {
            likedIds.remove(songId);
        }
    }

    // ---------------------------------------------------------------- 请求

    private static Map<String, Object> params() {
        return new LinkedHashMap<>();
    }

    private JSONObject request(String path, Map<String, Object> params) throws Exception {
        JSONObject payload = new JSONObject();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            payload.put(e.getKey(), e.getValue());
        }
        String csrf = cookies.get("__csrf");
        if (!TextUtils.isEmpty(csrf)) {
            payload.put("csrf_token", csrf);
        }

        String url = weapiUrl(path);
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(buildHeaders()))
                .post(RequestBody.create(FORM, NeteaseCrypto.weapiBody(payload.toString())))
                .build();
        return execute(request);
    }

    /**
     * 明文 form POST，不加密。少数老接口只在 {@code /api/} 这条路由上活着（红心就是），
     * 走 {@link #request} 的 weapi 路由会被打成 404。
     */
    private JSONObject requestApi(String path, Map<String, String> form) throws Exception {
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            body.add(e.getKey(), e.getValue());
        }
        String csrf = cookies.get("__csrf");
        if (!TextUtils.isEmpty(csrf)) {
            body.add("csrf_token", csrf);
        }
        Request request = new Request.Builder()
                .url(BASE + path)
                .headers(Headers.of(buildHeaders()))
                .post(body.build())
                .build();
        return execute(request);
    }

    private JSONObject execute(Request request) throws Exception {
        Response response = null;
        try {
            response = http.newCall(request).execute();
            for (String setCookie : response.headers("Set-Cookie")) {
                mergeSetCookie(setCookie);
            }
            String text = response.body() == null ? "" : response.body().string();
            if (TextUtils.isEmpty(text)) {
                throw new NeteaseException("网易云没有返回内容（HTTP " + response.code() + "）");
            }
            JSONObject json = new JSONObject(text);
            int code = json.optInt("code", response.code());
            if (code != 200) {
                throw new NeteaseException(describe(json, code));
            }
            return json;
        } catch (NeteaseException e) {
            throw e;
        } catch (IOException e) {
            throw new NeteaseException("网络不通：" + e.getMessage());
        } catch (Exception e) {
            throw new NeteaseException("响应解析失败：" + e.getMessage());
        } finally {
            if (response != null) {
                response.close();
            }
            // Set-Cookie 里可能带了新的 __csrf，顺手落盘
            saveCookieQuietly();
        }
    }

    private static String describe(JSONObject json, int code) {
        if (code == 301 || code == -201) {
            return "需要登录网易云（code=" + code + "）";
        }
        if (code == -462) {
            return "被网易云风控拦了，过一会儿再试（code=-462）";
        }
        String message = json.optString("message", "");
        if (TextUtils.isEmpty(message)) {
            message = json.optString("msg", "");
        }
        return TextUtils.isEmpty(message) ? ("网易云返回 code=" + code) : message;
    }

    private String weapiUrl(String path) {
        if (path.startsWith("/api/")) {
            return BASE + "/weapi/" + path.substring("/api/".length());
        }
        return BASE + "/weapi/" + path;
    }

    /**
     * 请求头。
     *
     * <p>不设 {@code Accept-Encoding}：交给 OkHttp 自己加，它才会顺带把 gzip 解掉。
     * 自己写死 gzip 的话拿到的是压缩字节流，还得手写解压。
     */
    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Referer", BASE + "/");
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("X-Real-IP", realIp);
        headers.put("X-Forwarded-For", realIp);
        headers.put("Cookie", cookieHeader());
        return headers;
    }

    /** 会话 cookie + 设备指纹。会话里已有的键优先，指纹只补空缺 */
    private String cookieHeader() {
        Map<String, String> merged = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        merged.put("__remember_me", "true");
        merged.put("ntes_kaola_ad", "1");
        merged.put("_ntes_nuid", nuid);
        merged.put("_ntes_nnid", nuid + "," + now);
        merged.put("WNMCID", wnmcid);
        merged.put("WEVNSM", "1.0.0");
        merged.put("NMTID", NeteaseCrypto.randomHex(16));
        merged.put("osver", OSVER);
        merged.put("deviceId", deviceId);
        merged.put("os", "pc");
        merged.put("channel", "netease");
        merged.put("appver", APPVER);
        merged.putAll(cookies);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Set-Cookie 形如 {@code MUSIC_U=xxx; Path=/; ...}，只取第一段的键值 */
    private void mergeSetCookie(String header) {
        if (TextUtils.isEmpty(header)) {
            return;
        }
        int end = header.indexOf(';');
        String head = (end < 0 ? header : header.substring(0, end)).trim();
        int eq = head.indexOf('=');
        if (eq <= 0) {
            return;
        }
        cookies.put(head.substring(0, eq).trim(), head.substring(eq + 1).trim());
        cookieDirty = true;
    }

    private void saveCookieQuietly() {
        if (!cookieDirty) {
            return;
        }
        cookieDirty = false;
        saveCookie();
    }

    // ---------------------------------------------------------------- 解析

    private static JSONArray arrayOf(JSONArray array) {
        return array == null ? new JSONArray() : array;
    }

    private static List<Song> parseSongs(JSONArray array) {
        List<Song> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Song song = parseSong(item);
            if (!TextUtils.isEmpty(song.id)) {
                out.add(song);
            }
        }
        return out;
    }

    private static Song parseSong(JSONObject o) {
        Song song = new Song();
        song.id = String.valueOf(o.opt("id") == null ? "" : o.opt("id"));
        song.name = o.optString("name", "");
        song.artist = artistOf(o);
        song.album = albumNameOf(o);
        song.picUrl = normalize(picOf(o));
        long dt = o.optLong("dt", 0);
        song.durationMs = dt > 0 ? dt : o.optLong("duration", 0);
        if ("null".equals(song.id)) {
            song.id = "";
        }
        return song;
    }

    /** 歌手名。新接口是 {@code ar[]}，老接口是 {@code artists[]} */
    private static String artistOf(JSONObject o) {
        JSONArray artists = o.optJSONArray("ar");
        if (artists == null) {
            artists = o.optJSONArray("artists");
        }
        if (artists == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject item = artists.optJSONObject(i);
            String name = item == null ? "" : item.optString("name", "");
            if (!TextUtils.isEmpty(name)) {
                names.add(name);
            }
        }
        return TextUtils.join(" / ", names);
    }

    /** 专辑名。新接口 {@code al}，老接口 {@code album}（可能是字符串） */
    private static String albumNameOf(JSONObject o) {
        JSONObject al = o.optJSONObject("al");
        if (al == null) {
            al = o.optJSONObject("album");
        }
        if (al != null) {
            return al.optString("name", "");
        }
        Object legacy = o.opt("album");
        return legacy instanceof String ? (String) legacy : "";
    }

    private static String picOf(JSONObject o) {
        JSONObject al = o.optJSONObject("al");
        if (al == null) {
            al = o.optJSONObject("album");
        }
        return al == null ? "" : al.optString("picUrl", "");
    }

    private static List<Playlist> parsePlaylists(JSONArray array) {
        List<Playlist> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Playlist playlist = new Playlist();
            playlist.id = String.valueOf(item.opt("id") == null ? "" : item.opt("id"));
            playlist.name = item.optString("name", "");
            // 榜单/账号歌单用 coverImgUrl，推荐歌单（/api/personalized/playlist）只有 picUrl
            playlist.coverUrl = normalize(item.optString("coverImgUrl", ""));
            if (TextUtils.isEmpty(playlist.coverUrl)) {
                playlist.coverUrl = normalize(item.optString("picUrl", ""));
            }
            playlist.trackCount = item.optInt("trackCount", 0);
            playlist.updateFrequency = item.optString("updateFrequency", "");
            playlist.specialType = item.optInt("specialType", 0);
            JSONObject creator = item.optJSONObject("creator");
            if (creator != null) {
                playlist.creator = creator.optString("nickname", "");
            }
            if (!TextUtils.isEmpty(playlist.id) && !"null".equals(playlist.id)) {
                out.add(playlist);
            }
        }
        return out;
    }

    /** 网易云的图床地址常是 {@code //} 开头或 http，统一成 https 免得被 cleartext 策略拦 */
    private static String normalize(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String cleaned = url.trim().replace("`", "");
        if (cleaned.startsWith("//")) {
            return "https:" + cleaned;
        }
        if (cleaned.startsWith("http://")) {
            return "https://" + cleaned.substring("http://".length());
        }
        return cleaned;
    }

    // ---------------------------------------------------------------- 小工具

    private static String readFile(File file) {
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            return new String(buf, 0, read, "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "读取 cookie 失败", e);
            return "";
        } finally {
            close(in);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
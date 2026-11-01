package com.github.catvod.music;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 在线歌曲的<b>统一解析核心</b>。
 *
 * <h3>为什么要有它</h3>
 * 取播放链接的途径越堆越多：网易云官方接口、已安装的 JS 脚本、用户手动指定的源、
 * 换个平台再搜一遍。每条路都有各自的音质、失败方式、脏链接问题，
 * 散在调用方里写一遍就会各写各的。所以统一收口到这里，
 * 媒体库、搜索、手动换源都只调这一个入口。
 *
 * <h3>解析顺序</h3>
 * <ol>
 *   <li><b>持久化缓存</b>：同一首歌、同一个源、同一个音质，15 分钟内直接复用；</li>
 *   <li><b>当前 / 指定音源</b>：用户手动挑的源优先，其次是上次用成功的那个；</li>
 *   <li><b>JS 插件</b>：已安装的脚本里挑支持该平台的；</li>
 *   <li><b>内置官方接口</b>：网易云 eapi → weapi；</li>
 *   <li><b>按元信息跨源搜索</b>：这首歌在别的平台上也有一份，就换过去取；</li>
 *   <li><b>网易云代理兜底</b>：直连不通就换代理地址再试一次。</li>
 * </ol>
 *
 * <h3>统一处理的脏活</h3>
 * 音质降级、加密容器（.ncm）过滤、占位流（“该内容为付费歌曲”这类）过滤、
 * 跨源搜出来的歌要跟目标歌对得上、失败重试——全在这里做，调用方只拿到一条能播的地址。
 */
public final class OnlineSourceResolver {

    private static final String TAG = "MusicResolve";

    private OnlineSourceResolver() {
    }

    /**
     * JS 侧的提供者。
     *
     * <p>core 层不认识 desk 层的页面，但「已安装了哪些脚本」「怎么拿到脚本运行时」只有那边知道，
     * 所以用接口把能力递进来。
     */
    public interface JsProvider {
        /** 已安装的 JS 音源脚本 */
        List<LxSource> installedSources();

        /** 取（必要时创建）某个脚本的运行时；拿不到返回 null */
        LxRuntime runtimeFor(String fileName);

        /**
         * 取一个<b>已经初始化好</b>的运行时。
         *
         * <p>没初始化就跑不了：调用方拿到的是「运行时未初始化」这种没法看的错误。
         * 这里负责把脚本跑起来，失败返回 null。
         */
        LxRuntime readyRuntime(String fileName);
    }

    /** 一次解析的请求 */
    public static class Request {
        /** 缓存键的一部分：网易云 id 或「歌名|歌手」 */
        public String songKey = "";
        /** 网易云歌曲 id（有就走官方接口） */
        public String onlineId = "";
        public String title = "";
        public String artist = "";
        public String album = "";
        /** 目标平台：wy / tx / kw / kg / mg */
        public String platform = "wy";
        /** 期望音质：flac / flac24bit / 320k / 128k */
        public String quality = "320k";
        /** 用户手动指定的音源 id（{@code wy} 或 {@code lx:文件名}）；空表示自动挑 */
        public String sourceId = "";
        /** 上次用成功的音源 id，自动挑的时候先试它 */
        public String preferredSourceId = "";
        /** 给 JS 引擎的歌曲信息 JSON */
        public String musicInfoJson = "";
        /** 网易云代理地址（空表示直连） */
        public String proxy = "";
        /** 允许跨平台搜索换源 */
        public boolean allowCrossSearch = true;
        /**
         * 这次要跳过的源。
         *
         * <p>「链接取到了但放不出来」时用：刚那个源给的地址已经废了，
         * 再用它试一次只是白等。填上它就会去试下一个源。
         */
        public String excludeSourceId = "";
    }

    /** 解析结果 */
    public static class Result {
        public boolean ok;
        public String url = "";
        public String sourceId = "";
        public String sourceName = "";
        public String quality = "";
        public String error = "";
        public boolean fromCache;
        /** 实际给出去的平台（跨源换源后可能是 tx） */
        public String platform = "wy";

        public String describe() {
            if (ok) {
                return (fromCache ? "缓存" : sourceName) + " · " + quality;
            }
            return error;
        }
    }

    /** 音质降级顺序：从高到低 */
    private static final String[] QUALITY_LADDER = {"flac24bit", "flac", "320k", "192k", "128k"};

    // ---------------------------------------------------------------- 主入口

    /**
     * 解析一条播放链接。
     *
     * <p>阻塞调用，必须放后台线程。
     */
    public static Result resolve(Context context, Request request, JsProvider js) {
        Result result = new Result();
        if (request == null) {
            result.error = "没有解析请求";
            return result;
        }
        if (TextUtils.isEmpty(request.songKey) && TextUtils.isEmpty(request.onlineId)) {
            result.error = "这首歌没有可用于解析的标识";
            return result;
        }

        // 1) 持久化缓存
        // 读的时候要把「自动」「上次成功的那个源」「用户指定的源」都试一遍：
        // 写的时候是用实际出链接的源当 key 的，只认 auto 会永远读不中自己写的东西
        String[] cacheSources = new String[]{
                TextUtils.isEmpty(request.sourceId) ? "auto" : request.sourceId,
                request.preferredSourceId,
                request.sourceId};
        for (String cs : cacheSources) {
            if (TextUtils.isEmpty(cs)) {
                continue;
            }
            String cached = UrlCache.get(context, UrlCache.key(request.songKey, cs, request.quality));
            if (TextUtils.isEmpty(cached) || !isUsableUrl(cached)) {
                continue;
            }
            result.ok = true;
            result.url = cached;
            result.sourceId = cs;
            result.sourceName = "缓存";
            result.quality = request.quality;
            result.fromCache = true;
            return result;
        }

        // 2) 当前 / 指定音源
        List<MusicSourceDef> ordered = orderedSources(context, request, js);
        for (MusicSourceDef def : ordered) {
            Result one = trySource(context, request, def, js);
            Log.i(TAG, "试源 " + def.name + "（" + def.platform + "）-> "
                    + (one.ok ? "成功 " + one.quality : "失败 " + one.error));
            if (one.ok) {
                // 写两份：一份按实际源 id（指定源时用得上），一份按 auto（自动路径用得上）
                UrlCache.put(context, UrlCache.key(request.songKey, def.id, one.quality), one.url);
                UrlCache.put(context, UrlCache.key(request.songKey, "auto", one.quality), one.url);
                return one;
            }
            result = one;
            if (!TextUtils.isEmpty(request.sourceId)) {
                // 用户点名了这个源，就不偷偷换别的源：换源得他自己选
                return result;
            }
        }

        // 5) 按元信息跨源搜索
        if (request.allowCrossSearch) {
            Result cross = crossSearch(context, request, js);
            if (cross.ok) {
                UrlCache.put(context,
                        UrlCache.key(request.songKey, cross.sourceId, cross.quality), cross.url);
                return cross;
            }
        }

        // 6) 网易云代理兜底
        if (!TextUtils.isEmpty(request.proxy) && !TextUtils.isEmpty(request.onlineId)) {
            Result viaProxy = viaProxy(context, request);
            if (viaProxy.ok) {
                UrlCache.put(context,
                        UrlCache.key(request.songKey, "wy-proxy", viaProxy.quality), viaProxy.url);
                return viaProxy;
            }
            if (TextUtils.isEmpty(result.error)) {
                result = viaProxy;
            }
        }

        if (TextUtils.isEmpty(result.error)) {
            result.error = "没有可用的音源";
        }
        return result;
    }

    /** 把「指定源 → 首选源 → 其它 JS 源 → 内置源」排好序 */
    private static List<MusicSourceDef> orderedSources(Context context, Request request,
            JsProvider js) {
        List<MusicSourceDef> all = listSources(context, js);
        List<MusicSourceDef> ordered = new ArrayList<>();
        if (!TextUtils.isEmpty(request.excludeSourceId)) {
            List<MusicSourceDef> kept = new ArrayList<>();
            for (MusicSourceDef d : all) {
                if (!request.excludeSourceId.equals(d.id)) {
                    kept.add(d);
                }
            }
            all = kept;
        }
        if (!TextUtils.isEmpty(request.sourceId)) {
            for (MusicSourceDef d : all) {
                if (d.id.equals(request.sourceId)) {
                    ordered.add(d);
                }
            }
            if (ordered.isEmpty()) {
                // 指定的源已经不在了（脚本被删了），退回自动挑
                ordered.addAll(all);
            }
            return ordered;
        }
        if (!TextUtils.isEmpty(request.preferredSourceId)) {
            for (MusicSourceDef d : all) {
                if (d.id.equals(request.preferredSourceId)) {
                    ordered.add(d);
                }
            }
        }
        for (MusicSourceDef d : all) {
            if (!ordered.contains(d)) {
                ordered.add(d);
            }
        }
        return ordered;
    }

    /**
     * 可用音源清单：内置网易云 + 已安装 JS 脚本注册的源。
     *
     * @param context 传 null 时不返回内置源（只给 JS 源），用于只列脚本的场景
     */
    public static List<MusicSourceDef> listSources(Context context, JsProvider js) {
        List<MusicSourceDef> list = new ArrayList<>();
        if (context != null) {
            list.add(MusicSourceDef.builtinWy());
        }
        if (js == null) {
            return list;
        }
        List<LxSource> sources = js.installedSources();
        if (sources == null) {
            return list;
        }
        for (LxSource source : sources) {
            if (!source.enabled) {
                continue;
            }
            if (source.platforms.isEmpty()) {
                // 没测过：先给个占位，用户可以在探测页测完再选
                MusicSourceDef def = new MusicSourceDef();
                def.id = "lx:" + source.fileName;
                def.name = source.displayName();
                def.type = MusicSourceDef.TYPE_JS;
                def.fileName = source.fileName;
                def.note = source.displayMeta();
                list.add(def);
                continue;
            }
            for (LxSource.Platform platform : source.platforms) {
                list.add(MusicSourceDef.js(source, platform));
            }
        }
        return list;
    }

    /** 用某一个源解析 */
    private static Result trySource(Context context, Request request, MusicSourceDef def,
            JsProvider js) {
        Result result = new Result();
        result.sourceId = def.id;
        result.sourceName = def.name;
        result.platform = def.platform;

        if (MusicSourceDef.TYPE_BUILTIN.equals(def.type)) {
            if (TextUtils.isEmpty(request.onlineId)) {
                result.error = "内置网易云需要歌曲 id";
                return result;
            }
            // 内置源也有音质档：官方按账号权限给，weapi 320k 是稳的
            for (String q : ladderFor(request.quality, def.qualities)) {
                int br = "flac".equals(q) || "flac24bit".equals(q) ? 1050000 : 320000;
                String url = eapiThenWeapi(context, request, br);
                if (!TextUtils.isEmpty(url) && isUsableUrl(url)) {
                    result.ok = true;
                    result.url = url;
                    result.quality = q;
                    return result;
                }
            }
            result.error = "网易云官方接口没给出可用地址";
            return result;
        }

        // JS 源
        if (js == null) {
            result.error = "没有可用的脚本运行时";
            return result;
        }
        // 必须先初始化：不初始化直接取链，脚本那边只能回一句「运行时未初始化」，
        // 用户看到的是一句没法理解的报错
        LxRuntime runtime = js.readyRuntime(def.fileName);
        if (runtime == null) {
            result.error = "音源脚本没能启动";
            return result;
        }
        // 源的平台和这首歌的平台对不上时（用户选了 QQ 源，歌却是网易云的），
        // 拿网易云的 id 去查 QQ 只会得到「查无此歌」。先按歌名/歌手在目标平台
        // 找到同一首，再拿它的 id 去取链。
        String info = request.musicInfoJson;
        if (!def.platform.equals(request.platform)) {
            LxRuntime.SearchHit hit = findOnPlatform(runtime, def.platform, request);
            if (hit == null) {
                result.error = LxSource.platformName(def.platform) + "上没找到同名歌曲";
                return result;
            }
            info = musicInfoJson(def.platform, hit.id, hit.title, hit.artist, hit.album);
        }
        for (String q : ladderFor(request.quality, def.qualities)) {
            LxRuntime.UrlResult url = runtime.resolveUrl(def.platform, info, q);
            if (url != null && url.ok && isUsableUrl(url.url)) {
                result.ok = true;
                result.url = url.url;
                result.quality = q;
                return result;
            }
            result.error = url == null ? "脚本没有返回结果" : url.error;
        }
        return result;
    }

    /** 在目标平台按歌名/歌手找同一首歌 */
    private static LxRuntime.SearchHit findOnPlatform(LxRuntime runtime, String platform,
            Request request) {
        if (TextUtils.isEmpty(request.title)) {
            return null;
        }
        List<LxRuntime.SearchHit> hits = runtime.search(platform, request.title, 1, 5);
        for (LxRuntime.SearchHit hit : hits) {
            if (sameSong(hit, request)) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 官方接口取链。
     *
     * <p>顺序是 weapi → eapi：eapi 那条路由现在「200 但空体」（服务端关了），
     * 放前面只是每次白等一次请求；weapi 一直稳，先用它。
     */
    private static String eapiThenWeapi(Context context, Request request, int br) {
        NeteaseClient client = NeteaseClient.get(context);
        try {
            if (!TextUtils.isEmpty(request.proxy)) {
                client.setProxy(request.proxy);
            }
            String url = client.songUrl(request.onlineId);
            if (!TextUtils.isEmpty(url)) {
                return url;
            }
            return client.songUrlEapi(request.onlineId, br);
        } finally {
            if (!TextUtils.isEmpty(request.proxy)) {
                client.setProxy("");
            }
        }
    }

    /** 代理兜底：切到代理地址再走一遍官方接口 */
    private static Result viaProxy(Context context, Request request) {
        Result result = new Result();
        result.sourceId = "wy-proxy";
        result.sourceName = "网易云代理";
        result.platform = "wy";
        NeteaseClient client = NeteaseClient.get(context);
        try {
            client.setProxy(request.proxy);
            String url = client.songUrl(request.onlineId);
            if (TextUtils.isEmpty(url)) {
                url = client.songUrlEapi(request.onlineId, 320000);
            }
            if (!TextUtils.isEmpty(url) && isUsableUrl(url)) {
                result.ok = true;
                result.url = url;
                result.quality = "320k";
                return result;
            }
            result.error = "代理也没给出可用地址";
        } catch (Throwable th) {
            result.error = "代理兜底失败：" + th.getMessage();
        } finally {
            client.setProxy("");
        }
        return result;
    }

    /**
     * 自动换源：拿歌名/歌手在<b>同一个平台</b>的其它脚本源里重新找同一首歌。
     *
     * <p>只在同平台内换。跨平台换（网易云的歌拿去 QQ 找）是像素播放器明确不做的：
     * 聚合音源内部会再换一层平台，最后拿回来的是别的平台的「占位试听流」，
     * 听着像对了其实放的是另一个人唱的版本。同平台的两个脚本源之间换，
     * 歌的标识一定是这一平台的，干净。
     */
    private static Result crossSearch(Context context, Request request, JsProvider js) {
        Result result = new Result();
        result.error = "没有别的音源能出这首歌";
        if (js == null || TextUtils.isEmpty(request.title)) {
            return result;
        }
        List<MusicSourceDef> sources = listSources(null, js);
        for (MusicSourceDef def : sources) {
            if (!def.supportsSearch || !request.platform.equals(def.platform)) {
                continue;
            }
            LxRuntime runtime = js.readyRuntime(def.fileName);
            if (runtime == null) {
                continue;
            }
            List<LxRuntime.SearchHit> hits = runtime.search(def.platform, request.title, 1, 5);
            LxRuntime.SearchHit hit = null;
            for (LxRuntime.SearchHit h : hits) {
                if (sameSong(h, request)) {
                    hit = h;
                    break;
                }
            }
            if (hit == null) {
                continue;
            }
            String info = musicInfoJson(def.platform, hit.id, hit.title, hit.artist, hit.album);
            for (String q : ladderFor(request.quality, def.qualities)) {
                LxRuntime.UrlResult url = runtime.resolveUrl(def.platform, info, q);
                if (url != null && url.ok && isUsableUrl(url.url)) {
                    result.ok = true;
                    result.url = url.url;
                    result.sourceId = def.id;
                    result.sourceName = def.name + " · " + LxSource.platformName(def.platform);
                    result.quality = q;
                    result.platform = def.platform;
                    return result;
                }
            }
        }
        return result;
    }
    /** 跨源搜出来的歌要跟目标对得上，否则宁可不要 */
    private static boolean sameSong(LxRuntime.SearchHit hit, Request request) {
        String wantTitle = clean(request.title);
        String gotTitle = clean(hit.title);
        if (TextUtils.isEmpty(wantTitle) || TextUtils.isEmpty(gotTitle)) {
            return false;
        }
        boolean titleOk = gotTitle.contains(wantTitle) || wantTitle.contains(gotTitle);
        if (!titleOk) {
            return false;
        }
        String wantArtist = clean(request.artist);
        if (TextUtils.isEmpty(wantArtist)) {
            return true;
        }
        String gotArtist = clean(hit.artist);
        if (TextUtils.isEmpty(gotArtist)) {
            return true;
        }
        return gotArtist.contains(wantArtist) || wantArtist.contains(gotArtist);
    }

    private static String clean(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        return s.replaceAll("[\\s　·・\\-_、,，.。!！?？'\"《》()（）]", "").toLowerCase(Locale.ROOT);
    }

    /** 构造给 JS 引擎的歌曲信息 */
    public static String musicInfoJson(String platform, String id, String title, String artist,
            String album) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", id == null ? "" : id);
            o.put("songid", id == null ? "" : id);
            o.put("songmid", id == null ? "" : id);
            o.put("title", title == null ? "" : title);
            o.put("song", title == null ? "" : title);
            o.put("name", title == null ? "" : title);
            o.put("artist", artist == null ? "" : artist);
            o.put("singer", artist == null ? "" : artist);
            o.put("album", album == null ? "" : album);
            o.put("album_name", album == null ? "" : album);
            o.put("platform", platform == null ? "" : platform);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------------------------------------------------------------- 脏链接过滤

    /**
     * 这条地址能不能直接播。
     *
     * <p>三类要拦：加密容器（.ncm，播不了）、占位流（“该内容为付费歌曲”这类）、
     * 以及根本不是网络地址的字符串。
     */
    public static boolean isUsableUrl(String url) {
        if (TextUtils.isEmpty(url) || "null".equals(url)) {
            return false;
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return false;
        }
        // 加密容器：这些格式是加密流，MediaPlayer 放不出声音
        // （列表对齐像素播放器：.mflac/.mgg 是 QQ 音乐的加密容器，.ncm 是网易云的）
        String[] encrypted = {".ncm", ".mflac", ".mflac0", ".mflac2", ".mgg", ".mgg0",
                ".mgg1", ".mggl", ".mmp4"};
        for (String e : encrypted) {
            if (u.contains(e)) {
                return false;
            }
        }
        // 占位流：有些源在拿不到版权时会给一个「提示地址」
        String[] placeholders = {"付费", "试听", "开通", "会员", "抱歉", "该内容", "不可用",
                "vip", "unavailable", "copyright"};
        for (String p : placeholders) {
            if (u.contains(p)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 歌词 / 封面

    /**
     * 取歌词：先问 JS 源，失败再走内置官方接口。
     *
     * @return 歌词文本；都没有返回空串
     */
    public static String lyric(Context context, Request request, JsProvider js) {
        if (js != null) {
            for (MusicSourceDef def : listSources(null, js)) {
                if (!def.supportsLyric) {
                    continue;
                }
                LxRuntime runtime = js.runtimeFor(def.fileName);
                if (runtime == null) {
                    continue;
                }
                String lyric = runtime.resolveLyric(def.platform, request.musicInfoJson);
                if (!TextUtils.isEmpty(lyric)) {
                    return lyric;
                }
            }
        }
        if (!TextUtils.isEmpty(request.onlineId)) {
            try {
                return NeteaseClient.get(context).lyric(request.onlineId);
            } catch (Throwable ignored) {
                return "";
            }
        }
        return "";
    }

    /**
     * 取封面：JS 源能补就补，补不了用内置的。
     *
     * @return 封面地址；都没有返回空串
     */
    public static String pic(Context context, Request request, JsProvider js) {
        if (js != null && TextUtils.isEmpty(request.album)) {
            for (MusicSourceDef def : listSources(null, js)) {
                if (!def.supportsPic) {
                    continue;
                }
                LxRuntime runtime = js.runtimeFor(def.fileName);
                if (runtime == null) {
                    continue;
                }
                String pic = runtime.resolvePic(def.platform, request.musicInfoJson);
                if (!TextUtils.isEmpty(pic) && pic.startsWith("http")) {
                    return pic;
                }
            }
        }
        if (!TextUtils.isEmpty(request.onlineId)) {
            String pic = NeteaseClient.get(context).picUrlOf(request.onlineId);
            if (!TextUtils.isEmpty(pic)) {
                return pic;
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 音质降级序列：从期望音质往下排，只保留这个源真支持的。
     */
    private static List<String> ladderFor(String wanted, List<String> supported) {
        List<String> ladder = new ArrayList<>();
        String start = TextUtils.isEmpty(wanted) ? "320k" : wanted;
        boolean started = false;
        for (String q : QUALITY_LADDER) {
            if (!started && !q.equals(start)) {
                continue;
            }
            started = true;
            if (supported == null || supported.isEmpty() || supported.contains(q)) {
                ladder.add(q);
            }
        }
        if (ladder.isEmpty()) {
            ladder.add(TextUtils.isEmpty(start) ? "128k" : start);
        }
        return ladder;
    }

    /** 当前 JS 源里有没有能出这个平台链接的 */
    public static boolean hasSourceFor(Context context, String platform, JsProvider js) {
        for (MusicSourceDef def : listSources(context, js)) {
            if (platform.equals(def.platform)) {
                return true;
            }
        }
        return false;
    }
}

package com.tvdesk.core.live;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.tvdesk.core.net.Ipv6;
import com.tvdesk.core.net.PreferIpv4Dns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 直播源的取、并、存。
 *
 * <h3>什么时候算「该更新了」</h3>
 * 主源每 6 小时重扫一次，所以缓存 TTL 也定 6 小时：进模块先拿磁盘上的目录立刻显示，
 * 过期了在后台拉新的，拉到再替换——不能让用户对着空列表等一轮下载。
 *
 * <h3>为什么要合并而不是只用一份</h3>
 * 同一个台在不同源里地址不一样，有的这条通、那条不通。<b>合并之后一个频道挂多条地址</b>，
 * 播放失败就换下一条，比让用户回列表手动「换源」省事。
 * 合并顺序即优先级：IPv4 源在前（国内大多能直连），IPv6 源在后，兜底源最后。
 *
 * <h3>同步上游更新</h3>
 * 每次刷新都是一次完整的重新拉取（源码不做增量），但只在需要时发生：
 * 进模块时判断 TTL、用户在设置里点「立即同步」、或者手动下拉。
 * 上游仓库怎么更新我们不管——它推到 master / Pages，我们下次拉到新的就是新的。
 */
public final class LiveRepository {

    private static final String TAG = "LiveRepo";

    /** 缓存 6 小时：和主源的扫描周期对齐 */
    public static final long TTL_MS = 6 * 60 * 60 * 1000L;

    private static final Object LOCK = new Object();
    /**
     * 缓存格式版本。源清单/合并规则变了就 +1：老目录里没有新源的高清地址，
     * 留着只会让人以为「加了源还是糊」。
     */
    private static final int CACHE_VERSION = 17;
    /** 单份源整体上限：主地址 10 秒拿不到就换镜像 */
    private static final long FETCH_TIMEOUT_MS = 8000L;
    /** 一次同步最多等多久（几份源加镜像，串行最坏情况） */
    private static final long SYNC_TIMEOUT_MS = 40000L;

    private static OkHttpClient http;

    private LiveRepository() {
    }

    private static OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .dns(PreferIpv4Dns.INSTANCE)
                    .build();
        }
        return http;
    }

    /** 缓存文件：filesDir/live/catalog.json */
    public static File cacheFile(Context context) {
        File dir = new File(context.getFilesDir(), "live");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, "catalog.json");
    }

    /**
     * 读缓存（不联网）。没有缓存返回 null。
     *
     * <p>界面第一帧就该有东西：先显示缓存，再判断要不要后台刷新。
     */
    public static LiveCatalog cached(Context context) {
        synchronized (LOCK) {
            return readCache(cacheFile(context));
        }
    }

    public static boolean fresh(LiveCatalog catalog) {
        return catalog != null && catalog.updatedAt > 0
                && System.currentTimeMillis() - catalog.updatedAt < TTL_MS;
    }

    /**
     * 拉一次最新（联网，阻塞）。调用方放后台线程。
     *
     * @param force true 表示不管缓存新旧都重拉；false 时缓存新鲜就直接返回缓存
     * @throws IOException 一份都取不到、且没有缓存可用时抛
     */
    public static LiveCatalog load(Context context, boolean force) throws IOException {
        synchronized (LOCK) {
            long started = System.currentTimeMillis();
            File file = cacheFile(context);
            LiveCatalog cached = readCache(file);
            if (!force && fresh(cached)) {
                return cached;
            }
            LiveCatalog merged = new LiveCatalog();
            StringBuilder names = new StringBuilder();
            // 有 IPv6 就先抓高清的 IPv6 单播源；没有就完全跳过它们，
            // 否则每个台都要先等一次连接超时才轮到 IPv4 那一路
            boolean ipv6 = Ipv6.available();
            for (LiveSources.Source source : LiveSources.ordered(ipv6)) {
                if (!source.primary && merged.channelCount() >= LiveSources.MIN_CHANNELS) {
                    // 主源已经够用，兜底源就不打了——省一次跨网下载
                    continue;
                }
                long left = SYNC_TIMEOUT_MS - (System.currentTimeMillis() - started);
                if (left < 3000L) {
                    break;
                }
                int before = merged.channelCount();
                if (fetchInto(merged, source, Math.min(left, FETCH_TIMEOUT_MS * 2))) {
                    int got = merged.channelCount() - before;
                    if (got > 0) {
                        if (names.length() > 0) {
                            names.append(" + ");
                        }
                        names.append(source.title).append('(').append(got).append(')');
                    }
                }
            }
            if (merged.channelCount() == 0) {
                if (cached != null && !cached.isEmpty()) {
                    Log.i(TAG, "这次没拉到，继续用 " + cached.channelCount() + " 个频道的老目录");
                    return cached;
                }
                throw new IOException("直播源都取不到，检查网络后重试");
            }
            // 多份源合过之后顺序是「谁先并进来谁在前」，必须按央视/卫视重排一遍
            merged.sortChannels();
            merged.updatedAt = System.currentTimeMillis();
            merged.sources = names.toString();
            writeCache(file, merged);
            Log.i(TAG, "同步完成：" + merged.channelCount() + " 个频道（" + merged.sources
                    + "）ipv6=" + ipv6);
            return merged;
        }
    }

    /** 拉一份源并进目录；主地址失败自动换镜像。@return 是否至少成功取到一次 */
    private static boolean fetchInto(LiveCatalog into, LiveSources.Source source, long timeoutMs) {
        for (String url : source.urls) {
            String body = get(url, timeoutMs);
            if (TextUtils.isEmpty(body)) {
                continue;
            }
            LiveCatalog parsed;
            try {
                parsed = M3uParser.parse(body);
            } catch (Throwable th) {
                Log.w(TAG, source.id + " 解析失败：" + th.getMessage());
                continue;
            }
            if (parsed.isEmpty()) {
                // 常见于源站返回了一个 HTML 错误页：解析不出频道也不能算成功
                Log.i(TAG, source.id + " " + url + " 解析出 0 个频道，换下一个地址");
                continue;
            }
            // 抽检必须在合并之前：解析得出来不等于播得动。咪咕这类源在非移动线路上
            // 整份 404，但台名和公网源重名，先合并进来就会成为每个台的「第 1 条地址」，
            // 于是每个台都要先白试一次失败才回落
            if (source.verify && !reachable(parsed)) {
                Log.i(TAG, source.id + " 抽检不通过（地址播不动），跳过这份源");
                return false;
            }
            if (into.epgUrl.length() == 0) {
                into.epgUrl = parsed.epgUrl;
            }
            for (String group : parsed.groupNames()) {
                for (LiveChannel channel : parsed.group(group)) {
                    into.add(channel);
                }
            }
            Log.i(TAG, source.id + " 取到 " + parsed.channelCount() + " 个频道：" + url);
            return true;
        }
        Log.i(TAG, source.id + " 所有地址都失败");
        return false;
    }

    /**
     * 抽检：从这份源里挑两条不同的地址各试一次，有一条通就算可用。
     *
     * <p>为什么抽两条：单条可能是源作者漏掉的死链；全都不通基本就是「这份源在当前网络
     * 不可用」（跨运营商、需要白名单、上游下线）。
     */
    private static boolean reachable(LiveCatalog catalog) {
        // 抽 3 条、至少 2 条通才算这份源可用：只过 1 条的多半是个别能播，
        // 剩下全是死链——这种源合并进来只会制造「这个台播不了」的错觉
        int tried = 0;
        int ok = 0;
        for (String group : catalog.groupNames()) {
            for (LiveChannel channel : catalog.group(group)) {
                if (channel.urls.isEmpty()) {
                    continue;
                }
                if (tried >= 3) {
                    return ok >= 2;
                }
                tried++;
                if (probe(channel.urls.get(0))) {
                    ok++;
                }
            }
        }
        return ok >= 2;
    }

    /** 拉一小段看是不是 m3u8；能通就说明这条链路可用 */
    private static boolean probe(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36")
                .build();
        OkHttpClient client = http().newBuilder()
                .callTimeout(3500, TimeUnit.MILLISECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.i(TAG, "抽检 " + request.url().host() + " HTTP " + response.code());
                return false;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return false;
            }
            // 能读到字节就算通（m3u8 是文本，分片是二进制，都满足）
            java.io.InputStream in = body.byteStream();
            try {
                return in.read() != -1;
            } finally {
                in.close();
            }
        } catch (Throwable th) {
            Log.i(TAG, "抽检 " + request.url().host() + " 异常 " + th.getClass().getSimpleName());
            return false;
        }
    }

    private static String get(String url, long timeoutMs) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36")
                .header("Accept", "*/*")
                .build();
        OkHttpClient client = http().newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.i(TAG, url + " HTTP " + response.code());
                return null;
            }
            ResponseBody body = response.body();
            return body == null ? null : body.string();
        } catch (Exception e) {
            Log.i(TAG, url + " 失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- 缓存读写

    private static LiveCatalog readCache(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(readText(file));
            if (root.optInt("v", 0) != CACHE_VERSION) {
                return null;
            }
            LiveCatalog catalog = new LiveCatalog();
            catalog.updatedAt = root.optLong("updatedAt");
            catalog.epgUrl = root.optString("epg", "");
            catalog.sources = root.optString("sources", "");
            JSONArray groups = root.optJSONArray("groups");
            if (groups != null) {
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group == null) {
                        continue;
                    }
                    String name = group.optString("name", "其他");
                    JSONArray channels = group.optJSONArray("channels");
                    if (channels == null) {
                        continue;
                    }
                    for (int j = 0; j < channels.length(); j++) {
                        JSONObject item = channels.optJSONObject(j);
                        if (item == null) {
                            continue;
                        }
                        LiveChannel channel = new LiveChannel(item.optString("name"), name);
                        channel.logo = item.optString("logo", "");
                        channel.epgId = item.optString("epgId", "");
                        JSONArray urls = item.optJSONArray("urls");
                        if (urls != null) {
                            for (int k = 0; k < urls.length(); k++) {
                                String url = urls.optString(k, "");
                                if (url.length() > 0) {
                                    channel.urls.add(url);
                                }
                            }
                        }
                        if (!channel.urls.isEmpty()) {
                            catalog.add(channel);
                        }
                    }
                }
            }
            // 缓存里可能还留着旧版没过滤的广告位（比如「支持作者」）：
            // 读的时候再过一遍，不用等下一次同步
            catalog.dropPlaceholders();
            catalog.sortChannels();
            if (catalog.isEmpty()) {
                return null;
            }
            return catalog;
        } catch (Throwable th) {
            Log.w(TAG, "缓存读不出来，当作没有：" + th.getMessage());
            return null;
        }
    }

    private static void writeCache(File file, LiveCatalog catalog) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", CACHE_VERSION);
            root.put("updatedAt", catalog.updatedAt);
            root.put("epg", catalog.epgUrl);
            root.put("sources", catalog.sources);
            JSONArray groups = new JSONArray();
            for (String name : catalog.groupNames()) {
                JSONObject group = new JSONObject();
                group.put("name", name);
                JSONArray channels = new JSONArray();
                for (LiveChannel channel : catalog.group(name)) {
                    JSONObject item = new JSONObject();
                    item.put("name", channel.name);
                    item.put("logo", channel.logo == null ? "" : channel.logo);
                    item.put("epgId", channel.epgId == null ? "" : channel.epgId);
                    JSONArray urls = new JSONArray();
                    for (String url : channel.urls) {
                        urls.put(url);
                    }
                    item.put("urls", urls);
                    channels.put(item);
                }
                group.put("channels", channels);
                groups.put(group);
            }
            root.put("groups", groups);
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(root.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable th) {
            Log.w(TAG, "缓存写不进去：" + th.getMessage());
        }
    }

    private static String readText(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /**
     * 「上次同步」的人类可读说法，给界面用。
     */
    public static String ago(long updatedAt) {
        if (updatedAt <= 0) {
            return "还没同步过";
        }
        long minutes = (System.currentTimeMillis() - updatedAt) / 60000L;
        if (minutes < 1) {
            return "刚刚同步";
        }
        if (minutes < 60) {
            return minutes + " 分钟前同步";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前同步";
        }
        return (hours / 24) + " 天前同步";
    }

    /** 兜底用：把一份目录摊平成「分组 + 频道」列表，测试和调试用 */
    public static List<LiveChannel> flatten(LiveCatalog catalog) {
        List<LiveChannel> out = new ArrayList<>();
        if (catalog == null) {
            return out;
        }
        // 用排过序的分组：央视/卫视在前，备用/测试垫底（上下键换台按这个顺序走）
        for (String group : catalog.rankedGroupNames()) {
            out.addAll(catalog.group(group));
        }
        return out;
    }
}

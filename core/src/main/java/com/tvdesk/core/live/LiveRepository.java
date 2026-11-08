package com.tvdesk.core.live;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

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
    /** 单份源整体上限：主地址 10 秒拿不到就换镜像 */
    private static final long FETCH_TIMEOUT_MS = 10000L;
    /** 一次同步最多等多久（几份源加镜像，串行最坏情况） */
    private static final long SYNC_TIMEOUT_MS = 45000L;

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
            for (LiveSources.Source source : LiveSources.all()) {
                if (!source.primary && merged.channelCount() >= LiveSources.MIN_CHANNELS) {
                    // 主源已经够用，兜底源就不打了——省一次跨网下载
                    continue;
                }
                long left = SYNC_TIMEOUT_MS - (System.currentTimeMillis() - started);
                if (left < 3000L) {
                    break;
                }
                int before = merged.channelCount();
                if (fetchInto(merged, source, Math.min(left, FETCH_TIMEOUT_MS * 3))) {
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
            merged.updatedAt = System.currentTimeMillis();
            merged.sources = names.toString();
            writeCache(file, merged);
            Log.i(TAG, "同步完成：" + merged.channelCount() + " 个频道（" + merged.sources + "）");
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
        for (String group : catalog.groupNames()) {
            out.addAll(catalog.group(group));
        }
        return out;
    }
}

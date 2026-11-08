package com.tvdesk.core.live;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.tvdesk.core.net.PreferIpv4Dns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 节目单的取、存、读。
 *
 * <h3>多源兜底</h3>
 * 公共 EPG 源很不稳：有的几天不更新，有的直接 502。所以按顺序试，
 * <b>第一个「解出来有内容」的就用</b>，并把它的地址记进缓存，下次先试它。
 *
 * <h3>只留有用的一段</h3>
 * 一份完整 XMLTV 有上万条节目，全存下来在盒子上既费内存也费解析时间。
 * 解析时就把「6 小时前 ~ 36 小时后」之外的丢掉——够判断现在和接下来两档了。
 *
 * <h3>过期不影响主流程</h3>
 * 节目单是锦上添花：拉不到、解析失败、数据过期，都只是界面上少一行字，
 * 直播该播还是播。所以这里的失败一律不外抛。
 */
public final class EpgRepository {

    private static final String TAG = "EpgRepo";

    /** 节目单一天变几次，6 小时刷新一次够用 */
    public static final long TTL_MS = 6 * 60 * 60 * 1000L;

    /**
     * 缓存格式版本。字段结构变了就 +1：老缓存读出来是残缺的，不如直接丢掉重拉。
     * （也顺手当「清缓存」的开关用 —— 验证时用测试数据写过一次缓存，就靠它作废。）
     */
    private static final int CACHE_VERSION = 1;

    /** 按优先级排的源。第一个能解出内容的就被记住，下次先试它 */
    private static final String[] SOURCES = {
            // e2.xml 比 e.xml 大（约 3MB）、频道更全，是同一个提供方的另一份导出
            "http://epg.51zmt.top:8000/e2.xml",
            "http://epg.51zmt.top:8000/e.xml",
            "http://e.erw.cc/e.xml.gz",
            // 本地文件也认（file:///sdcard/... ），便于自己准备一份离线节目单
    };

    private static final long FETCH_TIMEOUT_MS = 15000L;
    /** 保留窗口：往前 6 小时（要知道「正在播」），往后 36 小时 */
    private static final long WINDOW_BACK_MS = 6 * 60 * 60 * 1000L;
    private static final long WINDOW_AHEAD_MS = 36 * 60 * 60 * 1000L;

    private static final String KEY_LAST_SOURCE = "epg_last_source";

    private static OkHttpClient http;

    private EpgRepository() {
    }

    private static OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .dns(PreferIpv4Dns.INSTANCE)
                    .build();
        }
        return http;
    }

    public static File cacheFile(Context context) {
        File dir = new File(context.getFilesDir(), "live");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, "epg.json");
    }

    public static Epg cached(Context context) {
        return readCache(cacheFile(context));
    }

    public static boolean fresh(Epg epg) {
        return epg != null && !epg.isEmpty() && epg.updatedAt > 0
                && System.currentTimeMillis() - epg.updatedAt < TTL_MS;
    }

    /**
     * 拉一次节目单（联网阻塞，后台线程调用）。失败返回 null，不抛。
     *
     * @param force 为真时忽略缓存新鲜度
     */
    public static Epg load(Context context, boolean force) {
        synchronized (EpgRepository.class) {
            Epg cached = readCache(cacheFile(context));
            if (!force && fresh(cached)) {
                return cached;
            }
            long now = System.currentTimeMillis();
            long from = now - WINDOW_BACK_MS;
            long to = now + WINDOW_AHEAD_MS;

            String preferred = context.getSharedPreferences("desk", Context.MODE_PRIVATE)
                    .getString(KEY_LAST_SOURCE, "");
            String[] order = orderSources(preferred);
            for (String url : order) {
                byte[] body = get(url);
                if (body == null) {
                    continue;
                }
                try {
                    Epg epg = Epg.parse(unzipIfNeeded(body), from, to);
                    if (epg.isEmpty()) {
                        // 常见原因不是源坏了，而是它滞后（节目还停在两天前）。
                        // 这时候换下一个源，同时把「源里最晚到哪天」记下来，便于判断
                        Log.i(TAG, url + " 没有覆盖当前时间的节目（源里共 "
                                + epg.totalPrograms + " 条），换下一个源");
                        continue;
                    }
                    epg.updatedAt = now;
                    epg.source = url;
                    writeCache(cacheFile(context), epg);
                    context.getSharedPreferences("desk", Context.MODE_PRIVATE)
                            .edit().putString(KEY_LAST_SOURCE, url).apply();
                    Log.i(TAG, "节目单同步完成：" + epg.channelCount() + " 个频道，来自 " + url);
                    return epg;
                } catch (Throwable th) {
                    Log.i(TAG, url + " 解析失败：" + th.getClass().getSimpleName()
                            + " " + th.getMessage());
                }
            }
            // 全都不行：有旧缓存就继续用（界面上按时间查，过期的自然不显示）
            return cached;
        }
    }

    /** 把上次成功的源排到最前面（不在内置列表里的地址忽略：可能已经下线或换了） */
    private static String[] orderSources(String preferred) {
        if (TextUtils.isEmpty(preferred) || !known(preferred)) {
            return SOURCES;
        }
        String[] out = new String[SOURCES.length];
        out[0] = preferred;
        int index = 1;
        for (String url : SOURCES) {
            if (!url.equals(preferred) && index < out.length) {
                out[index++] = url;
            }
        }
        return out;
    }

    private static boolean known(String url) {
        for (String candidate : SOURCES) {
            if (candidate.equals(url)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] get(String url) {
        // 允许本地文件：方便把一份节目单放到 U 盘/内置存储里离线用，也方便自己造一份测试
        if (url.startsWith("file:")) {
            String path = url.substring("file://".length());
            if (path.startsWith("/") && !url.startsWith("file:///")) {
                path = "/" + path;
            }
            try {
                java.io.File file = new java.io.File(path);
                if (!file.exists() || file.length() == 0) {
                    Log.i(TAG, url + " 文件不存在");
                    return null;
                }
                java.io.FileInputStream in = new java.io.FileInputStream(file);
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    return out.toByteArray();
                } finally {
                    in.close();
                }
            } catch (Throwable th) {
                Log.i(TAG, url + " 读取失败：" + th.getMessage());
                return null;
            }
        }
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36")
                .build();
        OkHttpClient client = http().newBuilder()
                .callTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.i(TAG, url + " HTTP " + response.code());
                return null;
            }
            ResponseBody body = response.body();
            return body == null ? null : body.bytes();
        } catch (Exception e) {
            Log.i(TAG, url + " 失败：" + e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 源可能是 .xml 也可能是 .xml.gz，而 Content-Type 经常写错。
     *
     * <p>所以不看后缀看魔数：{@code 1f 8b} 开头就是 gzip。
     */
    private static InputStream unzipIfNeeded(byte[] body) throws IOException {
        if (body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
            return new GZIPInputStream(new ByteArrayInputStream(body));
        }
        return new ByteArrayInputStream(body);
    }

    // ---------------------------------------------------------------- 缓存

    private static Epg readCache(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(readText(file));
            if (root.optInt("v", 0) != CACHE_VERSION) {
                return null;
            }
            Epg epg = new Epg();
            epg.updatedAt = root.optLong("updatedAt");
            epg.source = root.optString("source", "");
            JSONArray channels = root.optJSONArray("channels");
            if (channels != null) {
                for (int i = 0; i < channels.length(); i++) {
                    JSONObject item = channels.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String name = item.optString("name", "");
                    EpgSchedule schedule = new EpgSchedule();
                    JSONArray programs = item.optJSONArray("programs");
                    if (programs != null) {
                        for (int j = 0; j < programs.length(); j++) {
                            JSONArray program = programs.optJSONArray(j);
                            if (program == null || program.length() < 3) {
                                continue;
                            }
                            schedule.add(new EpgSchedule.Program(program.optLong(0),
                                    program.optLong(1), program.optString(2, "")));
                        }
                    }
                    schedule.sort();
                    epg.putName(name, schedule);
                }
            }
            return epg.isEmpty() ? null : epg;
        } catch (Throwable th) {
            Log.w(TAG, "节目单缓存读不出来：" + th.getMessage());
            return null;
        }
    }

    private static void writeCache(File file, Epg epg) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", CACHE_VERSION);
            root.put("updatedAt", epg.updatedAt);
            root.put("source", epg.source);
            JSONArray channels = new JSONArray();
            for (Map.Entry<String, EpgSchedule> entry : epg.nameMap().entrySet()) {
                JSONObject item = new JSONObject();
                // 键本身就是归一化台名；读回来再归一化一次还是同一个键，查询照样命中
                item.put("name", entry.getKey());
                JSONArray programs = new JSONArray();
                for (EpgSchedule.Program program : entry.getValue().items) {
                    JSONArray one = new JSONArray();
                    one.put(program.startMs);
                    one.put(program.stopMs);
                    one.put(program.title);
                    programs.put(one);
                }
                item.put("programs", programs);
                channels.put(item);
            }
            root.put("channels", channels);
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(root.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable th) {
            Log.w(TAG, "节目单缓存写不进去：" + th.getMessage());
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
}

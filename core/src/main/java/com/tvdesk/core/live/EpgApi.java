package com.tvdesk.core.live;

import android.text.TextUtils;
import android.util.Log;

import com.tvdesk.core.net.PreferIpv4Dns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 按频道查节目单（不走整份 XMLTV）。
 *
 * <h3>为什么单独做一层</h3>
 * 整份 EPG 动辄几兆、上万条节目，一天只变几次，却要为了「我正在看的这一台」
 * 把整份拉下来、整份解析——在电视盒子上太贵了。按台查只拉几十条 JSON，
 * 看哪台查哪台，数据也更容易是当天的。
 *
 * <h3>接口</h3>
 * 用 51zmt 的 DIY 接口：{@code /api/diyp/?ch=台名&date=yyyy-MM-dd}，返回
 * {@code {"channel_name":"…","date":"…","epg_data":[{"start":"20:00","end":"20:30","title":"…"}]}}。
 *
 * <p><b>免费档的现实</b>：对没授权的请求，它会回一条占位节目
 * （{@code 精彩节目-暂未提供节目预告信息}）铺满整天。这种「有数据但都是废话」
 * 比空响应更坑——直接显示就成了「每个台都在播同一档节目」。
 * 所以这里专门识别占位文案，命中就当没有节目单。
 *
 * <h3>缓存</h3>
 * 内存里按「台名 + 日期」存，一天之内同一个台只查一次。进程重启就没，
 * 不做磁盘缓存——节目单是会过期的数据，存下来反而容易误导。
 */
public final class EpgApi {

    private static final String TAG = "EpgApi";

    private static final String ENDPOINT = "http://epg.51zmt.top:8000/api/diyp/";
    /** 占位文案：免费档没有授权时整天的节目都是这一条 */
    private static final String[] PLACEHOLDER = {
            "暂未提供", "未提供节目", "精彩节目-暂未提供", "无节目预告"
    };

    private static final long TIMEOUT_MS = 8000L;
    /** 最多记多少个台：一屏面板也就几十个 */
    private static final int CACHE_MAX = 60;

    private static OkHttpClient http;
    private static final Map<String, EpgSchedule> CACHE =
            new LinkedHashMap<String, EpgSchedule>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, EpgSchedule> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    private EpgApi() {
    }

    private static OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder()
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .dns(PreferIpv4Dns.INSTANCE)
                    .build();
        }
        return http;
    }

    private static String day() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /**
     * 查一个频道今天的节目单（阻塞，后台线程调用）。
     *
     * @return 没有可用节目单时返回 null（含「只有占位文案」的情况）
     */
    public static EpgSchedule fetch(LiveChannel channel) {
        if (channel == null || TextUtils.isEmpty(channel.name)) {
            return null;
        }
        String day = day();
        String key = LiveChannel.normalize(channel.name) + "@" + day;
        synchronized (CACHE) {
            EpgSchedule hit = CACHE.get(key);
            if (hit != null) {
                return hit == EMPTY ? null : hit;
            }
        }
        EpgSchedule schedule = request(channel.name, day);
        synchronized (CACHE) {
            // 查不到也记下来（存 null 用哨兵），免得同一个台反复打接口
            CACHE.put(key, schedule == null ? EMPTY : schedule);
        }
        return schedule;
    }

    /** 哨兵：表示「查过了，没有」。省得缓存里分不清「没查过」和「查过但没有」 */
    private static final EpgSchedule EMPTY = new EpgSchedule();

    private static EpgSchedule request(String name, String day) {
        String url;
        try {
            url = ENDPOINT + "?ch=" + URLEncoder.encode(name, "UTF-8") + "&date=" + day;
        } catch (Exception e) {
            return null;
        }
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 9)")
                .build();
        try (Response response = http().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.i(TAG, name + " HTTP " + response.code());
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            JSONObject json = new JSONObject(body.string());
            JSONArray items = json.optJSONArray("epg_data");
            if (items == null || items.length() == 0) {
                return null;
            }
            EpgSchedule schedule = new EpgSchedule();
            boolean allPlaceholder = true;
            long dayStart = dayStart(day);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String title = item.optString("title", "").trim();
                long start = at(dayStart, item.optString("start", ""));
                long stop = at(dayStart, item.optString("end", ""));
                if (start <= 0 || stop <= start) {
                    // 「23:00-01:00」这类跨零点：结束时间比开始小，补一天
                    if (start > 0 && stop > 0 && stop <= start) {
                        stop += 24 * 60 * 60 * 1000L;
                    } else {
                        continue;
                    }
                }
                if (!isPlaceholder(title)) {
                    allPlaceholder = false;
                }
                schedule.add(new EpgSchedule.Program(start, stop, title));
            }
            if (schedule.items.isEmpty()) {
                return null;
            }
            schedule.sort();
            if (allPlaceholder) {
                // 免费档的占位铺满整天：宁可没有，也别让用户看到同一个假节目名
                Log.i(TAG, name + " 只拿到占位节目单（未授权），按没有处理");
                return null;
            }
            Log.i(TAG, name + " 查到 " + schedule.items.size() + " 条节目");
            return schedule;
        } catch (Throwable th) {
            Log.i(TAG, name + " 查询失败：" + th.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean isPlaceholder(String title) {
        if (TextUtils.isEmpty(title)) {
            return true;
        }
        for (String mark : PLACEHOLDER) {
            if (title.contains(mark)) {
                return true;
            }
        }
        return false;
    }

    /** 当天 00:00 的毫秒时间戳 */
    private static long dayStart(String day) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date date = format.parse(day);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            return calendar.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    /** "20:30" + 当天零点 → 毫秒 */
    private static long at(long dayStart, String clock) {
        if (dayStart <= 0 || TextUtils.isEmpty(clock)) {
            return 0;
        }
        String[] parts = clock.trim().split(":");
        if (parts.length < 2) {
            return 0;
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            return dayStart + (hour * 60L + minute) * 60L * 1000L;
        } catch (Exception e) {
            return 0;
        }
    }
}

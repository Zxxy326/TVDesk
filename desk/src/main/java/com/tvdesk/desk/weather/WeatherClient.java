package com.tvdesk.desk.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * 桌面右上角的天气。
 *
 * <h3>数据来自哪</h3>
 * 移植自 Aerial TV 的 {@code weather/Weather.kt}：
 * <ul>
 *   <li>定位：{@code https://ipinfo.io/json} 的 {@code loc}（"lat,lon"），不需要 key；</li>
 *   <li>天气：{@code api.open-meteo.com/v1/forecast}，同样是免 key、国内可直连的接口；</li>
 *   <li>城市名：用户可以在设置里指定，指定后走 open-meteo 的 geocoding 查坐标，</li>
 * </ul>
 * 读数缓存一小时，三小时以上就不显示了（免得挂着一个早就过期的温度）。
 *
 * <p>阻塞调用，调用方自己放后台线程。
 */
public final class WeatherClient {

    private static final String TAG = "DeskWeather";
    private static final String PREFS = "desk_weather";
    private static final long FRESH_MS = 60 * 60 * 1000L;
    private static final long STALE_MS = 3 * 60 * 60 * 1000L;

    /** 天气种类，和 WMO 的 weather_code 对上 */
    public static final int CLEAR = 0;
    public static final int PARTLY = 1;
    public static final int CLOUDY = 2;
    public static final int FOG = 3;
    public static final int RAIN = 4;
    public static final int SNOW = 5;
    public static final int THUNDER = 6;

    /** 一次读数 */
    public static class Now {
        public int celsius;
        public int kind;
        public boolean day = true;
        public Integer high;
        public Integer low;
        public String place = "";
    }

    private WeatherClient() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 取天气。
     *
     * @param force 忽略缓存，强制刷新
     * @return 拿不到返回 null（调用方把天气那一块藏起来就是了）
     */
    public static Now fetch(Context context, boolean force) {
        SharedPreferences p = prefs(context);
        long at = p.getLong("fetched_at", 0L);
        if (!force && System.currentTimeMillis() - at < FRESH_MS) {
            Now cached = read(p);
            if (cached != null) {
                return cached;
            }
        }
        try {
            float[] latLon = locate(context, p);
            if (latLon == null) {
                return read(p);
            }
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + latLon[0]
                    + "&longitude=" + latLon[1]
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto";
            JSONObject json = new JSONObject(get(url));
            JSONObject current = json.optJSONObject("current");
            if (current == null) {
                return read(p);
            }
            JSONObject daily = json.optJSONObject("daily");
            Now now = new Now();
            now.celsius = (int) Math.round(current.optDouble("temperature_2m", 0));
            now.kind = kindOf(current.optInt("weather_code", 0));
            now.day = current.optInt("is_day", 1) == 1;
            now.high = dailyInt(daily, "temperature_2m_max");
            now.low = dailyInt(daily, "temperature_2m_min");
            now.place = p.getString("place", "");
            p.edit()
                    .putLong("fetched_at", System.currentTimeMillis())
                    .putInt("celsius", now.celsius)
                    .putInt("kind", now.kind)
                    .putBoolean("day", now.day)
                    .putInt("high", now.high == null ? Integer.MIN_VALUE : now.high)
                    .putInt("low", now.low == null ? Integer.MIN_VALUE : now.low)
                    .apply();
            return now;
        } catch (Throwable th) {
            Log.w(TAG, "天气取不到：" + th.getMessage());
            return read(p);
        }
    }

    private static Integer dailyInt(JSONObject daily, String key) {
        if (daily == null) {
            return null;
        }
        JSONArray arr = daily.optJSONArray(key);
        if (arr == null || arr.length() == 0) {
            return null;
        }
        double v = arr.optDouble(0, Double.NaN);
        return Double.isNaN(v) ? null : (int) Math.round(v);
    }

    private static Now read(SharedPreferences p) {
        long at = p.getLong("fetched_at", 0L);
        if (at <= 0L || System.currentTimeMillis() - at > STALE_MS) {
            return null;
        }
        Now now = new Now();
        now.celsius = p.getInt("celsius", 0);
        now.kind = p.getInt("kind", CLEAR);
        now.day = p.getBoolean("day", true);
        int high = p.getInt("high", Integer.MIN_VALUE);
        int low = p.getInt("low", Integer.MIN_VALUE);
        now.high = high == Integer.MIN_VALUE ? null : high;
        now.low = low == Integer.MIN_VALUE ? null : low;
        now.place = p.getString("place", "");
        return now;
    }

    /** 定位：用户指定过城市就查那座城，否则问 ipinfo */
    private static float[] locate(Context context, SharedPreferences p) {
        String city = p.getString("city", "");
        if (!TextUtils.isEmpty(city)) {
            float[] cached = new float[]{p.getFloat("lat", Float.NaN),
                    p.getFloat("lon", Float.NaN)};
            if (!Float.isNaN(cached[0]) && !Float.isNaN(cached[1])
                    && city.equals(p.getString("city_cached", ""))) {
                return cached;
            }
            float[] hit = geocode(city);
            if (hit != null) {
                p.edit().putFloat("lat", hit[0]).putFloat("lon", hit[1])
                        .putString("city_cached", city)
                        .putString("place", city).apply();
                return hit;
            }
        }
        try {
            JSONObject ip = new JSONObject(get("https://ipinfo.io/json"));
            String loc = ip.optString("loc", "");
            if (!loc.contains(",")) {
                return null;
            }
            String[] parts = loc.split(",");
            float lat = Float.parseFloat(parts[0].trim());
            float lon = Float.parseFloat(parts[1].trim());
            String name = ip.optString("city", "");
            p.edit().putFloat("lat", lat).putFloat("lon", lon)
                    .putString("place", name).apply();
            return new float[]{lat, lon};
        } catch (Throwable th) {
            Log.w(TAG, "IP 定位失败：" + th.getMessage());
            return null;
        }
    }

    /** 城市名 → 坐标（open-meteo 的 geocoding，免 key） */
    private static float[] geocode(String name) {
        try {
            String q = URLEncoder.encode(name.trim(), "UTF-8");
            JSONObject json = new JSONObject(get(
                    "https://geocoding-api.open-meteo.com/v1/search?name=" + q
                            + "&count=10&language=zh&format=json"));
            JSONArray results = json.optJSONArray("results");
            if (results == null || results.length() == 0) {
                return null;
            }
            // 同名的地方很多，优先城镇（PPLA/PPLC）再退到第一个
            JSONObject best = null;
            for (int i = 0; i < results.length(); i++) {
                JSONObject o = results.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String code = o.optString("feature_code", "");
                if (code.startsWith("PPLA") || "PPLC".equals(code)) {
                    best = o;
                    break;
                }
                if (best == null) {
                    best = o;
                }
            }
            if (best == null) {
                return null;
            }
            return new float[]{(float) best.optDouble("latitude", 0),
                    (float) best.optDouble("longitude", 0)};
        } catch (Throwable th) {
            Log.w(TAG, "城市查不到：" + name);
            return null;
        }
    }

    /** 设置城市（空串表示回到 IP 定位） */
    public static void setCity(Context context, String city) {
        prefs(context).edit()
                .putString("city", city == null ? "" : city.trim())
                .remove("city_cached")
                .putLong("fetched_at", 0L)
                .apply();
    }

    public static String getCity(Context context) {
        return prefs(context).getString("city", "");
    }

    /** WMO weather_code → 种类（对齐 Aerial TV 的映射） */
    private static int kindOf(int code) {
        if (code == 0) {
            return CLEAR;
        }
        if (code == 1 || code == 2) {
            return PARTLY;
        }
        if (code == 3) {
            return CLOUDY;
        }
        if (code == 45 || code == 48) {
            return FOG;
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return SNOW;
        }
        if (code >= 95) {
            return THUNDER;
        }
        return RAIN;
    }

    /**
     * 天气对应的描边图标（tabler 风格，和全站图标一个语言）。
     *
     * <p>emoji 版本 {@link #glyph} 在电视字体里粗细和旁边的文字对不上，
     * ⛅ 这种还会走彩色 emoji 字体，和满屏单色描边图标放一起很突兀。
     */
    public static int iconRes(int kind, boolean day) {
        switch (kind) {
            case CLEAR:
                return day ? com.tvdesk.desk.R.drawable.ic_weather_sun
                        : com.tvdesk.desk.R.drawable.ic_weather_moon;
            case PARTLY:
                return com.tvdesk.desk.R.drawable.ic_weather_partly;
            case CLOUDY:
                return com.tvdesk.desk.R.drawable.ic_weather_cloud;
            case FOG:
                return com.tvdesk.desk.R.drawable.ic_weather_fog;
            case RAIN:
                return com.tvdesk.desk.R.drawable.ic_weather_rain;
            case SNOW:
                return com.tvdesk.desk.R.drawable.ic_weather_snow;
            case THUNDER:
                return com.tvdesk.desk.R.drawable.ic_weather_storm;
            default:
                return com.tvdesk.desk.R.drawable.ic_weather_cloud;
        }
    }

    /** 天气的符号（系统字体里有的那几个） */
    public static String glyph(int kind, boolean day) {
        switch (kind) {
            case CLEAR:
                return day ? "☀" : "☾";
            case PARTLY:
                return day ? "⛅" : "☁";
            case CLOUDY:
                return "☁";
            case FOG:
                return "≋";
            case RAIN:
                return "☂";
            case SNOW:
                return "❄";
            case THUNDER:
                return "⚡";
            default:
                return "";
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "TVDesk/1.0 (Android TV)");
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            in.close();
            return buf.toString("UTF-8");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 给状态行用的完整描述，例如「晴 26° 18~30°」 */
    public static String describe(Now now) {
        if (now == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(now.celsius).append("°");
        if (now.high != null && now.low != null) {
            sb.append(" ").append(now.low).append("~").append(now.high).append("°");
        }
        return sb.toString();
    }

    /** 天气中文名 */
    public static String label(int kind) {
        switch (kind) {
            case CLEAR:
                return "晴";
            case PARTLY:
                return "多云";
            case CLOUDY:
                return "阴";
            case FOG:
                return "雾";
            case RAIN:
                return "雨";
            case SNOW:
                return "雪";
            case THUNDER:
                return "雷";
            default:
                return "";
        }
    }

    /** 温度带正负号，便于直接拼文字 */
    public static String temperature(Now now) {
        if (now == null) {
            return "";
        }
        return String.format(Locale.CHINA, "%d°", now.celsius);
    }
}

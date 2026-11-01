package com.github.catvod.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Map;

/**
 * 播放链接的持久化缓存。
 *
 * <h3>为什么落盘而不是只放内存</h3>
 * 取一条链可能要跑一次 JS 引擎、几十秒。切歌切回来、页面重建、进程被系统回收后重来，
 * 都在重复付这笔时间。落盘之后，进程重启也还在。
 *
 * <h3>缓存键</h3>
 * {@code songKey|source|quality} —— 同一首歌、换一个源、音质变了，都是不同的条目：
 * 不同源给的音质不同，同一首歌在不同音质下也是不同的地址。
 *
 * <h3>不缓存本机代理地址</h3>
 * 代理地址常常是 {@code http://127.0.0.1:端口} 这种本机地址，
 * 端口一变旧地址就失效，缓存下来反而会一直播错。碰到这类地址直接不写。
 */
public final class UrlCache {

    private static final String PREF = "music_url_cache";
    /** 缓存有效期 15 分钟 */
    public static final long TTL_MS = 15 * 60 * 1000L;

    private UrlCache() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 缓存键 */
    public static String key(String songKey, String sourceId, String quality) {
        return (songKey == null ? "" : songKey) + "|" + (sourceId == null ? "" : sourceId)
                + "|" + (quality == null ? "" : quality);
    }

    /**
     * 取缓存的地址。
     *
     * @return 没命中、过期或地址不可用时返回空串
     */
    public static String get(Context context, String cacheKey) {
        if (context == null || TextUtils.isEmpty(cacheKey)) {
            return "";
        }
        SharedPreferences p = prefs(context);
        String url = p.getString(cacheKey, "");
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        long at = p.getLong(cacheKey + "#at", 0L);
        if (at <= 0L || System.currentTimeMillis() - at > TTL_MS) {
            // 过期就顺手删掉，别让它一直占着
            p.edit().remove(cacheKey).remove(cacheKey + "#at").apply();
            return "";
        }
        if (isLocalProxy(url)) {
            // 本机代理端口每次重启都会变，缓存里的旧地址留着只会一直播错
            p.edit().remove(cacheKey).remove(cacheKey + "#at").apply();
            return "";
        }
        return url;
    }

    /**
     * 写缓存。
     *
     * <p>本机代理地址不写：端口变了旧地址就是错的，留着比没有更糟。
     */
    public static void put(Context context, String cacheKey, String url) {
        if (context == null || TextUtils.isEmpty(cacheKey) || TextUtils.isEmpty(url)) {
            return;
        }
        if (isLocalProxy(url)) {
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // 只缓存网络直链
            return;
        }
        prefs(context).edit()
                .putString(cacheKey, url)
                .putLong(cacheKey + "#at", System.currentTimeMillis())
                .apply();
    }

    /** 是不是本机代理地址（这类地址不缓存） */
    public static boolean isLocalProxy(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String u = url.toLowerCase(java.util.Locale.ROOT);
        return u.contains("127.0.0.1") || u.contains("localhost") || u.contains("[::1]");
    }

    /** 清空全部缓存 */
    public static void clear(Context context) {
        if (context == null) {
            return;
        }
        prefs(context).edit().clear().apply();
    }

    /** 当前缓存条数（设置页展示用） */
    public static int count(Context context) {
        if (context == null) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, ?> e : prefs(context).getAll().entrySet()) {
            if (!e.getKey().endsWith("#at")) {
                n++;
            }
        }
        return n;
    }
}

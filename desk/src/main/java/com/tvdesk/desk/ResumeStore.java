package com.tvdesk.desk;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 续播点：这部片子看到哪条线路的第几集。
 *
 * <h3>为什么另存一份</h3>
 * 内核本来就有一张 {@code vodRecord} 表（{@code RoomDataManger.insertVodRecord}），
 * 存的是整份 {@code VodInfo}，集数也在里面。但实机上那张表的表现不稳：
 * 有时写不进去、有时读出来的 id 对不上（源站改过 id），用户看到的就是
 * 「从播放记录点进去还是第一集」。
 *
 * <p>这里用一组极简的键值做兜底：{@code sourceKey|vodId → 集数|集名|时间}。
 * 它只依赖 SharedPreferences，读写都是同步的、不会失效。详情页两处都读，
 * 谁有值用谁；内核表里那份仍然照写，不冲突。
 *
 * <h3>一集之内的秒数</h3>
 * 不进这里：播放器按「源+id+线路+集数+集名」自己把进度存在缓存里，
 * 集数一对上，起播就会自动 seek 回上次退出的位置。
 */
final class ResumeStore {

    private static final String PREFS = "desk";
    private static final String KEY = "vod_resume";
    /** 最多记这么多部片子的续播点，超了丢最旧的 */
    private static final int MAX = 60;

    /** 读出来的续播点 */
    static final class Point {
        int index;
        String episode;

        Point(int index, String episode) {
            this.index = index;
            this.episode = episode;
        }
    }

    private ResumeStore() {
    }

    static void save(Context context, String sourceKey, String vodId, int index, String episode) {
        if (TextUtils.isEmpty(sourceKey) || TextUtils.isEmpty(vodId) || index < 0) {
            return;
        }
        SharedPreferences prefs = prefs(context);
        String key = keyOf(sourceKey, vodId);
        // 值格式：序号|集名|写入时间（时间用来淘汰最旧的）
        String value = index + "|" + (episode == null ? "" : episode.replace("|", " "))
                + "|" + System.currentTimeMillis();
        prefs.edit().putString(key, value).apply();
        trim(prefs);
    }

    static Point read(Context context, String sourceKey, String vodId) {
        if (TextUtils.isEmpty(sourceKey) || TextUtils.isEmpty(vodId)) {
            return null;
        }
        String value = prefs(context).getString(keyOf(sourceKey, vodId), null);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        String[] parts = value.split("\\|");
        if (parts.length < 1) {
            return null;
        }
        try {
            int index = Integer.parseInt(parts[0].trim());
            String episode = parts.length > 1 ? parts[1] : "";
            return new Point(index, episode);
        } catch (Throwable th) {
            return null;
        }
    }

    /** 太多片子时按写入时间淘汰一批，别让 prefs 无限长 */
    private static void trim(SharedPreferences prefs) {
        java.util.Map<String, ?> all = prefs.getAll();
        java.util.List<String> ours = new java.util.ArrayList<>();
        for (String key : all.keySet()) {
            if (key.startsWith(KEY + "_")) {
                ours.add(key);
            }
        }
        if (ours.size() <= MAX) {
            return;
        }
        java.util.Collections.sort(ours, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Long.compare(at(prefs, a), at(prefs, b));
            }
        });
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < ours.size() - MAX; i++) {
            editor.remove(ours.get(i));
        }
        editor.apply();
    }

    private static long at(SharedPreferences prefs, String key) {
        String value = prefs.getString(key, "");
        if (value == null) {
            return 0L;
        }
        String[] parts = value.split("\\|");
        if (parts.length < 3) {
            return 0L;
        }
        try {
            return Long.parseLong(parts[2].trim());
        } catch (Throwable th) {
            return 0L;
        }
    }

    private static String keyOf(String sourceKey, String vodId) {
        return KEY + "_" + sourceKey + "_" + vodId;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

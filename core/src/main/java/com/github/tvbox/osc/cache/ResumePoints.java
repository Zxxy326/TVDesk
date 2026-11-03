package com.github.tvbox.osc.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.github.tvbox.osc.base.App;

/**
 * 续播点：这部片子看到第几集的。
 *
 * <h3>为什么放在 core</h3>
 * 写它的人有两个：详情页（桌子模块，desk）和播放器（app 模块，播放器里换集、
 * 自动下一集都在那边改 {@code playIndex}）。app 依赖 desk 依赖 core，
 * 唯一能被两边同时引用的地方就是 core。
 *
 * <h3>为什么要另存一份</h3>
 * 内核本来就有一张 {@code vodRecord} 表，存整份 {@code VodInfo}，集数也在里面。
 * 但那张表的表现不稳：有时写不进去、有时读出来的 id 对不上（源站改过 id）。
 * 这里用一组极简键值做兜底：{@code 源|id → 集数|集名|时间}，另加一份按片名的
 * {@code 片名 → 集数|集名|时间}，源站换了详情页地址也能找回来。
 *
 * <p>一集之内的秒数不在这里：播放器把进度按「源+id+线路+集数+集名」存在内核缓存里，
 * 集数一对上，起播就会自动 seek 回上次退出的位置。
 */
public final class ResumePoints {

    private static final String PREFS = "desk";
    private static final String KEY = "vod_resume";
    /** 最多记这么多部片子的续播点，超了丢最旧的 */
    private static final int MAX = 60;

    /** 读出来的续播点 */
    public static final class Point {
        public int index;
        public String episode;
        /** 是不是按片名兜底找到的（跨了源，集数不能直接信） */
        public boolean byName;

        Point(int index, String episode) {
            this.index = index;
            this.episode = episode == null ? "" : episode;
        }
    }

    private ResumePoints() {
    }

    public static void save(String sourceKey, String vodId, String vodName, int index, String episode) {
        if (TextUtils.isEmpty(sourceKey) || TextUtils.isEmpty(vodId) || index < 0) {
            return;
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        String note = episode == null ? "" : episode.replace("|", " ");
        // 值格式：序号|集名|写入时间（时间用来淘汰最旧的）
        String value = index + "|" + note + "|" + System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(keyOf(sourceKey, vodId), value);
        if (!TextUtils.isEmpty(vodName)) {
            editor.putString(nameKeyOf(vodName), value);
        }
        editor.apply();
        trim(prefs);
    }

    public static Point read(String sourceKey, String vodId) {
        return read(sourceKey, vodId, null);
    }

    /**
     * 先按「源+id」查，没有再按片名查。
     *
     * <p>按片名找回的那份可能来自另一个源，集数编排未必一样，
     * 调用方要拿 {@link Point#episode} 的集名再核对一遍，别直接信这个数字。
     */
    public static Point read(String sourceKey, String vodId, String vodName) {
        if (TextUtils.isEmpty(sourceKey) || TextUtils.isEmpty(vodId)) {
            return null;
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return null;
        }
        Point point = parse(prefs.getString(keyOf(sourceKey, vodId), null));
        if (point != null || TextUtils.isEmpty(vodName)) {
            return point;
        }
        Point fallback = parse(prefs.getString(nameKeyOf(vodName), null));
        if (fallback != null) {
            fallback.byName = true;
        }
        return fallback;
    }

    private static Point parse(String value) {
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

    /** 按片名的兜底键（换过 id 时还能找回来） */
    private static String nameKeyOf(String vodName) {
        return KEY + "_n_" + (vodName == null ? "" : vodName.trim());
    }

    // ---------------------------------------------------------------- 一集之内看到第几秒

    private static final String POS = "vod_pos";

    /**
     * 记「这部片子的这一集看到第几秒」。
     *
     * <p>键按「片名 + 集名」而不是集数：同一部剧换了源/换了线路，集数会变，
     * 集名基本不变；按集数存的话，换条线路接着看就成了「跳到别的集的时间点」。
     */
    public static void savePosition(String vodName, String episode, long position) {
        if (TextUtils.isEmpty(vodName) || TextUtils.isEmpty(episode) || position <= 0) {
            return;
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit()
                .putString(posKeyOf(vodName, episode), position + "|" + System.currentTimeMillis())
                .apply();
    }

    /** 读「这一集看到第几秒」；没记过返回 0 */
    public static long position(String vodName, String episode) {
        if (TextUtils.isEmpty(vodName) || TextUtils.isEmpty(episode)) {
            return 0L;
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return 0L;
        }
        String value = prefs.getString(posKeyOf(vodName, episode), null);
        if (TextUtils.isEmpty(value)) {
            return 0L;
        }
        int cut = value.indexOf('|');
        try {
            return Long.parseLong((cut > 0 ? value.substring(0, cut) : value).trim());
        } catch (Throwable th) {
            return 0L;
        }
    }

    private static String posKeyOf(String vodName, String episode) {
        return POS + "_" + (vodName == null ? "" : vodName.trim())
                + "_" + (episode == null ? "" : episode.trim());
    }

    private static SharedPreferences prefs() {
        try {
            Context context = App.getInstance();
            if (context == null) {
                return null;
            }
            return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable th) {
            return null;
        }
    }
}

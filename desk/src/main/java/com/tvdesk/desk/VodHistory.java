package com.tvdesk.desk;

import android.content.Context;
import android.text.TextUtils;

import com.github.tvbox.osc.bean.Movie;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 影视播放历史（本地）。
 *
 * <h3>为什么要有</h3>
 * 顶栏「收藏」那一栏以前只放收藏。收藏是「我想留着以后看」，
 * 历史是「我刚看过、可能还想接着看」——两件事，但用户在这一栏里找的都是
 * 「我之前看过的那些片子」。收藏和历史各占一栏，找起来不混。
 *
 * <h3>怎么存的</h3>
 * 跟 {@link VodFavorites} 一样：整份清单序列化成 JSON 串塞进 {@code desk} 这组
 * SharedPreferences，上限 100 条，读一次缓存在内存里。
 *
 * <h3>什么时候记</h3>
 * 每播一集就记一次（记的是「这部片子」而不是「这一集」——
 * 详情页的选集进度播放器自己管着）。
 */
public final class VodHistory {

    private static final String PREFS = "desk";
    private static final String KEY = "vod_history";
    private static final int MAX = 100;

    /** 首装或读坏了的时候当作没有历史，而不是抛出去把首页带崩 */
    private static List<Movie.Video> entries;

    private VodHistory() {
    }

    public static List<Movie.Video> list(Context context) {
        if (entries == null) {
            entries = read(context);
        }
        return entries;
    }

    /**
     * 记一笔「看过这部片子」。
     *
     * <p>已经记过就把它挪到最前面（不是追加一条重复的），
     * 否则连着看同一部剧会产生一长串一样的记录。
     */
    public static void record(Context context, Movie.Video video) {
        if (video == null || TextUtils.isEmpty(video.name)) {
            return;
        }
        list(context);
        String key = keyOf(video);
        for (int i = 0; i < entries.size(); i++) {
            if (keyOf(entries.get(i)).equals(key)) {
                Movie.Video old = entries.remove(i);
                // 用新的一份覆盖：片名/海报可能被源更新过
                entries.add(0, old);
                write(context);
                return;
            }
        }
        entries.add(0, copy(video));
        while (entries.size() > MAX) {
            entries.remove(entries.size() - 1);
        }
        write(context);
    }

    /**
     * 删掉一条历史。
     *
     * <p>观看历史那一栏长按海报用：这条不想要了（比如点错了、
     * 是别人看的），删掉这一条，别的还在。
     */
    public static boolean remove(Context context, Movie.Video video) {
        list(context);
        String key = keyOf(video);
        for (int i = 0; i < entries.size(); i++) {
            if (keyOf(entries.get(i)).equals(key)) {
                entries.remove(i);
                write(context);
                return true;
            }
        }
        return false;
    }

    /** 清空历史 */
    public static void clear(Context context) {
        list(context);
        entries.clear();
        write(context);
    }

    /** 判重依据：跟收藏一致 —— 有源站标识就按 (sourceKey, id) 认 */
    private static String keyOf(Movie.Video video) {
        if (video == null) {
            return "";
        }
        if (!TextUtils.isEmpty(video.sourceKey) && !TextUtils.isEmpty(video.id)) {
            return video.sourceKey + "|" + video.id;
        }
        return "name|" + (video.name == null ? "" : video.name.trim());
    }

    /** 存副本：调用方手里那份是列表数据，之后还会被行刷新改内容 */
    private static Movie.Video copy(Movie.Video src) {
        Movie.Video dst = new Movie.Video();
        dst.id = src.id;
        dst.name = src.name;
        dst.pic = src.pic;
        dst.picSlide = src.picSlide;
        dst.type = src.type;
        dst.area = src.area;
        dst.year = src.year;
        dst.note = src.note;
        dst.des = src.des;
        dst.sourceKey = src.sourceKey;
        return dst;
    }

    private static List<Movie.Video> read(Context context) {
        List<Movie.Video> result = new ArrayList<>();
        String raw = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (TextUtils.isEmpty(raw)) {
            return result;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null || TextUtils.isEmpty(obj.optString("name"))) {
                    continue;
                }
                Movie.Video video = new Movie.Video();
                video.id = obj.optString("id", "");
                video.name = obj.optString("name");
                video.pic = obj.optString("pic", "");
                video.picSlide = obj.optString("picSlide", "");
                video.type = obj.optString("type", "");
                video.area = obj.optString("area", "");
                video.year = obj.optInt("year", 0);
                video.note = obj.optString("note", "");
                video.des = obj.optString("des", "");
                video.sourceKey = obj.optString("sourceKey", "");
                result.add(video);
            }
        } catch (Throwable th) {
            return new ArrayList<>();
        }
        return result;
    }

    private static void write(Context context) {
        JSONArray arr = new JSONArray();
        for (Movie.Video video : entries) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", video.id == null ? "" : video.id);
                obj.put("name", video.name == null ? "" : video.name);
                obj.put("pic", video.pic == null ? "" : video.pic);
                obj.put("picSlide", video.picSlide == null ? "" : video.picSlide);
                obj.put("type", video.type == null ? "" : video.type);
                obj.put("area", video.area == null ? "" : video.area);
                obj.put("year", video.year);
                obj.put("note", video.note == null ? "" : video.note);
                obj.put("des", video.des == null ? "" : video.des);
                obj.put("sourceKey", video.sourceKey == null ? "" : video.sourceKey);
            } catch (Throwable ignored) {
                // 一条写不进去就跳过，别把整份清单弄丢
                continue;
            }
            arr.put(obj);
        }
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }
}

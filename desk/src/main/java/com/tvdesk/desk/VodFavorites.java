package com.tvdesk.desk;

import android.content.Context;
import android.text.TextUtils;

import com.github.tvbox.osc.bean.Movie;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 影片收藏（纯本地）。
 *
 * <p>没有复用 TVBox 那张 {@code vodCollect} 表：它按 (sourceKey, vodId) 判重，而首页那批
 * 豆瓣条目两个字段都是空的——收第二部会把第一部顶掉。豆瓣条目本来也不可直接播，
 * 点它是按片名搜索，存在本地一样走得通。
 *
 * <p>整份清单序列化成一个 JSON 串放进 {@code desk} 这组 SharedPreferences：上限 100 条，
 * 一次读全、内存里判重，比给 desk 模块单开一间 Room 表划算。全部方法都在主线程调用。
 */
public final class VodFavorites {

    private static final String PREFS = "desk";
    private static final String KEY = "vod_favorites";
    private static final int MAX = 100;

    /** 首装或读坏了的时候当作没有收藏，而不是抛出去把首页带崩 */
    private static List<Movie.Video> entries;

    private VodFavorites() {
    }

    public static List<Movie.Video> list(Context context) {
        if (entries == null) {
            entries = read(context);
        }
        return entries;
    }

    /**
     * 收录/取消收录。
     *
     * @return true 表示按完之后是「已收藏」
     */
    public static boolean toggle(Context context, Movie.Video video) {
        if (video == null) {
            return false;
        }
        list(context);
        String key = keyOf(video);
        for (int i = 0; i < entries.size(); i++) {
            if (keyOf(entries.get(i)).equals(key)) {
                entries.remove(i);
                write(context);
                return false;
            }
        }
        entries.add(0, copy(video));
        while (entries.size() > MAX) {
            entries.remove(entries.size() - 1);
        }
        write(context);
        return true;
    }

    public static boolean contains(Context context, Movie.Video video) {
        if (video == null) {
            return false;
        }
        list(context);
        String key = keyOf(video);
        for (Movie.Video entry : entries) {
            if (keyOf(entry).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判重的依据。
     *
     * <p>有源站标识就按 (sourceKey, id) 认——换源之后同一部片的 id 可能撞车；
     * 首页榜单那批两个都没有，只能退回片名。
     */
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
                video.type = obj.optString("type", "");
                video.area = obj.optString("area", "");
                video.year = obj.optInt("year", 0);
                video.note = obj.optString("note", "");
                video.des = obj.optString("des", "");
                video.sourceKey = obj.optString("sourceKey", "");
                result.add(video);
            }
        } catch (Throwable th) {
            android.util.Log.w("VodFavorites", "收藏清单读不出来，当空的", th);
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
                obj.put("type", video.type == null ? "" : video.type);
                obj.put("area", video.area == null ? "" : video.area);
                obj.put("year", video.year);
                obj.put("note", video.note == null ? "" : video.note);
                obj.put("des", video.des == null ? "" : video.des);
                obj.put("sourceKey", video.sourceKey == null ? "" : video.sourceKey);
            } catch (Throwable ignored) {
                // JSONObject.put 只在 key 为 null 时抛，这里全是字面量
                continue;
            }
            arr.put(obj);
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }
}

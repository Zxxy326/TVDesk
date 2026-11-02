package com.tvdesk.desk;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索历史（本地）。
 *
 * <p>电视上打字成本高，搜过一次的片名不该再一个字母一个字母敲一遍。
 * TVBox 也是这个逻辑：搜索页一进来先给最近搜过的几个词。
 *
 * <p>存法跟收藏/历史一样：整份清单塞进 {@code desk} 这组 SharedPreferences，
 * 上限 {@link #MAX} 条，用 {@code \n} 分隔（词条里不会出现换行）。
 */
final class SearchHistory {

    private static final String KEY = "search_history";
    private static final int MAX = 10;

    private SearchHistory() {
    }

    static List<String> list(Context context) {
        List<String> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String item : raw.split("\n")) {
            if (item != null && !item.trim().isEmpty()) {
                out.add(item.trim());
            }
        }
        return out;
    }

    /**
     * 记一笔。已经搜过的挪到最前面，不重复追加——
     * 连着搜同一部剧不该在历史里排一长串一样的词。
     */
    static void record(Context context, String keyword) {
        if (keyword == null) {
            return;
        }
        String word = keyword.trim();
        if (word.isEmpty()) {
            return;
        }
        List<String> list = list(context);
        list.remove(word);
        list.add(0, word);
        while (list.size() > MAX) {
            list.remove(list.size() - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String item : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(item);
        }
        prefs(context).edit().putString(KEY, sb.toString()).apply();
    }

    static void clear(Context context) {
        prefs(context).edit().remove(KEY).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("desk", Context.MODE_PRIVATE);
    }
}

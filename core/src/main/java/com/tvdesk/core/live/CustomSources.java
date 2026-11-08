package com.tvdesk.core.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户自己添加的直播源。
 *
 * <h3>为什么要留这个口子</h3>
 * 内置源都是公开清单，画质和「是不是真频道」都不由我们控制（央视那一批尤其明显：
 * 公开源的 CCTV 常年指向海外广告农场）。用户手里往往有更好的东西——自家宽带的
 * IPTV 导出、朋友维护的私有清单、自己整理的一份 m3u。与其每次都由我们改代码加源，
 * 不如让用户自己填。
 *
 * <h3>优先级</h3>
 * 自定义源<b>排在内置源之前</b>合并（见 {@link LiveRepository#load}）：合并时地址
 * 是「先来先占」，同一个台自定义源的那条地址会成为第 1 条，换台先试它。用户特意
 * 添加的源，理应优先。
 *
 * <h3>存哪</h3>
 * SharedPreferences，只存地址和标题。清单内容本身不存——源会更新，缓存里那份是旧的。
 */
public final class CustomSources {

    /** 一条自定义源 */
    public static final class Entry {
        /** 源地址：http(s):// 或 file:///…（本地文件） */
        public final String url;
        /** 显示名，用户没填就用地址里的文件名 */
        public final String title;

        Entry(String url, String title) {
            this.url = url;
            this.title = title;
        }
    }

    private static final String PREFS = "tvdesk_live_custom";
    private static final String KEY = "sources";

    /** 上限：再多面板里也管理不过来，而且一次同步会越来越慢 */
    private static final int MAX = 20;

    private CustomSources() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 全部自定义源，按添加顺序 */
    public static List<Entry> all(Context context) {
        List<Entry> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY, "");
        if (TextUtils.isEmpty(raw)) {
            return out;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String url = item.optString("url", "");
                if (url.length() > 0) {
                    out.add(new Entry(url, item.optString("title", "")));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean isEmpty(Context context) {
        return all(context).isEmpty();
    }

    /**
     * 添加一条。地址会被规范化：允许用户只粘 {@code /sdcard/Download/a.m3u}
     * 这种本地路径，自动补成 {@code file://}。
     *
     * @return 添加后的错误提示；成功返回 null
     */
    public static String add(Context context, String rawUrl) {
        String url = normalize(rawUrl);
        if (url.length() == 0) {
            return "地址不能为空";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")
                && !url.startsWith("file://")) {
            return "只支持 http(s):// 或本地文件路径";
        }
        List<Entry> list = all(context);
        for (Entry entry : list) {
            if (entry.url.equals(url)) {
                return "这个地址已经添加过了";
            }
        }
        if (list.size() >= MAX) {
            return "最多添加 " + MAX + " 个源，先删掉几个";
        }
        try {
            // 注意不能直接 new JSONArray(存的值)：第一次添加时存的是空串，
            // 空串不是合法 JSON，会抛异常（踩过，表现是「添加后什么都没发生」）
            JSONArray array = existing(context);
            JSONObject item = new JSONObject();
            item.put("url", url);
            item.put("title", titleOf(url));
            array.put(item);
            prefs(context).edit().putString(KEY, array.toString()).apply();
            return null;
        } catch (Throwable th) {
            return "保存失败：" + th.getClass().getSimpleName();
        }
    }

    /** 按地址删除 */
    public static void remove(Context context, String url) {
        if (url == null) {
            return;
        }
        List<Entry> list = all(context);
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : list) {
                if (entry.url.equals(url)) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("url", entry.url);
                item.put("title", entry.title);
                array.put(item);
            }
        } catch (Throwable ignored) {
            return;
        }
        prefs(context).edit().putString(KEY, array.toString()).apply();
    }

    /**
     * 当前源列表的指纹。
     *
     * <p>缓存里记一份，读的时候比对：用户增删了源，旧目录里没有（或还留着）对应的台，
     * 必须重新合并一次。只比地址，不比顺序。
     */
    public static String stamp(Context context) {
        List<Entry> list = all(context);
        if (list.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Entry entry : list) {
            out.append(entry.url).append('\n');
        }
        return Integer.toHexString(out.toString().hashCode()) + "/" + list.size();
    }

    /** 读出已有的数组；没有或坏了就给一个空数组（不抛异常） */
    private static JSONArray existing(Context context) {
        String raw = prefs(context).getString(KEY, "");
        if (TextUtils.isEmpty(raw)) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (Throwable th) {
            return new JSONArray();
        }
    }

    private static String normalize(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.length() == 0) {
            return "";
        }
        // 用户可能从文件管理器里拷到的是 /sdcard/... 这种裸路径
        if (url.startsWith("/")) {
            return "file://" + url;
        }
        return url;
    }

    /** 显示名：取地址最后一段（去掉查询串） */
    private static String titleOf(String url) {
        String text = url;
        int query = text.indexOf('?');
        if (query > 0) {
            text = text.substring(0, query);
        }
        int slash = text.lastIndexOf('/');
        String name = slash >= 0 && slash < text.length() - 1 ? text.substring(slash + 1) : text;
        return name.length() == 0 ? url : name;
    }
}

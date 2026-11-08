package com.tvdesk.core.live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份解析好的直播目录：分组 → 频道。
 *
 * <p>分组顺序保留源里的出现顺序，不排序：央视/卫视/地方这种排法是源作者按用户习惯排的，
 * 按拼音重排反而把「最常看的那几个」冲到中间去。
 *
 * <p>频道在内部按名字归一化去重（见 {@link LiveChannel#key()}），
 * 同一个台的多条地址落在同一个对象里，播放时按顺序重试。
 */
public final class LiveCatalog {

    /** 分组名 → 频道。用 LinkedHashMap 保住源里的分组顺序 */
    public final Map<String, List<LiveChannel>> groups = new LinkedHashMap<>();
    /** EPG 地址（m3u 头部的 x-tvg-url），可能为空 */
    public String epgUrl = "";
    /** 这份目录什么时候拉的（毫秒），0 表示没记录 */
    public long updatedAt;
    /** 目录来自哪些源，给设置页显示用 */
    public String sources = "";

    private final Map<String, LiveChannel> index = new LinkedHashMap<>();

    /** 把一个频道并进目录；同名已有就合并地址 */
    public void add(LiveChannel channel) {
        if (channel == null || channel.name.length() == 0) {
            return;
        }
        String key = channel.key();
        LiveChannel exist = index.get(key);
        if (exist == null) {
            String group = channel.group.length() == 0 ? "其他" : channel.group;
            channel.group = group;
            index.put(key, channel);
            List<LiveChannel> list = groups.get(group);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(group, list);
            }
            list.add(channel);
            return;
        }
        for (String url : channel.urls) {
            if (!exist.urls.contains(url)) {
                exist.urls.add(url);
            }
        }
        if (exist.logo == null || exist.logo.length() == 0) {
            exist.logo = channel.logo;
        }
        if (exist.epgId == null || exist.epgId.length() == 0) {
            exist.epgId = channel.epgId;
        }
    }

    public int channelCount() {
        return index.size();
    }

    public boolean isEmpty() {
        return index.isEmpty();
    }

    /** 按分组名取；没有就返回空表 */
    public List<LiveChannel> group(String name) {
        List<LiveChannel> list = groups.get(name);
        return list == null ? new ArrayList<LiveChannel>() : list;
    }

    /** 分组名列表，顺序同源 */
    public List<String> groupNames() {
        return new ArrayList<>(groups.keySet());
    }

    /**
     * 去掉「更新时间」这类不是频道的占位分组。
     *
     * <p>源的生成脚本会往 m3u 里塞一条「更新时间」记录（地址是宣传片），
     * 它不是电视频道，留在列表里用户点进去只会看到一段广告。
     */
    public void dropPlaceholders() {
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, List<LiveChannel>> entry : groups.entrySet()) {
            String name = entry.getKey();
            if (isPlaceholderGroup(name)) {
                drop.add(name);
                continue;
            }
            List<LiveChannel> keep = new ArrayList<>(entry.getValue().size());
            for (LiveChannel channel : entry.getValue()) {
                if (!isPlaceholderChannel(channel.name)) {
                    keep.add(channel);
                } else {
                    index.remove(channel.key());
                }
            }
            entry.setValue(keep);
        }
        for (String name : drop) {
            List<LiveChannel> removed = groups.remove(name);
            if (removed != null) {
                for (LiveChannel channel : removed) {
                    index.remove(channel.key());
                }
            }
        }
        // 清完可能留下空分组（minSdk 19 不能用 Map.removeIf，走迭代器）
        java.util.Iterator<Map.Entry<String, List<LiveChannel>>> it = groups.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    private static boolean isPlaceholderGroup(String name) {
        return name.contains("更新时间") || name.contains("公告") || name.contains("说明");
    }

    private static boolean isPlaceholderChannel(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) {
            return true;
        }
        if (n.contains("更新时间") || n.contains("公告")) {
            return true;
        }
        // 「2026-11-08 06:26:46」这种纯时间戳
        return n.matches("\\d{4}-\\d{1,2}-\\d{1,2}.*");
    }
}

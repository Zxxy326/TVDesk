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
     * 排过序的分组名：央视 → 卫视 → 地方 → 其余 → 备用/测试垫底。
     *
     * <p>为什么要排：合并多份源之后，分组顺序取决于「哪份源先被并进来」。
     * 实测 suxuang 那份把「备用频道」排在最前，于是进直播第一个台就是死链，
     * 上下键换台也全在垃圾里跳。用户想看的永远是央视和卫视。
     *
     * <p>排序只调整展示和遍历顺序，不动分组内容。
     */
    public List<String> rankedGroupNames() {
        List<String> names = new ArrayList<>(groups.keySet());
        java.util.Collections.sort(names, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int ra = rank(a);
                int rb = rank(b);
                if (ra != rb) {
                    return ra - rb;
                }
                return 0;
            }
        });
        return names;
    }

    private static int rank(String group) {
        String name = group == null ? "" : group;
        // 英文分组（来自 iptv-org）排在中文分组之后：用户看的是央视/卫视，
        // 一进面板看到一排 News/General 会以为源乱了
        boolean ascii = true;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) > 127) {
                ascii = false;
                break;
            }
        }
        if (ascii && name.length() > 0) {
            return 80;
        }
        if (name.contains("备用") || name.contains("测试") || name.contains("其他")
                || name.contains("其它")) {
            return 90;
        }
        if (name.contains("央视") || name.equalsIgnoreCase("CCTV")) {
            return 0;
        }
        if (name.contains("卫视")) {
            return 10;
        }
        if (name.contains("地方") || name.contains("城市")) {
            return 20;
        }
        if (name.contains("影视") || name.contains("电影") || name.contains("电视剧")) {
            return 30;
        }
        if (name.contains("体育") || name.contains("赛事")) {
            return 35;
        }
        if (name.contains("少儿") || name.contains("儿童") || name.contains("动画")) {
            return 40;
        }
        if (name.contains("纪录") || name.contains("科教")) {
            return 45;
        }
        if (name.contains("音乐") || name.contains("戏曲")) {
            return 50;
        }
        return 60;
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
        if (name == null) {
            return true;
        }
        // iptv-org 那份清单里有一组 group-title="Undefined"，不是分类
        if ("undefined".equalsIgnoreCase(name.trim())) {
            return true;
        }
        // 「备用频道」这类是给主线路挂掉时用的备份，实测九成是死链或者回放，
        // 留在列表里只会让用户以为「这个台坏了」。整组丢掉。
        return name.contains("更新时间") || name.contains("公告") || name.contains("说明")
                || name.contains("备用") || name.contains("测试");
    }

    private static boolean isPlaceholderChannel(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) {
            return true;
        }
        // 「支持作者」是源作者给自己打的广告位（点进去是一段宣传片），不是电视频道。
        // 用户明确要求屏蔽，这里连同它的各种变体写法一起挡掉
        if (n.contains("更新时间") || n.contains("公告") || n.contains("支持作者")
                || n.contains("赞助") || n.contains("推广") || n.contains("免责")) {
            return true;
        }
        // 「2026-11-08 06:26:46」这种纯时间戳
        return n.matches("\\d{4}-\\d{1,2}-\\d{1,2}.*");
    }
}

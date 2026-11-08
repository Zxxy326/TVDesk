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
        // 源里的台名常带画质与状态标注：「CCTV-1 (720p)」「CCTV-1 HD (1080p)」
        // 「CCTV-6 [Geo-blocked]」。不清理的话同一个台会被当成好几个，
        // 列表里就是一串 CCTV-1 / CCTV-1 HD / CCTV-1 (720p)。
        // 先把广告农场的地址剔掉；剔完没有地址的台直接不要
        for (int i = channel.urls.size() - 1; i >= 0; i--) {
            if (isBadHost(channel.urls.get(i))) {
                channel.urls.remove(i);
            }
        }
        if (channel.urls.isEmpty()) {
            return;
        }
        String rawName = channel.name;
        boolean hd = looksHd(rawName);
        channel.rename(cleanName(rawName));
        if (channel.name.length() == 0) {
            return;
        }
        key = channel.key();

        LiveChannel exist = index.get(key);
        if (exist == null) {
            String group = channel.group.length() == 0 ? "其他" : channel.group;
            // 不同源的分组命名完全不统一：iptv-org 把 CCTV-1 归到 "General"，
            // vbskycn 归到「央视频道」，还有源用英文。统一按台名归一次，
            // 用户才能在「央视频道」里按 1、2、3 顺序看到央视。
            group = canonicalGroup(channel.name, group);
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
            if (exist.urls.contains(url)) {
                continue;
            }
            // 标着 HD/1080p 的那一条排到前面：合并后一个台可能有好几条地址，
            // 先试清晰的
            if (hd) {
                exist.urls.add(0, url);
            } else {
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
            if (isPlaceholderGroup(name) || isForeignGroup(name)) {
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

    /** 括号里出现这些词，说明是画质/状态标注，不属于台名 */
    private static final String[] TAG_WORDS = {
            "720", "1080", "2160", "576", "480", "4K", "8K", "GEO", "NOT24", "NOT 24", "SD", "HD"
    };

    /**
     * 去掉台名里的画质/状态标注。
     *
     * <p>手写而不是正则：要处理中英文四种括号，正则里字符类转义一层套一层，
     * 写错了还不容易看出来。这里按字符扫一遍，遇到括号就看里面有没有标注词，
     * 有就整段丢掉。
     */
    static String cleanName(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(name.length());
        int i = 0;
        while (i < name.length()) {
            char c = name.charAt(i);
            char end = 0;
            if (c == '(') {
                end = ')';
            } else if (c == '（') {
                end = '）';
            } else if (c == '[') {
                end = ']';
            } else if (c == '【') {
                end = '】';
            }
            if (end != 0) {
                int close = name.indexOf(end, i + 1);
                if (close > i && isTag(name.substring(i + 1, close))) {
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        String text = out.toString().replace('　', ' ').trim();
        while (text.indexOf("  ") >= 0) {
            text = text.replace("  ", " ");
        }
        // 行尾的「 HD」「 4K」也是标注
        String upper = text.toUpperCase();
        for (String suffix : new String[]{" HD", " 4K", " 8K"}) {
            if (upper.endsWith(suffix) && text.length() > suffix.length() + 1) {
                text = text.substring(0, text.length() - suffix.length()).trim();
                break;
            }
        }
        return text;
    }

    private static boolean isTag(String inner) {
        String upper = inner.toUpperCase().replace(" ", "");
        for (String word : TAG_WORDS) {
            if (upper.contains(word.replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }

    /** 这条记录是不是标着高清（决定合并后哪条地址排前面） */
    static boolean looksHd(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase();
        return upper.contains("1080") || upper.contains("2160") || upper.contains("4K")
                || upper.contains("8K") || upper.contains("HD");
    }

    /**
     * 按台名把分组归一到我们自己的三个大类。
     *
     * <p>不这么做的话，同一个央视在不同源里散落在「央视频道」「General」「News」里，
     * 面板上会分成好几撮，用户的「央视在前、按频道号排」就无从谈起。
     */
    private static String canonicalGroup(String name, String original) {
        String normalized = LiveChannel.normalize(name);
        if (cctvRank(name) < 900) {
            return "央视频道";
        }
        // CGTN 系列也算央视，但排在有编号的后面（cctvRank 给 900）
        if (normalized.startsWith("CGTN")) {
            return "央视频道";
        }
        if (normalized.contains("卫视")) {
            return "卫视频道";
        }
        return original;
    }

    /**
     * 每个分组内部按「用户熟悉的顺序」排一遍。
     *
     * <h3>为什么必须自己排</h3>
     * 源里的顺序是扫描出来的（谁先验证通过谁在前），央视会变成
     * CCTV13、CCTV5、CCTV1 这种乱序。用户找台靠的是「央视几套」，
     * 顺序一乱就只能一个个翻。
     *
     * <h3>规则</h3>
     * <ul>
     *   <li>央视：按频道号升序，CCTV5 在 CCTV5+ 之前（带 + 的算同一号的后一位）；</li>
     *   <li>卫视：湖南/浙江/江苏/东方/北京…这种常见顺序在前，其余按原名排在后面；</li>
     *   <li>其他分组：不动，保持源里的顺序（地方台本来没有公认顺序）。</li>
     * </ul>
     *
     * <p>{@code Collections.sort} 是稳定排序：同名的键保持原来的相对次序。
     */
    public void sortChannels() {
        for (Map.Entry<String, List<LiveChannel>> entry : groups.entrySet()) {
            String group = entry.getKey();
            final boolean cctv = group.contains("央视") || group.equalsIgnoreCase("CCTV");
            final boolean satellite = group.contains("卫视");
            if (!cctv && !satellite) {
                continue;
            }
            java.util.Collections.sort(entry.getValue(), new java.util.Comparator<LiveChannel>() {
                @Override
                public int compare(LiveChannel a, LiveChannel b) {
                    int ra = cctv ? cctvRank(a.name) : satelliteRank(a.name);
                    int rb = cctv ? cctvRank(b.name) : satelliteRank(b.name);
                    if (ra != rb) {
                        return ra - rb;
                    }
                    return 0;
                }
            });
        }
    }

    /**
     * 央视台的序号：CCTV1=10、CCTV2=20…CCTV5=50、CCTV5+=51。
     *
     * <p>「CCTV-1综合」「央视一套」这些写法都要认出来；认不出（比如 CCTV-4K）
     * 排到最后，但仍然排在一起。
     */
    private static int cctvRank(String name) {
        String key = LiveChannel.normalize(name);
        // 央视一套 / 央视13套
        java.util.regex.Matcher cn = java.util.regex.Pattern
                .compile("央视(\\d{1,2})套").matcher(key);
        if (cn.find()) {
            return number(cn.group(1)) * 10;
        }
        // CCTV-1 / CCTV1 / CCTV5+ / CCTV5PLUS
        java.util.regex.Matcher en = java.util.regex.Pattern
                .compile("CCTV-?(\\d{1,2})(\\+)?(K|PLUS)?").matcher(key);
        if (en.find()) {
            int base = number(en.group(1)) * 10;
            if (en.group(2) != null || "K".equals(en.group(3)) || "PLUS".equals(en.group(3))) {
                base += 1;
            }
            return base;
        }
        // 认不出号的（CCTV-4K、CCTV风云剧场…）排在数字台之后
        return 900;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return 99;
        }
    }

    /** 卫视的常用顺序；没列到的按 100 起排，保持源里的相对次序 */
    private static final String[] SATELLITE_ORDER = {
            "湖南", "浙江", "江苏", "东方", "上海", "北京", "安徽", "山东", "广东", "深圳",
            "天津", "四川", "湖北", "河南", "河北", "福建", "江西", "辽宁", "黑龙江", "吉林",
            "山西", "陕西", "甘肃", "宁夏", "青海", "云南", "贵州", "广西", "海南", "内蒙古",
            "新疆", "西藏", "重庆", "东南", "海峡", "三沙", "厦门", "延边", "兵团", "农林"
    };

    private static int satelliteRank(String name) {
        String key = LiveChannel.normalize(name);
        for (int i = 0; i < SATELLITE_ORDER.length; i++) {
            if (key.contains(SATELLITE_ORDER[i])) {
                return i * 10;
            }
        }
        return 1000;
    }

    /**
     * 已知的「假央视」主机。
     *
     * <p>这批海外 VPS 挂着央视的台名，实际放的是循环的卖药/推销广告（画面上直接打
     * 400 电话和二维码），很多公开源都在转它们的流。实测 74.91.26.218 还会 302
     * 跳到别的域名再返回 404——连"能播"都是假的。
     *
     * <p>拉黑的后果是这些台上没有地址、整条消失：宁可少一个台，也不要让用户点进去看广告。
     */
    private static final String[] BAD_HOSTS = {
            "74.91.26.218", "69.30.245.50", "204.12.221.218", "173.208.212.130",
            "38.75.136.137", "107.150.60.122", "63.141.230.178", "47.97.252.137"
    };

    /** 这条地址是不是来自已知的广告农场 */
    private static boolean isBadHost(String url) {
        if (url == null) {
            return true;
        }
        int start = url.indexOf("://");
        if (start < 0) {
            return false;
        }
        String rest = url.substring(start + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == ':' || c == '?') {
                end = i;
                break;
            }
        }
        String host = rest.substring(0, end);
        for (String bad : BAD_HOSTS) {
            if (bad.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /** 分组名是不是纯英文（iptv-org 的 News/General/Religious…） */
    private static boolean isForeignGroup(String name) {
        if (name == null || name.length() == 0) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) > 127) {
                return false;
            }
        }
        // 纯数字/符号的分组（比如「4K」）不算
        return name.matches(".*[A-Za-z].*");
    }

    /** 名称里有汉字吗 */
    private static boolean hasChinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x4E00 && text.charAt(i) <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    /**
     * 没有汉字但属于中国的对外频道：CCTV / CGTN 系列。
     *
     * <p>它们是央视办的英文频道，用户在「央视」里能看到，不该按「外文频道」删掉。
     */
    private static boolean isChineseBroadcaster(String name) {
        String key = LiveChannel.normalize(name);
        return key.startsWith("CCTV") || key.startsWith("CGTN") || key.startsWith("CHINAGLOBAL");
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

    /** 广告、带货、购物台：内容不是节目，是推销 */
    private static final String[] AD_WORDS = {
            "广告", "带货", "购物", "商城", "好物", "优惠", "促销", "秒杀", "抢购",
            "甩卖", "直购", "特卖", "福利", "下单", "直播间"
    };

    /**
     * 境外/敏感中文台：用户明确要求屏蔽。
     *
     * <p>这些台名在中文里看不出问题（都是四个字），只能按名单挡。
     */
    private static final String[] OVERSEAS_WORDS = {
            "新唐人", "大纪元", "希望之声", "美国之音", "自由亚洲", "阿波罗",
            "明慧", "神韵", "看中国", "博讯", "法轮"
    };

    private static boolean isPlaceholderChannel(String name) {
        if (name == null) {
            return true;
        }
        if (name.toUpperCase().contains("GEO-BLOCKED")) {
            return true;
        }
        for (String word : AD_WORDS) {
            if (name.contains(word)) {
                return true;
            }
        }
        for (String word : OVERSEAS_WORDS) {
            if (name.contains(word)) {
                return true;
            }
        }
        // 外文频道：整串没有一个汉字，且不是央视/CGTN 这类中国对外频道
        if (!hasChinese(name) && !isChineseBroadcaster(name)) {
            return true;
        }
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

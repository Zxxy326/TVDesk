package com.tvdesk.core.live;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播源解析。两种格式都吃：
 *
 * <ul>
 *   <li><b>M3U</b>（扩展 m3u）：{@code #EXTINF:-1 tvg-name="CCTV5" tvg-logo="…"
 *       group-title="央视频道", CCTV5} + 下一行的播放地址；</li>
 *   <li><b>TXT</b>（DIYP/TVBox 口径）：{@code 分组名,#genre#} 开头，之后每行
 *       {@code 频道名,地址}。</li>
 * </ul>
 *
 * <p>解析只做两件事：抽出字段、把同名频道合并进 {@link LiveCatalog}。
 * <b>不做可用性探测</b>——扫一遍几百个 m3u8 要几分钟，那属于订阅端的事；
 * 这里假设源作者已经筛过，播不动时由播放层换下一条地址。
 *
 * <p>几个容易踩的点：
 * <ul>
 *   <li>m3u 的频道名在逗号后面，但属性值里也可能有逗号（比如 tvg-name="A,B"），
 *       所以不能直接 {@code split(",")}，要跳过引号内的逗号；</li>
 *   <li>地址行可能是 {@code #EXTVLCOPT}、{@code #KODIPROP} 这类指令，不是地址；</li>
 *   <li>有的源一条频道挂好几行地址（多线路），这些都要收进同一个频道。</li>
 * </ul>
 */
public final class M3uParser {

    private static final Pattern ATTR = Pattern.compile("([a-zA-Z0-9-]+)=\"([^\"]*)\"");

    private M3uParser() {
    }

    /** 从一段文本解析出目录。{@code from} 用来标记来源，写进 {@link LiveCatalog#sources} */
    public static LiveCatalog parse(String text) {
        LiveCatalog catalog = new LiveCatalog();
        if (TextUtils.isEmpty(text)) {
            return catalog;
        }
        String[] lines = text.split("\\r?\\n");
        if (looksLikeM3u(text)) {
            parseM3u(lines, catalog);
        } else {
            parseTxt(lines, catalog);
        }
        catalog.dropPlaceholders();
        return catalog;
    }

    private static boolean looksLikeM3u(String text) {
        String head = text.length() > 512 ? text.substring(0, 512) : text;
        return head.indexOf("#EXTM3U") >= 0 || head.indexOf("#EXTINF") >= 0;
    }

    // ---------------------------------------------------------------- m3u

    private static void parseM3u(String[] lines, LiveCatalog catalog) {
        LiveChannel current = null;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("#EXTM3U")) {
                String tvg = media(line, "x-tvg-url");
                if (tvg.length() == 0) {
                    tvg = media(line, "url-tvg");
                }
                if (tvg.length() > 0 && catalog.epgUrl.length() == 0) {
                    catalog.epgUrl = tvg;
                }
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                current = extinf(line);
                continue;
            }
            if (line.startsWith("#")) {
                // #EXTVLCOPT / #KODIPROP / #EXTGRP 之类：只认 #EXTGRP 的分组
                if (current != null && line.startsWith("#EXTGRP:")) {
                    String group = line.substring("#EXTGRP:".length()).trim();
                    if (group.length() > 0 && current.group.length() == 0) {
                        current.group = group;
                    }
                }
                continue;
            }
            if (current != null) {
                // 一个 #EXTINF 后面可能跟着好几行地址（多线路），它们都属于同一个台，
                // 所以 current 不在第一行之后清空，等下一个 #EXTINF 覆盖它。
                // LiveCatalog.add 对同一个对象是幂等的（地址按 contains 去重）。
                current.urls.add(line);
                catalog.add(current);
            }
        }
    }

    /** 解一行 #EXTINF：#EXTINF:-1 tvg-name="x" …, 频道名 */
    private static LiveChannel extinf(String line) {
        String title = afterLastCommaOutsideQuotes(line);
        // 名字里常带前后空格和「[高清]」这类标记，先留原样，界面自己决定怎么显示
        title = title.trim();
        LiveChannel channel = new LiveChannel(title, "");
        Matcher matcher = ATTR.matcher(line);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase();
            String value = matcher.group(2).trim();
            if ("tvg-logo".equals(key)) {
                channel.logo = value;
            } else if ("group-title".equals(key)) {
                channel.group = value;
            } else if ("tvg-id".equals(key)) {
                channel.epgId = value;
            } else if ("tvg-name".equals(key) && channel.name.length() == 0) {
                channel.rename(value);
            }
        }
        if (channel.name.length() == 0) {
            channel.rename(channel.epgId);
        }
        return channel;
    }

    /** 取「引号之外的最后一个逗号」后面的部分 */
    private static String afterLastCommaOutsideQuotes(String line) {
        boolean inQuote = false;
        int cut = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                cut = i;
            }
        }
        return cut < 0 ? "" : line.substring(cut + 1);
    }

    /** 取 #EXTM3U 行里某个 key="value" 的值 */
    private static String media(String line, String key) {
        Matcher matcher = Pattern.compile(key + "=\"([^\"]*)\"").matcher(line);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    // ---------------------------------------------------------------- txt

    /**
     * TXT 口径：{@code 分组名,#genre#} 之后每行 {@code 频道名,地址}。
     *
     * <p>有源用 {@code 频道名,地址#地址#地址} 挂多线路，也用逗号分隔。两种都认：
     * 第一个逗号前是名字，后面的每一段都当地址收集。
     */
    private static void parseTxt(String[] lines, LiveCatalog catalog) {
        String group = "其他";
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            if (line.contains("#genre#")) {
                String name = line.split(",")[0].trim();
                if (name.length() > 0) {
                    group = name;
                }
                continue;
            }
            int comma = line.indexOf(',');
            if (comma <= 0) {
                continue;
            }
            String name = line.substring(0, comma).trim();
            String rest = line.substring(comma + 1).trim();
            if (name.length() == 0 || rest.length() == 0) {
                continue;
            }
            LiveChannel channel = new LiveChannel(name, group);
            // 多线路：逗号分不开（地址里本来就有逗号参数），所以按空格/# 再切一刀
            List<String> urls = splitUrls(rest);
            if (urls.isEmpty()) {
                continue;
            }
            channel.urls.addAll(urls);
            catalog.add(channel);
        }
    }

    /** 把「地址#地址」或「地址 地址」切成多条；只收看起来像地址的段 */
    private static List<String> splitUrls(String rest) {
        List<String> out = new ArrayList<>();
        String[] parts = rest.split("[#\\s]+");
        for (String part : parts) {
            String url = part.trim();
            if (url.length() == 0) {
                continue;
            }
            if (url.startsWith("http") || url.startsWith("rtmp") || url.startsWith("rtsp")
                    || url.startsWith("udp") || url.startsWith("rtp")) {
                out.add(url);
            }
        }
        return out;
    }
}

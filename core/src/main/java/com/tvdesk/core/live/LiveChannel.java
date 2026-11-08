package com.tvdesk.core.live;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个直播频道。
 *
 * <p>同一个频道名在多个源里出现时合并成一个对象，把各自的播放地址收进 {@link #urls}：
 * 主源挂了自动换下一条，不用回列表重新选。顺序就是「先试谁」的顺序——
 * 合并时按源优先级排（IPv4 源在前，IPv6 源在后，兜底源最后）。
 *
 * <p>台标地址可能为空（有些源不带 tvg-logo），界面自己要能退化成「名字首字」。
 */
public final class LiveChannel {

    /**
     * 频道名，已去掉首尾空白。合并和去重都按它（见 {@link #key()}）。
     *
     * <p>不是 final：m3u 里名字写在逗号后面，但个别源整行只有 tvg-name，
     * 解析时要先占位再补名字。赋值一律走构造器或 {@link #rename(String)}，保证去掉空白。
     */
    public String name;
    /** 分组名，来自 {@code group-title}（m3u）或 {@code #genre#}（txt） */
    public String group;
    /** 台标地址，可能为空 */
    public String logo;
    /** EPG id，可能为空。目前只存不用，留给以后的节目单 */
    public String epgId;
    /** 播放地址，按优先级排；至少一条 */
    public final List<String> urls = new ArrayList<>();

    public LiveChannel(String name, String group) {
        this.name = name == null ? "" : name.trim();
        this.group = group == null ? "" : group.trim();
    }

    /**
     * 合并用的键：名字归一化（去空格、全角转半角、大写化）。
     *
     * <p>「CCTV-1」和「CCTV1」、「CCTV 5」和「CCTV5」在不同源里写法不一样，
     * 直接按原字符串比会当成两个频道，界面上就是同一个台出现两次。
     */
    public String key() {
        return normalize(name);
    }

    /** 改名（去空白）。解析器补 tvg-name 用 */
    public void rename(String value) {
        this.name = value == null ? "" : value.trim();
    }

    /** 是否可以拿去播放：地址不空、且不是那种明显的占位串 */
    public boolean playable() {
        for (String url : urls) {
            if (!TextUtils.isEmpty(url) && (url.startsWith("http") || url.startsWith("rtmp")
                    || url.startsWith("rtsp") || url.startsWith("udp") || url.startsWith("rtp"))) {
                return true;
            }
        }
        return false;
    }

    /** 归一化：NFKC 折全角、去空白、去中间的点和横线差异之外的常见分隔符 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '　' || c == '·' || c == '•') {
                continue;
            }
            // 全角字母数字转半角（ＣＣＴＶ－５ 这种写法在源里并不少见）
            if (c >= '！' && c <= '～') {
                c = (char) (c - 0xFEE0);
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return name + "(" + urls.size() + ")";
    }
}

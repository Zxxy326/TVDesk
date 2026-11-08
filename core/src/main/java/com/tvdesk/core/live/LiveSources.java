package com.tvdesk.core.live;

/**
 * 内置直播源清单。
 *
 * <p>三份源，分工不同：
 * <ol>
 *   <li><b>vbskycn IPv4</b>（主）：{@code live.zbds.top} 每 6 小时扫一遍，
 *       带台标和 EPG 头，频道以央视/卫视/地方为主，是日常看的那一份；</li>
 *   <li><b>vbskycn IPv6</b>（补充）：同一项目另一份清单，只收 IPv6 地址。
 *       这一份经常是空的（作者扫不到就只留一条更新时间），所以取不到不算错；</li>
 *   <li><b>Guovin iptv-api</b>（兜底）：主源整个取不到、或者取到了但频道数少得离谱时用。</li>
 * </ol>
 *
 * <p>每份源都配了镜像：GitHub 域名在国内时通时不通，raw 取不到就换加速站，
 * 全部失败才判定这份源不可用。
 */
public final class LiveSources {

    /** 一个源：主地址 + 若干镜像，按顺序试 */
    public static final class Source {
        public final String id;
        public final String title;
        /** 是否算「主源」：主源都失败或者频道太少，才启用兜底源 */
        public final boolean primary;
        public final String[] urls;

        Source(String id, String title, boolean primary, String... urls) {
            this.id = id;
            this.title = title;
            this.primary = primary;
            this.urls = urls;
        }
    }

    private LiveSources() {
    }

    public static final Source IPV4 = new Source("vbskycn4", "vbskycn · IPv4", true,
            "https://live.zbds.top/tv/iptv4.m3u",
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u",
            "https://gh-proxy.com/raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv4.m3u");

    public static final Source IPV6 = new Source("vbskycn6", "vbskycn · IPv6", true,
            "https://live.zbds.top/tv/iptv6.m3u",
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv6.m3u",
            "https://gh-proxy.com/raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv6.m3u");

    public static final Source FALLBACK = new Source("guovin", "Guovin iptv-api", false,
            "https://guovin.github.io/iptv-api/result.m3u",
            "https://raw.githubusercontent.com/Guovin/iptv-api/gd1/output/result.m3u");

    public static Source[] all() {
        return new Source[]{IPV4, IPV6, FALLBACK};
    }

    /**
     * 主源加起来少于这个数，就认为「没取到有效内容」，启用兜底。
     *
     * <p>正常一份有上千个频道；主源被墙、被改格式、返回 HTML 错误页时，
     * 解析出来是 0 或个位数——用 50 当阈值足够区分「正常」和「坏了」。
     */
    public static final int MIN_CHANNELS = 50;
}

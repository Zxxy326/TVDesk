package com.tvdesk.core.live;

/**
 * 内置直播源清单。
 *
 * <h3>按清晰度排，不按数量排</h3>
 * 央视/卫视在公网 IPv4 那批源里几乎都是几百 K 的低码率转码，画面糊成这样，
 * 台再多也没意义。所以清单按<b>画质</b>分层：
 *
 * <ol>
 *   <li><b>fanmingming IPv6</b>：运营商 IPv6 单播（{@code 2409:...}），
 *       实测同一路 CCTV 是实打实的 1080p；</li>
 *   <li><b>YanG-1989「咪咕」</b>：移动咪咕的 4K/高清源，含体育赛事；</li>
 *   <li><b>vbskycn IPv6</b>：同项目的 IPv6 清单（当前上游常为空，取不到不算错）；</li>
 *   <li><b>vbskycn IPv4</b>：台最全（四百多），但画质普遍低，作为「能看就行」的兜底；</li>
 *   <li><b>Guovin iptv-api</b>：全挂了才用。</li>
 * </ol>
 *
 * <p>合并同名台时，<b>先出现的地址优先</b>（见 {@link LiveCatalog#add}），
 * 所以这个顺序直接决定播放时先试哪一路——同一个央视一台，优先后面的高清地址，
 * 播不动才回落到 IPv4 低码率那一路。
 *
 * <p>IPv6 那几份只在<b>本机 IPv6 真的通</b>时才排到前面（见 {@code Ipv6.available()}）：
 * 有地址但路不通的机器上先试 IPv6 等于每个台白等一次超时。
 */
public final class LiveSources {

    /** 一个源：主地址 + 若干镜像，按顺序试 */
    public static final class Source {
        public final String id;
        public final String title;
        /** 是否算「主源」：主源都失败或者频道太少，才启用兜底源 */
        public final boolean primary;
        /** 是否 IPv6 专用（决定要不要在本机没有 IPv6 时跳过） */
        public final boolean ipv6Only;
        /**
         * 合并前要不要先抽检两条地址。
         *
         * <p>只对「很可能整份播不动」的源开：跨运营商（移动源在电信线路上）、
         * 需要白名单（咪咕）。通不过就整份丢弃，否则它的台名会和公网源重名，
         * 变成每个台的「第 1 条地址」，每个台都先白试一次失败。
         * 其余源默认信任（多两次探测就把首次同步拖长了）。
         */
        public final boolean verify;
        public final String[] urls;

        Source(String id, String title, boolean primary, boolean ipv6Only, boolean verify,
               String... urls) {
            this.id = id;
            this.title = title;
            this.primary = primary;
            this.ipv6Only = ipv6Only;
            this.verify = verify;
            this.urls = urls;
        }
    }

    private LiveSources() {
    }

    /** 高清：运营商 IPv6 单播，画面最接近原画 */
    public static final Source FM_IPV6 = new Source("fangming6", "FanMingMing · IPv6 高清", true, true, true,
            "https://raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u",
            "https://cdn.jsdelivr.net/gh/fanmingming/live@main/tv/m3u/ipv6.m3u",
            "https://gh-proxy.com/raw.githubusercontent.com/fanmingming/live/main/tv/m3u/ipv6.m3u");

    /** 高清：移动咪咕，含 4K 体育；不在移动网络时多半播不动，但地址会作为候选保留 */
    public static final Source YANG = new Source("yang1989", "YanG-1989 · 咪咕高清", true, false, true,
            "https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u",
            "https://cdn.jsdelivr.net/gh/YanG-1989/m3u@main/Gather.m3u");

    /** vbskycn IPv6（同项目另一份清单，上游经常是空的） */
    public static final Source IPV6 = new Source("vbskycn6", "vbskycn · IPv6", true, true, false,
            "https://live.zbds.top/tv/iptv6.m3u",
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv6.m3u",
            "https://gh-proxy.com/raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv6.m3u");

    /** 台最全、画质一般：兜住「高清源里没有的地方台」 */
    public static final Source IPV4 = new Source("vbskycn4", "vbskycn · IPv4 全量", true, false, false,
            "https://live.zbds.top/tv/iptv4.m3u",
            "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u",
            "https://gh-proxy.com/raw.githubusercontent.com/vbskycn/iptv/refs/heads/master/tv/iptv4.m3u");

    /** 台最多、且带 130 条 IPv6 地址（社区每日扫描维护） */
    public static final Source SUXUANG6 = new Source("suxuang6", "suxuang · IPv6 综合", true, false, true,
            "https://raw.githubusercontent.com/suxuang/myIPTV/main/ipv6.m3u",
            "https://cdn.jsdelivr.net/gh/suxuang/myIPTV@main/ipv6.m3u");

    /** 少量但全是 4K/高清（北京卫视4K、东方卫视4K 这类） */
    public static final Source SUXUANG4K = new Source("suxuang4k", "suxuang · 4K 精选", true, false, true,
            "https://raw.githubusercontent.com/suxuang/myIPTV/main/ipv4.m3u",
            "https://cdn.jsdelivr.net/gh/suxuang/myIPTV@main/ipv4.m3u");

    /** 国际版国内台：一半标着 1080p，画质比公网转发源稳定 */
    public static final Source IPTV_ORG = new Source("iptvorg", "iptv-org · CN", true, false, false,
            "https://iptv-org.github.io/iptv/countries/cn.m3u");

    /** 最后兜底 */
    public static final Source FALLBACK = new Source("guovin", "Guovin iptv-api", true, false, false,
            "https://guovin.github.io/iptv-api/result.m3u",
            "https://raw.githubusercontent.com/Guovin/iptv-api/gd1/output/result.m3u");

    /**
     * 按当前网络能力给出抓取顺序。
     *
     * @param ipv6Available 本机 IPv6 是否真的通（{@code Ipv6.available()}）
     */
    public static Source[] ordered(boolean ipv6Available) {
        // 顺序 = 合并时地址的优先级。
        //
        // 现状（2026-11 实测）：公开的「高清」清单几乎都是地区内网源——fanmingming
        // 是北京移动 IPv6、suxuang 是广东电信、YanG 是移动咪咕，跨运营商/跨省
        // 整份 502。把它们并进来只会让每个台先试一条死链，反而更卡。
        // 所以默认只用这两份真正通得过的：vbskycn（台全、画质一般）、
        // iptv-org（少数 1080p，国内部分地址 geo 失效），兜底 Guovin。
        // 地区源留在类里，等有对应的宽带账号/组播源再接回来。
        return new Source[]{IPV4, FALLBACK, IPTV_ORG};
    }

    /**
     * 主源加起来少于这个数，就认为「没取到有效内容」，启用兜底。
     *
     * <p>正常一份有几百上千个频道；主源被墙、被改格式、返回 HTML 错误页时，
     * 解析出来是 0 或个位数。
     */
    public static final int MIN_CHANNELS = 50;
}

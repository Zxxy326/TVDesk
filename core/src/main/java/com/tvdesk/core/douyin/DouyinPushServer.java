package com.tvdesk.core.douyin;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;

import fi.iki.elonen.NanoHTTPD;

/**
 * 本机局域网 cookie 推送服务：手机或电脑浏览器打开 {@code http://<电视IP>:9979/}，
 * 把浏览器里的抖音 cookie 粘进去提交，电视这边当场收下。
 *
 * <h3>为什么要有它</h3>
 * 抖音没有给第三方用的扫码登录接口——网页端只有手机号 / 扫码 / 第三方三种，全都走
 * {@code passport.douyin.com} 的滑块与风控，电视里既没有 WebView 登录态也过不了那套验证。
 * 所以「登录抖音」在这台设备上只能等价于「把你电脑上已有的 cookie 搬过来」。
 * 剩下的问题只是怎么搬：遥控器上的输入法逐字敲一段两三 KB 的 cookie 是不现实的，
 * 于是让电视自己起一个只有局域网能访问的表单页，用扫码打开，键盘在手机上敲。
 *
 * <h3>为什么端口写死 9979</h3>
 * core 里 TVBox 的 {@code RemoteServer} 用的是可配置端口（默认 9978 一族），
 * 撞上了两边都起不来。这里换一个明显不同的固定端口，并且只在弹窗打开期间存活，
 * 关掉弹窗就 {@code stop()}，不常驻后台。
 *
 * <h3>安全边界</h3>
 * 只做三件事：回两张静态表单（{@code /} 收 cookie、{@code /search} 收关键词）、
 * 把收到的文本交给回调。
 * 不读本地文件、不执行任何传进来的内容、也不回显 cookie（提交成功只说「收到了」）；
 * 关键词会回显，但必须过 {@link #escape}——服务没有鉴权，能连上的人就能提交。
 * 监听在 {@code 0.0.0.0} 是必要的——局域网里别的设备要能连进来；
 * 这个服务没有任何鉴权，所以它只在用户主动打开弹窗时开着。
 */
public final class DouyinPushServer extends NanoHTTPD {

    /** cookie 表单页路径 */
    public static final String PATH_FORM = "/";

    /** 搜索关键词表单页路径。和 cookie 表单共用一个端口，省掉「两个弹窗抢端口」这件事 */
    public static final String PATH_SEARCH = "/search";

    /** 页面全是中文，charset 必须写进 Content-Type，否则手机浏览器可能按 latin-1 猜 */
    private static final String HTML = "text/html; charset=utf-8";

    private final Listener listener;

    public interface Listener {
        /**
         * 收到了 cookie。
         *
         * <p>在 NanoHTTPD 的工作线程上回调，实现方自己要切回主线程。
         *
         * @param raw 形如 {@code a=1; b=2} 的原始串，已去掉首尾空白
         */
        void onCookie(String raw);

        /**
         * 收到了搜索关键词。同样在工作线程上回调。
         *
         * <p>只有开着搜索页的服务会收到；cookie 弹窗那边实现成空操作即可。
         */
        void onQuery(String keyword);
    }

    public DouyinPushServer(int port, Listener listener) {
        // 不指定地址：默认绑 0.0.0.0，同网段的手机才连得上
        super(port);
        this.listener = listener;
    }

    /**
     * 电视在局域网里的 IPv4。
     *
     * <p>不走 core 里那个 {@code LocalIPAddress}：它先问 WifiManager，
     * Android 12 之后那条路要精确位置权限，而盒子多数根本没有 WifiManager 可用。
     * 这里直接遍历网卡，只认非回环的 IPv4。
     *
     * @return 找不到时返回 {@code null}，调用方据此提示「请手动输入地址」
     */
    public static String localIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        String host = address.getHostAddress();
                        if (host != null && !host.contains("%")) {
                            return host;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 拿不到网卡就只能退回手动输入，不影响本服务的可用性
        }
        return null;
    }

    /** 端口是否可用（起服务前先试一次，端口被占要能告诉用户） */
    public static boolean portFree(int port) {
        java.net.ServerSocket socket = null;
        try {
            socket = new java.net.ServerSocket();
            socket.bind(new java.net.InetSocketAddress(port));
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 从 {@code base} 端口往上挨个试，起第一个能占住的。
     *
     * <p>两个弹窗（导 cookie、搜关键词）都要在局域网里挂一个表单，而 9979 只有一个。
     * 谁先开谁占住它，后开的往上让一位——所以两边都必须走这里，而不是各写一套探测循环，
     * 否则两边会以为拿到了同一个端口，二维码指向的是对面那个表单。
     *
     * @param probes 最多往上试几位
     * @throws IOException 一位都没试成；异常信息就是最后一次失败的原因，直接显示给用户即可
     */
    public static DouyinPushServer startFrom(int base, int probes, Listener listener)
            throws java.io.IOException {
        String last = null;
        for (int i = 0; i < probes; i++) {
            int candidate = base + i;
            if (!portFree(candidate)) {
                continue;
            }
            try {
                // daemon=true：万一弹窗没走到 dismiss，也不会因为这条线程卡住进程退出
                DouyinPushServer server = new DouyinPushServer(candidate, listener);
                server.start(1000, true);
                return server;
            } catch (Exception e) {
                last = e.getMessage();
            }
        }
        throw new java.io.IOException(last == null ? "端口都被占了" : last);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (session.getMethod() == Method.GET && PATH_FORM.equals(uri)) {
            return newFixedLengthResponse(Response.Status.OK, HTML, FORM_HTML);
        }
        if (session.getMethod() == Method.POST && PATH_FORM.equals(uri)) {
            // 必须先 parseBody：NanoHTTPD 的 getParameters() 只有 URL 查询串，
            // 表单正文要 parseBody 之后才出现在 getParms() 里（少这一步就是永远收到空）
            String cookie = null;
            try {
                session.parseBody(new HashMap<String, String>());
                cookie = session.getParms().get("cookie");
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.OK, HTML,
                        page("没读到你提交的东西", "内容太长或格式不对，重新粘一次试试："
                                + escape(e.getMessage())));
            }
            if (cookie == null || cookie.trim().length() == 0) {
                return newFixedLengthResponse(Response.Status.OK, HTML,
                        page("没收到内容", "粘贴框是空的，把浏览器里的 cookie 整段粘进来再提交。"));
            }
            if (listener != null) {
                listener.onCookie(sanitize(cookie));
            }
            return newFixedLengthResponse(Response.Status.OK, HTML,
                    page("已发送到电视", "回到电视上就能刷出登录后的内容了。这个页面可以关掉。"));
        }
        if (session.getMethod() == Method.GET && PATH_SEARCH.equals(uri)) {
            return newFixedLengthResponse(Response.Status.OK, HTML, SEARCH_HTML);
        }
        if (session.getMethod() == Method.POST && PATH_SEARCH.equals(uri)) {
            String keyword = null;
            try {
                session.parseBody(new HashMap<String, String>());
                keyword = session.getParms().get("q");
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.OK, HTML,
                        page("没读到你提交的东西", "内容太长或格式不对，重新输入一次试试："
                                + escape(e.getMessage())));
            }
            String trimmed = keyword == null ? "" : keyword.trim();
            if (trimmed.length() == 0) {
                return newFixedLengthResponse(Response.Status.OK, HTML,
                        page("没收到关键词", "输入框是空的。回到电视上按「搜索」再开一次这个页面。"));
            }
            if (trimmed.length() > 60) {
                // 抖音的搜索接口对超长串直接返回空结果，与其在电视上显示「没有结果」
                // 不如在这里就说清是哪一步不对
                trimmed = trimmed.substring(0, 60);
            }
            if (listener != null) {
                listener.onQuery(trimmed);
            }
            return newFixedLengthResponse(Response.Status.OK, HTML,
                    page("已发送到电视", "「" + escape(trimmed) + "」已经发出去了，回到电视上看结果。"
                            + "这个页面现在可以关掉了。"));
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404");
    }

    /**
     * 把粘进来的串整理成 {@code k=v; k=v}。
     *
     * <p>三种真实来源都得认，否则用户会在第一步就卡住：
     *
     * <ul>
     *   <li>Cookie-Editor 一类插件导出的 <b>JSON 数组</b>（{@code [{"name":"UIFID","value":"…"}]}）。
     *       {@code UIFID} 和 {@code sessionid} 是 {@code HttpOnly} 的，脚本读不到，
     *       插件是不逐条手抄又能拿到它们的唯一办法，所以它排在第一位；</li>
     *   <li>devtools 的 Application → Cookies 里逐行复制（一行一条，没有分号）；</li>
     *   <li>{@code document.cookie} 那一行（能拿到 ttwid 之类，登录项拿不到）。</li>
     * </ul>
     *
     * <p>后两种按文本切：只留含 {@code =} 的段，顺带丢掉 {@code Name}/{@code Value} 这类表头行。
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            String fromJson = fromCookieJson(trimmed);
            if (fromJson != null) {
                return fromJson;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String piece : trimmed.split("[;\\r\\n]+")) {
            String line = piece.trim();
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(line, 0, eq).append('=').append(line.substring(eq + 1).trim());
        }
        return sb.toString();
    }

    /**
     * 插件导出的 JSON 数组 → {@code k=v} 串。
     *
     * @return 一个条目都没解析出来时返回 {@code null}，让调用方退回按文本切
     */
    private static String fromCookieJson(String json) {
        try {
            org.json.JSONArray array = new org.json.JSONArray(json);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject one = array.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                String name = one.optString("name", "");
                String value = one.optString("value", "");
                if (name.length() == 0 || value.length() == 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(name).append('=').append(value);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable ignored) {
            // 粘进来的其实不是 JSON，按普通文本再试一次
            return null;
        }
    }

    private static String page(String title, String note) {
        return "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title></head><body style=\"" + BODY_STYLE + "\">"
                + "<h1>" + title + "</h1><p>" + note + "</p></body></html>";
    }

    /**
     * 回显到网页上之前必须过一遍。
     *
     * <p>成功页会把用户输入的关键词原样打回去，不转义的话输入
     * {@code <img src=x onerror=...>} 就能在这台机子的浏览器里执行脚本——
     * 服务本身没有鉴权，能连上的人本来就能提交，所以这一道不能省。
     */
    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final String BODY_STYLE =
            "background:#111214;color:#f2f3f5;font:16px/1.6 -apple-system,system-ui,sans-serif;"
                    + "margin:0;padding:20px 16px 40px";

    /** 两页共用的样式。深色是因为这两张页都会被人用手机在暗环境里打开 */
    private static final String CSS =
            "body{margin:0;padding:20px 16px 48px;background:#111214;color:#f2f3f5;"
                    + "font:16px/1.6 -apple-system,system-ui,sans-serif}\n"
                    + "h1{font-size:20px;margin:0 0 6px}\n"
                    + "p{margin:0 0 14px;color:#9aa0a6;font-size:14px}\n"
                    + "ol{margin:0 0 16px;padding-left:20px;color:#9aa0a6;font-size:13px}\n"
                    + "li{margin:4px 0}\n"
                    + "code{background:#1f2124;padding:2px 6px;border-radius:4px;color:#e8eaed}\n"
                    + "textarea{width:100%;box-sizing:border-box;height:180px;background:#1f2124;"
                    + "color:#f2f3f5;border:1px solid #33363b;border-radius:10px;padding:12px;"
                    + "font:13px/1.5 ui-monospace,Menlo,monospace;resize:vertical}\n"
                    + "button{width:100%;margin-top:14px;padding:14px;border:0;border-radius:10px;"
                    + "background:#fff;color:#111214;font-size:17px;font-weight:700}\n";

    /**
     * 推送页。
     *
     * <p>写死的静态串：里面不回显任何用户输入，也不需要 JavaScript，
     * 表单直接用 POST 提交，兼容性最好（电视弹窗那边只要等回调）。
     */
    private static final String FORM_HTML = "<!doctype html>\n"
            + "<html lang=\"zh\"><head><meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>给电视发送抖音 cookie</title>\n"
            + "<style>\n" + CSS + "</style></head><body>\n"
            + "<h1>给电视发送抖音 cookie</h1>\n"
            + "<p>电视不能自己登抖音，所以把你浏览器里已经登好的那段 cookie 送过去。</p>\n"
            + "<ol>\n"
            + "<li>在这个浏览器里打开并登录 <code>douyin.com</code></li>\n"
            + "<li>用 <code>Cookie-Editor</code> 这类插件导出 douyin 的 cookie（JSON 或文本都行）"
            + "；没装插件就 F12 → <code>Application → Cookies</code>，把整列抄下来</li>\n"
            + "<li>粘到下面，提交</li>\n"
            + "</ol>\n"
            + "<p>关键项 <code>UIFID</code> / <code>sessionid</code> 是 HttpOnly 的，"
            + "控制台里的 <code>document.cookie</code> <b>取不到它们</b>——"
            + "只粘那一行只能算半登录，搜索和点赞还是用不了。</p>\n"
            + "<form method=\"post\" action=\"/\">\n"
            + "<textarea name=\"cookie\" placeholder=\"UIFID=…; sessionid=…; ttwid=…\" "
            + "autocapitalize=\"off\" autocomplete=\"off\" spellcheck=\"false\"></textarea>\n"
            + "<button type=\"submit\">发送到电视</button>\n"
            + "</form>\n"
            + "</body></html>";

    /**
     * 搜索页。和 cookie 页同一套样式、同一个服务，只是表单换成一行输入框。
     *
     * <p>它存在的理由和 cookie 页一样：电视遥控器上打中文要逐字翻候选词，
     * 搜一个词比登一次账号还劝退。
     */
    private static final String SEARCH_HTML = "<!doctype html>\n"
            + "<html lang=\"zh\"><head><meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>给电视搜索抖音</title>\n"
            + "<style>\n" + CSS
            + "input{width:100%;box-sizing:border-box;height:52px;background:#1f2124;"
            + "color:#f2f3f5;border:1px solid #33363b;border-radius:10px;padding:0 14px;"
            + "font:16px/1.5 system-ui}\n"
            + "</style></head><body>\n"
            + "<h1>给电视搜索抖音</h1>\n"
            + "<p>遥控器上打中文太慢了，关键词在这台手机上敲，提交完电视那边直接出结果。</p>\n"
            + "<form method=\"post\" action=\"" + PATH_SEARCH + "\">\n"
            + "<input name=\"q\" type=\"search\" placeholder=\"想看的关键词\" maxlength=\"60\" "
            + "autocomplete=\"off\" autocapitalize=\"off\">\n"
            + "<button type=\"submit\">发送到电视</button>\n"
            + "</form>\n"
            + "<p>在电视上按「返回」退出搜索结果，回到推荐流。电视收到这个词就会关掉这个页面，"
            + "要再搜一条得回电视上重按一次「搜索」。</p>\n"
            + "</body></html>";

    /** 便于日志里给出可点的地址 */
    public static String formUrl(int port) {
        String ip = localIpv4();
        return ip == null ? null : String.format(Locale.US, "http://%s:%d/", ip, port);
    }

    /** 搜索页地址（二维码的内容就是它） */
    public static String searchUrl(int port) {
        String ip = localIpv4();
        return ip == null ? null : String.format(Locale.US, "http://%s:%d%s", ip, port, PATH_SEARCH);
    }
}

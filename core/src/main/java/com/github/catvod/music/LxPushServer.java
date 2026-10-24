package com.github.catvod.music;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 音源脚本的局域网导入页：手机扫码打开 {@code http://<电视IP>:9987/}，
 * 选一个 LX 音源 .js 上传，电视这边直接落进 {@link LxManager#dir(Context)}。
 *
 * <h3>为什么需要它</h3>
 * 原来导入音源只有一条路：把 .js push 到 {@code Android/data/.../lxsource}，
 * 或者用 U 盘 + 文件管理器拷进去。电视上没有「打开文件」这种交互，
 * 普通用户根本走不到那一步。二维码把键盘和文件选择留给手机，电视只负责收。
 *
 * <h3>安全边界</h3>
 * 只收一个文件字段，只认 .js，大小上限 6MB，文件名会清洗；页面只在用户打开弹窗时开着，
 * 关弹窗即停。没有鉴权，所以不能常驻。
 */
public final class LxPushServer extends NanoHTTPD {

    public static final String PATH_FORM = "/";
    public static final String PATH_UPLOAD = "/upload";

    private static final String HTML = "text/html; charset=utf-8";
    private static final long MAX_BYTES = 6L * 1024 * 1024;
    private static final String TAG = "LxPushServer";

    private final Context app;
    private final Listener listener;

    public interface Listener {
        /** 文件已经落盘。在工作线程上回调，实现方自己切主线程 */
        void onImported(File file, String name);
    }

    public LxPushServer(int port, Context context, Listener listener) {
        // 默认绑 0.0.0.0，同网段的手机才连得上
        super(port);
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * 从 {@code base} 往上找第一个能占住的端口。
     *
     * <p>和抖音那个推送服务一样：撞上 TVBox 的 RemoteServer 或别的弹窗时让位，
     * 而不是两边都起不来。
     */
    public static LxPushServer startFrom(int base, int probes, Context context, Listener listener)
            throws IOException {
        String last = null;
        for (int i = 0; i < probes; i++) {
            int candidate = base + i;
            if (!portFree(candidate)) {
                continue;
            }
            try {
                LxPushServer server = new LxPushServer(candidate, context, listener);
                server.start(1000, true);
                return server;
            } catch (Exception e) {
                last = e.getMessage();
            }
        }
        throw new IOException(last == null ? "端口都被占了" : last);
    }

    private static boolean portFree(int port) {
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

    /** 电视在局域网里的 IPv4；找不到返回 null，调用方据此提示手动输入 */
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
        }
        return null;
    }

    public static String formUrl(int port) {
        String ip = localIpv4();
        return ip == null ? null : String.format(Locale.US, "http://%s:%d/", ip, port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (session.getMethod() == Method.GET && PATH_FORM.equals(uri)) {
            return newFixedLengthResponse(Response.Status.OK, HTML, formPage(null));
        }
        if (session.getMethod() == Method.POST && PATH_UPLOAD.equals(uri)) {
            return receive(session);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404");
    }

    private Response receive(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        try {
            // 表单正文要 parseBody 之后才进 files/getParms，少这一步永远是空
            session.parseBody(files);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.OK, HTML,
                    resultPage("没读到你上传的文件", "内容太大或格式不对：" + escape(String.valueOf(e)), false));
        }
        String tempPath = files.get("file");
        File temp = TextUtils.isEmpty(tempPath) ? null : new File(tempPath);
        if (temp == null || !temp.isFile() || temp.length() == 0) {
            return newFixedLengthResponse(Response.Status.OK, HTML,
                    resultPage("没收到文件", "回到表单重新选一次 .js 文件。", false));
        }
        if (temp.length() > MAX_BYTES) {
            return newFixedLengthResponse(Response.Status.OK, HTML,
                    resultPage("文件太大", "音源脚本一般几百 KB，最大只收 6MB。", false));
        }
        byte[] data;
        try {
            data = readAll(temp);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.OK, HTML,
                    resultPage("读不到上传的内容", escape(String.valueOf(e)), false));
        }
        String text = new String(data, java.nio.charset.Charset.forName("UTF-8"));
        if (!looksLikeSource(text)) {
            return newFixedLengthResponse(Response.Status.OK, HTML,
                    resultPage("这不像是音源脚本",
                            "文件里没有 LX 音源的 @name 头或脚本主体。请到音源仓库下载 .js，不要上传压缩包。",
                            false));
        }
        // NanoHTTPD 对 multipart 的参数表可能放原始文件名，也可能什么都不放；
        // 给临时文件名兜底成时间戳，别把 NanoHTTPD-12345 这种名字摆到电视上
        String rawName = session.getParms().get("file");
        if (!TextUtils.isEmpty(rawName)
                && rawName.toLowerCase(Locale.US).contains("nanohttpd")) {
            rawName = null;
        }
        String name = safeName(rawName);
        if (TextUtils.isEmpty(name)) {
            name = "imported-" + System.currentTimeMillis() + ".js";
        }
        File dest = new File(LxManager.dir(app), name);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(dest);
            out.write(data);
            out.flush();
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.OK, HTML,
                    resultPage("写不进电视的存储", escape(String.valueOf(e)), false));
        } finally {
            close(out);
        }
        Log.i(TAG, "导入音源 " + dest);
        if (listener != null) {
            listener.onImported(dest, name);
        }
        return newFixedLengthResponse(Response.Status.OK, HTML,
                resultPage("已导入：" + name,
                        "回到电视上就能在「音乐 → 音源」里看到它。可以继续上传下一个，或关掉这个页面。",
                        true));
    }

    private static byte[] readAll(File file) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            close(in);
        }
    }

    /**
     * 认一下这是不是 LX 音源，不要是个 .js 就收。
     *
     * <p>标准音源头是一段 {@code /** @name ... *}{@code /} 注释；混淆脚本里
     * {@code @name} 也保留着（它是元数据，不参与混淆）。再兜一个 {@code module.exports}
     * 和 {@code lx.}，覆盖手写的小脚本。
     */
    private static boolean looksLikeSource(String text) {
        if (TextUtils.isEmpty(text) || text.length() < 120) {
            return false;
        }
        return text.contains("@name")
                || text.contains("module.exports")
                || text.contains("lx.request");
    }

    /** 文件名只留安全字符，避免 {@code ../} 之类跳出音源目录 */
    private static String safeName(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        String base = raw;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
        if (base.length() > 64) {
            base = base.substring(base.length() - 64);
        }
        if (base.length() == 0) {
            return null;
        }
        if (!base.toLowerCase(Locale.US).endsWith(".js")) {
            base = base + ".js";
        }
        return base;
    }

    private static String formPage(String notice) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=zh><meta charset=utf-8>")
                .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>导入音源到电视</title>")
                .append("<style>body{margin:0;padding:28px 22px;background:#0f1117;color:#e8ebf1;")
                .append("font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}")
                .append("h1{font-size:22px;margin:0 0 8px}p{color:#9aa1b0;font-size:14px;line-height:1.6}")
                .append(".box{margin-top:20px;background:#171a23;border-radius:14px;padding:20px}")
                .append("input[type=file]{width:100%;color:#e8ebf1;font-size:15px}")
                .append("button{margin-top:18px;width:100%;height:48px;border:0;border-radius:24px;")
                .append("background:#fff;color:#111;font-size:16px;font-weight:700}")
                .append(".hint{color:#6f7686;font-size:12px;margin-top:14px}")
                .append(".ok{color:#7bd88f}.err{color:#ff8a8a}</style>")
                .append("<h1>导入音源到电视</h1>")
                .append("<p>选一个 LX 音源脚本（<b>.js</b>）上传，电视上会立刻出现在「音乐 → 音源」里。</p>");
        if (!TextUtils.isEmpty(notice)) {
            sb.append("<p class=ok>").append(escape(notice)).append("</p>");
        }
        sb.append("<div class=box><form method=post action='").append(PATH_UPLOAD)
                .append("' enctype='multipart/form-data'>")
                .append("<input type=file name=file accept='.js,text/javascript,application/javascript' required>")
                .append("<button type=submit>上传到电视</button></form>")
                .append("<p class=hint>只收 .js，最大 6MB。上传期间请保持电视上的弹窗开着。</p>")
                .append("</div></html>");
        return sb.toString();
    }

    private static String resultPage(String title, String note, boolean ok) {
        return "<!doctype html><html lang=zh><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + escape(title) + "</title>"
                + "<style>body{margin:0;padding:36px 22px;background:#0f1117;color:#e8ebf1;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}"
                + "h1{font-size:22px}p{color:#9aa1b0;line-height:1.7;font-size:15px}"
                + ".ok{color:#7bd88f}.err{color:#ff8a8a}</style>"
                + "<h1 class=" + (ok ? "ok" : "err") + ">" + escape(title) + "</h1>"
                + "<p>" + escape(note) + "</p>"
                + "<p><a style='color:#8ab4ff' href='" + PATH_FORM + "'>再传一个</a></p></html>";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}

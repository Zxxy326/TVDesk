package com.tvdesk.desk.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 在线更新。
 *
 * <h3>清单长什么样</h3>
 * 更新地址由用户填（设置里的「更新地址」），两种写法都认：
 * <ul>
 *   <li><b>JSON</b>：{@code {"versionCode":7,"versionName":"1.0.6","url":"https://…/TVDesk.apk",
 *       "notes":"修了什么"}}；</li>
 *   <li><b>纯文本</b>：只写版本号（{@code 1.0.6}），下载地址默认按「同目录同名 .apk」猜
 *       —— Aerial TV 的 {@code version.txt} 就是这个风格。</li>
 * </ul>
 * 版本高低一律比 {@code versionCode}：版本名是给人看的，比较字符串会翻车
 * （"1.0.10" 按字典序小于 "1.0.9"）。
 */
public final class UpdateClient {

    private static final String TAG = "DeskUpdate";

    /**
     * 内置更新源：国内不挂代理也要能拉。
     *
     * <p>清单是仓库里的 {@code dist/update.json}（跟着每次发版更新），按顺序试：
     * <ol>
     *   <li>jsDelivr —— 国内可以直连，走的是 CDN；缺点是缓存，所以清单里的版本号
     *       以发版时写入的为准，不用它推实时状态；</li>
     *   <li>gitmirror —— 另一个常用的 GitHub raw 镜像；</li>
     *   <li>GitHub raw —— 有代理时才通，放最后兜底。</li>
     * </ol>
     * 三个都失败就当「没有更新」，不打扰用户。
     */
    private static final String[] DEFAULT_MANIFESTS = {
            // @latest 会解析到最新的 tag。tag 是不可变的，cdN 缓存不会把「上一版的文件」
            // 当成这一版返回 —— 分支名（@main）不行：1.0.7 发版时，之前请求过 @main 的
            // 节点还在发 1.0.6 的缓存，最长 12 小时，客户端会一直以为「已是最新」。
            "https://cdn.jsdelivr.net/gh/Zxxy326/TVDesk@latest/dist/update.json",
            "https://raw.gitmirror.com/Zxxy326/TVDesk/main/dist/update.json",
            "https://raw.githubusercontent.com/Zxxy326/TVDesk/main/dist/update.json",
            "https://cdn.jsdelivr.net/gh/Zxxy326/TVDesk@main/dist/update.json",
    };

    /**
     * 下载镜像前缀（拼在 GitHub 的 release 直链前面）。
     *
     * <p>国内大部分电视没装代理，{@code github.com/releases/download/...} 直接超时。
     * 这几个是社区常用的下载加速，按顺序试，谁先给包就用谁；最后一个是直连，
     * 海外或者挂代理的环境会落到它。
     */
    private static final String[] MIRROR_PREFIXES = {
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://github.moeyy.xyz/",
            "",
    };

    /** 下载进度（百分比 0~100，-1 表示总长未知） */
    public interface Progress {
        void onProgress(int percent);
    }

    /** 一次检查的结论 */
    public static class Info {
        public int versionCode;
        public String versionName = "";
        public String url = "";
        public String notes = "";
        /** 解析出来的清单原文里的最新版本号（纯文本模式下用） */
        public boolean newer;
    }

    private UpdateClient() {
    }

    /** 当前安装包的版本号 */
    public static int currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (Throwable th) {
            return 0;
        }
    }

    public static String currentVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName;
        } catch (Throwable th) {
            return "";
        }
    }

    /**
     * 查一次更新。
     *
     * @return 有更新返回清单；没有更新 / 拉不到返回 null（错误在 {@link #lastError()}）
     */
    public static Info check(Context context, String manifestUrl) {
        if (TextUtils.isEmpty(manifestUrl)) {
            return checkDefault(context);
        }
        lastError = "";
        try {
            String body = get(manifestUrl).trim();
            if (TextUtils.isEmpty(body)) {
                lastError = "更新地址没返回内容";
                return null;
            }
            Info info = new Info();
            if (body.startsWith("{")) {
                JSONObject json = new JSONObject(body);
                info.versionCode = json.optInt("versionCode", 0);
                info.versionName = json.optString("versionName", "");
                info.url = json.optString("url", "");
                info.notes = json.optString("notes", "");
            } else {
                // 纯文本：只有版本名，按同目录的 apk 猜下载地址
                info.versionName = body.split("\\s+")[0];
                info.url = guessApkUrl(manifestUrl);
                // 没有 versionCode 就按名字里的数字拼一个：1.0.6 → 106
                info.versionCode = codeFromName(info.versionName);
            }
            if (TextUtils.isEmpty(info.url)) {
                lastError = "清单里没有下载地址";
                return null;
            }
            int mine = currentVersionCode(context);
            info.newer = info.versionCode > mine;
            return info;
        } catch (Throwable th) {
            lastError = "检查失败：" + th.getMessage();
            Log.w(TAG, "检查更新失败", th);
            return null;
        }
    }

    /**
     * 用内置源查一次：逐个试，谁先给出版本号就用谁。
     *
     * <p>三个源都拉不到时返回 null，{@link #lastError()} 里是最后一次的失败原因 ——
     * 后台静默检查不看这个，手动检查会给用户看。
     */
    public static Info checkDefault(Context context) {
        lastError = "";
        Info best = null;
        // 不是「第一个成功的就用」：某个镜像可能还在发上一版的缓存，
        // 那样会让客户端一直以为「已是最新」。所有源都问一遍，取版本号最大的。
        for (String url : DEFAULT_MANIFESTS) {
            Info info = checkFrom(context, url);
            if (info != null && (best == null || info.versionCode > best.versionCode)) {
                best = info;
            }
        }
        if (best == null && TextUtils.isEmpty(lastError)) {
            lastError = "连不上更新服务器";
        }
        return best;
    }


    /**
     * 按本机 ABI 从 {@code assets} 里挑安装包。
     *
     * <p>清单里三种包都有时，32 位电视拿 armeabi-v7a、64 位拿 arm64-v8a；
     * 找不到对应项就退回清单顶层的 {@code url}（一般是 universal）。
     */
    private static String pickAsset(JSONObject assets, String fallback) {
        if (assets == null) {
            return fallback;
        }
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            for (String abi : abis) {
                String url = assets.optString(abi, "");
                if (!TextUtils.isEmpty(url)) {
                    return url;
                }
            }
            String universal = assets.optString("universal", "");
            if (!TextUtils.isEmpty(universal)) {
                return universal;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 从一个具体地址读清单（不做默认源回退，避免递归） */
    private static Info checkFrom(Context context, String manifestUrl) {
        lastError = "";
        try {
            String body = get(manifestUrl).trim();
            if (TextUtils.isEmpty(body)) {
                lastError = "更新地址没返回内容";
                return null;
            }
            Info info = new Info();
            if (body.startsWith("{")) {
                JSONObject json = new JSONObject(body);
                info.versionCode = json.optInt("versionCode", 0);
                info.versionName = json.optString("versionName", "");
                info.url = json.optString("url", "");
                info.notes = json.optString("notes", "");
                // 清单里按 ABI 分了包就挑本机的那个：这台电视是 32 位就下 24MB 的，
                // 不去下 38MB 的通用包
                info.url = pickAsset(json.optJSONObject("assets"), info.url);
            } else {
                info.versionName = body.split("\\s+")[0];
                info.url = guessApkUrl(manifestUrl);
                info.versionCode = codeFromName(info.versionName);
            }
            if (TextUtils.isEmpty(info.url)) {
                lastError = "清单里没有下载地址";
                return null;
            }
            info.newer = info.versionCode > currentVersionCode(context);
            return info;
        } catch (Throwable th) {
            lastError = "检查失败：" + th.getMessage();
            Log.w(TAG, "检查更新失败（" + manifestUrl + "）", th);
            return null;
        }
    }

    private static volatile String lastError = "";

    public static String lastError() {
        return lastError == null ? "" : lastError;
    }

    /**
     * 下载安装包。
     *
     * @return 落地的 apk；失败返回 null
     */
    public static File download(Context context, Info info) {
        return download(context, info, null);
    }

    /**
     * 下载安装包：按镜像顺序试，谁先给全包就用谁。
     *
     * <p>「拿全包」的判据有两条：HTTP 2xx，且落地的文件是 zip（apk 头四字节 {@code PK\3\4}）。
     * 加速站有时会返回 200 加一张错误页，只看状态码会被骗。
     */
    public static File download(Context context, Info info, Progress progress) {
        if (info == null || TextUtils.isEmpty(info.url)) {
            lastError = "清单里没有下载地址";
            return null;
        }
        File dir = new File(context.getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) {
            lastError = "建不了下载目录";
            return null;
        }
        String lastFailure = "";
        for (String prefix : mirrorsFor(info.url)) {
            File apk = tryDownload(context, prefix + info.url, info, dir, progress, prefix);
            if (apk != null) {
                return apk;
            }
            lastFailure = lastError;
        }
        lastError = TextUtils.isEmpty(lastFailure) ? "下载失败" : lastFailure;
        return null;
    }

    /** 已经套了镜像地址的就不再套一层；非 GitHub 直链（自建源）也不套 */
    private static String[] mirrorsFor(String url) {
        if (!url.startsWith("https://github.com/")) {
            return new String[]{""};
        }
        return MIRROR_PREFIXES;
    }

    private static File tryDownload(Context context, String url, Info info, File dir,
                                    Progress progress, String prefix) {
        File target = new File(dir, "TVDesk-" + info.versionName + ".apk");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "TVDesk/" + info.versionName);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                lastError = "下载失败 HTTP " + code
                        + (TextUtils.isEmpty(prefix) ? "" : "（镜像 " + hostOf(prefix) + "）");
                return null;
            }
            long total = conn.getContentLength();
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[32 * 1024];
            long done = 0;
            int n;
            int lastPercent = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null && total > 0) {
                    int percent = (int) (done * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress.onProgress(percent);
                    }
                }
            }
            out.flush();
            in.close();
            out.close();
            // apk 是 zip，头四个字节必须是 PK\x03\x04，防止把错误页当安装包
            if (target.length() < 4096 || !isApk(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                lastError = "下载到的不是安装包"
                        + (TextUtils.isEmpty(prefix) ? "" : "（镜像 " + hostOf(prefix) + "）");
                return null;
            }
            if (progress != null) {
                progress.onProgress(100);
            }
            Log.i(TAG, "下载完成：" + target.length() + " 字节，来源 "
                    + (TextUtils.isEmpty(prefix) ? "直连" : hostOf(prefix)));
            return target;
        } catch (Throwable th) {
            lastError = "下载失败：" + th.getMessage()
                    + (TextUtils.isEmpty(prefix) ? "" : "（镜像 " + hostOf(prefix) + "）");
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable th) {
            return url;
        }
    }

    /** 拉起系统安装界面 */
    public static boolean install(Context context, File apk) {
        if (apk == null || !apk.isFile()) {
            return false;
        }
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Throwable th) {
            lastError = "打不开安装界面：" + th.getMessage();
            Log.w(TAG, "安装失败", th);
            return false;
        }
    }

    private static boolean isApk(File file) {
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            byte[] h = new byte[4];
            return in.read(h) == 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
        } catch (Throwable th) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    /** 纯文本清单模式下，把 …/version.txt 换成 …/xxx.apk */
    private static String guessApkUrl(String manifestUrl) {
        int slash = manifestUrl.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        String dir = manifestUrl.substring(0, slash + 1);
        String name = manifestUrl.substring(slash + 1);
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        return dir + base + ".apk";
    }

    /** "1.0.6" → 10006，够用来比大小 */
    private static int codeFromName(String name) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c == '.') {
                digits.append('0');
            }
        }
        try {
            return digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
        } catch (Throwable th) {
            return 0;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "TVDesk");
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            in.close();
            return buf.toString("UTF-8");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}

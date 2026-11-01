package com.tvdesk.desk.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
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
        lastError = "";
        if (TextUtils.isEmpty(manifestUrl)) {
            lastError = "还没填更新地址";
            return null;
        }
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
        if (info == null || TextUtils.isEmpty(info.url)) {
            return null;
        }
        File dir = new File(context.getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File target = new File(dir, "TVDesk-" + info.versionName + ".apk");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(info.url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "TVDesk/" + info.versionName);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                lastError = "下载失败 HTTP " + code;
                return null;
            }
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            in.close();
            out.close();
            // apk 是 zip，头四个字节必须是 PK\x03\x04，防止把错误页当安装包
            if (target.length() < 4096 || !isApk(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                lastError = "下载到的不是安装包";
                return null;
            }
            return target;
        } catch (Throwable th) {
            lastError = "下载失败：" + th.getMessage();
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

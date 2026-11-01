package com.github.catvod.music;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 音源市场：在线下载 LX 音源。
 *
 * <h3>做法照搬像素播放器</h3>
 * 市场内容来自像素播放器用的那个仓库（{@code guoyue2010/lxmusic-}）：
 * 它把每个版本的音源都按 {@code 版本目录/分类/音源名.js} 摆在仓库里。
 * 像素播放器是下 release 的 zip 再解包；这里改成
 * <b>用 GitHub 的 tree 接口列出仓库里的 js，再用 jsDelivr 直连下载单个文件</b>——
 * 好处有三：
 * <ul>
 *   <li>不用下整包（一个包 400KB+ 十几个音源，只要其中一个也全下）；</li>
 *   <li>jsDelivr 在很多网络下是通的，而 GitHub 的 release 直链经常断流
 *       （实测下到一半就断，解包直接 MALFORMED）；</li>
 *   <li>文件名在清单里就有，用户看得见自己下的是什么。</li>
 * </ul>
 * 目录结构、basename 取名、防目录穿越这些规矩都跟像素播放器一致。
 */
public final class MusicSourceMarket {

    private static final String TAG = "MusicMarket";

    /** 市场仓库（与像素播放器一致） */
    private static final String REPO_OWNER = "guoyue2010";
    private static final String REPO_NAME = "lxmusic-";
    private static final String TREE_API = "https://api.github.com/repos/" + REPO_OWNER
            + "/" + REPO_NAME + "/git/trees/main?recursive=1";
    /** jsDelivr 加速（GitHub 直链在很多网络下不通） */
    private static final String CDN = "https://cdn.jsdelivr.net/gh/" + REPO_OWNER + "/"
            + REPO_NAME + "@main/";

    private MusicSourceMarket() {
    }

    /** 市场里的一个音源 */
    public static class Item {
        /** 展示名（文件名） */
        public String name = "";
        /** 仓库内路径 */
        public String path = "";
        /** 下载直链 */
        public String url = "";
        /** 所属分类 */
        public String category = "";
        /** 所属版本目录 */
        public String version = "";
    }

    /**
     * 拉市场清单：最新版本目录下的全部 js。
     *
     * @return 音源列表；拉不到返回空表（调用方提示用户，别当成没有市场）
     */
    public static List<Item> fetchMarket() {
        List<Item> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(TREE_API).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "TVDesk");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "市场清单拉取失败 http=" + conn.getResponseCode());
                return out;
            }
            String body = readAll(conn.getInputStream());
            JSONArray arr = new JSONObject(body).optJSONArray("tree");
            if (arr == null) {
                return out;
            }
            String newest = null;
            long newestNo = -1;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String p = o.optString("path", "");
                if (!p.endsWith(".js")) {
                    continue;
                }
                String ver = p.contains("/") ? p.substring(0, p.indexOf('/')) : "";
                long no = versionNo(ver);
                if (no > newestNo) {
                    newestNo = no;
                    newest = ver;
                }
            }
            if (newest == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String p = o.optString("path", "");
                if (!p.endsWith(".js") || !p.startsWith(newest + "/")) {
                    continue;
                }
                Item item = new Item();
                item.version = newest;
                item.path = p;
                String rest = p.substring(newest.length() + 1);
                int slash = rest.lastIndexOf('/');
                item.category = slash > 0 ? rest.substring(0, slash) : "";
                item.name = slash > 0 ? rest.substring(slash + 1) : rest;
                item.url = CDN + encodePath(p);
                out.add(item);
            }
            Collections.sort(out, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    int c = a.category.compareTo(b.category);
                    return c != 0 ? c : a.name.compareTo(b.name);
                }
            });
        } catch (Throwable th) {
            Log.w(TAG, "市场清单异常", th);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return out;
    }

    /** 版本目录名里的数字（V261020 / v260511 → 261020 / 260511） */
    private static long versionNo(String ver) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ver.length(); i++) {
            char c = ver.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        try {
            return sb.length() == 0 ? -1 : Long.parseLong(sb.toString());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 路径里的中文与空格要编码，斜杠保留 */
    private static String encodePath(String path) {
        try {
            return java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")
                    .replace("%2F", "/");
        } catch (Exception e) {
            return path;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        close(in);
        return buf.toString("UTF-8");
    }

    /**
     * 下载一个音源并装进本地音源目录。
     *
     * @return 落地文件；失败返回 null
     */
    public static File downloadAndInstall(Context context, Item item) {
        if (item == null || TextUtils.isEmpty(item.url)) {
            return null;
        }
        byte[] bytes = fetch(item.url);
        if (bytes == null || bytes.length < 512) {
            return null;
        }
        File dir = LxManager.dir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        // 只取 basename，防目录穿越（跟像素播放器一致）
        String base = item.name.contains("/") ? item.name.substring(item.name.lastIndexOf('/') + 1)
                : item.name;
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            return null;
        }
        File target = new File(dir, base);
        OutputStream out = null;
        try {
            out = new FileOutputStream(target);
            out.write(bytes);
            out.flush();
            return target;
        } catch (Throwable th) {
            Log.w(TAG, "写入音源失败 " + base + "：" + th.getMessage());
            return null;
        } finally {
            close(out);
        }
    }

    /** 取一个直链的内容 */
    private static byte[] fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/124.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "下载失败 http=" + code + " " + url);
                return null;
            }
            long total = conn.getContentLengthLong();
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            long written = 0;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                written += n;
            }
            close(in);
            if (total > 0 && written != total) {
                Log.w(TAG, "下载不完整 " + written + "/" + total);
                return null;
            }
            return buf.toByteArray();
        } catch (Throwable th) {
            Log.w(TAG, "下载异常 " + url + "：" + th.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
            // 关不掉也无所谓
        }
    }
}

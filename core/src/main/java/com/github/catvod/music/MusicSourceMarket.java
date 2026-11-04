package com.github.catvod.music;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 音源市场：在线下载 LX 音源。
 *
 * <h3>数据源：GitHub Releases（不是仓库目录树）</h3>
 * 上游（{@code guoyue2010/lxmusic-}）现在只在 <b>Releases</b> 更新：
 * 每个版本打一个 {@code V261104.zip}，里面是 {@code V261104/分类/音源.js}。
 * 仓库 {@code main} 里的版本目录停在 {@code V260907} 就不动了 ——
 * 早先这里读的是 tree 接口，所以市场永远只看到 V260907，
 * 上游后来发了十几个版本，这边一点感觉都没有。
 *
 * <p>现在改成：读 {@code /releases/latest} → 下最新的 zip（缓存到 cacheDir，
 * 装音源时直接从缓存里取，不重复下载）→ 列出包里的 js。
 * 下载走国内可达的 GitHub 加速镜像（{@code ghfast.top} / {@code gh-proxy.com}）；
 * 直链在多数网络下是断的。每个镜像都校验实际字节数与 Content-Length，短一截就换下一个。
 *
 * <p>Releases 拿不到时（接口被限流、镜像全挂）再退回老路：
 * 用 GitHub tree 接口列 {@code main} 里的 js，至少还能装到 V260907 那一批。
 */
public final class MusicSourceMarket {

    private static final String TAG = "MusicMarket";

    private static final String REPO_OWNER = "guoyue2010";
    private static final String REPO_NAME = "lxmusic-";

    /** 最新 release（tag + assets） */
    private static final String API_LATEST = "https://api.github.com/repos/" + REPO_OWNER
            + "/" + REPO_NAME + "/releases/latest";

    /** 兜底：仓库目录树（main 里的版本目录不再更新，只在 releases 挂掉时用） */
    private static final String TREE_API = "https://api.github.com/repos/" + REPO_OWNER
            + "/" + REPO_NAME + "/git/trees/main?recursive=1";
    private static final String CDN = "https://cdn.jsdelivr.net/gh/" + REPO_OWNER + "/"
            + REPO_NAME + "@main/";

    /**
     * GitHub 加速镜像。直链（空字符串那个）放最后：
     * 实测国内大多数网络下 github.com 的 release 直链根本连不上。
     */
    private static final String[] MIRRORS = {
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "",
    };

    /** 一个音源包最大 48MB（历史上出过 28MB 的大包），超过就当镜像返回了脏数据 */
    private static final long MAX_ZIP_BYTES = 48L * 1024 * 1024;

    /** 缓存目录名（存在 cacheDir 下） */
    private static final String CACHE_DIR = "music-market";

    private static final String UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private MusicSourceMarket() {
    }

    /** 市场里的一个音源 */
    public static class Item {
        /** 展示名（文件名） */
        public String name = "";
        /** 包内路径（{@link #inZip} 为 true 时有效），或仓库内路径 */
        public String path = "";
        /** 下载直链（zip 或单个 js） */
        public String url = "";
        /** 所属分类（release 包里的「推荐 / 一般 / 优质」这一层） */
        public String category = "";
        /** 所属版本（release 的 tag，或仓库里的版本目录名） */
        public String version = "";
        /** true 表示这个音源在 release 的 zip 里，装的时候从缓存的包内解出来 */
        public boolean inZip;
    }

    /**
     * 拉市场清单。
     *
     * @return 音源列表；拉不到返回空表（调用方提示用户，别当成没有市场）
     */
    public static List<Item> fetchMarket(Context context) {
        List<Item> released = fetchFromRelease(context);
        if (!released.isEmpty()) {
            return released;
        }
        Log.w(TAG, "releases 拉不到，退回仓库目录树");
        return fetchFromTree();
    }

    // ------------------------------------------------------------------
    // Releases（主路径）
    // ------------------------------------------------------------------

    private static List<Item> fetchFromRelease(Context context) {
        List<Item> out = new ArrayList<>();
        if (context == null) {
            return out;
        }
        String tag = "";
        String assetUrl = "";
        long assetSize = 0;
        try {
            String body = httpGet(API_LATEST, "application/vnd.github+json", 15000, 25000);
            if (TextUtils.isEmpty(body)) {
                return out;
            }
            JSONObject root = new JSONObject(body);
            tag = root.optString("tag_name", "").trim();
            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) {
                        continue;
                    }
                    String name = a.optString("name", "");
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                        continue;
                    }
                    assetUrl = a.optString("browser_download_url", "");
                    assetSize = a.optLong("size", 0);
                    break;
                }
            }
        } catch (Throwable th) {
            Log.w(TAG, "release 信息异常：" + th.getMessage());
            return out;
        }
        if (TextUtils.isEmpty(tag) || TextUtils.isEmpty(assetUrl)) {
            Log.w(TAG, "最新 release 里没有 zip：tag=" + tag);
            return out;
        }
        File zip = cachedZip(context, tag, assetUrl, assetSize);
        if (zip == null) {
            return out;
        }
        List<String> paths = listZipEntries(zip);
        if (paths.isEmpty()) {
            // 包坏了：删掉，下次重下
            Log.w(TAG, "包里一个条目都没解出来，丢弃缓存");
            zip.delete();
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (String path : paths) {
            if (!path.toLowerCase(Locale.ROOT).endsWith(".js")) {
                continue;
            }
            if (path.startsWith("__MACOSX/")) {
                continue;
            }
            // 包里带一层版本目录（V261104/推荐/xxx.js）：展示时摘掉，分类保留
            String rest = path.startsWith(tag + "/") ? path.substring(tag.length() + 1) : path;
            int slash = rest.lastIndexOf('/');
            String name = slash >= 0 ? rest.substring(slash + 1) : rest;
            if (TextUtils.isEmpty(name) || !seen.add(name)) {
                continue;
            }
            Item item = new Item();
            item.version = tag;
            item.path = path;
            item.url = assetUrl;
            item.inZip = true;
            item.category = slash > 0 ? rest.substring(0, slash) : "";
            item.name = name;
            out.add(item);
        }
        sort(out);
        Log.i(TAG, "市场清单 " + tag + "（release）：" + out.size() + " 个音源");
        return out;
    }

    /**
     * 取这个 tag 的 zip（有缓存就用缓存）。
     *
     * @return 可用的本地 zip；下载失败返回 null
     */
    private static File cachedZip(Context context, String tag, String assetUrl, long assetSize) {
        File dir = new File(context.getCacheDir(), CACHE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "建不了缓存目录 " + dir);
            return null;
        }
        File target = new File(dir, tag + ".zip");
        if (isGoodZip(target, assetSize)) {
            return target;
        }
        // 只留当前版本，别让缓存越攒越大
        File[] olds = dir.listFiles();
        if (olds != null) {
            for (File old : olds) {
                if (!old.equals(target)) {
                    old.delete();
                }
            }
        }
        File part = new File(dir, tag + ".zip.part");
        if (!download(assetUrl, part, assetSize)) {
            part.delete();
            return null;
        }
        if (target.exists()) {
            target.delete();
        }
        if (!part.renameTo(target)) {
            Log.w(TAG, "缓存重命名失败");
            part.delete();
            return null;
        }
        return target;
    }

    private static boolean isGoodZip(File file, long expectSize) {
        if (file == null || !file.exists() || file.length() < 512) {
            return false;
        }
        // 上游可能重传同名 asset，大小差超过 1% 就当作旧包
        return expectSize <= 0 || Math.abs(file.length() - expectSize) <= expectSize / 100 + 16;
    }

    // ------------------------------------------------------------------
    // 仓库目录树（兜底）
    // ------------------------------------------------------------------

    private static List<Item> fetchFromTree() {
        List<Item> out = new ArrayList<>();
        String body = httpGet(TREE_API, "application/vnd.github+json", 15000, 25000);
        if (TextUtils.isEmpty(body)) {
            return out;
        }
        try {
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
                item.inZip = false;
                out.add(item);
            }
            sort(out);
        } catch (Throwable th) {
            Log.w(TAG, "市场清单异常", th);
        }
        return out;
    }

    /** 版本目录名里的数字（V261104 / v260511 → 261104 / 260511） */
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

    private static void sort(List<Item> out) {
        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                int ra = categoryRank(a.category);
                int rb = categoryRank(b.category);
                if (ra != rb) {
                    return ra - rb;
                }
                int c = a.category.compareTo(b.category);
                return c != 0 ? c : a.name.compareTo(b.name);
            }
        });
    }

    /**
     * 分类排序权重。上游用「推荐 / 优质 / 一般 / 未测试」标可用程度，
     * 按字典序排会把「一般」顶到最前面 —— 用户第一眼看到的应该是最好的那批。
     */
    private static int categoryRank(String category) {
        String c = category == null ? "" : category;
        if (c.contains("推荐")) {
            return 0;
        }
        if (c.contains("优质")) {
            return 1;
        }
        if (c.contains("一般")) {
            return 2;
        }
        if (c.contains("未测试")) {
            return 3;
        }
        return c.isEmpty() ? 4 : 5;
    }

    /** 路径里的中文与空格要编码，斜杠保留（jsDelivr 直连时用） */
    private static String encodePath(String path) {
        try {
            return URLEncoder.encode(path, "UTF-8").replace("+", "%20")
                    .replace("%2F", "/");
        } catch (Exception e) {
            return path;
        }
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /**
     * 下载一个音源并装进本地音源目录。
     *
     * @return 落地文件；失败返回 null
     */
    public static File downloadAndInstall(Context context, Item item) {
        if (item == null || context == null || TextUtils.isEmpty(item.name)) {
            return null;
        }
        // 只取 basename，防目录穿越（跟像素播放器一致）
        String base = item.name.contains("/") ? item.name.substring(item.name.lastIndexOf('/') + 1)
                : item.name;
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            return null;
        }
        File dir = LxManager.dir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        File target = new File(dir, base);
        byte[] bytes = item.inZip ? readFromZip(context, item) : fetch(item.url);
        if (bytes == null || bytes.length < 512) {
            return null;
        }
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

    /** 从缓存的 release 包里解出一个条目 */
    private static byte[] readFromZip(Context context, Item item) {
        File zip = new File(new File(context.getCacheDir(), CACHE_DIR), item.version + ".zip");
        if (!isGoodZip(zip, 0)) {
            // 缓存被清了：按已知地址重新下一次（装的时候才补，列清单时不做）
            File dir = zip.getParentFile();
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                return null;
            }
            File part = new File(dir, item.version + ".zip.part");
            if (!download(item.url, part, 0)) {
                part.delete();
                return null;
            }
            if (zip.exists()) {
                zip.delete();
            }
            if (!part.renameTo(zip)) {
                part.delete();
                return null;
            }
        }
        return extractEntry(zip, item.path);
    }

    /** 在包里找一条并读完；先按 UTF-8 认名字，认不到再按 GBK */
    private static byte[] extractEntry(File zip, final String wanted) {
        Charset[] charsets = {Charset.forName("UTF-8"), Charset.forName("GBK")};
        for (Charset cs : charsets) {
            final byte[][] holder = {null};
            scanZip(zip, cs, new EntryVisitor() {
                @Override
                public boolean visit(String path, InputStream in) throws Exception {
                    if (!path.equals(wanted)) {
                        return true;
                    }
                    holder[0] = readAll(in);
                    return false;
                }
            });
            if (holder[0] != null) {
                return holder[0];
            }
        }
        Log.w(TAG, "包里没有这个条目：" + wanted);
        return null;
    }

    /**
     * 顺序扫一遍包。
     *
     * <p>没用 {@link java.util.zip.ZipFile}：实机上它对上游这个包直接报
     * {@code MALFORMED[1]}（同一个文件在电脑上能正常打开），
     * 换成流式的 ZipInputStream 才读得动 —— 反正我们只要按名字取文件，顺序扫就够。
     */
    private static boolean scanZip(File file, Charset cs, EntryVisitor visitor) {
        ZipInputStream zin = null;
        try {
            zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)), cs);
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = entry.getName();
                if (path == null) {
                    continue;
                }
                if (!visitor.visit(path, zin)) {
                    return true;
                }
            }
            return true;
        } catch (Throwable th) {
            Log.w(TAG, "扫包失败（" + cs + "）：" + th.getMessage());
            return false;
        } finally {
            close(zin);
        }
    }

    /** 扫包时的回调 */
    private interface EntryVisitor {
        /** @return false 表示不用再看后面的条目 */
        boolean visit(String path, InputStream in) throws Exception;
    }

    /** 包里的文件清单（乱码就换 GBK 再扫一遍） */
    private static List<String> listZipEntries(File zip) {
        List<String> paths = new ArrayList<>();
        boolean ok = scanZip(zip, Charset.forName("UTF-8"), collectInto(paths));
        if (!ok || hasReplacementChar(paths)) {
            paths = new ArrayList<>();
            if (!scanZip(zip, Charset.forName("GBK"), collectInto(paths))) {
                return new ArrayList<>();
            }
        }
        return paths;
    }

    private static EntryVisitor collectInto(final List<String> paths) {
        return new EntryVisitor() {
            @Override
            public boolean visit(String path, InputStream in) {
                paths.add(path);
                return true;
            }
        };
    }

    private static boolean hasReplacementChar(List<String> names) {
        for (String name : names) {
            if (name != null && name.indexOf('\uFFFD') >= 0) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    // ------------------------------------------------------------------
    // 网络
    // ------------------------------------------------------------------

    /** 取一个直链的内容 */
    private static byte[] fetch(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "下载失败 http=" + code + " " + url);
                return null;
            }
            long total = conn.getContentLengthLong();
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
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

    /**
     * 把 release 包下到本地。依次试各个加速镜像，校验字节数，不合格换下一个。
     */
    private static boolean download(String url, File target, long expectSize) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        for (String mirror : MIRRORS) {
            String real = mirror + url;
            if (!writeToFile(real, target, expectSize)) {
                target.delete();
                continue;
            }
            // 有些镜像会返回 200 但内容是半截或错误页。字节数对上也不代表是有效 zip，
            // 落地前真开一次：开不动就删掉换下一个镜像（否则用户点安装才报错）。
            if (isReadableZip(target)) {
                Log.i(TAG, "音源包已下载（" + (mirror.isEmpty() ? "直链" : mirror) + "）"
                        + "，size=" + target.length());
                return true;
            }
            Log.w(TAG, "镜像给的包不是有效 zip（" + (mirror.isEmpty() ? "直链" : mirror)
                    + "，size=" + target.length() + "），换下一个");
            target.delete();
        }
        return false;
    }

    /** 能不能当 zip 打开（只顺序扫一遍条目名，不解压） */
    private static boolean isReadableZip(File file) {
        if (file == null || !file.exists() || file.length() < 512) {
            return false;
        }
        return !listZipEntries(file).isEmpty();
    }

    private static boolean writeToFile(String url, File target, long expectSize) {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "包下载失败 http=" + code + " " + url);
                return false;
            }
            long total = conn.getContentLengthLong();
            if (total > MAX_ZIP_BYTES) {
                Log.w(TAG, "包太大，放弃：" + total);
                return false;
            }
            in = conn.getInputStream();
            out = new FileOutputStream(target);
            byte[] chunk = new byte[32 * 1024];
            int n;
            long written = 0;
            while ((n = in.read(chunk)) > 0) {
                written += n;
                if (written > MAX_ZIP_BYTES) {
                    Log.w(TAG, "包超过上限，中断");
                    return false;
                }
                out.write(chunk, 0, n);
            }
            out.flush();
            if (expectSize > 0 && written != expectSize) {
                Log.w(TAG, "包不完整 " + written + "/" + expectSize + " @ " + url);
                return false;
            }
            if (total > 0 && written != total) {
                Log.w(TAG, "包不完整 " + written + "/" + total + " @ " + url);
                return false;
            }
            return written > 512;
        } catch (Throwable th) {
            Log.w(TAG, "包下载异常 " + url + "：" + th.getMessage());
            return false;
        } finally {
            close(out);
            close(in);
            if (conn != null) {
                conn.disconnect();
            }
            if (target.exists() && target.length() < 512) {
                target.delete();
            }
        }
    }

    private static String httpGet(String url, String accept, int connectTimeout, int readTimeout) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "TVDesk");
            if (!TextUtils.isEmpty(accept)) {
                conn.setRequestProperty("Accept", accept);
            }
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "接口返回 http=" + code + " " + url);
                return "";
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            close(in);
            return buf.toString("UTF-8");
        } catch (Throwable th) {
            Log.w(TAG, "接口异常 " + url + "：" + th.getMessage());
            return "";
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

package com.github.catvod.music;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 音源脚本的落盘目录与元数据解析。
 *
 * <h3>为什么放在外部私有目录</h3>
 * {@code Android/data/<pkg>/files/lxsource} 用 adb 可以直接 push 进去，
 * 电视上用文件管理器也能看到（部分系统需要授权）。放 {@code getFilesDir()} 虽然更稳，
 * 但用户完全没法往里放文件——电视上没有「打开文件」这种交互，只能靠 adb 或 U 盘。
 * 所以优先外部私有目录，拿不到再回落内部。
 *
 * <h3>头部解析为什么不用正则</h3>
 * 头部是注释，里面可能有 {@code *} 开头的空行、多行 changelog，用逐行扫描比正则稳，
 * 也不会因为 changelog 里出现 {@code @} 就误判。
 */
public class LxManager {

    private static final String TAG = "LxManager";

    private static final String DIR_NAME = "lxsource";

    /** 头部只读前 8KB，再长的注释也不合理 */
    private static final int HEAD_LIMIT = 8192;

    /** 音源目录。外部可用就用外部，否则内部 */
    public static File dir(Context context) {
        File ext = context.getExternalFilesDir(DIR_NAME);
        File target = ext != null ? ext : new File(context.getFilesDir(), DIR_NAME);
        if (!target.exists() && !target.mkdirs()) {
            Log.w(TAG, "mkdir failed: " + target);
        }
        return target;
    }

    /** 内嵌音源的文件名和 assets 路径。改名时连同 marker 一起换，老用户才会拿到新版 */
    private static final String BUILTIN_ASSET = "lxsource/molan-2.3.4.js";
    private static final String BUILTIN_NAME = "molan-2.3.4.js";
    private static final String BUILTIN_MARK = "lx_builtin_seeded_molan_234";

    /**
     * 第一次进音乐页时把内置音源拷进音源目录。
     *
     * <p>用 marker 而不是每次「文件不在就补」：用户删掉内置音源是明确的动作，
     * 不该下一次扫描又冒出来。拷贝失败不落 marker，下次还会重试。
     *
     * <p>音源脚本是第三方的，只随 debug 包带（{@code app/src/debug/assets/lxsource/}）。
     * 发行版里没这个目录，探一次就落 marker 收工，别每次扫描都重试一遍。
     */
    public static void seedBuiltin(Context context) {
        File marker = new File(context.getFilesDir(), BUILTIN_MARK);
        if (marker.exists()) {
            return;
        }
        File dest = new File(dir(context), BUILTIN_NAME);
        InputStream in = null;
        FileOutputStream out = null;
        try {
            if (!bundled(context)) {
                marker.createNewFile();
                return;
            }
            if (!dest.exists()) {
                in = context.getAssets().open(BUILTIN_ASSET);
                out = new FileOutputStream(dest);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            marker.createNewFile();
        } catch (Exception e) {
            Log.w(TAG, "seedBuiltin -> " + e);
        } finally {
            close(in);
            close(out);
        }
    }

    /** assets 里带没带内嵌音源。发行版不带，所以这一步得先问一句 */
    private static boolean bundled(Context context) {
        try {
            String[] names = context.getAssets().list("lxsource");
            if (names == null) {
                return false;
            }
            for (String name : names) {
                if (BUILTIN_NAME.equals(name)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 扫描目录下所有 .js。按文件名排序，保证每次进入顺序一致 */
    public static List<LxSource> scan(Context context) {
        seedBuiltin(context);
        List<LxSource> list = new ArrayList<>();
        File[] files = dir(context).listFiles();
        if (files == null) {
            return list;
        }
        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".js")) {
                continue;
            }
            LxSource source = parse(f);
            if (source != null) {
                list.add(source);
            }
        }
        Collections.sort(list, new Comparator<LxSource>() {
            @Override
            public int compare(LxSource a, LxSource b) {
                return a.fileName.compareToIgnoreCase(b.fileName);
            }
        });
        return list;
    }

    /**
     * 解析单个脚本文件的元数据。
     * 即使头部写得乱七八糟也会返回一个对象（name 退回文件名），
     * 只有读不出来时才返回 null。
     */
    public static LxSource parse(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        LxSource source = new LxSource();
        source.fileName = file.getName();
        source.size = file.length();
        source.modified = file.lastModified();

        String head = readHead(file);
        if (!TextUtils.isEmpty(head)) {
            parseHead(head, source);
        }
        if (TextUtils.isEmpty(source.name)) {
            // 没写 @name 的脚本不是合法 LX 源，但也不该直接从列表里消失——
            // 用户放错文件时得看得见，才知道是自己放错了
            source.name = "";
        }
        return source;
    }

    /** 读文件前 HEAD_LIMIT 字节，按 UTF-8 解 */
    private static String readHead(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[HEAD_LIMIT];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            return new String(buf, 0, read, "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "readHead " + file.getName() + " -> " + e);
            return "";
        } finally {
            close(in);
        }
    }

    /**
     * 逐行扫 {@code @key value}。
     * 只在遇到第一个 {@code @} 字段前保留 {@code /*!} 块，出了块就停，
     * 避免把正文里偶然出现的 {@code @author} 当成元数据。
     */
    private static void parseHead(String head, LxSource source) {
        String[] lines = head.split("\r?\n");
        boolean inBlock = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (!inBlock) {
                if (line.startsWith("/*!")) {
                    inBlock = true;
                    // 单行写法的 /*! @name x */ 也要认
                    line = line.substring(3).trim();
                    if (line.endsWith("*/")) {
                        line = line.substring(0, line.length() - 2).trim();
                    }
                    if (line.isEmpty()) {
                        continue;
                    }
                } else {
                    continue;
                }
            } else if (line.startsWith("*/")) {
                break;
            }

            // 去掉行首的 * 和空格
            while (line.startsWith("*")) {
                line = line.substring(1).trim();
            }
            if (!line.startsWith("@")) {
                continue;
            }
            int space = line.indexOf(' ');
            String key;
            String value;
            if (space < 0) {
                key = line.substring(1);
                value = "";
            } else {
                key = line.substring(1, space);
                value = line.substring(space + 1).trim();
            }
            switch (key) {
                case "name":
                    source.name = value;
                    break;
                case "version":
                    source.version = value;
                    break;
                case "author":
                    source.author = value;
                    break;
                case "description":
                    source.description = value;
                    break;
                case "homepage":
                    source.homepage = value;
                    break;
                case "update":
                    source.update = value;
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 把外部文件复制进音源目录。
     *
     * @return 导入后的文件；失败返回 null
     */
    public static File importFrom(Context context, File src) {
        if (src == null || !src.isFile()) {
            return null;
        }
        File target = new File(dir(context), src.getName());
        // 同名覆盖：用户重复导入同一份文件时应该更新，而不是堆一堆副本
        if (target.exists() && target.getAbsolutePath().equals(src.getAbsolutePath())) {
            return target;
        }
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(target);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return target;
        } catch (Exception e) {
            Log.w(TAG, "importFrom " + src + " -> " + e);
            return null;
        } finally {
            close(in);
            close(out);
        }
    }

    /** 读脚本全文（执行时用） */
    public static String readAll(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            return new String(buf, 0, read, "UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "readAll " + file.getName() + " -> " + e);
            return "";
        } finally {
            close(in);
        }
    }

    // ------------------------------------------------------------ 测试结果缓存

    private static final String STATE_FILE = "lx_state.json";

    /**
     * 保存测试结果。
     *
     * <p>不存的话，用户导入音源、测出「5 个平台 20 种音质」，退出模块再进来又变回
     * 「未测试」——看起来像没生效。测试结果和脚本文件是一体的，跟着一起持久化。
     */
    public static void saveState(Context context, List<LxSource> list) {
        org.json.JSONObject root = new org.json.JSONObject();
        try {
            for (LxSource s : list) {
                if (LxSource.STATUS_UNTESTED.equals(s.status)) {
                    continue;
                }
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("status", s.status);
                item.put("detail", s.detail);
                item.put("handler", s.hasRequestHandler);
                org.json.JSONArray platforms = new org.json.JSONArray();
                for (LxSource.Platform p : s.platforms) {
                    org.json.JSONObject po = new org.json.JSONObject();
                    po.put("id", p.id);
                    po.put("name", p.name);
                    po.put("actions", new org.json.JSONArray(p.actions));
                    po.put("qualities", new org.json.JSONArray(p.qualities));
                    platforms.put(po);
                }
                item.put("platforms", platforms);
                root.put(s.fileName, item);
            }
        } catch (Exception e) {
            Log.w(TAG, "saveState build -> " + e);
        }
        File file = new File(context.getFilesDir(), STATE_FILE);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(root.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "saveState write -> " + e);
        } finally {
            close(out);
        }
    }

    /** 把上次的测试结果贴回扫描出来的对象上。脚本文件改过就作废，重新测 */
    public static void applyState(Context context, List<LxSource> list) {
        File file = new File(context.getFilesDir(), STATE_FILE);
        if (!file.isFile()) {
            return;
        }
        String text = readAll(file);
        if (TextUtils.isEmpty(text)) {
            return;
        }
        org.json.JSONObject root;
        try {
            root = new org.json.JSONObject(text);
        } catch (Exception e) {
            return;
        }
        for (LxSource s : list) {
            org.json.JSONObject item = root.optJSONObject(s.fileName);
            if (item == null) {
                continue;
            }
            s.status = item.optString("status", LxSource.STATUS_UNTESTED);
            s.detail = item.optString("detail", "");
            s.hasRequestHandler = item.optBoolean("handler", false);
            s.platforms.clear();
            org.json.JSONArray platforms = item.optJSONArray("platforms");
            if (platforms == null) {
                continue;
            }
            for (int i = 0; i < platforms.length(); i++) {
                org.json.JSONObject po = platforms.optJSONObject(i);
                if (po == null) {
                    continue;
                }
                LxSource.Platform p = new LxSource.Platform();
                p.id = po.optString("id", "");
                p.name = po.optString("name", "");
                org.json.JSONArray actions = po.optJSONArray("actions");
                if (actions != null) {
                    for (int j = 0; j < actions.length(); j++) {
                        p.actions.add(actions.optString(j));
                    }
                }
                org.json.JSONArray qualities = po.optJSONArray("qualities");
                if (qualities != null) {
                    for (int j = 0; j < qualities.length(); j++) {
                        p.qualities.add(qualities.optString(j));
                    }
                }
                s.platforms.add(p);
            }
        }
    }

    public static boolean remove(Context context, LxSource source) {        if (source == null) {
            return false;
        }
        File f = new File(dir(context), source.fileName);
        return !f.exists() || f.delete();
    }

    /** 外部目录里能扫到、但还没导入的脚本（用于「发现新音源」提示） */
    public static List<File> discover(Context context) {
        List<File> found = new ArrayList<>();
        File[] roots = {
                new File("/sdcard/Download"),
                new File("/sdcard/Downloads"),
                new File("/storage/emulated/0/Download"),
        };
        File dest = dir(context);
        for (File root : roots) {
            File[] files = root.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (!f.isFile() || !f.getName().toLowerCase().endsWith(".js")) {
                    continue;
                }
                // 只认头部带 @name 的，免得把一堆无关 js 全列出来
                String head = readHead(f);
                if (head == null || !head.contains("@name")) {
                    continue;
                }
                File already = new File(dest, f.getName());
                if (already.exists() && already.length() == f.length()) {
                    continue;
                }
                found.add(f);
            }
        }
        return found;
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}

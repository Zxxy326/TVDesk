package com.github.catvod.music;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 LX 规范的音乐音源脚本（.js）。
 *
 * <h3>元数据从哪来</h3>
 * 头部注释块，例如：
 * <pre>
 * /*!
 *  * @name 墨澜聚合音源
 *  * @description 全平台支持flac
 *  * @version 2.3.4
 *  * @author 白姬9527
 *  * @homepage https://github.com/baiji6/molanyinyueyuan
 *  *&#47;
 * </pre>
 * 这些字段是纯文本，不执行脚本就能拿到，所以「导入即可见」不需要 JS 引擎。
 * 真正跑起来（拿到它支持哪些平台、每个平台支持哪些音质）必须执行脚本，
 * 那部分在 {@link LxRuntime} 里做。
 */
public class LxSource {

    /** 落盘后的文件名，同时作为唯一标识 */
    public String fileName = "";

    // ---- 来自头部注释 ----
    public String name = "";
    public String version = "";
    public String author = "";
    public String description = "";
    public String homepage = "";
    public String update = "";

    // ---- 文件信息 ----
    public long size;
    public long modified;

    /** 用户是否启用。禁用的源不参与搜索 */
    public boolean enabled = true;

    // ---- 运行结果（每次测试都会覆盖）----

    /** 未测试 / 正常 / 失败 */
    public String status = STATUS_UNTESTED;
    /** 失败原因，或成功时的补充说明 */
    public String detail = "";

    public final List<Platform> platforms = new ArrayList<>();

    /** 脚本是否注册了 request 处理器（能取播放链接） */
    public boolean hasRequestHandler;

    public static final String STATUS_UNTESTED = "未测试";
    public static final String STATUS_OK = "正常";
    public static final String STATUS_FAIL = "失败";

    /** 脚本声明支持的一个平台（网易云 / QQ音乐 / 酷我 …） */
    public static class Platform {
        /** 脚本里的 key：wy / tx / kw / kg / mg … */
        public String id = "";
        /** 展示名。脚本给了 name 就用脚本的，没给就用内置映射 */
        public String name = "";
        /** 支持的动作：musicUrl（取播放链接）等 */
        public final List<String> actions = new ArrayList<>();
        /** 支持音质：128k / 320k / flac / flac24bit … */
        public final List<String> qualities = new ArrayList<>();
        /** 取链接探测的结果，空表示还没测 */
        public String probeResult = "";
    }

    /**
     * LX 平台 key → 中文名。
     * 脚本里 sources 的每一项都会带 name，但有些源只写了 key，兜底用这个表。
     */
    private static final Map<String, String> PLATFORM_NAMES = new LinkedHashMap<>();

    static {
        PLATFORM_NAMES.put("wy", "网易云音乐");
        PLATFORM_NAMES.put("tx", "QQ音乐");
        PLATFORM_NAMES.put("qq", "QQ音乐");
        PLATFORM_NAMES.put("kw", "酷我音乐");
        PLATFORM_NAMES.put("kg", "酷狗音乐");
        PLATFORM_NAMES.put("mg", "咪咕音乐");
        PLATFORM_NAMES.put("local", "本地音乐");
        PLATFORM_NAMES.put("git", "自定义源");
    }

    public static String platformName(String id) {
        String name = PLATFORM_NAMES.get(id);
        return TextUtils.isEmpty(name) ? id : name;
    }

    /** 音质 key → 展示名 */
    public static String qualityName(String q) {
        if (TextUtils.isEmpty(q)) {
            return "";
        }
        switch (q) {
            case "128k":
                return "标准 128K";
            case "320k":
                return "高品 320K";
            case "flac":
                return "无损 FLAC";
            case "flac24bit":
                return "Hi-Res 24bit";
            case "192k":
                return "192K";
            case "48k":
                return "48K";
            default:
                return q;
        }
    }

    /** 标题行用：有 name 用 name，没有就退回文件名 */
    public String displayName() {
        if (!TextUtils.isEmpty(name)) {
            return name;
        }
        return TextUtils.isEmpty(fileName) ? "未命名音源" : fileName;
    }

    /** 副标题行用：「v2.3.4 · 白姬9527」 */
    public String displayMeta() {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(version)) {
            sb.append("v").append(version);
        }
        if (!TextUtils.isEmpty(author)) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(author);
        }
        return sb.toString();
    }

    /** 平台数量摘要：「5 个平台 · 15 种音质」 */
    public String platformSummary() {
        if (platforms.isEmpty()) {
            return "";
        }
        int qualityCount = 0;
        for (Platform p : platforms) {
            qualityCount += p.qualities.size();
        }
        return platforms.size() + " 个平台 · " + qualityCount + " 种音质";
    }
}

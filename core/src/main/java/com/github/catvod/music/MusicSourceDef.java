package com.github.catvod.music;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个「能出播放链接的音源」。
 *
 * <h3>为什么要有这层抽象</h3>
 * 取链的途径越来越多：网易云官方接口、已安装的 JS 脚本、用户手动指定的源。
 * 它们的能力不一样（能不能搜、能不能出歌词、能不能出封面、支持哪些音质），
 * 但调用方只想说「给我这首歌的链接」，不想知道背后是谁干的。
 * 所以这里把三者统一成同一种描述：{@code id} + 能力清单。
 *
 * <h3>id 的形状</h3>
 * <ul>
 *   <li>{@code wy} —— 内置网易云官方源；</li>
 *   <li>{@code lx:文件名} —— 已安装的 JS 脚本源；</li>
 *   <li>{@code manual:平台} —— 手动指定的平台（按平台挑脚本）。</li>
 * </ul>
 */
public final class MusicSourceDef {

    /** 内置源类型：网易云官方 */
    public static final String TYPE_BUILTIN = "builtin";
    /** JS 脚本源 */
    public static final String TYPE_JS = "js";

    public String id = "";
    /** 展示名 */
    public String name = "";
    /** {@link #TYPE_BUILTIN} 或 {@link #TYPE_JS} */
    public String type = TYPE_BUILTIN;
    /** 平台 key：wy / tx / kw / kg / mg */
    public String platform = "wy";
    /** JS 源对应的脚本文件名（{@link #TYPE_JS} 才有） */
    public String fileName = "";
    /** 这个源支持哪些音质 */
    public final List<String> qualities = new ArrayList<>();
    /** 能不能按平台搜歌 */
    public boolean supportsSearch;
    /** 能不能出歌词 */
    public boolean supportsLyric;
    /** 能不能出封面 */
    public boolean supportsPic;
    /** 内置源附带的说明（官方接口 / 落雪引擎） */
    public String note = "";

    public MusicSourceDef() {
    }

    public static MusicSourceDef builtinWy() {
        MusicSourceDef def = new MusicSourceDef();
        def.id = "wy";
        def.name = "网易云（官方）";
        def.type = TYPE_BUILTIN;
        def.platform = "wy";
        def.supportsSearch = true;
        def.supportsLyric = true;
        def.supportsPic = true;
        def.note = "官方接口 + eapi 兜底";
        def.qualities.add("320k");
        def.qualities.add("128k");
        return def;
    }

    public static MusicSourceDef js(LxSource source, LxSource.Platform platform) {
        MusicSourceDef def = new MusicSourceDef();
        def.id = "lx:" + source.fileName;
        def.name = source.displayName();
        def.type = TYPE_JS;
        def.fileName = source.fileName;
        def.platform = platform == null ? "wy" : platform.id;
        if (platform != null) {
            def.qualities.addAll(platform.qualities);
            def.supportsSearch = platform.actions.contains("search");
            def.supportsLyric = platform.actions.contains("lyric");
            def.supportsPic = platform.actions.contains("pic");
        }
        def.note = def.platform + " · " + source.displayMeta();
        return def;
    }

    /** 展示副标题：平台 + 能力 */
    public String displayMeta() {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(platform)) {
            sb.append(LxSource.platformName(platform));
        }
        if (supportsSearch) {
            sb.append(" · 可搜索");
        }
        if (supportsLyric) {
            sb.append(" · 可出歌词");
        }
        if (supportsPic) {
            sb.append(" · 可出封面");
        }
        if (!qualities.isEmpty()) {
            sb.append(" · ").append(qualities.size()).append(" 种音质");
        }
        return sb.toString();
    }

    public boolean isJs() {
        return TYPE_JS.equals(type);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MusicSourceDef)) {
            return false;
        }
        return id.equals(((MusicSourceDef) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

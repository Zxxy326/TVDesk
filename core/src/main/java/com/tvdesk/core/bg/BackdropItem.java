package com.tvdesk.core.bg;

import com.github.tvbox.osc.bean.Movie;

/**
 * 背景墙的一条内容。
 *
 * <p>影视和 B 站热门混在同一个队列里，所以这里不直接存 {@link Movie.Video}：
 * B 站条目根本没有 {@code vod_id} / 播放地址，硬塞进影视条目会被下游当成「可播放的影视」，
 * 点一下就去影视源里搜一个不存在的片子。
 *
 * <p>两种来源各带一个「载体」：影视条目带回原始 {@link Movie.Video}，点击时按原路进详情；
 * B 站条目只有 {@code bvid}，点击时进 B 站详情页（见 {@code DeskActivity#openVideo}）。
 * 两边都不冒充对方。
 */
public class BackdropItem {

    /** 影视（首页榜单 / 类目 / 海报墙来的条目） */
    public static final int KIND_MOVIE = 1;
    /** B 站热门投稿 */
    public static final int KIND_BILI = 2;
    /** 抖音推荐流 */
    public static final int KIND_DOUYIN = 3;

    /** B 站条目的 {@code sourceKey} 标记。影视源的 key 来自配置，不会等于这个值 */
    public static final String BILI_SOURCE_KEY = "bili";
    /** 抖音条目的 {@code sourceKey} 标记 */
    public static final String DOUYIN_SOURCE_KEY = "douyin";

    public int kind = KIND_MOVIE;

    /** 去重用的稳定键：{@code movie:<sourceKey>:<id>} 或 {@code bili:<bvid>} */
    public String key = "";

    /** 影视条目本体；B 站条目为合成出来的展示用条目，不含可播地址 */
    public Movie.Video video;

    /** 横版背景图（影视的 {@code vod_pic_slide} / B 站封面），可能为空 */
    public String image = "";
    /** 竖版海报，没有横图时兜底 */
    public String poster = "";

    /** B 站：投稿号 */
    public String bvid = "";
    /** B 站：分 P id，0 表示还没补 */
    public long cid;
    /** B 站：时长（秒） */
    public long durationSec;

    /**
     * 抖音：已经解析好的播放地址。
     *
     * <p>和 B 站、影视都不一样——{@code DouyinClient} 的推荐流里每条都带着现成的 URL，
     * 不用再拉详情、解析，拿到就能播。地址有时效，所以只在内存里放几十分钟。
     */
    public String playUrl = "";

    /** 原图实际像素。加载时回填，用来判断「够不够当 4K 背景」 */
    public int srcWidth;
    public int srcHeight;
    public boolean pixelKnown;

    public boolean isBili() {
        return kind == KIND_BILI;
    }

    public boolean isDouyin() {
        return kind == KIND_DOUYIN;
    }

    /** 展示标题：影视用片名，B 站用投稿标题 */
    public String title() {
        return video == null || video.name == null ? "" : video.name;
    }

    /** 这条有没有可播的视频身份 */
    public boolean hasVideo() {
        if (isDouyin()) {
            return !playUrl.isEmpty();
        }
        return isBili() && bvid != null && !bvid.isEmpty();
    }

    /** 严格 4K：原图至少 3840×2160 */
    public boolean isQuadHd() {
        return pixelKnown && srcWidth >= 3840 && srcHeight >= 2160;
    }

    /** 够当 1080p 背景：至少 1920 宽 */
    public boolean isFullHd() {
        return !pixelKnown || srcWidth >= 1920;
    }

    /** 原图像素记进日志用的一句话 */
    public String pixelText() {
        return pixelKnown ? (srcWidth + "x" + srcHeight) : "未探测";
    }
}

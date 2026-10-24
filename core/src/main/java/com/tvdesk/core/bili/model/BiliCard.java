package com.tvdesk.core.bili.model;

/**
 * 一张视频卡片。列表接口（热门 / 排行 / 推荐 / 搜索 / 收藏 / 历史）字段名不统一，
 * 在 {@code BiliClient} 里归一到这一个结构，UI 层只认它。
 */
public class BiliCard {
    public long aid;
    public String bvid = "";
    public long cid;
    /** 收藏夹条目才有（{@code favFolders}），点开时当 media_id 用 */
    public long favId;

    public String title = "";
    public String cover = "";
    public String up = "";
    public long upMid;
    public long durationSec;
    /** 观看历史才有：看到哪了，用来在角标上写「看到 12:34」 */
    public long progressSec;
    public long views;
    public long danmaku;
    /** 右上角小标签（分区名 / 「N 个视频」/「看到 mm:ss」） */
    public String badge = "";
    /** 来自哪个版块，只用于日志和埋点 */
    public String section = "";

    public boolean isFolder() {
        return favId > 0;
    }
}

package com.tvdesk.core.bili.model;

import java.util.ArrayList;
import java.util.List;

/** 视频详情（{@code /x/web-interface/view/detail}）。分 P 和合集都归到 {@link #episodes}。 */
public class BiliDetail {
    public long aid;
    public String bvid = "";
    /** pages 为空时的兜底 cid */
    public long cid;

    public String title = "";
    public String desc = "";
    public String cover = "";

    public String up = "";
    public long upMid;
    public String upFace = "";
    /** 稿件标签（view/detail 的 Tags），详情页只展示前两个 */
    public final List<String> tags = new ArrayList<>();

    public long views;
    public long danmaku;
    public long replies;
    public long favorites;
    public long likes;

    public long pubdate;
    public long durationSec;

    public final List<BiliEpisode> episodes = new ArrayList<>();

    public BiliEpisode first() {
        return episodes.isEmpty() ? null : episodes.get(0);
    }
}

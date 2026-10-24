package com.tvdesk.core.bili.model;

/** 一个可播的分段：单 P 视频只有一条，合集/多 P 每条对应一个 cid。 */
public class BiliEpisode {
    public long cid;
    /** 合集里的分集可能属于别的 bvid，所以每条自己带 */
    public String bvid = "";
    public int index;
    public String part = "";
    public long durationSec;
}

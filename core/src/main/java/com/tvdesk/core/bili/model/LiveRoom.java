package com.tvdesk.core.bili.model;

import java.util.ArrayList;
import java.util.List;

/** 直播间。列表里只有封面和人气，进房后再取 {@link #urls}。 */
public class LiveRoom {
    public long id;
    public long roomId;
    public long uid;
    public String title = "";
    public String cover = "";
    public String anchor = "";
    public String anchorFace = "";
    public String areaName = "";
    public long watchCount;
    /** 0 未开播 / 1 直播中 / 2 轮播 */
    public int liveStatus;
    public int currentQn;
    public final List<String> urls = new ArrayList<>();
    public final List<BiliStream.Quality> qualities = new ArrayList<>();
}

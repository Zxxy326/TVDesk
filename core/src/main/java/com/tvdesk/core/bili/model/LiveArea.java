package com.tvdesk.core.bili.model;

import java.util.ArrayList;
import java.util.List;

/** 直播分区。一级带 {@link #children}，二级带 {@link #parentId}。 */
public class LiveArea {
    public int id;
    public int parentId;
    public String name = "";
    public String cover = "";
    public final List<LiveArea> children = new ArrayList<>();
}

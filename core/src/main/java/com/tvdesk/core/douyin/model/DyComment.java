package com.tvdesk.core.douyin.model;

/** 一级评论。抖音的楼中楼在 {@code reply} 里，首版不展开。 */
public class DyComment {

    public String cid = "";
    public String user = "";
    public String avatar = "";
    public String text = "";
    public long digg;
    public long createdAt;
    /** 图片评论的地址，没有就是空 */
    public String image = "";
}

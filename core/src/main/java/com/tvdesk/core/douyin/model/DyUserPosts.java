package com.tvdesk.core.douyin.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 作者主页的一页作品。
 *
 * <p>分页参数跟着接口走：{@code max_cursor} 是下一页的游标，{@code has_more} 是服务端说的
 * 「还有没有」。两个都留着，主页才能像网页那样往下翻，而不是只给最近十几条。
 */
public class DyUserPosts {

    public final List<DyItem> items = new ArrayList<>();
    public long cursor;
    public boolean hasMore;
    /**
     * 服务端实际给了几条（含图文作品）。
     *
     * <p>{@link #items} 里只有能播的视频——图集没有 {@code play_addr}，早被过滤掉了。
     * 两个数一对比，界面才分得清「接口没给」和「给了，但都是图文」。
     */
    public int rawCount;
}

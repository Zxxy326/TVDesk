package com.tvdesk.core.bg;

import android.content.Context;
import android.text.TextUtils;

import com.github.tvbox.osc.bean.Movie;
import com.tvdesk.core.bili.BiliClient;
import com.tvdesk.core.bili.model.BiliCard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * B 站热门 → 背景条目。
 *
 * <p>热门榜单半小时到一小时才动一次，按方案第 4.6 条「间隔 30～60 分钟刷新」缓存，
 * 换背景不触发重新拉榜：沉浸态每 8 秒切一条，要是每次都去问接口，
 * 既浪费也不稳（这个接口偶发风控会返回空列表）。
 */
public class BiliBackdropSource {

    private static final long TTL_MS = 45 * 60 * 1000L;
    /** 一次取多少条。热门列表本身有去重，20 条足够一轮 14 条的池子 */
    private static final int WANT = 20;

    private static List<BackdropItem> cache;
    private static long cacheAt;

    /** 缓存还新鲜吗 */
    public static synchronized boolean fresh() {
        return cache != null && !cache.isEmpty() && System.currentTimeMillis() - cacheAt < TTL_MS;
    }

    /** 现成的缓存；过期或没拉过返回空列表（调用方自行决定要不要异步补） */
    public static synchronized List<BackdropItem> cached() {
        return cache == null ? new ArrayList<BackdropItem>() : cache;
    }

    /**
     * 拉一次热门。
     *
     * <p>在后台线程调用；失败抛 {@link IOException}，调用方保留上一次的缓存即可。
     * 网络或风控导致的空列表不当成「拉到了空」：那样会把已经混进队列的 B 站条目清掉。
     */
    public static synchronized List<BackdropItem> refresh(Context context) throws IOException {
        List<BiliCard> cards = BiliClient.get(context).popular(1, WANT);
        List<BackdropItem> items = new ArrayList<>();
        if (cards != null) {
            for (BiliCard card : cards) {
                BackdropItem item = of(card);
                if (item != null) {
                    items.add(item);
                }
            }
        }
        if (!items.isEmpty()) {
            cache = items;
            cacheAt = System.currentTimeMillis();
        }
        return items;
    }

    /** 一张热门卡片 → 背景条目。封面是横图（16:10 左右），铺满整屏不用裁太多 */
    public static BackdropItem of(BiliCard card) {
        if (card == null || TextUtils.isEmpty(card.bvid)) {
            return null;
        }
        if (TextUtils.isEmpty(card.cover)) {
            return null;
        }
        BackdropItem item = new BackdropItem();
        item.kind = BackdropItem.KIND_BILI;
        item.key = "bili:" + card.bvid;
        item.bvid = card.bvid;
        item.cid = card.cid;
        item.durationSec = card.durationSec;
        item.image = card.cover;
        item.poster = card.cover;

        // 合成一个「只用来展示」的条目。sourceKey 打上 bili 标记，
        // 点击时 DeskActivity 据此转去 B 站详情，不会拿去影视源里搜。
        Movie.Video video = new Movie.Video();
        video.name = card.title;
        video.pic = card.cover;
        video.picSlide = card.cover;
        video.sourceKey = BackdropItem.BILI_SOURCE_KEY;
        video.id = card.bvid;
        video.type = "B站热门";
        video.des = describe(card);
        item.video = video;
        return item;
    }

    /** 简介行：UP 主 · 时长 · 平台，让人一眼看出这不是影视条目 */
    private static String describe(BiliCard card) {
        StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(card.up)) {
            builder.append("UP 主 ").append(card.up);
        }
        if (card.durationSec > 0) {
            if (builder.length() > 0) {
                builder.append(" \u00b7 ");
            }
            long minutes = card.durationSec / 60;
            long seconds = card.durationSec % 60;
            builder.append(minutes).append(':')
                    .append(seconds < 10 ? "0" : "").append(seconds);
        }
        if (builder.length() > 0) {
            builder.append(" \u00b7 ");
        }
        builder.append("B 站热门");
        return builder.toString();
    }
}

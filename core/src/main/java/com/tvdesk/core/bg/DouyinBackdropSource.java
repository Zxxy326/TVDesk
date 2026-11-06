package com.tvdesk.core.bg;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.github.tvbox.osc.bean.Movie;
import com.tvdesk.core.douyin.DouyinClient;
import com.tvdesk.core.douyin.model.DyItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 抖音推荐流 → 屏保条目。
 *
 * <p>用抖音模块同一套推荐（{@code DouyinClient.feed}，平板口径），好处是每条都带着
 * 现成的播放地址，不用再解析；{@code refresh_index} 每次递增，服务端当「第几次下拉」，
 * 回来的就是新的一批——正好对上「别老是那几条」。
 *
 * <p><b>只留横屏</b>：竖屏铺到 16:9 屏上两边要裁掉一大半，只剩一条人像。
 * 这里在入池前就按 {@code width > height} 过滤掉，不带进队列。
 */
public final class DouyinBackdropSource {

    private static final String TAG = "DouyinBackdrop";

    /** 推荐流的地址有时效，半小时换一批就够 */
    private static final long TTL_MS = 30 * 60 * 1000L;
    /** 一次要多少条：横屏比例不高，多要一点才有得挑 */
    private static final int WANT = 30;

    private static List<BackdropItem> cache;
    private static long cacheAt;

    private DouyinBackdropSource() {
    }

    public static synchronized boolean fresh() {
        return cache != null && !cache.isEmpty() && System.currentTimeMillis() - cacheAt < TTL_MS;
    }

    public static synchronized List<BackdropItem> cached() {
        return cache == null ? new ArrayList<BackdropItem>() : cache;
    }

    /**
     * 拉一批推荐。
     *
     * <p>在后台线程调用。异常往外抛，调用方保留上一批缓存——
     * 抖音接口偶尔要 ttwid 或风控，失败不该把已经能放的内容清空。
     */
    public static synchronized List<BackdropItem> refresh(Context context) throws IOException {
        DouyinClient client = DouyinClient.get(context);
        List<BackdropItem> out = new ArrayList<>();
        int total = 0;
        int portrait = 0;
        StringBuilder seen = new StringBuilder();
        // 一次 feed 只回个位数（实测 2 条），多拉几轮才有得挑：
        // refresh_index 每次递增，服务端当「又下拉了一次」，回来的不是同一批
        for (int round = 0; round < 4 && out.size() < 16; round++) {
            List<DyItem> items;
            try {
                items = client.feed(WANT, seen.toString());
            } catch (Throwable th) {
                Log.i(TAG, "第 " + (round + 1) + " 轮推荐失败：" + th.getMessage());
                break;
            }
            if (items == null || items.isEmpty()) {
                break;
            }
            total += items.size();
            for (DyItem dy : items) {
                if (dy == null || TextUtils.isEmpty(dy.id)) {
                    continue;
                }
                if (seen.indexOf(dy.id) >= 0) {
                    continue;
                }
                if (seen.length() > 0) {
                    seen.append(',');
                }
                seen.append(dy.id);
                if (TextUtils.isEmpty(dy.playUrl) || dy.width <= 0 || dy.height <= 0) {
                    continue;
                }
                if (dy.height >= dy.width) {
                    portrait++;
                    continue;
                }
                if (TextUtils.isEmpty(dy.cover)) {
                    continue;
                }
                out.add(of(dy));
            }
        }
        if (!out.isEmpty()) {
            cache = out;
            cacheAt = System.currentTimeMillis();
        }
        Log.i(TAG, "推荐流共 " + total + " 条，横屏入池 " + out.size()
                + " 条，竖屏跳过 " + portrait + " 条");
        return out;
    }

    private static BackdropItem of(DyItem dy) {
        BackdropItem item = new BackdropItem();
        item.kind = BackdropItem.KIND_DOUYIN;
        item.key = "douyin:" + dy.id;
        item.playUrl = dy.playUrl;
        item.image = dy.cover;
        item.poster = dy.cover;
        item.durationSec = dy.durationMs / 1000L;
        item.srcWidth = dy.width;
        item.srcHeight = dy.height;
        item.pixelKnown = true;

        Movie.Video video = new Movie.Video();
        video.name = title(dy.desc);
        video.pic = dy.cover;
        video.picSlide = dy.cover;
        video.sourceKey = BackdropItem.DOUYIN_SOURCE_KEY;
        video.id = dy.id;
        video.type = "抖音";
        video.des = describe(dy);
        item.video = video;
        return item;
    }

    /** 抖音文案经常带一堆话题标签，取第一行、截短一点，免得 Hero 标题变三行 */
    private static String title(String desc) {
        if (TextUtils.isEmpty(desc)) {
            return "抖音视频";
        }
        String text = desc.replace('\n', ' ').trim();
        int hash = text.indexOf('#');
        if (hash > 6) {
            text = text.substring(0, hash).trim();
        }
        if (text.length() > 28) {
            text = text.substring(0, 28) + "…";
        }
        return text.isEmpty() ? "抖音视频" : text;
    }

    private static String describe(DyItem dy) {
        StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(dy.author)) {
            builder.append("@").append(dy.author);
        }
        long seconds = dy.durationMs / 1000L;
        if (seconds > 0) {
            if (builder.length() > 0) {
                builder.append(" \u00b7 ");
            }
            builder.append(seconds / 60).append(':')
                    .append(seconds % 60 < 10 ? "0" : "").append(seconds % 60);
        }
        if (builder.length() > 0) {
            builder.append(" \u00b7 ");
        }
        builder.append("抖音推荐");
        return builder.toString();
    }
}

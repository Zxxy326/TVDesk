package com.tvdesk.core.bg;

import com.github.tvbox.osc.bean.Movie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 背景轮播队列：影视和 B 站热门按 7:3 混在一起，每轮洗牌，一轮内不重复。
 *
 * <p>为什么要单独一个队列，而不是直接在首页 {@code items} 上加下标：
 * 海报行有条数上限（{@code ROW_LIMIT}），而背景池要按「适合铺满整屏的图有多少」来算；
 * 而且 B 站条目根本进不了 {@link Movie.Video} 列表。两者混在一个下标里，
 * 迟早会出现「背景是第 3 条、标题是第 5 条」这种对不上的情况。
 *
 * <p>洗牌规则对齐方案第 4.5 条：每轮重新洗，播完再洗；有两条以上时，
 * 新一轮的第一条不与上一轮的末条重复——否则沉浸态里会看到同一张图连着出现两次。
 */
public class BackdropQueue {

    /** 一轮多少条。8 秒一条大约两分钟，和「泡一杯茶回来看还是新的」这个体感对齐 */
    private static final int ROUND_SIZE = 14;
    /** 影视占比：方案默认 70% */
    private static final float MOVIE_RATIO = 0.7f;

    private final List<BackdropItem> movies = new ArrayList<>();
    private final List<BackdropItem> bili = new ArrayList<>();
    private final List<BackdropItem> round = new ArrayList<>();
    private final Random random = new Random();

    private int cursor = -1;
    private String lastKey = "";

    /**
     * 换一批影视条目。
     *
     * <p>传进来的是**截断前**的完整列表：海报行只铺前 N 条，背景池没这个限制。
     */
    public synchronized void setMovies(List<Movie.Video> list) {
        movies.clear();
        if (list == null) {
            resetRound();
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Movie.Video vod : list) {
            if (vod == null) {
                continue;
            }
            String key = keyOf(vod);
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            BackdropItem item = new BackdropItem();
            item.kind = BackdropItem.KIND_MOVIE;
            item.key = key;
            item.video = vod;
            item.image = vod.picSlide == null ? "" : vod.picSlide;
            item.poster = vod.pic == null ? "" : vod.pic;
            if (item.image.isEmpty() && item.poster.isEmpty()) {
                // 一张图都没有的条目放进来只会切出一屏黑
                continue;
            }
            movies.add(item);
        }
        resetRound();
    }

    /** 换一批 B 站热门 */
    public synchronized void setBili(List<BackdropItem> list) {
        bili.clear();
        if (list != null) {
            Set<String> seen = new HashSet<>();
            for (BackdropItem item : list) {
                if (item == null || item.bvid == null || item.bvid.isEmpty()) {
                    continue;
                }
                if (seen.add(item.key) && (!item.image.isEmpty() || !item.poster.isEmpty())) {
                    bili.add(item);
                }
            }
        }
        resetRound();
    }

    /** 影视条目的稳定键：有源 key + 内容 id 就用它们，否则退回片名 */
    public static String keyOf(Movie.Video vod) {
        if (vod == null) {
            return "";
        }
        String source = vod.sourceKey == null ? "" : vod.sourceKey.trim();
        String id = vod.id == null ? "" : vod.id.trim();
        if (!source.isEmpty() && !id.isEmpty()) {
            return "movie:" + source + ":" + id;
        }
        String name = vod.name == null ? "" : vod.name.trim();
        return name.isEmpty() ? "" : "movie:" + name;
    }

    public synchronized int size() {
        return movies.size() + bili.size();
    }

    public synchronized boolean isEmpty() {
        return size() == 0;
    }

    public synchronized int movieCount() {
        return movies.size();
    }

    public synchronized int biliCount() {
        return bili.size();
    }

    /** 沉浸态圆点用：当前在轮内的第几条 / 轮内一共几条 */
    public synchronized int roundIndex() {
        return Math.max(0, cursor);
    }

    public synchronized int roundSize() {
        return round.isEmpty() ? size() : round.size();
    }

    public synchronized BackdropItem current() {
        if (cursor < 0 || cursor >= round.size()) {
            return null;
        }
        return round.get(cursor);
    }

    /**
     * 下一条。
     *
     * @return 队列为空时返回 null；否则一定不是空条目
     */
    public synchronized BackdropItem next() {
        if (size() == 0) {
            return null;
        }
        if (round.isEmpty() || cursor + 1 >= round.size()) {
            buildRound();
        }
        if (round.isEmpty()) {
            return null;
        }
        cursor++;
        BackdropItem item = round.get(cursor);
        lastKey = item.key;
        return item;
    }

    /** 内容整批换过（切页签 / 换类目）时调用：换内容就别接着上一批的位置往下走 */
    public synchronized void resetRound() {
        round.clear();
        cursor = -1;
    }

    private void buildRound() {
        round.clear();
        int want = Math.min(ROUND_SIZE, movies.size() + bili.size());
        if (want <= 0) {
            cursor = -1;
            return;
        }
        List<BackdropItem> pickedMovies = pick(movies, Math.min(movies.size(),
                Math.round(want * MOVIE_RATIO)));
        List<BackdropItem> pickedBili = pick(bili, Math.min(bili.size(),
                want - pickedMovies.size()));
        // 某一池不够时用另一池补满，不为凑比例把轮里留空位
        if (pickedMovies.size() + pickedBili.size() < want) {
            pickedMovies = pick(movies, Math.min(movies.size(), want - pickedBili.size()));
        }
        if (pickedMovies.size() + pickedBili.size() < want) {
            pickedBili = pick(bili, Math.min(bili.size(), want - pickedMovies.size()));
        }
        round.addAll(pickedMovies);
        round.addAll(pickedBili);
        Collections.shuffle(round, random);
        if (round.size() > 1 && round.get(0).key.equals(lastKey)) {
            // 新一轮第一条和上一轮末条撞了：和第二条换一下，避免同一张图连播两次
            Collections.swap(round, 0, 1);
        }
        cursor = -1;
    }

    /** 从池子里随机挑 count 条（不改动原池顺序） */
    private List<BackdropItem> pick(List<BackdropItem> pool, int count) {
        List<BackdropItem> result = new ArrayList<>();
        if (count <= 0 || pool.isEmpty()) {
            return result;
        }
        List<BackdropItem> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, random);
        result.addAll(copy.subList(0, Math.min(count, copy.size())));
        return result;
    }
}

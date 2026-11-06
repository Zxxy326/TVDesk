package com.tvdesk.core.bg;

import com.github.tvbox.osc.bean.Movie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 屏保轮播队列：B 站热门 + 抖音推荐混排，完全随机、尽量不重复。
 *
 * <p>为什么不再放影视：影视的播放地址要么是网盘本地代理（403），要么得二次解析分享页，
 * 一条也播不出来；放进来只会让屏保里大半是静态图。首页的影视海报行照旧保留，
 * 想看片子走海报行，屏保就专心放「能动」的内容。
 *
 * <p>「尽量不重复」是两层：
 * <ul>
 *   <li>一轮内不重复（本来就是洗牌抽）；</li>
 *   <li>跨轮也记得最近播过的 {@link #RECENT_MAX} 条，下一轮优先挑没播过的，
 *       池子小的时候才会放宽——否则只有二十条时，第二轮必然和第一轮一模一样。</li>
 * </ul>
 */
public class BackdropQueue {

    /** 一轮多少条。10 秒一条大约两分半，和「放着当屏保」的节奏对齐 */
    private static final int ROUND_SIZE = 12;
    /** B 站占比：剩下给抖音 */
    private static final float BILI_RATIO = 0.5f;
    /** 记住最近播过的多少条，用来避开重复 */
    private static final int RECENT_MAX = 24;

    private final List<BackdropItem> bili = new ArrayList<>();
    private final List<BackdropItem> douyin = new ArrayList<>();
    private final List<BackdropItem> round = new ArrayList<>();
    /** 最近播过的 key，越靠后越新 */
    private final LinkedHashSet<String> recent = new LinkedHashSet<>();
    private final Random random = new Random();

    private int cursor = -1;
    private String lastKey = "";

    /**
     * 影视条目不再进屏保队列。
     *
     * <p>方法留着只是为了让调用点不用改；传什么都当空。
     */
    @Deprecated
    public synchronized void setMovies(List<Movie.Video> list) {
        // 屏保只放 B 站和抖音，影视条目不参与
    }

    /** 换一批 B 站热门 */
    public synchronized void setBili(List<BackdropItem> list) {
        bili.clear();
        addAll(bili, list);
        resetRound();
    }

    /** 换一批抖音推荐 */
    public synchronized void setDouyin(List<BackdropItem> list) {
        douyin.clear();
        addAll(douyin, list);
        resetRound();
    }

    private static void addAll(List<BackdropItem> pool, List<BackdropItem> list) {
        if (list == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (BackdropItem item : list) {
            if (item == null || item.key == null || item.key.isEmpty()) {
                continue;
            }
            if (item.image == null || item.image.isEmpty()) {
                continue;
            }
            if (seen.add(item.key)) {
                pool.add(item);
            }
        }
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
        return bili.size() + douyin.size();
    }

    public synchronized boolean isEmpty() {
        return size() == 0;
    }

    public synchronized int biliCount() {
        return bili.size();
    }

    public synchronized int douyinCount() {
        return douyin.size();
    }

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

    /** 看轮内接下来的第 offset 条（1 = 下一条），不移动游标 */
    public synchronized BackdropItem peek(int offset) {
        if (offset <= 0 || round.isEmpty()) {
            return null;
        }
        int index = cursor + offset;
        return index >= 0 && index < round.size() ? round.get(index) : null;
    }

    /**
     * 下一条。
     *
     * @return 队列为空时返回 null
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
        remember(item.key);
        return item;
    }

    /** 内容整批换过时调用 */
    public synchronized void resetRound() {
        round.clear();
        cursor = -1;
    }

    private void remember(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        recent.remove(key);
        recent.add(key);
        while (recent.size() > RECENT_MAX) {
            String oldest = recent.iterator().next();
            recent.remove(oldest);
        }
    }

    private void buildRound() {
        round.clear();
        int want = Math.min(ROUND_SIZE, bili.size() + douyin.size());
        if (want <= 0) {
            cursor = -1;
            return;
        }
        int wantBili = Math.min(bili.size(), Math.round(want * BILI_RATIO));
        int wantDouyin = Math.min(douyin.size(), want - wantBili);
        // 某一边不够时用另一边补满，不为凑比例留空位
        if (wantBili + wantDouyin < want) {
            wantBili = Math.min(bili.size(), want - wantDouyin);
        }
        if (wantBili + wantDouyin < want) {
            wantDouyin = Math.min(douyin.size(), want - wantBili);
        }
        round.addAll(pick(bili, wantBili));
        round.addAll(pick(douyin, wantDouyin));
        Collections.shuffle(round, random);
        if (round.size() > 1 && round.get(0).key.equals(lastKey)) {
            Collections.swap(round, 0, 1);
        }
        cursor = -1;
    }

    /**
     * 从池子里挑 count 条：优先挑最近没播过的，不够再从未过滤的里补。
     *
     * <p>这一步是「完全随机但不连着重复」的关键：池子只有二十条时，
     * 纯洗牌每轮都会把同样几条放进来；先排除最近播过的，手感才像「一直在换」。
     */
    private List<BackdropItem> pick(List<BackdropItem> pool, int count) {
        List<BackdropItem> result = new ArrayList<>();
        if (count <= 0 || pool.isEmpty()) {
            return result;
        }
        List<BackdropItem> fresh = new ArrayList<>();
        List<BackdropItem> stale = new ArrayList<>();
        for (BackdropItem item : pool) {
            if (recent.contains(item.key)) {
                stale.add(item);
            } else {
                fresh.add(item);
            }
        }
        Collections.shuffle(fresh, random);
        Collections.shuffle(stale, random);
        for (BackdropItem item : fresh) {
            if (result.size() >= count) {
                break;
            }
            result.add(item);
        }
        for (BackdropItem item : stale) {
            if (result.size() >= count) {
                break;
            }
            result.add(item);
        }
        return result;
    }
}

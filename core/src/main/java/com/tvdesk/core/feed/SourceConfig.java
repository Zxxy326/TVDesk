package com.tvdesk.core.feed;

import android.text.TextUtils;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HistoryHelper;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 内容源配置的读写。
 *
 * <p>「内容源」就是 TVBox 里说的「配置地址」：一个 json / xml / jpg 的接口地址，
 * TVBox 拉它拿到站点列表、类目、播放解析，桌面也拉它拿顶栏类目和海报。
 *
 * <h3>为什么要有这个类</h3>
 * 桌面的设置页和信号源页都需要「读当前源 / 选历史源 / 写入新源」，而这几件事都要碰 TVBox
 * 自己的存储（{@code Hawk} 里的 {@link HawkConfig#API_URL}）。如果让界面直接去读写 Hawk，
 * 「跟 TVBox 的接触点」就散落在 UI 代码里了——以后 TVBox 换存储、加字段，
 * 得满仓库找。收敛到这一个类里，UI 只调它的方法。
 *
 * <h3>写入之后谁负责生效</h3>
 * 这里<b>只写配置，不触发加载</b>。加载是 {@link SourceFeed} 的事，而「什么时候该重新加载」
 * 是桌面（{@code DeskActivity}）的事——它比对 {@link SourceFeed#currentApiUrl()} 和上次记下的
 * 值，发现变了就 {@code invalidateConfig()} 再重扫。
 *
 * <p>这样分工的好处：配源的地方不需要知道「配置怎么加载、jar 怎么挂、类目怎么取」，
 * 也不会出现「设置页和桌面各拉一遍配置」的重复请求。
 *
 * <h3>本类不做的两件事（有意为之）</h3>
 * <ul>
 *   <li><b>不校验地址能不能用。</b>校验等于把配置拉一遍（还可能连带着下 jar），
 *       那是几秒到几十秒的网络操作。设置页只负责把地址写进去，
 *       「这个源能不能出内容」由桌面回报——顶栏的提示条已经把
 *       「还没配置 / 源没响应 / 这个源没有可用类目」分开说了。</li>
 *   <li><b>不动直播源之外的任何设置。</b>见 {@link #apply(String)} 里关于
 *       {@link HawkConfig#LIVE_API_URL} 的说明。</li>
 * </ul>
 */
public final class SourceConfig {

    private SourceConfig() {
    }

    /**
     * 当前内容源地址，没配过就是空串。
     *
     * <p>直接复用 {@link SourceFeed#currentApiUrl()}：那个方法已经兜住了
     * 「Hawk 还没 init」这个在进程启动早期会真实发生的异常。
     */
    public static String current() {
        return SourceFeed.currentApiUrl();
    }

    /**
     * 用过的源，最近用的排前面。
     *
     * <p>读的是 TVBox 自己的历史（{@link HawkConfig#API_HISTORY}），所以用户以前在
     * TVBox 原版设置里填过的源也会出现在这里——不用重填一遍。
     *
     * <p>会做一次清洗：去空白、去重（保持顺序）。不做的话，如果历史里混进了空串，
     * 列表上会出现一行点不动的空条目。
     */
    public static List<String> history() {
        return cleanHistory(HawkConfig.API_HISTORY);
    }

    /**
     * 当前配置里的多线路。
     *
     * <p>TVBox 的多线路不是一份单独的清单：用户填的聚合配置里带 {@code urls} 时，
     * {@code ApiConfig} 会把它拆成「名字&lt;TAB&gt;地址」存进 {@link HawkConfig#API_LINE_LIST}，
     * 再自动切到第一条。这里把那份列表原样读出来给换源弹窗用，名字留着，
     * 界面才能显示「某某线路」而不是一串地址。
     *
     * <p>按地址去重：同一个线路被存两遍时只留第一条，免得弹窗里出现两个一模一样的选项。
     */
    public static List<String> lines() {
        List<String> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        try {
            List<String> raw = Hawk.get(HawkConfig.API_LINE_LIST, new ArrayList<String>());
            if (raw == null) {
                return result;
            }
            for (String item : raw) {
                if (item == null) {
                    continue;
                }
                String value = item.trim();
                String url = HistoryHelper.getApiLineUrl(value);
                if (url.isEmpty() || !seen.add(url)) {
                    continue;
                }
                result.add(value);
            }
        } catch (Throwable th) {
            // Hawk 还没 init 或存量的不是列表：当作没有多线路
        }
        return result;
    }

    /** 多线路来自哪个聚合配置地址。没有多线路（或还没加载过）时是空串。 */
    public static String lineSource() {
        try {
            String source = Hawk.get(HawkConfig.API_LINE_SOURCE, "");
            return source == null ? "" : source.trim();
        } catch (Throwable th) {
            return "";
        }
    }

    /**
     * 读一份源历史，清洗后返回（去空白、去重、保持「最近用的在前」）。
     *
     * <p>内容源和直播源共用这份逻辑：清洗规则只要两边不一致，同一个源在两个列表里
     * 就会一边显示一边不显示，那种不一致比多写一个方法贵得多。
     */
    private static List<String> cleanHistory(String key) {
        List<String> raw;
        try {
            raw = Hawk.get(key, new ArrayList<String>());
        } catch (Throwable th) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        // LinkedHashSet：既去重又保住「最近用的在前」的顺序
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        result.addAll(unique);
        return result;
    }

    /**
     * 把内容源写成 {@code url}。
     *
     * <p>做的事，与 TVBox 原版「设置 → 配置地址 → 确定」完全一致（见 {@code ApiDialog} +
     * {@code ModelSettingFragment} 里那个 {@code OnListener#onchange}）：
     * <ol>
     *   <li>写 {@link HawkConfig#API_URL} —— TVBox 的影视页和桌面读的都是它，
     *       所以这一步就等于「投送到 TVBox」。</li>
     *   <li>记进历史（{@link HistoryHelper#setApiHistory}），下次能在设置页直接选。</li>
     *   <li>跟着更新 {@link HawkConfig#LIVE_API_URL} —— 仅当它此前「跟着主接口走」时。</li>
     * </ol>
     *
     * <p><b>关于第 3 步</b>：TVBox 的直播接口是独立的一项，读取时如果它自己是空的就回落到
     * 主接口（见 {@code ApiConfig} 里 {@code Hawk.get(LIVE_API_URL, ...)} 后面那次回落）。
     * 于是有两种情况：用户从没单独设过直播源（此时它会跟着主接口自动走，不用管），
     * 或者用户单独设过（那是个有意的选择，不该被我们覆盖）。
     * 所以判据是「它当前等于旧的主接口地址」——等于就说明它是跟着走的，那就一起换；
     * 不等就说明是用户单独设的，留着他。
     *
     * <p>不做这一步的后果不是「直播不好用」这么轻：换完源之后 TVBox 的影视走新源、
     * 直播还挂在旧源上，用户会以为是新源没有直播。
     *
     * @return 是否真的改变了当前源。地址与原来相同（或为空白）时返回 false，
     *         调用方可以据此决定要不要提示「已应用」。
     */
    public static boolean apply(String url) {
        if (url == null) {
            return false;
        }
        String entry = url.trim();
        if (TextUtils.isEmpty(entry)) {
            return false;
        }
        // 历史和多线路里存的是「名字<TAB>地址」，真正能当配置地址用的是后半段。
        // 以前直接把整串写进 API_URL，换到一条带名字的线路时 TVBox 会把它当成无效地址。
        String value = HistoryHelper.getApiLineUrl(entry);
        if (TextUtils.isEmpty(value)) {
            value = entry;
        }
        String old = current();
        Hawk.put(HawkConfig.API_URL, value);
        HistoryHelper.setApiHistory(entry);

        // 直播源只在「此前跟随主接口」时才一起换，用户单独设过的保持不动
        try {
            String live = Hawk.get(HawkConfig.LIVE_API_URL, "");
            if (TextUtils.isEmpty(live) || live.trim().equals(old)) {
                Hawk.put(HawkConfig.LIVE_API_URL, value);
            }
        } catch (Throwable th) {
            // 直播这一项读不到不影响主流程——桌面只用主接口
        }
        return !value.equals(old);
    }

    /**
     * 清空内容源。
     *
     * <p>只清主接口，不动历史：用户接着就能在列表里把刚清掉的那个选回来。
     *
     * <p>清空后桌面不是「空白」，而是走回它自己的兜底——首页用豆瓣榜单出图、
     * 顶栏只剩「首页」、下面挂一条「还没配置内容源」的引导（见
     * {@code DeskActivity#showSourceMissing}）。
     */
    public static void clear() {
        Hawk.put(HawkConfig.API_URL, "");
    }

    // ------------------------------------------------------------------ 直播源

    /**
     * 当前直播源地址。
     *
     * <p><b>空串不是「没配」，而是「跟随内容源」</b>：TVBox 读这一项时如果它是空的，
     * 就回落到主接口（见 {@code ApiConfig}）。所以调用方要把空值说成「跟随内容源」，
     * 而不是给用户一句「你还没配直播源」。
     */
    public static String currentLive() {
        try {
            String live = Hawk.get(HawkConfig.LIVE_API_URL, "");
            return live == null ? "" : live.trim();
        } catch (Throwable th) {
            return "";
        }
    }

    /** 用过的直播源，最近用的排前面。清洗规则同 {@link #history()} */
    public static List<String> liveHistory() {
        return cleanHistory(HawkConfig.LIVE_API_HISTORY);
    }

    /**
     * 单独设一个直播源。
     *
     * <p>这一步之后它就不再跟随内容源了（{@link #apply(String)} 换主接口时，
     * 判据是「直播项当前等于旧主接口」才跟着换；现在它有了自己的值，就不会再被覆盖）。
     * 这正是用户点这一项想要的结果：内容源和直播源各用各的。
     *
     * @return 是否真的改变了当前直播源
     */
    public static boolean applyLive(String url) {
        if (url == null) {
            return false;
        }
        String value = url.trim();
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        String old = currentLive();
        Hawk.put(HawkConfig.LIVE_API_URL, value);
        HistoryHelper.setLiveApiHistory(value);
        return !value.equals(old);
    }

    /**
     * 让直播源重新跟随内容源（把这一项清空）。
     *
     * <p>信号源页上「单独设过直播源」之后要有一条退路，否则用户改错了只能再填一个别的地址，
     * 没法恢复成默认那种「跟主接口走」。
     */
    public static void clearLive() {
        Hawk.put(HawkConfig.LIVE_API_URL, "");
    }
}

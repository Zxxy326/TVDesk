package com.tvdesk.core.live;

import android.text.TextUtils;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 节目单（XMLTV）解析与查询。
 *
 * <h3>为什么按「台名」匹配而不是 tvg-id</h3>
 * tvg-id 各家写法不一致：直播源里的 {@code tvg-id="88"} 指的是源作者自己的编号，
 * EPG 里的 {@code channel id="5"} 是 EPG 提供方的编号，两边对不上是常态。
 * 而台名（{@code CCTV5}、{@code 湖南卫视}）两边都是人写的同一套叫法，
 * 归一化（大小写、空格、全角）之后命中率高得多。tvg-id 只当兜底。
 *
 * <h3>数据可能过期</h3>
 * 公共 EPG 源经常滞后一两天，甚至停更。所以查询是「按时间查」而不是「按台查了就显示」：
 * 此刻没有节目覆盖就返回空，界面上宁可少一行，也不要挂一条昨天的节目在上面。
 */
public final class Epg {

    private static final String TAG = "Epg";

    /** 台名（归一化）→ 节目单 */
    private final Map<String, EpgSchedule> byName = new HashMap<>();
    /** EPG 自己的 channel id（归一化）→ 节目单，tvg-id 兜底用 */
    private final Map<String, EpgSchedule> byId = new HashMap<>();
    /** 这份节目单什么时候拉的 */
    public long updatedAt;
    /** 数据来自哪份源，给诊断用 */
    public String source = "";
    /** 源里一共有多少条节目（没按时间窗过滤前）。诊断「源是不是滞后」用 */
    public int totalPrograms;
    /** 源里最晚一条节目的开始时间。比当前时间早太多就说明这份源过期了 */
    public long latestStart;

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    public int channelCount() {
        return byName.size();
    }

    /** 查一个频道的节目单：先按台名，再按 tvg-id */
    public EpgSchedule scheduleOf(LiveChannel channel) {
        if (channel == null) {
            return null;
        }
        EpgSchedule schedule = byName.get(LiveChannel.normalize(channel.name));
        if (schedule == null && !TextUtils.isEmpty(channel.epgId)) {
            schedule = byId.get(LiveChannel.normalize(channel.epgId));
        }
        return schedule;
    }

    void putName(String name, EpgSchedule schedule) {
        if (TextUtils.isEmpty(name) || schedule == null || schedule.items.isEmpty()) {
            return;
        }
        byName.put(LiveChannel.normalize(name), schedule);
    }

    void putId(String id, EpgSchedule schedule) {
        if (TextUtils.isEmpty(id) || schedule == null || schedule.items.isEmpty()) {
            return;
        }
        byId.put(LiveChannel.normalize(id), schedule);
    }

    Map<String, EpgSchedule> nameMap() {
        return byName;
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 解析一份 XMLTV。
     *
     * <p>流式读，不整份进内存：常见 EPG 一两兆、几万条节目，DOM 在电视盒子上很容易 OOM。
     *
     * @param windowFrom 早于这个时间的节目直接丢（默认保留 6 小时前，用来判断「正在播」）
     * @param windowTo   晚于这个时间的节目直接丢（默认 36 小时后）
     */
    public static Epg parse(InputStream in, long windowFrom, long windowTo) throws Exception {
        Epg epg = new Epg();
        Map<String, String> idToName = new HashMap<>();
        Map<String, EpgSchedule> schedules = new HashMap<>();

        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new InputStreamReader(in, "UTF-8"));

        String currentChannelId = null;
        boolean inProgramme = false;
        long start = 0;
        long stop = 0;
        String title = null;
        boolean inTitle = false;

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            switch (event) {
                case XmlPullParser.START_TAG:
                    if ("channel".equals(tag)) {
                        currentChannelId = parser.getAttributeValue(null, "id");
                    } else if ("display-name".equals(tag)) {
                        String name = parser.nextText().trim();
                        if (currentChannelId != null && name.length() > 0) {
                            idToName.put(currentChannelId, name);
                        }
                    } else if ("programme".equals(tag)) {
                        inProgramme = true;
                        title = null;
                        String channel = parser.getAttributeValue(null, "channel");
                        currentChannelId = channel;
                        start = parseTime(parser.getAttributeValue(null, "start"));
                        stop = parseTime(parser.getAttributeValue(null, "stop"));
                    } else if (inProgramme && "title".equals(tag)) {
                        inTitle = true;
                    }
                    break;
                case XmlPullParser.TEXT:
                    if (inProgramme && inTitle && title == null) {
                        title = parser.getText();
                    }
                    break;
                case XmlPullParser.END_TAG:
                    if ("title".equals(tag)) {
                        inTitle = false;
                    } else if ("programme".equals(tag)) {
                        inProgramme = false;
                        if (start > 0) {
                            epg.totalPrograms++;
                            if (start > epg.latestStart) {
                                epg.latestStart = start;
                            }
                        }
                        if (currentChannelId != null && start > 0 && stop > start
                                && stop >= windowFrom && start <= windowTo) {
                            EpgSchedule schedule = schedules.get(currentChannelId);
                            if (schedule == null) {
                                schedule = new EpgSchedule();
                                schedules.put(currentChannelId, schedule);
                            }
                            schedule.add(new EpgSchedule.Program(start, stop,
                                    TextUtils.isEmpty(title) ? "（无节目名）" : title));
                        }
                        title = null;
                    }
                    break;
                default:
                    break;
            }
            event = parser.next();
        }

        for (Map.Entry<String, EpgSchedule> entry : schedules.entrySet()) {
            String id = entry.getKey();
            EpgSchedule schedule = entry.getValue();
            schedule.sort();
            epg.putId(id, schedule);
            String name = idToName.get(id);
            if (!TextUtils.isEmpty(name)) {
                epg.putName(name, schedule);
            }
        }
        Log.i(TAG, "解析完成：总节目 " + epg.totalPrograms + " 条，落在时间窗内的频道 "
                + epg.byName.size() + " 个；源里最晚一条开始于 "
                + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new java.util.Date(
                epg.latestStart > 0 ? epg.latestStart : 0)));
        return epg;
    }

    /**
     * XMLTV 时间：{@code 20261108060000 +0800}。
     *
     * <p>没有时区后缀的（少数源）按本地时区算，算错也只是偏几小时，
     * 好过整份节目单因为格式不标准被丢掉。
     */
    private static long parseTime(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        String text = value.trim();
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
            return format.parse(text).getTime();
        } catch (Exception ignored) {
            // 没有时区：补一个本机默认时区再试
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            return format.parse(text).getTime();
        } catch (Exception ignored) {
            return 0;
        }
    }
}

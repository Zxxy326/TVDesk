package com.tvdesk.core.bili.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条可播的流。
 *
 * <p>B 站的返回有两类，这里都收进同一个结构，播放器按 {@link #kind} 分支：
 * <ul>
 *   <li>{@link #MP4}：{@link #urls} 是一条或多条整片 mp4（多 P 分片时按 order 排好）；</li>
 *   <li>{@link #DASH}：画面和声音是两条独立的 base_url，需要播放器合并。</li>
 * </ul>
 */
public class BiliStream {
    public static final int MP4 = 1;
    public static final int DASH = 16;

    public int kind = MP4;
    /** 实际选中的清晰度。DASH 下是选中视频流的 id，不是 data.quality */
    public int quality;
    /** 我们向它要的清晰度，两者不一致时界面要说明（大会员档位会被压下来） */
    public int requestedQn;
    public long timelength;

    public final List<String> urls = new ArrayList<>();

    public String dashVideo = "";
    public String dashAudio = "";
    public int dashVideoId;
    public long dashVideoDuration;
    public final List<String> dashVideoBackups = new ArrayList<>();
    public final List<String> dashAudioBackups = new ArrayList<>();

    public final List<Quality> qualities = new ArrayList<>();

    public static class Quality {
        public int qn;
        public String desc = "";
    }

    /** 清晰度码 → 人类可读名。接口没给 support_formats 时的兜底 */
    public static String nameOf(int qn) {
        switch (qn) {
            case 16:
                return "360P 流畅";
            case 32:
                return "480P 清晰";
            case 64:
                return "720P 准高清";
            case 80:
                return "1080P 高清";
            case 112:
                return "1080P+ 高码率";
            case 116:
                return "1080P60 帧";
            case 120:
                return "4K";
            case 125:
                return "HDR 真彩";
            default:
                return qn + "P";
        }
    }

    /** 当前这条流能不能播：DASH 要两条都在，mp4 要至少一段 */
    public boolean playable() {
        if (kind == MP4) {
            return !urls.isEmpty();
        }
        return !dashVideo.isEmpty();
    }
}

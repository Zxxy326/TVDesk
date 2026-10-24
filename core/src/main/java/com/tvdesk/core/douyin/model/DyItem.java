package com.tvdesk.core.douyin.model;

/**
 * 一条抖音作品，同时充当「卡片」和「播放项」。
 *
 * <p>字段名对齐抖音 web 接口自己的 JSON（{@code aweme_list[]} 里那几层），
 * 归一的工作放在 {@code DouyinClient}，UI 层只认这个结构。
 */
public class DyItem {

    public String id = "";
    /** 标题／文案，抖音的竖屏卡片没有独立标题，就是 desc */
    public String desc = "";
    public String cover = "";
    /** 作者点开时要用 sec_uid 拼用户页，数字 uid 反而没用 */
    public String author = "";
    public String authorUid = "";
    public String avatar = "";

    public long durationMs;
    public int width;
    public int height;

    /** 已经按「路径签名 → /aweme/v1/play/ → signature= → 首个」选好的播放地址，可能为空 */
    public String playUrl = "";
    /** 同一档码率下 H264 / H265 各一条时用来挑，见 {@code DouyinClient#resolvePlayUrl} */
    public String gearName = "";

    public long digg;
    public long comment;
    public long share;
    public long collect;
    /** 互动状态（登录后才有）：{@code status=1} 表示已点赞 */
    public int diggStatus;
    public int collectStatus;

    public String musicTitle = "";
    /** 合集／系列名，卡片右下角标签用 */
    public String mixName = "";

    public int awemeType;

    /** 竖屏判定：决定卡片是按 9:16 全屏还是按横屏信箱显示 */
    public boolean isVertical() {
        return height > width;
    }
}

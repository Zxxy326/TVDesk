package com.tvdesk.core.douyin.model;

/**
 * 一个抖音作者的主页资料（不是「我自己」——那是 {@link DyProfile}）。
 *
 * <p>字段只挑大屏上读得出的那几个：头像、昵称、抖音号、简介、粉丝/获赞/作品数。
 * 简介里塞的东西（商务合作、群号）在 10 尺外根本看不清，所以 UI 只给两行。
 */
public class DyUser {

    /** sec_uid：作者页、作品列表都用它，数字 uid 拼不出地址 */
    public String secUid = "";
    public String nickname = "";
    /** 抖音号，形如 {@code tvdesk_2026}；有的号没设，就是空串 */
    public String uniqueId = "";
    public String signature = "";
    public String avatar = "";
    /** IP 属地，有的版本给在 {@code ip_location} */
    public String ipLocation = "";

    public long follower;
    public long following;
    /** 获赞总数：接口给的是字符串，解析时已经转成数字 */
    public long totalFavorited;
    public long awemeCount;

    public boolean valid() {
        return !nickname.isEmpty();
    }
}

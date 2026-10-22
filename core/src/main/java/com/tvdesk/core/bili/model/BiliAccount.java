package com.tvdesk.core.bili.model;

/** nav 接口的账号部分。未登录时只有 {@link #loggedIn} 为 false，其余留空。 */
public class BiliAccount {
    public boolean loggedIn;
    public long mid;
    public String nickname = "";
    public String avatar = "";
    public int level;
    public boolean vip;
    public int fans;
}

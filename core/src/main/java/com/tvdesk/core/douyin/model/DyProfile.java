package com.tvdesk.core.douyin.model;

/** 当前 cookie 对应的那个账号。只用来回答「我登的是谁、这个 cookie 还认不认」。 */
public class DyProfile {

    public String uid = "";
    public String user = "";
    public String avatar = "";

    /** 抖音这个接口在没登录时也会回 200，只是 user 是空串，所以「有没有名字」就是结论 */
    public boolean valid() {
        return user != null && user.length() > 0;
    }
}

package com.tvdesk.core.douyin;

import java.io.IOException;

/**
 * 抖音接口被风控挡下来时抛的错。
 *
 * <p>分两类，UI 的处理方式完全不同，所以不能只抛一个 {@code IOException}：
 * <ul>
 *   <li>{@link #needsLogin} —— 缺的是登录 cookie（{@code UIFID} / {@code sessionid}）。
 *       表现是 403 {@code Blocked by ArgusSecurityPlugin Uifid Not Found}、
 *       {@code status_code=2483} 的空结果，或者响应头 {@code bd-tt-error-code: 8}
 *       （带着 cookie 来的，但那份已经不认了——过期就是走这一条）。
 *       这种必须把用户引到「去浏览器重新导一份」，而不是转圈之后报「失败」。</li>
 *   <li>其它 —— 网络、限流、接口改版。重试有可能好。</li>
 * </ul>
 */
public class DouyinException extends IOException {

    public final boolean needsLogin;

    public DouyinException(String message, boolean needsLogin) {
        super(message);
        this.needsLogin = needsLogin;
    }
}

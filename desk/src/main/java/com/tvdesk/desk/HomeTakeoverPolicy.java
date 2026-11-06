package com.tvdesk.desk;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

/**
 * 桌面接管的统一判定。
 *
 * <p>以前每个入口各写一套条件：窗口事件里判一次、亮屏复查判一次、连接后复查又是另一套，
 * 三处的宽松程度还不一样（连接复查会拿「强停前在不在桌面」这种旧标记放宽）。
 * 结果就是同一个场景下，走哪条路决定抢不抢，用户看到的是「有时候拉回来、有时候不拉」。
 *
 * <p>现在所有入口都只问这一个类：给证据、得到 ALLOW / DENY / RETRY 和一句人能读的原因。
 * 这里<b>不启动任何 Activity</b>——启动是调用方的事，判定必须能单测。
 */
public final class HomeTakeoverPolicy {

    /** 可以接管 */
    public static final int ALLOW = 1;
    /** 明确不接管 */
    public static final int DENY = 0;
    /** 信息不足，过一会儿再看（不要立刻拉前台） */
    public static final int RETRY = 2;

    /** 一轮恢复最多试几次，超过就熔断，等用户操作或明确的新事件 */
    private static final int MAX_RECOVER_ATTEMPTS = 4;

    public static final class Decision {
        public final int action;
        public final String reason;

        Decision(int action, String reason) {
            this.action = action;
            this.reason = reason;
        }

        public boolean allowed() {
            return action == ALLOW;
        }
    }

    /**
     * 判定所需的全部证据。
     *
     * <p>刻意都是普通字段：测试里 new 一个填好就能跑，不需要 mock 系统服务。
     */
    public static final class Evidence {
        /** 用户是否允许守护 */
        public boolean guardOn;
        /** 是否处于「临时进入原桌面」的放行期 */
        public boolean temporaryBypass;
        /** 屏幕是否处于可交互状态 */
        public boolean screenInteractive;
        /** 当前前台包名，未知为 null */
        public String foreground;
        /** 前台是不是原厂桌面（由 {@code HomeGuardService.isHomePackage} 判定） */
        public boolean foregroundIsHome;
        /** 本应用包名 */
        public String selfPackage;
        /** 这一轮恢复已经失败的次数 */
        public int recoverAttempts;
        /** 这个证据是什么时候取到的（毫秒）；0 表示没有证据 */
        public long evidenceAt;
        /** 当前时间（毫秒） */
        public long now;
    }

    private HomeTakeoverPolicy() {
    }

    /**
     * 判定顺序是有讲究的：先看「该不该管」，再看「管得着吗」，最后才看「现在是不是时候」。
     *
     * <p>顺序错了会出两类事故：先判前台再判意愿，会在用户关掉守护后仍然抢一次；
     * 先判节流再判前台，会把「用户主动按 HOME」这种明确意图也吞掉。
     */
    public static Decision decide(Context context, Evidence ev) {
        if (ev == null) {
            return new Decision(RETRY, "没有证据");
        }
        if (!ev.guardOn) {
            return new Decision(DENY, "守护已关闭");
        }
        if (ev.temporaryBypass) {
            return new Decision(DENY, "临时放行中");
        }
        if (ev.recoverAttempts >= MAX_RECOVER_ATTEMPTS) {
            return new Decision(DENY, "恢复已熔断，等用户操作");
        }
        if (!ev.screenInteractive) {
            return new Decision(DENY, "屏幕未就绪");
        }
        if (ev.foreground != null && ev.foreground.equals(ev.selfPackage)) {
            return new Decision(DENY, "已经在前台");
        }
        if (ev.foreground == null || ev.foreground.isEmpty()) {
            // 前台为空：可能是刚亮屏还没画出来，也可能是读数失败。两种情况都不该抢，
            // 但过一会儿再看是有意义的，所以给 RETRY 而不是 DENY
            return new Decision(RETRY, "前台未知");
        }
        if (ev.foregroundIsHome) {
            return new Decision(ALLOW, "原厂桌面");
        }
        return new Decision(DENY, "普通应用");
    }

    /** 屏幕现在是可交互的吗（API 20+ 用 isInteractive，更早只能看亮屏） */
    static boolean screenInteractive(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                return pm.isInteractive();
            }
            //noinspection deprecation
            return pm.isScreenOn();
        } catch (Throwable th) {
            return true;
        }
    }

    static int maxRecoverAttempts() {
        return MAX_RECOVER_ATTEMPTS;
    }
}

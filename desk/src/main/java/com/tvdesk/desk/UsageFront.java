package com.tvdesk.desk;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * 「现在前台是哪个 App」——不用无障碍的那条路。
 *
 * <h3>为什么要单独一份</h3>
 * 守护服务判断前台靠 {@code getRootInActiveWindow()}，那要无障碍绑定着才行。
 * 可最难的那一瞬（待机被强停后刚复活、系统桌面已经画出来）恰恰是<b>无障碍还没绑上</b>的时候，
 * 只有用量统计能立刻回答「前台是谁」。
 *
 * <p>Aerial 在 MiTV 上也是这么分的（{@code guard/UsageFront.kt}）：先用量用统计，
 * 拿不到再退回窗口事件。用量统计有 100–250ms 的延迟，所以调用方都要给一点余量。
 *
 * <h3>权限</h3>
 * 读用量统计要 {@code PACKAGE_USAGE_STATS}，这个权限用户点不出来，只能 adb
 * （{@code appops set <pkg> GET_USAGE_STATS allow}）。没有也<b>不会崩</b>：
 * {@link #foreground} 返回 null，调用方据此退回到原来的窗口事件判断。
 */
final class UsageFront {

    private static final String TAG = "DeskUsage";
    /** 往前看这么久的事件，够覆盖一次唤醒/一次切前台 */
    private static final long WINDOW_MS = 15_000L;

    private UsageFront() {
    }

    /** 现在有没有读用量统计的权限 */
    static boolean allowed(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), context.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), context.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 最近一次被拉到前台的包名。
     *
     * @return 拿不到（没权限、还没事件）时返回 {@code null}
     */
    static String foreground(Context context) {
        try {
            UsageStatsManager usm = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - WINDOW_MS, now);
            if (events == null) {
                return null;
            }
            UsageEvents.Event event = new UsageEvents.Event();
            String last = null;
            long lastAt = 0L;
            while (events.hasNextEvent()) {
                if (!events.getNextEvent(event)) {
                    break;
                }
                if (!isForegroundEvent(event.getEventType())) {
                    continue;
                }
                // 同一时刻可能有多条（切换过程中的中间态），取时间最晚的那条
                if (event.getTimeStamp() >= lastAt) {
                    lastAt = event.getTimeStamp();
                    last = event.getPackageName();
                }
            }
            return last;
        } catch (Throwable th) {
            Log.w(TAG, "读用量统计失败：" + th.getMessage());
            return null;
        }
    }

    private static boolean isForegroundEvent(int type) {
        if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
            return true;
        }
        // API 29 起改名 ACTIVITY_RESUMED，数值不变（1），这里显式写一下便于阅读
        return Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_RESUMED;
    }
}

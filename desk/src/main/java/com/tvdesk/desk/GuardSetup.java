package com.tvdesk.desk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/**
 * 把「桌面守护」这个无障碍服务自动打开。
 *
 * <h3>为什么要自己写而不是让用户去点</h3>
 * 电视上点「设置 → 无障碍 → 桌面守护 → 开启」这条路又长又难找，很多固件还把这一项藏在
 * 「更多设置」里。Aerial 的做法是装的时候用一条 adb 命令把服务直接写进系统设置
 * （{@code settings put secure enabled_accessibility_services …}），用户全程不用动手。
 * 这里照同样的思路：只要应用拿到了 {@code WRITE_SECURE_SETTINGS}（adb 授权过一次就有），
 * 启动时自己写。
 *
 * <p>没有这个权限（比如 U 盘装的、应用商店装的）就<b>什么都不做</b> ——
 * 不弹窗、不引导，把「开启守护」留在设置页里，用户想要的时候自己去点。
 *
 * <h3>为什么要「重绑」</h3>
 * 无障碍服务的配置（{@code accessibility_home.xml}）是在服务绑定时读的。升级安装后
 * 配置变了（比如这一版加了 HOME 键过滤），但系统不会自动重新绑定 —— 表现就是
 * 「守护开着，但按 HOME 还是回原桌面」。所以版本号一变就把服务从列表里摘掉、
 * 1.5 秒后再加回去，逼系统重新读一次配置。
 */
final class GuardSetup {

    private static final String TAG = "GuardSetup";
    /** 必须和清单里的服务组件名一致 */
    private static final String SERVICE = "com.tvdesk.app/com.tvdesk.desk.HomeGuardService";
    /** 摘掉服务到加回去之间的间隔：太短系统可能认为「没变」 */
    private static final long REBIND_DELAY_MS = 1500L;

    private GuardSetup() {
    }

    /** 必须和清单里的通知监听组件名一致 */
    private static final String LISTENER = "com.tvdesk.app/com.tvdesk.desk.RevivalListener";

    /**
     * 把「复活监听」写进系统设置。
     *
     * <p>待机时小米会把 TVDesk 进程杀掉、连无障碍绑定一起清空。通知监听是系统会主动
     * 重新绑定的一类服务（收到通知就拉起进程），Aerial 在 MiTV 上就是靠它复活的。
     *
     * <p>有 {@code WRITE_SECURE_SETTINGS} 时可以自己写，不用用户去设置里点。
     *
     * @return 已经在列表里（或刚写进去）
     */
    static boolean ensureNotificationListener(Context context) {
        if (!hasWriteSecureSettings(context)) {
            return RevivalListener.isEnabled(context);
        }
        try {
            String current = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            if (current == null) {
                current = "";
            }
            if (!current.contains(LISTENER)) {
                String next = current.isEmpty() ? LISTENER : current + ":" + LISTENER;
                Settings.Secure.putString(context.getContentResolver(),
                        "enabled_notification_listeners", next);
                Log.i(TAG, "已把复活监听写进系统设置");
            }
            // 只写设置字符串，系统那边的监听列表不一定跟上来 —— 待机时它会把「没人认领」
            // 的条目直接擦掉，复活链路就断了。requestRebind 是官方入口：让系统立刻
            // 重新绑定一次，绑上之后它才把这一条当自己的记录留住。
            requestListenerRebind(context);
            return true;
        } catch (Throwable th) {
            Log.w(TAG, "写通知监听设置失败：" + th.getMessage());
            return false;
        }
    }

    /**
     * 请系统立刻重新绑定复活监听。
     *
     * <p>Android 24 起有静态方法 {@code NotificationListenerService.requestRebind}，
     * 属主应用自己调用是允许的。不绑的话，{@code enabled_notification_listeners} 里
     * 有名字也只是「写着」，待机强停时会被一起抹掉（实测踩过）。
     */
    private static void requestListenerRebind(Context context) {
        if (android.os.Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                    new ComponentName(context, RevivalListener.class));
            Log.i(TAG, "已请求系统重绑复活监听");
        } catch (Throwable th) {
            Log.w(TAG, "请求重绑复活监听失败：" + th.getMessage());
        }
    }

    /**
     * 复活监听现在生效吗（设置页显示状态用）。
     *
     * <p><b>只读</b>。以前这里是 {@code isEnabled() || ensureNotificationListener()}——
     * 打开设置页看一眼状态，就顺手把通知监听权限给开了。状态查询不该有副作用，
     * 激活必须由用户点确认后单独调用。
     */
    static boolean notificationListenerReady(Context context) {
        return RevivalListener.isEnabled(context);
    }

    // ---------------------------------------------------------------- 保持 ADB

    /**
     * 唤醒后把 ADB 开关写回 1。
     *
     * <p>这台固件在待机/唤醒时会把 {@code global adb_enabled} 改成 0，网络调试随之断开，
     * 之后想连都连不上（Aerial 的 {@code keep_adb} 就是干这个的）。要
     * {@code WRITE_SECURE_SETTINGS}，没有就静默跳过。
     */
    static void keepAdbIfWanted(Context context) {
        if (!DeskPrefs.keepAdb(context) || !hasWriteSecureSettings(context)) {
            return;
        }
        try {
            int now = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0);
            if (now == 1) {
                return;
            }
            Settings.Global.putInt(context.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 1);
            Log.i(TAG, "ADB 被系统关掉了，已写回开启");
        } catch (Throwable th) {
            Log.w(TAG, "保持 ADB 失败：" + th.getMessage());
        }
    }

    /** ADB 现在开着吗（设置页显示状态用） */
    static boolean adbEnabled(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable th) {
            return false;
        }
    }

    /** 有没有写系统设置的权限（adb 授权过一次就一直有，除非恢复出厂） */
    static boolean hasWriteSecureSettings(Context context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                return context.checkSelfPermission(
                        android.Manifest.permission.WRITE_SECURE_SETTINGS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            // API 21/22 没有 Context.checkSelfPermission（那是 23 才有的）。
            // 直接调会抛 NoSuchMethod，被 catch 吃掉之后就变成「永远没授权」——
            // 于是 5.x 设备上「保持 ADB」永远不生效，还查不出原因
            return context.getPackageManager().checkPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    context.getPackageName()) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable th) {
            return false;
        }
    }

    /** 守护服务现在在系统的启用列表里吗 */
    static boolean isServiceEnabled(Context context) {
        return enabledServices(context).contains(SERVICE);
    }

    /** 无障碍总开关现在是不是开着 */
    static boolean accessibilityEnabled(Context context) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 守护真的可用吗：列表里有它 <b>并且</b> 无障碍总开关开着。
     *
     * <p>只看列表是不够的：实测踩过一次 —— 系统把 {@code accessibility_enabled} 改成 0，
     * 但 {@code enabled_accessibility_services} 里还留着我们的名字。当时的
     * {@link #ensureEnabled} 只判「名字在不在」，于是每次启动都以为「已经开着」直接返回，
     * 守护根本没绑上：按 HOME 直接回固件桌面，谁也拉不回来。
     */
    static boolean isGuardReady(Context context) {
        return isServiceEnabled(context) && accessibilityEnabled(context);
    }

    /** 服务真的被系统绑上了吗（设置页显示状态用） */
    static boolean isBound(Context context) {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (android.accessibilityservice.AccessibilityServiceInfo info
                    : am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info != null && SERVICE.equals(info.getId())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 读不到就当没绑上：设置页显示「已开启」，实际由 ensureEnabled 兜
        }
        return false;
    }

    /**
     * 确保守护是开着的。
     *
     * <p>要修的是两个东西，缺一个都不算开：<b>启用列表里的名字</b>和<b>无障碍总开关</b>。
     *
     * <p>总开关单独被判一次是有教训的：系统在服务崩掉或被清理之后，会把它改成 0，
     * 但列表里的名字还留着。只判名字的实现会认为「已经开着」，于是一直不去碰总开关，
     * 表现就是「守护开着，但按 HOME 回固件桌面」。
     *
     * <p>总开关从 0 变 1 时顺手把这一条摘掉再加回去：系统对「标记为 crashed」的服务
     * 不一定会重绑，摘一次能逼它重新读配置、重新绑。
     *
     * @return 真的开启了（或者本来就开着）；没权限 / 写失败返回 false
     */
    static boolean ensureEnabled(Context context) {
        if (!hasWriteSecureSettings(context)) {
            return false;
        }
        boolean listed = isServiceEnabled(context);
        boolean master = accessibilityEnabled(context);
        if (listed && master) {
            return true;
        }
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            String current = enabledServices(context);
            if (listed && !master) {
                // 名字在、总开关被关了：摘一次再放回去，逼系统重新绑
                Settings.Secure.putString(cr,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        removeService(current));
                Settings.Secure.putString(cr,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        addService(removeService(current)));
                Log.i(TAG, "无障碍总开关被关过，已重新登记守护");
            } else if (!listed) {
                Settings.Secure.putString(cr,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        addService(current));
                Log.i(TAG, "已把桌面守护写进系统设置");
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "桌面守护已开启（列表 " + listed + "，总开关 " + master + "）");
            return true;
        } catch (Throwable th) {
            Log.w(TAG, "写无障碍设置失败：" + th.getMessage());
            return false;
        }
    }

    private static String addService(String list) {
        if (TextUtils.isEmpty(list)) {
            return SERVICE;
        }
        if (list.contains(SERVICE)) {
            return list;
        }
        return list + ":" + SERVICE;
    }

    /**
     * 应用升级后强制重绑一次。
     *
     * <p>无障碍配置（比如 {@code canRequestFilterKeyEvents}）只在绑定时读一次，
     * 升级 APK 不会触发重读。这里把服务从启用列表里摘掉、过一会儿再加回去。
     *
     * @return 需要重绑并已经排上任务
     */
    static boolean rebindIfVersionChanged(Context context) {
        if (!hasWriteSecureSettings(context) || !isServiceEnabled(context)) {
            return false;
        }
        final int code = UpdateClientVersion(context);
        if (code <= 0 || code == DeskPrefs.guardBoundVersion(context)) {
            return false;
        }
        DeskPrefs.setGuardBoundVersion(context, code);
        final android.content.Context app = context.getApplicationContext();
        final String current = enabledServices(app);
        String without = removeService(current);
        try {
            Settings.Secure.putString(app.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
        } catch (Throwable th) {
            Log.w(TAG, "摘除守护失败：" + th.getMessage());
            return false;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    String now = enabledServices(app);
                    String next = TextUtils.isEmpty(now) ? SERVICE : now + ":" + SERVICE;
                    Settings.Secure.putString(app.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
                    Settings.Secure.putInt(app.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    Log.i(TAG, "守护已重绑（版本 " + code + "）");
                } catch (Throwable th) {
                    Log.w(TAG, "重绑守护失败：" + th.getMessage());
                }
            }
        }, REBIND_DELAY_MS);
        return true;
    }

    private static int UpdateClientVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Throwable th) {
            return 0;
        }
    }

    private static String enabledServices(Context context) {
        try {
            String value = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return value == null ? "" : value;
        } catch (Throwable th) {
            return "";
        }
    }

    /** 从 "a:b/c:d:e" 这样的列表里去掉自己那一条 */
    private static String removeService(String list) {
        if (TextUtils.isEmpty(list)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String item : list.split(":")) {
            if (TextUtils.isEmpty(item) || SERVICE.equals(item)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(':');
            }
            out.append(item);
        }
        return out.toString();
    }

    /**
     * 排上「定时复活」闹钟。
     *
     * <p>系统闹钟不依赖我们的进程：进程被杀之后，到点系统仍会把进程拉起来跑接收器。
     * 这是这台电视（待机会清理第三方应用）上唯一能保证「唤醒后还能回来」的机制。
     *
     * <p>一分钟一次。用 {@code setInexactRepeating}（不需要精确闹钟权限），
     * 电视常插电，实际触发间隔一般是 1–2 分钟；待机进 doze 期间会被推迟，
     * 但一唤醒系统就会把挂起的闹钟补上。
     */
    static void scheduleReviveAlarm(Context context) {
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent tick = new Intent(context, ReviveAlarmReceiver.class)
                    .setAction(ReviveAlarmReceiver.ACTION_REVIVE);
            android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(
                    context, 0, tick,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | (android.os.Build.VERSION.SDK_INT >= 23
                            ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));
            am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 60_000L,
                    60_000L, pending);
        } catch (Throwable th) {
            Log.w(TAG, "排复活闹钟失败：" + th.getMessage());
        }
    }

    /** 撤掉复活闹钟。关守护、还原授权时都要调，否则一分钟一次还在跑 */
    static void cancelReviveAlarm(Context context) {
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent tick = new Intent(context, ReviveAlarmReceiver.class)
                    .setAction(ReviveAlarmReceiver.ACTION_REVIVE);
            android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(
                    context, 0, tick,
                    android.app.PendingIntent.FLAG_NO_CREATE
                            | (android.os.Build.VERSION.SDK_INT >= 23
                            ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));
            if (pending != null) {
                am.cancel(pending);
                pending.cancel();
            }
        } catch (Throwable th) {
            Log.w(TAG, "取消复活闹钟失败：" + th.getMessage());
        }
    }

    /** 给设置页显示的一句话状态 */
    static String describe(Context context) {
        if (isGuardReady(context)) {
            return "已开启";
        }
        if (isServiceEnabled(context)) {
            return "已掉线";
        }
        if (hasWriteSecureSettings(context)) {
            return "可自动开启";
        }
        return "未开启";
    }
}

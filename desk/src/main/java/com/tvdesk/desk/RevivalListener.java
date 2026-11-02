package com.tvdesk.desk;

import android.content.Intent;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * 复活监听：被系统清理之后，靠它把桌面拉回来。
 *
 * <h3>为什么需要这一层</h3>
 * 这台 MiTV（Android 14）待机会做两件事：<b>杀掉 TVDesk 进程</b>，
 * 并且把 {@code enabled_accessibility_services} 一起清空。等用户唤醒电视，
 * 前台是固件桌面，而我们的守护服务和它绑定的无障碍权限都已经不在了 ——
 * 没人能拉回来，就只能停在小米桌面，直到用户手动打开一次 TVDesk。
 *
 * <p>Aerial 在同样的机器上是这么解的：<b>另外挂一个通知监听</b>。系统对通知监听有
 * 保活待遇 —— 进程被杀之后，只要有通知进来，系统会重新把服务绑定起来（连带拉起进程）。
 * 我们借这个时机做两件事：
 * <ol>
 *   <li>把被清空的无障碍绑定补回去（{@link GuardSetup#ensureEnabled}）；</li>
 *   <li>如果现在还在开机后的窗口期，说明用户刚开机/刚唤醒，前台多半还是原生桌面 ——
 *       直接把桌面拉起来。</li>
 * </ol>
 *
 * <p>不读通知内容：这里只重写 {@link #onListenerConnected()}，
 * {@code onNotificationPosted} 一律不碰。
 */
public class RevivalListener extends NotificationListenerService {

    private static final String TAG = "DeskRevival";

    /**
     * 开机后多久之内，连接时主动把桌面拉起来。
     *
     * <p>超过这个窗口说明电视已经用了一阵子，用户在别的应用里，
     * 这时候弹桌面就是打扰 —— 只补无障碍绑定，不抢前台。
     */
    private static final long BOOT_WINDOW_MS = 5 * 60_000L;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        long uptime = SystemClock.elapsedRealtime();
        Log.i(TAG, "通知监听已连接（开机 " + (uptime / 1000) + " 秒）");
        // 被清理时无障碍绑定会一起被清掉：先补回去，之后 HOME 键和窗口事件才有得接管
        try {
            GuardSetup.ensureEnabled(this);
        } catch (Throwable th) {
            Log.w(TAG, "补无障碍绑定失败：" + th.getMessage());
        }
        if (!DeskPrefs.homeGuard(this)) {
            return;
        }
        if (uptime > BOOT_WINDOW_MS) {
            Log.i(TAG, "已经过了开机窗口期，只补绑定、不抢前台");
            return;
        }
        if (DeskPrefs.stockAllowed(this)) {
            return;
        }
        try {
            Intent desk = new Intent(this, DeskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(desk);
            Log.i(TAG, "复活后把桌面拉起来");
        } catch (Throwable th) {
            Log.w(TAG, "复活后拉起桌面失败：" + th.getMessage());
        }
    }

    @Override
    public void onNotificationPosted(android.service.notification.StatusBarNotification sbn) {
        // 故意留空：不读任何通知内容，只借「服务被重新绑定」这个时机干活
    }

    @Override
    public void onNotificationRemoved(android.service.notification.StatusBarNotification sbn) {
        // 同上
    }

    /** 给设置页/日志用：这个监听现在有没有被系统授权 */
    static boolean isEnabled(android.content.Context context) {
        try {
            String value = android.provider.Settings.Secure.getString(
                    context.getContentResolver(),
                    "enabled_notification_listeners");
            return value != null && value.contains(context.getPackageName() + "/"
                    + RevivalListener.class.getName());
        } catch (Throwable th) {
            return false;
        }
    }
}

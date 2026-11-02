package com.tvdesk.desk;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * 复活监听：被系统清理之后，靠它把进程和守护一起拉回来。
 *
 * <h3>这台固件对待机做了什么</h3>
 * MiTV（Android 14）待机时 {@code killAllThirdApp} 会对所有第三方 App 执行
 * {@code forceStopPackage}，并且把 {@code enabled_accessibility_services} 一起清空。
 * 唤醒后前台是固件桌面，而我们的守护和它的无障碍权限都不在了 —— 没人拉得回来。
 *
 * <h3>Aerial 是怎么解的（也是这里面唯一有效的一招）</h3>
 * 另外挂一个<b>通知监听</b>。系统对通知监听有保活待遇：绑定断开后它会自己重绑，
 * 顺带把进程拉起来（日志里能看到 {@code notification listener binding died} 之后
 * 紧跟一条 {@code binding:}，约 0.5 秒）。重绑的那一刻我们做三件事：
 * <ol>
 *   <li>把被清空的无障碍绑定补回去（{@link GuardSetup#ensureEnabled}）；</li>
 *   <li>把被系统关掉的 ADB 写回（{@link GuardSetup#keepAdbIfWanted}）；</li>
 *   <li>兜底：如果无障碍没能绑上（系统偶尔会拒），自己看一眼前台 ——
 *       还停在固件桌面就直接拉起 TVDesk。</li>
 * </ol>
 *
 * <p>不读通知内容：只重写 {@link #onListenerConnected()}，
 * {@code onNotificationPosted} 一律不碰。
 */
public class RevivalListener extends NotificationListenerService {

    private static final String TAG = "DeskRevival";

    /**
     * 复活后等这么久再做兜底判断。
     *
     * <p>一千毫秒是给「无障碍绑定」留的时间：正常情况下守护在这之前已经连上了，
     * 它自己的复查（CONNECT_CHECKS 第一枪 1.2 秒）会带前台判断地拉回。
     * 这个兜底只在守护确实没起来时用，所以取在它之后。
     */
    private static final long FALLBACK_DELAY_MS = 2_500L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "通知监听已连接（系统已运行 " + (android.os.SystemClock.elapsedRealtime() / 1000)
                + " 秒）");
        // 被清理时无障碍绑定会一起被清掉：先补回去，之后 HOME 键和窗口事件才有得接管
        try {
            GuardSetup.ensureEnabled(this);
        } catch (Throwable th) {
            Log.w(TAG, "补无障碍绑定失败：" + th.getMessage());
        }
        GuardSetup.keepAdbIfWanted(this);
        if (!DeskPrefs.homeGuard(this)) {
            return;
        }
        handler.removeCallbacks(fallbackBringBack);
        handler.postDelayed(fallbackBringBack, FALLBACK_DELAY_MS);
    }

    /**
     * 兜底：无障碍没绑上、而我们又被叫醒了，就自己拉一次。
     *
     * <p>判据全部是「保守才动手」：
     * <ul>
     *   <li>守护已经绑上了 —— 交给它（它做的判断更全）；</li>
     *   <li>读不到前台（没有用量统计权限）—— 不动，宁可让用户自己按一下；</li>
     *   <li>前台是普通 App —— 用户在别处看东西，不能抢；</li>
     *   <li>放行期（用户主动去原桌面找东西）—— 不动。</li>
     * </ul>
     */
    private final Runnable fallbackBringBack = new Runnable() {
        @Override
        public void run() {
            if (!DeskPrefs.homeGuard(RevivalListener.this)) {
                return;
            }
            if (HomeGuardService.isEnabled(RevivalListener.this)) {
                // 守护已经起来了，它会带前台判断地接管
                Log.i(TAG, "守护已绑上，兜底交给它");
                return;
            }
            if (DeskPrefs.stockAllowed(RevivalListener.this)) {
                return;
            }
            String foreground = UsageFront.foreground(RevivalListener.this);
            if (foreground == null || getPackageName().equals(foreground)) {
                return;
            }
            if (!HomeRole.isHomePackage(RevivalListener.this, foreground)) {
                return;
            }
            Log.i(TAG, "守护没绑上、前台是原桌面（" + foreground + "），兜底拉起 TVDesk");
            try {
                Intent desk = new Intent(RevivalListener.this, DeskActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(desk);
            } catch (Throwable th) {
                Log.w(TAG, "兜底拉起桌面失败：" + th.getMessage());
            }
        }
    };

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.i(TAG, "通知监听断开（系统可能要重绑）");
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

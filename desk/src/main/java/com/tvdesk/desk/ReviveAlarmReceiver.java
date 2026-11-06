package com.tvdesk.desk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * 定时复活：进程被系统清掉之后，靠系统闹钟把桌面重新拉起来。
 *
 * <h3>为什么还要这一层</h3>
 * 这台 MiTV（Android 14）待机会做两件事：杀掉 TVDesk 进程，并且把
 * {@code enabled_accessibility_services} 一起清空。等用户唤醒电视，前台是固件桌面，
 * 守护服务已经不在，谁也拉不回来。
 *
 * <p>通知监听（{@link RevivalListener}）只有在「有新通知」时才会被系统重新绑定，
 * 电视上没通知就一直不会被拉起 —— 实测唤醒后 20 秒仍是原生桌面。所以再加这一条：
 * 系统闹钟（{@code AlarmManager}）不依赖我们的进程，即使进程已经死了，
 * 到点也会把进程拉起来跑这个接收器。
 *
 * <h3>只做两件事，不抢前台</h3>
 * <ol>
 *   <li>把被清空的无障碍绑定补回去（下次按 HOME 就能接管）；</li>
 *   <li>发现「上一次检查是黑屏、这一次亮着」= 刚唤醒，才把桌面拉起来。
 *       电视一直亮着（用户在别的应用里）时一律不动，避免闹钟变成骚扰。</li>
 * </ol>
 */
public class ReviveAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "DeskRevive";
    /** 自定义 action：闹钟只给自己发 */
    static final String ACTION_REVIVE = "com.tvdesk.app.action.REVIVE_TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_REVIVE.equals(intent.getAction())) {
            return;
        }
        if (!DeskPrefs.homeGuard(context)) {
            return;
        }
        boolean interactive = isInteractive(context);
        boolean wasInteractive = DeskPrefs.lastInteractive(context);
        DeskPrefs.setLastInteractive(context, interactive);

        // 主判据：守护是不是真的可用（列表里有名字 **且** 无障碍总开关开着）。
        // 只看列表会漏掉「系统把总开关关了、名字还留着」这种情况 ——
        // 那时按 HOME 会直接回固件桌面，而且没人再补，正是用户看到的「守护失效」。
        // 这个闹钟只做一件事：把丢掉的无障碍绑定补回来。
        //
        // 以前它还兼任「刚唤醒就拉起 TVDesk」——那是错的：闹钟是周期性的，
        // 屏幕一亮它就跑，根本不知道用户此刻是在看视频、进设置还是在 HDMI 里。
        // 抢前台的判断交给无障碍服务的亮屏复查（它手上有实时前台证据 + 统一判定）。
        boolean lost = !GuardSetup.isGuardReady(context);
        if (!lost) {
            GuardRuntimeState.endRecover();
            return;
        }
        // 熔断：一轮最多补这么几次，补不回来就停手等用户操作，
        // 否则每隔几分钟就写一次系统设置，既没用又是骚扰
        if (!GuardRuntimeState.beginRecoverIfNeeded()) {
            Log.i(TAG, "无障碍绑定仍缺失，但本轮恢复次数已用完，等用户处理");
            return;
        }
        Log.i(TAG, "无障碍绑定丢失，补第 " + GuardRuntimeState.recoverAttempts() + " 次");
        try {
            GuardSetup.ensureEnabled(context);
        } catch (Throwable th) {
            Log.w(TAG, "恢复无障碍绑定失败：" + th.getMessage());
        }
    }

    private boolean isInteractive(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable th) {
            return true;
        }
    }
}

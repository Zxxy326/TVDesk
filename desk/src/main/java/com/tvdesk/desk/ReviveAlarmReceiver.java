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

        // 主判据：无障碍绑定还在不在。系统待机清理时会把
        // enabled_accessibility_services 一起清空 —— 这个信号比「黑屏还是亮屏」准，
        // 因为它只在「我们被系统清理过」时才成立，用户正常使用时绝不触发。
        boolean lost = !GuardSetup.isServiceEnabled(context);
        if (lost) {
            Log.i(TAG, "无障碍绑定已丢失（系统清理过），补回来");
            try {
                GuardSetup.ensureEnabled(context);
            } catch (Throwable th) {
                Log.w(TAG, "恢复无障碍绑定失败：" + th.getMessage());
            }
        }

        if (!interactive) {
            return;
        }
        boolean justWoke = interactive && !wasInteractive;
        if (!lost && !justWoke) {
            // 绑定好好的、屏幕也一直亮着：用户在正常使用，不动
            return;
        }
        if (DeskPrefs.stockAllowed(context)) {
            return;
        }
        Log.i(TAG, (lost ? "绑定丢失" : "刚唤醒") + "，拉起 TVDesk");
        try {
            Intent desk = new Intent(context, DeskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(desk);
        } catch (Throwable th) {
            Log.w(TAG, "闹钟拉起桌面失败：" + th.getMessage());
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

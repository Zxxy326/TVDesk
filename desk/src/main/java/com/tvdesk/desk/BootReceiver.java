package com.tvdesk.desk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启。
 *
 * <h3>以前为什么开机不进 TVDesk</h3>
 * 这段逻辑以前有个门槛：<b>只有 {@link HomeRole#isDefault} 为真才拉起</b>。
 * 想法是「没设成默认桌面却抢着弹出来，就成了关不掉的开机广告」。但那套假设在国产固件上不成立：
 * 小米电视的桌面是 {@code com.mitv.tvhome}，系统根本不给第三方应用「设为默认桌面」的入口，
 * 于是 {@code isDefault} 永远是 false —— 开机一次都没拉过，用户看到的就是原生桌面。
 *
 * <p>现在改成：<b>开机就拉</b>，只跟随「桌面守护」这一个开关（关掉守护＝不接管＝不自启，
 * 语义一致）。这台电视上的 HOME 键接管本来就是靠守护服务做的，
 * 开机这一段只是把同一条接管链补全。
 *
 * <p>还有一种固件行为：BOOT_COMPLETED 之后它自己会再把原生桌面翻上来，把我们先拉起的
 * 窗口顶掉。在接收器里等太久不可靠（进程随时可能被收），所以这里只负责「尽早拉起来」，
 * 真正兜底的是 {@link HomeGuardService} —— 服务连上后会复查前台，还是原生桌面就再拉一次。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "DeskBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        // 覆盖安装之后也拉一次：升级完停在原生桌面，用户会以为桌面坏了
        boolean upgraded = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !upgraded) {
            return;
        }
        if (!DeskPrefs.homeGuard(context)) {
            Log.i(TAG, "桌面守护关着，开机不自启");
            return;
        }
        // 开机/升级后先把守护补回来：待机会把无障碍总开关关掉、升级会让系统把服务
        // 标记成 crashed 而不再绑定。不补的话「守护开着但按 HOME 回固件桌面」——
        // 1.1.6 升级时就是这么坏的。
        try {
            GuardSetup.ensureEnabled(context);
            GuardSetup.ensureNotificationListener(context);
            GuardSetup.scheduleReviveAlarm(context);
        } catch (Throwable th) {
            Log.w(TAG, "开机补守护失败：" + th.getMessage());
        }
        try {
            Intent desk = new Intent(context, DeskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(desk);
            Log.i(TAG, (boot ? "开机" : "升级后") + "拉起 TVDesk");
        } catch (Throwable th) {
            // 极少数固件会拦后台启动：不致命，守护服务连上后还会补一次
            Log.w(TAG, "开机拉起桌面失败：" + th.getMessage());
        }
    }
}

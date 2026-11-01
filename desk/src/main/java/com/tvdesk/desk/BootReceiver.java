package com.tvdesk.desk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启。
 *
 * <p>和「设为默认桌面」是一套的两半：设成默认桌面之后，系统开机本来就会拉起 HOME，
 * 但有的固件会先起自己的桌面、把 HOME 留到用户按键时才处理（小米 / 部分海信固件），
 * 那段空窗期看着就是「电视开了但没进桌面」。这里在开机完成后再主动拉一次，
 * 画面直接落在 TVDesk 上。
 *
 * <p>只在<b>已经是默认桌面</b>时才拉起：没设成默认桌面却抢着弹出来，就成了
 * 一个关不掉的开机广告。
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
        if (!boot) {
            return;
        }
        if (!HomeRole.isDefault(context)) {
            Log.i(TAG, "不是默认桌面，开机不主动拉起");
            return;
        }
        try {
            Intent desk = new Intent(context, DeskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(desk);
        } catch (Throwable th) {
            Log.w(TAG, "开机拉起桌面失败：" + th.getMessage());
        }
    }
}

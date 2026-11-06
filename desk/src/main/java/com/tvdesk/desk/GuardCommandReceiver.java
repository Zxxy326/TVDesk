package com.tvdesk.desk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 安装工具的「关掉守护」指令。
 *
 * <p>还原流程要先把应用自己的自动修复关掉，才删得掉无障碍授权。这一步没法靠
 * {@code settings put} 完成——授权删掉之后，只要应用还认为「守护开着」，
 * 通知监听重连或者闹钟到点就会把它绑回去，用户看到的是「还原了，过一会儿又开了」。
 *
 * <p>所以留一个口子：工具发一条广播，应用把开关关掉、取消待执行的闹钟，
 * 之后不再自己恢复。
 *
 * <p>只做「关」，不做「开」：开关的打开必须由用户在界面上确认（方案 6.3 条），
 * 一条广播就能悄悄打开无障碍，等于绕过了用户的知情同意。
 */
public class GuardCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "DeskGuardCmd";

    /** 工具调用：把守护关掉，停止自愈 */
    public static final String ACTION_DISABLE_GUARD = "com.tvdesk.app.action.DISABLE_GUARD";
    /** 工具调用：把守护打开（系统侧的授权由工具自己写，这里只翻应用里的开关） */
    public static final String ACTION_ENABLE_GUARD = "com.tvdesk.app.action.ENABLE_GUARD";
    /** 回执：工具据此确认指令真的被执行了，而不是「发了就算了」 */
    public static final String ACTION_GUARD_STATE = "com.tvdesk.app.action.GUARD_STATE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_DISABLE_GUARD.equals(action)) {
            DeskPrefs.setHomeGuard(context, false);
            try {
                GuardSetup.cancelReviveAlarm(context);
            } catch (Throwable th) {
                Log.w(TAG, "取消复活闹钟失败：" + th.getMessage());
            }
            Log.i(TAG, "收到工具指令：关闭桌面守护");
            // 用广播回执而不是 setResult：am broadcast 默认不等结果，
            // 而且工具在另一台机器上，看日志比看 result 更直接
            Intent back = new Intent(ACTION_GUARD_STATE);
            back.setPackage(context.getPackageName());
            back.putExtra("guard", false);
            context.sendBroadcast(back);
            return;
        }
        if (ACTION_ENABLE_GUARD.equals(action)) {
            DeskPrefs.setHomeGuard(context, true);
            try {
                GuardSetup.scheduleReviveAlarm(context);
            } catch (Throwable th) {
                Log.w(TAG, "排复活闹钟失败：" + th.getMessage());
            }
            Log.i(TAG, "收到工具指令：开启桌面守护");
            Intent back = new Intent(ACTION_GUARD_STATE);
            back.setPackage(context.getPackageName());
            back.putExtra("guard", true);
            context.sendBroadcast(back);
            return;
        }
        if (ACTION_GUARD_STATE.equals(action)) {
            Log.i(TAG, "守护状态回执：guard=" + intent.getBooleanExtra("guard", false));
        }
    }
}

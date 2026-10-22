package com.tvdesk.desk;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * 拉起电视厂商自己的设置页。设置页和信号源页都要用，所以从 {@link SettingsActivity} 里抽出来。
 *
 * <p>三级兜底。先 {@link Settings#ACTION_SETTINGS}——绝大多数电视和盒子都响应它；
 * 没有的话退到 {@link Settings#ACTION_WIRELESS_SETTINGS}，「无线设置」几乎家家都有；
 * 再没有就按包名找厂商设置的启动 Intent（小米电视实测两个标准 action 都不响应，
 * 它的设置在 {@code com.xiaomi.mitv.settings} 里，用 {@code getLaunchIntentForPackage}
 * 取入口、不写死类名，固件升级换类名也不会失效）。
 *
 * <p>这里只回报「成没成」，不弹提示：同一个失败在设置页该说「这台设备没给系统设置页」，
 * 在信号源页该说「请直接按遥控器上的信号源键」，措辞归调用方。
 */
public final class SystemSettings {

    /**
     * 主流电视厂商的系统设置包名。
     *
     * <p>只收实测过的：小米电视（含红米）的标准 action 一律不响应，必须按包名拉。
     * 其余品牌用 {@code getLaunchIntentForPackage} 试，取不到就自然跳过——
     * 装了这个包就多一条路，没装也不报错。
     */
    private static final String[] VENDOR_SETTINGS_PACKAGES = {
            "com.xiaomi.mitv.settings",
    };

    private SystemSettings() {
    }

    /** 有没有真的拉起来一个设置界面 */
    public static boolean open(Context context) {
        if (startIfResolvable(context, new Intent(Settings.ACTION_SETTINGS))) {
            return true;
        }
        if (startIfResolvable(context, new Intent(Settings.ACTION_WIRELESS_SETTINGS))) {
            android.util.Log.i("SystemSettings", "系统设置：ACTION_SETTINGS 不可用，退到无线设置");
            return true;
        }
        for (String pkg : VENDOR_SETTINGS_PACKAGES) {
            Intent probe = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (probe != null && startIfResolvable(context, probe)) {
                android.util.Log.i("SystemSettings", "系统设置：标准 action 都不可用，按包名拉起厂商设置");
                return true;
            }
        }
        android.util.Log.w("SystemSettings", "系统设置：标准 action 和厂商包名都没找到可用的设置页");
        return false;
    }

    /**
     * 起一个界面。外部页随时可能被厂商阉掉，所以 resolve 先问、start 再兜异常，
     * 两条都过了才算成。
     */
    static boolean startIfResolvable(Context context, Intent intent) {
        if (intent == null || intent.resolveActivity(context.getPackageManager()) == null) {
            return false;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (SecurityException e) {
            // 厂商设置页常带 signature 级权限，能解析到也起不来
            return false;
        }
    }
}

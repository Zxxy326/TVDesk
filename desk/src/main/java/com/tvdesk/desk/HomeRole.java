package com.tvdesk.desk;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

/**
 * 「让 TVDesk 接管电视桌面」这件事的入口。
 *
 * <p>不靠无障碍、不动系统应用，走的是 Android 自己的「默认桌面」机制：
 * {@code DeskActivity} 在清单里声明了 {@code HOME + DEFAULT}，用户在系统选择框里把它选成
 * 默认桌面之后，按 HOME 和开机都会回到这里。参考 aerial-tv 的安装方式。
 *
 * <p>Android 10 起有 {@link RoleManager}，能直接弹「设为默认桌面」的角色请求页；
 * 更老的系统只能打开系统的桌面设置页，让用户手动选。两条路都给不出去时返回 false，
 * 调用方据此提示用户去系统设置里找「桌面」。
 */
public final class HomeRole {

    private HomeRole() {
    }

    /** 现在按 HOME 会回到 TVDesk 吗 */
    public static boolean isDefault(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager manager = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
                if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    return manager.isRoleHeld(RoleManager.ROLE_HOME);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = context.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return info != null && info.activityInfo != null
                    && context.getPackageName().equals(info.activityInfo.packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 弹「设为默认桌面」。
     *
     * @return true 表示已经打开了系统界面；false 表示这台电视没有可用入口
     */
    public static boolean request(Activity activity, int requestCode) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager manager =
                        (RoleManager) activity.getSystemService(Context.ROLE_SERVICE);
                if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    activity.startActivityForResult(
                            manager.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return openHomeSettings(activity);
    }

    /** 老系统：打开「主屏幕/桌面」设置页，由用户自己选 */
    public static boolean openHomeSettings(Activity activity) {
        Intent[] candidates = {
                new Intent(Settings.ACTION_HOME_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent intent : candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}

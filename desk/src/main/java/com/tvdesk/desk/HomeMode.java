package com.tvdesk.desk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/**
 * 当前装的是哪个模式：standard（能当默认桌面）还是 guard（只能靠守护接管）。
 *
 * <p>两个模式同包名、同签名、只能装一个，区别只在清单里有没有 HOME 声明。
 * desk 模块不能反向依赖 app 的 BuildConfig，所以模式由 app 侧在清单里写
 * {@code tvdesk.home_mode} 元数据，这里读出来。
 *
 * <p>读不到就按 standard 处理：老版本升上来的都是 standard，
 * 把不确定的情况判成 guard 会让「设为默认桌面」这个入口凭空消失。
 */
public final class HomeMode {

    private static final String META_KEY = "tvdesk.home_mode";
    private static final String VALUE_GUARD = "guard";

    private static String cached;

    private HomeMode() {
    }

    public static String of(Context context) {
        if (cached != null) {
            return cached;
        }
        String value = null;
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            if (info != null && info.metaData != null) {
                value = info.metaData.getString(META_KEY);
            }
        } catch (Throwable ignored) {
            // 读不到按 standard 处理，见类注释
        }
        cached = VALUE_GUARD.equals(value) ? VALUE_GUARD : "standard";
        return cached;
    }

    /** 守护版：清单里没有 HOME，不能设为默认桌面 */
    public static boolean isGuard(Context context) {
        return VALUE_GUARD.equals(of(context));
    }

    /** 给设置页/诊断用的一句话 */
    public static String label(Context context) {
        return isGuard(context) ? "守护版" : "标准版";
    }
}

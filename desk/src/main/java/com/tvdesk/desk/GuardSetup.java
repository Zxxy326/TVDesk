package com.tvdesk.desk;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/**
 * 把「桌面守护」这个无障碍服务自动打开。
 *
 * <h3>为什么要自己写而不是让用户去点</h3>
 * 电视上点「设置 → 无障碍 → 桌面守护 → 开启」这条路又长又难找，很多固件还把这一项藏在
 * 「更多设置」里。Aerial 的做法是装的时候用一条 adb 命令把服务直接写进系统设置
 * （{@code settings put secure enabled_accessibility_services …}），用户全程不用动手。
 * 这里照同样的思路：只要应用拿到了 {@code WRITE_SECURE_SETTINGS}（adb 授权过一次就有），
 * 启动时自己写。
 *
 * <p>没有这个权限（比如 U 盘装的、应用商店装的）就<b>什么都不做</b> ——
 * 不弹窗、不引导，把「开启守护」留在设置页里，用户想要的时候自己去点。
 *
 * <h3>为什么要「重绑」</h3>
 * 无障碍服务的配置（{@code accessibility_home.xml}）是在服务绑定时读的。升级安装后
 * 配置变了（比如这一版加了 HOME 键过滤），但系统不会自动重新绑定 —— 表现就是
 * 「守护开着，但按 HOME 还是回原桌面」。所以版本号一变就把服务从列表里摘掉、
 * 1.5 秒后再加回去，逼系统重新读一次配置。
 */
final class GuardSetup {

    private static final String TAG = "GuardSetup";
    /** 必须和清单里的服务组件名一致 */
    private static final String SERVICE = "com.tvdesk.app/com.tvdesk.desk.HomeGuardService";
    /** 摘掉服务到加回去之间的间隔：太短系统可能认为「没变」 */
    private static final long REBIND_DELAY_MS = 1500L;

    private GuardSetup() {
    }

    /** 有没有写系统设置的权限（adb 授权过一次就一直有，除非恢复出厂） */
    static boolean hasWriteSecureSettings(Context context) {
        try {
            return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable th) {
            return false;
        }
    }

    /** 守护服务现在在系统的启用列表里吗 */
    static boolean isServiceEnabled(Context context) {
        return enabledServices(context).contains(SERVICE);
    }

    /**
     * 确保守护是开着的。
     *
     * @return 真的开启了（或者本来就开着）；没权限 / 写失败返回 false
     */
    static boolean ensureEnabled(Context context) {
        if (!hasWriteSecureSettings(context)) {
            return false;
        }
        if (isServiceEnabled(context)) {
            return true;
        }
        try {
            String current = enabledServices(context);
            String next = TextUtils.isEmpty(current) ? SERVICE : current + ":" + SERVICE;
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "已把桌面守护写进系统设置");
            return true;
        } catch (Throwable th) {
            Log.w(TAG, "写无障碍设置失败：" + th.getMessage());
            return false;
        }
    }

    /**
     * 应用升级后强制重绑一次。
     *
     * <p>无障碍配置（比如 {@code canRequestFilterKeyEvents}）只在绑定时读一次，
     * 升级 APK 不会触发重读。这里把服务从启用列表里摘掉、过一会儿再加回去。
     *
     * @return 需要重绑并已经排上任务
     */
    static boolean rebindIfVersionChanged(Context context) {
        if (!hasWriteSecureSettings(context) || !isServiceEnabled(context)) {
            return false;
        }
        final int code = UpdateClientVersion(context);
        if (code <= 0 || code == DeskPrefs.guardBoundVersion(context)) {
            return false;
        }
        DeskPrefs.setGuardBoundVersion(context, code);
        final android.content.Context app = context.getApplicationContext();
        final String current = enabledServices(app);
        String without = removeService(current);
        try {
            Settings.Secure.putString(app.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
        } catch (Throwable th) {
            Log.w(TAG, "摘除守护失败：" + th.getMessage());
            return false;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    String now = enabledServices(app);
                    String next = TextUtils.isEmpty(now) ? SERVICE : now + ":" + SERVICE;
                    Settings.Secure.putString(app.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
                    Settings.Secure.putInt(app.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    Log.i(TAG, "守护已重绑（版本 " + code + "）");
                } catch (Throwable th) {
                    Log.w(TAG, "重绑守护失败：" + th.getMessage());
                }
            }
        }, REBIND_DELAY_MS);
        return true;
    }

    private static int UpdateClientVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Throwable th) {
            return 0;
        }
    }

    private static String enabledServices(Context context) {
        try {
            String value = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return value == null ? "" : value;
        } catch (Throwable th) {
            return "";
        }
    }

    /** 从 "a:b/c:d:e" 这样的列表里去掉自己那一条 */
    private static String removeService(String list) {
        if (TextUtils.isEmpty(list)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String item : list.split(":")) {
            if (TextUtils.isEmpty(item) || SERVICE.equals(item)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(':');
            }
            out.append(item);
        }
        return out.toString();
    }

    /** 给设置页显示的一句话状态 */
    static String describe(Context context) {
        if (isServiceEnabled(context)) {
            return "已开启";
        }
        if (hasWriteSecureSettings(context)) {
            return "可自动开启";
        }
        return "未开启";
    }
}

package com.tvdesk.desk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;

/**
 * 打开「电视厂家自己的设置页」。
 *
 * <p>为什么要专门做一层：AOSP 那套 {@code Settings.ACTION_DISPLAY_SETTINGS} /
 * {@code ACTION_WIFI_SETTINGS} 在国产电视上要么是很旧的公版界面，要么干脆是另一套
 * 完全不相干的页面（实测在小米电视上，「显示设置」出来的是一个叫「图片」的页面，
 * 只有图片模式/背光/对比度，和用户按遥控器进的那个设置完全不是一回事）。
 * 用户要的是<b>厂家设置</b>——他平时调亮度、连 Wi-Fi 的那个界面。
 *
 * <p>这些 component 是从 Aerial TV 的 {@code SystemState.kt} 抄的：它把各家
 * （小米/雷鸟/海尔/PPTV/Whaley/极米）的设置入口都列了一遍，并且按「能解析到才用」
 * 的顺序试。这里只保留和我们相关的几家，顺序也照它：
 * 小米 → AOSP TV 的 NetworkActivity → PPTV → TCL → Whaley。
 *
 * <p>最后一档<b>不退回 AOSP 的 ACTION_* 设置</b>：宁可不打开、让面板给一句提示，
 * 也不要突然跳到另一个体系的页面——用户会以为点错了。
 */
public final class VendorSettings {

    private static final String XIAOMI = "com.xiaomi.mitv.settings";

    private VendorSettings() {
    }

    /** 有线还是无线：有线时小米要先给以太网那一页（Wi-Fi 页关着 Wi-Fi 会被当成「闪退」） */
    public static boolean isEthernet(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 打开厂家的网络设置页。
     *
     * @return 真的拉起来了
     */
    public static boolean openNetwork(Context context) {
        boolean wired = isEthernet(context);
        Intent[] pages = wired
                ? new Intent[]{
                new Intent("com.xiaomi.mitv.settings.ETHERNET_SETTINGS").setPackage(XIAOMI),
                xiaomiWifi(),
                new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                        "com.android.tv.settings",
                        "com.android.tv.settings.connectivity.NetworkActivity")),
                new Intent("com.pptv.intent.action.WIFI_ACTIVITY").setComponent(new ComponentName(
                        "com.pptv.setting", "com.pptv.setting.wifi.WifiWorkActivity")),
                new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                        "com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
                whaley(),
        }
                : new Intent[]{
                xiaomiWifi(),
                new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                        "com.android.tv.settings",
                        "com.android.tv.settings.connectivity.NetworkActivity")),
                new Intent("com.pptv.intent.action.WIFI_ACTIVITY").setComponent(new ComponentName(
                        "com.pptv.setting", "com.pptv.setting.wifi.WifiWorkActivity")),
                new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                        "com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
                whaley(),
        };
        return startFirst(context, pages);
    }

    /**
     * 打开厂家的「画面/亮度」设置页。
     *
     * <p>小米没有可跳转的画面设置 Activity：它的背光滑块是挂在广播上的
     * （{@code BACKLIGHT_POPUP} → SettingsBroadcastReceiver），发一条广播就会出现。
     * 这正好和面板里那根滑杆互补：滑杆能直接改系统亮度（实测有效），
     * 用户想进厂家那套细调就点这一行。
     */
    public static boolean openPicture(Context context) {
        // 1) 小米：背光滑块走广播
        Intent backlight = new Intent("com.xiaomi.mitv.settings.BACKLIGHT_POPUP")
                .setComponent(new ComponentName(XIAOMI,
                        "com.xiaomi.mitv.settings.SettingsBroadcastReceiver"));
        try {
            if (!context.getPackageManager().queryBroadcastReceivers(backlight, 0).isEmpty()) {
                context.sendBroadcast(backlight);
                return true;
            }
        } catch (Throwable ignored) {
        }
        // 2) TCL：画面设置是它设置应用里的一个服务
        try {
            if (installed(context, "com.tcl.settings")) {
                context.startService(new Intent("com.tcl.settings.SHOW_WINDOW")
                        .setPackage("com.tcl.settings"));
                return true;
            }
        } catch (Throwable ignored) {
        }
        // 3) 其余：PPTV 的图画与声音页，再不行进厂家设置主界面让用户自己找
        return startFirst(context, new Intent[]{
                new Intent("com.pptv.intent.action.PICTUREAUDIO_ACTIVITY").setComponent(
                        new ComponentName("com.pptv.setting",
                                "com.pptv.setting.media.PictureAudioSettingActivity")),
                xiaomiSettings(),
                new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                        "com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
                whaley(),
        });
    }

    /** 厂家设置主界面（暂时没用到，留着给「设置」这类入口） */
    public static boolean openSettings(Context context) {
        return startFirst(context, new Intent[]{
                xiaomiSettings(),
                new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                        "com.haier.tv.menusetting", "com.haier.tv.menusetting.RootActivity")),
                new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                        "com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
                new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                        "com.pptv.setting", "com.pptv.setting.root.SettingsActivity")),
                whaley(),
        });
    }

    private static Intent xiaomiWifi() {
        return new Intent("com.xiaomi.mitv.settings.WIFI_SETTINGS").setComponent(new ComponentName(
                XIAOMI, "com.xiaomi.mitv.settings.wifi.MitvWifiActivity"));
    }

    private static Intent xiaomiSettings() {
        return new Intent("com.xiaomi.mitv.settings.SETTINGS").setComponent(new ComponentName(
                XIAOMI, "com.xiaomi.mitv.settings.entry.MainActivity"));
    }

    /** Whaley（微鲸）：没有任何标准设置 action，只能开它自己的入口 */
    private static Intent whaley() {
        return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                "com.helios.setting", "com.helios.activity.StartActivity"));
    }

    /**
     * 按顺序试，第一个能解析到 Activity 的就拉起来。
     *
     * <p>「能解析到」比「包名存在」可靠：同一家的不同型号，包一样但页面可能没有。
     */
    private static boolean startFirst(Context context, Intent[] pages) {
        PackageManager pm = context.getPackageManager();
        for (Intent page : pages) {
            try {
                if (pm.resolveActivity(page, 0) != null) {
                    context.startActivity(page);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean installed(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    /** 兜底：连厂家设置都找不到时，至少把标准设置当最后一档（目前没接上，保留给别处用） */
    static Intent lastResort() {
        return new Intent(Settings.ACTION_SETTINGS);
    }
}

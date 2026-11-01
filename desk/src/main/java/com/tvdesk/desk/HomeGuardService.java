package com.tvdesk.desk;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * 无障碍接管 HOME 键。
 *
 * <h3>为什么不用「设为默认桌面」</h3>
 * 那套（HOME + DEFAULT 声明 + RoleManager）在原生 Android TV 上是正路，
 * 但国产电视固件经常不给第三方应用这个选择：设置页里根本没有「默认桌面」这一项，
 * 或者选了也被系统在开机时改回去。无障碍这条路绕开它：
 * 监听窗口切换，一旦发现有「桌面类应用」被显示出来、而它不是我们，就立刻把 TVDesk 拉起来。
 * 观感上按 HOME 就是直接回到 TVDesk。
 *
 * <h3>只认桌面，不认别的应用</h3>
 * 判断依据是「这个包是不是系统里声明了 CATEGORY_HOME 的应用」，不是「任何应用」。
 * 否则用户在别的 App 里正常用着，会被莫名其妙拽回桌面——那是流氓软件的行为。
 * 从 TVDesk 按 HOME → 系统桌面冒出来 → 被拉回 TVDesk，这条路是通的；
 * 从爱奇艺按 HOME → 同样回到 TVDesk，也符合预期。
 *
 * <h3>防死循环</h3>
 * 拉起 TVDesk 本身会产生新的窗口事件，如果不判断「已经是自己的包名」就会无限重启自己。
 * 另外桌面每次显示都会来一条事件，所以还有一个 400ms 的节流。
 */
public class HomeGuardService extends AccessibilityService {

    private static final String TAG = "HomeGuard";
    /** 两次接管之间至少隔这么久，防止窗口事件连发时反复拉起 */
    private static final long THROTTLE_MS = 400L;
    /** HOME 包名缓存的有效期：装了新桌面不用等太久就能被认出来 */
    private static final long HOME_CACHE_MS = 30_000L;

    private long lastTakeover;
    private Set<String> homePackages = new HashSet<>();
    private long homePackagesAt;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkgName = event.getPackageName();
        if (pkgName == null) {
            return;
        }
        String pkg = pkgName.toString();
        if (pkg.equals(getPackageName())) {
            // 已经是自己，别再拉一次
            return;
        }
        if (!DeskPrefs.homeGuard(this)) {
            return;
        }
        if (!isHomePackage(pkg)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTakeover < THROTTLE_MS) {
            return;
        }
        lastTakeover = now;
        Log.i(TAG, "接管 HOME：从 " + pkg + " 回到桌面");
        try {
            Intent desk = new Intent(this, DeskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(desk);
        } catch (Throwable th) {
            Log.w(TAG, "拉不起来：" + th.getMessage());
        }
    }

    /** 这个包是不是系统里的「桌面」 */
    private boolean isHomePackage(String pkg) {
        long now = System.currentTimeMillis();
        if (homePackages.isEmpty() || now - homePackagesAt > HOME_CACHE_MS) {
            homePackages = queryHomePackages();
            homePackagesAt = now;
        }
        return homePackages.contains(pkg);
    }

    private Set<String> queryHomePackages() {
        Set<String> out = new HashSet<>();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo info : getPackageManager()
                    .queryIntentActivities(home, PackageManager.MATCH_ALL)) {
                if (info.activityInfo != null) {
                    out.add(info.activityInfo.packageName);
                }
            }
        } catch (Throwable th) {
            Log.w(TAG, "查桌面列表失败：" + th.getMessage());
        }
        // 我们自己也在这个列表里（声明了 HOME），但上面已经排除了
        out.remove(getPackageName());
        return out;
    }

    @Override
    public void onInterrupt() {
        // 没有长时反馈要中断
    }

    // ---------------------------------------------------------------- 状态查询

    /** 无障碍服务现在是不是开着（用户可能在系统设置里关掉） */
    public static boolean isEnabled(Context context) {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (android.accessibilityservice.AccessibilityServiceInfo info
                    : am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                android.content.pm.ResolveInfo resolve = info.getResolveInfo();
                if (resolve != null && resolve.serviceInfo != null
                        && context.getPackageName().equals(resolve.serviceInfo.packageName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 系统实现差异，查不到就当没开
        }
        return false;
    }

    /** 跳到系统的无障碍设置页，让用户手动打开 */
    public static void openSettings(Context context) {
        try {
            context.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable th) {
            Log.w(TAG, "打不开无障碍设置：" + th.getMessage());
        }
    }

    /** 服务组件名（给说明文案用） */
    public static ComponentName component(Context context) {
        return new ComponentName(context, HomeGuardService.class);
    }
}

package com.tvdesk.desk;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * 桌面守护：让 HOME 键、开机、亮屏都回到 TVDesk。
 *
 * <h3>为什么不用「设为默认桌面」(RoleManager)</h3>
 * 那套（HOME + DEFAULT 声明 + RoleManager）在原生 Android TV 上是正路，
 * 但国产电视固件经常不给第三方应用这个选择：设置页里根本没有「默认桌面」这一项，
 * 或者选了也被系统在开机时改回去。所以这里是<b>两条腿走路</b>：
 * 能设成默认桌面就设（{@link HomeRole}），设不了就靠这个无障碍服务兜住。
 *
 * <h3>三条接管时机</h3>
 * <ol>
 *   <li><b>HOME 键</b>（{@link #onKeyEvent}）：用户按 HOME 时，如果 TVDesk 不是默认桌面，
 *       这一键本来会被原桌面拿走 —— 我们在按下那一刻就吞掉它并把自己叫到前台，
 *       原桌面连闪一下的机会都没有（Aerial 的 guard 也是这么做的）。</li>
 *   <li><b>原桌面被显示</b>（{@link #onAccessibilityEvent}）：某些固件会自己在开机后
 *       把原桌面翻上来，或者用户从别的地方绕过去；看到原桌面成为前台就拉回来。</li>
 *   <li><b>亮屏</b>：待机回来时有些电视会先显示原桌面。等 1.5 秒再看一眼前台是谁，
 *       还停在原桌面才动 —— 立刻抢会和固件自己的开机流程打架。</li>
 * </ol>
 *
 * <h3>什么时候不抢</h3>
 * 用户从应用页里主动打开了原桌面（或者从「我的应用」里点开了固件桌面），
 * 这时候还硬拉回来就是流氓行为。{@link DeskPrefs#allowStockLauncher} 记一个有效期，
 * 期间 HOME 键和窗口事件都放行；TVDesk 自己回到前台时立刻作废。
 *
 * <h3>防死循环</h3>
 * 拉起 TVDesk 本身会产生新的窗口事件，所以先看包名是不是自己；
 * 另外两次接管之间至少隔 {@link #THROTTLE_MS}，避免窗口事件连发时反复启动。
 */
public class HomeGuardService extends AccessibilityService {

    private static final String TAG = "HomeGuard";
    /** 两次接管之间至少隔这么久，防止窗口事件连发时反复拉起 */
    private static final long THROTTLE_MS = 400L;
    /** HOME 包名缓存的有效期：装了新桌面不用等太久就能被认出来 */
    private static final long HOME_CACHE_MS = 30_000L;
    /** 亮屏之后等这么久再判断：给固件自己的开机/唤醒流程留出时间 */
    private static final long SCREEN_ON_DELAY_MS = 1500L;

    private long lastTakeover;
    private Set<String> homePackages = new HashSet<>();
    private long homePackagesAt;
    /** 最近一次前台窗口的包名（亮屏延迟判断时用） */
    private String lastForeground;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DeskPrefs.homeGuard(context)) {
                return;
            }
            handler.removeCallbacks(takeOverAfterScreenOn);
            handler.postDelayed(takeOverAfterScreenOn, SCREEN_ON_DELAY_MS);
        }
    };

    private final Runnable takeOverAfterScreenOn = new Runnable() {
        @Override
        public void run() {
            if (DeskPrefs.stockAllowed(HomeGuardService.this)) {
                return;
            }
            String foreground = lastForeground;
            if (foreground == null || getPackageName().equals(foreground)) {
                return;
            }
            if (isHomePackage(foreground)) {
                Log.i(TAG, "亮屏后仍在原桌面（" + foreground + "），拉回 TVDesk");
                bringDeskFront("亮屏");
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (Throwable th) {
            Log.w(TAG, "注册亮屏广播失败：" + th.getMessage());
        }
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            int flags = info == null ? -1 : info.flags;
            boolean filter = info != null
                    && (flags & android.accessibilityservice.AccessibilityServiceInfo
                    .FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
            Log.i(TAG, "守护已连接：flags=" + flags + " 按键过滤=" + filter);
        } catch (Throwable th) {
            Log.w(TAG, "读服务配置失败：" + th.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenOn);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    /**
     * HOME 键过滤。
     *
     * @return true 表示吞掉这一键（不让原桌面起来）
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_HOME) {
            return false;
        }
        Log.i(TAG, "onKeyEvent HOME action=" + event.getAction());
        if (!DeskPrefs.homeGuard(this)) {
            return false;
        }
        if (DeskPrefs.stockAllowed(this)) {
            // 用户刚主动打开了原桌面，这一键留给他
            return false;
        }
        if (HomeRole.isDefault(this)) {
            // 已经是默认桌面：HOME 本来就归我们，不需要过滤
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // 按下就拉，别等抬起 —— 等抬起的话原桌面已经画出来了，看得见闪一下
            bringDeskFront("HOME 键");
        }
        return true;
    }

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
        lastForeground = pkg;
        if (pkg.equals(getPackageName())) {
            // 自己回到前台：放行作废，之后 HOME 键重新接管
            DeskPrefs.clearStockAllow(this);
            return;
        }
        if (!DeskPrefs.homeGuard(this)) {
            return;
        }
        if (DeskPrefs.stockAllowed(this)) {
            // 放行期里用户就在原桌面/或在找东西，不抢
            return;
        }
        if (!isHomePackage(pkg)) {
            return;
        }
        Log.i(TAG, "接管 HOME：从 " + pkg + " 回到桌面");
        bringDeskFront("窗口事件");
    }

    private void bringDeskFront(String why) {
        long now = System.currentTimeMillis();
        if (now - lastTakeover < THROTTLE_MS) {
            return;
        }
        lastTakeover = now;
        Log.i(TAG, "拉起桌面（" + why + "）");
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

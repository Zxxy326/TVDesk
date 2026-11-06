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

    /**
     * 服务连上之后复查前台的时刻（毫秒）。
     *
     * <p>开机时 BOOT_COMPLETED 拉起过一次，但有的固件紧接着又把自己的桌面翻上来，
     * 把 TVDesk 顶掉；而「窗口变化」事件在那之前就发生完了，服务这边一条都收不到。
     * 所以连接后主动查几次前台：还停在原生桌面就再拉一次。
     *
     * <p>第一枪放在 1.5 秒（和 Aerial 一样）：待机被强停之后是「通知监听先复活进程 → 再补无障碍绑定 →
     * 守护这时才连上」，而固件桌面在屏幕亮起那一刻就已经画好了。早期版本第一枪在 3 秒，
     * 用户看到的就是「守在小米桌面好几秒」。后面几枪覆盖固件开机流程较慢的情况。
     */
    private static final long[] CONNECT_CHECKS = {1_500L, 3_000L, 7_000L, 15_000L};

    /**
     * 收到「HOME 键」广播之后再查两次前台（毫秒）。
     *
     * <p>按下 HOME 时系统会发 {@code CLOSE_SYSTEM_DIALOGS}（reason=homekey），比
     * 「原桌面窗口出现」的事件早 200–300ms。用它当发令枪，能在固件桌面画出来之前就动手，
     * 用户看到的是直接回 TVDesk，而不是闪一下再回来（Aerial 在 MFTR0 上同一条路）。
     *
     * <p>两次是因为用量统计有 100–250ms 延迟：第一枪常常还读不到原桌面。
     */
    private static final long[] HOME_KEY_CHECKS = {140L, 420L};

    /**
     * 拉起之后复查的间隔（毫秒）。
     *
     * <p>固件桌面（小米 {@code com.mitv.tvhome}、创维/雷鸟的自家 launcher）经常在我们
     * startActivity 之后立刻把自己再翻回来 —— 它是常驻系统应用，抢前台比第三方快。
     * Aerial 在 MiTV 上的做法是「拉起来 → 1 秒后看一眼前台 → 还是原桌面就再拉」，
     * 并且会等过系统那段 {@code stopAppSwitches} 冻结期（约 5 秒）再补一次。
     *
     * <p>这里就是那套：先 1.2 秒短复查，不行再 6 秒补一枪，最多三轮。
     */
    private static final long RECHECK_SHORT_MS = 1_200L;
    private static final long RECHECK_LONG_MS = 6_000L;
    private static final int RECHECK_MAX = 3;

    /** 亮屏后除了第一枪，再多看两次：屏幕刚亮时前台往往还是空的 */
    private static final long[] SCREEN_ON_CHECKS = {1_500L, 5_000L, 10_000L};

    private long lastTakeover;
    /** 已经为「同一次接管」复查过几轮（防止无限拉） */
    private int recheckRound;
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
            // 屏幕刚亮的那一瞬间前台可能是空的（原桌面还没画出来），
            // 所以多排几次；每次都会重新判断，用户真在别的 App 里就什么都不做。
            for (long delay : SCREEN_ON_CHECKS) {
                handler.postDelayed(takeOverAfterScreenOn, delay);
            }
        }
    };

    /**
     * HOME 键广播。
     *
     * <p>按下 HOME 时系统会发 {@code CLOSE_SYSTEM_DIALOGS}（{@code reason=homekey}）。
     * 这条比「原桌面窗口出现」的无障碍事件早 200–300ms，拿它当发令枪就能在固件桌面
     * 画出来之前动手 —— 用户看到的是直接回 TVDesk，不闪。Aerial 在 MFTR0 上用的同一招。
     *
     * <p>注意：{@code adb shell input keyevent HOME} 注入的按键<b>不会</b>发这条广播，
     * 测这条路径必须用实体遥控器。
     */
    private final BroadcastReceiver homeKey = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DeskPrefs.homeGuard(context)) {
                return;
            }
            String reason = intent.getStringExtra("reason");
            if (reason == null || !reason.toLowerCase(java.util.Locale.ROOT).contains("home")) {
                return;
            }
            for (long delay : HOME_KEY_CHECKS) {
                handler.postDelayed(takeOverAfterHomeKey, delay);
            }
        }
    };

    private final Runnable takeOverAfterHomeKey = new Runnable() {
        @Override
        public void run() {
            if (!DeskPrefs.homeGuard(HomeGuardService.this)) {
                return;
            }
            if (DeskPrefs.stockAllowed(HomeGuardService.this)) {
                return;
            }
            String foreground = foregroundNow();
            if (foreground == null || getPackageName().equals(foreground)) {
                return;
            }
            if (!isHomePackage(foreground)) {
                return;
            }
            Log.i(TAG, "HOME 键广播：前台是 " + foreground + "，抢在窗口事件之前拉回");
            bringDeskFront("HOME 键广播");
        }
    };

    /**
     * 盯着系统的 ADB 开关。
     *
     * <p>这台固件在待机/唤醒时会把 {@code adb_enabled} 改成 0，网络调试就断了 ——
     * 之后遥控器怎么点都连不上，只能去开发者选项里再开一次。有了
     * {@code WRITE_SECURE_SETTINGS} 就能在它被关掉的瞬间写回去（Aerial 的 {@code keep_adb}）。
     */
    private final android.database.ContentObserver adbObserver =
            new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    GuardSetup.keepAdbIfWanted(HomeGuardService.this);
                }
            };

    private final Runnable takeOverAfterScreenOn = new Runnable() {
        @Override
        public void run() {
            // 不再用 lastForeground：那是「上一次窗口事件」留下的旧值，
            // 亮屏这一刻它可能已经是几分钟前的了。重新读一次实时前台
            String foreground = foregroundNow();
            HomeTakeoverPolicy.Evidence ev = evidence(foreground);
            HomeTakeoverPolicy.Decision decision =
                    HomeTakeoverPolicy.decide(HomeGuardService.this, ev);
            GuardRuntimeState.noteDecision(decision.reason);
            if (decision.allowed()) {
                Log.i(TAG, "亮屏后仍在原桌面（" + foreground + "），拉回 TVDesk");
                bringDeskFront("亮屏");
            } else if (decision.action == HomeTakeoverPolicy.RETRY) {
                // 前台还没画出来：过一会儿再看，不立刻抢
                scheduleRecheck();
            }
        }
    };

    /**
     * 连接后复查：前台是不是还停在原生桌面。
     *
     * <p>这条是「开机进 TVDesk」的兜底。BOOT_COMPLETED 里已经拉过，但那之后
     * 固件可能又把原生桌面翻上来；服务这时才连上，窗口事件都已经过去了。
     */
    private final Runnable checkAfterConnect = new Runnable() {
        @Override
        public void run() {
            if (!DeskPrefs.homeGuard(HomeGuardService.this)) {
                return;
            }
            if (DeskPrefs.stockAllowed(HomeGuardService.this)) {
                // 用户主动去原桌面找东西，别拽回来
                return;
            }
            String foreground = foregroundNow();
            HomeTakeoverPolicy.Evidence ev = evidence(foreground);
            HomeTakeoverPolicy.Decision decision =
                    HomeTakeoverPolicy.decide(HomeGuardService.this, ev);
            GuardRuntimeState.noteDecision(decision.reason);
            // 不再用「强停前在不在桌面」这种旧标记放宽：那是上一段时间的事实，
            // 拿它给「前台未知」背书，等于把用户后来打开的应用一起判成该接管
            if (decision.allowed()) {
                Log.i(TAG, "连接后复查：前台=" + foreground + "，拉回 TVDesk");
                bringDeskFront("开机/唤醒复查");
            } else if (decision.action == HomeTakeoverPolicy.RETRY) {
                scheduleRecheck();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        GuardRuntimeState.noteConnected();
        super.onServiceConnected();
        try {
            registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (Throwable th) {
            Log.w(TAG, "注册亮屏广播失败：" + th.getMessage());
        }
        // HOME 键广播：抢在固件桌面画出来之前动手（比窗口事件早 200–300ms）
        try {
            IntentFilter home = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Android 13 起注册非系统广播要显式声明「不导出」
                registerReceiver(homeKey, home, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(homeKey, home);
            }
        } catch (Throwable th) {
            Log.w(TAG, "注册 HOME 键广播失败：" + th.getMessage());
        }
        // 系统把 ADB 关掉时立刻写回（有 WRITE_SECURE_SETTINGS 才有意义）
        try {
            getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(
                            android.provider.Settings.Global.ADB_ENABLED),
                    false, adbObserver);
            GuardSetup.keepAdbIfWanted(this);
        } catch (Throwable th) {
            Log.w(TAG, "盯 ADB 开关失败：" + th.getMessage());
        }
        // 开机兜底：主动查几次前台（见 CONNECT_CHECKS）
        for (long delay : CONNECT_CHECKS) {
            handler.postDelayed(checkAfterConnect, delay);
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
        try {
            unregisterReceiver(homeKey);
        } catch (Throwable ignored) {
        }
        try {
            getContentResolver().unregisterContentObserver(adbObserver);
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
        // 这里**不再**用 HomeRole.isDefault 放行。
        //
        // 原来写的是「已经是默认桌面就不用过滤」，但那套假设在这台固件上不成立：
        // 实测即使 TVDesk 拿着 HOME 这个 role（isDefault=true），按 HOME 仍然是
        // com.mitv.tvhome 被拉起来 —— 固件自己抢走了这一键。放行的后果就是
        // 用户看到「HOME 回系统桌面」，然后靠窗口事件兜底那一下再弹回来，中间闪一下。
        // 所以只要是接管状态就自己吞掉这一键，不管 role 在谁手里。
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
            // 记下来：万一之后被系统强停，唤醒时要回到这里
            DeskPrefs.setWasHome(this, true);
            return;
        }
        if (isUserApp(pkg)) {
            // 用户在别的 App 里 —— 强停之后不该把他拽回来。
            // 只认「有启动入口的 App」：唤醒瞬间那些过渡窗口（SystemUI、锁屏、
            // 固件自己的启动页）也会来一条窗口事件，它们没有启动入口，
            // 不能拿它们当「用户在看别的 App」—— 早期版本就是这么把标记清掉的，
            // 结果唤醒后迟迟不接管。
            DeskPrefs.setWasHome(this, false);
        }
        HomeTakeoverPolicy.Evidence ev = evidence(pkg);
        HomeTakeoverPolicy.Decision decision = HomeTakeoverPolicy.decide(this, ev);
        GuardRuntimeState.noteDecision(decision.reason);
        if (!decision.allowed()) {
            return;
        }
        Log.i(TAG, "接管 HOME：从 " + pkg + " 回到桌面");
        bringDeskFront("窗口事件");
    }

    /**
     * 此刻前台是哪个包。
     *
     * <p>先主动读活动窗口（开机那一刻还没有窗口事件，只剩这条路），读不到再退回
     * {@link #lastForeground}。两个都没有就返回 null —— 调用方据此「什么都不做」，
     * 绝不会在信息不足时抢前台。
     */
    private String currentForeground() {
        try {
            android.view.accessibility.AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                if (pkg != null && pkg.length() > 0) {
                    return pkg.toString();
                }
            }
        } catch (Throwable th) {
            Log.w(TAG, "读活动窗口失败：" + th.getMessage());
        }
        return lastForeground;
    }

    /**
     * 此刻前台是哪个包 —— 两条路都试。
     *
     * <p>先读活动窗口（无障碍在的时候最准、最即时）；读不到再问用量统计
     * （{@link UsageFront}）。后者有 100–250ms 延迟，但<b>不需要无障碍绑定</b>，
     * 正好补上「刚复活、还没绑上」那一段。
     *
     * @return 两条路都拿不到时返回 null，调用方据此什么都不做
     */
    private String foregroundNow() {
        String pkg = currentForeground();
        if (pkg != null && !getPackageName().equals(pkg)) {
            return pkg;
        }
        String usage = UsageFront.foreground(this);
        return usage != null ? usage : pkg;
    }

    /** 把当前证据打包给统一判定 */
    private HomeTakeoverPolicy.Evidence evidence(String foreground) {
        HomeTakeoverPolicy.Evidence ev = new HomeTakeoverPolicy.Evidence();
        ev.guardOn = DeskPrefs.homeGuard(this);
        ev.temporaryBypass = DeskPrefs.stockAllowed(this);
        ev.screenInteractive = HomeTakeoverPolicy.screenInteractive(this);
        ev.foreground = foreground;
        ev.foregroundIsHome = foreground != null && isHomePackage(foreground);
        ev.selfPackage = getPackageName();
        ev.recoverAttempts = GuardRuntimeState.recoverAttempts();
        ev.evidenceAt = System.currentTimeMillis();
        ev.now = ev.evidenceAt;
        return ev;
    }

    private void bringDeskFront(String why) {
        long now = System.currentTimeMillis();
        if (now - lastTakeover < THROTTLE_MS) {
            return;
        }
        lastTakeover = now;
        recheckRound = 0;
        startDesk(why);
    }

    /** 真正启动（不带节流）：复查路径要用它 */
    private void startDesk(String why) {
        Log.i(TAG, "拉起桌面（" + why + "）");
        try {
            // 不要带 CATEGORY_HOME：Aerial 在 MiTV 上踩过 —— 带了之后系统会另起一个
            // 「home 类型」的任务，每次都是新的 MainActivity，焦点和页面状态全丢
            Intent desk = new Intent(this, DeskActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(desk);
        } catch (Throwable th) {
            Log.w(TAG, "拉不起来：" + th.getMessage());
        }
        scheduleRecheck();
    }

    private void scheduleRecheck() {
        handler.removeCallbacks(recheckAfterBring);
        if (recheckRound >= RECHECK_MAX) {
            return;
        }
        long delay = recheckRound == 0 ? RECHECK_SHORT_MS : RECHECK_LONG_MS;
        recheckRound++;
        handler.postDelayed(recheckAfterBring, delay);
    }

    /**
     * 拉起之后看一眼前台：又被原桌面抢回来就再拉一次。
     *
     * <p>这是「待机唤醒后停在小米桌面」的真正解药 —— 我们本来只拉一次，
     * 固件桌面随后把自己翻回来，之后就没人管了。
     */
    private final Runnable recheckAfterBring = new Runnable() {
        @Override
        public void run() {
            if (!DeskPrefs.homeGuard(HomeGuardService.this)) {
                return;
            }
            if (DeskPrefs.stockAllowed(HomeGuardService.this)) {
                return;
            }
            String foreground = currentForeground();
            if (foreground == null || getPackageName().equals(foreground)) {
                // 自己在前台：成了
                recheckRound = 0;
                return;
            }
            if (!isHomePackage(foreground)) {
                // 用户已经打开了别的应用，不打扰
                recheckRound = 0;
                return;
            }
            Log.i(TAG, "拉起后又被原桌面抢回（" + foreground + "），第 "
                    + recheckRound + " 次重试");
            startDesk("被抢回后重试");
        }
    };

    /** 有启动入口的包缓存（窗口事件很频繁，不能每次都问 PackageManager） */
    private final java.util.Map<String, Boolean> userAppCache = new java.util.HashMap<>();

    /**
     * 这个包是不是「用户能自己打开的 App」。
     *
     * <p>判据是有没有 {@code ACTION_MAIN + CATEGORY_LAUNCHER} 的入口 ——
     * SystemUI、锁屏、固件启动页这类过渡窗口都没有，所以不算「用户在别处」。
     */
    private boolean isUserApp(String pkg) {
        Boolean cached = userAppCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        boolean yes;
        try {
            yes = getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Throwable th) {
            yes = false;
        }
        if (userAppCache.size() > 400) {
            userAppCache.clear();
        }
        userAppCache.put(pkg, yes);
        return yes;
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

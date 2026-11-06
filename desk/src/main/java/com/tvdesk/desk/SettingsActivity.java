package com.tvdesk.desk;

import com.tvdesk.core.ui.DeskToast;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.tvdesk.core.feed.SourceConfig;
import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.motion.PressFx;
import com.tvdesk.desk.widget.TileWall;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页。
 *
 * <h3>为什么长得像「模块页」</h3>
 * 以前这里是「两列设置行」：面包屑 + 一排排小行，和其它模块（音乐 / B 站 / 应用）完全两个样子，
 * 从桌面点进来像换了个 App。现在结构和它们一致：左上角一颗胶囊（点了回桌面）、中间一排页签、
 * 下面三列卡片墙 —— 焦点、放大、白环、回弹全用同一套 {@link TileWall}，手感统一。
 *
 * <h3>为什么用卡片不用行</h3>
 * 电视上距离远，一行小字（标题 + 右侧开关）经常看不清状态。卡片能放三行：
 * <b>是什么 / 现在什么状态 / 按下去会发生什么</b>，一眼能读完。
 */
public class SettingsActivity extends DeskAdaptActivity {

    private static final String TAG = "DeskSettings";
    private static final int REQ_HOME = 0x51;

    /** 页签：桌面 / 播放 / 关于 */
    private static final String[] TABS = {"桌面", "播放", "关于"};
    private static final int TAB_DESK = 0;
    private static final int TAB_PLAY = 1;
    private static final int TAB_ABOUT = 2;

    private TileWall grid;
    private LinearLayout switcher;
    private LinearLayout navItems;

    private final List<Item> items = new ArrayList<>();
    private final CardAdapter adapter = new CardAdapter();
    private int tab = TAB_DESK;

    private boolean keepPlaying;
    private boolean showClock;
    private boolean showWeather;
    private boolean backdropVideo;
    private boolean backdropStrict4k;
    private int backdropDwell;

    /**
     * 一张设置卡。
     *
     * <p>{@code key} 是稳定标识（页签切换、状态刷新时按它保住焦点），
     * {@code value} 是当前状态那几个字（卡片第二行）。
     */
    private static final class Item {
        String key;
        String title;
        String value;
        String note;
        Runnable action;

        Item(String key, String title, String note, Runnable action) {
            this.key = key;
            this.title = title;
            this.note = note;
            this.action = action;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        FocusScaler.disableSystemHighlightTree(getWindow().getDecorView());

        grid = findViewById(R.id.settings_grid);
        switcher = findViewById(R.id.settings_switcher);
        navItems = findViewById(R.id.settings_nav_items);

        switcher.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        keepPlaying = DeskPrefs.keepPlayingOnLeave(this);
        showClock = DeskPrefs.showClock(this);
        showWeather = DeskPrefs.showWeather(this);
        backdropVideo = DeskPrefs.backdropVideo(this);
        backdropStrict4k = DeskPrefs.backdropStrict4k(this);
        backdropDwell = DeskPrefs.backdropDwellSec(this);

        buildTabs();
        setupGrid();
        buildItems();
        selectTab(TAB_DESK, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置/无障碍页回来：状态可能变了，整页刷一遍值
        buildItems();
        grid.notifyDataSetChanged();
        if (grid.findFocus() == null) {
            grid.focusPosition(0);
        }
    }

    /** 换电视时用来比对尺寸：卡片高按设计稿是 200dp */
    @Override
    protected String adaptProbe(Resources res) {
        return "设置卡片高 解析为 " + res.getDimensionPixelSize(R.dimen.settings_row_height) + "px";
    }

    // ---------------------------------------------------------------- 页签

    private void buildTabs() {
        navItems.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(TABS[i]);
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setBackgroundResource(R.drawable.bg_nav_item);
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(true);
            chip.setClickable(true);
            chip.setTextColor(androidx.appcompat.content.res.AppCompatResources
                    .getColorStateList(this, R.color.nav_item_text));
            chip.setSelected(i == tab);
            chip.setPadding(dp(20), 0, dp(20), 0);
            // 高度和其它模块的页签一致（32dp 在 44dp 的胶囊里，上下各留 6dp 给聚焦放大）
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            if (i > 0) {
                lp.setMarginStart(dp(4));
            }
            chip.setLayoutParams(lp);
            FocusScaler.attach(chip, FocusScaler.SCALE_FOCUSED);
            // 焦点扫过哪颗，内容就换到哪一组：和音乐 / 信号源页签一个行为，
            // 用户按左右时不用再按一次确定才看到内容变
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus && tab != index) {
                        selectTab(index, false);
                    }
                }
            });
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index, true);
                }
            });
            navItems.addView(chip);
        }
    }

    private void selectTab(int target, boolean focusContent) {
        tab = target;
        for (int i = 0; i < navItems.getChildCount(); i++) {
            navItems.getChildAt(i).setSelected(i == target);
        }
        buildItems();
        grid.notifyDataSetChanged();
        grid.scrollToTop();
        if (focusContent) {
            // 用户点了页签：和其它模块一致，焦点跟着进内容
            grid.post(new Runnable() {
                @Override
                public void run() {
                    grid.focusPosition(0);
                }
            });
        }
    }

    /** 页签到边：最左/最右一颗别再往外跑 */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            // 和其它模块同一个口径（见 BackTop）：焦点还在下面的设置卡上时，
            // 返回只把焦点收回顶栏页签；已经在顶栏（或左上角「桌面」）才真的退出设置。
            if (BackTop.toTopBar("设置", navItems, new Runnable() {
                @Override
                public void run() {
                    if (tab >= 0 && tab < navItems.getChildCount()) {
                        navItems.getChildAt(tab).requestFocus();
                    }
                }
            }, switcher)) {
                return true;
            }
            finish();
            return true;
        }
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                || event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
            View focused = getCurrentFocus();
            if (focused != null && navItems.indexOfChild(focused) >= 0) {
                int index = navItems.indexOfChild(focused);
                if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_LEFT && index == 0) {
                    return true;
                }
                if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        && index == navItems.getChildCount() - 1) {
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------- 卡片

    private void setupGrid() {
        grid.setAdapter(adapter);
        grid.setNumColumns(3);
        grid.setSpacingDp(20f, 20f);
        grid.setEdgeListener(new TileWall.OnEdgeListener() {
            @Override
            public boolean onEdge(int keyCode, int index) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                    // 第一排往上：回当前页签
                    View chip = navItems.getChildAt(Math.min(tab, navItems.getChildCount() - 1));
                    if (chip != null) {
                        chip.requestFocus();
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private void buildItems() {
        items.clear();
        if (tab == TAB_DESK) {
            add("source", "内容源",
                    "影视、B 站这些栏目都从这个源取内容。没配源时桌面只显示豆瓣榜单。",
                    new Runnable() {
                        @Override
                        public void run() {
                            openSourceDialog();
                        }
                    });
            add("guard", "桌面守护",
                    "按 HOME 键回到 TVDesk。装好后会自动开启，不用去系统设置里找。",
                    new Runnable() {
                        @Override
                        public void run() {
                            onGuardClicked();
                        }
                    });
            add("home", "设为默认桌面",
                    "让电视开机直接进 TVDesk。部分品牌的固件不支持，那就靠上面的桌面守护。",
                    new Runnable() {
                        @Override
                        public void run() {
                            requestHome();
                        }
                    });
            add("keepadb", "保持 ADB 调试",
                    "这台电视待机会把 ADB 关掉，之后连不上。开着它，被关掉的那一刻自动写回。"
                            + "需要 adb 授权过（WRITE_SECURE_SETTINGS），没授权时这开关不起作用。",
                    new Runnable() {
                        @Override
                        public void run() {
                            DeskPrefs.setKeepAdb(SettingsActivity.this, !DeskPrefs.keepAdb(SettingsActivity.this));
                            GuardSetup.keepAdbIfWanted(SettingsActivity.this);
                            refreshValues();
                        }
                    });
            add("system", "电视系统设置",
                    "网络、显示、声音、HDMI 都在电视自己的设置里，这里只是入口。",
                    new Runnable() {
                        @Override
                        public void run() {
                            openSystemSettings();
                        }
                    });
        } else if (tab == TAB_PLAY) {
            add("keep", "切走继续播放",
                    "切到别的版块或按 HOME 退到后台时，正在放的视频/音乐是否继续。",
                    new Runnable() {
                        @Override
                        public void run() {
                            keepPlaying = !keepPlaying;
                            DeskPrefs.setKeepPlayingOnLeave(SettingsActivity.this, keepPlaying);
                            refreshValues();
                        }
                    });
            add("clock", "显示时钟",
                    "桌面右上角的时间。沉浸轮播时它是唯一保留的桌面控件。",
                    new Runnable() {
                        @Override
                        public void run() {
                            showClock = !showClock;
                            DeskPrefs.setShowClock(SettingsActivity.this, showClock);
                            refreshValues();
                        }
                    });
            add("weather", "显示天气",
                    "时间和天气分开两个开关：只留一个也说得通。",
                    new Runnable() {
                        @Override
                        public void run() {
                            showWeather = !showWeather;
                            DeskPrefs.setShowWeather(SettingsActivity.this, showWeather);
                            refreshValues();
                        }
                    });
            add("backdrop", "动态背景",
                    "闲置进沉浸轮播时，有视频的条目会静音播 8 秒再切下一条（B 站热门）。"
                            + "关掉之后只轮播静态图，老盒子上更省功耗。",
                    new Runnable() {
                        @Override
                        public void run() {
                            backdropVideo = !backdropVideo;
                            DeskPrefs.setBackdropVideo(SettingsActivity.this, backdropVideo);
                            refreshValues();
                        }
                    });
            add("backdrop4k", "背景只要 4K",
                    "打开后背景视频只接受 2160p 轨道，拿不到就回静态图，不拿 1080p 顶上去。"
                            + "需要设备硬解和账户权限都到位，多数情况下画面会更少。",
                    new Runnable() {
                        @Override
                        public void run() {
                            backdropStrict4k = !backdropStrict4k;
                            DeskPrefs.setBackdropStrict4k(SettingsActivity.this, backdropStrict4k);
                            refreshValues();
                        }
                    });
            add("backdropdwell", "背景停留",
                    "每条背景停多久再换下一条。有视频的按首帧之后的时长算，"
                            + "视频比这短就播完即切。",
                    new Runnable() {
                        @Override
                        public void run() {
                            // 6 → 8 → 10 → 15 → 6 循环，四个档位够用
                            switch (backdropDwell) {
                                case 6:
                                    backdropDwell = 8;
                                    break;
                                case 8:
                                    backdropDwell = 10;
                                    break;
                                case 10:
                                    backdropDwell = 15;
                                    break;
                                default:
                                    backdropDwell = 6;
                                    break;
                            }
                            DeskPrefs.setBackdropDwellSec(SettingsActivity.this, backdropDwell);
                            refreshValues();
                        }
                    });
        } else {
            add("update", "检查更新",
                    "不用等后台自动查，这里立刻查一次；有新版会问你要不要下载安装。",
                    new Runnable() {
                        @Override
                        public void run() {
                            checkUpdate();
                        }
                    });
            add("manifest", "更新源",
                    "留空就用内置的国内镜像；想用自建源再填一个地址。",
                    new Runnable() {
                        @Override
                        public void run() {
                            editManifest();
                        }
                    });
            add("version", "版本",
                    "当前安装的版本号。更新会保留配置和登录状态。",
                    new Runnable() {
                        @Override
                        public void run() {
                            checkUpdate();
                        }
                    });
        }
        refreshValues();
    }

    private void add(String key, String title, String note, Runnable action) {
        items.add(new Item(key, title, note, action));
    }

    /** 只刷新「当前值」那几行字，不重建卡片（避免焦点抖一下） */
    private void refreshValues() {
        for (Item item : items) {
            item.value = valueOf(item.key);
        }
        grid.notifyDataSetChanged();
    }

    private String valueOf(String key) {
        switch (key) {
            case "source": {
                String url = SourceConfig.current();
                if (TextUtils.isEmpty(url)) {
                    return "未配置";
                }
                String builtin = com.tvdesk.core.feed.BuiltinSources.displayName(url);
                return TextUtils.isEmpty(builtin) ? url : builtin;
            }
            case "guard": {
                // 「开着」要看两样：列表里有它 + 无障碍总开关是 1。
                // 只看列表会误报 —— 系统把总开关关掉之后名字还留着，
                // 那时按 HOME 已经回固件桌面了。
                if (GuardSetup.isGuardReady(this)) {
                    return DeskPrefs.homeGuard(this) ? "已开启" : "已暂停";
                }
                if (GuardSetup.isServiceEnabled(this)) {
                    return "已掉线 · 按确定修复";
                }
                return GuardSetup.hasWriteSecureSettings(this) ? "待开启" : "未开启";
            }
            case "home":
                return HomeRole.isDefault(this) ? "已设为默认桌面" : "未设置";
            case "keepadb":
                return DeskPrefs.keepAdb(this) ? "开" : "关";
            case "keep":
                return keepPlaying ? "开" : "关";
            case "clock":
                return showClock ? "显示" : "隐藏";
            case "weather":
                return showWeather ? "显示" : "隐藏";
            case "backdrop":
                return backdropVideo ? "开" : "关";
            case "backdrop4k":
                return backdropStrict4k ? "仅 4K" : "允许降级";
            case "backdropdwell":
                return backdropDwell + " 秒";
            case "update":
                return "当前 " + com.tvdesk.desk.update.UpdateClient.currentVersionName(this);
            case "manifest": {
                String url = DeskPrefs.updateManifestUrl(this);
                return TextUtils.isEmpty(url) ? "内置镜像" : "自定义";
            }
            case "version":
                return "TVDesk " + com.tvdesk.desk.update.UpdateClient.currentVersionName(this);
            default:
                return "";
        }
    }

    // ---------------------------------------------------------------- 动作用

    private void openSourceDialog() {
        new SourceDialog(this, SourceConfig.current(), SourceConfig.history(),
                new SourceDialog.Listener() {
                    @Override
                    public void onApply(String url) {
                        boolean changed = SourceConfig.apply(url);
                        Log.i(TAG, "应用内容源：" + url);
                        refreshValues();
                        DeskToast.makeText(SettingsActivity.this,
                                changed ? R.string.settings_toast_applied
                                        : R.string.settings_toast_unchanged,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onClear() {
                        SourceConfig.clear();
                        refreshValues();
                        DeskToast.makeText(SettingsActivity.this,
                                R.string.settings_toast_cleared, Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    /**
     * 桌面守护那张卡：没开就当场把它打开（有权限时），开不了才送去系统设置页。
     */
    private void onGuardClicked() {
        // 判「能不能用」而不是「名字在不在」：系统关掉总开关之后名字还留着，
        // 那种状态下按 HOME 已经回固件桌面了，得走下面的修复分支
        if (!GuardSetup.isGuardReady(this)) {
            if (GuardSetup.ensureEnabled(this)) {
                // 刚写进去，服务要几秒才连上，这里先给个即时反馈
                DeskToast.makeText(this, "已开启桌面守护 · 按 HOME 会回到 TVDesk",
                        Toast.LENGTH_LONG).show();
                refreshValues();
                return;
            }
            DeskToast.makeText(this, "请在系统无障碍里打开「TVDesk 桌面守护」",
                    Toast.LENGTH_LONG).show();
            HomeGuardService.openSettings(this);
            return;
        }
        // 已经开着：给一个「临时停用」的出口
        boolean paused = !DeskPrefs.homeGuard(this);
        new android.app.AlertDialog.Builder(this, R.style.DeskDialogTheme)
                .setTitle("桌面守护已开启")
                .setMessage(paused
                        ? "现在是暂停状态：服务还在，但按 HOME 不会拉回来了。"
                        : "按 HOME 会回到 TVDesk。想临时用回电视自带的桌面，就先停用。")
                .setPositiveButton(paused ? "恢复接管" : "临时停用",
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                DeskPrefs.setHomeGuard(SettingsActivity.this,
                                        !DeskPrefs.homeGuard(SettingsActivity.this));
                                refreshValues();
                            }
                        })
                .setNegativeButton("去系统设置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        HomeGuardService.openSettings(SettingsActivity.this);
                    }
                })
                .show();
    }

    private void requestHome() {
        if (HomeRole.isDefault(this)) {
            if (!HomeRole.openHomeSettings(this)) {
                DeskToast.makeText(this, R.string.settings_toast_home_failed, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (!HomeRole.request(this, REQ_HOME)) {
            DeskToast.makeText(this, R.string.settings_toast_home_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME) {
            refreshValues();
            DeskToast.makeText(this, HomeRole.isDefault(this)
                    ? "已设为电视桌面 · 按 HOME 键就能回来"
                    : "还没设为默认桌面", Toast.LENGTH_LONG).show();
        }
    }

    private void openSystemSettings() {
        if (SystemSettings.open(this)) {
            return;
        }
        DeskToast.makeText(this, R.string.settings_toast_no_system_settings, Toast.LENGTH_LONG).show();
    }

    /** 更新源：弹一个输入框，留空回到内置镜像 */
    private void editManifest() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(DeskPrefs.updateManifestUrl(this));
        input.setHint("留空 = 用内置镜像");
        input.setTextSize(16f);
        new android.app.AlertDialog.Builder(this, R.style.DeskDialogTheme)
                .setTitle("更新源地址")
                .setView(input)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        DeskPrefs.setUpdateManifestUrl(SettingsActivity.this,
                                input.getText() == null ? "" : input.getText().toString().trim());
                        refreshValues();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 检查更新：拉清单 → 有新版弹窗（含忽略）→ 下载 → 拉起安装 */
    private void checkUpdate() {
        final String manifest = DeskPrefs.updateManifestUrl(this);
        setValue("update", "检查中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final com.tvdesk.desk.update.UpdateClient.Info info =
                        com.tvdesk.desk.update.UpdateClient.check(
                                getApplicationContext(), manifest);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshValues();
                        Log.i(TAG, "checkUpdate 结果："
                                + (info == null ? "null" : (info.versionName + "/" + info.versionCode
                                + " newer=" + info.newer)));
                        if (info == null) {
                            DeskToast.makeText(SettingsActivity.this,
                                    com.tvdesk.desk.update.UpdateClient.lastError(),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (!info.newer) {
                            DeskToast.makeText(SettingsActivity.this,
                                    "已是最新（" + com.tvdesk.desk.update.UpdateClient
                                            .currentVersionName(SettingsActivity.this) + "）",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        UpdatePrompt.show(SettingsActivity.this, info);
                    }
                });
            }
        }, "desk-update").start();
    }

    /** 临时改一张卡的第二行（例如「检查中…」） */
    private void setValue(String key, String text) {
        for (Item item : items) {
            if (item.key.equals(key)) {
                item.value = text;
                break;
            }
        }
        grid.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- 卡片适配

    private final class CardAdapter implements TileWall.Adapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public String keyAt(int index) {
            return index >= 0 && index < items.size() ? items.get(index).key : null;
        }

        @Override
        public View getView(int index, View convertView, ViewGroup parent) {
            View root = convertView;
            if (root == null) {
                root = LayoutInflater.from(SettingsActivity.this)
                        .inflate(R.layout.item_settings_card, parent, false);
            }
            final Item item = items.get(index);
            TextView title = root.findViewById(R.id.settings_card_title);
            TextView value = root.findViewById(R.id.settings_card_value);
            TextView note = root.findViewById(R.id.settings_card_note);
            title.setText(item.title);
            value.setText(item.value);
            note.setText(item.note);
            root.setTag(item.key);
            // 和其它模块的卡片一样的聚焦放大（1.05 就够，设置卡是整块面板）
            FocusScaler.attach(root, FocusScaler.SCALE_FOCUSED);
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PressFx.squish(v);
                    if (item.action != null) {
                        item.action.run();
                    }
                }
            });
            return root;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

package com.tvdesk.desk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.content.res.AppCompatResources;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.motion.RevealAnim;
import com.tvdesk.desk.widget.AppTileArt;
import com.tvdesk.desk.widget.AppTileView;
import com.tvdesk.desk.widget.GridScroll;
import com.tvdesk.desk.widget.LayoutStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * 应用模块页（内嵌在桌面的 module_host 里）：设备本地已安装应用的卡片墙。
 *
 * <h3>风格基准</h3>
 * 布局和 B 站页一字不差地同一套（左上胶囊 + 居中页签 + 子页签 + 6 列卡片墙 + FocusScaler
 * 1.08/1.06 + RevealAnim），只有卡片图框改成方的——应用图标是方的，套 16:10 会切掉左右。
 *
 * <h3>五个栏目</h3>
 * 可启动 / 第三方 / 系统 / 全部 / 搜索。同一份 {@link Entry} 目录做四种过滤 + 一种搜索，
 * 所以切栏目不会再打一遍 PackageManager——扫一次设备要读上百个图标，那是本页最慢的一步。
 *
 * <h3>为什么全在后台线程扫</h3>
 * {@code getApplicationIcon} 会从磁盘解 APK 里的图，机子慢的时候一百个应用能跑几百毫秒到几秒。
 * 放主线程就是开页一次卡顿。结果落回 {@link #catalog}，之后所有栏目切换都是纯内存过滤，
 * 切走再回来不再重扫（除非用户在胶囊菜单里点「重新扫描」）。
 *
 * <h3>焦点</h3>
 * 和音乐页/B 站页同一套：左右走几何（页签同行），上下在「页签 ↔ 子页签 ↔ 卡片墙」之间手动路由，
 * 卡片墙上下按下标 ± 列数自己算。
 *
 * <h3>两个操作入口</h3>
 * 确认键＝打开应用（打不开的包直接跳系统「应用信息」，不给死路）；
 * 长按确认＝操作弹窗（打开 / 应用信息 / 卸载）。卸载放长按而不是常驻按钮，
 * 是按「危险操作要收起来」的既有约定做的：误按一次就掉一个应用，代价比多按一次大。
 */
public class AppPage extends FrameLayout implements ModulePage {

    private static final String TAG = "AppPage";

    /**
     * 页签。第一个是默认页：电视上九成时间是找「自己装的那个 App」，
     * 而不是翻系统自带的几十个预装件，所以「用户安装」排头、开页就停在这儿。
     */
    private static final String[] SECTIONS = {"用户安装", "系统", "全部", "搜索"};

    private static final int SEC_USER = 0;
    private static final int SEC_SYSTEM = 1;
    /** 这一栏的页签画成放大镜图标（只放图标，不放字） */
    private static final int SEC_ALL = 2;
    private static final int SEC_SEARCH = 3;

    /** 子页签是排序方式，三个栏目共用一套 */
    private static final String[] SORTS = {"按名称", "按更新时间", "按安装时间"};

    /** 「我的排序」：清掉用户自定义、回到默认排序 */
    private static final int SORT_CUSTOM = 99;

    private static final int SORT_NAME = 0;
    private static final int SORT_UPDATE = 1;
    private static final int SORT_INSTALL = 2;

    /** 搜索防抖，和音乐页/B 站页同值 */
    private static final long SEARCH_DEBOUNCE_MS = 320L;

    /** 整页背景的目标不透明度：应用图标多为高饱和色块，压得比 B 站页更暗 */
    private static final float BG_ALPHA = 0.42f;

    /** 只在主线程用（排序只发生在 rebuild()），所以共用一个实例没问题 */
    private static final java.text.Collator COLLATOR =
            java.text.Collator.getInstance(Locale.CHINA);

    /** 一个安装包。图标是 Drawable，所以这份目录只在内存里活一轮 */
    static final class Entry {
        String pkg = "";
        String name = "";
        Drawable icon;
        /** 应用的电视横幅（leanback 应用才有），磁贴优先用它 */
        Drawable banner;
        String version = "";
        long installTime;
        long updateTime;
        boolean system;
        boolean launchable;
        /** 有电视端入口（LEANBACK_LAUNCHER）：只有这类应用的横幅才可信 */
        boolean leanback;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 重扫时旧结果作废：回调里比对它 */
    private int epoch;
    private boolean released;

    /** 设备的包目录。null 表示还没扫过 */
    private List<Entry> catalog;
    private boolean catalogLoading;
    private String catalogError;

    private final List<Entry> rows = new ArrayList<>();
    private final CardAdapter adapter = new CardAdapter();

    /**
     * 用户自己排的顺序。
     *
     * <p>电视上装的应用有几十个，常用的就那么几个，每次都要重新翻。
     * 做法跟 Aerial TV 的 {@code LayoutStore.kt} 一样：把排好的顺序记下来，
     * 下次打开照旧；新装的应用自动插到最前面（不用重排一遍就能看见）。
     */
    private LayoutStore layout;

    private int section = SEC_USER;
    private int sort = SORT_NAME;

    /**
     * 「移动位置」模式（照搬 Aerial 的 tile menu → Move）：
     * 长按图标选「移动位置」后进入，方向键把这张卡挪到相邻格，确定放下、返回撤销。
     */
    private boolean moving;
    private String movingPkg;
    /** 进入移动前的顺序，返回键撤销用 */
    private List<String> moveBackup;
    /** 移动模式的提示条，只在移动中出现 */
    private TextView moveHint;
    private String query = "";
    private boolean menuOpen;

    /** 已经贴上去的图标，同一个不重复加载，避免焦点在卡片间移动时闪 */
    private Drawable shownIcon;

    private final Runnable searchTask = new Runnable() {
        @Override
        public void run() {
            runSearch();
        }
    };

    private LinearLayout switcher;
    private LinearLayout switcherMenu;
    private LinearLayout navItems;
    private HorizontalScrollView subScroll;
    private LinearLayout subNav;
    private GridView grid;
    private TextView title;
    private TextView subtitle;
    private TextView emptyView;
    private EditText searchInput;
    private ImageView bgImage;
    private ProgressBar loadingView;

    /** 回桌面，由宿主 DeskActivity 传入 */
    private final Runnable onClose;

    /** 跳出到系统页之后再回来，包列表可能变了；这个标记由 {@link #onEnter()} 消费 */
    private boolean staleAfterLeave;

    public AppPage(Context context, Runnable onClose) {
        super(context);
        this.onClose = onClose;
        LayoutInflater.from(context).inflate(R.layout.activity_apps, this, true);

        switcher = findViewById(R.id.apps_switcher);
        switcherMenu = findViewById(R.id.apps_menu);
        navItems = findViewById(R.id.apps_nav_items);
        subScroll = findViewById(R.id.apps_sub_scroll);
        subNav = findViewById(R.id.apps_sub_nav);
        grid = findViewById(R.id.apps_grid);
        if (grid instanceof com.tvdesk.desk.widget.PageGridView) {
            // 整页铺满的墙：最左/最右再按就停住，别让焦点飞到左上角胶囊上
            ((com.tvdesk.desk.widget.PageGridView) grid).setKeepFocusAtEdges(true);
        }
        title = findViewById(R.id.apps_title);
        subtitle = findViewById(R.id.apps_subtitle);
        emptyView = findViewById(R.id.apps_empty);
        searchInput = findViewById(R.id.apps_search);
        bgImage = findViewById(R.id.apps_bg);
        loadingView = findViewById(R.id.apps_loading);

        buildSwitcher();
        buildSwitcherMenu();
        buildNav();
        // 排序存储要先建好：buildSubNav 会看有没有自定义顺序（「我的排序」那颗），
        // 那时候它必须是现成的
        layout = new LayoutStore(getContext());
        buildSubNav();
        setupGrid();
        wireSearch();

        ensureCatalog();
        rebuild();
    }

    // ---------------------------------------------------------------- 顶栏

    private void buildSwitcher() {
        FocusScaler.attach(switcher, FocusScaler.SCALE_FOCUSED);
        switcher.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMenuOpen(!menuOpen);
            }
        });
    }

    /**
     * 胶囊下拉：「返回桌面」是常驻出口，「重新扫描」把目录作废再扫一遍。
     *
     * <p>扫描只在后台跑，所以点完不会挡界面——它就是把 loading 立起来再落回去。
     */
    private void buildSwitcherMenu() {
        switcherMenu.removeAllViews();
        addMenuEntry("返回桌面", new Runnable() {
            @Override
            public void run() {
                closeToDesk();
            }
        });
        addMenuEntry("重新扫描", new Runnable() {
            @Override
            public void run() {
                setMenuOpen(false);
                rescan();
            }
        });
    }

    private void addMenuEntry(String label, final Runnable action) {
        TextView item = new TextView(getContext());
        item.setText(label);
        item.setTextColor(getResources().getColor(R.color.text_primary));
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), 0, dp(16), 0);
        item.setBackgroundResource(R.drawable.bg_module_item);
        item.setFocusable(true);
        item.setClickable(true);
        item.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.bottomMargin = dp(2);
        item.setLayoutParams(lp);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        switcherMenu.addView(item);
    }

    private void setMenuOpen(boolean open) {
        menuOpen = open;
        switcherMenu.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open && switcherMenu.getChildCount() > 0) {
            switcherMenu.getChildAt(0).requestFocus();
        }
    }

    private void buildNav() {
        navItems.removeAllViews();
        for (int i = 0; i < SECTIONS.length; i++) {
            final int index = i;
            TextView item = new TextView(getContext());
            item.setId(View.generateViewId());
            item.setText(SECTIONS[i]);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setTextColor(AppCompatResources.getColorStateList(getContext(),
                    R.color.nav_item_text));
            item.setBackgroundResource(R.drawable.bg_nav_item);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            item.setFocusable(true);
            item.setClickable(true);
            item.setFocusableInTouchMode(true);
            item.setPadding(getResources().getDimensionPixelSize(R.dimen.nav_item_pad_h), 0,
                    getResources().getDimensionPixelSize(R.dimen.nav_item_pad_h), 0);
            if (index == SEC_SEARCH) {
                // 搜索用放大镜，不跟栏目名挤在一起
                item.setText("");
                android.graphics.drawable.Drawable icon = AppCompatResources
                        .getDrawable(getContext(), R.drawable.ic_search);
                if (icon != null) {
                    int size = dp(19);
                    icon.setBounds(0, 0, size, size);
                    androidx.core.graphics.drawable.DrawableCompat.setTintList(icon,
                            AppCompatResources.getColorStateList(getContext(), R.color.nav_item_text));
                    item.setCompoundDrawables(null, null, null, null);
                    item.setCompoundDrawablesRelative(icon, null, null, null);
                }
                item.setContentDescription("搜索");
                // 图标页签自己不带文字内边距：左右补到跟「用户安装」的文字边距一样宽，
                // 放大镜右侧就不会比左侧挤
                item.setPadding(dp(14), 0, dp(14), 0);
                item.setGravity(android.view.Gravity.CENTER);
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            if (index > 0) {
                lp.leftMargin = dp(2);
            }
            item.setLayoutParams(lp);

            // 1.06：主界面胶囊/导航的统一档位
            FocusScaler.attach(item, 1.06f);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectSection(index, true);
                }
            });
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus || menuOpen || index == section) {
                        return;
                    }
                    selectSection(index, false);
                }
            });
            navItems.addView(item);
        }
        // 页签之间显式连左右：不连的话几何寻焦会挑中下面的卡片墙，
        // 尤其最后一颗（搜索图标）窄，从「全部」往右会直接跳进网格
        for (int i = 0; i < navItems.getChildCount(); i++) {
            View current = navItems.getChildAt(i);
            current.setNextFocusRightId(i + 1 < navItems.getChildCount()
                    ? navItems.getChildAt(i + 1).getId() : current.getId());
            current.setNextFocusLeftId(i > 0 ? navItems.getChildAt(i - 1).getId() : current.getId());
        }
        updateChipSelection();
    }

    private void updateChipSelection() {
        for (int i = 0; i < navItems.getChildCount(); i++) {
            navItems.getChildAt(i).setSelected(i == section);
        }
    }

    // ---------------------------------------------------------------- 子页签

    /**
     * 排序不再是一排 chips：跟 Aerial 一样，顺序由用户自己排
     * （长按图标 → 移动位置），没排过就按名称。
     * 这一排整条收起来，只留空实现给调用方。
     */
    private void buildSubNav() {
        if (subNav != null) {
            subNav.removeAllViews();
        }
        if (subScroll != null) {
            subScroll.setVisibility(View.GONE);
        }
    }

    private void addSubChip(String label, final int value) {
        TextView item = new TextView(getContext());
        item.setText(label);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        item.setTextColor(AppCompatResources.getColorStateList(getContext(),
                R.color.nav_item_text));
        item.setBackgroundResource(R.drawable.bg_nav_item);
        item.setGravity(Gravity.CENTER);
        item.setSingleLine(true);
        item.setFocusable(true);
        item.setClickable(true);
        item.setFocusableInTouchMode(true);
        int padH = dp(14);
        item.setPadding(padH, 0, padH, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        item.setLayoutParams(lp);
        // 记下这一颗代表哪种排序：「我的排序」那颗的序号和 sort 不对应，
        // 选中态只能靠这个 tag 判
        item.setTag(value);
        FocusScaler.attach(item, 1.06f);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSubChip(value);
            }
        });
        subNav.addView(item);
    }

    private void onSubChip(int value) {
        if (value == SORT_CUSTOM) {
            // 「我的排序」：清掉自定义，回到默认排序
            if (layout != null) {
                layout.reset();
            }
            sort = SORT_NAME;
            updateSubSelection();
            rebuild();
            buildSubNav();
            focusGrid(0, 2);
            toast("已恢复默认排序");
            return;
        }
        if (value == sort) {
            focusGrid(0, 2);
            return;
        }
        sort = value;
        updateSubSelection();
        rebuild();
        focusGrid(0, 2);
    }

    /**
     * 取这个应用<b>自带的最清晰那档</b>图标。
     *
     * <p>{@code getApplicationIcon} 是按屏幕密度选桶的：320dpi 的设备给 96px，
     * 240dpi 只给 72px，铺到桌面将近 200px 的瓷砖上就发虚（像素播放器在
     * 小米 MiTV4 上踩过这个坑）。这里直接问最高档 xxxhdpi，
     * 应用没提供就退回系统默认那套。
     */
    private static android.graphics.drawable.Drawable densestIcon(
            android.content.pm.PackageManager pm, ApplicationInfo ai) {
        int res = ai.icon;
        if (res != 0) {
            try {
                android.content.res.Resources res2 = pm.getResourcesForApplication(ai);
                android.graphics.drawable.Drawable d = res2.getDrawableForDensity(
                        res, android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) {
                    return d;
                }
            } catch (Throwable ignored) {
                // 有些包的资源读不出来，退回下面的系统接口
            }
        }
        try {
            return pm.getApplicationIcon(ai);
        } catch (Throwable th) {
            return null;
        }
    }

    private void updateSubSelection() {
        // 按 tag 里的排序值比对，不用下标：「我的排序」那颗排在最后，
        // 下标和 sort 对不上（sort 是 0/1/2，那颗是 99）。
        for (int i = 0; i < subNav.getChildCount(); i++) {
            View chip = subNav.getChildAt(i);
            Object tag = chip.getTag();
            int value = tag instanceof Integer ? (Integer) tag : i;
            chip.setSelected(value == sort);
        }
    }

    // ---------------------------------------------------------------- 栏目切换

    /**
     * @param focusContent true 表示用户按确认进来的，切完把焦点交给内容；
     *                     false 表示焦点路过，保持停在页签上
     */
    private void selectSection(int target, boolean focusContent) {
        if (target < 0 || target >= SECTIONS.length) {
            return;
        }
        section = target;
        updateChipSelection();
        rebuild();
        if (focusContent) {
            if (section == SEC_SEARCH && TextUtils.isEmpty(query)) {
                focusSearch();
            } else if (!rows.isEmpty()) {
                focusGrid(0, 2);
            } else {
                focusChip();
            }
        }
    }

    // ---------------------------------------------------------------- 目录扫描

    /** 目录没有就扫一次；已经有了直接用缓存的 */
    private void ensureCatalog() {
        if (catalog != null || catalogLoading) {
            return;
        }
        scan();
    }

    private void rescan() {
        if (catalogLoading) {
            return;
        }
        catalog = null;
        scan();
        rebuild();
    }

    /**
     * 后台枚举包 + 读图标。
     *
     * <p>「可启动」不是靠 ApplicationInfo 的字段判断的：一个包能不能从桌面打开，
     * 唯一可靠的问法是「它有没有 MAIN/LAUNCHER 的 activity」，所以先问这个拿到包名集合。
     */
    private void scan() {
        catalogLoading = true;
        catalogError = null;
        final int mine = epoch;
        final Context appContext = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Entry> scanned = null;
                String error = null;
                try {
                    scanned = readPackages(appContext);
                } catch (Exception e) {
                    error = TextUtils.isEmpty(e.getMessage())
                            ? "读应用列表失败" : e.getMessage();
                }
                final List<Entry> result = scanned;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || mine != epoch) {
                            return;
                        }
                        catalogLoading = false;
                        if (result != null) {
                            catalog = result;
                            catalogError = null;
                        } else {
                            catalogError = failure;
                        }
                        if (!released) {
                            rebuild();
                            // 首屏目录到达时焦点还停在页签上，就把它送进内容
                            if (!rows.isEmpty() && navItems.findFocus() != null) {
                                focusGrid(0, 2);
                            }
                        }
                    }
                });
            }
        }).start();
    }

    private static List<Entry> readPackages(Context context) {
        PackageManager pm = context.getPackageManager();

        // 只认「有桌面入口」的应用：queryIntentActivities 拿到的才算。
        //
        // <p>Aerial 也是这么筛的：设备上大量系统组件（安全插件、编解码更新器、
        // 商店插件、Analytics…）没有 launcher 入口，用户根本不该在应用墙里看到它们 ——
        // 以前按 getInstalledApplications 整表铺，它们就混进了「用户安装」。
        Map<String, android.content.pm.ActivityInfo> launchable =
                new HashMap<String, android.content.pm.ActivityInfo>();
        Map<String, android.content.pm.ActivityInfo> leanbackApps =
                new HashMap<String, android.content.pm.ActivityInfo>();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        if (infos != null) {
            for (ResolveInfo info : infos) {
                if (info.activityInfo != null) {
                    launchable.put(info.activityInfo.packageName, info.activityInfo);
                }
            }
        }
        // 小米应用商店只声明 APPSTORE_LAUNCHER，没有 CATEGORY_LAUNCHER（Aerial 同样处理）
        Intent store = new Intent(Intent.ACTION_MAIN);
        store.addCategory("android.intent.category.APPSTORE_LAUNCHER");
        List<ResolveInfo> storeInfos = pm.queryIntentActivities(store, 0);
        if (storeInfos != null) {
            for (ResolveInfo info : storeInfos) {
                if (info.activityInfo != null) {
                    launchable.put(info.activityInfo.packageName, info.activityInfo);
                }
            }
        }
        // 固件应用里几个根本不声明任何启动类目的（厂商自己的桌面才列得出来），
        // 按组件写死 —— 米家、高清播放器。照搬 Aerial 的 UNLISTED_ENTRIES
        String[][] unlisted = {
                {"com.xiaomi.smarthome.tv", "com.xiaomi.smarthome.tv.ui.MainActivity"},
                {"com.xiaomi.mitv.mediaexplorer",
                        "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"},
        };
        for (String[] pair : unlisted) {
            if (launchable.containsKey(pair[0])) {
                continue;
            }
            try {
                android.content.pm.ActivityInfo info = pm.getActivityInfo(
                        new android.content.ComponentName(pair[0], pair[1]), 0);
                if (info != null && info.exported && info.enabled
                        && info.applicationInfo != null && info.applicationInfo.enabled) {
                    launchable.put(info.packageName, info);
                }
            } catch (Exception ignored) {
                // 这台电视没装这个包 / 类名对不上：跳过
            }
        }
        // 电视端入口单独记一套：横幅（banner）是应用给电视做的图，
        // 只有真有电视端入口的应用才可信 —— 手机应用偶尔也声明 banner，
        // 那种拉过来铺满磁贴就是一场灾难（裁切、主体丢失都是它）。
        // 记的是 ActivityInfo：banner 得从<b>电视端那个 Activity</b> 上取，
        // ApplicationInfo.banner 常常是另一张（缩略图 / 手机横幅），
        // 铺满磁贴就「放大过多、裁切」（TVBox / 月光 / Yamby 都是这个原因）
        Intent leanbackIntent = new Intent(Intent.ACTION_MAIN);
        leanbackIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> leanbackInfos = pm.queryIntentActivities(leanbackIntent, 0);
        if (leanbackInfos != null) {
            for (ResolveInfo info : leanbackInfos) {
                if (info.activityInfo != null) {
                    leanbackApps.put(info.activityInfo.packageName, info.activityInfo);
                }
            }
        }

        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        List<Entry> entries = new ArrayList<>();
        if (apps == null) {
            return entries;
        }
        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) {
                continue;
            }
            Entry entry = new Entry();
            entry.pkg = ai.packageName;
            CharSequence label = null;
            try {
                label = pm.getApplicationLabel(ai);
            } catch (Exception e) {
                // 单个包读不出来不影响别的包，继续扫
            }
            entry.name = TextUtils.isEmpty(label) ? ai.packageName : label.toString();
            android.content.pm.ActivityInfo tvEntry = leanbackApps.get(ai.packageName);
            entry.launchable = tvEntry != null || launchable.containsKey(ai.packageName);
            entry.leanback = tvEntry != null;
            // 没有桌面入口的系统组件不列（安全插件、编解码更新器…）
            if (!entry.launchable) {
                continue;
            }
            entry.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            entry.icon = densestIcon(pm, ai);
            // 横幅是应用专门给电视做的图，比图标更「电视」。但只认有电视端入口的
            // 应用（Aerial 同款约束）：手机应用偶尔也声明 banner，那种图铺满磁贴
            // 就是裁切和主体丢失的来源。读不到就为 null，磁贴走图标管线
            if (entry.leanback) {
                int bannerRes = tvEntry != null && tvEntry.banner != 0 ? tvEntry.banner : ai.banner;
                if (bannerRes != 0) {
                    try {
                        entry.banner = pm.getResourcesForApplication(ai)
                                .getDrawableForDensity(bannerRes,
                                        android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
                    } catch (Exception e) {
                        entry.banner = null;
                    }
                }
            }
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                entry.version = pi == null || pi.versionName == null ? "" : pi.versionName;
                entry.installTime = pi == null ? 0L : pi.firstInstallTime;
                entry.updateTime = pi == null ? 0L : pi.lastUpdateTime;
            } catch (Exception e) {
                entry.version = "";
            }
            entries.add(entry);
        }
        return entries;
    }

    // ---------------------------------------------------------------- 组行

    /**
     * 唯一画界面的入口：栏目、排序、搜索词、目录状态都只改这里读的那几样。
     *
     * <p>过滤和排序都在内存里做——目录已经整包缓存，重新扫一次设备的代价全在磁盘 IO 上。
     */
    private void rebuild() {
        rows.clear();
        title.setText(SECTIONS[section]);

        List<Entry> source = catalog == null ? new ArrayList<Entry>() : catalog;
        String keyword = query.trim().toLowerCase(Locale.ROOT);
        for (Entry entry : source) {
            if (!matchesSection(entry) || !matchesKeyword(entry, keyword)) {
                continue;
            }
            rows.add(entry);
        }
        sortRows();
        // 用户自己排过的应用按那个顺序走 —— 这一条<b>盖过</b>上面的排序按钮。
        //
        // <p>「按名称 / 按更新时间 / 按安装时间」是默认排序，用户一旦自己排过顺序，
        // 再让他去切排序按钮把顺序打乱，是没道理的事。他排的顺序就是最终顺序，
        // 排序按钮这时该让位（想回到默认排序，把自定义顺序清掉即可）。
        //
        // <p>只在「全部 / 用户安装」这类整目录视图里生效：
        // 搜索结果按名字排更合理（用户是按名字找的）。
        if (section != SEC_SEARCH && layout != null && layout.hasCustomOrder()) {
            List<Entry> arranged = layout.arrange(rows, new LayoutStore.KeyOf<Entry>() {
                @Override
                public String key(Entry item) {
                    return item.pkg;
                }
            });
            rows.clear();
            rows.addAll(arranged);
        }

        subtitle.setText(headline());
        loadingView.setVisibility(rows.isEmpty() && catalogLoading ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setText(emptyLine());
        searchInput.setVisibility(section == SEC_SEARCH ? View.VISIBLE : View.GONE);
        // 那一行（标题 + 搜索框）只有搜索栏才有意义，其他栏目整行收起来，
        // 顶栏下面就紧接磁贴，不再夹一条空白带
        View toolRow = findViewById(R.id.apps_tool_row);
        if (toolRow != null) {
            toolRow.setVisibility(section == SEC_SEARCH ? View.VISIBLE : View.GONE);
        }

        // 整页背景跟着第一张卡走：焦点还在页签上时，背景不能留着上一个栏目的图标
        showBackground(rows.isEmpty() ? null : rows.get(0).icon, true);
        adapter.notifyDataSetChanged();
    }

    private boolean matchesSection(Entry entry) {
        switch (section) {
            case SEC_USER:
                return !entry.system;
            case SEC_SYSTEM:
                return entry.system;
            case SEC_ALL:
                return true;
            default:
                return true;
        }
    }

    /** 搜索同时命中应用名和包名：电视上键盘敲中文麻烦，包名往往是英文缩写 */
    private boolean matchesKeyword(Entry entry, String keyword) {
        if (section != SEC_SEARCH || TextUtils.isEmpty(keyword)) {
            return true;
        }
        return entry.name.toLowerCase(Locale.ROOT).contains(keyword)
                || entry.pkg.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private void sortRows() {
        Collections.sort(rows, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                switch (sort) {
                    case SORT_UPDATE:
                        return Long.compare(b.updateTime, a.updateTime);
                    case SORT_INSTALL:
                        return Long.compare(b.installTime, a.installTime);
                    default:
                        // 中文按拼音排，不是按 UTF-16 码点——后者会把中文名甩成一团乱序
                        return COLLATOR.compare(a.name, b.name);
                }
            }
        });
    }

    private String headline() {
        if (!TextUtils.isEmpty(catalogError)) {
            return catalogError;
        }
        if (catalogLoading) {
            return "正在扫描设备上的应用…";
        }
        if (section == SEC_SEARCH) {
            return TextUtils.isEmpty(query.trim())
                    ? "输入应用名或包名筛选"
                    : "“" + query.trim() + "”命中 " + rows.size() + " 个";
        }
        switch (section) {
            case SEC_USER:
                return "用户装的 " + rows.size() + " 个，系统自带的不在这里 · 长按看更多操作";
            case SEC_SYSTEM:
                return "系统自带 " + rows.size() + " 个，多数卸载不掉 · 长按看更多操作";
            default:
                return "设备上共 " + rows.size() + " 个包 · 长按看更多操作";
        }
    }

    private String emptyLine() {
        if (catalogLoading) {
            return "";
        }
        if (!TextUtils.isEmpty(catalogError)) {
            return catalogError;
        }
        if (section == SEC_SEARCH && !TextUtils.isEmpty(query.trim())) {
            return "没有名字或包名里带“" + query.trim() + "”的应用";
        }
        return "这里没有应用";
    }

    // ---------------------------------------------------------------- 交互

    /** 确认键：能开就开，开不了就直接把系统「应用信息」顶出来，不留死路 */
    private void activate(Entry entry) {
        if (entry.launchable && launch(entry)) {
            return;
        }
        showAppInfo(entry);
    }

    private boolean launch(Entry entry) {
        PackageManager pm = getContext().getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(entry.pkg);
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
            return true;
        } catch (Exception e) {
            android.util.Log.w(TAG, "打开失败 " + entry.pkg, e);
            toast("这个应用打不开：" + entry.name);
            return false;
        }
    }

    private void showAppInfo(Entry entry) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", entry.pkg, null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (Exception e) {
            android.util.Log.w(TAG, "应用信息打不开 " + entry.pkg, e);
            toast("这台设备的系统没给应用信息页");
        }
    }

    /**
     * 长按确认弹出的操作窗。卸载是危险操作，所以只在长按里出现，
     * 而且弹窗自己带「取消」和 BACK 退出两条出路（见 {@link AppActionDialog}）。
     */
    private void showActions(Entry entry) {
        Activity activity = activity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        AppActionDialog dialog = new AppActionDialog(activity, entry,
                new AppActionDialog.Listener() {
                    @Override
                    public void onOpen(Entry target) {
                        if (!launch(target)) {
                            showAppInfo(target);
                        }
                    }

                    @Override
                    public void onInfo(Entry target) {
                        showAppInfo(target);
                    }

                    @Override
                    public void onUninstall(Entry target) {
                        uninstall(target);
                    }

                    @Override
                    public void onMove(Entry target) {
                        startMove(target);
                    }

                    @Override
                    public void onMoveToTop(Entry target) {
                        moveToTop(target);
                    }
                });
        dialog.show();
    }

    /**
     * 把一个应用提到应用墙最上面，并记住这个顺序。
     *
     * <p>顺序存在 {@link LayoutStore} 里，下次进这一页照旧。
     * 用户装的（第三方）应用和系统应用一起排 —— 用户认的是「图标」，
     * 不是「是不是预装的」。
     */
    private void moveToTop(Entry entry) {
        if (entry == null || layout == null) {
            return;
        }
        // 先把它写进用户顺序，再把当前目录里所有应用也按同样的顺序存一份
        List<String> order = new ArrayList<>(layout.order());
        order.remove(entry.pkg);
        order.add(0, entry.pkg);
        if (catalog != null) {
            for (Entry e : catalog) {
                if (!order.contains(e.pkg)) {
                    order.add(e.pkg);
                }
            }
        }
        layout.save(order);
        rebuild();
        // 焦点回到刚提上来的那张卡，用户能立刻看到它动到哪了
        focusPkg(entry.pkg);
        toast(entry.name + " 已移到最前");
    }

    /** 把焦点放到指定包名的卡片上 */
    private void focusPkg(final String pkg) {
        if (pkg == null) {
            return;
        }
        for (int i = 0; i < adapter.getCount(); i++) {
            View child = grid.getChildAt(i);
            if (child == null) {
                continue;
            }
            Object tag = child.getTag();
            if (tag instanceof String && pkg.equals(tag)) {
                child.requestFocus();
                return;
            }
        }
    }

    /**
     * 卸载交给系统页，本页不碰包管理器。
     *
     * <p>一是这样才拿得到「二次确认」（系统自己的确认页），二是本页没有删包的权限，
     * 硬走 {@code PackageInstaller} 只会多写一坨没必要的代码。
     */
    private void uninstall(Entry entry) {
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.fromParts("package", entry.pkg, null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (Exception e) {
            android.util.Log.w(TAG, "卸载页打不开 " + entry.pkg, e);
            toast("这台设备不让从这里卸载");
        }
        // 回来时包列表已经变了，把这次进度的作废标记先落下（onEnter 会重扫）
        staleAfterLeave = true;
    }

    private void toast(String text) {
        Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 搜索

    private void wireSearch() {
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(searchTask);
                ui.postDelayed(searchTask, SEARCH_DEBOUNCE_MS);
            }
        });
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    ui.removeCallbacks(searchTask);
                    runSearch();
                    return true;
                }
                return false;
            }
        });
        searchInput.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    hideIme();
                    if (!rows.isEmpty()) {
                        focusGrid(0, 2);
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    hideIme();
                    focusChip();
                    return true;
                }
                return false;
            }
        });
    }

    private void runSearch() {
        String keyword = searchInput.getText().toString().trim();
        if (TextUtils.equals(keyword, query)) {
            return;
        }
        query = keyword;
        if (section != SEC_SEARCH) {
            return;
        }
        rebuild();
    }

    private void focusSearch() {
        if (searchInput.getVisibility() != View.VISIBLE) {
            return;
        }
        searchInput.requestFocus();
        searchInput.setSelection(searchInput.getText().length());
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------- 焦点

    private void setupGrid() {
        grid.setAdapter(adapter);
        grid.setSelector(new ColorDrawable(0));
        grid.setFocusable(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    private void focusChip() {
        // 用户主动把焦点送去顶栏：墙重排时不要再把焦点抢回列表
        if (grid instanceof com.tvdesk.desk.widget.PageGridView) {
            ((com.tvdesk.desk.widget.PageGridView) grid).markUserLeft();
        }
        if (section < navItems.getChildCount()) {
            navItems.getChildAt(section).requestFocus();
        }
    }

    /** 把焦点放到第 position 张卡上。目标行还没挂载时先平滑翻一行过去，再补焦 */
    private void focusGrid(final int position, final int retries) {
        if (gone() || adapter.getCount() == 0) {
            focusChip();
            return;
        }
        // 用户（或页面）把焦点送回墙里：撤掉「用户已离开」的标记
        if (grid instanceof com.tvdesk.desk.widget.PageGridView) {
            ((com.tvdesk.desk.widget.PageGridView) grid).markUserEntered();
        }
        View child = grid.getChildAt(position - grid.getFirstVisiblePosition());
        if (!GridScroll.isFullyVisible(grid, child)) {
            if (retries <= 0) {
                return;
            }
            long wait = GridScroll.smoothToRow(grid, position);
            postFocus(position, retries - 1, wait);
            return;
        }
        if (!child.requestFocus()) {
            if (retries <= 0) {
                return;
            }
            postFocus(position, retries - 1, 60);
            return;
        }
        if (retries <= 0) {
            return;
        }
        // 重排在下一帧，会把刚挂上焦的卡片回收掉，焦点静默落回模块胶囊。验一次，丢了重来。
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!gone() && !grid.hasFocus()) {
                    focusGrid(position, retries - 1);
                }
            }
        }, 32);
    }

    private void postFocus(final int position, final int retries, long delay) {
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                focusGrid(position, retries);
            }
        }, delay);
    }

    /**
     * 卡片墙的上下走位自己算（和音乐页/B 站页同一个理由）：
     * GridView 只挂载视口附近的卡片，下一排还没生成时系统找不到目标，会把焦点甩到看不见的地方。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (moving) {
                // 移动模式下方向键是「挪这张卡」，不是「换焦点」
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    moveStep(-1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    moveStep(1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    moveStep(-Math.max(1, grid.getNumColumns()));
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    moveStep(Math.max(1, grid.getNumColumns()));
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER) {
                    finishMove(true);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    finishMove(false);
                    return true;
                }
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && grid.getVisibility() == View.VISIBLE) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (moveGridVertical(keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    return true;
                }
            }
            // 到边了就地吃掉。
            //
            // <p>GridView 自己不管边界：第一列再按左，几何寻焦在墙外找不到更近的卡，
            // 就一路摸到左上角的模块胶囊上 —— 用户只是想在最左一排待着，
            // 焦点却飞走了，得按右再按回来。最后一列/最后一行同理。
            if (swallowAtEdge(keyCode)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 焦点这一下会不会把墙带到边界外：会就吃掉这个按键 */
    private boolean swallowAtEdge(int keyCode) {
        View focused = findFocus();
        if (focused == null || focused == grid) {
            return false;
        }
        // 顶栏页签：最左/最右一颗别再往外跑（右边那颗是放大镜）
        if (navItems != null && isDescendantOfView(focused, navItems)) {
            int index = navItems.indexOfChild(focused);
            if (index < 0) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && index == 0) {
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && index == navItems.getChildCount() - 1;
        }
        int position = grid.getPositionForView(focused);
        if (position < 0) {
            return false;
        }
        int columns = Math.max(1, grid.getNumColumns());
        int column = position % columns;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            return column == 0;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return column == columns - 1 || position == adapter.getCount() - 1;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            return position + columns >= adapter.getCount();
        }
        return false;
    }

    /** {@code node} 是不是挂在 {@code ancestor} 底下（含相等） */
    private static boolean isDescendantOfView(View node, View ancestor) {
        View current = node;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    // ---------------------------------------------------------------- 移动位置

    /** 从卡片菜单进入「移动位置」 */
    private void startMove(Entry entry) {
        if (entry == null || regionEmpty()) {
            return;
        }
        moving = true;
        movingPkg = entry.pkg;
        moveBackup = new ArrayList<String>();
        for (Entry item : rows) {
            moveBackup.add(item.pkg);
        }
        // 焦点回到这张卡上，接下来方向键挪它
        int index = indexOfPkg(entry.pkg);
        if (index >= 0) {
            focusGrid(index, 2);
        }
        showMoveHint(true);
    }

    private boolean regionEmpty() {
        return rows.isEmpty();
    }

    private int indexOfPkg(String pkg) {
        for (int i = 0; i < rows.size(); i++) {
            if (TextUtils.equals(rows.get(i).pkg, pkg)) {
                return i;
            }
        }
        return -1;
    }

    /** 把正在移动的那张卡往 {@code delta} 个位置挪一步 */
    private void moveStep(int delta) {
        int from = indexOfPkg(movingPkg);
        if (from < 0) {
            return;
        }
        int to = from + delta;
        if (to < 0 || to >= rows.size()) {
            return;
        }
        Entry moved = rows.remove(from);
        rows.add(to, moved);
        adapter.notifyDataSetChanged();
        focusGrid(to, 1);
    }

    /**
     * 结束移动。
     *
     * @param commit true = 放下并记住顺序；false = 撤销，回到进入移动前的顺序
     */
    private void finishMove(boolean commit) {
        if (!moving) {
            return;
        }
        if (!commit && moveBackup != null) {
            // 撤销：按备份顺序重排当前行
            List<Entry> restored = new ArrayList<Entry>();
            for (String pkg : moveBackup) {
                int at = indexOfPkg(pkg);
                if (at >= 0) {
                    restored.add(rows.get(at));
                }
            }
            for (Entry item : rows) {
                if (!restored.contains(item)) {
                    restored.add(item);
                }
            }
            rows.clear();
            rows.addAll(restored);
        }
        int keep = movingPkg == null ? 0 : indexOfPkg(movingPkg);
        moving = false;
        movingPkg = null;
        moveBackup = null;
        showMoveHint(false);
        if (commit) {
            persistOrder();
            toast("顺序已保存");
        }
        adapter.notifyDataSetChanged();
        if (keep >= 0) {
            focusGrid(keep, 2);
        }
    }

    /** 把当前顺序落盘：可见的行在前，不在这一栏里的应用按原顺序跟在后头 */
    private void persistOrder() {
        if (layout == null) {
            return;
        }
        List<String> order = new ArrayList<String>();
        for (Entry item : rows) {
            order.add(item.pkg);
        }
        if (catalog != null) {
            for (Entry item : catalog) {
                if (!order.contains(item.pkg)) {
                    order.add(item.pkg);
                }
            }
        }
        layout.save(order);
    }

    /** 移动模式提示条：浮在底部中间，进/出移动时显隐 */
    private void showMoveHint(boolean show) {
        if (show) {
            if (moveHint == null) {
                moveHint = new TextView(getContext());
                moveHint.setText("移动中：方向键挪位置 · 确定放下 · 返回撤销");
                moveHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                moveHint.setTextColor(0xFFFFFFFF);
                moveHint.setBackgroundResource(R.drawable.bg_pill);
                moveHint.setPadding(dp(22), dp(10), dp(22), dp(10));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.bottomMargin = dp(28);
                addView(moveHint, lp);
            }
            moveHint.setVisibility(View.VISIBLE);
        } else if (moveHint != null) {
            moveHint.setVisibility(View.GONE);
        }
    }

    private boolean moveGridVertical(boolean down) {
        View focused = findFocus();
        if (focused == null) {
            return false;
        }
        int position = grid.getPositionForView(focused);
        if (position < 0) {
            return false;
        }
        int columns = Math.max(1, grid.getNumColumns());
        if (down) {
            int next = position + columns;
            if (next < adapter.getCount()) {
                focusGrid(next, 2);
            }
            return true;
        }
        int previous = position - columns;
        if (previous >= 0) {
            focusGrid(previous, 2);
            return true;
        }
        if (subScroll.getVisibility() == View.VISIBLE && subNav.getChildCount() > 0) {
            subNav.getChildAt(0).requestFocus();
        } else {
            focusChip();
        }
        return true;
    }

    @Override
    public void onEnter() {
        released = false;
        // 从卸载页或系统应用信息页回来时，包可能已经变了
        if (staleAfterLeave) {
            staleAfterLeave = false;
            rescan();
        }
        post(new Runnable() {
            @Override
            public void run() {
                focusContentIfIdle();
            }
        });
        postDelayed(new Runnable() {
            @Override
            public void run() {
                focusContentIfIdle();
            }
        }, 300);
    }

    /** 焦点还空着或停在页签上，就把它挪进内容；用户已经自己按到别处时不抢 */
    private void focusContentIfIdle() {
        if (gone()) {
            return;
        }
        View focused = currentFocus();
        if (focused != null && navItems.findFocus() == null && switcher.findFocus() == null) {
            return;
        }
        if (section == SEC_SEARCH && TextUtils.isEmpty(query)) {
            focusChip();
        } else if (!rows.isEmpty()) {
            focusGrid(0, 2);
        } else {
            focusChip();
        }
    }

    private View currentFocus() {
        Activity activity = activity();
        return activity == null ? findFocus() : activity.getCurrentFocus();
    }

    private Activity activity() {
        Context context = getContext();
        return context instanceof Activity ? (Activity) context : null;
    }

    /** 宿主不在了。所有异步回调动界面之前先问这个 */
    private boolean gone() {
        Activity activity = activity();
        return activity == null || activity.isFinishing() || activity.isDestroyed();
    }

    // ---------------------------------------------------------------- 背景

    /**
     * 整页背景。把聚焦那个应用的图标解码成一张小图再拉满屏——
     * 放大插值本身就是最便宜的模糊，不用引 blur 库也不吃 GPU（和音乐/B 站页同一个手法）。
     *
     * <p>图标不是 URL，只能直接塞 Drawable；Glide 会先按 override 的尺寸渲染，
     * 出来就是一团和应用主色一致的氛围底。
     */
    /**
     * 背景不再跟着应用图标变。
     *
     * <p>Aerial 的 Home 会按当前应用的主色铺一层模糊氛围底，磁贴一换整页染色。
     * 在电视上看这层染色比默认壁纸更花（大面积单色压着标题和名字），
     * 这里直接把氛围层收掉，只留页面自己的默认背景。
     */
    private void showBackground(Drawable icon, boolean force) {
        if (bgImage == null) {
            return;
        }
        if (shownIcon != null || bgImage.getAlpha() > 0f) {
            shownIcon = null;
            bgImage.animate().alpha(0f).setDuration(240).start();
        }
    }


    // ---------------------------------------------------------------- ModulePage

    @Override
    public boolean handleKeyDown(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BACK && menuOpen) {
            setMenuOpen(false);
            switcher.requestFocus();
            return Back.consume("应用·收起胶囊菜单");
        }
        // 焦点兜底：任何方向键先把它救回页签，否则遥控器看起来像失灵
        if (findFocus() == null && keyCode >= KeyEvent.KEYCODE_DPAD_UP
                && keyCode <= KeyEvent.KEYCODE_DPAD_CENTER) {
            focusChip();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && navItems.findFocus() != null) {
            if (subScroll.getVisibility() == View.VISIBLE && subNav.getChildCount() > 0) {
                subNav.getChildAt(0).requestFocus();
            } else if (section == SEC_SEARCH && TextUtils.isEmpty(query)) {
                focusSearch();
            } else if (!rows.isEmpty()) {
                focusGrid(0, 2);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && subNav.hasFocus()) {
            focusChip();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (section == SEC_SEARCH && !TextUtils.isEmpty(query)) {
                query = "";
                searchInput.setText("");
                hideIme();
                rebuild();
                focusSearch();
                return Back.consume("应用·清空搜索词");
            }
            // 焦点还在内容区时，返回先回顶栏：顶栏是内容的上一层，
            // 一次返回跨两层（内容 → 桌面）会让人不知道自己在哪
            if (grid.findFocus() != null) {
                focusChip();
                return Back.consume("应用·回顶栏");
            }
            return Back.pass("应用·页内已无层级");
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            setMenuOpen(!menuOpen);
            return true;
        }
        return false;
    }

    @Override
    public void onRelease() {
        ui.removeCallbacks(searchTask);
        // 页实例被宿主缓存复用，这里只断回调，不清目录：
        // 切走再切回来不该重扫一遍设备，但回来时要能保证焦点不落空
        released = true;
    }

    // ---------------------------------------------------------------- 卡片

    private final class CardAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final AppTileView root;
            final boolean fresh = !(convertView instanceof AppTileView);
            if (fresh) {
                root = new AppTileView(getContext());
            } else {
                root = (AppTileView) convertView;
            }
            final Entry entry = rows.get(position);
            root.setName(entry.name);
            root.setTile(AppTileArt.tile(entry.pkg + "@" + entry.updateTime,
                    entry.banner, entry.icon));

            // GridView 的 item 默认不可聚焦，得自己开
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            // Aerial 的磁贴聚焦放大 1.15 倍（AppTile.FOCUSED_SCALE）
            FocusScaler.attach(root, AppTileView.FOCUSED_SCALE,
                    new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        showBackground(entry.icon, false);
                        root.setNameBright(true);
                    } else {
                        root.setNameBright(false);
                    }
                }
            });
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activate(entry);
                }
            });
            // 记下包名：「移到最前」之后要能把焦点放回这张卡
            root.setTag(entry.pkg);
            root.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showActions(entry);
                    // 返回 true：不然长按会被当成一次普通点击，弹完窗又去开应用
                    return true;
                }
            });

            if (!fresh || root.isFocused()) {
                RevealAnim.reset(root);
            } else {
                RevealAnim.staggerIn(root, position, dp(10));
            }
            return root;
        }
    }

    // ---------------------------------------------------------------- 工具

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void closeToDesk() {
        setMenuOpen(false);
        if (onClose != null) {
            onClose.run();
        }
    }
}

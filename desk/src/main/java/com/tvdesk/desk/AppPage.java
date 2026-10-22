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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
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

    private static final String[] SECTIONS = {"可启动", "第三方", "系统", "全部", "搜索"};

    private static final int SEC_LAUNCH = 0;
    private static final int SEC_THIRD = 1;
    private static final int SEC_SYSTEM = 2;
    private static final int SEC_ALL = 3;
    private static final int SEC_SEARCH = 4;

    /** 子页签是排序方式，三个栏目共用一套 */
    private static final String[] SORTS = {"按名称", "按更新时间", "按安装时间"};

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
        String version = "";
        long installTime;
        long updateTime;
        boolean system;
        boolean launchable;
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

    private int section = SEC_LAUNCH;
    private int sort = SORT_NAME;
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
        title = findViewById(R.id.apps_title);
        subtitle = findViewById(R.id.apps_subtitle);
        emptyView = findViewById(R.id.apps_empty);
        searchInput = findViewById(R.id.apps_search);
        bgImage = findViewById(R.id.apps_bg);
        loadingView = findViewById(R.id.apps_loading);

        buildSwitcher();
        buildSwitcherMenu();
        buildNav();
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
        updateChipSelection();
    }

    private void updateChipSelection() {
        for (int i = 0; i < navItems.getChildCount(); i++) {
            navItems.getChildAt(i).setSelected(i == section);
        }
    }

    // ---------------------------------------------------------------- 子页签

    private void buildSubNav() {
        subNav.removeAllViews();
        for (int i = 0; i < SORTS.length; i++) {
            addSubChip(SORTS[i], i);
        }
        subScroll.setVisibility(subNav.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        updateSubSelection();
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
        if (value == sort) {
            focusGrid(0, 2);
            return;
        }
        sort = value;
        updateSubSelection();
        rebuild();
        focusGrid(0, 2);
    }

    private void updateSubSelection() {
        for (int i = 0; i < subNav.getChildCount(); i++) {
            subNav.getChildAt(i).setSelected(i == sort);
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

        Set<String> launchable = new HashSet<>();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        if (infos != null) {
            for (ResolveInfo info : infos) {
                if (info.activityInfo != null) {
                    launchable.add(info.activityInfo.packageName);
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
            entry.launchable = launchable.contains(ai.packageName);
            entry.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            try {
                entry.icon = pm.getApplicationIcon(ai);
            } catch (Exception e) {
                entry.icon = null;
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

        subtitle.setText(headline());
        loadingView.setVisibility(rows.isEmpty() && catalogLoading ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setText(emptyLine());
        searchInput.setVisibility(section == SEC_SEARCH ? View.VISIBLE : View.GONE);

        // 整页背景跟着第一张卡走：焦点还在页签上时，背景不能留着上一个栏目的图标
        showBackground(rows.isEmpty() ? null : rows.get(0).icon, true);
        adapter.notifyDataSetChanged();
    }

    private boolean matchesSection(Entry entry) {
        switch (section) {
            case SEC_LAUNCH:
                return entry.launchable;
            case SEC_THIRD:
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
            case SEC_LAUNCH:
                return "共 " + rows.size() + " 个能直接打开 · 确认键打开，长按看更多操作";
            case SEC_THIRD:
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
                });
        dialog.show();
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
        if (section < navItems.getChildCount()) {
            navItems.getChildAt(section).requestFocus();
        }
    }

    /** 把焦点放到第 position 张卡上。目标不在视口里时 setSelection 之后要等一帧才生成子 View */
    private void focusGrid(final int position, final int retries) {
        if (gone() || adapter.getCount() == 0) {
            focusChip();
            return;
        }
        View child = grid.getChildAt(position - grid.getFirstVisiblePosition());
        if (child == null) {
            grid.setSelection(position);
        } else if (child.requestFocus()) {
            return;
        }
        if (retries <= 0) {
            return;
        }
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                focusGrid(position, retries - 1);
            }
        }, 60);
    }

    /**
     * 卡片墙的上下走位自己算（和音乐页/B 站页同一个理由）：
     * GridView 只挂载视口附近的卡片，下一排还没生成时系统找不到目标，会把焦点甩到看不见的地方。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && grid.getVisibility() == View.VISIBLE) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (moveGridVertical(keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
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
    private void showBackground(Drawable icon, boolean force) {
        if (bgImage == null) {
            return;
        }
        if (icon == null) {
            if (!force) {
                return;
            }
            shownIcon = null;
            bgImage.animate().alpha(0f).setDuration(320).start();
            return;
        }
        if (icon == shownIcon) {
            return;
        }
        shownIcon = icon;
        try {
            Glide.with(getContext())
                    .load(icon)
                    // 图标是纯色块，不像封面那样有细节可糊：解码尺寸给到 40 才拉得出氛围底，
                    // 用 B 站页的 200 会放大成一个能看清轮廓的巨型图标压在文字后面
                    .override(40, 40)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(420))
                    .into(bgImage);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "应用图标做背景渲染失败", th);
            return;
        }
        bgImage.animate().alpha(BG_ALPHA).setDuration(420).start();
    }

    // ---------------------------------------------------------------- ModulePage

    @Override
    public boolean handleKeyDown(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BACK && menuOpen) {
            setMenuOpen(false);
            switcher.requestFocus();
            return true;
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
                return true;
            }
            return false;
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
            View root = convertView;
            if (root == null) {
                root = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_app_card, parent, false);
            }
            final Entry entry = rows.get(position);
            TextView name = root.findViewById(R.id.apps_card_name);
            TextView note = root.findViewById(R.id.apps_card_note);
            TextView badge = root.findViewById(R.id.apps_card_badge);
            ImageView img = root.findViewById(R.id.apps_card_img);

            name.setText(entry.name);
            String noteText = noteOf(entry);
            note.setText(noteText);
            note.setVisibility(TextUtils.isEmpty(noteText) ? View.GONE : View.VISIBLE);
            badge.setText(entry.system ? "预装" : "");
            badge.setVisibility(entry.system ? View.VISIBLE : View.GONE);

            bindIcon(img, entry.icon);
            PosterCorners.clip(img,
                    getResources().getDimensionPixelSize(R.dimen.poster_radius) - dp(3));

            // GridView 的 item 默认不可聚焦，得自己开
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            FocusScaler.attach(root, FocusScaler.SCALE_FOCUSED,
                    new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        showBackground(entry.icon, false);
                    }
                }
            });
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activate(entry);
                }
            });
            root.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showActions(entry);
                    // 返回 true：不然长按会被当成一次普通点击，弹完窗又去开应用
                    return true;
                }
            });

            if (root.isFocused()) {
                RevealAnim.reset(root);
            } else {
                RevealAnim.staggerIn(root, position, dp(10));
            }
            return root;
        }
    }

    /** 版本 + 更新日期。两个都没有就整行不留，否则卡片下面是空一线 */
    private static String noteOf(Entry entry) {
        String version = TextUtils.isEmpty(entry.version) ? "" : "v" + entry.version;
        long stamp = entry.updateTime > 0 ? entry.updateTime : entry.installTime;
        String date = stamp > 0 ? new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .format(new Date(stamp)) : "";
        if (TextUtils.isEmpty(version)) {
            return date;
        }
        return TextUtils.isEmpty(date) ? version : version + " · " + date;
    }

    private void bindIcon(ImageView view, Drawable icon) {
        if (view == null) {
            return;
        }
        view.setImageDrawable(icon);
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

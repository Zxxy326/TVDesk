package com.tvdesk.desk;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.content.res.AppCompatResources;

import com.github.tvbox.osc.util.HistoryHelper;
import com.tvdesk.core.feed.SourceConfig;
import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.motion.RevealAnim;

import java.util.ArrayList;
import java.util.List;

/**
 * 信号源模块页（内嵌在桌面的 module_host 里）：内容源、直播源、电视输入源，三种「信号」放一页管。
 *
 * <h3>风格基准</h3>
 * 和应用页同一套骨架（左上胶囊 + 居中页签 + 6 列卡片墙 + FocusScaler 1.08/1.06 + RevealAnim）。
 * 少了搜索框、子页签和整页背景洗：这一页最多十几张卡，排序筛选是多余的一层；
 * 卡片只有线条图标，拿它做整页背景洗出来的是一团没有信息量的灰（详见 activity_signal.xml 的注释）。
 *
 * <h3>三个栏目各自管什么</h3>
 * <ul>
 *   <li><b>内容源</b>：TVBox 那个「配置地址」。卡片是「当前源 + 用过的源」，确认键直接切。
 *       写配置走 {@link SourceConfig}，和设置页是同一条通路，不是本页另存一份。</li>
 *   <li><b>直播源</b>：TVBox 的直播接口（{@code live_api_url}）。它默认为空 = 跟随内容源，
 *       所以这一栏的空值要说成「跟随内容源」而不是「没配」——这是两件不同的事。</li>
 *   <li><b>电视输入</b>：HDMI / 天线这些硬件输入，问系统的 Live TV 框架（{@link TvInputManager}）。</li>
 * </ul>
 *
 * <h3>换源之后谁去重新加载</h3>
 * 本页只写配置，不碰加载——和设置页一个道理（见 {@link SourceConfig} 的类注释）。
 * 区别是本页是内嵌页，写完不会触发桌面 {@code onResume}，所以桌面把同样的比对照排在
 * 「关掉模块页回桌面」那一刻也做了一遍（{@code DeskActivity#syncSourceIfChanged}）。
 * 不在本页当场重扫：桌面的顶栏和提示条此刻被本页盖着，重扫的加载提示会看不见，
 * 回来时反而像什么都没发生。
 *
 * <h3>电视输入为什么不做成「一定能切」</h3>
 * 切 HDMI 在 Android TV 上是 TV 应用（有角色授权的那个）的活。第三方按标准流程发
 * WATCH_PROGRAM 大概率被系统拒（SecurityException），
 * 所以这里按「试直通 → 试 VIEW → 退到厂商设置」的顺序走，全都不行就明确告诉用户
 * 按遥控器上的信号源键。不给死路，也不假装成功。
 */
public class SignalPage extends FrameLayout implements ModulePage {

    private static final String TAG = "SignalPage";

    private static final String[] SECTIONS = {"内容源", "直播源", "电视输入"};

    private static final int SEC_VOD = 0;
    private static final int SEC_LIVE = 1;
    private static final int SEC_INPUT = 2;

    /** 卡片有四种身份：填新地址 / 一个源地址 / 一个电视输入 / 打开系统设置 */
    private static final int KIND_ADD = 0;
    private static final int KIND_URL = 1;
    private static final int KIND_INPUT = 2;
    private static final int KIND_SYSTEM = 3;

    static final class Row {
        int kind;
        String title = "";
        String note = "";
        /** KIND_URL 是源地址原文（可能是「名\t址」一行），KIND_INPUT 是 input id */
        String value = "";
        Drawable icon;
        /** 当前生效的那个源，卡片右下角标「使用中」 */
        boolean current;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 重问系统输入时旧结果作废：回调里比对它 */
    private int epoch;
    private boolean released;

    private final List<Row> rows = new ArrayList<>();
    private final CardAdapter adapter = new CardAdapter();

    /** 电视输入的目录。null 表示还没问过系统 */
    private List<Row> inputCatalog;
    private boolean inputLoading;
    private String inputNote;

    private int section = SEC_VOD;
    private boolean menuOpen;

    private LinearLayout switcher;
    private LinearLayout switcherMenu;
    private LinearLayout navItems;
    private GridView grid;
    private TextView title;
    private TextView subtitle;
    private ProgressBar loadingView;

    /** 回桌面，由宿主 DeskActivity 传入 */
    private final Runnable onClose;

    public SignalPage(Context context, Runnable onClose) {
        super(context);
        this.onClose = onClose;
        LayoutInflater.from(context).inflate(R.layout.activity_signal, this, true);

        switcher = findViewById(R.id.signal_switcher);
        switcherMenu = findViewById(R.id.signal_menu);
        navItems = findViewById(R.id.signal_nav_items);
        grid = findViewById(R.id.signal_grid);
        title = findViewById(R.id.signal_title);
        subtitle = findViewById(R.id.signal_subtitle);
        loadingView = findViewById(R.id.signal_loading);

        buildSwitcher();
        buildSwitcherMenu();
        buildNav();
        setupGrid();

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
     * 胶囊下拉：「返回桌面」是常驻出口，「重新读取」把电视输入目录作废再问一次系统。
     *
     * <p>源那两栏不需要刷新按钮——它们读的是本机配置，每次 rebuild 都是现取的值。
     */
    private void buildSwitcherMenu() {
        switcherMenu.removeAllViews();
        addMenuEntry("返回桌面", new Runnable() {
            @Override
            public void run() {
                closeToDesk();
            }
        });
        addMenuEntry("重新读取", new Runnable() {
            @Override
            public void run() {
                setMenuOpen(false);
                reload();
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
        ensureInputs();
        rebuild();
        if (focusContent && !rows.isEmpty()) {
            focusGrid(0, 2);
        }
    }

    /** 电视输入那一栏作废重问，源那两栏只是重画 */
    private void reload() {
        // 先作废在飞的那次读取，否则旧结果会先落回界面、新结果再覆盖一次
        epoch++;
        inputCatalog = null;
        inputNote = null;
        // 在飞的那次读取已经被 epoch 作废，标记一起清掉，否则 ensureInputs 会以为还有人在读
        inputLoading = false;
        ensureInputs();
        rebuild();
        focusGrid(0, 2);
    }

    // ---------------------------------------------------------------- 电视输入

    /** 第一次进「电视输入」才问系统：没装 Live TV 框架的设备上这一步可能慢一点 */
    private void ensureInputs() {
        if (section != SEC_INPUT || inputCatalog != null || inputLoading) {
            return;
        }
        inputLoading = true;
        inputNote = null;
        final int mine = epoch;
        final Context appContext = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Row> found = readInputs(appContext);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || mine != epoch) {
                            return;
                        }
                        inputLoading = false;
                        if (found == null) {
                            // 系统压根没有直播电视框架。这不是故障，得说清楚去哪儿切
                            inputNote = "这台设备没有系统级电视输入服务 · 按遥控器上的「信号源」键，或用下面这张卡";
                            inputCatalog = new ArrayList<>();
                        } else {
                            inputCatalog = found;
                        }
                        if (!released) {
                            rebuild();
                            if (!rows.isEmpty() && navItems.findFocus() != null) {
                                focusGrid(0, 2);
                            }
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 问 {@link TvInputManager} 要硬件输入。
     *
     * <p>返回 null 表示「这台设备没有这套框架」，空列表表示「有框架但一个输入都没有」——
     * 两种情况给用户的解释不一样，不能糊在一起。
     *
     * <p>只收硬件口（按 {@code getType()} 排掉 {@code TYPE_OTHER}）：软件输入、投屏虚拟输入
     * 列出来只会占掉一格卡片位置，用户找 HDMI 反而更慢。用户在系统里藏掉的那些也不再摆一遍。
     */
    private static List<Row> readInputs(Context context) {
        if (Build.VERSION.SDK_INT < 21) {
            return null;
        }
        return readInputsLollipop(context);
    }

    @TargetApi(21)
    private static List<Row> readInputsLollipop(Context context) {
        Object service = context.getSystemService(Context.TV_INPUT_SERVICE);
        if (!(service instanceof TvInputManager)) {
            return null;
        }
        List<Row> result = new ArrayList<>();
        try {
            TvInputManager manager = (TvInputManager) service;
            List<TvInputInfo> infos = manager.getTvInputList();
            if (infos == null) {
                return result;
            }
            for (TvInputInfo info : infos) {
                if (info == null || info.getType() == TvInputInfo.TYPE_OTHER) {
                    continue;
                }
                if (info.isHidden(context)) {
                    // 用户在系统里藏掉的输入，这里不该又给他摆出来
                    continue;
                }
                Row row = new Row();
                row.kind = KIND_INPUT;
                row.value = info.getId();
                CharSequence label = null;
                try {
                    label = info.loadLabel(context);
                } catch (Exception e) {
                    // 标签读不出来就用 id 顶上，别把这一格丢掉
                }
                row.title = TextUtils.isEmpty(label) ? info.getId() : label.toString();
                row.note = typeLabel(info) + connectedNote(manager, info);
                Drawable icon = null;
                try {
                    icon = info.loadIcon(context);
                } catch (Exception e) {
                    icon = null;
                }
                row.icon = icon != null
                        ? icon
                        : AppCompatResources.getDrawable(context, R.drawable.ic_src_hdmi);
                result.add(row);
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "读电视输入失败", e);
        } catch (Error e) {
            // 部分盒子的 TvInputManager 桩实现会直接抛 UnsatisfiedLinkError
            android.util.Log.w(TAG, "读电视输入崩了", e);
        }
        return result;
    }

    /**
     * 输入类型念成中文。用 {@code getType()} 而不是 {@code isHardwareInput()}：
     * 后者要到 API 23 才有，而这套常量（HDMI / 分量 / AV…）从 21 就在，
     * 按类型排除 {@code TYPE_OTHER} 同样能把软件输入挡掉，还多分得清是哪一种口。
     */
    @TargetApi(21)
    private static String typeLabel(TvInputInfo info) {
        switch (info.getType()) {
            case TvInputInfo.TYPE_HDMI:
                return "HDMI";
            case TvInputInfo.TYPE_DVI:
                return "DVI";
            case TvInputInfo.TYPE_DISPLAY_PORT:
                return "DisplayPort";
            case TvInputInfo.TYPE_TUNER:
                return "天线 / 有线调谐";
            case TvInputInfo.TYPE_COMPOSITE:
                return "AV（复合视频）";
            case TvInputInfo.TYPE_COMPONENT:
                return "分量";
            case TvInputInfo.TYPE_SVIDEO:
                return "S-Video";
            case TvInputInfo.TYPE_SCART:
                return "SCART";
            case TvInputInfo.TYPE_VGA:
                return "VGA";
            default:
                return "外部输入";
        }
    }

    /** 没插线的口也列着会让人挨个点，所以把「未接信号」写在第二行文案上 */
    @TargetApi(21)
    private static String connectedNote(TvInputManager manager, TvInputInfo info) {
        try {
            return manager.getInputState(info.getId())
                    == TvInputManager.INPUT_STATE_DISCONNECTED ? " · 未接信号" : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 切到某个电视输入。两级尝试，全失败由调用方退到厂商设置。
     *
     * <p>{@code android.intent.action.WATCH_PROGRAM} 是标准流程那条，但它是系统级 action
     * （公开 SDK 里连常量都没有，所以这里按字符串发）：要求调用方是系统认定的 TV 应用，
     * 第三方多半吃 SecurityException。passthrough 的 {@code ACTION_VIEW} 在部分盒子上反而是通的。
     */
    private boolean tuneInput(Row row) {
        if (Build.VERSION.SDK_INT < 21) {
            return false;
        }
        Uri uri = null;
        try {
            uri = TvContract.buildChannelUriForPassthroughInput(row.value);
        } catch (Exception e) {
            android.util.Log.w(TAG, "passthrough uri 造不出来 " + row.value, e);
        }
        if (uri == null) {
            return false;
        }
        if (SystemSettings.startIfResolvable(getContext(),
                new Intent("android.intent.action.WATCH_PROGRAM", uri))) {
            return true;
        }
        return SystemSettings.startIfResolvable(getContext(), new Intent(Intent.ACTION_VIEW, uri));
    }

    // ---------------------------------------------------------------- 组行

    /**
     * 唯一画界面的入口。三栏的卡都从这里现取：源列表是本机配置（读内存），
     * 电视输入是 {@link #inputCatalog} 那份缓存。
     */
    private void rebuild() {
        rows.clear();
        title.setText(SECTIONS[section]);

        if (section == SEC_VOD) {
            rows.add(addCard("输入新地址", "手动填一个配置地址"));
            appendSourceRows(SourceConfig.current(), SourceConfig.history(),
                    R.drawable.ic_src_api);
        } else if (section == SEC_LIVE) {
            String live = SourceConfig.currentLive();
            String vod = SourceConfig.current();
            rows.add(addCard(live.isEmpty() ? "单独设一个直播地址" : "改或清空这个地址",
                    !live.isEmpty() ? "清空后跟随内容源"
                            : vod.isEmpty() ? "内容源也还没有 · 填一个直播地址"
                                    : "不设就跟随内容源"));
            // 跟随内容源时当前直播源就是主接口，卡片上得标出来，否则用户以为没生效
            appendSourceRows(live.isEmpty() ? vod : live,
                    SourceConfig.liveHistory(), R.drawable.ic_src_live);
        } else {
            if (inputCatalog != null) {
                rows.addAll(inputCatalog);
            }
            rows.add(systemCard());
        }

        subtitle.setText(headline());
        loadingView.setVisibility(section == SEC_INPUT && rows.size() <= 1 && inputLoading
                ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private Row addCard(String label, String note) {
        Row row = new Row();
        row.kind = KIND_ADD;
        row.title = label;
        row.note = note;
        row.icon = AppCompatResources.getDrawable(getContext(), R.drawable.ic_src_add);
        return row;
    }

    private Row systemCard() {
        Row row = new Row();
        row.kind = KIND_SYSTEM;
        row.title = "打开系统设置";
        row.note = "进去找「信号源」";
        row.icon = AppCompatResources.getDrawable(getContext(), R.drawable.ic_src_settings);
        return row;
    }

    /**
     * 把「当前 + 历史」摊成卡片。当前源可能同时也在历史里（应用时就会记进历史），
     * 所以按地址去重：同一个地址只留一张卡，标上「使用中」。
     */
    private void appendSourceRows(String current, List<String> history, int iconRes) {
        String target = HistoryHelper.getApiLineUrl(current);
        List<String> seen = new ArrayList<>();
        Drawable icon = AppCompatResources.getDrawable(getContext(), iconRes);
        if (!TextUtils.isEmpty(current)) {
            Row row = new Row();
            row.kind = KIND_URL;
            row.value = current;
            row.title = sourceTitle(current);
            row.note = sourceNote(current, row.title);
            row.icon = icon;
            row.current = true;
            rows.add(row);
            seen.add(target);
        }
        if (history == null) {
            return;
        }
        for (String item : history) {
            if (TextUtils.isEmpty(item) || seen.contains(HistoryHelper.getApiLineUrl(item))) {
                continue;
            }
            seen.add(HistoryHelper.getApiLineUrl(item));
            Row row = new Row();
            row.kind = KIND_URL;
            row.value = item;
            row.title = sourceTitle(item);
            row.note = sourceNote(item, row.title);
            row.icon = icon;
            rows.add(row);
        }
    }

    /**
     * 卡片标题。TVBox 的历史里可能混着「聚合源」那种「名字&lt;TAB&gt;地址」一行，
     * 有名字就用名字；纯地址就取域名——把整串 URL 当标题，六列卡片墙上一张都读不完。
     */
    private static String sourceTitle(String value) {
        String builtin = com.tvdesk.core.feed.BuiltinSources.displayName(HistoryHelper.getApiLineUrl(value));
        if (!TextUtils.isEmpty(builtin)) {
            return builtin;
        }
        String name = HistoryHelper.getApiLineName(value);
        if (!name.equals(value) && !TextUtils.isEmpty(name)) {
            return name;
        }
        String host = hostOf(value);
        return TextUtils.isEmpty(host) ? value : host;
    }

    /** 标题已经是整串地址时不再重复一遍；否则下面那行给完整地址 */
    private static String sourceNote(String value, String title) {
        String url = HistoryHelper.getApiLineUrl(value);
        if (TextUtils.isEmpty(url) || url.equals(title)) {
            return "";
        }
        return url;
    }

    private static String hostOf(String url) {
        try {
            Uri parsed = Uri.parse(url.trim());
            String host = parsed.getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    private String headline() {
        if (section == SEC_INPUT) {
            if (inputLoading && inputCatalog == null) {
                return "正在读取系统的电视输入…";
            }
            if (!TextUtils.isEmpty(inputNote)) {
                return inputNote;
            }
            int count = inputCatalog == null ? 0 : inputCatalog.size();
            if (count == 0) {
                return "系统没报来任何硬件输入 · 换 HDMI 请用遥控器上的「信号源」键，或打开下面这张卡";
            }
            return "系统给了 " + count + " 个输入 · 确认键切过去，切不动就走下面那张卡";
        }
        if (section == SEC_LIVE) {
            String live = SourceConfig.currentLive();
            if (live.isEmpty()) {
                return "直播跟随内容源 · 只有内容源没有直播时才需要单独设一个";
            }
            return "直播单独用 " + sourceTitle(live) + " · 想改回跟随内容源，点「改或清空这个地址」再选清空";
        }
        String current = SourceConfig.current();
        if (current.isEmpty()) {
            return "还没配置内容源 · 桌面退回豆瓣榜单，顶栏没有类目";
        }
        return "正在用 " + sourceTitle(current) + " · 共 " + (rows.size() - 1)
                + " 个源，确认键直接切";
    }

    // ---------------------------------------------------------------- 交互

    private void activate(Row row) {
        switch (row.kind) {
            case KIND_ADD:
                openSourceDialog();
                break;
            case KIND_URL:
                chooseSource(row);
                break;
            case KIND_INPUT:
                if (!tuneInput(row) && !SystemSettings.open(getContext())) {
                    toast("这台设备不让从这里切输入 · 请按遥控器上的「信号源」键");
                }
                break;
            case KIND_SYSTEM:
                if (!SystemSettings.open(getContext())) {
                    toast("这台设备没给可用的系统设置页");
                }
                break;
            default:
                break;
        }
    }

    /**
     * 把某个源设为当前。已经在用的那张卡再按一次不给死路：把弹窗顶出来，
     * 用户想改地址或清空都在里面。
     */
    private void chooseSource(Row row) {
        if (row.current) {
            openSourceDialog();
            return;
        }
        boolean changed = section == SEC_LIVE
                ? SourceConfig.applyLive(row.value)
                : SourceConfig.apply(row.value);
        android.util.Log.i(TAG, "切换" + SECTIONS[section] + "：" + row.value
                + "（" + (changed ? "有变化" : "和原地址相同") + "）");
        rebuild();
        // 刚切过去的源会排到第一张卡（当前源总在历史前面），焦点跟着它走，
        // 不然每次换完源焦点都掉回「输入新地址」那张卡上
        focusGrid(Math.min(1, rows.size() - 1), 2);
    }

    /**
     * 「输入新地址」那张卡。直接复用设置页那个弹窗：输入框、历史、清空/取消/应用，
     * 连交互通路（选历史只填不应用）都是同一套，不重写第二份。
     */
    private void openSourceDialog() {
        Activity activity = activity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final boolean live = section == SEC_LIVE;
        String current = live ? SourceConfig.currentLive() : SourceConfig.current();
        List<String> history = live ? SourceConfig.liveHistory() : SourceConfig.history();
        SourceDialog dialog = new SourceDialog(activity, current, history,
                new SourceDialog.Listener() {
                    @Override
                    public void onApply(String url) {
                        if (live) {
                            SourceConfig.applyLive(url);
                        } else {
                            SourceConfig.apply(url);
                        }
                        rebuild();
                    }

                    @Override
                    public void onClear() {
                        if (live) {
                            // 直播清空 = 恢复跟随内容源，和「没配」不是一回事，说清楚
                            SourceConfig.clearLive();
                            toast("直播源已恢复为跟随内容源");
                        } else {
                            SourceConfig.clear();
                            toast("内容源已清空 · 桌面会退回豆瓣榜单");
                        }
                        rebuild();
                    }
                });
        dialog.show();
    }

    private void toast(String text) {
        Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
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
     * 卡片墙的上下走位自己算（和音乐/应用页同一个理由）：
     * GridView 只挂载视口附近的卡片，下一排还没生成时系统找不到目标，会把焦点甩到看不见的地方。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && grid.getVisibility() == View.VISIBLE) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
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
        if (position - columns >= 0) {
            focusGrid(position - columns, 2);
            return true;
        }
        focusChip();
        return true;
    }

    @Override
    public void onEnter() {
        released = false;
        ensureInputs();
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
        if (!rows.isEmpty()) {
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
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                && navItems.findFocus() != null && !rows.isEmpty()) {
            focusGrid(0, 2);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            setMenuOpen(!menuOpen);
            return true;
        }
        return false;
    }

    @Override
    public void onRelease() {
        // 页实例被宿主缓存复用，这里只断异步回调：切走再切回来不必重问系统输入
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
                        .inflate(R.layout.item_signal_card, parent, false);
            }
            final Row row = rows.get(position);
            TextView name = root.findViewById(R.id.signal_card_name);
            TextView note = root.findViewById(R.id.signal_card_note);
            TextView badge = root.findViewById(R.id.signal_card_badge);
            ImageView img = root.findViewById(R.id.signal_card_img);

            name.setText(row.title);
            note.setText(row.note);
            note.setVisibility(TextUtils.isEmpty(row.note) ? View.GONE : View.VISIBLE);
            badge.setText(row.current ? "使用中" : "");
            badge.setVisibility(row.current ? View.VISIBLE : View.GONE);

            img.setImageDrawable(row.icon);
            PosterCorners.clip(img,
                    getResources().getDimensionPixelSize(R.dimen.poster_radius) - dp(3));

            // GridView 的 item 默认不可聚焦，得自己开
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            FocusScaler.attach(root, FocusScaler.SCALE_FOCUSED);
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    activate(row);
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

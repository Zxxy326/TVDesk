package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

import com.github.tvbox.osc.util.HistoryHelper;
import com.tvdesk.core.feed.BuiltinSources;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页长按弹出的换源弹窗。
 *
 * <h3>为什么长按海报</h3>
 * 换源和配置地址原来只藏在「设置」和「信号源」两个模块里，用户在首页看到内容不对时
 * 第一反应是换一个源，却没有任何入口。长按海报是最短的路：当前正看着什么，就从什么上面换。
 *
 * <h3>列表里有什么</h3>
 * <ol>
 *   <li><b>多线路</b>：配置 JSON 里带 {@code urls} 时 TVBox 拆出来的那份线路表
 *       （{@code API_LINE_LIST}）。名字和地址都是 TVBox 自己存的，这里不另存一份。</li>
 *   <li><b>当前源</b>：当前地址不在多线路里时单独列一行，让「现在用的是什么」始终看得见。</li>
 *   <li><b>用过的源</b>：{@link com.tvdesk.core.feed.SourceConfig#history()}，最多列 8 条。</li>
 * </ol>
 *
 * <p>正在用的那一行按确认键不是「再切一次」，而是打开配置地址——用户点它多半就是想改地址、
 * 换 Cookie 参数。其余行按确认键直接切走。
 *
 * <h3>焦点</h3>
 * 打开时焦点直接落在「使用中」那一行；没有就落第一行；一条源都没有时落「配置地址…」。
 * 每一行都带两行文字（名字 + 地址），长地址中间省略，不会撑破弹窗。
 * 动作行在列表下方，最后一行按下能直接到按钮，按钮按下回到最后一行。
 */
public final class SourceSwitchDialog extends Dialog {

    public interface Listener {
        /** 选中了一条线路或历史源。{@code value} 可能是「名字&lt;TAB&gt;地址」的原文 */
        void onPick(String value);

        /** 重新加载当前源 */
        void onRefresh();

        /** 打开配置地址弹窗 */
        void onConfigure();

        /** 进信号源页 */
        void onOpenSignal();
    }

    /**
     * 历史最多列几条。TVBox 最多存 30 条，全列出来会让遥控器走到手指酸；
     * 8 条覆盖绝大多数「切回上一个源」，多出来的在列表末尾说明，不做无声截断。
     */
    private static final int MAX_HISTORY = 8;

    private final String currentUrl;
    private final List<String> lines;
    private final List<String> history;
    private final Listener listener;

    private LinearLayout listBox;
    private ScrollView listScroll;
    private TextView emptyView;
    /** 最后一行可聚焦的源，按钮的「上」要接回这里 */
    private View lastRow;

    public SourceSwitchDialog(Activity activity, String currentUrl, List<String> lines,
                              List<String> history, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.currentUrl = currentUrl == null ? "" : currentUrl;
        this.lines = lines;
        this.history = history;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_source_switch);
        sizeWindow();

        listBox = findViewById(R.id.source_switch_list);
        listScroll = findViewById(R.id.source_switch_scroll);
        emptyView = findViewById(R.id.source_switch_empty);

        TextView current = findViewById(R.id.source_switch_current);
        current.setText(currentSummary());

        buildRows();
        wireActions();
        wireFocus();

        final View first = initialFocus();
        first.post(new Runnable() {
            @Override
            public void run() {
                if (isShowing()) {
                    first.requestFocus();
                }
            }
        });
    }

    private void sizeWindow() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.dimAmount = 0.6f;
        window.setAttributes(lp);
    }

    // ---------------------------------------------------------------- 组列表

    private void buildRows() {
        listBox.removeAllViews();
        lastRow = null;

        String current = HistoryHelper.getApiLineUrl(currentUrl);
        List<String> seen = new ArrayList<>();

        if (lines != null) {
            boolean sectionAdded = false;
            for (String line : lines) {
                String url = HistoryHelper.getApiLineUrl(line);
                if (TextUtils.isEmpty(url) || seen.contains(url)) {
                    continue;
                }
                if (!sectionAdded) {
                    addSection("多线路");
                    sectionAdded = true;
                }
                seen.add(url);
                addRow(line, url, url.equals(current));
            }
        }

        // 没有多线路、或者当前用的是另行填的地址：单独占一行标出「使用中」
        if (!TextUtils.isEmpty(current) && !seen.contains(current)) {
            addSection("当前源");
            addRow(currentUrl, current, true);
            seen.add(current);
        }

        List<String> shown = new ArrayList<>();
        int hidden = 0;
        if (history != null) {
            for (String item : history) {
                String url = HistoryHelper.getApiLineUrl(item);
                if (TextUtils.isEmpty(url) || seen.contains(url)) {
                    continue;
                }
                if (shown.size() >= MAX_HISTORY) {
                    hidden++;
                    continue;
                }
                shown.add(item);
                seen.add(url);
            }
        }
        if (!shown.isEmpty()) {
            addSection("用过的源");
            for (String item : shown) {
                addRow(item, HistoryHelper.getApiLineUrl(item), false);
            }
        }
        if (hidden > 0) {
            addNote("还有 " + hidden + " 个更早的源没列出，可以进「信号源页」看全部");
        }

        boolean empty = lastRow == null;
        listScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void addSection(String label) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTextColor(getContext().getResources().getColor(R.color.text_tertiary));
        int padH = getContext().getResources().getDimensionPixelSize(R.dimen.settings_row_pad_h) / 2;
        view.setPadding(padH, dp(8), padH, dp(4));
        view.setFocusable(false);
        view.setClickable(false);
        listBox.addView(view);
    }

    private void addRow(String value, String url, boolean current) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_dialog_item);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(true);
        int padH = context.getResources().getDimensionPixelSize(R.dimen.settings_row_pad_h);
        row.setPadding(padH, dp(6), padH, dp(6));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(labelOf(value));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTextColor(AppCompatResources.getColorStateList(context, R.color.dialog_item_text));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setFocusable(false);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (current) {
            TextView badge = new TextView(context);
            badge.setText("使用中");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badge.setTextColor(context.getResources().getColor(R.color.switch_on));
            badge.setSingleLine(true);
            badge.setFocusable(false);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.leftMargin = dp(8);
            head.addView(badge, badgeLp);
        }
        row.addView(head);

        TextView note = new TextView(context);
        note.setText(url);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setTextColor(context.getResources().getColor(R.color.text_tertiary));
        note.setSingleLine(true);
        // 地址末尾的域名/参数才是区分同名前缀的关键，中间省略比尾部省略有用
        note.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        note.setPadding(0, dp(2), 0, 0);
        note.setFocusable(false);
        row.addView(note);

        // 不定死高度：两行文字（名称 + 地址）在大字号下会顶出 52dp 的框
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        row.setMinimumHeight(dp(52));
        row.setLayoutParams(lp);
        row.setTag(current);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current) {
                    // 已经是当前源：用户点它多半是要改地址/加 Cookie 参数
                    configure();
                } else {
                    pick(value);
                }
            }
        });

        row.setId(View.generateViewId());
        listBox.addView(row);
        lastRow = row;
    }

    private void addNote(String text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTextColor(getContext().getResources().getColor(R.color.text_tertiary));
        view.setLineSpacing(dp(3), 1f);
        int padH = getContext().getResources().getDimensionPixelSize(R.dimen.settings_row_pad_h) / 2;
        view.setPadding(padH, dp(6), padH, dp(4));
        view.setFocusable(false);
        view.setClickable(false);
        listBox.addView(view);
    }

    /**
     * 条目显示名。优先级：内置源名称 → 多线路里的名字 → 域名。
     * 整串 URL 当标题在电视上读不完，六列卡片墙时代留下的教训。
     */
    private String labelOf(String value) {
        String url = HistoryHelper.getApiLineUrl(value);
        String builtin = BuiltinSources.displayName(url);
        if (!TextUtils.isEmpty(builtin)) {
            return builtin;
        }
        String name = HistoryHelper.getApiLineName(value);
        if (!TextUtils.isEmpty(name) && !name.equals(value)) {
            return name;
        }
        String host = hostOf(url);
        return TextUtils.isEmpty(host) ? (TextUtils.isEmpty(url) ? value : url) : host;
    }

    private String currentSummary() {
        String url = HistoryHelper.getApiLineUrl(currentUrl);
        if (TextUtils.isEmpty(url)) {
            return "还没配置内容源 · 选一个源，或进去填一个新地址";
        }
        String name = labelOf(currentUrl);
        String host = hostOf(url);
        if (TextUtils.isEmpty(host) || host.equals(name)) {
            return "当前：" + name;
        }
        return "当前：" + name + " · " + host;
    }

    private static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        try {
            String host = android.net.Uri.parse(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 动作

    private void wireActions() {
        findViewById(R.id.source_switch_btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (listener != null) {
                    listener.onRefresh();
                }
            }
        });
        findViewById(R.id.source_switch_btn_config).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                configure();
            }
        });
        findViewById(R.id.source_switch_btn_signal).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (listener != null) {
                    listener.onOpenSignal();
                }
            }
        });
        findViewById(R.id.source_switch_btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    private void pick(String value) {
        dismiss();
        if (listener != null) {
            listener.onPick(value);
        }
    }

    private void configure() {
        dismiss();
        if (listener != null) {
            listener.onConfigure();
        }
    }

    // ---------------------------------------------------------------- 焦点

    private void wireFocus() {
        if (lastRow == null) {
            return;
        }
        int lastId = lastRow.getId();
        lastRow.setNextFocusDownId(R.id.source_switch_btn_refresh);
        findViewById(R.id.source_switch_btn_refresh).setNextFocusUpId(lastId);
        findViewById(R.id.source_switch_btn_config).setNextFocusUpId(lastId);
        findViewById(R.id.source_switch_btn_signal).setNextFocusUpId(lastId);
        findViewById(R.id.source_switch_btn_cancel).setNextFocusUpId(lastId);
    }

    private View initialFocus() {
        View first = null;
        for (int i = 0; i < listBox.getChildCount(); i++) {
            View child = listBox.getChildAt(i);
            if (!child.isFocusable()) {
                continue;
            }
            if (first == null) {
                first = child;
            }
            if (Boolean.TRUE.equals(child.getTag())) {
                return child;
            }
        }
        return first != null ? first : findViewById(R.id.source_switch_btn_config);
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}

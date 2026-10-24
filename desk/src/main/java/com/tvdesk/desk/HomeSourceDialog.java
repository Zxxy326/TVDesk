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
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.TextView;

import com.github.tvbox.osc.bean.SourceBean;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页长按弹出的「请选择首页数据源」。
 *
 * <h3>换的是什么</h3>
 * TVBox 的配置里，一条配置地址（本 App 里叫「线路」）会解析出一张 sites 表，
 * 每一项就是一个<b>数据源</b>。这个弹窗只在这一层里换：{@code HawkConfig.HOME_API}
 * 记下选中站点的 key，配置和爬虫 jar 都不重拉（见
 * {@link com.tvdesk.core.feed.SourceFeed#invalidateResolved()}）。
 *
 * <p>以前长按弹的是 {@link SourceSwitchDialog}（换配置线路/历史源），名字像、
 * 层级却不是一回事：用户看到的是「豆豆/玩偶哥哥」这些站点，心里想换的也是它们。
 *
 * <p>两列布局和电视端 TVBox 的参考稿一致；当前项用 ✓ 和强调色标出来。
 * 底部「换线路 / 配置地址」把原来的弹窗留着当二级入口——换配置条线路仍然要能走。
 */
public final class HomeSourceDialog extends Dialog {

    public interface Listener {
        /** 选了另一个数据源。{@code key} 一定不同于打开时的首页源 */
        void onPick(SourceBean bean);

        /** 去换配置线路 / 改配置地址 */
        void onOpenLines();
    }

    /** 一屏最多露几行；再多就让焦点把列表滚起来，别把底部按钮顶出面板 */
    private static final int MAX_ROWS = 5;
    private static final int ITEM_HEIGHT_DP = 46;
    private static final int ROW_GAP_DP = 10;

    private final List<SourceBean> sites;
    private final String currentKey;
    private final Listener listener;

    private GridView grid;
    private View emptyView;
    private int currentIndex = -1;

    public HomeSourceDialog(Activity activity, List<SourceBean> sites, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.sites = sites == null ? new ArrayList<SourceBean>() : sites;
        this.listener = listener;
        this.currentKey = currentKey();
        for (int i = 0; i < this.sites.size(); i++) {
            SourceBean bean = this.sites.get(i);
            if (bean != null && currentKey.equals(bean.getKey())) {
                currentIndex = i;
                break;
            }
        }
    }

    private static String currentKey() {
        try {
            SourceBean home = com.github.tvbox.osc.api.ApiConfig.get().getHomeSourceBean();
            return home == null ? "" : home.getKey();
        } catch (Throwable th) {
            return "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_home_source);
        sizeWindow();

        grid = findViewById(R.id.home_source_grid);
        emptyView = findViewById(R.id.home_source_empty);
        TextView note = findViewById(R.id.home_source_note);
        note.setText(summary());

        buildCells();
        wireActions();
        focusInitial();
    }

    /**
     * 焦点落在「使用中」那一格；没有就落第一格；一条源都没有时落「换线路」。
     *
     * <p>必须等 GridView 排完版：格子是适配器现做的，onCreate 这一刻还没生成 view。
     * 先 setSelection 把目标行滚进视野，再在下一帧按位置取孩子节点。
     */
    private void focusInitial() {
        final View fallback = findViewById(R.id.home_source_btn_lines);
        if (sites.isEmpty()) {
            fallback.post(new Runnable() {
                @Override
                public void run() {
                    if (isShowing()) {
                        fallback.requestFocus();
                    }
                }
            });
            return;
        }
        final int target = currentIndex >= 0 ? currentIndex : 0;
        grid.post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing()) {
                    return;
                }
                grid.setSelection(target);
                grid.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isShowing()) {
                            return;
                        }
                        View child = grid.getChildAt(target - grid.getFirstVisiblePosition());
                        (child != null ? child : fallback).requestFocus();
                    }
                });
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

    private String summary() {
        if (sites.isEmpty()) {
            return "当前配置里没有可切换的数据源";
        }
        String name = "";
        for (SourceBean bean : sites) {
            if (bean != null && currentKey.equals(bean.getKey())) {
                name = bean.getName();
                break;
            }
        }
        String head = TextUtils.isEmpty(name) ? "还没选定首页数据源" : "当前：" + name;
        return head + " · 这条配置共 " + sites.size() + " 个数据源";
    }

    private void buildCells() {
        emptyView.setVisibility(sites.isEmpty() ? View.VISIBLE : View.GONE);
        grid.setVisibility(sites.isEmpty() ? View.GONE : View.VISIBLE);
        if (sites.isEmpty()) {
            return;
        }

        grid.setAdapter(new CellAdapter());

        int rows = Math.min(MAX_ROWS, (sites.size() + 1) / 2);
        int height = rows * dp(ITEM_HEIGHT_DP) + (rows - 1) * dp(ROW_GAP_DP)
                + grid.getPaddingTop() + grid.getPaddingBottom();
        ViewGroup.LayoutParams lp = grid.getLayoutParams();
        lp.height = height;
        grid.setLayoutParams(lp);
    }

    private void wireActions() {
        findViewById(R.id.home_source_btn_lines).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (listener != null) {
                    listener.onOpenLines();
                }
            }
        });
        findViewById(R.id.home_source_btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    private final class CellAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return sites.size();
        }

        @Override
        public Object getItem(int position) {
            return sites.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SourceBean bean = sites.get(position);
            final String key = bean.getKey();
            final boolean current = currentKey.equals(key);

            TextView cell = convertView instanceof TextView ? (TextView) convertView
                    : new TextView(getContext());
            cell.setText(current ? "✓ " + bean.getName() : bean.getName());
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            cell.setTextColor(getContext().getResources().getColor(
                    current ? R.color.switch_on : R.color.text_primary));
            cell.setGravity(Gravity.CENTER);
            cell.setSingleLine(true);
            cell.setEllipsize(TextUtils.TruncateAt.END);
            cell.setPadding(dp(14), 0, dp(14), 0);
            cell.setMinHeight(dp(ITEM_HEIGHT_DP));
            cell.setBackgroundResource(R.drawable.bg_module_item);
            cell.setSelected(current);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setFocusableInTouchMode(true);
            // 缩放顺带关掉系统在 API 26+ 叠的方形焦点高亮（圆角胶囊外再套直角框很像被裁）
            com.tvdesk.desk.motion.FocusScaler.attach(cell, 1.03f);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (current) {
                        dismiss();
                        return;
                    }
                    if (listener != null) {
                        listener.onPick(bean);
                    }
                    dismiss();
                }
            });

            return cell;
        }
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}

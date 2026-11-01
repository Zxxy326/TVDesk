package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

import com.github.tvbox.osc.bean.MovieSort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 类目筛选弹窗。
 *
 * <h3>为什么要有</h3>
 * 源站给的类目里，有些带筛选（年份、地区、类型…）。用户在顶栏选中一个类目后按确定，
 * 期望看到的是「这个类目 + 我要的筛选条件」，而不是直接铺一屏。
 *
 * <p>TVBox 原版的做法是：类目项旁边挂一个筛选图标，点它才弹筛选
 * （{@code HomeActivity} 里的 {@code showFilterIcon} / {@code GridFragment.showFilter}）。
 * 电视上多一个图标就多一次按键，而且那个图标和类目名挤在一排里很难认。
 * 这里改成<b>按确定键直接弹筛选</b>：类目已经选中了，用户按确定就是想「进入这个类目」，
 * 顺手把筛选摆出来最省事。
 *
 * <h3>交互</h3>
 * <ul>
 *   <li>每行一个筛选维度，行里横排所有可选值；</li>
 *   <li>再点一次已选中的值 = 取消这个维度的筛选（和 TVBox 一致）；</li>
 *   <li>「确定」应用并刷新列表，「重置」清掉全部筛选，「取消」什么都不改。</li>
 * </ul>
 */
public final class SortFilterDialog extends Dialog {

    public interface Listener {
        /** 筛选变了（点了确定或重置）：调用方该重新拉列表 */
        void onFilterChanged(MovieSort.SortData sort);

        /** 点了取消：什么都没改 */
        void onCancel();
    }

    private final MovieSort.SortData sort;
    private final Listener listener;

    public SortFilterDialog(Activity activity, MovieSort.SortData sort, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.sort = sort;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_sort_filter);
        // 系统在 API 26+ 会给焦点控件叠一层方形高亮，圆角按钮看着像被裁了一块
        com.tvdesk.desk.motion.FocusScaler.disableSystemHighlightTree(
                getWindow().getDecorView());
        sizeWindow();

        TextView title = findViewById(R.id.filter_title);
        if (title != null) {
            title.setText(sort == null ? "筛选" : sort.name + " · 筛选");
        }

        LinearLayout box = findViewById(R.id.filter_rows);
        if (box != null && sort != null) {
            for (MovieSort.SortFilter filter : sort.filters) {
                if (filter == null || filter.values == null || filter.values.isEmpty()) {
                    continue;
                }
                box.addView(buildRow(filter));
            }
        }

        View apply = findViewById(R.id.filter_apply);
        if (apply != null) {
            apply.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (listener != null) {
                        listener.onFilterChanged(sort);
                    }
                }
            });
        }
        View reset = findViewById(R.id.filter_reset);
        if (reset != null) {
            reset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (sort != null && sort.filterSelect != null) {
                        sort.filterSelect.clear();
                    }
                    dismiss();
                    if (listener != null) {
                        listener.onFilterChanged(sort);
                    }
                }
            });
        }
        View cancel = findViewById(R.id.filter_cancel);
        if (cancel != null) {
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    if (listener != null) {
                        listener.onCancel();
                    }
                }
            });
        }
    }

    /** 一行筛选：左边维度名，右边一排可选值 */
    private View buildRow(final MovieSort.SortFilter filter) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        TextView name = new TextView(getContext());
        name.setText(filter.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(androidx.appcompat.content.res.AppCompatResources
                .getColorStateList(getContext(), R.color.text_secondary));
        name.setMinWidth(dp(64));
        row.addView(name);

        // 值排在一行里，横向能滚：维度多了（年份 + 地区 + 类型…）一行放不下
        android.widget.HorizontalScrollView scroll =
                new android.widget.HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout values = new LinearLayout(getContext());
        values.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(values);

        List<String> labels = new ArrayList<>(filter.values.keySet());
        for (final String label : labels) {
            final String value = filter.values.get(label);
            TextView chip = new TextView(getContext());
            chip.setText(label);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setBackgroundResource(R.drawable.bg_episode_chip);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            chip.setSelected(value != null && value.equals(sort.filterSelect.get(filter.key)));
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setFocusableInTouchMode(true);
            com.tvdesk.desk.motion.FocusScaler.attach(chip, 1.03f);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String picked = sort.filterSelect.get(filter.key);
                    if (picked != null && picked.equals(value)) {
                        // 再点一次已选中的值 = 取消这个维度（和 TVBox 的行为一样）
                        sort.filterSelect.remove(filter.key);
                    } else {
                        sort.filterSelect.put(filter.key, value);
                    }
                    refreshRowState(filter, values);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            lp.rightMargin = dp(8);
            values.addView(chip, lp);
        }
        row.addView(scroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** 改完一个维度的选择后，把这一行所有值的选中态刷一遍 */
    private void refreshRowState(MovieSort.SortFilter filter, LinearLayout values) {
        String picked = sort.filterSelect.get(filter.key);
        for (int i = 0; i < values.getChildCount(); i++) {
            View child = values.getChildAt(i);
            if (!(child instanceof TextView)) {
                continue;
            }
            TextView chip = (TextView) child;
            String value = filter.values.get(chip.getText().toString());
            chip.setSelected(value != null && value.equals(picked));
        }
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }

    /** 弹窗尺寸：贴底、占满宽，像一层抽屉 */
    private void sizeWindow() {
        if (getWindow() == null) {
            return;
        }
        android.view.Window window = getWindow();
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.BOTTOM);
        android.view.WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.6f;
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(params);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 打开时把焦点给第一颗可选值：遥控器上不用再按一次「下」才开始选
            View root = findViewById(R.id.filter_rows);
            if (root instanceof LinearLayout) {
                LinearLayout rows = (LinearLayout) root;
                for (int i = 0; i < rows.getChildCount(); i++) {
                    View row = rows.getChildAt(i);
                    if (!(row instanceof LinearLayout)) {
                        continue;
                    }
                    LinearLayout line = (LinearLayout) row;
                    for (int j = 0; j < line.getChildCount(); j++) {
                        View cell = line.getChildAt(j);
                        if (cell instanceof android.widget.HorizontalScrollView) {
                            android.widget.HorizontalScrollView scroll =
                                    (android.widget.HorizontalScrollView) cell;
                            LinearLayout values = (LinearLayout) scroll.getChildAt(0);
                            if (values != null && values.getChildCount() > 0) {
                                values.getChildAt(0).requestFocus();
                                return;
                            }
                        }
                    }
                }
            }
        }
    }
}

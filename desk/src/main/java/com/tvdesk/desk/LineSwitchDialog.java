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

import com.github.tvbox.osc.bean.VodInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放中长按 OK 弹出的「选择线路」。
 *
 * <p>首页长按换的是<b>内容源</b>，这里换的是同一部片子在源里的<b>播放线路</b>：
 * {@link VodInfo#seriesFlags} 里那些「线路1 / 线路2 / 夸克 / 阿里」。
 * 用户的典型场景是正片卡住或者某条线没有字幕，换一条继续看——
 * 要是还得退出播放、回详情页点 chip，就为了换条线丢掉了当前进度。
 *
 * <p>只改 {@link VodInfo#playFlag}，不写任何配置。集数沿用当前集（新线路集数不够时
 * 落到最后一集），重播由宿主负责。
 */
public final class LineSwitchDialog extends Dialog {

    public interface Listener {
        /** 用户选了另一条线路。{@code flag} 一定不同于打开弹窗时的 playFlag */
        void onPick(String flag);
    }

    private final VodInfo info;
    private final Listener listener;
    private final List<String> flags;

    private LinearLayout listBox;
    private View lastRow;

    public LineSwitchDialog(Activity activity, VodInfo info, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.info = info;
        this.listener = listener;
        this.flags = flagsOf(info);
    }

    /**
     * 这部片子有几条线路。少于两条就没有换的意义，调用方据此决定弹不弹窗。
     * 优先用 seriesFlags；有些源只填了 seriesMap，用它的 key 兜底。
     */
    public static List<String> flagsOf(VodInfo info) {
        List<String> result = new ArrayList<>();
        if (info == null) {
            return result;
        }
        if (info.seriesFlags != null) {
            for (VodInfo.VodSeriesFlag flag : info.seriesFlags) {
                if (flag != null && !TextUtils.isEmpty(flag.name) && !result.contains(flag.name)) {
                    result.add(flag.name);
                }
            }
        }
        if (result.isEmpty() && info.seriesMap != null) {
            for (String key : info.seriesMap.keySet()) {
                if (!TextUtils.isEmpty(key) && !result.contains(key)) {
                    result.add(key);
                }
            }
        }
        return result;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_line_switch);
        sizeWindow();

        listBox = findViewById(R.id.line_switch_list);
        TextView current = findViewById(R.id.line_switch_current);
        current.setText("正在播放：" + currentName() + " · 共 " + flags.size() + " 条线路");

        buildRows();
        wireFocus();
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

    private String currentName() {
        if (!TextUtils.isEmpty(info.playFlag)) {
            return info.playFlag;
        }
        return flags.isEmpty() ? "未知线路" : flags.get(0);
    }

    private void buildRows() {
        listBox.removeAllViews();
        lastRow = null;
        final String active = currentName();

        for (final String flag : flags) {
            final boolean current = flag.equals(active);
            Context context = getContext();
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_dialog_item);
            row.setFocusable(true);
            row.setClickable(true);
            row.setFocusableInTouchMode(true);
            int padH = context.getResources().getDimensionPixelSize(R.dimen.settings_row_pad_h);
            row.setPadding(padH, dp(6), padH, dp(6));
            row.setMinimumHeight(dp(52));

            LinearLayout head = new LinearLayout(context);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setText(flag);
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
            note.setText(countOf(flag) + (current ? " · 继续播放" : " · 切换后接着当前集播放"));
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            note.setTextColor(context.getResources().getColor(R.color.text_tertiary));
            note.setSingleLine(true);
            note.setPadding(0, dp(2), 0, 0);
            note.setFocusable(false);
            row.addView(note);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(2);
            row.setLayoutParams(lp);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (current) {
                        dismiss();
                        return;
                    }
                    dismiss();
                    if (listener != null) {
                        listener.onPick(flag);
                    }
                }
            });

            row.setId(View.generateViewId());
            listBox.addView(row);
            lastRow = row;
            if (current) {
                final View target = row;
                row.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isShowing()) {
                            target.requestFocus();
                        }
                    }
                });
            }
        }
        if (lastRow == null) {
            // 一条线路都没取到：列表空着会把面板压成一条标题，这里给一句解释
            TextView empty = new TextView(getContext());
            empty.setText("这个源没有给出可选的线路");
            empty.setTextColor(getContext().getResources().getColor(R.color.text_tertiary));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setMinimumHeight(dp(120));
            listBox.addView(empty);
        }
    }

    private String countOf(String flag) {
        if (info.seriesMap == null) {
            return "线路";
        }
        List<VodInfo.VodSeries> list = info.seriesMap.get(flag);
        return list == null || list.isEmpty() ? "线路" : list.size() + " 集";
    }

    private void wireFocus() {
        View cancel = findViewById(R.id.line_switch_btn_cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        if (lastRow != null) {
            lastRow.setNextFocusDownId(R.id.line_switch_btn_cancel);
            cancel.setNextFocusUpId(lastRow.getId());
        }
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}

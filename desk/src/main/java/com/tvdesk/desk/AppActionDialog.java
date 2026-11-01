package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

/**
 * 应用卡片的操作弹窗（长按确认键弹出）。
 *
 * <h3>为什么卸载放在这里而不是卡片上</h3>
 * 卡片墙上的焦点每按一次方向键就换一张卡，常驻一个「卸载」按钮等于把不可撤销的操作
 * 放在了最容易误触的位置。长按才出弹窗、弹窗里再点一次、最后还要过系统自己的确认页，
 * 三道门槛是刻意的。
 *
 * <h3>出路</h3>
 * 每条动作都是「关闭弹窗 + 把意图交给 {@link AppPage}」，卸载和应用信息最终都由系统页接手，
 * 本页不碰包管理。「取消」和 BACK 都能退出，不会出现打开了退不出去的情况。
 *
 * <p>动作行按这个包能做什么现生成：打不开的不给「打开」，预装应用不给「卸载」
 * （系统那一页只会回一句「无法卸载」，早点不说比让用户白跑一趟好）。
 */
public final class AppActionDialog extends Dialog {

    public interface Listener {
        /** 点了「打开」 */
        void onOpen(AppPage.Entry entry);

        /** 点了「应用信息」 */
        void onInfo(AppPage.Entry entry);

        /** 点了「卸载」 */
        void onUninstall(AppPage.Entry entry);

        /** 点了「移到最前」：把这个应用提到应用墙的最上面 */
        void onMoveToTop(AppPage.Entry entry);

        /** 点了「移动位置」：进入移动模式，方向键自己摆位置（照搬 Aerial 的 Move） */
        void onMove(AppPage.Entry entry);
    }

    private final AppPage.Entry entry;
    private final Listener listener;

    public AppActionDialog(Activity activity, AppPage.Entry entry, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.entry = entry;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_app_action);
        // 系统在 API 26+ 会给焦点控件叠一层半透明矩形高亮，它按外接矩形画，
        // 圆角行/胶囊按钮的四个角就被「切」出直角边，看着像被裁了一块。
        com.tvdesk.desk.motion.FocusScaler.disableSystemHighlightTree(
                getWindow().getDecorView());
        sizeWindow();

        ((TextView) findViewById(R.id.dialog_app_title)).setText(entry.name);
        ((TextView) findViewById(R.id.dialog_app_pkg)).setText(entry.pkg);

        LinearLayout box = findViewById(R.id.dialog_app_actions);
        if (entry.launchable) {
            box.addView(addAction(box, "打开", false, new Runnable() {
                @Override
                public void run() {
                    listener.onOpen(entry);
                }
            }));
        }
        box.addView(addAction(box, "应用信息", false, new Runnable() {
            @Override
            public void run() {
                listener.onInfo(entry);
            }
        }));
        // 「移动位置」：Aerial 的 Move —— 进移动模式后方向键把卡片挪到想放的格子
        box.addView(addAction(box, "移动位置", false, new Runnable() {
            @Override
            public void run() {
                listener.onMove(entry);
            }
        }));
        // 「移到最前」：电视上装了几十个应用，常用的那几个每次都要翻半天。
        // 把它提到最上面，一次排好以后一直有效（做法同 Aerial TV 的 LayoutStore）。
        box.addView(addAction(box, "移到最前", false, new Runnable() {
            @Override
            public void run() {
                listener.onMoveToTop(entry);
            }
        }));
        if (!entry.system) {
            box.addView(addAction(box, "卸载", true, new Runnable() {
                @Override
                public void run() {
                    listener.onUninstall(entry);
                }
            }));
        }

        findViewById(R.id.dialog_app_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        // onCreate 阶段 View 还没 layout，直接 requestFocus 会失败
        final View first = box.getChildAt(0);
        first.post(new Runnable() {
            @Override
            public void run() {
                if (isShowing()) {
                    first.requestFocus();
                }
            }
        });
    }

    /** 弹窗由内容决定大小，只把背后压暗：卡片墙的文字还在后面，不压暗两层会糊在一起 */
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

    private View addAction(LinearLayout parent, String label, final boolean danger,
            final Runnable action) {
        TextView row = new TextView(getContext());
        row.setText(label);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        // 卸载那一行用项目的危险色（和「清空配置」同一个），其余行保留获焦提亮
        row.setTextColor(danger
                ? ColorStateList.valueOf(getContext().getResources()
                        .getColor(R.color.settings_danger_stroke))
                : AppCompatResources.getColorStateList(getContext(), R.color.dialog_item_text));
        row.setSingleLine(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_dialog_item);
        int padH = getContext().getResources().getDimensionPixelSize(R.dimen.settings_row_pad_h);
        row.setPadding(padH, 0, padH, 0);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                getContext().getResources().getDimensionPixelSize(R.dimen.settings_dialog_row_height));
        lp.bottomMargin = dp(2);
        row.setLayoutParams(lp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                action.run();
            }
        });
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}

package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.content.res.AppCompatResources;

/**
 * 回桌面前的续播确认。
 *
 * <h3>为什么非要问一句</h3>
 * 音乐/抖音这类页在桌面背后是没有窗的，用户回了桌面、屏幕停在桌面，
 * 声音却还在放——找不到声音从哪来，只能按 HOME 杀掉整个应用。反过来，
 * 一刀切地「离开就停」又会让「边听歌边挑片」这件事做不了。所以把选择交给用户，
 * 每次问、不记住：这一次想停不代表下一次也想停。
 *
 * <h3>三条出路</h3>
 * 「继续播放」和「停止播放」都离开这一页，「取消」和 BACK 留在原地。
 * 无论走哪条，焦点都要回到能继续操作的地方，不能出现弹窗关了却没人拿焦点的情况。
 */
public final class KeepPlayingDialog extends Dialog {

    public interface Listener {
        /** 保留播放，然后离开 */
        void onKeepPlaying();

        /** 停掉声音，然后离开 */
        void onStopPlaying();

        /** 什么都没选（取消 / BACK）：留在当前页 */
        void onCancel();
    }

    private final Listener listener;
    /** 已经选过动作，dismiss 时不要再补一次 onCancel */
    private boolean chosen;

    public KeepPlayingDialog(Activity activity, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.listener = listener;
        setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (!chosen && KeepPlayingDialog.this.listener != null) {
                    KeepPlayingDialog.this.listener.onCancel();
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_keep_playing);
        // 系统在 API 26+ 会给焦点控件叠一层半透明矩形高亮，它按外接矩形画，
        // 圆角行/胶囊按钮的四个角就被「切」出直角边，看着像被裁了一块。
        com.tvdesk.desk.motion.FocusScaler.disableSystemHighlightTree(
                getWindow().getDecorView());
        sizeWindow();

        LinearLayout box = findViewById(R.id.keep_playing_actions);
        box.addView(addAction(box, "继续播放", false, true));
        box.addView(addAction(box, "停止播放", true, false));

        findViewById(R.id.keep_playing_cancel).setOnClickListener(new View.OnClickListener() {
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

    /**
     * 外部已经替用户决定了去向（例如连按返回）。
     *
     * <p>直接 {@link #dismiss()} 会走 dismiss 监听里的「没选过就当取消」那条，
     * 把调用方刚定的去向又改回「留在原地」。这里先把 {@code chosen} 立起来再关，
     * 保证 onCancel 不会再补一枪。
     */
    public void dismissAsChosen() {
        chosen = true;
        dismiss();
    }

    /**
     * 弹窗开着时又来一次返回：算「停止播放」，离开并且把声音停掉。
     *
     * <p>以前的走向是 {@code cancel()} → onCancel → 留在原页：用户按第一下弹出询问、
     * 按第二下把询问关掉、人还在原页里，第三下又弹一张。遥控器上连按返回是很自然的动作，
     * 走成「原地转圈」就成了「返回键坏了」。
     *
     * <p>连按的语义是「我要走、别再响了」。这里必须落到<b>停止</b>而不是「继续播放」：
     * 选继续等于把正在放的声音留到桌面背后，用户看到的就是「都退出来了怎么还在放」。
     * 想留着当背景音的人，弹窗上有明明白白的「继续播放」可以点。
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            leaveStoppingPlayback();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        leaveStoppingPlayback();
    }

    private void leaveStoppingPlayback() {
        if (chosen) {
            return;
        }
        // 先立 chosen 再回调：走 dismiss 时 onDismiss 里的「没选过就当取消」才不会把
        // 调用方刚定的去向改回去
        chosen = true;
        dismiss();
        if (listener != null) {
            listener.onStopPlaying();
        }
    }

    /** 弹窗由内容决定大小，只把背后压暗 */
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

    private View addAction(LinearLayout parent, String label, final boolean danger, final boolean keep) {
        TextView row = new TextView(getContext());
        row.setText(label);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        // 「停止播放」用项目的危险色，和卸载、清空配置同一个口径
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
                chosen = true;
                dismiss();
                if (listener == null) {
                    return;
                }
                if (keep) {
                    listener.onKeepPlaying();
                } else {
                    listener.onStopPlaying();
                }
            }
        });
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}

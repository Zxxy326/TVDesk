package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.catvod.music.NeteaseClient;

/**
 * 网易云手机号登录（EAPI）—— 对齐像素播放器 v1.8.0。
 *
 * <p>比 WebView 登录快：不用等网页、不用过页面脚本，手机号 + 密码直接换 MUSIC_U。
 * 被风控（-462）时在状态行明确写出来，并给「改用扫码」的出口。
 *
 * <p>电视上没有输入法时只能走扫码——所以这里永远保留 fallback，不能把它做成唯一入口。
 */
public final class NeteasePhoneLoginDialog extends Dialog {

    public interface Listener {
        void onLoggedIn(String nickname);

        /** 改用扫码 / 粘 Cookie 那条老路 */
        void onFallback();
    }

    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText phoneInput;
    private EditText passInput;
    private TextView statusView;
    private TextView loginBtn;
    private boolean busy;

    public NeteasePhoneLoginDialog(Activity activity, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog_panel);
        int pad = dp(28);
        root.setPadding(pad, dp(24), pad, dp(20));

        TextView title = new TextView(getContext());
        title.setText("网易云登录");
        title.setTextColor(getContext().getResources().getColor(R.color.text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView hint = new TextView(getContext());
        hint.setText("手机号 + 密码，不需要验证码");
        hint.setTextColor(getContext().getResources().getColor(R.color.text_tertiary));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        hintLp.bottomMargin = dp(16);
        root.addView(hint, hintLp);

        phoneInput = buildInput("手机号", InputType.TYPE_CLASS_PHONE);
        root.addView(phoneInput, inputLp());

        passInput = buildInput("密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passLp = inputLp();
        passLp.bottomMargin = dp(14);
        root.addView(passInput, passLp);

        statusView = new TextView(getContext());
        statusView.setTextColor(getContext().getResources().getColor(R.color.text_secondary));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setVisibility(View.GONE);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.bottomMargin = dp(12);
        root.addView(statusView, stLp);

        // 按钮行：扫码兜底 + 登录
        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        TextView fallback = buildButton("改用扫码 / 粘 Cookie", false);
        fallback.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (listener != null) {
                    listener.onFallback();
                }
            }
        });
        actions.addView(fallback, new LinearLayout.LayoutParams(dp(220), dp(46)));

        View spacer = new View(getContext());
        actions.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        loginBtn = buildButton("登录", true);
        loginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });
        actions.addView(loginBtn, new LinearLayout.LayoutParams(dp(140), dp(46)));

        root.addView(actions);

        setContentView(root);
        com.tvdesk.desk.motion.FocusScaler.disableSystemHighlightTree(getWindow().getDecorView());
        sizeWindow();
        phoneInput.requestFocus();
    }

    private EditText buildInput(String hint, int inputType) {
        EditText e = new EditText(getContext());
        e.setHint(hint);
        e.setHintTextColor(getContext().getResources().getColor(R.color.text_tertiary));
        e.setTextColor(getContext().getResources().getColor(R.color.text_primary));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackgroundResource(R.drawable.bg_module_item);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setFocusable(true);
        e.setFocusableInTouchMode(true);
        return e;
    }

    private LinearLayout.LayoutParams inputLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lp.bottomMargin = dp(10);
        return lp;
    }

    private TextView buildButton(String label, boolean primary) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setFocusable(true);
        t.setClickable(true);
        t.setFocusableInTouchMode(true);
        if (primary) {
            t.setTextColor(0xFF101014);
            t.setBackgroundResource(R.drawable.bg_module_item);
        } else {
            t.setTextColor(getContext().getResources().getColor(R.color.text_secondary));
            t.setBackgroundResource(R.drawable.bg_module_item);
        }
        return t;
    }

    private void doLogin() {
        if (busy) {
            return;
        }
        final String phone = phoneInput.getText() == null ? "" : phoneInput.getText().toString().trim();
        final String pass = passInput.getText() == null ? "" : passInput.getText().toString();
        if (TextUtils.isEmpty(phone) || TextUtils.isEmpty(pass)) {
            setStatus("手机号和密码都要填");
            return;
        }
        busy = true;
        loginBtn.setEnabled(false);
        setStatus("正在登录…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                String nickname = "";
                try {
                    nickname = NeteaseClient.get(getContext()).loginByPhoneEapi(phone, pass);
                } catch (Throwable th) {
                    error = String.valueOf(th.getMessage());
                }
                final String err = error;
                final String nick = nickname;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        if (loginBtn != null) {
                            loginBtn.setEnabled(true);
                        }
                        if (err != null) {
                            setStatus("登录失败：" + err);
                            return;
                        }
                        dismiss();
                        if (listener != null) {
                            listener.onLoggedIn(nick);
                        }
                    }
                });
            }
        }).start();
    }

    private void setStatus(String text) {
        if (statusView == null) {
            return;
        }
        statusView.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        statusView.setText(text);
    }

    private int dp(int v) {
        return (int) (v * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    private void sizeWindow() {
        Window w = getWindow();
        if (w == null) {
            return;
        }
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = dp(560);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        w.setAttributes(lp);
    }
}
